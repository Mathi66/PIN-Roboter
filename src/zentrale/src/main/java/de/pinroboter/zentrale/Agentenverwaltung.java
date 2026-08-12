package de.pinroboter.zentrale;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Registrierung und Authentisierung der Standort-Agenten (FA-AGT-010/020).
 *
 * <p>Bis hierher waren die Agent-Schnittstellen ungeschützt: Wer das Netz der
 * Zentrale erreichte, konnte Gerätezustände melden. Das ist die letzte große
 * Lücke, die diese Klasse schließt.
 *
 * <p><b>Zwei Geheimnisse, zwei Lebensdauern:</b>
 * <ul>
 *   <li>Das <b>Registrierungsgeheimnis</b> gilt <em>einmal</em> und läuft ab.
 *       Ein Geheimnis, das beliebig oft gilt, ist ein Dauerzugang — genau das,
 *       was ein Registrierungsverfahren vermeiden soll.</li>
 *   <li>Das <b>Agent-Token</b> entsteht bei der Registrierung und gilt dauerhaft,
 *       bis es widerrufen wird.</li>
 * </ul>
 *
 * <p>Beide werden nur als Hash gespeichert. Ein Datenbankauszug reicht damit
 * nicht, um sich als Agent auszugeben — dieselbe Überlegung wie bei Passwörtern.
 * Verwendet wird SHA-256 statt Argon2: Die Werte sind 256 Bit Zufall aus einem
 * kryptographisch sicheren Generator, nicht menschengewählt. Gegen Zufall dieser
 * Länge hilft kein Durchprobieren, also braucht es kein absichtlich langsames
 * Verfahren — die Prüfung liegt auf dem heißen Pfad jeder Agentenmeldung.
 */
public class Agentenverwaltung {

    /** Wie lange ein Registrierungsgeheimnis gilt. Kurz genug, um nicht zu verwaisen. */
    public static final Duration GUELTIGKEIT = Duration.ofHours(24);

    private final Datenbank db;
    private final SecureRandom zufall = new SecureRandom();

    public Agentenverwaltung(Datenbank db) { this.db = db; }

    /**
     * @param einrichtungBekannt ob es eine Organisationseinheit dieses Namens
     *        gibt. Der Agent speichert die Einrichtung als <b>Text</b>; wird sie
     *        umbenannt oder gelöscht, meldet er ins Leere — die Zentrale kann
     *        seine Geräte dann keiner Gruppe zuordnen. Ohne dieses Merkmal
     *        stünde der Befund nur im technischen Protokoll.
     */
    public record Agent(long id, String bezeichnung, String einrichtung,
                        Instant zuletztGesehen, Integer protokollversion,
                        String bausteinversion, boolean einrichtungBekannt) {

        /** Ob die Zentrale mit diesem Agenten reden kann (FA-AGT-040). */
        public boolean versionUnterstuetzt() {
            return protokollversion == null
                    || de.pinroboter.gemeinsam.Protokollversion.unterstuetzt(protokollversion);
        }

        /** Klartext zur Version, oder {@code null} wenn alles passt. */
        public String versionsbewertung() {
            return protokollversion == null
                    ? "Dieser Agent hat sich noch nicht gemeldet."
                    : de.pinroboter.gemeinsam.Protokollversion.bewertung(protokollversion);
        }
    }

    public sealed interface Registrierung {
        record Erfolg(String token, long agentId) implements Registrierung {}
        record Abgelehnt(String grund) implements Registrierung {}
    }

    // ------------------------------------------------------- Geheimnis ausgeben

    /**
     * Erzeugt ein einmalig gültiges Registrierungsgeheimnis.
     * Der Klartext wird <b>nur hier</b> zurückgegeben und nirgends gespeichert.
     */
    public String geheimnisAnlegen(String bezeichnung, String einrichtung, String angelegtVon) {
        String geheimnis = zufallswert();
        try (PreparedStatement p = db.verbindung().prepareStatement("""
                INSERT INTO registrierung (geheimnis_hash, bezeichnung, einrichtung,
                                           gueltig_bis, angelegt_von)
                VALUES (?,?,?,?,?)""")) {
            p.setString(1, hash(geheimnis));
            p.setString(2, bezeichnung);
            p.setString(3, einrichtung);
            p.setTimestamp(4, Timestamp.from(Instant.now().plus(GUELTIGKEIT)));
            p.setString(5, angelegtVon);
            p.executeUpdate();
            return geheimnis;
        } catch (SQLException e) {
            throw new IllegalStateException("Registrierungsgeheimnis nicht anlegbar", e);
        }
    }

    // ------------------------------------------------------- Registrierung

    /**
     * Löst ein Registrierungsgeheimnis ein. Prüft in dieser Reihenfolge:
     * bekannt, nicht verbraucht, nicht abgelaufen.
     */
    public synchronized Registrierung registrieren(String geheimnis) {
        if (geheimnis == null || geheimnis.isBlank()) {
            return new Registrierung.Abgelehnt("Kein Registrierungsgeheimnis übermittelt.");
        }
        String h = hash(geheimnis);
        try {
            String bezeichnung, einrichtung;
            try (PreparedStatement p = db.verbindung().prepareStatement("""
                    SELECT bezeichnung, einrichtung, gueltig_bis, verbraucht
                    FROM registrierung WHERE geheimnis_hash = ?""")) {
                p.setString(1, h);
                try (ResultSet rs = p.executeQuery()) {
                    if (!rs.next()) {
                        return new Registrierung.Abgelehnt("Unbekanntes Registrierungsgeheimnis.");
                    }
                    if (rs.getTimestamp("verbraucht") != null) {
                        return new Registrierung.Abgelehnt(
                                "Dieses Registrierungsgeheimnis wurde bereits verwendet.");
                    }
                    if (rs.getTimestamp("gueltig_bis").toInstant().isBefore(Instant.now())) {
                        return new Registrierung.Abgelehnt(
                                "Dieses Registrierungsgeheimnis ist abgelaufen.");
                    }
                    bezeichnung = rs.getString("bezeichnung");
                    einrichtung = rs.getString("einrichtung");
                }
            }

            String token = zufallswert();
            long id;
            try (PreparedStatement p = db.verbindung().prepareStatement("""
                    INSERT INTO agent (bezeichnung, einrichtung, token_hash)
                    VALUES (?,?,?) RETURNING id""")) {
                p.setString(1, bezeichnung);
                p.setString(2, einrichtung);
                p.setString(3, hash(token));
                try (ResultSet rs = p.executeQuery()) { rs.next(); id = rs.getLong(1); }
            }
            try (PreparedStatement p = db.verbindung().prepareStatement(
                    "UPDATE registrierung SET verbraucht = now() WHERE geheimnis_hash = ?")) {
                p.setString(1, h);
                p.executeUpdate();
            }
            return new Registrierung.Erfolg(token, id);
        } catch (SQLException e) {
            return new Registrierung.Abgelehnt("Die Registrierung ist derzeit nicht möglich.");
        }
    }

    // ------------------------------------------------------- Authentisierung

    /** Prüft ein Agent-Token. Vermerkt nebenbei, wann der Agent zuletzt gesehen wurde. */
    public Optional<Agent> pruefeToken(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        try (PreparedStatement p = db.verbindung().prepareStatement("""
                UPDATE agent SET zuletzt_gesehen = now()
                WHERE token_hash = ? AND aktiv
                RETURNING id, bezeichnung, einrichtung, zuletzt_gesehen,
                          protokollversion, bausteinversion""")) {
            p.setString(1, hash(token));
            try (ResultSet rs = p.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                int v = rs.getInt("protokollversion");
                Integer version = rs.wasNull() ? null : v;   // sofort auswerten
                return Optional.of(new Agent(rs.getLong("id"), rs.getString("bezeichnung"),
                        rs.getString("einrichtung"),
                        rs.getTimestamp("zuletzt_gesehen").toInstant(),
                        version, rs.getString("bausteinversion"),
                        // Hier nicht ermittelt: Die Tokenpruefung laeuft bei
                        // jeder Agentmeldung und darf keine zweite Abfrage
                        // kosten. Der Befund gehoert in die Uebersicht, nicht in
                        // den Meldeweg.
                        true));
            }
        } catch (SQLException e) { return Optional.empty(); }
    }

    public List<Agent> alle() {
        List<Agent> liste = new ArrayList<>();
        try (Statement s = db.verbindung().createStatement();
             ResultSet rs = s.executeQuery("""
                     SELECT a.id, a.bezeichnung, a.einrichtung, a.zuletzt_gesehen,
                            a.protokollversion, a.bausteinversion,
                            EXISTS (SELECT 1 FROM organisation o
                                     WHERE o.bezeichnung = a.einrichtung) AS einr_da
                     FROM agent a WHERE a.aktiv ORDER BY a.bezeichnung""")) {
            while (rs.next()) {
                Timestamp t = rs.getTimestamp("zuletzt_gesehen");
                int v = rs.getInt("protokollversion");
                Integer version = rs.wasNull() ? null : v;   // sofort auswerten
                liste.add(new Agent(rs.getLong("id"), rs.getString("bezeichnung"),
                        rs.getString("einrichtung"), t == null ? null : t.toInstant(),
                        version, rs.getString("bausteinversion"),
                        rs.getBoolean("einr_da")));
            }
        }
        catch (SQLException e) {
            // Nicht verschlucken: Ein stiller SQL-Fehler ist praktisch
            // unauffindbar - so blieb ein fehlerhaftes WHERE monatelang
            // unentdeckt. Der Aufruf schlaegt trotzdem nicht fehl.
            System.err.println("Agentenverwaltung.alle: " + e.getMessage());
        }
        return liste;
    }

    /**
     * Ändert die Einrichtung eines registrierten Agenten.
     *
     * <p><b>Warum das möglich sein muss.</b> Ursprünglich war die Zuordnung
     * unveränderlich — „ein Agent steht in genau einer Praxis". Das stimmt, löst
     * aber den Fall nicht, der in der Praxis eintritt: Die Einrichtung wird
     * umbenannt oder umstrukturiert, und weil der Agent den <em>Namen</em>
     * speichert, meldet er ab da ins Leere. Ohne Korrekturmöglichkeit bleibt nur
     * die Neuregistrierung — mit neuem Schlüssel und dem Verlust aller
     * hinterlegten PINs an diesem Standort.
     *
     * <p>Die Änderung ist ein Sicherheitsereignis: Sie verschiebt, wohin ein
     * Agent meldet. Sie gehört deshalb ins Fachprotokoll, und sie ist nur
     * innerhalb des eigenen Geltungsbereichs möglich.
     *
     * @return Fehlertext oder {@code null} bei Erfolg
     */
    public String einrichtungSetzen(long agentId, String einrichtung, Geltungsbereich bereich) {
        if (einrichtung == null || einrichtung.isBlank()) {
            return "Bitte eine Einrichtung wählen.";
        }
        String name = einrichtung.trim();
        // Die Einrichtung muss existieren UND im Bereich des Aufrufers liegen -
        // sonst koennte jemand einen Agenten in eine fremde Einrichtung
        // umhaengen und damit deren Geraete in seine Sicht holen.
        try (PreparedStatement p = db.verbindung().prepareStatement(
                "SELECT count(*) FROM organisation WHERE bezeichnung = ?"
                + bereich.bedingung("id"))) {
            p.setString(1, name);
            bereich.wertSetzen(db.verbindung(), p, 2);
            try (ResultSet rs = p.executeQuery()) {
                rs.next();
                if (rs.getInt(1) != 1) {
                    return "„" + name + "“ ist keine eindeutige Organisationseinheit "
                            + "in Ihrem Zuständigkeitsbereich.";
                }
            }
        } catch (SQLException e) { return "Die Einrichtung konnte nicht geprüft werden."; }

        try (PreparedStatement p = db.verbindung().prepareStatement(
                "UPDATE agent SET einrichtung = ? WHERE id = ? AND aktiv")) {
            p.setString(1, name);
            p.setLong(2, agentId);
            return p.executeUpdate() > 0 ? null : "Diesen Agenten gibt es nicht mehr.";
        } catch (SQLException e) { return "Die Änderung konnte nicht gespeichert werden."; }
    }

    /** Die aktuelle Einrichtung eines Agenten — für das Protokoll vor der Änderung. */
    public String einrichtungVon(long agentId) {
        try (PreparedStatement p = db.verbindung().prepareStatement(
                "SELECT einrichtung FROM agent WHERE id = ?")) {
            p.setLong(1, agentId);
            try (ResultSet rs = p.executeQuery()) { return rs.next() ? rs.getString(1) : null; }
        } catch (SQLException e) { return null; }
    }

    public boolean widerrufen(long agentId) {
        try (PreparedStatement p = db.verbindung().prepareStatement(
                "UPDATE agent SET aktiv = FALSE WHERE id = ?")) {
            p.setLong(1, agentId);
            return p.executeUpdate() == 1;
        } catch (SQLException e) { return false; }
    }

    // ------------------------------------------------------- Hilfsmittel

    /** 256 Bit aus einem kryptographisch sicheren Generator. */
    private String zufallswert() {
        byte[] b = new byte[32];
        zufall.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    static String hash(String wert) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256")
                    .digest(wert.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(h);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 nicht verfügbar", e);
        }
    }
}
