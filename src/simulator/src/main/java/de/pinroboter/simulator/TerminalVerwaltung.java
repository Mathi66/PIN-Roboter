package de.pinroboter.simulator;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Simuliert die netzwerkbasierte Managementschnittstelle eines Kartenterminals
 * — die Strecke, über die PEviM den PIN-Wert entgegennimmt.
 *
 * <p>Nachgebildet nach [gemSpec_KT] 2.4.5.1 und 2.4.9: TLS-gesichert
 * ({@code A_24065}), Rollenanmeldung als {@code SMC-B_Nutzer} ({@code A_24075}),
 * PIN-Übergabe nur bei offener Aufforderung.
 *
 * <p>Die Pfade sind an die Weboberflächen realer Geräte angelehnt. Weil die
 * Hersteller hier nichts Spezifiziertes umsetzen müssen, kapselt der Agent diese
 * Schnittstelle hinter einer einheitlichen inneren Sicht (Architekturkonzept 5.4)
 * — ein anderer Hersteller ist dort eine neue Umsetzung, kein Umbau.
 */
public class TerminalVerwaltung implements HttpHandler {

    private final Zustand zustand;

    public TerminalVerwaltung(Zustand zustand) { this.zustand = zustand; }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        String pfad = ex.getRequestURI().getPath();
        String methode = ex.getRequestMethod();
        try {
            // /api/{terminalId}/...
            String[] teile = pfad.split("/");
            if (teile.length < 4) { antworte(ex, 404, "{\"fehler\":\"Pfad unbekannt\"}"); return; }
            String terminalId = teile[2];
            String aktion = teile[3];

            var opt = zustand.terminal(terminalId);
            if (opt.isEmpty()) { antworte(ex, 404, "{\"fehler\":\"Terminal unbekannt\"}"); return; }
            SimuliertesTerminal t = opt.get();

            if (!t.erreichbar()) { antworte(ex, 503, "{\"fehler\":\"Terminal nicht erreichbar\"}"); return; }

            switch (aktion) {
                case "anmelden" -> {
                    // A_24081 verlangt, dass die Rolle SMC-B_Nutzer bereits
                    // authentisiert ist, WENN das SICCT-Kommando eintrifft.
                    // Die Sitzung wird deshalb vor dem VerifyPin aufgebaut.
                    if (!t.pevimUnterstuetzt()) {
                        antworte(ex, 501, "{\"fehler\":\"Dieses Modell unterstützt PEviM nicht\"}");
                        return;
                    }
                    if (!t.pevimAktiv()) {
                        antworte(ex, 409, "{\"fehler\":\"PEviM ist an diesem Gerät nicht aktiviert\","
                                + "\"hinweis\":\"Aktivierung nur durch die Rolle Administrator (A_24078)\"}");
                        return;
                    }
                    String sitzung = t.anmeldenAlsSmcbNutzer();
                    antworte(ex, 200, "{\"sitzung\":\"" + sitzung + "\"}");
                }
                case "abmelden" -> {
                    t.abmelden(kopf(ex, "X-Sitzung"));
                    antworte(ex, 200, "{\"ok\":true}");
                }
                case "pin" -> {
                    String sitzung = kopf(ex, "X-Sitzung");
                    if (!t.sitzungGueltig(sitzung)) {
                        antworte(ex, 401, "{\"fehler\":\"Keine gültige Sitzung als SMC-B_Nutzer\"}");
                        return;
                    }
                    String koerper = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    String pin = jsonWert(koerper, "pin");
                    if (pin == null) { antworte(ex, 400, "{\"fehler\":\"Feld 'pin' fehlt\"}"); return; }
                    boolean angenommen = t.pinLiefern(pin);
                    if (!angenommen) {
                        antworte(ex, 409, "{\"fehler\":\"Das Terminal wartet derzeit auf keinen PIN-Wert\"}");
                        return;
                    }
                    antworte(ex, 200, "{\"ok\":true}");
                }
                case "status" -> antworte(ex, 200, """
                        {"id":"%s","name":"%s","modell":"%s","firmware":"%s",
                         "pevimUnterstuetzt":%s,"pevimAktiv":%s,"wartetAufPin":%s,"aktualisierend":%s}
                        """.formatted(t.id(), t.name(), t.modell(), t.firmware(),
                        t.pevimUnterstuetzt(), t.pevimAktiv(), t.wartetAufPin(), t.aktualisierend()));
                default -> antworte(ex, 404, "{\"fehler\":\"Aktion unbekannt\"}");
            }
        } catch (Exception e) {
            antworte(ex, 500, "{\"fehler\":\"" + e.getMessage() + "\"}");
        }
    }

    private static String kopf(HttpExchange ex, String name) {
        var l = ex.getRequestHeaders().get(name);
        return (l == null || l.isEmpty()) ? null : l.get(0);
    }

    /** Schlichter Werteauszug — der Simulator braucht keine JSON-Bibliothek. */
    static String jsonWert(String json, String feld) {
        var m = java.util.regex.Pattern.compile("\"" + feld + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    static void antworte(HttpExchange ex, int code, String koerper) throws IOException {
        byte[] b = koerper.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }
}
