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
                        String meldung, boolean istFehler, Long ausgewaehlt) {
        StringBuilder s = new StringBuilder("""
                <h2>Organisation</h2>
                <p class="unterzeile">Verbünde, Standorte und Einrichtungen — die Struktur,
                   der Geräte und Konten zugeordnet sind.</p>
                """);
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

        s.append(baumTafel(baum, darfVerwalten, ausgewaehlt));
        if (darfVerwalten && !baum.isEmpty()) s.append(anlegeformular(baum));
        return s.toString();
    }

    private static String baumTafel(List<Organisationsverwaltung.Einheit> baum,
                                    boolean darfVerwalten, Long ausgewaehlt) {
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
         .append(darfVerwalten ? "<th scope=\"col\">Aktion</th>" : "")
         .append("</tr></thead><tbody>");

        for (var e : baum) {
            // Die Einrueckung ist rein visuell; fuer Screenreader steht die
            // Ebene im Text, sonst geht die Struktur dort verloren.
            String einzug = "&nbsp;&nbsp;".repeat(Math.min(e.tiefe(), 8) * 2);
            s.append("<tr").append(ausgewaehlt != null && ausgewaehlt == e.id()
                    ? " class=\"hervor\"" : "").append(">");
            s.append("<td>").append(einzug)
             .append(e.tiefe() > 0 ? "<span aria-hidden=\"true\">└ </span>" : "")
             .append("<strong>").append(esc(e.bezeichnung())).append("</strong>")
             .append(e.istDemo() ? " <span class=\"marke\">Demo</span>" : "")
             .append("<span class=\"nurLeser\"> — Ebene ").append(e.tiefe() + 1).append("</span>")
             .append("</td>");
            s.append("<td>").append(esc(e.artAnzeige())).append("</td>");
            s.append("<td class=\"klein\">").append(e.leer()
                    ? "<span class=\"marke\">leer</span>" : esc(e.hindernis())).append("</td>");

            if (darfVerwalten) {
                s.append("<td class=\"aktionen\">");
                s.append("<a class=\"knopf klein\" href=\"/organisation?bearbeiten=")
                 .append(e.id()).append("#bearbeiten\">Bearbeiten</a>");
                s.append("</td>");
            }
            s.append("</tr>");

            if (darfVerwalten && ausgewaehlt != null && ausgewaehlt == e.id()) {
                s.append(bearbeitungszeile(e, baum));
            }
        }
        return s.append("</tbody></table></div></div>").toString();
    }

    /** Umbenennen, Umhängen und Löschen — direkt an der Zeile, ohne Seitenwechsel. */
    private static String bearbeitungszeile(Organisationsverwaltung.Einheit e,
                                            List<Organisationsverwaltung.Einheit> baum) {
        StringBuilder s = new StringBuilder();
        s.append("<tr id=\"bearbeiten\"><td colspan=\"4\"><div class=\"maske\">");

        s.append("""
                <form method="post" action="/organisation/%d/umbenennen" class="feldreihe">
                  <div class="feldgruppe"><label for="o-bez">Bezeichnung</label>
                    <input id="o-bez" name="bezeichnung" required maxlength="160" value="%s"></div>
                  <div class="feldgruppe"><label for="o-bez-k" class="nurLeser">Speichern</label>
                    <button id="o-bez-k" class="knopf" type="submit">Umbenennen</button></div>
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
                      <div class="feldgruppe"><label for="o-eltern-k" class="nurLeser">Verschieben</label>
                        <button id="o-eltern-k" class="knopf" type="submit">Umhängen</button></div>
                    </form>
                    <p id="o-eltern-hilfe" class="hilfe">Beim Umhängen zieht alles mit, was an
                       dieser Einheit hängt — Geräte, Konten und deren Zuständigkeiten.</p>"""
                    .formatted(e.id(), ziele));
        }

        if (e.elternId() != null) {
            if (e.leer()) {
                s.append("""
                        <form method="post" action="/organisation/%d/loeschen"
                              onsubmit="return confirm('Einheit „%s“ wirklich löschen?')">
                          <button class="knopf warnend" type="submit">Löschen</button>
                        </form>""".formatted(e.id(), esc(e.bezeichnung())));
            } else {
                s.append("<p class=\"hilfe\">Löschen nicht möglich: Daran hängen noch ")
                 .append(esc(e.hindernis())).append(". Bitte zuerst verschieben oder entfernen.</p>");
            }
        }

        s.append("<p><a href=\"/organisation\">Bearbeitung schließen</a></p>");
        return s.append("</div></td></tr>").toString();
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
                    <div class="tafel"><h3>Einheit hinzufügen</h3>
                    <p class="leer">In Ihrem Bereich gibt es keine Einheit, unterhalb derer sich
                       etwas anlegen ließe. Unterhalb einer Einrichtung ist Schluss — sie ist die
                       unterste Ebene.</p></div>""";
        }
        return """
                <details class="ausklapp"><summary>Einheit hinzufügen</summary>
                <div class="tafel"><form method="post" action="/organisation" class="maske">
                  <div class="feldreihe">
                    <div class="feldgruppe"><label for="n-bez">Bezeichnung</label>
                      <input id="n-bez" name="bezeichnung" required maxlength="160"
                             placeholder="z. B. Praxis Nord"></div>
                    <div class="feldgruppe"><label for="n-art">Art</label>
                      <select id="n-art" name="art" aria-describedby="n-art-hilfe">
                        <option value="EINRICHTUNG">Einrichtung (Praxis, MVZ, Standort einer Klinik)</option>
                        <option value="STANDORT">Standort (fasst mehrere Einrichtungen zusammen)</option>
                      </select></div>
                    <div class="feldgruppe"><label for="n-eltern">Unterhalb von</label>
                      <select id="n-eltern" name="elternId" required>%s</select></div>
                  </div>
                  <p id="n-art-hilfe" class="hilfe">Ein Standort ist nur unterhalb eines Verbunds
                     zulässig, eine Einrichtung unterhalb von beidem. Unterhalb einer Einrichtung
                     lässt sich nichts mehr anlegen.</p>
                  <button class="knopf haupt" type="submit" style="width:auto">Einheit anlegen</button>
                </form></div></details>""".formatted(eltern);
    }

    private static boolean zulaessigesZiel(String elternArt, String kindArt) {
        return switch (elternArt) {
            case "VERBUND"  -> kindArt.equals("STANDORT") || kindArt.equals("EINRICHTUNG");
            case "STANDORT" -> kindArt.equals("EINRICHTUNG");
            default         -> false;
        };
    }
}
