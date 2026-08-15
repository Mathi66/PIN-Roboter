package de.pinroboter.zentrale;

import java.util.List;

/**
 * Oberfläche der Einstellungen (FA-BAK-020 bis 050).
 *
 * <p>Zwei Rubriken: <b>Datensicherung</b> und <b>Systemtest</b>. Beide sind
 * Dinge, die man selten und bewusst tut — anders als „Instanz", wo der laufende
 * Betrieb steht.
 *
 * <h2>Was diese Seite anders macht als die übrigen</h2>
 *
 * Sie fragt an zwei Stellen nach einem <b>Schlüssel</b>, und dieser Schlüssel
 * wird nirgends gespeichert. Das ist ungewöhnlich genug, dass die Oberfläche es
 * <b>vor</b> dem Vorgang sagen muss, nicht danach: Wer den Schlüssel verliert,
 * kann seine heruntergeladene Sicherung nicht mehr öffnen — von niemandem, mit
 * keinem Werkzeug.
 */
final class Einstellungsseiten {

    private Einstellungsseiten() { }

    static String seite(List<Sicherungsverwaltung.Stand> staende,
                        Sicherungsverwaltung sicherung,
                        String systemtest, String meldung, boolean istFehler) {
        StringBuilder s = new StringBuilder(Zentrale.seitenkopf("Einstellungen",
                "Datensicherung und Systemtest. Vorgänge, die man selten und bewusst tut.",
                null));
        if (meldung != null) s.append(Anmeldeseiten.meldung(meldung, istFehler ? "warnend" : "gut"));

        s.append(ueberblick(sicherung));
        s.append(standListe(staende));
        s.append(hochladen());
        s.append("<div class=\"tafel\"><h3>Systemtest" + Bedienelemente.info("systemtest", "den Systemtest") + "</h3><div class=\"karte-leib\">"
               + "<p class=\"hilfe\">Werkzeuge für Vorführung und Messung. In einer "
               + "produktiven Installation werden sie nicht gebraucht.</p></div></div>");
        s.append(systemtest);
        return s.toString();
    }

    /** Je Plan der letzte Stand — die Frage „bin ich gesichert?" (FA-BAK-040). */
    private static String ueberblick(Sicherungsverwaltung v) {
        StringBuilder k = new StringBuilder();
        for (var plan : Sicherungsverwaltung.Plan.values()) {
            var staende = v.staende(plan);
            var letzter = staende.stream().findFirst();
            boolean ueberfaellig = v.ueberfaellig(plan);
            String fehler = v.fehler(plan);

            String zustand = fehler != null ? "fehler"
                    : ueberfaellig ? "warnung"
                    : letzter.isPresent() ? "ok" : "unbekannt";
            String zahl = letzter.map(st -> String.valueOf(staende.size())).orElse("—");
            String fuss = fehler != null ? "zuletzt fehlgeschlagen"
                    : letzter.map(st -> "zuletzt " + st.anzeige()).orElse("noch keine Sicherung");

            k.append("""
                    <div class="kachel %s">
                      <div class="bez">%s</div>
                      <div class="zahl">%s</div>
                      <span class="fuss">%s</span>
                    </div>"""
                    .formatted(zustand, esc(plan.anzeige), esc(zahl), esc(fuss)));
        }

        String hinweis;
        if (!v.planLaeuft()) {
            hinweis = """
                    <div class="hinweis warnend"><strong>Die geplante Sicherung ist ausgeschaltet.</strong>
                      Es wird nur gesichert, wenn Sie es von Hand auslösen. Eingeschaltet wird sie
                      über die Umgebungsvariable <code>SICHERUNG_PLAN</code>.</div>""";
        } else {
            hinweis = """
                    <div class="hinweis"><strong>Wo die Sicherungen liegen</strong>
                      In einem eigenen Speicherbereich, getrennt von der Datenbank — ein
                      versehentliches Entfernen der Betriebsdaten nimmt sie nicht mit.
                      <b>Gegen einen Ausfall der Festplatte hilft das nicht:</b> Dafür laden Sie
                      eine Sicherung herunter und bewahren sie woanders auf.</div>""";
        }

        return """
                <div class="tafel"><h3>Datensicherung
                  <span class="zaehler">%s belegt</span>" + Bedienelemente.info("sicherung-erstellen", "die Datensicherung") + "</h3>
                  <div class="karte-leib">
                    <div class="lage">%s</div>
                    %s
                    <div class="knopfreihe-links">
                      <form method="post" action="/einstellungen/sicherung/jetzt">
                        <input type="hidden" name="plan" value="TAEGLICH">
                        <button class="knopf haupt" type="submit"
                                title="Erzeugt sofort eine Sicherung, unabhängig vom Zeitplan"
                                aria-label="Jetzt sichern">Jetzt sichern</button>
                      </form>
                    </div>
                  </div>
                </div>""".formatted(esc(groesse(v.belegt())), k, hinweis);
    }

    /** Die vorhandenen Stände mit Herunterladen, Einspielen, Löschen. */
    private static String standListe(List<Sicherungsverwaltung.Stand> staende) {
        if (staende.isEmpty()) {
            return """
                    <div class="tafel"><h3>Vorhandene Stände" + Bedienelemente.info("sicherung-wiederherstellen", "die Wiederherstellung") + "</h3>
                      <p class="leer">Noch keine Sicherung vorhanden. Die erste entsteht
                         automatisch, sobald der Zeitplan das erste Mal greift.</p>
                    </div>""";
        }
        StringBuilder z = new StringBuilder();
        for (var st : staende) {
            z.append("""
                    <tr>
                      <td class="geraet">%s<span class="kennung zweitzeile">%s</span></td>
                      <td>%s</td>
                      <td class="zahl-zelle">%s</td>
                      <td class="aktionen">
                        <a class="knopf klein" href="/einstellungen/sicherung/%s/herunterladen"
                           title="Fragt nach einem Schlüssel und lädt die Sicherung verschlüsselt herunter"
                           aria-label="Sicherung herunterladen">Herunterladen</a>
                        <form method="post" action="/einstellungen/sicherung/%s/einspielen" class="reihe"
                              data-nachfrage="Stand vom %s wirklich einspielen? Der aktuelle Datenbestand wird ersetzt. Die Zentrale startet danach neu, alle Angemeldeten werden abgemeldet.">
                          <button class="knopf klein" type="submit"
                                  title="Ersetzt den aktuellen Datenbestand durch diesen Stand; die Zentrale startet danach neu"
                                  aria-label="Diesen Stand einspielen">Einspielen</button>
                        </form>
                        <form method="post" action="/einstellungen/sicherung/%s/loeschen" class="reihe"
                              data-nachfrage="Sicherung vom %s löschen? Sie lässt sich danach nicht wiederherstellen.">
                          <button class="knopf klein warnend" type="submit"
                                  title="Entfernt diese Sicherung endgültig"
                                  aria-label="Sicherung löschen">Löschen</button>
                        </form>
                      </td>
                    </tr>"""
                    .formatted(esc(st.anzeige()), esc(st.plan().anzeige),
                            esc(st.plan().anzeige), esc(st.groesse()),
                            esc(st.dateiname()), esc(st.dateiname()), esc(st.anzeige()),
                            esc(st.dateiname()), esc(st.anzeige())));
        }
        return """
                <div class="tafel"><h3>Vorhandene Stände <span class="zaehler">%d</span>" + Bedienelemente.info("sicherung-wiederherstellen", "die Wiederherstellung") + "</h3>
                  <div class="rollbar"><table>
                    <caption class="nurLeser">Vorhandene Sicherungen</caption>
                    <thead><tr>
                      <th scope="col">Zeitpunkt</th><th scope="col">Plan</th>
                      <th scope="col" class="zahl-zelle">Größe</th>
                      <th scope="col"><span class="nurLeser">Aktionen</span></th>
                    </tr></thead><tbody>%s</tbody></table></div>
                </div>""".formatted(staende.size(), z);
    }

    /** Eine Sicherung von außen einlesen. */
    private static String hochladen() {
        return """
                <div class="tafel"><h3>Sicherung hochladen" + Bedienelemente.info("sicherung-wiederherstellen", "das Hochladen") + "</h3>
                  <form method="post" action="/einstellungen/sicherung/hochladen"
                        enctype="multipart/form-data" class="karte-leib">
                    <div class="hinweis"><strong>Nur Dateien, die hier heruntergeladen wurden</strong>
                      Die Datei wird mit dem Schlüssel geöffnet und dabei geprüft. Wurde sie
                      nachträglich verändert, wird sie abgewiesen — nicht teilweise eingelesen.
                      Hochladen spielt noch nichts ein; der Stand erscheint danach in der Liste.</div>
                    <div class="feldreihe">
                      <div class="feldgruppe">
                        <label for="s-datei">Sicherungsdatei</label>
                        <input id="s-datei" type="file" name="datei" required accept=".prsich">
                      </div>
                      <div class="feldgruppe">
                        <label for="s-schluessel">Schlüssel der Datei</label>
                        <input id="s-schluessel" type="password" name="schluessel" required
                               autocomplete="off" minlength="12"
                               placeholder="derselbe wie beim Herunterladen">
                      </div>
                      <div class="feldaktion">
                        <button class="knopf" type="submit"
                                title="Liest die Datei ein und prüft sie; eingespielt wird noch nichts"
                                aria-label="Sicherung hochladen">Hochladen</button>
                      </div>
                    </div>
                  </form>
                </div>""";
    }

    /**
     * Die Maske für den Schlüssel vor dem Herunterladen.
     *
     * <p>Eine eigene Seite statt eines Aufklappers: Der Hinweis, dass ein
     * verlorener Schlüssel die Datei endgültig unbrauchbar macht, muss gelesen
     * werden, bevor jemand tippt. In einem Aufklapper neben dem Knopf würde er
     * überblättert.
     */
    static String schluesselMaske(Sicherungsverwaltung.Stand st, String fehler) {
        return Zentrale.detailkopf("/einstellungen", "Einstellungen",
                "Sicherung herunterladen", esc(st.anzeige()) + " · " + esc(st.groesse()), null)
             + (fehler == null ? "" : Anmeldeseiten.meldung(fehler, "warnend"))
             + """
                <div class="tafel">
                  <form method="post" action="/einstellungen/sicherung/%s/herunterladen"
                        class="karte-leib">
                    <div class="hinweis warnend">
                      <strong>Diesen Schlüssel gibt es nur einmal — bei Ihnen.</strong>
                      Die Datei wird damit verschlüsselt. Der Schlüssel wird <b>nicht
                      gespeichert</b>: nicht hier, nicht in der Datenbank, nicht im Protokoll.
                      Geht er verloren, lässt sich die Datei von niemandem mehr öffnen — auch
                      nicht vom Hersteller.
                    </div>
                    <div class="feldreihe">
                      <div class="feldgruppe">
                        <label for="d-schluessel">Schlüssel</label>
                        <input id="d-schluessel" type="password" name="schluessel" required
                               autocomplete="off" minlength="12" autofocus
                               aria-describedby="d-hilfe">
                        <p id="d-hilfe" class="hilfe">Mindestens 12 Zeichen. Eine Folge mehrerer
                           Wörter ist leichter zu merken und sicherer als etwas Kurzes mit
                           Sonderzeichen.</p>
                      </div>
                      <div class="feldgruppe">
                        <label for="d-schluessel2">Schlüssel wiederholen</label>
                        <input id="d-schluessel2" type="password" name="schluessel2" required
                               autocomplete="off" minlength="12">
                        <p class="hilfe">Ein Tippfehler fällt sonst erst auf, wenn die Sicherung
                           gebraucht wird.</p>
                      </div>
                    </div>
                    <div class="knopfreihe-links">
                      <button class="knopf haupt" type="submit"
                              title="Verschlüsselt die Sicherung mit diesem Schlüssel und lädt sie herunter"
                              aria-label="Verschlüsselt herunterladen">Verschlüsselt herunterladen</button>
                      <a class="knopf" href="/einstellungen"
                         title="Bricht ab und kehrt zu den Einstellungen zurück"
                         aria-label="Abbrechen">Abbrechen</a>
                    </div>
                  </form>
                </div>""".formatted(esc(st.dateiname()));
    }

    private static String groesse(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        return String.format(java.util.Locale.GERMANY, "%.1f MB", bytes / 1024.0 / 1024.0);
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }
}
