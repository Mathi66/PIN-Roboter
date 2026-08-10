package de.pinroboter.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Die Verbindungsverwaltung zur Remote SMCB API (ADR-0011).
 *
 * <p>Geprüft wird das Verhalten <b>ohne</b> Gegenstelle — also der Fall, den es
 * im Feld am häufigsten geben wird: ein Terminal, das die Schnittstelle nicht
 * anbietet oder bei dem sie nicht freigeschaltet ist. Der Durchstich mit
 * Gegenstelle steht in {@code pruefungen/smcb-durchstich.sh}; hier geht es um
 * das, was eine laufende Anlage schützt.
 */
class SmcbVerbindungenTest {

    /** Eine Verwaltung, die nie einen Schlüssel bekommt — und die Versuche zählt. */
    private static class OhneGegenstelle extends SmcbVerbindungen {
        final AtomicInteger versuche = new AtomicInteger();

        OhneGegenstelle(Kartenkenntnis k) {
            super(HttpClient.newHttpClient(), URI.create("https://terminal.invalid"),
                  URI.create("https://terminal.invalid"), k, null);
        }

        @Override String schluesselHolen(String terminalId) {
            versuche.incrementAndGet();
            return null;   // Gerät ohne Remote SMCB API
        }
    }

    /** Eine Kartenkenntnis, die alles kennt und nichts verbietet. */
    private static Kartenkenntnisse alleserlaubt() {
        return new Kartenkenntnisse();
    }

    private static class Kartenkenntnisse implements SmcbVerbindungen.Kartenkenntnis {
        final List<String> gefragteSlots = new CopyOnWriteArrayList<>();
        String bekannterSlot = "KT-1/Slot1";
        String hindernis = null;

        @Override public String kartenhandle(String terminalId, String slot) {
            gefragteSlots.add(slot);
            return (terminalId + "/" + slot).equals(bekannterSlot) ? "karte-1" : null;
        }
        @Override public char[] pin(String kartenhandle) { return "123456".toCharArray(); }
        @Override public String hindernis(String terminalId, String kartenhandle) { return hindernis; }
    }

    @Test
    @DisplayName("Ein Gerät ohne Remote SMCB API wird nicht im Takt bombardiert")
    void keineWiederholungImTakt() {
        var v = new OhneGegenstelle(alleserlaubt());

        // Zehn Überwachungsdurchgänge in Folge — so oft käme abgleichen() bei
        // einem 6-Sekunden-Takt in einer Minute vorbei.
        for (int i = 0; i < 10; i++) v.abgleichen(Set.of("KT-1"));

        assertEquals(1, v.versuche.get(),
                "Nach einem Fehlschlag darf erst nach " + SmcbVerbindungen.WARTEZEIT_NACH_FEHLSCHLAG
                + " erneut versucht werden. Bei 5.000 Geräten wäre alles andere eine Lastquelle.");
        v.close();
    }

    @Test
    @DisplayName("Ein Terminal ohne angemeldete Verbindung gilt nicht als verbunden")
    void nichtVerbunden() {
        var v = new OhneGegenstelle(alleserlaubt());
        v.abgleichen(Set.of("KT-1"));
        assertFalse(v.angemeldet("KT-1"));
        assertEquals(0, v.anzahlAngemeldet());
        v.close();
    }

    @Test
    @DisplayName("Jedes Gerät bekommt seinen eigenen Versuch")
    void jedesGeraetEinzeln() {
        var v = new OhneGegenstelle(alleserlaubt());
        v.abgleichen(Set.of("KT-1"));
        v.abgleichen(Set.of("KT-2"));
        assertEquals(2, v.versuche.get(), "KT-2 muss einen eigenen Versuch bekommen");
        v.close();
    }

    @Test
    @DisplayName("Ein flatterndes Gerät kann die Sperrfrist nicht umgehen")
    void flatternUmgehtDieSperrfristNicht() {
        var v = new OhneGegenstelle(alleserlaubt());
        v.abgleichen(Set.of("KT-1"));
        assertEquals(1, v.versuche.get());

        // Gerät fällt aus dem Sollzustand und kommt sofort wieder — etwa weil
        // ein wackeliges Netz es abwechselnd erreichbar meldet. Ohne diese
        // Regel entstünde daraus ein Dauerfeuer von Aufbauversuchen.
        for (int i = 0; i < 5; i++) {
            v.abgleichen(Set.of());
            v.abgleichen(Set.of("KT-1"));
        }
        assertEquals(1, v.versuche.get(),
                "Die Sperrfrist gilt weiter, auch wenn das Gerät zwischendurch "
                + "aus dem Sollzustand fällt.");
        v.close();
    }

    @Test
    @DisplayName("Zweimal derselbe Wunsch ergibt keinen zweiten Aufbau")
    void keinDoppelaufbau() {
        var v = new OhneGegenstelle(alleserlaubt());
        v.abgleichen(Set.of("KT-1"));
        v.abgleichen(Set.of("KT-1"));
        assertEquals(1, v.versuche.get());
        v.close();
    }
}
