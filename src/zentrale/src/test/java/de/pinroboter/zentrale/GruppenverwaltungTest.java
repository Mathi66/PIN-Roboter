package de.pinroboter.zentrale;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Integrationstest der Gerätegruppen (FA-GER-050) gegen echte PostgreSQL. */
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class GruppenverwaltungTest {

    /** Diese Klasse prüft Fachlogik; die Mandantentrennung hat eine eigene Testklasse. */
    private static final Geltungsbereich ALLES = Geltungsbereich.alles();

    private static Datenbank db;
    private static Gruppenverwaltung gv;

    @BeforeAll
    static void verbinden() throws Exception {
        db = new Datenbank(System.getenv("TEST_DB_URL"),
                System.getenv().getOrDefault("TEST_DB_USER", "pinroboter"),
                System.getenv().getOrDefault("TEST_DB_PASSWORD", "test"));
        gv = new Gruppenverwaltung(db);
    }

    @AfterAll static void trennen() { if (db != null) db.close(); }

    private String neu() { return "gruppe-" + UUID.randomUUID().toString().substring(0, 8); }

    @Test
    @DisplayName("Es gibt genau eine Vorgabegruppe")
    void vorgabegruppeVorhanden() {
        var v = gv.vorgabegruppe("Praxis");
        assertTrue(v.isPresent(), "Ohne Vorgabegruppe hätte ein neu erkanntes Gerät kein Zuhause");
        assertTrue(v.get().istVorgabe());
    }

    @Test
    @DisplayName("Gruppen lassen sich anlegen, doppelte Namen nicht")
    void anlegen() {
        String name = neu();
        assertNull(gv.anlegen(name, "Praxis", 60));
        String fehler = gv.anlegen(name, "Praxis", 60);
        assertNotNull(fehler);
        assertTrue(fehler.contains("bereits"));
    }

    @Test
    @DisplayName("Ein zu kurzes Prüfintervall wird mit Begründung abgelehnt")
    void intervallUntergrenze() {
        String fehler = gv.anlegen(neu(), "Praxis", 5);
        assertNotNull(fehler);
        assertTrue(fehler.contains("10 Sekunden"));
        assertTrue(fehler.length() > 40, "Die Meldung muss den Grund nennen, nicht nur ablehnen");
    }

    @Test
    @DisplayName("Neu gemeldete Geräte landen in der Vorgabegruppe")
    void neuesGeraetInVorgabegruppe() {
        String id = "KT-" + UUID.randomUUID().toString().substring(0, 8);
        assertTrue(gv.geraetSicherstellen(id, "Praxis", "Testgerät"));
        assertFalse(gv.geraetSicherstellen(id, "Praxis", "Testgerät"),
                "Ein zweites Mal darf es nicht erneut angelegt werden");
        assertEquals(gv.vorgabegruppe("Praxis").orElseThrow().id(),
                gv.gruppeVon(id).orElseThrow());
    }

    @Test
    @DisplayName("Geräte lassen sich umgruppieren, das Intervall folgt der Gruppe")
    void umgruppieren() {
        String name = neu();
        assertNull(gv.anlegen(name, "Praxis", 120));
        long neueGruppe = gv.alle("Praxis", ALLES).stream()
                .filter(g -> g.bezeichnung().equals(name)).findFirst().orElseThrow().id();

        String id = "KT-" + UUID.randomUUID().toString().substring(0, 8);
        gv.geraetSicherstellen(id, "Praxis", "Testgerät");
        assertNull(gv.zuordnen(id, neueGruppe));
        assertEquals(neueGruppe, gv.gruppeVon(id).orElseThrow());
        assertEquals(120, gv.intervalleJeGeraet("Praxis").get(id),
                "Das Prüfintervall muss der Gruppenzuordnung folgen");
    }

    @Test
    @DisplayName("FA-GER-050: Beim Löschen einer Gruppe fällt kein Gerät aus der Überwachung")
    void loeschenVerschiebtGeraete() {
        String name = neu();
        assertNull(gv.anlegen(name, "Praxis", 90));
        long id = gv.alle("Praxis", ALLES).stream()
                .filter(g -> g.bezeichnung().equals(name)).findFirst().orElseThrow().id();

        String geraet = "KT-" + UUID.randomUUID().toString().substring(0, 8);
        gv.geraetSicherstellen(geraet, "Praxis", "Testgerät");
        gv.zuordnen(geraet, id);

        assertNull(gv.loeschen(id, "Praxis"));
        assertEquals(gv.vorgabegruppe("Praxis").orElseThrow().id(),
                gv.gruppeVon(geraet).orElseThrow(),
                "Das Gerät muss in der Vorgabegruppe gelandet sein, nicht verschwunden");
    }

    @Test
    @DisplayName("Die Vorgabegruppe lässt sich nicht löschen")
    void vorgabegruppeGeschuetzt() {
        long id = gv.vorgabegruppe("Praxis").orElseThrow().id();
        String fehler = gv.loeschen(id, "Praxis");
        assertNotNull(fehler);
        assertTrue(fehler.contains("Vorgabegruppe"));
    }

    @Test
    @DisplayName("Das Prüfintervall lässt sich ändern, aber nicht unter die Untergrenze")
    void intervallAendern() {
        String name = neu();
        gv.anlegen(name, "Praxis", 60);
        long id = gv.alle("Praxis", ALLES).stream()
                .filter(g -> g.bezeichnung().equals(name)).findFirst().orElseThrow().id();
        assertNull(gv.intervallSetzen(id, 300));
        assertEquals(300, gv.alle("Praxis", ALLES).stream()
                .filter(g -> g.id() == id).findFirst().orElseThrow().pruefintervallSekunden());
        assertNotNull(gv.intervallSetzen(id, 3));
    }

    @Test
    @DisplayName("Eine unbekannte Einrichtung bekommt ihre Vorgabegruppe beim ersten Gerät")
    void vorgabegruppeEntstehtBeiBedarf() {
        // Migration 6 legt die Vorgabegruppe nur für "Praxis" an. Bis zum
        // 09.08.2026 fiel deshalb jedes Gerät eines Agenten an einem anderen
        // Standort still durch — es stand im Protokoll, aber in keiner Gruppe,
        // hatte also auch kein Prüfintervall.
        String einrichtung = "Standort-" + UUID.randomUUID().toString().substring(0, 8);
        assertTrue(gv.vorgabegruppe(einrichtung).isEmpty(), "Vorbedingung: Einrichtung ist neu");

        String terminal = "KT-" + UUID.randomUUID().toString().substring(0, 8);
        assertTrue(gv.geraetSicherstellen(terminal, einrichtung, "Terminal"),
                "Das Gerät muss erfasst werden, auch wenn die Einrichtung neu ist");

        var v = gv.vorgabegruppe(einrichtung);
        assertTrue(v.isPresent());
        assertTrue(v.get().istVorgabe());
        // Und das Gerät hängt daran — sonst hätte es kein Prüfintervall.
        assertEquals(v.get().id(), gv.gruppeVon(terminal).orElseThrow());
    }

    @Test
    @DisplayName("Zweimal anlegen erzeugt keine zweite Vorgabegruppe")
    void vorgabegruppeBleibtEinmalig() {
        String einrichtung = "Standort-" + UUID.randomUUID().toString().substring(0, 8);
        long ersteId = gv.vorgabegruppeAnlegen(einrichtung).orElseThrow().id();
        // Idempotenz zählt: Zwei Agenten derselben Einrichtung melden sich
        // gleichzeitig, und der eindeutige Index darf nicht zum Abbruch führen.
        assertEquals(ersteId, gv.vorgabegruppeAnlegen(einrichtung).orElseThrow().id());
        assertEquals(1, gv.alle(einrichtung, ALLES).stream().filter(Gruppenverwaltung.Gruppe::istVorgabe).count());
    }
}
