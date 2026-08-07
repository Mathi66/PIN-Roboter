package de.pinroboter.zentrale;

import de.pinroboter.gemeinsam.Geheimnisspeicher;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Mandantentrennung — die wichtigste Sicherheitsprüfung dieses Produkts.
 *
 * <p>CLAUDE.md nennt sie <i>„die häufigste schwere Sicherheitslücke in
 * mandantenfähigen Anwendungen"</i>. Diese Klasse prüft nicht, ob die Oberfläche
 * fremde Daten <i>anzeigt</i> — sie prüft, ob die Datenschicht sie überhaupt
 * herausgibt. Eine Trennung, die nur in der Anzeige besteht, ist keine.
 *
 * <h2>Der Aufbau</h2>
 * <pre>
 * Verbund Nord
 *  ├── Standort West
 *  │    ├── Praxis A   → Konnektor A, Terminal A
 *  │    └── Praxis B   → Konnektor B, Terminal B
 *  └── Standort Ost
 *       └── Praxis C   → Konnektor C, Terminal C
 * </pre>
 *
 * Geprüft wird aus vier Blickwinkeln: Praxis A, Standort West, Verbund Nord und
 * ein Benutzer ohne Zuordnung.
 */
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MandantentrennungTest {

    private static Datenbank db;
    private static Bestandsverwaltung bv;
    private static long verbund, west, ost, praxisA, praxisB, praxisC;
    private static long konnA, konnB, konnC;
    private static long termA, termB;

    private static Geltungsbereich bereichA, bereichWest, bereichVerbund;

    @BeforeAll
    static void aufbauen() throws Exception {
        db = new Datenbank(System.getenv("TEST_DB_URL"),
                System.getenv().getOrDefault("TEST_DB_USER", "pinroboter"),
                System.getenv().getOrDefault("TEST_DB_PASSWORD", "test"));
        bv = new Bestandsverwaltung(db,
                Geheimnisspeicher.ausBase64(Geheimnisspeicher.neuerSchluesselBase64()));
        aufraeumen();

        verbund = org("MT Verbund Nord", "VERBUND", null);
        west    = org("MT Standort West", "STANDORT", verbund);
        ost     = org("MT Standort Ost", "STANDORT", verbund);
        praxisA = org("MT Praxis A", "EINRICHTUNG", west);
        praxisB = org("MT Praxis B", "EINRICHTUNG", west);
        praxisC = org("MT Praxis C", "EINRICHTUNG", ost);

        var alles = Geltungsbereich.alles();
        konnA = bv.konnektorAnlegen(praxisA, "MT Konnektor A", "192.0.2.1", null,
                "M-A", "CS", "AP", "admin", "KennwortA", null, alles).id();
        konnB = bv.konnektorAnlegen(praxisB, "MT Konnektor B", "192.0.2.2", null,
                "M-B", "CS", "AP", "admin", "KennwortB", null, alles).id();
        konnC = bv.konnektorAnlegen(praxisC, "MT Konnektor C", "192.0.2.3", null,
                "M-C", "CS", "AP", null, null, null, alles).id();

        termA = bv.terminalAnlegen("MT-KT-A", konnA, "Terminal A", null, "192.0.2.11",
                "CHERRY ST-1506", null, null, null, true, false, alles).id();
        termB = bv.terminalAnlegen("MT-KT-B", konnB, "Terminal B", null, "192.0.2.12",
                "Ingenico ORGA 6141", null, null, null, false, false, alles).id();
        bv.terminalAnlegen("MT-KT-C", konnC, "Terminal C", null, "192.0.2.13",
                "CHERRY ST-1506", null, null, null, false, false, alles);

        bereichA       = Geltungsbereich.fuerBenutzer(db, praxisA, false);
        bereichWest    = Geltungsbereich.fuerBenutzer(db, west, false);
        bereichVerbund = Geltungsbereich.fuerBenutzer(db, verbund, false);
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
            s.executeUpdate("DELETE FROM kartenterminal WHERE terminal_id LIKE 'MT-KT-%'");
            s.executeUpdate("DELETE FROM konnektor WHERE bezeichnung LIKE 'MT Konnektor%'");
            s.executeUpdate("DELETE FROM organisation WHERE bezeichnung LIKE 'MT %'");
        }
    }

    private List<String> terminalnamen(Geltungsbereich b) {
        return bv.terminals(Bestandsverwaltung.Filter.leer(), null, false, 1, 100, b)
                .eintraege().stream()
                .map(Bestandsverwaltung.Terminal::bezeichnung)
                .filter(n -> n.startsWith("Terminal ")).sorted().toList();
    }

    private List<String> konnektornamen(Geltungsbereich b) {
        return bv.konnektoren(b).stream()
                .map(Bestandsverwaltung.Konnektor::bezeichnung)
                .filter(n -> n.startsWith("MT ")).sorted().toList();
    }

    // ------------------------------------------------------- Sicht nach unten

    @Test @Order(1)
    @DisplayName("Eine Praxis sieht ausschließlich ihre eigenen Geräte")
    void praxisSiehtNurSichSelbst() {
        assertEquals(List.of("Terminal A"), terminalnamen(bereichA));
        assertEquals(List.of("MT Konnektor A"), konnektornamen(bereichA));
    }

    @Test @Order(2)
    @DisplayName("Ein Standort sieht seine Einrichtungen — aber nicht den Nachbarstandort")
    void standortSiehtSeineEinrichtungen() {
        assertEquals(List.of("Terminal A", "Terminal B"), terminalnamen(bereichWest));
        assertEquals(List.of("MT Konnektor A", "MT Konnektor B"), konnektornamen(bereichWest));
        // Praxis C haengt an Standort Ost.
        assertFalse(terminalnamen(bereichWest).contains("Terminal C"));
    }

    @Test @Order(3)
    @DisplayName("Der Verbund sieht alles darunter")
    void verbundSiehtAlles() {
        assertEquals(List.of("Terminal A", "Terminal B", "Terminal C"), terminalnamen(bereichVerbund));
    }

    @Test @Order(4)
    @DisplayName("Es gibt keine Sicht nach oben oder zur Seite")
    void keineSichtNachObenOderZurSeite() {
        // Praxis A sieht ihren Standort nicht - und schon gar nicht die
        // Nachbarpraxis im selben Standort.
        assertFalse(bereichA.umfasst(west), "kein Blick auf den eigenen Standort");
        assertFalse(bereichA.umfasst(verbund), "kein Blick auf den Verbund");
        assertFalse(bereichA.umfasst(praxisB), "kein Blick auf die Nachbarpraxis");
        assertTrue(bereichA.umfasst(praxisA));

        assertTrue(bereichWest.umfasst(praxisA));
        assertTrue(bereichWest.umfasst(praxisB));
        assertFalse(bereichWest.umfasst(praxisC), "kein Blick in den anderen Standort");
        assertFalse(bereichWest.umfasst(ost));
    }

    @Test @Order(5)
    @DisplayName("Ein Benutzer ohne Zuordnung sieht nichts — nicht alles")
    void ohneZuordnungNichts() {
        // Diese Richtung ist die ganze Sicherheit: Ein Fehler in der Zuordnung
        // darf zu "sieht nichts" fuehren, niemals zu "sieht alles".
        var keiner = Geltungsbereich.fuerBenutzer(db, null, false);
        assertTrue(keiner.leer());
        assertTrue(terminalnamen(keiner).isEmpty());
        assertTrue(konnektornamen(keiner).isEmpty());

        var unbekannt = Geltungsbereich.fuerBenutzer(db, 999_999_999L, false);
        assertTrue(unbekannt.leer(), "eine unbekannte Organisation ergibt keinen Zugriff");
        assertTrue(terminalnamen(unbekannt).isEmpty());
    }

    // ------------------------------------------- Manipulierte Kennungen

    @Test @Order(10)
    @DisplayName("Ein fremdes Gerät ist über seine Kennung nicht erreichbar")
    void fremdesGeraetNichtUeberIdErreichbar() {
        // Der Angriff, gegen den es hier geht: Jemand liest eine Kennung aus
        // einer URL ab und ruft sie mit seiner eigenen Sitzung auf.
        assertTrue(bv.terminal(termA, bereichA).isPresent(), "das eigene Gerät natürlich schon");
        assertTrue(bv.terminal(termB, bereichA).isEmpty(),
                "das Gerät der Nachbarpraxis darf nicht auffindbar sein");
        assertTrue(bv.konnektor(konnB, bereichA).isEmpty());
        assertTrue(bv.konnektor(konnC, bereichWest).isEmpty());
    }

    @Test @Order(11)
    @DisplayName("Ein fremdes Gerät lässt sich nicht ändern")
    void fremdesGeraetNichtAenderbar() {
        var e = bv.terminalAendern(termB, "Gekapert", null, "10.0.0.1", null, null,
                null, null, false, false, true, bereichA);
        assertFalse(e.geklappt());
        assertEquals("Dieses Kartenterminal gibt es nicht mehr.", e.fehler(),
                "dieselbe Meldung wie bei einem gelöschten Datensatz — ein „Zugriff "
                + "verweigert“ würde verraten, dass es ihn gibt");

        var t = bv.terminal(termB, Geltungsbereich.alles()).orElseThrow();
        assertEquals("Terminal B", t.bezeichnung(), "der Datensatz ist unverändert");
    }

    @Test @Order(12)
    @DisplayName("Ein fremdes Gerät lässt sich nicht löschen")
    void fremdesGeraetNichtLoeschbar() {
        assertFalse(bv.terminalLoeschen(termB, bereichA).geklappt());
        assertTrue(bv.terminal(termB, Geltungsbereich.alles()).isPresent(), "es ist noch da");

        assertFalse(bv.konnektorLoeschen(konnB, bereichA).geklappt());
        assertTrue(bv.konnektor(konnB, Geltungsbereich.alles()).isPresent());
    }

    @Test @Order(13)
    @DisplayName("Ein Konnektor lässt sich nicht in einer fremden Einrichtung anlegen")
    void keinKonnektorInFremderEinrichtung() {
        // Die Einrichtung kommt aus einem Formularfeld und ist manipulierbar.
        // Eine Mandantengrenze, die nur in der Auswahlliste besteht, ist keine.
        var e = bv.konnektorAnlegen(praxisB, "Eingeschleust", "10.0.0.9", null,
                "M", "CS", "AP", null, null, null, bereichA);
        assertFalse(e.geklappt());
        assertTrue(e.fehler().contains("außerhalb Ihres"), e.fehler());
        assertEquals(1, bv.konnektoren(Geltungsbereich.fuerBenutzer(db, praxisB, false)).size(),
                "in Praxis B ist kein Konnektor dazugekommen");
    }

    @Test @Order(14)
    @DisplayName("Ein Terminal lässt sich nicht an einen fremden Konnektor hängen")
    void keinTerminalAnFremdemKonnektor() {
        var e = bv.terminalAnlegen("MT-KT-EINGESCHLEUST", konnB, "Eingeschleust", null,
                "10.0.0.9", null, null, null, null, false, false, bereichA);
        assertFalse(e.geklappt());
        assertTrue(e.fehler().contains("Konnektor auswählen"), e.fehler());
    }

    @Test @Order(15)
    @DisplayName("Eine Massenaktion greift nicht auf fremde Geräte durch")
    void massenaktionBleibtImBereich() {
        // Der gefaehrlichste Fall: Die Kennungen kommen als Liste aus dem
        // Formular. Ohne Bereichsgrenze wuerde eine praeparierte Anfrage fremde
        // Geraete deaktivieren - lautlos, denn die Liste zeigt sie ja nicht.
        var e = bv.massenAktivSetzen(List.of(termA, termB), false, bereichA);
        assertEquals(1, e.geaendert(), "nur das eigene Gerät");
        assertEquals(1, e.uebersprungen());
        assertFalse(e.vollstaendig(), "der Benutzer erfährt, dass nicht alles ausgeführt wurde");

        var fremd = bv.terminal(termB, Geltungsbereich.alles()).orElseThrow();
        assertTrue(fremd.aktiv(), "das fremde Gerät ist unverändert aktiv");

        bv.massenAktivSetzen(List.of(termA), true, bereichA);
    }

    @Test @Order(16)
    @DisplayName("Filterwerte verraten keine fremden Modelle")
    void filterwerteBleibenImBereich() {
        // Eine Auswahlliste, die alle Modelle des Hauses zeigt, verrät den
        // Gerätebestand anderer Mandanten — auch ohne einen einzigen Datensatz.
        var modelleA = bv.vorhandeneWerte("modell", bereichA);
        assertEquals(List.of("CHERRY ST-1506"), modelleA);
        assertFalse(modelleA.contains("Ingenico ORGA 6141"),
                "das Modell aus Praxis B darf nicht auftauchen");

        assertTrue(bv.vorhandeneWerte("modell", bereichWest).contains("Ingenico ORGA 6141"),
                "der Standort sieht beide");
    }

    @Test @Order(17)
    @DisplayName("Die Einrichtungsauswahl zeigt nur erreichbare Einheiten")
    void organisationsauswahlBleibtImBereich() {
        var fuerA = bv.organisationen(bereichA).stream()
                .map(Bestandsseiten.Organisationsauswahl::bezeichnung)
                .filter(n -> n.startsWith("MT ")).toList();
        assertEquals(List.of("MT Praxis A"), fuerA);

        var fuerWest = bv.organisationen(bereichWest).stream()
                .map(Bestandsseiten.Organisationsauswahl::bezeichnung)
                .filter(n -> n.startsWith("MT ")).sorted().toList();
        assertEquals(List.of("MT Praxis A", "MT Praxis B", "MT Standort West"), fuerWest);
    }

    @Test @Order(18)
    @DisplayName("Suche und Filter durchbrechen die Grenze nicht")
    void sucheDurchbrichtNichts() {
        // Eine Suche ist der naheliegendste Weg, an fremde Daten zu kommen -
        // sie fasst schliesslich den ganzen Bestand an.
        var treffer = bv.terminals(new Bestandsverwaltung.Filter("Terminal", null, null, null,
                null, null, null), null, false, 1, 100, bereichA);
        assertEquals(1, treffer.gesamt(), "die Suche findet nur das eigene Gerät");

        var nachFremdemModell = bv.terminals(new Bestandsverwaltung.Filter(null, null, null, null,
                "Ingenico ORGA 6141", null, null), null, false, 1, 100, bereichA);
        assertEquals(0, nachFremdemModell.gesamt(),
                "auch ein Filter auf ein fremdes Modell liefert nichts");

        var ueberFremdenKonnektor = bv.terminals(new Bestandsverwaltung.Filter(null, konnB, null,
                null, null, null, null), null, false, 1, 100, bereichA);
        assertEquals(0, ueberFremdenKonnektor.gesamt(),
                "auch nicht über die Kennung eines fremden Konnektors");
    }

    @Test @Order(185)
    @DisplayName("Ein Administrator ohne Organisation kommt als Instanzbetreiber aus der Datenbank")
    void adminOhneOrganisationBleibtInstanzbetreiber() throws Exception {
        // Gefunden im Vollauf: rs.wasNull() gilt fuer die ZULETZT gelesene
        // Spalte. Stand die Pruefung hinter weiteren getXxx-Aufrufen, bekam ein
        // Administrator ohne Organisation die Kennung 0 statt null - galt damit
        // nicht mehr als Instanzbetreiber und sah GAR NICHTS. Ein Fehler, der
        // die Anwendung nicht abstuerzen laesst, sondern still leert.
        var bv2 = new Benutzerverwaltung(db);
        String name = "mt-instanzadmin";
        try (Statement s = db.verbindung().createStatement()) {
            s.executeUpdate("DELETE FROM benutzer WHERE benutzername = '" + name + "'");
        }
        var e = bv2.anlegen(name, "MT Instanzadmin", "EinSehrLangesPruefwortOhneHinweis".toCharArray(),
                Benutzerverwaltung.Rolle.ADMINISTRATOR);
        assertFalse(e instanceof Benutzerverwaltung.Anmeldung.Abgelehnt,
                () -> "Konto muss angelegt werden können: " + e);

        var geladen = bv2.alle().stream()
                .filter(b -> b.benutzername().equals(name)).findFirst().orElseThrow();
        assertNull(geladen.organisationId(),
                "ohne Zuordnung muss die Kennung null sein, nicht 0");
        assertTrue(geladen.istInstanzbetreiber());
        assertTrue(Geltungsbereich.fuerBenutzer(db, geladen.organisationId(),
                geladen.istInstanzbetreiber()).unbeschraenkt());

        try (Statement s = db.verbindung().createStatement()) {
            s.executeUpdate("DELETE FROM benutzer WHERE benutzername = '" + name + "'");
        }
    }

    @Test @Order(19)
    @DisplayName("Der Instanzbetreiber sieht alles — und nur er")
    void instanzbetreiber() {
        var betreiber = Geltungsbereich.fuerBenutzer(db, null, true);
        assertTrue(betreiber.unbeschraenkt());
        assertEquals(3, terminalnamen(betreiber).size());

        // Ein Benutzer OHNE Administratorrolle und ohne Zuordnung ist kein
        // Instanzbetreiber - er sieht nichts.
        var normal = new Benutzerverwaltung.Benutzer(1, "x", "X",
                Benutzerverwaltung.Rolle.BENUTZER, true, true, null);
        assertFalse(normal.istInstanzbetreiber());
        var admin = new Benutzerverwaltung.Benutzer(1, "a", "A",
                Benutzerverwaltung.Rolle.ADMINISTRATOR, true, true, null);
        assertTrue(admin.istInstanzbetreiber());
        var adminMitOrg = new Benutzerverwaltung.Benutzer(1, "a", "A",
                Benutzerverwaltung.Rolle.ADMINISTRATOR, true, true, praxisA);
        assertFalse(adminMitOrg.istInstanzbetreiber(),
                "ein Administrator einer Einrichtung ist kein Instanzbetreiber");
    }
}
