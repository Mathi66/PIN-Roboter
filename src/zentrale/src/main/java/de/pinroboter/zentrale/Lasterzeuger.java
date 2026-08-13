package de.pinroboter.zentrale;

import java.sql.*;

/**
 * Erzeugt einen Bestand in der Größe des Mengengerüsts (NFA-LEI-010, NFA-LEI-020).
 *
 * <p>Der Anforderungskatalog nennt <b>5.000 Kartenterminals</b> und verlangt,
 * dass die erste Seite in unter einer Sekunde steht. Diese Zahl lässt sich
 * weder schätzen noch am MVZ-Demodatensatz mit 36 Geräten prüfen — sie braucht
 * echte Daten in echter Menge.
 *
 * <p><b>Warum das im Produkt steht und nicht nur im Prüfskript:</b> Der
 * Betreiber soll die Messung auf <i>seiner</i> Hardware wiederholen können. Ein
 * Zielwert, der nur auf der Entwicklungsmaschine gilt, sagt über einen
 * Raspberry Pi 5 in einer Praxis nichts aus — und genau der ist die
 * Referenzhardware (ADR-0006).
 *
 * <p>Alles, was hier entsteht, trägt das Demo-Kennzeichen und verschwindet mit
 * {@link Demodaten#loeschen()}. Echte Bestandsdaten werden nie angefasst.
 */
public class Lasterzeuger {

    /** Größe des Mengengerüsts aus CLAUDE.md und NFA-LEI-020. */
    public static final int TERMINALS_ZIEL = 5000;

    /**
     * Terminals je Konnektor. Bei 5.000 Geräten und 1.000 HSK-Instanzen im
     * Maximum sind das im Schnitt fünf — hier bewusst gröber (50), damit die
     * Zahl der Konnektoren die Messung nicht dominiert.
     */
    private static final int TERMINALS_JE_KONNEKTOR = 50;

    private static final String[] MODELLE = {
            "CHERRY ST-1506", "Ingenico ORGA 6141", "CHERRY ST-1503", "Worldline Yomani"
    };
    private static final String[] FIRMWARE = { "4.3.0", "4.2.1", "3.7.4", "5.0.1" };

    public record Ergebnis(int konnektoren, int terminals, long dauerMs) {}

    private final Datenbank db;

    public Lasterzeuger(Datenbank db) { this.db = db; }

    /** Wie viele Demo-Terminals aus dem Lasterzeuger bereits vorhanden sind. */
    public int vorhandene() {
        try (Statement s = db.verbindung().createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT count(*) FROM kartenterminal WHERE ist_demo AND terminal_id LIKE 'KT-LAST-%'")) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) { return 0; }
    }

    /**
     * Legt einen Bestand in der Zielgröße an.
     *
     * <p>Als <b>eine Transaktion mit Stapelverarbeitung</b>. 5.000 einzelne
     * Einfügungen mit je einem Netzumlauf dauern auf schwacher Hardware
     * Minuten — das wäre kein Werkzeug, sondern ein Hindernis.
     *
     * @param anzahl gewünschte Terminalzahl
     * @return Anzahl und gemessene Dauer
     */
    public Ergebnis erzeugen(int anzahl) throws SQLException {
        int ziel = Math.max(1, Math.min(50_000, anzahl));
        long start = System.nanoTime();

        boolean vorherAuto = db.verbindung().getAutoCommit();
        db.verbindung().setAutoCommit(false);
        int konnektoren = 0;
        int terminals = 0;
        try {
            long verbundId = organisation("Lasttest-Verbund", "VERBUND", null);
            long orgId = organisation("Lasttest-Einrichtung", "EINRICHTUNG", verbundId);

            int noetig = (ziel + TERMINALS_JE_KONNEKTOR - 1) / TERMINALS_JE_KONNEKTOR;
            long[] konnektorIds = new long[noetig];
            for (int k = 0; k < noetig; k++) {
                konnektorIds[k] = konnektor(orgId, k);
                konnektoren++;
            }

            try (PreparedStatement p = db.verbindung().prepareStatement("""
                    INSERT INTO kartenterminal (terminal_id, konnektor_id, organisation_id,
                        bezeichnung, standortangabe, adresse, modell, firmware,
                        hat_smcb, pevim_aktiv, aktiv, ist_demo)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,TRUE)
                    ON CONFLICT (terminal_id) DO NOTHING""")) {
                for (int i = 0; i < ziel; i++) {
                    long konnektorId = konnektorIds[i / TERMINALS_JE_KONNEKTOR];
                    // Bewusst gestreut: Ein Bestand, in dem alle Werte gleich
                    // sind, laesst Sortierung und Filter schneller aussehen,
                    // als sie in einer echten Klinik waeren.
                    boolean smcb = i % 4 == 0;
                    p.setString(1, "KT-LAST-%05d".formatted(i));
                    p.setLong(2, konnektorId);
                    p.setLong(3, orgId);
                    p.setString(4, "Gerät %05d %s".formatted(i, buchstabe(i)));
                    p.setString(5, "Ebene %d, Raum %03d".formatted(i % 7 + 1, i % 400));
                    p.setString(6, "10.%d.%d.%d".formatted(i / 65536 % 256, i / 256 % 256, i % 256));
                    p.setString(7, MODELLE[i % MODELLE.length]);
                    p.setString(8, FIRMWARE[i % FIRMWARE.length]);
                    p.setBoolean(9, smcb);
                    p.setBoolean(10, smcb && i % 8 == 0);
                    p.setBoolean(11, i % 20 != 0);      // jedes 20. deaktiviert
                    p.addBatch();
                    if ((i + 1) % 1000 == 0) { p.executeBatch(); }
                }
                p.executeBatch();
            }
            terminals = ziel;
            db.verbindung().commit();
        } catch (SQLException e) {
            db.verbindung().rollback();
            throw e;
        } finally {
            db.verbindung().setAutoCommit(vorherAuto);
        }

        // Ohne aktuelle Planungsdaten waehlt PostgreSQL nach einem Masseneinfuegen
        // womoeglich einen sequenziellen Durchlauf, obwohl ein Index vorliegt.
        // Eine Messung ohne ANALYZE misst dann den falschen Plan - und im Betrieb
        // erledigt das der Autovacuum, nur eben nicht in derselben Sekunde.
        try (Statement s = db.verbindung().createStatement()) {
            s.execute("ANALYZE kartenterminal");
            s.execute("ANALYZE konnektor");
        }

        return new Ergebnis(konnektoren, terminals, (System.nanoTime() - start) / 1_000_000);
    }

    /** Entfernt ausschließlich die Datensätze des Lasterzeugers. */
    public int entfernen() throws SQLException {
        boolean vorherAuto = db.verbindung().getAutoCommit();
        db.verbindung().setAutoCommit(false);
        int weg;
        try (Statement s = db.verbindung().createStatement()) {
            // Zuerst die Alarme: Sie verweisen ueber terminal_id auf Geraete, die
            // es gleich nicht mehr gibt. Blieben sie stehen, waeren sie fuer
            // immer offen - eine Behebungsmeldung kommt fuer ein geloeschtes
            // Geraet nie (Befund 13.08.2026).
            s.executeUpdate("""
                    DELETE FROM alarm
                    WHERE objekt LIKE 'KT-LAST-%'
                       OR einrichtung IN ('Lasttest-Einrichtung', 'Lasttest-Verbund')""");
            weg = s.executeUpdate("DELETE FROM kartenterminal WHERE terminal_id LIKE 'KT-LAST-%'");
            s.executeUpdate("DELETE FROM konnektor WHERE bezeichnung LIKE 'Lastkonnektor %'");
            s.executeUpdate("""
                    DELETE FROM organisation
                    WHERE bezeichnung IN ('Lasttest-Einrichtung', 'Lasttest-Verbund')""");
            db.verbindung().commit();
        } catch (SQLException e) {
            db.verbindung().rollback();
            throw e;
        } finally {
            db.verbindung().setAutoCommit(vorherAuto);
        }
        try (Statement s = db.verbindung().createStatement()) {
            s.execute("ANALYZE kartenterminal");
        }
        return weg;
    }

    // -------------------------------------------------------------- Innenleben

    private long organisation(String bezeichnung, String art, Long elternId) throws SQLException {
        try (PreparedStatement p = db.verbindung().prepareStatement(
                "SELECT id FROM organisation WHERE bezeichnung = ?")) {
            p.setString(1, bezeichnung);
            try (ResultSet rs = p.executeQuery()) { if (rs.next()) return rs.getLong(1); }
        }
        try (PreparedStatement p = db.verbindung().prepareStatement("""
                INSERT INTO organisation (bezeichnung, art, eltern_id, ist_demo)
                VALUES (?,?,?,TRUE) RETURNING id""")) {
            p.setString(1, bezeichnung);
            p.setString(2, art);
            if (elternId == null) p.setNull(3, Types.BIGINT); else p.setLong(3, elternId);
            try (ResultSet rs = p.executeQuery()) { rs.next(); return rs.getLong(1); }
        }
    }

    private long konnektor(long orgId, int nummer) throws SQLException {
        String bez = "Lastkonnektor %04d".formatted(nummer);
        try (PreparedStatement p = db.verbindung().prepareStatement(
                "SELECT id FROM konnektor WHERE bezeichnung = ?")) {
            p.setString(1, bez);
            try (ResultSet rs = p.executeQuery()) { if (rs.next()) return rs.getLong(1); }
        }
        try (PreparedStatement p = db.verbindung().prepareStatement("""
                INSERT INTO konnektor (organisation_id, bezeichnung, adresse, modell,
                    mandant_id, clientsystem_id, arbeitsplatz_id, ist_demo)
                VALUES (?,?,?,?,?,?,?,TRUE) RETURNING id""")) {
            p.setLong(1, orgId);
            p.setString(2, bez);
            p.setString(3, "10.200.%d.%d".formatted(nummer / 256 % 256, nummer % 256));
            p.setString(4, "KoCo HSK");
            p.setString(5, "Mandant-Last");
            p.setString(6, "CS-Last");
            p.setString(7, "AP-%04d".formatted(nummer));
            try (ResultSet rs = p.executeQuery()) { rs.next(); return rs.getLong(1); }
        }
    }

    /** Streut die Anfangsbuchstaben, damit die Sortierung etwas zu tun bekommt. */
    private static String buchstabe(int i) {
        return String.valueOf((char) ('A' + (i * 7) % 26));
    }
}
