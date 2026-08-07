package de.pinroboter.agent;

import javax.crypto.Cipher;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/**
 * Das Schlüsselpaar des Agenten für die Ende-zu-Ende-Übergabe der PIN
 * (ADR-0009, FA-PIN-010).
 *
 * <h2>Wozu</h2>
 *
 * Die PIN wird in der Oberfläche eingegeben — die läuft in der Zentrale. Die
 * Zentrale darf die PIN aber <b>nie</b> sehen (ADR-0002, Sicherheitsleitplanke).
 * Bei 1.000 Standorten wäre eine Zentrale mit allen PINs ein Ziel, dessen
 * Kompromittierung hunderte Praxen lahmlegt.
 *
 * <p>Lösung: Der Browser verschlüsselt mit dem <b>öffentlichen</b> Schlüssel
 * dieses Agenten. Die Zentrale reicht das Chiffrat weiter und kann es nicht
 * lesen. Nur der Agent hat den privaten Teil — und der verlässt sein Gerät nie.
 *
 * <h2>Warum RSA-OAEP und nicht etwas Moderneres</h2>
 *
 * Nicht aus Vorliebe: <b>WebCrypto</b> im Browser beherrscht für asymmetrische
 * Verschlüsselung ausschließlich RSA-OAEP. ECIES wäre schlanker, ist dort aber
 * nicht verfügbar, und eine eigene Kryptographie-Bibliothek in die Oberfläche zu
 * laden wäre die schlechtere Wahl — sie müsste ausgeliefert, gepflegt und
 * geprüft werden.
 *
 * <p>3072 Bit statt 2048: Die BSI-Empfehlung {@code TR-02102-1} nennt 2048 nur
 * noch bis 2029 als ausreichend. Eine PIN, die heute hinterlegt wird, soll
 * länger sicher sein als das Gerät, auf dem sie liegt.
 *
 * <h2>Der private Schlüssel</h2>
 *
 * Liegt im Datenverzeichnis des Agenten, in einer Datei, die nur er lesen darf.
 * Er wird beim ersten Start erzeugt und danach wiederverwendet — sonst wäre nach
 * jedem Neustart jede hinterlegte PIN unbrauchbar.
 */
public final class Schluesselpaar {

    /** Schlüssellänge. Siehe Klassenkommentar zur Begründung. */
    private static final int BITS = 3072;

    private static final String VERFAHREN = "RSA/ECB/OAEPPadding";

    /**
     * OAEP-Parameter, <b>vollständig ausgeschrieben</b>.
     *
     * <p>Das ist kein Detail, sondern der Punkt, an dem diese Umsetzung schon
     * einmal gescheitert ist: Javas Kurzform {@code OAEPWithSHA-256AndMGF1Padding}
     * setzt nur den <em>Nachrichten</em>-Hash auf SHA-256 und lässt MGF1
     * stillschweigend auf <b>SHA-1</b>. WebCrypto verwendet für beides SHA-256.
     * Beide Seiten sind für sich schlüssig — zusammen passen sie nicht, und der
     * Fehler zeigt sich erst als „konnte nicht entschlüsselt werden" am echten
     * Browser.
     *
     * <p>Ein Modultest, der mit derselben Java-Kurzform ver- und entschlüsselt,
     * findet das <b>nicht</b>: Er ist auf beiden Seiten gleich falsch. Deshalb
     * prüft der Systemnachweis gegen OpenSSL, also gegen eine unabhängige
     * Umsetzung.
     */
    private static final javax.crypto.spec.OAEPParameterSpec OAEP =
            new javax.crypto.spec.OAEPParameterSpec("SHA-256", "MGF1",
                    java.security.spec.MGF1ParameterSpec.SHA256,
                    javax.crypto.spec.PSource.PSpecified.DEFAULT);

    private final KeyPair paar;

    private Schluesselpaar(KeyPair paar) { this.paar = paar; }

    /**
     * Lädt das Schlüsselpaar oder erzeugt es beim ersten Start.
     *
     * @param datei Ablage des privaten Schlüssels im Datenverzeichnis
     */
    public static Schluesselpaar ladenOderErzeugen(Path datei) throws GeneralSecurityException {
        try {
            if (Files.exists(datei)) {
                byte[] roh = Base64.getDecoder().decode(Files.readString(datei).trim());
                var kf = KeyFactory.getInstance("RSA");
                PrivateKey privat = kf.generatePrivate(new PKCS8EncodedKeySpec(roh));
                // Den oeffentlichen Teil aus dem privaten ableiten - er steht in
                // einem RSA-Schluessel ohnehin drin, und eine zweite Datei waere
                // eine zweite Stelle, an der etwas auseinanderlaufen kann.
                var rsa = (java.security.interfaces.RSAPrivateCrtKey) privat;
                PublicKey oeffentlich = kf.generatePublic(
                        new java.security.spec.RSAPublicKeySpec(
                                rsa.getModulus(), rsa.getPublicExponent()));
                return new Schluesselpaar(new KeyPair(oeffentlich, privat));
            }
        } catch (Exception e) {
            // Eine unlesbare Datei wird NICHT stillschweigend ersetzt: Damit
            // waeren alle hinterlegten PINs unbrauchbar, ohne dass jemand
            // erfaehrt, warum. Lieber laut scheitern.
            throw new GeneralSecurityException(
                    "Der private Schlüssel in " + datei + " ist nicht lesbar. "
                    + "Wurde die Datei verändert? Bei Verlust müssen alle PINs neu "
                    + "hinterlegt werden — dazu die Datei entfernen und den Agenten "
                    + "neu starten.", e);
        }

        var g = KeyPairGenerator.getInstance("RSA");
        g.initialize(BITS);
        KeyPair neu = g.generateKeyPair();
        try {
            Files.createDirectories(datei.getParent());
            Files.writeString(datei, Base64.getEncoder().encodeToString(neu.getPrivate().getEncoded()));
            // Nur der Eigentuemer darf lesen. Auf Dateisystemen ohne
            // POSIX-Rechte (Windows-Volumes) schlaegt das fehl - das ist
            // hinnehmbar, aber es soll auffallen.
            try {
                Files.setPosixFilePermissions(datei,
                        java.util.Set.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                                         java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
            } catch (UnsupportedOperationException | java.io.IOException e) {
                System.err.println("  Hinweis: Die Dateirechte des privaten Schlüssels konnten "
                        + "nicht eingeschränkt werden (" + e.getMessage() + "). "
                        + "Auf diesem Dateisystem ist das nicht durchsetzbar.");
            }
        } catch (java.io.IOException e) {
            throw new GeneralSecurityException(
                    "Der private Schlüssel konnte nicht gespeichert werden: " + e.getMessage()
                    + " — ohne Ablage wäre nach jedem Neustart jede hinterlegte PIN unbrauchbar.", e);
        }
        return new Schluesselpaar(neu);
    }

    /** Nur für Tests: ein Paar im Arbeitsspeicher. */
    static Schluesselpaar imSpeicher() throws GeneralSecurityException {
        var g = KeyPairGenerator.getInstance("RSA");
        g.initialize(BITS);
        return new Schluesselpaar(g.generateKeyPair());
    }

    /**
     * Der öffentliche Schlüssel, wie ihn WebCrypto erwartet.
     *
     * <p>SubjectPublicKeyInfo (X.509), Base64. Der Browser liest ihn mit
     * {@code crypto.subtle.importKey("spki", …)}.
     */
    public String oeffentlichBase64() {
        return Base64.getEncoder().encodeToString(paar.getPublic().getEncoded());
    }

    /**
     * Entschlüsselt ein Chiffrat aus der Oberfläche.
     *
     * @return die PIN als Zeichenfeld — vom Aufrufer nach Gebrauch zu überschreiben
     * @throws GeneralSecurityException wenn das Chiffrat nicht zu diesem
     *         Schlüssel gehört oder verändert wurde
     */
    public char[] entschluesseln(String chiffratBase64) throws GeneralSecurityException {
        Cipher c = Cipher.getInstance(VERFAHREN);
        c.init(Cipher.DECRYPT_MODE, paar.getPrivate(), OAEP);
        byte[] klar = c.doFinal(Base64.getDecoder().decode(chiffratBase64));
        char[] pin = new String(klar, StandardCharsets.UTF_8).toCharArray();
        java.util.Arrays.fill(klar, (byte) 0);
        return pin;
    }

    /** Nur für Tests: verschlüsselt wie der Browser es täte. */
    String verschluesseln(String klartext) throws GeneralSecurityException {
        Cipher c = Cipher.getInstance(VERFAHREN);
        c.init(Cipher.ENCRYPT_MODE, paar.getPublic(), OAEP);
        return Base64.getEncoder().encodeToString(
                c.doFinal(klartext.getBytes(StandardCharsets.UTF_8)));
    }
}
