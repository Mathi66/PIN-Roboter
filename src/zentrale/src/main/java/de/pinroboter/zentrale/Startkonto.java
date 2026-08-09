package de.pinroboter.zentrale;

import de.pinroboter.gemeinsam.Ereignisart;
import de.pinroboter.gemeinsam.Totp;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;

/**
 * Ein Administratorkonto aus der Konfiguration (FA-BEN-035).
 *
 * <h2>Warum es das gibt</h2>
 *
 * Ohne diesen Weg hängt der Zugang zur Zentrale an einem Konto, das <b>einmalig
 * beim ersten Start</b> entsteht. Geht das Datenvolumen verloren — Neuaufbau,
 * versehentliches {@code docker compose down -v}, misslungene Wiederherstellung
 * —, entsteht bei der nächsten Ersteinrichtung ein <b>neues TOTP-Geheimnis</b>.
 * Der Eintrag in der Authenticator-App des Betreibers zeigt dann ins Leere, und
 * niemand kommt mehr hinein, bis jemand mit Datenbankzugriff hilft.
 *
 * <p>Genau das ist am 09.08.2026 im Projekt mehrfach passiert. In einer Praxis
 * ohne IT-Personal wäre es ein Ausfall mit Anruf beim Hersteller.
 *
 * <h2>Was es tut</h2>
 *
 * Beim Start wird das Konto angelegt, <b>falls es fehlt</b>. Ist das
 * TOTP-Geheimnis mitgegeben, gilt es sofort — der Betreiber behält seinen
 * App-Eintrag über jeden Neuaufbau hinweg.
 *
 * <h2>Was es ausdrücklich NICHT tut</h2>
 *
 * <b>Ein vorhandenes Konto wird nicht angefasst.</b> Kein Zurücksetzen von
 * Passwort, Rolle oder zweitem Faktor bei jedem Neustart. Sonst könnte ein
 * Betreiber sein Passwort ändern und es beim nächsten Neustart stillschweigend
 * zurückgesetzt bekommen — schlimmer noch: Ein Angreifer mit Schreibzugriff auf
 * die Compose-Datei bekäme bei jedem Neustart wieder Zutritt, auch nachdem man
 * ihn ausgesperrt hat.
 *
 * <h2>Sicherheitsvorbehalt — vom Betreiber zu tragen</h2>
 *
 * Wer die Konfiguration lesen kann, kennt <b>beide Faktoren</b>. Der zweite
 * Faktor ist dann keiner mehr. Das ist ein bewusster Tausch: Wiedereinstieg
 * nach Datenverlust gegen die Trennung der Faktoren. Deshalb gilt:
 *
 * <ul>
 *   <li>Das Passwort wird bevorzugt aus einer <b>Datei</b> gelesen
 *       ({@code START_ADMIN_PASSWORT_DATEI}) — ein Docker-Secret taucht nicht in
 *       {@code docker inspect} und nicht in der Prozessliste auf.</li>
 *   <li>Die Stärkeprüfung gilt auch hier. Ein schwaches Passwort in der
 *       Konfiguration wäre die bequemste Hintertür überhaupt.</li>
 *   <li>Beim Anlegen erscheint eine <b>deutliche Warnung im Betriebsprotokoll</b>
 *       und ein Eintrag im Fachprotokoll — niemals mit Passwort oder Geheimnis.</li>
 *   <li>Nach der Einrichtung sollte der Betreiber die Variablen entfernen. Der
 *       Hinweis steht in {@code docs/04-betrieb.md}.</li>
 * </ul>
 */
final class Startkonto {

    private Startkonto() { }

    /**
     * Legt das Konto an, falls konfiguriert und noch nicht vorhanden.
     *
     * <p>Schlägt etwas fehl, wird gewarnt, <b>nicht abgebrochen</b>. Ein
     * fehlerhaft konfiguriertes Startkonto darf die Überwachung nicht anhalten
     * — das ist die Kernfunktion, und sie läuft auch ohne Oberfläche weiter.
     *
     * @return kurze Beschreibung dessen, was geschehen ist (für Protokoll und Test)
     */
    static String anlegenFallsNoetig(Datenbank db, Benutzerverwaltung benutzer,
                                     Fachprotokoll protokoll) {
        String name = env("START_ADMIN_BENUTZER");
        if (name == null) return "nicht konfiguriert";

        char[] passwort = passwortLesen();
        if (passwort == null) {
            warnen("START_ADMIN_BENUTZER ist gesetzt, aber weder START_ADMIN_PASSWORT "
                 + "noch START_ADMIN_PASSWORT_DATEI. Das Startkonto wurde nicht angelegt.");
            return "Passwort fehlt";
        }

        try {
            if (vorhanden(db, name)) {
                // Bewusst still: Bei jedem Neustart eine Warnung zu schreiben,
                // die nichts bedeutet, stumpft gegen echte Warnungen ab.
                return "vorhanden";
            }

            String totp = env("START_ADMIN_TOTP");
            var ergebnis = benutzer.anlegen(name, env("START_ADMIN_NAME") == null
                    ? name : env("START_ADMIN_NAME"),
                    passwort.clone(), Benutzerverwaltung.Rolle.ADMINISTRATOR);

            if (ergebnis instanceof Benutzerverwaltung.Anmeldung.Abgelehnt a) {
                warnen("Das Startkonto konnte nicht angelegt werden: " + a.meldung());
                return "abgelehnt: " + a.meldung();
            }
            long id = ((Benutzerverwaltung.Anmeldung.TotpEinrichten) ergebnis).benutzerId();

            String vermerk;
            if (totp == null) {
                // Ohne vorgegebenes Geheimnis muss der zweite Faktor bei der
                // ersten Anmeldung eingerichtet werden. Das Konto ist damit
                // zwar wiederhergestellt, aber der App-Eintrag von vorher
                // passt nicht mehr — genau das, was vermieden werden sollte.
                warnen("Startkonto „" + name + "\" angelegt, aber ohne START_ADMIN_TOTP. "
                     + "Der zweite Faktor muss bei der ersten Anmeldung neu eingerichtet "
                     + "werden; ein vorhandener Eintrag in der Authenticator-App gilt nicht mehr.");
                vermerk = "ohne vorgegebenen zweiten Faktor";
            } else {
                totpSetzen(db, id, totp.replaceAll("\\s", "").toUpperCase());
                vermerk = "mit vorgegebenem zweiten Faktor";
            }

            warnen("Startkonto „" + name + "\" aus der Konfiguration angelegt (" + vermerk + "). "
                 + "Solange START_ADMIN_* gesetzt ist, entsteht dieses Konto nach jedem "
                 + "Datenverlust neu. Nach der Einrichtung sollten die Variablen entfernt werden.");

            // Im Fachprotokoll, weil es eine sicherheitsrelevante Handlung ist:
            // Ein Administrator ist entstanden, ohne dass ihn jemand angelegt hat.
            // Niemals mit Passwort oder Geheimnis - das erzwingt Fachprotokoll
            // ohnehin zentral, hier steht es zur Sicherheit noch einmal.
            protokoll.anfuegen("Praxis", null, Ereignisart.BENUTZER_ANGELEGT, true,
                    "Konfiguration", null,
                    "Startkonto „" + name + "“ aus der Konfiguration angelegt (" + vermerk + ")",
                    "Prüfen Sie, ob dieses Konto erwartet war. Wenn nicht, deaktivieren Sie es "
                    + "und entfernen Sie die Variablen START_ADMIN_* aus der Konfiguration.");
            return "angelegt " + vermerk;
        } catch (Exception e) {
            warnen("Das Startkonto konnte nicht angelegt werden: " + e.getMessage());
            return "Fehler";
        } finally {
            Arrays.fill(passwort, '\0');
        }
    }

    // ------------------------------------------------------------------ intern

    private static boolean vorhanden(Datenbank db, String name) throws SQLException {
        try (PreparedStatement p = db.verbindung().prepareStatement(
                "SELECT 1 FROM benutzer WHERE benutzername = ?")) {
            p.setString(1, name.trim());
            try (ResultSet rs = p.executeQuery()) { return rs.next(); }
        }
    }

    /**
     * Setzt das vorgegebene TOTP-Geheimnis und markiert es als bestätigt.
     *
     * <p>Die Bestätigung wird hier übersprungen — sie ist der Beweis, dass die
     * App das Geheimnis wirklich hat. Bei einem vorgegebenen Geheimnis hat der
     * Betreiber diesen Beweis bereits erbracht, als er es in die Konfiguration
     * geschrieben hat. Ohne diesen Schritt liefe er in die Einrichtungsmaske
     * und bekäme ein anderes Geheimnis — womit der Zweck verfehlt wäre.
     */
    private static void totpSetzen(Datenbank db, long id, String base32) throws SQLException {
        // Prüfen, dass es überhaupt ein lesbares Geheimnis ist. Ein
        // Tippfehler in der Konfiguration soll hier auffallen, nicht bei der
        // ersten Anmeldung um sechs Uhr morgens.
        byte[] probe = Totp.ausBase32(base32);
        if (probe == null || probe.length < 10) {
            throw new IllegalArgumentException(
                    "START_ADMIN_TOTP ist kein gültiges Base32-Geheimnis (mindestens 16 Zeichen).");
        }
        try (PreparedStatement p = db.verbindung().prepareStatement(
                "UPDATE benutzer SET totp_geheimnis = ?, totp_bestaetigt = TRUE WHERE id = ?")) {
            p.setString(1, base32);
            p.setLong(2, id);
            p.executeUpdate();
        }
    }

    /**
     * Das Passwort, bevorzugt aus einer Datei.
     *
     * <p>Ein Docker-Secret liegt als Datei unter {@code /run/secrets/…} und
     * erscheint weder in {@code docker inspect} noch in der Prozessliste. Die
     * Variante als Umgebungsvariable bleibt für einfache Installationen
     * erhalten, ist aber die schlechtere.
     */
    private static char[] passwortLesen() {
        String datei = env("START_ADMIN_PASSWORT_DATEI");
        if (datei != null) {
            try {
                String inhalt = Files.readString(Path.of(datei), StandardCharsets.UTF_8);
                // Ein abschliessender Zeilenumbruch ist beim Anlegen der Datei
                // die Regel, nicht die Ausnahme - und wuerde stillschweigend
                // zum falschen Passwort fuehren.
                String bereinigt = inhalt.strip();
                if (!bereinigt.isEmpty()) return bereinigt.toCharArray();
                warnen("Die Datei aus START_ADMIN_PASSWORT_DATEI ist leer: " + datei);
            } catch (Exception e) {
                warnen("Die Datei aus START_ADMIN_PASSWORT_DATEI ist nicht lesbar: " + e.getMessage());
            }
        }
        String direkt = env("START_ADMIN_PASSWORT");
        return direkt == null ? null : direkt.toCharArray();
    }

    private static String env(String name) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static void warnen(String text) {
        System.err.println("[Startkonto] " + text);
    }
}
