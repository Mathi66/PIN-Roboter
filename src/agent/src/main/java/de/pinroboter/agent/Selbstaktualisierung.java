package de.pinroboter.agent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * Beauftragt den Austausch dieses Agenten (FA-AGT-100, PO 14.08.2026).
 *
 * <h2>Warum der Agent das nicht selbst kann</h2>
 *
 * Ein Container kann sich nicht selbst austauschen — er müsste sich dafür
 * beenden und danach noch etwas tun. Diese Arbeit macht ein zweiter Dienst
 * neben ihm. Der Agent legt ihm einen <b>Auftrag in den Briefkasten</b>.
 *
 * <p>Bewusst kein Zugriff auf den Docker-Dienst: Wer ihn hat, ist faktisch
 * Administrator des Praxisrechners. Der Agent kann genau eines — eine Datei in
 * ein Verzeichnis legen, das nur er und der Aktualisierer sehen.
 *
 * <h2>Warum eine Datei und kein Netzwerkaufruf</h2>
 *
 * Zuerst war es ein HTTP-Aufruf an Watchtower, abgesichert mit einem
 * gemeinsamen Kennwort. Der Briefkasten ist einfacher und dichter zugleich:
 * kein Dienst, der auf Anfragen lauscht, kein Kennwort, das zueinander passen
 * muss, und keine Schnittstelle, die jemand im Container-Netz erreichen
 * könnte.
 *
 * <h2>Was im Auftrag steht</h2>
 *
 * Vier Zeilen: Programmablage, Benutzer, Token, Zielversion. Der Token ist
 * dabei, weil der Aktualisierer sich damit anmelden muss — er nimmt ihn über
 * die Standardeingabe entgegen, sodass er in keiner Prozessliste steht, und
 * <b>löscht die Datei als Erstes</b>.
 *
 * <p>Rechte {@code rw-------}: Nur der Eigentümer. Auf dem Weg dorthin liegt
 * ein Geheimnis, wenn auch nur für Sekunden.
 *
 * <h2>Was danach passiert</h2>
 *
 * Im Erfolgsfall wird der Container dieses Agenten ersetzt — der laufende
 * Vorgang endet <b>mitten im Satz</b>. Deshalb quittiert der Agent den Auftrag,
 * <b>bevor</b> er ihn ablegt. Ob es geklappt hat, entscheidet ohnehin nicht er,
 * sondern die Zentrale: an der Version, mit der er wiederkommt.
 */
final class Selbstaktualisierung {

    private final Path briefkasten;

    /**
     * @param verzeichnis das geteilte Volume, im Auslieferungszustand
     *                    {@code /auftrag}. Fehlt es, läuft auf diesem Rechner
     *                    kein Aktualisierungsdienst — dann muss von Hand
     *                    aktualisiert werden, und das ist eine Entscheidung,
     *                    kein Fehler.
     */
    Selbstaktualisierung(Path verzeichnis) {
        this.briefkasten = verzeichnis;
    }

    /** Ist auf diesem Rechner überhaupt ein Aktualisierungsdienst eingerichtet? */
    boolean eingerichtet() { return Files.isDirectory(briefkasten); }

    /**
     * Legt den Auftrag ab.
     *
     * @return {@code null}, wenn er abgelegt wurde, sonst der Grund im Klartext
     */
    String beauftragen(String registry, String benutzer, char[] token, String zielversion) {
        if (!eingerichtet()) {
            return "Auf diesem Rechner läuft kein Aktualisierungsdienst. "
                 + "Die Aktualisierung muss dort von Hand erfolgen: "
                 + "docker compose pull agent && docker compose up -d agent";
        }
        Path datei = briefkasten.resolve("auftrag");
        // Vier Zeilen, feste Reihenfolge. Kein JSON: Das Gegenstück ist ein
        // Shell-Skript, und „sed -n 1p" ist dort ehrlicher als ein
        // Zerleger, den jemand von Hand schreiben müsste.
        StringBuilder inhalt = new StringBuilder()
                .append(registry == null ? "" : registry).append('\n')
                .append(benutzer == null ? "" : benutzer).append('\n')
                .append(token == null ? "" : new String(token)).append('\n')
                .append(zielversion == null ? "" : zielversion).append('\n');
        try {
            Files.writeString(datei, inhalt, StandardCharsets.UTF_8);
            try {
                Files.setPosixFilePermissions(datei,
                        PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException aufWindows) {
                // Windows kennt keine POSIX-Rechte. Kein Grund abzubrechen.
            }
            return null;
        } catch (Exception e) {
            return "Der Auftrag ließ sich nicht ablegen: " + e.getMessage()
                 + " — ist " + briefkasten + " beschreibbar?";
        } finally {
            // Der Inhalt ist geschrieben; im Speicher hat er nichts mehr
            // verloren. Der StringBuilder lässt sich nicht sicher löschen —
            // das char[] des Aufrufers schon, und darum kümmert sich dieser.
            inhalt.setLength(0);
        }
    }

    /**
     * Räumt einen liegengebliebenen Auftrag weg.
     *
     * <p>Beim Start aufgerufen. Läuft auf diesem Rechner kein
     * Aktualisierungsdienst — oder ist er ausgefallen —, bliebe der Auftrag
     * samt Token liegen. Genau das soll nicht passieren.
     *
     * @return Meldung, wenn etwas zu tun war
     */
    String beimStartAufraeumen() {
        Path datei = briefkasten.resolve("auftrag");
        if (!Files.exists(datei)) return null;
        try {
            Files.delete(datei);
            return "Ein liegengebliebener Aktualisierungsauftrag wurde entfernt. "
                 + "Läuft der Dienst „aktualisierung“ auf diesem Rechner?";
        } catch (Exception e) {
            return "Ein Aktualisierungsauftrag ließ sich nicht entfernen: "
                 + e.getMessage() + " — bitte " + datei + " von Hand löschen.";
        }
    }

    /**
     * Was der Aktualisierer zuletzt gemeldet hat, oder {@code null}.
     *
     * <p>Nur im Fehlerfall interessant: Bei Erfolg ist dieser Agent längst
     * ersetzt, und der neue findet die Meldung beim Start vor.
     */
    String ergebnisLesen() {
        Path datei = briefkasten.resolve("ergebnis");
        try {
            if (!Files.isReadable(datei)) return null;
            String t = Files.readString(datei, StandardCharsets.UTF_8).trim();
            Files.deleteIfExists(datei);
            return t.isBlank() || t.equals("ok") ? null : t;
        } catch (Exception e) {
            return null;
        }
    }
}
