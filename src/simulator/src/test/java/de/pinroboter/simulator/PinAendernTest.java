package de.pinroboter.simulator;

import de.pinroboter.gemeinsam.PinStatus;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PIN ändern auf der Karte (FA-PIN-060, {@code A_24082}).
 *
 * <p>Der wichtigste Punkt ist nicht, dass die Änderung klappt, sondern
 * <b>dass ein Fehlversuch zählt</b>: Auch das Ändern kann die Karte sperren.
 * Wer das übersieht, baut eine Funktion, die im Betrieb Karten unbrauchbar
 * macht — und drei Fehlversuche sind schnell erreicht, wenn jemand die alte
 * PIN nicht sicher weiß.
 */
class PinAendernTest {

    private SimulierteKarte karte() {
        return new SimulierteKarte("card-test", "80276000000000000001", "SMC-B", "123456");
    }

    @Test
    @DisplayName("Mit richtiger alter PIN wird geändert")
    void aendern() {
        var k = karte();
        assertTrue(k.pinAendern("123456", "654321"));
        assertEquals("OK", k.pruefe("654321").ergebnis(), "Die neue PIN muss gelten");
    }

    @Test
    @DisplayName("Die alte PIN gilt nach der Änderung nicht mehr")
    void alteGiltNichtMehr() {
        var k = karte();
        k.pinAendern("123456", "654321");
        assertNotEquals("OK", k.pruefe("123456").ergebnis());
    }

    @Test
    @DisplayName("Eine falsche alte PIN verbraucht einen Versuch")
    void fehlversuchZaehlt() {
        var k = karte();
        int vorher = k.verbleibendeVersuche();
        assertFalse(k.pinAendern("999999", "654321"));
        assertEquals(vorher - 1, k.verbleibendeVersuche(),
                "Auch das Ändern muss den Fehlversuchszähler belasten");
        // Und die PIN bleibt die alte — eine halb durchgeführte Änderung
        // wäre schlimmer als gar keine.
        assertEquals("OK", k.pruefe("123456").ergebnis());
    }

    @Test
    @DisplayName("Drei falsche Änderungsversuche sperren die Karte")
    void dreiFehlversucheSperren() {
        var k = karte();
        for (int i = 0; i < 3; i++) k.pinAendern("999999", "654321");
        assertEquals(PinStatus.BLOCKED, k.status());
        // Auch mit der richtigen alten PIN geht danach nichts mehr.
        assertFalse(k.pinAendern("123456", "654321"));
    }

    @Test
    @DisplayName("Eine erfolgreiche Änderung setzt den Fehlversuchszähler zurück")
    void zaehlerZurueck() {
        var k = karte();
        k.pinAendern("999999", "654321");
        assertEquals(SimulierteKarte.MAX_VERSUCHE - 1, k.verbleibendeVersuche());
        assertTrue(k.pinAendern("123456", "654321"));
        assertEquals(SimulierteKarte.MAX_VERSUCHE, k.verbleibendeVersuche());
    }

    @Test
    @DisplayName("Eine gesperrte Karte lässt sich nicht ändern")
    void gesperrtBleibtGesperrt() {
        var k = karte();
        k.sonderzustandSetzen(PinStatus.BLOCKED);
        assertFalse(k.pinAendern("123456", "654321"));
    }
}
