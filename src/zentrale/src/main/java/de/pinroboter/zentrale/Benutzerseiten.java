package de.pinroboter.zentrale;

import java.util.List;

import static de.pinroboter.zentrale.Zentrale.esc;

/**
 * Oberfläche der Benutzerverwaltung (Block B, FA-BEN-010 bis FA-BEN-040).
 *
 * <p>Der Aufbau folgt dem Arbeitsablauf: Wer hier ist, will meist <b>eine</b>
 * Person finden und eine Sache mit ihr tun — einladen, zurücksetzen,
 * deaktivieren. Deshalb steht die Suche oben und jede Aktion in der Zeile.
 */
final class Benutzerseiten {

    private Benutzerseiten() { }

    /** Konten je Seite. Bei bis zu 1.000 Benutzern ist Blättern Pflicht. */
    static final int SEITENGROESSE = 25;

    record Ansicht(String suche, String rolle, String status, int seite) {}

    static String seite(List<Benutzerverwaltung.Benutzer> konten, int gesamt, Ansicht a,
                        List<Einladungen.Einladung> einladungen,
                        List<Bestandsseiten.Organisationsauswahl> organisationen,
                        boolean darfVerwalten, String meldung, boolean istFehler,
                        String neuesGeheimnis, String neuerName) {
        StringBuilder s = new StringBuilder(Zentrale.seitenkopf("Konten",
                "Zugänge, Einladungen und zweiter Faktor.", null));
        if (meldung != null) {
            s.append(Anmeldeseiten.meldung(meldung, istFehler ? "warnend" : "gut"));
        }

        // Ein Einladungsgeheimnis erscheint genau einmal. Das muss die
        // Oberflaeche unmissverstaendlich sagen, sonst klickt jemand weiter und
        // die Einladung ist verloren.
        if (neuesGeheimnis != null) {
            s.append("""
                    <div class="tafel"><h3>Einladung für %s
                      <span class="zaehler">wird nur jetzt angezeigt</span></h3>
                    <div class="anmeldeinhalt">
                      <div class="hinweis"><strong>Dieser Zugangscode erscheint genau einmal.</strong>
                        Geben Sie ihn der eingeladenen Person auf einem Weg weiter, den Sie
                        selbst verantworten — nicht per unverschlüsselter E-Mail. Sie setzt damit
                        ihr Kennwort selbst; Sie erfahren es nie.</div>
                      <div class="geheimnis"><code>%s</code></div>
                      <p class="hilfe">Einzulösen unter <code>/einladung</code>.
                         Gültig für %d Tage, danach verfällt der Code.</p>
                    </div></div>"""
                    .formatted(esc(neuerName), esc(neuesGeheimnis),
                            Einladungen.GUELTIGKEIT.toDays()));
        }

        s.append(kontenTafel(konten, gesamt, a));
        if (!einladungen.isEmpty()) s.append(einladungsTafel(einladungen, darfVerwalten));
        if (darfVerwalten) s.append(einladungsformular(organisationen));
        return s.toString();
    }

    private static String kontenTafel(List<Benutzerverwaltung.Benutzer> konten, int gesamt,
                                      Ansicht a) {
        StringBuilder s = new StringBuilder();
        s.append("<div class=\"tafel\"><h3>Konten <span class=\"zaehler\">")
         .append(gesamt).append("</span></h3>");

        s.append("""
                <form method="get" action="/benutzer" class="filterleiste" role="search"
                      hx-get="/benutzer" hx-target="#kontenliste" hx-select="#kontenliste"
                      hx-swap="outerHTML" hx-push-url="true" hx-indicator="#kontenliste"
                      hx-trigger="input changed delay:250ms from:#b-suche, change">
                  <div class="feldgruppe">
                    <label for="b-suche">Suche</label>
                    <input id="b-suche" name="suche" value="%s" type="search"
                           placeholder="Benutzername oder Name"
                           autocomplete="off" aria-describedby="b-treffer">
                  </div>
                  <div class="feldgruppe">
                    <label for="b-rolle">Rolle</label>
                    <select id="b-rolle" name="rolle">
                      <option value="">alle Rollen</option>
                      <option value="ADMINISTRATOR"%s>Administrator</option>
                      <option value="BENUTZER"%s>Benutzer</option>
                    </select>
                  </div>
                  <div class="feldgruppe">
                    <label for="b-status">Status</label>
                    <select id="b-status" name="status">
                      <option value="">alle</option>
                      <option value="aktiv"%s>aktiv</option>
                      <option value="inaktiv"%s>deaktiviert</option>
                      <option value="ohne-totp"%s>zweiter Faktor fehlt</option>
                    </select>
                  </div>
                  <a class="knopf" href="/benutzer">Zurücksetzen</a>
                </form>"""
                .formatted(esc(nz(a.suche())),
                        "ADMINISTRATOR".equals(a.rolle()) ? " selected" : "",
                        "BENUTZER".equals(a.rolle()) ? " selected" : "",
                        "aktiv".equals(a.status()) ? " selected" : "",
                        "inaktiv".equals(a.status()) ? " selected" : "",
                        "ohne-totp".equals(a.status()) ? " selected" : ""));

        // Nur dieser Bereich wird beim Tippen ausgetauscht (hx-select oben).
        s.append("<div id=\"kontenliste\" class=\"traegt\">");
        s.append("<p class=\"suchstand\" id=\"b-treffer\" role=\"status\" aria-live=\"polite\" "
                + "style=\"padding:.5rem 1.2rem 0;margin:0\">").append(gesamt).append(" Treffer</p>");
        if (konten.isEmpty()) {
            s.append("<p class=\"leer\">Keine Konten für diese Auswahl.</p>");
        } else {
            s.append("""
                    <div class="rollbar"><table>
                    <caption class="nurLeser">Benutzerkonten</caption>
                    <thead><tr>
                      <th scope="col">Benutzername</th><th scope="col">Name</th>
                      <th scope="col">Rolle</th><th scope="col">Zuständig für</th>
                      <th scope="col">Zweiter Faktor</th><th scope="col">Status</th>
                      <th scope="col">Aktionen</th>
                    </tr></thead><tbody>""");
            for (var b : konten) {
                s.append("""
                        <tr%s>
                          <td><code>%s</code></td><td>%s</td><td>%s</td><td>%s</td>
                          <td>%s</td><td>%s</td>
                          <td class="aktionen">
                            <form method="post" action="/benutzer/%d/totp-zuruecksetzen" class="reihe"
                                  onsubmit="return confirm('Zweiten Faktor für „%s“ zurücksetzen?\\n\\nDie Person richtet ihn bei der nächsten Anmeldung neu ein.')">
                              <button class="knopf klein" type="submit">Zweiten Faktor zurücksetzen</button>
                            </form>
                            <form method="post" action="/benutzer/%d/aktiv" class="reihe"
                                  onsubmit="return confirm('Konto „%s“ %s?')">
                              <input type="hidden" name="aktiv" value="%s">
                              <button class="knopf klein %s" type="submit">%s</button>
                            </form>
                          </td>
                        </tr>"""
                        .formatted(b.aktiv() ? "" : " class=\"stillgelegt\"",
                                esc(b.benutzername()), esc(b.anzeigename()),
                                esc(b.rolle().anzeige()),
                                b.organisationId() == null
                                        ? "<span class=\"marke\">gesamte Instanz</span>"
                                        : esc(String.valueOf(b.organisationId())),
                                b.totpBestaetigt()
                                        ? "<span class=\"marke ok\"><span class=\"sym\">✓</span>eingerichtet</span>"
                                        : "<span class=\"marke warnung\">fehlt</span>",
                                b.aktiv() ? "<span class=\"marke ok\"><span class=\"sym\">✓</span>aktiv</span>"
                                          : "<span class=\"marke\">deaktiviert</span>",
                                b.id(), esc(b.anzeigename()),
                                b.id(), esc(b.anzeigename()),
                                b.aktiv() ? "deaktivieren" : "aktivieren",
                                b.aktiv() ? "nein" : "ja",
                                b.aktiv() ? "warnend" : "",
                                b.aktiv() ? "Deaktivieren" : "Aktivieren"));
            }
            s.append("</tbody></table></div>");
            s.append(blaetterleiste(gesamt, a));
        }
        s.append("</div>");   // Ende kontenliste
        return s.append("</div>").toString();
    }

    private static String einladungsTafel(List<Einladungen.Einladung> liste, boolean darfVerwalten) {
        StringBuilder s = new StringBuilder(
                "<div class=\"tafel\"><h3>Offene Einladungen <span class=\"zaehler\">"
                + liste.size() + "</span></h3><div class=\"rollbar\"><table><thead><tr>"
                + "<th scope=\"col\">Benutzername</th><th scope=\"col\">Name</th>"
                + "<th scope=\"col\">Rolle</th><th scope=\"col\">Gültig bis</th>"
                + "<th scope=\"col\">Eingeladen von</th><th scope=\"col\">Aktion</th>"
                + "</tr></thead><tbody>");
        for (var e : liste) {
            s.append("""
                    <tr><td><code>%s</code></td><td>%s</td><td>%s</td>
                    <td>%s%s</td><td>%s</td>
                    <td class="aktionen">%s</td></tr>"""
                    .formatted(esc(e.benutzername()), esc(e.anzeigename()),
                            esc(e.rolle().anzeige()),
                            esc(zeit(e.gueltigBis())),
                            e.abgelaufen() ? " <span class=\"marke fehler\">abgelaufen</span>" : "",
                            esc(e.eingeladenVon()),
                            !darfVerwalten ? "—" : """
                            <form method="post" action="/benutzer/einladung/zurueckziehen" class="reihe">
                              <input type="hidden" name="benutzername" value="%s">
                              <button class="knopf klein warnend" type="submit">Zurückziehen</button>
                            </form>""".formatted(esc(e.benutzername()))));
        }
        return s.append("</tbody></table></div></div>").toString();
    }

    private static String einladungsformular(List<Bestandsseiten.Organisationsauswahl> orgs) {
        StringBuilder opt = new StringBuilder();
        for (var o : orgs) {
            opt.append("<option value=\"").append(o.id()).append("\">")
               .append(esc(o.bezeichnung())).append("</option>");
        }
        return """
                <div class="tafel">
                <details class="ausklapp"><summary>Person einladen</summary>
                <form method="post" action="/benutzer/einladen" class="maske">
                  <div class="hinweis">
                    <strong>Sie legen kein Kennwort fest.</strong> Die eingeladene Person setzt es
                    selbst und richtet ihren zweiten Faktor ein. Das ist Absicht: Ein Kennwort,
                    das Sie kennen, macht spätere Handlungen dieses Kontos nicht mehr eindeutig
                    zurechenbar.
                  </div>
                  <div class="feldreihe">
                    <div class="feldgruppe"><label for="n-benutzer">Benutzername</label>
                      <input id="n-benutzer" name="benutzername" required maxlength="80"
                             placeholder="z. B. a.beispiel"></div>
                    <div class="feldgruppe"><label for="n-name">Name</label>
                      <input id="n-name" name="anzeigename" required maxlength="120"
                             placeholder="Anna Beispiel" aria-describedby="n-name-hilfe"></div>
                  </div>
                  <p id="n-name-hilfe" class="hilfe">Unter diesem Namen erscheint die Person im
                     Protokoll. Er sollte die Person eindeutig bezeichnen.</p>
                  <div class="feldreihe">
                    <div class="feldgruppe"><label for="n-rolle">Rolle</label>
                      <select id="n-rolle" name="rolle">
                        <option value="BENUTZER">Benutzer — verwaltet Kartenterminals</option>
                        <option value="ADMINISTRATOR">Administrator — verwaltet zusätzlich Konten</option>
                      </select></div>
                    <div class="feldgruppe"><label for="n-org">Zuständig für</label>
                      <select id="n-org" name="organisationId" required>%s</select></div>
                  </div>
                  <button class="knopf haupt" type="submit" style="width:auto">Einladung erzeugen</button>
                </form></details></div>""".formatted(opt);
    }

    /** Seite zum Einlösen — ohne Anmeldung erreichbar, deshalb bewusst karg. */
    static String einloesenSeite(String geheimnis, String fehler) {
        return Anmeldeseiten.rahmen("Einladung einlösen", """
                %s
                <p class="unterzeile">Sie wurden zum PIN-Roboter eingeladen. Setzen Sie hier Ihr
                   Kennwort. Im nächsten Schritt richten Sie den zweiten Faktor ein.</p>
                <form method="post" action="/einladung" autocomplete="on">
                  <label for="e-code">Zugangscode aus der Einladung</label>
                  <input id="e-code" name="geheimnis" required value="%s" autocomplete="off">
                  <label for="e-pw">Neues Kennwort</label>
                  <input id="e-pw" name="kennwort" type="password" required
                         autocomplete="new-password" aria-describedby="e-pw-hilfe">
                  <p id="e-pw-hilfe" class="hilfe">Mindestens 12 Zeichen. Eine Folge mehrerer
                     Wörter ist leichter zu merken und schwerer zu raten als ein kurzes
                     Kennwort mit Sonderzeichen.</p>
                  <button class="knopf haupt" type="submit">Kennwort setzen</button>
                </form>""".formatted(Anmeldeseiten.meldung(fehler, "warnend"),
                        esc(nz(geheimnis))));
    }

    private static String blaetterleiste(int gesamt, Ansicht a) {
        int seiten = Math.max(1, (gesamt + SEITENGROESSE - 1) / SEITENGROESSE);
        if (seiten <= 1) return "";
        String basis = "/benutzer?" + (nz(a.suche()).isBlank() ? "" : "suche=" + url(a.suche()) + "&")
                + (nz(a.rolle()).isBlank() ? "" : "rolle=" + url(a.rolle()) + "&")
                + (nz(a.status()).isBlank() ? "" : "status=" + url(a.status()) + "&");
        long von = (long) (a.seite() - 1) * SEITENGROESSE + 1;
        long bis = Math.min(gesamt, (long) a.seite() * SEITENGROESSE);
        return """
                <nav class="blaettern" aria-label="Seiten der Benutzerliste">
                  %s<span>Konto %d bis %d von %d · Seite %d von %d</span>%s
                </nav>"""
                .formatted(
                        a.seite() > 1
                                ? "<a class=\"knopf\" href=\"" + basis + "seite=" + (a.seite() - 1) + "\">Vorherige</a>"
                                : "<span class=\"knopf gesperrt\" aria-disabled=\"true\">Vorherige</span>",
                        von, bis, gesamt, a.seite(), seiten,
                        a.seite() < seiten
                                ? "<a class=\"knopf\" href=\"" + basis + "seite=" + (a.seite() + 1) + "\">Nächste</a>"
                                : "<span class=\"knopf gesperrt\" aria-disabled=\"true\">Nächste</span>");
    }

    private static String zeit(java.time.Instant i) {
        return java.time.LocalDateTime.ofInstant(i, java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));
    }

    private static String url(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
