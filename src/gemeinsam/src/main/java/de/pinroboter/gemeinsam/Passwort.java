package de.pinroboter.gemeinsam;

import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Passwortspeicherung mit <b>Argon2id</b>.
 *
 * <p>NFA-SIC-010 verlangt ein „für Passwörter vorgesehenes, rechenaufwendiges
 * Verfahren". Argon2id ist der Gewinner der Password Hashing Competition und die
 * Empfehlung des BSI wie auch des OWASP. Es ist absichtlich langsam und
 * speicherhungrig — beides bremst das massenhafte Durchprobieren gestohlener
 * Hashes aus.
 *
 * <p>Verwendet wird die Argon2-Umsetzung aus BouncyCastle. Die Bibliothek ist
 * für das Produkt ohnehin unverzichtbar (Brainpool-TLS, ADR-0006), also entsteht
 * hier keine zusätzliche Abhängigkeit.
 *
 * <p>Das Format ist selbstbeschreibend: Es enthält die Parameter, mit denen der
 * Hash entstand. Damit lassen sich die Kosten später erhöhen, ohne bestehende
 * Anmeldungen zu brechen — alte Hashes bleiben prüfbar.
 */
public final class Passwort {

    // Parameter nach OWASP-Empfehlung für Argon2id (Stand 2026):
    // 19 MiB Speicher, 2 Durchgänge, Parallelität 1.
    private static final int SPEICHER_KIB = 19 * 1024;
    private static final int DURCHGAENGE = 2;
    private static final int PARALLEL = 1;
    private static final int SALZ_LAENGE = 16;
    private static final int HASH_LAENGE = 32;

    private Passwort() {}

    /** @return selbstbeschreibender Hash, gefahrlos speicherbar */
    public static String hashen(char[] passwort) {
        byte[] salz = new byte[SALZ_LAENGE];
        new SecureRandom().nextBytes(salz);
        byte[] hash = rechnen(passwort, salz, SPEICHER_KIB, DURCHGAENGE, PARALLEL);
        var b64 = Base64.getEncoder().withoutPadding();
        return "$argon2id$v=19$m=%d,t=%d,p=%d$%s$%s".formatted(
                SPEICHER_KIB, DURCHGAENGE, PARALLEL,
                b64.encodeToString(salz), b64.encodeToString(hash));
    }

    /**
     * Prüft ein Passwort gegen einen gespeicherten Hash.
     *
     * <p>Gibt bei jedem Fehler schlicht {@code false} zurück — nie eine
     * Unterscheidung zwischen „Benutzer unbekannt", „Hash beschädigt" und
     * „Passwort falsch". Solche Unterschiede sind für Angreifer verwertbar.
     */
    public static boolean pruefen(char[] passwort, String gespeichert) {
        if (passwort == null || gespeichert == null) return false;
        try {
            String[] teile = gespeichert.split("\\$");
            if (teile.length != 6 || !"argon2id".equals(teile[1])) return false;

            int speicher = 0, durchgaenge = 0, parallel = 0;
            for (String p : teile[3].split(",")) {
                String[] kv = p.split("=");
                switch (kv[0]) {
                    case "m" -> speicher = Integer.parseInt(kv[1]);
                    case "t" -> durchgaenge = Integer.parseInt(kv[1]);
                    case "p" -> parallel = Integer.parseInt(kv[1]);
                    default -> { }
                }
            }
            var b64 = Base64.getDecoder();
            byte[] salz = b64.decode(teile[4]);
            byte[] erwartet = b64.decode(teile[5]);
            byte[] erhalten = rechnen(passwort, salz, speicher, durchgaenge, parallel);
            return gleichInKonstanterZeit(erwartet, erhalten);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Mindestanforderungen an ein neues Passwort.
     *
     * <p>Bewusst nur Länge, keine Zeichenklassen-Regeln: Erzwungene Sonderzeichen
     * führen erfahrungsgemäß zu {@code Passwort1!} und zu Zetteln am Monitor. Das
     * BSI empfiehlt seit 2020 Länge statt Komplexität.
     *
     * @return null wenn in Ordnung, sonst eine Meldung, die sagt was zu tun ist
     */
    public static String pruefeStaerke(char[] passwort) {
        if (passwort == null || passwort.length < 12) {
            return "Das Passwort muss mindestens 12 Zeichen lang sein. "
                 + "Eine Folge mehrerer Wörter ist leichter zu merken und sicherer "
                 + "als ein kurzes Passwort mit Sonderzeichen.";
        }
        if (passwort.length > 256) {
            return "Das Passwort darf höchstens 256 Zeichen lang sein.";
        }
        String s = new String(passwort).toLowerCase();
        for (String schwach : new String[]{"passwort", "password", "123456", "qwertz", "pinroboter"}) {
            if (s.contains(schwach)) {
                return "Das Passwort enthält eine sehr häufige Zeichenfolge (\"" + schwach
                     + "\"). Bitte wählen Sie eine andere.";
            }
        }
        return null;
    }

    private static byte[] rechnen(char[] passwort, byte[] salz,
                                  int speicherKiB, int durchgaenge, int parallel) {
        Argon2Parameters p = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withSalt(salz)
                .withMemoryAsKB(speicherKiB)
                .withIterations(durchgaenge)
                .withParallelism(parallel)
                .build();
        Argon2BytesGenerator g = new Argon2BytesGenerator();
        g.init(p);
        byte[] ergebnis = new byte[HASH_LAENGE];
        byte[] roh = new String(passwort).getBytes(StandardCharsets.UTF_8);
        try {
            g.generateBytes(roh, ergebnis);
        } finally {
            java.util.Arrays.fill(roh, (byte) 0);
        }
        return ergebnis;
    }

    private static boolean gleichInKonstanterZeit(byte[] a, byte[] b) {
        if (a.length != b.length) return false;
        int unterschied = 0;
        for (int i = 0; i < a.length; i++) unterschied |= a[i] ^ b[i];
        return unterschied == 0;
    }
}
