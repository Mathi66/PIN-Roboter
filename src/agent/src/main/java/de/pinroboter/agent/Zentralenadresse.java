package de.pinroboter.agent;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Wo die Zentrale erreichbar ist — und wie man das ändert, ohne an jeden
 * Praxisrechner zu müssen (PO, 13.08.2026).
 *
 * <h2>Warum das nicht bei der Umgebungsvariablen bleiben konnte</h2>
 *
 * Die Adresse kommt aus {@code ZENTRALE_URL}. Das genügt für die
 * Inbetriebnahme — aber nicht für einen <b>Serverumzug</b>: Zieht die Zentrale
 * auf eine andere Adresse, zeigen alle ausgelieferten Agenten ins Leere. Sie
 * über die {@code .env} nachzuziehen hieße, an jedem Praxisrechner die Datei zu
 * bearbeiten und den Container neu zu starten. Bei 150 betreuten Einrichtungen
 * ist das keine Option; die Agenten wären schlicht verloren.
 *
 * <h2>Wie es zusammenspielt</h2>
 *
 * <pre>
 * ZENTRALE_URL (Umgebung)  →  Vorgabe bei der Inbetriebnahme
 * /daten/zentrale-url      →  überschreibt sie, wenn vorhanden
 * </pre>
 *
 * <p><b>Die Datei gewinnt.</b> Sie ist die jüngere und bewusste Entscheidung
 * eines Menschen an genau diesem Standort; die Umgebungsvariable stammt aus dem
 * Einrichtungspaket von damals. Die Oberfläche zeigt trotzdem <b>beide</b>
 * Werte an — sonst rätselt jemand, warum seine Änderung an der {@code .env}
 * nichts bewirkt.
 *
 * <p>Sie liegt im Datenverzeichnis, also im selben Volume wie Token und
 * Schlüsselpaar: Was einen Neustart überleben muss, gehört zusammen.
 *
 * <h2>Was die Änderung nicht ist</h2>
 *
 * Kein Sicherheitsrisiko, das es nicht schon gäbe: Die Oberfläche ist nur auf
 * {@code 127.0.0.1} erreichbar, und wer lokalen Zugriff auf den Rechner hat,
 * kann den Container ohnehin anhalten. Ein Umlenken auf eine fremde Zentrale
 * nützt einem Angreifer nichts — das Token gilt nur bei der echten, und PINs
 * sind für den öffentlichen Schlüssel <em>dieses</em> Agenten verschlüsselt.
 */
final class Zentralenadresse {

    private final Path datei;
    private final URI ausUmgebung;
    private volatile URI aktuell;

    Zentralenadresse(Path datei, String ausUmgebung) {
        this.datei = datei;
        this.ausUmgebung = URI.create(ausUmgebung);
        this.aktuell = gespeicherteLesen().orElse(this.ausUmgebung);
    }

    URI aktuell()     { return aktuell; }
    URI ausUmgebung() { return ausUmgebung; }

    /** Weicht die laufende Adresse von der Umgebungsvariablen ab? */
    boolean abweichend() { return !aktuell.equals(ausUmgebung); }

    private java.util.Optional<URI> gespeicherteLesen() {
        try {
            if (!Files.isReadable(datei)) return java.util.Optional.empty();
            String t = Files.readString(datei, StandardCharsets.UTF_8).trim();
            return t.isBlank() ? java.util.Optional.empty()
                               : java.util.Optional.of(URI.create(t));
        } catch (Exception e) {
            // Eine unlesbare Datei darf den Start nicht verhindern — dann gilt
            // die Umgebungsvariable, und der Agent läuft wenigstens irgendwo hin.
            System.out.println("  Gespeicherte Zentralen-Adresse unlesbar: " + e.getMessage());
            return java.util.Optional.empty();
        }
    }

    /**
     * Setzt eine neue Adresse — <b>erst nach einer Probe</b>.
     *
     * <p>Ohne Probe könnte ein Tippfehler den Agenten dauerhaft von seiner
     * Zentrale abschneiden, und die einzige Stelle, an der man das korrigieren
     * kann, wäre wieder der Praxisrechner. Deshalb wird die neue Adresse zuerst
     * gefragt: Nur was mit {@code "status":"ok"} antwortet, wird übernommen.
     *
     * @return Fehlertext, oder {@code null} bei Erfolg
     */
    String setzen(String eingabe) {
        if (eingabe == null || eingabe.isBlank()) {
            return "Bitte eine Adresse angeben, zum Beispiel https://pin-roboter.example.de/";
        }
        String text = eingabe.trim();
        if (!text.endsWith("/")) text = text + "/";

        URI neu;
        try {
            neu = URI.create(text);
        } catch (IllegalArgumentException e) {
            return "Das ist keine gültige Adresse. Erwartet wird etwas wie "
                 + "https://pin-roboter.example.de/";
        }
        if (neu.getScheme() == null || !(neu.getScheme().equals("http")
                                      || neu.getScheme().equals("https"))) {
            return "Die Adresse muss mit http:// oder https:// beginnen.";
        }
        if (neu.getHost() == null || neu.getHost().isBlank()) {
            return "In der Adresse fehlt der Rechnername.";
        }

        String probe = erreichbar(neu);
        if (probe != null) return probe;

        try {
            Files.createDirectories(datei.getParent());
            Files.writeString(datei, neu.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "Die Adresse ließ sich nicht speichern: " + e.getMessage()
                 + " — ist das Datenverzeichnis beschreibbar?";
        }
        this.aktuell = neu;
        return null;
    }

    /**
     * Warum die Verbindung scheiterte — in Klartext.
     *
     * <p>Viele Netzwerkausnahmen tragen <b>keine</b> Meldung: Aus
     * {@code getMessage()} wird dann „null“, und die Anzeige lautete am
     * 13.08.2026 „… nicht erreichbar: null“. Der Klassenname sagt mehr, ist
     * aber Englisch und technisch — also übersetzt.
     */
    private static String grund(Exception e) {
        String art = e.getClass().getSimpleName();
        String text = e.getMessage();
        String klartext = switch (art) {
            case "UnknownHostException" ->
                    "Der Rechnername ist im Netz nicht auflösbar";
            case "ConnectException" ->
                    "Es kam keine Verbindung zustande — falscher Port, oder nichts läuft dort";
            case "HttpConnectTimeoutException", "ConnectTimeoutException" ->
                    "Keine Antwort innerhalb von 8 Sekunden";
            case "SSLHandshakeException" ->
                    "Die verschlüsselte Verbindung kam nicht zustande "
                    + "(passt das Zertifikat der Zentrale?)";
            case "SSLException" -> "Fehler in der verschlüsselten Verbindung";
            default -> null;
        };
        if (klartext == null) {
            return (text == null || text.isBlank()) ? art : text;
        }
        return (text == null || text.isBlank()) ? klartext : klartext + " (" + text + ")";
    }

    /**
     * Antwortet dort eine Zentrale?
     *
     * <p>Geprüft wird {@code /health} — dieselbe Schnittstelle, die auch das
     * Deploy-Skript benutzt. Sie braucht keine Anmeldung und verrät nichts.
     */
    private String erreichbar(URI adresse) {
        try {
            var http = Zentraleverbindung.client();
            var a = http.send(HttpRequest.newBuilder(adresse.resolve("/health"))
                            .timeout(Duration.ofSeconds(8)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (a.statusCode() != 200) {
                return "Unter dieser Adresse antwortet etwas, aber keine betriebsbereite "
                     + "Zentrale (HTTP " + a.statusCode() + ").";
            }
            if (!a.body().contains("\"status\":\"ok\"")) {
                return "Die Zentrale dort meldet sich als nicht betriebsbereit: " + a.body();
            }
            return null;
        } catch (Exception e) {
            return "Unter dieser Adresse ist keine Zentrale erreichbar: " + grund(e);
        }
    }
}
