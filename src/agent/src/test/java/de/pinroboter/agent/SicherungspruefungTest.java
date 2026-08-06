package de.pinroboter.agent;

import de.pinroboter.gemeinsam.PinStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Prüft FA-PIN-030 — die Anforderung, die verhindert, dass das Produkt selbst
 * eine SMC-B sperrt.
 *
 * <p>Dieser Test ist nach dem Anforderungskatalog <b>vor jeder Auslieferung
 * verpflichtend</b>. An echter Hardware verbrennt er bei jedem Durchlauf einen
 * Fehlversuch der Karte; hier läuft er in jedem Bauvorgang (ADR-0007).
 */
class SicherungspruefungTest {

    private static final int UNBEKANNT = -1;

    @Test
    @DisplayName("Regelfall: nicht verifizierte Karte mit hinterlegter PIN wird freigegeben")
    void regelfallWirdFreigegeben() {
        var urteil = Sicherungspruefung.pruefe(
                PinStatus.VERIFIABLE, 3, UNBEKANNT, false, false, true, false);
        assertInstanceOf(Sicherungspruefung.Urteil.Freigabe.class, urteil);
    }

    @Test
    @DisplayName("FA-PIN-030: gesunkener Fehlversuchszähler hält die Automatik an")
    void gesunkenerZaehlerHaeltAn() {
        // Zuvor 3 Versuche beobachtet, jetzt nur noch 2: Irgendetwas hat eine
        // falsche PIN übergeben. Es wird NICHT weiterprobiert.
        var urteil = Sicherungspruefung.pruefe(
                PinStatus.VERIFIABLE, 2, 3, false, false, true, false);

        var abgelehnt = assertInstanceOf(Sicherungspruefung.Urteil.Abgelehnt.class, urteil);
        assertTrue(abgelehnt.grund().contains("gesunken"),
                "Der Grund muss den gesunkenen Zähler benennen: " + abgelehnt.grund());
        assertTrue(abgelehnt.handlung().toLowerCase().contains("pin"),
                "Die Meldung muss sagen, was zu tun ist (NFA-USA-020)");
    }

    @Test
    @DisplayName("FA-PIN-030: bei nur noch einem Versuch wird nicht geraten")
    void letzterVersuchWirdNichtVerbraucht() {
        var urteil = Sicherungspruefung.pruefe(
                PinStatus.VERIFIABLE, 1, 1, false, false, true, false);
        assertInstanceOf(Sicherungspruefung.Urteil.Abgelehnt.class, urteil);
    }

    @Test
    @DisplayName("Gesperrte Karte löst keinen weiteren Versuch aus")
    void gesperrteKarteWirdNichtVersucht() {
        var urteil = Sicherungspruefung.pruefe(
                PinStatus.BLOCKED, 0, 1, false, false, true, false);
        var abgelehnt = assertInstanceOf(Sicherungspruefung.Urteil.Abgelehnt.class, urteil);
        assertTrue(abgelehnt.handlung().contains("PUK"),
                "Die Meldung muss auf die PUK verweisen, sonst sucht der Betreiber falsch");
    }

    @Test
    @DisplayName("FA-UEB-050: während eines Firmware-Updates wird nicht verifiziert")
    void keineVerifikationWaehrendUpdate() {
        var urteil = Sicherungspruefung.pruefe(
                PinStatus.VERIFIABLE, 3, 3, false, false, true, true);
        var abgelehnt = assertInstanceOf(Sicherungspruefung.Urteil.Abgelehnt.class, urteil);
        assertTrue(abgelehnt.grund().contains("Firmware"));
    }

    @Test
    @DisplayName("FA-PIN-050: Not-Aus verhindert jede Verifikation")
    void notAusVerhindertAlles() {
        var urteil = Sicherungspruefung.pruefe(
                PinStatus.VERIFIABLE, 3, 3, false, true, true, false);
        assertInstanceOf(Sicherungspruefung.Urteil.Abgelehnt.class, urteil);
    }

    @Test
    @DisplayName("Angehaltene Automatik läuft nicht von selbst wieder an")
    void angehalteneAutomatikBleibtAn() {
        var urteil = Sicherungspruefung.pruefe(
                PinStatus.VERIFIABLE, 3, 3, true, false, true, false);
        assertInstanceOf(Sicherungspruefung.Urteil.Abgelehnt.class, urteil);
    }

    @Test
    @DisplayName("Ohne hinterlegte PIN wird nichts versucht")
    void ohnePinKeinVersuch() {
        var urteil = Sicherungspruefung.pruefe(
                PinStatus.VERIFIABLE, 3, 3, false, false, false, false);
        assertInstanceOf(Sicherungspruefung.Urteil.Abgelehnt.class, urteil);
    }

    @Test
    @DisplayName("FA-PIN-040: Transport-PIN erzeugt eine erklärende Meldung statt eines Versuchs")
    void transportPinWirdErklaert() {
        var urteil = Sicherungspruefung.pruefe(
                PinStatus.TRANSPORT_PIN, 3, 3, false, false, true, false);
        var abgelehnt = assertInstanceOf(Sicherungspruefung.Urteil.Abgelehnt.class, urteil);
        assertFalse(abgelehnt.handlung().isBlank(),
                "Jeder nicht behebbare Zustand braucht eine Handlungsanweisung");
    }

    @Test
    @DisplayName("Bereits verifizierte Karte löst keinen Versuch aus")
    void verifizierteKarteBleibtUnangetastet() {
        var urteil = Sicherungspruefung.pruefe(
                PinStatus.VERIFIED, 3, 3, false, false, true, false);
        assertInstanceOf(Sicherungspruefung.Urteil.Abgelehnt.class, urteil);
    }
}
