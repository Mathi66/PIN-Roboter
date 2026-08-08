package de.pinroboter.zentrale;

import java.sql.*;
import java.util.*;

/**
 * Vermittelt PIN-Aufträge zwischen Oberfläche und Agent (ADR-0009, FA-PIN-010).
 *
 * <h2>Was diese Klasse ausdrücklich NICHT kann</h2>
 *
 * <b>Sie kann keine PIN lesen.</b> Was hier durchgeht, ist mit dem öffentlichen
 * Schlüssel des Agenten verschlüsselt; der private Teil liegt ausschließlich auf
 * dessen Gerät. Die Zentrale reicht ein Chiffrat weiter, dessen Inhalt sie nicht
 * kennt — und das ist der ganze Zweck.
 *
 * <p>Bei 1.000 Standorten wäre eine Zentrale, die alle PINs kennt, ein Ziel,
 * dessen Kompromittierung hunderte Praxen lahmlegt. Jede davon drei Fehlversuche
 * von der Kartensperre entfernt.
 *
 * <h2>Das Chiffrat wird gelöscht</h2>
 *
 * Nach der Zustellung an den Agenten wird es aus der Datenbank entfernt. Die
 * Zentrale kann es zwar nicht lesen — aber ein Chiffrat, das ewig liegt, ist ein
 * Angriffsziel für später, etwa wenn der private Schlüssel des Agenten eines
 * Tages doch abhandenkommt.
 */
public class Pinverwaltung {

    public record Auftrag(long id, String kartenhandle, String art, String chiffrat,
                          String chiffratNeu) {}

    /** Nur das Kennzeichen — nie der Wert. */
    public record Hinterlegt(String kartenhandle, String seit) {}

    private final Datenbank db;

    public Pinverwaltung(Datenbank db) { this.db = db; }

    // -------------------------------------------------- Schlüssel des Agenten

    /** Nimmt den öffentlichen Schlüssel entgegen. Er ist nicht geheim. */
    public void schluesselMerken(long agentId, String oeffentlich) {
        try (PreparedStatement p = db.verbindung().prepareStatement(
                "UPDATE agent SET oeffentlicher_schluessel = ?, schluessel_gemeldet = now()"
                + " WHERE id = ?")) {
            p.setString(1, oeffentlich);
            p.setLong(2, agentId);
            p.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Öffentlicher Schlüssel nicht speicherbar: " + e.getMessage());
        }
    }

    /**
     * Der öffentliche Schlüssel des Agenten, der ein Terminal betreut.
     *
     * <p>Fehlt er, kann keine PIN hinterlegt werden — die Oberfläche muss das
     * sagen, statt ein Formular anzubieten, das nicht funktionieren kann.
     */
    public Optional<String> schluesselFuerTerminal(String terminalId) {
        try (PreparedStatement p = db.verbindung().prepareStatement("""
                SELECT a.oeffentlicher_schluessel
                FROM agent a
                JOIN ermittelt_terminal e ON e.agent_id = a.id
                WHERE a.aktiv AND a.oeffentlicher_schluessel IS NOT NULL
                  AND e.terminal_id = ?
                ORDER BY a.zuletzt_gesehen DESC NULLS LAST LIMIT 1""")) {
            p.setString(1, terminalId);
            try (ResultSet rs = p.executeQuery()) {
                return rs.next() ? Optional.ofNullable(rs.getString(1)) : Optional.empty();
            }
        } catch (SQLException e) { return Optional.empty(); }
    }

    private Optional<Long> agentFuerTerminal(String terminalId) {
        try (PreparedStatement p = db.verbindung().prepareStatement("""
                SELECT a.id FROM agent a
                JOIN ermittelt_terminal e ON e.agent_id = a.id
                WHERE a.aktiv AND e.terminal_id = ?
                ORDER BY a.zuletzt_gesehen DESC NULLS LAST LIMIT 1""")) {
            p.setString(1, terminalId);
            try (ResultSet rs = p.executeQuery()) {
                return rs.next() ? Optional.of(rs.getLong(1)) : Optional.empty();
            }
        } catch (SQLException e) { return Optional.empty(); }
    }

    // ---------------------------------------------------------------- Aufträge

    /**
     * Legt einen Auftrag an, eine PIN zu hinterlegen.
     *
     * @param chiffrat die im Browser verschlüsselte PIN. <b>Kein Klartext</b> —
     *        wer hier Klartext übergibt, bricht die Zusage aus ADR-0009.
     * @return Fehlermeldung im Klartext, oder {@code null}
     */
    public String hinterlegen(String terminalId, String kartenhandle, String chiffrat,
                              String angelegtVon) {
        if (chiffrat == null || chiffrat.isBlank()) {
            return "Es wurde keine verschlüsselte PIN übermittelt. Ist JavaScript im "
                 + "Browser abgeschaltet? Ohne Verschlüsselung im Browser kann die PIN "
                 + "nicht entgegengenommen werden.";
        }
        // Eine Laengenpruefung als Notbremse: Ein RSA-3072-Chiffrat ist immer
        // 384 Byte, Base64 also rund 512 Zeichen. Kaeme hier eine sechsstellige
        // Zeichenfolge an, waere das eine ungeschuetzte PIN - und das muss
        // auffallen, nicht durchgehen.
        if (chiffrat.length() < 300) {
            return "Die übermittelten Daten sehen nicht nach einer verschlüsselten PIN aus. "
                 + "Aus Sicherheitsgründen wurde nichts gespeichert.";
        }
        var agentId = agentFuerTerminal(terminalId);
        if (agentId.isEmpty()) {
            return "Für dieses Kartenterminal ist kein Standort-Agent bekannt. "
                 + "Die PIN kann erst hinterlegt werden, wenn ein Agent das Gerät gemeldet hat.";
        }
        return auftragAnlegen(agentId.get(), kartenhandle, "HINTERLEGEN", chiffrat, angelegtVon);
    }

    /**
     * Legt einen Auftrag an, die PIN zu ändern (FA-PIN-060, {@code A_24082}).
     *
     * <p><b>Beide Werte sind Chiffrate</b> — die alte PIN zum Nachweis, die
     * neue als Ersatz. Die Zentrale kann keinen von beiden lesen; sie prüft
     * nur, dass es überhaupt Chiffrate sind.
     *
     * <p>Die Prüfung, ob die neue PIN zweimal gleich eingegeben wurde, findet
     * im Browser statt — sie muss dort stattfinden, denn hier liegen nur noch
     * verschlüsselte Werte, und zwei Verschlüsselungen desselben Klartexts
     * sehen unterschiedlich aus (RSA-OAEP ist nicht deterministisch).
     */
    public String aendern(String terminalId, String kartenhandle, String chiffratAlt,
                          String chiffratNeu, String angelegtVon) {
        if (chiffratAlt == null || chiffratAlt.length() < 300
                || chiffratNeu == null || chiffratNeu.length() < 300) {
            return "Es wurden keine verschlüsselten PINs übermittelt. Ist JavaScript im "
                 + "Browser abgeschaltet? Ohne Verschlüsselung im Browser kann die Änderung "
                 + "nicht entgegengenommen werden.";
        }
        var agentId = agentFuerTerminal(terminalId);
        if (agentId.isEmpty()) {
            return "Für dieses Kartenterminal ist kein Standort-Agent bekannt.";
        }
        if (kartenhandle == null || kartenhandle.isBlank()) {
            return "Für dieses Terminal ist keine SMC-B gemeldet.";
        }
        try (PreparedStatement p = db.verbindung().prepareStatement("""
                INSERT INTO pin_auftrag (agent_id, kartenhandle, art, chiffrat, chiffrat_neu,
                                         angelegt_von)
                VALUES (?,?,'AENDERN',?,?,?)""")) {
            p.setLong(1, agentId.get());
            p.setString(2, kartenhandle);
            p.setString(3, chiffratAlt);
            p.setString(4, chiffratNeu);
            p.setString(5, angelegtVon);
            p.executeUpdate();
            return null;
        } catch (SQLException e) {
            return "Der Auftrag konnte nicht angelegt werden.";
        }
    }

    /** Legt einen Auftrag an, eine PIN zu löschen. */
    public String loeschen(String terminalId, String kartenhandle, String angelegtVon) {
        var agentId = agentFuerTerminal(terminalId);
        if (agentId.isEmpty()) {
            return "Für dieses Kartenterminal ist kein Standort-Agent bekannt.";
        }
        return auftragAnlegen(agentId.get(), kartenhandle, "LOESCHEN", null, angelegtVon);
    }

    private String auftragAnlegen(long agentId, String kartenhandle, String art,
                                  String chiffrat, String angelegtVon) {
        if (kartenhandle == null || kartenhandle.isBlank()) {
            return "Für dieses Terminal ist keine SMC-B gemeldet.";
        }
        try (PreparedStatement p = db.verbindung().prepareStatement("""
                INSERT INTO pin_auftrag (agent_id, kartenhandle, art, chiffrat, angelegt_von)
                VALUES (?,?,?,?,?)""")) {
            p.setLong(1, agentId);
            p.setString(2, kartenhandle);
            p.setString(3, art);
            p.setString(4, chiffrat);
            p.setString(5, angelegtVon);
            p.executeUpdate();
            return null;
        } catch (SQLException e) {
            return "Der Auftrag konnte nicht angelegt werden.";
        }
    }

    /**
     * Offene Aufträge für einen Agenten und Vermerk der Zustellung.
     *
     * <p><b>Das Chiffrat wird im selben Zug gelöscht.</b> Der Agent bekommt es
     * genau einmal; scheitert die Zustellung, muss neu hinterlegt werden. Das
     * ist die sicherere Richtung: Ein wiederholt abrufbares Chiffrat wäre ein
     * dauerhaftes Ziel.
     */
    public List<Auftrag> abholen(long agentId) {
        List<Auftrag> liste = new ArrayList<>();
        try (PreparedStatement p = db.verbindung().prepareStatement("""
                UPDATE pin_auftrag SET zugestellt = now()
                WHERE id IN (SELECT id FROM pin_auftrag
                             WHERE agent_id = ? AND zugestellt IS NULL
                             ORDER BY id LIMIT 50)
                RETURNING id, kartenhandle, art, chiffrat, chiffrat_neu""")) {
            p.setLong(1, agentId);
            try (ResultSet rs = p.executeQuery()) {
                while (rs.next()) {
                    liste.add(new Auftrag(rs.getLong("id"), rs.getString("kartenhandle"),
                            rs.getString("art"), rs.getString("chiffrat"),
                            rs.getString("chiffrat_neu")));
                }
            }
        } catch (SQLException e) {
            System.err.println("PIN-Aufträge nicht abrufbar: " + e.getMessage());
            return List.of();
        }

        // Chiffrate sofort loeschen - sie wurden gerade ausgeliefert.
        if (!liste.isEmpty()) {
            try (PreparedStatement p = db.verbindung().prepareStatement(
                    "UPDATE pin_auftrag SET chiffrat = NULL, chiffrat_neu = NULL "
                    + "WHERE id = ANY (?)")) {
                p.setArray(1, db.verbindung().createArrayOf("bigint",
                        liste.stream().map(Auftrag::id).toArray(Long[]::new)));
                p.executeUpdate();
            } catch (SQLException e) {
                System.err.println("Chiffrat nicht löschbar: " + e.getMessage());
            }
        }
        return liste;
    }

    /** Der Agent meldet zurück, ob es geklappt hat. */
    public void quittieren(long agentId, long auftragId, boolean erfolg, String fehler,
                           String kartenhandle, String art) {
        try (PreparedStatement p = db.verbindung().prepareStatement(
                "UPDATE pin_auftrag SET quittiert = now(), fehler = ? WHERE id = ? AND agent_id = ?")) {
            p.setString(1, erfolg ? null : fehler);
            p.setLong(2, auftragId);
            p.setLong(3, agentId);
            p.executeUpdate();
        } catch (SQLException ignored) { }

        if (!erfolg) return;
        try {
            if ("HINTERLEGEN".equals(art) || "AENDERN".equals(art)) {
                try (PreparedStatement p = db.verbindung().prepareStatement("""
                        INSERT INTO pin_hinterlegt (agent_id, kartenhandle) VALUES (?,?)
                        ON CONFLICT (agent_id, kartenhandle) DO UPDATE SET seit = now()""")) {
                    p.setLong(1, agentId);
                    p.setString(2, kartenhandle);
                    p.executeUpdate();
                }
            } else {
                try (PreparedStatement p = db.verbindung().prepareStatement(
                        "DELETE FROM pin_hinterlegt WHERE agent_id = ? AND kartenhandle = ?")) {
                    p.setLong(1, agentId);
                    p.setString(2, kartenhandle);
                    p.executeUpdate();
                }
            }
        } catch (SQLException ignored) { }
    }

    // ------------------------------------------------------------- Kennzeichen

    /** Ob für dieses Kartenhandle eine PIN hinterlegt ist. Nur das — nie der Wert. */
    public Optional<Hinterlegt> hinterlegtFuer(String kartenhandle) {
        try (PreparedStatement p = db.verbindung().prepareStatement("""
                SELECT h.kartenhandle, h.seit FROM pin_hinterlegt h
                JOIN agent a ON a.id = h.agent_id AND a.aktiv
                WHERE h.kartenhandle = ? LIMIT 1""")) {
            p.setString(1, kartenhandle);
            try (ResultSet rs = p.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new Hinterlegt(rs.getString(1),
                        rs.getTimestamp(2).toLocalDateTime()
                                .format(java.time.format.DateTimeFormatter
                                        .ofPattern("dd.MM.yyyy HH:mm"))));
            }
        } catch (SQLException e) { return Optional.empty(); }
    }

    /**
     * SMC-B-Karten, für die eine PIN hinterlegt sein sollte, deren Agent den
     * Schlüssel aber nicht mehr hat (FA-INS-040).
     *
     * <h2>Wozu</h2>
     *
     * Nach einer Wiederherstellung der Zentrale steht in {@code pin_hinterlegt},
     * welche Karten versorgt waren. Wurde der <b>Agent</b> neu aufgesetzt, ist
     * sein Schlüsselpaar ein anderes — die PINs in seiner Ablage sind unlesbar
     * oder gar nicht mehr da. Die Zentrale kann das nicht selbst merken: Sie
     * sieht nur, dass der Agent sich mit einem <b>neuen öffentlichen
     * Schlüssel</b> gemeldet hat.
     *
     * <p>Genau das ist das Kennzeichen. Ohne diese Ansicht bliebe die Praxis im
     * Glauben, alles sei versorgt — bis die erste Karte unverifiziert bleibt
     * und niemand weiß, warum.
     */
    public List<Hinterlegt> neuZuHinterlegen() {
        List<Hinterlegt> liste = new ArrayList<>();
        try (PreparedStatement p = db.verbindung().prepareStatement("""
                SELECT h.kartenhandle, h.seit
                FROM pin_hinterlegt h
                JOIN agent a ON a.id = h.agent_id AND a.aktiv
                WHERE a.schluessel_gemeldet IS NOT NULL
                  AND a.schluessel_gemeldet > h.seit
                ORDER BY h.seit""")) {
            try (ResultSet rs = p.executeQuery()) {
                while (rs.next()) {
                    liste.add(new Hinterlegt(rs.getString(1),
                            rs.getTimestamp(2).toLocalDateTime()
                                    .format(java.time.format.DateTimeFormatter
                                            .ofPattern("dd.MM.yyyy HH:mm"))));
                }
            }
        } catch (SQLException e) {
            System.err.println("Nicht ermittelbar, welche PINs fehlen: " + e.getMessage());
        }
        return liste;
    }

    /** Ob ein Auftrag noch auf Zustellung wartet — für die Anzeige „wird übertragen". */
    public boolean auftragOffen(String kartenhandle) {
        try (PreparedStatement p = db.verbindung().prepareStatement(
                "SELECT 1 FROM pin_auftrag WHERE kartenhandle = ? AND quittiert IS NULL LIMIT 1")) {
            p.setString(1, kartenhandle);
            try (ResultSet rs = p.executeQuery()) { return rs.next(); }
        } catch (SQLException e) { return false; }
    }
}
