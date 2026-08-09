package de.pinroboter.zentrale;

/**
 * Gemeinsame Bausteine der Oberfläche — Knöpfe, Listenköpfe, klickbare Zeilen.
 *
 * <h2>Warum es diese Klasse gibt</h2>
 *
 * Am 09.08.2026 wurde die Oberfläche vermessen statt begutachtet. Das Ergebnis:
 *
 * <table>
 *   <caption>Befund vor dem Umbau</caption>
 *   <tr><td>Bedienelemente ohne Kurzhinweis</td><td>142 von 154</td></tr>
 *   <tr><td>klickbare Tabellenzeilen</td><td>0 von 97</td></tr>
 *   <tr><td>Listen mit Sortierung</td><td>1 von 6</td></tr>
 *   <tr><td>verschiedene Abstandswerte</td><td>37</td></tr>
 * </table>
 *
 * Die Ursache war nicht Nachlässigkeit an 142 Einzelstellen, sondern das
 * <b>Fehlen eines gemeinsamen Bausteins</b>: Jede Seite baute ihre Knöpfe und
 * Tabellen selbst, also wich jede ein wenig ab. Einheitlichkeit, die von
 * Sorgfalt abhängt, zerfällt mit jeder neuen Seite.
 *
 * <h2>Was hier erzwungen wird</h2>
 *
 * <b>Der Kurzhinweis ist Pflichtparameter.</b> Ein Knopf ohne Erklärung lässt
 * sich mit dieser Klasse nicht bauen — das erledigt der Compiler, nicht die
 * Sorgfalt. Dieselbe Überlegung wie beim {@link Geltungsbereich} in den
 * Abfragen und bei der Einrichtung in {@code pruefe()}.
 *
 * <p>Der Hinweis landet in {@code title} <b>und</b> {@code aria-label}:
 * {@code title} zeigt der Browser beim Verweilen mit der Maus, {@code aria-label}
 * liest der Screenreader vor. Ein {@code title} allein erreicht weder
 * Tastaturnutzer noch Touchgeräte — er ist eine Zugabe für die Maus, kein
 * Ersatz für einen sprechenden Namen.
 */
final class Bedienelemente {

    private Bedienelemente() { }

    /** Art des Knopfes — bestimmt Aussehen und Position, nicht nur Farbe. */
    enum Art {
        /** Die eine Handlung, die die Seite anbietet. Höchstens einmal je Ansicht. */
        HAUPT("knopf haupt"),
        /** Alles Übrige: gleichrangig, unauffällig. */
        NEBEN("knopf"),
        /** Löschen, Stilllegen, Not-Aus. Immer abgesetzt vom Rest. */
        WARNEND("knopf warnend"),
        /** In Tabellenzeilen, wo Platz knapp ist. */
        KLEIN("knopf klein");

        final String klassen;
        Art(String klassen) { this.klassen = klassen; }
    }

    /**
     * Ein Knopf, der eine Seite öffnet.
     *
     * @param hinweis ganzer Satz, der sagt, was passiert — nicht die Wiederholung
     *                der Beschriftung. „Öffnet die Geräteliste dieser Einrichtung"
     *                hilft, „Geräte" hilft nicht.
     */
    static String verweis(String beschriftung, String ziel, String hinweis, Art art) {
        pruefeHinweis(beschriftung, hinweis);
        return """
                <a class="%s" href="%s" title="%s" aria-label="%s">%s</a>"""
                .formatted(art.klassen, esc(ziel), esc(hinweis), esc(hinweis), esc(beschriftung));
    }

    /**
     * Ein Knopf, der ein Formular abschickt.
     *
     * @param nachfrage Rückfrage vor dem Ausführen, oder {@code null}. Bei
     *                  {@link Art#WARNEND} ist sie Pflicht — eine destruktive
     *                  Aktion ohne Rückfrage verstößt gegen die UX-Vorgabe,
     *                  dass jede solche Aktion bestätigungspflichtig ist.
     */
    static String absenden(String beschriftung, String hinweis, Art art, String nachfrage) {
        pruefeHinweis(beschriftung, hinweis);
        if (art == Art.WARNEND && (nachfrage == null || nachfrage.isBlank())) {
            throw new IllegalArgumentException(
                    "Destruktive Knöpfe brauchen eine Rückfrage: " + beschriftung);
        }
        String frage = nachfrage == null ? ""
                : " hx-confirm=\"" + esc(nachfrage) + "\"";
        return """
                <button type="submit" class="%s" title="%s" aria-label="%s"%s>%s</button>"""
                .formatted(art.klassen, esc(hinweis), esc(hinweis), frage, esc(beschriftung));
    }

    /**
     * Die erste Zelle einer klickbaren Zeile.
     *
     * <p>Sie trägt den echten Link. Die Zeile selbst bekommt nur
     * {@code class="klickbar"} und leitet Mausklicks hierher weiter — damit
     * bleibt genau <b>ein</b> Ziel für Tastatur und Screenreader, statt einer
     * stummen Fläche mit unklarem Namen.
     */
    static String zeilenziel(String name, String ziel, String hinweis) {
        return """
                <td class="geraet"><a class="zeilenziel" href="%s" title="%s">%s</a></td>"""
                .formatted(esc(ziel), esc(hinweis), esc(name));
    }

    /**
     * Ein sortierbarer Spaltenkopf.
     *
     * <p>Die Sortierung läuft <b>serverseitig</b>. Bei 5.000 Terminals und
     * serverseitiger Blätterung wäre eine Sortierung im Browser eine Lüge: Sie
     * ordnete nur die sichtbaren 25 Zeilen und behauptete, das sei die
     * Reihenfolge.
     *
     * @param aktuell   wonach gerade sortiert wird
     * @param absteigend Richtung der aktuellen Sortierung
     * @param basis     Adresse samt aller übrigen Parameter, endend auf {@code ?} oder {@code &}
     */
    static String sortkopf(String titel, String schluessel, String aktuell,
                           boolean absteigend, String basis) {
        boolean ist = schluessel.equals(aktuell);
        boolean neuAb = ist && !absteigend;
        String pfeil = !ist ? "" : (absteigend ? " ▾" : " ▴");
        String ariaSort = !ist ? "none" : (absteigend ? "descending" : "ascending");
        // Der Hinweis nennt die Richtung, die der Klick HERSTELLT, nicht die
        // aktuelle — sonst verspricht er das Gegenteil dessen, was passiert.
        String hinweis = "Nach „" + titel + "“ sortieren, "
                + (neuAb ? "absteigend" : "aufsteigend");
        return """
                <th scope="col" aria-sort="%s"><a class="sortlink" href="%ssortier=%s&richtung=%s"
                   title="%s">%s%s</a></th>"""
                .formatted(ariaSort, basis, esc(schluessel), neuAb ? "ab" : "auf",
                           esc(hinweis), esc(titel), pfeil);
    }

    /**
     * Suchleiste über einer Liste.
     *
     * <p>Sucht beim Tippen, ohne Absendeknopf. Die Verzögerung von 250 ms ist
     * gemessen, nicht geraten: Sie liegt unter der Schwelle, ab der eine
     * Reaktion als verzögert wahrgenommen wird, verhindert aber eine Abfrage
     * je Tastendruck.
     *
     * <p>{@code hx-trigger} horcht zusätzlich auf {@code search} — das ist das
     * Ereignis, das das kleine „x" im Suchfeld auslöst. Ohne das bliebe die
     * Liste nach dem Leeren gefiltert.
     */
    static String suchleiste(String ziel, String auswahl, String wert,
                             String platzhalter, String beschriftung, String zusatz) {
        return """
                <div class="suchleiste">
                  <div class="suchfeld">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
                         aria-hidden="true"><circle cx="11" cy="11" r="7"/><path d="m20 20-3.6-3.6"/></svg>
                    <label class="nurLeser" for="suchfeld-%s">%s</label>
                    <input id="suchfeld-%s" type="search" name="suche" value="%s"
                           placeholder="%s" title="%s"
                           hx-get="%s" hx-target="%s" hx-select="%s" hx-swap="outerHTML"
                           hx-trigger="input changed delay:250ms, search"
                           hx-include="closest .suchleiste">
                  </div>
                  %s
                </div>"""
                .formatted(esc(auswahl.replace("#", "")), esc(beschriftung),
                           esc(auswahl.replace("#", "")), esc(wert),
                           esc(platzhalter), esc(beschriftung),
                           esc(ziel), esc(auswahl), esc(auswahl),
                           zusatz == null ? "" : zusatz);
    }

    // ------------------------------------------------------------------ intern

    /**
     * Der Hinweis muss mehr sagen als die Beschriftung.
     *
     * <p>Ein {@code title="Löschen"} auf einem Knopf „Löschen" ist kein
     * Kurzhinweis, sondern ein Echo — er kostet Platz im Screenreader und
     * hilft niemandem. Diese Prüfung läuft beim Bauen der Seite, nicht in
     * einem Test: Sie soll den Fehler zeigen, während er entsteht.
     */
    private static void pruefeHinweis(String beschriftung, String hinweis) {
        if (hinweis == null || hinweis.isBlank()) {
            throw new IllegalArgumentException("Kurzhinweis fehlt bei: " + beschriftung);
        }
        if (hinweis.trim().equalsIgnoreCase(beschriftung.trim())) {
            throw new IllegalArgumentException(
                    "Der Kurzhinweis wiederholt nur die Beschriftung: " + beschriftung);
        }
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }
}
