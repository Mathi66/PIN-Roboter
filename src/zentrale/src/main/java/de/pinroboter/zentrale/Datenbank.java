package de.pinroboter.zentrale;

import java.sql.*;
import java.util.Properties;

/**
 * Zugriff auf die Datenhaltung der Zentrale (PostgreSQL, ADR-0006).
 *
 * <p>Migrationen laufen beim Start automatisch und idempotent (FA-INS-010):
 * Der Betreiber hat kein IT-Personal, das eine Datenbank von Hand einrichten
 * könnte. Jede Migration prüft ihren eigenen Zustand, damit ein wiederholter
 * Start folgenlos bleibt.
 *
 * <p><b>Warum das Fachprotokoll persistent sein muss:</b> Es ist der Nachweis
 * über das selbsttätige Handeln des Roboters (ADR-0005). Ein Protokoll, das
 * einen Neustart nicht übersteht, ist als Nachweis wertlos — und Neustarts sind
 * durch Produktupdates der Regelfall, nicht die Ausnahme.
 *
 * <p>Der <em>Gerätezustand</em> wird bewusst <b>nicht</b> gespeichert: Er ist
 * flüchtig und wird vom Standort-Agenten binnen eines Prüfintervalls ohnehin neu
 * gemeldet. Ihn zu persistieren hieße, nach einem Neustart einen womöglich
 * veralteten Zustand anzuzeigen — schlechter als ehrlich „noch unbekannt".
 */
public class Datenbank implements AutoCloseable {

    private final Connection verbindung;

    public Datenbank(String url, String benutzer, String kennwort) throws SQLException {
        Properties p = new Properties();
        p.setProperty("user", benutzer);
        p.setProperty("password", kennwort);
        p.setProperty("ApplicationName", "PIN-Roboter Zentrale");
        this.verbindung = DriverManager.getConnection(url, p);
        this.verbindung.setAutoCommit(true);
        migrieren();
    }

    /** Wartet beim Start auf die Datenbank — im Compose-Stack startet sie parallel. */
    public static Datenbank verbinden(String url, String benutzer, String kennwort,
                                      int versuche) throws SQLException {
        SQLException letzter = null;
        for (int i = 1; i <= versuche; i++) {
            try {
                return new Datenbank(url, benutzer, kennwort);
            } catch (SQLException e) {
                letzter = e;
                System.out.println("  Warte auf die Datenbank … (" + i + "/" + versuche + ")");
                try { Thread.sleep(2000); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
        throw letzter;
    }

    private void migrieren() throws SQLException {
        try (Statement s = verbindung.createStatement()) {
            s.execute("""
                CREATE TABLE IF NOT EXISTS schema_stand (
                  version     INTEGER PRIMARY KEY,
                  angewendet  TIMESTAMPTZ NOT NULL DEFAULT now()
                )""");
        }

        migration(1, """
            CREATE TABLE fachprotokoll (
              id            BIGSERIAL PRIMARY KEY,
              zeitpunkt     TIMESTAMPTZ  NOT NULL DEFAULT now(),
              einrichtung   TEXT         NOT NULL,
              geraet        TEXT,
              art           TEXT         NOT NULL,
              stufe         TEXT         NOT NULL,
              erfolg        BOOLEAN      NOT NULL,
              ausloeser     TEXT         NOT NULL,
              vorgang       TEXT,
              text          TEXT,
              handlung      TEXT
            );
            -- Indizes von Anfang an: Ohne sie ist die Protokollansicht bei realen
            -- Datenmengen unbrauchbar (FA-PRO-030 verlangt < 2 s bei 5 Mio Zeilen).
            CREATE INDEX fachprotokoll_zeit_idx        ON fachprotokoll (zeitpunkt DESC);
            CREATE INDEX fachprotokoll_einrichtung_idx ON fachprotokoll (einrichtung, zeitpunkt DESC);
            CREATE INDEX fachprotokoll_geraet_idx      ON fachprotokoll (geraet, zeitpunkt DESC);
            CREATE INDEX fachprotokoll_art_idx         ON fachprotokoll (art, zeitpunkt DESC);
            CREATE INDEX fachprotokoll_vorgang_idx     ON fachprotokoll (vorgang);
            """);

        migration(2, """
            -- Nur anfügbar (ADR-0005): Änderungen und Löschungen einzelner
            -- Einträge sind auf Datenbankebene unterbunden. Die Rotation nach
            -- Speicherbudget läuft über eine eigene Funktion, die den Schutz
            -- bewusst umgeht — sie ist der einzige zulässige Löschweg.
            CREATE OR REPLACE FUNCTION fachprotokoll_unveraenderlich()
            RETURNS TRIGGER AS $$
            BEGIN
              IF current_setting('pinroboter.rotation', true) = 'an' THEN
                RETURN OLD;
              END IF;
              RAISE EXCEPTION
                'Das Fachprotokoll ist nur anfuegbar. Aenderung oder Loeschung einzelner Eintraege ist unzulaessig.';
            END;
            $$ LANGUAGE plpgsql;

            CREATE TRIGGER fachprotokoll_schutz
              BEFORE UPDATE OR DELETE ON fachprotokoll
              FOR EACH ROW EXECUTE FUNCTION fachprotokoll_unveraenderlich();
            """);

        migration(3, """
            CREATE TABLE benutzer (
              id             BIGSERIAL PRIMARY KEY,
              benutzername   TEXT        NOT NULL UNIQUE,
              anzeigename    TEXT        NOT NULL,
              passwort_hash  TEXT        NOT NULL,
              totp_geheimnis TEXT,
              totp_bestaetigt BOOLEAN    NOT NULL DEFAULT FALSE,
              rolle          TEXT        NOT NULL,
              aktiv          BOOLEAN     NOT NULL DEFAULT TRUE,
              fehlversuche   INTEGER     NOT NULL DEFAULT 0,
              gesperrt_bis   TIMESTAMPTZ,
              letztes_totp_fenster BIGINT,
              angelegt       TIMESTAMPTZ NOT NULL DEFAULT now()
            );
            CREATE UNIQUE INDEX benutzer_name_idx ON benutzer (lower(benutzername));
            """);

        migration(4, """
            CREATE TABLE sitzung (
              kennung     TEXT        PRIMARY KEY,
              benutzer_id BIGINT      NOT NULL REFERENCES benutzer(id) ON DELETE CASCADE,
              angelegt    TIMESTAMPTZ NOT NULL DEFAULT now(),
              letzte_tat  TIMESTAMPTZ NOT NULL DEFAULT now()
            );
            CREATE INDEX sitzung_benutzer_idx ON sitzung (benutzer_id);
            """);

        migration(5, """
            CREATE TABLE agent (
              id            BIGSERIAL PRIMARY KEY,
              bezeichnung   TEXT        NOT NULL,
              einrichtung   TEXT        NOT NULL,
              token_hash    TEXT        NOT NULL UNIQUE,
              angelegt      TIMESTAMPTZ NOT NULL DEFAULT now(),
              zuletzt_gesehen TIMESTAMPTZ,
              aktiv         BOOLEAN     NOT NULL DEFAULT TRUE
            );

            -- Einmalig gueltiges, zeitlich begrenztes Registrierungsgeheimnis
            -- (FA-AGT-010). Es wird verbraucht, nicht wiederverwendet: Ein
            -- Geheimnis, das beliebig oft gilt, ist ein Dauerzugang.
            CREATE TABLE registrierung (
              geheimnis_hash TEXT        PRIMARY KEY,
              bezeichnung    TEXT        NOT NULL,
              einrichtung    TEXT        NOT NULL,
              gueltig_bis    TIMESTAMPTZ NOT NULL,
              verbraucht     TIMESTAMPTZ,
              angelegt_von   TEXT        NOT NULL,
              angelegt       TIMESTAMPTZ NOT NULL DEFAULT now()
            );
            """);
    }

    private void migration(int version, String sql) throws SQLException {
        try (PreparedStatement p = verbindung.prepareStatement(
                "SELECT 1 FROM schema_stand WHERE version = ?")) {
            p.setInt(1, version);
            if (p.executeQuery().next()) return;   // idempotent
        }
        try (Statement s = verbindung.createStatement()) {
            s.execute(sql);
        }
        try (PreparedStatement p = verbindung.prepareStatement(
                "INSERT INTO schema_stand (version) VALUES (?)")) {
            p.setInt(1, version);
            p.executeUpdate();
        }
        System.out.println("  Migration " + version + " angewendet");
    }

    public Connection verbindung() { return verbindung; }

    @Override public void close() {
        try { verbindung.close(); } catch (SQLException ignored) { }
    }
}
