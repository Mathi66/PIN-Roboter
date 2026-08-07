package de.pinroboter.zentrale;

import java.util.List;
import java.util.Optional;

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

    /**
     * Zustand der Geräteliste — Filter, Sortierung und Auswahllisten in einem
     * Bündel, damit jede Verknüpfung ihn vollständig weiterreichen kann. Ohne
     * das verliert ein Seitenwechsel den Filter, und der Benutzer wundert sich.
     */
    record Ansicht(Bestandsverwaltung.Filter filter, Bestandsverwaltung.Sortierung sortierung,
                   boolean absteigend, List<String> modelle, List<String> firmwareStaende,
                   List<Gruppenverwaltung.Gruppe> gruppen, Ermittlung.Auswahl aufrufkontext) {}

    static String seite(List<Bestandsverwaltung.Konnektor> konnektoren,
                        Bestandsverwaltung.Seite<Bestandsverwaltung.Terminal> terminals,
                        List<Organisationsauswahl> organisationen, Ansicht ansicht,
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
        s.append("""
                <p class="unterzeile">
                  <a class="knopf" href="/bestand/ermittelt">Ermittelte Geräte des Konnektors ansehen</a>
                </p>""");
        s.append(konnektorTafel(konnektoren, organisationen, ansicht.aufrufkontext()));
        s.append(terminalTafel(terminals, konnektoren, ansicht));
        return s.toString();
    }

    /** Kurzform einer Organisationseinheit für die Auswahlliste. */
    record Organisationsauswahl(long id, String bezeichnung, String art) {}

    // ------------------------------------------------------------ Konnektoren

    private static String konnektorTafel(List<Bestandsverwaltung.Konnektor> liste,
                                         List<Organisationsauswahl> organisationen,
                                         Ermittlung.Auswahl kontext) {
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
                  %s
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
                </form></details>""".formatted(organisationsOptionen(organisationen, null),
                        aufrufkontextFelder(kontext, "k", null, null, null)));

        return s.append("</div>").toString();
    }

    // -------------------------------------------------------------- Terminals

    private static String terminalTafel(Bestandsverwaltung.Seite<Bestandsverwaltung.Terminal> seite,
                                        List<Bestandsverwaltung.Konnektor> konnektoren,
                                        Ansicht a) {
        var f = a.filter();
        String suche = f.suche();
        Long filterKonnektor = f.konnektorId();
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
                  <div class="feldgruppe">
                    <label for="t-status">Status</label>
                    <select id="t-status" name="status">
                      <option value="">alle</option>
                      <option value="aktiv"%s>aktiv</option>
                      <option value="inaktiv"%s>deaktiviert</option>
                    </select>
                  </div>
                  <div class="feldgruppe">
                    <label for="t-err">Erreichbarkeit</label>
                    <select id="t-err" name="erreichbar">
                      <option value="">alle</option>
                      <option value="ja"%s>erreichbar</option>
                      <option value="nein"%s>nicht erreichbar</option>
                    </select>
                  </div>
                  <div class="feldgruppe">
                    <label for="t-modell">Modell</label>
                    <select id="t-modell" name="modell">
                      <option value="">alle Modelle</option>%s
                    </select>
                  </div>
                  <div class="feldgruppe">
                    <label for="t-fw">Firmware</label>
                    <select id="t-fw" name="firmware">
                      <option value="">alle Stände</option>%s
                    </select>
                  </div>
                  <input type="hidden" name="sortierung" value="%s">
                  <input type="hidden" name="richtung" value="%s">
                  <button class="knopf" type="submit">Filtern</button>
                  <a class="knopf" href="/bestand">Zurücksetzen</a>
                </form>"""
                .formatted(esc(suche == null ? "" : suche),
                        konnektorOptionen(konnektoren, filterKonnektor),
                        Boolean.TRUE.equals(f.aktiv()) ? " selected" : "",
                        Boolean.FALSE.equals(f.aktiv()) ? " selected" : "",
                        Boolean.TRUE.equals(f.erreichbar()) ? " selected" : "",
                        Boolean.FALSE.equals(f.erreichbar()) ? " selected" : "",
                        werteOptionen(a.modelle(), f.modell()),
                        werteOptionen(a.firmwareStaende(), f.firmware()),
                        a.sortierung().name().toLowerCase(),
                        a.absteigend() ? "ab" : "auf"));

        if (seite.eintraege().isEmpty()) {
            s.append("<p class=\"leer\">")
             .append(seite.gesamt() == 0 && (suche == null || suche.isBlank())
                     ? "Noch kein Kartenterminal erfasst."
                     : "Keine Kartenterminals für diese Suche.")
             .append("</p>");
        } else {
            s.append("""
                    <form method="post" action="/bestand/massenaktion" id="massenform"
                          onsubmit="return massenPruefung(this)">
                    <div class="rollbar"><table>
                    <caption class="nurLeser">Erfasste Kartenterminals</caption>
                    <thead><tr>
                      <th scope="col" class="wahl"><span class="nurLeser">Auswahl</span></th>
                      %s%s%s%s%s
                      <th scope="col">SMC-B</th>
                      <th scope="col">Zugang</th>
                      %s
                      <th scope="col">Aktionen</th>
                    </tr></thead><tbody>"""
                    .formatted(
                            spaltenkopf(a, Bestandsverwaltung.Sortierung.BEZEICHNUNG),
                            spaltenkopf(a, Bestandsverwaltung.Sortierung.KENNUNG),
                            spaltenkopf(a, Bestandsverwaltung.Sortierung.KONNEKTOR),
                            spaltenkopf(a, Bestandsverwaltung.Sortierung.STANDORT),
                            spaltenkopf(a, Bestandsverwaltung.Sortierung.MODELL),
                            spaltenkopf(a, Bestandsverwaltung.Sortierung.ERREICHBARKEIT)));
            for (var t : seite.eintraege()) {
                s.append("""
                        <tr%s>
                          <td class="wahl"><input type="checkbox" name="geraet" value="%d"
                            form="massenform" aria-label="%s auswählen"></td>
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
            s.append(massenleiste(a));
            s.append("</form>");
            s.append(blaetterleiste(seite, a));
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

    // ------------------------------------------------- Ermittelte Geräte

    /**
     * Zeigt den Abgleich zwischen Konnektor und Bestand (FA-GER-030, FA-GER-035).
     *
     * <p>Die Seite ist eine <b>Vorschlagsliste</b>, keine Bestandsansicht. Jede
     * Übernahme braucht einen Knopfdruck; nichts geschieht von selbst.
     */
    static String ermitteltSeite(List<Ermittlung.Fund> funde,
                                 Optional<Ermittlung.Modell> modell,
                                 List<Bestandsverwaltung.Konnektor> konnektoren,
                                 String meldung, boolean istFehler) {
        StringBuilder s = new StringBuilder("""
                <h2>Ermittelte Geräte</h2>
                <p class="unterzeile">Was der Standort-Agent beim Konnektor vorgefunden hat ·
                   <a href="/bestand">&larr; Zum erfassten Bestand</a></p>
                """);
        if (meldung != null) {
            s.append(Anmeldeseiten.meldung(meldung, istFehler ? "warnend" : "gut"));
        }

        if (funde.isEmpty()) {
            s.append("""
                    <div class="tafel"><p class="leer">
                      Noch keine Meldung eines Standort-Agenten. Der Agent fragt den Konnektor
                      bei jedem Prüfdurchlauf ab und meldet, was er findet — sobald er
                      verbunden ist, erscheinen die Geräte hier.
                    </p></div>""");
            return s.append(modellTafel(modell)).toString();
        }

        long neu = funde.stream().filter(f -> f.lage() == Ermittlung.Lage.NEU).count();
        s.append("<div class=\"tafel\"><h3>Abgleich mit dem Bestand <span class=\"zaehler\">")
         .append(funde.size()).append(" Gerät(e)");
        if (neu > 0) s.append(", ").append(neu).append(" noch nicht erfasst");
        s.append("</span></h3>");

        s.append("""
                <div class="hinweis">
                  <strong>Es wird nichts von selbst übernommen.</strong> Diese Liste ist ein
                  Vorschlag. Ein falsch konfigurierter oder zurückgesetzter Konnektor würde
                  sonst einen gepflegten Bestand überschreiben — mit Zugangsdaten und
                  Zuordnungen, die niemand wiederherstellen kann.
                </div>
                <div class="rollbar"><table>
                <caption class="nurLeser">Beim Konnektor ermittelte Kartenterminals</caption>
                <thead><tr>
                  <th scope="col" class="wahl"><span class="nurLeser">Auswahl</span></th>
                  <th scope="col">Kennung</th><th scope="col">Name</th>
                  <th scope="col">Modell / Firmware</th><th scope="col">SMC-B</th>
                  <th scope="col">PEviM</th><th scope="col">Lage</th>
                  <th scope="col">Zuletzt gesehen</th><th scope="col">Aktion</th>
                </tr></thead><tbody>""");

        for (var f : funde) {
            s.append("""
                    <tr>
                      <td class="wahl">%s</td>
                      <td><code>%s</code></td><td>%s</td>
                      <td class="klein">%s%s</td>
                      <td>%s</td><td>%s</td>
                      <td>%s%s</td>
                      <td class="klein">%s</td>
                      <td class="aktionen">%s</td>
                    </tr>"""
                    .formatted(
                            f.lage() == Ermittlung.Lage.NEU
                                    ? ("<input type=\"checkbox\" name=\"fund\" value=\"" + f.id()
                                       + "\" form=\"sammelform\" aria-label=\"" + esc(f.terminalId())
                                       + " auswählen\">")
                                    : "",
                            esc(f.terminalId()), esc(nz(f.name())),
                            esc(nz(f.modell())),
                            f.firmware() == null ? "" : " / " + esc(f.firmware()),
                            f.hatSmcb() ? "<span class=\"marke ok\"><span class=\"sym\">✓</span>vorhanden</span>"
                                        : "<span class=\"marke\">keine</span>",
                            pevimMarke(f), lageMarke(f), abweichungsliste(f),
                            esc(nz(f.gesehen())), uebernahmeKnopf(f, konnektoren)));
        }
        s.append("</tbody></table></div>");
        if (neu > 0 && !konnektoren.isEmpty()) {
            // Sammeluebernahme: FA-GER-030 erlaubt "einzeln oder als Auswahl".
            // Auch hier gilt die Grundregel - erst bestaetigen, dann uebernehmen.
            StringBuilder kOpt = new StringBuilder();
            for (var k : konnektoren) {
                kOpt.append("<option value=\"").append(k.id()).append("\">")
                    .append(esc(k.bezeichnung())).append(" — ").append(esc(k.organisation()))
                    .append("</option>");
            }
            s.append("""
                    <form method="post" action="/bestand/ermittelt/uebernehmen" id="sammelform"
                          class="massenleiste"
                          onsubmit="return sammelPruefung()">
                      <p class="hilfe">Ausgewählte Geräte:
                         <output id="s-zahl" for="sammelform">0</output> von %d neu erkannten.</p>
                      <div class="feldgruppe">
                        <label for="s-konnektor">Konnektor für alle</label>
                        <select id="s-konnektor" name="konnektorId">%s</select>
                      </div>
                      <button class="knopf haupt" type="submit">Auswahl übernehmen</button>
                      <p class="hilfe">Die Adressen der Geräte bleiben dabei leer und sind
                         danach je Gerät nachzutragen — der Konnektor liefert sie nicht mit.</p>
                    </form>
                    <script>
                      (function () {
                        function zaehlen() {
                          var n = document.querySelectorAll('input[name=fund]:checked').length;
                          var o = document.getElementById('s-zahl');
                          if (o) o.textContent = n;
                        }
                        document.addEventListener('change', function (e) {
                          if (e.target && e.target.name === 'fund') zaehlen();
                        });
                        zaehlen();
                      })();
                      function sammelPruefung() {
                        var n = document.querySelectorAll('input[name=fund]:checked').length;
                        if (n === 0) {
                          alert('Bitte zuerst mindestens ein Gerät auswählen.');
                          return false;
                        }
                        return confirm(n + ' Gerät(e) in den Bestand übernehmen?');
                      }
                    </script>""".formatted(neu, kOpt));
        }
        s.append("</div>");
        return s.append(modellTafel(modell)).toString();
    }

    private static String uebernahmeKnopf(Ermittlung.Fund f,
                                          List<Bestandsverwaltung.Konnektor> konnektoren) {
        if (f.lage() != Ermittlung.Lage.NEU) {
            return f.bestandsId() == null ? "—"
                    : "<a class=\"knopf klein\" href=\"/bestand/terminal/" + f.bestandsId()
                      + "\">Im Bestand ansehen</a>";
        }
        if (konnektoren.isEmpty()) {
            return "<span class=\"klein\">Erst einen Konnektor erfassen</span>";
        }
        return """
                <form method="post" action="/bestand/ermittelt/%d/uebernehmen" class="uebernahme">
                  <label class="nurLeser" for="u-k-%d">Konnektor für %s</label>
                  <select id="u-k-%d" name="konnektorId">%s</select>
                  <label class="nurLeser" for="u-a-%d">Adresse für %s</label>
                  <input id="u-a-%d" name="adresse" placeholder="Adresse (optional)" maxlength="200">
                  <button class="knopf klein haupt" type="submit">Übernehmen</button>
                </form>"""
                .formatted(f.id(), f.id(), esc(f.terminalId()), f.id(),
                        konnektorOptionen(konnektoren, null),
                        f.id(), esc(f.terminalId()), f.id());
    }

    private static String pevimMarke(Ermittlung.Fund f) {
        if (!f.pevimUnterstuetzt()) {
            return "<span class=\"marke\" title=\"Dieses Modell unterstützt die automatische "
                 + "PIN-Eingabe nicht (A_24074).\">nicht unterstützt</span>";
        }
        return f.pevimAktiv()
                ? "<span class=\"marke ok\"><span class=\"sym\">✓</span>aktiv</span>"
                : "<span class=\"marke warnung\" title=\"Die Funktion muss direkt am Gerät "
                  + "aktiviert werden (A_24076).\">nicht aktiviert</span>";
    }

    private static String lageMarke(Ermittlung.Fund f) {
        String art = switch (f.lage()) {
            case NEU          -> "warnung";
            case BEKANNT      -> "ok";
            case ABWEICHEND   -> "warnung";
            case VERSCHWUNDEN -> "fehler";
        };
        return "<span class=\"marke " + art + "\" title=\"" + esc(f.lage().erklaerung) + "\">"
                + esc(f.lage().anzeige) + "</span>";
    }

    private static String abweichungsliste(Ermittlung.Fund f) {
        if (f.abweichungen().isEmpty()) {
            return f.lage() == Ermittlung.Lage.VERSCHWUNDEN
                    ? "<div class=\"handlung\">Prüfen, ob das Gerät abgebaut wurde. "
                      + "Der Bestand wird nicht selbsttätig geändert.</div>"
                    : "";
        }
        StringBuilder s = new StringBuilder("<div class=\"handlung\">");
        for (String a : f.abweichungen()) s.append(esc(a)).append("<br>");
        return s.append("</div>").toString();
    }

    private static String modellTafel(Optional<Ermittlung.Modell> modell) {
        if (modell.isEmpty()) return "";
        var m = modell.get();
        if (!m.verfuegbar()) {
            // NFA-QS-035: Faellt die herstellerspezifische Schnittstelle aus,
            // muss die Oberflaeche auf die entfallende Unterstuetzung hinweisen -
            // und darf nicht so tun, als fehle etwas Pflichtiges.
            return """
                    <div class="tafel"><h3>Informationsmodell des Konnektors</h3>
                    <div class="anmeldeinhalt">%s
                      <p class="hilfe">Diese Schnittstelle ist herstellerspezifisch und von der
                         gematik nicht spezifiziert. Ohne sie funktioniert alles weiter — die
                         Werte des Aufrufkontexts (Mandant, Clientsystem, Arbeitsplatz) sind
                         dann von Hand einzutragen, statt zur Auswahl zu stehen.</p>
                    </div></div>"""
                    .formatted(Anmeldeseiten.meldung(
                            "Das Informationsmodell ist derzeit nicht abrufbar. "
                            + "Die Unterstützung beim Einrichten entfällt dadurch.", "warnend"));
        }
        return """
                <div class="tafel"><h3>Informationsmodell des Konnektors
                  <span class="zaehler">gelesen %s</span></h3>
                <div class="anmeldeinhalt">
                  <p class="hilfe">Aus diesen Werten setzt sich der Aufrufkontext zusammen
                     (<code>TUC_KON_000</code>). Sie beim Erfassen eines Konnektors
                     <strong>auszuwählen statt abzutippen</strong> beseitigt die häufigste
                     Fehlerquelle bei der Inbetriebnahme: Ein Tippfehler zeigt sich später
                     nur als unspezifischer Zugriffsfehler.</p>
                  <pre class="modell">%s</pre>
                </div></div>"""
                .formatted(esc(nz(m.gesehen())), esc(nz(m.inhalt())));
    }

    // ------------------------------------------------------- Einzelbearbeitung

    static String konnektorMaske(Bestandsverwaltung.Konnektor k, Ermittlung.Auswahl kontext,
                                 String fehler) {
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
                  %s
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
                        aufrufkontextFelder(kontext, "b", k.mandantId(), k.clientsystemId(),
                                k.arbeitsplatzId()),
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

    /**
     * Karten im Terminal (FA-GER-040).
     *
     * <p>Angezeigt werden <b>alle</b> Karten, nicht nur die SMC-B. eGK und HBA
     * stecken in der Praxis regelmäßig mit im Gerät; sie zu verschweigen wäre
     * bequem, aber der Betreiber soll sehen, was drinsteckt — und dass der
     * Roboter sie ausdrücklich in Ruhe lässt.
     */
    static String kartenTafel(List<Ermittlung.Karte> karten) {
        if (karten.isEmpty()) {
            return """
                    <div class="tafel"><h3>Karten im Terminal</h3>
                    <p class="leer">Noch keine Karten gemeldet. Der Standort-Agent meldet sie,
                       sobald er das Gerät abgefragt hat.</p></div>""";
        }
        StringBuilder s = new StringBuilder("""
                <div class="tafel"><h3>Karten im Terminal <span class="zaehler">%d</span></h3>
                <div class="rollbar"><table>
                <caption class="nurLeser">Karten im Kartenterminal</caption>
                <thead><tr>
                  <th scope="col">Kartentyp</th><th scope="col">ICCSN</th>
                  <th scope="col">Zertifikat gültig bis</th><th scope="col">Verwaltung</th>
                </tr></thead><tbody>""".formatted(karten.size()));
        for (var k : karten) {
            s.append("""
                    <tr><td>%s</td><td><code class="klein">%s</code></td>
                    <td>%s</td><td>%s</td></tr>"""
                    .formatted(esc(k.typAnzeige()), esc(nz(k.iccsn())),
                            ablaufMarke(k), verwaltungsMarke(k)));
        }
        s.append("</tbody></table></div>");
        // A_24080 gehoert erklaert, nicht nur durchgesetzt. Wer liest, warum
        // eine Karte nicht verwaltet wird, fragt nicht beim Support nach.
        if (karten.stream().anyMatch(k -> !k.verwaltet())) {
            s.append("""
                    <div class="hinweis">
                      <strong>eGK und HBA werden nicht verwaltet.</strong> Die automatische
                      PIN-Eingabe über die Managementschnittstelle ist ausschließlich für die
                      SMC-B zulässig (gematik A_24080). Der Roboter erfasst diese Karten, zeigt
                      sie an — und fasst sie nie an.
                    </div>""");
        }
        return s.append("</div>").toString();
    }

    private static String ablaufMarke(Ermittlung.Karte k) {
        if (k.gueltigBis() == null || k.gueltigBis().isBlank()) {
            return "<span class=\"marke\">nicht gemeldet</span>";
        }
        String datum = esc(k.gueltigBis());
        if (k.abgelaufen()) {
            return "<span class=\"marke fehler\"><span class=\"sym\">×</span>" + datum
                 + " — abgelaufen</span>";
        }
        if (k.laeuftBaldAb()) {
            // Der Austausch einer SMC-B braucht Vorlauf bei der
            // Kartenherausgabe. Drei Monate sind knapp, aber machbar.
            return "<span class=\"marke warnung\">" + datum + " — läuft bald ab</span>";
        }
        return datum;
    }

    private static String verwaltungsMarke(Ermittlung.Karte k) {
        return k.verwaltet()
                ? "<span class=\"marke ok\"><span class=\"sym\">✓</span>vom Roboter verwaltet</span>"
                : "<span class=\"marke\" title=\"PEviM ist nur für SMC-B zulässig (A_24080).\">"
                  + "nicht verwaltet</span>";
    }

    static String terminalMaske(Bestandsverwaltung.Terminal t, List<Ermittlung.Karte> karten,
                                String fehler) {
        return terminalMaske(t, karten, fehler, null, null, false, false);
    }

    static String terminalMaske(Bestandsverwaltung.Terminal t, List<Ermittlung.Karte> karten,
                                String fehler, String smcbHandle, String oeffentlicherSchluessel,
                                boolean pinHinterlegt, boolean auftragOffen) {
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
        s.append(kartenTafel(karten));
        s.append(pinTafel(t, smcbHandle, oeffentlicherSchluessel, pinHinterlegt, auftragOffen));
        return s.toString();
    }

    /**
     * Die PIN-Maske (FA-PIN-010, FA-PIN-060) nach ADR-0009.
     *
     * <h2>Die PIN verlässt diesen Browser nur verschlüsselt</h2>
     *
     * Das eingegebene Feld wird <b>nicht abgeschickt</b> — es hat bewusst keinen
     * {@code name}. Abgeschickt wird nur, was das Skript mit dem öffentlichen
     * Schlüssel des Standort-Agenten verschlüsselt hat. Damit kann die Zentrale
     * die PIN nicht lesen, selbst wenn sie es wollte.
     *
     * <h2>Warum hier ausnahmsweise JavaScript nötig ist</h2>
     *
     * Verschlüsseln im Browser geht ohne Skript nicht. Das ist eine echte
     * Einschränkung, und sie wird in der Maske ausgesprochen statt verschwiegen
     * (ADR-0009). Ohne Skript sieht der Betreiber den Hinweis und weiß, woran es
     * liegt — nicht ein Formular, das lautlos nichts tut.
     */
    static String pinTafel(Bestandsverwaltung.Terminal t, String smcbHandle,
                           String schluessel, boolean hinterlegt, boolean auftragOffen) {
        StringBuilder s = new StringBuilder("<div class=\"tafel\" id=\"pin\"><h3>SMC-B-PIN</h3>");

        if (!t.hatSmcb() || smcbHandle == null || smcbHandle.isBlank()) {
            return s.append("""
                    <p class="leer">Für dieses Kartenterminal ist keine SMC-B gemeldet.
                       Eine PIN lässt sich erst hinterlegen, wenn der Standort-Agent
                       eine SMC-B im Gerät gefunden hat.</p></div>""").toString();
        }

        // Zustand zuerst - die haeufigste Frage an dieser Stelle ist
        // "ist ueberhaupt eine PIN hinterlegt?", nicht "wie hinterlege ich eine".
        if (auftragOffen) {
            s.append("""
                    <p class="marke warnung">Wird zum Standort-Agenten übertragen …
                       Die Übertragung erfolgt beim nächsten Durchgang des Agenten.</p>""");
        } else if (hinterlegt) {
            s.append("""
                    <p class="marke ok"><span class="sym">✓</span>PIN hinterlegt</p>
                    <p class="hilfe">Die PIN liegt verschlüsselt beim Standort-Agenten.
                       Sie lässt sich hier nicht anzeigen — auch nicht von einem
                       Administrator. Zum Ändern eine neue PIN eingeben.</p>""");
        } else {
            s.append("""
                    <p class="marke">Keine PIN hinterlegt</p>
                    <p class="hilfe">Ohne hinterlegte PIN kann der Roboter den verifizierten
                       Zustand dieser SMC-B nicht wiederherstellen.</p>""");
        }

        if (schluessel == null || schluessel.isBlank()) {
            return s.append("""
                    <div class="hinweis"><strong>Noch kein Schlüssel des Standort-Agenten.</strong>
                      Die PIN wird im Browser mit dem Schlüssel des Agenten verschlüsselt, damit
                      sie die Zentrale nie im Klartext erreicht. Dieser Schlüssel liegt noch nicht
                      vor. Er wird gemeldet, sobald der Agent das erste Mal Bestand meldet —
                      danach ist diese Maske nutzbar.</div></div>""").toString();
        }

        s.append("""
                <noscript><div class="hinweis warnend">
                  <strong>Diese Maske braucht JavaScript.</strong> Die PIN wird in Ihrem Browser
                  verschlüsselt, bevor sie abgeschickt wird — damit die Zentrale sie nie im
                  Klartext sieht. Das geht ohne JavaScript nicht. Bitte JavaScript für diese
                  Seite erlauben. Alle anderen Funktionen des Produkts kommen ohne aus.
                </div></noscript>

                <form method="post" action="/bestand/terminal/%d/pin" class="maske" id="pinMaske">
                  <input type="hidden" name="chiffrat" id="pinChiffrat">
                  <input type="hidden" name="kartenhandle" value="%s">
                  <div class="feldgruppe">
                    <label for="pinFeld">%s</label>
                    <input id="pinFeld" type="password" inputmode="numeric" autocomplete="off"
                           maxlength="16" aria-describedby="pinHilfe">
                  </div>
                  <p id="pinHilfe" class="hilfe">
                    Die PIN wird in Ihrem Browser verschlüsselt und ausschließlich für den
                    Standort-Agenten lesbar übertragen. Sie wird nirgends im Klartext
                    gespeichert und erscheint in keinem Protokoll.
                  </p>
                  <p id="pinMeldung" class="hilfe" role="status" aria-live="polite"></p>
                  <button class="knopf haupt" type="submit" style="width:auto">%s</button>
                </form>""".formatted(t.id(), esc(smcbHandle),
                        hinterlegt ? "Neue PIN" : "PIN", hinterlegt ? "PIN ersetzen" : "PIN hinterlegen"));

        if (hinterlegt) {
            s.append("""
                    <form method="post" action="/bestand/terminal/%d/pin-loeschen" class="maske"
                          onsubmit="return confirm('Hinterlegte PIN wirklich löschen? Der Roboter kann diese SMC-B danach nicht mehr verifizieren.')">
                      <input type="hidden" name="kartenhandle" value="%s">
                      <button class="knopf" type="submit" style="width:auto">PIN löschen</button>
                    </form>
                    <p class="hilfe">Löschen wirkt sofort in der Anzeige und beim nächsten
                       Durchgang beim Agenten. Der Roboter verifiziert diese Karte danach
                       nicht mehr.</p>""".formatted(t.id(), esc(smcbHandle)));
        }

        s.append("<script>\n").append(pinSkript(schluessel)).append("\n</script>");
        return s.append("</div>").toString();
    }

    /**
     * Verschlüsselt die PIN im Browser (WebCrypto, RSA-OAEP mit SHA-256).
     *
     * <p>Muss zu {@code Schluesselpaar} im Agenten passen: SPKI-Import,
     * RSA-OAEP, SHA-256. Ändert sich dort etwas, ändert es sich hier mit.
     */
    private static String pinSkript(String schluesselBase64) {
        return """
                (function () {
                  var f = document.getElementById('pinMaske');
                  var feld = document.getElementById('pinFeld');
                  var ziel = document.getElementById('pinChiffrat');
                  var meldung = document.getElementById('pinMeldung');
                  if (!f || !window.crypto || !window.crypto.subtle) {
                    if (meldung) meldung.textContent =
                      'Dieser Browser unterstützt die nötige Verschlüsselung nicht. '
                      + 'Bitte einen aktuellen Browser verwenden — die PIN darf nicht '
                      + 'unverschlüsselt übertragen werden.';
                    if (f) f.querySelector('button').disabled = true;
                    return;
                  }
                  var roh = atob('%s');
                  var bytes = new Uint8Array(roh.length);
                  for (var i = 0; i < roh.length; i++) bytes[i] = roh.charCodeAt(i);

                  f.addEventListener('submit', function (e) {
                    if (ziel.value) return;           // zweiter Durchlauf: abschicken
                    e.preventDefault();
                    var pin = feld.value;
                    if (!pin) { meldung.textContent = 'Bitte eine PIN eingeben.'; return; }
                    meldung.textContent = 'Wird verschlüsselt …';
                    crypto.subtle.importKey('spki', bytes.buffer,
                        { name: 'RSA-OAEP', hash: 'SHA-256' }, false, ['encrypt'])
                      .then(function (k) {
                        return crypto.subtle.encrypt({ name: 'RSA-OAEP' }, k,
                            new TextEncoder().encode(pin));
                      })
                      .then(function (ch) {
                        var b = new Uint8Array(ch), t = '';
                        for (var i = 0; i < b.length; i++) t += String.fromCharCode(b[i]);
                        ziel.value = btoa(t);
                        feld.value = '';              // Klartext sofort aus dem Feld
                        meldung.textContent = 'Wird übertragen …';
                        f.submit();
                      })
                      .catch(function () {
                        meldung.textContent = 'Die PIN konnte nicht verschlüsselt werden. '
                          + 'Aus Sicherheitsgründen wurde nichts abgeschickt. '
                          + 'Bitte die Seite neu laden und erneut versuchen.';
                      });
                  });
                })();""".formatted(schluesselBase64);
    }

    // ----------------------------------------------------------- Kleinteiliges

    /** Rückmeldung einer Erreichbarkeitsprüfung, als HTMX-Einschub. */
    static String pruefmeldung(String geraet, Bestandsverwaltung.Erreichbarkeit e) {
        return Anmeldeseiten.meldung(
                (e.erreichbar() ? "✓ " : "× ") + esc(geraet) + ": " + esc(e.hinweis())
                        + " (geprüft in " + e.dauerMs() + " ms)",
                e.erreichbar() ? "gut" : "warnend");
    }

    /**
     * Baut die Abfragezeichenfolge aus dem gesamten Ansichtszustand.
     *
     * <p>Jeder Link muss Filter <b>und</b> Sortierung mitführen. Fehlt auch nur
     * eines, springt die Liste beim Blättern auf die Vorgabe zurück — bei 5.000
     * Geräten der sichere Weg, jemanden zu verlieren.
     */
    private static String zustand(Ansicht a) {
        var f = a.filter();
        StringBuilder q = new StringBuilder();
        if (f.suche() != null && !f.suche().isBlank()) q.append("suche=").append(url(f.suche())).append('&');
        if (f.konnektorId() != null) q.append("konnektor=").append(f.konnektorId()).append('&');
        if (f.aktiv() != null)       q.append("status=").append(f.aktiv() ? "aktiv" : "inaktiv").append('&');
        if (f.erreichbar() != null)  q.append("erreichbar=").append(f.erreichbar() ? "ja" : "nein").append('&');
        if (f.modell() != null && !f.modell().isBlank())     q.append("modell=").append(url(f.modell())).append('&');
        if (f.firmware() != null && !f.firmware().isBlank()) q.append("firmware=").append(url(f.firmware())).append('&');
        q.append("sortierung=").append(a.sortierung().name().toLowerCase()).append('&');
        q.append("richtung=").append(a.absteigend() ? "ab" : "auf").append('&');
        return q.toString();
    }

    /** Spaltenkopf, der beim Anklicken die Sortierung umschaltet. */
    private static String spaltenkopf(Ansicht a, Bestandsverwaltung.Sortierung nach) {
        boolean aktiv = a.sortierung() == nach;
        // Ein erneuter Klick auf dieselbe Spalte dreht die Richtung um.
        boolean neuAbsteigend = aktiv && !a.absteigend();
        var b = new Ansicht(a.filter(), nach, neuAbsteigend, a.modelle(), a.firmwareStaende(),
                a.gruppen(), a.aufrufkontext());
        String pfeil = !aktiv ? "" : (a.absteigend() ? " ▾" : " ▴");
        // aria-sort teilt Screenreadern mit, wonach gerade sortiert ist (WCAG).
        String ariaSort = !aktiv ? "none" : (a.absteigend() ? "descending" : "ascending");
        return """
                <th scope="col" aria-sort="%s"><a class="sortlink" href="/bestand?%s#terminals"
                   >%s%s</a></th>"""
                .formatted(ariaSort, zustand(b), esc(nach.anzeige), pfeil);
    }

    /**
     * Massenaktionen unter der Liste (FA-GER-060).
     *
     * <p>Bewusst <b>unter</b> der Tabelle: Erst auswählen, dann handeln. Eine
     * Aktionsleiste über einer 25-zeiligen Liste verleitet zum Klicken, bevor
     * die Auswahl steht.
     */
    private static String massenleiste(Ansicht a) {
        StringBuilder gruppen = new StringBuilder();
        for (var g : a.gruppen()) {
            gruppen.append("<option value=\"").append(g.id()).append("\">")
                   .append(esc(g.bezeichnung())).append(" (").append(esc(g.einrichtung()))
                   .append(")</option>");
        }
        String gruppenBlock = a.gruppen().isEmpty() ? "" : """
                  <div class="feldgruppe">
                    <label for="m-gruppe">Gerätegruppe</label>
                    <select id="m-gruppe" name="gruppeId">%s</select>
                  </div>
                  <button class="knopf" type="submit" name="aktion" value="gruppe">Gruppe zuweisen</button>
                """.formatted(gruppen);

        return """
                <div class="massenleiste">
                  <p class="hilfe" id="m-hinweis">Ausgewählte Geräte:
                     <output id="m-zahl" for="massenform">0</output>.
                     Aktionen wirken auf alle ausgewählten Geräte dieser Seite.</p>
                  %s
                  <button class="knopf" type="submit" name="aktion" value="aktivieren">Aktivieren</button>
                  <button class="knopf warnend" type="submit" name="aktion" value="deaktivieren"
                          >Deaktivieren</button>
                  <input type="hidden" name="zurueck" value="%s">
                </div>
                <script>
                  // Zaehlt die Auswahl mit und verhindert eine Aktion ohne Auswahl.
                  // Ohne diese Rueckmeldung klickt man auf "Deaktivieren" und
                  // weiss nicht, ob gerade 3 oder 30 Geraete gemeint sind.
                  (function () {
                    var f = document.getElementById('massenform');
                    if (!f) return;
                    function zaehlen() {
                      var n = document.querySelectorAll('input[name=geraet]:checked').length;
                      var o = document.getElementById('m-zahl');
                      if (o) o.textContent = n;
                    }
                    document.addEventListener('change', function (e) {
                      if (e.target && e.target.name === 'geraet') zaehlen();
                    });
                    zaehlen();
                  })();
                  function massenPruefung(f) {
                    var n = document.querySelectorAll('input[name=geraet]:checked').length;
                    if (n === 0) {
                      alert('Bitte zuerst mindestens ein Kartenterminal auswählen.');
                      return false;
                    }
                    return confirm('Aktion auf ' + n + ' ausgewählte(s) Kartenterminal(s) anwenden?');
                  }
                </script>""".formatted(gruppenBlock, esc(zustand(a)));
    }

    /**
     * Felder für den Aufrufkontext (FA-GER-035).
     *
     * <p>Liegt ein Informationsmodell vor, werden die Werte <b>zur Auswahl
     * angeboten</b> statt abgetippt. Das ist der eigentliche Zweck der
     * Anforderung: Ein Tippfehler in Mandanten-, Clientsystem- oder
     * Arbeitsplatz-ID zeigt sich später nur als unspezifischer Zugriffsfehler,
     * und niemand sucht ihn an der richtigen Stelle.
     *
     * <p>Fehlt das Modell — die Webadmin-Schnittstelle ist herstellerspezifisch
     * und immer optional (NFA-QS-035) —, erscheinen Freitextfelder. Und auch bei
     * vorhandenem Modell bleibt ein Freitextfeld erreichbar: Der Konnektor kann
     * Werte kennen, die er nicht meldet, und dann darf die Oberfläche nicht im
     * Weg stehen.
     */
    private static String aufrufkontextFelder(Ermittlung.Auswahl kontext, String praefix,
                                              String mandant, String clientsystem,
                                              String arbeitsplatz) {
        boolean ausModell = kontext != null && kontext.vorhanden();
        String einleitung = ausModell
                ? """
                  <p class="hilfe">Diese Werte stammen aus dem Informationsmodell des Konnektors
                     und begleiten jeden Aufruf. <strong>Auswählen statt abtippen</strong> —
                     ein Tippfehler zeigt sich später nur als unspezifischer Zugriffsfehler.
                     Über „anderer Wert“ lässt sich jederzeit ein nicht gemeldeter Wert
                     eintragen.</p>"""
                : """
                  <p class="hilfe">Diese drei Werte stehen in der Konfiguration des Konnektors
                     und begleiten jeden Aufruf. Ein Tippfehler zeigt sich später nur als
                     unspezifischer Zugriffsfehler — deshalb sorgfältig übertragen.
                     <em>Sobald ein Standort-Agent das Informationsmodell gelesen hat, stehen
                     sie hier zur Auswahl.</em></p>""";

        return """
                <fieldset><legend>Aufrufkontext</legend>
                  %s
                  <div class="feldreihe">%s%s%s</div>
                </fieldset>"""
                .formatted(einleitung,
                        kontextFeld(praefix + "-man", "mandantId", "Mandanten-ID",
                                ausModell ? kontext.mandanten() : List.of(), mandant),
                        kontextFeld(praefix + "-cli", "clientsystemId", "Clientsystem-ID",
                                ausModell ? kontext.clientsysteme() : List.of(), clientsystem),
                        kontextFeld(praefix + "-arb", "arbeitsplatzId", "Arbeitsplatz-ID",
                                ausModell ? kontext.arbeitsplaetze() : List.of(), arbeitsplatz));
    }

    /**
     * Ein Feld des Aufrufkontexts: Auswahlliste mit Freitext-Ausweg, oder reines
     * Freitextfeld.
     *
     * <p>Der Freitext-Ausweg ist als {@code <datalist>} umgesetzt — ein
     * gewöhnliches Eingabefeld mit Vorschlägen. Das ist einer Auswahlliste plus
     * „anderer Wert"-Umschaltung vorzuziehen: Es braucht kein JavaScript, ist
     * mit der Tastatur vollständig bedienbar und für Screenreader ein normales
     * Textfeld (WCAG 2.2 AA).
     */
    private static String kontextFeld(String id, String name, String beschriftung,
                                      List<Ermittlung.Eintrag> werte, String wert) {
        if (werte.isEmpty()) {
            return """
                    <div class="feldgruppe"><label for="%s">%s</label>
                      <input id="%s" name="%s" required maxlength="80" value="%s"></div>"""
                    .formatted(id, beschriftung, id, name, esc(nz(wert)));
        }
        StringBuilder opt = new StringBuilder();
        for (var e : werte) {
            opt.append("<option value=\"").append(esc(e.id())).append("\">")
               .append(esc(e.anzeige())).append("</option>");
        }
        return """
                <div class="feldgruppe"><label for="%s">%s</label>
                  <input id="%s" name="%s" list="%s-liste" required maxlength="80" value="%s"
                         autocomplete="off" aria-describedby="%s-hilfe">
                  <datalist id="%s-liste">%s</datalist>
                  <p id="%s-hilfe" class="hilfe">%d Wert(e) aus dem Konnektor · anderer Wert möglich</p>
                </div>"""
                .formatted(id, beschriftung, id, name, id, esc(nz(wert)), id, id, opt, id,
                        werte.size());
    }

    private static String werteOptionen(List<String> werte, String gewaehlt) {
        StringBuilder s = new StringBuilder();
        for (String w : werte) {
            s.append("<option value=\"").append(esc(w)).append('"')
             .append(w.equals(gewaehlt) ? " selected" : "").append('>')
             .append(esc(w)).append("</option>");
        }
        return s.toString();
    }

    private static String blaetterleiste(Bestandsverwaltung.Seite<?> seite, Ansicht a) {
        if (seite.seitenZahl() <= 1) return "";
        String basis = "/bestand?" + zustand(a);
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
