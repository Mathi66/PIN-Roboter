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

        migration(6, """
            CREATE TABLE geraetegruppe (
              id             BIGSERIAL PRIMARY KEY,
              bezeichnung    TEXT        NOT NULL,
              einrichtung    TEXT        NOT NULL,
              -- Prüfintervall je Gruppe (FA-UEB-010). Bei 5.000 Terminals ist die
              -- Last nur so steuerbar: Die Notaufnahme braucht 30 Sekunden, das
              -- Archiv kommt mit fünf Minuten aus.
              pruefintervall_sekunden INTEGER NOT NULL DEFAULT 60
                CHECK (pruefintervall_sekunden >= 10),
              ist_vorgabe    BOOLEAN     NOT NULL DEFAULT FALSE,
              angelegt       TIMESTAMPTZ NOT NULL DEFAULT now()
            );
            CREATE UNIQUE INDEX geraetegruppe_name_idx
              ON geraetegruppe (einrichtung, lower(bezeichnung));

            -- Genau eine Vorgabegruppe je Einrichtung: Ein neu erkanntes Gerät
            -- muss irgendwo landen, ohne dass jemand es vorher zuordnet.
            CREATE UNIQUE INDEX geraetegruppe_vorgabe_idx
              ON geraetegruppe (einrichtung) WHERE ist_vorgabe;

            CREATE TABLE geraet (
              terminal_id    TEXT        PRIMARY KEY,
              einrichtung    TEXT        NOT NULL,
              gruppe_id      BIGINT      NOT NULL REFERENCES geraetegruppe(id),
              bezeichnung    TEXT,
              standort       TEXT,
              aktiv          BOOLEAN     NOT NULL DEFAULT TRUE,
              erstmals_gesehen TIMESTAMPTZ NOT NULL DEFAULT now()
            );
            CREATE INDEX geraet_gruppe_idx ON geraet (gruppe_id);

            INSERT INTO geraetegruppe (bezeichnung, einrichtung, ist_vorgabe)
            VALUES ('Nicht zugeordnet', 'Praxis', TRUE);
            """);

        migration(7, """
            CREATE TABLE alarm (
              id            BIGSERIAL PRIMARY KEY,
              einrichtung   TEXT        NOT NULL,
              objekt        TEXT,                    -- Gerät, Karte, Agent …
              art           TEXT        NOT NULL,
              schweregrad   TEXT        NOT NULL,
              meldung       TEXT        NOT NULL,
              handlung      TEXT,
              seit          TIMESTAMPTZ NOT NULL DEFAULT now(),
              zuletzt       TIMESTAMPTZ NOT NULL DEFAULT now(),
              quittiert_von TEXT,
              quittiert_am  TIMESTAMPTZ,
              behoben_am    TIMESTAMPTZ
            );

            -- Je Objekt und Art hoechstens EIN offener Alarm. Das ist der
            -- Unterschied zwischen Alarm und Protokolleintrag: Das Protokoll
            -- sammelt Ereignisse, der Alarm beschreibt einen ANDAUERNDEN
            -- Zustand. Ohne diese Regel erzeugt ein alle vier Sekunden
            -- pruefender Agent 900 Alarme pro Stunde fuer dasselbe Problem.
            CREATE UNIQUE INDEX alarm_offen_idx
              ON alarm (einrichtung, coalesce(objekt, ''), art)
              WHERE behoben_am IS NULL;

            CREATE INDEX alarm_seit_idx ON alarm (seit DESC);
            """);

        migration(8, """
            -- Dreistufige Organisationsstruktur (ADR-0002, erweitert am 07.08.2026).
            --
            -- ADR-0002 sah zwei Ebenen vor: Serviceorganisation und Einrichtung.
            -- Der MVZ-Verbund des POs hat drei: Verbund, Standort, Praxis - und
            -- die Berechtigungsebenen haengen genau daran. Ohne die mittlere
            -- Ebene gaebe es keinen Geltungsbereich fuer einen Benutzer, der
            -- einen Standort betreut, aber nicht den ganzen Verbund.
            CREATE TABLE organisation (
              id            BIGSERIAL PRIMARY KEY,
              bezeichnung   TEXT        NOT NULL,
              art           TEXT        NOT NULL
                              CHECK (art IN ('VERBUND','STANDORT','EINRICHTUNG')),
              eltern_id     BIGINT      REFERENCES organisation(id) ON DELETE CASCADE,
              -- Demo-Daten sind technisch markiert. Die Loeschfunktion fasst
              -- ausschliesslich markierte Datensaetze an und kann echte Praxen
              -- damit nicht einmal versehentlich erwischen.
              ist_demo      BOOLEAN     NOT NULL DEFAULT FALSE,
              angelegt      TIMESTAMPTZ NOT NULL DEFAULT now()
            );
            CREATE INDEX organisation_eltern_idx ON organisation (eltern_id);
            CREATE INDEX organisation_demo_idx   ON organisation (ist_demo);

            -- Ein Verbund hat keine Eltern, alles andere schon.
            ALTER TABLE organisation ADD CONSTRAINT organisation_hierarchie
              CHECK ((art = 'VERBUND' AND eltern_id IS NULL)
                  OR (art <> 'VERBUND' AND eltern_id IS NOT NULL));

            CREATE TABLE konnektor (
              id             BIGSERIAL PRIMARY KEY,
              organisation_id BIGINT     NOT NULL REFERENCES organisation(id) ON DELETE CASCADE,
              bezeichnung    TEXT        NOT NULL,
              adresse        TEXT        NOT NULL,
              modell         TEXT,
              mandant_id     TEXT        NOT NULL,
              clientsystem_id TEXT       NOT NULL,
              arbeitsplatz_id TEXT       NOT NULL,
              aktiv          BOOLEAN     NOT NULL DEFAULT TRUE,
              ist_demo       BOOLEAN     NOT NULL DEFAULT FALSE,
              angelegt       TIMESTAMPTZ NOT NULL DEFAULT now()
            );
            CREATE INDEX konnektor_org_idx ON konnektor (organisation_id);

            CREATE TABLE kartenterminal (
              id             BIGSERIAL PRIMARY KEY,
              terminal_id    TEXT        NOT NULL UNIQUE,
              konnektor_id   BIGINT      NOT NULL REFERENCES konnektor(id) ON DELETE CASCADE,
              organisation_id BIGINT     NOT NULL REFERENCES organisation(id) ON DELETE CASCADE,
              gruppe_id      BIGINT      REFERENCES geraetegruppe(id),
              bezeichnung    TEXT        NOT NULL,
              standortangabe TEXT,
              modell         TEXT,
              kartenhandle   TEXT,
              iccsn          TEXT,
              aktiv          BOOLEAN     NOT NULL DEFAULT TRUE,
              ist_demo       BOOLEAN     NOT NULL DEFAULT FALSE,
              angelegt       TIMESTAMPTZ NOT NULL DEFAULT now()
            );
            CREATE INDEX kartenterminal_konnektor_idx ON kartenterminal (konnektor_id);
            CREATE INDEX kartenterminal_org_idx       ON kartenterminal (organisation_id);

            -- Geltungsbereich eines Benutzers: die Organisationseinheit, fuer die
            -- er zustaendig ist. Ebene 1 = Praxis, Ebene 2 = Standort,
            -- Ebene 3 = Verbund. Die Ebene ergibt sich aus der Art der
            -- verknuepften Organisation - sie wird nicht getrennt gespeichert,
            -- weil zwei Quellen fuer dieselbe Wahrheit irgendwann auseinanderlaufen.
            ALTER TABLE benutzer ADD COLUMN organisation_id BIGINT
              REFERENCES organisation(id) ON DELETE SET NULL;
            ALTER TABLE benutzer ADD COLUMN ist_demo BOOLEAN NOT NULL DEFAULT FALSE;
            CREATE INDEX benutzer_org_idx ON benutzer (organisation_id);
            """);

        migration(9, """
            -- Arbeitsplaetze aus dem Informationsmodell des Konnektors
            -- ([gemSpec_Kon]). Ein Arbeitsplatz buendelt ein oder mehrere
            -- Kartenterminals; der Aufrufkontext jeder Fachoperation nennt ihn
            -- neben Mandant und Clientsystem (TUC_KON_000).
            --
            -- Warum das hier steht und nicht nur im Konnektor: Der Aufrufkontext
            -- entscheidet, WELCHES Terminal der Konnektor anspricht. Ohne die
            -- Zuordnung im eigenen Bestand koennte der Agent nicht bestimmen,
            -- mit welcher Arbeitsplatz-Kennung er eine bestimmte SMC-B erreicht.
            CREATE TABLE arbeitsplatz (
              id              BIGSERIAL PRIMARY KEY,
              konnektor_id    BIGINT      NOT NULL REFERENCES konnektor(id) ON DELETE CASCADE,
              organisation_id BIGINT      NOT NULL REFERENCES organisation(id) ON DELETE CASCADE,
              arbeitsplatz_id TEXT        NOT NULL,
              bezeichnung     TEXT        NOT NULL,
              art             TEXT        NOT NULL
                                CHECK (art IN ('EMPFANG','BEHANDLUNG','SONSTIGES')),
              ist_demo        BOOLEAN     NOT NULL DEFAULT FALSE,
              angelegt        TIMESTAMPTZ NOT NULL DEFAULT now(),
              UNIQUE (konnektor_id, arbeitsplatz_id)
            );
            CREATE INDEX arbeitsplatz_konnektor_idx ON arbeitsplatz (konnektor_id);

            ALTER TABLE kartenterminal ADD COLUMN arbeitsplatz_id BIGINT
              REFERENCES arbeitsplatz(id) ON DELETE SET NULL;

            -- Ob in diesem Terminal eine SMC-B steckt. In einer Praxis steckt sie
            -- typischerweise am Empfang; die Behandlungsterminals lesen eGK und
            -- brauchen keine. Nur Terminals MIT SMC-B sind fuer die PIN-Automatik
            -- ueberhaupt von Belang - die uebrigen werden erfasst und ueberwacht,
            -- aber nie verifiziert.
            ALTER TABLE kartenterminal ADD COLUMN hat_smcb BOOLEAN NOT NULL DEFAULT FALSE;

            -- Managementschnittstelle freigeschaltet (PEviM aktiv, A_24078).
            ALTER TABLE kartenterminal ADD COLUMN pevim_aktiv BOOLEAN NOT NULL DEFAULT FALSE;

            CREATE INDEX kartenterminal_smcb_idx ON kartenterminal (hat_smcb)
              WHERE hat_smcb;
            """);

        migration(10, """
            -- Bestandsverwaltung (FA-GER-010, FA-GER-020).
            --
            -- Zugangsdaten liegen VERSCHLUESSELT (AES-GCM, siehe
            -- Geheimnisspeicher). Sie muessen wieder verwendbar sein - ein Hash
            -- genuegt hier also nicht, anders als beim Anmeldekennwort. Die
            -- Spalten heissen bewusst "_chiffrat": Wer sie in einer Abfrage
            -- sieht, soll sofort erkennen, dass hier kein Klartext steht.
            ALTER TABLE konnektor ADD COLUMN benutzer_name       TEXT;
            ALTER TABLE konnektor ADD COLUMN passwort_chiffrat   TEXT;

            -- Nur gesetzt, wenn die herstellerspezifische Webadmin-Schnittstelle
            -- genutzt wird. Sie ist immer optional (NFA-QS-035) - ohne sie
            -- bleibt alles von Hand erfassbar.
            ALTER TABLE konnektor ADD COLUMN webadmin_adresse    TEXT;

            -- Ergebnis der letzten Erreichbarkeitspruefung. Ein nicht
            -- erreichbarer Konnektor darf gespeichert werden: Bei der
            -- Ersteinrichtung steht die Gegenstelle oft noch gar nicht.
            ALTER TABLE konnektor ADD COLUMN zuletzt_erreichbar  BOOLEAN;
            ALTER TABLE konnektor ADD COLUMN zuletzt_geprueft    TIMESTAMPTZ;
            ALTER TABLE konnektor ADD COLUMN pruefhinweis        TEXT;

            -- Administrationszugang des Kartenterminals. Ueber ihn laeuft
            -- PEviM: die PIN wird an die Managementschnittstelle uebergeben
            -- ([gemSpec_KT] 2.4.9).
            ALTER TABLE kartenterminal ADD COLUMN adresse            TEXT;
            ALTER TABLE kartenterminal ADD COLUMN admin_benutzer     TEXT;
            ALTER TABLE kartenterminal ADD COLUMN admin_chiffrat     TEXT;
            ALTER TABLE kartenterminal ADD COLUMN seriennummer       TEXT;
            ALTER TABLE kartenterminal ADD COLUMN firmware           TEXT;
            ALTER TABLE kartenterminal ADD COLUMN zuletzt_erreichbar BOOLEAN;
            ALTER TABLE kartenterminal ADD COLUMN zuletzt_geprueft   TIMESTAMPTZ;
            ALTER TABLE kartenterminal ADD COLUMN pruefhinweis       TEXT;

            -- Suche ueber Bezeichnung und Standort (FA-GER-060). Bei 5.000
            -- Geraeten entscheidet der Index darueber, ob die Liste in unter
            -- einer Sekunde steht.
            CREATE INDEX kartenterminal_suche_idx
              ON kartenterminal (lower(bezeichnung), lower(coalesce(standortangabe,'')));
            """);

        migration(11, """
            -- Was der Agent beim Konnektor vorgefunden hat (FA-GER-030).
            --
            -- Streng getrennt vom erfassten Bestand: Diese Tabelle ist ein
            -- BEFUND, keine Konfiguration. Nichts hier wird selbsttaetig in
            -- den Bestand uebernommen - das verlangt FA-GER-030 ausdruecklich
            -- ("Uebernahme erst nach ausdruecklicher Bestaetigung, nie
            -- automatisch"). Waere es anders, koennte ein falsch
            -- konfigurierter Konnektor den gepflegten Bestand ueberschreiben.
            CREATE TABLE ermittelt_terminal (
              id                BIGSERIAL PRIMARY KEY,
              agent_id          BIGINT      REFERENCES agent(id) ON DELETE CASCADE,
              terminal_id       TEXT        NOT NULL,
              name              TEXT,
              modell            TEXT,
              firmware          TEXT,
              erreichbar        BOOLEAN     NOT NULL DEFAULT FALSE,
              pevim_unterstuetzt BOOLEAN    NOT NULL DEFAULT FALSE,
              pevim_aktiv       BOOLEAN     NOT NULL DEFAULT FALSE,
              hat_smcb          BOOLEAN     NOT NULL DEFAULT FALSE,
              kartenhandle      TEXT,
              iccsn             TEXT,
              gesehen           TIMESTAMPTZ NOT NULL DEFAULT now(),
              uebernommen_am    TIMESTAMPTZ,
              UNIQUE (agent_id, terminal_id)
            );

            -- Das Informationsmodell des Konnektors (FA-GER-035). Als Text
            -- abgelegt statt zerlegt: Es ist herstellerspezifisch, und ein
            -- eigenes Schema dafuer waere eine Festlegung auf einen Hersteller,
            -- bevor wir einen zweiten gesehen haben.
            CREATE TABLE ermitteltes_modell (
              agent_id     BIGINT      PRIMARY KEY REFERENCES agent(id) ON DELETE CASCADE,
              verfuegbar   BOOLEAN     NOT NULL DEFAULT FALSE,
              inhalt       TEXT,
              gesehen      TIMESTAMPTZ NOT NULL DEFAULT now()
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
