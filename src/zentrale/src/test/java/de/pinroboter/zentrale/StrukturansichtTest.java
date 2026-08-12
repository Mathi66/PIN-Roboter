package de.pinroboter.zentrale;

import de.pinroboter.gemeinsam.Ampel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Die Rechenlogik der Strukturansicht.
 *
 * <p>Ohne Datenbank: Was hier schiefgehen kann, ist die Summierung über den
 * Baum und die Bewertung eines Teilbaums — beides reine Rechnung. Die
 * Darstellung selbst prüfen die Systemnachweise am laufenden Stack.
 */
class StrukturansichtTest {

    /**
     * Ein Aufbau wie im MVZ:
     * <pre>
     * Träger
     *  ├── Standort Nord
     *  │    ├── Praxis A   (2 Geräte, eines im Fehler)
     *  │    └── Praxis B   (1 Gerät, in Ordnung)
     *  └── Standort Süd
     *       └── Praxis C   (1 Gerät, Warnung)
     *            └── Fachbereich (1 Gerät, in Ordnung)
     * </pre>
     */
    private static final long TRAEGER = 1, NORD = 2, SUED = 3, A = 4, B = 5, C = 6, FB = 7;

    private static List<Organisationsverwaltung.Einheit> baum() {
        // Tiefensuchreihenfolge — so liefert sie Organisationsverwaltung.baum().
        return List.of(
                einheit(TRAEGER, "Träger", "VERBUND", null, 0),
                einheit(NORD, "Nord", "STANDORT", TRAEGER, 1),
                einheit(A, "Praxis A", "EINRICHTUNG", NORD, 2),
                einheit(B, "Praxis B", "EINRICHTUNG", NORD, 2),
                einheit(SUED, "Süd", "STANDORT", TRAEGER, 1),
                einheit(C, "Praxis C", "EINRICHTUNG", SUED, 2),
                einheit(FB, "Fachbereich", "FACHBEREICH", C, 3));
    }

    @Test
    @DisplayName("Jede Einheit bekommt, was in ihrem Teilbaum steckt")
    void summeUeberDenTeilbaum() {
        var eigene = Map.of(
                A,  bilanz(2, 1, 0, 1),      // 2 Geräte: 1 ok, 1 Fehler
                B,  bilanz(1, 1, 0, 0),
                C,  bilanz(1, 0, 1, 0),      // 1 Gerät mit Warnung
                FB, bilanz(1, 1, 0, 0));

        var summe = Strukturansicht.summieren(baum(), eigene);

        assertEquals(3, summe.get(NORD).terminals(), "Praxis A und B");
        assertEquals(2, summe.get(C).terminals(), "die Praxis selbst und ihr Fachbereich");
        assertEquals(2, summe.get(SUED).terminals());
        assertEquals(5, summe.get(TRAEGER).terminals(), "alles zusammen");
        assertEquals(1, summe.get(FB).terminals(), "ein Blatt trägt nur sich selbst");
    }

    @Test
    @DisplayName("Ein einziges ausgefallenes Gerät färbt den ganzen Ast")
    void schlechtesterZustandGewinnt() {
        var summe = Strukturansicht.summieren(baum(), Map.of(
                A, bilanz(2, 1, 0, 1),
                B, bilanz(1, 1, 0, 0)));

        assertEquals(Ampel.FEHLER, summe.get(NORD).ampel(),
                "Ein Mittelwert würde 99 gesunde Geräte über ein ausgefallenes legen");
        assertEquals(Ampel.FEHLER, summe.get(TRAEGER).ampel(), "und bis nach oben durchschlagen");
        assertEquals(Ampel.OK, summe.get(B).ampel(), "der gesunde Nachbar bleibt grün");
    }

    @Test
    @DisplayName("Warnung schlägt in Ordnung, Fehler schlägt Warnung")
    void rangfolgeDerAmpel() {
        assertEquals(Ampel.OK,      bilanz(1, 1, 0, 0).ampel());
        assertEquals(Ampel.WARNUNG, bilanz(2, 1, 1, 0).ampel());
        assertEquals(Ampel.FEHLER,  bilanz(3, 1, 1, 1).ampel());
    }

    @Test
    @DisplayName("Ohne Geräte ist der Zustand unbekannt, nicht in Ordnung")
    void leerIstNichtGut() {
        assertEquals(Ampel.UNBEKANNT, Strukturansicht.Bilanz.LEER.ampel(),
                "„noch nie gesehen“ ist etwas anderes als „geprüft und gut“");
        assertEquals("keine Kartenterminals", Strukturansicht.Bilanz.LEER.klartext());
    }

    @Test
    @DisplayName("Der Klartext nennt Zahlen, nicht nur Farben")
    void klartextFuerScreenreader() {
        String t = bilanz(4, 2, 1, 1).klartext();
        assertTrue(t.contains("4 Kartenterminal"), t);
        assertTrue(t.contains("1 mit Handlungsbedarf"), t);
        assertTrue(t.contains("2 in Ordnung"), t);
    }

    @Test
    @DisplayName("Eine Einheit ohne Elternteil bleibt für sich")
    void wurzelOhneElternteil() {
        var summe = Strukturansicht.summieren(
                List.of(einheit(TRAEGER, "Allein", "VERBUND", null, 0)),
                Map.of(TRAEGER, bilanz(1, 1, 0, 0)));
        assertEquals(1, summe.get(TRAEGER).terminals());
    }

    // ------------------------------------------------------------- Kleinteile

    private static Strukturansicht.Bilanz bilanz(int terminals, int ok, int warnung, int fehler) {
        return new Strukturansicht.Bilanz(terminals, 0, 0, ok, warnung, fehler, 0);
    }

    private static Organisationsverwaltung.Einheit einheit(long id, String name, String art,
                                                           Long eltern, int tiefe) {
        return new Organisationsverwaltung.Einheit(id, name, art, eltern, tiefe,
                false, 0, 0, 0, 0, null, null);
    }
}
