package de.pinroboter.simulator;

import com.sun.net.httpserver.*;
import de.pinroboter.gemeinsam.PinStatus;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

/**
 * Simulierte TI-Umgebung: Konnektor und Kartenterminals.
 *
 * <p>Zweck nach ADR-0007: Drei Nachweise sind mit echter Hardware nicht führbar
 * — das Mengengerüst von 5.000 Terminals, die geforderten Fehlerfälle, und vor
 * allem FA-PIN-030 (falsche PIN löst genau einen Versuch aus), der real bei
 * jedem Durchlauf einen Fehlversuch der Karte verbrennt.
 *
 * <p>Der Simulator wird <b>nicht ausgeliefert</b>. Er ist Werkzeug der
 * Qualitätssicherung und ersetzt nicht die Kalibrierung an echter Hardware.
 *
 * <h2>Schnittstellen</h2>
 * <ul>
 *   <li>{@code 8443} — Konnektor, SOAP über Brainpool-TLS</li>
 *   <li>{@code 8444} — Kartenterminal-Management über Brainpool-TLS</li>
 *   <li>{@code 8480} — Steuerung für Tests, unverschlüsselt, nur im internen Netz</li>
 * </ul>
 */
public class Simulator {

    public static void main(String[] args) throws Exception {
        BrainpoolFreischaltung.anwenden();   // muss als Erstes laufen
        Zustand zustand = Zustand.standardaufbau();

        System.out.println("""
                ────────────────────────────────────────────────────────────
                  PIN-Roboter · Simulierte TI-Umgebung
                ────────────────────────────────────────────────────────────""");

        var kette = BrainpoolZertifikate.erzeugen("simulator");
        System.out.println("  Zertifikatskette erzeugt auf " + BrainpoolZertifikate.KURVE);

        SSLContext ctx = TlsAufbau.serverKontext(kette);
        TlsAufbau.diagnose(ctx);

        HttpsServer konnektor = starteTls(8443, ctx, "/", new KonnektorSoap(zustand), "Konnektor (SOAP)");

        // Webadmin-Schnittstelle: herstellerspezifisch, von niemandem
        // spezifiziert, und deshalb immer optional (NFA-QS-035). Sie liegt auf
        // demselben Port wie die Fachschnittstelle, aber unter eigenem Pfad -
        // ein laengerer Kontextpfad gewinnt bei HttpServer gegen "/".
        Informationsmodell modell = Informationsmodell.standardaufbau(zustand);
        konnektor.createContext("/webadmin/informationsmodell",
                ex -> TerminalVerwaltung.antworte(ex, 200, modell.alsJson()));
        System.out.printf("  %-28s https://0.0.0.0:%d/webadmin/informationsmodell%n",
                "Konnektor-Webadmin", 8443);
        starteTls(8444, ctx, "/api/", new TerminalVerwaltung(zustand), "Kartenterminal-Management");
        starteSteuerung(8480, zustand, kette);
        // Nur fuer die Nachweise: ein Auffangserver fuer Benachrichtigungsmails.
        Mailauffang.starten(1025);

        System.out.println("""
                  TLS: %s, nur zulässige Suites nach [gemSpec_Krypt]
                ────────────────────────────────────────────────────────────
                  Bereit. %d Terminals simuliert.
                """.formatted(String.join(", ", TlsAufbau.PROTOKOLLE), zustand.terminals().size()));
        zustand.terminals().forEach(t -> System.out.printf(
                "    %-18s %-22s Firmware %-8s PEviM %s%n",
                t.id(), t.modell(), t.firmware(),
                !t.pevimUnterstuetzt() ? "nicht unterstützt" : (t.pevimAktiv() ? "aktiv" : "vorhanden, nicht aktiviert")));
        System.out.println();

        Thread.currentThread().join();
    }

    private static HttpsServer starteTls(int port, SSLContext ctx, String pfad,
                                         HttpHandler handler, String bezeichnung) throws IOException {
        HttpsServer server = HttpsServer.create(new InetSocketAddress(port), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(ctx) {
            @Override public void configure(HttpsParameters params) {
                SSLParameters p = ctx.getDefaultSSLParameters();
                TlsAufbau.parameterSetzen(p);
                params.setSSLParameters(p);
            }
        });
        server.createContext(pfad, handler);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        System.out.printf("  %-28s https://0.0.0.0:%d%n", bezeichnung, port);
        return server;
    }

    /**
     * Steuerschnittstelle für automatisierte Tests: versetzt die Umgebung in
     * definierte Zustände. Reproduzierbarkeit ist der halbe Wert eines
     * Simulators — ohne sie ließe sich kein Fehlerfall im Bauvorgang prüfen.
     */
    private static void starteSteuerung(int port, Zustand z,
                                        BrainpoolZertifikate.Kette kette) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        // Der Agent holt hier den Vertrauensanker. An echter Hardware kommt er
        // aus der TI-PKI; im Simulator entsteht er bei jedem Start neu.
        server.createContext("/ca.pem", ex -> {
            try {
                byte[] b = BrainpoolZertifikate.alsPem(kette.caZertifikat()).getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/x-pem-file");
                ex.sendResponseHeaders(200, b.length);
                ex.getResponseBody().write(b);
            } catch (Exception e) { ex.sendResponseHeaders(500, -1); }
            ex.close();
        });

        // Auffang fuer Benachrichtigungsmails (FA-ALA-020). Die Nachweise
        // fragen hier ab, was tatsaechlich verschickt wurde.
        server.createContext("/mail", ex -> {
            if ("DELETE".equals(ex.getRequestMethod())) {
                Mailauffang.leeren();
                TerminalVerwaltung.antworte(ex, 200, "{\"geleert\":true}");
                return;
            }
            var eingang = Mailauffang.eingang();
            StringBuilder j = new StringBuilder("{\"anzahl\":").append(eingang.size())
                    .append(",\"nachrichten\":[");
            for (int i = 0; i < eingang.size(); i++) {
                if (i > 0) j.append(',');
                j.append('"').append(eingang.get(i)
                        .replace("\\", "\\\\").replace("\"", "\\\"")
                        .replace("\n", "\\n").replace("\r", "")).append('"');
            }
            TerminalVerwaltung.antworte(ex, 200, j.append("]}").toString());
        });

        // Zählung der Statusabfragen je Karte — Messgrundlage für FA-UEB-010.
        server.createContext("/abfragen", ex -> {
            if ("DELETE".equals(ex.getRequestMethod())) {
                KonnektorSoap.ABFRAGEN.clear();
                TerminalVerwaltung.antworte(ex, 200, "{\"geleert\":true}");
                return;
            }
            StringBuilder j = new StringBuilder("{");
            boolean erst = true;
            for (var e : KonnektorSoap.ABFRAGEN.entrySet()) {
                if (!erst) j.append(',');
                j.append('"').append(e.getKey()).append("\":").append(e.getValue().get());
                erst = false;
            }
            TerminalVerwaltung.antworte(ex, 200, j.append('}').toString());
        });

        server.createContext("/steuerung/", ex -> {
            String[] t = ex.getRequestURI().getPath().split("/");
            // /steuerung/{terminalId}/{aktion}[/{wert}]
            if (t.length < 4) { TerminalVerwaltung.antworte(ex, 400, "{\"fehler\":\"Pfad unvollständig\"}"); return; }
            var opt = z.terminal(t[2]);
            if (opt.isEmpty()) { TerminalVerwaltung.antworte(ex, 404, "{\"fehler\":\"Terminal unbekannt\"}"); return; }
            SimuliertesTerminal term = opt.get();
            String wert = t.length > 4 ? t[4] : null;

            try {
                switch (t[3]) {
                    case "erreichbar"    -> term.erreichbarSetzen(Boolean.parseBoolean(wert));
                    case "pevim"         -> term.pevimAktivSetzen(Boolean.parseBoolean(wert));
                    case "verzoegerung"  -> term.antwortverzoegerungSetzen(Integer.parseInt(wert));
                    case "entziehen"     -> term.alleKarten().forEach(SimulierteKarte::verifikationEntziehen);
                    case "zuruecksetzen" -> term.alleKarten().forEach(SimulierteKarte::zuruecksetzen);
                    case "sperren"       -> term.alleKarten().forEach(k -> k.sonderzustandSetzen(PinStatus.BLOCKED));
                    case "transportpin"  -> term.alleKarten().forEach(k -> k.sonderzustandSetzen(PinStatus.TRANSPORT_PIN));
                    case "firmware"      -> {
                        // /steuerung/{id}/firmware/{version}?dauer=<ms>
                        String q = ex.getRequestURI().getQuery();
                        long dauer = 2000;
                        if (q != null && q.startsWith("dauer=")) {
                            try { dauer = Long.parseLong(q.substring(6)); } catch (Exception ignored) {}
                        }
                        final long d = dauer;
                        new Thread(() -> {
                            try { term.firmwareEinspielen(wert, d); } catch (InterruptedException ignored) {}
                        }).start();
                    }
                    default -> { TerminalVerwaltung.antworte(ex, 404, "{\"fehler\":\"Aktion unbekannt\"}"); return; }
                }
                TerminalVerwaltung.antworte(ex, 200, "{\"ok\":true}");
            } catch (Exception e) {
                TerminalVerwaltung.antworte(ex, 400, "{\"fehler\":\"" + e.getMessage() + "\"}");
            }
        });

        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        System.out.printf("  %-28s http://0.0.0.0:%d  (nur intern)%n", "Steuerung / CA-Ausgabe", port);
    }
}
