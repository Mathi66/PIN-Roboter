package de.pinroboter.zentrale;

import java.util.List;

import static de.pinroboter.zentrale.Zentrale.esc;

/**
 * Oberfläche der Benachrichtigung (FA-ALA-020).
 *
 * <p>Zwei getrennte Dinge auf einer Seite: <b>wie</b> versendet wird (der
 * Mailserver, instanzweit, nur für den Betreiber) und <b>an wen</b> (die
 * Regeln je Einrichtung, für jeden Administrator in seinem Bereich).
 */
final class Benachrichtigungsseiten {

    private Benachrichtigungsseiten() { }

    static String seite(Benachrichtigung.Servereinstellung server,
                        List<Benachrichtigung.Regel> regeln,
                        List<Bestandsseiten.Organisationsauswahl> organisationen,
                        boolean darfServer, boolean darfRegeln,
                        String meldung, boolean istFehler, String verlauf) {
        StringBuilder s = new StringBuilder(Zentrale.seitenkopf("Benachrichtigung",
                "Wer wird per E-Mail informiert, wenn der Roboter etwas meldet.", null));

        if (meldung != null) s.append(Anmeldeseiten.meldung(meldung, istFehler ? "warnend" : "gut"));

        // Der SMTP-Verlauf hilft bei der Fehlersuche, gehoert aber nicht in den
        // Vordergrund - er ist technisch und fuer die meisten unlesbar.
        if (verlauf != null && !verlauf.isBlank()) {
            s.append("<details class=\"ausklapp\"><summary>Technische Einzelheiten des Versands</summary>")
             .append("<pre class=\"modell\">").append(esc(verlauf)).append("</pre></details>");
        }

        s.append(serverTafel(server, darfServer));
        s.append(regelTafel(regeln, organisationen, darfRegeln));
        return s.toString();
    }

    private static String serverTafel(Benachrichtigung.Servereinstellung e, boolean darf) {
        StringBuilder s = new StringBuilder("<div class=\"tafel\"><h3>Mailserver");
        s.append(e == null ? " <span class=\"marke warnung\">nicht eingerichtet</span>"
                           : " <span class=\"marke ok\"><span class=\"sym\">✓</span>eingerichtet</span>");
        s.append("</h3>");

        if (!darf) {
            return s.append("""
                    <div class="karte-leib karte-leib">
                    <div class="hinweis">Den Mailserver richtet der Betreiber dieser Instanz ein.
                      Sie können darunter festlegen, wer in Ihrem Bereich benachrichtigt wird.</div>
                    </div></div>""").toString();
        }

        s.append("""
                <form method="post" action="/benachrichtigung/server" class="maske">
                  <div class="feldreihe">
                    <div class="feldgruppe"><label for="m-host">Mailserver</label>
                      <input id="m-host" name="host" required maxlength="200" value="%s"
                             placeholder="mail.praxis.de"></div>
                    <div class="feldgruppe"><label for="m-port">Port</label>
                      <input id="m-port" name="port" required inputmode="numeric" value="%s"
                             aria-describedby="m-port-hilfe"></div>
                    <div class="feldgruppe"><label for="m-abs">Absenderadresse</label>
                      <input id="m-abs" name="absender" required maxlength="200" value="%s"
                             placeholder="pin-roboter@praxis.de"></div>
                  </div>
                  <p id="m-port-hilfe" class="hilfe">587 für STARTTLS (üblich), 465 für
                     durchgehendes TLS, 25 nur im eigenen Netz.</p>

                  <fieldset><legend>Anmeldung am Mailserver</legend>
                    <div class="feldreihe">
                      <div class="feldgruppe"><label for="m-ben">Benutzername <span class="optional">(optional)</span></label>
                        <input id="m-ben" name="benutzer" maxlength="200" value="%s" autocomplete="off"></div>
                      <div class="feldgruppe"><label for="m-pw">Kennwort</label>
                        <input id="m-pw" name="kennwort" type="password" maxlength="200"
                               autocomplete="new-password" aria-describedby="m-pw-hilfe"></div>
                    </div>
                    <p id="m-pw-hilfe" class="hilfe">%s Es wird verschlüsselt gespeichert und
                       lässt sich nicht wieder anzeigen. Ein leeres Feld lässt das gespeicherte
                       Kennwort unverändert.</p>
                  </fieldset>

                  <div class="ankreuz">
                    <input id="m-tls" name="tls" type="checkbox" value="ja" %s>
                    <label for="m-tls">Verschlüsselung verlangen (empfohlen)</label>
                  </div>
                  <p class="hilfe">Ohne diese Einstellung wird auch an Mailserver gesendet, die
                     keine Verschlüsselung anbieten. Die Nachricht wäre auf dem Weg mitlesbar —
                     vertretbar nur, wenn der Server im eigenen Netz steht.</p>

                  <button class="knopf haupt" type="submit" title="Speichert die Zugangsdaten verschlüsselt; das Kennwort erscheint nie im Protokoll" aria-label="Mailserver speichern">Mailserver speichern</button>
                </form>"""
                .formatted(e == null ? "" : esc(e.host()),
                        e == null ? "587" : String.valueOf(e.port()),
                        e == null ? "" : esc(e.absender()),
                        e == null ? "" : esc(nz(e.benutzer())),
                        e != null && e.kennwortHinterlegt()
                                ? "Ein Kennwort ist hinterlegt." : "Es ist kein Kennwort hinterlegt.",
                        e == null || e.tlsVerlangt() ? "checked" : ""));

        if (e != null) {
            s.append("""
                    <div class="maske" style="border-top:1px solid var(--rand)">
                      <form method="post" action="/benachrichtigung/test" class="feldreihe">
                        <div class="feldgruppe"><label for="m-test">Testnachricht senden an</label>
                          <input id="m-test" name="testempfaenger" required maxlength="200"
                                 placeholder="ihre.adresse@praxis.de"></div>
                        <div class="feldaktion">
                          <button id="m-test-k" class="knopf" type="submit" title="Sendet sofort eine Probemail an die eingetragenen Empfänger" aria-label="Testnachricht senden">Testnachricht senden</button></div>
                      </form>
                      <p class="hilfe">Ohne Testversand merken Sie erst beim ersten echten Alarm,
                         ob die Einstellung stimmt — also genau dann, wenn die Nachricht
                         gebraucht wird.</p>
                    </div>""");
        }
        return s.append("</div>").toString();
    }

    private static String regelTafel(List<Benachrichtigung.Regel> regeln,
                                     List<Bestandsseiten.Organisationsauswahl> organisationen,
                                     boolean darf) {
        StringBuilder s = new StringBuilder("<div class=\"tafel\"><h3>Wer wird benachrichtigt");
        s.append(" <span class=\"zaehler\">").append(regeln.size()).append("</span></h3>");

        if (regeln.isEmpty()) {
            s.append("""
                    <p class="leer">Noch keine Empfänger festgelegt. Ohne Regel verschickt der
                       Roboter keine E-Mail — Alarme erscheinen dann nur in der Oberfläche.</p>""");
        } else {
            s.append("""
                    <div class="rollbar"><table>
                    <caption class="nurLeser">Benachrichtigungsregeln je Organisationseinheit</caption>
                    <thead><tr>
                      <th scope="col">Einrichtung</th><th scope="col">Empfänger</th>
                      <th scope="col">Ab Schweregrad</th><th scope="col">Sammelfenster</th>
                      <th scope="col">Status</th><th scope="col">Aktion</th>
                    </tr></thead><tbody>""");
            for (var r : regeln) {
                s.append("""
                        <tr><td class="geraet">%s</td><td>%s</td><td>%s</td>
                        <td>%s</td><td>%s</td>
                        <td class="aktionen"><form method="post" action="/benachrichtigung/regel/%d/loeschen"
                              onsubmit="return confirm('Benachrichtigung für „%s“ entfernen?')">
                          <button class="knopf klein warnend" type="submit" title="Löscht diese Benachrichtigungsregel" aria-label="Regel entfernen">Entfernen</button>
                        </form></td></tr>"""
                        .formatted(esc(r.organisation()), esc(r.empfaenger()),
                                esc(schwelleAnzeige(r.schwelle())),
                                r.sammelfensterSekunden() == 0 ? "sofort"
                                        : esc(Benachrichtigung.fensterText(r.sammelfensterSekunden())),
                                r.aktiv() ? "<span class=\"marke ok\"><span class=\"sym\">✓</span>aktiv</span>"
                                          : "<span class=\"marke\">abgeschaltet</span>",
                                r.organisationId(), esc(r.organisation())));
            }
            s.append("</tbody></table></div>");
        }

        if (!darf) return s.append("</div>").toString();

        StringBuilder auswahl = new StringBuilder();
        for (var o : organisationen) {
            auswahl.append("<option value=\"").append(o.id()).append("\">")
                   .append(esc(o.bezeichnung())).append("</option>");
        }

        s.append("""
                <div class="maske" style="border-top:1px solid var(--rand)">
                <form method="post" action="/benachrichtigung/regel" class="maske karte-blank">
                  <div class="feldreihe">
                    <div class="feldgruppe"><label for="r-org">Einrichtung</label>
                      <select id="r-org" name="organisationId" required>%s</select></div>
                    <div class="feldgruppe"><label for="r-emp">Empfänger</label>
                      <input id="r-emp" name="empfaenger" required maxlength="400"
                             placeholder="leitung@praxis.de, it@dienstleister.de"
                             aria-describedby="r-emp-hilfe"></div>
                  </div>
                  <p id="r-emp-hilfe" class="hilfe">Mehrere Adressen durch Komma trennen.</p>
                  <div class="feldreihe">
                    <div class="feldgruppe"><label for="r-schwelle">Ab Schweregrad</label>
                      <select id="r-schwelle" name="schwelle">
                        <option value="KRITISCH">nur Kritisches</option>
                        <option value="WARNUNG" selected>Warnungen und Kritisches</option>
                        <option value="HINWEIS">alles</option>
                      </select></div>
                    <div class="feldgruppe"><label for="r-fenster">Sammelfenster (Sekunden)</label>
                      <input id="r-fenster" name="fenster" value="300" inputmode="numeric"
                             aria-describedby="r-fenster-hilfe"></div>
                  </div>
                  <p id="r-fenster-hilfe" class="hilfe">
                    <strong>Die wichtigste Einstellung auf dieser Seite.</strong> Fällt ein
                    Konnektor aus, entstehen binnen Sekunden hunderte Alarme. Der Roboter sammelt
                    sie für die eingestellte Dauer und verschickt <em>eine</em> Nachricht mit einer
                    Zusammenfassung. 0 bedeutet: jede Meldung sofort einzeln — bei wenigen Geräten
                    sinnvoll, bei vielen nicht.
                  </p>
                  <div class="ankreuz">
                    <input id="r-aktiv" name="aktiv" type="checkbox" value="ja" checked>
                    <label for="r-aktiv">Aktiv</label>
                  </div>
                  <button class="knopf haupt" type="submit" title="Legt fest, wer ab welchem Schweregrad benachrichtigt wird" aria-label="Benachrichtigungsregel speichern">Regel speichern</button>
                </form></div>""".formatted(auswahl));

        return s.append("</div>").toString();
    }

    private static String schwelleAnzeige(String s) {
        return switch (s) {
            case "KRITISCH" -> "nur Kritisches";
            case "WARNUNG"  -> "Warnungen und Kritisches";
            default         -> "alles";
        };
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
