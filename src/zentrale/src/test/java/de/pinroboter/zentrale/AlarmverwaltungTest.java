package de.pinroboter.zentrale;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Integrationstest der Alarmierung (FA-ALA-010) gegen echte PostgreSQL. */
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class AlarmverwaltungTest {

    /** Diese Klasse prüft Fachlogik; die Mandantentrennung hat eine eigene Testklasse. */
    private static final Geltungsbereich ALLES = Geltungsbereich.alles();

    private static Datenbank db;
    private static Alarmverwaltung av;
    private static final String E = "PruefEinrichtung";

    @BeforeAll
    static void verbinden() throws Exception {
        db = new Datenbank(System.getenv("TEST_DB_URL"),
                System.getenv().getOrDefault("TEST_DB_USER", "pinroboter"),
                System.getenv().getOrDefault("TEST_DB_PASSWORD", "test"));
        av = new Alarmverwaltung(db);
    }

    @AfterAll static void trennen() { if (db != null) db.close(); }

    private String obj() { return "KT-" + UUID.randomUUID().toString().substring(0, 8); }

    @Test
    @DisplayName("Der erste Alarm ist neu, jede Wiederholung nicht")
    void keineDuplikate() {
        String o = obj();
        assertTrue(av.melden(E, o, Alarmverwaltung.Art.GERAET_NICHT_ERREICHBAR,
                "Antwortet nicht", "Netzwerk prüfen"), "Der erste muss als neu gelten");

        // Ein alle vier Sekunden pruefender Agent meldet denselben Zustand
        // hundertfach. Nur der erste darf eine Benachrichtigung ausloesen.
        for (int i = 0; i < 100; i++) {
            assertFalse(av.melden(E, o, Alarmverwaltung.Art.GERAET_NICHT_ERREICHBAR,
                    "Antwortet nicht", "Netzwerk prüfen"),
                    "Wiederholung " + i + " darf nicht als neu gelten");
        }
        assertEquals(1, av.offene(E, ALLES).stream().filter(a -> o.equals(a.objekt())).count(),
                "Es darf genau ein offener Alarm existieren");
    }

    @Test
    @DisplayName("FA-ALA-010: Ein behobener Zustand schließt den Alarm automatisch")
    void behobenSchliesstAutomatisch() {
        String o = obj();
        av.melden(E, o, Alarmverwaltung.Art.KARTE_UNVERIFIZIERT, "Nicht verifiziert", "Warten");
        assertEquals(1, av.offene(E, ALLES).stream().filter(a -> o.equals(a.objekt())).count());

        assertTrue(av.beheben(E, o, Alarmverwaltung.Art.KARTE_UNVERIFIZIERT));
        assertEquals(0, av.offene(E, ALLES).stream().filter(a -> o.equals(a.objekt())).count(),
                "Nach dem Beheben darf kein offener Alarm mehr übrig sein");

        assertFalse(av.beheben(E, o, Alarmverwaltung.Art.KARTE_UNVERIFIZIERT),
                "Zweimaliges Beheben darf nichts mehr bewirken");
    }

    @Test
    @DisplayName("Derselbe Zustand kann nach dem Beheben erneut auftreten")
    void erneutesAuftreten() {
        String o = obj();
        assertTrue(av.melden(E, o, Alarmverwaltung.Art.GERAET_NICHT_ERREICHBAR, "weg", "prüfen"));
        assertTrue(av.beheben(E, o, Alarmverwaltung.Art.GERAET_NICHT_ERREICHBAR));
        assertTrue(av.melden(E, o, Alarmverwaltung.Art.GERAET_NICHT_ERREICHBAR, "weg", "prüfen"),
                "Ein wiederkehrendes Problem muss erneut alarmieren");
    }

    @Test
    @DisplayName("Verschiedene Arten am selben Gerät sind eigene Alarme")
    void arteneigenstaendig() {
        String o = obj();
        assertTrue(av.melden(E, o, Alarmverwaltung.Art.GERAET_NICHT_ERREICHBAR, "a", "b"));
        assertTrue(av.melden(E, o, Alarmverwaltung.Art.PEVIM_NICHT_AKTIV, "c", "d"));
        assertEquals(2, av.offene(E, ALLES).stream().filter(a -> o.equals(a.objekt())).count());
    }

    @Test
    @DisplayName("Quittieren wirkt einmal und ändert den offenen Zustand nicht")
    void quittieren() {
        String o = obj();
        av.melden(E, o, Alarmverwaltung.Art.AUTOMATIK_ANGEHALTEN, "angehalten", "PIN prüfen");
        var alarm = av.offene(E, ALLES).stream().filter(a -> o.equals(a.objekt())).findFirst().orElseThrow();
        assertFalse(alarm.quittiert());

        assertTrue(av.quittieren(alarm.id(), "Anna Beispiel", Geltungsbereich.alles()));
        assertFalse(av.quittieren(alarm.id(), "Jemand anders", Geltungsbereich.alles()), "Zweimal quittieren geht nicht");

        var danach = av.offene(E, ALLES).stream().filter(a -> o.equals(a.objekt())).findFirst().orElseThrow();
        assertTrue(danach.quittiert());
        assertEquals("Anna Beispiel", danach.quittiertVon());
        assertNotNull(danach.quittiertAm(),
                "Ein quittierter Alarm bleibt offen — quittiert heißt gesehen, nicht behoben");
    }

    @Test
    @DisplayName("Quittierte Alarme zählen nicht mehr als offener Handlungsbedarf")
    void zaehlungIgnoriertQuittierte() {
        String o = obj();
        av.melden(E, o, Alarmverwaltung.Art.KARTE_GESPERRT, "gesperrt", "PUK");
        int vorher = av.zaehlen(E).get(Alarmverwaltung.Schweregrad.KRITISCH);
        var a = av.offene(E, ALLES).stream().filter(x -> o.equals(x.objekt())).findFirst().orElseThrow();
        av.quittieren(a.id(), "Prüfer", Geltungsbereich.alles());
        assertEquals(vorher - 1, av.zaehlen(E).get(Alarmverwaltung.Schweregrad.KRITISCH));
    }

    @Test
    @DisplayName("Jeder Alarm trägt eine Handlungsempfehlung (NFA-USA-020)")
    void handlungsempfehlungVorhanden() {
        String o = obj();
        av.melden(E, o, Alarmverwaltung.Art.KARTE_GESPERRT,
                "Die SMC-B ist gesperrt.",
                "Die Karte lässt sich nur mit der PUK entsperren.");
        var a = av.offene(E, ALLES).stream().filter(x -> o.equals(x.objekt())).findFirst().orElseThrow();
        assertNotNull(a.handlung());
        assertTrue(a.handlung().length() > 20, "Eine Handlungsempfehlung muss etwas sagen");
    }

    @Test
    @DisplayName("Kritische Alarme stehen oben")
    void reihenfolgeNachSchweregrad() {
        String o1 = obj(), o2 = obj();
        av.melden(E, o1, Alarmverwaltung.Art.PEVIM_NICHT_AKTIV, "Hinweis", "irgendwas");
        av.melden(E, o2, Alarmverwaltung.Art.KARTE_GESPERRT, "Kritisch", "PUK");
        var liste = av.offene(E, ALLES);
        assertEquals(Alarmverwaltung.Schweregrad.KRITISCH, liste.get(0).schweregrad(),
                "Was den Betrieb anhält, muss zuerst ins Auge fallen");
    }
    @Test
    @DisplayName("Ein Alarm außerhalb des Geltungsbereichs lässt sich nicht quittieren")
    void quittierenNurImBereich() {
        String o = obj();
        av.melden(E, o, Alarmverwaltung.Art.GERAET_NICHT_ERREICHBAR,
                "nicht erreichbar", "Netzwerk prüfen");
        var a = av.offene(E, ALLES).stream()
                .filter(x -> o.equals(x.objekt())).findFirst().orElseThrow();
        // Ein leerer Bereich sieht nichts und darf auch nichts stillstellen.
        assertFalse(av.quittieren(a.id(), "Fremder", Geltungsbereich.keiner()));
        assertFalse(av.offene(E, ALLES).stream()
                .filter(x -> x.id() == a.id()).findFirst().orElseThrow().quittiert());
    }

}
