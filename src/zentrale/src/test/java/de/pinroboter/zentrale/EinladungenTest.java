package de.pinroboter.zentrale;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Einladungen für neue Konten (Block B, FA-BEN-010).
 *
 * <p>Schwerpunkt: die Zusagen, deren Bruch teuer wäre — ein Geheimnis gilt
 * einmal, es steht nicht im Klartext in der Datenbank, und niemand lädt in eine
 * fremde Einrichtung ein.
 */
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EinladungenTest {

    private static final char[] KENNWORT = "EinLangesUnverdaechtigesWort42".toCharArray();

    private static Datenbank db;
    private static Benutzerverwaltung bv;
    private static Einladungen ein;
    private static long praxisA, praxisB;
    private static Geltungsbereich bereichA;

    @BeforeAll
    static void aufbauen() throws Exception {
        db = new Datenbank(System.getenv("TEST_DB_URL"),
                System.getenv().getOrDefault("TEST_DB_USER", "pinroboter"),
                System.getenv().getOrDefault("TEST_DB_PASSWORD", "test"));
        bv = new Benutzerverwaltung(db);
        ein = new Einladungen(db, bv);
        aufraeumen();
        long verbund = org("EL Verbund", "VERBUND", null);
        praxisA = org("EL Praxis A", "EINRICHTUNG", verbund);
        praxisB = org("EL Praxis B", "EINRICHTUNG", verbund);
        bereichA = Geltungsbereich.fuerBenutzer(db, praxisA, false);
    }

    @AfterAll
    static void abbauen() throws Exception {
        if (db != null) { aufraeumen(); db.close(); }
    }

    private static long org(String bez, String art, Long eltern) throws SQLException {
        try (PreparedStatement p = db.verbindung().prepareStatement("""
                INSERT INTO organisation (bezeichnung, art, eltern_id, ist_demo)
                VALUES (?,?,?,FALSE) RETURNING id""")) {
            p.setString(1, bez); p.setString(2, art);
            if (eltern == null) p.setNull(3, Types.BIGINT); else p.setLong(3, eltern);
            try (ResultSet rs = p.executeQuery()) { rs.next(); return rs.getLong(1); }
        }
    }

    private static void aufraeumen() throws SQLException {
        try (Statement s = db.verbindung().createStatement()) {
            s.executeUpdate("DELETE FROM einladung WHERE benutzername LIKE 'el-%'");
            s.executeUpdate("DELETE FROM benutzer WHERE benutzername LIKE 'el-%'");
            s.executeUpdate("DELETE FROM organisation WHERE bezeichnung LIKE 'EL %'");
        }
    }

    @Test @Order(1)
    @DisplayName("Eine Einladung liefert ein Geheimnis — und speichert nur dessen Hash")
    void einladenSpeichertNurDenHash() throws Exception {
        var e = ein.einladen("el-anna", "Anna Beispiel", Benutzerverwaltung.Rolle.BENUTZER,
                praxisA, "Testadmin", bereichA);
        assertTrue(e.geklappt(), () -> String.valueOf(e.fehler()));
        assertNotNull(e.geheimnis());
        assertTrue(e.geheimnis().length() >= 40, "das Geheimnis muss lang genug sein");

        // Wer die Datenbank liest, darf keine Einladung einloesen koennen.
        try (Statement s = db.verbindung().createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT geheimnis_hash FROM einladung WHERE benutzername = 'el-anna'")) {
            rs.next();
            String gespeichert = rs.getString(1);
            assertNotEquals(e.geheimnis(), gespeichert, "kein Klartext in der Datenbank");
            assertEquals(Einladungen.hash(e.geheimnis()), gespeichert);
        }
    }

    @Test @Order(2)
    @DisplayName("Vor dem Einlösen gibt es noch kein Konto")
    void nochKeinKonto() {
        assertTrue(bv.alle(Geltungsbereich.alles()).stream()
                .noneMatch(b -> b.benutzername().equals("el-anna")),
                "eine Einladung ist noch kein Konto");
        assertEquals(1, ein.offene(bereichA).size());
    }

    @Test @Order(3)
    @DisplayName("Das Einlösen legt das Konto an und richtet den zweiten Faktor ein")
    void einloesen() {
        var vorher = ein.offene(bereichA);
        assertEquals(1, vorher.size());
        // Das Geheimnis ist nur beim Erzeugen bekannt - hier neu einladen.
        ein.zurueckziehen("el-anna", bereichA);
        var e = ein.einladen("el-anna", "Anna Beispiel", Benutzerverwaltung.Rolle.BENUTZER,
                praxisA, "Testadmin", bereichA);

        var erg = ein.einloesen(e.geheimnis(), KENNWORT.clone());
        assertInstanceOf(Einladungen.Einloesung.Erfolg.class, erg, () -> String.valueOf(erg));
        var erfolg = (Einladungen.Einloesung.Erfolg) erg;
        assertNotNull(erfolg.totpGeheimnis(), "der zweite Faktor wird vorbereitet");

        var konto = bv.alle(Geltungsbereich.alles()).stream()
                .filter(b -> b.benutzername().equals("el-anna")).findFirst().orElseThrow();
        assertEquals("Anna Beispiel", konto.anzeigename());
        assertEquals(praxisA, konto.organisationId(),
                "das Konto landet in der eingeladenen Einrichtung");
        assertFalse(konto.totpBestaetigt(), "der zweite Faktor ist erst eingerichtet, nicht bestätigt");
    }

    @Test @Order(4)
    @DisplayName("Dasselbe Geheimnis ein zweites Mal wird abgewiesen")
    void geheimnisGiltEinmal() {
        var e = ein.einladen("el-bert", "Bert Beispiel", Benutzerverwaltung.Rolle.BENUTZER,
                praxisA, "Testadmin", bereichA);
        assertInstanceOf(Einladungen.Einloesung.Erfolg.class,
                ein.einloesen(e.geheimnis(), KENNWORT.clone()));

        var zweiter = ein.einloesen(e.geheimnis(), KENNWORT.clone());
        assertInstanceOf(Einladungen.Einloesung.Abgelehnt.class, zweiter);
        assertTrue(((Einladungen.Einloesung.Abgelehnt) zweiter).grund().contains("bereits verwendet"));
    }

    @Test @Order(5)
    @DisplayName("Ein erfundenes Geheimnis wird abgewiesen, ohne etwas zu verraten")
    void erfundenesGeheimnis() {
        var e = ein.einloesen("voellig-erfunden", KENNWORT.clone());
        assertInstanceOf(Einladungen.Einloesung.Abgelehnt.class, e);
        String grund = ((Einladungen.Einloesung.Abgelehnt) e).grund();
        assertTrue(grund.contains("nicht gültig"), grund);
        assertFalse(grund.toLowerCase().contains("benutzer"),
                "die Meldung darf keine Kontonamen verraten: " + grund);
    }

    @Test @Order(6)
    @DisplayName("Ein schwaches Kennwort verbraucht die Einladung nicht")
    void schwachesKennwortVerbrauchtNicht() {
        // Sonst waere eine Einladung durch einen Tippfehler unbrauchbar - und
        // der Eingeladene muesste beim Administrator um eine neue bitten.
        var e = ein.einladen("el-clara", "Clara Beispiel", Benutzerverwaltung.Rolle.BENUTZER,
                praxisA, "Testadmin", bereichA);
        var schwach = ein.einloesen(e.geheimnis(), "kurz".toCharArray());
        assertInstanceOf(Einladungen.Einloesung.Abgelehnt.class, schwach);

        var stark = ein.einloesen(e.geheimnis(), KENNWORT.clone());
        assertInstanceOf(Einladungen.Einloesung.Erfolg.class, stark,
                "nach einem schwachen Versuch muss die Einladung noch gelten");
    }

    @Test @Order(7)
    @DisplayName("Eine abgelaufene Einladung wird abgewiesen")
    void abgelaufeneEinladung() throws Exception {
        var e = ein.einladen("el-dora", "Dora Beispiel", Benutzerverwaltung.Rolle.BENUTZER,
                praxisA, "Testadmin", bereichA);
        try (PreparedStatement p = db.verbindung().prepareStatement(
                "UPDATE einladung SET gueltig_bis = now() - interval '1 hour' WHERE benutzername = ?")) {
            p.setString(1, "el-dora");
            p.executeUpdate();
        }
        var erg = ein.einloesen(e.geheimnis(), KENNWORT.clone());
        assertInstanceOf(Einladungen.Einloesung.Abgelehnt.class, erg);
        assertTrue(((Einladungen.Einloesung.Abgelehnt) erg).grund().contains("abgelaufen"));
    }

    @Test @Order(8)
    @DisplayName("Niemand lädt in eine fremde Einrichtung ein")
    void keineEinladungInFremdeEinrichtung() {
        // Die Einrichtung kommt aus einem Formularfeld. Ohne Pruefung koennte
        // sich ein Praxisadministrator selbst Zugang zur Nachbarpraxis schaffen.
        var e = ein.einladen("el-eingeschleust", "Eingeschleust",
                Benutzerverwaltung.Rolle.ADMINISTRATOR, praxisB, "Testadmin", bereichA);
        assertFalse(e.geklappt());
        assertTrue(e.fehler().contains("außerhalb Ihres"), e.fehler());
    }

    @Test @Order(9)
    @DisplayName("Offene Einladungen bleiben im Geltungsbereich")
    void offeneImBereich() {
        ein.einladen("el-fritz", "Fritz Beispiel", Benutzerverwaltung.Rolle.BENUTZER,
                praxisB, "Testadmin", Geltungsbereich.alles());

        var fuerA = ein.offene(bereichA).stream().map(Einladungen.Einladung::benutzername).toList();
        assertFalse(fuerA.contains("el-fritz"),
                "Praxis A darf die Einladung von Praxis B nicht sehen");
        assertTrue(ein.offene(Geltungsbereich.alles()).stream()
                .anyMatch(i -> i.benutzername().equals("el-fritz")));
    }

    @Test @Order(10)
    @DisplayName("Ein doppelter Benutzername wird abgewiesen")
    void keinDoppelterName() {
        var e = ein.einladen("el-anna", "Anna Zwei", Benutzerverwaltung.Rolle.BENUTZER,
                praxisA, "Testadmin", bereichA);
        assertFalse(e.geklappt());
        assertTrue(e.fehler().contains("bereits ein Konto"), e.fehler());
    }

    @Test @Order(105)
    @DisplayName("Der letzte handlungsfähige Administrator lässt sich nicht deaktivieren")
    void letzterAdministratorBleibt() throws Exception {
        // Sonst sperrt sich eine Praxis versehentlich selbst aus - und in einer
        // Einrichtung ohne IT-Personal ist das ein Fall fuer einen Dienstleister.
        var alles = Geltungsbereich.alles();
        try (Statement s = db.verbindung().createStatement()) {
            s.executeUpdate("UPDATE benutzer SET aktiv = FALSE WHERE rolle = 'ADMINISTRATOR'");
            s.executeUpdate("""
                    INSERT INTO benutzer (benutzername, anzeigename, passwort_hash, rolle, aktiv)
                    VALUES ('el-letzter', 'Letzter Admin', 'x', 'ADMINISTRATOR', TRUE)""");
        }
        long id;
        try (Statement s = db.verbindung().createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT id FROM benutzer WHERE benutzername = 'el-letzter'")) {
            rs.next(); id = rs.getLong(1);
        }

        String fehler = bv.aktivSetzen(id, false, alles);
        assertNotNull(fehler, "das Deaktivieren muss abgelehnt werden");
        assertTrue(fehler.contains("letzte handlungsfähige"), fehler);

        var konto = bv.benutzer(id, alles).orElseThrow();
        assertTrue(konto.aktiv(), "er ist nach dem Versuch weiterhin aktiv");

        // Mit einem zweiten Administrator geht es dann.
        try (Statement s = db.verbindung().createStatement()) {
            s.executeUpdate("""
                    INSERT INTO benutzer (benutzername, anzeigename, passwort_hash, rolle, aktiv)
                    VALUES ('el-zweiter', 'Zweiter Admin', 'x', 'ADMINISTRATOR', TRUE)""");
        }
        assertNull(bv.aktivSetzen(id, false, alles),
                "mit einem zweiten Administrator ist das Deaktivieren erlaubt");
        assertFalse(bv.benutzer(id, alles).orElseThrow().aktiv());

        try (Statement s = db.verbindung().createStatement()) {
            s.executeUpdate("DELETE FROM benutzer WHERE benutzername IN ('el-letzter','el-zweiter')");
            s.executeUpdate("UPDATE benutzer SET aktiv = TRUE WHERE rolle = 'ADMINISTRATOR'");
        }
    }

    @Test @Order(11)
    @DisplayName("Eine Einladung lässt sich zurückziehen — aber nicht aus fremdem Bereich")
    void zurueckziehen() {
        assertTrue(ein.zurueckziehen("el-fritz", Geltungsbereich.alles()));
        assertFalse(ein.zurueckziehen("el-fritz", Geltungsbereich.alles()),
                "eine zurückgezogene Einladung gibt es nicht mehr");

        var e = ein.einladen("el-gustav", "Gustav Beispiel", Benutzerverwaltung.Rolle.BENUTZER,
                praxisB, "Testadmin", Geltungsbereich.alles());
        assertTrue(e.geklappt());
        assertFalse(ein.zurueckziehen("el-gustav", bereichA),
                "Praxis A darf die Einladung von Praxis B nicht zurückziehen");
        assertTrue(ein.zurueckziehen("el-gustav", Geltungsbereich.alles()));
    }
}
