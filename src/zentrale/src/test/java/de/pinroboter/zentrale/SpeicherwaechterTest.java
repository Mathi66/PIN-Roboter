package de.pinroboter.zentrale;

import de.pinroboter.gemeinsam.Ereignisart;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Prüft Speicherbudget und gestaffelte Rotation (FA-PRO-070) gegen eine echte
 * Datenbank — die belegte Größe lässt sich nur dort messen.
 */
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class SpeicherwaechterTest {

    private static Datenbank db;

    @BeforeAll
    static void verbinden() throws Exception {
        db = new Datenbank(System.getenv("TEST_DB_URL"),
                System.getenv().getOrDefault("TEST_DB_USER", "pinroboter"),
                System.getenv().getOrDefault("TEST_DB_PASSWORD", "test"));
    }

    @AfterAll static void trennen() { if (db != null) db.close(); }

    @Test
    @DisplayName("Angaben wie 2 GB oder 500 MB werden richtig umgerechnet")
    void budgetAusText() {
        assertEquals(2L * 1073741824L, Speicherwaechter.budgetAusText("2 GB", 0));
        assertEquals(500L * 1048576L, Speicherwaechter.budgetAusText("500MB", 0));
        assertEquals(1024L, Speicherwaechter.budgetAusText("1 KB", 0));
        assertEquals(4711L, Speicherwaechter.budgetAusText("4711", 0));
        assertEquals(99L, Speicherwaechter.budgetAusText("Unsinn", 99), "Vorgabe bei Unsinn");
        assertEquals(99L, Speicherwaechter.budgetAusText(null, 99));
    }

    @Test
    @DisplayName("Der Füllstand wird lesbar dargestellt")
    void lesbareGroessen() {
        var z = new Speicherwaechter.Zustand(1536, 1073741824L, 3);
        assertEquals("1 KB", z.belegtLesbar());
        assertEquals("1.00 GB", z.budgetLesbar());
        assertEquals(0, z.prozent());
    }

    @Test
    @DisplayName("Die Schwellen greifen wie beschrieben")
    void schwellen() {
        assertFalse(new Speicherwaechter.Zustand(70, 100, 0).rotationNoetig());
        assertTrue(new Speicherwaechter.Zustand(80, 100, 0).rotationNoetig());
        assertFalse(new Speicherwaechter.Zustand(80, 100, 0).alarmNoetig());
        assertTrue(new Speicherwaechter.Zustand(90, 100, 0).alarmNoetig());
    }

    @Test
    @DisplayName("FA-PRO-070: Rotation setzt bei überschrittenem Budget ein")
    void rotationGreift() {
        // Das Budget wird NACH dem Befüllen bemessen, nicht vorher: Sonst hinge
        // der Test daran, wie viel in der Datenbank ohnehin schon liegt — und
        // wäre je nach Ausführungsreihenfolge grün oder rot.
        var messer = new Speicherwaechter(db, Long.MAX_VALUE);
        var p = new Fachprotokoll(db, messer);
        for (int i = 0; i < 400; i++) {
            p.anfuegen("RotTest", "KT-" + i, Ereignisart.GERAET_ERREICHBAR,
                    true, "Test", null, "Fülltext " + i, null);
        }
        long belegt = messer.zustand().belegtBytes();
        var w = new Speicherwaechter(db, (long) (belegt * 0.7));   // Budget unter Belegung

        var vorher = w.zustand();
        assertTrue(vorher.rotationNoetig(),
                "Bei einem Budget unter der Belegung muss rotiert werden, belegt: "
                + vorher.belegtLesbar());

        var e = w.rotieren();
        assertTrue(e.rotiert(), "Es muss rotiert worden sein");
        assertTrue(e.geloescht() > 0, "Es müssen Einträge gelöscht worden sein");
    }

    @Test
    @DisplayName("Gestaffelt: Routineereignisse weichen vor sicherheitsrelevanten")
    void gestaffelteReihenfolge() {
        var messer = new Speicherwaechter(db, Long.MAX_VALUE);
        var p = new Fachprotokoll(db, messer);

        // Erst Sicherheitsereignisse, danach viele Routineereignisse. Weil die
        // Sicherheitsereignisse AELTER sind, wuerde eine reine Sortierung nach
        // Alter sie zuerst loeschen - genau das darf nicht passieren.
        for (int i = 0; i < 30; i++) {
            p.anfuegen("StaffelTest", null, Ereignisart.PIN_HINTERLEGT,
                    true, "Test", null, "Sicherheitsereignis " + i, null);
        }
        long sicherheitVorher = zaehle("StaffelTest", "SICHERHEIT");
        assertTrue(sicherheitVorher >= 30);

        for (int i = 0; i < 3000; i++) {
            p.anfuegen("StaffelTest", "KT-" + i, Ereignisart.GERAET_ERREICHBAR,
                    true, "Test", null, "Routine " + i, null);
        }

        // Budget knapp unter die jetzige Belegung setzen - unabhaengig davon,
        // wie viel schon in der Datenbank lag.
        var w = new Speicherwaechter(db, (long) (messer.zustand().belegtBytes() * 0.8));
        w.rotieren();
        long sicherheitNachher = zaehle("StaffelTest", "SICHERHEIT");
        long routineNachher = zaehle("StaffelTest", "ROUTINE");

        assertTrue(routineNachher < 3000,
                "Routineereignisse müssen gelöscht worden sein");
        assertEquals(sicherheitVorher, sicherheitNachher,
                "Sicherheitsrelevante Einträge dürfen NICHT gelöscht werden, "
                + "solange noch Routineereignisse da sind — auch wenn sie älter sind");
    }

    @Test
    @DisplayName("Ein zu kleines Budget vernichtet nicht das ganze Protokoll")
    void zuKleinesBudgetVernichtetNichtAlles() {
        var w = new Speicherwaechter(db, 1024);   // 1 KB — kleiner als die Indizes
        var p = new Fachprotokoll(db, w);
        for (int i = 0; i < 50; i++) {
            p.anfuegen("ZuKlein", null, Ereignisart.PIN_HINTERLEGT,
                    true, "Test", null, "Wichtiger Nachweis " + i, null);
        }
        var e = w.rotieren();
        assertTrue(e.budgetZuKlein(),
                "Der Zustand muss als 'Budget zu klein' erkennbar sein");
        assertTrue(zaehle("ZuKlein", "SICHERHEIT") > 0,
                "Ein zu klein konfiguriertes Budget darf nicht alle Nachweise löschen");
    }

    @Test
    @DisplayName("Ohne Budgetüberschreitung wird nichts gelöscht")
    void keineRotationOhneNot() {
        var w = new Speicherwaechter(db, 100L * 1024 * 1024 * 1024);   // 100 GB
        var e = w.rotieren();
        assertFalse(e.rotiert());
        assertEquals(0, e.geloescht());
    }

    private long zaehle(String einrichtung, String stufe) {
        try (var p = db.verbindung().prepareStatement(
                "SELECT count(*) FROM fachprotokoll WHERE einrichtung = ? AND stufe = ?")) {
            p.setString(1, einrichtung);
            p.setString(2, stufe);
            try (var rs = p.executeQuery()) { return rs.next() ? rs.getLong(1) : 0; }
        } catch (Exception e) { return -1; }
    }
}
