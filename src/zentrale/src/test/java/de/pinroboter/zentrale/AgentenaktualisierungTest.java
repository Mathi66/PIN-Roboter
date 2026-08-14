package de.pinroboter.zentrale;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Statement;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Die Zugangsdaten zur Programmablage, die an Standort-Agenten gehen
 * (FA-AGT-080, PO 14.08.2026).
 *
 * <h2>Wo die Absicherung liegt</h2>
 *
 * Bis zum 14.08.2026 stand vor der Weitergabe ein Schalter. Er war als Not-Aus
 * gedacht, solange nachts von selbst aktualisiert wurde. Seit nur noch auf
 * Auftrag aktualisiert wird, ist jeder Vorgang ohnehin eine bewusste Handlung —
 * ein Schalter davor wäre eine Hürde, die man einmal umlegt und nie wieder
 * ansieht.
 *
 * <p>Die Absicherung liegt jetzt dort, wo sie wirkt: <b>Die Zentrale gibt die
 * Zugangsdaten nur heraus, wenn für den fragenden Agenten ein Auftrag
 * vorliegt.</b> Das prüft {@code ZentraleTest} beziehungsweise der
 * Systemnachweis; hier geht es um die Ebene darunter — dass überhaupt die
 * richtigen Daten herauskommen und der Token verschlüsselt liegt.
 */
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class AgentenaktualisierungTest {

    private static Datenbank db;
    private static Versionspruefung vp;

    @BeforeEach
    void aufbauen() throws Exception {
        if (db == null) {
            db = new Datenbank(System.getenv("TEST_DB_URL"),
                    System.getenv().getOrDefault("TEST_DB_USER", "pinroboter"),
                    System.getenv().getOrDefault("TEST_DB_PASSWORD", "test"));
            // Ein fester Schlüssel: Der Test prüft das Zusammenspiel, nicht die
            // Verschlüsselung selbst — die hat ihre eigenen Prüfungen.
            var tresor = de.pinroboter.gemeinsam.Geheimnisspeicher.ausBase64(
                    Base64.getEncoder().encodeToString(new byte[32]));
            vp = new Versionspruefung(db, tresor);
        }
        aufraeumen();
    }

    @AfterAll
    static void abraeumen() throws Exception { if (db != null) aufraeumen(); }

    @Test
    @DisplayName("Ohne eingerichteten Zugang gibt es nichts weiterzugeben")
    void ohneZugang() {
        assertNull(vp.agentenzugang());
    }

    @Test
    @DisplayName("Sie liefert Registry, Benutzer und Token im Klartext")
    void liefert() throws Exception {
        zugangEinrichten("ghp-testtoken-2");

        var z = vp.agentenzugang();
        assertNotNull(z);
        assertEquals("ghcr.io", z.registry());
        assertEquals("pruefbenutzer", z.benutzer());
        assertEquals("ghp-testtoken-2", z.token(),
                "Der Agent braucht den Klartext — verschlüsselt kann sich "
                + "die Aktualisierung nicht anmelden");
    }

    @Test
    @DisplayName("Der Token liegt verschlüsselt in der Datenbank, nicht im Klartext")
    void nichtImKlartext() throws Exception {
        zugangEinrichten("ghp-testtoken-4");

        try (Statement s = db.verbindung().createStatement();
             var rs = s.executeQuery("SELECT token_chiffrat FROM registry_zugang")) {
            assertTrue(rs.next());
            String ch = rs.getString(1);
            assertNotNull(ch);
            assertFalse(ch.contains("ghp-testtoken-4"),
                    "Ein Datenbankabzug — etwa eine Sicherung — würde den Token sonst mitnehmen");
            assertTrue(ch.startsWith("v1:"), "erwartet wird das Format aus ADR-0008: " + ch);
        }
    }

    @Test
    @DisplayName("Ohne Benutzernamen bleibt die Weitergabe leer statt halb")
    void unvollstaendig() throws Exception {
        zugangEinrichten("ghp-testtoken-5");
        try (Statement s = db.verbindung().createStatement()) {
            s.executeUpdate("UPDATE registry_zugang SET benutzer = NULL");
        }
        assertNull(vp.agentenzugang(),
                "Eine halbe Anmeldung scheitert erst beim Abholen des Abbilds — "
                + "dort sieht sie niemand");
    }

    // ------------------------------------------------------------- Kleinteile

    /** Richtet den Zugang über den regulären Weg ein, damit er echt verschlüsselt ist. */
    private static void zugangEinrichten(String token) {
        vp.zugangSpeichern("ghcr.io", "mathi66/pin-roboter", "pruefbenutzer", token);
    }

    private static void aufraeumen() throws Exception {
        try (Statement s = db.verbindung().createStatement()) {
            s.executeUpdate("DELETE FROM registry_zugang");
        }
    }
}
