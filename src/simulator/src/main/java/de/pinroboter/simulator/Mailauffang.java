package de.pinroboter.simulator;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Ein SMTP-Auffangserver für die Nachweise (FA-ALA-020).
 *
 * <h2>Warum im Simulator und nicht MailHog</h2>
 *
 * Der Simulator ersetzt in diesem Projekt die Fremdsysteme, die im Test nicht
 * verfügbar sind — Konnektor und Kartenterminals. Ein Mailserver gehört in
 * dieselbe Kategorie.
 *
 * <p>Der erste Anlauf verwendete MailHog. Das ist bequem und funktioniert —
 * solange das Image lokal liegt. Am 08.08.2026 hing der Nachweislauf beim
 * Ziehen des Images, und ein Prüflauf, der ohne Internetzugang stehen bleibt,
 * ist als Nachweis unbrauchbar. Der Rest der Nachweise kommt mit den Images
 * aus, die der Bauvorgang ohnehin braucht; diese Klasse hält das durch.
 *
 * <h2>Was er kann</h2>
 *
 * Genau so viel, wie ein Auffangserver können muss: den Dialog aus
 * {@code RFC 5321} führen, die Nachricht annehmen und sie zum Abruf
 * bereitlegen. Kein Weiterleiten, keine Postfächer, keine Zustellung.
 *
 * <p><b>Absichtlich ohne TLS und ohne Anmeldung.</b> Er nimmt an, was kommt.
 * Die Verschlüsselung des Versands wird nicht hier geprüft, sondern gegen
 * einen echten Mailserver beim Kunden — hier geht es um die Frage, ob aus
 * 50 Alarmen eine Nachricht wird.
 */
public final class Mailauffang {

    /** Empfangene Nachrichten, in Reihenfolge des Eingangs. */
    private static final List<String> EINGANG = new CopyOnWriteArrayList<>();

    private Mailauffang() { }

    public static List<String> eingang() { return List.copyOf(EINGANG); }

    public static void leeren() { EINGANG.clear(); }

    public static void starten(int port) throws IOException {
        ServerSocket lauscher = new ServerSocket(port);
        Thread.ofVirtual().start(() -> {
            while (!lauscher.isClosed()) {
                try {
                    Socket s = lauscher.accept();
                    Thread.ofVirtual().start(() -> bedienen(s));
                } catch (IOException e) {
                    if (!lauscher.isClosed()) System.err.println("  Mailauffang: " + e.getMessage());
                }
            }
        });
        System.out.printf("  %-28s smtp://0.0.0.0:%d%n", "Mailauffang (Test)", port);
    }

    private static void bedienen(Socket s) {
        try (s;
             var ein = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
             var aus = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8))) {

            s.setSoTimeout(30_000);
            sagen(aus, "220 pinroboter-mailauffang bereit");

            StringBuilder nachricht = new StringBuilder();
            String zeile;
            while ((zeile = ein.readLine()) != null) {
                String befehl = zeile.toUpperCase();

                if (befehl.startsWith("EHLO") || befehl.startsWith("HELO")) {
                    // Keine Faehigkeit anbieten: kein STARTTLS, kein AUTH. Der
                    // Versand muss damit umgehen koennen, dass ein Server
                    // nichts davon kann.
                    sagen(aus, "250 pinroboter-mailauffang");
                } else if (befehl.startsWith("MAIL FROM") || befehl.startsWith("RCPT TO")) {
                    sagen(aus, "250 OK");
                } else if (befehl.equals("DATA")) {
                    sagen(aus, "354 Ende mit einer Zeile, die nur einen Punkt enthält");
                    nachricht.setLength(0);
                    String d;
                    while ((d = ein.readLine()) != null) {
                        if (d.equals(".")) break;
                        // Punkt-Stopfen rueckgaengig machen (RFC 5321, 4.5.2) —
                        // sonst prueft der Nachweis gegen einen Text, den so
                        // niemand im Postfach sieht.
                        nachricht.append(d.startsWith("..") ? d.substring(1) : d).append("\n");
                    }
                    EINGANG.add(nachricht.toString());
                    sagen(aus, "250 OK: angenommen");
                } else if (befehl.equals("QUIT")) {
                    sagen(aus, "221 Auf Wiedersehen");
                    return;
                } else if (befehl.equals("RSET") || befehl.startsWith("NOOP")) {
                    sagen(aus, "250 OK");
                } else {
                    sagen(aus, "502 Nicht unterstützt");
                }
            }
        } catch (IOException ignored) {
            // Ein abgebrochener Dialog ist im Test kein Fehler.
        }
    }

    private static void sagen(BufferedWriter aus, String s) throws IOException {
        aus.write(s);
        aus.write("\r\n");
        aus.flush();
    }
}
