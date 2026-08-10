package de.pinroboter.gemeinsam;

import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.macs.CMac;
import org.bouncycastle.crypto.params.KeyParameter;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Das Protokoll der <b>Remote SMCB API</b> (ADR-0011).
 *
 * <h2>Warum es diese Klasse gibt</h2>
 *
 * Bis zum 10.08.2026 nahm dieses Produkt an, der Agent <i>schiebe</i> die PIN
 * ans Kartenterminal. Die Herstellerspezifikation sagt das Gegenteil:
 *
 * <blockquote>
 * „While authenticated the Remote SMCB API will send {@code OutputRequests} and
 * {@code InputPinRequests} if a PIN verification is requested on the respective
 * slot from the Konnektor."<br>
 * — ST-1506 Remote Management Specification 2.8, Abschnitt 7.4
 * </blockquote>
 *
 * <p><b>Das Terminal fragt, der Agent antwortet.</b> Er ist ein PIN-Provider,
 * kein Auftraggeber.
 *
 * <h2>Was hier liegt und was nicht</h2>
 *
 * Nur das <b>Protokoll</b>: Nachrichtenaufbau, Antwortberechnung, Prüfungen.
 * Die Verbindung selbst führt {@code SmcbVerbindung} im Agenten, die
 * Gegenstelle bildet der Simulator nach. So lässt sich dieselbe Rechnung auf
 * beiden Seiten prüfen, ohne sie zweimal zu schreiben — die Lehre aus dem
 * RSA-OAEP-Fehler, wo genau das schiefging.
 */
public final class SmcbProtokoll {

    /** Der Protokollname des WebSocket-Unterprotokolls (Abschnitt 7.4). */
    public static final String UNTERPROTOKOLL = "cobra-smcb";

    /** Die von dieser Umsetzung unterstützte Fassung (Abschnitt 7.7.3). */
    public static final String API_VERSION = "1.0";

    /**
     * Frist für die Antwort auf den {@code AuthenticateRequest}.
     *
     * <p>„The timeout until a response is accepted is 30 seconds."
     * (Abschnitt 7.7.3). Wir rechnen mit weniger, damit ein knapper Ausgang
     * nicht am Netz scheitert.
     */
    public static final int ANTWORTFRIST_SEKUNDEN = 30;

    /** Gültigkeit des Schlüssels aus {@code SmcbAuthentication} (Abschnitt 7.4). */
    public static final int SCHLUESSEL_GUELTIG_SEKUNDEN = 30;

    private static final SecureRandom ZUFALL = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();

    private SmcbProtokoll() { }

    /** Die Nachrichtenarten aus dem JSON-Schema in Abschnitt 7.2. */
    public enum Art {
        Notify, Cancel,
        AuthenticateRequest, AuthenticateResponse,
        OutputRequest, OutputResponse,
        InputPinRequest, InputPinResponse;

        public static Art aus(String s) {
            for (Art a : values()) if (a.name().equals(s)) return a;
            return null;
        }
    }

    /**
     * Die Antwortgründe eines {@code InputPinResponse} (Abschnitt 7.7.8).
     *
     * <p>{@code Pin} ist der Erfolgsfall. Die drei anderen sind Absagen — und
     * jede bedeutet etwas anderes, weshalb sie nicht zusammengefasst werden:
     * {@code CancelButton} ist eine Entscheidung, {@code Timeout} ein
     * Zeitproblem, {@code Error} ein Fehler bei uns.
     */
    public enum Grund { Pin, Timeout, CancelButton, Error }

    // ------------------------------------------------------------ Authentisierung

    /**
     * Die Antwort auf die Challenge des Terminals (Abschnitt 7.7.4).
     *
     * <p>Der Spezifikationstext lautet:
     * <pre>
     *   key       = convert_hex_string_to_binary(Key)
     *   challenge = convert_hex_string_to_binary(Challenge)
     *   alg       = AES256-CBC
     *   response  = CMAC(alg, key, challenge)
     * </pre>
     *
     * <p><b>„AES256-CBC" meint hier den Blockchiffre-Betrieb innerhalb von
     * CMAC</b>, nicht eine zusätzliche Verschlüsselung. CMAC ist nach NIST
     * SP 800-38B über AES definiert und liefert 16 Byte — passend zu den
     * geforderten 32 Hexzeichen.
     *
     * @param schluesselHex 64 Hexzeichen (32 Byte) aus {@code SmcbAuthentication}
     * @param challengeHex  64 Hexzeichen (32 Byte) aus dem {@code AuthenticateRequest}
     * @return 32 Hexzeichen, kleingeschrieben
     */
    public static String antwortAufChallenge(String schluesselHex, String challengeHex) {
        byte[] schluessel = hexZuBytes(schluesselHex, 32, "Schlüssel");
        byte[] challenge = hexZuBytes(challengeHex, 32, "Challenge");

        CMac mac = new CMac(AESEngine.newInstance());
        mac.init(new KeyParameter(schluessel));
        mac.update(challenge, 0, challenge.length);
        byte[] aus = new byte[mac.getMacSize()];
        mac.doFinal(aus, 0);

        java.util.Arrays.fill(schluessel, (byte) 0);
        return HEX.formatHex(aus);
    }

    /** Erzeugt eine Challenge — für die Gegenstelle im Simulator. */
    public static String challengeErzeugen() {
        byte[] b = new byte[32];
        ZUFALL.nextBytes(b);
        return HEX.formatHex(b);
    }

    /** Erzeugt einen Sitzungs- oder Nachrichtenbezeichner (32 Hexzeichen). */
    public static String kennungErzeugen() {
        byte[] b = new byte[16];
        ZUFALL.nextBytes(b);
        return HEX.formatHex(b);
    }

    /** Erzeugt einen Authentisierungsschlüssel (64 Hexzeichen). */
    public static String schluesselErzeugen() {
        byte[] b = new byte[32];
        ZUFALL.nextBytes(b);
        return HEX.formatHex(b);
    }

    /**
     * Vergleicht zwei Antworten in gleichbleibender Zeit.
     *
     * <p>Ein Vergleich, der beim ersten Unterschied abbricht, verrät über die
     * Laufzeit, wie viele Zeichen stimmten. Bei einer Challenge-Response ist das
     * der Unterschied zwischen „nicht zu erraten" und „in Schritten zu erraten".
     */
    public static boolean antwortStimmt(String erwartet, String erhalten) {
        if (erwartet == null || erhalten == null) return false;
        if (erwartet.length() != erhalten.length()) return false;
        int unterschied = 0;
        for (int i = 0; i < erwartet.length(); i++) {
            unterschied |= erwartet.charAt(i) ^ erhalten.charAt(i);
        }
        return unterschied == 0;
    }

    // ------------------------------------------------------------ Nachrichten

    /**
     * Baut eine Nachricht nach dem Schema aus Abschnitt 7.2.
     *
     * <p>Von Hand gebaut statt mit einer JSON-Bibliothek: Das Schema ist
     * überschaubar, die Werte sind auf Hexzeichen und Ziffern beschränkt, und
     * eine weitere Abhängigkeit wäre nach CLAUDE.md begründungspflichtig.
     * Alles, was hier eingesetzt wird, ist vorher geprüft.
     */
    public static String nachricht(String msgId, String inReplyToId, String sessionId,
                                   Art art, String nutzlast) {
        StringBuilder kopf = new StringBuilder("{\"MsgId\":\"").append(pruefeKennung(msgId)).append('"');
        if (inReplyToId != null) kopf.append(",\"InReplyToId\":\"").append(pruefeKennung(inReplyToId)).append('"');
        if (sessionId != null) kopf.append(",\"SessionId\":\"").append(pruefeKennung(sessionId)).append('"');
        return "{\"Header\":" + kopf + "},\"PayloadType\":\"" + art.name()
             + "\",\"Payload\":" + (nutzlast == null ? "{}" : nutzlast) + "}";
    }

    /**
     * Die Antwort auf eine PIN-Anfrage (Abschnitt 7.7.8).
     *
     * <p>Bei {@link Grund#Pin} ist die PIN Pflicht, bei {@code Error} und
     * {@code CancelButton} verboten. Diese Regel wird hier durchgesetzt, nicht
     * dem Aufrufer überlassen — sonst schickt irgendwann jemand eine PIN mit
     * dem Code {@code Error} an ein Gerät.
     */
    public static String pinAntwort(Grund grund, char[] pin) {
        if (grund == Grund.Pin && (pin == null || pin.length == 0)) {
            throw new IllegalArgumentException("Grund Pin ohne PIN-Wert");
        }
        if ((grund == Grund.Error || grund == Grund.CancelButton) && pin != null && pin.length > 0) {
            throw new IllegalArgumentException("Grund " + grund + " darf keine PIN tragen");
        }
        StringBuilder s = new StringBuilder("{\"Code\":\"").append(grund.name()).append('"');
        if (pin != null && pin.length > 0) {
            for (char c : pin) {
                if (c < '0' || c > '9') {
                    throw new IllegalArgumentException("Die PIN darf nur Ziffern enthalten.");
                }
            }
            s.append(",\"Pin\":\"").append(pin).append('"');
        }
        return s.append('}').toString();
    }

    /**
     * Liest einen Zeichenkettenwert aus einer flachen JSON-Ebene.
     *
     * <p>Bewusst ein enger Leser statt einer Bibliothek — dieselbe Überlegung
     * wie im Agenten an anderer Stelle: Die Struktur ist bekannt und flach, die
     * Werte sind durch das Schema auf Hexzeichen, Ziffern und feste Wörter
     * begrenzt. Was nicht dem erwarteten Muster entspricht, wird verworfen.
     */
    public static String feld(String json, String name) {
        if (json == null) return null;
        var m = java.util.regex.Pattern
                .compile("\"" + java.util.regex.Pattern.quote(name) + "\"\\s*:\\s*\"([^\"]*)\"")
                .matcher(json);
        return m.find() ? m.group(1) : null;
    }

    /** Liest einen Zahlwert aus einer flachen JSON-Ebene. */
    public static Integer zahl(String json, String name) {
        if (json == null) return null;
        var m = java.util.regex.Pattern
                .compile("\"" + java.util.regex.Pattern.quote(name) + "\"\\s*:\\s*(-?\\d+)")
                .matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : null;
    }

    /** Liest einen Wahrheitswert aus einer flachen JSON-Ebene. */
    public static Boolean wahrheit(String json, String name) {
        if (json == null) return null;
        var m = java.util.regex.Pattern
                .compile("\"" + java.util.regex.Pattern.quote(name) + "\"\\s*:\\s*(true|false)")
                .matcher(json);
        return m.find() ? Boolean.valueOf(m.group(1)) : null;
    }

    /** Die Nutzlast einer Nachricht — alles hinter {@code "Payload":}. */
    public static String nutzlast(String json) {
        int i = json.indexOf("\"Payload\"");
        if (i < 0) return null;
        int auf = json.indexOf('{', i);
        if (auf < 0) return null;
        int tiefe = 0;
        for (int k = auf; k < json.length(); k++) {
            char c = json.charAt(k);
            if (c == '{') tiefe++;
            else if (c == '}' && --tiefe == 0) return json.substring(auf, k + 1);
        }
        return null;
    }

    // ------------------------------------------------------------------ intern

    private static byte[] hexZuBytes(String hex, int erwarteteBytes, String was) {
        if (hex == null || !hex.matches("[0-9a-fA-F]{" + (erwarteteBytes * 2) + "}")) {
            throw new IllegalArgumentException(
                    was + " muss aus genau " + (erwarteteBytes * 2) + " Hexzeichen bestehen.");
        }
        return HEX.parseHex(hex.toLowerCase(Locale.ROOT));
    }

    private static String pruefeKennung(String k) {
        if (k == null || !k.matches("[0-9a-f]{32}")) {
            throw new IllegalArgumentException("Kennung muss aus 32 Hexzeichen bestehen: " + k);
        }
        return k;
    }
}
