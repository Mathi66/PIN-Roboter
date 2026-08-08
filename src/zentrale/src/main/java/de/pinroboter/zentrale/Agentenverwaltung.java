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

    public record Agent(long id, String bezeichnung, String einrichtung,
                        Instant zuletztGesehen, Integer protokollversion,
                        String bausteinversion) {

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
                        version, rs.getString("bausteinversion")));
            }
        } catch (SQLException e) { return Optional.empty(); }
    }

    public List<Agent> alle() {
        List<Agent> liste = new ArrayList<>();
        try (Statement s = db.verbindung().createStatement();
             ResultSet rs = s.executeQuery("""
                     SELECT id, bezeichnung, einrichtung, zuletzt_gesehen,
                            protokollversion, bausteinversion
                     FROM agent WHERE aktiv ORDER BY bezeichnung""")) {
            while (rs.next()) {
                Timestamp t = rs.getTimestamp("zuletzt_gesehen");
                int v = rs.getInt("protokollversion");
                Integer version = rs.wasNull() ? null : v;   // sofort auswerten
                liste.add(new Agent(rs.getLong("id"), rs.getString("bezeichnung"),
                        rs.getString("einrichtung"), t == null ? null : t.toInstant(),
                        version, rs.getString("bausteinversion")));
            }
        } catch (SQLException ignored) { }
        return liste;
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
