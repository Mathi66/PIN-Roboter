package de.pinroboter.gemeinsam;

import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;

/**
 * Verschlüsselte Sicherungsdatei (FA-BAK-020, ADR-0010).
 *
 * <h2>Was die Datei leistet</h2>
 *
 * Sie ist <b>verschlüsselt und integritätsgeschützt</b> — beides in einem
 * Schritt über AES-256-GCM. Zwei getrennte Verfahren (Verschlüsselung plus
 * HMAC) hätten eine zusätzliche Stelle, an der man es falsch zusammensetzen
 * kann.
 *
 * <h2>Aufbau</h2>
 *
 * <pre>
 *   Magie      8 Byte   "PRSICH01"  — Formatkennung samt Version
 *   Salt      16 Byte   je Datei neu
 *   Nonce     12 Byte   je Datei neu
 *   Zeit       8 Byte   Zeitpunkt der Sicherung (Epochensekunden)
 *   Chiffrat   Rest     AES-256-GCM, Prüfsumme angehängt
 * </pre>
 *
 * <p><b>Der Kopf bleibt unverschlüsselt</b>, geht aber als <i>Additional
 * Authenticated Data</i> in die GCM-Prüfung ein: lesbar, aber nicht
 * veränderbar. So kann die Oberfläche vor dem Entschlüsseln sagen, <i>was</i>
 * für eine Datei das ist — statt bei einer versehentlich gewählten Fremddatei
 * nur „Schlüssel falsch" zu melden.
 *
 * <h2>Warum ein Salt je Datei</h2>
 *
 * Sonst ergäbe dieselbe Passphrase immer denselben Schlüssel, und eine einmal
 * gebrochene Datei öffnete alle.
 *
 * <h2>Warum Argon2id und nicht die Passphrase direkt</h2>
 *
 * Eine Passphrase ist keine 32 Byte Zufall. Ohne Schlüsselableitung mit
 * Rechenaufwand wäre eine heruntergeladene Datei mit einem Wörterbuchangriff zu
 * öffnen. Die Parameter sind bewusst spürbar: Ein Download darf eine Sekunde
 * dauern, ein Angreifer mit Millionen Versuchen soll daran scheitern.
 *
 * <h2>Der Schlüssel wird vergessen</h2>
 *
 * Die Passphrase kommt als {@code char[]} herein und wird vor der Rückkehr
 * überschrieben — auch im Fehlerfall. Ausdrückliche Vorgabe des PO. Das
 * bedeutet: <b>Geht sie verloren, ist die Datei endgültig unbrauchbar.</b>
 */
public final class Sicherungsdatei {

    private static final byte[] MAGIE = "PRSICH01".getBytes(StandardCharsets.US_ASCII);
    private static final int SALT = 16, NONCE = 12, PRUEFSUMME_BITS = 128;
    private static final int KOPF = 8 + SALT + NONCE + 8;

    /** Argon2id, gemessen auf der Referenzhardware: rund 0,4 s je Ableitung. */
    private static final int SPEICHER_KB = 65536, DURCHGAENGE = 3, SPUREN = 2;

    private static final SecureRandom ZUFALL = new SecureRandom();

    private Sicherungsdatei() { }

    /** Ergebnis des Einlesens — der Kopf ist auch dann lesbar, wenn nicht entschlüsselt wurde. */
    public record Inhalt(byte[] daten, Instant erzeugt) { }

    /** Formatfehler: Die Datei ist keine Sicherung dieses Produkts. */
    public static class KeinSicherungsformat extends Exception {
        public KeinSicherungsformat(String m) { super(m); }
    }

    /** Schlüssel falsch oder Datei verändert — bewusst nicht unterschieden. */
    public static class NichtLesbar extends Exception {
        public NichtLesbar(String m) { super(m); }
    }

    // ------------------------------------------------------------------ Schreiben

    /**
     * Verschlüsselt eine Sicherung.
     *
     * @param passphrase wird vor der Rückkehr überschrieben
     */
    public static byte[] verschluesseln(byte[] klartext, char[] passphrase, Instant erzeugt)
            throws Exception {
        byte[] salt = new byte[SALT], nonce = new byte[NONCE];
        ZUFALL.nextBytes(salt);
        ZUFALL.nextBytes(nonce);

        byte[] kopf = kopfBauen(salt, nonce, erzeugt);
        byte[] schluessel = null;
        try {
            schluessel = ableiten(passphrase, salt);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(schluessel, "AES"),
                    new GCMParameterSpec(PRUEFSUMME_BITS, nonce));
            // Der Kopf wird mitgeprüft, aber nicht verschlüsselt.
            c.updateAAD(kopf);
            byte[] chiffrat = c.doFinal(klartext);

            ByteArrayOutputStream aus = new ByteArrayOutputStream(kopf.length + chiffrat.length);
            aus.write(kopf);
            aus.write(chiffrat);
            return aus.toByteArray();
        } finally {
            if (schluessel != null) Arrays.fill(schluessel, (byte) 0);
            Arrays.fill(passphrase, '\0');
        }
    }

    // ------------------------------------------------------------------ Lesen

    /**
     * Liest den Kopf, ohne zu entschlüsseln.
     *
     * <p>Damit lässt sich vor der Schlüsseleingabe sagen, ob überhaupt eine
     * Sicherung dieses Produkts vorliegt und von wann sie stammt.
     */
    public static Instant kopfLesen(byte[] datei) throws KeinSicherungsformat {
        if (datei == null || datei.length < KOPF + 16) {
            throw new KeinSicherungsformat("Die Datei ist zu klein für eine Sicherung.");
        }
        for (int i = 0; i < MAGIE.length; i++) {
            if (datei[i] != MAGIE[i]) {
                throw new KeinSicherungsformat(
                        "Das ist keine Sicherungsdatei des PIN-Roboters. "
                        + "Erwartet wird eine Datei, die hier heruntergeladen wurde.");
            }
        }
        long sek = ByteBuffer.wrap(datei, 8 + SALT + NONCE, 8).getLong();
        return Instant.ofEpochSecond(sek);
    }

    /**
     * Entschlüsselt eine Sicherung.
     *
     * <p>Ein falscher Schlüssel und eine veränderte Datei führen zur
     * <b>gleichen</b> Meldung. Das ist Absicht: Die Unterscheidung wäre für
     * einen Angreifer nützlicher als für den Betreiber — er erführe, ob er beim
     * Raten näher kommt.
     *
     * @param passphrase wird vor der Rückkehr überschrieben
     */
    public static Inhalt entschluesseln(byte[] datei, char[] passphrase)
            throws KeinSicherungsformat, NichtLesbar {
        Instant erzeugt = kopfLesen(datei);
        byte[] salt = Arrays.copyOfRange(datei, 8, 8 + SALT);
        byte[] nonce = Arrays.copyOfRange(datei, 8 + SALT, 8 + SALT + NONCE);
        byte[] kopf = Arrays.copyOfRange(datei, 0, KOPF);
        byte[] chiffrat = Arrays.copyOfRange(datei, KOPF, datei.length);

        byte[] schluessel = null;
        try {
            schluessel = ableiten(passphrase, salt);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(schluessel, "AES"),
                    new GCMParameterSpec(PRUEFSUMME_BITS, nonce));
            c.updateAAD(kopf);
            return new Inhalt(c.doFinal(chiffrat), erzeugt);
        } catch (Exception e) {
            throw new NichtLesbar(
                    "Die Datei ließ sich nicht öffnen. Entweder stimmt der Schlüssel nicht, "
                    + "oder die Datei wurde nach dem Herunterladen verändert. "
                    + "Beides führt zur selben Meldung — geprüft wird die Datei als Ganzes.");
        } finally {
            if (schluessel != null) Arrays.fill(schluessel, (byte) 0);
            Arrays.fill(passphrase, '\0');
        }
    }

    // ------------------------------------------------------------------ intern

    private static byte[] kopfBauen(byte[] salt, byte[] nonce, Instant erzeugt) {
        ByteBuffer b = ByteBuffer.allocate(KOPF);
        b.put(MAGIE).put(salt).put(nonce).putLong(erzeugt.getEpochSecond());
        return b.array();
    }

    /**
     * Argon2id über die Passphrase.
     *
     * <p>Dasselbe Verfahren wie bei den Kennwörtern — ein Verfahren im ganzen
     * Produkt, nicht drei.
     */
    private static byte[] ableiten(char[] passphrase, byte[] salt) {
        var b = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withIterations(DURCHGAENGE)
                .withMemoryAsKB(SPEICHER_KB)
                .withParallelism(SPUREN)
                .withSalt(salt);
        var gen = new Argon2BytesGenerator();
        gen.init(b.build());
        byte[] schluessel = new byte[32];
        gen.generateBytes(passphrase, schluessel);
        return schluessel;
    }

    /**
     * Mindestanforderung an den Schlüssel.
     *
     * <p>Kein Kennwort im Sinne der Anmeldung, sondern ein Schlüssel für eine
     * Datei, die das Haus verlässt. Zwölf Zeichen sind die Untergrenze; eine
     * Wortfolge ist leichter zu merken und sicherer als etwas Kurzes mit
     * Sonderzeichen.
     *
     * @return Klartextmeldung, oder {@code null} wenn brauchbar
     */
    public static String pruefeSchluessel(char[] passphrase) {
        if (passphrase == null || passphrase.length < 12) {
            return "Der Schlüssel muss mindestens 12 Zeichen haben. "
                 + "Eine Folge mehrerer Wörter ist leichter zu merken und sicherer "
                 + "als ein kurzer Schlüssel mit Sonderzeichen.";
        }
        return null;
    }
}
