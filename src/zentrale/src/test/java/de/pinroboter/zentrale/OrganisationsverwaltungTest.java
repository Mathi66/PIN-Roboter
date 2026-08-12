package de.pinroboter.zentrale;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Organisationsstruktur ändern (FA-ORG-020).
 *
 * <p>Die Prüfungen hier sind überwiegend <b>Angriffsversuche</b>, nicht
 * Funktionsprüfungen. Grund: Wer den Baum ändern darf, ändert Geltungsbereiche.
 * Ein Standortadministrator, der eine fremde Praxis unter sich hängen kann, hat
 * damit Zugriff auf deren Geräte, Protokolle und Konten — ohne einen einzigen
 * Fehler in der Mandantentrennung selbst.
 *
 * <h2>Der Aufbau</h2>
 * <pre>
 * Verbund Nord (OV)
 *  ├── Standort West
 *  │    └── Praxis A
 *  └── Standort Ost
 *       └── Praxis C
 * Verbund Süd (OV)        ← fremder Mandant
 *  └── Praxis Fremd
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class OrganisationsverwaltungTest {

    private static Datenbank db;
    private static Organisationsverwaltung ov;
    private static long nord, west, ost, praxisA, praxisC, sued, fremd;
    private static Geltungsbereich bereichWest, bereichNord, bereichPraxisA;

    @BeforeEach
    void aufbauen() throws Exception {
        if (db == null) {
            db = new Datenbank(System.getenv("TEST_DB_URL"),
                    System.getenv().getOrDefault("TEST_DB_USER", "pinroboter"),
                    System.getenv().getOrDefault("TEST_DB_PASSWORD", "test"));
        }
        ov = new Organisationsverwaltung(db);
        aufraeumen();

        nord    = org("OV Verbund Nord", "VERBUND", null);
        west    = org("OV Standort West", "STANDORT", nord);
        ost     = org("OV Standort Ost", "STANDORT", nord);
        praxisA = org("OV Praxis A", "EINRICHTUNG", west);
        praxisC = org("OV Praxis C", "EINRICHTUNG", ost);
        sued    = org("OV Verbund Sued", "VERBUND", null);
        fremd   = org("OV Praxis Fremd", "EINRICHTUNG", sued);

        bereichWest    = Geltungsbereich.fuerBenutzer(db, west, false);
        bereichNord    = Geltungsbereich.fuerBenutzer(db, nord, false);
        bereichPraxisA = Geltungsbereich.fuerBenutzer(db, praxisA, false);
    }

    @AfterAll
    static void abraeumen() throws Exception { if (db != null) aufraeumen(); }

    // ------------------------------------------------------- Der Normalfall

    @Test
    @DisplayName("Der Baum zeigt nur den eigenen Bereich, von oben nach unten")
    void baumImBereich() {
        assertEquals(List.of("OV Standort West", "OV Praxis A"), bezeichnungen(bereichWest));
        assertEquals(List.of("OV Verbund Nord", "OV Standort Ost", "OV Praxis C",
                             "OV Standort West", "OV Praxis A"), bezeichnungen(bereichNord));
        // Der fremde Mandant taucht in keinem der beiden auf.
        assertFalse(bezeichnungen(bereichNord).contains("OV Praxis Fremd"));
    }

    @Test
    @DisplayName("Eine Einrichtung lässt sich unterhalb des eigenen Standorts anlegen")
    void anlegen() {
        var e = ov.anlegen("OV Praxis Neu", "EINRICHTUNG", west, bereichWest);
        assertTrue(e.geklappt(), e.fehler());
        assertTrue(bezeichnungen(bereichWest).contains("OV Praxis Neu"));
    }

    @Test
    @DisplayName("Umbenennen ändert nur den Namen, nicht die Zuordnung")
    void umbenennen() {
        assertTrue(ov.umbenennen(praxisA, "OV Praxis A neu", bereichWest).geklappt());
        var e = ov.einheit(praxisA, bereichWest).orElseThrow();
        assertEquals("OV Praxis A neu", e.bezeichnung());
        assertEquals(west, e.elternId());
    }

    @Test
    @DisplayName("Umhängen innerhalb des eigenen Bereichs funktioniert")
    void umhaengenImBereich() {
        var e = ov.umhaengen(praxisA, ost, bereichNord);
        assertTrue(e.geklappt(), e.fehler());
        assertEquals(ost, ov.einheit(praxisA, bereichNord).orElseThrow().elternId());
    }

    // --------------------------------------------------- Die Angriffsversuche

    @Test
    @DisplayName("Eine fremde Einheit lässt sich nicht unter den eigenen Bereich ziehen")
    void keineUebernahmeFremder() {
        // Der Standortadministrator West versucht, die fremde Praxis zu holen.
        var e = ov.umhaengen(fremd, west, bereichWest);
        assertFalse(e.geklappt());
        assertEquals(sued, elternVon(fremd), "Die fremde Einheit wurde verschoben!");
    }

    @Test
    @DisplayName("Die eigene Einheit lässt sich nicht aus dem Bereich hinausschieben")
    void keinWegschieben() {
        var e = ov.umhaengen(praxisA, sued, bereichWest);
        assertFalse(e.geklappt());
        assertEquals(west, elternVon(praxisA));
    }

    @Test
    @DisplayName("Unterhalb einer fremden Einheit lässt sich nichts anlegen")
    void keinAnlegenFremd() {
        var e = ov.anlegen("OV Eindringling", "EINRICHTUNG", sued, bereichWest);
        assertFalse(e.geklappt());
        assertEquals(0, ov.baum(Geltungsbereich.alles()).stream()
                .filter(x -> x.bezeichnung().equals("OV Eindringling")).count());
    }

    @Test
    @DisplayName("Ein Zyklus wird abgelehnt — er würde den Zweig vom Baum abtrennen")
    void keinZyklus() {
        // Standort West unter seine eigene Praxis haengen.
        var e = ov.umhaengen(west, praxisA, bereichNord);
        assertFalse(e.geklappt());
        assertEquals(nord, elternVon(west));
        // Und der Baum ist unverändert vollständig.
        assertEquals(5, ov.baum(bereichNord).size());
    }

    @Test
    @DisplayName("Eine Einheit kann sich nicht selbst übergeordnet sein")
    void nichtSichSelbst() {
        assertFalse(ov.umhaengen(west, west, bereichNord).geklappt());
        assertEquals(nord, elternVon(west));
    }

    @Test
    @DisplayName("Ein Benutzer ohne Bereich kann nichts anlegen und nichts sehen")
    void ohneBereich() {
        var keiner = Geltungsbereich.keiner();
        assertTrue(ov.baum(keiner).isEmpty());
        assertFalse(ov.anlegen("OV Nichts", "EINRICHTUNG", west, keiner).geklappt());
        assertFalse(ov.umhaengen(praxisA, ost, keiner).geklappt());
        assertFalse(ov.loeschen(praxisA, keiner).geklappt());
    }

    // ------------------------------------------------------- Die Art-Regeln

    @Test
    @DisplayName("Unterhalb einer Betriebsstätte ist nur ein Fachbereich zulässig")
    void nurFachbereichUnterEinrichtung() {
        // Seit ADR-0012 gliedert ein Fachbereich große Betriebsstätten — in
        // einer Klinik stehen 200 Kartenterminals nicht „irgendwo".
        var falsch = ov.anlegen("OV Zu tief", "EINRICHTUNG", praxisA, bereichNord);
        assertFalse(falsch.geklappt(), "Eine Betriebsstätte unter einer Betriebsstätte gibt es nicht");
        assertTrue(falsch.fehler().contains("Fachbereich"), falsch.fehler());

        var richtig = ov.anlegen("OV Labor", "FACHBEREICH", praxisA, bereichNord);
        assertTrue(richtig.geklappt(), richtig.meldung());
    }

    @Test
    @DisplayName("Unterhalb eines Fachbereichs lässt sich nichts mehr anlegen")
    void nichtsUnterFachbereich() {
        assertTrue(ov.anlegen("OV Radiologie", "FACHBEREICH", praxisA, bereichNord).geklappt());
        long fach = ov.baum(bereichNord).stream()
                .filter(e -> e.bezeichnung().equals("OV Radiologie"))
                .findFirst().orElseThrow().id();

        var e = ov.anlegen("OV Noch tiefer", "FACHBEREICH", fach, bereichNord);
        assertFalse(e.geklappt());
        assertTrue(e.fehler().contains("unterste Ebene"), e.fehler());
    }

    @Test
    @DisplayName("Ein Standort gehört nicht unter eine Einrichtung")
    void standortNichtUnterEinrichtung() {
        assertFalse(ov.umhaengen(ost, praxisA, bereichNord).geklappt());
        assertEquals(nord, elternVon(ost));
    }

    // ------------------------------------------------------------- Löschen

    @Test
    @DisplayName("Eine leere Einheit lässt sich löschen")
    void loeschenLeer() {
        var e = ov.loeschen(praxisC, bereichNord);
        assertTrue(e.geklappt(), e.fehler());
        assertFalse(bezeichnungen(bereichNord).contains("OV Praxis C"));
    }

    @Test
    @DisplayName("Eine Einheit mit Unterbau wird nicht gelöscht — sonst risse sie ihn mit")
    void loeschenNichtLeer() {
        var e = ov.loeschen(west, bereichNord);
        assertFalse(e.geklappt());
        // Die Meldung sagt, was im Weg steht - nicht nur, dass es nicht geht.
        assertTrue(e.fehler().contains("untergeordnete"), e.fehler());
        assertTrue(bezeichnungen(bereichNord).contains("OV Praxis A"));
    }

    @Test
    @DisplayName("Eine Einheit mit Konto wird nicht gelöscht")
    void loeschenMitKonto() throws Exception {
        try (PreparedStatement p = db.verbindung().prepareStatement("""
                INSERT INTO benutzer (benutzername, anzeigename, passwort_hash, totp_geheimnis,
                                      rolle, organisation_id)
                VALUES ('ov-nutzer-1','OV Nutzer','x','x','BENUTZER',?)""")) {
            p.setLong(1, praxisC);
            p.executeUpdate();
        }
        var e = ov.loeschen(praxisC, bereichNord);
        assertFalse(e.geklappt());
        assertTrue(e.fehler().contains("Konto"), e.fehler());
    }

    @Test
    @DisplayName("Die oberste Einheit des eigenen Bereichs lässt sich nicht löschen")
    void obersteBleibt() {
        // Aus Sicht von West ist West selbst die Wurzel - sie hat in diesem
        // Bereich kein sichtbares Elternteil.
        assertFalse(ov.loeschen(west, bereichWest).geklappt());
        assertNotNull(elternVon(west));
    }

    @Test
    @DisplayName("Eine Einrichtung sieht ihre eigene Einheit, aber keine darüber")
    void einrichtungSiehtNurSich() {
        assertEquals(List.of("OV Praxis A"), bezeichnungen(bereichPraxisA));
        assertTrue(ov.einheit(west, bereichPraxisA).isEmpty());
    }

    // ------------------------------------------------- TI-Identität (ADR-0012)

    @Test
    @DisplayName("Die Betriebsstätte nimmt BSNR und Telematik-ID auf")
    void tiIdentitaetAnDerBetriebsstaette() {
        var e = ov.tiIdentitaetSetzen(praxisA, "729112233",
                "1-SMC-B-Testkarte-883110000999888", bereichNord);
        assertTrue(e.geklappt(), e.meldung());
        var geladen = ov.einheit(praxisA, bereichNord).orElseThrow();
        assertEquals("729112233", geladen.bsnr());
        assertEquals("1-SMC-B-Testkarte-883110000999888", geladen.telematikId());
    }

    @Test
    @DisplayName("Ein Standort trägt keine BSNR — er ist keine Leistungserbringerinstitution")
    void keineTiIdentitaetAmStandort() {
        var e = ov.tiIdentitaetSetzen(west, "729112233", null, bereichNord);
        assertFalse(e.geklappt());
        assertTrue(e.fehler().contains("Betriebsstätte"), e.fehler());
        assertNull(ov.einheit(west, bereichNord).orElseThrow().bsnr());
    }

    @Test
    @DisplayName("Eine BSNR mit acht Ziffern wird abgelehnt, der Altwert bleibt")
    void bsnrFormWirdGeprueft() {
        ov.tiIdentitaetSetzen(praxisA, "729112233", null, bereichNord);
        var e = ov.tiIdentitaetSetzen(praxisA, "72911223", null, bereichNord);
        assertFalse(e.geklappt());
        assertTrue(e.fehler().contains("neun Ziffern"), e.fehler());
        assertEquals("729112233", ov.einheit(praxisA, bereichNord).orElseThrow().bsnr());
    }

    @Test
    @DisplayName("Ein leeres Feld löscht die Angabe — sie ist freiwillig")
    void tiIdentitaetIstFreiwillig() {
        ov.tiIdentitaetSetzen(praxisA, "729112233", "1-SMC-B-Testkarte-1", bereichNord);
        var e = ov.tiIdentitaetSetzen(praxisA, "", "", bereichNord);
        assertTrue(e.geklappt(), e.meldung());
        var geladen = ov.einheit(praxisA, bereichNord).orElseThrow();
        assertNull(geladen.bsnr());
        assertNull(geladen.telematikId());
    }

    @Test
    @DisplayName("Eine fremde Betriebsstätte lässt sich nicht mit einer BSNR versehen")
    void tiIdentitaetNurImEigenenBereich() {
        var e = ov.tiIdentitaetSetzen(fremd, "729112233", null, bereichNord);
        assertFalse(e.geklappt());
        assertNull(bsnrRoh(fremd));
    }

    // ------------------------------------------------------------- Kleinteile

    private String bsnrRoh(long id) {
        try (PreparedStatement p = db.verbindung().prepareStatement(
                "SELECT bsnr FROM organisation WHERE id = ?")) {
            p.setLong(1, id);
            try (ResultSet rs = p.executeQuery()) { return rs.next() ? rs.getString(1) : null; }
        } catch (SQLException e) { throw new IllegalStateException(e); }
    }

    private List<String> bezeichnungen(Geltungsbereich b) {
        return ov.baum(b).stream().map(Organisationsverwaltung.Einheit::bezeichnung).toList();
    }

    private Long elternVon(long id) {
        try (PreparedStatement p = db.verbindung().prepareStatement(
                "SELECT eltern_id FROM organisation WHERE id = ?")) {
            p.setLong(1, id);
            try (ResultSet rs = p.executeQuery()) {
                if (!rs.next()) return null;
                long e = rs.getLong(1);
                return rs.wasNull() ? null : e;      // sofort auswerten
            }
        } catch (SQLException e) { throw new IllegalStateException(e); }
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
            s.executeUpdate("DELETE FROM benutzer WHERE benutzername LIKE 'ov-nutzer-%'");
            s.executeUpdate("DELETE FROM organisation WHERE bezeichnung LIKE 'OV %'");
        }
    }
}
