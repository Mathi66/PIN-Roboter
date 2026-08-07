package de.pinroboter.zentrale;

import java.util.List;

import static de.pinroboter.zentrale.Zentrale.esc;

/**
 * Oberfläche der Bestandsverwaltung (FA-GER-010, FA-GER-020, FA-GER-060).
 *
 * <p>Der Aufbau folgt dem Arbeitsablauf, nicht der Datenbank (CLAUDE.md 4.1):
 * Zuerst der Konnektor — ohne ihn ist kein Terminal erreichbar —, darunter die
 * Terminals mit Suche und Seitenblättern. Wer eine Praxis einrichtet, arbeitet
 * die Seite von oben nach unten ab.
 *
 * <p>Zugangsdaten erscheinen hier grundsätzlich nur als leeres Eingabefeld mit
 * dem Hinweis, ob eines hinterlegt ist. Es gibt keinen Pfad, auf dem ein
 * gespeichertes Kennwort zurück in die Oberfläche gelangt.
 */
final class Bestandsseiten {

    private Bestandsseiten() { }

    /** Trefferanzahl je Seite. Bei 5.000 Geräten ist Blättern Pflicht (FA-GER-060). */
    static final int SEITENGROESSE = 25;

    // ------------------------------------------------------------- Gesamtseite

    static String seite(List<Bestandsverwaltung.Konnektor> konnektoren,
                        Bestandsverwaltung.Seite<Bestandsverwaltung.Terminal> terminals,
                        List<Organisationsauswahl> organisationen,
                        String suche, Long filterKonnektor,
                        boolean zugangsdatenMoeglich, String meldung, boolean meldungIstFehler) {
        StringBuilder s = new StringBuilder("""
                <h2>Bestand</h2>
                <p class="unterzeile">Konnektoren und Kartenterminals erfassen und pflegen.</p>
                """);
        if (meldung != null) {
            s.append(Anmeldeseiten.meldung(meldung, meldungIstFehler ? "warnend" : "gut"));
        }
        if (!zugangsdatenMoeglich) {
            s.append(Anmeldeseiten.meldung(
                    "Es ist kein Schlüssel für Zugangsdaten hinterlegt (GEHEIMNIS_SCHLUESSEL). "
                    + "Geräte lassen sich vollständig erfassen, nur Kennwörter können nicht "
                    + "gespeichert werden. Ohne hinterlegtes Kennwort kann der Roboter sich "
                    + "später nicht selbsttätig anmelden.", "warnend"));
        }
        s.append(konnektorTafel(konnektoren, organisationen));
        s.append(terminalTafel(terminals, konnektoren, suche, filterKonnektor));
        return s.toString();
    }

    /** Kurzform einer Organisationseinheit für die Auswahlliste. */
    record Organisationsauswahl(long id, String bezeichnung, String art) {}

    // ------------------------------------------------------------ Konnektoren

    private static String konnektorTafel(List<Bestandsverwaltung.Konnektor> liste,
                                         List<Organisationsauswahl> organisationen) {
        StringBuilder s = new StringBuilder();
        s.append("<div class=\"tafel\"><h3>Konnektoren und Highspeed-Konnektoren <span class=\"zaehler\">")
         .append(liste.size()).append("</span></h3>");

        if (liste.isEmpty()) {
            s.append("<p class=\"leer\">Noch kein Konnektor erfasst. "
                    + "Beginnen Sie mit dem Konnektor — die Kartenterminals werden ihm zugeordnet.</p>");
        } else {
            s.append("""
                    <div class="rollbar"><table>
                    <caption class="nurLeser">Erfasste Konnektoren</caption>
                    <thead><tr>
                      <th scope="col">Bezeichnung</th><th scope="col">Einrichtung</th>
                      <th scope="col">Adresse</th><th scope="col">Aufrufkontext</th>
                      <th scope="col">Zugang</th><th scope="col">Erreichbarkeit</th>
                      <th scope="col">Terminals</th><th scope="col">Aktionen</th>
                    </tr></thead><tbody>""");
            for (var k : liste) {
                s.append("""
                        <tr%s>
                          <td><a href="/bestand/konnektor/%d">%s</a>%s</td>
                          <td>%s</td>
                          <td><code>%s</code></td>
                          <td class="klein"><code>%s</code> / <code>%s</code> / <code>%s</code></td>
                          <td>%s</td>
                          <td>%s</td>
                          <td>%d</td>
                          <td class="aktionen">
                            <button class="knopf klein" hx-post="/bestand/konnektor/%d/pruefen"
                                    hx-target="#bestandsmeldung" hx-swap="innerHTML">Erreichbarkeit prüfen</button>
                            <form method="post" action="/bestand/konnektor/%d/loeschen" class="reihe"
                                  onsubmit="return confirm('Konnektor „%s“ löschen?')">
                              <button class="knopf klein warnend" type="submit">Löschen</button>
                            </form>
                          </td>
                        </tr>"""
                        .formatted(k.aktiv() ? "" : " class=\"stillgelegt\"",
                                k.id(), esc(k.bezeichnung()),
                                k.aktiv() ? "" : " <span class=\"marke\">deaktiviert</span>",
                                esc(k.organisation()), esc(k.adresse()),
                                esc(k.mandantId()), esc(k.clientsystemId()), esc(k.arbeitsplatzId()),
                                zugangsMarke(k.benutzerName(), k.passwortHinterlegt()),
                                erreichbarkeitsMarke(k.zuletztErreichbar(), k.zuletztGeprueft(), k.pruefhinweis()),
                                k.terminalzahl(),
                                k.id(), k.id(), esc(k.bezeichnung())));
            }
            s.append("</tbody></table></div>");
        }

        s.append("""
                <details class="ausklapp"><summary>Konnektor hinzufügen</summary>
                <form method="post" action="/bestand/konnektor" class="maske">
                  <div class="feldgruppe">
                    <label for="k-org">Einrichtung</label>
                    <select id="k-org" name="organisationId" required>%s</select>
                  </div>
                  <div class="feldgruppe">
                    <label for="k-bez">Bezeichnung</label>
                    <input id="k-bez" name="bezeichnung" required maxlength="120"
                           placeholder="z. B. Konnektor Praxis Nord">
                  </div>
                  <div class="feldgruppe">
                    <label for="k-adr">Adresse</label>
                    <input id="k-adr" name="adresse" required maxlength="200"
                           placeholder="192.168.1.10:443"
                           aria-describedby="k-adr-hilfe">
                    <p id="k-adr-hilfe" class="hilfe">Rechnername oder IP-Adresse, wahlweise mit Port.
                       Die Erreichbarkeit wird beim Speichern geprüft; ein noch nicht
                       angeschlossener Konnektor lässt sich trotzdem erfassen.</p>
                  </div>
                  <div class="feldgruppe">
                    <label for="k-mod">Modell <span class="optional">(optional)</span></label>
                    <input id="k-mod" name="modell" maxlength="120" placeholder="z. B. KoCo Konnektor">
                  </div>
                  <fieldset>
                    <legend>Aufrufkontext</legend>
                    <p class="hilfe">Diese drei Werte stehen in der Konfiguration des Konnektors
                       und begleiten jeden Aufruf. Ein Tippfehler zeigt sich später nur als
                       unspezifischer Zugriffsfehler — deshalb sorgfältig übertragen.</p>
                    <div class="feldreihe">
                      <div class="feldgruppe"><label for="k-man">Mandanten-ID</label>
                        <input id="k-man" name="mandantId" required maxlength="80"></div>
                      <div class="feldgruppe"><label for="k-cli">Clientsystem-ID</label>
                        <input id="k-cli" name="clientsystemId" required maxlength="80"></div>
                      <div class="feldgruppe"><label for="k-arb">Arbeitsplatz-ID</label>
                        <input id="k-arb" name="arbeitsplatzId" required maxlength="80"></div>
                    </div>
                  </fieldset>
                  <fieldset>
                    <legend>Zugang <span class="optional">(optional)</span></legend>
                    <p class="hilfe">Nach dem Speichern ist das Kennwort nicht mehr anzeigbar —
                       weder hier noch über eine Schnittstelle.</p>
                    <div class="feldreihe">
                      <div class="feldgruppe"><label for="k-ben">Benutzername</label>
                        <input id="k-ben" name="benutzerName" maxlength="80" autocomplete="off"></div>
                      <div class="feldgruppe"><label for="k-pw">Kennwort</label>
                        <input id="k-pw" name="passwort" type="password" maxlength="200"
                               autocomplete="new-password"></div>
                      <div class="feldgruppe"><label for="k-web">Webadmin-Adresse</label>
                        <input id="k-web" name="webadminAdresse" maxlength="200"
                               aria-describedby="k-web-hilfe"></div>
                    </div>
                    <p id="k-web-hilfe" class="hilfe">Herstellerspezifisch und immer optional.
                       Ohne sie bleibt alles von Hand erfassbar.</p>
                  </fieldset>
                  <button class="knopf haupt" type="submit" style="width:auto">Konnektor speichern</button>
                </form></details>""".formatted(organisationsOptionen(organisationen, null)));

        return s.append("</div>").toString();
    }

    // -------------------------------------------------------------- Terminals

    private static String terminalTafel(Bestandsverwaltung.Seite<Bestandsverwaltung.Terminal> seite,
                                        List<Bestandsverwaltung.Konnektor> konnektoren,
                                        String suche, Long filterKonnektor) {
        StringBuilder s = new StringBuilder();
        s.append("<div class=\"tafel\" id=\"terminals\"><h3>Kartenterminals <span class=\"zaehler\">")
         .append(seite.gesamt()).append("</span></h3>");

        // Suche und Filter laufen serverseitig - bei 5.000 Geraeten ist alles
        // andere unbrauchbar (FA-GER-060).
        s.append("""
                <form method="get" action="/bestand" class="filterleiste" role="search">
                  <div class="feldgruppe">
                    <label for="t-suche">Suche</label>
                    <input id="t-suche" name="suche" value="%s" type="search"
                           placeholder="Bezeichnung, Kennung oder Standort">
                  </div>
                  <div class="feldgruppe">
                    <label for="t-konn">Konnektor</label>
                    <select id="t-konn" name="konnektor">
                      <option value="">alle Konnektoren</option>%s
                    </select>
                  </div>
                  <button class="knopf" type="submit">Suchen</button>
                  <a class="knopf" href="/bestand">Zurücksetzen</a>
                </form>"""
                .formatted(esc(suche == null ? "" : suche),
                        konnektorOptionen(konnektoren, filterKonnektor)));

        if (seite.eintraege().isEmpty()) {
            s.append("<p class=\"leer\">")
             .append(seite.gesamt() == 0 && (suche == null || suche.isBlank())
                     ? "Noch kein Kartenterminal erfasst."
                     : "Keine Kartenterminals für diese Suche.")
             .append("</p>");
        } else {
            s.append("""
                    <div class="rollbar"><table>
                    <caption class="nurLeser">Erfasste Kartenterminals</caption>
                    <thead><tr>
                      <th scope="col">Bezeichnung</th><th scope="col">Kennung</th>
                      <th scope="col">Konnektor</th><th scope="col">Standort</th>
                      <th scope="col">Modell / Firmware</th><th scope="col">SMC-B</th>
                      <th scope="col">Zugang</th><th scope="col">Erreichbarkeit</th>
                      <th scope="col">Aktionen</th>
                    </tr></thead><tbody>""");
            for (var t : seite.eintraege()) {
                s.append("""
                        <tr%s>
                          <td><a href="/bestand/terminal/%d">%s</a>%s</td>
                          <td><code>%s</code></td>
                          <td>%s</td>
                          <td>%s</td>
                          <td class="klein">%s%s</td>
                          <td>%s</td>
                          <td>%s</td>
                          <td>%s</td>
                          <td class="aktionen">
                            <button class="knopf klein" hx-post="/bestand/terminal/%d/pruefen"
                                    hx-target="#bestandsmeldung" hx-swap="innerHTML">Prüfen</button>
                            <form method="post" action="/bestand/terminal/%d/loeschen" class="reihe"
                                  onsubmit="return confirm('Kartenterminal „%s“ löschen?')">
                              <button class="knopf klein warnend" type="submit">Löschen</button>
                            </form>
                          </td>
                        </tr>"""
                        .formatted(t.aktiv() ? "" : " class=\"stillgelegt\"",
                                t.id(), esc(t.bezeichnung()),
                                t.aktiv() ? "" : " <span class=\"marke\">deaktiviert</span>",
                                esc(t.terminalId()), esc(t.konnektor()), esc(nz(t.standortangabe())),
                                esc(nz(t.modell())),
                                t.firmware() == null ? "" : " / " + esc(t.firmware()),
                                smcbMarke(t),
                                zugangsMarke(t.adminBenutzer(), t.adminPasswortHinterlegt()),
                                erreichbarkeitsMarke(t.zuletztErreichbar(), t.zuletztGeprueft(), t.pruefhinweis()),
                                t.id(), t.id(), esc(t.bezeichnung())));
            }
            s.append("</tbody></table></div>");
            s.append(blaetterleiste(seite, suche, filterKonnektor));
        }

        s.append("""
                <details class="ausklapp"><summary>Kartenterminal hinzufügen</summary>
                <form method="post" action="/bestand/terminal" class="maske">
                  <div class="feldreihe">
                    <div class="feldgruppe">
                      <label for="t-konnektor">Konnektor</label>
                      <select id="t-konnektor" name="konnektorId" required>%s</select>
                    </div>
                    <div class="feldgruppe">
                      <label for="t-id">Kennung</label>
                      <input id="t-id" name="terminalId" required maxlength="80"
                             placeholder="KT-EMPFANG-01" aria-describedby="t-id-hilfe">
                    </div>
                  </div>
                  <p id="t-id-hilfe" class="hilfe">Die Kennung, unter der der Konnektor das Gerät
                     führt. Sie muss eindeutig sein.</p>
                  <div class="feldreihe">
                    <div class="feldgruppe"><label for="t-bez">Bezeichnung</label>
                      <input id="t-bez" name="bezeichnung" required maxlength="120"
                             placeholder="z. B. Empfang links"></div>
                    <div class="feldgruppe"><label for="t-std">Standort <span class="optional">(optional)</span></label>
                      <input id="t-std" name="standort" maxlength="160"></div>
                  </div>
                  <div class="feldreihe">
                    <div class="feldgruppe"><label for="t-adr">Adresse</label>
                      <input id="t-adr" name="adresse" required maxlength="200" placeholder="192.168.1.51:443"></div>
                    <div class="feldgruppe"><label for="t-mod">Modell <span class="optional">(optional)</span></label>
                      <input id="t-mod" name="modell" maxlength="120" placeholder="z. B. CHERRY ST-1506"></div>
                    <div class="feldgruppe"><label for="t-ser">Seriennummer <span class="optional">(optional)</span></label>
                      <input id="t-ser" name="seriennummer" maxlength="120"></div>
                  </div>
                  <fieldset>
                    <legend>Administrationszugang <span class="optional">(optional)</span></legend>
                    <p class="hilfe">Über diesen Zugang übergibt der Roboter die PIN an die
                       Managementschnittstelle. Nach dem Speichern ist das Kennwort nicht mehr anzeigbar.</p>
                    <div class="feldreihe">
                      <div class="feldgruppe"><label for="t-aben">Benutzername</label>
                        <input id="t-aben" name="adminBenutzer" maxlength="80" autocomplete="off"></div>
                      <div class="feldgruppe"><label for="t-apw">Kennwort</label>
                        <input id="t-apw" name="adminPasswort" type="password" maxlength="200"
                               autocomplete="new-password"></div>
                    </div>
                  </fieldset>
                  <fieldset>
                    <legend>Karte und Automatik</legend>
                    <div class="ankreuz">
                      <input id="t-smcb" name="hatSmcb" type="checkbox" value="ja">
                      <label for="t-smcb">In diesem Terminal steckt eine SMC-B</label>
                    </div>
                    <div class="ankreuz">
                      <input id="t-pevim" name="pevimAktiv" type="checkbox" value="ja">
                      <label for="t-pevim">PIN-Automatik nutzen (Managementschnittstelle freigeschaltet)</label>
                    </div>
                    <p class="hilfe">Die PIN-Automatik ist ausschließlich für SMC-B zulässig
                       (gematik A_24080). Für Terminals ohne SMC-B bleibt sie abgewählt —
                       diese Geräte werden erfasst und überwacht, aber nie verifiziert.</p>
                  </fieldset>
                  <button class="knopf haupt" type="submit" style="width:auto">Kartenterminal speichern</button>
                </form></details>""".formatted(konnektorOptionen(konnektoren, null)));

        return s.append("</div>").toString();
    }

    // ------------------------------------------------------- Einzelbearbeitung

    static String konnektorMaske(Bestandsverwaltung.Konnektor k, String fehler) {
        StringBuilder s = new StringBuilder();
        s.append("<h2>Konnektor: ").append(esc(k.bezeichnung())).append("</h2>");
        s.append("<p class=\"unterzeile\"><a href=\"/bestand\">&larr; Zurück zum Bestand</a></p>");
        if (fehler != null) s.append(Anmeldeseiten.meldung(fehler, "warnend"));

        s.append("""
                <div class="tafel"><form method="post" action="/bestand/konnektor/%d" class="maske">
                  <div class="feldreihe">
                    <div class="feldgruppe"><label for="b-bez">Bezeichnung</label>
                      <input id="b-bez" name="bezeichnung" required maxlength="120" value="%s"></div>
                    <div class="feldgruppe"><label for="b-adr">Adresse</label>
                      <input id="b-adr" name="adresse" required maxlength="200" value="%s"></div>
                    <div class="feldgruppe"><label for="b-mod">Modell</label>
                      <input id="b-mod" name="modell" maxlength="120" value="%s"></div>
                  </div>
                  <fieldset><legend>Aufrufkontext</legend>
                    <div class="feldreihe">
                      <div class="feldgruppe"><label for="b-man">Mandanten-ID</label>
                        <input id="b-man" name="mandantId" required maxlength="80" value="%s"></div>
                      <div class="feldgruppe"><label for="b-cli">Clientsystem-ID</label>
                        <input id="b-cli" name="clientsystemId" required maxlength="80" value="%s"></div>
                      <div class="feldgruppe"><label for="b-arb">Arbeitsplatz-ID</label>
                        <input id="b-arb" name="arbeitsplatzId" required maxlength="80" value="%s"></div>
                    </div>
                  </fieldset>
                  <fieldset><legend>Zugang</legend>
                    <div class="feldreihe">
                      <div class="feldgruppe"><label for="b-ben">Benutzername</label>
                        <input id="b-ben" name="benutzerName" maxlength="80" value="%s" autocomplete="off"></div>
                      <div class="feldgruppe"><label for="b-pw">Neues Kennwort</label>
                        <input id="b-pw" name="passwort" type="password" maxlength="200"
                               autocomplete="new-password" aria-describedby="b-pw-hilfe"></div>
                      <div class="feldgruppe"><label for="b-web">Webadmin-Adresse</label>
                        <input id="b-web" name="webadminAdresse" maxlength="200" value="%s"></div>
                    </div>
                    <p id="b-pw-hilfe" class="hilfe">%s Ein leeres Feld lässt das gespeicherte
                       Kennwort unverändert.</p>
                  </fieldset>
                  <div class="ankreuz">
                    <input id="b-aktiv" name="aktiv" type="checkbox" value="ja" %s>
                    <label for="b-aktiv">Aktiv — der Roboter darf diesen Konnektor ansprechen</label>
                  </div>
                  <button class="knopf haupt" type="submit" style="width:auto">Änderungen speichern</button>
                </form></div>"""
                .formatted(k.id(), esc(k.bezeichnung()), esc(k.adresse()), esc(nz(k.modell())),
                        esc(k.mandantId()), esc(k.clientsystemId()), esc(k.arbeitsplatzId()),
                        esc(nz(k.benutzerName())), esc(nz(k.webadminAdresse())),
                        k.passwortHinterlegt()
                                ? "Ein Kennwort ist hinterlegt und lässt sich nicht anzeigen."
                                : "Es ist kein Kennwort hinterlegt.",
                        k.aktiv() ? "checked" : ""));

        if (k.passwortHinterlegt()) {
            s.append("""
                    <div class="tafel"><h3>Hinterlegtes Kennwort entfernen</h3>
                    <p class="unterzeile">Danach kann der Roboter sich an diesem Konnektor nicht
                       mehr selbsttätig anmelden.</p>
                    <form method="post" action="/bestand/konnektor/%d/kennwort-entfernen"
                          onsubmit="return confirm('Hinterlegtes Kennwort entfernen?')">
                      <button class="knopf warnend" type="submit">Kennwort entfernen</button>
                    </form></div>""".formatted(k.id()));
        }
        return s.toString();
    }

    static String terminalMaske(Bestandsverwaltung.Terminal t, String fehler) {
        StringBuilder s = new StringBuilder();
        s.append("<h2>Kartenterminal: ").append(esc(t.bezeichnung())).append("</h2>");
        s.append("<p class=\"unterzeile\">Kennung <code>").append(esc(t.terminalId()))
         .append("</code> am Konnektor ").append(esc(t.konnektor()))
         .append(" · <a href=\"/bestand\">&larr; Zurück zum Bestand</a></p>");
        if (fehler != null) s.append(Anmeldeseiten.meldung(fehler, "warnend"));

        s.append("""
                <div class="tafel"><form method="post" action="/bestand/terminal/%d" class="maske">
                  <div class="feldreihe">
                    <div class="feldgruppe"><label for="m-bez">Bezeichnung</label>
                      <input id="m-bez" name="bezeichnung" required maxlength="120" value="%s"></div>
                    <div class="feldgruppe"><label for="m-std">Standort</label>
                      <input id="m-std" name="standort" maxlength="160" value="%s"></div>
                    <div class="feldgruppe"><label for="m-adr">Adresse</label>
                      <input id="m-adr" name="adresse" required maxlength="200" value="%s"></div>
                  </div>
                  <div class="feldreihe">
                    <div class="feldgruppe"><label for="m-mod">Modell</label>
                      <input id="m-mod" name="modell" maxlength="120" value="%s"></div>
                    <div class="feldgruppe"><label for="m-ser">Seriennummer</label>
                      <input id="m-ser" name="seriennummer" maxlength="120" value="%s"></div>
                    <div class="feldgruppe"><label for="m-fw">Firmware</label>
                      <input id="m-fw" value="%s" readonly aria-describedby="m-fw-hilfe"></div>
                  </div>
                  <p id="m-fw-hilfe" class="hilfe">Der Firmware-Stand wird vom Gerät gemeldet und
                     hier nicht von Hand gepflegt.</p>
                  <fieldset><legend>Administrationszugang</legend>
                    <div class="feldreihe">
                      <div class="feldgruppe"><label for="m-aben">Benutzername</label>
                        <input id="m-aben" name="adminBenutzer" maxlength="80" value="%s" autocomplete="off"></div>
                      <div class="feldgruppe"><label for="m-apw">Neues Kennwort</label>
                        <input id="m-apw" name="adminPasswort" type="password" maxlength="200"
                               autocomplete="new-password" aria-describedby="m-apw-hilfe"></div>
                    </div>
                    <p id="m-apw-hilfe" class="hilfe">%s Ein leeres Feld lässt das gespeicherte
                       Kennwort unverändert.</p>
                  </fieldset>
                  <fieldset><legend>Karte und Automatik</legend>
                    <div class="ankreuz">
                      <input id="m-smcb" name="hatSmcb" type="checkbox" value="ja" %s>
                      <label for="m-smcb">In diesem Terminal steckt eine SMC-B</label>
                    </div>
                    <div class="ankreuz">
                      <input id="m-pevim" name="pevimAktiv" type="checkbox" value="ja" %s>
                      <label for="m-pevim">PIN-Automatik nutzen</label>
                    </div>
                    <p class="hilfe">Nur für SMC-B zulässig (gematik A_24080).</p>
                  </fieldset>
                  <div class="ankreuz">
                    <input id="m-aktiv" name="aktiv" type="checkbox" value="ja" %s>
                    <label for="m-aktiv">Aktiv — dieses Gerät wird überwacht</label>
                  </div>
                  <button class="knopf haupt" type="submit" style="width:auto">Änderungen speichern</button>
                </form></div>"""
                .formatted(t.id(), esc(t.bezeichnung()), esc(nz(t.standortangabe())),
                        esc(nz(t.adresse())), esc(nz(t.modell())), esc(nz(t.seriennummer())),
                        esc(t.firmware() == null ? "unbekannt" : t.firmware()),
                        esc(nz(t.adminBenutzer())),
                        t.adminPasswortHinterlegt()
                                ? "Ein Kennwort ist hinterlegt und lässt sich nicht anzeigen."
                                : "Es ist kein Kennwort hinterlegt.",
                        t.hatSmcb() ? "checked" : "", t.pevimAktiv() ? "checked" : "",
                        t.aktiv() ? "checked" : ""));
        return s.toString();
    }

    // ----------------------------------------------------------- Kleinteiliges

    /** Rückmeldung einer Erreichbarkeitsprüfung, als HTMX-Einschub. */
    static String pruefmeldung(String geraet, Bestandsverwaltung.Erreichbarkeit e) {
        return Anmeldeseiten.meldung(
                (e.erreichbar() ? "✓ " : "× ") + esc(geraet) + ": " + esc(e.hinweis())
                        + " (geprüft in " + e.dauerMs() + " ms)",
                e.erreichbar() ? "gut" : "warnend");
    }

    private static String blaetterleiste(Bestandsverwaltung.Seite<?> seite, String suche, Long konnektor) {
        if (seite.seitenZahl() <= 1) return "";
        String basis = "/bestand?" + (suche == null || suche.isBlank() ? "" : "suche=" + url(suche) + "&")
                + (konnektor == null ? "" : "konnektor=" + konnektor + "&");
        return """
                <nav class="blaettern" aria-label="Seiten der Geräteliste">
                  %s
                  <span>Seite %d von %d · %d Geräte</span>
                  %s
                </nav>"""
                .formatted(
                        seite.hatVorherige()
                                ? "<a class=\"knopf\" href=\"" + basis + "seite=" + (seite.seitenNummer() - 1)
                                  + "#terminals\" rel=\"prev\">Vorherige</a>"
                                : "<span class=\"knopf gesperrt\" aria-disabled=\"true\">Vorherige</span>",
                        seite.seitenNummer(), seite.seitenZahl(), seite.gesamt(),
                        seite.hatNaechste()
                                ? "<a class=\"knopf\" href=\"" + basis + "seite=" + (seite.seitenNummer() + 1)
                                  + "#terminals\" rel=\"next\">Nächste</a>"
                                : "<span class=\"knopf gesperrt\" aria-disabled=\"true\">Nächste</span>");
    }

    private static String smcbMarke(Bestandsverwaltung.Terminal t) {
        if (!t.hatSmcb()) return "<span class=\"marke\">keine</span>";
        return t.pevimAktiv()
                ? "<span class=\"marke ok\"><span class=\"sym\">✓</span>mit Automatik</span>"
                : "<span class=\"marke\">ohne Automatik</span>";
    }

    private static String zugangsMarke(String benutzer, boolean kennwort) {
        if (!kennwort && (benutzer == null || benutzer.isBlank())) {
            return "<span class=\"marke\">nicht hinterlegt</span>";
        }
        return "<span class=\"marke ok\"><span class=\"sym\">✓</span>"
                + esc(benutzer == null || benutzer.isBlank() ? "hinterlegt" : benutzer)
                + (kennwort ? " · Kennwort gespeichert" : " · ohne Kennwort") + "</span>";
    }

    private static String erreichbarkeitsMarke(Boolean erreichbar, String zeitpunkt, String hinweis) {
        if (erreichbar == null) return "<span class=\"marke\">noch nicht geprüft</span>";
        return "<span class=\"marke " + (erreichbar ? "ok" : "fehler") + "\" title=\""
                + esc(hinweis == null ? "" : hinweis) + "\"><span class=\"sym\">"
                + (erreichbar ? "✓" : "×") + "</span>"
                + (erreichbar ? "erreichbar" : "nicht erreichbar") + "</span>"
                + (zeitpunkt == null ? "" : "<div class=\"klein\">" + esc(zeitpunkt) + "</div>");
    }

    private static String organisationsOptionen(List<Organisationsauswahl> liste, Long gewaehlt) {
        StringBuilder s = new StringBuilder();
        for (var o : liste) {
            s.append("<option value=\"").append(o.id()).append("\"")
             .append(gewaehlt != null && gewaehlt == o.id() ? " selected" : "").append(">")
             .append(esc(o.bezeichnung())).append(" (").append(esc(artName(o.art()))).append(")</option>");
        }
        return s.toString();
    }

    private static String konnektorOptionen(List<Bestandsverwaltung.Konnektor> liste, Long gewaehlt) {
        StringBuilder s = new StringBuilder();
        for (var k : liste) {
            s.append("<option value=\"").append(k.id()).append("\"")
             .append(gewaehlt != null && gewaehlt == k.id() ? " selected" : "").append(">")
             .append(esc(k.bezeichnung())).append(" — ").append(esc(k.organisation())).append("</option>");
        }
        return s.toString();
    }

    private static String artName(String art) {
        return switch (art) {
            case "VERBUND"     -> "Verbund";
            case "STANDORT"    -> "Standort";
            case "EINRICHTUNG" -> "Einrichtung";
            default            -> art;
        };
    }

    private static String url(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
