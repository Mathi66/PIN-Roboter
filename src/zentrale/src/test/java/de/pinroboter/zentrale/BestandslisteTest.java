package de.pinroboter.zentrale;

import de.pinroboter.gemeinsam.Geheimnisspeicher;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sortierung, Filter und Massenaktionen der Geräteliste (FA-GER-060).
 *
 * <p>Die Anforderung heißt „bleibt bei 5.000 Terminals bedienbar". Das
 * entscheidet sich nicht an der Optik, sondern daran, ob Sortieren, Filtern und
 * Massenaktionen <b>serverseitig</b> und <b>korrekt</b> arbeiten.
 */
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BestandslisteTest {

    /**
     * Diese Testklassen prüfen Fachlogik, nicht Mandantentrennung — sie
     * arbeiten deshalb uneingeschränkt. Die Trennung selbst hat eine eigene
     * Testklasse: {@code MandantentrennungTest}.
     */
    private static final Geltungsbereich ALLES = Geltungsbereich.alles();

    private static Datenbank db;
    private static Bestandsverwaltung bv;
    private static Gruppenverwaltung gv;
    private static long orgId;
    private static long konnektorId;

    @BeforeAll
    static void aufbauen() throws Exception {
        db = new Datenbank(System.getenv("TEST_DB_URL"),
                System.getenv().getOrDefault("TEST_DB_USER", "pinroboter"),
                System.getenv().getOrDefault("TEST_DB_PASSWORD", "test"));
        bv = new Bestandsverwaltung(db,
                Geheimnisspeicher.ausBase64(Geheimnisspeicher.neuerSchluesselBase64()));
        gv = new Gruppenverwaltung(db);
        aufraeumen();

        long verbundId;
        try (Statement s = db.verbindung().createStatement();
             ResultSet rs = s.executeQuery("""
                     INSERT INTO organisation (bezeichnung, art, ist_demo)
                     VALUES ('Testverbund Liste', 'VERBUND', FALSE) RETURNING id""")) {
            rs.next(); verbundId = rs.getLong(1);
        }
        try (PreparedStatement p = db.verbindung().prepareStatement("""
                INSERT INTO organisation (bezeichnung, art, eltern_id, ist_demo)
                VALUES ('Testeinrichtung Liste', 'EINRICHTUNG', ?, FALSE) RETURNING id""")) {
            p.setLong(1, verbundId);
            try (ResultSet rs = p.executeQuery()) { rs.next(); orgId = rs.getLong(1); }
        }
        konnektorId = bv.konnektorAnlegen(orgId, "Konnektor Liste", "192.0.2.10:443",
                null, "M", "CS", "AP", null, null, null, ALLES).id();

        // Absichtlich gemischt: verschiedene Modelle, Firmware-Staende und
        // Bezeichnungen, die alphabetisch anders liegen als die Kennungen.
        anlegen("KT-L-01", "Zebra Empfang",  "CHERRY ST-1506",     "4.2.1", true);
        anlegen("KT-L-02", "Alpha Labor",    "CHERRY ST-1506",     "4.3.0", true);
        anlegen("KT-L-03", "Mitte Behandlung", "Ingenico ORGA 6141", "3.7.4", false);
        anlegen("KT-L-04", "Beta Archiv",    "Ingenico ORGA 6141", "3.7.4", true);
    }

    private static void anlegen(String id, String bez, String modell, String firmware, boolean smcb) {
        var e = bv.terminalAnlegen(id, konnektorId, bez, "Flur", "192.0.2.50", modell,
                null, null, null, smcb, false, ALLES);
        assertTrue(e.geklappt(), () -> String.valueOf(e.fehler()));
        try (PreparedStatement p = db.verbindung().prepareStatement(
                "UPDATE kartenterminal SET firmware = ? WHERE id = ?")) {
            p.setString(1, firmware);
            p.setLong(2, e.id());
            p.executeUpdate();
        } catch (SQLException ex) { fail(ex); }
    }

    @AfterAll
    static void abbauen() throws Exception {
        if (db != null) { aufraeumen(); db.close(); }
    }

    private static void aufraeumen() throws SQLException {
        try (Statement s = db.verbindung().createStatement()) {
            s.executeUpdate("""
                    DELETE FROM kartenterminal WHERE konnektor_id IN (SELECT id FROM konnektor
                      WHERE organisation_id IN (SELECT id FROM organisation
                        WHERE bezeichnung LIKE '%Liste'))""");
            s.executeUpdate("""
                    DELETE FROM konnektor WHERE organisation_id IN (SELECT id FROM organisation
                      WHERE bezeichnung LIKE '%Liste')""");
            s.executeUpdate("DELETE FROM geraetegruppe WHERE bezeichnung LIKE 'Testgruppe%'");
            s.executeUpdate("DELETE FROM organisation WHERE bezeichnung = 'Testeinrichtung Liste'");
            s.executeUpdate("DELETE FROM organisation WHERE bezeichnung = 'Testverbund Liste'");
        }
    }

    private Bestandsverwaltung.Filter nurUnsere() {
        return new Bestandsverwaltung.Filter(null, konnektorId, null, null, null, null, null);
    }

    private List<String> bezeichnungen(Bestandsverwaltung.Sortierung s, boolean ab) {
        return bv.terminals(nurUnsere(), s, ab, 1, 50, ALLES).eintraege().stream()
                .map(Bestandsverwaltung.Terminal::bezeichnung).toList();
    }

    // ------------------------------------------------------------- Sortierung

    @Test @Order(1)
    @DisplayName("Sortierung nach Bezeichnung, auf- und absteigend")
    void sortierungBezeichnung() {
        assertEquals(List.of("Alpha Labor", "Beta Archiv", "Mitte Behandlung", "Zebra Empfang"),
                bezeichnungen(Bestandsverwaltung.Sortierung.BEZEICHNUNG, false));
        assertEquals(List.of("Zebra Empfang", "Mitte Behandlung", "Beta Archiv", "Alpha Labor"),
                bezeichnungen(Bestandsverwaltung.Sortierung.BEZEICHNUNG, true));
    }

    @Test @Order(2)
    @DisplayName("Sortierung nach Kennung ist unabhängig von der Bezeichnung")
    void sortierungKennung() {
        var ids = bv.terminals(nurUnsere(), Bestandsverwaltung.Sortierung.KENNUNG, false, 1, 50, ALLES)
                .eintraege().stream().map(Bestandsverwaltung.Terminal::terminalId).toList();
        assertEquals(List.of("KT-L-01", "KT-L-02", "KT-L-03", "KT-L-04"), ids);
    }

    @Test @Order(3)
    @DisplayName("Sortierung nach Modell und Firmware")
    void sortierungModellUndFirmware() {
        var modelle = bv.terminals(nurUnsere(), Bestandsverwaltung.Sortierung.MODELL, false, 1, 50, ALLES)
                .eintraege().stream().map(Bestandsverwaltung.Terminal::modell).toList();
        assertEquals("CHERRY ST-1506", modelle.get(0));
        assertEquals("Ingenico ORGA 6141", modelle.get(3));

        var fw = bv.terminals(nurUnsere(), Bestandsverwaltung.Sortierung.FIRMWARE, false, 1, 50, ALLES)
                .eintraege().stream().map(Bestandsverwaltung.Terminal::firmware).toList();
        assertEquals("3.7.4", fw.get(0));
        assertEquals("4.3.0", fw.get(3));
    }

    @Test @Order(4)
    @DisplayName("Ein unbekanntes Sortierfeld führt zur Vorgabe statt zu einem Fehler")
    void unbekannteSortierung() {
        // Der Wert kommt aus der URL und geht unmittelbar in die Abfrage ein -
        // ohne Weissliste waere das eine SQL-Einschleusung.
        assertEquals(Bestandsverwaltung.Sortierung.BEZEICHNUNG,
                Bestandsverwaltung.Sortierung.aus("gibtesnicht"));
        assertEquals(Bestandsverwaltung.Sortierung.BEZEICHNUNG,
                Bestandsverwaltung.Sortierung.aus(null));
        assertEquals(Bestandsverwaltung.Sortierung.BEZEICHNUNG,
                Bestandsverwaltung.Sortierung.aus("bezeichnung; DROP TABLE kartenterminal"));
        assertEquals(Bestandsverwaltung.Sortierung.MODELL,
                Bestandsverwaltung.Sortierung.aus("MODELL"), "Groß- und Kleinschreibung egal");
    }

    @Test @Order(5)
    @DisplayName("Auch beim Sortieren erscheint keine Zeile auf zwei Seiten")
    void keineUeberschneidungBeimSortieren() {
        // Zwei Geraete haben dieselbe Firmware. Ohne zweites Sortierfeld waere
        // ihre Reihenfolge zufaellig - und sie koennten zwischen den Seiten
        // wandern.
        var seite1 = bv.terminals(nurUnsere(), Bestandsverwaltung.Sortierung.FIRMWARE, false, 1, 2, ALLES);
        var seite2 = bv.terminals(nurUnsere(), Bestandsverwaltung.Sortierung.FIRMWARE, false, 2, 2, ALLES);
        var ids1 = seite1.eintraege().stream().map(Bestandsverwaltung.Terminal::id).toList();
        var ids2 = seite2.eintraege().stream().map(Bestandsverwaltung.Terminal::id).toList();
        assertEquals(2, ids1.size());
        assertEquals(2, ids2.size());
        assertTrue(java.util.Collections.disjoint(ids1, ids2),
                "keine Überschneidung zwischen Seite 1 und 2");
    }

    // ----------------------------------------------------------------- Filter

    @Test @Order(10)
    @DisplayName("Filter nach Modell und Firmware")
    void filterModellFirmware() {
        var nachModell = bv.terminals(new Bestandsverwaltung.Filter(null, konnektorId, null, null,
                "Ingenico ORGA 6141", null, null), null, false, 1, 50, ALLES);
        assertEquals(2, nachModell.gesamt());

        var nachFirmware = bv.terminals(new Bestandsverwaltung.Filter(null, konnektorId, null, null,
                null, "3.7.4", null), null, false, 1, 50, ALLES);
        assertEquals(2, nachFirmware.gesamt());

        var beides = bv.terminals(new Bestandsverwaltung.Filter(null, konnektorId, null, null,
                "CHERRY ST-1506", "3.7.4", null), null, false, 1, 50, ALLES);
        assertEquals(0, beides.gesamt(), "Filter wirken zusammen, nicht alternativ");
    }

    @Test @Order(11)
    @DisplayName("Filter nach SMC-B und Status")
    void filterSmcbUndStatus() {
        var mitSmcb = bv.terminals(new Bestandsverwaltung.Filter(null, konnektorId, null, null,
                null, null, true), null, false, 1, 50, ALLES);
        assertEquals(3, mitSmcb.gesamt());

        assertEquals(4, bv.terminals(new Bestandsverwaltung.Filter(null, konnektorId, true, null,
                null, null, null), null, false, 1, 50, ALLES).gesamt(), "alle sind zunächst aktiv");
    }

    @Test @Order(12)
    @DisplayName("„Nicht erreichbar“ sammelt keine ungeprüften Geräte ein")
    void ungeprueftIstNichtUnerreichbar() {
        // Ein nie geprueftes Geraet ist unbekannt, nicht ausgefallen. Wuerde der
        // Filter es einsammeln, suchte der Betreiber Fehler, wo keine sind.
        var unerreichbar = bv.terminals(new Bestandsverwaltung.Filter(null, konnektorId, null,
                false, null, null, null), null, false, 1, 50, ALLES);
        assertEquals(0, unerreichbar.gesamt(), "noch wurde nichts geprüft");

        var erreichbar = bv.terminals(new Bestandsverwaltung.Filter(null, konnektorId, null,
                true, null, null, null), null, false, 1, 50, ALLES);
        assertEquals(0, erreichbar.gesamt());
    }

    @Test @Order(13)
    @DisplayName("Die Filterauswahl bietet nur Werte an, die tatsächlich vorkommen")
    void vorhandeneWerte() {
        assertTrue(bv.vorhandeneWerte("modell", ALLES).contains("CHERRY ST-1506"));
        assertTrue(bv.vorhandeneWerte("firmware", ALLES).contains("4.3.0"));
        // Auch hier eine Weissliste - der Spaltenname geht in die Abfrage ein.
        assertTrue(bv.vorhandeneWerte("bezeichnung; DROP TABLE kartenterminal", ALLES).isEmpty());
        assertTrue(bv.vorhandeneWerte("adresse", ALLES).isEmpty(), "nicht freigegebene Spalte");
    }

    // ---------------------------------------------------------- Massenaktionen

    @Test @Order(20)
    @DisplayName("Mehrere Geräte lassen sich auf einmal deaktivieren")
    void massenDeaktivieren() {
        var ids = bv.terminals(nurUnsere(), null, false, 1, 50, ALLES).eintraege().stream()
                .map(Bestandsverwaltung.Terminal::id).toList();
        var e = bv.massenAktivSetzen(ids, false, ALLES);
        assertEquals(4, e.geaendert());
        assertTrue(e.vollstaendig());

        assertEquals(4, bv.terminals(new Bestandsverwaltung.Filter(null, konnektorId, false, null,
                null, null, null), null, false, 1, 50, ALLES).gesamt());

        bv.massenAktivSetzen(ids, true, ALLES);
    }

    @Test @Order(21)
    @DisplayName("Eine leere Auswahl ändert nichts und sagt das auch")
    void leereAuswahl() {
        var e = bv.massenAktivSetzen(List.of(), false, ALLES);
        assertEquals(0, e.geaendert());
        assertFalse(e.hinweise().isEmpty(), "der Benutzer muss erfahren, warum nichts geschah");
        assertTrue(e.hinweise().get(0).contains("kein Gerät"));
    }

    @Test @Order(22)
    @DisplayName("Verschwundene Geräte werden gezählt, nicht verschwiegen")
    void verschwundeneWerdenGezaehlt() {
        var ids = bv.terminals(nurUnsere(), null, false, 1, 50, ALLES).eintraege().stream()
                .map(Bestandsverwaltung.Terminal::id).toList();
        var mitGeist = new java.util.ArrayList<>(ids);
        mitGeist.add(999_999_999L);

        var e = bv.massenAktivSetzen(mitGeist, true, ALLES);
        assertEquals(4, e.geaendert());
        assertEquals(1, e.uebersprungen());
        assertFalse(e.vollstaendig(), "„4 von 5“ darf nicht als voller Erfolg gemeldet werden");
        assertFalse(e.hinweise().isEmpty());
    }

    @Test @Order(23)
    @DisplayName("Eine Gruppe nimmt nur Geräte ihrer eigenen Einrichtung auf")
    void gruppeBleibtInIhrerEinrichtung() {
        // FA-GER-050: Eine Gruppe liegt innerhalb genau einer Einrichtung.
        // Waere das ueber eine Massenaktion aushebelbar, waere die
        // Mandantentrennung offen.
        assertNull(gv.anlegen("Testgruppe Liste", "Testeinrichtung Liste", 60));
        long gruppeId = gv.alle("Testeinrichtung Liste").get(0).id();

        var ids = bv.terminals(nurUnsere(), null, false, 1, 50, ALLES).eintraege().stream()
                .map(Bestandsverwaltung.Terminal::id).toList();
        var e = bv.massenGruppeZuweisen(ids, gruppeId, ALLES);
        assertEquals(4, e.geaendert(), () -> "Hinweise: " + e.hinweise());
        assertTrue(e.vollstaendig());

        assertNull(gv.anlegen("Testgruppe Fremd", "Ganz andere Einrichtung", 60));
        long fremdeGruppe = gv.alle("Ganz andere Einrichtung").get(0).id();
        var f = bv.massenGruppeZuweisen(ids, fremdeGruppe, ALLES);
        assertEquals(0, f.geaendert(), "kein Gerät darf in eine fremde Einrichtung wandern");
        assertFalse(f.hinweise().isEmpty());
        assertTrue(f.hinweise().get(0).contains("anderen Einrichtung"), f.hinweise().toString());
    }

    @Test @Order(24)
    @DisplayName("Eine gelöschte Gruppe wird gemeldet, nicht ignoriert")
    void geloeschteGruppe() {
        var e = bv.massenGruppeZuweisen(List.of(1L), 999_999_999L, ALLES);
        assertEquals(0, e.geaendert());
        assertTrue(e.hinweise().get(0).contains("gibt es nicht mehr"), e.hinweise().toString());
    }
}
