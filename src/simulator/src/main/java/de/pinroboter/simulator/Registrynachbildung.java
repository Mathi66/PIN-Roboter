package de.pinroboter.simulator;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Nachbildung der OCI-Registry-Schnittstelle für die Nachweise (FA-UPD-010).
 *
 * <h2>Warum im Simulator</h2>
 *
 * Der echte Weg führt zu <b>GHCR</b> — also ins Internet. Ein Prüflauf, der
 * Internetzugang braucht, ist als Nachweis unbrauchbar: Er wird rot, wenn das
 * Netz klemmt, und sagt dann nichts über das Produkt. Dieselbe Überlegung wie
 * beim Mailauffang (FA-ALA-020), der zuerst MailHog verwendete und daran
 * scheiterte.
 *
 * <p>Nachgebildet ist genau der Ausschnitt, den ADR-0001 nennt: die
 * <b>Auflistung der Tags</b> und die Prüfung des Zugangs. Kein Push, keine
 * Manifeste, keine Schichten.
 *
 * <h2>Was hier absichtlich prüfbar ist</h2>
 *
 * Die Akzeptanz von FA-UPD-010 verlangt, dass <b>ungültige oder abgelaufene
 * Zugangsdaten als sichtbarer Fehlerzustand erscheinen</b> — nicht als
 * ausbleibende Meldung. Deshalb kennt diese Nachbildung drei Zustände:
 *
 * <ul>
 *   <li>gültiges Token → Tagliste</li>
 *   <li>falsches Token → {@code 401}, wie eine echte Registry</li>
 *   <li>über {@code /registry/abgelaufen} umschaltbar → dauerhaft {@code 401}</li>
 * </ul>
 */
public class Registrynachbildung implements HttpHandler {

    /** Das im Testaufbau gültige Token. Kein Geheimnis — es steht hier im Quelltext. */
    public static final String GUELTIGES_TOKEN = "ghp-testaufbau-1234567890";

    /** Umschaltbar, um den Ablauf von Zugangsdaten zu prüfen. */
    private static volatile boolean abgelaufen = false;

    public static void abgelaufenSetzen(boolean an) { abgelaufen = an; }

    /**
     * Die Versionen, die der Testaufbau anbietet.
     *
     * <p>Absteigend sortiert wie bei einer echten Registry nicht garantiert —
     * die Sortierung ist Sache des Aufrufers, und genau das soll geprüft
     * werden.
     */
    private static final String TAGS = """
            {"name":"nieting/pin-roboter","tags":["0.9.0","1.0.0","1.0.1","1.1.0","latest"]}""";

    /**
     * Die Versionen des <b>Agent-Abbilds</b> (FA-AGT-100).
     *
     * <p>Zentrale und Agent sind zwei getrennte Abbilder in derselben Ablage;
     * der Name des Agenten ergibt sich aus dem der Zentrale mit dem Zusatz
     * {@code -agent}. Ohne diese Nachbildung ließe sich die gezielte
     * Aktualisierung eines Standorts nicht am Testaufbau prüfen — und ein
     * Vorgang, der einen Standort für eine Minute aus der Überwachung nimmt,
     * gehört geprüft.
     *
     * <p>Bewusst ein <b>anderer</b> Satz als oben: So fällt auf, wenn die
     * Zentrale versehentlich ihre eigenen Versionen für den Agenten anbietet.
     */
    private static final String AGENT_TAGS = """
            {"name":"nieting/pin-roboter-agent","tags":["0.9.0","1.0.0","1.2.0","stabil"]}""";

    /**
     * Änderungshinweise auf Deutsch, wie sie im Repository liegen.
     *
     * <p>Eine echte Registry liefert das nicht. In der Auslieferung kommen sie
     * aus einer Datei neben den Images; hier ist derselbe Inhalt unter
     * {@code /registry/hinweise} abrufbar, damit der Nachweis den ganzen Weg
     * prüfen kann.
     *
     * <p>{@code sicherheit} steuert die Hervorhebung — ein Sicherheitsupdate
     * muss sich von einer Verbesserung unterscheiden lassen, ohne den Text zu
     * lesen.
     */
    private static final String HINWEISE = """
            {"versionen":[
              {"version":"1.1.0","sicherheit":false,
               "text":"Geräte-Zeitstrahl und Vorgangsansicht. PIN über die Oberfläche änderbar."},
              {"version":"1.0.1","sicherheit":true,
               "text":"Sicherheitskorrektur: Der Geltungsbereich wird beim Quittieren von Alarmen geprüft."},
              {"version":"1.0.0","sicherheit":false,
               "text":"Erste Fassung für den Produktivbetrieb."},
              {"version":"0.9.0","sicherheit":false,
               "text":"Vorabfassung für den Testaufbau."}
            ]}""";

    @Override
    public void handle(HttpExchange ex) throws IOException {
        String pfad = ex.getRequestURI().getPath();

        if (pfad.endsWith("/abgelaufen")) {
            String q = ex.getRequestURI().getQuery();
            abgelaufenSetzen(q == null || !q.contains("nein"));
            TerminalVerwaltung.antworte(ex, 200, "{\"abgelaufen\":" + abgelaufen + "}");
            return;
        }
        if (pfad.endsWith("/hinweise")) {
            TerminalVerwaltung.antworte(ex, 200, HINWEISE);
            return;
        }

        // Wie eine echte Registry: Basic-Auth mit Benutzer und Token.
        String kopf = ex.getRequestHeaders().getFirst("Authorization");
        if (abgelaufen || !zugangGueltig(kopf)) {
            ex.getResponseHeaders().add("WWW-Authenticate", "Basic realm=\"pin-roboter\"");
            TerminalVerwaltung.antworte(ex, 401,
                    "{\"errors\":[{\"code\":\"UNAUTHORIZED\",\"message\":\"authentication required\"}]}");
            return;
        }
        // Welches Abbild ist gemeint? Der Pfad lautet
        // /registry/v2/<abbild>/tags/list — und <abbild> enthält selbst einen
        // Schrägstrich (nieting/pin-roboter).
        TerminalVerwaltung.antworte(ex, 200, pfad.contains("-agent/") ? AGENT_TAGS : TAGS);
    }

    private static boolean zugangGueltig(String kopf) {
        if (kopf == null || !kopf.startsWith("Basic ")) return false;
        try {
            String klar = new String(Base64.getDecoder().decode(kopf.substring(6)),
                    StandardCharsets.UTF_8);
            int i = klar.indexOf(':');
            return i > 0 && GUELTIGES_TOKEN.equals(klar.substring(i + 1));
        } catch (Exception e) {
            return false;
        }
    }
}
