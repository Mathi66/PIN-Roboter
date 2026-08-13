package de.pinroboter.agent;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;

/**
 * Holt die Zugangsdaten zur Programmablage von der Zentrale und legt sie so ab,
 * dass die selbsttätige Aktualisierung sie benutzen kann (PO, 13.08.2026).
 *
 * <h2>Warum nicht einfach in die .env</h2>
 *
 * Die Programmablage ist privat; ohne Zugangsdaten kommt kein Agent an sein
 * Abbild. Sie in jedes Einrichtungspaket zu schreiben hieße: derselbe Token in
 * jeder Praxis, sichtbar in {@code docker inspect} und in der Prozessliste,
 * tauschbar nur durch einen Besuch vor Ort.
 *
 * <p>Die Zentrale kennt den Token ohnehin — sie prüft damit selbst auf neue
 * Versionen. Sie gibt ihn über die bereits bestehende, verschlüsselte
 * Verbindung an <b>registrierte</b> Agenten weiter. Damit gibt es eine Stelle
 * zum Pflegen, und ein widerrufener Agent bekommt nichts mehr.
 *
 * <h2>Was das nicht leistet — ehrlich</h2>
 *
 * Der Token landet trotzdem auf dem Praxisrechner; anders kann Watchtower ihn
 * nicht benutzen. Der Gewinn ist nicht Unsichtbarkeit, sondern
 * <b>Austauschbarkeit</b>: eine Stelle statt hundertfünfzig.
 *
 * <h2>Die Datei</h2>
 *
 * Docker-Format, wie {@code docker login} sie schreibt. Watchtower bekommt das
 * Verzeichnis über {@code DOCKER_CONFIG} und liest sie <b>nur</b>.
 *
 * <p>Bleibt die Antwort der Zentrale leer — Weitergabe abgeschaltet oder kein
 * Zugang eingerichtet —, wird die Datei <b>gelöscht</b>. Genau das ist der
 * Schalter: Ohne Anmeldedaten holt Watchtower nichts mehr.
 */
final class Registrierzugang {

    private final Path datei;
    private final HttpClient http;
    private final URI zentrale;

    /** Zuletzt geschriebener Stand — spart das Neuschreiben bei jedem Takt. */
    private volatile String letzterStand = "";

    /** Nur die Adresse der Programmablage — kein Geheimnis, taugt zur Anzeige. */
    private volatile String letzteRegistry;

    Registrierzugang(Path verzeichnis, HttpClient http, URI zentrale) {
        this.datei = verzeichnis.resolve("config.json");
        this.http = http;
        this.zentrale = zentrale;
    }

    /**
     * Holt den Stand von der Zentrale und schreibt ihn fort.
     *
     * @return kurze Meldung, wenn sich etwas geändert hat — sonst {@code null}
     */
    String abgleichen(String agentToken) {
        String antwort;
        try {
            var a = http.send(HttpRequest.newBuilder(zentrale.resolve("/api/agent/registry"))
                            .header("X-Agent-Token", agentToken)
                            .timeout(Duration.ofSeconds(10)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (a.statusCode() != 200) return null;   // ältere Zentrale kennt den Pfad nicht
            antwort = a.body();
        } catch (Exception e) {
            // Kein Grund zur Beunruhigung: Die Zentrale ist gerade nicht
            // erreichbar. Der vorhandene Stand bleibt, wie er ist.
            return null;
        }

        if (!antwort.contains("\"verfuegbar\":true")) {
            return entfernen();
        }
        String registry = wert(antwort, "registry");
        String benutzer = wert(antwort, "benutzer");
        String token    = wert(antwort, "token");
        if (registry.isBlank() || benutzer.isBlank() || token.isBlank()) return entfernen();

        return schreiben(registry, benutzer, token);
    }

    private String schreiben(String registry, String benutzer, String token) {
        String auth = Base64.getEncoder().encodeToString(
                (benutzer + ":" + token).getBytes(StandardCharsets.UTF_8));
        String inhalt = """
                {"auths":{"%s":{"auth":"%s"}}}""".formatted(registry, auth);

        // Vergleich über den Inhalt, nicht über den Token: So steht nirgends
        // ein Geheimnis im Speicher, das nicht ohnehin gleich in die Datei geht.
        if (inhalt.equals(letzterStand)) return null;
        try {
            Files.createDirectories(datei.getParent());
            Files.writeString(datei, inhalt, StandardCharsets.UTF_8);
            // Nur der Eigentümer. Watchtower läuft im selben Volume und liest
            // als root — andere Benutzer auf dem Host gehen es nichts an.
            try {
                Files.setPosixFilePermissions(datei,
                        java.nio.file.attribute.PosixFilePermissions.fromString("rw-r-----"));
            } catch (UnsupportedOperationException ignoriert) {
                // Windows kennt keine POSIX-Rechte. Kein Grund abzubrechen.
            }
            letzterStand = inhalt;
            letzteRegistry = registry;
            return "Zugangsdaten für die Aktualisierung von der Zentrale übernommen";
        } catch (Exception e) {
            return "Zugangsdaten für die Aktualisierung nicht ablegbar: " + e.getMessage();
        }
    }

    private String entfernen() {
        if (letzterStand.isEmpty() && !Files.exists(datei)) return null;
        try {
            Files.deleteIfExists(datei);
            letzterStand = "";
            letzteRegistry = null;
            return "Selbsttätige Aktualisierung ist in der Zentrale abgeschaltet — "
                 + "die Zugangsdaten wurden entfernt";
        } catch (Exception e) {
            return "Zugangsdaten ließen sich nicht entfernen: " + e.getMessage();
        }
    }

    /** Liegt eine verwendbare Anmeldung auf der Platte? Für die Anzeige. */
    boolean bereit() { return !letzterStand.isEmpty() && Files.exists(datei); }

    /** Für welche Programmablage — ohne Benutzer und ohne Token. */
    String registry() { return letzteRegistry; }

    /** Ein Feld aus der Antwort. Kein JSON-Werkzeug für drei Zeichenketten. */
    private static String wert(String json, String feld) {
        var m = java.util.regex.Pattern.compile("\"" + feld + "\"\\s*:\\s*\"([^\"]*)\"")
                .matcher(json);
        return m.find() ? m.group(1) : "";
    }
}
