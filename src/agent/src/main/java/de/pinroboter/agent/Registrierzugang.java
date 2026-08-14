package de.pinroboter.agent;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Holt die Zugangsdaten zur Programmablage — <b>nur im Moment einer
 * Aktualisierung</b> (PO, 14.08.2026).
 *
 * <h2>Warum sie nicht dauerhaft hier liegen</h2>
 *
 * Bis zum 14.08.2026 lagen sie ständig auf dem Praxisrechner, in einer Datei,
 * die der Aktualisierungsdienst lesen konnte. Nötig war das allein wegen des
 * nächtlichen Laufs: Um halb vier musste dieser Dienst allein an die Anmeldung
 * kommen, ohne dass jemand etwas anstößt.
 *
 * <p>Diesen Lauf gibt es nicht mehr. Aktualisiert wird nur auf Auftrag der
 * Zentrale — und damit werden die Zugangsdaten nur in dem Moment gebraucht, in
 * dem ein Mensch das Update ausgelöst hat.
 *
 * <pre>
 * Auftrag da  →  hier holen  →  in den Briefkasten legen  →  gelesen, gelöscht
 *                (nie gespeichert)                          (Sekunden später)
 * </pre>
 *
 * <p>Diese Klasse schreibt <b>nichts</b> auf die Platte. Sie fragt, gibt
 * weiter, und vergisst.
 *
 * <h2>Was das nicht leistet — ehrlich</h2>
 *
 * Wer in dem Moment, in dem ein Update läuft, Administrator des
 * Praxisrechners ist, kommt an den Token. Der Gewinn ist die <b>Zeit</b>:
 * Sekunden statt dauerhaft — und auf einem Rechner, an dem niemand
 * aktualisiert, überhaupt nichts.
 */
final class Registrierzugang {

    /**
     * Zugangsdaten für einen einzelnen Vorgang.
     *
     * <p>Der Token ist ein {@code char[]}, kein String: Ein String bleibt im
     * Zeichenvorrat liegen, bis der Speicher aufgeräumt wird, und lässt sich
     * nicht überschreiben. Dieselbe Überlegung wie bei den PINs (ADR-0009).
     */
    record Zugang(String registry, String benutzer, char[] token) {

        /** Überschreibt den Token. Nach Gebrauch aufzurufen. */
        void loeschen() { if (token != null) java.util.Arrays.fill(token, '\0'); }
    }

    private final HttpClient http;
    private final URI zentrale;

    /** Nur die Adresse der Programmablage — kein Geheimnis, taugt zur Anzeige. */
    private volatile String letzteRegistry;

    Registrierzugang(HttpClient http, URI zentrale) {
        this.http = http;
        this.zentrale = zentrale;
    }

    /** Für welche Programmablage zuletzt — ohne Benutzer und ohne Token. */
    String registry() { return letzteRegistry; }

    /**
     * Fragt die Zentrale nach den Zugangsdaten.
     *
     * <p>Sie gibt sie nur heraus, wenn für diesen Agenten ein Auftrag vorliegt
     * <em>und</em> die Weitergabe eingeschaltet ist. Ein Agent, der ohne Anlass
     * fragt, bekommt nichts.
     *
     * @return die Zugangsdaten, oder {@code null} — dann steht der Grund in
     *         {@link #letzterFehler()}
     */
    Zugang holen(String agentToken) {
        fehler = null;
        String antwort;
        try {
            var a = http.send(HttpRequest.newBuilder(zentrale.resolve("/api/agent/registry"))
                            .header("X-Agent-Token", agentToken)
                            .timeout(Duration.ofSeconds(10)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (a.statusCode() == 403) {
                fehler = "Die Zentrale gibt für diesen Agenten keine Zugangsdaten heraus. "
                       + "Liegt dort wirklich ein Auftrag vor?";
                return null;
            }
            if (a.statusCode() != 200) {
                fehler = "Die Zentrale antwortete mit HTTP " + a.statusCode() + ".";
                return null;
            }
            antwort = a.body();
        } catch (Exception e) {
            fehler = "Die Zentrale ist nicht erreichbar: "
                   + de.pinroboter.gemeinsam.Netzfehler.klartext(e);
            return null;
        }

        if (!antwort.contains("\"verfuegbar\":true")) {
            fehler = "Die Weitergabe der Zugangsdaten ist in der Zentrale abgeschaltet. "
                   + "Unter „Instanz → Produktversion“ lässt sie sich einschalten.";
            return null;
        }
        String registry = wert(antwort, "registry");
        String benutzer = wert(antwort, "benutzer");
        String token    = wert(antwort, "token");
        if (registry.isBlank() || benutzer.isBlank() || token.isBlank()) {
            fehler = "Die Zentrale hat unvollständige Zugangsdaten geliefert.";
            return null;
        }
        letzteRegistry = registry;
        return new Zugang(registry, benutzer, token.toCharArray());
    }

    private volatile String fehler;

    /** Warum das letzte Holen scheiterte. */
    String letzterFehler() { return fehler; }

    /** Ein Feld aus der Antwort. Kein JSON-Werkzeug für drei Zeichenketten. */
    private static String wert(String json, String feld) {
        var m = java.util.regex.Pattern.compile("\"" + feld + "\"\\s*:\\s*\"([^\"]*)\"")
                .matcher(json);
        return m.find() ? m.group(1) : "";
    }
}
