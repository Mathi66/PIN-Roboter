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
     *
     * <p>Der Startwert ist {@link Fachprotokoll#INSTANZ}, nicht „Praxis". Er
     * wird bei der ersten Meldung überschrieben; bliebe er stehen, fiele der
     * Zustand sonst einer echten Einrichtung zu, die es womöglich gibt
     * (Befund 12.08.2026).
     */
    public String einrichtung = Fachprotokoll.INSTANZ;
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
    /**
     * Ob in diesem Terminal eine SMC-B steckt.
     *
     * <p>Der Unterschied ist größer, als er aussieht: Ein Gerät <b>ohne</b>
     * SMC-B hat nichts freizuschalten. Es wird überwacht, aber es kann bei ihm
     * nichts zu tun geben. Ohne diese Unterscheidung stand jedes
     * Behandlungsterminal mit „Der Zustand konnte nicht ermittelt werden" in
     * der Liste „Was zu tun ist" — bei einem MVZ mit 36 Geräten waren das 27
     * Zeilen, hinter denen keine einzige Handlung stand. Eine Liste, in der
     * fast alles steht, sagt nichts mehr (Vorgabe PO, 11.08.2026).
     */
    public boolean hatSmcb() {
        return kartenhandle != null && !kartenhandle.isBlank();
    }

    public Ampel ampel() {
        if (!erreichbar) return Ampel.FEHLER;
        if (automatikAngehalten) return Ampel.FEHLER;
        if (aktualisierend) return Ampel.WARNUNG;
        // Ohne SMC-B gibt es keinen PIN-Zustand, den man bewerten könnte.
        // „Unbekannt" ist hier keine Unsicherheit, sondern schlicht die
        // richtige Antwort: Es gibt nichts zu wissen.
        if (!hatSmcb()) return Ampel.UNBEKANNT;
        return pinStatus.ampel();
    }

    public String zustandstext() {
        if (!erreichbar) return "nicht erreichbar";
        if (aktualisierend) return "wird aktualisiert";
        if (automatikAngehalten) return "Automatik angehalten";
        if (!hatSmcb()) return "keine SMC-B";
        return pinStatus.anzeige();
    }

    /**
     * Erklärung des Zustands im Klartext — für die Spalte „Was zu tun ist".
     *
     * <p>Bei einem Gerät ohne SMC-B ist die Antwort keine Fehlermeldung,
     * sondern eine Feststellung. „Der Zustand konnte nicht ermittelt werden"
     * klingt nach einer Störung, wo keine ist.
     */
    public String zustandserklaerung() {
        if (hinweis != null && !hinweis.isBlank()) return hinweis;
        if (!hatSmcb()) {
            return "In diesem Gerät steckt keine SMC-B. Es wird überwacht, "
                 + "aber es gibt nichts freizuschalten.";
        }
        return pinStatus.erklaerung();
    }

    public String zuletztGeprueftText() {
        if (zuletztGeprueft == null) return "—";
        long s = java.time.Duration.between(zuletztGeprueft, Instant.now()).toSeconds();
        if (s < 60) return "vor " + s + " s";
        if (s < 3600) return "vor " + (s / 60) + " min";
        return "vor " + (s / 3600) + " h";
    }
}
