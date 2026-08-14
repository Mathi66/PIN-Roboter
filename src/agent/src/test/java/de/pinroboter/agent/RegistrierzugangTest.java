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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Der Weg der Zugangsdaten zur Programmablage (FA-AGT-100, PO 14.08.2026).
 *
 * <h2>Was sich am 14.08.2026 geändert hat</h2>
 *
 * Vorher lagen die Zugangsdaten <b>dauerhaft</b> auf dem Praxisrechner, damit
 * der nächtliche Lauf sie lesen konnte. Diesen Lauf gibt es nicht mehr;
 * aktualisiert wird nur auf Auftrag. Damit werden sie nur im Moment des
 * Updates gebraucht.
 *
 * <p>Die Prüfungen hier sind deshalb zur Hälfte Aufräumprüfungen: Ein Token,
 * der nach dem Vorgang liegen bleibt, ist der eigentliche Fehler — und er
 * fällt niemandem auf, weil alles funktioniert.
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

    private Registrierzugang zugang() {
        return new Registrierzugang(HttpClient.newHttpClient(), basis);
    }

    // ────────────────────────────────────────────── Holen bei der Zentrale

    @Test
    @DisplayName("Freigegebene Zugangsdaten kommen an — und nichts wird gespeichert")
    void holt() {
        antwort.set("""
                {"verfuegbar":true,"registry":"ghcr.io","benutzer":"mathi66","token":"ghp-geheim-1"}""");
        var r = zugang();
        var z = r.holen("agent-token-1");

        assertNotNull(z);
        assertEquals("ghcr.io", z.registry());
        assertEquals("mathi66", z.benutzer());
        assertArrayEquals("ghp-geheim-1".toCharArray(), z.token());
        assertEquals("agent-token-1", gesehenerToken.get(),
                "Ohne Agent-Token dürfte die Zentrale nichts herausgeben");
        assertNull(r.letzterFehler());
    }

    @Test
    @DisplayName("Der Token lässt sich aus dem Speicher löschen")
    void tokenLoeschbar() {
        // Ein String bliebe im Zeichenvorrat liegen und ließe sich nicht
        // überschreiben — dieselbe Überlegung wie bei den PINs (ADR-0009).
        antwort.set("""
                {"verfuegbar":true,"registry":"ghcr.io","benutzer":"u","token":"geheim"}""");
        var z = zugang().holen("a");
        z.loeschen();
        assertArrayEquals(new char[]{0, 0, 0, 0, 0, 0}, z.token());
    }

    @Test
    @DisplayName("Ohne Auftrag gibt die Zentrale nichts heraus")
    void ohneAuftrag() {
        // Die Zentrale antwortet mit 403, wenn kein Auftrag vorliegt. Das ist
        // der Kern der Änderung: Auf einem Rechner, an dem niemand
        // aktualisiert, liegt kein Token.
        status.set(403);
        antwort.set("""
                {"verfuegbar":false,"grund":"kein Auftrag"}""");
        var r = zugang();
        assertNull(r.holen("a"));
        assertTrue(r.letzterFehler().contains("Auftrag"), r.letzterFehler());
    }

    @Test
    @DisplayName("Abgeschaltete Weitergabe wird verständlich gemeldet")
    void abgeschaltet() {
        antwort.set("""
                {"verfuegbar":false}""");
        var r = zugang();
        assertNull(r.holen("a"));
        assertTrue(r.letzterFehler().contains("abgeschaltet"), r.letzterFehler());
        assertTrue(r.letzterFehler().contains("Produktversion"),
                "Die Meldung muss sagen, wo es einzuschalten ist: " + r.letzterFehler());
    }

    @Test
    @DisplayName("Unvollständige Angaben werden abgelehnt statt halb benutzt")
    void unvollstaendig() {
        antwort.set("""
                {"verfuegbar":true,"registry":"ghcr.io","benutzer":"u","token":""}""");
        var r = zugang();
        assertNull(r.holen("a"));
        assertNotNull(r.letzterFehler());
    }

    @Test
    @DisplayName("Eine nicht erreichbare Zentrale meldet einen lesbaren Grund")
    void zentraleWeg() {
        server.stop(0);
        server = null;
        var r = zugang();
        assertNull(r.holen("a"));
        assertNotNull(r.letzterFehler());
        assertFalse(r.letzterFehler().contains("null"),
                "Die Meldung muss lesbar sein: " + r.letzterFehler());
    }

    // ───────────────────────────────────────────── Der Briefkasten

    @Test
    @DisplayName("Der Auftrag landet im Briefkasten und ist nicht welt-lesbar")
    void auftragAblegen(@TempDir Path tmp) throws Exception {
        var s = new Selbstaktualisierung(tmp);
        assertNull(s.beauftragen("ghcr.io", "u", "geheim".toCharArray(), "1.2.0"));

        Path datei = tmp.resolve("auftrag");
        assertTrue(Files.exists(datei));
        var zeilen = Files.readAllLines(datei);
        assertEquals("ghcr.io", zeilen.get(0));
        assertEquals("u", zeilen.get(1));
        assertEquals("geheim", zeilen.get(2));
        assertEquals("1.2.0", zeilen.get(3));

        try {
            var rechte = Files.getPosixFilePermissions(datei);
            assertFalse(rechte.contains(java.nio.file.attribute.PosixFilePermission.OTHERS_READ),
                    "Im Auftrag steht ein Token: " + rechte);
            assertFalse(rechte.contains(java.nio.file.attribute.PosixFilePermission.GROUP_READ),
                    "Auch die Gruppe hat hier nichts zu suchen: " + rechte);
        } catch (UnsupportedOperationException aufWindows) {
            Assumptions.abort("Dateisystem ohne POSIX-Rechte");
        }
    }

    @Test
    @DisplayName("Ohne Aktualisierungsdienst wird kein Auftrag abgelegt")
    void ohneDienst(@TempDir Path tmp) {
        // Manche Häuser entfernen die Dienste bewusst. Dann darf kein Token
        // ins Nichts geschrieben werden — und die Meldung muss den Weg von
        // Hand nennen.
        var s = new Selbstaktualisierung(tmp.resolve("gibt-es-nicht"));
        assertFalse(s.eingerichtet());
        String f = s.beauftragen("ghcr.io", "u", "geheim".toCharArray(), "1.2.0");
        assertNotNull(f);
        assertTrue(f.contains("compose pull"), "Die Meldung muss den Handweg nennen: " + f);
    }

    @Test
    @DisplayName("Ein liegengebliebener Auftrag wird beim Start entfernt")
    void liegengebliebenes(@TempDir Path tmp) throws Exception {
        // Der Fall, auf den es ankommt: Nach einem geglückten Update wurde der
        // alte Agent ausgetauscht, bevor er aufräumen konnte. Bliebe die Datei
        // liegen, läge auch der Token weiter da.
        Files.writeString(tmp.resolve("auftrag"), "ghcr.io\\nu\\ngeheim\\n1.2.0\\n");
        var s = new Selbstaktualisierung(tmp);

        String m = s.beimStartAufraeumen();
        assertNotNull(m, "Das Aufräumen muss sichtbar sein, nicht stillschweigend");
        assertFalse(Files.exists(tmp.resolve("auftrag")),
                "Ein Token darf nach dem Vorgang nicht liegen bleiben");
    }

    @Test
    @DisplayName("Ohne liegengebliebenen Auftrag gibt es nichts zu melden")
    void nichtsLiegengeblieben(@TempDir Path tmp) {
        assertNull(new Selbstaktualisierung(tmp).beimStartAufraeumen());
    }

    @Test
    @DisplayName("Ein Fehlschlag des Aktualisierers erreicht den neuen Agenten")
    void ergebnisWirdGelesen(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("ergebnis"), "Die Programmablage hat abgelehnt.");
        var s = new Selbstaktualisierung(tmp);

        assertEquals("Die Programmablage hat abgelehnt.", s.ergebnisLesen());
        assertFalse(Files.exists(tmp.resolve("ergebnis")),
                "Gelesen ist erledigt — sonst meldet jeder Neustart dasselbe");
        assertNull(s.ergebnisLesen());
    }

    @Test
    @DisplayName("Ein erfolgreicher Lauf meldet nichts")
    void erfolgIstStill(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("ergebnis"), "ok");
        assertNull(new Selbstaktualisierung(tmp).ergebnisLesen(),
                "Erfolg braucht keine Meldung — die neue Version ist die Meldung");
    }
}
