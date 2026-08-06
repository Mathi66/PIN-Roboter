package de.pinroboter.gemeinsam;

/**
 * PIN-Zustand einer Karte, wie ihn der Konnektor über {@code GetPinStatus}
 * liefert. Werte und Bedeutung nach [gemSpec_Kon] TAB_KON_051 (TIP1-A_4589).
 *
 * <p>Alle sechs Werte sind verpflichtend zu unterscheiden (FA-UEB-010).
 */
public enum PinStatus {
    /** Bereits verifiziert — der Sollzustand. Nichts zu tun. */
    VERIFIED("verifiziert", "Die Karte ist freigeschaltet und einsatzbereit."),
    /** Echt-PIN, noch nicht verifiziert. Auslöser für die Automatik. */
    VERIFIABLE("nicht verifiziert", "Die Karte muss freigeschaltet werden."),
    /** Transport-PIN noch nicht geändert. Nur manuell lösbar. */
    TRANSPORT_PIN("Transport-PIN", "Die Erst-PIN wurde noch nicht geändert. Das muss einmalig am Kartenterminal geschehen."),
    /** Leer-PIN. */
    EMPTY_PIN("Leer-PIN", "Für diese Karte ist keine PIN gesetzt. Bitte am Kartenterminal einrichten."),
    /** Gesperrt — nur per PUK lösbar. Alarm. */
    BLOCKED("gesperrt", "Die Karte ist gesperrt und lässt sich nur mit der PUK wieder entsperren."),
    /** PIN-Schutz ausgeschaltet, Verifikation nicht erforderlich. */
    DISABLED("PIN-Schutz aus", "Für diese Karte ist keine PIN-Prüfung erforderlich."),
    /** Kein Wert vom Konnektor erhalten — z. B. weil er nicht erreichbar ist. */
    UNBEKANNT("unbekannt", "Der Zustand konnte nicht ermittelt werden.");

    private final String anzeige;
    private final String erklaerung;

    PinStatus(String anzeige, String erklaerung) {
        this.anzeige = anzeige;
        this.erklaerung = erklaerung;
    }

    /** Kurzform für die Oberfläche, deutsch (NFA-USA-020). */
    public String anzeige() { return anzeige; }

    /** Satz, der dem Betreiber sagt, was Sache ist — nicht nur der Code. */
    public String erklaerung() { return erklaerung; }

    /** Nur in diesem Zustand darf die Automatik eine Verifikation auslösen. */
    public boolean automatikMoeglich() { return this == VERIFIABLE; }

    /** Zustände, die menschliches Eingreifen erfordern (FA-PIN-040). */
    public boolean handlungsbedarf() {
        return this == TRANSPORT_PIN || this == EMPTY_PIN || this == BLOCKED;
    }

    public Ampel ampel() {
        return switch (this) {
            case VERIFIED, DISABLED -> Ampel.OK;
            case VERIFIABLE -> Ampel.WARNUNG;
            case BLOCKED, TRANSPORT_PIN, EMPTY_PIN -> Ampel.FEHLER;
            case UNBEKANNT -> Ampel.UNBEKANNT;
        };
    }
}
