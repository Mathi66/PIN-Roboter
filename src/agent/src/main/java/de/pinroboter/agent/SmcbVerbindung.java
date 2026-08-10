package de.pinroboter.agent;

import de.pinroboter.gemeinsam.SmcbProtokoll;
import de.pinroboter.gemeinsam.SmcbProtokoll.Art;
import de.pinroboter.gemeinsam.SmcbProtokoll.Grund;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Eine dauerhafte Verbindung zur <b>Remote SMCB API</b> eines Kartenterminals
 * (ADR-0011).
 *
 * <h2>Der Bruch mit dem alten Modell</h2>
 *
 * Bis zum 10.08.2026 baute der Agent eine kurze Sitzung auf, <i>schob</i> die
 * PIN hin und meldete sich ab. Die Herstellerspezifikation verlangt das
 * Gegenteil: eine Verbindung, die <b>offen bleibt</b>, und auf der das Terminal
 * fragt, wenn der Konnektor eine Verifikation auslöst.
 *
 * <blockquote>
 * „While authenticated the Remote SMCB API will send {@code OutputRequests} and
 * {@code InputPinRequests} if a PIN verification is requested on the respective
 * slot from the Konnektor."<br>
 * — ST-1506 Remote Management Specification 2.8, Abschnitt 7.4
 * </blockquote>
 *
 * <h2>Die drei Zustände</h2>
 *
 * <pre>
 *   GETRENNT ──verbinden──▶ VERBUNDEN ──Challenge beantwortet──▶ ANGEMELDET
 *      ▲                        │                                    │
 *      └────────────────────────┴────────────────────────────────────┘
 *                        Abbruch, Fehler, Schließen
 * </pre>
 *
 * Der Schlüssel aus {@code SmcbAuthentication} gilt <b>30 Sekunden</b>. Wer
 * ihn nicht in dieser Zeit einlöst, muss einen neuen holen — deshalb wird er
 * unmittelbar vor dem Verbindungsaufbau beschafft, nicht auf Vorrat.
 *
 * <h2>Wo die Sicherungslogik greift</h2>
 *
 * <b>Vor dem Beantworten, nicht vor dem Senden.</b> Das ist der zweite Bruch
 * mit dem alten Modell: Früher entschied der Agent, ob er die PIN schickt. Jetzt
 * entscheidet er, ob er auf eine Frage antwortet, die schon gestellt ist. Sagt
 * die Sicherungsprüfung Nein, wird mit {@link Grund#CancelButton} abgesagt —
 * das Terminal bricht dann sauber ab, statt in eine Zeitüberschreitung zu
 * laufen.
 *
 * <p><b>Eine Verbindung je Terminal, und nur eine.</b> Die Spezifikation lässt
 * nicht mehr zu: „At any time at most one connection to the Remote SMCB API is
 * allowed." Wer eine zweite aufbaut, wirft die erste hinaus.
 */
public class SmcbVerbindung implements AutoCloseable {

    public enum Zustand { GETRENNT, VERBUNDEN, ANGEMELDET, GESCHEITERT }

    /** Woher die PIN kommt, wenn das Terminal fragt. */
    public interface Pinquelle {
        /**
         * @param slot Kartenslot, den das Terminal nennt (z. B. {@code Slot1})
         * @return die PIN, oder {@code null} wenn keine hinterlegt ist
         */
        char[] pinFuer(String slot);
    }

    /** Ob gerade geantwortet werden darf — LeftTries, Not-Aus, KSR-Lauf. */
    public interface Freigabe {
        /** @return Grund der Ablehnung im Klartext, oder {@code null} für „ja" */
        String hindernis(String slot);
    }

    /** Was passiert ist — für Protokoll und Alarme. */
    public interface Beobachter {
        void angemeldet(String terminalId);
        void pinGeliefert(String terminalId, String slot);
        void pinVerweigert(String terminalId, String slot, String grund);
        void getrennt(String terminalId, String grund);
    }

    private final String terminalId;
    private final URI basis;
    private final HttpClient http;
    private final Pinquelle pins;
    private final Freigabe freigabe;
    private final Beobachter beobachter;
    private final Supplier<String> schluesselHolen;

    private final AtomicReference<Zustand> zustand = new AtomicReference<>(Zustand.GETRENNT);
    private volatile WebSocket ws;
    private volatile String sessionId;
    private volatile String schluessel;
    private volatile Instant angemeldetSeit;
    private volatile String letzterFehler;

    /** Teilnachrichten sammeln: Der Server darf fragmentieren (Abschnitt 7.1). */
    private final StringBuilder puffer = new StringBuilder();

    public SmcbVerbindung(String terminalId, URI basis, HttpClient http,
                          Supplier<String> schluesselHolen, Pinquelle pins,
                          Freigabe freigabe, Beobachter beobachter) {
        this.terminalId = terminalId;
        this.basis = basis;
        this.http = http;
        this.schluesselHolen = schluesselHolen;
        this.pins = pins;
        this.freigabe = freigabe;
        this.beobachter = beobachter;
    }

    public Zustand zustand() { return zustand.get(); }
    public String terminalId() { return terminalId; }
    public Instant angemeldetSeit() { return angemeldetSeit; }
    public String letzterFehler() { return letzterFehler; }

    // ------------------------------------------------------------------ Aufbau

    /**
     * Baut die Verbindung auf und meldet sich an.
     *
     * <p>Der Schlüssel wird <b>hier</b> geholt, nicht vorher: Er gilt nur 30
     * Sekunden. Ihn auf Vorrat zu beschaffen hieße, ihn regelmäßig verfallen zu
     * lassen.
     *
     * @return {@code true}, wenn die Verbindung steht und angemeldet ist
     */
    public boolean verbinden() {
        if (zustand.get() == Zustand.ANGEMELDET) return true;
        try {
            schluessel = schluesselHolen.get();
            if (schluessel == null) {
                scheitern("Kein Authentisierungsschlüssel erhalten. Ist „Remote PIN\" "
                        + "am Terminal für diesen Slot eingeschaltet?");
                return false;
            }

            // Am echten Gerät ist die Basis das Terminal selbst, und der Pfad
            // wäre nur „/cobra-smcb". Hier steht die Kennung davor, weil der
            // Simulator alle Terminals hinter einem Port bündelt — dieselbe
            // Konvention wie in TerminalClient („/api/<id>/…"). Zu ändern ist
            // das später an genau dieser Stelle.
            URI ziel = URI.create(basis.toString().replaceFirst("^http", "ws")
                    .replaceAll("/+$", "") + "/" + terminalId + "/"
                    + SmcbProtokoll.UNTERPROTOKOLL);

            ws = http.newWebSocketBuilder()
                    .subprotocols(SmcbProtokoll.UNTERPROTOKOLL)
                    .connectTimeout(Duration.ofSeconds(10))
                    .buildAsync(ziel, new Horcher())
                    .get(15, java.util.concurrent.TimeUnit.SECONDS);

            zustand.set(Zustand.VERBUNDEN);

            // Auf die Anmeldung warten: Das Terminal schickt von sich aus einen
            // AuthenticateRequest, sobald die Verbindung steht.
            for (int i = 0; i < SmcbProtokoll.ANTWORTFRIST_SEKUNDEN * 4; i++) {
                if (zustand.get() == Zustand.ANGEMELDET) return true;
                if (zustand.get() == Zustand.GESCHEITERT) return false;
                Thread.sleep(250);
            }
            scheitern("Das Terminal hat sich nach dem Verbinden nicht gemeldet.");
            return false;

        } catch (Exception e) {
            scheitern(e.getMessage() == null ? e.toString() : e.getMessage());
            return false;
        }
    }

    @Override
    public void close() {
        try {
            if (ws != null) ws.sendClose(WebSocket.NORMAL_CLOSURE, "Ende").join();
        } catch (Exception ignored) {
            // Beim Schließen ist ein Fehler ohne Folgen — die Verbindung ist ohnehin weg.
        } finally {
            ws = null;
            sessionId = null;
            loescheSchluessel();
            if (zustand.getAndSet(Zustand.GETRENNT) == Zustand.ANGEMELDET && beobachter != null) {
                beobachter.getrennt(terminalId, "geschlossen");
            }
        }
    }

    // ------------------------------------------------------------------ Empfang

    private class Horcher implements WebSocket.Listener {
        @Override
        public void onOpen(WebSocket w) {
            w.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket w, CharSequence daten, boolean letztes) {
            puffer.append(daten);
            if (letztes) {
                String nachricht = puffer.toString();
                puffer.setLength(0);
                try {
                    verarbeiten(w, nachricht);
                } catch (Exception e) {
                    // Eine unverständliche Nachricht darf die Verbindung nicht
                    // stillschweigend beenden — sonst sucht niemand die Ursache.
                    System.err.println("[" + terminalId + "] Nachricht nicht verarbeitbar: "
                            + e.getMessage());
                }
            }
            w.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket w, int code, String grund) {
            zustand.set(Zustand.GETRENNT);
            loescheSchluessel();
            if (beobachter != null) beobachter.getrennt(terminalId, "Code " + code + " " + grund);
            return null;
        }

        @Override
        public void onError(WebSocket w, Throwable t) {
            scheitern(t.getMessage() == null ? t.toString() : t.getMessage());
        }
    }

    private void verarbeiten(WebSocket w, String nachricht) {
        Art art = Art.aus(SmcbProtokoll.feld(nachricht, "PayloadType"));
        if (art == null) return;
        String msgId = SmcbProtokoll.feld(nachricht, "MsgId");
        String last = SmcbProtokoll.nutzlast(nachricht);

        switch (art) {
            case AuthenticateRequest -> anmelden(w, msgId, last);
            case InputPinRequest -> pinAnfrage(w, msgId, last);
            case OutputRequest -> ausgabeAnfrage(w, msgId, last);
            case Notify -> hinweis(nachricht, last);
            case Cancel -> {
                // Die Karte wurde gezogen, während gefragt wurde. Nichts zu tun:
                // Die Spezifikation verlangt ausdrücklich KEINE Antwort.
                System.out.println("[" + terminalId + "] Anfrage vom Terminal abgebrochen");
            }
            default -> { }
        }
    }

    /** Challenge beantworten (Abschnitt 7.7.3/7.7.4). */
    private void anmelden(WebSocket w, String msgId, String last) {
        String version = SmcbProtokoll.feld(last, "ApiVersion");
        if (version != null && !SmcbProtokoll.API_VERSION.equals(version)) {
            scheitern("Das Terminal spricht Protokollfassung " + version
                    + ", unterstützt wird " + SmcbProtokoll.API_VERSION + ".");
            return;
        }
        String challenge = SmcbProtokoll.feld(last, "Challenge");
        if (challenge == null) { scheitern("Challenge fehlt in der Anmeldeanfrage."); return; }

        String antwort = SmcbProtokoll.antwortAufChallenge(schluessel, challenge);
        // Der Schlüssel darf nur EINE Antwort erzeugen ("The key must only be
        // used to compute a single CMAC") — danach ist er wertlos.
        loescheSchluessel();

        senden(w, SmcbProtokoll.nachricht(SmcbProtokoll.kennungErzeugen(), msgId, null,
                Art.AuthenticateResponse, "{\"Response\":\"" + antwort + "\"}"));
    }

    /**
     * Die PIN-Anfrage des Terminals beantworten (Abschnitt 7.7.7/7.7.8).
     *
     * <p>Hier greift die Sicherungslogik — <b>bevor</b> geantwortet wird. Eine
     * Absage über {@link Grund#CancelButton} ist dabei besser als Schweigen: Das
     * Terminal bricht sauber ab, statt in die Zeitüberschreitung zu laufen und
     * den Anwender warten zu lassen.
     */
    private void pinAnfrage(WebSocket w, String msgId, String last) {
        String slot = SmcbProtokoll.feld(last, "Slot");
        Integer min = SmcbProtokoll.zahl(last, "MinLen");
        Integer max = SmcbProtokoll.zahl(last, "MaxLen");

        String hindernis = freigabe == null ? null : freigabe.hindernis(slot);
        if (hindernis != null) {
            antwortSenden(w, msgId, Grund.CancelButton, null);
            if (beobachter != null) beobachter.pinVerweigert(terminalId, slot, hindernis);
            return;
        }

        char[] pin = pins == null ? null : pins.pinFuer(slot);
        try {
            if (pin == null || pin.length == 0) {
                antwortSenden(w, msgId, Grund.CancelButton, null);
                if (beobachter != null) {
                    beobachter.pinVerweigert(terminalId, slot, "Für diesen Slot ist keine PIN hinterlegt.");
                }
                return;
            }
            // Die Längengrenzen kommen mit der Anfrage. Eine PIN, die nicht
            // hineinpasst, würde das Terminal ohnehin abweisen — und der
            // Versuch zählte womöglich auf den Fehlversuchszähler.
            if ((min != null && pin.length < min) || (max != null && pin.length > max)) {
                antwortSenden(w, msgId, Grund.Error, null);
                if (beobachter != null) {
                    beobachter.pinVerweigert(terminalId, slot,
                            "Die hinterlegte PIN hat " + pin.length + " Stellen, das Terminal "
                            + "erwartet " + min + " bis " + max + ".");
                }
                return;
            }
            antwortSenden(w, msgId, Grund.Pin, pin);
            if (beobachter != null) beobachter.pinGeliefert(terminalId, slot);
        } finally {
            if (pin != null) Arrays.fill(pin, '\0');
        }
    }

    /**
     * Eine Anzeigeanforderung (Abschnitt 7.7.5).
     *
     * <p>Es gibt niemanden, dem etwas anzuzeigen wäre — der Roboter arbeitet
     * ohne Menschen davor. Beantwortet wird trotzdem, sonst wartet das Terminal.
     */
    private void ausgabeAnfrage(WebSocket w, String msgId, String last) {
        Boolean antwortErwartet = SmcbProtokoll.wahrheit(last, "ExpectResponse");
        if (Boolean.FALSE.equals(antwortErwartet)) return;
        senden(w, SmcbProtokoll.nachricht(SmcbProtokoll.kennungErzeugen(), msgId, sessionId,
                Art.OutputResponse, "{\"Code\":\"Ok\"}"));
    }

    /** Quittung des Terminals (Abschnitt 7.7.1). Code 0 heißt Erfolg. */
    private void hinweis(String nachricht, String last) {
        Integer code = SmcbProtokoll.zahl(last, "Code");
        String sid = SmcbProtokoll.feld(nachricht, "SessionId");

        if (zustand.get() == Zustand.VERBUNDEN) {
            if (code != null && code == 0 && sid != null) {
                sessionId = sid;
                angemeldetSeit = Instant.now();
                letzterFehler = null;
                zustand.set(Zustand.ANGEMELDET);
                if (beobachter != null) beobachter.angemeldet(terminalId);
            } else {
                scheitern("Die Anmeldung wurde abgelehnt (Code " + code + "). "
                        + "Stimmt die Admin-PIN des Terminals?");
            }
            return;
        }
        if (code != null && code != 0) {
            System.err.println("[" + terminalId + "] Terminal meldet Code " + code);
        }
    }

    // ------------------------------------------------------------------ intern

    private void antwortSenden(WebSocket w, String msgId, Grund grund, char[] pin) {
        senden(w, SmcbProtokoll.nachricht(SmcbProtokoll.kennungErzeugen(), msgId, sessionId,
                Art.InputPinResponse, SmcbProtokoll.pinAntwort(grund, pin)));
    }

    private void senden(WebSocket w, String text) {
        try {
            w.sendText(text, true).join();
        } catch (Exception e) {
            scheitern("Senden fehlgeschlagen: " + e.getMessage());
        }
    }

    private void scheitern(String grund) {
        letzterFehler = grund;
        zustand.set(Zustand.GESCHEITERT);
        loescheSchluessel();
        System.err.println("[" + terminalId + "] SMCB-Verbindung: " + grund);
    }

    /** Der Schlüssel wird nach Gebrauch überschrieben — er taugt nur einmal. */
    private void loescheSchluessel() {
        schluessel = null;
    }
}
