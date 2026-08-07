package de.pinroboter.gemeinsam;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.time.Instant;

/**
 * Zeitbasiertes Einmalkennwort nach <a href="https://www.rfc-editor.org/rfc/rfc6238">RFC 6238</a>.
 *
 * <p>Der zweite Faktor ist laut FA-BEN-020 verpflichtend und nicht abschaltbar.
 * Grund: Das Produkt verwaltet SMC-B-PINs. Ein gestohlenes Passwort allein darf
 * nicht genügen, um die Automatik einer Praxis zu übernehmen.
 *
 * <p>Selbst umgesetzt statt als Abhängigkeit eingebunden — der Algorithmus ist
 * knapp fünfzig Zeilen, und der Standard liefert <b>offizielle Testvektoren</b>,
 * gegen die sich die Umsetzung beweisen lässt. Eine Bibliothek dafür wäre eine
 * ungeprüfte Abhängigkeit mehr an sicherheitskritischer Stelle.
 *
 * <p>Toleranz: ±1 Zeitfenster (FA-BEN-020). Mehr wäre bequemer und würde das
 * Zeitfenster für einen abgefangenen Code unnötig verlängern.
 */
public final class Totp {

    /** 30 Sekunden je Zeitfenster — der Wert, den alle gängigen Apps erwarten. */
    public static final int SCHRITT_SEKUNDEN = 30;
    public static final int STELLEN = 6;
    private static final String ALGORITHMUS = "HmacSHA1";

    private Totp() {}

    /** Erzeugt ein Geheimnis (160 Bit, wie von RFC 4226 empfohlen). */
    public static byte[] geheimnisErzeugen() {
        byte[] g = new byte[20];
        new SecureRandom().nextBytes(g);
        return g;
    }

    public static String code(byte[] geheimnis, Instant zeitpunkt) {
        return code(geheimnis, zeitpunkt.getEpochSecond() / SCHRITT_SEKUNDEN);
    }

    /** Erzeugt den Code für einen Zählerwert (RFC 4226, HOTP). */
    public static String code(byte[] geheimnis, long zaehler) {
        try {
            Mac mac = Mac.getInstance(ALGORITHMUS);
            mac.init(new SecretKeySpec(geheimnis, ALGORITHMUS));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(zaehler).array());

            // Dynamic Truncation nach RFC 4226, Abschnitt 5.3
            int versatz = hash[hash.length - 1] & 0x0F;
            int binaer = ((hash[versatz]     & 0x7F) << 24)
                       | ((hash[versatz + 1] & 0xFF) << 16)
                       | ((hash[versatz + 2] & 0xFF) << 8)
                       |  (hash[versatz + 3] & 0xFF);

            int modulo = (int) Math.pow(10, STELLEN);
            return String.format("%0" + STELLEN + "d", binaer % modulo);
        } catch (Exception e) {
            throw new IllegalStateException("TOTP-Berechnung fehlgeschlagen", e);
        }
    }

    /**
     * Prüft einen eingegebenen Code gegen das Geheimnis.
     *
     * <p>Toleriert genau ein Zeitfenster in beide Richtungen — das gleicht
     * geringe Uhrabweichungen und Tippzeit aus, ohne die Gültigkeitsdauer
     * unnötig zu verlängern.
     *
     * <p>Der Vergleich läuft in <b>konstanter Zeit</b>. Ein Vergleich mit
     * {@code equals} verrät über die Laufzeit, wie viele Stellen stimmen — bei
     * sechs Ziffern ist das ein praktisch ausnutzbarer Unterschied.
     */
    public static boolean pruefe(byte[] geheimnis, String eingabe, Instant jetzt) {
        if (eingabe == null) return false;
        String bereinigt = eingabe.replaceAll("\\s", "");
        if (bereinigt.length() != STELLEN) return false;

        long fenster = jetzt.getEpochSecond() / SCHRITT_SEKUNDEN;
        boolean treffer = false;
        for (long v = fenster - 1; v <= fenster + 1; v++) {
            // Bewusst ohne vorzeitigen Abbruch: Die Prüfung soll unabhängig
            // davon gleich lange dauern, welches Fenster passt.
            treffer |= gleichInKonstanterZeit(code(geheimnis, v), bereinigt);
        }
        return treffer;
    }

    /** Für eine bereits verbrauchte Codeprüfung: Welches Fenster hat gepasst? */
    public static long fensterVon(byte[] geheimnis, String eingabe, Instant jetzt) {
        String bereinigt = eingabe == null ? "" : eingabe.replaceAll("\\s", "");
        long fenster = jetzt.getEpochSecond() / SCHRITT_SEKUNDEN;
        for (long v = fenster - 1; v <= fenster + 1; v++) {
            if (gleichInKonstanterZeit(code(geheimnis, v), bereinigt)) return v;
        }
        return -1;
    }

    private static boolean gleichInKonstanterZeit(String a, String b) {
        if (a.length() != b.length()) return false;
        int unterschied = 0;
        for (int i = 0; i < a.length(); i++) unterschied |= a.charAt(i) ^ b.charAt(i);
        return unterschied == 0;
    }

    // ---------------------------------------------------------------- Base32

    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    /** Base32 nach RFC 4648 — das Format, das Authenticator-Apps erwarten. */
    public static String alsBase32(byte[] daten) {
        StringBuilder sb = new StringBuilder();
        int puffer = 0, bits = 0;
        for (byte b : daten) {
            puffer = (puffer << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                sb.append(BASE32.charAt((puffer >> (bits - 5)) & 0x1F));
                bits -= 5;
            }
        }
        if (bits > 0) sb.append(BASE32.charAt((puffer << (5 - bits)) & 0x1F));
        return sb.toString();
    }

    public static byte[] ausBase32(String text) {
        String t = text.replaceAll("[\\s=]", "").toUpperCase();
        java.io.ByteArrayOutputStream aus = new java.io.ByteArrayOutputStream();
        int puffer = 0, bits = 0;
        for (char c : t.toCharArray()) {
            int wert = BASE32.indexOf(c);
            if (wert < 0) throw new IllegalArgumentException("Kein gültiges Base32-Zeichen: " + c);
            puffer = (puffer << 5) | wert;
            bits += 5;
            if (bits >= 8) {
                aus.write((puffer >> (bits - 8)) & 0xFF);
                bits -= 8;
            }
        }
        return aus.toByteArray();
    }

    /**
     * Einrichtungs-URI für Authenticator-Apps (Format {@code otpauth://}).
     * Der Benutzer scannt sie als QR-Code oder gibt das Geheimnis von Hand ein.
     */
    public static String einrichtungsUri(String benutzer, byte[] geheimnis, String herausgeber) {
        String e = java.net.URLEncoder.encode(herausgeber, java.nio.charset.StandardCharsets.UTF_8);
        String b = java.net.URLEncoder.encode(benutzer, java.nio.charset.StandardCharsets.UTF_8);
        return "otpauth://totp/%s:%s?secret=%s&issuer=%s&algorithm=SHA1&digits=%d&period=%d"
                .formatted(e, b, alsBase32(geheimnis), e, STELLEN, SCHRITT_SEKUNDEN);
    }
}
