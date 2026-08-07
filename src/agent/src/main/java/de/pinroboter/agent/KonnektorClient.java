package de.pinroboter.agent;

import de.pinroboter.gemeinsam.PinErgebnis;
import de.pinroboter.gemeinsam.PinStatus;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Zugriff auf die Außenschnittstelle des Konnektors (Basisanwendung Kartendienst).
 *
 * <p>Der Client ist von Hand geschrieben statt erzeugt. Bei vier Operationen ist
 * das überschaubar, und es hält den Aufruf, der über eine PIN entscheidet,
 * lesbar — das war die Vorgabe aus ADR-0006. Jede Operation trägt hier die
 * Anforderungs-ID, gegen die sie gebaut ist.
 */
public class KonnektorClient {

    private final HttpClient http;
    private final URI endpunkt;
    private final Aufrufkontext kontext;

    /**
     * Aufrufkontext nach [gemSpec_Kon] {@code TUC_KON_000}. Diese drei Werte
     * stammen aus der Konfiguration des Konnektors. Von Hand abgetippt sind sie
     * die häufigste Fehlerquelle bei der Inbetriebnahme, weil ein Tippfehler
     * als unspezifischer Zugriffsfehler erscheint — deshalb sieht FA-GER-035
     * vor, sie aus dem Informationsmodell zur Auswahl anzubieten.
     */
    public record Aufrufkontext(String mandantId, String clientSystemId, String workplaceId) {
        public String alsXml() {
            return """
                    <Context><MandantId>%s</MandantId><ClientSystemId>%s</ClientSystemId>
                    <WorkplaceId>%s</WorkplaceId></Context>"""
                    .formatted(mandantId, clientSystemId, workplaceId);
        }
    }

    public record Terminal(String id, String name, String modell, String firmware,
                           boolean erreichbar, boolean pevimUnterstuetzt, boolean pevimAktiv) {}

    public record Karte(String kartenhandle, String kartentyp, String iccsn, String terminalId) {}

    public record StatusAntwort(PinStatus status, int verbleibendeVersuche, boolean aktualisierend) {}

    public record VerifyAntwort(PinErgebnis ergebnis, int verbleibendeVersuche,
                                String fehlercode, String fehlertext) {
        public boolean erfolgreich() { return ergebnis == PinErgebnis.OK; }
    }

    public KonnektorClient(HttpClient http, URI endpunkt, Aufrufkontext kontext) {
        this.http = http; this.endpunkt = endpunkt; this.kontext = kontext;
    }

    /** [gemSpec_Kon] GetCardTerminals — Bestandsermittlung (FA-GER-030). */
    public List<Terminal> terminals() throws Exception {
        String a = ruf("<GetCardTerminals>" + kontext.alsXml() + "</GetCardTerminals>");
        List<Terminal> ergebnis = new ArrayList<>();
        var m = Pattern.compile("<CardTerminal>(.*?)</CardTerminal>", Pattern.DOTALL).matcher(a);
        while (m.find()) {
            String b = m.group(1);
            ergebnis.add(new Terminal(tag(b, "CtId"), tag(b, "Name"), tag(b, "Product"),
                    tag(b, "FirmwareVersion"), bool(b, "Connected"),
                    bool(b, "PevimSupported"), bool(b, "PevimActive")));
        }
        return ergebnis;
    }

    /** [gemSpec_Kon] GetCards — Karten je Terminal (FA-GER-040). */
    public List<Karte> karten() throws Exception {
        String a = ruf("<GetCards>" + kontext.alsXml() + "</GetCards>");
        List<Karte> ergebnis = new ArrayList<>();
        var m = Pattern.compile("<Card>(.*?)</Card>", Pattern.DOTALL).matcher(a);
        while (m.find()) {
            String b = m.group(1);
            ergebnis.add(new Karte(tag(b, "CardHandle"), tag(b, "CardType"),
                    tag(b, "Iccsn"), tag(b, "CtId")));
        }
        return ergebnis;
    }

    /**
     * [gemSpec_Kon] TIP1-A_4589 GetPinStatus — Grundlage der Überwachung
     * (FA-UEB-010). Für eine SM-B ist {@code PinTyp = PIN.SMC}.
     */
    public StatusAntwort pinStatus(String kartenhandle) throws Exception {
        String a = ruf("""
                <GetPinStatus>%s<CardHandle>%s</CardHandle><PinTyp>PIN.SMC</PinTyp></GetPinStatus>"""
                .formatted(kontext.alsXml(), kartenhandle));

        String fehler = tag(a, "Code");
        if (!fehler.isEmpty()) return new StatusAntwort(PinStatus.UNBEKANNT, -1, false);

        String s = tag(a, "PinStatus");
        PinStatus status;
        try { status = PinStatus.valueOf(s); } catch (Exception e) { status = PinStatus.UNBEKANNT; }
        int links = zahl(tag(a, "LeftTries"), -1);
        return new StatusAntwort(status, links, "true".equals(tag(a, "Updating")));
    }

    /**
     * [gemSpec_Kon] TIP1-A_4587 VerifyPin — stößt die Verifikation an.
     *
     * <p><b>Diese Operation darf nur nach bestandener {@link Sicherungspruefung}
     * aufgerufen werden.</b> Sie verbraucht im Fehlerfall einen Versuch der
     * Karte; drei davon sperren sie.
     */
    public VerifyAntwort verifyPin(String kartenhandle) throws Exception {
        String a = ruf("""
                <VerifyPin>%s<CardHandle>%s</CardHandle><PinTyp>PIN.SMC</PinTyp></VerifyPin>"""
                .formatted(kontext.alsXml(), kartenhandle));

        String code = tag(a, "Code");
        if (!code.isEmpty()) {
            return new VerifyAntwort(PinErgebnis.ERROR, -1, code, tag(a, "Text"));
        }
        PinErgebnis e;
        try { e = PinErgebnis.valueOf(tag(a, "PinResult")); }
        catch (Exception ex) { e = PinErgebnis.ERROR; }
        return new VerifyAntwort(e, zahl(tag(a, "LeftTries"), -1), null, null);
    }

    /**
     * Liest das Informationsmodell über die Webadmin-Schnittstelle (FA-GER-035).
     *
     * <p><b>Diese Schnittstelle ist herstellerspezifisch und von niemandem
     * spezifiziert.</b> Sie ist deshalb <b>immer optional</b> (NFA-QS-035): Ist
     * sie nicht erreichbar oder nicht konfiguriert, bleibt jede Angabe von Hand
     * erfassbar. Ein Fehler hier darf nichts anhalten — er wird gemeldet, und
     * die Einrichtung läuft ohne die Unterstützung weiter.
     *
     * @param webadmin Basisadresse der Webadmin-Schnittstelle, oder {@code null}
     * @return das Modell als JSON, oder {@code null}, wenn es nicht abrufbar ist
     */
    public String informationsmodell(URI webadmin) {
        if (webadmin == null) return null;
        try {
            HttpRequest r = HttpRequest.newBuilder(webadmin.resolve("/webadmin/informationsmodell"))
                    .timeout(Duration.ofSeconds(15)).GET().build();
            var a = http.send(r, HttpResponse.BodyHandlers.ofString());
            return a.statusCode() == 200 ? a.body() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String ruf(String inhalt) throws Exception {
        String umschlag = """
                <?xml version="1.0" encoding="UTF-8"?>
                <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
                  <soap:Body>%s</soap:Body></soap:Envelope>""".formatted(inhalt);

        HttpRequest r = HttpRequest.newBuilder(endpunkt)
                .header("Content-Type", "text/xml; charset=utf-8")
                .timeout(Duration.ofSeconds(45))
                .POST(HttpRequest.BodyPublishers.ofString(umschlag))
                .build();
        return http.send(r, HttpResponse.BodyHandlers.ofString()).body();
    }

    private static String tag(String xml, String name) {
        var m = Pattern.compile("<" + name + ">([^<]*)</" + name + ">").matcher(xml);
        return m.find() ? m.group(1).trim() : "";
    }

    private static boolean bool(String xml, String name) { return "true".equals(tag(xml, name)); }

    private static int zahl(String s, int vorgabe) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return vorgabe; }
    }
}
