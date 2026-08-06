package de.pinroboter.agent;

import de.pinroboter.gemeinsam.PinStatus;

/**
 * Die Schutzlogik vor jedem Verifikationsversuch.
 *
 * <p><b>Diese Klasse ist der wichtigste Code des Produkts.</b> Das erste
 * Qualitätsziel des Architekturkonzepts lautet: Das System darf eine SMC-B unter
 * keinen Umständen selbst sperren. Drei falsche Versuche genügen, und die Praxis
 * verliert den TI-Zugang, bis jemand mit der PUK kommt.
 *
 * <p>Sie sitzt bewusst im Agenten und nicht in der Zentrale: Sie muss auch dann
 * greifen, wenn die Verbindung zur Zentrale fehlt. Eine Schutzfunktion, die eine
 * Netzverbindung voraussetzt, ist keine.
 *
 * <p><b>Der Sonderweg:</b> Überall sonst im System wird ein Fehlschlag mit
 * wachsendem Abstand wiederholt. Hier nicht — nach genau einem abgelehnten
 * Versuch hält die Automatik an (FA-PIN-030). Wiederholen wäre der direkte Weg
 * zur gesperrten Karte.
 */
public final class Sicherungspruefung {

    /** Ergebnis der Prüfung: darf verifiziert werden, und wenn nicht, warum nicht. */
    public sealed interface Urteil {
        record Freigabe() implements Urteil {}
        record Abgelehnt(String grund, String handlung) implements Urteil {}
    }

    private Sicherungspruefung() {}

    /**
     * @param status              aktueller PIN-Status laut Konnektor
     * @param verbleibendeVersuche  {@code LeftTries} aus der Antwort
     * @param zuletztGesehen      zuvor beobachteter Wert, oder -1 wenn unbekannt
     * @param automatikAngehalten ob die Automatik für diese Karte angehalten wurde
     * @param notAus              ob der Not-Aus aktiv ist
     * @param pinHinterlegt       ob überhaupt eine PIN vorliegt
     * @param terminalAktualisiert ob am Terminal gerade ein Firmware-Update läuft
     */
    public static Urteil pruefe(PinStatus status, int verbleibendeVersuche, int zuletztGesehen,
                                boolean automatikAngehalten, boolean notAus,
                                boolean pinHinterlegt, boolean terminalAktualisiert) {

        if (notAus) {
            return new Urteil.Abgelehnt("Not-Aus ist aktiv",
                    "Der Not-Aus muss aufgehoben werden, bevor die Automatik wieder arbeitet.");
        }

        if (automatikAngehalten) {
            return new Urteil.Abgelehnt("Die Automatik wurde für diese Karte angehalten",
                    "Bitte die hinterlegte PIN prüfen und die Automatik ausdrücklich wieder freigeben.");
        }

        // FA-UEB-050: Während eines Firmware-Updates ist das Terminal im Zustand
        // „aktualisierend". Ein Verifikationsversuch würde im ungünstigen Fall
        // den Fehlversuchszähler belasten.
        if (terminalAktualisiert) {
            return new Urteil.Abgelehnt("Am Kartenterminal läuft gerade ein Firmware-Update",
                    "Es ist nichts zu tun — die Freischaltung erfolgt automatisch, sobald das Update abgeschlossen ist.");
        }

        if (status == PinStatus.BLOCKED) {
            return new Urteil.Abgelehnt("Die Karte ist gesperrt",
                    "Die Karte lässt sich nur mit der PUK entsperren. Ein weiterer PIN-Versuch hilft nicht.");
        }

        if (status.handlungsbedarf()) {
            return new Urteil.Abgelehnt(status.anzeige(), status.erklaerung());
        }

        if (!status.automatikMoeglich()) {
            return new Urteil.Abgelehnt("Zustand „" + status.anzeige() + "\" erfordert keine Freischaltung",
                    "Es ist nichts zu tun.");
        }

        if (!pinHinterlegt) {
            return new Urteil.Abgelehnt("Für diese Karte ist keine PIN hinterlegt",
                    "Bitte die PIN hinterlegen, damit die Automatik arbeiten kann.");
        }

        // Der Kern: Ist der Fehlversuchszähler gegenüber der letzten Beobachtung
        // gesunken, hat irgendetwas eine falsche PIN übergeben — wir selbst oder
        // jemand am Gerät. In beiden Fällen wird nicht weiterprobiert.
        if (zuletztGesehen >= 0 && verbleibendeVersuche < zuletztGesehen) {
            return new Urteil.Abgelehnt(
                    "Die Zahl der verbleibenden PIN-Versuche ist von %d auf %d gesunken"
                            .formatted(zuletztGesehen, verbleibendeVersuche),
                    "Die Automatik wurde angehalten, damit die Karte nicht gesperrt wird. "
                            + "Bitte die hinterlegte PIN prüfen.");
        }

        // Zusätzliche Untergrenze: Bei nur noch einem Versuch wird nicht
        // automatisch geraten, auch wenn der Zähler nicht gesunken ist.
        if (verbleibendeVersuche <= 1) {
            return new Urteil.Abgelehnt(
                    "Es ist nur noch %d Versuch übrig".formatted(verbleibendeVersuche),
                    "Die Automatik hält an, um eine Sperrung zu vermeiden. "
                            + "Bitte die PIN einmal von Hand am Kartenterminal prüfen.");
        }

        return new Urteil.Freigabe();
    }
}
