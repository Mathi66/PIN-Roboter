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
    /** Nicht final: SICCT MODIFY VERIFICATION DATA ändert sie (A_24082). */
    private String richtigePin;

    /**
     * Ablaufdatum des Zertifikats (FA-GER-040).
     *
     * <p>Es steht im Zertifikat der Karte, nicht auf der Karte selbst. Für den
     * Betrieb ist es die wichtigste Angabe nach dem PIN-Status: Eine abgelaufene
     * SMC-B nimmt keine Verifikation mehr an, und der Austausch braucht Vorlauf
     * bei der Kartenherausgabe.
     */
    private final String zertifikatGueltigBis;

    private int verbleibendeVersuche = MAX_VERSUCHE;
    private boolean verifiziert = false;
    private boolean gesperrt = false;
    private PinStatus sonderzustand = null;

    public SimulierteKarte(String kartenhandle, String iccsn, String kartentyp, String richtigePin) {
        this(kartenhandle, iccsn, kartentyp, richtigePin, "2029-12-31");
    }

    public SimulierteKarte(String kartenhandle, String iccsn, String kartentyp,
                           String richtigePin, String zertifikatGueltigBis) {
        this.kartenhandle = kartenhandle;
        this.iccsn = iccsn;
        this.kartentyp = kartentyp;
        this.richtigePin = richtigePin;
        this.zertifikatGueltigBis = zertifikatGueltigBis;
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
    public String zertifikatGueltigBis() { return zertifikatGueltigBis; }
    public String iccsn() { return iccsn; }
    public String kartentyp() { return kartentyp; }

    public record Pruefergebnis(String ergebnis, int verbleibendeVersuche) {}
    /**
     * Ändert die PIN — das Kartenkommando hinter
     * {@code SICCT MODIFY VERIFICATION DATA} ({@code A_24082}).
     *
     * <p><b>Die alte PIN muss stimmen.</b> Eine Karte, die eine neue PIN ohne
     * Nachweis der alten annähme, wäre kein Sicherheitsmerkmal mehr. Ein
     * Fehlversuch zählt wie bei der Verifikation — auch das Ändern kann die
     * Karte sperren.
     *
     * @return {@code true}, wenn geändert wurde
     */
    public synchronized boolean pinAendern(String alt, String neu) {
        if (gesperrt || sonderzustand == PinStatus.BLOCKED) return false;
        if (!richtigePin.equals(alt)) {
            verbleibendeVersuche--;
            if (verbleibendeVersuche <= 0) { gesperrt = true; verifiziert = false; }
            return false;
        }
        richtigePin = neu;
        verbleibendeVersuche = MAX_VERSUCHE;
        // Die Karte bleibt verifiziert: Der Nutzer hat sich soeben mit der
        // alten PIN ausgewiesen. Ein erzwungenes Abmelden waere in einer
        // Praxis nur laestig.
        return true;
    }

}
