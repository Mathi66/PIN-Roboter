package de.pinroboter.zentrale;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Rechte je Ebene (Block A).
 *
 * <p>Der {@link Geltungsbereich} regelt, <i>welche Daten</i> jemand sieht.
 * {@link Rechte} regelt, <i>welche Art von Handlung</i> ihm zusteht. Diese
 * Klasse prüft die zweite Frage — insbesondere, dass ein Praxisadministrator
 * keine instanzweiten Aktionen auslösen kann.
 */
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class RechteTest {

    private static Datenbank db;
    private static long verbund, standort, praxis;

    @BeforeAll
    static void aufbauen() throws Exception {
        db = new Datenbank(System.getenv("TEST_DB_URL"),
                System.getenv().getOrDefault("TEST_DB_USER", "pinroboter"),
                System.getenv().getOrDefault("TEST_DB_PASSWORD", "test"));
        aufraeumen();
        verbund  = org("RT Verbund", "VERBUND", null);
        standort = org("RT Standort", "STANDORT", verbund);
        praxis   = org("RT Praxis", "EINRICHTUNG", standort);
    }

    @AfterAll
    static void abbauen() throws Exception {
        if (db != null) { aufraeumen(); db.close(); }
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
            s.executeUpdate("DELETE FROM organisation WHERE bezeichnung LIKE 'RT %'");
        }
    }

    private static Rechte rechte(Benutzerverwaltung.Rolle rolle, Long orgId) {
        return Rechte.fuer(db, new Benutzerverwaltung.Benutzer(
                1, "x", "X", rolle, true, true, orgId));
    }

    @Test
    @DisplayName("Die Ebene ergibt sich aus der Art der Organisationseinheit")
    void ebeneAusOrganisation() {
        assertEquals(Rechte.Ebene.INSTANZ,
                rechte(Benutzerverwaltung.Rolle.ADMINISTRATOR, null).ebene());
        assertEquals(Rechte.Ebene.VERBUND,
                rechte(Benutzerverwaltung.Rolle.ADMINISTRATOR, verbund).ebene());
        assertEquals(Rechte.Ebene.STANDORT,
                rechte(Benutzerverwaltung.Rolle.ADMINISTRATOR, standort).ebene());
        assertEquals(Rechte.Ebene.EINRICHTUNG,
                rechte(Benutzerverwaltung.Rolle.ADMINISTRATOR, praxis).ebene());
    }

    @Test
    @DisplayName("Ein Benutzer ohne Zuordnung ist kein Instanzbetreiber")
    void ohneZuordnungKeinBetreiber() {
        // Diese Richtung ist die ganze Sicherheit: Eine fehlende Zuordnung darf
        // nie zu uneingeschraenktem Zugriff fuehren.
        var r = rechte(Benutzerverwaltung.Rolle.BENUTZER, null);
        assertEquals(Rechte.Ebene.UNBEKANNT, r.ebene());
        assertFalse(r.darfBestandPflegen());
        assertFalse(r.darfInstanzVerwalten());
        assertFalse(r.darfNotAus());
    }

    @Test
    @DisplayName("Eine gelöschte Organisationseinheit lässt keine Rechte übrig")
    void verschwundeneOrganisation() {
        var r = rechte(Benutzerverwaltung.Rolle.ADMINISTRATOR, 999_999_999L);
        assertEquals(Rechte.Ebene.UNBEKANNT, r.ebene());
        assertFalse(r.darfBestandPflegen());
        assertFalse(r.darfOrganisationVerwalten());
    }

    @Test
    @DisplayName("Bestand pflegen darf jeder Administrator in seinem Bereich")
    void bestandPflegen() {
        assertTrue(rechte(Benutzerverwaltung.Rolle.ADMINISTRATOR, praxis).darfBestandPflegen());
        assertTrue(rechte(Benutzerverwaltung.Rolle.ADMINISTRATOR, standort).darfBestandPflegen());
        assertTrue(rechte(Benutzerverwaltung.Rolle.ADMINISTRATOR, null).darfBestandPflegen());
        assertFalse(rechte(Benutzerverwaltung.Rolle.BENUTZER, praxis).darfBestandPflegen(),
                "ein Benutzer ohne Administratorrolle nicht");
    }

    @Test
    @DisplayName("Organisationen anlegen erst ab Standortebene")
    void organisationErstAbStandort() {
        // Sonst koennte ein Praxisadministrator seinen eigenen Geltungsbereich
        // erweitern - die Mandantengrenze waere von innen aufhebbar.
        assertFalse(rechte(Benutzerverwaltung.Rolle.ADMINISTRATOR, praxis).darfOrganisationVerwalten(),
                "eine Praxis legt keine weiteren Einrichtungen an");
        assertTrue(rechte(Benutzerverwaltung.Rolle.ADMINISTRATOR, standort).darfOrganisationVerwalten());
        assertTrue(rechte(Benutzerverwaltung.Rolle.ADMINISTRATOR, verbund).darfOrganisationVerwalten());
        assertTrue(rechte(Benutzerverwaltung.Rolle.ADMINISTRATOR, null).darfOrganisationVerwalten());
    }

    @Test
    @DisplayName("Instanzweite Werkzeuge nur für den Instanzbetreiber")
    void instanzWerkzeugeNurFuerBetreiber() {
        // Demodatensatz und Lasttestbestand legen Daten quer ueber alle
        // Mandanten an oder entfernen sie.
        assertTrue(rechte(Benutzerverwaltung.Rolle.ADMINISTRATOR, null).darfInstanzVerwalten());
        assertFalse(rechte(Benutzerverwaltung.Rolle.ADMINISTRATOR, verbund).darfInstanzVerwalten(),
                "auch ein Verbundadministrator nicht — er ist nicht die Instanz");
        assertFalse(rechte(Benutzerverwaltung.Rolle.ADMINISTRATOR, standort).darfInstanzVerwalten());
        assertFalse(rechte(Benutzerverwaltung.Rolle.ADMINISTRATOR, praxis).darfInstanzVerwalten());
    }

    @Test
    @DisplayName("Den Not-Aus darf jeder Administrator — bewusst großzügig")
    void notAusFuerJedenAdministrator() {
        // Ein Sicherheitsmittel darf nicht an einer Genehmigung haengen.
        assertTrue(rechte(Benutzerverwaltung.Rolle.ADMINISTRATOR, praxis).darfNotAus());
        assertFalse(rechte(Benutzerverwaltung.Rolle.BENUTZER, praxis).darfNotAus(),
                "ein Benutzer ohne Administratorrolle aber nicht");
    }

    @Test
    @DisplayName("Die Beschreibung nennt Rolle und Ebene")
    void beschreibung() {
        // Sie steht in der Fehlermeldung. Ein blankes „Nur Administratoren"
        // laesst jemanden mit Administratorrolle ratlos zurueck, wenn in
        // Wahrheit die Ebene nicht reicht.
        assertEquals("Administrator · Einrichtung",
                rechte(Benutzerverwaltung.Rolle.ADMINISTRATOR, praxis).beschreibung());
        assertEquals("Benutzer · Standort",
                rechte(Benutzerverwaltung.Rolle.BENUTZER, standort).beschreibung());
        assertEquals("nicht angemeldet", Rechte.keine().beschreibung());
    }
}
