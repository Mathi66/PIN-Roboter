package de.pinroboter.zentrale;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

/**
 * Versand einer E-Mail über SMTP (FA-ALA-020).
 *
 * <h2>Warum von Hand und nicht mit einer Bibliothek</h2>
 *
 * Dieses Produkt braucht genau eine Sache: eine Textnachricht an einen
 * Mailserver übergeben. Kein Empfang, keine Anhänge, kein MIME-Multipart,
 * keine Adressbücher. Dafür sind {@code jakarta.mail} und {@code angus-mail}
 * zwei Abhängigkeiten samt {@code jakarta.activation} — Angriffsfläche und
 * Pflegeaufwand für eine Anwendung, die in Arztpraxen ohne IT-Personal läuft.
 *
 * <p>{@code RFC 5321} ist für diesen Ausschnitt schmal und stabil. Dieselbe
 * Überlegung wie beim handgeschriebenen SOAP-Client in ADR-0006: Bei wenigen
 * Operationen ist eigener, lesbarer Code die bessere Wahl als eine
 * Bibliothek, deren Umfang man nicht braucht.
 *
 * <h2>Was diese Klasse ausdrücklich NICHT kann</h2>
 *
 * Anhänge, HTML-Mail, mehrere Empfänger mit unterschiedlichen Kopfzeilen,
 * DKIM-Signatur. Wer das braucht, wechselt zu einer Bibliothek — und das wäre
 * dann eine bewusste Entscheidung, kein Nachrüsten an dieser Stelle.
 *
 * <h2>Sicherheit</h2>
 *
 * <ul>
 *   <li><b>STARTTLS ist der Regelfall</b> und wird verlangt, sobald der Server
 *       es anbietet. Ein Server ohne TLS wird nur bedient, wenn der Betreiber
 *       das ausdrücklich einstellt — und die Oberfläche sagt, was das bedeutet.</li>
 *   <li><b>Zugangsdaten erscheinen nie in einer Meldung.</b> Auch nicht in der
 *       Fehlermeldung des Testversands: Ein Server, der {@code AUTH} ablehnt,
 *       gibt manchmal den Benutzernamen zurück.</li>
 *   <li>Der Mailtext wird vom Aufrufer erzeugt. <b>Er enthält nie eine PIN und
 *       keine personenbezogenen Daten</b> — durchgesetzt in
 *       {@link Benachrichtigung}, nicht hier.</li>
 * </ul>
 */
public final class Mailversand {

    /** Verbindungs- und Leseabbruch. Ein hängender Mailserver darf nichts anhalten. */
    private static final Duration GEDULD = Duration.ofSeconds(20);

    public record Zugang(String host, int port, boolean tlsVerlangt,
                         String benutzer, char[] kennwort, String absender) {}

    /** Ergebnis eines Versands. {@code fehler == null} heißt: angenommen. */
    public record Ergebnis(String fehler, String verlauf) {
        public boolean geklappt() { return fehler == null; }
    }

    private Mailversand() { }

    /**
     * Sendet eine Textnachricht.
     *
     * @param empfaenger eine oder mehrere Adressen
     * @return das Ergebnis. Der {@code verlauf} enthält die Serverantworten für
     *         den Testversand — <b>ohne</b> die {@code AUTH}-Zeile.
     */
    public static Ergebnis senden(Zugang z, List<String> empfaenger,
                                  String betreff, String text) {
        if (z == null || z.host() == null || z.host().isBlank()) {
            return new Ergebnis("Es ist kein Mailserver eingerichtet.", null);
        }
        if (empfaenger == null || empfaenger.isEmpty()) {
            return new Ergebnis("Es ist keine Empfängeradresse hinterlegt.", null);
        }
        for (String e : empfaenger) {
            if (!gueltigeAdresse(e)) {
                return new Ergebnis("„" + e + "“ ist keine gültige E-Mail-Adresse.", null);
            }
        }

        StringBuilder verlauf = new StringBuilder();
        try (Socket roh = new Socket()) {
            roh.connect(new java.net.InetSocketAddress(z.host(), z.port()),
                    (int) GEDULD.toMillis());
            roh.setSoTimeout((int) GEDULD.toMillis());

            Socket s = roh;
            // Port 465 spricht TLS von der ersten Nullstelle an (implizites TLS),
            // 25 und 587 beginnen im Klartext und schalten mit STARTTLS um.
            if (z.port() == 465) s = tlsAufsetzen(roh, z.host());

            var ein = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            var aus = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));

            String gruss = lesen(ein, verlauf);
            pruefe(gruss, "220", "Der Mailserver hat die Verbindung nicht angenommen");

            String faehig = befehl(aus, ein, verlauf, "EHLO pin-roboter");
            pruefe(faehig, "250", "Der Mailserver hat EHLO abgelehnt");

            boolean kannStarttls = faehig.toUpperCase().contains("STARTTLS");
            if (s == roh && kannStarttls) {
                pruefe(befehl(aus, ein, verlauf, "STARTTLS"), "220",
                        "Der Mailserver hat STARTTLS abgelehnt");
                s = tlsAufsetzen(roh, z.host());
                ein = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                aus = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
                // Nach STARTTLS ist EHLO zu wiederholen (RFC 3207): Die
                // Faehigkeitsliste vor der Verschluesselung ist nicht vertrauenswuerdig.
                pruefe(befehl(aus, ein, verlauf, "EHLO pin-roboter"), "250",
                        "Der Mailserver hat EHLO nach STARTTLS abgelehnt");
            } else if (s == roh && z.tlsVerlangt()) {
                return new Ergebnis(
                        "Der Mailserver bietet keine Verschlüsselung an (STARTTLS). "
                        + "Der Versand wurde abgebrochen. Wenn Ihr Mailserver im eigenen Netz "
                        + "steht und Sie das bewusst in Kauf nehmen, lässt sich die "
                        + "Verschlüsselungspflicht in den Einstellungen abschalten.",
                        verlauf.toString());
            }

            if (z.benutzer() != null && !z.benutzer().isBlank()) {
                // AUTH PLAIN: \\0benutzer\\0kennwort, Base64. Die Zeile selbst
                // wird NICHT in den Verlauf geschrieben.
                byte[] roh64 = ("\0" + z.benutzer() + "\0" + new String(z.kennwort()))
                        .getBytes(StandardCharsets.UTF_8);
                String antwort = befehlStill(aus, ein,
                        "AUTH PLAIN " + Base64.getEncoder().encodeToString(roh64));
                java.util.Arrays.fill(roh64, (byte) 0);
                verlauf.append("> AUTH PLAIN ****\n< ").append(antwort).append("\n");
                if (!antwort.startsWith("235")) {
                    // Die Serverantwort kann den Benutzernamen enthalten -
                    // deshalb wird sie hier NICHT durchgereicht.
                    return new Ergebnis("Der Mailserver hat die Zugangsdaten abgelehnt. "
                            + "Bitte Benutzername und Kennwort prüfen.", verlauf.toString());
                }
            }

            pruefe(befehl(aus, ein, verlauf, "MAIL FROM:<" + z.absender() + ">"), "250",
                    "Der Mailserver hat die Absenderadresse abgelehnt");
            for (String e : empfaenger) {
                pruefe(befehl(aus, ein, verlauf, "RCPT TO:<" + e + ">"), "250",
                        "Der Mailserver hat die Empfängeradresse „" + e + "“ abgelehnt");
            }
            pruefe(befehl(aus, ein, verlauf, "DATA"), "354",
                    "Der Mailserver hat den Nachrichtenteil abgelehnt");

            schreiben(aus, nachricht(z.absender(), empfaenger, betreff, text));
            schreiben(aus, ".");
            String angenommen = lesen(ein, verlauf);
            pruefe(angenommen, "250", "Der Mailserver hat die Nachricht nicht angenommen");

            befehl(aus, ein, verlauf, "QUIT");
            return new Ergebnis(null, verlauf.toString());

        } catch (Absage a) {
            return new Ergebnis(a.getMessage(), verlauf.toString());
        } catch (java.net.UnknownHostException e) {
            return new Ergebnis("Der Mailserver „" + z.host() + "“ ist nicht auflösbar. "
                    + "Bitte den Namen prüfen.", verlauf.toString());
        } catch (java.net.SocketTimeoutException e) {
            return new Ergebnis("Der Mailserver antwortet nicht innerhalb von "
                    + GEDULD.toSeconds() + " Sekunden.", verlauf.toString());
        } catch (IOException e) {
            return new Ergebnis("Verbindung zum Mailserver nicht möglich: "
                    + e.getMessage(), verlauf.toString());
        }
    }

    // ------------------------------------------------------------- Nachricht

    /**
     * Baut die Nachricht nach RFC 5322.
     *
     * <p>Der Betreff wird nach {@code RFC 2047} kodiert. Ohne das erscheinen
     * Umlaute im Posteingang als Buchstabensalat — und die Meldungen dieses
     * Produkts sind auf Deutsch.
     */
    static String nachricht(String absender, List<String> empfaenger,
                            String betreff, String text) {
        StringBuilder m = new StringBuilder();
        m.append("From: PIN-Roboter <").append(absender).append(">\r\n");
        m.append("To: ").append(String.join(", ", empfaenger)).append("\r\n");
        m.append("Subject: ").append(betreffKodiert(betreff)).append("\r\n");
        m.append("MIME-Version: 1.0\r\n");
        m.append("Content-Type: text/plain; charset=UTF-8\r\n");
        m.append("Content-Transfer-Encoding: 8bit\r\n");
        m.append("Auto-Submitted: auto-generated\r\n");   // RFC 3834: keine Abwesenheitsantworten
        m.append("\r\n");
        // Punkt-Stopfen (RFC 5321, 4.5.2): Eine Zeile, die mit einem Punkt
        // beginnt, wuerde sonst die Nachricht vorzeitig beenden.
        for (String zeile : text.split("\n", -1)) {
            if (zeile.startsWith(".")) m.append('.');
            m.append(zeile.stripTrailing()).append("\r\n");
        }
        return m.toString();
    }

    /** RFC 2047, Base64-Variante — robust für beliebige UTF-8-Zeichen. */
    static String betreffKodiert(String betreff) {
        if (betreff.chars().allMatch(c -> c >= 32 && c < 127)) return betreff;
        return "=?UTF-8?B?"
                + Base64.getEncoder().encodeToString(betreff.getBytes(StandardCharsets.UTF_8))
                + "?=";
    }

    static boolean gueltigeAdresse(String a) {
        // Bewusst grob: Eine vollstaendige RFC-5322-Pruefung ist berüchtigt
        // fehleranfaellig, und der Mailserver prueft ohnehin. Hier geht es nur
        // darum, offensichtlichen Unsinn und eingeschleuste Zeilenumbrueche
        // abzufangen.
        return a != null && a.matches("[^\\s<>,;:\\\\\"]+@[^\\s<>,;:\\\\\"@]+\\.[A-Za-z]{2,}");
    }

    // ------------------------------------------------------------ Innenleben

    /** Eine Absage des Servers — mit einer Meldung, die dem Betreiber hilft. */
    private static final class Absage extends IOException {
        Absage(String meldung) { super(meldung); }
    }

    private static Socket tlsAufsetzen(Socket roh, String host) throws IOException {
        SSLSocket tls = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                .createSocket(roh, host, roh.getPort(), false);
        tls.setUseClientMode(true);
        // Der Name im Zertifikat muss zum Host passen. Ohne diese Zeile
        // akzeptiert der Socket jedes gueltige Zertifikat, auch ein fremdes.
        var p = tls.getSSLParameters();
        p.setEndpointIdentificationAlgorithm("HTTPS");
        tls.setSSLParameters(p);
        tls.startHandshake();
        return tls;
    }

    private static String befehl(BufferedWriter aus, BufferedReader ein,
                                 StringBuilder verlauf, String zeile) throws IOException {
        verlauf.append("> ").append(zeile).append("\n");
        schreiben(aus, zeile);
        return lesen(ein, verlauf);
    }

    /** Wie {@link #befehl}, aber ohne den Befehl zu protokollieren. */
    private static String befehlStill(BufferedWriter aus, BufferedReader ein,
                                      String zeile) throws IOException {
        schreiben(aus, zeile);
        return lesen(ein, new StringBuilder());
    }

    private static void schreiben(BufferedWriter aus, String s) throws IOException {
        aus.write(s);
        aus.write("\r\n");
        aus.flush();
    }

    /** Liest eine Antwort, auch mehrzeilige (Zeilen mit „250-“ setzen fort). */
    private static String lesen(BufferedReader ein, StringBuilder verlauf) throws IOException {
        StringBuilder alles = new StringBuilder();
        String zeile;
        while ((zeile = ein.readLine()) != null) {
            alles.append(zeile).append("\n");
            if (zeile.length() < 4 || zeile.charAt(3) != '-') break;
        }
        if (alles.length() == 0) throw new Absage("Der Mailserver hat die Verbindung beendet.");
        String antwort = alles.toString().strip();
        verlauf.append("< ").append(antwort).append("\n");
        return antwort;
    }

    private static void pruefe(String antwort, String erwartet, String wasSchiefging)
            throws Absage {
        if (!antwort.startsWith(erwartet)) {
            throw new Absage(wasSchiefging + ": " + kurz(antwort));
        }
    }

    private static String kurz(String s) {
        String eine = s.lines().findFirst().orElse(s);
        return eine.length() > 160 ? eine.substring(0, 160) + " …" : eine;
    }
}
