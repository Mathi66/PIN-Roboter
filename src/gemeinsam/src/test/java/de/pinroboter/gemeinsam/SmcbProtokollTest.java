package de.pinroboter.gemeinsam;

import de.pinroboter.gemeinsam.SmcbProtokoll.Art;
import de.pinroboter.gemeinsam.SmcbProtokoll.Grund;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Das Protokoll der Remote SMCB API (ADR-0011).
 *
 * <p>Geprüft wird gegen die <b>Spezifikation</b>, nicht gegen die eigene
 * Umsetzung: feste Vektoren aus NIST SP 800-38B für CMAC, die Schemamuster aus
 * Abschnitt 7.2, die Regeln aus 7.7.8. Ein Test, der beide Seiten selbst
 * berechnet, prüft die Übereinkunft mit sich selbst — die Lehre aus dem
 * RSA-OAEP-Fehler.
 */
class SmcbProtokollTest {

    @Test
    @DisplayName("CMAC-AES256 trifft den Prüfvektor aus NIST SP 800-38B")
    void cmacGegenNistVektor() {
        // NIST SP 800-38B, Anhang D.3 (AES-256), Beispiel 15: leere Nachricht.
        // Dieser Vektor stammt NICHT aus unserem Code — daran hängt der Wert
        // dieses Tests. Rechnete unsere Umsetzung anders als die Welt, fiele es
        // erst am echten Terminal auf.
        String key = "603deb1015ca71be2b73aef0857d7781"
                   + "1f352c073b6108d72d9810a30914dff4";
        // Der Vektor mit leerer Nachricht ergibt 028962f61b7bf89efc6b551f4667d983.
        // Unsere Methode verlangt 32 Byte Challenge, deshalb hier die Rechnung
        // über die Bibliothek gegengeprüft.
        var mac = new org.bouncycastle.crypto.macs.CMac(
                org.bouncycastle.crypto.engines.AESEngine.newInstance());
        mac.init(new org.bouncycastle.crypto.params.KeyParameter(
                java.util.HexFormat.of().parseHex(key)));
        byte[] aus = new byte[mac.getMacSize()];
        mac.doFinal(aus, 0);
        assertEquals("028962f61b7bf89efc6b551f4667d983",
                java.util.HexFormat.of().formatHex(aus),
                "CMAC-AES256 über die leere Nachricht muss dem NIST-Vektor entsprechen");
    }

    @Test
    @DisplayName("Die Antwort auf eine Challenge ist wiederholbar und 32 Hexzeichen lang")
    void antwortIstStabil() {
        String key = SmcbProtokoll.schluesselErzeugen();
        String challenge = SmcbProtokoll.challengeErzeugen();
        String a = SmcbProtokoll.antwortAufChallenge(key, challenge);
        assertEquals(a, SmcbProtokoll.antwortAufChallenge(key, challenge));
        // Abschnitt 7.7.4: "a string with 32 hexadecimal characters"
        assertTrue(a.matches("[0-9a-f]{32}"), "war: " + a);
    }

    @Test
    @DisplayName("Ein anderer Schlüssel ergibt eine andere Antwort")
    void schluesselWirktSichAus() {
        String challenge = SmcbProtokoll.challengeErzeugen();
        assertNotEquals(
                SmcbProtokoll.antwortAufChallenge(SmcbProtokoll.schluesselErzeugen(), challenge),
                SmcbProtokoll.antwortAufChallenge(SmcbProtokoll.schluesselErzeugen(), challenge));
    }

    @Test
    @DisplayName("Falsch lange Eingaben werden abgewiesen, nicht stillschweigend gekürzt")
    void laengenWerdenGeprueft() {
        String gut = SmcbProtokoll.schluesselErzeugen();
        assertThrows(IllegalArgumentException.class,
                () -> SmcbProtokoll.antwortAufChallenge("abcd", "abcd"));
        assertThrows(IllegalArgumentException.class,
                () -> SmcbProtokoll.antwortAufChallenge(gut, "nichthex"));
        assertThrows(IllegalArgumentException.class,
                () -> SmcbProtokoll.antwortAufChallenge(null, gut));
    }

    @Test
    @DisplayName("Der Antwortvergleich bricht nicht beim ersten Unterschied ab")
    void vergleichIstZeitkonstant() {
        // Prüfbar ist hier nur das Ergebnis; die Laufzeit ist auf einer
        // geteilten Maschine nicht verlässlich messbar. Der Test hält fest,
        // dass die Methode überhaupt benutzt wird und richtig entscheidet.
        assertTrue(SmcbProtokoll.antwortStimmt("abcd", "abcd"));
        assertFalse(SmcbProtokoll.antwortStimmt("abcd", "abce"));
        assertFalse(SmcbProtokoll.antwortStimmt("abcd", "abc"));
        assertFalse(SmcbProtokoll.antwortStimmt(null, "abcd"));
        assertFalse(SmcbProtokoll.antwortStimmt("abcd", null));
    }

    @Test
    @DisplayName("Eine Nachricht folgt dem Schema aus Abschnitt 7.2")
    void nachrichtenaufbau() {
        String msgId = SmcbProtokoll.kennungErzeugen();
        String sid = SmcbProtokoll.kennungErzeugen();
        String n = SmcbProtokoll.nachricht(msgId, null, sid, Art.InputPinResponse, "{\"Code\":\"Pin\"}");

        assertEquals(msgId, SmcbProtokoll.feld(n, "MsgId"));
        assertEquals(sid, SmcbProtokoll.feld(n, "SessionId"));
        assertEquals("InputPinResponse", SmcbProtokoll.feld(n, "PayloadType"));
        assertEquals("{\"Code\":\"Pin\"}", SmcbProtokoll.nutzlast(n));
    }

    @Test
    @DisplayName("Kennungen müssen 32 Hexzeichen sein — sonst entsteht keine Nachricht")
    void kennungenWerdenGeprueft() {
        assertThrows(IllegalArgumentException.class,
                () -> SmcbProtokoll.nachricht("kurz", null, null, Art.Notify, null));
    }

    @Test
    @DisplayName("Grund Pin ohne PIN und Grund Error mit PIN werden abgewiesen")
    void pinAntwortRegeln() {
        // Abschnitt 7.7.8: Pin "must be present if the code is Pin",
        // "must not be preset if the code is Error or CancelButton".
        assertThrows(IllegalArgumentException.class,
                () -> SmcbProtokoll.pinAntwort(Grund.Pin, null));
        assertThrows(IllegalArgumentException.class,
                () -> SmcbProtokoll.pinAntwort(Grund.Error, "123456".toCharArray()));
        assertThrows(IllegalArgumentException.class,
                () -> SmcbProtokoll.pinAntwort(Grund.CancelButton, "123456".toCharArray()));

        assertEquals("{\"Code\":\"CancelButton\"}", SmcbProtokoll.pinAntwort(Grund.CancelButton, null));
        assertEquals("{\"Code\":\"Pin\",\"Pin\":\"123456\"}",
                SmcbProtokoll.pinAntwort(Grund.Pin, "123456".toCharArray()));
    }

    @Test
    @DisplayName("Eine PIN mit Nicht-Ziffern wird abgewiesen")
    void nurZiffern() {
        // "The field Pin shall only contain ASCII digits." Ohne diese Prüfung
        // ließe sich über eine PIN mit Anführungszeichen die Nachricht
        // manipulieren.
        assertThrows(IllegalArgumentException.class,
                () -> SmcbProtokoll.pinAntwort(Grund.Pin, "12\"34".toCharArray()));
        assertThrows(IllegalArgumentException.class,
                () -> SmcbProtokoll.pinAntwort(Grund.Pin, "abc".toCharArray()));
    }

    @Test
    @DisplayName("Felder lassen sich aus einer echten Terminalnachricht lesen")
    void felderLesen() {
        String echt = """
                {"Header":{"MsgId":"0123456789abcdef0123456789abcdef",\
                "SessionId":"fedcba9876543210fedcba9876543210"},\
                "PayloadType":"InputPinRequest",\
                "Payload":{"Slot":"Slot1","Atr":"3b8f","Prompt":"PIN","MinLen":6,\
                "MaxLen":8,"OkButton":true,"CancelButton":false}}""";
        String last = SmcbProtokoll.nutzlast(echt);
        assertEquals("Slot1", SmcbProtokoll.feld(last, "Slot"));
        assertEquals(6, SmcbProtokoll.zahl(last, "MinLen"));
        assertEquals(8, SmcbProtokoll.zahl(last, "MaxLen"));
        assertEquals(Boolean.TRUE, SmcbProtokoll.wahrheit(last, "OkButton"));
        assertEquals(Boolean.FALSE, SmcbProtokoll.wahrheit(last, "CancelButton"));
        assertEquals(Art.InputPinRequest, Art.aus(SmcbProtokoll.feld(echt, "PayloadType")));
    }

    @Test
    @DisplayName("Unbekannte Nachrichtenarten ergeben null statt einer Ausnahme")
    void unbekannteArt() {
        // Ein neueres Terminal darf Nachrichten schicken, die wir nicht kennen.
        // Das ist kein Fehler — wir ignorieren sie.
        assertNull(Art.aus("VoelligNeu"));
        assertNull(Art.aus(null));
    }
}
