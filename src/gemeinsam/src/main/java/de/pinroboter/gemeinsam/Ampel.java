package de.pinroboter.gemeinsam;

/**
 * Vier Zustände für die Oberfläche. Bewusst wenige — eine Praxismitarbeiterin
 * soll auf einen Blick sehen, ob etwas zu tun ist.
 *
 * <p>Farbe ist nie das einzige Unterscheidungsmerkmal: Jede Stufe hat zusätzlich
 * ein Symbol und einen Text (WCAG 2.2, NFA-USA-010).
 */
public enum Ampel {
    OK("in Ordnung", "✓"),
    WARNUNG("Aufmerksamkeit", "!"),
    FEHLER("Handlungsbedarf", "×"),
    UNBEKANNT("unbekannt", "?");

    private final String text;
    private final String symbol;

    Ampel(String text, String symbol) { this.text = text; this.symbol = symbol; }
    public String text() { return text; }
    public String symbol() { return symbol; }
    public String css() { return name().toLowerCase(); }
}
