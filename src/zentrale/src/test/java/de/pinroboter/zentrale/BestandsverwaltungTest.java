package de.pinroboter.zentrale;

import de.pinroboter.gemeinsam.Geheimnisspeicher;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Bestandsverwaltung gegen eine echte Datenbank (FA-GER-010, FA-GER-020).
 *
 * <p>Schwerpunkt sind die Zusagen, deren Bruch teuer wäre: Zugangsdaten dürfen
 * nicht zurück in die Oberfläche gelangen, ein Konnektor mit Terminals darf
 * nicht stillschweigend einen halben Standort mitreißen, und die PIN-Automatik
 * darf nicht an einem Terminal ohne SMC-B landen.
 */
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BestandsverwaltungTest {

    /**
     * Diese Testklassen prüfen Fachlogik, nicht Mandantentrennung — sie
     * arbeiten deshalb uneingeschränkt. Die Trennung selbst hat eine eigene
     * Testklasse: {@code MandantentrennungTest}.
     */
    private static final Geltungsbereich ALLES = Geltungsbereich.alles();

    private static Datenbank db;
    private static Bestandsverwaltung bv;
    private static long orgId;
    private static long konnektorId;
    private static long terminalId;

    @BeforeAll
    static void aufbauen() throws Exception {
        db = new Datenbank(System.getenv("TEST_DB_URL"),
                System.getenv().getOrDefault("TEST_DB_USER", "pinroboter"),
                System.getenv().getOrDefault("TEST_DB_PASSWORD", "test"));
        bv = new Bestandsverwaltung(db,
                Geheimnisspeicher.ausBase64(Geheimnisspeicher.neuerSchluesselBase64()));
        aufraeumenDaten();
        // Eine Einrichtung haengt immer unter einem Verbund - das erzwingt die
        // Bedingung organisation_hierarchie und ist die Grundlage der spaeteren
        // Mandantentrennung.
        long verbundId;
        try (Statement s = db.verbindung().createStatement();
             ResultSet rs = s.executeQuery("""
                     INSERT INTO organisation (bezeichnung, art, ist_demo)
                     VALUES ('Testverbund Bestand', 'VERBUND', FALSE) RETURNING id""")) {
            rs.next();
            verbundId = rs.getLong(1);
        }
        try (PreparedStatement p = db.verbindung().prepareStatement("""
                INSERT INTO organisation (bezeichnung, art, eltern_id, ist_demo)
                VALUES ('Testeinrichtung Bestand', 'EINRICHTUNG', ?, FALSE) RETURNING id""")) {
            p.setLong(1, verbundId);
            try (ResultSet rs = p.executeQuery()) { rs.next(); orgId = rs.getLong(1); }
        }
    }

    @AfterAll
    static void abbauen() throws Exception {
        if (db != null) { aufraeumenDaten(); db.close(); }
    }

    private static void aufraeumenDaten() throws SQLException {
        try (Statement s = db.verbindung().createStatement()) {
            s.executeUpdate("""
                    DELETE FROM kartenterminal WHERE konnektor_id IN
                      (SELECT id FROM konnektor WHERE organisation_id IN
                        (SELECT id FROM organisation WHERE bezeichnung = 'Testeinrichtung Bestand'))""");
            s.executeUpdate("""
                    DELETE FROM konnektor WHERE organisation_id IN
                      (SELECT id FROM organisation WHERE bezeichnung = 'Testeinrichtung Bestand')""");
            s.executeUpdate("DELETE FROM organisation WHERE bezeichnung = 'Testeinrichtung Bestand'");
            s.executeUpdate("DELETE FROM organisation WHERE bezeichnung = 'Testverbund Bestand'");
        }
    }

    // ------------------------------------------------------------ Konnektoren

    @Test @Order(1)
    @DisplayName("Ein Konnektor lässt sich mit vollem Aufrufkontext anlegen")
    void konnektorAnlegen() {
        var e = bv.konnektorAnlegen(orgId, "Prüfkonnektor", "192.0.2.10:443", "KoCo Konnektor",
                "Mandant-1", "CS-1", "AP-1", "admin", "streng-geheim", null, ALLES);
        assertTrue(e.geklappt(), () -> "unerwarteter Fehler: " + e.fehler());
        konnektorId = e.id();

        var k = bv.konnektor(konnektorId, ALLES).orElseThrow();
        assertEquals("Prüfkonnektor", k.bezeichnung());
        assertEquals("Mandant-1", k.mandantId());
        assertTrue(k.aktiv());
        assertEquals(0, k.terminalzahl());
    }

    @Test @Order(2)
    @DisplayName("Das Kennwort ist über keinen Lesepfad auslesbar")
    void kennwortNichtAuslesbar() throws Exception {
        var k = bv.konnektor(konnektorId, ALLES).orElseThrow();
        assertTrue(k.passwortHinterlegt(), "es muss sichtbar sein, DASS eines hinterlegt ist");

        // Der Record darf den Wert nirgends tragen - auch nicht in einem Feld,
        // das jemand spaeter versehentlich in die Oberflaeche schreibt.
        assertFalse(k.toString().contains("streng-geheim"),
                "kein Klartext in der Textdarstellung: " + k);

        // Und in der Datenbank steht ein Chiffrat, kein Klartext.
        try (Statement s = db.verbindung().createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT passwort_chiffrat FROM konnektor WHERE id = " + konnektorId)) {
            rs.next();
            String gespeichert = rs.getString(1);
            assertNotNull(gespeichert);
            assertFalse(gespeichert.contains("streng-geheim"));
            assertTrue(gespeichert.startsWith("v1:"), "erwartetes Format: " + gespeichert);
        }

        // Nur der ausdrueckliche Weg liefert den Wert.
        assertEquals("streng-geheim", bv.konnektorPasswort(konnektorId));
    }

    @Test @Order(3)
    @DisplayName("Eine Änderung ohne Kennwortfeld lässt das Kennwort stehen")
    void aenderungLoeschtKennwortNicht() {
        var e = bv.konnektorAendern(konnektorId, "Prüfkonnektor neu", "192.0.2.11:443", null,
                "Mandant-1", "CS-1", "AP-1", "admin", "", null, true, ALLES);
        assertTrue(e.geklappt(), () -> String.valueOf(e.fehler()));

        var k = bv.konnektor(konnektorId, ALLES).orElseThrow();
        assertEquals("Prüfkonnektor neu", k.bezeichnung());
        assertEquals("192.0.2.11:443", k.adresse());
        assertTrue(k.passwortHinterlegt(), "das Kennwort darf durch eine Umbenennung nicht verschwinden");
        assertEquals("streng-geheim", bv.konnektorPasswort(konnektorId));
    }

    @Test @Order(4)
    @DisplayName("Ein unvollständiger Aufrufkontext wird mit erklärender Meldung abgelehnt")
    void aufrufkontextIstPflicht() {
        // Ohne diese drei Werte schlaegt spaeter jeder Fachaufruf fehl
        // (TUC_KON_000) - und zwar mit einer Meldung, die den Zusammenhang
        // nicht erkennen laesst. Deshalb hier hart pruefen.
        var ohneMandant = bv.konnektorAnlegen(orgId, "X", "192.0.2.20", null,
                "", "CS", "AP", null, null, null, ALLES);
        assertFalse(ohneMandant.geklappt());
        assertTrue(ohneMandant.fehler().contains("Mandanten-ID"), ohneMandant.fehler());

        var ohneAdresse = bv.konnektorAnlegen(orgId, "X", "  ", null, "M", "CS", "AP", null, null, null, ALLES);
        assertFalse(ohneAdresse.geklappt());
        assertTrue(ohneAdresse.fehler().contains("Adresse"), ohneAdresse.fehler());

        var ohneBezeichnung = bv.konnektorAnlegen(orgId, "", "192.0.2.20", null, "M", "CS", "AP", null, null, null, ALLES);
        assertFalse(ohneBezeichnung.geklappt());
        assertTrue(ohneBezeichnung.fehler().contains("Bezeichnung"), ohneBezeichnung.fehler());
    }

    @Test @Order(5)
    @DisplayName("Ohne Schlüssel bleibt alles erfassbar, nur ohne Kennwort")
    void ohneSchluessel() {
        var ohne = new Bestandsverwaltung(db, null);
        assertFalse(ohne.zugangsdatenMoeglich());

        var mitKennwort = ohne.konnektorAnlegen(orgId, "Ohne Tresor", "192.0.2.30", null,
                "M", "CS", "AP", "admin", "geheim", null, ALLES);
        assertFalse(mitKennwort.geklappt(), "mit Kennwort muss es scheitern");
        assertTrue(mitKennwort.fehler().contains("GEHEIMNIS_SCHLUESSEL"),
                "die Meldung muss den Namen der Variablen nennen: " + mitKennwort.fehler());

        var ohneKennwort = ohne.konnektorAnlegen(orgId, "Ohne Tresor", "192.0.2.30", null,
                "M", "CS", "AP", "admin", null, null, ALLES);
        assertTrue(ohneKennwort.geklappt(),
                () -> "ohne Kennwort muss es gehen: " + ohneKennwort.fehler());
        ohne.konnektorLoeschen(ohneKennwort.id(), ALLES);
    }

    // -------------------------------------------------------------- Terminals

    @Test @Order(10)
    @DisplayName("Ein Kartenterminal übernimmt die Einrichtung seines Konnektors")
    void terminalAnlegen() {
        var e = bv.terminalAnlegen("KT-TEST-01", konnektorId, "Empfang links", "Foyer",
                "192.0.2.51:443", "CHERRY ST-1506", "SN-0815", "admin", "term-geheim",
                true, true, ALLES);
        assertTrue(e.geklappt(), () -> String.valueOf(e.fehler()));
        terminalId = e.id();

        var t = bv.terminal(terminalId, ALLES).orElseThrow();
        assertEquals(orgId, t.organisationId(), "die Einrichtung kommt vom Konnektor");
        assertEquals(konnektorId, t.konnektorId());
        assertTrue(t.hatSmcb());
        assertTrue(t.pevimAktiv());
        assertTrue(t.adminPasswortHinterlegt());
        assertFalse(t.toString().contains("term-geheim"), "kein Klartext in der Textdarstellung");
        assertEquals("term-geheim", bv.terminalAdminPasswort(terminalId));
    }

    @Test @Order(11)
    @DisplayName("PIN-Automatik ohne SMC-B wird abgelehnt (A_24080)")
    void pevimNurMitSmcb() {
        // PEviM ist ausschliesslich fuer SMC-B zulaessig. Ein Versuch gegen eine
        // andere Karte waere nicht nur wirkungslos, sondern koennte deren
        // Fehlversuchszaehler belasten.
        var e = bv.terminalAnlegen("KT-TEST-02", konnektorId, "Behandlung 1", null,
                "192.0.2.52", null, null, null, null, false, true, ALLES);
        assertFalse(e.geklappt());
        assertTrue(e.fehler().contains("A_24080"), e.fehler());

        var aendern = bv.terminalAendern(terminalId, "Empfang links", "Foyer", "192.0.2.51:443",
                null, null, null, null, false, true, true, ALLES);
        assertFalse(aendern.geklappt(), "auch beim Ändern muss die Regel greifen");
    }

    @Test @Order(12)
    @DisplayName("Eine doppelte Kennung wird mit verständlicher Meldung abgelehnt")
    void kennungIstEindeutig() {
        var e = bv.terminalAnlegen("KT-TEST-01", konnektorId, "Zweites Gerät", null,
                "192.0.2.53", null, null, null, null, false, false, ALLES);
        assertFalse(e.geklappt());
        assertTrue(e.fehler().contains("bereits"), e.fehler());
        assertFalse(e.fehler().contains("duplicate key"),
                "die Datenbankmeldung darf nicht durchschlagen: " + e.fehler());
    }

    @Test @Order(13)
    @DisplayName("Suche und Seitenblätter arbeiten serverseitig")
    void sucheUndBlaettern() {
        for (int i = 2; i <= 12; i++) {
            var e = bv.terminalAnlegen("KT-TEST-%02d".formatted(i), konnektorId,
                    "Behandlung " + i, "Flur B", "192.0.2." + (60 + i), null, null,
                    null, null, false, false, ALLES);
            assertTrue(e.geklappt(), () -> String.valueOf(e.fehler()));
        }
        var alle = bv.terminals(new Bestandsverwaltung.Filter(null, konnektorId, null, null, null, null, null, null),
                null, false, 1, 5, ALLES);
        assertEquals(12, alle.gesamt());
        assertEquals(5, alle.eintraege().size(), "eine Seite liefert nur ihre eigenen Treffer");
        assertEquals(3, alle.seitenZahl());
        assertTrue(alle.hatNaechste());
        assertFalse(alle.hatVorherige());

        var letzte = bv.terminals(new Bestandsverwaltung.Filter(null, konnektorId, null, null, null, null, null, null),
                null, false, 3, 5, ALLES);
        assertEquals(2, letzte.eintraege().size());
        assertFalse(letzte.hatNaechste());

        var gesucht = bv.terminals(new Bestandsverwaltung.Filter("empfang", null, null, null, null, null, null, null),
                null, false, 1, 25, ALLES);
        assertEquals(1, gesucht.gesamt(), "Suche über die Bezeichnung");
        assertEquals("KT-TEST-01", gesucht.eintraege().get(0).terminalId());

        assertEquals(11, bv.terminals(new Bestandsverwaltung.Filter("Flur B", null, null, null, null, null, null, null),
                null, false, 1, 25, ALLES).gesamt(),
                "Suche über den Standort");
        assertEquals(1, bv.terminals(new Bestandsverwaltung.Filter("KT-TEST-07", null, null, null, null, null, null, null),
                null, false, 1, 25, ALLES).gesamt(),
                "Suche über die Kennung");
        assertEquals(0, bv.terminals(new Bestandsverwaltung.Filter("gibtesnicht", null, null, null, null, null, null, null),
                null, false, 1, 25, ALLES).gesamt());
    }

    // ---------------------------------------------------------------- Löschen

    @Test @Order(20)
    @DisplayName("Ein Konnektor mit Terminals lässt sich nicht löschen")
    void loeschschutz() {
        // Das Schema wuerde per ON DELETE CASCADE alle Terminals mitnehmen.
        // Ein Fehlgriff wuerde damit einen halben Standort aus der Ueberwachung
        // entfernen - lautlos.
        var e = bv.konnektorLoeschen(konnektorId, ALLES);
        assertFalse(e.geklappt());
        assertTrue(e.fehler().contains("12"), "die Anzahl muss genannt werden: " + e.fehler());
        assertTrue(e.fehler().contains("Behandlung"),
                "betroffene Geräte müssen benannt werden: " + e.fehler());
        assertTrue(e.fehler().contains("…"),
                "bei mehr Geräten als aufgezählt muss das erkennbar sein: " + e.fehler());

        assertEquals(12, bv.terminals(new Bestandsverwaltung.Filter(null, konnektorId, null, null, null, null, null, null),
                null, false, 1, 50, ALLES).gesamt(),
                "nichts darf gelöscht worden sein");
    }

    @Test @Order(21)
    @DisplayName("Nach dem Entfernen aller Terminals lässt sich der Konnektor löschen")
    void loeschenNachAufraeumen() {
        for (var t : bv.terminals(new Bestandsverwaltung.Filter(null, konnektorId, null, null, null, null, null, null),
                null, false, 1, 100, ALLES).eintraege()) {
            assertTrue(bv.terminalLoeschen(t.id(), ALLES).geklappt());
        }
        assertEquals(0, bv.terminals(new Bestandsverwaltung.Filter(null, konnektorId, null, null, null, null, null, null),
                null, false, 1, 50, ALLES).gesamt());
        assertTrue(bv.konnektorLoeschen(konnektorId, ALLES).geklappt());
        assertTrue(bv.konnektor(konnektorId, ALLES).isEmpty());
    }

    @Test @Order(22)
    @DisplayName("Operationen auf gelöschten Datensätzen melden das, statt zu scheitern")
    void verschwundeneDatensaetze() {
        assertFalse(bv.konnektorLoeschen(konnektorId, ALLES).geklappt());
        assertFalse(bv.konnektorAendern(konnektorId, "X", "1.2.3.4", null, "M", "C", "A",
                null, null, null, true, ALLES).geklappt());
        assertFalse(bv.terminalLoeschen(terminalId, ALLES).geklappt());
        assertTrue(bv.terminal(terminalId, ALLES).isEmpty());
    }

    // ------------------------------------------------ Erreichbarkeitsprüfung

    @Test @Order(30)
    @DisplayName("Eine unlesbare Adresse wird als solche gemeldet, nicht als Ausfall")
    void adresseUnlesbar() {
        var e = bv.erreichbarkeitPruefen("das ist keine adresse");
        assertFalse(e.erreichbar());
        assertTrue(e.hinweis().contains("nicht lesbar") || e.hinweis().contains("nicht auflösbar"),
                e.hinweis());
    }

    @Test @Order(31)
    @DisplayName("Die Prüfung antwortet deutlich innerhalb der geforderten 10 Sekunden")
    void pruefungIstSchnellGenug() {
        // 192.0.2.0/24 ist nach RFC 5737 fuer Dokumentation reserviert und
        // wird nirgends geroutet - ein verlaesslicher Zeitueberschreitungsfall.
        long start = System.nanoTime();
        var e = bv.erreichbarkeitPruefen("192.0.2.123:443");
        long dauerMs = (System.nanoTime() - start) / 1_000_000;

        assertFalse(e.erreichbar());
        assertTrue(dauerMs < 10_000, "Akzeptanzkriterium: Ergebnis binnen 10 s, gemessen: " + dauerMs);
        assertFalse(e.hinweis().isBlank(), "es muss ein Hinweis kommen, nicht nur ein Nein");
    }

    @Test @Order(32)
    @DisplayName("Eine fehlende Adresse führt nicht zu einem Absturz")
    void keineAdresse() {
        var e = bv.erreichbarkeitPruefen(null);
        assertFalse(e.erreichbar());
        assertTrue(e.hinweis().contains("keine Adresse"), e.hinweis());
    }

    // ── Kartenterminals ohne Konnektor (Vorgabe PO, 11.08.2026) ─────────────

    /**
     * Ein eigener Konnektor für diese Testgruppe.
     *
     * <p>Der aus {@code aufbauen()} wird von einem früheren Test gelöscht. Sich
     * darauf zu verlassen, dass er noch da ist, machte diese Tests von der
     * Reihenfolge abhängig — und der Fehlschlag hätte wie ein Produktfehler
     * ausgesehen („Diesen Konnektor gibt es nicht mehr").
     */
    private long eigenerKonnektor() {
        var e = bv.konnektorAnlegen(orgId, "Konnektor Zuordnungstests",
                "192.0.2.200:443", "KoCo Konnektor", "Mandant1", "PIN-Roboter",
                "Arbeitsplatz1", null, null, null, ALLES);
        assertTrue(e.geklappt(), e.fehler());
        return e.id();
    }

    @Test @Order(40)
    @DisplayName("Ein Terminal lässt sich ohne Konnektor erfassen, direkt an einer Einrichtung")
    void ohneKonnektorErfassen() {
        // Der Fall aus der Praxis: Das Gerät ist geliefert und aufgestellt,
        // aber am Konnektor noch nicht angemeldet. Wer es dann nicht erfassen
        // kann, führt seinen Bestand nebenher auf Papier.
        var e = bv.terminalAnlegen("KT-OHNE-KONN", null, orgId, "Anmeldung",
                "Foyer", "192.0.2.90", "CHERRY ST-1506", null, null, null,
                false, false, ALLES);
        assertTrue(e.geklappt(), e.fehler());

        var t = bv.terminal(e.id(), ALLES).orElseThrow();
        assertNull(t.konnektorId(), "kein Konnektor zugeordnet");
        assertEquals(orgId, t.organisationId(), "die Einrichtung ist trotzdem gesetzt");
        assertFalse(t.automatikMoeglich());
        assertNotNull(t.automatikHindernis(),
                "Es muss im Klartext dastehen, warum die Automatik nicht greift — "
                + "sonst wartet jemand auf eine Freischaltung, die niemand auslöst.");
    }

    @Test @Order(41)
    @DisplayName("Ohne Einrichtung geht es nicht — sie ist die Mandantengrenze")
    void ohneEinrichtungNicht() {
        var e = bv.terminalAnlegen("KT-OHNE-ORG", null, 0L, "Irgendwo",
                null, "192.0.2.91", null, null, null, null, false, false, ALLES);
        assertFalse(e.geklappt(), "Ein Gerät ohne Einrichtung hätte keine Mandantengrenze");
        assertTrue(e.fehler().contains("Einrichtung"), e.fehler());
    }

    @Test @Order(42)
    @DisplayName("Ein Konnektor aus einer FREMDEN Einrichtung wird abgewiesen")
    void fremderKonnektorAbgewiesen() throws Exception {
        long fremdeOrg;
        try (Statement st = db.verbindung().createStatement();
             ResultSet rs = st.executeQuery("""
                     INSERT INTO organisation (bezeichnung, art, eltern_id, ist_demo)
                     SELECT 'Fremde Einrichtung Bestand', 'EINRICHTUNG', eltern_id, FALSE
                     FROM organisation WHERE id = """ + orgId + " RETURNING id")) {
            rs.next(); fremdeOrg = rs.getLong(1);
        }
        // Terminal soll zur fremden Einrichtung gehören, der Konnektor gehört
        // aber zur eigenen. Das wäre in keiner der beiden Sichten richtig
        // einsortiert.
        var e = bv.terminalAnlegen("KT-QUER", eigenerKonnektor(), fremdeOrg, "Quergestellt",
                null, "192.0.2.92", null, null, null, null, false, false, ALLES);
        assertFalse(e.geklappt(), "Terminal und Konnektor müssen zur selben Einrichtung gehören");
        assertTrue(e.fehler().contains("gehört zu"), e.fehler());
    }

    @Test @Order(43)
    @DisplayName("Der Konnektor lässt sich nachtragen — und wieder entfernen")
    void konnektorNachtragen() {
        var e = bv.terminalAnlegen("KT-NACHTRAG", null, orgId, "Wartezimmer",
                null, "192.0.2.93", null, null, null, null, false, false, ALLES);
        assertTrue(e.geklappt(), e.fehler());
        long id = e.id();

        long konn = eigenerKonnektor();
        var z = bv.terminalKonnektorSetzen(id, konn, ALLES);
        assertTrue(z.geklappt(), z.fehler());
        assertEquals(konn, bv.terminal(id, ALLES).orElseThrow().konnektorId());

        // Auch der umgekehrte Weg muss gehen: Ein Gerät wird umgesetzt, der
        // alte Konnektor stimmt nicht mehr, der neue steht noch nicht fest.
        assertTrue(bv.terminalKonnektorSetzen(id, null, ALLES).geklappt());
        assertNull(bv.terminal(id, ALLES).orElseThrow().konnektorId());
    }

    @Test @Order(44)
    @DisplayName("Ein Terminal ohne Konnektor steht trotzdem in der Liste")
    void ohneKonnektorInDerListe() {
        var seite = bv.terminals(Bestandsverwaltung.Filter.leer(), null, false, 1, 200, ALLES);
        assertTrue(seite.eintraege().stream().anyMatch(t -> "KT-OHNE-KONN".equals(t.terminalId())),
                "Ein LEFT JOIN, kein JOIN: Sonst verschwindet genau das Gerät aus der "
                + "Übersicht, das noch Aufmerksamkeit braucht.");
    }

    @Test @Order(45)
    @DisplayName("Die Liste ist standardmäßig nach Einrichtung sortiert")
    void standardsortierung() {
        var seite = bv.terminals(Bestandsverwaltung.Filter.leer(), null, false, 1, 200, ALLES);
        var namen = seite.eintraege().stream()
                .map(Bestandsverwaltung.Terminal::organisation).toList();
        var sortiert = namen.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
        assertEquals(sortiert, namen,
                "Alphabetisch nach Gerätenamen war die falsche Grundordnung: In einem MVZ "
                + "heißen die Geräte in jeder Betriebsstätte gleich. Nach Einrichtung "
                + "sortiert steht zusammen, was zusammengehört.");
    }
}
