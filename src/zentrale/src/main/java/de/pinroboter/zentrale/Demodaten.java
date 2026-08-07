package de.pinroboter.zentrale;

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

    private final Datenbank db;

    public Demodaten(Datenbank db) { this.db = db; }

    public record Bestand(int organisationen, int konnektoren, int terminals, int benutzer) {
        public boolean vorhanden() { return organisationen > 0; }
    }

    public record Konto(String benutzername, String anzeigename, String rolle,
                        String geltungsbereich, String totpGeheimnis) {}

    // ---------------------------------------------------------------- Abfrage

    public Bestand bestand() {
        try (Statement s = db.verbindung().createStatement();
             ResultSet rs = s.executeQuery("""
                     SELECT (SELECT count(*) FROM organisation    WHERE ist_demo) AS orgs,
                            (SELECT count(*) FROM konnektor       WHERE ist_demo) AS kon,
                            (SELECT count(*) FROM kartenterminal  WHERE ist_demo) AS term,
                            (SELECT count(*) FROM benutzer        WHERE ist_demo) AS ben""")) {
            if (rs.next()) {
                return new Bestand(rs.getInt("orgs"), rs.getInt("kon"),
                        rs.getInt("term"), rs.getInt("ben"));
            }
        } catch (SQLException ignored) { }
        return new Bestand(0, 0, 0, 0);
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
            long verbund = organisation("MVZ-Verbund Musterstadt", "VERBUND", null);

            String[] standorte = {"Nord", "Mitte", "Süd"};
            // Zwei Facharztpraxen und eine Allgemeinpraxis je Standort.
            String[][] praxen = {
                    {"Kardiologie",   "FACHARZT"},
                    {"Orthopädie",    "FACHARZT"},
                    {"Allgemeinmedizin", "ALLGEMEIN"}
            };

            int laufendeNummer = 0;
            for (String standortname : standorte) {
                long standort = organisation("Standort " + standortname, "STANDORT", verbund);

                for (String[] p : praxen) {
                    laufendeNummer++;
                    String praxisname = p[0] + " " + standortname;
                    long praxis = organisation(praxisname, "EINRICHTUNG", standort);

                    // Ein Konnektor je Praxis, mit eigener SMC-B.
                    String kuerzel = kuerzel(standortname, p[0]);
                    long konnektor = konnektor(praxis, "Konnektor " + praxisname,
                            "https://10.%d.%d.10:443/".formatted(standortIndex(standortname) + 1, laufendeNummer),
                            "KoCo Konnektor", "Mandant-" + kuerzel);

                    // Vier Kartenterminals je Praxis. Alle mit freigeschalteter
                    // Managementschnittstelle (Vorgabe PO) - in einem
                    // eingerichteten Verbund ist das der Regelfall.
                    String[] raeume = {"Empfang", "Behandlung 1", "Behandlung 2", "Labor"};
                    for (int t = 0; t < raeume.length; t++) {
                        kartenterminal(konnektor, praxis,
                                "KT-%s-%02d".formatted(kuerzel, t + 1),
                                raeume[t],
                                "10.%d.%d.%d".formatted(standortIndex(standortname) + 1,
                                        laufendeNummer, 20 + t),
                                "CHERRY ST-1506",
                                "card-smcb-" + kuerzel);
                    }

                    // Ebene 1: zustaendig fuer genau diese Praxis
                    konten.add(benutzer("praxis." + kuerzel.toLowerCase(),
                            p[0] + " " + standortname, "BENUTZER", praxis, praxisname));
                }

                // Ebene 2: zustaendig fuer alle Praxen dieses Standorts
                konten.add(benutzer("standort." + standortname.toLowerCase()
                                .replace("ü", "ue"),
                        "Leitung Standort " + standortname, "BENUTZER", standort,
                        "Standort " + standortname));
            }

            // Ebene 3: zustaendig fuer den gesamten Verbund
            konten.add(benutzer("verbund.leitung", "Verbundleitung", "BENUTZER",
                    verbund, "MVZ-Verbund Musterstadt"));

            // Drei Administratoren mit unterschiedlichem Geltungsbereich - genau
            // hier zeigt sich, ob das Rechtemodell traegt: Ein Administrator
            // eines Standorts darf dessen Benutzer verwalten, aber keine fremden.
            konten.add(benutzer("admin.verbund", "Administrator Verbund",
                    "ADMINISTRATOR", verbund, "MVZ-Verbund Musterstadt"));
            long standortNord = organisationSuchen("Standort Nord");
            konten.add(benutzer("admin.nord", "Administrator Nord",
                    "ADMINISTRATOR", standortNord, "Standort Nord"));
            long praxisKardioNord = organisationSuchen("Kardiologie Nord");
            konten.add(benutzer("admin.kardio.nord", "Administrator Kardiologie Nord",
                    "ADMINISTRATOR", praxisKardioNord, "Kardiologie Nord"));

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
            s.executeUpdate("DELETE FROM kartenterminal WHERE ist_demo");
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
        try (PreparedStatement p = db.verbindung().prepareStatement("""
                INSERT INTO organisation (bezeichnung, art, eltern_id, ist_demo)
                VALUES (?,?,?,TRUE) RETURNING id""")) {
            p.setString(1, bezeichnung);
            p.setString(2, art);
            if (eltern == null) p.setNull(3, Types.BIGINT); else p.setLong(3, eltern);
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
                                       mandant_id, clientsystem_id, arbeitsplatz_id, ist_demo)
                VALUES (?,?,?,?,?,'PIN-Roboter','Arbeitsplatz1',TRUE) RETURNING id""")) {
            p.setLong(1, org);
            p.setString(2, bezeichnung);
            p.setString(3, adresse);
            p.setString(4, modell);
            p.setString(5, mandantId);
            try (ResultSet rs = p.executeQuery()) { rs.next(); return rs.getLong(1); }
        }
    }

    private void kartenterminal(long konnektor, long org, String terminalId,
                                String raum, String adresse, String modell,
                                String kartenhandle) throws SQLException {
        try (PreparedStatement p = db.verbindung().prepareStatement("""
                INSERT INTO kartenterminal (terminal_id, konnektor_id, organisation_id,
                        bezeichnung, standortangabe, modell, kartenhandle, ist_demo)
                VALUES (?,?,?,?,?,?,?,TRUE)""")) {
            p.setString(1, terminalId);
            p.setLong(2, konnektor);
            p.setLong(3, org);
            p.setString(4, raum);
            p.setString(5, adresse);
            p.setString(6, modell);
            p.setString(7, kartenhandle);
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

    private static String kuerzel(String standort, String fach) {
        String s = standort.substring(0, 1);
        String f = switch (fach) {
            case "Kardiologie" -> "KAR";
            case "Orthopädie" -> "ORT";
            default -> "ALL";
        };
        return s + f;
    }
}
