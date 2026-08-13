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

    /**
     * Die Einrichtung dieser Tests — und zwar als <b>echte
     * Organisationseinheit</b>, nicht als Freitext.
     *
     * <p>Bis zum 11.08.2026 arbeiteten diese Tests mit der Einrichtung
     * „Praxis", zu der es keine Organisationseinheit gibt. Sie prüften damit
     * einen Zustand, den das Produkt gar nicht haben soll: Gerätegruppen ohne
     * Mandantenzuordnung sind für jede eingeschränkte Abfrage unsichtbar. Erst
     * als {@code organisation_id} auf NOT NULL ging (Migration 23), fiel es
     * auf — die Tests wurden rot, das Produkt war richtiger als vorher.
     */
    private static final String EINRICHTUNG = "Testeinrichtung Gruppen";

    @BeforeAll
    static void verbinden() throws Exception {
        db = new Datenbank(System.getenv("TEST_DB_URL"),
                System.getenv().getOrDefault("TEST_DB_USER", "pinroboter"),
                System.getenv().getOrDefault("TEST_DB_PASSWORD", "test"));
        gv = new Gruppenverwaltung(db);
        aufraeumen();

        // Eine Einrichtung haengt immer unter einem Verbund — das erzwingt die
        // Bedingung organisation_hierarchie.
        long verbund;
        try (java.sql.Statement st = db.verbindung().createStatement();
             java.sql.ResultSet rs = st.executeQuery("""
                     INSERT INTO organisation (bezeichnung, art, ist_demo)
                     VALUES ('Testverbund Gruppen', 'VERBUND', FALSE) RETURNING id""")) {
            rs.next(); verbund = rs.getLong(1);
        }
        try (java.sql.PreparedStatement p = db.verbindung().prepareStatement("""
                INSERT INTO organisation (bezeichnung, art, eltern_id, ist_demo)
                VALUES (?, 'EINRICHTUNG', ?, FALSE)""")) {
            p.setString(1, EINRICHTUNG);
            p.setLong(2, verbund);
            p.executeUpdate();
        }
    }

    @AfterAll static void trennen() throws Exception {
        if (db != null) { aufraeumen(); db.close(); }
    }

    private static void aufraeumen() throws Exception {
        try (java.sql.Statement s = db.verbindung().createStatement()) {
            s.executeUpdate("DELETE FROM geraet WHERE einrichtung = '" + EINRICHTUNG + "'");
            s.executeUpdate("DELETE FROM geraetegruppe WHERE einrichtung = '" + EINRICHTUNG + "'");
            s.executeUpdate("DELETE FROM organisation WHERE bezeichnung = '" + EINRICHTUNG + "'");
            s.executeUpdate("DELETE FROM geraet WHERE einrichtung LIKE 'Standort-%'");
            s.executeUpdate("DELETE FROM geraetegruppe WHERE einrichtung LIKE 'Standort-%'");
            s.executeUpdate("DELETE FROM organisation WHERE bezeichnung LIKE 'Standort-%'");
            s.executeUpdate("DELETE FROM organisation WHERE bezeichnung = 'Testverbund Gruppen'");
        }
    }

    private String neu() { return "gruppe-" + UUID.randomUUID().toString().substring(0, 8); }

    /**
     * Eine frische Einrichtung — als <b>echte Organisationseinheit</b>.
     *
     * <p>Ein zufälliger Name allein genügt seit Migration 23 nicht mehr: Ohne
     * Organisationseinheit entsteht keine Gerätegruppe, und das ist gewollt.
     * Diese Tests bilden damit ab, wie das Produkt tatsächlich benutzt wird —
     * ein Agent wird auf eine vorhandene Einheit registriert.
     */
    private static String neueEinrichtung() throws Exception {
        String name = "Standort-" + UUID.randomUUID().toString().substring(0, 8);
        try (java.sql.PreparedStatement p = db.verbindung().prepareStatement("""
                INSERT INTO organisation (bezeichnung, art, eltern_id, ist_demo)
                SELECT ?, 'EINRICHTUNG', id, FALSE
                FROM organisation WHERE bezeichnung = 'Testverbund Gruppen'""")) {
            p.setString(1, name);
            p.executeUpdate();
        }
        return name;
    }

    @Test
    @DisplayName("Es gibt genau eine Vorgabegruppe")
    void vorgabegruppeVorhanden() {
        // Sie entsteht beim ersten Bedarf, nicht auf Vorrat: Eine leere Gruppe
        // in jeder Einrichtung, die es je gab, waere Ballast.
        var v = gv.vorgabegruppeAnlegen(EINRICHTUNG);
        assertTrue(v.isPresent(), "Ohne Vorgabegruppe hätte ein neu erkanntes Gerät kein Zuhause");
        assertTrue(v.get().istVorgabe());
        assertTrue(gv.vorgabegruppe(EINRICHTUNG).isPresent(), "und sie bleibt auffindbar");
    }

    @Test
    @DisplayName("Ohne Organisationseinheit entsteht KEINE Gruppe")
    void keineGruppeOhneOrganisation() {
        // Der Kern des Befunds vom 11.08.2026: Eine Gruppe ohne
        // Organisationseinheit ist für jede mandantengebundene Abfrage
        // unsichtbar (NULL = ANY(...) ist niemals wahr) und erscheint nur dem
        // Instanzbetreiber. Genau daher meldete die Übersicht 9 Gruppen und die
        // Liste 19.
        String erfunden = "Einrichtung die es nicht gibt " + UUID.randomUUID();
        assertTrue(gv.vorgabegruppeAnlegen(erfunden).isEmpty(),
                "Lieber keine Gruppe als eine, die niemand sehen kann");
        assertNotNull(gv.anlegen("Irgendeine Gruppe", erfunden, 60),
                "Auch das Anlegen von Hand muss abgelehnt werden");
    }

    @Test
    @DisplayName("Gruppen lassen sich anlegen, doppelte Namen nicht")
    void anlegen() {
        String name = neu();
        assertNull(gv.anlegen(name, EINRICHTUNG, 60));
        String fehler = gv.anlegen(name, EINRICHTUNG, 60);
        assertNotNull(fehler);
        assertTrue(fehler.contains("bereits"));
    }

    @Test
    @DisplayName("Ein zu kurzes Prüfintervall wird mit Begründung abgelehnt")
    void intervallUntergrenze() {
        String fehler = gv.anlegen(neu(), EINRICHTUNG, 5);
        assertNotNull(fehler);
        assertTrue(fehler.contains("10 Sekunden"));
        assertTrue(fehler.length() > 40, "Die Meldung muss den Grund nennen, nicht nur ablehnen");
    }

    @Test
    @DisplayName("Neu gemeldete Geräte landen in der Vorgabegruppe")
    void neuesGeraetInVorgabegruppe() {
        String id = "KT-" + UUID.randomUUID().toString().substring(0, 8);
        assertTrue(gv.geraetSicherstellen(id, EINRICHTUNG, "Testgerät"));
        assertFalse(gv.geraetSicherstellen(id, EINRICHTUNG, "Testgerät"),
                "Ein zweites Mal darf es nicht erneut angelegt werden");
        assertEquals(gv.vorgabegruppe(EINRICHTUNG).orElseThrow().id(),
                gv.gruppeVon(id).orElseThrow());
    }

    @Test
    @DisplayName("Geräte lassen sich umgruppieren, das Intervall folgt der Gruppe")
    void umgruppieren() {
        String name = neu();
        assertNull(gv.anlegen(name, EINRICHTUNG, 120));
        long neueGruppe = gv.alle(EINRICHTUNG, ALLES).stream()
                .filter(g -> g.bezeichnung().equals(name)).findFirst().orElseThrow().id();

        String id = "KT-" + UUID.randomUUID().toString().substring(0, 8);
        gv.geraetSicherstellen(id, EINRICHTUNG, "Testgerät");
        assertNull(gv.zuordnen(id, neueGruppe));
        assertEquals(neueGruppe, gv.gruppeVon(id).orElseThrow());
        assertEquals(120, gv.intervalleJeGeraet(EINRICHTUNG).get(id),
                "Das Prüfintervall muss der Gruppenzuordnung folgen");
    }

    @Test
    @DisplayName("FA-GER-050: Beim Löschen einer Gruppe fällt kein Gerät aus der Überwachung")
    void loeschenVerschiebtGeraete() {
        String name = neu();
        assertNull(gv.anlegen(name, EINRICHTUNG, 90));
        long id = gv.alle(EINRICHTUNG, ALLES).stream()
                .filter(g -> g.bezeichnung().equals(name)).findFirst().orElseThrow().id();

        String geraet = "KT-" + UUID.randomUUID().toString().substring(0, 8);
        gv.geraetSicherstellen(geraet, EINRICHTUNG, "Testgerät");
        gv.zuordnen(geraet, id);

        assertNull(gv.loeschen(id, ALLES));
        assertEquals(gv.vorgabegruppe(EINRICHTUNG).orElseThrow().id(),
                gv.gruppeVon(geraet).orElseThrow(),
                "Das Gerät muss in der Vorgabegruppe gelandet sein, nicht verschwunden");
    }

    @Test
    @DisplayName("Die Vorgabegruppe lässt sich nicht löschen")
    void vorgabegruppeGeschuetzt() {
        long id = gv.vorgabegruppe(EINRICHTUNG).orElseThrow().id();
        String fehler = gv.loeschen(id, ALLES);
        assertNotNull(fehler);
        assertTrue(fehler.contains("Vorgabegruppe"));
    }

    @Test
    @DisplayName("Das Prüfintervall lässt sich ändern, aber nicht unter die Untergrenze")
    void intervallAendern() {
        String name = neu();
        gv.anlegen(name, EINRICHTUNG, 60);
        long id = gv.alle(EINRICHTUNG, ALLES).stream()
                .filter(g -> g.bezeichnung().equals(name)).findFirst().orElseThrow().id();
        assertNull(gv.intervallSetzen(id, 300, ALLES));
        assertEquals(300, gv.alle(EINRICHTUNG, ALLES).stream()
                .filter(g -> g.id() == id).findFirst().orElseThrow().pruefintervallSekunden());
        assertNotNull(gv.intervallSetzen(id, 3, ALLES));
    }

    @Test
    @DisplayName("Eine unbekannte Einrichtung bekommt ihre Vorgabegruppe beim ersten Gerät")
    void vorgabegruppeEntstehtBeiBedarf() throws Exception {
        // Migration 6 legt die Vorgabegruppe nur für EINRICHTUNG an. Bis zum
        // 09.08.2026 fiel deshalb jedes Gerät eines Agenten an einem anderen
        // Standort still durch — es stand im Protokoll, aber in keiner Gruppe,
        // hatte also auch kein Prüfintervall.
        String einrichtung = neueEinrichtung();
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
    void vorgabegruppeBleibtEinmalig() throws Exception {
        String einrichtung = neueEinrichtung();
        long ersteId = gv.vorgabegruppeAnlegen(einrichtung).orElseThrow().id();
        // Idempotenz zählt: Zwei Agenten derselben Einrichtung melden sich
        // gleichzeitig, und der eindeutige Index darf nicht zum Abbruch führen.
        assertEquals(ersteId, gv.vorgabegruppeAnlegen(einrichtung).orElseThrow().id());
        assertEquals(1, gv.alle(einrichtung, ALLES).stream().filter(Gruppenverwaltung.Gruppe::istVorgabe).count());
    }

    @Test
    @DisplayName("Eine bestehende Gruppe wird zur Vorgabe, wenn der Name schon belegt ist")
    void bestehendeGruppeWirdVorgabe() throws Exception {
        // Am 10.08.2026 am laufenden Stand aufgetreten: In einer Einrichtung
        // gab es bereits eine von Hand angelegte Gruppe dieses Namens — ohne
        // Vorgabe-Kennzeichen. Das Anlegen der Vorgabegruppe scheiterte am
        // eindeutigen Namensindex, ON CONFLICT schluckte es, und JEDES vom
        // Agenten gemeldete Gerät fiel durch. Vier Terminals waren im
        // Protokoll sichtbar, aber in keiner Geräteliste.
        String einrichtung = neueEinrichtung();
        assertNull(gv.anlegen(Gruppenverwaltung.vorgabename(einrichtung), einrichtung, 60));
        assertTrue(gv.vorgabegruppe(einrichtung).isEmpty(),
                "Vorbedingung: eine Gruppe, aber keine als Vorgabe gekennzeichnet");

        String terminal = "KT-" + UUID.randomUUID().toString().substring(0, 8);
        assertTrue(gv.geraetSicherstellen(terminal, einrichtung, "Terminal"),
                "Das Gerät muss erfasst werden, auch wenn der Name der Vorgabegruppe belegt ist");

        // Und zwar in der VORHANDENEN Gruppe, nicht in einer zweiten mit
        // Ausweichnamen: Wer eine Gruppe angelegt hat, will Geräte dort haben.
        assertEquals(1, gv.alle(einrichtung, ALLES).size(),
                "Es darf keine zweite Gruppe entstanden sein");
        var v = gv.vorgabegruppe(einrichtung);
        assertTrue(v.isPresent());
        assertEquals(Gruppenverwaltung.vorgabename(einrichtung), v.get().bezeichnung());
        assertEquals(v.get().id(), gv.gruppeVon(terminal).orElseThrow());
    }

    @Test
    @DisplayName("Neben fachlich benannten Gruppen entsteht eine eigene Vorgabegruppe")
    void eigeneVorgabeNebenFachgruppen() throws Exception {
        // Wichtige Unterscheidung: Hat eine Einrichtung Gruppen wie
        // „Notaufnahme" und „Archiv", darf NICHT eine davon zur Vorgabe werden
        // — sonst landete jedes neu erkannte Gerät in der Notaufnahme. Es
        // entsteht eine eigene Vorgabegruppe mit dem Namen der Einrichtung.
        // Die Namen tragen die Einrichtung: Seit dem 13.08.2026 muss eine
        // Gruppenbezeichnung instanzweit eindeutig sein, und diese Testklasse
        // legt viele Einrichtungen in derselben Datenbank an.
        String einrichtung = neueEinrichtung();
        assertNull(gv.anlegen("Notaufnahme " + einrichtung, einrichtung, 30));
        assertNull(gv.anlegen("Archiv " + einrichtung, einrichtung, 300));

        var v = gv.vorgabegruppeAnlegen(einrichtung);
        assertTrue(v.isPresent());
        assertEquals(Gruppenverwaltung.vorgabename(einrichtung), v.get().bezeichnung());
        assertEquals(3, gv.alle(einrichtung, ALLES).size());
        // Und die fachlichen Gruppen behalten ihr Prüfintervall.
        assertEquals(30, gv.alle(einrichtung, ALLES).stream()
                .filter(g -> g.bezeichnung().equals("Notaufnahme " + einrichtung))
                .findFirst().orElseThrow().pruefintervallSekunden());
    }

    @Test
    @DisplayName("Ist der Name belegt, wird die älteste vorhandene Gruppe zur Vorgabe")
    void aeltesteWirdVorgabeWennNameBelegt() throws Exception {
        // Nur in diesem Fall greift der zweite Anlauf: Der Vorgabename lässt sich
        // nicht anlegen, weil es den Namen schon gibt. Deterministisch über das
        // Anlegedatum, damit nicht die Sortierreihenfolge der Datenbank
        // entscheidet, wo neue Geräte landen.
        String einrichtung = neueEinrichtung();
        assertNull(gv.anlegen(Gruppenverwaltung.vorgabename(einrichtung), einrichtung, 60));
        assertNull(gv.anlegen("Danach angelegt", einrichtung, 60));

        var v = gv.vorgabegruppeAnlegen(einrichtung);
        assertTrue(v.isPresent());
        assertEquals(Gruppenverwaltung.vorgabename(einrichtung), v.get().bezeichnung());
        assertEquals(2, gv.alle(einrichtung, ALLES).size(),
                "Es darf keine dritte Gruppe entstanden sein");
    }

    // ── Die Befunde vom 11.08.2026 ──────────────────────────────────────────

    @Test
    @DisplayName("Löschen verschiebt Geräte in die Vorgabegruppe DER EIGENEN Einrichtung")
    void loeschenBleibtInDerEinrichtung() throws Exception {
        // Der Fehler: Die Oberfläche übergab fest „Praxis" als Einrichtung.
        // Gab es dort eine Vorgabegruppe, wanderten die Geräte einer fremden
        // Praxis dorthin — über die Mandantengrenze hinweg. Gab es keine,
        // scheiterte jedes Löschen mit „Es gibt keine Vorgabegruppe".
        String einrichtung = neueEinrichtung();
        var vorgabe = gv.vorgabegruppeAnlegen(einrichtung).orElseThrow();

        String fachname = "Notaufnahme " + einrichtung;
        assertNull(gv.anlegen(fachname, einrichtung, 30));
        long fach = gv.alle(einrichtung, ALLES).stream()
                .filter(g -> g.bezeichnung().equals(fachname))
                .findFirst().orElseThrow().id();

        String terminal = "KT-" + UUID.randomUUID().toString().substring(0, 8);
        assertTrue(gv.geraetSicherstellen(terminal, einrichtung, "Terminal"));
        assertNull(gv.zuordnen(terminal, fach));

        assertNull(gv.loeschen(fach, ALLES));
        assertEquals(vorgabe.id(), gv.gruppeVon(terminal).orElseThrow(),
                "Das Gerät muss in der Vorgabegruppe SEINER Einrichtung landen");
    }

    @Test
    @DisplayName("Eine Gruppe außerhalb des Geltungsbereichs lässt sich nicht ändern")
    void fremdeGruppeUnantastbar() throws Exception {
        String einrichtung = neueEinrichtung();
        assertNull(gv.anlegen("Fremde Gruppe", einrichtung, 60));
        long id = gv.alle(einrichtung, ALLES).get(0).id();

        // Ein Geltungsbereich, der nichts umfasst — wie bei einem Benutzer
        // einer anderen Einrichtung.
        var nichts = Geltungsbereich.keiner();

        assertNotNull(gv.intervallSetzen(id, 120, nichts),
                "Eine manipulierte Kennung darf kein fremdes Prüfintervall verstellen — "
                + "das hebelt die Überwachung einer fremden Einrichtung aus");
        assertNotNull(gv.umbenennen(id, "Gekapert", nichts));
        assertNotNull(gv.loeschen(id, nichts));

        // Und nichts davon ist passiert.
        var unveraendert = gv.gruppe(id, ALLES).orElseThrow();
        assertEquals(60, unveraendert.pruefintervallSekunden());
        assertEquals("Fremde Gruppe", unveraendert.bezeichnung());
    }

    @Test
    @DisplayName("Eine Gruppe lässt sich umbenennen, ohne ihre Geräte zu verlieren")
    void umbenennenErhaeltZuordnung() throws Exception {
        String einrichtung = neueEinrichtung();
        gv.vorgabegruppeAnlegen(einrichtung);
        String tippfehler = "Notaufnahem " + einrichtung;
        String richtig    = "Notaufnahme " + einrichtung;
        assertNull(gv.anlegen(tippfehler, einrichtung, 30));
        long id = gv.alle(einrichtung, ALLES).stream()
                .filter(g -> g.bezeichnung().equals(tippfehler))
                .findFirst().orElseThrow().id();

        String terminal = "KT-" + UUID.randomUUID().toString().substring(0, 8);
        assertTrue(gv.geraetSicherstellen(terminal, einrichtung, "Terminal"));
        assertNull(gv.zuordnen(terminal, id));

        assertNull(gv.umbenennen(id, richtig, ALLES));

        assertEquals(richtig, gv.gruppe(id, ALLES).orElseThrow().bezeichnung());
        assertEquals(id, gv.gruppeVon(terminal).orElseThrow(),
                "Der Tippfehler kostet keine Zuordnung — vorher musste man löschen "
                + "und neu anlegen, und die Geräte zogen in die Vorgabegruppe um");
    }

    @Test
    @DisplayName("Die Vorgabegruppe trägt die Einrichtung im Namen — der Befund vom 13.08.2026")
    void vorgabenameIstEindeutig() {
        // Vorher hießen in einem MVZ alle neun Vorgabegruppen „Alle Geräte".
        // Ein Bezeichner, dessen Bedeutung von der Nachbarspalte abhängt, ist
        // keiner: In Auswahlfeldern und im Protokoll steht die Spalte nicht.
        assertEquals("Alle Geräte – Praxis Nord",
                Gruppenverwaltung.vorgabename("Praxis Nord"));
        assertNotEquals(Gruppenverwaltung.vorgabename("Praxis Nord"),
                Gruppenverwaltung.vorgabename("Praxis Süd"),
                "Zwei Einrichtungen dürfen nicht denselben Gruppennamen bekommen");
    }

    @Test
    @DisplayName("Eine Bezeichnung, die es anderswo schon gibt, wird abgelehnt")
    void nameInstanzweitEindeutig() throws Exception {
        // Vorgabe PO, 13.08.2026: Zwei Betriebsstätten dürfen nicht beide eine
        // Gruppe „Notaufnahme" haben — in einem Auswahlfeld oder im Protokoll
        // wäre sonst nicht erkennbar, welche gemeint ist.
        String eine = neueEinrichtung(), andere = neueEinrichtung();
        String name = "GV Notaufnahme " + UUID.randomUUID().toString().substring(0, 6);
        assertNull(gv.anlegen(name, eine, 60));

        String fehler = gv.anlegen(name, andere, 60);
        assertNotNull(fehler, "Derselbe Name in einer anderen Einrichtung muss scheitern");
        assertTrue(fehler.contains("ergänzen"), fehler);

        // Groß-/Kleinschreibung zählt nicht als Unterschied.
        assertNotNull(gv.anlegen(name.toLowerCase(), andere, 60));

        // Mit Kennzeichnung geht es.
        assertNull(gv.anlegen(name + " " + andere, andere, 60));
    }

    @Test
    @DisplayName("Die Meldung verrät nicht, in welcher Einrichtung der Name schon vorkommt")
    void meldungVerraetKeinenFremdenBestand() throws Exception {
        // Eine Mandantengrenze, die man mit einem Formular ausprobieren kann,
        // ist keine: Sonst erführe eine Praxis, wie die Gruppen fremder
        // Mandanten heißen.
        String fremde = neueEinrichtung(), eigene = neueEinrichtung();
        String name = "GV Geheim " + UUID.randomUUID().toString().substring(0, 6);
        assertNull(gv.anlegen(name, fremde, 60));

        String fehler = gv.anlegen(name, eigene, 60);
        assertNotNull(fehler);
        assertFalse(fehler.contains(fremde),
                "Die Meldung nennt die fremde Einrichtung: " + fehler);
    }

    @Test
    @DisplayName("Umbenennen auf denselben Namen ist erlaubt")
    void umbenennenAufSichSelbst() throws Exception {
        String e = neueEinrichtung();
        String name = "GV Umbenenntest " + UUID.randomUUID().toString().substring(0, 6);
        assertNull(gv.anlegen(name, e, 60));
        long id = gv.alle(e, ALLES).stream()
                .filter(g -> name.equals(g.bezeichnung())).findFirst().orElseThrow().id();

        assertNull(gv.umbenennen(id, name, ALLES),
                "Die eigene Kennung muss von der Prüfung ausgenommen sein");
        assertNull(gv.umbenennen(id, name.toLowerCase(), ALLES),
                "Auch eine reine Schreibweisenänderung");
    }
}
