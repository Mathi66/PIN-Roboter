package de.pinroboter.zentrale;

import java.util.List;

import static de.pinroboter.zentrale.Zentrale.esc;

/**
 * Oberfläche der Organisationsverwaltung (FA-ORG-020).
 *
 * <p>Diese Seite zeigt die Struktur, in der alles andere hängt: Geräte,
 * Konten, Protokolle. Wer sie öffnet, will fast immer eines von zwei Dingen —
 * eine neue Einrichtung anlegen oder eine bestehende an eine andere Stelle
 * hängen. Beides steht deshalb direkt am Baum, nicht in einem Untermenü.
 *
 * <p>Der Baum wird <b>eingerückt</b> dargestellt statt verschachtelt. Bei
 * hunderten Einrichtungen bleibt eine Tabelle durchsuchbar und tastaturfähig;
 * verschachtelte Listen werden es nicht.
 */
final class Organisationsseiten {

    private Organisationsseiten() { }

    static String seite(List<Organisationsverwaltung.Einheit> baum, boolean darfVerwalten,
                        String meldung, boolean istFehler) {
        StringBuilder s = new StringBuilder(Zentrale.seitenkopf("Organisation",
                "Verbünde, Standorte und Einrichtungen — die Struktur, der Geräte und "
                + "Konten zugeordnet sind.",
                darfVerwalten && !baum.isEmpty()
                        ? Bedienelemente.verweis("Einheit hinzufügen", "/organisation/neu",
                                "Legt einen Verbund, Standort oder eine Einrichtung an",
                                Bedienelemente.Art.HAUPT)
                        : null));
        if (meldung != null) {
            s.append(Anmeldeseiten.meldung(meldung, istFehler ? "warnend" : "gut"));
        }

        if (!darfVerwalten) {
            // Nicht verschweigen, sondern erklaeren: Wer die Struktur sieht,
            // aber keinen Knopf findet, sucht sonst an der falschen Stelle.
            s.append("""
                    <div class="hinweis">Sie sehen Ihre Struktur, können sie aber nicht ändern.
                      Das Anlegen und Umhängen von Einheiten ist ab der Standortebene möglich —
                      damit niemand seinen eigenen Zuständigkeitsbereich erweitern kann.</div>""");
        }

        s.append(baumTafel(baum, darfVerwalten));
        return s.toString();
    }

    private static String baumTafel(List<Organisationsverwaltung.Einheit> baum,
                                    boolean darfVerwalten) {
        if (baum.isEmpty()) {
            return """
                    <div class="tafel"><h3>Struktur</h3>
                    <p class="leer">Ihnen ist keine Organisationseinheit zugeordnet.
                       Wenden Sie sich an den Betreiber dieser Instanz.</p></div>""";
        }
        StringBuilder s = new StringBuilder();
        s.append("<div class=\"tafel\"><h3>Struktur <span class=\"zaehler\">")
         .append(baum.size()).append("</span></h3><div class=\"rollbar\"><table>")
         .append("<caption class=\"nurLeser\">Organisationseinheiten, eingerückt nach Ebene</caption>")
         .append("<thead><tr><th scope=\"col\">Einheit</th><th scope=\"col\">Art</th>")
         .append("<th scope=\"col\">Enthält</th>")
         .append("<th scope=\"col\"><span class=\"nurLeser\">Aktion</span></th>")
         .append("</tr></thead><tbody>");

        for (var e : baum) {
            // Die Einrueckung ist rein visuell; fuer Screenreader steht die
            // Ebene im Text, sonst geht die Struktur dort verloren.
            String einzug = "&nbsp;&nbsp;".repeat(Math.min(e.tiefe(), 8) * 2);
            s.append("<tr class=\"klickbar\">");
            s.append("<td>").append(einzug)
             .append(e.tiefe() > 0 ? "<span aria-hidden=\"true\">└ </span>" : "")
             .append("<a class=\"zeilenziel\" href=\"/organisation/").append(e.id())
             .append("\" title=\"Öffnet „").append(esc(e.bezeichnung()))
             .append("“ mit allem, was daran hängt\">").append(esc(e.bezeichnung())).append("</a>")
             .append(e.istDemo() ? " <span class=\"marke\">Demo</span>" : "")
             .append("<span class=\"nurLeser\"> — Ebene ").append(e.tiefe() + 1).append("</span>")
             .append("</td>");
            s.append("<td>").append(esc(e.artAnzeige())).append("</td>");
            s.append("<td class=\"klein\">").append(e.leer()
                    ? "<span class=\"marke\">leer</span>" : esc(e.hindernis())).append("</td>");

            // Kein „Öffnen"-Knopf mehr: Die ganze Zeile öffnet, der Name ist
            // der Link. Bei vierzehn Einheiten waren das vierzehn Knöpfe, die
            // alle dasselbe sagten.
            s.append("<td class=\"aktionen\"><span class=\"pfeil-hin\" aria-hidden=\"true\">›</span></td>");
            s.append("</tr>");
        }
        return s.append("</tbody></table></div></div>").toString();
    }

    /**
     * Die Detailseite einer Einheit (UX-Konzept, Stufe 5).
     *
     * <p>Sie beantwortet zuerst die Frage, die jemand tatsächlich hat: <b>Was
     * gehört zu dieser Praxis?</b> Erst danach kommen die Änderungsmasken. Die
     * Kacheln sind Absprungpunkte — jede Zahl führt gefiltert dorthin, wo die
     * gezählten Dinge stehen.
     */
    static String detailseite(Organisationsverwaltung.Einheit e,
                              List<Organisationsverwaltung.Einheit> pfad,
                              Organisationsverwaltung.Beziehungen b,
                              List<Organisationsverwaltung.Einheit> baum,
                              boolean darfVerwalten, String meldung, boolean istFehler) {
        StringBuilder s = new StringBuilder();

        // Der Weg von oben: Bei einem Verbund mit drei Ebenen ist sonst nicht
        // erkennbar, wo man gerade steht.
        String weg = pfad.stream().limit(Math.max(0, pfad.size() - 1))
                .map(x -> esc(x.bezeichnung())).reduce((x, y) -> x + " › " + y).orElse(null);

        s.append(Zentrale.detailkopf("/organisation", "Organisation", e.bezeichnung(),
                esc(e.artAnzeige()) + (weg == null ? "" : " · " + weg),
                darfVerwalten && e.leer() && e.elternId() != null
                        ? loeschknopf(e) : null));

        if (meldung != null) s.append(Anmeldeseiten.meldung(meldung, istFehler ? "warnend" : "gut"));

        s.append(beziehungstafel(e, b));
        if (e.istBetriebsstaette()) s.append(tiIdentitaet(e, darfVerwalten));
        if (darfVerwalten) s.append(aenderungstafel(e, baum));
        else s.append("""
                <div class="hinweis">Sie sehen diese Einheit, können sie aber nicht ändern.
                  Das Umbenennen und Umhängen ist ab der Standortebene möglich — damit niemand
                  seinen eigenen Zuständigkeitsbereich erweitern kann.</div>""");
        return s.toString();
    }

    private static String loeschknopf(Organisationsverwaltung.Einheit e) {
        return """
                <form method="post" action="/organisation/%d/loeschen"
                      data-nachfrage="Einheit &bdquo;%s&ldquo; wirklich löschen?">%s</form>"""
                .formatted(e.id(), esc(e.bezeichnung()),
                        Bedienelemente.absenden("Löschen",
                                "Löscht die leere Einheit „" + e.bezeichnung() + "“",
                                Bedienelemente.Art.WARNEND,
                                "Einheit „" + e.bezeichnung() + "“ wirklich löschen?"));
    }

    /** Alles, was an der Einheit hängt — als Absprungpunkte. */
    private static String beziehungstafel(Organisationsverwaltung.Einheit e,
                                          Organisationsverwaltung.Beziehungen b) {
        if (b == null) return "";
        StringBuilder s = new StringBuilder("""
                <div class="tafel"><h3>Was zu dieser Einheit gehört</h3>
                <div class="karte-leib karte-leib"><div class="beziehungen">""");

        s.append(kachel(b.terminals(), "Kartenterminals",
                "/bestand?einrichtung=" + e.id(), "Geräte ansehen"));
        s.append(kachel(b.smcb(), "SMC-B-Karten",
                "/bestand?einrichtung=" + e.id() + "&amp;smcb=ja",
                b.smcbVerifiziert() + " mit hinterlegter PIN"));
        s.append(kachel(b.konnektoren(), "Konnektoren",
                "/bestand?einrichtung=" + e.id(), "Anbindung ansehen"));
        s.append(kachel(b.konten(), "Konten",
                "/benutzer?organisation=" + e.id(),
                b.administratoren() + " Administrator(en)"));
        s.append(kachel(b.gruppen(), "Gerätegruppen", "/gruppen", "Prüfintervalle"));
        s.append(kachel(b.alarme(), "offene Alarme", "/", "Zur Lage"));
        s.append(kachel(b.protokoll(), "Protokolleinträge",
                "/protokoll?einrichtung=" + e.id(), "letzte 30 Tage"));
        if (b.einheiten() > 1) {
            s.append(kachel(b.einheiten() - 1, "untergeordnete Einheiten",
                    "/organisation", "Struktur ansehen"));
        }
        return s.append("</div></div></div>").toString();
    }

    private static String kachel(int zahl, String was, String ziel, String hin) {
        return """
                <a class="beziehung" href="%s">
                  <span class="zahl">%d</span><span class="was">%s</span>
                  <span class="hin">%s →</span>
                </a>""".formatted(ziel, zahl, esc(was), esc(hin));
    }

    /**
     * BSNR und Telematik-ID — nur an der Betriebsstätte (ADR-0012).
     *
     * <p>Sie ist die Leistungserbringerinstitution: Sie trägt die SMC-B, und
     * über die Telematik-ID wird die institutionelle Identität technisch
     * abgebildet. An einer Trägergesellschaft oder einem Fachbereich hätten
     * diese Felder keine Bedeutung — deshalb erscheinen sie dort gar nicht.
     */
    private static String tiIdentitaet(Organisationsverwaltung.Einheit e, boolean darfVerwalten) {
        String werte = """
                <dl class="angaben">
                  <dt>Betriebsstättennummer</dt><dd>%s</dd>
                  <dt>Telematik-ID</dt><dd>%s</dd>
                </dl>"""
                .formatted(e.bsnr() == null || e.bsnr().isBlank() ? "— noch nicht hinterlegt"
                                : "<code>" + esc(e.bsnr()) + "</code>",
                        e.telematikId() == null || e.telematikId().isBlank()
                                ? "— noch nicht hinterlegt"
                                : "<code>" + esc(e.telematikId()) + "</code>");

        if (!darfVerwalten) {
            return "<div class=\"tafel\"><h3>Angaben zur Betriebsstätte</h3>" + werte + "</div>";
        }
        return """
                <div class="tafel"><h3>Angaben zur Betriebsstätte</h3>
                <p class="hilfe">Diese Betriebsstätte ist die Leistungserbringerinstitution:
                   Sie führt eine eigene SMC-B. Über die <strong>Telematik-ID</strong> wird
                   ihre institutionelle Identität in der TI abgebildet, die
                   <strong>BSNR</strong> stellt den Bezug zur Abrechnung her.
                   Beide Angaben sind freiwillig — falls sie noch nicht vorliegen, lassen sie
                   sich später nachtragen. Die BSNR ist mit der zuständigen KV abzustimmen.</p>
                <form method="post" action="/organisation/%d/ti-identitaet" class="maske">
                  <div class="feldreihe">
                    <div class="feldgruppe"><label for="o-bsnr">Betriebsstättennummer</label>
                      <input id="o-bsnr" name="bsnr" maxlength="20" value="%s"
                             inputmode="numeric" placeholder="neun Ziffern"></div>
                    <div class="feldgruppe"><label for="o-tid">Telematik-ID</label>
                      <input id="o-tid" name="telematikId" maxlength="120" value="%s"
                             placeholder="z. B. 1-SMC-B-Testkarte-883110000123456"></div>
                  </div>
                  <button class="knopf" type="submit"
                          title="Speichert BSNR und Telematik-ID dieser Betriebsstätte"
                          aria-label="Angaben speichern">Angaben speichern</button>
                </form>%s</div>"""
                .formatted(e.id(), esc(e.bsnr() == null ? "" : e.bsnr()),
                        esc(e.telematikId() == null ? "" : e.telematikId()), werte);
    }

    /** Umbenennen, Umhängen und Löschen — auf der Detailseite, nicht in der Liste. */
    private static String aenderungstafel(Organisationsverwaltung.Einheit e,
                                          List<Organisationsverwaltung.Einheit> baum) {
        StringBuilder s = new StringBuilder(
                "<div class=\"tafel\"><h3>Einheit ändern</h3><div class=\"maske\">");

        s.append("""
                <form method="post" action="/organisation/%d/umbenennen" class="feldreihe">
                  <div class="feldgruppe"><label for="o-bez">Bezeichnung</label>
                    <input id="o-bez" name="bezeichnung" required maxlength="160" value="%s"></div>
                  <div class="feldaktion">
                    <button id="o-bez-k" class="knopf" type="submit" title="Speichert den neuen Namen dieser Einheit" aria-label="Speichert den neuen Namen dieser Einheit">Umbenennen</button></div>
                </form>""".formatted(e.id(), esc(e.bezeichnung())));

        // Umhaengen: nur Ziele anbieten, die zulaessig sind. Ein Auswahlfeld
        // voller Eintraege, die beim Absenden abgelehnt werden, ist eine Falle.
        StringBuilder ziele = new StringBuilder();
        for (var z : baum) {
            if (z.id() == e.id()) continue;
            if (!zulaessigesZiel(z.art(), e.art())) continue;
            ziele.append("<option value=\"").append(z.id()).append("\"")
                 .append(e.elternId() != null && e.elternId() == z.id() ? " selected" : "")
                 .append(">").append("&nbsp;".repeat(z.tiefe() * 4))
                 .append(esc(z.bezeichnung())).append(" (").append(esc(z.artAnzeige()))
                 .append(")</option>");
        }
        if (ziele.length() == 0) {
            s.append("<p class=\"hilfe\">Für diese Einheit gibt es in Ihrem Bereich keine "
                    + "zulässige übergeordnete Einheit.</p>");
        } else {
            s.append("""
                    <form method="post" action="/organisation/%d/umhaengen" class="feldreihe">
                      <div class="feldgruppe"><label for="o-eltern">Übergeordnete Einheit</label>
                        <select id="o-eltern" name="elternId" aria-describedby="o-eltern-hilfe">%s</select></div>
                      <div class="feldaktion">
                        <button id="o-eltern-k" class="knopf" type="submit" title="Verschiebt diese Einheit unter die gewählte übergeordnete Einheit" aria-label="Verschiebt diese Einheit unter die gewählte übergeordnete Einheit">Umhängen</button></div>
                      <p id="o-eltern-hilfe" class="hilfe feldbreit">Beim Umhängen zieht alles
                         mit, was an dieser Einheit hängt — Geräte, Konten und deren
                         Zuständigkeiten.</p>
                    </form>"""
                    .formatted(e.id(), ziele));
        }

        if (e.elternId() != null && !e.leer()) {
            s.append("<div class=\"hinweis\"><div><strong>Löschen nicht möglich</strong>"
                    + "Daran hängen noch ").append(esc(e.hindernis()))
             .append(". Bitte zuerst verschieben oder entfernen.</div></div>");
        }
        return s.append("</div></div>").toString();
    }

    /** Die Anlegemaske als eigene Seite (UX-Konzept, Stufe 4). */
    static String anlegeseite(List<Organisationsverwaltung.Einheit> baum) {
        return Zentrale.detailkopf("/organisation", "Organisation", "Einheit hinzufügen",
                "Eine neue Einheit liegt immer unterhalb einer bestehenden.", null)
              + anlegeformular(baum);
    }

    private static String anlegeformular(List<Organisationsverwaltung.Einheit> baum) {
        StringBuilder eltern = new StringBuilder();
        for (var e : baum) {
            if (e.art().equals("EINRICHTUNG")) continue;   // darunter geht nichts
            eltern.append("<option value=\"").append(e.id()).append("\">")
                  .append("&nbsp;".repeat(e.tiefe() * 4)).append(esc(e.bezeichnung()))
                  .append(" (").append(esc(e.artAnzeige())).append(")</option>");
        }
        if (eltern.length() == 0) {
            return """
                    <div class="tafel">
                    <p class="leer">In Ihrem Bereich gibt es keine Einheit, unterhalb derer sich
                       etwas anlegen ließe. Unterhalb einer Einrichtung ist Schluss — sie ist die
                       unterste Ebene.</p></div>""";
        }
        return """
                <div class="tafel"><form method="post" action="/organisation" class="maske">
                  <div class="feldreihe">
                    <div class="feldgruppe"><label for="n-bez">Bezeichnung</label>
                      <input id="n-bez" name="bezeichnung" required maxlength="160"
                             placeholder="z. B. Praxis Nord"></div>
                    <div class="feldgruppe"><label for="n-art">Art</label>
                      <select id="n-art" name="art" aria-describedby="n-art-hilfe">
                        <option value="EINRICHTUNG">Betriebsstätte (die LEI — mit BSNR und eigener SMC-B)</option>
                        <option value="FACHBEREICH">Fachbereich (gliedert eine große Betriebsstätte)</option>
                        <option value="STANDORT">Standort (fasst mehrere Betriebsstätten räumlich zusammen)</option>
                        <option value="LEISTUNGSERBRINGER">Leistungserbringer (das zugelassene MVZ)</option>
                      </select></div>
                    <div class="feldgruppe"><label for="n-eltern">Unterhalb von</label>
                      <select id="n-eltern" name="elternId" required>%s</select></div>
                  </div>
                  <p id="n-art-hilfe" class="hilfe">Die <strong>Betriebsstätte</strong> ist die
                     Leistungserbringerinstitution: Sie trägt BSNR, SMC-B und Telematik-ID.
                     Alle anderen Ebenen sind freiwillig — eine Einzelpraxis besteht aus
                     Trägergesellschaft und Betriebsstätte, mehr braucht es nicht.
                     Ein <strong>Fachbereich</strong> gliedert eine große Betriebsstätte und
                     steht nur unterhalb von ihr; darunter lässt sich nichts mehr anlegen.</p>
                  <button class="knopf haupt" type="submit" title="Legt die Einheit mit den eingegebenen Angaben an" aria-label="Legt die Einheit mit den eingegebenen Angaben an">Einheit anlegen</button>
                </form></div>""".formatted(eltern);
    }

    /**
     * Wohin sich eine Einheit umhängen lässt (ADR-0012).
     *
     * <p>Muss mit {@code Organisationsverwaltung.erlaubtUnter} übereinstimmen —
     * die eine Regel prüft das Umhängen, die andere das Anlegen. Zwei Listen,
     * die dasselbe meinen: Beim Erweitern IMMER beide anfassen.
     */
    private static boolean zulaessigesZiel(String elternArt, String kindArt) {
        return switch (elternArt) {
            case "VERBUND" -> kindArt.equals("LEISTUNGSERBRINGER")
                           || kindArt.equals("STANDORT") || kindArt.equals("EINRICHTUNG");
            case "LEISTUNGSERBRINGER" -> kindArt.equals("STANDORT")
                                      || kindArt.equals("EINRICHTUNG");
            case "STANDORT"    -> kindArt.equals("EINRICHTUNG");
            case "EINRICHTUNG" -> kindArt.equals("FACHBEREICH");
            default            -> false;
        };
    }
}
