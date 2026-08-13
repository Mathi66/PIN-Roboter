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

        migration(12, """
            -- Mandantentrennung, zweite Haelfte.
            --
            -- Fachprotokoll, Alarme und Geraetegruppen fuehrten die Einrichtung
            -- bisher als TEXT. Eine Mandantengrenze ueber einen Textvergleich
            -- waere bruechig: Zwei Einrichtungen mit gleichem Namen in
            -- verschiedenen Verbuenden wuerden zusammenfallen, und eine
            -- Umbenennung wuerde die Zuordnung stillschweigend zerreissen.
            --
            -- Das Textfeld BLEIBT erhalten. Es ist die Anzeige und der Inhalt
            -- des unveraenderlichen Protokolls; es zu ueberschreiben waere eine
            -- nachtraegliche Aenderung an Eintraegen, die per FA-PRO-010
            -- unveraenderlich sind. Die neue Spalte tritt daneben.
            -- BEWUSST OHNE Fremdschluessel. Ein ON DELETE SET NULL wuerde beim
            -- Loeschen einer Organisation ein UPDATE auf dem Protokoll ausloesen
            -- - und das ist unveraenderlich (FA-PRO-010). Die Organisation
            -- liesse sich dann ueberhaupt nicht mehr loeschen.
            --
            -- Die Spalte traegt die Mandantengrenze; der Name steht daneben im
            -- Textfeld und bleibt lesbar, auch wenn die Organisation laengst
            -- entfernt wurde. Genau das ist bei einem Nachweis erwuenscht.
            ALTER TABLE fachprotokoll  ADD COLUMN organisation_id BIGINT;
            ALTER TABLE alarm          ADD COLUMN organisation_id BIGINT
              REFERENCES organisation(id) ON DELETE SET NULL;
            ALTER TABLE geraetegruppe  ADD COLUMN organisation_id BIGINT
              REFERENCES organisation(id) ON DELETE SET NULL;

            -- Datenuebernahme aus dem Textfeld, soweit eindeutig zuzuordnen.
            -- Mehrdeutige Namen bleiben ohne Zuordnung - lieber eine Luecke,
            -- die auffaellt, als eine falsche Zuordnung, die niemand bemerkt.
            UPDATE fachprotokoll f SET organisation_id = o.id
            FROM organisation o
            WHERE f.organisation_id IS NULL AND o.bezeichnung = f.einrichtung
              AND (SELECT count(*) FROM organisation x WHERE x.bezeichnung = f.einrichtung) = 1;

            UPDATE alarm a SET organisation_id = o.id
            FROM organisation o
            WHERE a.organisation_id IS NULL AND o.bezeichnung = a.einrichtung
              AND (SELECT count(*) FROM organisation x WHERE x.bezeichnung = a.einrichtung) = 1;

            UPDATE geraetegruppe g SET organisation_id = o.id
            FROM organisation o
            WHERE g.organisation_id IS NULL AND o.bezeichnung = g.einrichtung
              AND (SELECT count(*) FROM organisation x WHERE x.bezeichnung = g.einrichtung) = 1;

            CREATE INDEX fachprotokoll_org_idx ON fachprotokoll (organisation_id, zeitpunkt DESC);
            CREATE INDEX alarm_org_idx         ON alarm (organisation_id);
            CREATE INDEX geraetegruppe_org_idx ON geraetegruppe (organisation_id);
            """);

        migration(13, """
            -- Einladungen (Block B, FA-BEN-010).
            --
            -- Ein Administrator legt kein Kennwort fest, sondern laedt ein. Der
            -- Eingeladene setzt sein Kennwort selbst und richtet den zweiten
            -- Faktor ein. Grund: Ein vom Administrator gesetztes Kennwort ist
            -- ihm bekannt - damit ist jede spaetere Handlung des Kontos nicht
            -- mehr eindeutig zurechenbar, und genau das braucht ein System, das
            -- selbsttaetig handelt (ADR-0005).
            --
            -- Gespeichert wird nur der HASH des Einladungsgeheimnisses, wie beim
            -- Registrierungsgeheimnis des Agenten. Wer die Datenbank liest, kann
            -- keine Einladung einloesen.
            CREATE TABLE einladung (
              geheimnis_hash TEXT        PRIMARY KEY,
              benutzername   TEXT        NOT NULL,
              anzeigename    TEXT        NOT NULL,
              rolle          TEXT        NOT NULL,
              organisation_id BIGINT     REFERENCES organisation(id) ON DELETE CASCADE,
              gueltig_bis    TIMESTAMPTZ NOT NULL,
              verbraucht     TIMESTAMPTZ,
              eingeladen_von TEXT        NOT NULL,
              angelegt       TIMESTAMPTZ NOT NULL DEFAULT now()
            );
            CREATE UNIQUE INDEX einladung_name_offen_idx
              ON einladung (lower(benutzername)) WHERE verbraucht IS NULL;
            CREATE INDEX einladung_org_idx ON einladung (organisation_id);
            """);

        migration(14, """
            -- Korrektur am Schutz des Fachprotokolls.
            --
            -- Der Trigger gab im privilegierten Fall RETURN OLD zurueck. Bei
            -- einem BEFORE DELETE ist das richtig; bei einem BEFORE UPDATE
            -- bedeutet es aber, dass die Aenderung STILLSCHWEIGEND VERWORFEN
            -- wird - kein Fehler, keine Meldung, nur keine Wirkung.
            --
            -- Aufgefallen ist es bei der Pseudonymisierung (Art. 17 DSGVO):
            -- Sie lief durch, meldete geaenderte Zeilen, und das Protokoll war
            -- unveraendert. Die Rotation war nie betroffen, weil sie loescht.
            CREATE OR REPLACE FUNCTION fachprotokoll_unveraenderlich()
            RETURNS TRIGGER AS $$
            BEGIN
              IF current_setting('pinroboter.rotation', true) = 'an' THEN
                -- Bei DELETE muss OLD zurueck, bei UPDATE NEW. Sonst greift
                -- die Aenderung nicht.
                IF TG_OP = 'DELETE' THEN
                  RETURN OLD;
                ELSE
                  RETURN NEW;
                END IF;
              END IF;
              RAISE EXCEPTION
                'Das Fachprotokoll ist nur anfuegbar. Aenderung oder Loeschung einzelner Eintraege ist unzulaessig.';
            END;
            $$ LANGUAGE plpgsql;
            """);

        migration(15, """
            -- Karten je Terminal (FA-GER-040).
            --
            -- Erfasst werden ALLE Karten, nicht nur die SMC-B. eGK und HBA
            -- stecken in der Praxis regelmaessig mit im Terminal und sind fuer
            -- PEviM ausdruecklich unzulaessig (A_24080). Sie zu verschweigen
            -- waere bequem - der Betreiber soll aber sehen, was in seinem
            -- Geraet steckt und dass der Roboter sie in Ruhe laesst.
            CREATE TABLE ermittelte_karte (
              id             BIGSERIAL PRIMARY KEY,
              agent_id       BIGINT      REFERENCES agent(id) ON DELETE CASCADE,
              terminal_id    TEXT        NOT NULL,
              kartenhandle   TEXT        NOT NULL,
              kartentyp      TEXT        NOT NULL,
              iccsn          TEXT,
              verwaltet      BOOLEAN     NOT NULL DEFAULT FALSE,
              gueltig_bis    DATE,
              gesehen        TIMESTAMPTZ NOT NULL DEFAULT now(),
              UNIQUE (agent_id, kartenhandle)
            );
            CREATE INDEX ermittelte_karte_terminal_idx ON ermittelte_karte (terminal_id);

            -- Ablaufdatum am erfassten Terminal, fuer die Bestandsansicht.
            ALTER TABLE kartenterminal ADD COLUMN zertifikat_gueltig_bis DATE;
            """);

        migration(16, """
            -- PIN-Hinterlegung Ende-zu-Ende (ADR-0009, FA-PIN-010).
            --
            -- Der oeffentliche Schluessel des Agenten. Der Browser holt ihn und
            -- verschluesselt die PIN damit; die Zentrale kann das Ergebnis nicht
            -- lesen. Oeffentliche Schluessel sind nicht geheim - sie duerfen
            -- hier stehen.
            ALTER TABLE agent ADD COLUMN oeffentlicher_schluessel TEXT;
            ALTER TABLE agent ADD COLUMN schluessel_gemeldet TIMESTAMPTZ;

            -- Auftraege an den Agenten: eine PIN hinterlegen oder loeschen.
            --
            -- Das Chiffrat liegt hier NUR BIS ZUR ZUSTELLUNG. Die Zentrale kann
            -- es ohnehin nicht lesen, aber ein Chiffrat, das ewig liegt, ist ein
            -- Angriffsziel fuer spaeter - etwa wenn der private Schluessel des
            -- Agenten eines Tages doch abhandenkommt.
            CREATE TABLE pin_auftrag (
              id             BIGSERIAL PRIMARY KEY,
              agent_id       BIGINT      NOT NULL REFERENCES agent(id) ON DELETE CASCADE,
              kartenhandle   TEXT        NOT NULL,
              art            TEXT        NOT NULL CHECK (art IN ('HINTERLEGEN','LOESCHEN')),
              -- NULL bei LOESCHEN. Nach der Zustellung auch bei HINTERLEGEN.
              chiffrat       TEXT,
              angelegt_von   TEXT        NOT NULL,
              angelegt       TIMESTAMPTZ NOT NULL DEFAULT now(),
              zugestellt     TIMESTAMPTZ,
              quittiert      TIMESTAMPTZ,
              fehler         TEXT
            );
            CREATE INDEX pin_auftrag_offen_idx ON pin_auftrag (agent_id)
              WHERE quittiert IS NULL;

            -- Welche Karte eine hinterlegte PIN hat. NUR das Kennzeichen -
            -- der Wert liegt ausschliesslich beim Agenten.
            CREATE TABLE pin_hinterlegt (
              agent_id     BIGINT      NOT NULL REFERENCES agent(id) ON DELETE CASCADE,
              kartenhandle TEXT        NOT NULL,
              seit         TIMESTAMPTZ NOT NULL DEFAULT now(),
              PRIMARY KEY (agent_id, kartenhandle)
            );
            """);

        migration(17, """
            -- Benachrichtigung per E-Mail (FA-ALA-020).

            -- Der Mailserver gilt instanzweit: In einer Praxis gibt es einen,
            -- und ein Dienstleister betreibt ihn fuer alle betreuten
            -- Einrichtungen. Nur eine Zeile, erzwungen ueber "nur_eine".
            CREATE TABLE mail_server (
              nur_eine      BOOLEAN     PRIMARY KEY DEFAULT TRUE CHECK (nur_eine),
              host          TEXT        NOT NULL,
              port          INTEGER     NOT NULL DEFAULT 587,
              -- Verschluesselung ist Vorgabe. Abschaltbar, weil ein Mailserver
              -- im eigenen Praxisnetz sie manchmal nicht anbietet - dann sagt
              -- die Oberflaeche, was das bedeutet.
              tls_verlangt  BOOLEAN     NOT NULL DEFAULT TRUE,
              benutzer      TEXT,
              -- Wie bei den Geraete-Zugangsdaten (ADR-0008): AES-256-GCM,
              -- Schluessel aus der Umgebung. Nie im Klartext, nie auslesbar.
              kennwort_chiffrat TEXT,
              absender      TEXT        NOT NULL,
              geaendert     TIMESTAMPTZ NOT NULL DEFAULT now()
            );

            -- Wer wird benachrichtigt, ab welchem Schweregrad, und wie lange
            -- wird gesammelt. Je Organisationseinheit einstellbar.
            CREATE TABLE mail_regel (
              organisation_id BIGINT   PRIMARY KEY REFERENCES organisation(id) ON DELETE CASCADE,
              empfaenger    TEXT        NOT NULL,
              -- KRITISCH, WARNUNG oder HINWEIS. Alles darunter wird nicht gemeldet.
              schwelle      TEXT        NOT NULL DEFAULT 'WARNUNG'
                              CHECK (schwelle IN ('KRITISCH','WARNUNG','HINWEIS')),
              -- Das Sammelfenster ist die wichtigste Einstellung dieser
              -- Tabelle. Bei einem Konnektorausfall entstehen in Sekunden
              -- hunderte Alarme; ohne Sammlung waeren das hunderte Mails, und
              -- der Empfaenger richtet einen Filter ein. Dann sieht er auch die
              -- eine wichtige Mail nicht mehr.
              sammelfenster_sekunden INTEGER NOT NULL DEFAULT 300
                              CHECK (sammelfenster_sekunden BETWEEN 0 AND 86400),
              aktiv         BOOLEAN     NOT NULL DEFAULT TRUE,
              geaendert     TIMESTAMPTZ NOT NULL DEFAULT now()
            );

            -- Wartende Alarme. Ein Eintrag entsteht, wenn ein NEUER Alarm
            -- gemeldet wird; verschickt wird, sobald das Sammelfenster der
            -- Einrichtung abgelaufen ist.
            CREATE TABLE mail_warteschlange (
              id            BIGSERIAL   PRIMARY KEY,
              organisation_id BIGINT    NOT NULL REFERENCES organisation(id) ON DELETE CASCADE,
              alarm_id      BIGINT      REFERENCES alarm(id) ON DELETE CASCADE,
              schweregrad   TEXT        NOT NULL,
              objekt        TEXT,
              meldung       TEXT        NOT NULL,
              handlung      TEXT,
              angelegt      TIMESTAMPTZ NOT NULL DEFAULT now(),
              versendet     TIMESTAMPTZ
            );
            CREATE INDEX mail_warteschlange_offen_idx
              ON mail_warteschlange (organisation_id, angelegt) WHERE versendet IS NULL;
            """);

        migration(18, """
            -- Versionsunterschiede zwischen Agent und Zentrale (FA-AGT-040).
            --
            -- Bei 1.000 Standorten ist eine gemischte Landschaft der Normalfall:
            -- Ein Agent, dessen Praxis ueber Nacht ausgeschaltet war, meldet
            -- sich Tage spaeter mit einer aelteren Fassung. Ohne Versionsangabe
            -- zeigt sich das als seltsames Verhalten, dessen Ursache niemand
            -- findet.
            ALTER TABLE agent ADD COLUMN protokollversion INTEGER;
            ALTER TABLE agent ADD COLUMN bausteinversion  TEXT;
            """);

        migration(19, """
            -- PIN ändern (FA-PIN-060, A_24082).
            --
            -- Die Aenderung braucht ZWEI Werte: die alte PIN zum Nachweis und
            -- die neue. Beide werden im Browser verschluesselt; die Zentrale
            -- kann keinen von beiden lesen (ADR-0009). Das zweite Chiffrat
            -- wird wie das erste nach der Zustellung geloescht.
            ALTER TABLE pin_auftrag ADD COLUMN chiffrat_neu TEXT;
            ALTER TABLE pin_auftrag DROP CONSTRAINT IF EXISTS pin_auftrag_art_check;
            ALTER TABLE pin_auftrag ADD CONSTRAINT pin_auftrag_art_check
              CHECK (art IN ('HINTERLEGEN','LOESCHEN','AENDERN'));
            """);

        migration(20, """
            -- Neue Versionen erkennen (FA-UPD-010, ADR-0001).
            --
            -- Nur EINE Zeile: Eine Instanz bezieht ihre Abbilder aus einer
            -- Registry. Erzwungen ueber "nur_eine".
            CREATE TABLE registry_zugang (
              nur_eine       BOOLEAN     PRIMARY KEY DEFAULT TRUE CHECK (nur_eine),
              registry       TEXT        NOT NULL,
              abbild         TEXT        NOT NULL,
              benutzer       TEXT,
              -- Wie alle Zugangsdaten: AES-256-GCM (ADR-0008).
              token_chiffrat TEXT,
              -- Der letzte Fehler wird GESPEICHERT, nicht nur geloggt. Ein
              -- abgelaufenes Token darf nicht als "keine Updates vorhanden"
              -- erscheinen — genau das verlangt die Akzeptanz von FA-UPD-010.
              letzter_fehler TEXT,
              zuletzt_geprueft    TIMESTAMPTZ,
              gefundene_versionen TEXT,
              geaendert      TIMESTAMPTZ NOT NULL DEFAULT now()
            );
            """);

        migration(21, """
            -- Gerätegruppen und Geräte als Demo kennzeichnen.
            --
            -- Der Demodatensatz legt seit dem 10.08.2026 auch Gruppen an und
            -- ordnet die Kartenterminals zu (Vorgabe PO). Ohne dieses Merkmal
            -- ließe er sich nicht mehr rückstandsfrei entfernen — und genau
            -- das verspricht die Oberfläche: "Echte Bestandsdaten bleiben
            -- unberührt."
            ALTER TABLE geraetegruppe ADD COLUMN IF NOT EXISTS ist_demo BOOLEAN NOT NULL DEFAULT FALSE;
            ALTER TABLE geraet        ADD COLUMN IF NOT EXISTS ist_demo BOOLEAN NOT NULL DEFAULT FALSE;
            """);

        migration(22, """
            -- Kartenterminals ohne Konnektor erfassen (Vorgabe PO, 11.08.2026).
            --
            -- Bisher war ein Konnektor Pflicht, und die Einrichtung des
            -- Terminals ergab sich aus ihm. In der Praxis passt das nicht immer:
            -- Ein Gerät wird geliefert und aufgestellt, BEVOR es am Konnektor
            -- angemeldet ist. Wer es dann nicht erfassen kann, führt seinen
            -- Bestand nebenher auf Papier — und der Bestand in diesem Produkt
            -- ist ab da unvollständig.
            --
            -- Die Einrichtung wird damit zur eigenständigen Angabe. Sie war es
            -- technisch schon (organisation_id stand immer in der Zeile), aber
            -- fachlich abgeleitet. Jetzt ist sie das Pflichtfeld und der
            -- Konnektor die Ergänzung.
            --
            -- Was ein Terminal ohne Konnektor NICHT kann: an der PIN-Automatik
            -- teilnehmen. Das SICCT-Kommando sendet der Konnektor; ohne ihn
            -- gibt es keine Verifikation, die zu beantworten wäre. Die
            -- Oberfläche sagt das ausdrücklich, statt ein Gerät stumm nie
            -- freizuschalten.
            ALTER TABLE kartenterminal ALTER COLUMN konnektor_id DROP NOT NULL;
            """);

        migration(23, """
            -- Gerätegruppen ohne Organisation: aufräumen und künftig verhindern.
            --
            -- BEFUND (PO, 11.08.2026): Die Übersicht meldete 9 Gruppen, die
            -- Gruppenliste zeigte 19. Beide hatten recht — sie zählten
            -- Verschiedenes.
            --
            -- Ursache: geraetegruppe.organisation_id war optional, und
            -- vorgabegruppeAnlegen() setzte sie über eine Unterabfrage. Fand die
            -- keine eindeutige Organisation, entstand die Gruppe trotzdem, nur
            -- eben mit NULL. Eine solche Zeile ist für JEDE mandantengebundene
            -- Abfrage unsichtbar (NULL = ANY(...) ist niemals wahr) — sie
            -- erscheint nur dem Instanzbetreiber, dessen Geltungsbereich keine
            -- Bedingung erzeugt. Daher die zwei Zahlen.
            --
            -- Das ist mehr als ein Anzeigefehler: Ein Datensatz ohne
            -- Mandantenzuordnung gehört in diesem Produkt nicht in die
            -- Datenbank. Er lässt sich weder einer Einrichtung zurechnen noch
            -- einem Zuständigen zeigen.

            -- 1. Was sich zuordnen lässt, wird zugeordnet: über den
            --    Einrichtungsnamen, aber nur wenn er eindeutig ist.
            UPDATE geraetegruppe g SET organisation_id = o.id
              FROM organisation o
             WHERE g.organisation_id IS NULL
               AND o.bezeichnung = g.einrichtung
               AND (SELECT count(*) FROM organisation x WHERE x.bezeichnung = g.einrichtung) = 1;

            -- 2. Der Rest ist Altlast ohne Bezug. Gelöscht wird NUR, was leer
            --    ist — eine Gruppe mit Geräten anzufassen wäre Datenverlust.
            DELETE FROM geraetegruppe g
             WHERE g.organisation_id IS NULL
               AND NOT EXISTS (SELECT 1 FROM geraet t WHERE t.gruppe_id = g.id);

            -- 3. Ab jetzt unmöglich. Sollte hier noch eine Zeile übrig sein —
            --    ohne Organisation, aber MIT Geräten —, schlägt die Migration
            --    fehl. Das ist gewollt: Dieser Fall braucht eine Entscheidung
            --    des Betreibers und darf nicht stillschweigend weiterlaufen.
            ALTER TABLE geraetegruppe ALTER COLUMN organisation_id SET NOT NULL;
            """);

        migration(24, """
            -- Die Gerätehierarchie folgt der Organisationsstruktur (ADR-0012,
            -- Vorgabe PO 12.08.2026).

            -- 1. Zwei neue Ebenen.
            --
            -- LEISTUNGSERBRINGER: das zugelassene MVZ zwischen Trägergesellschaft
            -- und Betriebsstätte. FACHBEREICH: unterhalb der Betriebsstätte, denn
            -- in einer Klinik stehen Kartenterminals nicht „irgendwo in der
            -- Betriebsstätte".
            --
            -- Alle Zwischenebenen bleiben OPTIONAL. Eine Einzelpraxis ist
            -- weiterhin VERBUND → EINRICHTUNG.
            ALTER TABLE organisation DROP CONSTRAINT IF EXISTS organisation_art_check;
            ALTER TABLE organisation ADD CONSTRAINT organisation_art_check
              CHECK (art IN ('VERBUND','LEISTUNGSERBRINGER','STANDORT',
                             'EINRICHTUNG','FACHBEREICH'));

            -- 2. Die Betriebsstätte trägt ihre TI-Identität.
            --
            -- Beides optional: Beim Einrichten liegen die Angaben oft noch nicht
            -- vor, und eine Pflichtangabe, die niemand hat, führt zu
            -- Fantasiewerten. Nur eine EINRICHTUNG ist die LEI — nur dort sind
            -- die Felder fachlich sinnvoll.
            ALTER TABLE organisation ADD COLUMN IF NOT EXISTS bsnr TEXT;
            ALTER TABLE organisation ADD COLUMN IF NOT EXISTS telematik_id TEXT;

            -- 3. Konnektor ↔ Einrichtung wird n:m.
            --
            -- Mit Konnektor as a Service im Rechenzentrum teilen sich mehrere
            -- Leistungserbringer einen Konnektor. Bisher hätte man ihn mehrfach
            -- erfassen müssen — gleiche Adresse, verschiedene Kennungen, jeder
            -- Zustandswechsel mehrfach protokolliert.
            --
            -- konnektor.organisation_id bleibt und bedeutet ab jetzt den
            -- BETREIBER: Wer den Konnektor betreibt, verwaltet ihn; wer ihn
            -- nutzt, sieht ihn. Ohne diese Trennung könnte eine kleine Praxis
            -- den Konnektor umkonfigurieren, an dem zwanzig andere hängen.
            CREATE TABLE IF NOT EXISTS konnektor_nutzung (
              konnektor_id    BIGINT NOT NULL REFERENCES konnektor(id) ON DELETE CASCADE,
              organisation_id BIGINT NOT NULL REFERENCES organisation(id) ON DELETE CASCADE,
              angelegt        TIMESTAMPTZ NOT NULL DEFAULT now(),
              PRIMARY KEY (konnektor_id, organisation_id)
            );
            CREATE INDEX IF NOT EXISTS konnektor_nutzung_org_idx
              ON konnektor_nutzung (organisation_id);

            -- Bestehende Konnektoren: Betreiber bleibt, Nutzung kommt dazu.
            -- Kein Datensatz verliert seine Zuordnung.
            INSERT INTO konnektor_nutzung (konnektor_id, organisation_id)
              SELECT id, organisation_id FROM konnektor
              ON CONFLICT DO NOTHING;

            -- 4. Konnektor oder HSK — die Zuordnungsregel unterscheidet sich.
            --
            -- Bei TI 2.0 gilt eine feste Zuordnung HSK-Instanz → eine LEI; ein
            -- Konnektor im RZ darf mehrere bedienen. Ohne dieses Merkmal ließe
            -- sich die Regel nicht durchsetzen.
            ALTER TABLE konnektor ADD COLUMN IF NOT EXISTS art TEXT NOT NULL DEFAULT 'KONNEKTOR';
            ALTER TABLE konnektor DROP CONSTRAINT IF EXISTS konnektor_art_check;
            ALTER TABLE konnektor ADD CONSTRAINT konnektor_art_check
              CHECK (art IN ('KONNEKTOR','HSK'));
            -- Aus dem Modellnamen ableiten, wo es eindeutig ist.
            UPDATE konnektor SET art = 'HSK'
             WHERE art = 'KONNEKTOR' AND modell ILIKE '%HSK%';
            """);

        // ── 25 · Ein Fremdschlüssel, der seiner eigenen Spalte widersprach ──
        //
        // Migration 23 setzte geraetegruppe.organisation_id auf NOT NULL: Eine
        // Gerätegruppe ohne Organisationseinheit ist für niemanden sichtbar.
        // Der Fremdschlüssel stand aber weiter auf ON DELETE SET NULL — die
        // Datenbank hätte beim Löschen einer Einheit genau den Wert setzen
        // sollen, den sie zugleich verbietet.
        //
        // Wirkung: Das Löschen des Demodatensatzes brach mit
        // „null value in column organisation_id violates not-null constraint"
        // ab, sobald der Agent eine eigene Vorgabegruppe angelegt hatte. Der
        // Fehler war seit Migration 23 vorhanden und fiel erst am 12.08.2026
        // auf — die Nachweise löschten immer nur Gruppen, die sie selbst
        // angelegt hatten.
        //
        // Richtig ist CASCADE: Die Gruppe gehört zu ihrer Einheit. Verschwindet
        // die Einheit, verschwindet die Gruppe — und mit ihr die
        // Gerätezuordnungen, die ohne Gruppe keinen Sinn ergeben.
        migration(25, """
            ALTER TABLE geraetegruppe DROP CONSTRAINT IF EXISTS geraetegruppe_organisation_id_fkey;
            ALTER TABLE geraetegruppe ADD CONSTRAINT geraetegruppe_organisation_id_fkey
              FOREIGN KEY (organisation_id) REFERENCES organisation(id) ON DELETE CASCADE;

            -- geraet.gruppe_id hatte gar keine Regel und hätte die Kette
            -- blockiert. gruppe_id ist NOT NULL — eine Zuordnung ohne Gruppe
            -- gibt es nicht, also geht die Zeile mit.
            ALTER TABLE geraet DROP CONSTRAINT IF EXISTS geraet_gruppe_id_fkey;
            ALTER TABLE geraet ADD CONSTRAINT geraet_gruppe_id_fkey
              FOREIGN KEY (gruppe_id) REFERENCES geraetegruppe(id) ON DELETE CASCADE;
            """);

        // ── 26 · Vorgabegruppen bekommen einen sprechenden Namen ────────────
        //
        // Befund des PO (13.08.2026): In einem MVZ mit neun Betriebsstätten
        // hießen alle neun Vorgabegruppen „Alle Geräte". Die Unterscheidung
        // steckte allein in der Nachbarspalte „Einrichtung" — und überall dort,
        // wo ein Gruppenname OHNE diese Spalte auftaucht (Auswahlfeld,
        // Fachprotokoll, Fehlermeldung), sagte er gar nichts.
        //
        // Umbenannt wird nur, was noch den alten Namen trägt. Wer seine
        // Vorgabegruppe selbst umbenannt hat, behält seinen Namen — eine
        // Migration, die eine bewusste Benennung überschreibt, wäre übergriffig.
        //
        // Die Einrichtung ist instanzweit eindeutig, der neue Name damit auch.
        migration(26, """
            UPDATE geraetegruppe
               SET bezeichnung = 'Alle Geräte – ' || einrichtung
             WHERE ist_vorgabe
               AND bezeichnung = 'Alle Geräte'
               AND NOT EXISTS (
                     SELECT 1 FROM geraetegruppe g2
                      WHERE g2.einrichtung = geraetegruppe.einrichtung
                        AND lower(g2.bezeichnung) = lower('Alle Geräte – ' || geraetegruppe.einrichtung));
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
        try { verbindung.close(); }
        catch (SQLException e) {
            // Nicht verschlucken: Ein stiller SQL-Fehler ist praktisch
            // unauffindbar - so blieb ein fehlerhaftes WHERE monatelang
            // unentdeckt. Der Aufruf schlaegt trotzdem nicht fehl.
            System.err.println("Datenbank.verbindung: " + e.getMessage());
        }
    }
}
