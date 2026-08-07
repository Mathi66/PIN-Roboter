package de.pinroboter.zentrale;

import de.pinroboter.gemeinsam.Passwort;
import de.pinroboter.gemeinsam.Totp;

import java.security.SecureRandom;
import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Benutzer, Rollen, Anmeldung mit zweitem Faktor (FA-BEN-010 bis FA-BEN-040).
 *
 * <p>Die Anmeldung verlangt <b>immer</b> Benutzername, Passwort und TOTP. Der
 * zweite Faktor ist nicht abschaltbar — das Produkt verwaltet SMC-B-PINs, und
 * ein gestohlenes Passwort allein darf nicht genügen.
 */
public class Benutzerverwaltung {

    /** Nach so vielen Fehlversuchen wird das Konto zeitweise gesperrt. */
    private static final int MAX_FEHLVERSUCHE = 5;
    private static final Duration SPERRDAUER = Duration.ofMinutes(15);
    private static final Duration SITZUNG_UNTAETIG = Duration.ofMinutes(30);

    public enum Rolle {
        ADMINISTRATOR("Administrator", "verwaltet Benutzer und Einstellungen"),
        BENUTZER("Benutzer", "verwaltet Kartenterminals");

        private final String anzeige, beschreibung;
        Rolle(String a, String b) { anzeige = a; beschreibung = b; }
        public String anzeige() { return anzeige; }
        public String beschreibung() { return beschreibung; }
    }

    public record Benutzer(long id, String benutzername, String anzeigename,
                           Rolle rolle, boolean aktiv, boolean totpBestaetigt) {}

    /** Ergebnis einer Anmeldung. Meldungen sagen, was zu tun ist (NFA-USA-020). */
    public sealed interface Anmeldung {
        record Erfolg(String sitzung, Benutzer benutzer) implements Anmeldung {}
        record TotpEinrichten(long benutzerId, String uri, String geheimnis) implements Anmeldung {}
        record Abgelehnt(String meldung) implements Anmeldung {}
        record Gesperrt(String meldung) implements Anmeldung {}
    }

    private final Datenbank db;
    private final SecureRandom zufall = new SecureRandom();

    public Benutzerverwaltung(Datenbank db) { this.db = db; }

    // ------------------------------------------------------------- Onboarding

    /** FA-BEN-030: Solange kein Konto existiert, ist nur die Ersteinrichtung erreichbar. */
    public boolean ersteinrichtungNoetig() {
        try (Statement s = db.verbindung().createStatement();
             ResultSet rs = s.executeQuery("SELECT count(*) FROM benutzer WHERE aktiv")) {
            return rs.next() && rs.getLong(1) == 0;
        } catch (SQLException e) { return false; }
    }

    /**
     * Legt einen Benutzer an und erzeugt sein TOTP-Geheimnis.
     * Das Geheimnis gilt erst nach einmaliger Verifikation (FA-BEN-020).
     */
    public Anmeldung anlegen(String benutzername, String anzeigename,
                             char[] passwort, Rolle rolle) {
        String schwach = Passwort.pruefeStaerke(passwort);
        if (schwach != null) return new Anmeldung.Abgelehnt(schwach);
        if (benutzername == null || benutzername.isBlank()) {
            return new Anmeldung.Abgelehnt("Bitte einen Benutzernamen angeben.");
        }

        byte[] geheimnis = Totp.geheimnisErzeugen();
        String sql = """
                INSERT INTO benutzer (benutzername, anzeigename, passwort_hash,
                                      totp_geheimnis, rolle)
                VALUES (?,?,?,?,?) RETURNING id""";
        try (PreparedStatement p = db.verbindung().prepareStatement(sql)) {
            p.setString(1, benutzername.trim());
            p.setString(2, anzeigename == null || anzeigename.isBlank()
                    ? benutzername.trim() : anzeigename.trim());
            p.setString(3, Passwort.hashen(passwort));
            p.setString(4, Totp.alsBase32(geheimnis));
            p.setString(5, rolle.name());
            try (ResultSet rs = p.executeQuery()) {
                rs.next();
                long id = rs.getLong(1);
                return new Anmeldung.TotpEinrichten(id,
                        Totp.einrichtungsUri(benutzername.trim(), geheimnis, "PIN-Roboter"),
                        Totp.alsBase32(geheimnis));
            }
        } catch (SQLException e) {
            if ("23505".equals(e.getSQLState())) {
                return new Anmeldung.Abgelehnt("Diesen Benutzernamen gibt es bereits.");
            }
            return new Anmeldung.Abgelehnt("Der Benutzer konnte nicht angelegt werden.");
        } finally {
            Arrays.fill(passwort, '\0');
        }
    }

    /** Schließt die TOTP-Einrichtung ab — erst nach erfolgreicher Probe. */
    public boolean totpBestaetigen(long benutzerId, String code) {
        try (PreparedStatement p = db.verbindung().prepareStatement(
                "SELECT totp_geheimnis FROM benutzer WHERE id = ?")) {
            p.setLong(1, benutzerId);
            try (ResultSet rs = p.executeQuery()) {
                if (!rs.next()) return false;
                byte[] g = Totp.ausBase32(rs.getString(1));
                if (!Totp.pruefe(g, code, Instant.now())) return false;
            }
        } catch (Exception e) { return false; }

        try (PreparedStatement p = db.verbindung().prepareStatement(
                "UPDATE benutzer SET totp_bestaetigt = TRUE WHERE id = ?")) {
            p.setLong(1, benutzerId);
            return p.executeUpdate() == 1;
        } catch (SQLException e) { return false; }
    }

    // ------------------------------------------------------------- Anmeldung

    public Anmeldung anmelden(String benutzername, char[] passwort, String totpCode) {
        try {
            String sql = """
                    SELECT id, benutzername, anzeigename, passwort_hash, totp_geheimnis,
                           totp_bestaetigt, rolle, aktiv, fehlversuche, gesperrt_bis,
                           letztes_totp_fenster
                    FROM benutzer WHERE lower(benutzername) = lower(?)""";
            try (PreparedStatement p = db.verbindung().prepareStatement(sql)) {
                p.setString(1, benutzername == null ? "" : benutzername.trim());
                try (ResultSet rs = p.executeQuery()) {
                    if (!rs.next()) {
                        // Bewusst dieselbe Meldung wie bei falschem Passwort:
                        // Sonst verrät die Anmeldung, welche Konten existieren.
                        return new Anmeldung.Abgelehnt(
                                "Benutzername, Passwort oder Einmalkennwort stimmen nicht.");
                    }
                    long id = rs.getLong("id");

                    Timestamp gesperrt = rs.getTimestamp("gesperrt_bis");
                    if (gesperrt != null && gesperrt.toInstant().isAfter(Instant.now())) {
                        long min = Duration.between(Instant.now(), gesperrt.toInstant()).toMinutes() + 1;
                        return new Anmeldung.Gesperrt(
                                "Das Konto ist nach mehreren Fehlversuchen vorübergehend gesperrt. "
                              + "Bitte in " + min + " Minuten erneut versuchen.");
                    }
                    if (!rs.getBoolean("aktiv")) {
                        return new Anmeldung.Abgelehnt(
                                "Dieses Konto ist deaktiviert. Bitte an einen Administrator wenden.");
                    }

                    boolean passtPasswort = Passwort.pruefen(passwort, rs.getString("passwort_hash"));
                    byte[] geheimnis = Totp.ausBase32(rs.getString("totp_geheimnis"));

                    if (!rs.getBoolean("totp_bestaetigt")) {
                        if (!passtPasswort) { fehlversuch(id); return abgelehnt(); }
                        return new Anmeldung.TotpEinrichten(id,
                                Totp.einrichtungsUri(rs.getString("benutzername"), geheimnis, "PIN-Roboter"),
                                Totp.alsBase32(geheimnis));
                    }

                    long fenster = Totp.fensterVon(geheimnis, totpCode, Instant.now());
                    boolean passtTotp = fenster >= 0;

                    // Ein verbrauchter Code ist nicht erneut verwendbar (FA-BEN-020).
                    // Sonst nützt ein abgefangener Code bis zu 90 Sekunden lang.
                    long letztes = rs.getLong("letztes_totp_fenster");
                    if (!rs.wasNull() && passtTotp && fenster <= letztes) {
                        fehlversuch(id);
                        return new Anmeldung.Abgelehnt(
                                "Dieses Einmalkennwort wurde bereits verwendet. "
                              + "Bitte den nächsten Code aus Ihrer App abwarten.");
                    }

                    if (!passtPasswort || !passtTotp) { fehlversuch(id); return abgelehnt(); }

                    erfolgVermerken(id, fenster);
                    return new Anmeldung.Erfolg(sitzungAnlegen(id), new Benutzer(
                            id, rs.getString("benutzername"), rs.getString("anzeigename"),
                            Rolle.valueOf(rs.getString("rolle")), true, true));
                }
            }
        } catch (Exception e) {
            return new Anmeldung.Abgelehnt("Die Anmeldung ist derzeit nicht möglich.");
        } finally {
            if (passwort != null) Arrays.fill(passwort, '\0');
        }
    }

    private Anmeldung abgelehnt() {
        return new Anmeldung.Abgelehnt("Benutzername, Passwort oder Einmalkennwort stimmen nicht.");
    }

    private void fehlversuch(long id) {
        try (PreparedStatement p = db.verbindung().prepareStatement("""
                UPDATE benutzer SET fehlversuche = fehlversuche + 1,
                  gesperrt_bis = CASE WHEN fehlversuche + 1 >= ? THEN now() + ? * interval '1 minute'
                                      ELSE gesperrt_bis END
                WHERE id = ?""")) {
            p.setInt(1, MAX_FEHLVERSUCHE);
            p.setLong(2, SPERRDAUER.toMinutes());
            p.setLong(3, id);
            p.executeUpdate();
        } catch (SQLException ignored) { }
    }

    private void erfolgVermerken(long id, long fenster) {
        try (PreparedStatement p = db.verbindung().prepareStatement("""
                UPDATE benutzer SET fehlversuche = 0, gesperrt_bis = NULL,
                                    letztes_totp_fenster = ? WHERE id = ?""")) {
            p.setLong(1, fenster);
            p.setLong(2, id);
            p.executeUpdate();
        } catch (SQLException ignored) { }
    }

    // ------------------------------------------------------------- Sitzungen

    private String sitzungAnlegen(long benutzerId) throws SQLException {
        byte[] roh = new byte[32];
        zufall.nextBytes(roh);
        String kennung = Base64.getUrlEncoder().withoutPadding().encodeToString(roh);
        try (PreparedStatement p = db.verbindung().prepareStatement(
                "INSERT INTO sitzung (kennung, benutzer_id) VALUES (?,?)")) {
            p.setString(1, kennung);
            p.setLong(2, benutzerId);
            p.executeUpdate();
        }
        return kennung;
    }

    /** NFA-SIC-050: Sitzungen laufen nach Untätigkeit ab. */
    public Optional<Benutzer> sitzungPruefen(String kennung) {
        if (kennung == null || kennung.isBlank()) return Optional.empty();
        String sql = """
                SELECT b.id, b.benutzername, b.anzeigename, b.rolle, b.aktiv, b.totp_bestaetigt,
                       s.letzte_tat
                FROM sitzung s JOIN benutzer b ON b.id = s.benutzer_id
                WHERE s.kennung = ?""";
        try (PreparedStatement p = db.verbindung().prepareStatement(sql)) {
            p.setString(1, kennung);
            try (ResultSet rs = p.executeQuery()) {
                if (!rs.next() || !rs.getBoolean("aktiv")) return Optional.empty();
                Instant letzte = rs.getTimestamp("letzte_tat").toInstant();
                if (Duration.between(letzte, Instant.now()).compareTo(SITZUNG_UNTAETIG) > 0) {
                    abmelden(kennung);
                    return Optional.empty();
                }
                beruehren(kennung);
                return Optional.of(new Benutzer(rs.getLong("id"), rs.getString("benutzername"),
                        rs.getString("anzeigename"), Rolle.valueOf(rs.getString("rolle")),
                        true, rs.getBoolean("totp_bestaetigt")));
            }
        } catch (SQLException e) { return Optional.empty(); }
    }

    private void beruehren(String kennung) {
        try (PreparedStatement p = db.verbindung().prepareStatement(
                "UPDATE sitzung SET letzte_tat = now() WHERE kennung = ?")) {
            p.setString(1, kennung);
            p.executeUpdate();
        } catch (SQLException ignored) { }
    }

    public void abmelden(String kennung) {
        try (PreparedStatement p = db.verbindung().prepareStatement(
                "DELETE FROM sitzung WHERE kennung = ?")) {
            p.setString(1, kennung);
            p.executeUpdate();
        } catch (SQLException ignored) { }
    }

    // ------------------------------------------------------------- Verwaltung

    public List<Benutzer> alle() {
        List<Benutzer> liste = new ArrayList<>();
        try (Statement s = db.verbindung().createStatement();
             ResultSet rs = s.executeQuery("""
                     SELECT id, benutzername, anzeigename, rolle, aktiv, totp_bestaetigt
                     FROM benutzer ORDER BY lower(benutzername)""")) {
            while (rs.next()) {
                liste.add(new Benutzer(rs.getLong("id"), rs.getString("benutzername"),
                        rs.getString("anzeigename"), Rolle.valueOf(rs.getString("rolle")),
                        rs.getBoolean("aktiv"), rs.getBoolean("totp_bestaetigt")));
            }
        } catch (SQLException ignored) { }
        return liste;
    }

    /**
     * FA-BEN-010: Es muss immer mindestens einen handlungsfähigen Administrator
     * geben. Der letzte lässt sich weder löschen noch herabstufen — sonst sperrt
     * sich eine Praxis versehentlich selbst aus.
     */
    public boolean istLetzterAdministrator(long benutzerId) {
        try (PreparedStatement p = db.verbindung().prepareStatement("""
                SELECT count(*) FROM benutzer
                WHERE rolle = 'ADMINISTRATOR' AND aktiv AND totp_bestaetigt AND id <> ?""")) {
            p.setLong(1, benutzerId);
            try (ResultSet rs = p.executeQuery()) {
                return rs.next() && rs.getLong(1) == 0;
            }
        } catch (SQLException e) { return true; }   // im Zweifel schützen
    }
}
