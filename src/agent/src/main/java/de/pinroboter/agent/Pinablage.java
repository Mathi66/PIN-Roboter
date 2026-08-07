package de.pinroboter.agent;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;

/**
 * Verschlüsselte Ablage der SMC-B-PINs beim Agenten (ADR-0009, NFA-SIC-010).
 *
 * <p>Die PIN muss einen Neustart überstehen — sonst müsste sie nach jedem
 * Containerwechsel neu hinterlegt werden, und in einer Praxis ohne IT-Personal
 * bedeutet das einen Anruf beim Dienstleister.
 *
 * <p><b>Verschlüsselt mit AES-256-GCM</b>, Schlüssel aus
 * {@code PIN_ABLAGE_SCHLUESSEL}. Dieselbe Bauart wie beim Geräte-Zugang der
 * Zentrale (ADR-0008), aber ein <b>getrennter Schlüssel</b>: Wer den einen hat,
 * bekommt damit nicht auch den anderen.
 *
 * <p><b>Ohne Schlüssel wird nichts gespeichert.</b> Die PIN bleibt dann nur im
 * Arbeitsspeicher und ist nach einem Neustart weg. Das ist die richtige
 * Richtung: Lieber eine PIN neu hinterlegen als eine im Klartext auf der Platte.
 */
public final class Pinablage {

    private static final String MARKE = "v1:";
    private static final int NONCE = 12;

    private final Path datei;
    private final SecretKey schluessel;
    private final SecureRandom zufall = new SecureRandom();

    private Pinablage(Path datei, SecretKey schluessel) {
        this.datei = datei;
        this.schluessel = schluessel;
    }

    public static Pinablage ausUmgebung(Path datei) {
        String b64 = System.getenv("PIN_ABLAGE_SCHLUESSEL");
        if (b64 == null || b64.isBlank()) {
            System.err.println("  Hinweis: PIN_ABLAGE_SCHLUESSEL ist nicht gesetzt. "
                    + "Hinterlegte PINs bleiben nur im Arbeitsspeicher und sind nach einem "
                    + "Neustart weg. Erzeugen mit: openssl rand -base64 32");
            return new Pinablage(datei, null);
        }
        try {
            byte[] roh = Base64.getDecoder().decode(b64.trim());
            if (roh.length != 32) {
                System.err.println("  Hinweis: PIN_ABLAGE_SCHLUESSEL hat nicht 32 Byte. "
                        + "Die Ablage bleibt abgeschaltet.");
                return new Pinablage(datei, null);
            }
            var k = new SecretKeySpec(roh, "AES");
            Arrays.fill(roh, (byte) 0);
            return new Pinablage(datei, k);
        } catch (IllegalArgumentException e) {
            System.err.println("  Hinweis: PIN_ABLAGE_SCHLUESSEL ist kein gültiges Base64. "
                    + "Die Ablage bleibt abgeschaltet.");
            return new Pinablage(datei, null);
        }
    }

    public boolean aktiv() { return schluessel != null; }

    /** Legt eine PIN ab. Ohne Schlüssel folgenlos — die PIN bleibt im Arbeitsspeicher. */
    public synchronized void speichern(String kartenhandle, char[] pin) {
        if (schluessel == null) return;
        var alle = alleRoh();
        alle.put(kartenhandle, chiffrieren(new String(pin)));
        schreiben(alle);
    }

    public synchronized void entfernen(String kartenhandle) {
        if (schluessel == null) return;
        var alle = alleRoh();
        if (alle.remove(kartenhandle) != null) schreiben(alle);
    }

    /** Lädt alle abgelegten PINs beim Start. */
    public synchronized Map<String, char[]> alleLaden() {
        Map<String, char[]> ergebnis = new LinkedHashMap<>();
        if (schluessel == null) return ergebnis;
        for (var e : alleRoh().entrySet()) {
            try {
                ergebnis.put(e.getKey(), entschluesseln(e.getValue()).toCharArray());
            } catch (Exception ex) {
                // Ein einzelner unlesbarer Eintrag darf nicht alle anderen
                // mitnehmen - er wird gemeldet und uebersprungen.
                System.err.println("  PIN für " + e.getKey() + " ist nicht lesbar "
                        + "(Schlüssel geändert?). Sie muss neu hinterlegt werden.");
            }
        }
        return ergebnis;
    }

    // -------------------------------------------------------------- Innenleben

    private Map<String, String> alleRoh() {
        Map<String, String> m = new LinkedHashMap<>();
        try {
            if (!Files.exists(datei)) return m;
            for (String zeile : Files.readAllLines(datei)) {
                int i = zeile.indexOf('=');
                if (i > 0) m.put(zeile.substring(0, i), zeile.substring(i + 1));
            }
        } catch (Exception ignored) { }
        return m;
    }

    private void schreiben(Map<String, String> alle) {
        StringBuilder s = new StringBuilder();
        alle.forEach((k, v) -> s.append(k).append('=').append(v).append('\n'));
        try {
            Files.createDirectories(datei.getParent());
            Files.writeString(datei, s.toString());
            try {
                Files.setPosixFilePermissions(datei,
                        Set.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                               java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
            } catch (UnsupportedOperationException | java.io.IOException ignored) { }
        } catch (Exception e) {
            System.err.println("  PIN-Ablage nicht schreibbar: " + e.getMessage());
        }
    }

    private String chiffrieren(String klar) {
        try {
            byte[] nonce = new byte[NONCE];
            zufall.nextBytes(nonce);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, schluessel, new GCMParameterSpec(128, nonce));
            byte[] ch = c.doFinal(klar.getBytes(StandardCharsets.UTF_8));
            byte[] zus = new byte[nonce.length + ch.length];
            System.arraycopy(nonce, 0, zus, 0, nonce.length);
            System.arraycopy(ch, 0, zus, nonce.length, ch.length);
            return MARKE + Base64.getEncoder().encodeToString(zus);
        } catch (Exception e) {
            throw new IllegalStateException("PIN konnte nicht verschlüsselt werden.", e);
        }
    }

    private String entschluesseln(String gespeichert) throws Exception {
        if (!gespeichert.startsWith(MARKE)) throw new IllegalStateException("unbekanntes Format");
        byte[] zus = Base64.getDecoder().decode(gespeichert.substring(MARKE.length()));
        byte[] nonce = Arrays.copyOfRange(zus, 0, NONCE);
        byte[] ch = Arrays.copyOfRange(zus, NONCE, zus.length);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, schluessel, new GCMParameterSpec(128, nonce));
        return new String(c.doFinal(ch), StandardCharsets.UTF_8);
    }
}
