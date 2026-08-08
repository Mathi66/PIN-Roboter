package de.pinroboter.zentrale;

import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Versionsvergleich und Auswertung der Registry-Antwort (FA-UPD-010).
 *
 * <p>Der Vergleich läuft <b>über Zahlen, nicht über Zeichenketten</b>. Als Text
 * verglichen wäre `1.10.0` kleiner als `1.9.0` — der Betreiber bekäme kein
 * Update angeboten, obwohl eines vorliegt. Das fällt erst bei der zehnten
 * Nebenversion auf, also spät.
 */
class VersionsvergleichTest {

    @Test
    @DisplayName("1.10.0 ist neuer als 1.9.0 — als Text verglichen wäre es kleiner")
    void zahlenNichtZeichen() {
        assertTrue(Versionspruefung.vergleiche("1.10.0", "1.9.0") > 0);
        assertTrue(Versionspruefung.neuer("1.10.0", "1.9.0"));
        // Zur Gegenprobe: die Zeichenkettenordnung sagt das Gegenteil.
        assertTrue("1.10.0".compareTo("1.9.0") < 0);
    }

    @Test
    @DisplayName("Gleiche Versionen sind nicht neuer")
    void gleich() {
        assertEquals(0, Versionspruefung.vergleiche("1.0.1", "1.0.1"));
        assertFalse(Versionspruefung.neuer("1.0.1", "1.0.1"));
    }

    @Test
    @DisplayName("Unterschiedlich lange Versionen werden richtig verglichen")
    void unterschiedlicheLaenge() {
        assertTrue(Versionspruefung.vergleiche("1.1", "1.0.5") > 0);
        assertEquals(0, Versionspruefung.vergleiche("1.0", "1.0.0"));
    }

    @Test
    @DisplayName("Im Entwicklungslauf ohne Versionsangabe gilt alles als neuer")
    void unbekannteLaufendeVersion() {
        // Die harmlose Richtung: lieber ein Update anbieten, das keines ist,
        // als eines verschweigen, das gebraucht wird.
        assertTrue(Versionspruefung.neuer("0.1.0", "unbekannt"));
    }

    @Test
    @DisplayName("„latest\" wird nicht als Version angeboten")
    void latestWirdVerworfen() {
        var tags = Versionspruefung.tagsLesen(
                "{\"name\":\"x/y\",\"tags\":[\"1.0.0\",\"latest\",\"1.1.0\"]}");
        // "latest" ist ein Zeiger, keine Version — ihn anzuzeigen hieße, ein
        // Update auf "dasselbe nochmal" vorzuschlagen.
        assertEquals(java.util.List.of("1.0.0", "1.1.0"), tags);
    }

    @Test
    @DisplayName("Eine leere oder unerwartete Antwort ergibt keine Versionen, keinen Absturz")
    void robustGegenUnsinn() {
        assertTrue(Versionspruefung.tagsLesen("").isEmpty());
        assertTrue(Versionspruefung.tagsLesen("{}").isEmpty());
        assertTrue(Versionspruefung.tagsLesen("kein json").isEmpty());
        assertTrue(Versionspruefung.tagsLesen("{\"tags\":[]}").isEmpty());
    }

    @Test
    @DisplayName("Vorabfassungen werden nach ihrer Zahlenfolge eingeordnet")
    void vorabfassung() {
        assertTrue(Versionspruefung.vergleiche("1.0.0", "0.9.0") > 0);
        assertFalse(Versionspruefung.neuer("0.9.0", "1.0.0"));
    }
}
