package de.pinroboter.zentrale;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Bremse für wiederholte Anmeldeversuche eines Absenders.
 *
 * <p>Die Kontosperre schützt das einzelne Konto. Diese Bremse schützt gegen den
 * aussichtsreicheren Angriff: viele Konten mit je einem naheliegenden Kennwort.
 */
class AnmeldebremseTest {

    @Test
    @DisplayName("Die ersten Fehlversuche kosten nichts — Tippfehler sind normal")
    void ersteVersucheOhneVerzoegerung() {
        var b = new Anmeldebremse();
        for (int i = 1; i <= Anmeldebremse.FREIVERSUCHE; i++) {
            b.fehlversuch("10.0.0.1");
            assertTrue(b.wartezeit("10.0.0.1").isZero(),
                    "Versuch " + i + " darf noch nicht bremsen");
        }
    }

    @Test
    @DisplayName("Danach wächst die Wartezeit — und ist gedeckelt")
    void wartezeitWaechstUndIstGedeckelt() {
        var b = new Anmeldebremse();
        for (int i = 0; i < Anmeldebremse.FREIVERSUCHE + 1; i++) b.fehlversuch("10.0.0.2");
        var erste = b.wartezeit("10.0.0.2");
        assertFalse(erste.isZero());

        b.fehlversuch("10.0.0.2");
        assertTrue(b.wartezeit("10.0.0.2").compareTo(erste) > 0, "sie muss wachsen");

        // Laenger warten hilft nicht mehr und blockiert nur einen
        // Bearbeitungsstrang - der Deckel ist Absicht.
        for (int i = 0; i < 200; i++) b.fehlversuch("10.0.0.2");
        assertEquals(Anmeldebremse.HOECHSTENS, b.wartezeit("10.0.0.2"));
    }

    @Test
    @DisplayName("Absender werden getrennt gezählt")
    void absenderGetrennt() {
        var b = new Anmeldebremse();
        for (int i = 0; i < 20; i++) b.fehlversuch("10.0.0.3");
        assertTrue(b.gebremst("10.0.0.3"));
        assertFalse(b.gebremst("10.0.0.4"), "ein anderer Absender ist nicht betroffen");
    }

    @Test
    @DisplayName("Eine erfolgreiche Anmeldung räumt den Zähler ab")
    void erfolgRaeumtAb() {
        var b = new Anmeldebremse();
        for (int i = 0; i < 20; i++) b.fehlversuch("10.0.0.5");
        assertTrue(b.gebremst("10.0.0.5"));
        b.erfolg("10.0.0.5");
        assertFalse(b.gebremst("10.0.0.5"));
        assertTrue(b.wartezeit("10.0.0.5").isZero());
    }

    @Test
    @DisplayName("Ein unbekannter Absender wird nicht gebremst")
    void unbekannterAbsender() {
        var b = new Anmeldebremse();
        assertTrue(b.wartezeit("10.0.0.9").isZero());
        assertTrue(b.wartezeit(null).isZero());
        assertTrue(b.wartezeit("").isZero());
    }

    @Test
    @DisplayName("Auch ohne Absenderangabe wird gezählt — nicht durchgewinkt")
    void ohneAngabeTrotzdemZaehlen() {
        // Sonst waere die Bremse durch Weglassen der Angabe umgehbar.
        var b = new Anmeldebremse();
        for (int i = 0; i < 20; i++) b.fehlversuch(null);
        assertTrue(b.gebremst(null));
        assertTrue(b.gebremst(""), "leer und null landen im selben Topf");
    }

    @Test
    @DisplayName("Die Adresse wird nicht im Klartext gehalten")
    void adresseNichtImKlartext() {
        // Sie ist ein personenbezogenes Datum, und der Zaehler braucht sie
        // nicht - er muss nur unterscheiden koennen.
        var b = new Anmeldebremse();
        b.fehlversuch("192.168.178.42");
        assertFalse(b.toString().contains("192.168.178.42"));
    }

    @Test
    @DisplayName("Die Grenzwerte sind fachlich plausibel")
    void grenzwerte() {
        assertTrue(Anmeldebremse.FREIVERSUCHE >= 3,
                "weniger würde bei normalen Tippfehlern bremsen");
        assertTrue(Anmeldebremse.HOECHSTENS.compareTo(Duration.ofMinutes(2)) <= 0,
                "eine zu lange Sperre blockiert Bearbeitungsstränge");
        assertTrue(Anmeldebremse.VERFALL.compareTo(Duration.ofMinutes(5)) >= 0);
    }
}
