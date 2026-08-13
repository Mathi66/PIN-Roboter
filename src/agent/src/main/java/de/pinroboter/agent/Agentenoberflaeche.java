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
 * „Oberfläche: http://127.0.0.1:8091“, und der Aufruf lief ins Leere.
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
            // Ein Kontext je Bereich. "/" ist der Pfad der Übersicht und wird
            // deshalb NICHT zusätzlich angemeldet — zweimal derselbe Pfad
            // beendet den Server mit „cannot add context to list“.
            for (var b : Bereich.values()) server.createContext(b.pfad, this::seite);
            server.createContext("/health", this::gesundheit);
            server.createContext("/zentrale", this::zentraleAendern);
            server.setExecutor(null);
            server.start();
            System.out.printf("  Oberfläche: http://127.0.0.1:%d  (nur auf diesem Rechner%s)%n",
                    port, "0.0.0.0".equals(bindeadresse) ? ", über das Port-Mapping" : "");
        } catch (Exception e) {
            // ALLES fangen, nicht nur IOException. Am 13.08.2026 riss ein
            // doppelt angemeldeter Kontext (IllegalArgumentException) den
            // ganzen Agenten in eine Neustartschleife — die Zusage „die
            // Kernaufgabe hängt nicht an einer Anzeige" war damit gebrochen.
            // Der TI-Zugang einer Praxis darf nicht an unserer Verwaltungs-
            // schicht hängen (ADR-0002).
            System.out.println("  Oberfläche nicht gestartet: " + e
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
        ex.getResponseHeaders().add("Location", "/einstellungen");
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

    /**
     * Die Bereiche der Oberfläche.
     *
     * <p>Eigene Seiten statt einer langen (PO, 13.08.2026) — dieselbe Bauweise
     * wie in der Zentrale, damit sich niemand umgewöhnen muss. Bei 36 Geräten
     * war die eine Seite schon zu lang, um noch etwas zu finden.
     */
    private enum Bereich {
        UEBERSICHT("/",             "Übersicht"),
        GERAETE("/geraete",         "Geräte"),
        MELDUNGEN("/meldungen",     "Meldungen"),
        EINSTELLUNGEN("/einstellungen", "Einstellungen");

        final String pfad, titel;
        Bereich(String pfad, String titel) { this.pfad = pfad; this.titel = titel; }

        static Bereich ausPfad(String pfad) {
            for (var b : values()) if (b.pfad.equals(pfad)) return b;
            return null;
        }
    }

    private void seite(HttpExchange ex) throws IOException {
        var b = Bereich.ausPfad(ex.getRequestURI().getPath());
        if (b == null) {
            antworten(ex, 404, "text/plain; charset=utf-8", "Diese Seite gibt es nicht.");
            return;
        }
        antworten(ex, 200, "text/html; charset=utf-8", html(b));
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

    /**
     * Der Rahmen: Seitenleiste links, Inhalt rechts.
     *
     * <p>Nachgebaut nach der Zentrale (PO, 13.08.2026) — gleiche Farben,
     * gleiche Radien, gleiche Anordnung. <b>Nachgebaut, nicht nachgeladen:</b>
     * Der Agent holt sich nichts von der Zentrale, sonst wäre die Seite genau
     * dann kaputt, wenn die Verbindung dorthin fehlt — also in dem Fall, für
     * den es sie gibt.
     */
    private String html(Bereich aktiv) {
        var s = new StringBuilder();
        s.append("""
                <!doctype html><html lang="de"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <!-- Aktualisiert sich selbst: Wer bei der Inbetriebnahme etwas
                     ändert, soll die Wirkung sehen, ohne neu zu laden. -->
                <meta http-equiv="refresh" content="15">
                <title>%s · Standort-Agent</title><style>%s</style></head><body>
                <div class="rahmen">%s<main>%s</main></div></body></html>"""
                .formatted(esc(aktiv.titel), stil(), leiste(aktiv), inhalt(aktiv)));
        return s.toString();
    }

    /** Die Seitenleiste — Marke, Bereiche, Zustand. */
    private String leiste(Bereich aktiv) {
        var s = new StringBuilder("<aside class=\"leiste\">");
        s.append("""
                <div class="marke-kopf">
                  <div class="marke-zeichen">PR</div>
                  <div class="marke-text">Standort-Agent<small>%s</small><small>%s</small></div>
                </div>"""
                .formatted(esc(zustand.einrichtung().isBlank()
                                ? "keiner Einrichtung zugeordnet" : zustand.einrichtung()),
                        esc("unbekannt".equals(zustand.version())
                                ? "Entwicklungsstand" : "Version " + zustand.version())));

        s.append("<nav class=\"bereiche\"><div class=\"bereich-titel\">Betrieb</div>");
        for (var b : Bereich.values()) {
            if (b == Bereich.EINSTELLUNGEN) {
                s.append("<div class=\"bereich-titel\">Verwaltung</div>");
            }
            s.append("<a href=\"").append(b.pfad).append("\"")
             .append(b == aktiv ? " aria-current=\"page\"" : "").append(">")
             .append(sinnbild(b)).append(esc(b.titel));
            // Die Zahl offener Punkte gehört an den Bereich, nicht in eine
            // Meldung, die man wegklickt.
            if (b == Bereich.UEBERSICHT) {
                long offen = zustand.pruefliste().stream().filter(x -> !x.erfuellt()).count();
                if (offen > 0) s.append("<span class=\"zahl-bereich\">").append(offen)
                                .append("</span>");
            }
            if (b == Bereich.GERAETE) {
                long klemmt = zustand.geraete().values().stream()
                        .filter(g -> g.hindernis() != null).count();
                if (klemmt > 0) s.append("<span class=\"zahl-bereich\">").append(klemmt)
                                 .append("</span>");
            }
            s.append("</a>");
        }
        s.append("</nav>");

        boolean ok = zustand.arbeitsfaehig();
        s.append("<div class=\"leiste-fuss\"><div class=\"angemeldet\">")
         .append("<div class=\"konto-kreis ").append(ok ? "kreisGut" : "kreisSchlecht")
         .append("\">").append(ok ? "✓" : "×").append("</div>")
         .append("<div class=\"konto-text\"><b>").append(ok ? "arbeitsfähig" : "nicht arbeitsfähig")
         .append("</b><span>").append(zustand.notAus() ? "Not-Aus aktiv"
                 : "läuft seit " + esc(Betriebszustand.vorWieLange(zustand.start())))
         .append("</span></div></div></div>");
        return s.append("</aside>").toString();
    }

    /** Kleine Sinnbilder wie in der Zentrale — im Code, nicht nachgeladen. */
    private static String sinnbild(Bereich b) {
        String pfade = switch (b) {
            case UEBERSICHT -> "<path d=\"M3 13.5 12 5l9 8.5\"/><path d=\"M5.5 11.8V20h13v-8.2\"/>";
            case GERAETE -> "<rect x=\"6\" y=\"2.8\" width=\"12\" height=\"18.4\" rx=\"2.2\"/>"
                          + "<rect x=\"8.4\" y=\"5.4\" width=\"7.2\" height=\"4.4\" rx=\"1\"/>"
                          + "<path d=\"M9.6 19h4.8\"/>";
            case MELDUNGEN -> "<path d=\"M5 4.5h14v12H8l-3 3z\"/><path d=\"M8.5 8.5h7M8.5 12h4.5\"/>";
            case EINSTELLUNGEN -> "<path d=\"M4 7h16M4 12h16M4 17h16\"/>"
                          + "<circle cx=\"9\" cy=\"7\" r=\"1.8\"/><circle cx=\"15\" cy=\"17\" r=\"1.8\"/>";
        };
        return "<svg viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" "
             + "stroke-width=\"1.7\" stroke-linecap=\"round\" stroke-linejoin=\"round\" "
             + "aria-hidden=\"true\">" + pfade + "</svg>";
    }

    private String inhalt(Bereich b) {
        return switch (b) {
            case UEBERSICHT    -> uebersicht();
            case GERAETE       -> geraeteseite();
            case MELDUNGEN     -> meldungsseite();
            case EINSTELLUNGEN -> einstellungsseite();
        };
    }

    private String seitenkopf(String titel, String unterzeile, String rechts) {
        return """
                <div class="seitenkopf"><div><h2>%s</h2>
                <p class="unterzeile">%s</p></div>
                <div class="kopf-rechts">%s</div></div>"""
                .formatted(esc(titel), esc(unterzeile), rechts == null ? "" : rechts);
    }

    private String zustandsmarke() {
        boolean ok = zustand.arbeitsfaehig();
        return "<span class=\"marke " + (ok ? "gut" : "schlecht") + "\">"
             + (ok ? "✓ arbeitsfähig" : "× nicht arbeitsfähig") + "</span>";
    }

    // ------------------------------------------------------------ Übersicht

    private String uebersicht() {
        var s = new StringBuilder(seitenkopf("Übersicht",
                "Ist der Agent arbeitsfähig — und was fehlt noch?", zustandsmarke()));
        s.append(meldungsband());

        if (zustand.notAus()) {
            s.append("""
                    <div class="tafel hinweisTafel schlechtHg">
                      <strong>Not-Aus ist aktiv.</strong> Die Zentrale hat die Automatik
                      angehalten. Es werden keine Karten freigeschaltet, bis er dort wieder
                      aufgehoben wird.</div>""");
        }

        s.append("<div class=\"tafel\"><h3>Inbetriebnahme</h3><ol class=\"liste\">");
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
        s.append("</ol></div>");

        s.append("<div class=\"lage\">");
        kachel(s, "Kartenterminals", zustand.terminals() + "",
                zustand.terminalsErreichbar() + " erreichbar");
        kachel(s, "SMC-B", zustand.smcbKarten() + "",
                zustand.pinsHinterlegt() + " mit hinterlegter PIN");
        kachel(s, "Offene Verbindungen", zustand.verbindungenOffen() + "",
                "zu Kartenterminals");
        kachel(s, "Grundtakt", zustand.grundtakt() + " s", "Prüfabstand");
        s.append("</div>");

        s.append("<div class=\"tafel\"><h3>Verbindungen</h3><dl class=\"angaben\">");
        angabe(s, "Zentrale", zustand.zentrale());
        angabe(s, "zuletzt erreicht", Betriebszustand.vorWieLange(zustand.zentraleGesehen()));
        angabe(s, "Konnektor", zustand.konnektorAdresse());
        angabe(s, "zuletzt erreicht", Betriebszustand.vorWieLange(zustand.konnektorGesehen()));
        angabe(s, "läuft seit", Betriebszustand.vorWieLange(zustand.start()));
        return s.append("</dl></div>").toString();
    }

    // -------------------------------------------------------------- Geräte

    private String geraeteseite() {
        var geraete = zustand.geraete();
        long klemmt = geraete.values().stream().filter(g -> g.hindernis() != null).count();
        var s = new StringBuilder(seitenkopf("Geräte",
                "Was der Konnektor über die Kartenterminals meldet.",
                klemmt > 0 ? "<span class=\"marke schlecht\">" + klemmt + " mit Hindernis</span>"
                           : "<span class=\"marke gut\">✓ alle bereit</span>"));
        s.append(meldungsband());

        if (geraete.isEmpty()) {
            return s.append("""
                    <div class="tafel"><p class="leer">Noch keine Geräte gemeldet. Sobald der
                       Agent den Konnektor erreicht, stehen sie hier.</p></div>""").toString();
        }

        s.append("<div class=\"tafel\"><h3>Kartenterminals <span class=\"zaehler\">")
         .append(geraete.size()).append("</span></h3><div class=\"rollbar\"><table><thead><tr>")
         .append("<th>Gerät</th><th>Modell</th><th>Zustand</th><th>SMC-B</th>")
         .append("<th class=\"zahlSpalte\">Versuche</th><th>geprüft</th>")
         .append("</tr></thead><tbody>");

        geraete.values().stream()
            .sorted(java.util.Comparator.comparing(Betriebszustand.Geraetezeile::id))
            .forEach(g -> {
                String hindernis = g.hindernis();
                s.append("<tr class=\"").append(hindernis != null ? "nein" : "").append("\">");

                s.append("<td><div class=\"kennung\">").append(esc(g.id())).append("</div>");
                if (g.name() != null && !g.name().isBlank() && !g.name().equals(g.id())) {
                    s.append("<div class=\"klein\">").append(esc(g.name())).append("</div>");
                }
                s.append("</td>");

                // Modell und Firmware — die erste Frage bei jeder Rückfrage an
                // den Hersteller.
                s.append("<td class=\"klein\">").append(esc(nz(g.modell())));
                if (g.firmware() != null && !g.firmware().isBlank()) {
                    s.append("<div>Firmware ").append(esc(g.firmware())).append("</div>");
                }
                s.append("</td>");

                s.append("<td>");
                if (!g.erreichbar())            s.append(marke("schlecht", "antwortet nicht"));
                else if (g.aktualisierend())    s.append(marke("warn", "wird aktualisiert"));
                else if (!g.pevimUnterstuetzt())s.append(marke("", "kein PEviM"));
                else if (!g.pevimAktiv())       s.append(marke("warn", "PEviM aus"));
                else                            s.append(marke("gut", "bereit"));
                if (g.verbindung()) s.append("<div class=\"klein\">Verbindung steht</div>");
                s.append("</td>");

                s.append("<td>");
                if (!g.smcbVorhanden()) {
                    s.append("<span class=\"klein\">keine eingelegt</span>");
                } else {
                    s.append(esc(g.pinStatus())).append("<div class=\"klein\">")
                     .append(g.pinHinterlegt() ? "PIN hinterlegt" : "keine PIN hinterlegt")
                     .append("</div>");
                }
                s.append("</td>");

                // Die wichtigste Zahl: Bei 0 ist die Karte gesperrt.
                s.append("<td class=\"zahlSpalte\">");
                if (!g.smcbVorhanden() || g.versuche() < 0) {
                    s.append("—");
                } else {
                    s.append("<span class=\"").append(g.versuche() == 0 ? "schlechtText"
                                    : g.versuche() <= 1 ? "warnText" : "")
                     .append("\">").append(g.versuche()).append("</span>");
                }
                s.append("</td>");

                s.append("<td class=\"klein\">")
                 .append(esc(Betriebszustand.vorWieLange(g.geprueft()))).append("</td></tr>");

                if (hindernis != null) {
                    s.append("<tr class=\"hindernisZeile\"><td colspan=\"6\">")
                     .append("<div class=\"handlung\">").append(esc(hindernis))
                     .append("</div></td></tr>");
                }
            });
        return s.append("""
                </tbody></table></div>
                <p class="klein">„Versuche“ ist der Fehlversuchszähler der SMC-B. Bei 0 ist die
                   Karte gesperrt und lässt sich nur mit der PUK wieder freischalten — der
                   Roboter versucht es dann nicht mehr.</p></div>""").toString();
    }

    private static String marke(String art, String text) {
        return "<span class=\"marke " + art + "\">" + esc(text) + "</span>";
    }

    // ----------------------------------------------------------- Meldungen

    private String meldungsseite() {
        var liste = zustand.letzteMeldungen();
        var s = new StringBuilder(seitenkopf("Meldungen",
                "Was zuletzt geschehen ist — für die Fehlersuche.",
                "<span class=\"marke\">" + liste.size() + " Einträge</span>"));
        s.append(meldungsband());

        if (liste.isEmpty()) {
            return s.append("""
                    <div class="tafel"><p class="leer">Noch nichts zu berichten. Das ist der
                       Normalfall, solange alles läuft.</p></div>""").toString();
        }
        s.append("<div class=\"tafel\"><div class=\"rollbar\"><table><tbody>");
        var uhr = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
        for (var m : liste) {
            s.append("<tr class=\"").append(m.fehler() ? "nein" : "").append("\">")
             .append("<td class=\"zeit\">").append(uhr.format(m.zeit())).append("</td>")
             .append("<td>").append(esc(m.text())).append("</td></tr>");
        }
        return s.append("""
                </tbody></table></div>
                <p class="klein">Diese Liste liegt nur im Arbeitsspeicher und ist nach einem
                   Neustart leer. Das dauerhafte Protokoll führt die Zentrale — dort steht
                   jede Handlung mit Vorgangsnummer.</p></div>""").toString();
    }

    // -------------------------------------------------------- Einstellungen

    private String einstellungsseite() {
        var s = new StringBuilder(seitenkopf("Einstellungen",
                "Was sich hier ändern lässt, ohne an die Konfigurationsdatei zu gehen.", null));
        s.append(meldungsband());

        s.append("""
                <div class="tafel"><h3>Adresse der Zentrale</h3>
                <p class="unterzeile">Ändern Sie diese Angabe, wenn die Zentrale
                   <strong>umgezogen ist</strong> — etwa auf einen anderen Server oder Namen.
                   Der Agent prüft die neue Adresse, <em>bevor</em> er sie übernimmt: Antwortet
                   dort keine betriebsbereite Zentrale, bleibt alles beim Alten.</p>
                <form method="post" action="/zentrale" class="maske">
                  <div class="feldgruppe">
                    <label for="adr">Adresse</label>
                    <input id="adr" name="adresse" type="url" required value="%s"
                           placeholder="https://pin-roboter.example.de/">
                  </div>
                  <button type="submit">Prüfen und übernehmen</button>
                </form>"""
                .formatted(esc(adresse.aktuell().toString())));
        if (adresse.abweichend()) {
            s.append("<p class=\"klein\">Hier gilt eine gespeicherte Adresse. Die Datei "
                    + "<code>.env</code> nennt <code>")
             .append(esc(adresse.ausUmgebung().toString()))
             .append("</code> — dieser Wert wird nicht mehr verwendet. Um wieder auf ihn "
                    + "zurückzugehen, tragen Sie ihn hier ein.</p>");
        }
        s.append("</div>");

        s.append("""
                <div class="tafel"><h3>Was hier nicht geändert werden kann</h3>
                <p class="unterzeile">Adresse des Konnektors, Adresse der Kartenterminals,
                   Vertrauensanker und Aufrufkontext stehen in der Datei <code>.env</code>
                   neben der <code>docker-compose.yml</code>. Sie gehören zur Einrichtung
                   dieses Standorts und ändern sich nicht im Betrieb — anders als die Adresse
                   der Zentrale, die bei einem Serverumzug wechselt.</p>
                <p class="unterzeile">Nach einer Änderung dort:
                   <code>docker compose up -d</code></p></div>""");

        s.append("<div class=\"tafel\"><h3>Angaben zu diesem Agenten</h3><dl class=\"angaben\">");
        angabe(s, "Version", "unbekannt".equals(zustand.version())
                ? "Entwicklungsstand" : zustand.version());
        angabe(s, "Einrichtung", zustand.einrichtung());
        angabe(s, "Konnektor", zustand.konnektorAdresse());
        angabe(s, "Grundtakt", zustand.grundtakt() + " Sekunden");
        angabe(s, "läuft seit", Betriebszustand.vorWieLange(zustand.start()));
        return s.append("</dl></div>").toString();
    }

    /** Die Rückmeldung der letzten Änderung — auf jeder Seite, dann verbraucht. */
    private String meldungsband() {
        if (meldung == null) return "";
        String m = meldung;
        boolean fehler = meldungIstFehler;
        meldung = null;
        return "<div class=\"tafel hinweisTafel " + (fehler ? "schlechtHg" : "gutHg") + "\">"
             + esc(m) + "</div>";
    }

    private static void kachel(StringBuilder s, String titel, String zahl, String zusatz) {
        s.append("<div class=\"kachel\"><div class=\"bez\">").append(esc(titel))
         .append("</div><div class=\"zahl\">").append(esc(zahl))
         .append("</div><span class=\"fuss\">").append(esc(zusatz)).append("</span></div>");
    }

    private static void angabe(StringBuilder s, String was, String wert) {
        s.append("<dt>").append(esc(was)).append("</dt><dd>")
         .append(esc(wert == null || wert.isBlank() ? "—" : wert)).append("</dd>");
    }

    private static String nz(String s) { return s == null || s.isBlank() ? "—" : s; }

    /**
     * Der Stil — nachgebaut nach dem der Zentrale.
     *
     * <p>Dieselben Farbwerte, dasselbe Abstandsraster, dieselben Radien. Eine
     * Kopie ist hier das kleinere Übel: Nachladen scheidet aus (siehe
     * {@link #html}), und ein zweites Stylesheet auszuliefern hieße, es in zwei
     * Bausteinen zu pflegen. Der Agent zeigt vier Seiten — dafür genügt ein
     * Ausschnitt.
     */
    private static String stil() {
        return """
                :root{--grund:#e8ecf3;--grund-tief:#dbe2ee;--karte:#fff;
                  --glas:rgba(255,255,255,.78);--glas-rand:rgba(255,255,255,.85);--rand:#c3cddd;
                  --text:#101623;--gedaempft:#55606f;
                  --ok:#0f7b3f;--ok-hg:#e2f2e9;--warnung:#8a5300;--warnung-hg:#fbf0dd;
                  --fehler:#a51f1f;--fehler-hg:#fbe7e7;
                  --akzent:#1e3a8f;--akzent-hg:#e3e9f8;--auf-akzent:#fff;
                  --r1:.25rem;--r2:.5rem;--r3:.75rem;--r4:1rem;--r5:1.5rem;
                  --radius:18px;--radius-klein:10px;
                  --schatten:0 1px 2px rgba(16,22,35,.05),0 8px 24px -8px rgba(16,22,35,.14);
                  --schrift:system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;
                  --maschine:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace}
                @media(prefers-color-scheme:dark){:root{--grund:#0c1017;--grund-tief:#070a10;
                  --karte:#181f2d;--glas:rgba(24,31,45,.76);--glas-rand:rgba(120,140,175,.16);
                  --rand:#2b3648;--text:#e7ecf4;--gedaempft:#98a4b6;
                  --ok:#5dcb8d;--ok-hg:#10281c;--warnung:#e3ad5c;--warnung-hg:#2a2013;
                  --fehler:#f28c8c;--fehler-hg:#2c1717;
                  --akzent:#93b4ff;--akzent-hg:#1a2440;--auf-akzent:#0c1017;
                  --schatten:0 1px 2px rgba(0,0,0,.5),0 12px 32px -12px rgba(0,0,0,.7)}}
                *{box-sizing:border-box}
                body{margin:0;min-height:100vh;background:var(--grund);color:var(--text);
                  font:15px/1.55 var(--schrift);-webkit-font-smoothing:antialiased}
                a{color:var(--akzent)}
                :focus-visible{outline:3px solid var(--akzent);outline-offset:2px;border-radius:6px}
                .rahmen{display:grid;grid-template-columns:15.5rem 1fr;gap:var(--r4);
                  padding:var(--r4);max-width:1500px;margin:0 auto;min-height:100vh}
                .leiste,.tafel,.seitenkopf,.kachel{background:var(--karte);
                  border:1px solid var(--rand);border-radius:var(--radius);
                  box-shadow:var(--schatten)}
                @supports (backdrop-filter:blur(1px)){.leiste,.tafel,.seitenkopf,.kachel{
                  background:var(--glas);backdrop-filter:blur(22px) saturate(150%);
                  -webkit-backdrop-filter:blur(22px) saturate(150%);
                  border-color:var(--glas-rand)}}
                .leiste{position:sticky;top:var(--r4);align-self:start;
                  max-height:calc(100vh - 2*var(--r4));display:flex;flex-direction:column;
                  padding:var(--r4) var(--r3);overflow-y:auto}
                .marke-kopf{display:flex;align-items:center;gap:var(--r3);
                  padding:var(--r1) var(--r2) var(--r4)}
                .marke-zeichen{width:2.1rem;height:2.1rem;border-radius:9px;flex:none;
                  display:grid;place-items:center;background:var(--akzent);
                  color:var(--auf-akzent);font-weight:700;font-size:.82rem}
                .marke-text{font-weight:660;letter-spacing:-.02em;line-height:1.15;min-width:0}
                .marke-text small{display:block;font-weight:400;font-size:.74rem;
                  color:var(--gedaempft);overflow:hidden;text-overflow:ellipsis;
                  white-space:nowrap}
                nav.bereiche{display:flex;flex-direction:column;gap:var(--r1)}
                .bereich-titel{font-size:.68rem;text-transform:uppercase;letter-spacing:.09em;
                  color:var(--gedaempft);font-weight:600;padding:var(--r4) var(--r3) var(--r1)}
                nav.bereiche a{display:flex;align-items:center;gap:var(--r2);width:100%;
                  padding:var(--r2) var(--r3);border-radius:var(--radius-klein);
                  color:var(--gedaempft);text-decoration:none;font-weight:520;font-size:.92rem;
                  transition:background .16s,color .16s}
                nav.bereiche a:hover{background:var(--akzent-hg);color:var(--akzent)}
                nav.bereiche a[aria-current="page"]{background:var(--akzent-hg);
                  color:var(--akzent);font-weight:650}
                nav.bereiche svg{flex:none;width:1.05rem;height:1.05rem}
                .zahl-bereich{margin-left:auto;font:600 .74rem/1 var(--maschine);
                  padding:var(--r1) var(--r2);border-radius:5px;background:var(--fehler-hg);
                  color:var(--fehler)}
                .leiste-fuss{margin-top:auto;padding-top:var(--r4);
                  border-top:1px solid var(--rand)}
                .angemeldet{display:flex;align-items:center;gap:var(--r2);
                  padding:var(--r1) var(--r2);min-width:0}
                .konto-kreis{width:1.9rem;height:1.9rem;border-radius:50%;flex:none;display:grid;
                  place-items:center;font-weight:660;font-size:.9rem}
                .kreisGut{background:var(--ok-hg);color:var(--ok)}
                .kreisSchlecht{background:var(--fehler-hg);color:var(--fehler)}
                .konto-text{font-size:.84rem;line-height:1.25;min-width:0}
                .konto-text b{font-weight:600;display:block}
                .konto-text span{color:var(--gedaempft);font-size:.76rem}
                main{min-width:0;display:flex;flex-direction:column;gap:var(--r4)}
                .seitenkopf{display:flex;align-items:flex-start;gap:var(--r4);
                  padding:var(--r4) var(--r5);flex-wrap:wrap}
                .seitenkopf h2{margin:0;font-size:1.32rem;font-weight:680;letter-spacing:-.025em}
                .kopf-rechts{margin-left:auto;display:flex;gap:var(--r2);align-items:center}
                h3{font-size:1.02rem;margin:0 0 var(--r3);font-weight:660}
                .unterzeile{color:var(--gedaempft);margin:var(--r1) 0 0;font-size:.9rem}
                .tafel{padding:var(--r4) var(--r5)}
                .hinweisTafel{font-weight:520}
                .gutHg{background:var(--ok-hg);color:var(--ok);border-color:var(--ok)}
                .schlechtHg{background:var(--fehler-hg);color:var(--fehler);
                  border-color:var(--fehler)}
                .lage{display:grid;grid-template-columns:repeat(auto-fit,minmax(13rem,1fr));
                  gap:var(--r4)}
                .kachel{padding:var(--r4) var(--r5);display:flex;flex-direction:column}
                .kachel .bez{font-size:.74rem;text-transform:uppercase;letter-spacing:.07em;
                  color:var(--gedaempft);font-weight:620;margin-bottom:var(--r1)}
                .kachel .zahl{font:700 2rem/1.1 var(--schrift);letter-spacing:-.035em;
                  font-variant-numeric:tabular-nums;margin-top:auto}
                .kachel .fuss{font-size:.8rem;color:var(--gedaempft);margin-top:var(--r1)}
                .marke{display:inline-flex;align-items:center;gap:var(--r1);
                  padding:.15rem .6rem;border-radius:999px;font-size:.8rem;font-weight:600;
                  background:var(--grund-tief);color:var(--gedaempft)}
                .marke.gut{background:var(--ok-hg);color:var(--ok)}
                .marke.schlecht{background:var(--fehler-hg);color:var(--fehler)}
                .marke.warn{background:var(--warnung-hg);color:var(--warnung)}
                .zaehler{font:600 .74rem/1 var(--maschine);padding:var(--r1) var(--r2);
                  border-radius:5px;background:var(--akzent-hg);color:var(--akzent);
                  margin-left:var(--r2)}
                .liste{list-style:none;margin:0;padding:0}
                .liste li{display:flex;gap:var(--r3);padding:var(--r3) 0;
                  border-top:1px solid var(--rand)}
                .liste li:first-child{border-top:0;padding-top:0}
                .zeichen{flex:none;width:1.5rem;height:1.5rem;border-radius:999px;display:grid;
                  place-items:center;font-weight:700}
                .ja .zeichen{background:var(--ok-hg);color:var(--ok)}
                .nein .zeichen{background:var(--fehler-hg);color:var(--fehler)}
                .klein{font-size:.84rem;color:var(--gedaempft)}
                .leer{color:var(--gedaempft);text-align:center;padding:var(--r5) 0;margin:0}
                .handlung{margin-top:var(--r1);padding-left:var(--r3);font-size:.88rem;
                  border-left:3px solid var(--akzent);color:var(--text)}
                .angaben{display:grid;grid-template-columns:auto 1fr;gap:var(--r2) var(--r4);
                  margin:0}
                .angaben dt{color:var(--gedaempft);font-size:.9rem}
                .angaben dd{margin:0;word-break:break-all}
                .rollbar{overflow-x:auto}
                table{width:100%;border-collapse:collapse;font-size:.9rem}
                th{text-align:left;padding:var(--r2);font-size:.72rem;font-weight:620;
                  text-transform:uppercase;letter-spacing:.06em;color:var(--gedaempft);
                  border-bottom:1px solid var(--rand);white-space:nowrap}
                td{padding:var(--r3) var(--r2);border-top:1px solid var(--rand);
                  vertical-align:top}
                tbody tr:first-child td{border-top:0}
                tr.nein td{background:var(--fehler-hg)}
                .hindernisZeile td{padding-top:0;border-top:0}
                .zeit,.kennung{white-space:nowrap;color:var(--gedaempft);
                  font-family:var(--maschine);font-size:.84rem}
                .kennung{color:var(--text);font-weight:600}
                .zahlSpalte{text-align:right;font-variant-numeric:tabular-nums;font-weight:640}
                .schlechtText{color:var(--fehler)}
                .warnText{color:var(--warnung)}
                .maske{display:flex;flex-wrap:wrap;gap:var(--r3);align-items:end;
                  margin:var(--r4) 0 0}
                .feldgruppe{flex:1;min-width:18rem}
                label{display:block;font-size:.84rem;color:var(--gedaempft);
                  margin-bottom:var(--r1);font-weight:560}
                input{width:100%;padding:var(--r2) var(--r3);border:1px solid var(--rand);
                  border-radius:var(--radius-klein);background:var(--karte);color:var(--text);
                  font:inherit}
                button{padding:var(--r2) var(--r4);border:0;border-radius:var(--radius-klein);
                  background:var(--akzent);color:var(--auf-akzent);font:inherit;font-weight:600;
                  cursor:pointer}
                button:hover{filter:brightness(1.15)}
                code{font-family:var(--maschine);font-size:.9em}
                @media(max-width:60rem){.rahmen{grid-template-columns:1fr}
                  .leiste{position:static;max-height:none}
                  nav.bereiche{flex-direction:row;flex-wrap:wrap}
                  .bereich-titel{display:none}
                  .leiste-fuss{margin-top:var(--r4)}}
                @media(prefers-reduced-motion:reduce){*{transition:none!important}}
                """;
    }

    /** Kein Framework, also von Hand — und lieber einmal zu viel. */
    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
