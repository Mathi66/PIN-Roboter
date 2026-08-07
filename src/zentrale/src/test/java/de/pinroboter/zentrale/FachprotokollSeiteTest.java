package de.pinroboter.zentrale;

import de.pinroboter.gemeinsam.Ereignisart;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Blättern in der Protokollansicht (ADR-0005).
 *
 * <p>Bis zum 07.08.2026 zeigte die Ansicht nur die 200 jüngsten Einträge und
 * konnte nicht zurück. Wer einen Vorgang von gestern nachvollziehen wollte,
 * musste exportieren — für ein System, das selbsttätig handelt, zu wenig.
 */
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FachprotokollSeiteTest {

    private static final String GERAET = "KT-BLAETTERTEST";
    private static final int ANZAHL = 137;   // absichtlich kein Vielfaches der Seitengröße

    /** Diese Klasse prüft das Blättern; die Mandantentrennung hat eine eigene Testklasse. */
    private static final Geltungsbereich ALLES = Geltungsbereich.alles();

    private static Datenbank db;
    private static Fachprotokoll protokoll;

    @BeforeAll
    static void aufbauen() throws Exception {
        db = new Datenbank(System.getenv("TEST_DB_URL"),
                System.getenv().getOrDefault("TEST_DB_USER", "pinroboter"),
                System.getenv().getOrDefault("TEST_DB_PASSWORD", "test"));
        protokoll = new Fachprotokoll(db, new Speicherwaechter(db, Long.MAX_VALUE));

        // Nicht aufgeraeumt, sondern uebersprungen, wenn schon befuellt:
        // Das Fachprotokoll ist unveraenderlich (FA-PRO-010) - ein DELETE
        // scheitert, und das soll es auch. Nur die Rotation darf loeschen.
        // Beim ersten Anlauf dieses Tests war genau das der Fehler; er hat
        // damit ungewollt den Loeschschutz bestaetigt.
        if (vorhanden() == 0) {
            for (int i = 1; i <= ANZAHL; i++) {
                protokoll.anfuegen("Blättertest", GERAET,
                        i % 3 == 0 ? Ereignisart.GERAET_NICHT_ERREICHBAR : Ereignisart.GERAET_ERREICHBAR,
                        i % 3 != 0, "Test", null, "Eintrag " + i, null);
            }
        }
        assertEquals(ANZAHL, vorhanden(), "Ausgangslage für alle folgenden Erwartungen");
    }

    @AfterAll
    static void abbauen() throws Exception {
        if (db != null) db.close();
    }

    private static long vorhanden() throws Exception {
        try (Statement s = db.verbindung().createStatement();
             java.sql.ResultSet rs = s.executeQuery(
                     "SELECT count(*) FROM fachprotokoll WHERE geraet = '" + GERAET + "'")) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    @Test @Order(0)
    @DisplayName("Das Fachprotokoll lässt sich nicht löschen (FA-PRO-010)")
    void protokollIstUnveraenderlich() {
        // Hier bestaetigt, weil dieser Test es beim Aufraeumen entdeckt hat.
        assertThrows(Exception.class, () -> {
            try (Statement s = db.verbindung().createStatement()) {
                s.executeUpdate("DELETE FROM fachprotokoll WHERE geraet = '" + GERAET + "'");
            }
        }, "ein Löschversuch muss scheitern, nicht stillschweigend gelingen");
    }

    @Test @Order(1)
    @DisplayName("Die erste Seite zeigt die jüngsten Einträge und kennt die Gesamtzahl")
    void ersteSeite() {
        var s = protokoll.seite(GERAET, null, null, 1, 50, ALLES);
        assertEquals(50, s.eintraege().size());
        assertEquals(ANZAHL, s.gesamt());
        assertEquals(3, s.seitenZahl(), "137 Einträge zu 50 sind drei Seiten");
        assertFalse(s.hatVorherige());
        assertTrue(s.hatNaechste());
        assertEquals(1, s.von());
        assertEquals(50, s.bis());
        assertEquals("Eintrag " + ANZAHL, s.eintraege().get(0).text(), "jüngster zuerst");
    }

    @Test @Order(2)
    @DisplayName("Die letzte Seite ist angebrochen und meldet kein Weiter")
    void letzteSeite() {
        var s = protokoll.seite(GERAET, null, null, 3, 50, ALLES);
        assertEquals(37, s.eintraege().size(), "137 − 100 = 37");
        assertTrue(s.hatVorherige());
        assertFalse(s.hatNaechste());
        assertEquals(101, s.von());
        assertEquals(137, s.bis());
        assertEquals("Eintrag 1", s.eintraege().get(36).text(), "ältester zuletzt");
    }

    @Test @Order(3)
    @DisplayName("Keine Seite zeigt einen Eintrag zweimal")
    void keineUeberschneidung() {
        // Ein Klassiker bei LIMIT/OFFSET: Ohne eindeutige Sortierung wandern
        // Zeilen zwischen Seiten. Die Sortierung nach (zeitpunkt, id) schliesst
        // das aus - auch bei gleichem Zeitstempel.
        var alle = new java.util.HashSet<Long>();
        int gezaehlt = 0;
        for (int nr = 1; nr <= 3; nr++) {
            for (var e : protokoll.seite(GERAET, null, null, nr, 50, ALLES).eintraege()) {
                assertTrue(alle.add(e.id()), "Eintrag " + e.id() + " erscheint auf zwei Seiten");
                gezaehlt++;
            }
        }
        assertEquals(ANZAHL, gezaehlt, "über alle Seiten müssen alle Einträge kommen");
    }

    @Test @Order(4)
    @DisplayName("Ein Filter wirkt auf Trefferzahl und Seiten gleichermaßen")
    void filterWirktAufBeides() {
        var s = protokoll.seite(GERAET, null, "fehler", 1, 50, ALLES);
        assertEquals(45, s.gesamt(), "jeder dritte von 137 ist fehlgeschlagen");
        assertEquals(1, s.seitenZahl());
        assertTrue(s.eintraege().stream().noneMatch(Ereignis::erfolg),
                "die Seite darf nur Fehlschläge enthalten");

        var nachArt = protokoll.seite(GERAET, Ereignisart.GERAET_ERREICHBAR.name(), null, 1, 50, ALLES);
        assertEquals(92, nachArt.gesamt());
    }

    @Test @Order(5)
    @DisplayName("Unsinnige Seitenangaben führen nicht zu Fehlern")
    void unsinnigeAngaben() {
        assertEquals(1, protokoll.seite(GERAET, null, null, 0, 50, ALLES).seitenNummer(),
                "Seite 0 wird zu Seite 1");
        assertEquals(1, protokoll.seite(GERAET, null, null, -5, 50, ALLES).seitenNummer());
        assertTrue(protokoll.seite(GERAET, null, null, 999, 50, ALLES).eintraege().isEmpty(),
                "hinter dem Ende gibt es nichts — aber auch keinen Fehler");

        // Nach oben begrenzt, damit eine manipulierte Anfrage nicht das ganze
        // Protokoll zieht.
        assertEquals(500, protokoll.seite(GERAET, null, null, 1, 100_000, ALLES).seitengroesse());
        assertEquals(1, protokoll.seite(GERAET, null, null, 1, 0, ALLES).seitengroesse());
    }

    @Test @Order(6)
    @DisplayName("Ein leeres Ergebnis meldet null Treffer statt zu scheitern")
    void leeresErgebnis() {
        var s = protokoll.seite("GIBTESNICHT", null, null, 1, 50, ALLES);
        assertTrue(s.eintraege().isEmpty());
        assertEquals(0, s.gesamt());
        assertEquals(1, s.seitenZahl(), "auch ohne Treffer gibt es eine Seite");
        assertEquals(0, s.von());
        assertFalse(s.hatNaechste());
    }
}
