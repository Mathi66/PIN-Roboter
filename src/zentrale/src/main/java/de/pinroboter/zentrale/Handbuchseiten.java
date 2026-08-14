package de.pinroboter.zentrale;

import java.util.List;

/**
 * Die Darstellung des Handbuchs.
 *
 * <h2>Der Aufbau der Seite</h2>
 *
 * Links das Verzeichnis, rechts das Kapitel. Die Zweiteilung ist die
 * naheliegende — und die einzige, die beim Nachschlagen funktioniert: Man muss
 * jederzeit sehen können, wo man ist und was es sonst noch gibt.
 *
 * <p>Bei schmalem Fenster wandert das Verzeichnis nach oben und wird zu einer
 * Auswahl. Ein Tablet ist der Zweitfall, aber ein wirklicher.
 *
 * <h2>Die Suche</h2>
 *
 * Sie reagiert beim Tippen (300 ms Verzögerung) und ersetzt dabei nur die
 * Trefferliste, nicht die Seite. Ohne JavaScript funktioniert dasselbe Feld als
 * gewöhnliches Formular — die Suche ist der wichtigste Zugang zu einem
 * Handbuch, und sie darf nicht an einer Einstellung im Browser scheitern.
 */
final class Handbuchseiten {

    private Handbuchseiten() { }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;")
                                 .replace(">", "&gt;").replace("\"", "&quot;");
    }

    /** Die Handbuchseite mit einem geöffneten Kapitel. */
    static String seite(Handbuch.Kapitel aktiv, String suchbegriff) {
        return seite(aktiv, suchbegriff, null);
    }

    /**
     * Die Handbuchseite.
     *
     * <p>Immer vollständig — auch beim Tippen in der Suche. HTMX holt sich
     * daraus nur den Inhaltsbereich ({@code hx-select}); ohne JavaScript
     * liefert dieselbe Adresse die ganze Seite. Damit gibt es <b>einen</b>
     * Weg statt zweier, die auseinanderlaufen können.
     *
     * @param stattKapitel ersetzt den Inhaltsbereich, etwa durch Suchtreffer
     */
    static String seite(Handbuch.Kapitel aktiv, String suchbegriff, String stattKapitel) {
        StringBuilder s = new StringBuilder();

        s.append("""
                <div class="hb">
                  <aside class="hb-verzeichnis">
                    <form class="hb-suche" method="get" action="/handbuch/suche"
                          role="search" aria-label="Handbuch durchsuchen">
                      <label class="nurLeser" for="hb-q">Im Handbuch suchen</label>
                      <input id="hb-q" name="q" type="search" value="%s"
                             placeholder="Suchen, z. B. PIN oder Alarm"
                             autocomplete="off"
                             hx-get="/handbuch/suche" hx-target="#hb-inhalt"
                             hx-select="#hb-inhalt" hx-swap="outerHTML"
                             hx-trigger="input changed delay:300ms, search"
                             hx-push-url="true">
                      <button class="knopf klein" type="submit">Suchen</button>
                    </form>
                """.formatted(esc(suchbegriff)));

        for (Handbuch.Teil teil : Handbuch.Teil.values()) {
            s.append("<div class=\"hb-teil\"><div class=\"hb-teil-titel\">")
             .append(esc(teil.titel()))
             .append("<small>").append(esc(teil.unter())).append("</small></div><ul>");
            for (var k : Handbuch.desTeils(teil)) {
                boolean ist = aktiv != null && aktiv.kennung().equals(k.kennung());
                s.append("<li><a href=\"/handbuch/").append(k.kennung()).append('"')
                 .append(ist ? " class=\"hb-aktiv\" aria-current=\"page\"" : "")
                 .append("><span class=\"hb-symbol\" aria-hidden=\"true\">")
                 .append(k.symbol()).append("</span><span><b>")
                 .append(esc(k.titel())).append("</b><small>")
                 .append(esc(k.kurz())).append("</small></span></a></li>");
            }
            s.append("</ul></div>");
        }

        s.append("</aside><div class=\"hb-inhalt\" id=\"hb-inhalt\">");
        s.append(stattKapitel != null ? stattKapitel
                                      : aktiv == null ? "" : kapitel(aktiv));
        s.append("</div></div>");
        return s.toString();
    }

    /** Ein Kapitel — für die ganze Seite und für den Nachlade-Ausschnitt. */
    static String kapitel(Handbuch.Kapitel k) {
        StringBuilder s = new StringBuilder();
        s.append("""
                <article class="hb-kapitel">
                  <div class="hb-kopf">
                    <div class="hb-kopf-symbol" aria-hidden="true">%s</div>
                    <div>
                      <div class="hb-kopf-teil">%s</div>
                      <h1>%s</h1>
                      <p class="hb-kopf-kurz">%s</p>
                    </div>
                  </div>
                """.formatted(k.symbol(), esc(k.teil().titel()), esc(k.titel()),
                              esc(k.kurz())));

        s.append(k.inhalt());

        var vor = Handbuch.nachbar(k.kennung(), -1);
        var nach = Handbuch.nachbar(k.kennung(), 1);
        s.append("<nav class=\"hb-weiter\" aria-label=\"Weitere Kapitel\">");
        s.append(vor == null ? "<span></span>" : """
                <a href="/handbuch/%s" class="hb-weiter-links">
                  <small>Vorher</small><b>%s</b></a>"""
                .formatted(vor.kennung(), esc(vor.titel())));
        s.append(nach == null ? "<span></span>" : """
                <a href="/handbuch/%s" class="hb-weiter-rechts">
                  <small>Als Nächstes</small><b>%s</b></a>"""
                .formatted(nach.kennung(), esc(nach.titel())));
        s.append("</nav></article>");
        return s.toString();
    }

    /** Die Trefferliste. */
    static String treffer(String begriff, List<Handbuch.Treffer> liste) {
        if (begriff == null || begriff.isBlank()) {
            return kapitel(Handbuch.erstes());
        }
        StringBuilder s = new StringBuilder("<article class=\"hb-kapitel\">");
        s.append("<div class=\"hb-kopf\"><div class=\"hb-kopf-symbol\" aria-hidden=\"true\">🔍</div>")
         .append("<div><div class=\"hb-kopf-teil\">Suche</div><h1>")
         .append(liste.isEmpty() ? "Nichts gefunden" : liste.size() + " Kapitel")
         .append("</h1><p class=\"hb-kopf-kurz\">zu „").append(esc(begriff))
         .append("“</p></div></div>");

        if (liste.isEmpty()) {
            s.append("""
                    <p class="leer">Zu diesem Begriff steht nichts im Handbuch.
                       Versuchen Sie ein einzelnes Wort — etwa <em>PIN</em>,
                       <em>Agent</em>, <em>Alarm</em> oder <em>Sicherung</em>.
                       Unter <a href="/handbuch/begriffe">Begriffe</a> steht,
                       was ein Wort in dieser Oberfläche bedeutet.</p>""");
        } else {
            s.append("<div class=\"hb-treffer\">");
            for (var t : liste) {
                s.append("""
                        <a href="/handbuch/%s" class="hb-treffer-eintrag">
                          <span class="hb-symbol" aria-hidden="true">%s</span>
                          <span>
                            <b>%s</b>
                            <small class="hb-treffer-teil">%s</small>
                            <span class="hb-treffer-auszug">%s</span>
                          </span>
                        </a>"""
                        .formatted(t.kapitel().kennung(), t.kapitel().symbol(),
                                esc(t.kapitel().titel()), esc(t.kapitel().teil().titel()),
                                hervorheben(t.auszug(), begriff)));
            }
            s.append("</div>");
        }
        return s.append("</article>").toString();
    }

    /**
     * Ein Handbuchabschnitt, wie er im Hilfefenster erscheint (FA-DOK-020).
     *
     * <p>Drei Teile: woher der Text stammt, der Text selbst, und ein Weg ins
     * volle Kapitel. Der letzte ist wichtig — eine Kurzhilfe beantwortet die
     * Frage von jetzt, aber manchmal ist die eigentliche Frage eine größere.
     */
    static String hilfe(Handbuch.Hilfe h) {
        return """
                <div class="hilfe-kopf">
                  <div>
                    <div class="hilfe-herkunft">%s %s</div>
                    <h2 id="hilfefenster-titel">%s</h2>
                  </div>
                  <button class="hilfe-zu" type="button" onclick="pinHilfeZu()"
                          aria-label="Hilfe schließen"><span aria-hidden="true">×</span></button>
                </div>
                <div class="hilfe-leib">%s</div>
                <div class="hilfe-fuss">
                  <a href="/handbuch/%s" class="knopf klein">Im Handbuch weiterlesen</a>
                </div>"""
                .formatted(h.kapitel().symbol(), esc(h.kapitel().titel()),
                        esc(h.titel() == null ? h.kapitel().titel() : h.titel()),
                        h.inhalt(), esc(h.kapitel().kennung()));
    }

    /**
     * Wenn es zu einer Kennung nichts gibt.
     *
     * <p>Ein Info-Knopf, der ins Leere führt, ist schlimmer als keiner: Wer
     * ihn drückt, hat eine Frage. Deshalb wenigstens der Weg ins Handbuch.
     */
    static String hilfeFehlt(String kennung) {
        return """
                <div class="hilfe-kopf">
                  <div><h2 id="hilfefenster-titel">Dazu steht noch nichts im Handbuch</h2></div>
                  <button class="hilfe-zu" type="button" onclick="pinHilfeZu()"
                          aria-label="Hilfe schließen"><span aria-hidden="true">×</span></button>
                </div>
                <div class="hilfe-leib">
                  <p>Für „%s“ ist noch kein Abschnitt hinterlegt. Bitte melden Sie das —
                     eine fehlende Erklärung ist ein Mangel, kein Zufall.</p>
                </div>
                <div class="hilfe-fuss">
                  <a href="/handbuch" class="knopf klein">Handbuch öffnen</a>
                </div>"""
                .formatted(esc(kennung));
    }

    /**
     * Hebt den gesuchten Begriff im Auszug hervor.
     *
     * <p>Erst escapen, dann markieren — andersherum würde die Markierung
     * selbst escaped und stünde sichtbar im Text. Gesucht wird ohne Rücksicht
     * auf Groß- und Kleinschreibung, aber die <b>Schreibweise des Fundes</b>
     * bleibt erhalten: „PIN“ soll nicht als „pin“ erscheinen.
     */
    private static String hervorheben(String text, String begriff) {
        String sicher = esc(text);
        String b = esc(begriff.trim());
        if (b.isEmpty()) return sicher;

        StringBuilder s = new StringBuilder();
        String klein = sicher.toLowerCase(), bk = b.toLowerCase();
        int i = 0;
        while (true) {
            int j = klein.indexOf(bk, i);
            if (j < 0) { s.append(sicher.substring(i)); break; }
            s.append(sicher, i, j).append("<mark>")
             .append(sicher, j, j + b.length()).append("</mark>");
            i = j + b.length();
        }
        return s.toString();
    }
}
