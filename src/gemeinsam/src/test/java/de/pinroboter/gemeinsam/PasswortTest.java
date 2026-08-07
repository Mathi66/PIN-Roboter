package de.pinroboter.gemeinsam;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PasswortTest {

    @Test
    @DisplayName("Richtiges Passwort wird erkannt, falsches abgewiesen")
    void hashenUndPruefen() {
        String h = Passwort.hashen("KorrektesPferdBatterieHeftklammer".toCharArray());
        assertTrue(Passwort.pruefen("KorrektesPferdBatterieHeftklammer".toCharArray(), h));
        assertFalse(Passwort.pruefen("korrektesPferdBatterieHeftklammer".toCharArray(), h),
                "Groß- und Kleinschreibung muss unterschieden werden");
        assertFalse(Passwort.pruefen("etwasAnderes".toCharArray(), h));
    }

    @Test
    @DisplayName("NFA-SIC-010: Der Hash enthält das Passwort nicht im Klartext")
    void hashEnthaeltKeinKlartext() {
        String geheim = "MeinGeheimesPasswort2026";
        String h = Passwort.hashen(geheim.toCharArray());
        assertFalse(h.contains(geheim));
        assertFalse(h.toLowerCase().contains(geheim.toLowerCase()));
    }

    @Test
    @DisplayName("Gleiches Passwort ergibt nie denselben Hash (Salz)")
    void salzWirktZufaellig() {
        char[] p = "GleichesPasswortZweimal".toCharArray();
        assertNotEquals(Passwort.hashen(p), Passwort.hashen(p.clone()),
                "Ohne unterschiedliches Salz wären Rainbow-Tabellen möglich");
    }

    @Test
    @DisplayName("Der Hash beschreibt seine eigenen Parameter")
    void formatIstSelbstbeschreibend() {
        String h = Passwort.hashen("EinPasswortMitLaenge".toCharArray());
        assertTrue(h.startsWith("$argon2id$v=19$"), "erhalten: " + h);
        assertTrue(h.contains("m=") && h.contains("t=") && h.contains("p="));
    }

    @Test
    @DisplayName("Beschädigte oder fremde Hashes führen nie zu einer Anmeldung")
    void unsinnWirdAbgewiesen() {
        char[] p = "beliebig".toCharArray();
        assertFalse(Passwort.pruefen(p, null));
        assertFalse(Passwort.pruefen(p, ""));
        assertFalse(Passwort.pruefen(p, "kein-hash"));
        assertFalse(Passwort.pruefen(p, "$argon2id$kaputt"));
        assertFalse(Passwort.pruefen(p, "$md5$v=19$m=1,t=1,p=1$AAAA$BBBB"));
        assertFalse(Passwort.pruefen(null, Passwort.hashen(p)));
    }

    @Test
    @DisplayName("Zu kurze und sehr häufige Passwörter werden abgelehnt")
    void staerkepruefung() {
        assertNotNull(Passwort.pruefeStaerke("kurz".toCharArray()));
        assertNotNull(Passwort.pruefeStaerke("elfZeichen1".toCharArray()));
        assertNotNull(Passwort.pruefeStaerke("meinPasswort123".toCharArray()),
                "enthält eine sehr häufige Zeichenfolge");
        assertNull(Passwort.pruefeStaerke("ZwoelfZeichen".toCharArray()));
        assertNull(Passwort.pruefeStaerke("Drei Woerter Reichen".toCharArray()));
    }

    @Test
    @DisplayName("Die Meldung sagt, was zu tun ist (NFA-USA-020)")
    void meldungIstHandlungsanweisung() {
        String m = Passwort.pruefeStaerke("kurz".toCharArray());
        assertNotNull(m);
        assertTrue(m.contains("12 Zeichen"), "Die Mindestlänge muss genannt werden");
        assertTrue(m.length() > 40, "Ein bloßes 'zu kurz' hilft niemandem weiter");
    }
}
