package de.pinroboter.zentrale;

import de.pinroboter.gemeinsam.Geheimnisspeicher;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Bestandsermittlung und Abgleich (FA-GER-030, FA-GER-035).
 *
 * <p>Der wichtigste Test dieser Klasse ist {@link #nichtsWirdSelbsttaetigGeaendert()}.
 * Er prüft die Zusage, an der alles andere hängt: Ein Konnektor darf den
 * gepflegten Bestand nicht überschreiben.
 */
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ErmittlungTest {

    private static Datenbank db;
    private static Ermittlung erm;
    private static Bestandsverwaltung bv;
    private static long agentId;
    private static long orgId;
    private static long konnektorId;

    @BeforeAll
    static void aufbauen() throws Exception {
        db = new Datenbank(System.getenv("TEST_DB_URL"),
                System.getenv().getOrDefault("TEST_DB_USER", "pinroboter"),
                System.getenv().getOrDefault("TEST_DB_PASSWORD", "test"));
        erm = new Ermittlung(db);
        bv = new Bestandsverwaltung(db,
                Geheimnisspeicher.ausBase64(Geheimnisspeicher.neuerSchluesselBase64()));
        aufraeumen();

        long verbundId;
        try (Statement s = db.verbindung().createStatement();
             ResultSet rs = s.executeQuery("""
                     INSERT INTO organisation (bezeichnung, art, ist_demo)
                     VALUES ('Testverbund Ermittlung', 'VERBUND', FALSE) RETURNING id""")) {
            rs.next(); verbundId = rs.getLong(1);
        }
        try (PreparedStatement p = db.verbindung().prepareStatement("""
                INSERT INTO organisation (bezeichnung, art, eltern_id, ist_demo)
                VALUES ('Testeinrichtung Ermittlung', 'EINRICHTUNG', ?, FALSE) RETURNING id""")) {
            p.setLong(1, verbundId);
            try (ResultSet rs = p.executeQuery()) { rs.next(); orgId = rs.getLong(1); }
        }
        try (Statement s = db.verbindung().createStatement();
             ResultSet rs = s.executeQuery("""
                     INSERT INTO agent (bezeichnung, einrichtung, token_hash, angelegt)
                     VALUES ('Testagent Ermittlung', 'Testeinrichtung Ermittlung', 'x', now())
                     RETURNING id""")) {
            rs.next(); agentId = rs.getLong(1);
        }
        konnektorId = bv.konnektorAnlegen(orgId, "Konnektor Ermittlung", "192.0.2.10:443",
                null, "M", "CS", "AP", null, null, null).id();
    }

    @AfterAll
    static void abbauen() throws Exception {
        if (db != null) { aufraeumen(); db.close(); }
    }

    private static void aufraeumen() throws SQLException {
        try (Statement s = db.verbindung().createStatement()) {
            s.executeUpdate("DELETE FROM ermittelt_terminal");
            s.executeUpdate("DELETE FROM ermitteltes_modell");
            s.executeUpdate("""
                    DELETE FROM kartenterminal WHERE konnektor_id IN (SELECT id FROM konnektor
                      WHERE organisation_id IN (SELECT id FROM organisation
                        WHERE bezeichnung LIKE '%Ermittlung'))""");
            s.executeUpdate("""
                    DELETE FROM konnektor WHERE organisation_id IN (SELECT id FROM organisation
                      WHERE bezeichnung LIKE '%Ermittlung')""");
            s.executeUpdate("DELETE FROM agent WHERE bezeichnung = 'Testagent Ermittlung'");
            s.executeUpdate("DELETE FROM organisation WHERE bezeichnung = 'Testeinrichtung Ermittlung'");
            s.executeUpdate("DELETE FROM organisation WHERE bezeichnung = 'Testverbund Ermittlung'");
        }
    }

    private static Map<String, Object> gemeldet(String id, String modell, boolean smcb, boolean pevim) {
        var m = new LinkedHashMap<String, Object>();
        m.put("terminalId", id);
        m.put("name", "Gerät " + id);
        m.put("modell", modell);
        m.put("firmware", "4.2.1");
        m.put("erreichbar", true);
        m.put("pevimUnterstuetzt", true);
        m.put("pevimAktiv", pevim);
        m.put("hatSmcb", smcb);
        if (smcb) { m.put("kartenhandle", "card-" + id); m.put("iccsn", "8027600101169990" + id.length()); }
        return m;
    }

    @Test @Order(1)
    @DisplayName("Eine Meldung des Agenten wird aufgenommen")
    void aufnehmen() {
        int n = erm.aufnehmen(agentId, List.of(
                gemeldet("KT-A", "CHERRY ST-1506", true, true),
                gemeldet("KT-B", "CHERRY ST-1506", false, false),
                gemeldet("KT-C", "Ingenico ORGA 6141", true, false)));
        assertEquals(3, n);
        assertEquals(3, erm.neueAnzahl(), "noch ist keines erfasst");
    }

    @Test @Order(2)
    @DisplayName("Eine zweite Meldung legt nichts doppelt an")
    void zweiteMeldungIstIdempotent() {
        erm.aufnehmen(agentId, List.of(gemeldet("KT-A", "CHERRY ST-1506", true, true)));
        assertEquals(3, erm.abgleich().size(), "keine Dubletten");
    }

    @Test @Order(3)
    @DisplayName("Ohne Erfassung ist jeder Fund „neu erkannt“")
    void alleNeu() {
        var funde = erm.abgleich();
        assertEquals(3, funde.size());
        assertTrue(funde.stream().allMatch(f -> f.lage() == Ermittlung.Lage.NEU),
                "alle Funde müssen als neu gelten");
        assertTrue(funde.stream().allMatch(f -> f.bestandsId() == null));
    }

    @Test @Order(4)
    @DisplayName("Übernahme trägt die Angaben des Konnektors in den Bestand")
    void uebernehmen() {
        var fund = erm.abgleich().stream().filter(f -> f.terminalId().equals("KT-A")).findFirst().orElseThrow();
        var e = bv.uebernehmen(fund, konnektorId, "192.0.2.51:443");
        assertTrue(e.geklappt(), () -> String.valueOf(e.fehler()));
        erm.alsUebernommenMarkieren(fund.id());

        var t = bv.terminal(e.id()).orElseThrow();
        assertEquals("KT-A", t.terminalId());
        assertEquals("CHERRY ST-1506", t.modell());
        assertEquals("4.2.1", t.firmware());
        assertTrue(t.hatSmcb());
        assertTrue(t.pevimAktiv());
        assertEquals(orgId, t.organisationId(), "die Einrichtung kommt vom Konnektor");
        assertEquals(2, erm.neueAnzahl());
    }

    @Test @Order(5)
    @DisplayName("Dasselbe Gerät lässt sich nicht zweimal übernehmen")
    void keineDoppelteUebernahme() {
        var fund = erm.abgleich().stream().filter(f -> f.terminalId().equals("KT-A")).findFirst().orElseThrow();
        assertEquals(Ermittlung.Lage.BEKANNT, fund.lage(), "jetzt ist es erfasst");
        var e = bv.uebernehmen(fund, konnektorId, null);
        assertFalse(e.geklappt());
        assertTrue(e.fehler().contains("bereits erfasst"), e.fehler());
    }

    @Test @Order(6)
    @DisplayName("PIN-Automatik wird nicht übernommen, wenn keine SMC-B steckt (A_24080)")
    void pevimNurMitSmcb() {
        // Der Konnektor darf melden, was er will - die Regel setzen wir durch.
        erm.aufnehmen(agentId, List.of(gemeldet("KT-D", "CHERRY ST-1506", false, true)));
        var fund = erm.abgleich().stream().filter(f -> f.terminalId().equals("KT-D")).findFirst().orElseThrow();
        assertTrue(fund.pevimAktiv(), "der Konnektor meldet PEviM aktiv");
        assertFalse(fund.hatSmcb(), "aber ohne SMC-B");

        var e = bv.uebernehmen(fund, konnektorId, null);
        assertTrue(e.geklappt());
        var t = bv.terminal(e.id()).orElseThrow();
        assertFalse(t.pevimAktiv(), "im Bestand darf die Automatik nicht aktiv sein");
    }

    @Test @Order(7)
    @DisplayName("Eine Abweichung wird erkannt und benannt")
    void abweichungWirdErkannt() {
        // Am Geraet wurde PEviM abgeschaltet - der Bestand sagt noch "aktiv".
        erm.aufnehmen(agentId, List.of(gemeldet("KT-A", "CHERRY ST-1506", true, false)));
        var fund = erm.abgleich().stream().filter(f -> f.terminalId().equals("KT-A")).findFirst().orElseThrow();

        assertEquals(Ermittlung.Lage.ABWEICHEND, fund.lage());
        assertFalse(fund.abweichungen().isEmpty());
        assertTrue(fund.abweichungen().stream().anyMatch(a -> a.contains("PIN-Automatik")),
                "die Abweichung muss benannt sein: " + fund.abweichungen());
    }

    @Test @Order(8)
    @DisplayName("Eine Abweichung ändert den Bestand NICHT selbsttätig")
    void nichtsWirdSelbsttaetigGeaendert() {
        // Die Kernzusage von FA-GER-030 und FA-GER-035. Wuerde der Bestand
        // nachgezogen, koennte ein zurueckgesetzter Konnektor Zugangsdaten,
        // Gruppenzuordnungen und Standortangaben vernichten.
        var t = bv.terminals("KT-A", null, false, 1, 10).eintraege().get(0);
        assertTrue(t.pevimAktiv(), "der erfasste Wert muss unverändert bleiben");
        assertTrue(t.hatSmcb());
        assertEquals("192.0.2.51:443", t.adresse(), "auch die Adresse bleibt stehen");
    }

    @Test @Order(9)
    @DisplayName("Ein nicht mehr gemeldetes Gerät wird angezeigt, nicht gelöscht")
    void verschwundenesGeraet() throws Exception {
        // Der Konnektor meldet KT-A nicht mehr - abgebaut oder ausgefallen.
        try (Statement s = db.verbindung().createStatement()) {
            s.executeUpdate("DELETE FROM ermittelt_terminal WHERE terminal_id = 'KT-A'");
        }
        var funde = erm.abgleich();
        var weg = funde.stream().filter(f -> "KT-A".equals(f.terminalId())).findFirst();
        assertTrue(weg.isPresent(), "das Gerät muss sichtbar bleiben");
        assertEquals(Ermittlung.Lage.VERSCHWUNDEN, weg.get().lage());

        assertEquals(1, bv.terminals("KT-A", null, false, 1, 10).gesamt(),
                "im Bestand darf es NICHT verschwunden sein");
    }

    @Test @Order(10)
    @DisplayName("Das Informationsmodell wird abgelegt und wiedergefunden")
    void informationsmodell() {
        String json = "{\"mandanten\":[{\"id\":\"Mandant-1\"}]}";
        erm.modellAblegen(agentId, true, json);
        var m = erm.modell(agentId).orElseThrow();
        assertTrue(m.verfuegbar());
        assertEquals(json, m.inhalt());
        assertNotNull(m.gesehen());
    }

    @Test @Order(12)
    @DisplayName("Ein zweiter Agent erzeugt keine Karteileichen")
    void zweiterAgentErzeugtKeineDubletten() throws Exception {
        // Gefunden beim Vollauf der Systemnachweise: Das Pruefskript startet
        // den Stack mehrfach, jeder Lauf registriert einen neuen Agenten - und
        // ploetzlich standen 12 Funde in der Liste statt 4. Im Betrieb
        // passiert dasselbe nach einer Neuinstallation des Agenten.
        long zweiterAgent;
        try (Statement s = db.verbindung().createStatement();
             ResultSet rs = s.executeQuery("""
                     INSERT INTO agent (bezeichnung, einrichtung, token_hash, angelegt)
                     VALUES ('Testagent Ermittlung', 'Testeinrichtung Ermittlung', 'y', now())
                     RETURNING id""")) {
            rs.next(); zweiterAgent = rs.getLong(1);
        }
        int vorher = erm.abgleich().size();
        erm.aufnehmen(zweiterAgent, List.of(gemeldet("KT-B", "CHERRY ST-1506", false, false)));

        assertEquals(vorher, erm.abgleich().size(),
                "dasselbe Gerät darf nicht doppelt erscheinen, nur weil ein zweiter Agent es meldet");
    }

    @Test @Order(13)
    @DisplayName("Funde eines abgemeldeten Agenten verschwinden aus der Liste")
    void abgemeldeterAgentVerschwindet() throws Exception {
        int vorher = erm.abgleich().size();
        assertTrue(vorher > 0);
        try (Statement s = db.verbindung().createStatement()) {
            s.executeUpdate("UPDATE agent SET aktiv = FALSE WHERE bezeichnung = 'Testagent Ermittlung'");
        }
        assertEquals(0, erm.abgleich().size(),
                "ohne aktiven Agenten gibt es keinen aktuellen Befund");
        assertEquals(0, erm.neueAnzahl());
        assertTrue(erm.juengstesModell().isEmpty(),
                "auch das Informationsmodell eines abgemeldeten Agenten zählt nicht mehr");
        try (Statement s = db.verbindung().createStatement()) {
            s.executeUpdate("UPDATE agent SET aktiv = TRUE WHERE bezeichnung = 'Testagent Ermittlung'");
        }
    }

    @Test @Order(14)
    @DisplayName("Der Aufrufkontext wird aus dem Informationsmodell zur Auswahl aufbereitet")
    void aufrufkontextAusModell() {
        // Genau die Struktur, die der Simulator liefert - einschliesslich der
        // verschachtelten Terminalliste je Arbeitsplatz, an der ein naiver
        // Zerleger scheitern wuerde.
        String json = """
                {"mandanten":[{"id":"Mandant-1","name":"Praxis Hauptstandort",\
                "clientsysteme":["CS-PVS","CS-ROBOTER"],"arbeitsplaetze":["AP-EMPFANG"]}],\
                "clientsysteme":[{"id":"CS-PVS","name":"Praxisverwaltungssystem"},\
                {"id":"CS-ROBOTER","name":"PIN-Roboter"}],\
                "arbeitsplaetze":[{"id":"AP-EMPFANG","name":"Empfang","terminals":["KT-A","KT-B"]},\
                {"id":"AP-LABOR","name":"Labor","terminals":[]},\
                {"id":"AP-VERWALTUNG","name":"Verwaltung","terminals":[]}]}""";
        erm.modellAblegen(agentId, true, json);

        var a = erm.aufrufkontextAuswahl();
        assertTrue(a.vorhanden());
        assertEquals(1, a.mandanten().size());
        assertEquals("Mandant-1", a.mandanten().get(0).id());
        assertEquals("Praxis Hauptstandort (Mandant-1)", a.mandanten().get(0).anzeige());

        assertEquals(2, a.clientsysteme().size());
        assertEquals(List.of("CS-PVS", "CS-ROBOTER"),
                a.clientsysteme().stream().map(Ermittlung.Eintrag::id).toList());

        assertEquals(3, a.arbeitsplaetze().size(),
                "die verschachtelte Terminalliste darf den Zerleger nicht verwirren");
        assertEquals(List.of("AP-EMPFANG", "AP-LABOR", "AP-VERWALTUNG"),
                a.arbeitsplaetze().stream().map(Ermittlung.Eintrag::id).toList());
    }

    @Test @Order(15)
    @DisplayName("Ohne Modell gibt es leere Listen — nie einen Fehler")
    void aufrufkontextOhneModell() {
        // NFA-QS-035: Faellt die herstellerspezifische Schnittstelle aus, muss
        // alles weiter von Hand erfassbar bleiben. Ein Fehler hier wuerde die
        // Konnektormaske unbenutzbar machen.
        erm.modellAblegen(agentId, false, null);
        assertFalse(erm.aufrufkontextAuswahl().vorhanden());

        erm.modellAblegen(agentId, true, "das ist kein JSON");
        assertFalse(erm.aufrufkontextAuswahl().vorhanden(), "unlesbares Modell = keine Auswahl");

        erm.modellAblegen(agentId, true, "{\"mandanten\":[]}");
        assertFalse(erm.aufrufkontextAuswahl().vorhanden());

        erm.modellAblegen(agentId, true, "{\"mandanten\":[{\"id\":\"M1\"}]}");
        var a = erm.aufrufkontextAuswahl();
        assertTrue(a.vorhanden());
        assertEquals("M1", a.mandanten().get(0).anzeige(), "ohne Namen zeigt die Kennung allein");
    }

    @Test @Order(11)
    @DisplayName("Fehlt die Webadmin-Schnittstelle, wird das vermerkt statt verschwiegen")
    void informationsmodellNichtVerfuegbar() {
        // NFA-QS-035: Die Oberflaeche muss auf die entfallende Unterstuetzung
        // hinweisen koennen - dazu muss der Unterschied zwischen "nie gelesen"
        // und "gelesen, nicht verfuegbar" in den Daten stehen.
        erm.modellAblegen(agentId, false, null);
        var m = erm.modell(agentId).orElseThrow();
        assertFalse(m.verfuegbar());
        assertNull(m.inhalt());
    }
}
