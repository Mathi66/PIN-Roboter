package de.pinroboter.zentrale;

import de.pinroboter.gemeinsam.Ereignisart;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pseudonymisierung im Protokoll (Art. 17 DSGVO).
 *
 * <p>Der Konflikt: Das Fachprotokoll ist unveränderlich (FA-PRO-010), enthält
 * aber einen Personenbezug. Die Auflösung: Nach einer Frist wird der Name durch
 * eine Kennung ersetzt — die Handlung bleibt nachvollziehbar, die Person nicht
 * mehr benennbar.
 */
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PseudonymisierungTest {

    private static final String GERAET = "KT-PSEUDONYM";

    private static Datenbank db;
    private static Fachprotokoll protokoll;

    @BeforeAll
    static void aufbauen() throws Exception {
        db = new Datenbank(System.getenv("TEST_DB_URL"),
                System.getenv().getOrDefault("TEST_DB_USER", "pinroboter"),
                System.getenv().getOrDefault("TEST_DB_PASSWORD", "test"));
        protokoll = new Fachprotokoll(db, new Speicherwaechter(db, Long.MAX_VALUE));
    }

    @AfterAll
    static void abbauen() throws Exception { if (db != null) db.close(); }

    /** Legt einen Eintrag an und datiert ihn zurück. */
    private void alterEintrag(String ausloeser, int tageAlt) throws SQLException {
        protokoll.anfuegen("Praxis", GERAET, Ereignisart.GERAET_ERREICHBAR, true,
                ausloeser, null, "Eintrag von " + ausloeser, null);
        try (PreparedStatement p = db.verbindung().prepareStatement("""
                UPDATE fachprotokoll SET zeitpunkt = now() - make_interval(days => ?)
                WHERE geraet = ? AND ausloeser = ? AND zeitpunkt > now() - interval '1 minute'""")) {
            // Das Zurueckdatieren ist selbst eine Aenderung am Protokoll -
            // ueber denselben privilegierten Weg wie die Rotation.
            try (Statement s = db.verbindung().createStatement()) {
                s.execute("SET pinroboter.rotation = 'an'");
            }
            p.setInt(1, tageAlt);
            p.setString(2, GERAET);
            p.setString(3, ausloeser);
            p.executeUpdate();
            try (Statement s = db.verbindung().createStatement()) {
                s.execute("SET pinroboter.rotation = 'aus'");
            }
        }
    }

    private long zaehle(String ausloeser) throws SQLException {
        try (PreparedStatement p = db.verbindung().prepareStatement(
                "SELECT count(*) FROM fachprotokoll WHERE geraet = ? AND ausloeser = ?")) {
            p.setString(1, GERAET);
            p.setString(2, ausloeser);
            try (ResultSet rs = p.executeQuery()) { rs.next(); return rs.getLong(1); }
        }
    }

    @Test @Order(1)
    @DisplayName("Alte Einträge verlieren den Personenbezug, junge behalten ihn")
    void alteVerlierenDenNamen() throws Exception {
        var p = new Pseudonymisierung(db, protokoll, 183);
        alterEintrag("Anna Beispiel", 200);
        alterEintrag("Bert Beispiel", 10);

        assertEquals(1, zaehle("Anna Beispiel"));
        p.jetztPseudonymisieren();

        assertEquals(0, zaehle("Anna Beispiel"), "der alte Eintrag trägt keinen Namen mehr");
        assertEquals(1, zaehle("Bert Beispiel"), "der junge behält ihn — die Frist gilt");
    }

    @Test @Order(2)
    @DisplayName("Die Handlung bleibt vollständig nachvollziehbar")
    void handlungBleibt() throws Exception {
        // Das ist der Kern: Verloren geht die Person, nicht der Vorgang.
        try (PreparedStatement p = db.verbindung().prepareStatement("""
                SELECT ausloeser, text, geraet, art, erfolg FROM fachprotokoll
                WHERE geraet = ? AND text = 'Eintrag von Anna Beispiel'""")) {
            p.setString(1, GERAET);
            try (ResultSet rs = p.executeQuery()) {
                assertTrue(rs.next(), "der Eintrag muss noch da sein");
                assertTrue(rs.getString("ausloeser").startsWith("Person "),
                        "der Auslöser ist eine Kennung: " + rs.getString("ausloeser"));
                assertEquals(GERAET, rs.getString("geraet"));
                assertEquals("GERAET_ERREICHBAR", rs.getString("art"));
                assertTrue(rs.getBoolean("erfolg"));
            }
        }
    }

    @Test @Order(3)
    @DisplayName("Rollen ohne Personenbezug bleiben unberührt")
    void rollenBleiben() throws Exception {
        // "System" oder "Standort-Agent" sind keine Personen - sie zu
        // pseudonymisieren wuerde nur die Lesbarkeit zerstoeren.
        var p = new Pseudonymisierung(db, protokoll, 183);
        alterEintrag("System", 300);
        alterEintrag("Standort-Agent", 300);
        p.jetztPseudonymisieren();

        assertEquals(1, zaehle("System"));
        assertEquals(1, zaehle("Standort-Agent"));
    }

    @Test @Order(4)
    @DisplayName("Ein zweiter Lauf ändert nichts mehr")
    void zweiterLaufIstFolgenlos() throws Exception {
        var p = new Pseudonymisierung(db, protokoll, 183);
        assertEquals(0, p.jetztPseudonymisieren(),
                "bereits pseudonymisierte Einträge werden nicht erneut angefasst");
    }

    @Test @Order(5)
    @DisplayName("Frist 0 schaltet ab — als bewusste Entscheidung, sichtbar")
    void fristNullSchaltetAb() throws Exception {
        var aus = new Pseudonymisierung(db, protokoll, 0);
        assertFalse(aus.aktiv());
        assertEquals(0, aus.jetztPseudonymisieren());
        assertEquals(0, aus.faellig());
        assertEquals("abgeschaltet", aus.beschreibung());
    }

    @Test @Order(6)
    @DisplayName("Eine unlesbare Frist führt zur Vorgabe, nicht zum Abschalten")
    void unlesbareFrist() {
        // Ein Tippfehler in der Konfiguration darf den Schutz nicht still
        // aufheben. Die Richtung ist dieselbe wie beim Geltungsbereich.
        assertEquals(Pseudonymisierung.VORGABE_TAGE, Pseudonymisierung.fristAusUmgebung(),
                "ohne gesetzte Variable gilt die Vorgabe");
        assertTrue(Pseudonymisierung.VORGABE_TAGE > 0);
    }

    @Test @Order(7)
    @DisplayName("Die fällige Menge lässt sich abfragen, ohne zu ändern")
    void faelligOhneAenderung() throws Exception {
        var p = new Pseudonymisierung(db, protokoll, 183);
        alterEintrag("Clara Beispiel", 400);
        assertTrue(p.faellig() >= 1);
        assertEquals(1, zaehle("Clara Beispiel"), "das Abfragen ändert nichts");
        p.jetztPseudonymisieren();
        assertEquals(0, zaehle("Clara Beispiel"));
    }
}
