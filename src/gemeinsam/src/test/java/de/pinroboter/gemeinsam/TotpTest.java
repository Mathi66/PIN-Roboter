package de.pinroboter.gemeinsam;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Prüft die TOTP-Umsetzung gegen die <b>offiziellen Testvektoren aus RFC 6238,
 * Anhang B</b>.
 *
 * <p>Das ist der Grund, warum der Algorithmus selbst umgesetzt und nicht als
 * Abhängigkeit eingebunden wurde: Gegen einen veröffentlichten Standard lässt
 * sich die eigene Umsetzung beweisen. Stimmen diese Werte, stimmt der
 * Algorithmus — unabhängig davon, wer ihn geschrieben hat.
 */
class TotpTest {

    /** Testschlüssel aus RFC 6238, Anhang B: ASCII "12345678901234567890". */
    private static final byte[] RFC_SCHLUESSEL =
            "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    @Test
    @DisplayName("RFC 6238 Anhang B — alle SHA-1-Testvektoren")
    void rfcTestvektoren() {
        // Zeitpunkt (Unix-Sekunden) -> erwarteter Code.
        // Die Werte im RFC sind achtstellig; wir nutzen die sechs letzten Stellen,
        // weil RFC 6238 sechs Stellen als Regelfall vorsieht.
        long[][] faelle = {
                {59L,          287082L},
                {1111111109L,  81804L},
                {1111111111L,  50471L},
                {1234567890L,  5924L},
                {2000000000L,  279037L},
                {20000000000L, 353130L},
        };
        for (long[] fall : faelle) {
            String erwartet = String.format("%06d", fall[1]);
            String erhalten = Totp.code(RFC_SCHLUESSEL, Instant.ofEpochSecond(fall[0]));
            assertEquals(erwartet, erhalten,
                    "RFC-6238-Vektor für t=" + fall[0] + " stimmt nicht");
        }
    }

    @Test
    @DisplayName("Ein frisch erzeugter Code wird angenommen")
    void frischerCodeWirdAngenommen() {
        byte[] g = Totp.geheimnisErzeugen();
        Instant jetzt = Instant.now();
        assertTrue(Totp.pruefe(g, Totp.code(g, jetzt), jetzt));
    }

    @Test
    @DisplayName("FA-BEN-020: genau ein Zeitfenster Toleranz — nicht mehr")
    void toleranzGenauEinFenster() {
        byte[] g = Totp.geheimnisErzeugen();
        Instant jetzt = Instant.ofEpochSecond(1_700_000_000L);
        int s = Totp.SCHRITT_SEKUNDEN;

        assertTrue(Totp.pruefe(g, Totp.code(g, jetzt.minusSeconds(s)), jetzt),
                "ein Fenster in der Vergangenheit muss toleriert werden");
        assertTrue(Totp.pruefe(g, Totp.code(g, jetzt.plusSeconds(s)), jetzt),
                "ein Fenster in der Zukunft muss toleriert werden");

        assertFalse(Totp.pruefe(g, Totp.code(g, jetzt.minusSeconds(2L * s)), jetzt),
                "zwei Fenster in der Vergangenheit dürfen NICHT mehr gelten");
        assertFalse(Totp.pruefe(g, Totp.code(g, jetzt.plusSeconds(2L * s)), jetzt),
                "zwei Fenster in der Zukunft dürfen NICHT mehr gelten");
    }

    @Test
    @DisplayName("Falsche und unsinnige Eingaben werden abgewiesen")
    void falscheEingaben() {
        byte[] g = Totp.geheimnisErzeugen();
        Instant jetzt = Instant.now();
        assertFalse(Totp.pruefe(g, "000000", jetzt.plusSeconds(9999)));
        assertFalse(Totp.pruefe(g, null, jetzt));
        assertFalse(Totp.pruefe(g, "", jetzt));
        assertFalse(Totp.pruefe(g, "12345", jetzt),   "zu kurz");
        assertFalse(Totp.pruefe(g, "1234567", jetzt), "zu lang");
    }

    @Test
    @DisplayName("Leerzeichen in der Eingabe stören nicht")
    void leerzeichenWerdenIgnoriert() {
        // Authenticator-Apps zeigen Codes als "123 456" an; genau so tippen
        // Nutzer sie ab. Daran darf eine Anmeldung nicht scheitern.
        byte[] g = Totp.geheimnisErzeugen();
        Instant jetzt = Instant.now();
        String code = Totp.code(g, jetzt);
        String mitLuecke = code.substring(0, 3) + " " + code.substring(3);
        assertTrue(Totp.pruefe(g, mitLuecke, jetzt));
    }

    @Test
    @DisplayName("Base32 ist umkehrbar und entspricht RFC 4648")
    void base32() {
        assertEquals("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", Totp.alsBase32(RFC_SCHLUESSEL));
        byte[] g = Totp.geheimnisErzeugen();
        assertArrayEquals(g, Totp.ausBase32(Totp.alsBase32(g)));
    }

    @Test
    @DisplayName("Einrichtungs-URI enthält alles, was eine App braucht")
    void einrichtungsUri() {
        byte[] g = Totp.geheimnisErzeugen();
        String uri = Totp.einrichtungsUri("anna.beispiel", g, "PIN-Roboter");
        assertTrue(uri.startsWith("otpauth://totp/"));
        assertTrue(uri.contains("secret=" + Totp.alsBase32(g)));
        assertTrue(uri.contains("digits=6"));
        assertTrue(uri.contains("period=30"));
    }

    @Test
    @DisplayName("Das erzeugte Geheimnis hat die von RFC 4226 empfohlene Länge")
    void geheimnisLaenge() {
        assertEquals(20, Totp.geheimnisErzeugen().length, "160 Bit");
        assertFalse(java.util.Arrays.equals(
                Totp.geheimnisErzeugen(), Totp.geheimnisErzeugen()),
                "zwei Geheimnisse dürfen nie gleich sein");
    }
}
