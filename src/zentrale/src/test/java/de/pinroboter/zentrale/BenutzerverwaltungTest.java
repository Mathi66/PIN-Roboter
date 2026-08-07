package de.pinroboter.zentrale;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integrationstest der Benutzerverwaltung gegen eine echte PostgreSQL-Datenbank.
 *
 * <p>Läuft nur, wenn {@code TEST_DB_URL} gesetzt ist — sonst wird er
 * übersprungen statt rot zu werden. Ein Test, der ohne Infrastruktur
 * fehlschlägt, sagt nichts über das Produkt aus und entwertet die Suite.
 *
 * <p>Aufruf über {@code pruefungen/systemnachweise.sh}, das die Datenbank
 * bereitstellt.
 */
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class BenutzerverwaltungTest {

    private static Datenbank db;
    private static Benutzerverwaltung bv;

    @BeforeAll
    static void verbinden() throws Exception {
        db = new Datenbank(System.getenv("TEST_DB_URL"),
                System.getenv().getOrDefault("TEST_DB_USER", "pinroboter"),
                System.getenv().getOrDefault("TEST_DB_PASSWORD", "test"));
        bv = new Benutzerverwaltung(db);
    }

    @AfterAll
    static void trennen() { if (db != null) db.close(); }

    private String neuerName() { return "pruef-" + UUID.randomUUID().toString().substring(0, 8); }

    /** Legt einen einsatzbereiten Benutzer an (angelegt + TOTP bestätigt). */
    private record Konto(long id, String name, char[] passwort, byte[] geheimnis) {}

    private Konto kontoAnlegen(Benutzerverwaltung.Rolle rolle) {
        String name = neuerName();
        char[] pw = "RichtigPferdBatterieKlammer".toCharArray();
        var ergebnis = bv.anlegen(name, "Prüfkonto", pw.clone(), rolle);
        var einr = assertInstanceOf(Benutzerverwaltung.Anmeldung.TotpEinrichten.class, ergebnis);
        byte[] g = de.pinroboter.gemeinsam.Totp.ausBase32(einr.geheimnis());
        assertTrue(bv.totpBestaetigen(einr.benutzerId(),
                de.pinroboter.gemeinsam.Totp.code(g, Instant.now())));
        return new Konto(einr.benutzerId(), name, pw, g);
    }

    @Test
    @DisplayName("FA-BEN-020: Anmeldung gelingt nur mit Passwort UND gültigem Einmalkennwort")
    void anmeldungBrauchtBeideFaktoren() {
        Konto k = kontoAnlegen(Benutzerverwaltung.Rolle.ADMINISTRATOR);
        var jetzt = Instant.now();

        assertInstanceOf(Benutzerverwaltung.Anmeldung.Erfolg.class,
                bv.anmelden(k.name(), k.passwort().clone(),
                        de.pinroboter.gemeinsam.Totp.code(k.geheimnis(), jetzt)),
                "richtiges Passwort und richtiger Code");

        assertInstanceOf(Benutzerverwaltung.Anmeldung.Abgelehnt.class,
                bv.anmelden(k.name(), "FalschesPasswort123".toCharArray(),
                        de.pinroboter.gemeinsam.Totp.code(k.geheimnis(), jetzt)),
                "falsches Passwort trotz richtigem Code");

        assertInstanceOf(Benutzerverwaltung.Anmeldung.Abgelehnt.class,
                bv.anmelden(k.name(), k.passwort().clone(), "000000"),
                "richtiges Passwort, falscher Code");
    }

    @Test
    @DisplayName("Ein verbrauchtes Einmalkennwort gilt kein zweites Mal")
    void codeIstEinmalig() {
        Konto k = kontoAnlegen(Benutzerverwaltung.Rolle.BENUTZER);
        String code = de.pinroboter.gemeinsam.Totp.code(k.geheimnis(), Instant.now());

        assertInstanceOf(Benutzerverwaltung.Anmeldung.Erfolg.class,
                bv.anmelden(k.name(), k.passwort().clone(), code));

        var zweite = bv.anmelden(k.name(), k.passwort().clone(), code);
        var abgelehnt = assertInstanceOf(Benutzerverwaltung.Anmeldung.Abgelehnt.class, zweite);
        assertTrue(abgelehnt.meldung().contains("bereits verwendet"),
                "Die Meldung muss den Grund nennen: " + abgelehnt.meldung());
    }

    @Test
    @DisplayName("Unbekannter Benutzer und falsches Passwort ergeben dieselbe Meldung")
    void keinRueckschlussAufKontenBestand() {
        Konto k = kontoAnlegen(Benutzerverwaltung.Rolle.BENUTZER);
        var unbekannt = bv.anmelden("gibt-es-nicht-" + UUID.randomUUID(),
                "IrgendeinPasswort1".toCharArray(), "123456");
        var falsch = bv.anmelden(k.name(), "FalschesPasswort123".toCharArray(), "123456");

        assertEquals(
                ((Benutzerverwaltung.Anmeldung.Abgelehnt) unbekannt).meldung(),
                ((Benutzerverwaltung.Anmeldung.Abgelehnt) falsch).meldung(),
                "Unterschiedliche Meldungen verraten, welche Konten existieren");
    }

    @Test
    @DisplayName("Nach fünf Fehlversuchen wird das Konto zeitweise gesperrt")
    void kontosperreNachFehlversuchen() {
        Konto k = kontoAnlegen(Benutzerverwaltung.Rolle.BENUTZER);
        for (int i = 0; i < 5; i++) {
            bv.anmelden(k.name(), "FalschesPasswort123".toCharArray(), "000000");
        }
        var jetzt = bv.anmelden(k.name(), k.passwort().clone(),
                de.pinroboter.gemeinsam.Totp.code(k.geheimnis(), Instant.now()));
        var gesperrt = assertInstanceOf(Benutzerverwaltung.Anmeldung.Gesperrt.class, jetzt,
                "auch mit richtigen Daten muss die Sperre greifen");
        assertTrue(gesperrt.meldung().contains("Minuten"),
                "Die Meldung muss sagen, wie lange: " + gesperrt.meldung());
    }

    @Test
    @DisplayName("Vor der TOTP-Bestätigung ist keine Anmeldung möglich")
    void ohneBestaetigungKeineAnmeldung() {
        String name = neuerName();
        char[] pw = "RichtigPferdBatterieKlammer".toCharArray();
        var e = bv.anlegen(name, "Ohne Bestätigung", pw.clone(),
                Benutzerverwaltung.Rolle.BENUTZER);
        var einr = assertInstanceOf(Benutzerverwaltung.Anmeldung.TotpEinrichten.class, e);
        byte[] g = de.pinroboter.gemeinsam.Totp.ausBase32(einr.geheimnis());

        var versuch = bv.anmelden(name, pw.clone(),
                de.pinroboter.gemeinsam.Totp.code(g, Instant.now()));
        assertInstanceOf(Benutzerverwaltung.Anmeldung.TotpEinrichten.class, versuch,
                "Statt Zugang muss die Einrichtung verlangt werden");
    }

    @Test
    @DisplayName("Ein falscher Code bestätigt die TOTP-Einrichtung nicht")
    void falscherCodeBestaetigtNicht() {
        var e = bv.anlegen(neuerName(), "Test", "RichtigPferdBatterieKlammer".toCharArray(),
                Benutzerverwaltung.Rolle.BENUTZER);
        var einr = assertInstanceOf(Benutzerverwaltung.Anmeldung.TotpEinrichten.class, e);
        assertFalse(bv.totpBestaetigen(einr.benutzerId(), "000000"));
    }

    @Test
    @DisplayName("Sitzungen sind gültig, nach Abmeldung nicht mehr")
    void sitzungslebenszyklus() {
        Konto k = kontoAnlegen(Benutzerverwaltung.Rolle.ADMINISTRATOR);
        var erfolg = (Benutzerverwaltung.Anmeldung.Erfolg) bv.anmelden(k.name(),
                k.passwort().clone(), de.pinroboter.gemeinsam.Totp.code(k.geheimnis(), Instant.now()));

        assertTrue(bv.sitzungPruefen(erfolg.sitzung()).isPresent());
        bv.abmelden(erfolg.sitzung());
        assertTrue(bv.sitzungPruefen(erfolg.sitzung()).isEmpty(),
                "Nach dem Abmelden muss die Sitzung serverseitig ungültig sein");
        assertTrue(bv.sitzungPruefen("erfundene-kennung").isEmpty());
        assertTrue(bv.sitzungPruefen(null).isEmpty());
    }

    @Test
    @DisplayName("Zu schwache Passwörter werden mit Begründung abgelehnt")
    void schwachesPasswortAbgelehnt() {
        var e = bv.anlegen(neuerName(), "Test", "kurz".toCharArray(),
                Benutzerverwaltung.Rolle.BENUTZER);
        var ab = assertInstanceOf(Benutzerverwaltung.Anmeldung.Abgelehnt.class, e);
        assertTrue(ab.meldung().contains("12 Zeichen"));
    }

    @Test
    @DisplayName("Doppelte Benutzernamen werden verhindert")
    void keineDoppeltenNamen() {
        String name = neuerName();
        bv.anlegen(name, "Erster", "RichtigPferdBatterieKlammer".toCharArray(),
                Benutzerverwaltung.Rolle.BENUTZER);
        var zweiter = bv.anlegen(name, "Zweiter", "RichtigPferdBatterieKlammer".toCharArray(),
                Benutzerverwaltung.Rolle.BENUTZER);
        var ab = assertInstanceOf(Benutzerverwaltung.Anmeldung.Abgelehnt.class, zweiter);
        assertTrue(ab.meldung().contains("bereits"));
    }

    @Test
    @DisplayName("FA-BEN-010: Der letzte Administrator ist geschützt")
    void letzterAdministratorGeschuetzt() {
        Konto a = kontoAnlegen(Benutzerverwaltung.Rolle.ADMINISTRATOR);
        Konto b = kontoAnlegen(Benutzerverwaltung.Rolle.ADMINISTRATOR);
        assertFalse(bv.istLetzterAdministrator(a.id()),
                "Bei zwei Administratoren ist keiner der letzte");
        assertFalse(bv.istLetzterAdministrator(b.id()));
        assertTrue(bv.alle().size() >= 2);
    }
}
