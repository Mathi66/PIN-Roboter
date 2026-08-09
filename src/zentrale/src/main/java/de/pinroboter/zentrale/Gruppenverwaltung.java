package de.pinroboter.zentrale;

import java.sql.*;
import java.util.*;

/**
 * Gerätegruppen und Gerätezuordnung (FA-GER-050).
 *
 * <p>Gruppen sind mehr als Ordnung: Sie tragen das <b>Prüfintervall</b> und
 * später die <b>Rollout-Pläne</b> (ADR-0004). Bei 5.000 Terminals ist die Last
 * nur darüber steuerbar — die Notaufnahme braucht 30 Sekunden, das Archiv kommt
 * mit fünf Minuten aus.
 *
 * <p>Deshalb stehen sie schon in der ersten Version im Datenmodell, obwohl der
 * Firmware-Rollout eine spätere Stufe ist. Nachrüsten wäre ein Umbau des halben
 * Modells.
 */
public class Gruppenverwaltung {

    public record Gruppe(long id, String bezeichnung, String einrichtung,
                         int pruefintervallSekunden, boolean istVorgabe, int geraetezahl) {}

    public record Geraet(String terminalId, String einrichtung, long gruppeId,
                         String bezeichnung, String standort, boolean aktiv) {}

    private final Datenbank db;

    public Gruppenverwaltung(Datenbank db) { this.db = db; }

    // ---------------------------------------------------------------- Gruppen

    /** Gruppen im Geltungsbereich. Eine Gruppe trägt Prüfintervalle und später
     *  Rollout-Pläne — wer sie sieht, sieht die Betriebsplanung einer Praxis. */
    /**
     * Alle Gruppen im Geltungsbereich, unabhängig von der Einrichtung.
     *
     * <p>Bis zum 09.08.2026 gab es nur die Abfrage nach einer festen
     * Einrichtung — und die Oberfläche reichte dafür hart {@code "Praxis"}
     * durch. Damit war jede Gruppe an dieselbe Einrichtung gebunden, obwohl
     * FA-GER-050 verlangt: <i>„Eine Gruppe liegt innerhalb genau einer
     * Einrichtung."</i> Bei neun Praxen an einer Instanz war das nicht mehr
     * tragfähig.
     */
    public List<Gruppe> alle(Geltungsbereich bereich) {
        List<Gruppe> liste = new ArrayList<>();
        try (PreparedStatement p = db.verbindung().prepareStatement("""
                SELECT g.id, g.bezeichnung, g.einrichtung, g.pruefintervall_sekunden,
                       g.ist_vorgabe, count(t.terminal_id) AS anzahl
                FROM geraetegruppe g
                LEFT JOIN geraet t ON t.gruppe_id = g.id
                WHERE 1=1""" + bereich.bedingung("g.organisation_id")
                + " GROUP BY g.id ORDER BY g.ist_vorgabe DESC, lower(g.einrichtung),"
                + " lower(g.bezeichnung)")) {
            bereich.wertSetzen(db.verbindung(), p, 1);
            try (ResultSet rs = p.executeQuery()) {
                while (rs.next()) liste.add(lesen(rs));
            }
        } catch (SQLException e) {
            System.err.println("Gerätegruppen nicht lesbar: " + e.getMessage());
        }
        return liste;
    }

    public List<Gruppe> alle(String einrichtung, Geltungsbereich bereich) {
        List<Gruppe> liste = new ArrayList<>();
        try (PreparedStatement p = db.verbindung().prepareStatement("""
                SELECT g.id, g.bezeichnung, g.einrichtung, g.pruefintervall_sekunden,
                       g.ist_vorgabe, count(t.terminal_id) AS anzahl
                FROM geraetegruppe g
                LEFT JOIN geraet t ON t.gruppe_id = g.id
                WHERE g.einrichtung = ?""" + bereich.bedingung("g.organisation_id")
                + " GROUP BY g.id ORDER BY g.ist_vorgabe DESC, lower(g.bezeichnung)")) {
            p.setString(1, einrichtung);
            bereich.wertSetzen(db.verbindung(), p, 2);
            try (ResultSet rs = p.executeQuery()) {
                while (rs.next()) liste.add(lesen(rs));
            }
        }
        catch (SQLException e) {
            // Nicht verschlucken: Ein stiller SQL-Fehler ist praktisch
            // unauffindbar - so blieb ein fehlerhaftes WHERE monatelang
            // unentdeckt. Der Aufruf schlaegt trotzdem nicht fehl.
            System.err.println("Gruppenverwaltung.alle: " + e.getMessage());
        }
        return liste;
    }

    public Optional<Gruppe> vorgabegruppe(String einrichtung) {
        try (PreparedStatement p = db.verbindung().prepareStatement("""
                SELECT g.id, g.bezeichnung, g.einrichtung, g.pruefintervall_sekunden,
                       g.ist_vorgabe, 0 AS anzahl
                FROM geraetegruppe g WHERE g.einrichtung = ? AND g.ist_vorgabe""")) {
            p.setString(1, einrichtung);
            try (ResultSet rs = p.executeQuery()) {
                return rs.next() ? Optional.of(lesen(rs)) : Optional.empty();
            }
        } catch (SQLException e) { return Optional.empty(); }
    }

    /** @return Fehlermeldung im Klartext, oder null bei Erfolg */
    public String anlegen(String bezeichnung, String einrichtung, int intervall) {
        if (bezeichnung == null || bezeichnung.isBlank()) {
            return "Bitte eine Bezeichnung für die Gruppe angeben.";
        }
        if (intervall < 10) {
            return "Das Prüfintervall muss mindestens 10 Sekunden betragen. "
                 + "Kürzere Abstände belasten Konnektor und Terminals unnötig.";
        }
        try (PreparedStatement p = db.verbindung().prepareStatement("""
                INSERT INTO geraetegruppe (bezeichnung, einrichtung, pruefintervall_sekunden,
                                           organisation_id)
                VALUES (?,?,?,
                        (SELECT o.id FROM organisation o WHERE o.bezeichnung = ?
                         AND (SELECT count(*) FROM organisation x WHERE x.bezeichnung = ?) = 1))""")) {
            p.setString(1, bezeichnung.trim());
            p.setString(2, einrichtung);
            p.setInt(3, intervall);
            p.setString(4, einrichtung);
            p.setString(5, einrichtung);
            p.executeUpdate();
            return null;
        } catch (SQLException e) {
            if ("23505".equals(e.getSQLState())) {
                return "Eine Gruppe mit dieser Bezeichnung gibt es bereits.";
            }
            return "Die Gruppe konnte nicht angelegt werden.";
        }
    }

    public String intervallSetzen(long gruppeId, int sekunden) {
        if (sekunden < 10) return "Das Prüfintervall muss mindestens 10 Sekunden betragen.";
        try (PreparedStatement p = db.verbindung().prepareStatement(
                "UPDATE geraetegruppe SET pruefintervall_sekunden = ? WHERE id = ?")) {
            p.setInt(1, sekunden);
            p.setLong(2, gruppeId);
            return p.executeUpdate() == 1 ? null : "Diese Gruppe gibt es nicht.";
        } catch (SQLException e) { return "Das Intervall konnte nicht geändert werden."; }
    }

    /**
     * Löscht eine Gruppe. Zugeordnete Geräte ziehen in die Vorgabegruppe um —
     * kein Gerät darf durch eine Löschung aus der Überwachung fallen (FA-GER-050).
     */
    public String loeschen(long gruppeId, String einrichtung) {
        var vorgabe = vorgabegruppe(einrichtung);
        if (vorgabe.isEmpty()) return "Es gibt keine Vorgabegruppe.";
        if (vorgabe.get().id() == gruppeId) {
            return "Die Vorgabegruppe lässt sich nicht löschen. Neu erkannte Geräte "
                 + "brauchen eine Gruppe, in der sie landen können.";
        }
        try {
            try (PreparedStatement p = db.verbindung().prepareStatement(
                    "UPDATE geraet SET gruppe_id = ? WHERE gruppe_id = ?")) {
                p.setLong(1, vorgabe.get().id());
                p.setLong(2, gruppeId);
                p.executeUpdate();
            }
            try (PreparedStatement p = db.verbindung().prepareStatement(
                    "DELETE FROM geraetegruppe WHERE id = ?")) {
                p.setLong(1, gruppeId);
                return p.executeUpdate() == 1 ? null : "Diese Gruppe gibt es nicht.";
            }
        } catch (SQLException e) { return "Die Gruppe konnte nicht gelöscht werden."; }
    }

    // ---------------------------------------------------------------- Geräte

    /**
     * Trägt ein gemeldetes Gerät ein, falls es noch unbekannt ist.
     * Neue Geräte landen in der Vorgabegruppe.
     *
     * @return true, wenn das Gerät neu war
     */
    public boolean geraetSicherstellen(String terminalId, String einrichtung, String bezeichnung) {
        var vorgabe = vorgabegruppe(einrichtung);
        if (vorgabe.isEmpty()) vorgabe = vorgabegruppeAnlegen(einrichtung);
        if (vorgabe.isEmpty()) return false;
        try (PreparedStatement p = db.verbindung().prepareStatement("""
                INSERT INTO geraet (terminal_id, einrichtung, gruppe_id, bezeichnung)
                VALUES (?,?,?,?) ON CONFLICT (terminal_id) DO NOTHING""")) {
            p.setString(1, terminalId);
            p.setString(2, einrichtung);
            p.setLong(3, vorgabe.get().id());
            p.setString(4, bezeichnung);
            return p.executeUpdate() == 1;
        } catch (SQLException e) { return false; }
    }

    /**
     * Legt die Vorgabegruppe einer Einrichtung an, falls sie noch fehlt.
     *
     * <p>Migration 6 erzeugt sie nur für die Einrichtung {@code "Praxis"}. Bis
     * zum 09.08.2026 fiel deshalb jedes Gerät eines Agenten an einem anderen
     * Standort <b>still durch</b>: {@code geraetSicherstellen} gab {@code false}
     * zurück, niemand sah hin, und das Terminal tauchte im Protokoll auf, aber
     * nirgends in der Geräteverwaltung — ohne Gruppe, also auch ohne
     * Prüfintervall.
     *
     * <p>Die Gruppe entsteht deshalb beim ersten Bedarf. Idempotent: Ein
     * Wettlauf zweier Agenten derselben Einrichtung endet an dem eindeutigen
     * Index {@code geraetegruppe_vorgabe_idx}, und der Verlierer liest die
     * inzwischen vorhandene Gruppe.
     */
    Optional<Gruppe> vorgabegruppeAnlegen(String einrichtung) {
        try (PreparedStatement p = db.verbindung().prepareStatement("""
                INSERT INTO geraetegruppe (bezeichnung, einrichtung, ist_vorgabe,
                                           pruefintervall_sekunden, organisation_id)
                VALUES ('Alle Geräte', ?, TRUE, 60,
                        (SELECT o.id FROM organisation o WHERE o.bezeichnung = ?
                         AND (SELECT count(*) FROM organisation x WHERE x.bezeichnung = ?) = 1))
                ON CONFLICT DO NOTHING""")) {
            p.setString(1, einrichtung);
            p.setString(2, einrichtung);
            p.setString(3, einrichtung);
            p.executeUpdate();
        } catch (SQLException e) {
            // Nicht verschlucken: Ohne Vorgabegruppe wird das Gerät nicht erfasst.
            System.err.println("Vorgabegruppe für " + einrichtung
                    + " konnte nicht angelegt werden: " + e.getMessage());
        }
        return vorgabegruppe(einrichtung);
    }

    public String zuordnen(String terminalId, long gruppeId) {
        try (PreparedStatement p = db.verbindung().prepareStatement(
                "UPDATE geraet SET gruppe_id = ? WHERE terminal_id = ?")) {
            p.setLong(1, gruppeId);
            p.setString(2, terminalId);
            return p.executeUpdate() == 1 ? null : "Dieses Gerät ist nicht erfasst.";
        } catch (SQLException e) {
            return "Die Zuordnung konnte nicht gespeichert werden.";
        }
    }

    /** Prüfintervalle je Gerät — der Agent holt sie sich hier ab. */
    public Map<String, Integer> intervalleJeGeraet(String einrichtung) {
        Map<String, Integer> m = new LinkedHashMap<>();
        try (PreparedStatement p = db.verbindung().prepareStatement("""
                SELECT t.terminal_id, g.pruefintervall_sekunden
                FROM geraet t JOIN geraetegruppe g ON g.id = t.gruppe_id
                WHERE t.einrichtung = ? AND t.aktiv""")) {
            p.setString(1, einrichtung);
            try (ResultSet rs = p.executeQuery()) {
                while (rs.next()) m.put(rs.getString(1), rs.getInt(2));
            }
        }
        catch (SQLException e) {
            // Nicht verschlucken: Ein stiller SQL-Fehler ist praktisch
            // unauffindbar - so blieb ein fehlerhaftes WHERE monatelang
            // unentdeckt. Der Aufruf schlaegt trotzdem nicht fehl.
            System.err.println("Gruppenverwaltung.intervalleJeGeraet: " + e.getMessage());
        }
        return m;
    }

    public Optional<Long> gruppeVon(String terminalId) {
        try (PreparedStatement p = db.verbindung().prepareStatement(
                "SELECT gruppe_id FROM geraet WHERE terminal_id = ?")) {
            p.setString(1, terminalId);
            try (ResultSet rs = p.executeQuery()) {
                return rs.next() ? Optional.of(rs.getLong(1)) : Optional.empty();
            }
        } catch (SQLException e) { return Optional.empty(); }
    }

    private static Gruppe lesen(ResultSet rs) throws SQLException {
        return new Gruppe(rs.getLong("id"), rs.getString("bezeichnung"),
                rs.getString("einrichtung"), rs.getInt("pruefintervall_sekunden"),
                rs.getBoolean("ist_vorgabe"), rs.getInt("anzahl"));
    }
}
