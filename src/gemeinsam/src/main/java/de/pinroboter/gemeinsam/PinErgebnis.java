package de.pinroboter.gemeinsam;

/**
 * Ergebnis einer PIN-Verifikation, nach [gemSpec_Kon] TAB_KON_047 (TIP1-A_4587).
 */
public enum PinErgebnis {
    OK("erfolgreich"),
    REJECTED("PIN abgelehnt"),
    ERROR("Fehler"),
    WASBLOCKED("war bereits gesperrt"),
    NOWBLOCKED("durch diesen Versuch gesperrt"),
    TRANSPORT_PIN("Transport-PIN gesetzt");

    private final String anzeige;
    PinErgebnis(String anzeige) { this.anzeige = anzeige; }
    public String anzeige() { return anzeige; }
}
