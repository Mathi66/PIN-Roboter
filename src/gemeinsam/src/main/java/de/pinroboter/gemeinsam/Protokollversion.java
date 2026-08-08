package de.pinroboter.gemeinsam;

/**
 * Version der Schnittstelle zwischen Agent und Zentrale (FA-AGT-040).
 *
 * <h2>Warum das nötig ist</h2>
 *
 * Agent und Zentrale werden getrennt aktualisiert. Bei 1.000 Standorten ist
 * das kein Sonderfall, sondern der Normalfall: Ein Agent in einer Praxis, die
 * über Nacht ausgeschaltet war, meldet sich Tage später mit einer älteren
 * Fassung. Watchtower rollt nicht überall gleichzeitig aus.
 *
 * <p>Ohne Versionsangabe zeigt sich der Unterschied als seltsames Verhalten:
 * ein Feld fehlt, ein Wert wird nicht übernommen, eine Funktion bleibt wirkungslos
 * — und niemand weiß warum. <b>Mit</b> Versionsangabe steht in der Oberfläche,
 * was los ist.
 *
 * <h2>Die Regel</h2>
 *
 * Die Zentrale unterstützt eine <b>Spanne</b> von Protokollversionen, nicht
 * genau eine. Ein Agent innerhalb der Spanne arbeitet normal; einer außerhalb
 * wird angezeigt und weiter überwacht — <b>er wird nicht abgewiesen</b>. Ein
 * abgewiesener Agent hieße: keine PIN-Verifikation in dieser Praxis. Ein
 * veralteter Agent, der seine Kernaufgabe noch erfüllt, ist besser als gar
 * keiner.
 */
public final class Protokollversion {

    /** Version, die dieser Baustein spricht. Bei jeder Änderung der Schnittstelle erhöhen. */
    public static final int AKTUELL = 2;

    /**
     * Älteste Version, die die Zentrale noch bedient.
     *
     * <p>1 ist die Fassung vor der gruppenweisen Taktung (FA-UEB-010): Ein
     * solcher Agent kennt {@code /api/agent/intervalle} nicht und prüft mit
     * seinem globalen Wert weiter. Das ist überwachungsfähig, nur nicht
     * steuerbar — also unterstützt, aber sichtbar veraltet.
     */
    public static final int AELTESTE_UNTERSTUETZTE = 1;

    private Protokollversion() { }

    public static boolean unterstuetzt(int version) {
        return version >= AELTESTE_UNTERSTUETZTE && version <= AKTUELL;
    }

    /** Klartext für die Oberfläche — sagt, was zu tun ist, nicht nur was ist. */
    public static String bewertung(int version) {
        if (version == AKTUELL) return null;
        if (version < AELTESTE_UNTERSTUETZTE) {
            return "Dieser Standort-Agent ist zu alt (Protokoll " + version
                 + ", unterstützt ab " + AELTESTE_UNTERSTUETZTE + "). Er wird weiter überwacht, "
                 + "aber neuere Funktionen wirken dort nicht. Bitte den Agenten aktualisieren.";
        }
        if (version > AKTUELL) {
            return "Dieser Standort-Agent ist neuer als die Zentrale (Protokoll " + version
                 + " gegenüber " + AKTUELL + "). Bitte zuerst die Zentrale aktualisieren — "
                 + "sonst bleiben Funktionen des Agenten ungenutzt.";
        }
        return "Dieser Standort-Agent verwendet Protokoll " + version + ", aktuell ist "
             + AKTUELL + ". Er arbeitet weiter; ein Update bringt die neueren Funktionen.";
    }
}
