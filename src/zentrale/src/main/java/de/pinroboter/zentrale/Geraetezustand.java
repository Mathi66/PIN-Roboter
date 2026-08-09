package de.pinroboter.zentrale;

import de.pinroboter.gemeinsam.Ampel;
import de.pinroboter.gemeinsam.PinStatus;

import java.time.Instant;

/** Zuletzt gemeldeter Zustand eines Kartenterminals samt SMC-B. */
public class Geraetezustand {
    public String terminalId, name, modell, firmware, kartenhandle;

    /**
     * Die Einrichtung, zu der dieses Gerät gehört.
     *
     * <p>Sie stammt aus der <b>Registrierung des Agenten</b>, nicht aus seiner
     * Meldung. Bis zum 09.08.2026 stand hier überall fest {@code "Praxis"} —
     * damit trugen Alarme, Protokolleinträge und Gerätegruppen aller Standorte
     * denselben Namen, und die Mandantengrenze griff für sie nicht.
     */
    public String einrichtung = "Praxis";
    public PinStatus pinStatus = PinStatus.UNBEKANNT;
    public int verbleibendeVersuche = -1;
    public boolean erreichbar, pevimUnterstuetzt, pevimAktiv, aktualisierend, automatikAngehalten;
    public String hinweis, handlung;
    public Instant zuletztGeprueft;

    /**
     * Gesamtbewertung für die Übersicht. Ein Gerät ohne PEviM ist kein Fehler —
     * es wird überwacht, nur nicht automatisiert (A_24074). Das muss die
     * Oberfläche unterscheiden, sonst wirkt eine gesunde Anlage alarmiert.
     */
    public Ampel ampel() {
        if (!erreichbar) return Ampel.FEHLER;
        if (automatikAngehalten) return Ampel.FEHLER;
        if (aktualisierend) return Ampel.WARNUNG;
        return pinStatus.ampel();
    }

    public String zustandstext() {
        if (!erreichbar) return "nicht erreichbar";
        if (aktualisierend) return "wird aktualisiert";
        if (automatikAngehalten) return "Automatik angehalten";
        return pinStatus.anzeige();
    }

    public String zuletztGeprueftText() {
        if (zuletztGeprueft == null) return "—";
        long s = java.time.Duration.between(zuletztGeprueft, Instant.now()).toSeconds();
        if (s < 60) return "vor " + s + " s";
        if (s < 3600) return "vor " + (s / 60) + " min";
        return "vor " + (s / 3600) + " h";
    }
}
