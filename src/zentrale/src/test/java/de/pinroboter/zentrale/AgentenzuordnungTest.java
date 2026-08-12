package de.pinroboter.zentrale;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Die Einrichtung eines Standort-Agenten korrigieren.
 *
 * <h2>Warum es diese Funktion gibt</h2>
 *
 * Der Agent speichert seine Einrichtung als <b>Text</b>, nicht als Verweis.
 * Wird die Organisationseinheit umbenannt oder gelöscht, meldet er ab da ins
 * Leere: Die Zentrale findet keine Vorgabegruppe und kann seine Geräte keiner
 * Gruppe zuordnen. Im Protokoll steht dann zeilenweise
 * „Gerät … konnte der Einrichtung … nicht zugeordnet werden" — in der
 * Oberfläche sah bis zum 12.08.2026 alles normal aus.
 *
 * <p>Ursprünglich war die Zuordnung bewusst unveränderlich („ein Agent steht in
 * genau einer Praxis"). Der Satz stimmt, führt im Fehlerfall aber in eine
 * Sackgasse: Es bliebe nur die Neuregistrierung — mit neuem Schlüssel und dem
 * Verlust aller an diesem Standort hinterlegten PINs.
 *
 * <p>Die Prüfungen hier sind zur Hälfte Angriffsversuche: Wer einen Agenten
 * umhängen kann, entscheidet, in wessen Sicht dessen Geräte landen.
 */
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class AgentenzuordnungTest {

    private static Datenbank db;
    private static Agentenverwaltung av;
    private static long verbund, praxisA, fremd;
    private static Geltungsbereich bereichVerbund, bereichPraxisA;

    @BeforeEach
    void aufbauen() throws Exception {
        if (db == null) {
            db = new Datenbank(System.getenv("TEST_DB_URL"),
                    System.getenv().getOrDefault("TEST_DB_USER", "pinroboter"),
                    System.getenv().getOrDefault("TEST_DB_PASSWORD", "test"));
        }
        av = new Agentenverwaltung(db);
        aufraeumen();

        verbund = org("AZ Verbund", "VERBUND", null);
        praxisA = org("AZ Praxis A", "EINRICHTUNG", verbund);
        fremd   = org("AZ Praxis Fremd", "EINRICHTUNG", org("AZ Verbund Fremd", "VERBUND", null));

        bereichVerbund = Geltungsbereich.fuerBenutzer(db, verbund, false);
        bereichPraxisA = Geltungsbereich.fuerBenutzer(db, praxisA, false);
    }

    @AfterAll
    static void abraeumen() throws Exception { if (db != null) aufraeumen(); }

    @Test
    @DisplayName("Ein Agent lässt sich einer anderen Einrichtung zuordnen")
    void umhaengen() throws Exception {
        long id = agent("AZ Agent", "Praxis von gestern");
        assertNull(av.einrichtungSetzen(id, "AZ Praxis A", bereichVerbund));
        assertEquals("AZ Praxis A", av.einrichtungVon(id));
    }

    @Test
    @DisplayName("Eine Einrichtung, die es nicht gibt, wird abgelehnt")
    void unbekannteEinrichtung() throws Exception {
        long id = agent("AZ Agent", "AZ Praxis A");
        String fehler = av.einrichtungSetzen(id, "Gibt es nicht", bereichVerbund);
        assertNotNull(fehler);
        assertTrue(fehler.contains("eindeutige"), fehler);
        assertEquals("AZ Praxis A", av.einrichtungVon(id), "Der Altwert bleibt");
    }

    @Test
    @DisplayName("Eine fremde Einrichtung wird abgelehnt — sonst holt jemand fremde Geräte in seine Sicht")
    void fremdeEinrichtung() throws Exception {
        long id = agent("AZ Agent", "AZ Praxis A");
        assertNotNull(av.einrichtungSetzen(id, "AZ Praxis Fremd", bereichPraxisA));
        assertEquals("AZ Praxis A", av.einrichtungVon(id));
        // Auch der Verbund darf nicht über seine Grenze hinaus.
        assertNotNull(av.einrichtungSetzen(id, "AZ Praxis Fremd", bereichVerbund));
    }

    @Test
    @DisplayName("Eine leere Angabe ändert nichts")
    void leereAngabe() throws Exception {
        long id = agent("AZ Agent", "AZ Praxis A");
        assertNotNull(av.einrichtungSetzen(id, "  ", bereichVerbund));
        assertNotNull(av.einrichtungSetzen(id, null, bereichVerbund));
        assertEquals("AZ Praxis A", av.einrichtungVon(id));
    }

    @Test
    @DisplayName("Die Übersicht kennzeichnet eine Einrichtung, die es nicht mehr gibt")
    void unbekanntWirdGemeldet() throws Exception {
        long verwaist = agent("AZ Agent verwaist", "Praxis von gestern");
        long heil     = agent("AZ Agent heil", "AZ Praxis A");

        var liste = av.alle();
        assertFalse(eintrag(liste, verwaist).einrichtungBekannt(),
                "Ohne dieses Merkmal stünde der Befund nur im technischen Protokoll");
        assertTrue(eintrag(liste, heil).einrichtungBekannt());

        // Und nach der Korrektur ist der Befund weg.
        assertNull(av.einrichtungSetzen(verwaist, "AZ Praxis A", bereichVerbund));
        assertTrue(eintrag(av.alle(), verwaist).einrichtungBekannt());
    }

    // ------------------------------------------------------------- Kleinteile

    private static Agentenverwaltung.Agent eintrag(
            java.util.List<Agentenverwaltung.Agent> liste, long id) {
        return liste.stream().filter(a -> a.id() == id).findFirst()
                .orElseThrow(() -> new AssertionError("Agent " + id + " fehlt in der Liste"));
    }

    /** Ein registrierter Agent — ohne den Registrierungsweg, der hier nicht geprüft wird. */
    private static long agent(String bezeichnung, String einrichtung) throws SQLException {
        try (PreparedStatement p = db.verbindung().prepareStatement("""
                INSERT INTO agent (bezeichnung, einrichtung, token_hash, aktiv)
                VALUES (?,?,?,TRUE) RETURNING id""")) {
            p.setString(1, bezeichnung);
            p.setString(2, einrichtung);
            p.setString(3, "az-" + bezeichnung + "-" + einrichtung);
            try (ResultSet rs = p.executeQuery()) { rs.next(); return rs.getLong(1); }
        }
    }

    private static long org(String bez, String art, Long eltern) throws SQLException {
        try (PreparedStatement p = db.verbindung().prepareStatement("""
                INSERT INTO organisation (bezeichnung, art, eltern_id, ist_demo)
                VALUES (?,?,?,FALSE) RETURNING id""")) {
            p.setString(1, bez);
            p.setString(2, art);
            if (eltern == null) p.setNull(3, Types.BIGINT); else p.setLong(3, eltern);
            try (ResultSet rs = p.executeQuery()) { rs.next(); return rs.getLong(1); }
        }
    }

    private static void aufraeumen() throws SQLException {
        try (Statement s = db.verbindung().createStatement()) {
            s.executeUpdate("DELETE FROM agent WHERE bezeichnung LIKE 'AZ %'");
            s.executeUpdate("DELETE FROM organisation WHERE bezeichnung LIKE 'AZ %'");
        }
    }
}
