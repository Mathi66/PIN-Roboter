package de.pinroboter.zentrale;

import de.pinroboter.gemeinsam.Geheimnisspeicher;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Benachrichtigung per E-Mail (FA-ALA-020).
 *
 * <p>Geprüft wird vor allem das <b>Sammelfenster</b>. Der Versand selbst
 * braucht einen Mailserver und wird im Systemnachweis gegen einen
 * Auffangserver geprüft; hier geht es um die Frage, ob aus 400 Alarmen eine
 * Mail wird oder 400.
 */
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class BenachrichtigungTest {

    private static Datenbank db;
    private Benachrichtigung bn;
    private long verbund, praxisA, praxisB;

    @BeforeEach
    void aufbauen() throws Exception {
        if (db == null) {
            db = new Datenbank(System.getenv("TEST_DB_URL"),
                    System.getenv().getOrDefault("TEST_DB_USER", "pinroboter"),
                    System.getenv().getOrDefault("TEST_DB_PASSWORD", "test"));
        }
        bn = new Benachrichtigung(db,
                Geheimnisspeicher.ausBase64(Geheimnisspeicher.neuerSchluesselBase64()));
        aufraeumen();
        verbund = org("BN Verbund", "VERBUND", null);
        praxisA = org("BN Praxis A", "EINRICHTUNG", verbund);
        praxisB = org("BN Praxis B", "EINRICHTUNG", verbund);
    }

    @AfterAll
    static void abraeumen() throws Exception { if (db != null) aufraeumen(); }

    // ------------------------------------------------------- Das Sammelfenster

    @Test
    @DisplayName("400 Alarme in einem Fenster ergeben EINE Mail, nicht 400")
    void keineMailflut() throws Exception {
        bn.regelSpeichern(praxisA, "leitung@praxis.example", "WARNUNG", "300", true, alles());

        for (int i = 1; i <= 400; i++) {
            assertTrue(bn.einreihen(praxisA, "KRITISCH", "KT-" + i,
                    "Kartenterminal nicht erreichbar", "Netzwerk prüfen"));
        }
        assertEquals(400, wartend(praxisA), "alle 400 sollten warten");

        // Fenster noch nicht abgelaufen: nichts geht hinaus.
        assertEquals(0, bn.versendeFaellige().mails());
        assertEquals(400, wartend(praxisA));

        // Fenster abgelaufen — ohne Mailserver scheitert der Versand, aber die
        // Zahl der VERSUCHE ist entscheidend: einer, nicht 400.
        alterMachen(praxisA, 600);
        var d = bn.versendeFaellige();
        assertEquals(0, d.mails());
        assertEquals(1, d.fehler().size(),
                "Es darf genau EIN Versandversuch je Einrichtung entstehen, nicht 400");
    }

    @Test
    @DisplayName("Das Fenster zählt ab dem ÄLTESTEN Alarm, nicht ab dem jüngsten")
    void fensterAbAeltestem() throws Exception {
        bn.regelSpeichern(praxisA, "leitung@praxis.example", "WARNUNG", "300", true, alles());
        bn.einreihen(praxisA, "KRITISCH", "KT-1", "Erster Alarm", null);
        alterMachen(praxisA, 600);                       // dieser wartet lange genug
        bn.einreihen(praxisA, "KRITISCH", "KT-2", "Zweiter Alarm", null);   // frisch

        // Zählte das Fenster ab dem jüngsten, schöbe jeder neue Alarm den
        // Versand weiter hinaus — bei einem anhaltenden Ausfall ginge nie eine
        // Mail hinaus. Genau das darf nicht passieren.
        var d = bn.versendeFaellige();
        assertEquals(1, d.fehler().size(), "Die Einrichtung muss fällig sein");
    }

    @Test
    @DisplayName("Ein Fenster von 0 Sekunden meldet sofort")
    void sofort() throws Exception {
        bn.regelSpeichern(praxisA, "leitung@praxis.example", "WARNUNG", "0", true, alles());
        bn.einreihen(praxisA, "KRITISCH", "KT-1", "Sofort melden", null);
        assertEquals(1, bn.versendeFaellige().fehler().size());
    }

    @Test
    @DisplayName("Ein gescheiterter Versand verwirft die Alarme nicht")
    void nichtsVerlieren() throws Exception {
        bn.regelSpeichern(praxisA, "leitung@praxis.example", "WARNUNG", "0", true, alles());
        bn.einreihen(praxisA, "KRITISCH", "KT-1", "Karte gesperrt", "PUK nötig");
        bn.versendeFaellige();
        // Eine nicht zugestellte Meldung über eine gesperrte Karte ist genau
        // die, die man braucht — sie bleibt für den nächsten Versuch liegen.
        assertEquals(1, wartend(praxisA));
    }

    // -------------------------------------------------------------- Schwelle

    @Test
    @DisplayName("Unterhalb der Schwelle wird nicht eingereiht")
    void schwelleGreift() throws Exception {
        bn.regelSpeichern(praxisA, "leitung@praxis.example", "KRITISCH", "60", true, alles());
        assertFalse(bn.einreihen(praxisA, "WARNUNG", "KT-1", "nur eine Warnung", null));
        assertFalse(bn.einreihen(praxisA, "HINWEIS", "KT-2", "nur ein Hinweis", null));
        assertTrue(bn.einreihen(praxisA, "KRITISCH", "KT-3", "kritisch", null));
        assertEquals(1, wartend(praxisA));
    }

    @Test
    @DisplayName("Eine abgeschaltete Regel reiht nichts ein")
    void abgeschaltet() throws Exception {
        bn.regelSpeichern(praxisA, "leitung@praxis.example", "HINWEIS", "60", false, alles());
        assertFalse(bn.einreihen(praxisA, "KRITISCH", "KT-1", "kritisch", null));
        assertEquals(0, wartend(praxisA));
    }

    @Test
    @DisplayName("Ohne Regel wird nichts eingereiht — kein stiller Versand an niemanden")
    void ohneRegel() {
        assertFalse(bn.einreihen(praxisB, "KRITISCH", "KT-1", "kritisch", null));
        assertEquals(0, wartend(praxisB));
    }

    // -------------------------------------------------------------- Vererbung

    @Test
    @DisplayName("Die Regel des Verbunds gilt auch für die Praxis darunter")
    void regelWirdVererbt() throws Exception {
        bn.regelSpeichern(verbund, "dienstleister@example.com", "WARNUNG", "300", true, alles());
        // Ohne Vererbung müsste jede der 1.000 Einrichtungen einzeln gepflegt werden.
        assertTrue(bn.einreihen(praxisA, "KRITISCH", "KT-1", "kritisch", null));
        assertEquals(verbund, bn.regelFuer(praxisA).orElseThrow().organisationId());
    }

    @Test
    @DisplayName("Eine eigene Regel schlägt die geerbte")
    void eigeneRegelGewinnt() throws Exception {
        bn.regelSpeichern(verbund, "dienstleister@example.com", "WARNUNG", "300", true, alles());
        bn.regelSpeichern(praxisA, "praxis@example.com", "KRITISCH", "60", true, alles());
        assertEquals(praxisA, bn.regelFuer(praxisA).orElseThrow().organisationId());
        assertEquals(verbund, bn.regelFuer(praxisB).orElseThrow().organisationId());
    }

    // ------------------------------------------------------- Mandantentrennung

    @Test
    @DisplayName("Eine Regel außerhalb des Geltungsbereichs lässt sich nicht setzen")
    void fremdeRegel() {
        var fehler = bn.regelSpeichern(praxisA, "fremd@example.com", "WARNUNG", "60", true,
                Geltungsbereich.keiner());
        assertNotNull(fehler);
        assertTrue(bn.regelFuer(praxisA).isEmpty());
    }

    // --------------------------------------------------------------- Eingaben

    @Test
    @DisplayName("Eine unsinnige Adresse wird abgewiesen und sagt welche")
    void adressePruefen() {
        var f = bn.regelSpeichern(praxisA, "kein-at-zeichen", "WARNUNG", "60", true, alles());
        assertNotNull(f);
        assertTrue(f.contains("kein-at-zeichen"), f);
    }

    @Test
    @DisplayName("Ein Sammelfenster über 24 Stunden wird abgewiesen")
    void fensterGrenze() {
        assertNotNull(bn.regelSpeichern(praxisA, "a@b.de", "WARNUNG", "90000", true, alles()));
        assertNotNull(bn.regelSpeichern(praxisA, "a@b.de", "WARNUNG", "-1", true, alles()));
        assertNull(bn.regelSpeichern(praxisA, "a@b.de", "WARNUNG", "86400", true, alles()));
    }

    @Test
    @DisplayName("Mehrere Empfänger lassen sich in einem Feld eingeben")
    void mehrereEmpfaenger() {
        assertNull(bn.regelSpeichern(praxisA, "a@b.de, c@d.de; e@f.de", "WARNUNG", "60",
                true, alles()));
        assertEquals(3, Benachrichtigung.adressenZerlegen(
                bn.regelFuer(praxisA).orElseThrow().empfaenger()).size());
    }

    // ----------------------------------------------------------- Mailinhalt

    @Test
    @DisplayName("Gleichartige Meldungen werden zusammengefasst, nicht 400-mal wiederholt")
    void textFasstZusammen() {
        var w = new java.util.ArrayList<Benachrichtigung.Wartend>();
        for (int i = 1; i <= 400; i++) {
            w.add(new Benachrichtigung.Wartend("KRITISCH", "KT-" + i,
                    "Kartenterminal nicht erreichbar", "Netzwerk prüfen"));
        }
        String text = Benachrichtigung.text(w,
                new Benachrichtigung.Regel(1, "Praxis Nord", "a@b.de", "WARNUNG", 300, true));

        assertEquals(1, text.split("Kartenterminal nicht erreichbar", -1).length - 1,
                "Der Meldungstext darf genau einmal vorkommen, nicht 400-mal");
        assertTrue(text.contains("Betroffen: 400 Geräte"), text);
        assertTrue(text.contains("…"), "Bei 400 Geräten dürfen nicht alle Namen aufgezählt werden");
    }

    @Test
    @DisplayName("Der Betreff nennt Zahl und höchsten Schweregrad")
    void betreff() {
        var eine = List.of(new Benachrichtigung.Wartend("WARNUNG", "KT-1", "x", null));
        assertEquals("PIN-Roboter Praxis Nord: 1 Meldung (Warnung)",
                Benachrichtigung.betreff(eine, "Praxis Nord"));

        var gemischt = List.of(
                new Benachrichtigung.Wartend("WARNUNG", "KT-1", "x", null),
                new Benachrichtigung.Wartend("KRITISCH", "KT-2", "y", null));
        assertEquals("PIN-Roboter Praxis Nord: 2 Meldungen (kritisch)",
                Benachrichtigung.betreff(gemischt, "Praxis Nord"));
    }

    @Test
    @DisplayName("Der Mailtext sagt ausdrücklich, dass er keine Geheimnisse enthält")
    void textNenntZusage() {
        var w = List.of(new Benachrichtigung.Wartend("KRITISCH", "KT-1", "Karte gesperrt", "PUK"));
        String text = Benachrichtigung.text(w,
                new Benachrichtigung.Regel(1, "Praxis", "a@b.de", "WARNUNG", 300, true));
        assertTrue(text.contains("keine Zugangsdaten"), text);
        assertTrue(text.contains("personenbezogenen Daten"), text);
    }

    @Test
    @DisplayName("Umlaute im Betreff werden nach RFC 2047 kodiert")
    void betreffKodierung() {
        assertEquals("PIN-Roboter: 2 Meldungen",
                Mailversand.betreffKodiert("PIN-Roboter: 2 Meldungen"));
        String k = Mailversand.betreffKodiert("Gerät nicht erreichbar");
        assertTrue(k.startsWith("=?UTF-8?B?") && k.endsWith("?="), k);
        assertEquals("Gerät nicht erreichbar", new String(
                java.util.Base64.getDecoder().decode(k.substring(10, k.length() - 2)),
                java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("Eine Zeile mit führendem Punkt beendet die Nachricht nicht vorzeitig")
    void punktStopfen() {
        String m = Mailversand.nachricht("a@b.de", List.of("c@d.de"), "Test",
                "Zeile eins\n. Punkt am Anfang\nZeile drei");
        assertTrue(m.contains("\r\n.. Punkt am Anfang\r\n"),
                "Der Punkt muss verdoppelt werden (RFC 5321, 4.5.2):\n" + m);
    }

    @Test
    @DisplayName("Eine Adresse mit Zeilenumbruch wird abgewiesen — sonst ließen sich Kopfzeilen einschleusen")
    void keineEinschleusung() {
        assertFalse(Mailversand.gueltigeAdresse("a@b.de\r\nBcc: fremd@example.com"));
        assertFalse(Mailversand.gueltigeAdresse("a@b.de, fremd@example.com"));
        assertTrue(Mailversand.gueltigeAdresse("leitung@praxis.example"));
    }

    // ------------------------------------------------------------- Kleinteile

    private Geltungsbereich alles() { return Geltungsbereich.alles(); }

    private int wartend(long orgId) {
        try (PreparedStatement p = db.verbindung().prepareStatement(
                "SELECT count(*) FROM mail_warteschlange WHERE organisation_id = ? "
                + "AND versendet IS NULL")) {
            p.setLong(1, orgId);
            try (ResultSet rs = p.executeQuery()) { rs.next(); return rs.getInt(1); }
        } catch (SQLException e) { throw new IllegalStateException(e); }
    }

    /** Schiebt die Wartenden künstlich in die Vergangenheit. */
    private void alterMachen(long orgId, int sekunden) throws SQLException {
        try (PreparedStatement p = db.verbindung().prepareStatement(
                "UPDATE mail_warteschlange SET angelegt = now() - make_interval(secs => ?) "
                + "WHERE organisation_id = ? AND versendet IS NULL")) {
            p.setInt(1, sekunden);
            p.setLong(2, orgId);
            p.executeUpdate();
        }
    }

    private static long org(String bez, String art, Long eltern) throws SQLException {
        try (PreparedStatement p = db.verbindung().prepareStatement("""
                INSERT INTO organisation (bezeichnung, art, eltern_id, ist_demo)
                VALUES (?,?,?,FALSE) RETURNING id""")) {
            p.setString(1, bez);
            p.setString(2, art);
            if (eltern == null) p.setNull(3, Types.BIGINT); else p.setLong(3, eltern);
            try (ResultSet rs = p.executeQuery()) { rs.next(); return rs.getLong(1); }
        }
    }

    private static void aufraeumen() throws SQLException {
        try (Statement s = db.verbindung().createStatement()) {
            s.executeUpdate("DELETE FROM mail_warteschlange WHERE organisation_id IN "
                    + "(SELECT id FROM organisation WHERE bezeichnung LIKE 'BN %')");
            s.executeUpdate("DELETE FROM mail_regel WHERE organisation_id IN "
                    + "(SELECT id FROM organisation WHERE bezeichnung LIKE 'BN %')");
            s.executeUpdate("DELETE FROM organisation WHERE bezeichnung LIKE 'BN %'");
            s.executeUpdate("DELETE FROM mail_server");
        }
    }
}
