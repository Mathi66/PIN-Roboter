package de.pinroboter.agent;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Die Oberfläche des Standort-Agenten: Inbetriebnahme, Zustand, Fehlersuche
 * (PO, 13.08.2026).
 *
 * <h2>Nur auf diesem Rechner erreichbar</h2>
 *
 * Der Agent baut seine Verbindungen ausschließlich nach außen auf, damit im
 * Praxisnetz keine Freigabe nötig ist (ADR-0002) — eine Oberfläche, die im
 * ganzen Netz antwortet, würde genau das aufweichen. Wer den Agenten betreut,
 * sitzt an diesem Rechner oder ist per Fernwartung darauf.
 *
 * <h3>Warum die Bindeadresse einstellbar ist</h3>
 *
 * <p>Ohne Container ist {@code 127.0.0.1} richtig, und das ist die Vorgabe.
 * <b>Im Container ist es falsch</b>: Ein dort an {@code 127.0.0.1} gebundener
 * Dienst ist von außen nicht erreichbar, auch nicht über ein Port-Mapping —
 * Docker leitet an die Adresse des Containers, nicht an dessen Loopback. Der
 * erste Versuch am 13.08.2026 lief genau in diesen Fehler: Der Agent meldete
 * „Oberfläche: http://127.0.0.1:8091", und der Aufruf lief ins Leere.
 *
 * <p>Im Container gilt deshalb {@code OBERFLAECHE_ADRESSE=0.0.0.0}, und die
 * Beschränkung übernimmt das Port-Mapping:
 * {@code ports: ["127.0.0.1:8091:8091"]}. Damit ist der Port <b>auf dem Host</b>
 * nur lokal erreichbar — von außen unsichtbar, wie gewollt.
 *
 * <h2>Nur lesend, ohne Geheimnisse</h2>
 *
 * Die Konfiguration kommt aus Umgebungsvariablen; eine Oberfläche, die sie
 * ändern könnte, hätte zwei Wahrheiten. Und sie zeigt <b>keine</b> PIN, kein
 * Token, keinen Schlüssel — auch nicht gekürzt. Was man ansehen kann, wird
 * irgendwann abfotografiert.
 *
 * <h2>Ohne Fremdbibliothek</h2>
 *
 * {@code com.sun.net.httpserver} gehört zum JDK. Ein Webframework im Agenten
 * wären zusätzliche Megabyte und zusätzliche Abhängigkeiten in einem Baustein,
 * der im Praxisnetz neben dem TI-Zugang läuft.
 */
final class Agentenoberflaeche {

    /** Voreinstellung. Über {@code OBERFLAECHE_PORT} änderbar, 0 schaltet ab. */
    static final int VORGABEPORT = 8091;

    /**
     * Voreinstellung der Bindeadresse: nur dieser Rechner. Im Container muss
     * {@code OBERFLAECHE_ADRESSE=0.0.0.0} gesetzt werden — siehe Klassentext.
     */
    static final String VORGABEADRESSE = "127.0.0.1";

    private final Betriebszustand zustand;
    private final Zentralenadresse adresse;
    private final java.util.function.Consumer<java.net.URI> uebernehmen;
    /** Meldung der letzten Änderung — wird einmal angezeigt, dann verworfen. */
    private volatile String meldung;
    private volatile boolean meldungIstFehler;

    Agentenoberflaeche(Betriebszustand zustand, Zentralenadresse adresse,
                       java.util.function.Consumer<java.net.URI> uebernehmen) {
        this.zustand = zustand;
        this.adresse = adresse;
        this.uebernehmen = uebernehmen;
    }

    /**
     * Startet den Server. Schlägt das fehl, arbeitet der Agent trotzdem
     * weiter: Die Kernaufgabe darf nicht an einer Anzeige hängen.
     */
    void starten(int port, String bindeadresse) {
        if (port <= 0) {
            System.out.println("  Oberfläche abgeschaltet (OBERFLAECHE_PORT=0)");
            return;
        }
        try {
            var server = HttpServer.create(
                    new InetSocketAddress(InetAddress.getByName(bindeadresse), port), 0);
            server.createContext("/", this::seite);
            server.createContext("/health", this::gesundheit);
            server.createContext("/zentrale", this::zentraleAendern);
            server.setExecutor(null);
            server.start();
            System.out.printf("  Oberfläche: http://127.0.0.1:%d  (nur auf diesem Rechner%s)%n",
                    port, "0.0.0.0".equals(bindeadresse) ? ", über das Port-Mapping" : "");
        } catch (IOException e) {
            System.out.println("  Oberfläche nicht gestartet: " + e.getMessage()
                    + " — der Agent arbeitet weiter.");
        }
    }

    // ------------------------------------------------------------- Antworten

    private void gesundheit(HttpExchange ex) throws IOException {
        boolean ok = zustand.arbeitsfaehig();
        antworten(ex, ok ? 200 : 503, "application/json; charset=utf-8",
                ("{\"status\":\"" + (ok ? "ok" : "fehler") + "\""
                 + ",\"registriert\":" + zustand.pruefliste().get(0).erfuellt()
                 + ",\"terminals\":" + zustand.terminals()
                 + ",\"version\":\"" + esc(zustand.version()) + "\"}"));
    }

    /**
     * Ändert die Adresse der Zentrale (Serverumzug).
     *
     * <p>Nur POST: Ein GET wäre von einem Verlaufseintrag auslösbar. Die
     * eigentliche Prüfung — antwortet dort wirklich eine Zentrale? — macht
     * {@link Zentralenadresse#setzen}; hier steht nur der Weg dorthin.
     */
    private void zentraleAendern(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) {
            antworten(ex, 405, "text/plain; charset=utf-8", "Nur POST.");
            return;
        }
        String koerper = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String wert = feld(koerper, "adresse");

        String fehler = adresse.setzen(wert);
        if (fehler == null) {
            uebernehmen.accept(adresse.aktuell());
            zustand.grunddatenZentrale(adresse.aktuell().toString());
            zustand.melden(false, "Adresse der Zentrale geändert auf " + adresse.aktuell());
            meldung = "Die Zentrale ist jetzt " + adresse.aktuell()
                    + " — geprüft und übernommen. Die Änderung überlebt einen Neustart.";
            meldungIstFehler = false;
        } else {
            // Nicht übernommen: Ein Tippfehler würde den Agenten sonst dauerhaft
            // von seiner Zentrale abschneiden.
            zustand.melden(true, "Adresse der Zentrale NICHT geändert: " + fehler);
            meldung = fehler;
            meldungIstFehler = true;
        }
        // Nach dem Absenden zurück auf die Seite — sonst wiederholt ein
        // Neuladen die Änderung.
        ex.getResponseHeaders().add("Location", "/");
        ex.sendResponseHeaders(303, -1);
        ex.close();
    }

    /** Ein Feld aus einem urlkodierten Formular. Kein Framework, also von Hand. */
    private static String feld(String koerper, String name) {
        for (String teil : koerper.split("&")) {
            int g = teil.indexOf('=');
            if (g < 0) continue;
            if (java.net.URLDecoder.decode(teil.substring(0, g), StandardCharsets.UTF_8)
                    .equals(name)) {
                return java.net.URLDecoder.decode(teil.substring(g + 1), StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    private void seite(HttpExchange ex) throws IOException {
        if (!"/".equals(ex.getRequestURI().getPath())) {
            antworten(ex, 404, "text/plain; charset=utf-8", "Diese Seite gibt es nicht.");
            return;
        }
        antworten(ex, 200, "text/html; charset=utf-8", html());
    }

    private void antworten(HttpExchange ex, int status, String typ, String inhalt)
            throws IOException {
        byte[] b = inhalt.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", typ);
        // Der Zustand ändert sich ständig; ein Zwischenspeicher zeigte Vergangenheit.
        ex.getResponseHeaders().add("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, b.length);
        try (var o = ex.getResponseBody()) { o.write(b); }
    }

    // ---------------------------------------------------------------- Anzeige

    private String html() {
        var s = new StringBuilder();
        s.append("""
                <!doctype html><html lang="de"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <!-- Aktualisiert sich selbst: Wer bei der Inbetriebnahme etwas
                     ändert, soll die Wirkung sehen, ohne neu zu laden. -->
                <meta http-equiv="refresh" content="15">
                <title>Standort-Agent · PIN-Roboter</title><style>%s</style></head><body>
                """.formatted(stil()));

        boolean ok = zustand.arbeitsfaehig();
        s.append("<header><h1>Standort-Agent</h1><p class=\"ort\">")
         .append(esc(zustand.einrichtung().isBlank() ? "noch keiner Einrichtung zugeordnet"
                                                     : zustand.einrichtung()))
         .append(" · Version ").append(esc(zustand.version())).append("</p>")
         .append("<p class=\"marke ").append(ok ? "gut" : "schlecht").append("\">")
         .append(ok ? "✓ arbeitsfähig" : "× nicht arbeitsfähig").append("</p>");
        if (zustand.notAus()) {
            s.append("<p class=\"marke schlecht\">× Not-Aus aktiv — die Zentrale hat die "
                    + "Automatik angehalten</p>");
        }
        s.append("</header>");

        if (meldung != null) {
            s.append("<p class=\"hinweis ").append(meldungIstFehler ? "schlechtHg" : "gutHg")
             .append("\">").append(esc(meldung)).append("</p>");
            meldung = null;      // nur einmal zeigen
        }

        // ── Inbetriebnahme ──────────────────────────────────────────────
        s.append("<section><h2>Inbetriebnahme</h2><ol class=\"liste\">");
        for (var p : zustand.pruefliste()) {
            s.append("<li class=\"").append(p.erfuellt() ? "ja" : "nein").append("\">")
             .append("<span class=\"zeichen\">").append(p.erfuellt() ? "✓" : "×").append("</span>")
             .append("<div><strong>").append(esc(p.was())).append("</strong>")
             .append("<div class=\"klein\">").append(esc(p.klartext())).append("</div>");
            if (p.handlung() != null) {
                s.append("<div class=\"handlung\">").append(esc(p.handlung())).append("</div>");
            }
            s.append("</div></li>");
        }
        s.append("</ol></section>");

        // ── Zustand ─────────────────────────────────────────────────────
        s.append("<section><h2>Zustand</h2><div class=\"kacheln\">");
        kachel(s, "Kartenterminals", zustand.terminals() + "",
                zustand.terminalsErreichbar() + " erreichbar");
        kachel(s, "SMC-B", zustand.smcbKarten() + "",
                zustand.pinsHinterlegt() + " mit hinterlegter PIN");
        kachel(s, "Offene Verbindungen", zustand.verbindungenOffen() + "",
                "zu Kartenterminals");
        kachel(s, "Grundtakt", zustand.grundtakt() + " s", "Prüfabstand");
        s.append("</div><dl class=\"angaben\">");
        angabe(s, "Zentrale", zustand.zentrale());
        angabe(s, "zuletzt erreicht", Betriebszustand.vorWieLange(zustand.zentraleGesehen()));
        angabe(s, "Konnektor", zustand.konnektorAdresse());
        angabe(s, "zuletzt erreicht", Betriebszustand.vorWieLange(zustand.konnektorGesehen()));
        angabe(s, "läuft seit", Betriebszustand.vorWieLange(zustand.start()));
        s.append("</dl></section>");

        // ── Zentrale umstellen ──────────────────────────────────────────
        s.append("""
                <section><h2>Adresse der Zentrale</h2>
                <p class="klein">Ändern Sie diese Angabe, wenn die Zentrale <strong>umgezogen
                   ist</strong> — etwa auf einen anderen Server oder Namen. Der Agent prüft die
                   neue Adresse, <em>bevor</em> er sie übernimmt: Antwortet dort keine
                   betriebsbereite Zentrale, bleibt alles beim Alten.</p>
                <form method="post" action="/zentrale">
                  <label for="adr">Neue Adresse</label>
                  <input id="adr" name="adresse" type="url" required value="%s"
                         placeholder="https://pin-roboter.example.de/">
                  <button type="submit">Prüfen und übernehmen</button>
                </form>"""
                .formatted(esc(adresse.aktuell().toString())));
        if (adresse.abweichend()) {
            s.append("<p class=\"klein\">Hier gilt eine gespeicherte Adresse. Die Datei "
                    + "<code>.env</code> nennt <code>").append(esc(adresse.ausUmgebung().toString()))
             .append("</code> — dieser Wert wird nicht mehr verwendet. Um wieder auf ihn "
                    + "zurückzugehen, tragen Sie ihn hier ein.</p>");
        }
        s.append("</section>");

        // ── Geräte ──────────────────────────────────────────────────────
        var geraete = zustand.geraete();
        if (!geraete.isEmpty()) {
            s.append("<section><h2>Geräte</h2><table><tbody>");
            geraete.entrySet().stream().sorted(java.util.Map.Entry.comparingByKey())
                    .forEach(e -> s.append("<tr><td class=\"kennung\">")
                            .append(esc(e.getKey())).append("</td><td>")
                            .append(esc(e.getValue())).append("</td></tr>"));
            s.append("</tbody></table></section>");
        }

        // ── Meldungen ───────────────────────────────────────────────────
        s.append("<section><h2>Letzte Meldungen</h2>");
        var meldungen = zustand.letzteMeldungen();
        if (meldungen.isEmpty()) {
            s.append("<p class=\"klein\">Noch nichts zu berichten.</p>");
        } else {
            s.append("<table><tbody>");
            var uhr = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
            for (var m : meldungen) {
                s.append("<tr class=\"").append(m.fehler() ? "nein" : "").append("\">")
                 .append("<td class=\"zeit\">").append(uhr.format(m.zeit())).append("</td>")
                 .append("<td>").append(esc(m.text())).append("</td></tr>");
            }
            s.append("</tbody></table>");
        }
        s.append("""
                <p class="klein">Diese Liste liegt nur im Arbeitsspeicher und ist nach einem
                   Neustart leer. Das dauerhafte Protokoll führt die Zentrale.</p>
                </section>
                <footer>Diese Seite ist nur auf diesem Rechner erreichbar. Sie zeigt keine
                   PINs, keine Zugangsdaten und keine Schlüssel. Geändert werden kann hier
                   ausschließlich die Adresse der Zentrale; alles andere steht in der Datei
                   <code>.env</code>.</footer>
                </body></html>""");
        return s.toString();
    }

    private static void kachel(StringBuilder s, String titel, String zahl, String zusatz) {
        s.append("<div class=\"kachel\"><div class=\"bez\">").append(esc(titel))
         .append("</div><div class=\"zahl\">").append(esc(zahl))
         .append("</div><div class=\"klein\">").append(esc(zusatz)).append("</div></div>");
    }

    private static void angabe(StringBuilder s, String was, String wert) {
        s.append("<dt>").append(esc(was)).append("</dt><dd>")
         .append(esc(wert == null || wert.isBlank() ? "—" : wert)).append("</dd>");
    }

    /**
     * Eigenes CSS statt des Stylesheets der Zentrale.
     *
     * <p>Der Agent läuft im Praxisnetz und soll <b>nichts</b> aus dem Internet
     * nachladen — auch nicht von der eigenen Zentrale: Ist sie nicht
     * erreichbar, wäre genau dann die Seite unbrauchbar, wenn man sie braucht.
     */
    private static String stil() {
        return """
                :root{--grund:#f4f6fa;--karte:#fff;--rand:#d3dae6;--text:#101623;
                  --gedaempft:#55606f;--gut:#0f7b3f;--gut-hg:#e2f2e9;
                  --schlecht:#a51f1f;--schlecht-hg:#fbe7e7;--akzent:#1e3a8f}
                @media(prefers-color-scheme:dark){:root{--grund:#0c1017;--karte:#181f2d;
                  --rand:#2b3648;--text:#e7ecf4;--gedaempft:#98a4b6;--gut:#5dcb8d;
                  --gut-hg:#10281c;--schlecht:#f28c8c;--schlecht-hg:#2c1717;--akzent:#93b4ff}}
                *{box-sizing:border-box}
                body{margin:0;padding:1.5rem;background:var(--grund);color:var(--text);
                  font:16px/1.5 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;
                  max-width:60rem;margin-inline:auto}
                h1{font-size:1.4rem;margin:0}
                h2{font-size:1.05rem;margin:0 0 .75rem}
                header{margin-bottom:1.5rem}
                .ort{color:var(--gedaempft);margin:.25rem 0 .75rem}
                .marke{display:inline-block;margin:0;padding:.25rem .75rem;border-radius:999px;
                  font-weight:600;font-size:.9rem}
                .marke.gut{background:var(--gut-hg);color:var(--gut)}
                .marke.schlecht{background:var(--schlecht-hg);color:var(--schlecht)}
                section{background:var(--karte);border:1px solid var(--rand);border-radius:12px;
                  padding:1rem 1.25rem;margin-bottom:1rem}
                .liste{list-style:none;margin:0;padding:0}
                .liste li{display:flex;gap:.75rem;padding:.6rem 0;border-top:1px solid var(--rand)}
                .liste li:first-child{border-top:0}
                .zeichen{flex:none;width:1.5rem;height:1.5rem;border-radius:999px;
                  display:grid;place-items:center;font-weight:700}
                .ja .zeichen{background:var(--gut-hg);color:var(--gut)}
                .nein .zeichen{background:var(--schlecht-hg);color:var(--schlecht)}
                .klein{font-size:.86rem;color:var(--gedaempft)}
                .handlung{margin-top:.35rem;padding-left:.7rem;font-size:.88rem;
                  border-left:3px solid var(--akzent)}
                .kacheln{display:grid;grid-template-columns:repeat(auto-fit,minmax(9rem,1fr));
                  gap:.75rem;margin-bottom:1rem}
                .kachel{border:1px solid var(--rand);border-radius:10px;padding:.75rem}
                .bez{font-size:.82rem;color:var(--gedaempft)}
                .zahl{font-size:1.6rem;font-weight:650;line-height:1.2}
                .angaben{display:grid;grid-template-columns:auto 1fr;gap:.35rem 1rem;margin:0}
                .angaben dt{color:var(--gedaempft);font-size:.9rem}
                .angaben dd{margin:0;word-break:break-all}
                table{width:100%;border-collapse:collapse;font-size:.9rem}
                td{padding:.4rem .5rem;border-top:1px solid var(--rand);vertical-align:top}
                tr:first-child td{border-top:0}
                tr.nein td{color:var(--schlecht)}
                .zeit,.kennung{white-space:nowrap;color:var(--gedaempft);
                  font-family:ui-monospace,Menlo,Consolas,monospace;font-size:.84rem}
                form{display:flex;flex-wrap:wrap;gap:.5rem;align-items:end;margin:.75rem 0 0}
                label{display:block;font-size:.86rem;color:var(--gedaempft);margin-bottom:.2rem}
                input{flex:1;min-width:16rem;padding:.5rem .6rem;border:1px solid var(--rand);
                  border-radius:8px;background:var(--grund);color:var(--text);font:inherit}
                button{padding:.55rem 1rem;border:0;border-radius:8px;background:var(--akzent);
                  color:#fff;font:inherit;font-weight:600;cursor:pointer}
                button:hover{filter:brightness(1.1)}
                .hinweis{padding:.7rem .9rem;border-radius:10px;margin:0 0 1rem}
                .gutHg{background:var(--gut-hg);color:var(--gut)}
                .schlechtHg{background:var(--schlecht-hg);color:var(--schlecht)}
                footer{color:var(--gedaempft);font-size:.84rem;padding:0 .25rem 1rem}
                code{font-family:ui-monospace,Menlo,Consolas,monospace;font-size:.9em}
                @media(max-width:34rem){.angaben{grid-template-columns:1fr}}
                """;
    }

    /** Kein Framework, also von Hand — und lieber einmal zu viel. */
    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
