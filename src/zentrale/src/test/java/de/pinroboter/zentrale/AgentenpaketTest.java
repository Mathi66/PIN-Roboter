package de.pinroboter.zentrale;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Das Einrichtungspaket für einen Standort-Agenten (PO, 13.08.2026).
 *
 * <p>Geprüft wird der <b>Inhalt</b>, nicht das Herunterladen: Was in diesen
 * Dateien steht, tippt sonst jemand von Hand ab — ein falscher Wert darin
 * kostet in einer Praxis eine Stunde Suche.
 */
class AgentenpaketTest {

    private static final String ZENTRALE = "https://pin-roboter.beispiel.de/";
    private static final String GEHEIM   = "AbCdEf1234567890";

    private Map<String, String> paket() {
        return entpacken(Agentenpaket.bauen(ZENTRALE, GEHEIM,
                "Hausarztpraxis Nordstadt", "Agent Nordstadt", "0.3.2"));
    }

    @Test
    @DisplayName("Das Paket enthält genau die vier Dateien, die jemand braucht")
    void vierDateien() {
        var p = paket();
        assertEquals(4, p.size(), "Mehr Dateien heißt mehr Fragen: " + p.keySet());
        assertTrue(p.containsKey("pin-roboter-agent/docker-compose.yml"));
        assertTrue(p.containsKey("pin-roboter-agent/.env"));
        assertTrue(p.containsKey("pin-roboter-agent/ANLEITUNG.md"));
        // Seit dem 14.08.2026 dazu: Das Aktualisierungsskript liegt offen
        // dabei, statt in einem fremden Abbild zu stecken. Wer es liest, weiß
        // danach genau, was auf seinem Rechner passiert.
        assertTrue(p.containsKey("pin-roboter-agent/aktualisieren.sh"));
    }

    @Test
    @DisplayName("Adresse der Zentrale und Zugangscode sind eingetragen")
    void vorausgefuellt() {
        String env = paket().get("pin-roboter-agent/.env");
        assertTrue(env.contains("ZENTRALE_URL=" + ZENTRALE), env);
        assertTrue(env.contains("REGISTRIERUNG=" + GEHEIM),
                "Der Zugangscode gehört hinein — sonst tippt ihn jemand ab und vertippt sich");
    }

    @Test
    @DisplayName("Was der Betreiber selbst wissen muss, steht als leeres Feld da")
    void auszufuellendesIstLeer() {
        String env = paket().get("pin-roboter-agent/.env");
        // Kein erfundener Wert: Eine falsche Konnektor-Adresse, die plausibel
        // aussieht, kostet mehr Zeit als ein leeres Feld.
        assertTrue(env.contains("CA_URL="), env);
        assertTrue(env.contains("TERMINAL_SMCB_URL="), env);
        assertTrue(env.contains("PIN_ABLAGE_SCHLUESSEL="), env);
    }

    @Test
    @DisplayName("Keine PIN und kein Kennwort im Paket")
    void keineGeheimnisseAusserDemZugangscode() {
        var p = paket();
        for (var e : p.entrySet()) {
            String t = e.getValue();
            assertFalse(t.contains("PIN_card_"), e.getKey() + " enthält eine Karten-PIN");
            assertFalse(t.matches("(?s).*\\bPIN=\\d{6}\\b.*"), e.getKey() + " enthält eine PIN");
            assertFalse(t.contains("DB_PASSWORD"), e.getKey() + " nennt das Datenbankkennwort");
            assertFalse(t.contains("GEHEIMNIS_SCHLUESSEL="),
                    e.getKey() + " nennt den Schlüssel der Zentrale");
        }
    }

    @Test
    @DisplayName("Das Compose zieht den freigegebenen Stand, nie :latest")
    void freigegebenerStand() {
        String yml = paket().get("pin-roboter-agent/docker-compose.yml");
        assertTrue(yml.contains("pin-roboter-agent:stabil"), yml);
        assertFalse(yml.contains(":latest"),
                "„latest“ wäre jede Veröffentlichung, auch die ungeprüfte");
        assertTrue(yml.contains("Erzeugt mit Stand 0.3.2"),
                "Womit das Paket erzeugt wurde, gehört dokumentiert");
    }

    @Test
    @DisplayName("Der Aktualisierer bekommt den Docker-Socket nicht direkt")
    void keinDirekterSocket() {
        // Wer den Socket hat, ist faktisch Administrator des Rechners
        // (CLAUDE.md, Watchtower-Vorbehalt). Nur der Vermittler darf ihn sehen.
        String yml = paket().get("pin-roboter-agent/docker-compose.yml");
        assertTrue(yml.contains("docker-socket-proxy"), yml);
        assertTrue(yml.contains("DOCKER_HOST: tcp://dockerzugang:2375"),
                "Der Aktualisierer muss über den Vermittler gehen");
        assertTrue(yml.contains("EXEC: \"0\""),
                "Befehle in fremden Containern dürfen nicht erlaubt sein");
        // Genau EINE Einbindezeile — nicht ein Vorkommen: Die Zeile nennt den
        // Pfad zweimal, links die Quelle, rechts das Ziel im Container.
        long zeilen = yml.lines().filter(z -> z.strip().startsWith("- /var/run/docker.sock")).count();
        assertEquals(1, zeilen, "Nur der Vermittler bindet den Socket ein");
        assertTrue(yml.contains("/var/run/docker.sock:/var/run/docker.sock:ro"),
                "und den nur lesend");
    }

    @Test
    @DisplayName("Kein Dienst hat zwei environment-Blöcke")
    void keineDoppeltenSchluessel() {
        // Am 13.08.2026 standen beim Dienst „agent“ zwei environment-Blöcke.
        // In YAML gewinnt bei doppeltem Schlüssel der letzte — alles darüber
        // fällt weg, ohne Fehlermeldung. Hier wäre REGISTRY_VERZEICHNIS
        // verschwunden und die selbsttätige Aktualisierung stillschweigend
        // ausgefallen. Ein Kommentarfehler sieht anders aus als ein
        // Datenverlust; dieser sah aus wie einer.
        String yml = paket().get("pin-roboter-agent/docker-compose.yml");

        String dienst = null;
        var gesehen = new java.util.HashMap<String, java.util.List<String>>();
        for (String zeile : yml.split("\n")) {
            if (zeile.isBlank() || zeile.strip().startsWith("#")) continue;
            int einzug = zeile.length() - zeile.stripLeading().length();
            String inhalt = zeile.strip();
            // Dienste stehen auf Einzug 2, ihre Schlüssel auf Einzug 4.
            if (einzug == 2 && inhalt.endsWith(":")) {
                dienst = inhalt.substring(0, inhalt.length() - 1);
                gesehen.put(dienst, new java.util.ArrayList<>());
            } else if (einzug == 4 && dienst != null && inhalt.contains(":")) {
                String schluessel = inhalt.substring(0, inhalt.indexOf(':'));
                var liste = gesehen.get(dienst);
                assertFalse(liste.contains(schluessel),
                        "Dienst „" + dienst + "“ hat „" + schluessel + "“ zweimal — "
                        + "in YAML gewinnt der letzte, der erste fällt still weg");
                liste.add(schluessel);
            }
        }
        assertTrue(gesehen.containsKey("agent"), "Der Dienst „agent“ fehlt: " + gesehen.keySet());
    }

    private static String zeile(String text, String beginn) {
        return text.lines().filter(z -> z.startsWith(beginn))
                .map(z -> z.substring(beginn.length()).strip())
                .findFirst().orElse("");
    }

    @Test
    @DisplayName("Die Zugangsdaten stehen nirgends im Paket")
    void keinTokenImPaket() {
        // Der Kern der Änderung vom 14.08.2026: Der Token liegt nicht mehr
        // dauerhaft auf dem Praxisrechner. Er kommt erst mit dem Auftrag —
        // und im Paket steht er gar nicht.
        for (var e : paket().entrySet()) {
            assertFalse(e.getValue().contains("ghp_"),
                    "In " + e.getKey() + " steht ein Token");
            assertFalse(e.getValue().contains("DOCKER_CONFIG"),
                    "In " + e.getKey() + " liegt noch der alte Weg über eine "
                    + "dauerhafte config.json");
        }
    }

    @Test
    @DisplayName("Der Briefkasten ist getrennt vom Datenverzeichnis")
    void briefkastenGetrennt() {
        // In /daten liegen Token, privater Schlüssel und die PIN-Ablage. Die
        // gehen die Aktualisierung nichts an.
        String yml = paket().get("pin-roboter-agent/docker-compose.yml");
        assertTrue(yml.contains("auftrag:/auftrag"), yml);
        assertTrue(yml.contains("AUFTRAG_VERZEICHNIS: /auftrag"),
                "Der Agent muss wissen, wohin er den Auftrag legt");
        assertFalse(yml.contains("agentdaten:/auftrag"),
                "Das Datenverzeichnis gehört nicht in den Briefkasten");
        // Der Aktualisierer bekommt das Datenverzeichnis nirgends zu sehen.
        int nachAktualisierung = yml.indexOf("aktualisierung:");
        int nachDockerzugang = yml.indexOf("dockerzugang:");
        String block = yml.substring(nachAktualisierung, nachDockerzugang);
        assertFalse(block.contains("agentdaten"),
                "Die Aktualisierung darf /daten nicht sehen:\n" + block);
    }

    @Test
    @DisplayName("Der Aktualisierer tut von selbst nichts")
    void nurAufAuftrag() {
        // Kein Zeitplan, kein periodischer Lauf: Eine fehlerhafte Freigabe
        // träfe sonst in derselben Nacht alle Standorte gleichzeitig.
        String yml = paket().get("pin-roboter-agent/docker-compose.yml");
        assertFalse(yml.contains("SCHEDULE"),
                "Ein Zeitplan hieße: Aktualisierung ohne Entscheidung");
        assertFalse(yml.contains("PERIODIC_POLLS"), yml);
        assertFalse(yml.contains("watchtower"),
                "Ein Überwachungsdienst für eine Aufgabe ohne Überwachung");

        String skript = paket().get("pin-roboter-agent/aktualisieren.sh");
        assertNotNull(skript, "Das Skript fehlt im Paket");
        assertTrue(skript.contains("Von selbst wird nichts aktualisiert"),
                "Der Betreiber soll beim Lesen sofort sehen, dass hier nichts "
                + "von allein passiert");
    }

    @Test
    @DisplayName("Das Skript nimmt den Token über die Standardeingabe")
    void tokenNichtInDerProzessliste() {
        String skript = paket().get("pin-roboter-agent/aktualisieren.sh");
        assertTrue(skript.contains("--password-stdin"),
                "Als Befehlszeilenargument stünde der Token in der Prozessliste, "
                + "die jeder auf diesem Rechner lesen kann");
        assertFalse(skript.contains("-p \"$TOKEN\""), skript);
        assertTrue(skript.contains("rm -f \"$AUFTRAG\""),
                "Der Auftrag muss als Erstes gelöscht werden — er enthält den Token");
        assertTrue(skript.contains("docker logout"),
                "Nach dem Vorgang darf keine Anmeldung liegen bleiben");
    }

    @Test
    @DisplayName("Angefasst wird nur der Agent")
    void nurDerAgent() {
        String skript = paket().get("pin-roboter-agent/aktualisieren.sh");
        assertTrue(skript.contains("docker compose pull agent"),
                "Ohne den Dienstnamen zöge compose alles neu");
        assertTrue(skript.contains("docker compose up -d agent"),
                "Sonst beendete sich der Aktualisierer mitten im eigenen Befehl");
    }

    @Test
    @DisplayName("Das Volume für die Agentendaten fehlt nicht")
    void volumeVorhanden() {
        String yml = paket().get("pin-roboter-agent/docker-compose.yml");
        // Ohne dieses Volume verliert der Agent bei jedem Neustart Token und
        // Schlüsselpaar — und jede hinterlegte PIN wird unbrauchbar.
        assertTrue(yml.contains("agentdaten:/daten"), yml);
        assertTrue(yml.contains("volumes:"), yml);
    }

    @Test
    @DisplayName("Die Anleitung nennt Einrichtung, Agent und Zentrale")
    void anleitungIstZugeschnitten() {
        String md = paket().get("pin-roboter-agent/ANLEITUNG.md");
        assertTrue(md.contains("Hausarztpraxis Nordstadt"), md.substring(0, 200));
        assertTrue(md.contains("Agent Nordstadt"));
        assertTrue(md.contains(ZENTRALE));
        // Was jemand wirklich braucht: der Startbefehl und wie man nachsieht.
        assertTrue(md.contains("docker compose up -d"));
        assertTrue(md.contains("docker compose logs"));
    }

    @Test
    @DisplayName("Die Anleitung sagt, dass der Zugangscode abläuft und löschbar ist")
    void anleitungWarntVorDemGeheimnis() {
        String md = paket().get("pin-roboter-agent/ANLEITUNG.md");
        assertTrue(md.contains("24 Stunden"), "Die Gültigkeit gehört genannt");
        assertTrue(md.toLowerCase().contains("löschen"),
                "Nach der Registrierung hat die Zeile keinen Wert mehr — das gehört gesagt");
    }

    @Test
    @DisplayName("Sonderzeichen in Namen zerlegen das Paket nicht")
    void sonderzeichen() {
        var p = entpacken(Agentenpaket.bauen(ZENTRALE, GEHEIM,
                "Gynäkologie Südpark & Co", "Agent \"Süd\"", "0.3.2"));
        assertEquals(4, p.size());
        assertTrue(p.get("pin-roboter-agent/ANLEITUNG.md").contains("Gynäkologie Südpark & Co"));
    }

    @Test
    @DisplayName("Die Anleitung behandelt eine nicht öffentliche Programmablage")
    void anleitungNenntRegistrierungsZugang() {
        // Befund 13.08.2026: Das GHCR-Repository ist privat. Ohne „docker login“
        // scheitert der erste Start mit „unauthorized“ — eine Meldung, mit der
        // in einer Praxis niemand etwas anfangen kann.
        String md = paket().get("pin-roboter-agent/ANLEITUNG.md");
        assertTrue(md.contains("docker login ghcr.io"), "Der Anmeldebefehl fehlt");
        assertTrue(md.contains("unauthorized"),
                "Die Fehlertabelle muss die Meldung nennen, an der man es erkennt");
    }

    @Test
    @DisplayName("Das Compose legt die Oberfläche nur auf diesen Rechner")
    void oberflaecheNurLokal() {
        String yml = paket().get("pin-roboter-agent/docker-compose.yml");
        assertTrue(yml.contains("127.0.0.1:8091:8091"),
                "Ohne die Bindung an 127.0.0.1 wäre die Oberfläche im Praxisnetz "
                + "erreichbar — das weichte ADR-0002 auf");
        assertTrue(yml.contains("OBERFLAECHE_ADRESSE"),
                "Im Container muss an 0.0.0.0 gebunden werden, sonst greift das "
                + "Port-Mapping nicht");
    }

    @Test
    @DisplayName("Die Anleitung nennt die Oberfläche und den Umzug")
    void anleitungNenntOberflaeche() {
        String md = paket().get("pin-roboter-agent/ANLEITUNG.md");
        assertTrue(md.contains("127.0.0.1:8091"), md.substring(0, 200));
        assertTrue(md.contains("umzieht") || md.contains("neue Adresse"),
                "Der Serverumzug gehört in die Anleitung");
    }

    @Test
    @DisplayName("Die Anleitung erklärt die Aktualisierung und wie man sie abstellt")
    void anleitungErklaertAktualisierung() {
        String md = paket().get("pin-roboter-agent/ANLEITUNG.md");
        assertTrue(md.contains("aktualisiert sich nicht von selbst"),
                "Der wichtigste Satz: Hier passiert nichts ohne Entscheidung");
        assertTrue(md.contains("Wenn Sie das nicht möchten"),
                "Manche Häuser wollen jede Änderung selbst am Rechner vornehmen");
        assertTrue(md.contains("bleiben freigeschaltet"),
                "Die Beruhigung, auf die es in einer Praxis ankommt");
        assertTrue(md.contains("aktualisieren.sh"),
                "Der Betreiber soll wissen, wo er nachlesen kann, was passiert");
        assertTrue(md.contains("liegt kein Zugangstoken"),
                "Dass am Standort kein Token liegt, ist der Kern der Änderung "
                + "vom 14.08.2026 — das gehört in die Anleitung");
    }

    // ------------------------------------------------------------- Kleinteile

    private static Map<String, String> entpacken(byte[] zip) {
        var inhalt = new HashMap<String, String>();
        try (var z = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            for (var e = z.getNextEntry(); e != null; e = z.getNextEntry()) {
                inhalt.put(e.getName(), new String(z.readAllBytes(), StandardCharsets.UTF_8));
            }
        } catch (Exception e) { throw new IllegalStateException(e); }
        return inhalt;
    }
}
