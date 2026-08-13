package de.pinroboter.zentrale;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Statement;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Die Weitergabe der Zugangsdaten zur Programmablage an die Standort-Agenten
 * (PO, 13.08.2026).
 *
 * <h2>Warum es diesen Schalter gibt</h2>
 *
 * Die Programmablage bleibt privat. Damit ein Agent sich selbst aktualisieren
 * kann, braucht die Aktualisierung eine Anmeldung — und die kennt nur die
 * Zentrale. Sie gibt sie an registrierte Agenten weiter.
 *
 * <p>Das ist eine Weitergabe von Zugangsdaten, also nichts, was stillschweigend
 * geschieht. Deshalb steht sie <b>ab Werk aus</b> und ist mit einem Schalter
 * verbunden, der auch als Not-Aus taugt: Ohne Anmeldung holt die Aktualisierung
 * kein Abbild mehr.
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
    @DisplayName("Ab Werk ist die Weitergabe aus — auch mit hinterlegtem Zugang")
    void vorgabeIstAus() throws Exception {
        zugangEinrichten("ghp-testtoken-1");
        assertNull(vp.agentenzugang(),
                "Wer einen Zugang hinterlegt, damit die Zentrale nach Updates sieht, "
                + "hat damit nicht der Weitergabe an jeden Agenten zugestimmt");
    }

    @Test
    @DisplayName("Eingeschaltet liefert sie Registry, Benutzer und Token im Klartext")
    void eingeschaltet() throws Exception {
        zugangEinrichten("ghp-testtoken-2");
        vp.anAgenten(true);

        var z = vp.agentenzugang();
        assertNotNull(z);
        assertEquals("ghcr.io", z.registry());
        assertEquals("pruefbenutzer", z.benutzer());
        assertEquals("ghp-testtoken-2", z.token(),
                "Der Agent braucht den Klartext — verschlüsselt kann sich "
                + "die Aktualisierung nicht anmelden");
    }

    @Test
    @DisplayName("Ausgeschaltet wird er sofort wieder vorenthalten")
    void notAus() throws Exception {
        zugangEinrichten("ghp-testtoken-3");
        vp.anAgenten(true);
        assertNotNull(vp.agentenzugang());

        vp.anAgenten(false);
        assertNull(vp.agentenzugang(),
                "Der Not-Aus wirkt nur, wenn die Zentrale ab sofort nichts mehr herausgibt");
    }

    @Test
    @DisplayName("Der Token liegt verschlüsselt in der Datenbank, nicht im Klartext")
    void nichtImKlartext() throws Exception {
        zugangEinrichten("ghp-testtoken-4");
        vp.anAgenten(true);

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
        vp.anAgenten(true);
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
