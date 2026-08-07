package de.pinroboter.gemeinsam;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Prüft die verschlüsselte Ablage von Zugangsdaten.
 *
 * <p>Hier hängt eine Sicherheitszusage dran: „nach dem Speichern nicht mehr
 * auslesbar" (FA-GER-010). Diese Tests prüfen, dass die Zusage nicht an einer
 * Kleinigkeit scheitert — etwa an wiederverwendeten Nonces, die AES-GCM
 * vollständig brechen würden.
 */
class GeheimnisspeicherTest {

    private final Geheimnisspeicher tresor =
            Geheimnisspeicher.ausBase64(Geheimnisspeicher.neuerSchluesselBase64());

    @Test
    @DisplayName("Was hineingeht, kommt unverändert wieder heraus")
    void hinUndZurueck() {
        String klar = "gehe1m! mit Umlauten äöüß und Leerzeichen";
        String chiffrat = tresor.verschluesseln(klar);
        assertNotEquals(klar, chiffrat);
        assertFalse(chiffrat.contains("gehe1m"), "der Klartext darf nirgends durchscheinen");
        assertEquals(klar, tresor.entschluesseln(chiffrat));
    }

    @Test
    @DisplayName("Derselbe Wert ergibt nie zweimal dasselbe Chiffrat")
    void nonceIstJedesMalNeu() {
        // Ein wiederverwendeter Nonce bricht AES-GCM vollstaendig. Gleiche
        // Chiffrate wuerden ausserdem verraten, dass zwei Geraete dasselbe
        // Kennwort haben - schon das ist eine Information zu viel.
        String a = tresor.verschluesseln("dasselbe Kennwort");
        String b = tresor.verschluesseln("dasselbe Kennwort");
        assertNotEquals(a, b);
        assertEquals("dasselbe Kennwort", tresor.entschluesseln(a));
        assertEquals("dasselbe Kennwort", tresor.entschluesseln(b));
    }

    @Test
    @DisplayName("Ein verändertes Chiffrat wird erkannt und nicht stillschweigend hingenommen")
    void manipulationFliegtAuf() {
        String c = tresor.verschluesseln("Kennwort");
        // Ein einzelnes Zeichen im Base64-Teil kippen.
        char[] z = c.toCharArray();
        int i = z.length - 3;
        z[i] = z[i] == 'A' ? 'B' : 'A';
        String verbogen = new String(z);

        var e = assertThrows(IllegalStateException.class, () -> tresor.entschluesseln(verbogen));
        assertTrue(e.getMessage().contains("neu hinterlegen"),
                "die Meldung muss sagen, was zu tun ist: " + e.getMessage());
    }

    @Test
    @DisplayName("Ein fremder Schlüssel entschlüsselt nicht")
    void fremderSchluessel() {
        String c = tresor.verschluesseln("Kennwort");
        var anderer = Geheimnisspeicher.ausBase64(Geheimnisspeicher.neuerSchluesselBase64());
        assertThrows(IllegalStateException.class, () -> anderer.entschluesseln(c));
    }

    @Test
    @DisplayName("Leere Eingaben bleiben leer statt zu einem Chiffrat zu werden")
    void leeresBleibtLeer() {
        // Sonst waere „kein Kennwort hinterlegt" nicht mehr von „leeres
        // Kennwort hinterlegt" unterscheidbar, und die Oberflaeche zeigte
        // faelschlich einen gespeicherten Zugang an.
        assertNull(tresor.verschluesseln(null));
        assertNull(tresor.verschluesseln(""));
        assertNull(tresor.entschluesseln(null));
        assertNull(tresor.entschluesseln("   "));
    }

    @Test
    @DisplayName("Ein untauglicher Schlüssel wird abgelehnt statt notdürftig verwendet")
    void schluesselPruefung() {
        assertNull(Geheimnisspeicher.ausBase64(null));
        assertNull(Geheimnisspeicher.ausBase64(""));
        assertNull(Geheimnisspeicher.ausBase64("kein gültiges Base64 !!!"));
        assertNull(Geheimnisspeicher.ausBase64(
                Base64.getEncoder().encodeToString(new byte[16])), "16 Byte sind zu kurz");
        assertNotNull(Geheimnisspeicher.ausBase64(
                Base64.getEncoder().encodeToString(new byte[32])));
    }

    @Test
    @DisplayName("Ein Chiffrat aus einem unbekannten Format wird klar gemeldet")
    void unbekanntesFormat() {
        var e = assertThrows(IllegalStateException.class,
                () -> tresor.entschluesseln("irgendetwas ohne Marke"));
        assertTrue(e.getMessage().contains("neu hinterlegt"), e.getMessage());
    }

    @Test
    @DisplayName("Erzeugte Schlüssel haben die richtige Länge und wiederholen sich nicht")
    void schluesselErzeugung() {
        String a = Geheimnisspeicher.neuerSchluesselBase64();
        String b = Geheimnisspeicher.neuerSchluesselBase64();
        assertNotEquals(a, b);
        assertEquals(32, Base64.getDecoder().decode(a).length);
    }
}
