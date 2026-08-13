package de.pinroboter.agent;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Die Zugangsdaten zur Programmablage, die der Agent von der Zentrale bezieht.
 *
 * <h2>Worum es geht</h2>
 *
 * Die Programmablage ist privat (PO, 13.08.2026). Damit die selbsttätige
 * Aktualisierung an das Abbild kommt, braucht sie eine Anmeldung. Sie in jedes
 * Einrichtungspaket zu schreiben hieße: derselbe Token in jeder Praxis,
 * tauschbar nur vor Ort. Stattdessen holt der Agent ihn bei der Zentrale.
 *
 * <p>Die Prüfungen hier sind zur Hälfte Abschaltprüfungen: Der Schalter in der
 * Zentrale wirkt nur, wenn der Agent die Datei auch wieder <b>entfernt</b>.
 * Bliebe sie liegen, liefe die Aktualisierung weiter — und der Schalter wäre
 * eine Anzeige ohne Wirkung.
 */
class RegistrierzugangTest {

    private HttpServer server;
    private final AtomicReference<String> antwort = new AtomicReference<>("");
    private final AtomicReference<Integer> status = new AtomicReference<>(200);
    private final AtomicReference<String> gesehenerToken = new AtomicReference<>(null);
    private URI basis;

    @BeforeEach
    void zentraleNachbilden() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/agent/registry", aus -> {
            gesehenerToken.set(aus.getRequestHeaders().getFirst("X-Agent-Token"));
            byte[] b = antwort.get().getBytes(StandardCharsets.UTF_8);
            aus.sendResponseHeaders(status.get(), b.length);
            try (var o = aus.getResponseBody()) { o.write(b); }
        });
        server.start();
        basis = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
    }

    @AfterEach
    void abraeumen() { if (server != null) server.stop(0); }

    private Registrierzugang zugang(Path verzeichnis) {
        return new Registrierzugang(verzeichnis, HttpClient.newHttpClient(), basis);
    }

    @Test
    @DisplayName("Freigegebene Zugangsdaten landen im Docker-Format auf der Platte")
    void schreibt(@TempDir Path tmp) throws Exception {
        antwort.set("""
                {"verfuegbar":true,"registry":"ghcr.io","benutzer":"mathi66","token":"ghp-geheim-1"}""");
        var z = zugang(tmp);

        assertNotNull(z.abgleichen("agent-token-1"), "Die Änderung muss gemeldet werden");
        assertEquals("agent-token-1", gesehenerToken.get(),
                "Ohne Agent-Token dürfte die Zentrale nichts herausgeben");

        Path datei = tmp.resolve("config.json");
        assertTrue(Files.exists(datei));
        String inhalt = Files.readString(datei, StandardCharsets.UTF_8);

        // Watchtower liest genau dieses Format; ein eigenes wäre wertlos.
        String erwartet = Base64.getEncoder().encodeToString(
                "mathi66:ghp-geheim-1".getBytes(StandardCharsets.UTF_8));
        assertTrue(inhalt.contains("\"ghcr.io\""), inhalt);
        assertTrue(inhalt.contains(erwartet), "Die Anmeldung fehlt oder ist falsch kodiert");
        assertFalse(inhalt.contains("ghp-geheim-1"),
                "Der Token steht im Base64-Feld, nicht zusätzlich im Klartext");
    }

    @Test
    @DisplayName("Die Datei ist nicht für alle lesbar")
    void rechte(@TempDir Path tmp) throws Exception {
        antwort.set("""
                {"verfuegbar":true,"registry":"ghcr.io","benutzer":"u","token":"t"}""");
        zugang(tmp).abgleichen("a");
        Path datei = tmp.resolve("config.json");
        try {
            var rechte = Files.getPosixFilePermissions(datei);
            assertFalse(rechte.contains(java.nio.file.attribute.PosixFilePermission.OTHERS_READ),
                    "Zugangsdaten dürfen nicht welt-lesbar sein: " + rechte);
        } catch (UnsupportedOperationException aufWindows) {
            Assumptions.abort("Dateisystem ohne POSIX-Rechte");
        }
    }

    @Test
    @DisplayName("Ein zweiter Abgleich ohne Änderung meldet nichts")
    void keineWiederholungsmeldung(@TempDir Path tmp) {
        // Sonst stünde bei einem Takt von 60 s jede Minute dieselbe Zeile in
        // den Meldungen und verdeckte alles andere.
        antwort.set("""
                {"verfuegbar":true,"registry":"ghcr.io","benutzer":"u","token":"t"}""");
        var z = zugang(tmp);
        assertNotNull(z.abgleichen("a"));
        assertNull(z.abgleichen("a"));
        assertNull(z.abgleichen("a"));
    }

    @Test
    @DisplayName("Ein neuer Token wird übernommen")
    void tokenwechsel(@TempDir Path tmp) throws Exception {
        antwort.set("""
                {"verfuegbar":true,"registry":"ghcr.io","benutzer":"u","token":"alt"}""");
        var z = zugang(tmp);
        z.abgleichen("a");

        antwort.set("""
                {"verfuegbar":true,"registry":"ghcr.io","benutzer":"u","token":"neu"}""");
        assertNotNull(z.abgleichen("a"), "Der Wechsel muss gemeldet werden");

        String erwartet = Base64.getEncoder().encodeToString(
                "u:neu".getBytes(StandardCharsets.UTF_8));
        assertTrue(Files.readString(tmp.resolve("config.json")).contains(erwartet),
                "Ein Tausch in der Zentrale muss ohne Besuch vor Ort wirken — "
                + "das ist der ganze Zweck der Übung");
    }

    @Test
    @DisplayName("Abgeschaltet in der Zentrale heißt: Datei weg")
    void abschalten(@TempDir Path tmp) throws Exception {
        antwort.set("""
                {"verfuegbar":true,"registry":"ghcr.io","benutzer":"u","token":"t"}""");
        var z = zugang(tmp);
        z.abgleichen("a");
        assertTrue(Files.exists(tmp.resolve("config.json")));

        antwort.set("""
                {"verfuegbar":false}""");
        assertNotNull(z.abgleichen("a"), "Das Abschalten muss sichtbar gemeldet werden");
        assertFalse(Files.exists(tmp.resolve("config.json")),
                "Bleibt die Datei liegen, ist der Schalter in der Zentrale wirkungslos");
    }

    @Test
    @DisplayName("Unvollständige Angaben zählen wie abgeschaltet")
    void unvollstaendig(@TempDir Path tmp) throws Exception {
        antwort.set("""
                {"verfuegbar":true,"registry":"ghcr.io","benutzer":"u","token":""}""");
        var z = zugang(tmp);
        assertNull(z.abgleichen("a"));
        assertFalse(Files.exists(tmp.resolve("config.json")),
                "Eine halbe Anmeldung ist schlechter als keine — sie scheitert erst "
                + "beim Abholen des Abbilds");
    }

    @Test
    @DisplayName("Eine Zentrale, die den Pfad nicht kennt, ändert nichts")
    void aeltereZentrale(@TempDir Path tmp) throws Exception {
        // Beim Rollout laufen kurzzeitig neue Agenten gegen eine alte Zentrale.
        // Ein 404 darf keine vorhandene Anmeldung löschen.
        antwort.set("""
                {"verfuegbar":true,"registry":"ghcr.io","benutzer":"u","token":"t"}""");
        var z = zugang(tmp);
        z.abgleichen("a");

        status.set(404);
        antwort.set("nicht gefunden");
        assertNull(z.abgleichen("a"));
        assertTrue(Files.exists(tmp.resolve("config.json")),
                "Der vorhandene Stand muss stehen bleiben");
    }

    @Test
    @DisplayName("Eine nicht erreichbare Zentrale ändert nichts")
    void zentraleWeg(@TempDir Path tmp) throws Exception {
        antwort.set("""
                {"verfuegbar":true,"registry":"ghcr.io","benutzer":"u","token":"t"}""");
        var z = zugang(tmp);
        z.abgleichen("a");
        server.stop(0);
        server = null;

        assertNull(z.abgleichen("a"), "Ein Netzwerkausfall ist keine Zustandsänderung");
        assertTrue(Files.exists(tmp.resolve("config.json")));
    }

    @Test
    @DisplayName("Ohne je Zugangsdaten gesehen zu haben wird nichts gemeldet")
    void stillImRegelfall(@TempDir Path tmp) {
        // Der Regelfall beim Kunden: Die Weitergabe ist gar nicht eingeschaltet.
        // Dann darf der Agent nicht bei jedem Takt etwas zu sagen haben.
        antwort.set("""
                {"verfuegbar":false}""");
        var z = zugang(tmp);
        assertNull(z.abgleichen("a"));
        assertNull(z.abgleichen("a"));
    }
}
