package de.pinroboter.simulator;

import java.security.Security;

/**
 * Nimmt die Brainpool-Kurven aus der Sperrliste des JDK.
 *
 * <p>Java sperrt sie in seiner Standard-Sicherheitsrichtlinie: In
 * {@code jdk.tls.disabledAlgorithms} und {@code jdk.certpath.disabledAlgorithms}
 * steht {@code include jdk.disabled.namedCurves}, und dort sind die
 * Brainpool-Kurven aufgeführt. Ohne diese Freischaltung meldet BouncyCastle
 * {@code "contained no usable NamedGroup values"}, und der Handshake scheitert
 * mit {@code handshake_failure} — obwohl die Bibliothek die Kurven beherrscht.
 *
 * <p><b>Die Reihenfolge ist entscheidend:</b> Der Aufruf muss als Erstes in
 * {@code main()} stehen. Wird er später ausgeführt, hat der Krypto-Provider die
 * Kurvenliste bereits gelesen, und die Freischaltung bleibt wirkungslos. Der
 * Fehler äußert sich dann als „found no selectable cipher suite" — was nach
 * einem Konfigurationsfehler bei den Cipher-Suites aussieht und in die Irre
 * führt.
 *
 * <p>Gefunden beim Ausführen des Prototypen am 07.08.2026, nicht beim Lesen der
 * Dokumentation. Der Befund gehört in die Umsetzungsbeschreibung: Er kostet
 * andernfalls jeden, der das nachbaut, einen halben Tag.
 */
public final class BrainpoolFreischaltung {

    private BrainpoolFreischaltung() {}

    /** Schritt 1: Sperre aufheben. Muss vor jeder Provider-Nutzung laufen. */
    public static void anwenden() {
        Security.setProperty("jdk.disabled.namedCurves", "");
        for (String eigenschaft : new String[]{"jdk.tls.disabledAlgorithms",
                                               "jdk.certpath.disabledAlgorithms"}) {
            String wert = Security.getProperty(eigenschaft);
            if (wert == null) continue;
            String bereinigt = java.util.Arrays.stream(wert.split(","))
                    .map(String::trim)
                    .filter(e -> !e.contains("jdk.disabled.namedCurves"))
                    .filter(e -> !e.isBlank())
                    .reduce((a, b) -> a + ", " + b).orElse("");
            Security.setProperty(eigenschaft, bereinigt);
        }
    }

    /**
     * Schritt 2: Kurvenliste setzen — erst <b>nach</b> der Registrierung von
     * BouncyCastle. Zu früh gesetzt lehnt das Standard-JDK sie ab mit
     * „contains no supported named groups", weil es Brainpool nicht kennt.
     */
    /** Gibt aus, was wirklich gilt — Diagnose statt Vermutung. */
    public static void zustandAusgeben(String wer) {
        System.out.println("  [" + wer + "] jdk.disabled.namedCurves = \""
                + Security.getProperty("jdk.disabled.namedCurves") + "\"");
        String d = Security.getProperty("jdk.tls.disabledAlgorithms");
        System.out.println("  [" + wer + "] enthaelt namedCurves-Sperre: "
                + (d != null && d.contains("jdk.disabled.namedCurves")));
    }

    public static void kurvenlisteSetzen() {
        System.setProperty("jdk.tls.namedGroups", "brainpoolP256r1,brainpoolP384r1");
    }
}
