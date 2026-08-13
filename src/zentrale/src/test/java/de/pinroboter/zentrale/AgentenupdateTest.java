package de.pinroboter.zentrale;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Gezielte Aktualisierung eines Standort-Agenten (FA-AGT-100, PO 13.08.2026).
 *
 * <h2>Was hier wirklich geprüft wird</h2>
 *
 * Der Kern ist <b>nicht</b>, dass ein Auftrag gespeichert wird, sondern dass er
 * sich <b>schließt</b> — und zwar an der richtigen Tatsache. Der Agent
 * quittiert, bevor er neu startet; danach kann er nichts mehr sagen. Ob es
 * geklappt hat, entscheidet deshalb die Version, mit der er wiederkommt.
 *
 * <p>Ein Auftrag, der nie zu einem Ergebnis kommt, sieht in der Oberfläche aus
 * wie Fortschritt. Das ist der Zustand, den diese Prüfungen ausschließen.
 */
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class AgentenupdateTest {

    private static Datenbank db;
    private static Agentenaktualisierung au;
    private static long agentId;

    @BeforeEach
    void aufbauen() throws Exception {
        if (db == null) {
            db = new Datenbank(System.getenv("TEST_DB_URL"),
                    System.getenv().getOrDefault("TEST_DB_USER", "pinroboter"),
                    System.getenv().getOrDefault("TEST_DB_PASSWORD", "test"));
            au = new Agentenaktualisierung(db, new Fachprotokoll(db, new Speicherwaechter(db, Long.MAX_VALUE)));
        }
        aufraeumen();
        agentId = agent("AU Agent", "0.3.0");
    }

    @AfterAll
    static void abraeumen() throws Exception { if (db != null) aufraeumen(); }

    @Test
    @DisplayName("Ein Auftrag wird angelegt und ist für den Agenten abholbar")
    void anfordern() {
        assertNull(au.anfordern(agentId, "0.4.0", "pruefer", "AU Praxis"));
        var a = au.offener(agentId);
        assertNotNull(a);
        assertEquals("0.4.0", a.zielversion());
        assertEquals("0.3.0", a.vonVersion(), "Woher der Agent kam, gehört zum Vorgang");
        assertEquals(Agentenaktualisierung.Status.ANGEFORDERT, a.status());
        assertEquals("pruefer", a.angefordertVon());
    }

    @Test
    @DisplayName("Auf die laufende Version wird nicht aktualisiert")
    void schonDrauf() {
        String f = au.anfordern(agentId, "0.3.0", "pruefer", "AU Praxis");
        assertNotNull(f);
        assertTrue(f.contains("bereits"), f);
        assertNull(au.offener(agentId));
    }

    @Test
    @DisplayName("Zwei gleichzeitige Aufträge sind ausgeschlossen")
    void nurEinerGleichzeitig() {
        // Zwei Anstöße hießen zwei Neustarts, und keiner wüsste, welcher
        // gewirkt hat.
        assertNull(au.anfordern(agentId, "0.4.0", "pruefer", "AU Praxis"));
        String f = au.anfordern(agentId, "0.5.0", "pruefer", "AU Praxis");
        assertNotNull(f);
        assertTrue(f.contains("läuft bereits"), f);
        assertEquals("0.4.0", au.offener(agentId).zielversion(), "Der erste bleibt stehen");
    }

    @Test
    @DisplayName("Erfolg entscheidet die Version, mit der der Agent wiederkommt")
    void erfolgUeberDieWirkung() {
        au.anfordern(agentId, "0.4.0", "pruefer", "AU Praxis");
        au.angestossen(agentId, true, "angestoßen");
        assertEquals(Agentenaktualisierung.Status.ANGESTOSSEN, au.offener(agentId).status());

        // Noch die alte Version: nichts entschieden, der Auftrag bleibt offen.
        assertNull(au.abschlussPruefen(agentId, "0.3.0"));
        assertNotNull(au.offener(agentId));

        // Mit der Zielversion wieder da — das ist die Verifikation.
        String meldung = au.abschlussPruefen(agentId, "0.4.0");
        assertNotNull(meldung);
        assertTrue(meldung.contains("0.4.0"), meldung);
        assertNull(au.offener(agentId), "Der Auftrag ist erledigt");
        assertEquals(Agentenaktualisierung.Status.ERFOLGREICH, au.letzter(agentId).status());
    }

    @Test
    @DisplayName("Ein Agent, der mit der alten Version wiederkommt, gilt nach der Frist als gescheitert")
    void fristLaeuftAb() throws Exception {
        au.anfordern(agentId, "0.4.0", "pruefer", "AU Praxis");
        au.angestossen(agentId, true, null);
        zurueckdatieren(Agentenaktualisierung.FRIST.plusMinutes(1));

        String meldung = au.abschlussPruefen(agentId, "0.3.0");
        assertNotNull(meldung);
        assertTrue(meldung.contains("fehlgeschlagen"), meldung);

        var f = au.letzter(agentId);
        assertEquals(Agentenaktualisierung.Status.FEHLGESCHLAGEN, f.status());
        assertTrue(f.meldung().contains("logs aktualisierung"),
                "Die Meldung muss sagen, was zu tun ist: " + f.meldung());
        assertTrue(f.meldung().contains("läuft unverändert weiter"),
                "Wichtigste Beruhigung: Der Standort ist nicht ausgefallen");
    }

    @Test
    @DisplayName("Ein Agent, der gar nicht wiederkommt, läuft ebenfalls ab")
    void ohneRueckmeldung() throws Exception {
        // Der schwerere Fall: Er meldet nichts, an das sich eine Prüfung
        // hängen könnte. Ohne eigenen Takt bliebe der Auftrag ewig auf „läuft“.
        au.anfordern(agentId, "0.4.0", "pruefer", "AU Praxis");
        au.angestossen(agentId, true, null);
        assertTrue(au.fristenPruefen().isEmpty(), "Vor der Frist passiert nichts");

        zurueckdatieren(Agentenaktualisierung.FRIST.plusMinutes(1));
        var abgelaufen = au.fristenPruefen();
        assertEquals(1, abgelaufen.size());
        assertEquals(Agentenaktualisierung.Status.FEHLGESCHLAGEN, au.letzter(agentId).status());
        assertTrue(au.letzter(agentId).meldung().contains("nicht wieder gemeldet"),
                au.letzter(agentId).meldung());

        assertTrue(au.fristenPruefen().isEmpty(), "Ein zweiter Lauf meldet nichts nach");
    }

    @Test
    @DisplayName("Ein Anstoß, der schon am Standort scheitert, endet sofort")
    void anstossScheitert() {
        // Etwa wenn die Aktualisierungsdienste aus dem Paket entfernt wurden.
        // Zehn Minuten „läuft“ anzuzeigen wäre gelogen.
        au.anfordern(agentId, "0.4.0", "pruefer", "AU Praxis");
        au.angestossen(agentId, false, "Kein Aktualisierungsdienst auf diesem Rechner.");
        assertNull(au.offener(agentId));
        var f = au.letzter(agentId);
        assertEquals(Agentenaktualisierung.Status.FEHLGESCHLAGEN, f.status());
        assertEquals("Kein Aktualisierungsdienst auf diesem Rechner.", f.meldung());
    }

    @Test
    @DisplayName("Ein Auftrag lässt sich abbrechen")
    void abbrechen() {
        au.anfordern(agentId, "0.4.0", "pruefer", "AU Praxis");
        assertNull(au.abbrechen(agentId, "pruefer", "AU Praxis"));
        assertNull(au.offener(agentId));
        assertEquals(Agentenaktualisierung.Status.ABGEBROCHEN, au.letzter(agentId).status());

        assertNotNull(au.abbrechen(agentId, "pruefer", "AU Praxis"),
                "Ein zweiter Abbruch hat nichts zum Abbrechen");
    }

    @Test
    @DisplayName("Nach einem Abbruch ist ein neuer Anlauf möglich")
    void neuerAnlauf() {
        au.anfordern(agentId, "0.4.0", "pruefer", "AU Praxis");
        au.abbrechen(agentId, "pruefer", "AU Praxis");
        assertNull(au.anfordern(agentId, "0.4.0", "pruefer", "AU Praxis"),
                "Sonst bliebe ein Standort nach einem Fehlversuch dauerhaft blockiert");
        assertNotNull(au.offener(agentId));
    }

    @Test
    @DisplayName("Der Verlauf bleibt erhalten — auch der gescheiterte Versuch")
    void verlauf() {
        // „Wurde das je versucht?" ist die erste Frage bei einem Agenten, der
        // zurückhängt.
        au.anfordern(agentId, "0.4.0", "pruefer", "AU Praxis");
        au.abbrechen(agentId, "pruefer", "AU Praxis");
        au.anfordern(agentId, "0.5.0", "pruefer", "AU Praxis");
        au.angestossen(agentId, true, null);
        au.abschlussPruefen(agentId, "0.5.0");

        var v = au.verlauf(agentId, 10);
        assertEquals(2, v.size());
        assertEquals("0.5.0", v.get(0).zielversion(), "Neueste zuerst");
        assertEquals(Agentenaktualisierung.Status.ERFOLGREICH, v.get(0).status());
        assertEquals(Agentenaktualisierung.Status.ABGEBROCHEN, v.get(1).status());
    }

    // ------------------------------------------------------------- Kleinteile

    private static void zurueckdatieren(java.time.Duration um) throws SQLException {
        try (PreparedStatement p = db.verbindung().prepareStatement(
                "UPDATE agent_aktualisierung SET angefordert_am = now() - (? || ' seconds')::interval "
                + "WHERE agent_id = ?")) {
            p.setString(1, String.valueOf(um.toSeconds()));
            p.setLong(2, agentId);
            p.executeUpdate();
        }
    }

    private static long agent(String bezeichnung, String version) throws SQLException {
        try (PreparedStatement p = db.verbindung().prepareStatement("""
                INSERT INTO agent (bezeichnung, einrichtung, token_hash, aktiv, bausteinversion)
                VALUES (?,?,?,TRUE,?) RETURNING id""")) {
            p.setString(1, bezeichnung);
            p.setString(2, "AU Praxis");
            p.setString(3, "au-" + bezeichnung);
            p.setString(4, version);
            try (ResultSet rs = p.executeQuery()) { rs.next(); return rs.getLong(1); }
        }
    }

    private static void aufraeumen() throws SQLException {
        try (Statement s = db.verbindung().createStatement()) {
            // Die Aufträge gehen per CASCADE mit. Das Fachprotokoll bleibt
            // stehen: Es ist per Trigger unveränderlich (ADR-0005), und genau
            // das soll es sein — auch für Prüfläufe.
            s.executeUpdate("DELETE FROM agent WHERE bezeichnung LIKE 'AU %'");
        }
    }
}
