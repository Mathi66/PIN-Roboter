package de.pinroboter.zentrale;

import de.pinroboter.gemeinsam.Demoszenario;
import de.pinroboter.gemeinsam.Geheimnisspeicher;
import de.pinroboter.gemeinsam.Passwort;
import de.pinroboter.gemeinsam.Totp;

import java.sql.*;
import java.util.*;

/**
 * Erzeugt und entfernt einen vollständigen MVZ-Verbund als Demodatensatz.
 *
 * <p>Zweck: ein realistisches Szenario, an dem sich Mandantentrennung,
 * Berechtigungsebenen und die Bedienbarkeit bei echten Stückzahlen prüfen
 * lassen. Eine Oberfläche, die mit vier Geräten gut aussieht, sagt nichts über
 * ihr Verhalten bei 36 aus.
 *
 * <h2>Struktur (Vorgabe PO, 07.08.2026)</h2>
 * <pre>
 * MVZ-Verbund
 *   ├── Standort Nord    ── 2 Facharztpraxen + 1 Allgemeinpraxis
 *   ├── Standort Mitte   ── dito
 *   └── Standort Süd     ── dito
 *
 * je Praxis:  1 Konnektor mit eigener SMC-B, 4 Kartenterminals
 * gesamt:     9 Praxen, 9 Konnektoren, 36 Terminals
 * </pre>
 *
 * <h2>Warum ein Demo-Kennzeichen und kein „alles löschen"</h2>
 * Jeder erzeugte Datensatz trägt {@code ist_demo = TRUE}. Die Löschfunktion
 * fasst <b>ausschließlich</b> markierte Daten an. Damit kann sie eine echte
 * Praxis nicht einmal versehentlich erwischen — auch dann nicht, wenn jemand
 * den Knopf in einer Produktivinstallation drückt. Ein Löschknopf ohne diese
 * Grenze wäre in einem System, das später Echtdaten hält, fahrlässig.
 */
public class Demodaten {

    /** Einheitliches Kennwort aller Demo-Konten — sie sind zum Ausprobieren da. */
    public static final String DEMO_KENNWORT = "DemoZugangMvzVerbund";

    /**
     * Administrationskennwort der Demo-Kartenterminals.
     *
     * <p>Die Geräte gibt es nur im Simulator; das Kennwort schützt nichts. Es
     * wird trotzdem <b>auf demselben Weg abgelegt wie ein echtes</b> —
     * AES-256-GCM über den Geheimnisspeicher ([ADR-0008]). Ein Demodatensatz,
     * der sein Kennwort im Klartext ablegt, würde genau die Stelle ungeprüft
     * lassen, auf die es ankommt.
     */
    public static final String DEMO_TERMINALKENNWORT = "DemoTerminalAdmin2026";

    /** Benutzername des Administrationszugangs an den Demo-Terminals. */
    public static final String DEMO_TERMINALBENUTZER = "admin";

    /** Zugang zur Konnektor-Verwaltung — ebenfalls nur im Simulator gültig. */
    public static final String DEMO_KONNEKTORBENUTZER = "pinroboter";
    public static final String DEMO_KONNEKTORKENNWORT = "DemoKonnektorZugang2026";

    private final Datenbank db;

    /**
     * Verschlüsselt die Zugangsdaten. {@code null}, wenn kein
     * {@code GEHEIMNIS_SCHLUESSEL} gesetzt ist — dann entstehen Demodaten ohne
     * Zugangsdaten statt gar keiner.
     */
    private final Geheimnisspeicher tresor;

    public Demodaten(Datenbank db) { this(db, null); }

    public Demodaten(Datenbank db, Geheimnisspeicher tresor) {
        this.db = db;
        this.tresor = tresor;
    }

    public record Bestand(int organisationen, int konnektoren, int arbeitsplaetze,
                          int terminals, int terminalsMitSmcb, int benutzer) {
        public boolean vorhanden() { return organisationen > 0; }
    }

    public record Konto(String benutzername, String anzeigename, String rolle,
                        String geltungsbereich, String totpGeheimnis) {}

    // ---------------------------------------------------------------- Abfrage

    public Bestand bestand() {
        try (Statement s = db.verbindung().createStatement();
             ResultSet rs = s.executeQuery("""
                     SELECT (SELECT count(*) FROM organisation   WHERE ist_demo) AS orgs,
                            (SELECT count(*) FROM konnektor      WHERE ist_demo) AS kon,
                            (SELECT count(*) FROM arbeitsplatz   WHERE ist_demo) AS ap,
                            (SELECT count(*) FROM kartenterminal WHERE ist_demo) AS term,
                            (SELECT count(*) FROM kartenterminal WHERE ist_demo AND hat_smcb) AS smcb,
                            (SELECT count(*) FROM benutzer       WHERE ist_demo) AS ben""")) {
            if (rs.next()) {
                return new Bestand(rs.getInt("orgs"), rs.getInt("kon"), rs.getInt("ap"),
                        rs.getInt("term"), rs.getInt("smcb"), rs.getInt("ben"));
            }
        }
        catch (SQLException e) {
            // Nicht verschlucken: Ein stiller SQL-Fehler ist praktisch
            // unauffindbar - so blieb ein fehlerhaftes WHERE monatelang
            // unentdeckt. Der Aufruf schlaegt trotzdem nicht fehl.
            System.err.println("Demodaten.bestand: " + e.getMessage());
        }
        return new Bestand(0, 0, 0, 0, 0, 0);
    }

    // ---------------------------------------------------------------- Anlegen

    /** @return die angelegten Konten, damit sie einmalig angezeigt werden können */
    public List<Konto> anlegen() throws SQLException {
        if (bestand().vorhanden()) {
            throw new IllegalStateException("Es sind bereits Demodaten vorhanden.");
        }
        List<Konto> konten = new ArrayList<>();
        boolean vorherAuto = db.verbindung().getAutoCommit();
        db.verbindung().setAutoCommit(false);
        try {
            long traeger = organisation(Demoszenario.TRAEGER, "VERBUND", null);

            // Der zentrale Konnektor des Traegers (ADR-0012). Drei kleine
            // Betriebsstaetten teilen ihn sich - fuer zwei Kartenterminals lohnt
            // kein eigener. Betrieben wird er vom Traeger, genutzt von anderen:
            // genau die Trennung, die ADR-0012 eingefuehrt hat.
            long rzKonnektor = konnektor(traeger, Demoszenario.RZ_KONNEKTOR,
                    "https://10.99.0.10:443/", "KoCo Konnektor", "Mandant-RZ");

            int laufendeNummer = 0;
            for (String standortname : Demoszenario.STANDORTE) {
                long standort = organisation("Standort " + standortname, "STANDORT", traeger);

                for (var b : Demoszenario.anStandort(standortname)) {
                    laufendeNummer++;
                    long praxis = organisation(b.name(), "EINRICHTUNG", standort,
                            b.bsnr(), b.telematikId());

                    // Ein Konnektor je Betriebsstaette - ausser bei den drei
                    // kleinen, die den zentralen mitnutzen.
                    long konnektor;
                    if (b.imRechenzentrum()) {
                        konnektor = rzKonnektor;
                        konnektorNutzung(rzKonnektor, praxis);
                    } else {
                        konnektor = konnektor(praxis, "Konnektor " + b.name(),
                                "https://10.%d.%d.10:443/".formatted(
                                        standortIndex(standortname) + 1, laufendeNummer),
                                "KoCo Konnektor", "Mandant-" + b.kuerzel());
                    }

                    // Fachbereiche, wo es sie gibt (ADR-0012). Sie haben keine
                    // eigene SMC-B - sie teilen sich die der Betriebsstaette.
                    Map<String, Long> fachbereiche = new LinkedHashMap<>();
                    for (var f : b.fachbereiche()) {
                        fachbereiche.put(f.name(),
                                organisation(f.name() + " " + b.name(), "FACHBEREICH", praxis));
                    }

                    // Informationsmodell des Konnektors ([gemSpec_Kon],
                    // Aufrufkontext TUC_KON_000): ein Empfangs-Arbeitsplatz mit
                    // SMC-B, dazu Behandler-Arbeitsplaetze ohne.
                    //
                    // Fuer die PIN-Automatik ist damit genau EIN Terminal je
                    // Betriebsstaette von Belang. Die uebrigen werden erfasst
                    // und ueberwacht, aber nie verifiziert - sie haben nichts
                    // freizuschalten.
                    int oktett = 20;

                    // Die Arbeitsplatzkennung traegt das Kuerzel: Am geteilten
                    // Konnektor gaebe es sonst dreimal "AP-EMPFANG".
                    long apEmpfang = arbeitsplatz(konnektor, praxis,
                            "AP-" + b.kuerzel() + "-EMPFANG", "Empfang", "EMPFANG");
                    kartenterminal(konnektor, praxis, apEmpfang,
                            b.empfangsterminal(), "Empfang",
                            "10.%d.%d.%d".formatted(standortIndex(standortname) + 1,
                                    laufendeNummer, oktett++),
                            Demoszenario.MODELL, b.kartenhandle(),
                            true,    // SMC-B steckt hier
                            true);   // Managementschnittstelle freigeschaltet

                    for (int i = 1; i <= b.behandlungsraeume(); i++) {
                        // Das Geraet haengt an der untersten passenden Ebene:
                        // am Fachbereich, wo es einen gibt, sonst an der
                        // Betriebsstaette selbst (ADR-0012, Entscheidung 4).
                        long ebene = fachbereiche.getOrDefault(b.fachbereichVon(i), praxis);
                        long apBehandlung = arbeitsplatz(konnektor, ebene,
                                "AP-" + b.kuerzel() + "-BEHANDLUNG-" + i,
                                "Behandlung " + i, "BEHANDLUNG");
                        kartenterminal(konnektor, ebene, apBehandlung,
                                b.behandlungsterminal(i), "Behandlung " + i,
                                "10.%d.%d.%d".formatted(standortIndex(standortname) + 1,
                                        laufendeNummer, oktett++),
                                Demoszenario.MODELL, null,
                                false,   // keine SMC-B
                                true);   // Managementschnittstelle dennoch freigeschaltet
                    }

                    // Eine Gruppe je Betriebsstaette, mit allen ihren Terminals.
                    long gruppe = geraetegruppe(b.name(), praxis);
                    geraeteZuordnen(praxis, b.name(), gruppe);

                    // Ebene 1: zustaendig fuer genau diese Betriebsstaette
                    konten.add(benutzer(b.benutzername(),
                            b.name() + " (" + b.fach() + ")", "BENUTZER", praxis, b.name()));
                }

                // Ebene 2: zustaendig fuer alle Betriebsstaetten dieses Standorts
                konten.add(benutzer("standort." + standortname.toLowerCase()
                                .replace("ü", "ue"),
                        "Leitung Standort " + standortname, "BENUTZER", standort,
                        "Standort " + standortname));
            }

            // Ebene 3: zustaendig fuer das gesamte MVZ
            konten.add(benutzer("verbund.leitung", "Geschäftsführung MVZ", "BENUTZER",
                    traeger, Demoszenario.TRAEGER));

            // Drei Administratoren mit unterschiedlichem Geltungsbereich - genau
            // hier zeigt sich, ob das Rechtemodell traegt: Ein Administrator
            // eines Standorts darf dessen Benutzer verwalten, aber keine fremden.
            konten.add(benutzer("admin.verbund", "Administrator MVZ",
                    "ADMINISTRATOR", traeger, Demoszenario.TRAEGER));
            long standortNord = organisationSuchen("Standort Nord");
            konten.add(benutzer("admin.nord", "Administrator Standort Nord",
                    "ADMINISTRATOR", standortNord, "Standort Nord"));
            var erste = Demoszenario.BETRIEBSSTAETTEN.get(0);
            long ersteBetriebsstaette = organisationSuchen(erste.name());
            konten.add(benutzer("admin.praxis", "Administrator " + erste.name(),
                    "ADMINISTRATOR", ersteBetriebsstaette, erste.name()));

            db.verbindung().commit();
            return konten;
        } catch (SQLException e) {
            db.verbindung().rollback();
            throw e;
        } finally {
            db.verbindung().setAutoCommit(vorherAuto);
        }
    }

    // ---------------------------------------------------------------- Löschen

    /**
     * Entfernt ausschließlich markierte Demodaten.
     *
     * <p>Jede Anweisung trägt {@code WHERE ist_demo} — es gibt keinen Pfad in
     * dieser Klasse, der unmarkierte Daten anfassen könnte.
     */
    public Bestand loeschen() throws SQLException {
        Bestand vorher = bestand();
        boolean vorherAuto = db.verbindung().getAutoCommit();
        db.verbindung().setAutoCommit(false);
        try (Statement s = db.verbindung().createStatement()) {
            // ZUERST die PIN-Kennzeichen der Demokarten — danach sind die
            // Kartenhandles nicht mehr auffindbar.
            //
            // Sie stammen nicht aus dieser Klasse, sondern vom Agenten: Er
            // meldet, fuer welche Karten er eine PIN hat. Bleiben sie stehen,
            // sammeln sich mit jedem Neuaufbau des Demodatensatzes
            // Karteileichen zu Karten, die es nicht mehr gibt — und die
            // Uebersicht zaehlt hinterlegte PINs, die niemand mehr zuordnen
            // kann.
            s.executeUpdate("""
                    DELETE FROM pin_hinterlegt
                    WHERE kartenhandle IN (SELECT kartenhandle FROM kartenterminal
                                           WHERE ist_demo AND kartenhandle IS NOT NULL)""");
            // Dann die Zuordnung, dann die Gruppe: geraet verweist auf
            // geraetegruppe. In umgekehrter Reihenfolge scheitert das Loeschen
            // am Fremdschluessel.
            // Und die Zeilen, die der AGENT zu Demogeräten angelegt hat. Sie
            // tragen kein Demo-Kennzeichen, denn der Agent kennt den
            // Unterschied nicht — er meldet, was der Konnektor ihm nennt.
            // Blieben sie stehen, zeigte die Übersicht Geräte und Gruppen zu
            // einem Bestand, den es nicht mehr gibt.
            s.executeUpdate("""
                    DELETE FROM geraet
                    WHERE terminal_id IN (SELECT terminal_id FROM kartenterminal WHERE ist_demo)""");
            s.executeUpdate("""
                    DELETE FROM geraet
                    WHERE gruppe_id IN (SELECT g.id FROM geraetegruppe g
                                        JOIN organisation o ON o.id = g.organisation_id
                                        WHERE o.ist_demo)""");
            s.executeUpdate("DELETE FROM geraet          WHERE ist_demo");
            s.executeUpdate("""
                    DELETE FROM geraetegruppe
                    WHERE organisation_id IN (SELECT id FROM organisation WHERE ist_demo)""");
            s.executeUpdate("DELETE FROM geraetegruppe   WHERE ist_demo");
            s.executeUpdate("DELETE FROM kartenterminal WHERE ist_demo");
            s.executeUpdate("DELETE FROM arbeitsplatz   WHERE ist_demo");
            s.executeUpdate("DELETE FROM konnektor      WHERE ist_demo");
            s.executeUpdate("DELETE FROM benutzer       WHERE ist_demo");
            // Der Verbund loescht Standorte und Praxen per ON DELETE CASCADE mit.
            s.executeUpdate("DELETE FROM organisation   WHERE ist_demo");
            db.verbindung().commit();
            return vorher;
        } catch (SQLException e) {
            db.verbindung().rollback();
            throw e;
        } finally {
            db.verbindung().setAutoCommit(vorherAuto);
        }
    }

    // ---------------------------------------------------------------- Bausteine

    private long organisation(String bezeichnung, String art, Long eltern) throws SQLException {
        return organisation(bezeichnung, art, eltern, null, null);
    }

    /**
     * Eine Organisationseinheit, wahlweise mit TI-Identität (ADR-0012).
     *
     * <p>BSNR und Telematik-ID stehen nur an der Betriebsstätte — sie ist die
     * Leistungserbringerinstitution. Träger, Standort und Fachbereich haben
     * keine.
     */
    private long organisation(String bezeichnung, String art, Long eltern,
                              String bsnr, String telematikId) throws SQLException {
        try (PreparedStatement p = db.verbindung().prepareStatement("""
                INSERT INTO organisation (bezeichnung, art, eltern_id, bsnr, telematik_id,
                        ist_demo)
                VALUES (?,?,?,?,?,TRUE) RETURNING id""")) {
            p.setString(1, bezeichnung);
            p.setString(2, art);
            if (eltern == null) p.setNull(3, Types.BIGINT); else p.setLong(3, eltern);
            if (bsnr == null) p.setNull(4, Types.VARCHAR); else p.setString(4, bsnr);
            if (telematikId == null) p.setNull(5, Types.VARCHAR);
            else p.setString(5, telematikId);
            try (ResultSet rs = p.executeQuery()) { rs.next(); return rs.getLong(1); }
        }
    }

    private long organisationSuchen(String bezeichnung) throws SQLException {
        try (PreparedStatement p = db.verbindung().prepareStatement(
                "SELECT id FROM organisation WHERE bezeichnung = ? AND ist_demo")) {
            p.setString(1, bezeichnung);
            try (ResultSet rs = p.executeQuery()) { rs.next(); return rs.getLong(1); }
        }
    }

    private long konnektor(long org, String bezeichnung, String adresse,
                           String modell, String mandantId) throws SQLException {
        try (PreparedStatement p = db.verbindung().prepareStatement("""
                INSERT INTO konnektor (organisation_id, bezeichnung, adresse, modell,
                                       mandant_id, clientsystem_id, arbeitsplatz_id,
                                       benutzer_name, passwort_chiffrat, ist_demo)
                VALUES (?,?,?,?,?,'PIN-Roboter','Arbeitsplatz1',?,?,TRUE) RETURNING id""")) {
            p.setLong(1, org);
            p.setString(2, bezeichnung);
            p.setString(3, adresse);
            p.setString(4, modell);
            p.setString(5, mandantId);
            if (tresor == null) {
                p.setNull(6, Types.VARCHAR);
                p.setNull(7, Types.VARCHAR);
            } else {
                p.setString(6, DEMO_KONNEKTORBENUTZER);
                p.setString(7, tresor.verschluesseln(DEMO_KONNEKTORKENNWORT));
            }
            try (ResultSet rs = p.executeQuery()) {
                rs.next();
                long id = rs.getLong(1);
                // Der Betreiber nutzt seinen Konnektor immer - dieselbe
                // Feststellung wie in Bestandsverwaltung.konnektorAnlegen.
                // Fehlte sie hier, waere die Nutzungstabelle im Demobestand
                // anders gefuellt als im echten (ADR-0012).
                konnektorNutzung(id, org);
                return id;
            }
        }
    }

    /** Trägt eine Betriebsstätte als Mitnutzerin eines Konnektors ein (ADR-0012). */
    private void konnektorNutzung(long konnektor, long organisation) throws SQLException {
        try (PreparedStatement p = db.verbindung().prepareStatement(
                "INSERT INTO konnektor_nutzung (konnektor_id, organisation_id) "
                + "VALUES (?,?) ON CONFLICT DO NOTHING")) {
            p.setLong(1, konnektor);
            p.setLong(2, organisation);
            p.executeUpdate();
        }
    }

    private long arbeitsplatz(long konnektor, long org, String kennung,
                              String bezeichnung, String art) throws SQLException {
        try (PreparedStatement p = db.verbindung().prepareStatement("""
                INSERT INTO arbeitsplatz (konnektor_id, organisation_id, arbeitsplatz_id,
                        bezeichnung, art, ist_demo)
                VALUES (?,?,?,?,?,TRUE) RETURNING id""")) {
            p.setLong(1, konnektor);
            p.setLong(2, org);
            p.setString(3, kennung);
            p.setString(4, bezeichnung);
            p.setString(5, art);
            try (ResultSet rs = p.executeQuery()) { rs.next(); return rs.getLong(1); }
        }
    }

    /**
     * Eine Gerätegruppe für eine Praxis, mit allen ihren Kartenterminals.
     *
     * <p>Vorgabe des PO vom 10.08.2026. Vorher legte der Demodatensatz Geräte
     * an, aber keine Gruppen — und die Gruppenseite zeigte damit eine leere
     * Liste, obwohl 36 Terminals erfasst waren. Wer die Funktion „Prüfintervall
     * je Gruppe" beurteilen soll, sieht dann nichts, was er beurteilen könnte.
     *
     * <p>Die Gruppe ist die <b>Vorgabegruppe</b> ihrer Einrichtung: Meldet ein
     * Agent später ein weiteres Gerät an diesem Standort, landet es hier statt
     * ins Leere zu fallen.
     *
     * <p>Eine Gruppe je <b>Einrichtung</b>, nicht je Standort — eine Gruppe
     * liegt nach FA-GER-050 in genau einer Einrichtung. „Standort Nord" mit den
     * Terminals dreier Praxen wäre technisch keine Gruppe, sondern drei.
     */
    private long geraetegruppe(String einrichtung, long organisationId) throws SQLException {
        // Gibt es die Gruppe schon? Der Name „Alle Geräte" ist naheliegend, und
        // ein Betreiber kann ihn längst vergeben haben. Beim ersten Versuch am
        // 10.08.2026 scheiterte daran der GESAMTE Demodatensatz: Der eindeutige
        // Namensindex warf, die Transaktion rollte zurück, und die Oberfläche
        // meldete nur „konnte nicht angelegt werden".
        //
        // Eine vorhandene Gruppe wird deshalb verwendet, nicht verdoppelt. Sie
        // bekommt aber KEIN Demo-Kennzeichen — sie gehört dem Betreiber, und
        // das Entfernen der Demodaten darf sie nicht mitnehmen.
        try (PreparedStatement p = db.verbindung().prepareStatement(
                "SELECT id FROM geraetegruppe WHERE einrichtung = ? AND lower(bezeichnung) = ?")) {
            p.setString(1, einrichtung);
            p.setString(2, "alle geräte");
            try (ResultSet rs = p.executeQuery()) {
                if (rs.next()) {
                    long id = rs.getLong(1);
                    vorgabeSicherstellen(einrichtung, id);
                    return id;
                }
            }
        }
        try (PreparedStatement p = db.verbindung().prepareStatement("""
                INSERT INTO geraetegruppe (bezeichnung, einrichtung, ist_vorgabe,
                        pruefintervall_sekunden, organisation_id, ist_demo)
                VALUES ('Alle Geräte', ?, TRUE, 60, ?, TRUE) RETURNING id""")) {
            p.setString(1, einrichtung);
            p.setLong(2, organisationId);
            try (ResultSet rs = p.executeQuery()) { rs.next(); return rs.getLong(1); }
        }
    }

    /**
     * Macht die Gruppe zur Vorgabe, falls die Einrichtung noch keine hat.
     *
     * <p>Ohne Vorgabegruppe fällt jedes später vom Agenten gemeldete Gerät
     * durch — es steht dann im Protokoll, aber in keiner Geräteliste.
     */
    private void vorgabeSicherstellen(String einrichtung, long gruppe) throws SQLException {
        try (PreparedStatement p = db.verbindung().prepareStatement("""
                UPDATE geraetegruppe SET ist_vorgabe = TRUE WHERE id = ?
                  AND NOT EXISTS (SELECT 1 FROM geraetegruppe
                                  WHERE einrichtung = ? AND ist_vorgabe)""")) {
            p.setLong(1, gruppe);
            p.setString(2, einrichtung);
            p.executeUpdate();
        }
    }

    /**
     * Ordnet die Terminals einer Praxis ihrer Gruppe zu.
     *
     * <p>Die Tabelle {@code geraet} trägt die Gruppenzuordnung; sie wird sonst
     * vom Agenten gefüllt, wenn er ein Gerät erstmals meldet. Im Demodatensatz
     * gibt es keinen Agenten, deshalb hier.
     */
    private void geraeteZuordnen(long organisationId, String einrichtung, long gruppe)
            throws SQLException {
        try (PreparedStatement p = db.verbindung().prepareStatement("""
                INSERT INTO geraet (terminal_id, einrichtung, gruppe_id, bezeichnung,
                        standort, aktiv, ist_demo)
                SELECT t.terminal_id, ?, ?, t.bezeichnung, t.standortangabe, TRUE, TRUE
                FROM kartenterminal t
                WHERE t.organisation_id IN (
                          WITH RECURSIVE teilbaum(id) AS (
                              SELECT ?::bigint
                              UNION ALL
                              SELECT o.id FROM organisation o JOIN teilbaum tb ON o.eltern_id = tb.id)
                          SELECT id FROM teilbaum)
                  AND t.ist_demo
                ON CONFLICT (terminal_id) DO NOTHING""")) {
            p.setString(1, einrichtung);
            p.setLong(2, gruppe);
            p.setLong(3, organisationId);
            p.executeUpdate();
        }
    }

    /**
     * Ein Kartenterminal samt Administrationszugang.
     *
     * <p>Der Zugang wird <b>verschlüsselt</b> abgelegt (ADR-0008) — auf
     * demselben Weg wie ein echter. Fehlt der {@code GEHEIMNIS_SCHLUESSEL},
     * bleibt das Feld leer und der Rest entsteht trotzdem: Ein Demodatensatz,
     * der an einer fehlenden Umgebungsvariablen scheitert, hilft niemandem.
     */
    private void kartenterminal(long konnektor, long org, long arbeitsplatz,
                                String terminalId, String raum, String adresse,
                                String modell, String kartenhandle,
                                boolean hatSmcb, boolean pevimAktiv) throws SQLException {
        try (PreparedStatement p = db.verbindung().prepareStatement("""
                INSERT INTO kartenterminal (terminal_id, konnektor_id, organisation_id,
                        arbeitsplatz_id, bezeichnung, standortangabe, modell,
                        kartenhandle, hat_smcb, pevim_aktiv,
                        admin_benutzer, admin_chiffrat, ist_demo)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,TRUE)""")) {
            p.setString(1, terminalId);
            p.setLong(2, konnektor);
            p.setLong(3, org);
            p.setLong(4, arbeitsplatz);
            p.setString(5, raum);
            p.setString(6, adresse);
            p.setString(7, modell);
            if (kartenhandle == null) p.setNull(8, Types.VARCHAR);
            else p.setString(8, kartenhandle);
            p.setBoolean(9, hatSmcb);
            p.setBoolean(10, pevimAktiv);
            if (tresor == null) {
                p.setNull(11, Types.VARCHAR);
                p.setNull(12, Types.VARCHAR);
            } else {
                p.setString(11, DEMO_TERMINALBENUTZER);
                p.setString(12, tresor.verschluesseln(DEMO_TERMINALKENNWORT));
            }
            p.executeUpdate();
        }
    }

    private Konto benutzer(String benutzername, String anzeigename, String rolle,
                           long organisation, String geltungsbereich) throws SQLException {
        byte[] geheimnis = Totp.geheimnisErzeugen();
        try (PreparedStatement p = db.verbindung().prepareStatement("""
                INSERT INTO benutzer (benutzername, anzeigename, passwort_hash,
                        totp_geheimnis, totp_bestaetigt, rolle, organisation_id, ist_demo)
                VALUES (?,?,?,?,TRUE,?,?,TRUE)""")) {
            p.setString(1, benutzername);
            p.setString(2, anzeigename);
            p.setString(3, Passwort.hashen(DEMO_KENNWORT.toCharArray()));
            p.setString(4, Totp.alsBase32(geheimnis));
            p.setString(5, rolle);
            p.setLong(6, organisation);
            p.executeUpdate();
        }
        return new Konto(benutzername, anzeigename, rolle, geltungsbereich,
                Totp.alsBase32(geheimnis));
    }

    private static int standortIndex(String name) {
        return switch (name) { case "Nord" -> 0; case "Mitte" -> 1; default -> 2; };
    }

}
