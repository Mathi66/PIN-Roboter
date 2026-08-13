package de.pinroboter.zentrale;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Das Einrichtungspaket für einen Standort-Agenten (PO, 13.08.2026).
 *
 * <h2>Warum ein Paket und nicht das Image</h2>
 *
 * Naheliegend wäre, das Agent-Image selbst zum Herunterladen anzubieten. Das
 * wäre aus drei Gründen falsch:
 *
 * <ul>
 *   <li>Es sind <b>50 MB</b>, die die Zentrale erst selbst beschaffen müsste —
 *       sie hat keinen Zugriff auf einen Docker-Dienst.</li>
 *   <li>Ein so verteilter Agent bekäme <b>nie ein Update</b>. Aus der Registry
 *       gezogen, holt {@code docker compose pull} später die Unterschiede.</li>
 *   <li>Der Bezugsweg wäre ein anderer als der von DevOps festgelegte. Zwei
 *       Wege zum selben Baustein heißt: irgendwann laufen sie auseinander.</li>
 * </ul>
 *
 * <p>Das Paket enthält deshalb <b>keine Software</b>, sondern das, was man
 * sonst von Hand abtippt: eine fertige {@code docker-compose.yml}, eine
 * vorausgefüllte {@code .env} und eine Anleitung. Das Image kommt beim Start
 * aus GHCR.
 *
 * <h2>Das Registrierungsgeheimnis liegt in der Datei</h2>
 *
 * Bewusst. Es gilt <b>einmalig</b> und läuft nach 24 Stunden ab — genau dafür
 * ist es gebaut. Der Alternativweg wäre, einen vierzigstelligen Zufallswert
 * abzutippen; in der Praxis endet das in Tippfehlern und darin, dass jemand ihn
 * per E-Mail schickt.
 *
 * <p>Die Anleitung sagt deshalb ausdrücklich, dass die Datei nach der
 * Registrierung ihren Wert verliert und gelöscht werden kann.
 */
final class Agentenpaket {

    private Agentenpaket() { }

    /**
     * Baut das ZIP.
     *
     * @param zentraleUrl  Adresse <b>dieser</b> Zentrale, wie der Browser sie
     *                     gerade sieht — nicht geraten, sondern aus der Anfrage
     * @param geheimnis    frisch erzeugtes Registrierungsgeheimnis
     * @param einrichtung  Betriebsstätte, der dieser Agent zugeordnet ist
     * @param bezeichnung  Anzeigename des Agenten
     * @param version      Fassung dieser Zentrale — der Agent bekommt dieselbe
     */
    static byte[] bauen(String zentraleUrl, String geheimnis, String einrichtung,
                        String bezeichnung, String version) {
        var aus = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(aus, StandardCharsets.UTF_8)) {
            eintrag(zip, "pin-roboter-agent/docker-compose.yml", compose(version));
            eintrag(zip, "pin-roboter-agent/.env",
                    env(zentraleUrl, geheimnis, einrichtung, bezeichnung));
            eintrag(zip, "pin-roboter-agent/ANLEITUNG.md",
                    anleitung(zentraleUrl, einrichtung, bezeichnung));
        } catch (Exception e) {
            throw new IllegalStateException("Einrichtungspaket nicht erzeugbar", e);
        }
        return aus.toByteArray();
    }

    private static void eintrag(ZipOutputStream zip, String name, String inhalt) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(inhalt.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    // ------------------------------------------------------------- Bausteine

    private static String compose(String version) {
        // Der Tag „stabil“ statt einer festen Nummer (PO, 13.08.2026).
        //
        // Zuerst stand hier die laufende Version — mit der Begründung, ein
        // Update sei eine Entscheidung und kein Nebeneffekt. Das bleibt richtig,
        // ist aber nicht die ganze Rechnung: Gerade am Anfang kommen häufig
        // Fehlerbehebungen, und niemand kann sie an 150 Praxisrechnern von Hand
        // einspielen.
        //
        // „stabil“ löst beides. Der Tag wandert NICHT automatisch mit jeder
        // Veröffentlichung, sondern nur, wenn eine Version ausdrücklich
        // freigegeben wird (.github/workflows/stabil-freigeben.yml). Die
        // Entscheidung bleibt also — sie fällt nur einmal zentral statt
        // hundertfach vor Ort.
        //
        // Die laufende Version steht als Kommentar dabei: Wer wissen will, mit
        // welchem Stand dieses Paket erzeugt wurde, findet es hier.
        String tag = "unbekannt".equals(version) ? "0.3.2" : version;
        return """
                # PIN-Roboter · Standort-Agent
                #
                # Erzeugt von Ihrer Zentrale. Starten mit:
                #     docker compose up -d
                #
                # Der Agent baut Verbindungen ausschließlich NACH AUSSEN auf. In Ihrer
                # Firewall ist deshalb keine eingehende Freigabe nötig.
                services:
                  agent:
                    # „stabil“ ist die jeweils freigegebene Fassung.
                    # Erzeugt mit Stand %s.
                    image: ghcr.io/mathi66/pin-roboter-agent:stabil
                    container_name: pin-roboter-agent
                    restart: unless-stopped
                    env_file: .env
                    # Die Oberfläche des Agenten: Inbetriebnahme, Zustand,
                    # Fehlersuche. Nur auf DIESEM Rechner erreichbar —
                    # http://127.0.0.1:8091. Im Container an 0.0.0.0 gebunden,
                    # weil ein dort an 127.0.0.1 gebundener Dienst per
                    # Port-Mapping gar nicht erreichbar wäre; die Beschränkung
                    # macht die Adresse vor dem Doppelpunkt.
                    ports:
                      - "127.0.0.1:8091:8091"
                    # EIN environment-Block. Zwei wären kein Kommentarfehler,
                    # sondern ein stiller Datenverlust: In YAML gewinnt bei
                    # doppeltem Schlüssel der letzte, und alles darüber fiele
                    # weg.
                    environment:
                      OBERFLAECHE_ADRESSE: "0.0.0.0"
                      # Wohin die Zugangsdaten für die Aktualisierung gehören.
                      REGISTRY_VERZEICHNIS: /registry
                      # Wo der Agent die Aktualisierung anstoßen kann, wenn die
                      # Zentrale es verlangt. Kennwort steht in der .env.
                      AKTUALISIERUNG_URL: http://aktualisierung:8080
                    volumes:
                      # Token, Schlüsselpaar und die verschlüsselte PIN-Ablage.
                      # OHNE dieses Volume verliert der Agent nach jedem Neustart
                      # seine Anmeldung — und jede hinterlegte PIN wird unbrauchbar,
                      # weil sie für ein Schlüsselpaar verschlüsselt ist, das es dann
                      # nicht mehr gibt.
                      - agentdaten:/daten
                      # Eigenes Volume NUR für die Zugangsdaten zur
                      # Programmablage. Nicht in /daten: Dort liegen Token,
                      # privater Schlüssel und die PIN-Ablage — die gehen die
                      # Aktualisierung nichts an, auch nicht lesend.
                      - registryzugang:/registry
                    # Der Agent soll auch auf schwacher Hardware mitlaufen.
                    mem_limit: 512m
                    security_opt: [no-new-privileges:true]
                    cap_drop: [ALL]
                    logging:
                      driver: json-file
                      options: { max-size: "10m", max-file: "5" }

                    labels:
                      # Nur DIESER Container wird selbsttätig aktualisiert.
                      com.centurylinklabs.watchtower.enable: "true"

                  # ── Selbsttätige Aktualisierung ────────────────────────────
                  #
                  # Holt neue Fassungen des Agenten aus der Programmablage und
                  # startet ihn neu. Ohne das müsste jemand an jeden Rechner.
                  #
                  # Drei Vorkehrungen (CLAUDE.md, Watchtower-Vorbehalt):
                  #  1. Nur der Agent, nichts anderes auf diesem Rechner
                  #     (LABEL_ENABLE mit dem Label oben).
                  #  2. Nur nachts — ein Neustart während der Sprechzeit
                  #     unterbräche die Überwachung.
                  #  3. Kein direkter Zugriff auf den Docker-Socket, sondern
                  #     über einen Vermittler mit eingeschränkten Rechten.
                  aktualisierung:
                    image: containrrr/watchtower:1.7.1
                    container_name: pin-roboter-aktualisierung
                    restart: unless-stopped
                    depends_on: [dockerzugang]
                    environment:
                      DOCKER_HOST: tcp://dockerzugang:2375
                      WATCHTOWER_LABEL_ENABLE: "true"
                      # Die Zugangsdaten zur Programmablage stehen NICHT hier,
                      # sondern in einer Datei, die der Agent von der Zentrale
                      # bezieht. Damit liegt der Token nicht in dieser Datei,
                      # nicht in „docker inspect" und nicht in der Prozessliste
                      # — und er lässt sich zentral tauschen statt vor Ort.
                      #
                      # Schaltet die Zentrale die Aktualisierung ab, löscht der
                      # Agent die Datei. Dann findet Watchtower keine Anmeldung
                      # mehr und holt nichts: Das IST der Schalter.
                      DOCKER_CONFIG: /registry
                      # 03:30 Uhr. Ortszeit des Rechners, siehe TZ unten.
                      WATCHTOWER_SCHEDULE: "%s"
                      # Alte Abbilder wegräumen — sonst füllt sich die Platte.
                      WATCHTOWER_CLEANUP: "true"
                      WATCHTOWER_INCLUDE_RESTARTING: "true"
                      # Zusätzlich zum nächtlichen Lauf auf Zuruf: Damit kann
                      # ein Administrator in der Zentrale einen einzelnen
                      # Standort gezielt aktualisieren, statt bis morgen früh
                      # zu warten (FA-AGT-100).
                      #
                      # PERIODIC_POLLS ist dabei nicht optional: Ohne diesen
                      # Schalter läuft Watchtower im reinen Anfragebetrieb und
                      # der nächtliche Plan oben wäre wirkungslos.
                      WATCHTOWER_HTTP_API_UPDATE: "true"
                      WATCHTOWER_HTTP_API_PERIODIC_POLLS: "true"
                      WATCHTOWER_HTTP_API_TOKEN: ${AKTUALISIERUNG_TOKEN}
                      TZ: Europe/Berlin
                    volumes:
                      # Nur lesend, und nur dieses eine Verzeichnis: Watchtower
                      # braucht die Anmeldedatei, schreiben darf sie allein der
                      # Agent. An das Datenverzeichnis kommt es nicht.
                      - registryzugang:/registry:ro
                    logging:
                      driver: json-file
                      options: { max-size: "10m", max-file: "3" }

                  # Der Vermittler zum Docker-Dienst. Watchtower bekommt damit
                  # NICHT den vollen Socket: Wer ihn hat, ist faktisch
                  # Administrator des ganzen Rechners.
                  #
                  # Freigegeben ist nur, was zum Aktualisieren nötig ist. Nicht
                  # freigegeben: exec (Befehle in fremden Containern ausführen),
                  # volumes, networks, secrets, configs, swarm.
                  dockerzugang:
                    image: tecnativa/docker-socket-proxy:0.3.0
                    container_name: pin-roboter-dockerzugang
                    restart: unless-stopped
                    environment:
                      CONTAINERS: "1"
                      IMAGES: "1"
                      POST: "1"
                      # Ausdrücklich nicht:
                      EXEC: "0"
                      VOLUMES: "0"
                      NETWORKS: "0"
                      SECRETS: "0"
                      CONFIGS: "0"
                      SWARM: "0"
                      SERVICES: "0"
                      TASKS: "0"
                      NODES: "0"
                      AUTH: "0"
                      INFO: "0"
                    volumes:
                      - /var/run/docker.sock:/var/run/docker.sock:ro
                    # Er ist die einzige Stelle mit Socket-Zugriff und deshalb
                    # der wertvollste Angriffspunkt in diesem Stapel.
                    security_opt: [no-new-privileges:true]
                    read_only: true
                    tmpfs: [/run]

                volumes:
                  agentdaten:
                  registryzugang:
                """.formatted(tag, "0 30 3 * * *");
    }

    private static String env(String zentraleUrl, String geheimnis, String einrichtung,
                              String bezeichnung) {
        return """
                # PIN-Roboter · Standort-Agent — Konfiguration
                #
                # Vorausgefüllt von Ihrer Zentrale. Ergänzen Sie die drei Adressen
                # unten, dann starten Sie mit:  docker compose up -d

                # ── Bereits eingetragen, bitte nicht ändern ──────────────────────
                ZENTRALE_URL=%s
                # Einmalig gültig, läuft nach 24 Stunden ab. Nach der Registrierung
                # kann diese Zeile entfernt werden — sie hat dann keinen Wert mehr.
                REGISTRIERUNG=%s

                # Zugeordnet zu: %s
                # Agent: %s

                # ── Von Ihnen auszufüllen ───────────────────────────────────────
                #
                # Adresse Ihres Konnektors. Die Angaben stehen in dessen
                # Verwaltungsoberfläche oder in den Unterlagen Ihres Dienstleisters.
                KONNEKTOR_URL=https://192.168.1.10:443/

                # Adresse der Verwaltungsschnittstelle Ihrer Kartenterminals.
                TERMINAL_URL=https://192.168.1.20:443/

                # Nur ausfüllen, wenn Ihre Kartenterminals die Remote-PIN-Schnittstelle
                # auf einem ANDEREN Port anbieten als die Verwaltung. Im Regelfall ist
                # es derselbe — dann diese Zeile leer lassen.
                TERMINAL_SMCB_URL=

                # Vertrauensanker: das Zertifikat der Stelle, die die Zertifikate von
                # Konnektor und Kartenterminal ausgestellt hat. Ihr Konnektor bietet es
                # üblicherweise zum Herunterladen an.
                CA_URL=

                # ── Aufrufkontext des Konnektors ────────────────────────────────
                # Diese drei Werte legt Ihr Konnektor fest. Stimmen sie nicht, weist er
                # jede Anfrage ab. Sie stehen in seiner Verwaltungsoberfläche.
                MANDANT_ID=Mandant1
                CLIENTSYSTEM_ID=PIN-Roboter
                ARBEITSPLATZ_ID=Arbeitsplatz1

                # ── Optional ────────────────────────────────────────────────────
                # Wie oft der Agent prüft, wenn die Zentrale nichts anderes vorgibt.
                PRUEFINTERVALL_SEKUNDEN=60

                # Schlüssel für die verschlüsselte PIN-Ablage (ADR-0009). Ohne ihn
                # bleiben hinterlegte PINs nur im Arbeitsspeicher und sind nach einem
                # Neustart weg. Erzeugen mit:   openssl rand -base64 32
                PIN_ABLAGE_SCHLUESSEL=

                # ── Selbsttätige Aktualisierung ─────────────────────────────────
                # Gemeinsames Kennwort zwischen Agent und Aktualisierungsdienst.
                # Damit kann ein Administrator in der Zentrale diesen Standort
                # gezielt aktualisieren. Es wirkt NUR innerhalb dieses Rechners und
                # kann genau eines: „sieh jetzt nach einer neuen Fassung".
                #
                # Für Sie erzeugt — bitte nicht ändern, sonst passen die beiden
                # Dienste nicht mehr zusammen.
                AKTUALISIERUNG_TOKEN=%s
                """.formatted(zentraleUrl, geheimnis, einrichtung, bezeichnung,
                              kennwort());
    }

    /**
     * Ein Kennwort für den Aktualisierungsdienst — je Paket ein anderes.
     *
     * <p>Ein festes Kennwort im Abbild wäre in jeder Praxis dasselbe; wer eines
     * kennt, könnte in jeder anderen Neustarts auslösen. Es wirkt zwar nur
     * innerhalb eines Rechners, aber ein gemeinsames Geheimnis über hundert
     * Standorte hinweg ist der Fehler, den man später nicht mehr repariert.
     */
    private static String kennwort() {
        byte[] b = new byte[24];
        new java.security.SecureRandom().nextBytes(b);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static String anleitung(String zentraleUrl, String einrichtung, String bezeichnung) {
        return """
                # Standort-Agent einrichten

                Für: **%s** · Agent: **%s** · Zentrale: %s

                Der Standort-Agent ist das Programm, das in Ihrem Praxisnetz mit
                Konnektor und Kartenterminals spricht. Er läuft dort, wo auch die
                Geräte stehen — die Zentrale erreicht er von sich aus.

                ---

                ## Was Sie brauchen

                - Einen Rechner im Praxisnetz, der **Docker** ausführen kann
                  (Windows, macOS oder Linux) und **eingeschaltet bleibt**.
                - Die **Adresse Ihres Konnektors** und Ihrer **Kartenterminals**.
                - Etwa **50 MB** Download beim ersten Start.

                > Der Agent braucht **keine Freigabe in Ihrer Firewall**. Er baut alle
                > Verbindungen selbst auf; von außen ist er nicht erreichbar.

                ---

                ## Schritt 1 — Adressen eintragen

                Öffnen Sie die Datei **`.env`** in einem Texteditor. Die Zeilen unter
                „Von Ihnen auszufüllen“ brauchen Ihre Werte:

                | Eintrag | Woher |
                |---|---|
                | `KONNEKTOR_URL` | Verwaltungsoberfläche des Konnektors oder Unterlagen Ihres Dienstleisters |
                | `TERMINAL_URL` | Adresse Ihrer Kartenterminals |
                | `CA_URL` | Zertifikat der ausstellenden Stelle, meist vom Konnektor zum Herunterladen |
                | `MANDANT_ID`, `CLIENTSYSTEM_ID`, `ARBEITSPLATZ_ID` | Aufrufkontext, steht im Konnektor |

                Alles andere ist bereits eingetragen.

                ## Schritt 2 — Zugang zur Programmablage (nur beim ersten Mal)

                Das Programm liegt in einer **Ablage im Internet** (GitHub Container
                Registry). Sie ist nicht öffentlich, deshalb braucht Ihr Rechner für
                den **ersten** Download eine Anmeldung. Benutzernamen und Token nennt
                Ihnen Ihr Dienstleister:

                ```
                docker login ghcr.io
                ```

                Sie erkennen, dass dieser Schritt nötig ist, an der Meldung
                `unauthorized` oder `denied` beim nächsten Schritt.

                > **Nur dieses eine Mal.** Für alle **späteren** Aktualisierungen
                > holt sich der Agent die Anmeldung selbst von der Zentrale — sie
                > steht deshalb nirgends in diesen Dateien. Wechselt Ihr
                > Dienstleister den Token, müssen Sie hier nichts tun.
                >
                > Damit das funktioniert, muss die Weitergabe in der Zentrale
                > eingeschaltet sein (dort unter „Instanz“ → „Programmablage“). Ist
                > sie es nicht, läuft der Agent normal weiter — er aktualisiert sich
                > dann nur nicht von selbst. Die Prüfliste unter
                > <http://127.0.0.1:8091> sagt Ihnen, woran Sie sind.

                ## Schritt 3 — Starten

                Öffnen Sie ein Terminal in **diesem Ordner** und geben Sie ein:

                ```
                docker compose up -d
                ```

                Beim ersten Mal lädt Docker das Programm herunter; das dauert je nach
                Anschluss ein bis zwei Minuten.

                ## Schritt 4 — Nachsehen, ob es geklappt hat

                Öffnen Sie im Browser **<http://127.0.0.1:8091>**. Dort sehen Sie eine
                Prüfliste der Inbetriebnahme, den Zustand aller Geräte und die letzten
                Meldungen — mit einem Hinweis, was zu tun ist, falls etwas fehlt.

                > Diese Seite ist **nur auf diesem Rechner** erreichbar, nicht im
                > Praxisnetz und nicht aus dem Internet. Sie zeigt keine PINs und keine
                > Zugangsdaten.

                Wahlweise geht es auch im Terminal:

                ```
                docker compose logs -f
                ```

                Sie sollten sehen:

                ```
                  TLS bereit (TLS 1.2, Brainpool über BouncyCastle)
                  Registrierung erfolgreich
                ```

                Danach steht Ihr Agent in der Zentrale unter **Verwaltung →
                Standort-Agenten** mit dem heutigen Datum unter „Zuletzt gesehen“.

                Mit `Strg+C` verlassen Sie die Anzeige; der Agent läuft weiter.

                ---

                ## Wenn etwas nicht klappt

                | Meldung | Bedeutung |
                |---|---|
                | `unauthorized` / `denied` beim Herunterladen | Die Programmablage ist nicht öffentlich. Schritt 2 ausführen; Zugangsdaten bekommen Sie von Ihrem Dienstleister. |
                | Der Agent bleibt auf einer alten Version | Prüfen Sie unter <http://127.0.0.1:8091>, ob „Selbsttätige Aktualisierung" erfüllt ist. Fehlt sie, ist die Weitergabe in der Zentrale nicht eingeschaltet. Sonst hilft `docker compose logs aktualisierung`. |
                | `Unbekanntes Registrierungsgeheimnis` | Das Geheimnis ist abgelaufen (24 Stunden) oder schon benutzt. Laden Sie in der Zentrale ein neues Paket herunter. |
                | `Warte auf den Simulator` / keine Verbindung zum Konnektor | `KONNEKTOR_URL` prüfen. Ist der Rechner im selben Netz wie der Konnektor? |
                | `malformed PEM data` | `CA_URL` zeigt nicht auf ein Zertifikat. |
                | `no selectable cipher suite` | Der Vertrauensanker passt nicht zu den Geräten. Fragen Sie Ihren Dienstleister nach dem richtigen Zertifikat. |

                ## Zur Sicherheit

                - **Die Datei `.env` enthält Ihr Registrierungsgeheimnis.** Es gilt
                  einmalig und läuft nach 24 Stunden ab. Nach erfolgreicher
                  Registrierung können Sie die Zeile `REGISTRIERUNG=…` löschen.
                - Geben Sie den Ordner nicht weiter und legen Sie ihn nicht in eine
                  Dateiablage, die andere lesen können.
                - **PINs gehören nicht in diese Datei.** Sie werden in der Zentrale
                  hinterlegt und verschlüsselt an genau diesen Agenten übertragen.

                ## Wenn die Zentrale umzieht

                Bekommt Ihre Zentrale eine **neue Adresse**, tragen Sie sie unter
                <http://127.0.0.1:8091> im Abschnitt „Adresse der Zentrale“ ein. Der Agent
                prüft sie, bevor er sie übernimmt — antwortet dort keine betriebsbereite
                Zentrale, bleibt alles beim Alten.

                Die Änderung bleibt über einen Neustart erhalten. Sie müssen dafür weder
                die Datei `.env` bearbeiten noch den Container neu aufsetzen.

                ## Aktualisierung

                **Der Agent hält sich selbst aktuell.** Jede Nacht um 3:30 Uhr sieht er
                nach, ob eine neue freigegebene Fassung vorliegt, holt sie und startet
                sich neu. Der Neustart dauert wenige Sekunden.

                Ausgerollt wird nur, was **ausdrücklich freigegeben** wurde — nicht jede
                Veröffentlichung. Ihre Zentrale zeigt unter „Verwaltung → Standort-Agenten“,
                welche Fassung bei Ihnen läuft.

                Von Hand geht es auch jederzeit:

                ```
                docker compose pull
                docker compose up -d
                ```

                ### Wenn Sie das nicht möchten

                Manche Häuser wollen jede Änderung selbst bestimmen. Dann in der
                `docker-compose.yml` die beiden Dienste `aktualisierung` und
                `dockerzugang` entfernen (oder auskommentieren) und statt `:stabil` eine
                feste Nummer eintragen, etwa `:0.4.0`. Danach:

                ```
                docker compose up -d
                ```

                ### Zur Sicherheit

                Für die Aktualisierung läuft ein zweiter kleiner Dienst mit, der Docker
                steuern darf — allerdings **nur eingeschränkt**: Er darf Container
                auflisten, neu starten und Abbilder holen. Nicht erlaubt sind unter
                anderem Befehle in fremden Containern und der Zugriff auf Datenträger.
                Der Agent selbst ist der einzige Container, der aktualisiert wird.
                """.formatted(einrichtung, bezeichnung, zentraleUrl);
    }
}
