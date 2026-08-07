package de.pinroboter.simulator;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Simuliert die Außenschnittstelle des Konnektors (Basisanwendung Kartendienst).
 *
 * <p>Nachgebildet sind die Operationen aus [gemSpec_Kon] 4.1.5.5:
 * {@code GetCardTerminals}, {@code GetCards}, {@code GetPinStatus} und
 * {@code VerifyPin}. Das XML ist bewusst schlicht gehalten — es geht darum, den
 * Ablauf und die Zustandsübergänge realistisch abzubilden, nicht darum, eine
 * WSDL bis ins letzte Attribut nachzubauen.
 *
 * <p><b>Der Kern ist {@code VerifyPin}:</b> Der Konnektor sendet daraufhin ein
 * SICCT PERFORM VERIFICATION an das Terminal, und erst dort entscheidet sich
 * nach A_24081, ob der PIN-Wert über die Managementschnittstelle entgegen-
 * genommen wird oder das (hier nicht bediente) PIN-Pad erwartet.
 */
public class KonnektorSoap implements HttpHandler {

    private final Zustand zustand;
    private static final Pattern P_CARDHANDLE = Pattern.compile("CardHandle>([^<]+)<");
    private static final Pattern P_PINTYP = Pattern.compile("PinTyp>([^<]+)<");

    public KonnektorSoap(Zustand zustand) { this.zustand = zustand; }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        String koerper = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String antwort;
        int code = 200;
        try {
            if (koerper.contains("GetCardTerminals"))       antwort = getCardTerminals();
            else if (koerper.contains("GetCards"))          antwort = getCards();
            else if (koerper.contains("GetPinStatus"))      antwort = getPinStatus(koerper);
            else if (koerper.contains("VerifyPin"))         antwort = verifyPin(koerper);
            else { antwort = fehler("4000", "Syntaxfehler: unbekannte Operation"); code = 400; }
        } catch (Exception e) {
            antwort = fehler("4000", "Fehler im Simulator: " + e.getMessage());
            code = 500;
        }
        byte[] b = antwort.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "text/xml; charset=utf-8");
        ex.sendResponseHeaders(code, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    private String getCardTerminals() {
        StringBuilder sb = new StringBuilder();
        for (SimuliertesTerminal t : zustand.terminals()) {
            sb.append("""
                    <CardTerminal>
                      <CtId>%s</CtId><Name>%s</Name><Product>%s</Product>
                      <FirmwareVersion>%s</FirmwareVersion><Connected>%s</Connected>
                      <PevimSupported>%s</PevimSupported><PevimActive>%s</PevimActive>
                    </CardTerminal>
                    """.formatted(t.id(), t.name(), t.modell(), t.firmware(),
                    t.erreichbar(), t.pevimUnterstuetzt(), t.pevimAktiv()));
        }
        return umschlag("<GetCardTerminalsResponse>" + sb + "</GetCardTerminalsResponse>");
    }

    private String getCards() {
        StringBuilder sb = new StringBuilder();
        for (SimuliertesTerminal t : zustand.terminals()) {
            for (SimulierteKarte k : t.alleKarten()) {
                sb.append("""
                        <Card><CardHandle>%s</CardHandle><CardType>%s</CardType>
                          <Iccsn>%s</Iccsn><CtId>%s</CtId>
                          <CertificateExpirationDate>%s</CertificateExpirationDate></Card>
                        """.formatted(k.kartenhandle(), k.kartentyp(), k.iccsn(), t.id(),
                        k.zertifikatGueltigBis()));
            }
        }
        return umschlag("<GetCardsResponse>" + sb + "</GetCardsResponse>");
    }

    private String getPinStatus(String anfrage) throws Exception {
        String handle = wert(P_CARDHANDLE, anfrage);
        var gefunden = zustand.findeKarte(handle);
        if (gefunden.isEmpty()) return fehler("4209", "Unbekanntes CardHandle: " + handle);

        SimuliertesTerminal t = gefunden.get().terminal();
        SimulierteKarte k = gefunden.get().karte();
        if (!t.erreichbar()) return fehler("4047", "Kartenterminal nicht erreichbar");
        verzoegern(t);

        // Ein laufendes Firmware-Update wird als eigener Zustand gemeldet.
        // Der Agent MUSS in dieser Zeit auf Verifikationsversuche verzichten
        // (FA-UEB-050) — sonst belastet er den Fehlversuchszähler.
        String aktualisierend = t.aktualisierend() ? "true" : "false";

        return umschlag("""
                <GetPinStatusResponse>
                  <PinStatus>%s</PinStatus><LeftTries>%d</LeftTries>
                  <Updating>%s</Updating>
                </GetPinStatusResponse>
                """.formatted(k.status().name(), k.verbleibendeVersuche(), aktualisierend));
    }

    private String verifyPin(String anfrage) throws Exception {
        String handle = wert(P_CARDHANDLE, anfrage);
        String pinTyp = wert(P_PINTYP, anfrage);

        var gefunden = zustand.findeKarte(handle);
        if (gefunden.isEmpty()) return fehler("4209", "Unbekanntes CardHandle: " + handle);

        SimuliertesTerminal t = gefunden.get().terminal();
        SimulierteKarte k = gefunden.get().karte();

        // A_24080 / TAB_KON_047: eGK wird von dieser Operation nicht unterstützt.
        if ("EGK".equalsIgnoreCase(k.kartentyp())) {
            return fehler("4209", "Kartentyp eGK wird durch diese Operation nicht unterstützt.");
        }
        if (!"PIN.SMC".equals(pinTyp)) {
            return fehler("4000", "Für eine SM-B ist PinTyp=PIN.SMC zu verwenden, erhalten: " + pinTyp);
        }
        if (!t.erreichbar())   return fehler("4047", "Kartenterminal nicht erreichbar");
        if (t.aktualisierend()) return fehler("4047", "Kartenterminal wird gerade aktualisiert");
        verzoegern(t);

        // Prüfkette A_24081: Nimmt das Terminal den PIN-Wert über die
        // Managementschnittstelle entgegen — oder erwartet es das PIN-Pad?
        if (!t.pevimGreift(k)) {
            // Kein PIN-Pad besetzt: In der Wirklichkeit stünde hier ein Mensch.
            // 4078 ist der Fehler, den der Konnektor für „PIN-Eingabe über das
            // Clientsystem ist nicht zugelassen" vorsieht.
            return fehler("4078", "PIN-Eingabe über das Clientsystem ist nicht zugelassen");
        }

        String pin = t.pinAnfordern(handle, 30);
        if (pin == null) {
            return fehler("4047", "Zeitüberschreitung: kein PIN-Wert über die Managementschnittstelle erhalten");
        }

        SimulierteKarte.Pruefergebnis erg = k.pruefe(pin);
        return umschlag("""
                <VerifyPinResponse>
                  <PinResult>%s</PinResult><LeftTries>%d</LeftTries>
                </VerifyPinResponse>
                """.formatted(erg.ergebnis(), erg.verbleibendeVersuche()));
    }

    private void verzoegern(SimuliertesTerminal t) throws InterruptedException {
        if (t.antwortverzoegerungMs() > 0) Thread.sleep(t.antwortverzoegerungMs());
    }

    private static String wert(Pattern p, String s) {
        Matcher m = p.matcher(s);
        return m.find() ? m.group(1).trim() : "";
    }

    private static String umschlag(String inhalt) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
                  <soap:Body>%s</soap:Body>
                </soap:Envelope>
                """.formatted(inhalt);
    }

    private static String fehler(String code, String text) {
        return umschlag("""
                <Status><Result>Error</Result>
                  <Error><Code>%s</Code><Text>%s</Text></Error>
                </Status>""".formatted(code, text));
    }
}
