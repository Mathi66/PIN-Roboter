package de.pinroboter.zentrale;

import de.pinroboter.gemeinsam.Geheimnisspeicher;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Lasterzeuger für die Leistungsmessung (NFA-LEI-010).
 *
 * <p>Geprüft wird mit 1.000 Geräten statt 5.000 — die Größenordnung stimmt, der
 * Bauvorgang bleibt kurz. Die volle Messung mit 5.000 Terminals und 5 Millionen
 * Protokolleinträgen steht im
 * <a href="../../../../../../../docs/00-referenzen/messung-leistung-mengengeruest.md">Messbericht</a>.
 */
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LasterzeugerTest {

    private static final int ANZAHL = 1000;

    /**
     * Diese Testklassen prüfen Fachlogik, nicht Mandantentrennung — sie
     * arbeiten deshalb uneingeschränkt. Die Trennung selbst hat eine eigene
     * Testklasse: {@code MandantentrennungTest}.
     */
    private static final Geltungsbereich ALLES = Geltungsbereich.alles();

    private static Datenbank db;
    private static Lasterzeuger last;
    private static Bestandsverwaltung bv;

    @BeforeAll
    static void aufbauen() throws Exception {
        db = new Datenbank(System.getenv("TEST_DB_URL"),
                System.getenv().getOrDefault("TEST_DB_USER", "pinroboter"),
                System.getenv().getOrDefault("TEST_DB_PASSWORD", "test"));
        last = new Lasterzeuger(db);
        bv = new Bestandsverwaltung(db,
                Geheimnisspeicher.ausBase64(Geheimnisspeicher.neuerSchluesselBase64()));
        last.entfernen();
    }

    @AfterAll
    static void abbauen() throws Exception {
        if (db != null) { last.entfernen(); db.close(); }
    }

    @Test @Order(1)
    @DisplayName("Der Bestand entsteht in der gewünschten Größe")
    void erzeugen() throws Exception {
        var e = last.erzeugen(ANZAHL);
        assertEquals(ANZAHL, e.terminals());
        assertEquals(20, e.konnektoren(), "50 Terminals je Konnektor");
        assertEquals(ANZAHL, last.vorhandene());
    }

    @Test @Order(2)
    @DisplayName("Die Daten sind gestreut, nicht uniform")
    void datenSindGestreut() throws Exception {
        // Ein Bestand, in dem alle Werte gleich sind, laesst Sortierung und
        // Filter schneller aussehen, als sie in einer echten Klinik waeren.
        try (Statement s = db.verbindung().createStatement();
             ResultSet rs = s.executeQuery("""
                     SELECT count(DISTINCT modell), count(DISTINCT firmware),
                            count(*) FILTER (WHERE hat_smcb), count(*) FILTER (WHERE NOT aktiv)
                     FROM kartenterminal WHERE terminal_id LIKE 'KT-LAST-%'""")) {
            rs.next();
            assertTrue(rs.getInt(1) >= 4, "mehrere Modelle");
            assertTrue(rs.getInt(2) >= 4, "mehrere Firmware-Stände");
            assertEquals(ANZAHL / 4, rs.getInt(3), "jedes vierte Gerät mit SMC-B");
            assertEquals(ANZAHL / 20, rs.getInt(4), "jedes zwanzigste deaktiviert");
        }
    }

    @Test @Order(3)
    @DisplayName("Alles trägt das Demo-Kennzeichen")
    void allesIstDemo() throws Exception {
        try (Statement s = db.verbindung().createStatement();
             ResultSet rs = s.executeQuery("""
                     SELECT count(*) FROM kartenterminal
                     WHERE terminal_id LIKE 'KT-LAST-%' AND NOT ist_demo""")) {
            rs.next();
            assertEquals(0, rs.getInt(1),
                    "ohne Demo-Kennzeichen wäre der Testbestand nicht mehr sauber entfernbar");
        }
    }

    @Test @Order(4)
    @DisplayName("Ein zweiter Aufruf verdoppelt nichts")
    void idempotent() throws Exception {
        last.erzeugen(ANZAHL);
        assertEquals(ANZAHL, last.vorhandene(), "gleiche Kennungen, keine Dubletten");
    }

    @Test @Order(5)
    @DisplayName("Die erste Seite bleibt bei 1.000 Geräten deutlich unter einer Sekunde")
    void ersteSeiteIstSchnell() {
        var filter = Bestandsverwaltung.Filter.leer();
        // Einmal warmlaufen, damit nicht der erste Verbindungsaufbau gemessen wird.
        bv.terminals(filter, null, false, 1, 25, ALLES);

        long start = System.nanoTime();
        var seite = bv.terminals(filter, Bestandsverwaltung.Sortierung.BEZEICHNUNG, false, 1, 25, ALLES);
        long ms = (System.nanoTime() - start) / 1_000_000;

        assertEquals(25, seite.eintraege().size());
        assertTrue(seite.gesamt() >= ANZAHL);
        assertTrue(ms < 1000, "NFA-LEI-010 verlangt unter 1 s, gemessen: " + ms + " ms");
    }

    @Test @Order(6)
    @DisplayName("Suche, Filter und Sortierung bleiben ebenfalls schnell")
    void abfragenBleibenSchnell() {
        record Fall(String name, Bestandsverwaltung.Filter f, Bestandsverwaltung.Sortierung s) {}
        var faelle = java.util.List.of(
                new Fall("Suche", new Bestandsverwaltung.Filter("Raum 12", null, null, null,
                        null, null, null), null),
                new Fall("Filter Modell", new Bestandsverwaltung.Filter(null, null, null, null,
                        "CHERRY ST-1506", null, null), null),
                new Fall("Sortierung Firmware", Bestandsverwaltung.Filter.leer(),
                        Bestandsverwaltung.Sortierung.FIRMWARE));

        for (var fall : faelle) {
            bv.terminals(fall.f(), fall.s(), false, 1, 25, ALLES);   // warmlaufen
            long start = System.nanoTime();
            bv.terminals(fall.f(), fall.s(), true, 1, 25, ALLES);
            long ms = (System.nanoTime() - start) / 1_000_000;
            assertTrue(ms < 1000, fall.name() + " brauchte " + ms + " ms (Ziel < 1000)");
        }
    }

    @Test @Order(7)
    @DisplayName("Entfernen lässt nichts zurück — und nur den Testbestand")
    void entfernen() throws Exception {
        // Ein Datensatz, der ueberleben muss.
        try (Statement s = db.verbindung().createStatement()) {
            s.executeUpdate("""
                    INSERT INTO organisation (bezeichnung, art, ist_demo)
                    VALUES ('Echte Praxis Lasttest', 'VERBUND', FALSE)""");
        }
        int weg = last.entfernen();
        assertEquals(ANZAHL, weg);
        assertEquals(0, last.vorhandene());

        try (Statement s = db.verbindung().createStatement();
             ResultSet rs = s.executeQuery("""
                     SELECT count(*) FROM konnektor WHERE bezeichnung LIKE 'Lastkonnektor %'""")) {
            rs.next();
            assertEquals(0, rs.getInt(1), "auch die Konnektoren müssen weg sein");
        }
        try (Statement s = db.verbindung().createStatement();
             ResultSet rs = s.executeQuery("""
                     SELECT count(*) FROM organisation WHERE bezeichnung = 'Echte Praxis Lasttest'""")) {
            rs.next();
            assertEquals(1, rs.getInt(1), "Daten ohne Demo-Kennzeichen bleiben unberührt");
            s.executeUpdate("DELETE FROM organisation WHERE bezeichnung = 'Echte Praxis Lasttest'");
        }
    }
}
