package de.pinroboter.gemeinsam;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * Verschlüsselte Ablage von Zugangsdaten (FA-GER-010, FA-GER-020).
 *
 * <p>Die Anforderung lautet: Zugangsdaten sind nach dem Speichern über die
 * Oberfläche und über jede Schnittstelle <b>nicht mehr auslesbar</b>. Ein
 * Passwort-Hash genügt hier nicht — anders als beim Anmeldekennwort muss der
 * Wert selbst wieder verwendbar sein, um sich beim Konnektor anzumelden.
 * Deshalb Verschlüsselung, nicht Hashing.
 *
 * <p>Verfahren: <b>AES-256 im GCM-Modus</b>. GCM erkennt Veränderungen am
 * Chiffrat; ein manipulierter Datenbankeintrag führt zu einem Fehler statt zu
 * einem stillen Fehlverhalten. Je Verschlüsselung wird ein neuer 12-Byte-Nonce
 * gezogen — bei GCM ist ein wiederverwendeter Nonce mit demselben Schlüssel der
 * eine Fehler, der das Verfahren vollständig bricht.
 *
 * <p><b>Woher der Schlüssel kommt:</b> aus der Umgebungsvariablen
 * {@code GEHEIMNIS_SCHLUESSEL} (Base64, 32 Byte). Nicht aus dem Abbild, nicht
 * aus dem Repository — siehe CLAUDE.md, Abschnitt 5. Fehlt sie, verweigert die
 * Anwendung das Speichern von Zugangsdaten mit einer erklärenden Meldung,
 * statt einen erratenen Schlüssel zu verwenden.
 *
 * <p><b>Grenze dieses Verfahrens:</b> Wer Datenbank <i>und</i> Schlüssel hat,
 * kann entschlüsseln. Das ist unvermeidbar, wenn der Wert wieder verwendbar
 * sein muss. Der Schutz richtet sich gegen Datenbanksicherungen, Auszüge und
 * versehentliche Ausgaben — nicht gegen einen Angreifer mit vollem Systemzugriff.
 * Die SMC-B-PIN fällt ausdrücklich <b>nicht</b> hierunter: Sie liegt beim
 * Agenten und erreicht die Zentrale nie (ADR-0002).
 */
public final class Geheimnisspeicher {

    /** Kennzeichnung am Anfang jedes Chiffrats — macht Formatwechsel später erkennbar. */
    private static final String MARKE = "v1:";
    private static final int NONCE_LAENGE = 12;      // GCM-Standard, RFC 5116
    private static final int PRUEFSUMME_BITS = 128;

    private final SecretKey schluessel;
    private final SecureRandom zufall = new SecureRandom();

    private Geheimnisspeicher(SecretKey schluessel) { this.schluessel = schluessel; }

    /**
     * Liest den Schlüssel aus der Umgebung.
     *
     * @return der Speicher, oder {@code null}, wenn kein gültiger Schlüssel
     *         hinterlegt ist. Der Aufrufer muss diesen Fall behandeln und dem
     *         Betreiber sagen, was zu tun ist.
     */
    public static Geheimnisspeicher ausUmgebung() {
        return ausBase64(System.getenv("GEHEIMNIS_SCHLUESSEL"));
    }

    /** @return der Speicher, oder {@code null} bei fehlendem oder ungültigem Schlüssel */
    public static Geheimnisspeicher ausBase64(String base64) {
        if (base64 == null || base64.isBlank()) return null;
        byte[] roh;
        try {
            roh = Base64.getDecoder().decode(base64.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (roh.length != 32) { Arrays.fill(roh, (byte) 0); return null; }
        SecretKey k = new SecretKeySpec(roh, "AES");
        Arrays.fill(roh, (byte) 0);
        return new Geheimnisspeicher(k);
    }

    /** Erzeugt einen neuen Schlüssel in der Form, die in die Umgebung gehört. */
    public static String neuerSchluesselBase64() {
        byte[] roh = new byte[32];
        new SecureRandom().nextBytes(roh);
        String s = Base64.getEncoder().encodeToString(roh);
        Arrays.fill(roh, (byte) 0);
        return s;
    }

    /**
     * Verschlüsselt einen Wert.
     *
     * @return {@code v1:<Base64 von Nonce||Chiffrat>}; bei leerer Eingabe {@code null},
     *         damit „kein Zugangsdatum hinterlegt" von „leeres Zugangsdatum"
     *         unterscheidbar bleibt.
     */
    public String verschluesseln(String klartext) {
        if (klartext == null || klartext.isEmpty()) return null;
        try {
            byte[] nonce = new byte[NONCE_LAENGE];
            zufall.nextBytes(nonce);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, schluessel, new GCMParameterSpec(PRUEFSUMME_BITS, nonce));
            byte[] chiffrat = c.doFinal(klartext.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            byte[] zusammen = new byte[nonce.length + chiffrat.length];
            System.arraycopy(nonce, 0, zusammen, 0, nonce.length);
            System.arraycopy(chiffrat, 0, zusammen, nonce.length, chiffrat.length);
            return MARKE + Base64.getEncoder().encodeToString(zusammen);
        } catch (Exception e) {
            // Absichtlich ohne den Klartext in der Meldung.
            throw new IllegalStateException("Zugangsdaten konnten nicht verschlüsselt werden.", e);
        }
    }

    /**
     * Entschlüsselt einen Wert.
     *
     * <p>Diese Methode ist der einzige Weg zurück und wird ausschließlich dort
     * aufgerufen, wo der Wert tatsächlich für eine Anmeldung gebraucht wird —
     * nie im Anzeigepfad der Oberfläche.
     *
     * @return der Klartext, oder {@code null}, wenn nichts hinterlegt ist
     * @throws IllegalStateException wenn das Chiffrat nicht zum Schlüssel passt
     *         oder verändert wurde
     */
    public String entschluesseln(String gespeichert) {
        if (gespeichert == null || gespeichert.isBlank()) return null;
        if (!gespeichert.startsWith(MARKE)) {
            throw new IllegalStateException(
                    "Unbekanntes Format der gespeicherten Zugangsdaten. "
                    + "Die Zugangsdaten müssen neu hinterlegt werden.");
        }
        try {
            byte[] zusammen = Base64.getDecoder().decode(gespeichert.substring(MARKE.length()));
            byte[] nonce = Arrays.copyOfRange(zusammen, 0, NONCE_LAENGE);
            byte[] chiffrat = Arrays.copyOfRange(zusammen, NONCE_LAENGE, zusammen.length);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, schluessel, new GCMParameterSpec(PRUEFSUMME_BITS, nonce));
            return new String(c.doFinal(chiffrat), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Die gespeicherten Zugangsdaten lassen sich nicht entschlüsseln. "
                    + "Entweder wurde der Schlüssel GEHEIMNIS_SCHLUESSEL geändert, oder "
                    + "der Datenbankeintrag wurde verändert. Bitte die Zugangsdaten "
                    + "neu hinterlegen.", e);
        }
    }
}
