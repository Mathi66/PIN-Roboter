package de.pinroboter.agent;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.security.GeneralSecurityException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Prüft die Ende-zu-Ende-Übergabe der PIN (ADR-0009, FA-PIN-010).
 *
 * <p>Der zentrale Punkt dieser Tests: <b>Nirgends darf die PIN im Klartext
 * liegen</b> — nicht in der Schlüsseldatei, nicht in der Ablage. Das wird hier
 * nicht behauptet, sondern in den Dateien nachgesehen.
 */
class PinUebergabeTest {

    @TempDir Path verzeichnis;

    @Test
    @DisplayName("Was der Browser verschlüsselt, entschlüsselt nur der Agent")
    void rundlauf() throws Exception {
        var paar = Schluesselpaar.ladenOderErzeugen(verzeichnis.resolve("s"));
        String chiffrat = paar.verschluesseln("123456");
        assertArrayEquals("123456".toCharArray(), paar.entschluesseln(chiffrat));
    }

    @Test
    @DisplayName("Der öffentliche Schlüssel ist SPKI — genau das erwartet WebCrypto")
    void spki() throws Exception {
        var paar = Schluesselpaar.ladenOderErzeugen(verzeichnis.resolve("s"));
        byte[] roh = java.util.Base64.getDecoder().decode(paar.oeffentlichBase64());
        // Ein SPKI-Block laesst sich als X509EncodedKeySpec einlesen; das ist
        // dieselbe Kodierung, die crypto.subtle.importKey("spki", ...) verlangt.
        var k = java.security.KeyFactory.getInstance("RSA")
                .generatePublic(new java.security.spec.X509EncodedKeySpec(roh));
        assertEquals(3072, ((java.security.interfaces.RSAPublicKey) k).getModulus().bitLength());
    }

    @Test
    @DisplayName("MGF1 verwendet SHA-256 — nicht Javas stillschweigendes SHA-1")
    void mgf1IstSha256() throws Exception {
        var paar = Schluesselpaar.ladenOderErzeugen(verzeichnis.resolve("s"));
        byte[] spki = java.util.Base64.getDecoder().decode(paar.oeffentlichBase64());
        var pub = java.security.KeyFactory.getInstance("RSA")
                .generatePublic(new java.security.spec.X509EncodedKeySpec(spki));

        // So verschluesselt WebCrypto: SHA-256 fuer Nachricht UND MGF1.
        var c = javax.crypto.Cipher.getInstance("RSA/ECB/OAEPPadding");
        c.init(javax.crypto.Cipher.ENCRYPT_MODE, pub,
                new javax.crypto.spec.OAEPParameterSpec("SHA-256", "MGF1",
                        java.security.spec.MGF1ParameterSpec.SHA256,
                        javax.crypto.spec.PSource.PSpecified.DEFAULT));
        String wieImBrowser = java.util.Base64.getEncoder()
                .encodeToString(c.doFinal("123456".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertArrayEquals("123456".toCharArray(), paar.entschluesseln(wieImBrowser));

        // Und so NICHT: Javas Kurzform laesst MGF1 auf SHA-1. Genau daran ist
        // die erste Fassung gescheitert - der Nachweis haelt das fest, damit es
        // niemand versehentlich zurueckbaut.
        var falsch = javax.crypto.Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        falsch.init(javax.crypto.Cipher.ENCRYPT_MODE, pub);
        String mitSha1Mgf1 = java.util.Base64.getEncoder()
                .encodeToString(falsch.doFinal("123456".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThrows(GeneralSecurityException.class, () -> paar.entschluesseln(mitSha1Mgf1));
    }

    @Test
    @DisplayName("Ein Chiffrat für einen anderen Agenten lässt sich nicht entschlüsseln")
    void fremdesChiffrat() throws Exception {
        var a = Schluesselpaar.ladenOderErzeugen(verzeichnis.resolve("a"));
        var b = Schluesselpaar.ladenOderErzeugen(verzeichnis.resolve("b"));
        String fremd = b.verschluesseln("123456");
        assertThrows(GeneralSecurityException.class, () -> a.entschluesseln(fremd));
    }

    @Test
    @DisplayName("Der Schlüssel überlebt den Neustart — sonst wäre jede PIN danach unbrauchbar")
    void bleibtErhalten() throws Exception {
        Path d = verzeichnis.resolve("s");
        var erst = Schluesselpaar.ladenOderErzeugen(d);
        String chiffrat = erst.verschluesseln("654321");
        var nachNeustart = Schluesselpaar.ladenOderErzeugen(d);
        assertArrayEquals("654321".toCharArray(), nachNeustart.entschluesseln(chiffrat));
        assertEquals(erst.oeffentlichBase64(), nachNeustart.oeffentlichBase64());
    }

    @Test
    @DisplayName("Eine beschädigte Schlüsseldatei wird nicht stillschweigend ersetzt")
    void beschaedigt() throws Exception {
        Path d = verzeichnis.resolve("s");
        Schluesselpaar.ladenOderErzeugen(d);
        Files.writeString(d, "das ist kein Schlüssel");
        var e = assertThrows(GeneralSecurityException.class,
                () -> Schluesselpaar.ladenOderErzeugen(d));
        // Die Meldung muss sagen, was zu tun ist (UX-Vorgabe 4.2).
        assertTrue(e.getMessage().contains("neu hinterlegt"), e.getMessage());
    }

    // ------------------------------------------------------------- Ablage

    @Test
    @DisplayName("Die abgelegte PIN steht nicht im Klartext in der Datei")
    void ablageIstVerschluesselt() throws Exception {
        Path d = verzeichnis.resolve("pins");
        var ablage = ablageMitSchluessel(d);
        ablage.speichern("card_1", "123456".toCharArray());

        String inhalt = Files.readString(d);
        assertFalse(inhalt.contains("123456"), "Die PIN steht im Klartext in der Ablage: " + inhalt);
        assertTrue(inhalt.startsWith("card_1=v1:"), inhalt);

        // ... und laesst sich beim Neustart wieder lesen.
        assertArrayEquals("123456".toCharArray(), ablageMitSchluessel(d).alleLaden().get("card_1"));
    }

    @Test
    @DisplayName("Löschen entfernt die PIN wirklich aus der Ablage")
    void loeschen() throws Exception {
        Path d = verzeichnis.resolve("pins");
        var ablage = ablageMitSchluessel(d);
        ablage.speichern("card_1", "123456".toCharArray());
        ablage.speichern("card_2", "999999".toCharArray());
        ablage.entfernen("card_1");

        var geladen = ablageMitSchluessel(d).alleLaden();
        assertFalse(geladen.containsKey("card_1"));
        assertArrayEquals("999999".toCharArray(), geladen.get("card_2"));
    }

    @Test
    @DisplayName("Ohne Schlüssel wird nichts auf die Platte geschrieben")
    void ohneSchluesselKeineDatei() {
        Path d = verzeichnis.resolve("pins");
        var ablage = Pinablage.ausUmgebung(d);   // PIN_ABLAGE_SCHLUESSEL ist im Test nicht gesetzt
        assertFalse(ablage.aktiv());
        ablage.speichern("card_1", "123456".toCharArray());
        assertFalse(Files.exists(d), "Ohne Schlüssel darf keine Ablage entstehen.");
    }

    @Test
    @DisplayName("Ein unlesbarer Eintrag nimmt die anderen nicht mit")
    void unlesbarerEintrag() throws Exception {
        Path d = verzeichnis.resolve("pins");
        ablageMitSchluessel(d).speichern("card_gut", "123456".toCharArray());
        Files.writeString(d, Files.readString(d) + "card_kaputt=v1:AAAA\n");

        var geladen = ablageMitSchluessel(d).alleLaden();
        assertArrayEquals("123456".toCharArray(), geladen.get("card_gut"));
        assertFalse(geladen.containsKey("card_kaputt"));
    }

    /**
     * Pinablage mit festem Schlüssel. Die Umgebungsvariable lässt sich im Test
     * nicht setzen, deshalb über Reflexion — die Alternative wäre eine
     * Testschnittstelle im Produktcode, und die wäre eine zweite Tür zu den PINs.
     */
    private static Pinablage ablageMitSchluessel(Path d) throws Exception {
        byte[] roh = new byte[32];
        for (int i = 0; i < 32; i++) roh[i] = (byte) i;
        var k = new javax.crypto.spec.SecretKeySpec(roh, "AES");
        var kon = Pinablage.class.getDeclaredConstructor(Path.class, javax.crypto.SecretKey.class);
        kon.setAccessible(true);
        return kon.newInstance(d, k);
    }
}
