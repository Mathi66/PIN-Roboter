package de.pinroboter.zentrale;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Das Handbuch in der Oberfläche (FA-DOK-010, PO 13.08.2026).
 *
 * <h2>Was hier geprüft wird — und was nicht</h2>
 *
 * Ob ein Text gut geschrieben ist, kann kein Test beurteilen. Prüfbar ist die
 * <b>Mechanik drumherum</b>: dass die Suche findet, was da ist, dass kein
 * Kapitel ins Leere zeigt, und dass das erzeugte HTML unversehrt ist.
 *
 * <p>Der letzte Punkt hat einen konkreten Anlass. Am 13.08.2026 ist mir
 * dreimal dasselbe passiert: Ein deutsches Zitat mit typografischem Anfang und
 * <em>geradem</em> Ende — {@code „Wort"} — schließt in Java einen String und
 * in HTML ein Attribut. Im Fließtext ist es nur hässlich, in einem Attribut
 * zerlegt es die Seite. Beides prüft dieser Test, weil ich diesen Fehler
 * offenbar wieder mache.
 */
class HandbuchTest {

    @Test
    @DisplayName("Jedes Kapitel hat eine eindeutige Kennung und Inhalt")
    void grundgeruest() {
        var kennungen = new HashSet<String>();
        for (var k : Handbuch.alle()) {
            assertTrue(kennungen.add(k.kennung()),
                    "Kennung doppelt: " + k.kennung() + " — die Adresse wäre mehrdeutig");
            assertFalse(k.titel().isBlank(), k.kennung());
            assertFalse(k.kurz().isBlank(), k.kennung() + " hat keine Kurzbeschreibung");
            assertTrue(k.inhalt().length() > 400,
                    k.kennung() + " ist zu dünn für ein Kapitel");
            assertNotNull(k.teil());
        }
        assertTrue(Handbuch.alle().size() >= 12, "zu wenige Kapitel");
    }

    @Test
    @DisplayName("Alle drei Teile sind besetzt")
    void teile() {
        for (var t : Handbuch.Teil.values()) {
            assertFalse(Handbuch.desTeils(t).isEmpty(),
                    "Ein leerer Abschnitt im Verzeichnis sieht aus wie ein Fehler: " + t);
        }
    }

    @Test
    @DisplayName("Kein unaufgelöster Platzhalter im Text")
    void keinePlatzhalter() {
        // Die Grafiken werden über String.formatted eingesetzt. Ein
        // vergessenes Argument stünde als „%s" mitten im Text.
        for (var k : Handbuch.alle()) {
            assertFalse(k.inhalt().contains("%s"),
                    k.kennung() + " enthält einen unaufgelösten Platzhalter");
        }
    }

    @Test
    @DisplayName("Typografische Anführungszeichen sind paarweise geschlossen")
    void anfuehrungszeichen() {
        // Der Fehler, den ich an diesem Tag dreimal gemacht habe: „Wort" statt
        // „Wort“. In einem HTML-Attribut zerlegt das die Seite.
        for (var k : Handbuch.alle()) {
            long auf = k.inhalt().chars().filter(c -> c == '„').count();
            long zu  = k.inhalt().chars().filter(c -> c == '“').count();
            assertEquals(auf, zu, k.kennung() + ": " + auf + " öffnende, " + zu
                    + " schließende Anführungszeichen — ein „Wort\" mit geradem "
                    + "Schlusszeichen bricht in einem Attribut das HTML");
        }
    }

    @Test
    @DisplayName("Die HTML-Elemente sind ausgeglichen")
    void htmlUnversehrt() {
        // Kein vollständiger Parser — aber ein offenes <div> oder <table>
        // verschiebt den Rest der Seite, und genau das fällt beim Schreiben
        // langer Texte nicht auf.
        for (var k : Handbuch.alle()) {
            for (String tag : new String[]{"div", "p", "table", "tbody", "tr", "td",
                                           "ul", "ol", "li", "dl", "figure", "svg",
                                           "section", "details"}) {
                long auf = zaehle(k.inhalt(), "<" + tag + " ") + zaehle(k.inhalt(), "<" + tag + ">");
                long zu = zaehle(k.inhalt(), "</" + tag + ">");
                assertEquals(auf, zu, k.kennung() + ": <" + tag + "> "
                        + auf + "× geöffnet, " + zu + "× geschlossen");
            }
        }
    }

    @Test
    @DisplayName("Jede Zeichnung ist für Screenreader beschrieben")
    void grafikenBeschrieben() {
        for (var k : Handbuch.alle()) {
            int svgs = (int) zaehle(k.inhalt(), "<svg ");
            if (svgs == 0) continue;
            assertEquals(svgs, zaehle(k.inhalt(), "role=\"img\""),
                    k.kennung() + ": nicht jede Zeichnung trägt role=\"img\"");
            assertEquals(svgs, zaehle(k.inhalt(), "aria-label=\""),
                    k.kennung() + ": eine Zeichnung ohne Beschreibung ist für einen "
                    + "Screenreader nicht vorhanden");
            assertEquals(svgs, zaehle(k.inhalt(), "<figcaption>"),
                    k.kennung() + ": jede Zeichnung braucht eine Bildunterschrift");
        }
    }

    @Test
    @DisplayName("Interne Verweise zeigen auf vorhandene Kapitel")
    void verweiseGehenNichtInsLeere() {
        var m = java.util.regex.Pattern.compile("href=\"/handbuch/([a-z-]+)\"");
        for (var k : Handbuch.alle()) {
            var treffer = m.matcher(k.inhalt());
            while (treffer.find()) {
                String ziel = treffer.group(1);
                if (ziel.equals("suche")) continue;
                assertNotNull(Handbuch.finden(ziel),
                        k.kennung() + " verweist auf „" + ziel + "“ — das gibt es nicht");
            }
        }
    }

    @Test
    @DisplayName("Die Suche findet und ordnet nach Bedeutung")
    void suche() {
        var treffer = Handbuch.suchen("Fehlversuch");
        assertFalse(treffer.isEmpty());
        for (var t : treffer) {
            assertFalse(t.auszug().isBlank(),
                    "Eine Trefferliste ohne Kontext zwingt dazu, jeden Eintrag zu öffnen");
        }

        // Ein Treffer im Titel wiegt schwerer als einer im Fließtext.
        var nachTitel = Handbuch.suchen("Datensicherung");
        assertEquals("sicherung", nachTitel.get(0).kapitel().kennung(),
                "Das Kapitel, das so heißt, gehört nach oben");
    }

    @Test
    @DisplayName("Der Auszug enthält den gesuchten Begriff")
    void auszugZeigtDieFundstelle() {
        for (var t : Handbuch.suchen("PUK")) {
            if (t.kapitel().titel().toLowerCase().contains("puk")) continue;
            assertTrue(t.auszug().toLowerCase().contains("puk"),
                    t.kapitel().kennung() + ": der Auszug zeigt nicht die Fundstelle: "
                    + t.auszug());
        }
    }

    @Test
    @DisplayName("Zu kurze oder leere Eingaben suchen nicht")
    void keineSinnloseSuche() {
        // Ein einzelner Buchstabe fände jedes Kapitel — das ist keine Antwort,
        // sondern eine Liste.
        assertTrue(Handbuch.suchen("").isEmpty());
        assertTrue(Handbuch.suchen(null).isEmpty());
        assertTrue(Handbuch.suchen("a").isEmpty());
        assertTrue(Handbuch.suchen("   ").isEmpty());
    }

    @Test
    @DisplayName("Die Suche ignoriert Groß- und Kleinschreibung")
    void schreibweiseEgal() {
        assertEquals(Handbuch.suchen("konnektor").size(), Handbuch.suchen("KONNEKTOR").size());
    }

    @Test
    @DisplayName("Was es nicht gibt, wird nicht gefunden")
    void nichtsGefunden() {
        assertTrue(Handbuch.suchen("Kaffeemaschine").isEmpty());
    }

    @Test
    @DisplayName("Das Blättern endet an den Rändern statt umzuspringen")
    void blaettern() {
        var erstes = Handbuch.alle().get(0);
        var letztes = Handbuch.alle().get(Handbuch.alle().size() - 1);
        assertNull(Handbuch.nachbar(erstes.kennung(), -1));
        assertNull(Handbuch.nachbar(letztes.kennung(), 1));
        assertNotNull(Handbuch.nachbar(erstes.kennung(), 1));
        assertNull(Handbuch.nachbar("gibt-es-nicht", 1));
    }

    @Test
    @DisplayName("Die Trefferanzeige escapet, was aus der Eingabe kommt")
    void keineEinschleusung() {
        // Der Suchbegriff landet in der Ausgabe. Ohne Escaping wäre das eine
        // Einschleusungsstelle — auch wenn hier nur der eigene Text steht.
        String html = Handbuchseiten.treffer("<script>alarm()</script>", java.util.List.of());
        assertFalse(html.contains("<script>"), html);
        assertTrue(html.contains("&lt;script&gt;"));
    }

    @Test
    @DisplayName("Die Hervorhebung behält die Schreibweise des Fundes")
    void hervorhebung() {
        String html = Handbuchseiten.treffer("pin", Handbuch.suchen("pin"));
        assertTrue(html.contains("<mark>"), "nichts hervorgehoben");
        // „PIN" soll nicht als „pin" erscheinen, nur weil klein gesucht wurde.
        assertTrue(html.contains("<mark>PIN</mark>"),
                "Die Schreibweise im Text muss erhalten bleiben");
    }

    // ─────────────────────────────────── Hilfe aus dem Handbuch (FA-DOK-020)

    @Test
    @DisplayName("Jeder Info-Knopf im Code findet seinen Abschnitt im Handbuch")
    void keinInfoKnopfInsLeere() throws Exception {
        // Der wichtigste Test dieser Funktion. Ein Info-Knopf, der ins Leere
        // führt, ist schlimmer als keiner: Wer ihn drückt, hat eine Frage.
        // Gelesen wird der Quelltext, weil die Kennungen dort stehen — eine
        // Liste von Konstanten wäre eine zweite Pflegestelle und damit genau
        // das, was diese Funktion abschaffen soll.
        var quellen = java.nio.file.Path.of("src/main/java/de/pinroboter/zentrale");
        var muster = java.util.regex.Pattern.compile(
                "Bedienelemente\\.info\\(\"([a-z0-9-]+)\"");
        var fehlend = new java.util.TreeMap<String, String>();
        int gefunden = 0;

        try (var dateien = java.nio.file.Files.walk(quellen)) {
            for (var d : dateien.filter(f -> f.toString().endsWith(".java")).toList()) {
                String text = java.nio.file.Files.readString(d);
                var t = muster.matcher(text);
                while (t.find()) {
                    gefunden++;
                    if (Handbuch.hilfe(t.group(1)) == null) {
                        fehlend.put(t.group(1), d.getFileName().toString());
                    }
                }
            }
        }
        assertTrue(gefunden > 0, "Kein einziger Info-Knopf gefunden — stimmt der Pfad?");
        assertTrue(fehlend.isEmpty(),
                "Diese Info-Knöpfe zeigen ins Leere: " + fehlend);
    }

    @Test
    @DisplayName("Jeder Abschnitt liefert Inhalt, Titel und Kapitelbezug")
    void abschnitteVollstaendig() {
        var kennungen = Handbuch.hilfekennungen();
        assertFalse(kennungen.isEmpty(), "Es ist kein Abschnitt markiert");
        for (String k : kennungen) {
            var h = Handbuch.hilfe(k);
            assertNotNull(h, k);
            assertTrue(h.inhalt().length() > 80,
                    k + " ist zu kurz für eine Erklärung: " + h.inhalt());
            assertNotNull(h.kapitel(), k);
            assertNotNull(h.titel(),
                    k + " steht unter keiner Überschrift — dann fehlt dem Fenster "
                    + "der Titel");
            assertFalse(h.inhalt().contains("<section"),
                    k + " enthält einen verschachtelten Abschnitt; die Extraktion "
                    + "schneidet dann am falschen </section> ab");
        }
    }

    @Test
    @DisplayName("Eine unbekannte Kennung liefert nichts statt irgendetwas")
    void unbekannteKennung() {
        assertNull(Handbuch.hilfe("gibt-es-nicht"));
        assertNull(Handbuch.hilfe(""));
        assertNull(Handbuch.hilfe(null));
    }

    @Test
    @DisplayName("Das Hilfefenster nennt Herkunft und führt ins Kapitel")
    void hilfefenster() {
        var h = Handbuch.hilfe(Handbuch.hilfekennungen().iterator().next());
        String html = Handbuchseiten.hilfe(h);
        assertTrue(html.contains("hilfefenster-titel"),
                "Ohne diese Kennung kann der Dialog nicht beschriftet werden");
        assertTrue(html.contains("/handbuch/" + h.kapitel().kennung()),
                "Eine Kurzhilfe muss ins volle Kapitel führen — manchmal ist die "
                + "eigentliche Frage eine größere");
        assertTrue(html.contains("hilfe-zu"), "Es fehlt der Schließen-Knopf");
    }

    @Test
    @DisplayName("Ein Info-Knopf ist auch ohne JavaScript ein Weg ins Handbuch")
    void infoOhneJavaScript() {
        String html = Bedienelemente.info("programmablage", "die Programmablage");
        assertTrue(html.contains("href=\"/handbuch/hilfe/programmablage\""),
                "Ohne echtes href hinge die Erklärung an einer Browsereinstellung");
        assertTrue(html.contains("aria-label="),
                "Ein Fragezeichen ohne Beschriftung ist für einen Screenreader stumm");
    }

    @Test
    @DisplayName("Die Seite trägt Verzeichnis und Inhalt")
    void seitengeruest() {
        String html = Handbuchseiten.seite(Handbuch.erstes(), null);
        assertTrue(html.contains("hb-verzeichnis"));
        assertTrue(html.contains("id=\"hb-inhalt\""),
                "Ohne dieses Ziel könnte die Suche den Ausschnitt nicht tauschen");
        assertTrue(html.contains("aria-current=\"page\""),
                "Das offene Kapitel muss auch für einen Screenreader erkennbar sein");
        for (var k : Handbuch.alle()) {
            assertTrue(html.contains("/handbuch/" + k.kennung()),
                    "Im Verzeichnis fehlt: " + k.kennung());
        }
    }

    private static long zaehle(String text, String muster) {
        long n = 0;
        for (int i = text.indexOf(muster); i >= 0; i = text.indexOf(muster, i + 1)) n++;
        return n;
    }
}
