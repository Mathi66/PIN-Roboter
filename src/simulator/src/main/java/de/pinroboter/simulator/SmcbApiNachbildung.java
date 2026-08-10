package de.pinroboter.simulator;

import de.pinroboter.gemeinsam.SmcbProtokoll;
import de.pinroboter.gemeinsam.SmcbProtokoll.Art;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Nachbildung der <b>Remote SMCB API</b> eines Kartenterminals (ADR-0011).
 *
 * <h2>Warum das hier steht</h2>
 *
 * Bis zum 10.08.2026 bildete der Simulator nach, was wir <i>angenommen</i>
 * hatten: Der Agent schiebt die PIN hin. Die Herstellerspezifikation verlangt
 * das Gegenteil — <b>das Terminal fragt</b>. Damit war der Simulator die
 * Bestätigung eines Irrtums, und alle Nachweise darauf wertlos für die Frage,
 * ob es an echter Hardware funktioniert.
 *
 * <p>Diese Nachbildung folgt der ST-1506 Remote Management Specification 2.8,
 * Kapitel 6 bis 8. Sie ersetzt <b>keine Messung am Gerät</b> — sie stellt nur
 * sicher, dass wir gegen das richtige <i>Muster</i> bauen.
 *
 * <h2>Warum ein eigener WebSocket statt einer Bibliothek</h2>
 *
 * Der Simulator läuft auf {@code com.sun.net.httpserver}, der keine WebSockets
 * kann. Eine Bibliothek dafür wäre eine weitere Abhängigkeit allein für den
 * Prüfstand. Der benötigte Ausschnitt von RFC 6455 ist überschaubar: Handshake,
 * Textrahmen, Schließen. Binärrahmen, Erweiterungen und Fragmentierung über
 * 64 KB hinaus braucht dieses Protokoll nicht.
 */
public class SmcbApiNachbildung {

    /** Ausgestellte Schlüssel je Terminal — gültig 30 s, genau einmal nutzbar. */
    private static final Map<String, Schluessel> SCHLUESSEL = new ConcurrentHashMap<>();

    /** Offene Verbindungen je Terminal. Es darf höchstens eine geben. */
    private static final Map<String, Sitzung> SITZUNGEN = new ConcurrentHashMap<>();

    /**
     * Wie oft eine PIN <b>über dieses Protokoll</b> geliefert wurde, je Terminal.
     *
     * <p>Nur für Nachweise. Ohne diesen Zähler ließe sich bloß zeigen, dass die
     * Verifikation gelingt — nicht, auf welchem Weg. Nach dem Fund vom
     * 10.08.2026 ist genau das der Unterschied, auf den es ankommt: Der alte
     * Nachweis war grün, obwohl das Übergabemodell falsch war.
     */
    private static final Map<String, java.util.concurrent.atomic.AtomicInteger> UEBERGABEN =
            new ConcurrentHashMap<>();

    /** Anzahl der über {@code cobra-smcb} gelieferten PINs (nur Nachweise). */
    public static int uebergaben(String terminalId) {
        var z = UEBERGABEN.get(terminalId);
        return z == null ? 0 : z.get();
    }

    private record Schluessel(String wert, long gueltigBis) { }

    /** Eine bestehende Verbindung samt Zustand. */
    public static class Sitzung {
        final String terminalId;
        final OutputStream aus;
        final AtomicReference<String> sessionId = new AtomicReference<>();
        volatile String challenge;
        volatile String erwarteteAntwort;
        /** Wartet gerade eine PIN-Anfrage auf Antwort? */
        final AtomicReference<String> offeneAnfrage = new AtomicReference<>();
        final java.util.concurrent.BlockingQueue<String> antworten =
                new java.util.concurrent.LinkedBlockingQueue<>();

        Sitzung(String terminalId, OutputStream aus) {
            this.terminalId = terminalId; this.aus = aus;
        }

        public boolean angemeldet() { return sessionId.get() != null; }
    }

    /**
     * Stellt einen Authentisierungsschlüssel aus ({@code SmcbAuthentication}).
     *
     * <p>„If there was already an active key, the old key is removed." — ein
     * zweiter Aufruf macht den ersten wertlos. Und: „It is not allowed to
     * regenerate a key if there is an active connection."
     */
    public static synchronized String schluesselAusstellen(String terminalId) {
        Sitzung s = SITZUNGEN.get(terminalId);
        if (s != null && s.angemeldet()) return null;   // „Remote SMCB already connected"
        String k = SmcbProtokoll.schluesselErzeugen();
        SCHLUESSEL.put(terminalId, new Schluessel(k,
                System.currentTimeMillis() + SmcbProtokoll.SCHLUESSEL_GUELTIG_SEKUNDEN * 1000L));
        return k;
    }

    public static Sitzung sitzung(String terminalId) { return SITZUNGEN.get(terminalId); }

    /**
     * Fordert eine PIN an — als hätte der Konnektor eine Verifikation ausgelöst.
     *
     * <p>Das ist der Kern der Nachbildung: Das <b>Terminal</b> stellt die Frage.
     *
     * @return die gelieferte PIN, oder {@code null} bei Absage/Zeitablauf
     */
    public static char[] pinAnfordern(String terminalId, String slot, String atr,
                                      int minLen, int maxLen, int sekunden) {
        Sitzung s = SITZUNGEN.get(terminalId);
        if (s == null || !s.angemeldet()) return null;

        String msgId = SmcbProtokoll.kennungErzeugen();
        s.offeneAnfrage.set(msgId);
        s.antworten.clear();

        String last = ("{\"Slot\":\"%s\",\"Atr\":\"%s\",\"Prompt\":\"PIN\",\"Message\":\"SMC-B\","
                     + "\"MessageType\":\"Validate\",\"MinLen\":%d,\"MaxLen\":%d,"
                     + "\"TimeoutFirst\":%d,\"TimeoutOther\":%d,\"TimeoutAll\":%d,"
                     + "\"OkButton\":true,\"CancelButton\":true}")
                .formatted(slot, atr, minLen, maxLen, sekunden, sekunden, sekunden);
        senden(s, SmcbProtokoll.nachricht(msgId, null, s.sessionId.get(),
                Art.InputPinRequest, last));

        try {
            String antwort = s.antworten.poll(sekunden, java.util.concurrent.TimeUnit.SECONDS);
            if (antwort == null) return null;
            String code = SmcbProtokoll.feld(SmcbProtokoll.nutzlast(antwort), "Code");
            if (!"Pin".equals(code)) return null;
            String pin = SmcbProtokoll.feld(SmcbProtokoll.nutzlast(antwort), "Pin");
            if (pin == null) return null;
            UEBERGABEN.computeIfAbsent(terminalId,
                    x -> new java.util.concurrent.atomic.AtomicInteger()).incrementAndGet();
            return pin.toCharArray();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            s.offeneAnfrage.set(null);
        }
    }

    /** Bricht eine laufende Anfrage ab — etwa weil die Karte gezogen wurde. */
    public static void abbrechen(String terminalId) {
        Sitzung s = SITZUNGEN.get(terminalId);
        if (s == null) return;
        String offen = s.offeneAnfrage.get();
        if (offen == null) return;
        senden(s, SmcbProtokoll.nachricht(SmcbProtokoll.kennungErzeugen(), null,
                s.sessionId.get(), Art.Cancel, "{\"RequestId\":\"" + offen + "\"}"));
    }

    // ------------------------------------------------------------ WebSocket

    /**
     * Bedient eine eingehende Verbindung auf {@code /cobra-smcb}.
     *
     * <p>Wird vom Rohsocket-Zweig des Simulators aufgerufen, weil der
     * eingebaute HTTP-Server keine Protokollumschaltung beherrscht.
     */
    public static void bedienen(String terminalId, Socket socket, String schluesselKopf)
            throws IOException {
        InputStream ein = socket.getInputStream();
        OutputStream aus = socket.getOutputStream();

        // Ohne gültigen Schlüssel wird sofort getrennt:
        // „There is no Authentication Key available: ST-1506 disconnects WS connection."
        Schluessel k = SCHLUESSEL.get(terminalId);
        if (k == null || k.gueltigBis() < System.currentTimeMillis()) {
            SCHLUESSEL.remove(terminalId);
            socket.close();
            return;
        }
        SCHLUESSEL.remove(terminalId);   // gilt nur für eine Anmeldung

        handschlag(aus, schluesselKopf);

        Sitzung s = new Sitzung(terminalId, aus);
        // „At any time at most one connection is allowed" — die alte fliegt raus.
        Sitzung alt = SITZUNGEN.put(terminalId, s);
        if (alt != null) try { alt.aus.close(); } catch (IOException ignored) { }

        s.challenge = SmcbProtokoll.challengeErzeugen();
        s.erwarteteAntwort = SmcbProtokoll.antwortAufChallenge(k.wert(), s.challenge);
        senden(s, SmcbProtokoll.nachricht(SmcbProtokoll.kennungErzeugen(), null, null,
                Art.AuthenticateRequest,
                "{\"ApiVersion\":\"" + SmcbProtokoll.API_VERSION + "\",\"Challenge\":\""
                + s.challenge + "\"}"));

        try {
            String text;
            while ((text = rahmenLesen(ein)) != null) {
                verarbeiten(s, text);
            }
        } catch (IOException e) {
            // Verbindungsabbruch ist der Normalfall beim Beenden.
        } finally {
            SITZUNGEN.remove(terminalId, s);
            try { socket.close(); } catch (IOException ignored) { }
        }
    }

    private static void verarbeiten(Sitzung s, String text) {
        Art art = Art.aus(SmcbProtokoll.feld(text, "PayloadType"));
        if (art == null) return;
        String last = SmcbProtokoll.nutzlast(text);

        switch (art) {
            case AuthenticateResponse -> {
                String antwort = SmcbProtokoll.feld(last, "Response");
                if (SmcbProtokoll.antwortStimmt(s.erwarteteAntwort, antwort)) {
                    s.sessionId.set(SmcbProtokoll.kennungErzeugen());
                    senden(s, SmcbProtokoll.nachricht(SmcbProtokoll.kennungErzeugen(), null,
                            s.sessionId.get(), Art.Notify, "{\"Code\":0}"));
                } else {
                    // „the authentication key is invalidated. No state transitions
                    // are taken." — der Schlüssel ist ohnehin schon verbraucht.
                    senden(s, SmcbProtokoll.nachricht(SmcbProtokoll.kennungErzeugen(), null, null,
                            Art.Notify, "{\"Code\":7}"));
                }
            }
            case InputPinResponse, OutputResponse -> s.antworten.offer(text);
            default -> { }
        }
    }

    private static synchronized void senden(Sitzung s, String text) {
        try {
            rahmenSchreiben(s.aus, text);
        } catch (IOException e) {
            System.err.println("[SMCB " + s.terminalId + "] Senden fehlgeschlagen: " + e.getMessage());
        }
    }

    /** Der Handschlag nach RFC 6455, Abschnitt 4.2.2. */
    private static void handschlag(OutputStream aus, String schluesselKopf) throws IOException {
        String magisch = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
        String antwort;
        try {
            byte[] sha1 = MessageDigest.getInstance("SHA-1")
                    .digest((schluesselKopf + magisch).getBytes(StandardCharsets.UTF_8));
            antwort = Base64.getEncoder().encodeToString(sha1);
        } catch (Exception e) {
            throw new IOException(e);
        }
        aus.write(("HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + antwort + "\r\n"
                + "Sec-WebSocket-Protocol: " + SmcbProtokoll.UNTERPROTOKOLL + "\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        aus.flush();
    }

    /** Einen Textrahmen schreiben (RFC 6455, Abschnitt 5.2). Server maskiert nicht. */
    private static void rahmenSchreiben(OutputStream aus, String text) throws IOException {
        byte[] b = text.getBytes(StandardCharsets.UTF_8);
        ByteBuffer kopf = ByteBuffer.allocate(10);
        kopf.put((byte) 0x81);                       // FIN + Opcode 1 (Text)
        if (b.length < 126) {
            kopf.put((byte) b.length);
        } else if (b.length < 65536) {
            kopf.put((byte) 126).putShort((short) b.length);
        } else {
            kopf.put((byte) 127).putLong(b.length);
        }
        aus.write(kopf.array(), 0, kopf.position());
        aus.write(b);
        aus.flush();
    }

    /**
     * Einen Textrahmen lesen.
     *
     * <p>Rahmen vom Client sind <b>immer maskiert</b> (RFC 6455, 5.3) — ein
     * unmaskierter Rahmen wäre ein Protokollfehler. Fragmentierung wird
     * zusammengesetzt.
     */
    private static String rahmenLesen(InputStream ein) throws IOException {
        StringBuilder ganz = new StringBuilder();
        while (true) {
            int b0 = ein.read();
            if (b0 < 0) return null;
            boolean letztes = (b0 & 0x80) != 0;
            int opcode = b0 & 0x0F;
            if (opcode == 8) return null;            // Schließen

            int b1 = ein.read();
            if (b1 < 0) return null;
            boolean maskiert = (b1 & 0x80) != 0;
            long laenge = b1 & 0x7F;
            if (laenge == 126) {
                laenge = ((long) ein.read() << 8) | ein.read();
            } else if (laenge == 127) {
                laenge = 0;
                for (int i = 0; i < 8; i++) laenge = (laenge << 8) | ein.read();
            }

            byte[] maske = new byte[4];
            if (maskiert) if (ein.readNBytes(maske, 0, 4) != 4) return null;

            byte[] nutz = ein.readNBytes((int) laenge);
            if (nutz.length != laenge) return null;
            if (maskiert) for (int i = 0; i < nutz.length; i++) nutz[i] ^= maske[i % 4];

            if (opcode == 9) continue;               // Ping — wird hier nicht beantwortet
            ganz.append(new String(nutz, StandardCharsets.UTF_8));
            if (letztes) return ganz.toString();
        }
    }

    /** Setzt alles zurück — für den Prüfstand zwischen den Nachweisen. */
    public static void zuruecksetzen() {
        SITZUNGEN.values().forEach(s -> {
            try { s.aus.close(); } catch (IOException ignored) { }
        });
        SITZUNGEN.clear();
        SCHLUESSEL.clear();
    }
}
