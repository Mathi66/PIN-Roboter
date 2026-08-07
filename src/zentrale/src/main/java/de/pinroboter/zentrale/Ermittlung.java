package de.pinroboter.zentrale;

import java.sql.*;
import java.util.*;

/**
 * Was der Agent beim Konnektor vorgefunden hat (FA-GER-030, FA-GER-035).
 *
 * <p><b>Die eine Regel, die alles bestimmt:</b> Hier wird nichts selbsttätig in
 * den Bestand übernommen. Die Anforderung sagt es wörtlich — *„Die Übernahme
 * erfolgt erst nach ausdrücklicher Bestätigung, einzeln oder als Auswahl — nie
 * automatisch."* Und FA-GER-035: *„Ein erneutes Auslesen erkennt Abweichungen
 * und zeigt sie an, ohne selbsttätig zu ändern."*
 *
 * <p>Der Grund ist nicht Vorsicht um ihrer selbst willen: Ein falsch
 * konfigurierter oder frisch zurückgesetzter Konnektor würde sonst einen
 * gepflegten Bestand überschreiben — mit Zugangsdaten, Gruppenzuordnungen und
 * Standortangaben, die niemand wiederherstellen kann.
 *
 * <p>Deshalb liegt der Befund in eigenen Tabellen, getrennt vom Bestand, und die
 * Oberfläche zeigt den <b>Abgleich</b>: was neu ist, was übereinstimmt, was
 * abweicht, und was im Bestand steht, aber der Konnektor nicht mehr kennt.
 */
public class Ermittlung {

    /** Wie ein ermitteltes Gerät zum erfassten Bestand steht. */
    public enum Lage {
        NEU("neu erkannt", "Dieses Gerät kennt der Konnektor, es ist aber noch nicht erfasst."),
        BEKANNT("erfasst", "Erfasst und deckungsgleich."),
        ABWEICHEND("weicht ab", "Erfasst, aber die Angaben des Konnektors stimmen nicht überein."),
        VERSCHWUNDEN("nicht mehr gemeldet",
                "Erfasst, aber der Konnektor kennt dieses Gerät nicht mehr.");

        public final String anzeige;
        public final String erklaerung;
        Lage(String a, String e) { anzeige = a; erklaerung = e; }
    }

    public record Fund(long id, String terminalId, String name, String modell, String firmware,
                       boolean erreichbar, boolean pevimUnterstuetzt, boolean pevimAktiv,
                       boolean hatSmcb, String kartenhandle, String iccsn, String gesehen,
                       Lage lage, List<String> abweichungen, Long bestandsId) {}

    public record Modell(boolean verfuegbar, String inhalt, String gesehen) {}

    private final Datenbank db;

    public Ermittlung(Datenbank db) { this.db = db; }

    // ------------------------------------------------------------- Entgegennahme

    /**
     * Nimmt eine Bestandsmeldung des Agenten entgegen.
     *
     * <p>Vorhandene Einträge werden aktualisiert, neue angelegt. Ein Gerät, das
     * der Konnektor nicht mehr meldet, wird <b>nicht gelöscht</b> — es soll als
     * „nicht mehr gemeldet" sichtbar bleiben, weil das ein Hinweis auf ein
     * ausgefallenes oder abgebautes Gerät ist.
     *
     * @return Anzahl der aufgenommenen Geräte
     */
    public int aufnehmen(long agentId, List<Map<String, Object>> terminals) {
        int n = 0;
        try (PreparedStatement p = db.verbindung().prepareStatement("""
                INSERT INTO ermittelt_terminal (agent_id, terminal_id, name, modell, firmware,
                    erreichbar, pevim_unterstuetzt, pevim_aktiv, hat_smcb, kartenhandle, iccsn, gesehen)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,now())
                ON CONFLICT (agent_id, terminal_id) DO UPDATE SET
                    name = EXCLUDED.name, modell = EXCLUDED.modell, firmware = EXCLUDED.firmware,
                    erreichbar = EXCLUDED.erreichbar,
                    pevim_unterstuetzt = EXCLUDED.pevim_unterstuetzt,
                    pevim_aktiv = EXCLUDED.pevim_aktiv, hat_smcb = EXCLUDED.hat_smcb,
                    kartenhandle = EXCLUDED.kartenhandle, iccsn = EXCLUDED.iccsn,
                    gesehen = now()""")) {
            for (var t : terminals) {
                String id = text(t.get("terminalId"));
                if (id.isBlank()) continue;
                p.setLong(1, agentId);
                p.setString(2, id);
                p.setString(3, nullWennLeer(text(t.get("name"))));
                p.setString(4, nullWennLeer(text(t.get("modell"))));
                p.setString(5, nullWennLeer(text(t.get("firmware"))));
                p.setBoolean(6, wahr(t.get("erreichbar")));
                p.setBoolean(7, wahr(t.get("pevimUnterstuetzt")));
                p.setBoolean(8, wahr(t.get("pevimAktiv")));
                p.setBoolean(9, wahr(t.get("hatSmcb")));
                p.setString(10, nullWennLeer(text(t.get("kartenhandle"))));
                p.setString(11, nullWennLeer(text(t.get("iccsn"))));
                p.addBatch();
                n++;
            }
            p.executeBatch();
        } catch (SQLException e) {
            return 0;
        }
        return n;
    }

    /** Legt das gelesene Informationsmodell ab, oder vermerkt, dass es fehlt. */
    public void modellAblegen(long agentId, boolean verfuegbar, String inhalt) {
        try (PreparedStatement p = db.verbindung().prepareStatement("""
                INSERT INTO ermitteltes_modell (agent_id, verfuegbar, inhalt, gesehen)
                VALUES (?,?,?,now())
                ON CONFLICT (agent_id) DO UPDATE SET
                    verfuegbar = EXCLUDED.verfuegbar, inhalt = EXCLUDED.inhalt, gesehen = now()""")) {
            p.setLong(1, agentId);
            p.setBoolean(2, verfuegbar);
            p.setString(3, verfuegbar ? inhalt : null);
            p.executeUpdate();
        } catch (SQLException ignored) { }
    }

    /**
     * Das zuletzt gemeldete Informationsmodell eines aktiven Agenten.
     *
     * <p>Ersetzt die frühere Auswahl „Modell des ersten Agenten". Die war
     * willkürlich: Nach einem Agentenaustausch lieferte sie das Modell eines
     * längst abgemeldeten Agenten — oder gar keins.
     *
     * <p>Bei mehreren Standorten muss die Oberfläche je Agent gruppieren; das
     * gehört zum Umbau auf Mandantentrennung (Block A).
     */
    public Optional<Modell> juengstesModell() {
        try (PreparedStatement p = db.verbindung().prepareStatement("""
                SELECT m.verfuegbar, m.inhalt, m.gesehen
                FROM ermitteltes_modell m
                JOIN agent a ON a.id = m.agent_id AND a.aktiv
                ORDER BY m.gesehen DESC LIMIT 1""");
             ResultSet rs = p.executeQuery()) {
            if (!rs.next()) return Optional.empty();
            return Optional.of(new Modell(rs.getBoolean(1), rs.getString(2),
                    zeitpunkt(rs.getTimestamp(3))));
        } catch (SQLException e) { return Optional.empty(); }
    }

    public Optional<Modell> modell(long agentId) {
        try (PreparedStatement p = db.verbindung().prepareStatement(
                "SELECT verfuegbar, inhalt, gesehen FROM ermitteltes_modell WHERE agent_id=?")) {
            p.setLong(1, agentId);
            try (ResultSet rs = p.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new Modell(rs.getBoolean(1), rs.getString(2),
                        zeitpunkt(rs.getTimestamp(3))));
            }
        } catch (SQLException e) { return Optional.empty(); }
    }

    // ----------------------------------------------------------------- Abgleich

    /**
     * Gleicht den Befund mit dem erfassten Bestand ab.
     *
     * <p>Verglichen wird über die Terminal-Kennung — sie ist im Bestand
     * eindeutig und stammt beim erfassten wie beim ermittelten Gerät aus
     * derselben Quelle, dem Konnektor.
     */
    public List<Fund> abgleich() {
        List<Fund> liste = new ArrayList<>();
        // Zwei Filter, die im Vollauf der Systemnachweise noetig wurden:
        //
        // 1. Nur AKTIVE Agenten. Ein widerrufener oder ausgetauschter Agent
        //    hinterlaesst sonst Karteileichen, und die Oberflaeche meldet
        //    Geraete als "nicht mehr gemeldet", die laengst ein anderer Agent
        //    betreut. Nach einer Neuinstallation waere die Liste unbrauchbar.
        //
        // 2. Je Terminal-Kennung nur der JUENGSTE Fund. Melden zwei Agenten
        //    dasselbe Geraet - waehrend eines Austauschs etwa -, zaehlt der
        //    letzte Stand, nicht beide.
        try (PreparedStatement p = db.verbindung().prepareStatement("""
                SELECT DISTINCT ON (e.terminal_id)
                       e.*, t.id AS bestand_id, t.modell AS b_modell, t.hat_smcb AS b_smcb,
                       t.pevim_aktiv AS b_pevim, t.bezeichnung AS b_bezeichnung
                FROM ermittelt_terminal e
                JOIN agent a ON a.id = e.agent_id AND a.aktiv
                LEFT JOIN kartenterminal t ON t.terminal_id = e.terminal_id
                ORDER BY e.terminal_id, e.gesehen DESC""");
             ResultSet rs = p.executeQuery()) {
            while (rs.next()) {
                long bestandsId = rs.getLong("bestand_id");
                // Ab hier unveraendert; sortiert wird nach dem Lesen, weil
                // DISTINCT ON die Sortierung der Abfrage vorgibt.
                boolean erfasst = !rs.wasNull();

                List<String> abweichungen = new ArrayList<>();
                Lage lage;
                if (!erfasst) {
                    lage = Lage.NEU;
                } else {
                    // Verglichen wird nur, was der Konnektor tatsaechlich liefert.
                    // Standort, Gruppe und Zugangsdaten kennt er nicht - eine
                    // Abweichung dort waere keine.
                    vergleiche(abweichungen, "Modell", rs.getString("b_modell"), rs.getString("modell"));
                    if (rs.getBoolean("b_smcb") != rs.getBoolean("hat_smcb")) {
                        abweichungen.add("SMC-B: erfasst „%s“, Konnektor meldet „%s“".formatted(
                                jaNein(rs.getBoolean("b_smcb")), jaNein(rs.getBoolean("hat_smcb"))));
                    }
                    if (rs.getBoolean("b_pevim") != rs.getBoolean("pevim_aktiv")) {
                        abweichungen.add("PIN-Automatik: erfasst „%s“, Gerät meldet „%s“".formatted(
                                jaNein(rs.getBoolean("b_pevim")), jaNein(rs.getBoolean("pevim_aktiv"))));
                    }
                    lage = abweichungen.isEmpty() ? Lage.BEKANNT : Lage.ABWEICHEND;
                }

                liste.add(new Fund(rs.getLong("id"), rs.getString("terminal_id"),
                        rs.getString("name"), rs.getString("modell"), rs.getString("firmware"),
                        rs.getBoolean("erreichbar"), rs.getBoolean("pevim_unterstuetzt"),
                        rs.getBoolean("pevim_aktiv"), rs.getBoolean("hat_smcb"),
                        rs.getString("kartenhandle"), rs.getString("iccsn"),
                        zeitpunkt(rs.getTimestamp("gesehen")), lage, abweichungen,
                        erfasst ? bestandsId : null));
            }
        } catch (SQLException ignored) { }

        // Noch nicht erfasste Geraete zuerst - das ist die einzige Zeile mit
        // Handlungsbedarf.
        liste.sort(Comparator.comparingInt((Fund f) -> f.lage().ordinal())
                .thenComparing(f -> f.terminalId().toLowerCase()));
        liste.addAll(verschwundene());
        return liste;
    }

    /**
     * Geräte, die erfasst sind, aber vom Konnektor nicht mehr gemeldet werden.
     *
     * <p>Sie werden angezeigt, nicht gelöscht. Ein Gerät kann vorübergehend
     * abgemeldet sein; ein automatisches Entfernen aus dem Bestand würde
     * Zugangsdaten und Zuordnungen vernichten.
     */
    private List<Fund> verschwundene() {
        List<Fund> liste = new ArrayList<>();
        try (PreparedStatement p = db.verbindung().prepareStatement("""
                SELECT t.id, t.terminal_id, t.bezeichnung, t.modell
                FROM kartenterminal t
                WHERE NOT t.ist_demo
                  AND EXISTS (SELECT 1 FROM ermittelt_terminal e
                              JOIN agent a ON a.id = e.agent_id AND a.aktiv)
                  AND NOT EXISTS (SELECT 1 FROM ermittelt_terminal e
                                  JOIN agent a ON a.id = e.agent_id AND a.aktiv
                                  WHERE e.terminal_id = t.terminal_id)
                ORDER BY lower(t.bezeichnung)""");
             ResultSet rs = p.executeQuery()) {
            while (rs.next()) {
                liste.add(new Fund(0, rs.getString("terminal_id"), rs.getString("bezeichnung"),
                        rs.getString("modell"), null, false, false, false, false, null, null, null,
                        Lage.VERSCHWUNDEN, List.of(), rs.getLong("id")));
            }
        } catch (SQLException ignored) { }
        return liste;
    }

    public Optional<Fund> fund(long id) {
        return abgleich().stream().filter(f -> f.id() == id).findFirst();
    }

    /** Vermerkt, dass ein Fund in den Bestand übernommen wurde. */
    public void alsUebernommenMarkieren(long id) {
        try (PreparedStatement p = db.verbindung().prepareStatement(
                "UPDATE ermittelt_terminal SET uebernommen_am = now() WHERE id=?")) {
            p.setLong(1, id);
            p.executeUpdate();
        } catch (SQLException ignored) { }
    }

    /** Wie viele Funde noch nicht im Bestand stehen — für die Anzeige im Menü. */
    public int neueAnzahl() {
        try (PreparedStatement p = db.verbindung().prepareStatement("""
                SELECT count(DISTINCT e.terminal_id) FROM ermittelt_terminal e
                JOIN agent a ON a.id = e.agent_id AND a.aktiv
                WHERE NOT EXISTS (SELECT 1 FROM kartenterminal t
                                  WHERE t.terminal_id = e.terminal_id)""");
             ResultSet rs = p.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) { return 0; }
    }

    // -------------------------------------------------------------- Innenleben

    private static void vergleiche(List<String> ziel, String feld, String bestand, String ermittelt) {
        String a = bestand == null ? "" : bestand.trim();
        String b = ermittelt == null ? "" : ermittelt.trim();
        // Ein im Bestand leeres Feld ist keine Abweichung, sondern eine Luecke -
        // sie wird beim Uebernehmen gefuellt, nicht als Konflikt gemeldet.
        if (a.isEmpty() || b.isEmpty() || a.equalsIgnoreCase(b)) return;
        ziel.add("%s: erfasst „%s“, Konnektor meldet „%s“".formatted(feld, a, b));
    }

    private static String jaNein(boolean b) { return b ? "ja" : "nein"; }
    private static String text(Object o)    { return o == null ? "" : String.valueOf(o); }
    private static String nullWennLeer(String s) { return s == null || s.isBlank() ? null : s.trim(); }

    private static boolean wahr(Object o) {
        return o instanceof Boolean b ? b : "true".equalsIgnoreCase(String.valueOf(o));
    }

    private static String zeitpunkt(Timestamp t) {
        return t == null ? null : t.toLocalDateTime()
                .format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));
    }
}
