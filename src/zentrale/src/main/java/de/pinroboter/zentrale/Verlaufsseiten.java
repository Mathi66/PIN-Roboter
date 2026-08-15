package de.pinroboter.zentrale;

import java.util.List;

import static de.pinroboter.zentrale.Zentrale.esc;

/**
 * Zwei der vier Sichten aus ADR-0005: Geräte-Zeitstrahl (FA-PRO-050) und
 * Vorgangsansicht (FA-PRO-060).
 *
 * <p>Beide beantworten dieselbe Grundfrage — <i>was ist hier passiert?</i> —
 * aus zwei Richtungen. Der Zeitstrahl fragt nach einem <b>Gerät</b> über die
 * Zeit, die Vorgangsansicht nach einem <b>Vorgang</b> über die Geräte.
 *
 * <p>Das gefilterte Protokoll leistet das nicht: Dort sucht man Einträge, hier
 * liest man eine Geschichte.
 */
final class Verlaufsseiten {

    private Verlaufsseiten() { }

    /** Auswählbare Zeiträume. Mehr Auswahl hilft niemandem. */
    record Zeitraum(String kennung, String anzeige, java.time.Duration dauer) {}

    static final List<Zeitraum> ZEITRAEUME = List.of(
            new Zeitraum("24h", "letzte 24 Stunden", java.time.Duration.ofHours(24)),
            new Zeitraum("7t",  "letzte 7 Tage",     java.time.Duration.ofDays(7)),
            new Zeitraum("30t", "letzte 30 Tage",    java.time.Duration.ofDays(30)),
            new Zeitraum("alle","alles",             null));

    static java.time.Instant seitFuer(String kennung) {
        return ZEITRAEUME.stream()
                .filter(z -> z.kennung().equals(kennung)).findFirst()
                .map(Zeitraum::dauer).map(d -> d == null ? null : java.time.Instant.now().minus(d))
                .orElse(java.time.Instant.now().minus(java.time.Duration.ofDays(7)));
    }

    // ─────────────────────────────────────────────── Geräte-Zeitstrahl

    static String zeitstrahl(String terminalId, String bezeichnung,
                             Fachprotokoll.Seite seite, String zeitraum,
                             String sicht, List<String> feinauswahl) {
        StringBuilder s = new StringBuilder(Zentrale.detailkopf(
                "/geraete", "Kartenterminals", bezeichnung,
                "Verlauf von <code>" + esc(terminalId) + "</code>", null));

        s.append("<div class=\"tafel\"><h3>Zeitstrahl <span class=\"zaehler\">")
         .append(seite.gesamt()).append("</span>" + Bedienelemente.info("zeitstrahl", "den Zeitstrahl") + "</h3>");
        s.append(filterleiste(terminalId, zeitraum, sicht, feinauswahl));

        if (seite.eintraege().isEmpty()) {
            s.append("""
                    <p class="leer">Für diesen Zeitraum und diese Auswahl gibt es keine
                       Einträge. Ein längerer Zeitraum oder weniger Filter zeigt mehr.</p>""");
        } else {
            s.append("<div class=\"baum\">");
            String letzterTag = null;
            for (var e : seite.eintraege()) {
                String tag = e.zeitLokal().substring(0, 10);
                if (!tag.equals(letzterTag)) {
                    // Tagestrenner: Ohne ihn verschwimmt bei 200 Eintraegen,
                    // was gestern und was heute war.
                    s.append("<div class=\"bereich-titel tagestrenner\">")
                     .append(esc(tag)).append("</div>");
                    letzterTag = tag;
                }
                s.append(eintrag(e));
            }
            s.append("</div>");
        }
        return s.append("</div>").toString();
    }

    private static String eintrag(Ereignis e) {
        String stufe = !e.erfolg() ? "e-fehler"
                : switch (e.art().stufe().name()) {
                    case "SICHERHEIT" -> "e-warnung";
                    default           -> "e-ok";
                  };
        // Den Text nur zeigen, wenn er mehr sagt als die Ereignisart selbst.
        // Sonst steht "Gerät wieder erreichbar" zweimal untereinander.
        String text = e.text() == null ? "" : e.text().trim();
        boolean sagtMehr = !text.isEmpty() && !text.equalsIgnoreCase(e.art().anzeige());

        return """
                <div class="eintrag %s">
                  <div class="eintrag-streifen" aria-hidden="true"></div>
                  <div class="eintrag-text">
                    <b>%s</b>
                    %s
                    %s
                    <span class="wann">%s · %s%s</span>
                  </div>
                </div>""".formatted(stufe, esc(e.art().anzeige()),
                    sagtMehr ? "<div class=\"handlung\" style=\"border-left-color:var(--rand)\">"
                             + esc(text) + "</div>" : "",
                    e.handlung() == null || e.handlung().isBlank() ? ""
                            : "<div class=\"handlung\">" + esc(e.handlung()) + "</div>",
                    esc(e.zeitLokal()), esc(e.ausloeser()),
                    e.vorgang() == null || e.vorgang().isBlank() ? ""
                            : " · <a href=\"/vorgang/" + esc(e.vorgang()) + "\">Vorgang ansehen</a>");
    }

    /**
     * Die vier Sichten, die tatsächlich gebraucht werden.
     *
     * <p>Bis zum 10.08.2026 zeigte der Zeitstrahl <b>59 Ankreuzfelder, alle
     * angehakt</b>. Das ist kein Filter — es ist eine Wand. Zwei Dinge waren
     * daran falsch:
     *
     * <ul>
     *   <li><b>Die Vorgabe „alles an" verlangt Abwählen.</b> Wer nur
     *       Freischaltungen sehen will, müsste 52 Häkchen entfernen. Das tut
     *       niemand — also filtert niemand.</li>
     *   <li><b>Die Liste folgte der Datenbank, nicht der Frage.</b> Ein
     *       Anwender fragt „gab es Probleme?", nicht „möchte ich
     *       {@code PEVIM_NICHT_AKTIV} sehen?".</li>
     * </ul>
     *
     * <p>Deshalb jetzt: vier Sichten, die je eine echte Frage beantworten. Die
     * Feinauswahl bleibt erreichbar — sie ist die Ausnahme, nicht der Regelweg,
     * und deshalb zugeklappt.
     */
    private enum Sicht {
        ALLES("Alles", "Jeder Eintrag, nichts ausgeblendet"),
        PROBLEME("Nur Probleme", "Fehlschläge, Ausfälle, gesperrte Karten — was schiefging"),
        AUTOMATIK("Was der Roboter tat", "Freischaltungen, Prüfungen, Zustandswechsel der Geräte"),
        MENSCHEN("Änderungen durch Menschen", "Wer hat was erfasst, geändert, gelöscht");

        final String anzeige, erklaerung;
        Sicht(String anzeige, String erklaerung) { this.anzeige = anzeige; this.erklaerung = erklaerung; }

        boolean passt(de.pinroboter.gemeinsam.Ereignisart art) {
            return switch (this) {
                case ALLES     -> true;
                case PROBLEME  -> art.istProblem();
                case AUTOMATIK -> art.istAutomatik();
                // Alles, was ein Mensch auslöst: weder Automatik noch reine
                // Störungsmeldung. Bewusst als Rest definiert — sonst fällt
                // eine neue Ereignisart aus allen Sichten heraus und ist nur
                // noch über die Feinauswahl zu finden.
                case MENSCHEN  -> !art.istAutomatik() && !art.istProblem();
            };
        }

        static Sicht aus(String s) {
            for (Sicht x : values()) if (x.name().equalsIgnoreCase(s)) return x;
            return ALLES;
        }
    }

    /** Die Ereignisarten einer Sicht — für den Filter der Abfrage. */
    static List<String> artenDerSicht(String sicht) {
        Sicht s = Sicht.aus(sicht);
        if (s == Sicht.ALLES) return List.of();
        return java.util.Arrays.stream(de.pinroboter.gemeinsam.Ereignisart.values())
                .filter(s::passt).map(Enum::name).toList();
    }

    /**
     * Zeitraum, Sicht und Feinauswahl.
     *
     * @param sicht      die ausdrücklich gewählte Sicht ({@code null} = Alles)
     * @param feinauswahl die vom Benutzer <b>selbst</b> angekreuzten Arten —
     *                   NICHT die, die sich aus der Sicht ergeben
     */
    private static String filterleiste(String terminalId, String zeitraum,
                                       String sicht, List<String> feinauswahl) {
        StringBuilder z = new StringBuilder();
        for (var zr : ZEITRAEUME) {
            z.append("<option value=\"").append(zr.kennung()).append("\"")
             .append(zr.kennung().equals(zeitraum) ? " selected" : "").append(">")
             .append(esc(zr.anzeige())).append("</option>");
        }

        // Die aktive Sicht steht im Parameter — sie wird NICHT mehr aus der
        // Artenliste zurueckgerechnet.
        //
        // Der Fehler dahinter (Befund PO, 11.08.2026): Die Sicht kreuzte die
        // zugehoerigen Arten in der Feinauswahl an. Beim naechsten Absenden
        // gingen diese Haekchen mit — und weil die Feinauswahl die Sicht
        // ueberstimmt, blieb die Ansicht stehen, egal welche Kachel man klickte.
        // Eine Sackgasse: Wer einmal „Nur Probleme" gewaehlt hatte, kam nicht
        // mehr heraus.
        boolean feinAktiv = !feinauswahl.isEmpty();
        String aktiv = feinAktiv ? "" : Sicht.aus(sicht).name();

        // Die Sichten sind LINKS, keine Radios im Formular.
        //
        // Ein Link traegt nur den Zeitraum und die Sicht — die Feinauswahl
        // faellt dabei weg, und genau das ist gemeint: „Sicht waehlen" setzt
        // eine Feinauswahl ausser Kraft. Als Formularfelder liesse sich das
        // ohne JavaScript nicht ausdruecken, und ein onchange-Handler hat den
        // Widerspruch oben nur verdeckt.
        StringBuilder sichten = new StringBuilder();
        for (Sicht s : Sicht.values()) {
            boolean ist = s.name().equals(aktiv);
            sichten.append("""
                    <a class="sicht%s" href="/geraete/%s/verlauf?zeitraum=%s&sicht=%s"%s
                       title="%s">
                      <span class="sicht-name">%s</span>
                      <span class="sicht-was">%s</span>
                    </a>"""
                    .formatted(ist ? " gewaehlt" : "", esc(terminalId), esc(zeitraum), s.name(),
                            ist ? " aria-current=\"true\"" : "",
                            esc(s.erklaerung), esc(s.anzeige), esc(s.erklaerung)));
        }
        if (feinAktiv) {
            sichten.append("""
                    <p class="hilfe sichten-fuss">
                      Zurzeit gilt die Feinauswahl unten (%d Ereignisart(en)).
                      Ein Klick auf eine Sicht setzt sie zurück.
                    </p>""".formatted(feinauswahl.size()));
        }

        // Die Feinauswahl: nach Themenbereich gruppiert, zugeklappt. Sie ist
        // fuer den Fall da, dass jemand genau eine Ereignisart sucht — nicht
        // fuer den Alltag.
        StringBuilder fein = new StringBuilder();
        for (var b : de.pinroboter.gemeinsam.Ereignisart.Bereich.values()) {
            fein.append("<fieldset class=\"artgruppe\"><legend>")
                .append(esc(b.anzeige())).append("</legend>");
            for (var art : de.pinroboter.gemeinsam.Ereignisart.values()) {
                if (art.bereich() != b) continue;
                boolean an = feinauswahl.contains(art.name());
                fein.append("<label class=\"ankreuz nebeneinander\">")
                    .append("<input type=\"checkbox\" name=\"art\" value=\"")
                    .append(art.name()).append("\"").append(an ? " checked" : "")
                    .append("> <span>").append(esc(art.anzeige())).append("</span></label>");
            }
            fein.append("</fieldset>");
        }

        return """
                <form method="get" action="/geraete/%s/verlauf" class="filterleiste">
                  <div class="feldgruppe schmal">
                    <label for="v-zeit">Zeitraum</label>
                    <select id="v-zeit" name="zeitraum">%s</select>
                  </div>
                  <div class="feldaktion">
                    <button class="knopf" type="submit"
                            title="Wendet Zeitraum und Feinauswahl auf den Zeitstrahl an"
                            aria-label="Filter anwenden">Anwenden</button>
                  </div>
                  <details class="ausklapp feinauswahl">
                    <summary>Einzelne Ereignisarten wählen</summary>
                    <div class="feinauswahl-leib">
                      <p class="hilfe">Setzt die Sicht oben außer Kraft. Ohne Häkchen
                         entscheidet die Sicht.</p>
                      %s
                    </div>
                  </details>
                </form>
                <nav class="sichten" aria-label="Welche Ereignisse sollen erscheinen?">%s</nav>"""
                .formatted(esc(terminalId), z, fein, sichten);
    }

    // ─────────────────────────────────────────────── Vorgangsansicht

    /**
     * Alle Ereignisse eines Vorgangs (FA-PRO-060).
     *
     * <p>Ein Vorgang kann mehrere Geräte betreffen — ein KSR-Lauf, eine
     * Massenaktion, später ein Firmware-Rollout. Die Ansicht zeigt deshalb den
     * <b>Fortschritt</b>: wie viele Geräte erledigt, wie viele offen, wie viele
     * fehlgeschlagen sind. Eine reine Liste beantwortet die Frage „ist das
     * durch?" nicht.
     */
    static String vorgang(String vorgangsnummer, Fachprotokoll.Seite seite) {
        long geraete = seite.eintraege().stream()
                .map(Ereignis::geraet).filter(java.util.Objects::nonNull).distinct().count();
        long fehler = seite.eintraege().stream().filter(e -> !e.erfolg()).count();
        boolean laeuft = seite.eintraege().stream()
                .anyMatch(e -> e.zeitpunkt().isAfter(java.time.Instant.now().minusSeconds(120)));

        StringBuilder s = new StringBuilder(Zentrale.detailkopf(
                "/protokoll", "Protokoll", "Vorgang " + vorgangsnummer,
                seite.gesamt() + " Ereignis(se)"
                        + (geraete > 0 ? " an " + geraete + " Gerät(en)" : ""),
                laeuft ? "<span class=\"marke warnung\"><span class=\"puls\"></span>läuft</span>"
                       : "<span class=\"marke ok\"><span class=\"sym\">✓</span>abgeschlossen</span>"));

        if (seite.eintraege().isEmpty()) {
            return s.append("""
                    <div class="tafel"><p class="leer">Zu dieser Vorgangsnummer gibt es keine
                       Einträge — vielleicht ist sie schon aus dem Protokoll rotiert.</p></div>""")
                    .toString();
        }

        s.append("""
                <dl class="lage">
                  <div class="kachel"><dt class="bez">Ereignisse</dt><dd class="zahl">%d</dd></div>
                  <div class="kachel"><dt class="bez">Betroffene Geräte</dt><dd class="zahl">%d</dd></div>
                  <div class="kachel %s"><dt class="bez">Fehlgeschlagen</dt><dd class="zahl">%d</dd></div>
                </dl>""".formatted(seite.gesamt(), geraete, fehler > 0 ? "fehler" : "", fehler));

        // Selbsttaetige Aktualisierung, solange der Vorgang laeuft — danach
        // nicht mehr: Ein abgeschlossener Vorgang aendert sich nicht, und
        // jede weitere Abfrage waere Last ohne Nutzen.
        s.append("<div class=\"tafel\"")
         .append(laeuft ? " hx-get=\"/teil/vorgang/" + esc(vorgangsnummer)
                        + "\" hx-trigger=\"every 5s\" hx-swap=\"outerHTML\"" : "")
         .append("><h3>Verlauf" + Bedienelemente.info("zeitstrahl", "die Vorgangsansicht") + "</h3><div class=\"baum\">");
        for (var e : seite.eintraege()) s.append(eintrag(e));
        return s.append("</div></div>").toString();
    }
}
