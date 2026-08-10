package de.pinroboter.agent;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.regex.Pattern;

/**
 * Zugriff auf die netzwerkbasierte Managementschnittstelle eines Kartenterminals.
 *
 * <p>Diese Schnittstelle ist <b>nicht</b> von der gematik spezifiziert — die
 * Hersteller lösen sie jeweils eigen. Deshalb ist sie hinter einer einheitlichen
 * Sicht gekapselt (Architekturkonzept 5.4): Ein weiterer Hersteller ist eine
 * neue Umsetzung dieser Klasse, kein Eingriff in die Ablaufsteuerung.
 *
 * <p>Die hier umgesetzte Variante orientiert sich an der Weboberfläche des
 * CHERRY ST-1506. Am Testaufbau ist zu prüfen, ob eine Anmeldung per
 * TLS-Client-Zertifikat möglich ist — [gemSpec_KT] {@code A_24065}, Hinweis 2
 * lässt das zu, und es würde das Terminal-Kennwort als vorzuhaltendes Geheimnis
 * ersparen.
 */
public class TerminalClient implements AutoCloseable {

    private final HttpClient http;
    private final URI basis;
    private final String terminalId;
    private volatile String sitzung;

    public TerminalClient(HttpClient http, URI basis, String terminalId) {
        this.http = http; this.basis = basis; this.terminalId = terminalId;
    }

    /**
     * Meldet die Rolle {@code SMC-B_Nutzer} an ([gemSpec_KT] A_24075).
     *
     * <p>Muss <b>vor</b> dem {@code VerifyPin} geschehen: A_24081 verlangt, dass
     * die Rolle bereits authentisiert ist, wenn das SICCT-Kommando eintrifft.
     * Die Reihenfolge ist Teil der Spezifikation, nicht der Bequemlichkeit.
     */
    public Anmeldeergebnis anmelden() throws Exception {
        HttpResponse<String> a = post("/api/" + terminalId + "/anmelden", "{}", false);
        if (a.statusCode() == 200) {
            sitzung = feld(a.body(), "sitzung");
            return new Anmeldeergebnis(true, null, null);
        }
        return new Anmeldeergebnis(false, feld(a.body(), "fehler"), feld(a.body(), "hinweis"));
    }

    /**
     * Holt einen Authentisierungsschlüssel für die Remote SMCB API
     * ({@code SmcbAuthentication}, ST-1506-Spezifikation 2.8 Abschnitt 4.38).
     *
     * <p>Der Schlüssel gilt <b>30 Sekunden</b> und taugt für <b>genau einen</b>
     * CMAC. Er wird deshalb unmittelbar vor dem Verbindungsaufbau geholt,
     * niemals auf Vorrat.
     *
     * <p><b>Abweichung im Simulator:</b> Am echten Gerät wird der Schlüssel über
     * das WebSocket-Unterprotokoll {@code cobra} angefordert. Der Simulator
     * bietet ihn zusätzlich über diesen HTTP-Endpunkt an, weil das Verwalten
     * einer zweiten WebSocket-Verbindung für den Nachweis der PIN-Übergabe
     * nichts beiträgt. Diese Klasse ist die Stelle, an der die Umstellung auf
     * {@code cobra} später zu machen ist — und nur hier.
     *
     * @return 64 Hexzeichen, oder {@code null} wenn das Gerät keinen ausgibt
     */
    public String smcbSchluessel() throws Exception {
        HttpResponse<String> a = post("/api/" + terminalId + "/smcb-schluessel", "{}", false);
        if (a.statusCode() != 200) return null;
        String k = feld(a.body(), "key");
        return k != null && k.matches("[0-9a-fA-F]{64}") ? k : null;
    }

    /**
     * Liefert den PIN-Wert an eine offene Aufforderung des Terminals.
     *
     * @deprecated Dieses Muster gibt es an echten Kartenterminals nicht
     *     (ADR-0011). Die PIN wird nicht hingeschoben, sondern auf Anfrage des
     *     Terminals über {@link SmcbVerbindung} geliefert. Die Methode bleibt
     *     nur, um im Nachweis ein Gerät <i>ohne</i> Remote SMCB API
     *     gegenzuprüfen; im Betrieb ruft sie niemand mehr auf.
     */
    @Deprecated
    public boolean pinLiefern(char[] pin) throws Exception {
        if (sitzung == null) throw new IllegalStateException("Keine Sitzung als SMC-B_Nutzer");
        String koerper = "{\"pin\":\"" + new String(pin) + "\"}";
        HttpResponse<String> a = post("/api/" + terminalId + "/pin", koerper, true);
        return a.statusCode() == 200;
    }

    /**
     * Hinterlegt alte und neue PIN an der Managementschnittstelle (FA-PIN-060).
     *
     * <p><b>Das SICCT-Kommando sendet der Konnektor, nicht wir.</b> Dieser
     * Aufruf legt die Werte bereit; ausgelöst wird die Änderung über die
     * SOAP-Schnittstelle des Konnektors. Das Terminal entscheidet dann nach
     * {@code A_24082}, ob es die Werte von hier nimmt oder ein PIN-Pad
     * verlangt — deshalb muss diese Sitzung stehen, BEVOR das Kommando
     * eintrifft.
     *
     * <p><b>Auch das Ändern kann die Karte sperren.</b> Stimmt die alte PIN
     * nicht, zählt der Fehlversuchszähler herunter — genau wie beim
     * Verifizieren. Der Aufrufer muss deshalb dieselbe Sicherungsprüfung
     * vorschalten (Sicherungspruefung, FA-PIN-030): erst prüfen, wie viele
     * Versuche übrig sind, und bei Unsicherheit abbrechen statt es zu wagen.
     *
     * @return das Ergebnis samt verbleibender Versuche
     */
    public Aenderungsergebnis pinAendern(String kartenhandle, char[] alt, char[] neu)
            throws Exception {
        if (sitzung == null) throw new IllegalStateException("Keine Sitzung als SMC-B_Nutzer");
        String koerper = "{\"kartenhandle\":\"" + kartenhandle + "\",\"alt\":\""
                + new String(alt) + "\",\"neu\":\"" + new String(neu) + "\"}";
        HttpResponse<String> a = post("/api/" + terminalId + "/pin-aendern", koerper, true);
        int uebrig = -1;
        try { uebrig = Integer.parseInt(feld(a.body(), "verbleibendeVersuche")); }
        catch (Exception ignored) { }
        boolean ok = a.statusCode() == 200 && "true".equals(feld(a.body(), "geaendert"));
        return new Aenderungsergebnis(ok, uebrig,
                ok ? null : feldOderStandard(a.body(), "fehler",
                        "Das Kartenterminal hat die Änderung abgelehnt."));
    }

    public record Aenderungsergebnis(boolean geklappt, int verbleibendeVersuche, String fehler) {}

    private static String feldOderStandard(String json, String name, String vorgabe) {
        String w = feld(json, name);
        return w == null || w.isBlank() ? vorgabe : w;
    }

    public boolean wartetAufPin() throws Exception {
        HttpResponse<String> a = get("/api/" + terminalId + "/status");
        return a.statusCode() == 200 && "true".equals(feld(a.body(), "wartetAufPin"));
    }

    @Override public void close() {
        try {
            if (sitzung != null) post("/api/" + terminalId + "/abmelden", "{}", true);
        } catch (Exception ignored) {
        } finally { sitzung = null; }
    }

    public record Anmeldeergebnis(boolean erfolgreich, String fehler, String hinweis) {}

    private HttpResponse<String> post(String pfad, String koerper, boolean mitSitzung) throws Exception {
        var b = HttpRequest.newBuilder(basis.resolve(pfad))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(koerper));
        if (mitSitzung && sitzung != null) b.header("X-Sitzung", sitzung);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String pfad) throws Exception {
        var b = HttpRequest.newBuilder(basis.resolve(pfad)).timeout(Duration.ofSeconds(15)).GET();
        if (sitzung != null) b.header("X-Sitzung", sitzung);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String feld(String json, String name) {
        var m = Pattern.compile("\"" + name + "\"\\s*:\\s*\"?([^\",}]*)\"?").matcher(json);
        return m.find() ? m.group(1) : null;
    }
}
