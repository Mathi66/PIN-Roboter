package de.pinroboter.zentrale;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Prüft den Demodatensatz (MVZ-Verbund) gegen eine echte Datenbank.
 *
 * <p>Der Datensatz ist mehr als Beiwerk: An ihm entscheidet sich, ob
 * Mandantentrennung und Berechtigungsebenen tragen, und ob die Oberfläche bei
 * echten Stückzahlen bedienbar bleibt.
 */
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DemodatenTest {

    private static Datenbank db;
    private static Demodaten dd;

    @BeforeAll
    static void verbinden() throws Exception {
        db = new Datenbank(System.getenv("TEST_DB_URL"),
                System.getenv().getOrDefault("TEST_DB_USER", "pinroboter"),
                System.getenv().getOrDefault("TEST_DB_PASSWORD", "test"));
        dd = new Demodaten(db);
        if (dd.bestand().vorhanden()) dd.loeschen();
    }

    @AfterAll
    static void aufraeumen() throws Exception {
        if (dd != null && dd.bestand().vorhanden()) dd.loeschen();
        if (db != null) db.close();
    }

    private long zaehle(String sql) throws SQLException {
        try (Statement s = db.verbindung().createStatement(); ResultSet rs = s.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : -1;
        }
    }

    @Test @Order(1)
    @DisplayName("Der Verbund entsteht in der vorgegebenen Größe")
    void groesse() throws Exception {
        var konten = dd.anlegen();
        var b = dd.bestand();

        // Seit ADR-0012 kommen die Fachbereiche einer Betriebsstätte dazu.
        int fachbereiche = de.pinroboter.gemeinsam.Demoszenario.BETRIEBSSTAETTEN.stream()
                .mapToInt(x -> x.fachbereiche().size()).sum();
        assertEquals(13 + fachbereiche, b.organisationen(),
                "1 Träger + 3 Standorte + 9 Betriebsstätten + Fachbereiche");
        // Die drei kleinen Betriebsstätten teilen sich den zentralen Konnektor.
        int eigene = (int) de.pinroboter.gemeinsam.Demoszenario.BETRIEBSSTAETTEN.stream()
                .filter(x -> !x.imRechenzentrum()).count();
        assertEquals(eigene + 1, b.konnektoren(),
                "je eigenständiger Betriebsstätte ein Konnektor, dazu der zentrale");
        int gesamt = de.pinroboter.gemeinsam.Demoszenario.terminalsGesamt();
        assertEquals(gesamt, b.arbeitsplaetze(),
                "je Betriebsstätte 1 Empfang + ihre Behandlungsräume");
        assertEquals(gesamt, b.terminals(), "je Arbeitsplatz ein Terminal");
        assertEquals(16, b.benutzer(), "9 Ebene 1 + 3 Ebene 2 + 1 Ebene 3 + 3 Administratoren");
        assertEquals(16, konten.size(), "alle Konten werden zurückgegeben");
    }

    @Test @Order(2)
    @DisplayName("Genau eine SMC-B je Praxis, und zwar am Empfang")
    void smcbNurAmEmpfang() throws Exception {
        assertEquals(9, zaehle("SELECT count(*) FROM kartenterminal WHERE ist_demo AND hat_smcb"),
                "eine SMC-B je Praxis");
        assertEquals(0, zaehle("""
                SELECT count(*) FROM kartenterminal t JOIN arbeitsplatz a ON a.id = t.arbeitsplatz_id
                WHERE t.ist_demo AND t.hat_smcb AND a.art <> 'EMPFANG'"""),
                "keine SMC-B an einem Behandlungsplatz");
        assertEquals(0, zaehle("""
                SELECT count(*) FROM kartenterminal t JOIN arbeitsplatz a ON a.id = t.arbeitsplatz_id
                WHERE t.ist_demo AND NOT t.hat_smcb AND a.art = 'EMPFANG'"""),
                "jeder Empfang hat eine SMC-B");
    }

    @Test @Order(3)
    @DisplayName("Alle Terminals haben die Managementschnittstelle freigeschaltet")
    void pevimUeberall() throws Exception {
        assertEquals(de.pinroboter.gemeinsam.Demoszenario.terminalsGesamt(),
                zaehle("SELECT count(*) FROM kartenterminal WHERE ist_demo AND pevim_aktiv"));
    }

    @Test @Order(4)
    @DisplayName("Die Hierarchie ist dreistufig und lückenlos")
    void hierarchie() throws Exception {
        assertEquals(1, zaehle("SELECT count(*) FROM organisation WHERE ist_demo AND art='VERBUND'"));
        assertEquals(3, zaehle("SELECT count(*) FROM organisation WHERE ist_demo AND art='STANDORT'"));
        assertEquals(9, zaehle("SELECT count(*) FROM organisation WHERE ist_demo AND art='EINRICHTUNG'"));
        assertEquals(0, zaehle("""
                SELECT count(*) FROM organisation WHERE ist_demo AND art <> 'VERBUND'
                  AND eltern_id IS NULL"""), "nur der Verbund hat keine Eltern");
        assertEquals(9, zaehle("""
                SELECT count(*) FROM organisation p JOIN organisation s ON s.id = p.eltern_id
                JOIN organisation v ON v.id = s.eltern_id
                WHERE p.art='EINRICHTUNG' AND s.art='STANDORT' AND v.art='VERBUND'"""),
                "jede Praxis hängt über einen Standort am Verbund");
    }

    @Test @Order(5)
    @DisplayName("Benutzer decken alle drei Berechtigungsebenen ab")
    void berechtigungsebenen() throws Exception {
        assertEquals(9, zaehle("""
                SELECT count(*) FROM benutzer b JOIN organisation o ON o.id = b.organisation_id
                WHERE b.ist_demo AND o.art='EINRICHTUNG' AND b.rolle='BENUTZER'"""),
                "Ebene 1: je Praxis ein Benutzer");
        assertEquals(3, zaehle("""
                SELECT count(*) FROM benutzer b JOIN organisation o ON o.id = b.organisation_id
                WHERE b.ist_demo AND o.art='STANDORT' AND b.rolle='BENUTZER'"""),
                "Ebene 2: je Standort ein Benutzer");
        assertEquals(1, zaehle("""
                SELECT count(*) FROM benutzer b JOIN organisation o ON o.id = b.organisation_id
                WHERE b.ist_demo AND o.art='VERBUND' AND b.rolle='BENUTZER'"""),
                "Ebene 3: einer für den ganzen Verbund");
        assertEquals(3, zaehle("SELECT count(*) FROM benutzer WHERE ist_demo AND rolle='ADMINISTRATOR'"));

        // Die drei Administratoren haben unterschiedliche Geltungsbereiche -
        // daran zeigt sich spaeter, ob die Rechtepruefung wirklich greift.
        assertEquals(3, zaehle("""
                SELECT count(DISTINCT o.art) FROM benutzer b
                JOIN organisation o ON o.id = b.organisation_id
                WHERE b.ist_demo AND b.rolle='ADMINISTRATOR'"""),
                "je ein Administrator auf Verbund-, Standort- und Praxisebene");
    }

    @Test @Order(6)
    @DisplayName("Ein zweiter Anlauf legt nichts doppelt an")
    void keinDoppeltesAnlegen() {
        assertThrows(IllegalStateException.class, () -> dd.anlegen());
    }

    @Test @Order(7)
    @DisplayName("Löschen entfernt Demodaten restlos — und nur diese")
    void loeschenNurDemodaten() throws Exception {
        // Ein Datensatz OHNE Demo-Kennzeichen, der überleben muss.
        try (Statement s = db.verbindung().createStatement()) {
            s.executeUpdate("""
                    INSERT INTO organisation (bezeichnung, art, ist_demo)
                    VALUES ('Echte Praxis Dr. Musterfrau', 'VERBUND', FALSE)""");
        }
        long echteVorher = zaehle("SELECT count(*) FROM organisation WHERE NOT ist_demo");

        dd.loeschen();

        assertFalse(dd.bestand().vorhanden(), "Demodaten müssen weg sein");
        assertEquals(0, zaehle("SELECT count(*) FROM kartenterminal WHERE ist_demo"));
        assertEquals(0, zaehle("SELECT count(*) FROM arbeitsplatz   WHERE ist_demo"));
        assertEquals(0, zaehle("SELECT count(*) FROM konnektor      WHERE ist_demo"));
        assertEquals(0, zaehle("SELECT count(*) FROM benutzer       WHERE ist_demo"));

        assertEquals(echteVorher, zaehle("SELECT count(*) FROM organisation WHERE NOT ist_demo"),
                "Datensätze ohne Demo-Kennzeichen dürfen NIE angefasst werden");

        try (Statement s = db.verbindung().createStatement()) {
            s.executeUpdate("DELETE FROM organisation WHERE bezeichnung = 'Echte Praxis Dr. Musterfrau'");
        }
    }

    @Test @Order(8)
    @DisplayName("Nach dem Löschen lässt sich der Datensatz neu anlegen")
    void wiederherstellbar() throws Exception {
        dd.anlegen();
        assertEquals(de.pinroboter.gemeinsam.Demoszenario.terminalsGesamt(),
                dd.bestand().terminals());
    }

    @Test @Order(8_1)
    @DisplayName("Das Szenario bildet ein MVZ mit unterschiedlich großen Betriebsstätten ab")
    void mvzSzenario() throws Exception {
        if (!dd.bestand().vorhanden()) dd.anlegen();

        assertEquals(de.pinroboter.gemeinsam.Demoszenario.terminalsGesamt(),
                dd.bestand().terminals(), "Der Bestand muss dem Szenario entsprechen");
        assertEquals(9, dd.bestand().terminalsMitSmcb(),
                "Die SMC-B ist die Institutionskarte EINER Betriebsstätte — genau eine je Stätte");

        // Der Punkt des Szenarios: verschieden große Einheiten. Eine Oberfläche,
        // die mit lauter Vierergruppen gut aussieht, sagt nichts über eine
        // Betriebsstätte mit sechs Geräten und eine mit zweien.
        assertTrue(zaehle("SELECT count(DISTINCT anzahl) FROM ("
                + "SELECT organisation_id, count(*) AS anzahl FROM kartenterminal "
                + "WHERE ist_demo GROUP BY organisation_id) x") >= 3,
                "Mindestens drei verschiedene Größen — sonst ist es kein Szenario, "
                + "sondern eine Tabelle");

        // Kein Name darf doppelt vorkommen: In der Lage-Übersicht steht die
        // Betriebsstätte neben dem Gerätenamen, und neun Zeilen "Behandlung 1"
        // sind nur unterscheidbar, wenn die Stätte es ist.
        assertEquals(9, zaehle("SELECT count(DISTINCT bezeichnung) FROM organisation "
                + "WHERE ist_demo AND art = 'EINRICHTUNG'"));
    }

    @Test @Order(9)
    @DisplayName("Zugangsdaten liegen verschlüsselt vor, nie im Klartext")
    void zugangsdatenVerschluesselt() throws Exception {
        if (dd.bestand().vorhanden()) dd.loeschen();
        var tresor = de.pinroboter.gemeinsam.Geheimnisspeicher.ausBase64(
                de.pinroboter.gemeinsam.Geheimnisspeicher.neuerSchluesselBase64());
        new Demodaten(db, tresor).anlegen();

        assertEquals(de.pinroboter.gemeinsam.Demoszenario.terminalsGesamt(),
                zaehle("SELECT count(*) FROM kartenterminal "
                + "WHERE ist_demo AND admin_chiffrat IS NOT NULL"),
                "Jedes Demo-Terminal braucht einen Administrationszugang");
        int konnektoren = (int) de.pinroboter.gemeinsam.Demoszenario.BETRIEBSSTAETTEN.stream()
                .filter(x -> !x.imRechenzentrum()).count() + 1;
        assertEquals(konnektoren, zaehle("SELECT count(*) FROM konnektor "
                + "WHERE ist_demo AND passwort_chiffrat IS NOT NULL"));

        // Der Kern: Das Kennwort darf NIRGENDS im Klartext stehen. Ein
        // Demodatensatz, der es unverschlüsselt ablegt, ließe genau die Stelle
        // ungeprüft, auf die es ankommt (ADR-0008).
        assertEquals(0, zaehle("SELECT count(*) FROM kartenterminal WHERE ist_demo "
                + "AND admin_chiffrat LIKE '%" + Demodaten.DEMO_TERMINALKENNWORT + "%'"),
                "Das Kennwort steht im Klartext in der Datenbank");

        // Und es muss sich zurückgewinnen lassen — verschlüsselt abgelegt ist
        // wertlos, wenn der Agent es nicht wieder herausbekommt.
        try (Statement st = db.verbindung().createStatement();
             ResultSet rs = st.executeQuery("SELECT admin_chiffrat FROM kartenterminal "
                     + "WHERE ist_demo AND admin_chiffrat IS NOT NULL LIMIT 1")) {
            assertTrue(rs.next());
            assertEquals(Demodaten.DEMO_TERMINALKENNWORT, tresor.entschluesseln(rs.getString(1)));
        }
    }

    @Test @Order(10)
    @DisplayName("Ohne Geheimnisspeicher entstehen Demodaten ohne Zugangsdaten — nicht gar keine")
    void ohneTresorTrotzdemDaten() throws Exception {
        if (dd.bestand().vorhanden()) dd.loeschen();
        new Demodaten(db, null).anlegen();

        // Ein fehlender GEHEIMNIS_SCHLUESSEL darf den Demodatensatz nicht
        // verhindern. Wer ihn ausprobieren will, hat ihn oft noch nicht gesetzt.
        int gesamt = de.pinroboter.gemeinsam.Demoszenario.terminalsGesamt();
        assertEquals(gesamt, dd.bestand().terminals());
        assertEquals(gesamt, zaehle("SELECT count(*) FROM kartenterminal "
                + "WHERE ist_demo AND admin_chiffrat IS NULL"));
    }
}
