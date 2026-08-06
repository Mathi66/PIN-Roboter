package de.pinroboter.simulator;

import de.pinroboter.gemeinsam.PinStatus;

/**
 * Eine simulierte SMC-B mit echtem Fehlversuchszähler.
 *
 * <p>Der Zähler ist der Grund, warum dieser Simulator existiert: Der wichtigste
 * Test des gesamten Produkts (FA-PIN-030 — eine falsche PIN darf genau
 * <em>einen</em> Versuch auslösen) verbrennt an echter Hardware bei jedem
 * Durchlauf einen Versuch. Nach drei Läufen wäre die Karte gesperrt. Hier läuft
 * er in jedem Bauvorgang (ADR-0007).
 */
public class SimulierteKarte {

    public static final int MAX_VERSUCHE = 3;

    private final String kartenhandle;
    private final String iccsn;
    private final String kartentyp;
    private final String richtigePin;

    private int verbleibendeVersuche = MAX_VERSUCHE;
    private boolean verifiziert = false;
    private boolean gesperrt = false;
    private PinStatus sonderzustand = null;

    public SimulierteKarte(String kartenhandle, String iccsn, String kartentyp, String richtigePin) {
        this.kartenhandle = kartenhandle;
        this.iccsn = iccsn;
        this.kartentyp = kartentyp;
        this.richtigePin = richtigePin;
    }

    public synchronized PinStatus status() {
        if (sonderzustand != null) return sonderzustand;
        if (gesperrt) return PinStatus.BLOCKED;
        return verifiziert ? PinStatus.VERIFIED : PinStatus.VERIFIABLE;
    }

    public synchronized int verbleibendeVersuche() {
        return gesperrt ? 0 : verbleibendeVersuche;
    }

    /**
     * Prüft eine PIN und führt den Fehlversuchszähler nach.
     * Verhalten nach [gemSpec_Kon] TAB_KON_047.
     */
    public synchronized Pruefergebnis pruefe(String pin) {
        if (gesperrt) return new Pruefergebnis("WASBLOCKED", 0);
        if (sonderzustand == PinStatus.TRANSPORT_PIN) return new Pruefergebnis("TRANSPORT_PIN", verbleibendeVersuche);

        if (richtigePin.equals(pin)) {
            verifiziert = true;
            verbleibendeVersuche = MAX_VERSUCHE;   // Zähler wird bei Erfolg zurückgesetzt
            return new Pruefergebnis("OK", verbleibendeVersuche);
        }

        verbleibendeVersuche--;
        if (verbleibendeVersuche <= 0) {
            gesperrt = true;
            verbleibendeVersuche = 0;
            return new Pruefergebnis("NOWBLOCKED", 0);
        }
        return new Pruefergebnis("REJECTED", verbleibendeVersuche);
    }

    /** Entzieht den verifizierten Zustand — etwa nach Neustart des Terminals. */
    public synchronized void verifikationEntziehen() { verifiziert = false; }

    public synchronized void zuruecksetzen() {
        verbleibendeVersuche = MAX_VERSUCHE;
        verifiziert = false;
        gesperrt = false;
        sonderzustand = null;
    }

    public synchronized void sonderzustandSetzen(PinStatus s) { this.sonderzustand = s; }

    public String kartenhandle() { return kartenhandle; }
    public String iccsn() { return iccsn; }
    public String kartentyp() { return kartentyp; }

    public record Pruefergebnis(String ergebnis, int verbleibendeVersuche) {}
}
