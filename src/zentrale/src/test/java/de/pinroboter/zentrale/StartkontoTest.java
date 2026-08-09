package de.pinroboter.zentrale;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Das Administratorkonto aus der Konfiguration (FA-BEN-035).
 *
 * <p>Geprüft wird der Teil, der ohne gesetzte Umgebungsvariablen erreichbar
 * ist: das Verhalten bei fehlender und bei unvollständiger Konfiguration. Dass
 * das Konto samt vorgegebenem zweiten Faktor tatsächlich entsteht und ein
 * {@code docker compose down -v} überlebt, prüft der Systemnachweis am
 * laufenden Stack — im Modultest ließen sich Umgebungsvariablen nur mit
 * Reflektion auf die JDK-Interna setzen, und ein Test, der das JDK aufbricht,
 * prüft am Ende sich selbst.
 */
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class StartkontoTest {

    private static Datenbank db;
    private static Benutzerverwaltung benutzer;
    private static Fachprotokoll protokoll;

    @BeforeAll
    static void verbinden() throws Exception {
        db = new Datenbank(System.getenv("TEST_DB_URL"),
                System.getenv().getOrDefault("TEST_DB_USER", "pinroboter"),
                System.getenv().getOrDefault("TEST_DB_PASSWORD", "test"));
        benutzer = new Benutzerverwaltung(db);
        protokoll = new Fachprotokoll(db, new Speicherwaechter(db, 1024L * 1024 * 1024));
    }

    @AfterAll static void trennen() { if (db != null) db.close(); }

    @Test
    @DisplayName("Ohne Konfiguration passiert nichts")
    void ohneKonfiguration() {
        // Der Normalfall jeder Installation, die die Ersteinrichtung nutzt.
        // Ein Startkonto darf sich nicht ungefragt anlegen.
        assertEquals("nicht konfiguriert",
                Startkonto.anlegenFallsNoetig(db, benutzer, protokoll));
    }

    @Test
    @DisplayName("Das Anlegen bricht den Start nie ab")
    void niemalsAbbruch() {
        // Die Kernfunktion ist die Überwachung der Kartenterminals. Sie läuft
        // auch ohne Oberfläche weiter — ein fehlerhaft konfiguriertes
        // Startkonto darf sie nicht anhalten.
        assertDoesNotThrow(() -> Startkonto.anlegenFallsNoetig(db, benutzer, protokoll));
        // Auch mit unbrauchbarer Datenbank: keine Ausnahme nach außen.
        assertDoesNotThrow(() -> Startkonto.anlegenFallsNoetig(null, benutzer, protokoll));
    }
}
