package de.pinroboter.zentrale;

import de.pinroboter.gemeinsam.Passwort;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Einladungen für neue Benutzerkonten (Block B, FA-BEN-010).
 *
 * <h2>Warum eingeladen und nicht angelegt wird</h2>
 *
 * Ein Administrator, der ein Kennwort festlegt, <b>kennt es</b>. Damit ist jede
 * spätere Handlung dieses Kontos nicht mehr eindeutig zurechenbar — und genau
 * das braucht ein System, das selbsttätig handelt und dessen Auslösung
 * nachvollziehbar sein muss (ADR-0005).
 *
 * <p>Stattdessen: Der Administrator lädt ein, der Eingeladene setzt sein
 * Kennwort selbst und richtet den zweiten Faktor ein. Der Administrator sieht
 * das Kennwort nie.
 *
 * <h2>Wie das Geheimnis geschützt ist</h2>
 *
 * Gespeichert wird nur der <b>SHA-256-Hash</b>. Wer die Datenbank liest, kann
 * keine Einladung einlösen. Der Klartext erscheint genau einmal — beim
 * Erzeugen — und wird danach nirgends wieder ausgegeben.
 *
 * <p>Eine Einladung ist <b>einmalig</b> und <b>befristet</b>. Ein Geheimnis, das
 * beliebig oft und unbegrenzt gilt, ist ein Dauerzugang.
 */
public class Einladungen {

    /** Wie lange eine Einladung gilt. Lang genug für einen Arbeitstag, kurz genug, um zu verfallen. */
    public static final Duration GUELTIGKEIT = Duration.ofDays(7);

    public record Einladung(String benutzername, String anzeigename,
                            Benutzerverwaltung.Rolle rolle, Long organisationId,
                            String einrichtung, Instant gueltigBis, String eingeladenVon,
                            boolean verbraucht) {
        public boolean abgelaufen() { return Instant.now().isAfter(gueltigBis); }
        public boolean offen()      { return !verbraucht && !abgelaufen(); }
    }

    /** Ergebnis des Einlösens. */
    public sealed interface Einloesung {
        record Erfolg(long benutzerId, String totpGeheimnis, String totpUri) implements Einloesung {}
        record Abgelehnt(String grund) implements Einloesung {}
    }

    private final Datenbank db;
    private final Benutzerverwaltung benutzer;
    private final SecureRandom zufall = new SecureRandom();

    public Einladungen(Datenbank db, Benutzerverwaltung benutzer) {
        this.db = db;
        this.benutzer = benutzer;
    }

    /**
     * Erzeugt eine Einladung.
     *
     * @return das Geheimnis im Klartext — <b>erscheint genau einmal</b> —, oder
     *         eine Fehlermeldung. Der Aufrufer muss es dem Eingeladenen auf
     *         einem Weg zukommen lassen, den er selbst verantwortet.
     */
    public Ergebnis einladen(String benutzername, String anzeigename,
                             Benutzerverwaltung.Rolle rolle, Long organisationId,
                             String eingeladenVon, Geltungsbereich bereich) {
        if (benutzername == null || benutzername.isBlank()) {
            return Ergebnis.fehler("Bitte einen Benutzernamen angeben.");
        }
        if (anzeigename == null || anzeigename.isBlank()) {
            return Ergebnis.fehler("Bitte einen Namen angeben, unter dem die Person "
                    + "im Protokoll erscheint.");
        }
        // Die Einrichtung kommt aus einem Formularfeld und ist manipulierbar.
        // Ohne diese Pruefung koennte jemand ein Konto in einer fremden
        // Einrichtung anlegen - und sich damit selbst Zugang dorthin schaffen.
        if (!bereich.umfasst(organisationId)) {
            return Ergebnis.fehler("Die gewählte Einrichtung liegt außerhalb Ihres "
                    + "Zuständigkeitsbereichs.");
        }
        if (benutzer.alle(Geltungsbereich.alles()).stream()
                .anyMatch(b -> b.benutzername().equalsIgnoreCase(benutzername.trim()))) {
            return Ergebnis.fehler("Es gibt bereits ein Konto mit diesem Benutzernamen.");
        }

        String geheimnis = neuesGeheimnis();
        try (PreparedStatement p = db.verbindung().prepareStatement("""
                INSERT INTO einladung (geheimnis_hash, benutzername, anzeigename, rolle,
                    organisation_id, gueltig_bis, eingeladen_von)
                VALUES (?,?,?,?,?,?,?)""")) {
            p.setString(1, hash(geheimnis));
            p.setString(2, benutzername.trim());
            p.setString(3, anzeigename.trim());
            p.setString(4, rolle.name());
            if (organisationId == null) p.setNull(5, Types.BIGINT); else p.setLong(5, organisationId);
            p.setTimestamp(6, Timestamp.from(Instant.now().plus(GUELTIGKEIT)));
            p.setString(7, eingeladenVon);
            p.executeUpdate();
            return Ergebnis.erfolg(geheimnis);
        } catch (SQLException e) {
            if ("23505".equals(e.getSQLState())) {
                return Ergebnis.fehler("Für diesen Benutzernamen ist bereits eine Einladung offen. "
                        + "Bitte diese zurückziehen, bevor eine neue erzeugt wird.");
            }
            return Ergebnis.fehler("Die Einladung konnte nicht erzeugt werden.");
        }
    }

    /**
     * Löst eine Einladung ein: Kennwort setzen, Konto anlegen, TOTP vorbereiten.
     *
     * <p>Die Einladung wird dabei <b>verbraucht</b>. Ein zweiter Versuch mit
     * demselben Geheimnis wird abgewiesen — auch dann, wenn das Anlegen des
     * Kontos scheitert, denn sonst ließe sich ein Geheimnis durch absichtliche
     * Fehlversuche beliebig oft prüfen.
     */
    public synchronized Einloesung einloesen(String geheimnis, char[] kennwort) {
        if (geheimnis == null || geheimnis.isBlank()) {
            return new Einloesung.Abgelehnt("Diese Einladung ist nicht gültig.");
        }
        String h = hash(geheimnis);
        String name, anzeige, rolle;
        Long orgId;
        try (PreparedStatement p = db.verbindung().prepareStatement("""
                SELECT benutzername, anzeigename, rolle, organisation_id, gueltig_bis, verbraucht
                FROM einladung WHERE geheimnis_hash = ?""")) {
            p.setString(1, h);
            try (ResultSet rs = p.executeQuery()) {
                if (!rs.next()) {
                    return new Einloesung.Abgelehnt("Diese Einladung ist nicht gültig. "
                            + "Bitte beim Administrator eine neue anfordern.");
                }
                if (rs.getTimestamp("verbraucht") != null) {
                    return new Einloesung.Abgelehnt("Diese Einladung wurde bereits verwendet.");
                }
                if (rs.getTimestamp("gueltig_bis").toInstant().isBefore(Instant.now())) {
                    return new Einloesung.Abgelehnt("Diese Einladung ist abgelaufen. "
                            + "Bitte beim Administrator eine neue anfordern.");
                }
                name = rs.getString("benutzername");
                anzeige = rs.getString("anzeigename");
                rolle = rs.getString("rolle");
                long o = rs.getLong("organisation_id");
                orgId = rs.wasNull() ? null : o;
            }
        } catch (SQLException e) {
            return new Einloesung.Abgelehnt("Die Einladung kann derzeit nicht geprüft werden.");
        }

        String schwach = Passwort.pruefeStaerke(kennwort);
        if (schwach != null) return new Einloesung.Abgelehnt(schwach);

        // Erst verbrauchen, dann anlegen: Sonst liesse sich ein Geheimnis durch
        // absichtliche Fehlversuche beliebig oft pruefen.
        try (PreparedStatement p = db.verbindung().prepareStatement(
                "UPDATE einladung SET verbraucht = now() WHERE geheimnis_hash = ? AND verbraucht IS NULL")) {
            p.setString(1, h);
            if (p.executeUpdate() != 1) {
                return new Einloesung.Abgelehnt("Diese Einladung wurde bereits verwendet.");
            }
        } catch (SQLException e) {
            return new Einloesung.Abgelehnt("Die Einladung kann derzeit nicht eingelöst werden.");
        }

        var angelegt = benutzer.anlegen(name, anzeige, kennwort,
                Benutzerverwaltung.Rolle.valueOf(rolle));
        if (!(angelegt instanceof Benutzerverwaltung.Anmeldung.TotpEinrichten t)) {
            String grund = angelegt instanceof Benutzerverwaltung.Anmeldung.Abgelehnt a
                    ? a.meldung() : "Das Konto konnte nicht angelegt werden.";
            return new Einloesung.Abgelehnt(grund);
        }

        if (orgId != null) {
            try (PreparedStatement p = db.verbindung().prepareStatement(
                    "UPDATE benutzer SET organisation_id = ? WHERE id = ?")) {
                p.setLong(1, orgId);
                p.setLong(2, t.benutzerId());
                p.executeUpdate();
            }
            catch (SQLException e) {
                // Nicht verschlucken: Ein stiller SQL-Fehler ist praktisch
                // unauffindbar - so blieb ein fehlerhaftes WHERE monatelang
                // unentdeckt. Der Aufruf schlaegt trotzdem nicht fehl.
                System.err.println("Einladungen.einloesen: " + e.getMessage());
            }
        }
        return new Einloesung.Erfolg(t.benutzerId(), t.geheimnis(), t.uri());
    }

    /** Offene und abgelaufene Einladungen im Geltungsbereich. */
    public List<Einladung> offene(Geltungsbereich bereich) {
        List<Einladung> liste = new ArrayList<>();
        try (PreparedStatement p = db.verbindung().prepareStatement("""
                SELECT e.benutzername, e.anzeigename, e.rolle, e.organisation_id,
                       o.bezeichnung AS org_bez, e.gueltig_bis, e.eingeladen_von, e.verbraucht
                FROM einladung e
                LEFT JOIN organisation o ON o.id = e.organisation_id
                WHERE e.verbraucht IS NULL""" + bereich.bedingung("e.organisation_id")
                + " ORDER BY e.angelegt DESC")) {
            bereich.wertSetzen(db.verbindung(), p, 1);
            try (ResultSet rs = p.executeQuery()) {
                while (rs.next()) {
                    long o = rs.getLong("organisation_id");
                    Long orgId = rs.wasNull() ? null : o;
                    liste.add(new Einladung(rs.getString("benutzername"), rs.getString("anzeigename"),
                            Benutzerverwaltung.Rolle.valueOf(rs.getString("rolle")), orgId,
                            rs.getString("org_bez"), rs.getTimestamp("gueltig_bis").toInstant(),
                            rs.getString("eingeladen_von"), false));
                }
            }
        } catch (SQLException e) {
            System.err.println("Einladungen nicht lesbar: " + e.getMessage());
        }
        return liste;
    }

    /** Zieht eine offene Einladung zurück. */
    public boolean zurueckziehen(String benutzername, Geltungsbereich bereich) {
        try (PreparedStatement p = db.verbindung().prepareStatement(
                "DELETE FROM einladung WHERE lower(benutzername) = lower(?) AND verbraucht IS NULL"
                + bereich.bedingung("organisation_id"))) {
            p.setString(1, benutzername);
            bereich.wertSetzen(db.verbindung(), p, 2);
            return p.executeUpdate() > 0;
        } catch (SQLException e) { return false; }
    }

    // -------------------------------------------------------------- Innenleben

    public record Ergebnis(String geheimnis, String fehler) {
        static Ergebnis erfolg(String g) { return new Ergebnis(g, null); }
        static Ergebnis fehler(String f) { return new Ergebnis(null, f); }
        public boolean geklappt()        { return fehler == null; }
    }

    private String neuesGeheimnis() {
        byte[] roh = new byte[32];
        zufall.nextBytes(roh);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(roh);
    }

    static String hash(String klartext) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256")
                    .digest(klartext.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(h);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 nicht verfügbar", e);
        }
    }
}
