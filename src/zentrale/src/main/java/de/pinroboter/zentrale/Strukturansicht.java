package de.pinroboter.zentrale;

import de.pinroboter.gemeinsam.Ampel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static de.pinroboter.zentrale.Zentrale.esc;

/**
 * Die Organisationsstruktur als Bild statt als Tabelle.
 *
 * <h2>Wozu eine zweite Sicht auf dieselben Daten</h2>
 *
 * Die Tabelle beantwortet gut: <em>„Welche Einheiten gibt es?"</em> Sie
 * beantwortet schlecht: <em>„Wie hängt das zusammen, und wo klemmt es?"</em>
 * Bei fünf möglichen Ebenen — Trägergesellschaft, Leistungserbringer, Standort,
 * Betriebsstätte, Fachbereich — ist eine eingerückte Liste nicht mehr auf einen
 * Blick zu erfassen.
 *
 * <p>Die Strukturansicht zeigt dieselben Einheiten als <b>Karten in einem
 * Baum</b>: jede mit ihrem Symbol, ihren Zahlen und der Ampel <em>ihres
 * Teilbaums</em>. Wer auf die oberste Karte schaut, sieht, ob im ganzen Haus
 * etwas rot ist — ohne zu blättern.
 *
 * <h2>Entwurfsentscheidungen</h2>
 *
 * <p><b>HTML und CSS, kein gezeichnetes Diagramm.</b> Ein SVG-Organigramm sieht
 * auf einem Bildschirm gut aus und versagt auf jedem anderen: Es lässt sich
 * nicht umbrechen, nicht mit der Tastatur bedienen, nicht vergrößern, und ein
 * Screenreader findet darin keine Reihenfolge. Die Karten sind gewöhnliche
 * Verweise in verschachtelten Listen; die Linien dazwischen zeichnet CSS.
 * Damit ist die Ansicht ohne Zusatzaufwand tastaturbedienbar und vorlesbar.
 *
 * <p><b>Kein JavaScript zum Auf- und Zuklappen.</b> Tiefe Ebenen stecken in
 * {@code <details>} — ein Browserelement, das Tastatur, Screenreader und die
 * Suchfunktion des Browsers von sich aus beherrschen.
 *
 * <p><b>Geräte werden gezeigt, solange es wenige sind.</b> Unter jeder Einheit
 * stehen ihre Kartenterminals als Kacheln — bis zwölf Stück. Darüber tritt an
 * ihre Stelle ein Verweis in die Geräteliste, die für große Zahlen gebaut ist
 * (Suche, Filter, Blätterung). Bei 5.000 Geräten wäre ein Knoten je Gerät weder
 * darstellbar noch lesbar; die Zahl steht aber im Verweis, damit nichts
 * stillschweigend verschwindet.
 *
 * <p><b>Farbe ist nie die einzige Aussage</b> (WCAG 1.4.1). Jede Ampel trägt
 * ein eigenes Sinnbild und einen Text.
 */
final class Strukturansicht {

    private Strukturansicht() { }

    /** Zahlen und Zustand eines Teilbaums — das, was auf einer Karte steht. */
    record Bilanz(int terminals, int konnektoren, int benutzer,
                  int ok, int warnung, int fehler, int unbekannt) {

        static final Bilanz LEER = new Bilanz(0, 0, 0, 0, 0, 0, 0);

        Bilanz plus(Bilanz a) {
            return new Bilanz(terminals + a.terminals, konnektoren + a.konnektoren,
                    benutzer + a.benutzer, ok + a.ok, warnung + a.warnung,
                    fehler + a.fehler, unbekannt + a.unbekannt);
        }

        /**
         * Die Gesamtbewertung des Teilbaums — der schlechteste Zustand darin.
         *
         * <p>Ein Mittelwert wäre gefährlich: Neunundneunzig gesunde Geräte
         * dürfen ein ausgefallenes nicht grün färben.
         */
        Ampel ampel() {
            if (fehler > 0)  return Ampel.FEHLER;
            if (warnung > 0) return Ampel.WARNUNG;
            if (ok > 0)      return Ampel.OK;
            return Ampel.UNBEKANNT;
        }

        /** Klartext für Screenreader und Kurzhinweis. */
        String klartext() {
            if (terminals == 0) return "keine Kartenterminals";
            List<String> teile = new ArrayList<>();
            if (fehler > 0)    teile.add(fehler + " mit Handlungsbedarf");
            if (warnung > 0)   teile.add(warnung + " mit Aufmerksamkeit");
            if (ok > 0)        teile.add(ok + " in Ordnung");
            if (unbekannt > 0) teile.add(unbekannt + " ohne Rückmeldung");
            return terminals + " Kartenterminal(s): " + String.join(", ", teile);
        }
    }

    /**
     * Ein Kartenterminal, wie es unter seiner Einheit erscheint.
     *
     * @param smcb  ob eine SMC-B darin steckt. Nur diese Geräte nimmt die
     *              PIN-Automatik überhaupt in die Hand (A_24080) — das gehört
     *              sichtbar gemacht, sonst wundert man sich über 35 „stille"
     *              Terminals.
     */
    record Geraet(String terminalId, String name, boolean smcb, Ampel ampel) {}

    /**
     * Ein Konnektor unter seiner Einheit.
     *
     * @param betreibt an der betreibenden Einheit {@code true}, beim Mitnutzer
     *        {@code false} (ADR-0012)
     */
    record Konnektor(long id, String name, String art, boolean betreibt) {}

    /**
     * Ab dieser Zahl werden die Geräte einer Einheit nicht mehr einzeln
     * gezeigt, sondern verlinkt.
     *
     * <p>Zwölf ist keine gemessene Grenze, sondern eine gestalterische: Darüber
     * hinaus liest niemand mehr Kacheln, sondern sucht — und dafür gibt es die
     * Geräteliste mit Suche, Filter und Blätterung.
     */
    private static final int GERAETE_HOECHSTENS = 12;

    /**
     * Summiert die Bilanzen über den Baum: jede Einheit bekommt, was in ihrem
     * <b>Teilbaum</b> steckt.
     *
     * <p>Der Baum kommt in Tiefensuchreihenfolge — also wird er <b>rückwärts</b>
     * durchlaufen. Dann hat jedes Kind seine eigene Summe bereits, wenn sein
     * Elternteil an die Reihe kommt: eine Schleife statt Rekursion, ein
     * Durchlauf statt einem je Ebene. Bei einem Klinikverbund mit hunderten
     * Einheiten ist das der Unterschied zwischen einer Seite und einer
     * Wartezeit.
     *
     * @param eigene nur die unmittelbar an einer Einheit hängenden Dinge
     * @return dieselbe Abbildung, aber über den Teilbaum summiert
     */
    static Map<Long, Bilanz> summieren(List<Organisationsverwaltung.Einheit> baum,
                                       Map<Long, Bilanz> eigene) {
        Map<Long, Bilanz> summe = new java.util.HashMap<>(eigene);
        for (int i = baum.size() - 1; i >= 0; i--) {
            var e = baum.get(i);
            if (e.elternId() == null) continue;
            var kind = summe.getOrDefault(e.id(), Bilanz.LEER);
            summe.merge(e.elternId(), kind, Bilanz::plus);
        }
        return summe;
    }

    /**
     * Baut die Ansicht.
     *
     * @param baum      die Einheiten des Geltungsbereichs, in Baumreihenfolge
     * @param bilanzen  Bilanz je Einheit, <b>bereits über den Teilbaum summiert</b>
     * @param geraete   Geräte je Einheit, <b>nur die direkt zugeordneten</b>
     * @param konnektoren Konnektoren je Einheit — betriebene und mitgenutzte
     */
    static String tafel(List<Organisationsverwaltung.Einheit> baum,
                        Map<Long, Bilanz> bilanzen,
                        Map<Long, List<Geraet>> geraete,
                        Map<Long, List<Konnektor>> konnektoren) {
        if (baum.isEmpty()) {
            return """
                    <div class="tafel"><h3>Struktur</h3>
                    <p class="leer">Ihnen ist keine Organisationseinheit zugeordnet.
                       Wenden Sie sich an den Betreiber dieser Instanz.</p></div>""";
        }

        // Kinder je Elternteil, in der Reihenfolge des Baums.
        Map<Long, List<Organisationsverwaltung.Einheit>> kinder = new LinkedHashMap<>();
        List<Organisationsverwaltung.Einheit> wurzeln = new ArrayList<>();
        for (var e : baum) {
            if (e.elternId() == null || baum.stream().noneMatch(x -> x.id() == e.elternId())) {
                wurzeln.add(e);
            } else {
                kinder.computeIfAbsent(e.elternId(), k -> new ArrayList<>()).add(e);
            }
        }

        StringBuilder s = new StringBuilder();
        s.append("<div class=\"tafel\"><h3>Struktur <span class=\"zaehler\">")
         .append(baum.size()).append("</span></h3>");
        s.append(legende());
        s.append("<div class=\"strukturfeld\"><ul class=\"strukturbaum wurzel\">");
        for (var w : wurzeln) s.append(knoten(w, kinder, bilanzen, geraete, konnektoren, 0));
        s.append("</ul></div></div>");
        return s.toString();
    }

    /**
     * Ein Knoten samt seiner Nachkommen.
     *
     * <p>Ab der dritten Ebene wird zugeklappt. Der Grund ist nicht die Optik:
     * Ein Klinikverbund mit hunderten Fachbereichen ergäbe eine Seite, die
     * niemand überblickt und die im Browser spürbar ruckelt. Wieviel darin
     * steckt, steht am zugeklappten Element — sonst klappt man blind auf.
     */
    private static String knoten(Organisationsverwaltung.Einheit e,
                                 Map<Long, List<Organisationsverwaltung.Einheit>> kinder,
                                 Map<Long, Bilanz> bilanzen,
                                 Map<Long, List<Geraet>> geraete,
                                 Map<Long, List<Konnektor>> konnektoren, int tiefe) {
        var meine = kinder.getOrDefault(e.id(), List.of());
        StringBuilder s = new StringBuilder("<li class=\"strukturast\">");
        s.append(karte(e, bilanzen.getOrDefault(e.id(), Bilanz.LEER)));
        s.append(konnektorreihe(konnektoren.getOrDefault(e.id(), List.of())));
        s.append(geraetereihe(e, geraete.getOrDefault(e.id(), List.of())));

        if (!meine.isEmpty()) {
            boolean offen = tiefe < 2;
            int enthalten = zaehleNachkommen(e.id(), kinder);
            s.append("<details class=\"strukturzweig\"").append(offen ? " open" : "").append(">");
            s.append("<summary title=\"Zeigt die untergeordneten Einheiten von „")
             .append(esc(e.bezeichnung())).append("“ an oder blendet sie aus\">")
             .append("<span class=\"zweigzahl\">").append(meine.size()).append("</span> ")
             .append(meine.size() == 1 ? "untergeordnete Einheit" : "untergeordnete Einheiten")
             .append(enthalten > meine.size()
                     ? " <span class=\"klein\">(" + enthalten + " insgesamt)</span>" : "")
             .append("</summary>");
            s.append("<ul class=\"strukturbaum\">");
            for (var k : meine) {
                s.append(knoten(k, kinder, bilanzen, geraete, konnektoren, tiefe + 1));
            }
            s.append("</ul></details>");
        }
        return s.append("</li>").toString();
    }

    private static int zaehleNachkommen(long id,
            Map<Long, List<Organisationsverwaltung.Einheit>> kinder) {
        int summe = 0;
        for (var k : kinder.getOrDefault(id, List.of())) {
            summe += 1 + zaehleNachkommen(k.id(), kinder);
        }
        return summe;
    }

    /**
     * Eine Einheit als Karte.
     *
     * <p>Die ganze Karte ist ein Verweis — nicht ein kleiner Link darin. Ein
     * Klickziel von 200 Pixeln Breite trifft man auch mit dem Finger auf dem
     * Tablet und mit einer zittrigen Hand.
     */
    private static String karte(Organisationsverwaltung.Einheit e, Bilanz b) {
        Ampel a = b.ampel();
        StringBuilder zahlen = new StringBuilder();
        if (b.terminals > 0) {
            zahlen.append(zahl(Symbole.kartenterminal(), b.terminals,
                    b.terminals == 1 ? "Kartenterminal" : "Kartenterminals"));
        }
        if (b.konnektoren > 0) {
            zahlen.append(zahl(Symbole.konnektor(), b.konnektoren,
                    b.konnektoren == 1 ? "Konnektor" : "Konnektoren"));
        }
        if (b.benutzer > 0) {
            zahlen.append(zahl(Symbole.benutzer(), b.benutzer,
                    b.benutzer == 1 ? "Konto" : "Konten"));
        }
        if (zahlen.isEmpty()) {
            zahlen.append("<span class=\"strukturleer\">noch nichts zugeordnet</span>");
        }

        return """
                <a class="strukturkarte %s" href="/organisation/%d"
                   title="Öffnet „%s“ mit allem, was daran hängt">
                  <span class="kartensinnbild">%s</span>
                  <span class="kartentext">
                    <span class="kartenname">%s%s</span>
                    <span class="kartenart">%s</span>
                    <span class="kartenzahlen">%s</span>
                  </span>
                  <span class="kartenampel ampel-%s">%s<span class="nurLeser">%s — %s</span></span>
                </a>"""
                .formatted(a.css(), e.id(), esc(e.bezeichnung()),
                        Symbole.zuArt(e.art()),
                        esc(e.bezeichnung()),
                        e.istDemo() ? " <span class=\"marke\">Demo</span>" : "",
                        esc(e.artAnzeige()) + tiIdentitaet(e),
                        zahlen, a.css(), Symbole.zuAmpel(a), a.text(), esc(b.klartext()));
    }

    /**
     * Die Konnektoren einer Einheit — betriebene und mitgenutzte.
     *
     * <p>Sie stehen <b>vor</b> den Kartenterminals, weil sie das Bindeglied
     * sind: Ohne Konnektor gibt es keine PIN-Automatik, egal wie gesund die
     * Terminals aussehen (ADR-0011).
     *
     * <p>Ein mitgenutzter Konnektor ist gestrichelt umrandet und sagt es auch
     * im Text. Er gehört jemand anderem — wer das nicht sieht, sucht die
     * Zugangsdaten an der falschen Stelle.
     */
    private static String konnektorreihe(List<Konnektor> konnektoren) {
        if (konnektoren.isEmpty()) return "";
        StringBuilder s = new StringBuilder("<ul class=\"geraetereihe\">");
        for (var k : konnektoren) {
            s.append("""
                    <li><a class="geraetechip konnektorchip%s" href="/bestand/konnektor/%d"
                           title="Öffnet den %s „%s“%s">
                      <span class="chipsinnbild">%s</span>
                      <span class="chiptext">
                        <span class="chipname">%s</span>
                        <span class="chipzusatz">%s</span>
                      </span>
                    </a></li>"""
                    .formatted(k.betreibt() ? "" : " mitgenutzt", k.id(),
                            "HSK".equals(k.art()) ? "Highspeed-Konnektor" : "Konnektor",
                            esc(k.name()),
                            k.betreibt() ? "" : " — diese Einheit nutzt ihn nur mit",
                            "HSK".equals(k.art()) ? Symbole.hsk() : Symbole.konnektor(),
                            esc(k.name()),
                            k.betreibt()
                                    ? ("HSK".equals(k.art()) ? "HSK, eigener Betrieb"
                                                             : "Konnektor, eigener Betrieb")
                                    : "mitgenutzt"));
        }
        return s.append("</ul>").toString();
    }

    /**
     * Die Geräte, die direkt an dieser Einheit hängen.
     *
     * <p>Sie stehen unter ihrer Karte, eingerückt wie ein Kindknoten — denn
     * genau das sind sie: die unterste Ebene des Baums. Jede Kachel trägt die
     * Ampel ihres Geräts, damit man den Ausfall sieht, ohne die Karte darüber
     * deuten zu müssen.
     */
    private static String geraetereihe(Organisationsverwaltung.Einheit e, List<Geraet> geraete) {
        if (geraete.isEmpty()) return "";

        if (geraete.size() > GERAETE_HOECHSTENS) {
            // Kein stilles Abschneiden: Die Zahl steht da, und der Verweis führt
            // in die Liste, die für so viele Geräte gebaut ist.
            return """
                    <div class="geraetereihe"><a class="geraetemehr" href="/geraete?suche=%s"
                       title="Öffnet die Geräteliste, gefiltert auf „%s“">
                       %s Alle %d Kartenterminals dieser Einheit ansehen</a></div>"""
                    .formatted(esc(e.bezeichnung()), esc(e.bezeichnung()),
                            Symbole.kartenterminal(), geraete.size());
        }

        StringBuilder s = new StringBuilder("<ul class=\"geraetereihe\">");
        for (var g : geraete) {
            s.append("""
                    <li><a class="geraetechip ampel-%s" href="/geraete/%s/verlauf"
                           title="Öffnet den Zeitstrahl von „%s“ (%s)">
                      <span class="chipsinnbild">%s</span>
                      <span class="chiptext">
                        <span class="chipname">%s</span>
                        <span class="chipzusatz">%s</span>
                      </span>
                      <span class="chipampel">%s<span class="nurLeser">%s</span></span>
                    </a></li>"""
                    .formatted(g.ampel().css(), esc(g.terminalId()),
                            esc(g.name()), esc(g.terminalId()),
                            g.smcb() ? Symbole.smcb() : Symbole.kartenterminal(),
                            esc(g.name()),
                            g.smcb() ? "mit SMC-B" : "ohne SMC-B",
                            Symbole.zuAmpel(g.ampel()), g.ampel().text()));
        }
        return s.append("</ul>").toString();
    }

    /** Die BSNR gehört auf die Karte — sie ist das Kennzeichen der Betriebsstätte. */
    private static String tiIdentitaet(Organisationsverwaltung.Einheit e) {
        if (!e.istBetriebsstaette() || e.bsnr() == null || e.bsnr().isBlank()) return "";
        return " · BSNR " + esc(e.bsnr());
    }

    private static String zahl(String sinnbild, int wert, String wort) {
        return "<span class=\"kartenzahl\">" + sinnbild + wert
                + "<span class=\"nurLeser\"> " + wort + "</span>"
                + "<span class=\"zahlwort\" aria-hidden=\"true\">" + wort + "</span></span>";
    }

    /**
     * Was die Farben bedeuten.
     *
     * <p>Sie steht <b>über</b> dem Baum, nicht darunter: Wer die Ansicht zum
     * ersten Mal sieht, soll die Zeichen lesen können, bevor er sie deutet.
     */
    private static String legende() {
        return """
                <p class="strukturlegende">
                  <span class="ampel-ok">%s in Ordnung</span>
                  <span class="ampel-warnung">%s Aufmerksamkeit</span>
                  <span class="ampel-fehler">%s Handlungsbedarf</span>
                  <span class="ampel-unbekannt">%s keine Rückmeldung</span>
                  <span class="klein">Die Farbe einer Karte gilt für alles, was darunter hängt.</span>
                </p>"""
                .formatted(Symbole.ok(), Symbole.warnung(), Symbole.fehler(), Symbole.unbekannt());
    }
}
