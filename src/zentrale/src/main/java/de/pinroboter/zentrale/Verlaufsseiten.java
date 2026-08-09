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
                             List<String> gewaehlteArten) {
        StringBuilder s = new StringBuilder(Zentrale.detailkopf(
                "/geraete", "Kartenterminals", bezeichnung,
                "Verlauf von <code>" + esc(terminalId) + "</code>", null));

        s.append("<div class=\"tafel\"><h3>Zeitstrahl <span class=\"zaehler\">")
         .append(seite.gesamt()).append("</span></h3>");
        s.append(filterleiste(terminalId, zeitraum, gewaehlteArten));

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
                    s.append("<div class=\"bereich-titel\" style=\"padding:.7rem 1.2rem .3rem\">")
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

    private static String filterleiste(String terminalId, String zeitraum,
                                       List<String> gewaehlt) {
        StringBuilder z = new StringBuilder();
        for (var zr : ZEITRAEUME) {
            z.append("<option value=\"").append(zr.kennung()).append("\"")
             .append(zr.kennung().equals(zeitraum) ? " selected" : "").append(">")
             .append(esc(zr.anzeige())).append("</option>");
        }
        // Ereignisarten als Ankreuzfelder statt Auswahlliste: Sichtbar ist, was
        // an und was aus ist, ohne die Liste zu oeffnen.
        StringBuilder a = new StringBuilder();
        for (var art : de.pinroboter.gemeinsam.Ereignisart.values()) {
            boolean an = gewaehlt.isEmpty() || gewaehlt.contains(art.name());
            a.append("<label class=\"ankreuz\" style=\"display:inline-flex;margin-right:.9rem\">")
             .append("<input type=\"checkbox\" name=\"art\" value=\"").append(art.name()).append("\"")
             .append(an ? " checked" : "").append("> <span style=\"font-size:.84rem\">")
             .append(esc(art.anzeige())).append("</span></label>");
        }
        return """
                <form method="get" action="/geraete/%s/verlauf" class="filterleiste">
                  <div class="feldgruppe" style="flex:0 1 14rem">
                    <label for="v-zeit">Zeitraum</label>
                    <select id="v-zeit" name="zeitraum">%s</select>
                  </div>
                  <button class="knopf" type="submit" title="Wendet Zeitraum und Ereignisarten auf den Zeitstrahl an" aria-label="Filter anwenden">Anwenden</button>
                  <a class="knopf" href="/geraete/%s/verlauf" title="Verwirft die Filter und zeigt wieder den vollständigen Zeitstrahl" aria-label="Filter zurücksetzen">Zurücksetzen</a>
                  <details class="ausklapp" style="flex:1 1 100%%;border:0">
                    <summary class="zeile-schmal">Ereignisarten ein- und ausblenden</summary>
                    <div class="zeile-schmal">%s</div>
                  </details>
                </form>""".formatted(esc(terminalId), z, esc(terminalId), a);
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
         .append("><h3>Verlauf</h3><div class=\"baum\">");
        for (var e : seite.eintraege()) s.append(eintrag(e));
        return s.append("</div></div>").toString();
    }
}
