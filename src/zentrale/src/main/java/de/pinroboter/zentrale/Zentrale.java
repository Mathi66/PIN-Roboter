package de.pinroboter.zentrale;

import de.pinroboter.gemeinsam.Ampel;
import de.pinroboter.gemeinsam.Ereignisart;
import de.pinroboter.gemeinsam.PinStatus;
import io.javalin.Javalin;
import io.javalin.http.Context;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Zentrale: Oberfläche, Fachprotokoll, Zustandsübersicht.
 *
 * <p>Kennt bewusst <b>keine</b> SMC-B-PIN und keine Terminal-Zugänge — die
 * liegen ausschließlich beim Standort-Agenten (ADR-0002). Die Zentrale steuert,
 * protokolliert und zeigt an; der Agent besitzt und handelt.
 *
 * <p>Die Oberfläche wird server-seitig erzeugt. Grund ist die Nachweispflicht
 * für WCAG 2.2 AA und der Umstand, dass große Listen ohnehin serverseitig
 * gefiltert werden müssen (ADR-0006).
 */
public class Zentrale {

    private final Map<String, Geraetezustand> geraete = new ConcurrentHashMap<>();
    private final Fachprotokoll protokoll;
    private final Datenbank db;
    private volatile boolean notAus = false;
    private volatile Instant agentGesehen = null;

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8090"));
        String url  = env("DB_URL",  "jdbc:postgresql://datenbank:5432/pinroboter");
        String nutz = env("DB_USER", "pinroboter");
        String kenn = env("DB_PASSWORD", null);
        if (kenn == null || kenn.isBlank()) {
            // NFA-BET-010: Fehlende Pflichtangaben fuehren zu einer verstaendlichen
            // Meldung, nicht zu einem Absturz mit Stapelueberwachung.
            System.err.println("""
                    Die Umgebungsvariable DB_PASSWORD fehlt.
                    Die Zentrale benoetigt Zugangsdaten zur Datenbank. Bitte in der
                    Konfigurationsdatei ergaenzen und den Stack neu starten.""");
            System.exit(2);
        }
        // Das Speicherbudget begrenzt den einzigen unbegrenzt wachsenden Anteil
        // (FA-PRO-070). 200.000 Eintraege entsprechen grob 100 MB.
        long budget = Long.parseLong(env("PROTOKOLL_HOECHSTZAHL", "200000"));

        Datenbank db = Datenbank.verbinden(url, nutz, kenn, 30);
        new Zentrale(db, budget).starten(port);
    }

    Zentrale(Datenbank db, long protokollHoechstzahl) {
        this.db = db;
        this.protokoll = new Fachprotokoll(db, protokollHoechstzahl);
    }

    private static String env(String name, String vorgabe) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? vorgabe : v;
    }

    void starten(int port) {
        Javalin app = Javalin.create(cfg -> {
            cfg.showJavalinBanner = false;
            cfg.staticFiles.add(s -> { s.hostedPath = "/statisch"; s.directory = "/"; s.location = io.javalin.http.staticfiles.Location.CLASSPATH; });
        });

        app.get("/",           c -> c.html(seite("uebersicht", uebersicht())));
        app.get("/geraete",    c -> c.html(seite("geraete", geraeteSeite())));
        app.get("/protokoll",  c -> c.html(seite("protokoll", protokollSeite(c))));
        app.get("/teil/uebersicht", c -> c.html(uebersicht()));
        app.get("/teil/geraete",    c -> c.html(geraeteTabelle()));
        app.get("/teil/protokoll",  c -> c.html(protokollTabelle(c)));

        app.post("/notaus", c -> {
            notAus = !notAus;
            protokoll.anfuegen("Praxis", null,
                    notAus ? Ereignisart.NOT_AUS_AUSGELOEST : Ereignisart.NOT_AUS_AUFGEHOBEN,
                    true, "Benutzer", null,
                    notAus ? "Not-Aus ausgelöst — die Automatik ruht."
                           : "Not-Aus aufgehoben — die Automatik arbeitet wieder.", null);
            c.header("HX-Refresh", "true").result("");
        });

        // Meldeschnittstelle des Agenten. Im Produkt eine gegenseitig
        // authentifizierte WebSocket-Verbindung, ausgehend vom Agenten (ADR-0002).
        app.post("/api/melden", this::agentMeldung);
        app.get("/api/notaus", c -> c.json(Map.of("notAus", notAus)));

        app.start("0.0.0.0", port);
        System.out.println("""
                ────────────────────────────────────────────────────────────
                  PIN-Roboter · Zentrale
                  Oberfläche:  http://localhost:%d
                  Protokoll:   %d Einträge in der Datenbank
                ────────────────────────────────────────────────────────────
                """.formatted(port, protokoll.anzahl()));
    }

    @SuppressWarnings("unchecked")
    private void agentMeldung(Context c) {
        Map<String, Object> m = c.bodyAsClass(Map.class);
        agentGesehen = Instant.now();
        String id = String.valueOf(m.get("terminalId"));

        Geraetezustand g = geraete.computeIfAbsent(id, k -> {
            Geraetezustand neu = new Geraetezustand();
            neu.terminalId = k;
            protokoll.anfuegen("Praxis", k, Ereignisart.GERAET_ERKANNT, true, "Automatik", null,
                    "Kartenterminal erstmals erkannt", null);
            return neu;
        });

        PinStatus vorher = g.pinStatus;
        boolean vorherErreichbar = g.erreichbar;

        g.name = str(m, "name", g.name);
        g.modell = str(m, "modell", g.modell);
        g.firmware = str(m, "firmware", g.firmware);
        g.kartenhandle = str(m, "kartenhandle", g.kartenhandle);
        g.erreichbar = bool(m, "erreichbar");
        g.pevimUnterstuetzt = bool(m, "pevimUnterstuetzt");
        g.pevimAktiv = bool(m, "pevimAktiv");
        g.aktualisierend = bool(m, "aktualisierend");
        g.automatikAngehalten = bool(m, "automatikAngehalten");
        g.verbleibendeVersuche = zahl(m, "verbleibendeVersuche");
        g.hinweis = str(m, "hinweis", null);
        g.handlung = str(m, "handlung", null);
        g.zuletztGeprueft = Instant.now();
        try { g.pinStatus = PinStatus.valueOf(String.valueOf(m.get("pinStatus"))); }
        catch (Exception e) { g.pinStatus = PinStatus.UNBEKANNT; }

        // Grundregel ADR-0005: nur Zustandsänderungen protokollieren, keine Abfragen.
        if (vorher != g.pinStatus && vorher != null) {
            protokoll.anfuegen("Praxis", id, Ereignisart.PIN_STATUS_GEAENDERT, true, "Automatik", null,
                    "Status: %s → %s".formatted(vorher.anzeige(), g.pinStatus.anzeige()), null);
        }
        if (vorherErreichbar != g.erreichbar) {
            protokoll.anfuegen("Praxis", id,
                    g.erreichbar ? Ereignisart.GERAET_ERREICHBAR : Ereignisart.GERAET_NICHT_ERREICHBAR,
                    g.erreichbar, "Automatik", null,
                    g.erreichbar ? "Gerät wieder erreichbar" : "Gerät nicht erreichbar", null);
        }

        // Vom Agenten gemeldete Ereignisse übernehmen
        Object roh = m.get("ereignisse");
        if (roh instanceof List<?> liste) {
            for (Object o : liste) {
                if (o instanceof Map<?, ?> e) {
                    Ereignisart art;
                    try { art = Ereignisart.valueOf(String.valueOf(e.get("art"))); }
                    catch (Exception ex) { continue; }
                    protokoll.anfuegen("Praxis", id, art,
                            Boolean.TRUE.equals(e.get("erfolg")), "Automatik",
                            e.get("vorgang") == null ? null : String.valueOf(e.get("vorgang")),
                            String.valueOf(e.get("text")),
                            e.get("handlung") == null ? null : String.valueOf(e.get("handlung")));
                }
            }
        }
        c.json(Map.of("ok", true, "notAus", notAus));
    }

    // ---------------------------------------------------------------- Ansichten

    private String uebersicht() {
        var alle = geraete.values();
        long ok = alle.stream().filter(g -> g.ampel() == Ampel.OK).count();
        long warn = alle.stream().filter(g -> g.ampel() == Ampel.WARNUNG).count();
        long fehler = alle.stream().filter(g -> g.ampel() == Ampel.FEHLER).count();
        long ohnePevim = alle.stream().filter(g -> !g.pevimAktiv).count();

        StringBuilder s = new StringBuilder();
        if (notAus) s.append("""
                <div class="hinweis" style="border-left-color:var(--fehler);background:var(--fehler-hg)">
                  <strong>Not-Aus ist aktiv</strong>
                  Die Automatik ruht. Es werden keine Karten freigeschaltet, bis der Not-Aus aufgehoben wird.
                </div>""");

        s.append("""
                <div class="lage">
                  <a class="kachel ok" href="/geraete"><div class="zahl">%d</div><div class="bez">Karten freigeschaltet</div></a>
                  <a class="kachel warnung" href="/geraete"><div class="zahl">%d</div><div class="bez">brauchen Aufmerksamkeit</div></a>
                  <a class="kachel fehler" href="/geraete"><div class="zahl">%d</div><div class="bez">Handlungsbedarf</div></a>
                  <a class="kachel unbekannt" href="/geraete"><div class="zahl">%d</div><div class="bez">ohne Automatik (PEviM)</div></a>
                </div>""".formatted(ok, warn, fehler, ohnePevim));

        var auffaellig = alle.stream()
                .filter(g -> g.ampel() != Ampel.OK)
                .sorted(Comparator.comparing(g -> g.terminalId)).toList();

        s.append("<div class=\"tafel\"><h3>Was zu tun ist <span class=\"zaehler\">")
         .append(auffaellig.isEmpty() ? "nichts offen" : auffaellig.size() + " Punkte").append("</span></h3>");
        if (auffaellig.isEmpty()) {
            s.append("<p class=\"leer\">Alle überwachten Karten sind freigeschaltet. Es ist nichts zu tun.</p>");
        } else {
            s.append("<div class=\"rollbar\"><table><thead><tr><th>Gerät</th><th>Zustand</th><th>Was zu tun ist</th></tr></thead><tbody>");
            for (Geraetezustand g : auffaellig) {
                s.append("""
                        <tr><td class="geraet">%s<br><span style="font-weight:400;color:var(--gedaempft);font-size:.85em">%s</span></td>
                        <td>%s</td><td>%s%s</td></tr>"""
                        .formatted(esc(anzeigename(g)), esc(g.terminalId), marke(g),
                                esc(g.hinweis != null ? g.hinweis : g.pinStatus.erklaerung()),
                                g.handlung == null ? "" : "<div class=\"handlung\">" + esc(g.handlung) + "</div>"));
            }
            s.append("</tbody></table></div>");
        }
        s.append("</div>");
        return s.toString();
    }

    private String geraeteSeite() {
        return """
                <h2>Kartenterminals</h2>
                <p class="unterzeile">Zustand aller überwachten Geräte. Aktualisiert sich selbsttätig.</p>
                <div class="tafel"><h3>Geräte <span class="zaehler"><span class="puls"></span> live</span></h3>
                <div id="geraete" hx-get="/teil/geraete" hx-trigger="load, every 3s" hx-swap="innerHTML">%s</div></div>
                """.formatted(geraeteTabelle());
    }

    private String geraeteTabelle() {
        if (geraete.isEmpty()) return "<p class=\"leer\">Noch keine Geräte gemeldet. "
                + "Sobald der Standort-Agent verbunden ist, erscheinen sie hier.</p>";
        StringBuilder s = new StringBuilder("<div class=\"rollbar\"><table><thead><tr>"
                + "<th>Gerät</th><th>Modell</th><th>Firmware</th><th>Zustand</th>"
                + "<th>Automatik</th><th>Versuche</th><th>geprüft</th></tr></thead><tbody>");
        geraete.values().stream().sorted(Comparator.comparing(g -> g.terminalId)).forEach(g -> s.append("""
                <tr><td class="geraet">%s<br><span style="font-weight:400;color:var(--gedaempft);font-size:.85em">%s</span></td>
                <td>%s</td><td><code>%s</code></td><td>%s</td><td>%s</td>
                <td style="font-variant-numeric:tabular-nums">%s</td><td class="zeit">%s</td></tr>"""
                .formatted(esc(anzeigename(g)), esc(g.terminalId), esc(nz(g.modell)), esc(nz(g.firmware)),
                        marke(g), automatikMarke(g),
                        g.verbleibendeVersuche < 0 ? "—" : String.valueOf(g.verbleibendeVersuche),
                        g.zuletztGeprueftText())));
        return s.append("</tbody></table></div>").toString();
    }

    private String protokollSeite(Context c) {
        StringBuilder arten = new StringBuilder("<option value=\"\">alle Ereignisarten</option>");
        for (Ereignisart a : Ereignisart.values())
            arten.append("<option value=\"%s\">%s</option>".formatted(a.name(), esc(a.anzeige())));
        return """
                <h2>Protokoll</h2>
                <p class="unterzeile">Jede Handlung des Roboters ist hier nachvollziehbar.
                   Protokolliert werden Zustandsänderungen und Handlungen — keine Abfragen.</p>
                <div class="tafel">
                  <div class="filter">
                    <label class="nurLeser" for="f-art">Ereignisart</label>
                    <select id="f-art" name="art" hx-get="/teil/protokoll" hx-target="#protokoll" hx-include=".filter">%s</select>
                    <label class="nurLeser" for="f-erg">Ergebnis</label>
                    <select id="f-erg" name="ergebnis" hx-get="/teil/protokoll" hx-target="#protokoll" hx-include=".filter">
                      <option value="">alle Ergebnisse</option><option value="erfolg">erfolgreich</option>
                      <option value="fehler">fehlgeschlagen</option></select>
                    <button class="knopf" hx-get="/teil/protokoll" hx-target="#protokoll" hx-include=".filter">Aktualisieren</button>
                  </div>
                  <div id="protokoll" hx-get="/teil/protokoll" hx-trigger="every 5s" hx-include=".filter" hx-swap="innerHTML">%s</div>
                </div>""".formatted(arten, protokollTabelle(c));
    }

    private String protokollTabelle(Context c) {
        var liste = protokoll.filtern(c.queryParam("geraet"), c.queryParam("art"),
                c.queryParam("ergebnis"), 200);
        if (liste.isEmpty()) return "<p class=\"leer\">Keine Einträge für diese Auswahl.</p>";
        StringBuilder s = new StringBuilder("<div class=\"rollbar\"><table><thead><tr>"
                + "<th>Zeitpunkt</th><th>Gerät</th><th>Ereignis</th><th>Ergebnis</th>"
                + "<th>Beschreibung</th><th>Vorgang</th></tr></thead><tbody>");
        for (Ereignis e : liste) {
            s.append("""
                    <tr><td class="zeit">%s</td><td class="geraet">%s</td><td>%s</td>
                    <td><span class="marke %s"><span class="sym">%s</span>%s</span></td>
                    <td>%s%s</td><td><code>%s</code></td></tr>"""
                    .formatted(e.zeitLokal(), esc(nz(e.geraet())), esc(e.art().anzeige()),
                            e.erfolg() ? "ok" : "fehler", e.erfolg() ? "✓" : "×",
                            e.erfolg() ? "erfolgreich" : "fehlgeschlagen",
                            esc(nz(e.text())),
                            e.handlung() == null ? "" : "<div class=\"handlung\">" + esc(e.handlung()) + "</div>",
                            esc(e.vorgang() == null ? "—" : e.vorgang())));
        }
        return s.append("</tbody></table></div>").toString();
    }

    // ---------------------------------------------------------------- Rahmen

    private String seite(String aktiv, String inhalt) {
        String agentText = agentGesehen == null ? "kein Agent verbunden" : "Agent verbunden";
        return """
                <!doctype html><html lang="de"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <title>PIN-Roboter</title><link rel="stylesheet" href="/statisch/stil.css">
                <script src="/statisch/htmx.min.js" defer></script></head><body>
                <a href="#inhalt" class="nurLeser">Zum Inhalt springen</a>
                <header>
                  <h1>PIN-Roboter <span>· %s</span></h1>
                  <nav aria-label="Hauptnavigation">
                    <a href="/" %s>Übersicht</a>
                    <a href="/geraete" %s>Kartenterminals</a>
                    <a href="/protokoll" %s>Protokoll</a>
                    <button class="knopf %s" hx-post="/notaus"
                      title="Hält die Automatik sofort an">%s</button>
                  </nav>
                </header>
                <main id="inhalt">
                  <div hx-get="/teil/uebersicht" hx-trigger="every 3s" hx-swap="innerHTML" %s>%s</div>
                </main></body></html>"""
                .formatted(esc(agentText),
                        aktiv.equals("uebersicht") ? "aria-current=\"page\"" : "",
                        aktiv.equals("geraete") ? "aria-current=\"page\"" : "",
                        aktiv.equals("protokoll") ? "aria-current=\"page\"" : "",
                        notAus ? "" : "warnend", notAus ? "Not-Aus aufheben" : "Not-Aus",
                        aktiv.equals("uebersicht") ? "" : "hx-disable", inhalt);
    }

    private String marke(Geraetezustand g) {
        Ampel a = g.ampel();
        return "<span class=\"marke %s\"><span class=\"sym\">%s</span>%s</span>"
                .formatted(a.css(), a.symbol(), esc(g.zustandstext()));
    }

    /** Ein Gerät ohne PEviM ist kein Fehler — es wird überwacht, nur nicht automatisiert. */
    private String automatikMarke(Geraetezustand g) {
        if (!g.pevimUnterstuetzt)
            return "<span class=\"marke unbekannt\"><span class=\"sym\">–</span>Modell ohne PEviM</span>";
        if (!g.pevimAktiv)
            return "<span class=\"marke warnung\"><span class=\"sym\">!</span>nicht aktiviert</span>";
        if (g.automatikAngehalten)
            return "<span class=\"marke fehler\"><span class=\"sym\">×</span>angehalten</span>";
        return "<span class=\"marke ok\"><span class=\"sym\">✓</span>aktiv</span>";
    }

    private static String anzeigename(Geraetezustand g) {
        return g.name != null && !g.name.isBlank() ? g.name : g.terminalId;
    }

    private static String nz(String s) { return s == null ? "—" : s; }
    private static String str(Map<String, Object> m, String k, String vorgabe) {
        Object o = m.get(k); return o == null ? vorgabe : String.valueOf(o);
    }
    private static boolean bool(Map<String, Object> m, String k) { return Boolean.TRUE.equals(m.get(k)); }
    private static int zahl(Map<String, Object> m, String k) {
        Object o = m.get(k); return o instanceof Number n ? n.intValue() : -1;
    }
    static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
