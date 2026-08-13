package de.pinroboter.zentrale;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Das Kennzeichen „PIN hinterlegt" — und die Frage, wer die Wahrheit kennt.
 *
 * <p>Bis zum 11.08.2026 leitete die Zentrale es allein aus <b>ihren eigenen
 * Aufträgen</b> ab. Eine PIN, die auf einem anderen Weg zum Agenten kam, war für
 * sie unsichtbar: Die Oberfläche zeigte „keine PIN hinterlegt", während der
 * Agent Karten freischaltete. Diese Tests halten fest, dass der <b>Agent</b> die
 * Wahrheit meldet — und dass sein Bestand nur ergänzt, nie löscht.
 */
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class PinverwaltungTest {

    private static Datenbank db;
    private Pinverwaltung pins;
    private long agentId;

    @BeforeAll
    static void verbinden() throws Exception {
        db = new Datenbank(System.getenv("TEST_DB_URL"),
                System.getenv().getOrDefault("TEST_DB_USER", "pinroboter"),
                System.getenv().getOrDefault("TEST_DB_PASSWORD", "test"));
    }

    @AfterAll
    static void schliessen() throws Exception {
        if (db != null) db.close();
    }

    @BeforeEach
    void aufsetzen() throws Exception {
        pins = new Pinverwaltung(db);
        String kennung = "pruef-" + Long.toHexString(System.nanoTime());
        try (PreparedStatement p = db.verbindung().prepareStatement(
                "INSERT INTO agent (bezeichnung, einrichtung, token_hash, aktiv) "
                + "VALUES (?,?,?,TRUE) RETURNING id")) {
            p.setString(1, kennung);
            p.setString(2, kennung);
            p.setString(3, kennung);
            try (ResultSet rs = p.executeQuery()) { rs.next(); agentId = rs.getLong(1); }
        }
    }

    @AfterEach
    void aufraeumen() throws Exception {
        try (PreparedStatement p = db.verbindung().prepareStatement(
                "DELETE FROM agent WHERE id = ?")) {
            p.setLong(1, agentId);
            p.executeUpdate();   // pin_hinterlegt haengt per ON DELETE CASCADE dran
        }
    }

    @Test
    @DisplayName("Der gemeldete Bestand des Agenten wird zum Kennzeichen")
    void bestandWirdUebernommen() {
        String handle = "karte-" + Long.toHexString(System.nanoTime());
        assertTrue(pins.hinterlegtFuer(handle).isEmpty(), "Vorbedingung");

        pins.bestandMelden(agentId, List.of(handle));

        assertTrue(pins.hinterlegtFuer(handle).isPresent(),
                "Was der Agent hat, muss die Oberflaeche zeigen — sonst widersprechen "
                + "sich Anzeige und Verhalten.");
    }

    @Test
    @DisplayName("Eine zweite Meldung verschiebt den Zeitpunkt nicht")
    void wiederholteMeldungIstFolgenlos() throws Exception {
        String handle = "karte-" + Long.toHexString(System.nanoTime());
        pins.bestandMelden(agentId, List.of(handle));
        Timestamp erst = seit(handle);

        pins.bestandMelden(agentId, List.of(handle));

        assertEquals(erst, seit(handle),
                "Der Agent meldet in jedem Durchgang. Wuerde jede Meldung den "
                + "Zeitpunkt erneuern, stuende in der Oberflaeche immer „seit "
                + "gerade eben“ — und die Warnung nach einem Agentwechsel "
                + "(FA-INS-040) griffe nie.");
    }

    @Test
    @DisplayName("Was der Agent nicht mehr meldet, verschwindet NICHT")
    void verschwundenesBleibtStehen() {
        String handle = "karte-" + Long.toHexString(System.nanoTime());
        pins.bestandMelden(agentId, List.of(handle));

        // Der Agent wurde neu aufgesetzt und hat die PIN nicht mehr.
        pins.bestandMelden(agentId, List.of());

        assertTrue(pins.hinterlegtFuer(handle).isPresent(),
                "Der Eintrag traegt die Warnung „PIN neu zu hinterlegen“. "
                + "Wuerde er hier verschwinden, verschwaende die Warnung zusammen "
                + "mit dem Problem, das sie meldet.");
    }

    @Test
    @DisplayName("Eine leere Meldung tut gar nichts")
    void leereMeldung() {
        assertDoesNotThrow(() -> pins.bestandMelden(agentId, List.of()));
    }

    @Test
    @DisplayName("Ein unveränderter Schlüssel erneuert den Meldezeitpunkt nicht")
    void gleicherSchluesselAendertNichts() throws Exception {
        String schluessel = "MIIBIjANBgkq-nachweis-" + Long.toHexString(System.nanoTime());
        pins.schluesselMerken(agentId, schluessel);
        Timestamp erst = schluesselGemeldet();
        assertNotNull(erst, "Der erste Schlüssel muss einen Zeitpunkt setzen");

        pins.schluesselMerken(agentId, schluessel);

        assertEquals(erst, schluesselGemeldet(),
                "Der Agent meldet seinen Schlüssel in JEDEM Durchgang. Würde der "
                + "Zeitpunkt jedes Mal erneuert, gälte binnen eines Prüfintervalls "
                + "jede hinterlegte PIN als fehlend (FA-INS-040) — ein Fehlalarm, "
                + "der die echte Warnung wertlos macht.");
    }

    @Test
    @DisplayName("Ein anderer Schlüssel setzt den Meldezeitpunkt neu")
    void neuerSchluesselZaehlt() throws Exception {
        pins.schluesselMerken(agentId, "schluessel-alt-" + Long.toHexString(System.nanoTime()));
        Timestamp erst = schluesselGemeldet();

        // Der Agent wurde neu aufgesetzt: anderes Schlüsselpaar, alte PINs
        // unlesbar. Genau das muss sichtbar werden.
        pins.schluesselMerken(agentId, "schluessel-neu-" + Long.toHexString(System.nanoTime()));

        assertTrue(schluesselGemeldet().after(erst),
                "Ein Schlüsselwechsel ist der Auslöser der Warnung — er muss "
                + "den Zeitpunkt fortschreiben.");
    }

    @Test
    @DisplayName("Nach einem Schlüsselwechsel gilt die PIN als neu zu hinterlegen")
    void warnungNachSchluesselwechsel() {
        String handle = "karte-" + Long.toHexString(System.nanoTime());
        pins.schluesselMerken(agentId, "alt-" + Long.toHexString(System.nanoTime()));
        pins.bestandMelden(agentId, List.of(handle));

        assertTrue(pins.neuZuHinterlegen().stream().noneMatch(h -> h.kartenhandle().equals(handle)),
                "Solange der Schlüssel derselbe ist, fehlt nichts");

        pins.schluesselMerken(agentId, "neu-" + Long.toHexString(System.nanoTime()));

        assertTrue(pins.neuZuHinterlegen().stream().anyMatch(h -> h.kartenhandle().equals(handle)),
                "Nach dem Wechsel kann der Agent die PIN nicht mehr lesen — das "
                + "muss die Praxis erfahren, bevor die erste Karte unverifiziert bleibt.");
    }

    @Test
    @DisplayName("Instanzweite Vorgänge landen bei KEINER Einrichtung — auch wenn eine Einrichtung so heißt")
    void instanzweitBleibtInstanzweit() throws Exception {
        // Der Befund vom 12.08.2026: Instanzweite Vorgänge trugen die
        // Einrichtung „Praxis". Das funktionierte nur, solange es keine
        // Organisationseinheit dieses Namens gab — ein Eintrag ohne passende
        // Organisation bekommt keine organisation_id und bleibt für
        // eingeschränkte Benutzer unsichtbar.
        //
        // Legt aber jemand eine Praxis mit diesem Namen an, kippen schlagartig
        // ALLE Instanzereignisse in ihren Geltungsbereich: Anmeldungen,
        // Sicherungen, Benutzerverwaltung. Eine Mandantengrenze, die von einem
        // frei wählbaren Namen abhängt, ist keine.
        long verbund, praxis;
        try (Statement st = db.verbindung().createStatement();
             ResultSet rs = st.executeQuery("""
                     INSERT INTO organisation (bezeichnung, art, ist_demo)
                     VALUES ('Verbund Instanztest', 'VERBUND', FALSE) RETURNING id""")) {
            rs.next(); verbund = rs.getLong(1);
        }
        try (PreparedStatement p = db.verbindung().prepareStatement("""
                INSERT INTO organisation (bezeichnung, art, eltern_id, ist_demo)
                VALUES ('Praxis', 'EINRICHTUNG', ?, FALSE) RETURNING id""")) {
            p.setLong(1, verbund);
            try (ResultSet rs = p.executeQuery()) { rs.next(); praxis = rs.getLong(1); }
        }
        try {
            var protokoll = new Fachprotokoll(db, null);
            var e = protokoll.anfuegen(Fachprotokoll.INSTANZ, null,
                    de.pinroboter.gemeinsam.Ereignisart.ANMELDUNG_ERFOLGREICH, true,
                    "Nachweis", null, "Anmeldung im Instanztest", null);

            Long org;
            try (PreparedStatement p = db.verbindung().prepareStatement(
                    "SELECT organisation_id FROM fachprotokoll WHERE id = ?")) {
                p.setLong(1, e.id());
                try (ResultSet rs = p.executeQuery()) {
                    rs.next();
                    long wert = rs.getLong(1);
                    org = rs.wasNull() ? null : wert;
                }
            }
            assertNull(org, "Ein instanzweiter Vorgang darf keiner Einrichtung zufallen — "
                    + "sonst sieht ein Praxisadministrator die Instanzverwaltung mit.");

            // Die Gegenprobe: Der Name ist als Organisationseinheit gesperrt.
            assertNotNull(Organisationsverwaltung.namePruefen(Fachprotokoll.INSTANZ),
                    "Der Name muss beim Anlegen abgewiesen werden — sonst ist die "
                    + "Kollision nur unwahrscheinlich statt ausgeschlossen");
        } finally {
            // Der Protokolleintrag bleibt stehen — das Fachprotokoll ist
            // unveränderlich (FA-PRO-010), und eine Datenbankregel erzwingt
            // das. Der erste Anlauf dieses Tests wollte ihn wegräumen und
            // scheiterte daran: Die Zusage gilt auch für Tests.
            try (Statement st = db.verbindung().createStatement()) {
                st.executeUpdate("DELETE FROM organisation WHERE id = " + praxis);
                st.executeUpdate("DELETE FROM organisation WHERE id = " + verbund);
            }
        }
    }

    @Test
    @DisplayName("Ein gerätebezogenes Ereignis steht unter der Einrichtung SEINES Geräts")
    void geraetSchlaegtAufrufer() throws Exception {
        // Der Befund vom 12.08.2026: Ein Agent ist für EINE Einrichtung
        // registriert, betreut in einem MVZ aber Geräte mehrerer
        // Betriebsstätten. Erreichbarkeit und Erkennung standen deshalb unter
        // der Einrichtung aus der Agentregistrierung — für den Zuständigen der
        // betroffenen Betriebsstätte unsichtbar.
        //
        // Die Regel greift zentral in Fachprotokoll.anfuegen, nicht an jeder
        // Aufrufstelle: Was von Hand richtig gemacht werden muss, wird
        // irgendwo vergessen.
        String terminal = "KT-REGELTEST-" + Long.toHexString(System.nanoTime());
        String eigene = "Einrichtung des Geräts " + Long.toHexString(System.nanoTime());
        // Ein Gerät braucht eine Gruppe (NOT NULL) — jedes Gerät gehört zu
        // genau einer, damit es ein Prüfintervall hat (FA-GER-050).
        long orgId, gruppeId;
        try (Statement st = db.verbindung().createStatement();
             ResultSet rs = st.executeQuery("""
                     INSERT INTO organisation (bezeichnung, art, ist_demo)
                     VALUES ('Verbund Regeltest', 'VERBUND', FALSE) RETURNING id""")) {
            rs.next(); orgId = rs.getLong(1);
        }
        try (PreparedStatement p = db.verbindung().prepareStatement("""
                INSERT INTO geraetegruppe (bezeichnung, einrichtung, ist_vorgabe,
                        pruefintervall_sekunden, organisation_id)
                VALUES ('Alle Geräte – Pinpruefung', ?, TRUE, 60, ?) RETURNING id""")) {
            p.setString(1, eigene);
            p.setLong(2, orgId);
            try (ResultSet rs = p.executeQuery()) { rs.next(); gruppeId = rs.getLong(1); }
        }
        try (PreparedStatement p = db.verbindung().prepareStatement(
                "INSERT INTO geraet (terminal_id, einrichtung, gruppe_id, bezeichnung) "
                + "VALUES (?,?,?,?)")) {
            p.setString(1, terminal);
            p.setString(2, eigene);
            p.setLong(3, gruppeId);
            p.setString(4, "Regeltest");
            p.executeUpdate();
        }
        try {
            var protokoll = new Fachprotokoll(db, null);
            // Der Aufrufer behauptet eine ANDERE Einrichtung — so wie ein Agent,
            // der unter seiner Registrierung meldet.
            var e = protokoll.anfuegen("Einrichtung des Agenten", terminal,
                    de.pinroboter.gemeinsam.Ereignisart.GERAET_ERREICHBAR, true,
                    "Standort-Agent", null, "Gerät wieder erreichbar", null);

            try (PreparedStatement p = db.verbindung().prepareStatement(
                    "SELECT einrichtung FROM fachprotokoll WHERE id = ?")) {
                p.setLong(1, e.id());
                try (ResultSet rs = p.executeQuery()) {
                    rs.next();
                    assertEquals(eigene, rs.getString(1),
                            "Das Gerät bestimmt die Einrichtung, nicht der Aufrufer — sonst "
                            + "sieht der Zuständige das Ereignis seines eigenen Geräts nicht.");
                }
            }
        } finally {
            try (Statement st = db.verbindung().createStatement()) {
                st.executeUpdate("DELETE FROM geraet WHERE terminal_id = '" + terminal + "'");
                st.executeUpdate("DELETE FROM geraetegruppe WHERE id = " + gruppeId);
                st.executeUpdate("DELETE FROM organisation WHERE id = " + orgId);
            }
        }
    }

    @Test
    @DisplayName("Ist das Gerät unbekannt, bleibt die genannte Einrichtung stehen")
    void unbekanntesGeraetBehaeltVorgabe() throws Exception {
        // Beim Erfassen eines Geräts gibt es die Zuordnung noch nicht. Dann ist
        // die genannte Einrichtung das Beste, was es gibt — sie zu verwerfen
        // wäre schlechter als sie zu übernehmen.
        var protokoll = new Fachprotokoll(db, null);
        var e = protokoll.anfuegen("Genannte Einrichtung", "KT-GIBTESNICHT-" + System.nanoTime(),
                de.pinroboter.gemeinsam.Ereignisart.TERMINAL_ANGELEGT, true,
                "Nachweis", null, "Gerät erfasst", null);
        try (PreparedStatement p = db.verbindung().prepareStatement(
                "SELECT einrichtung FROM fachprotokoll WHERE id = ?")) {
            p.setLong(1, e.id());
            try (ResultSet rs = p.executeQuery()) {
                rs.next();
                assertEquals("Genannte Einrichtung", rs.getString(1));
            }
        }
    }

    private Timestamp schluesselGemeldet() throws SQLException {
        try (PreparedStatement p = db.verbindung().prepareStatement(
                "SELECT schluessel_gemeldet FROM agent WHERE id = ?")) {
            p.setLong(1, agentId);
            try (ResultSet rs = p.executeQuery()) { rs.next(); return rs.getTimestamp(1); }
        }
    }

    private Timestamp seit(String handle) throws SQLException {
        try (PreparedStatement p = db.verbindung().prepareStatement(
                "SELECT seit FROM pin_hinterlegt WHERE agent_id = ? AND kartenhandle = ?")) {
            p.setLong(1, agentId);
            p.setString(2, handle);
            try (ResultSet rs = p.executeQuery()) { rs.next(); return rs.getTimestamp(1); }
        }
    }
}
