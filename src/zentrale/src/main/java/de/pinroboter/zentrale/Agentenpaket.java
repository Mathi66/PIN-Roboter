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
            eintrag(zip, "pin-roboter-agent/aktualisieren.sh", skript());
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
                      # Wohin der Agent einen Aktualisierungsauftrag legt.
                      AUFTRAG_VERZEICHNIS: /auftrag
                    volumes:
                      # Token, Schlüsselpaar und die verschlüsselte PIN-Ablage.
                      # OHNE dieses Volume verliert der Agent nach jedem Neustart
                      # seine Anmeldung — und jede hinterlegte PIN wird unbrauchbar,
                      # weil sie für ein Schlüsselpaar verschlüsselt ist, das es dann
                      # nicht mehr gibt.
                      - agentdaten:/daten
                      # Der Briefkasten für Aktualisierungsaufträge. Nicht in
                      # /daten: Dort liegen Token, privater Schlüssel und die
                      # PIN-Ablage — die gehen die Aktualisierung nichts an.
                      - auftrag:/auftrag
                    # Der Agent soll auch auf schwacher Hardware mitlaufen.
                    mem_limit: 512m
                    security_opt: [no-new-privileges:true]
                    cap_drop: [ALL]
                    logging:
                      driver: json-file
                      options: { max-size: "10m", max-file: "5" }

                  # ── Aktualisierung auf Auftrag ─────────────────────────────
                  #
                  # Tauscht den Agenten gegen eine neue Fassung aus, wenn die
                  # Zentrale es verlangt. Von selbst tut dieser Dienst nichts —
                  # er wartet auf eine Auftragsdatei, die der Agent schreibt.
                  #
                  # Warum kein fertiges Werkzeug: Watchtower ist ein
                  # ÜBERWACHUNGSdienst, und überwachen soll hier nichts. Was
                  # gebraucht wird, sind vier Befehle — dieselben, die auch in
                  # der Anleitung für die Aktualisierung von Hand stehen. Der
                  # Betreiber kann „aktualisieren.sh“ lesen und versteht danach
                  # genau, was auf seinem Rechner passiert.
                  #
                  # Vier Vorkehrungen:
                  #  1. Nur der Agent wird angefasst (compose … agent), nichts
                  #     anderes auf diesem Rechner.
                  #  2. Nur auf Auftrag. Wer aktualisiert, wählt den Zeitpunkt.
                  #  3. Kein direkter Docker-Socket, sondern ein Vermittler mit
                  #     eingeschränkten Rechten.
                  #  4. Die Zugangsdaten stehen NICHT hier. Sie stecken in der
                  #     Auftragsdatei, die der Agent frisch von der Zentrale
                  #     holt — und die dieser Dienst als Erstes löscht.
                  aktualisierung:
                    image: docker:28-cli
                    container_name: pin-roboter-aktualisierung
                    restart: unless-stopped
                    depends_on: [dockerzugang]
                    environment:
                      DOCKER_HOST: tcp://dockerzugang:2375
                      TZ: Europe/Berlin
                    volumes:
                      # Das Skript — lesbar, nachvollziehbar, nur lesend.
                      - ./aktualisieren.sh:/aktualisieren.sh:ro
                      # Diese Datei, damit compose weiß, wie der Agent
                      # aussehen soll. Es muss nichts nachgebaut werden.
                      - ./docker-compose.yml:/projekt/docker-compose.yml:ro
                      - ./.env:/projekt/.env:ro
                      # Der Briefkasten: Hier legt der Agent seinen Auftrag ab.
                      - auftrag:/auftrag
                    working_dir: /projekt
                    entrypoint: ["/bin/sh", "/aktualisieren.sh"]
                    security_opt: [no-new-privileges:true]
                    logging:
                      driver: json-file
                      options: { max-size: "10m", max-file: "3" }

                  # Der Vermittler zum Docker-Dienst. Der Aktualisierer bekommt
                  # damit NICHT den vollen Socket: Wer ihn hat, ist faktisch
                  # Administrator des ganzen Rechners.
                  #
                  # Freigegeben ist, was „compose up“ braucht. Nicht
                  # freigegeben: exec — Befehle in fremden Containern
                  # ausführen —, secrets, configs und swarm.
                  dockerzugang:
                    image: tecnativa/docker-socket-proxy:0.3.0
                    container_name: pin-roboter-dockerzugang
                    restart: unless-stopped
                    environment:
                      CONTAINERS: "1"
                      IMAGES: "1"
                      POST: "1"
                      # compose legt fehlende Netze und Volumes an und fragt
                      # die Serverfassung ab. Ohne diese drei bricht es ab.
                      NETWORKS: "1"
                      VOLUMES: "1"
                      INFO: "1"
                      # Ausdrücklich nicht:
                      EXEC: "0"
                      SECRETS: "0"
                      CONFIGS: "0"
                      SWARM: "0"
                      SERVICES: "0"
                      TASKS: "0"
                      NODES: "0"
                      AUTH: "0"
                    volumes:
                      - /var/run/docker.sock:/var/run/docker.sock:ro
                    # Er ist die einzige Stelle mit Socket-Zugriff und deshalb
                    # der wertvollste Angriffspunkt in diesem Stapel.
                    security_opt: [no-new-privileges:true]
                    read_only: true
                    tmpfs: [/run]

                volumes:
                  agentdaten:
                  # Der Briefkasten zwischen Agent und Aktualisierung. Nur
                  # diese beiden Dienste sehen ihn; ein Auftrag liegt darin
                  # Sekunden, dann ist er gelesen und gelöscht.
                  auftrag:
                """.formatted(tag);
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
                """.formatted(zentraleUrl, geheimnis, einrichtung, bezeichnung);
    }

    /**
     * Das Aktualisierungsskript, das im Paket mitgeliefert wird.
     *
     * <h2>Warum ein eigenes Skript und kein fertiges Werkzeug</h2>
     *
     * Zuerst stand hier Watchtower. Das ist ein <b>Überwachungs</b>dienst — er
     * sieht nach, ob es etwas Neues gibt, und handelt selbsttätig. Seit der PO
     * entschieden hat, dass nur noch auf Auftrag aktualisiert wird
     * (14.08.2026), überwacht hier nichts mehr: Gebraucht werden vier Befehle,
     * genau die, die auch in der Anleitung für die Aktualisierung von Hand
     * stehen.
     *
     * <p>Der zweite Grund wiegt schwerer. Watchtower liest seine Anmeldung nur
     * aus einer {@code config.json}, die dafür auf der Platte liegen muss. Ein
     * eigenes Skript kann den Token <b>über die Standardeingabe</b> annehmen —
     * er steht dann in keiner Datei und in keiner Prozessliste, und
     * {@code docker logout} räumt hinterher auf.
     *
     * <p>Der dritte: Der Betreiber kann diese Datei lesen. Danach weiß er
     * genau, was auf seinem Rechner passiert. Das ist bei einem fremden Abbild
     * nicht so.
     */
    private static String skript() {
        return """
                #!/bin/sh
                # ═══════════════════════════════════════════════════════════════
                #  PIN-Roboter — Aktualisierung des Standort-Agenten
                #
                #  Dieser Dienst tut von sich aus NICHTS. Er wartet auf einen
                #  Auftrag, den der Agent in /auftrag/auftrag ablegt, nachdem
                #  die Zentrale ihn dazu angewiesen hat.
                #
                #  Was dann passiert, ist genau das, was Sie auch von Hand
                #  eingeben würden:
                #
                #      docker login    (nur wenn die Ablage privat ist)
                #      docker compose pull agent
                #      docker compose up -d agent
                #      docker logout
                #
                #  Angefasst wird ausschließlich der Dienst „agent". Was sonst
                #  auf diesem Rechner läuft, bleibt unberührt.
                # ═══════════════════════════════════════════════════════════════
                set -u

                AUFTRAG=/auftrag/auftrag
                ERGEBNIS=/auftrag/ergebnis

                melde() { echo "$(date '+%%Y-%%m-%%d %%H:%%M:%%S')  $1"; }

                melde "Bereit. Warte auf einen Auftrag der Zentrale."
                melde "Von selbst wird nichts aktualisiert."

                while true; do
                  if [ ! -f "$AUFTRAG" ]; then
                    sleep 2
                    continue
                  fi

                  # Zuerst lesen und SOFORT löschen: Im Auftrag steht das
                  # Zugriffstoken. Es soll so kurz wie möglich auf der Platte
                  # liegen — Sekunden, nicht Minuten.
                  REGISTRY=$(sed -n '1p' "$AUFTRAG")
                  BENUTZER=$(sed -n '2p' "$AUFTRAG")
                  TOKEN=$(sed -n '3p' "$AUFTRAG")
                  VERSION=$(sed -n '4p' "$AUFTRAG")
                  rm -f "$AUFTRAG"

                  melde "Auftrag erhalten: Aktualisierung auf $VERSION"

                  FEHLER=""

                  if [ -n "$REGISTRY" ] && [ -n "$BENUTZER" ] && [ -n "$TOKEN" ]; then
                    # Der Token geht über die Standardeingabe, nicht als
                    # Befehlszeilenargument: Sonst stünde er in der
                    # Prozessliste, die jeder auf diesem Rechner lesen kann.
                    if printf '%%s' "$TOKEN" | docker login "$REGISTRY" \
                         -u "$BENUTZER" --password-stdin >/dev/null 2>&1; then
                      melde "An der Programmablage angemeldet."
                    else
                      FEHLER="Die Programmablage hat die Zugangsdaten abgelehnt."
                    fi
                  fi
                  TOKEN=""

                  if [ -z "$FEHLER" ]; then
                    melde "Hole das Abbild ..."
                    if ! docker compose pull agent 2>&1; then
                      FEHLER="Das Abbild ließ sich nicht holen. Gibt es die Version"
                      FEHLER="$FEHLER $VERSION in der Programmablage?"
                    fi
                  fi

                  if [ -z "$FEHLER" ]; then
                    melde "Tausche den Agenten aus ..."
                    # Nur den Agenten. Dieser Dienst und der Vermittler bleiben
                    # unberührt — sonst beendete sich der Aktualisierer mitten
                    # im eigenen Befehl.
                    if ! docker compose up -d agent 2>&1; then
                      FEHLER="Der Agent ließ sich nicht neu starten."
                    fi
                  fi

                  # Aufräumen in jedem Fall: Die Anmeldung soll nicht liegen
                  # bleiben, auch nicht nach einem Fehlschlag.
                  docker logout "$REGISTRY" >/dev/null 2>&1 || true

                  if [ -z "$FEHLER" ]; then
                    melde "Fertig. Der Agent meldet sich gleich mit der neuen Version."
                    echo "ok" > "$ERGEBNIS"
                  else
                    melde "FEHLGESCHLAGEN: $FEHLER"
                    echo "$FEHLER" > "$ERGEBNIS"
                  fi
                done
                """;
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
                | Der Agent bleibt auf einer alten Version | Prüfen Sie unter <http://127.0.0.1:8091>, ob „Aktualisierung möglich" erfüllt ist. Fehlt der Punkt, ist die Weitergabe in der Zentrale nicht eingeschaltet. Sonst hilft `docker compose logs aktualisierung`. |
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

                **Der Agent aktualisiert sich nicht von selbst.** Er wird ausgetauscht,
                wenn Ihre Zentrale es verlangt — und nur dann. Wer aktualisiert, wählt
                damit auch den Zeitpunkt.

                Angestoßen wird das in der Zentrale unter „Instanz → Standort-Agenten“.
                Dort steht auch, welche Fassung bei Ihnen läuft und was bereitsteht.

                Der Austausch dauert wenige Sekunden. Währenddessen werden die
                Kartenterminals kurz nicht überwacht — **bereits freigeschaltete Karten
                bleiben freigeschaltet.**

                Von Hand geht es jederzeit:

                ```
                docker compose pull agent
                docker compose up -d agent
                ```

                ### Was dabei auf Ihrem Rechner passiert

                Genau diese vier Befehle — nachzulesen in `aktualisieren.sh`, die Datei
                liegt offen in diesem Ordner:

                ```
                docker login          (nur wenn die Programmablage privat ist)
                docker compose pull agent
                docker compose up -d agent
                docker logout
                ```

                Die Zugangsdaten für die Programmablage stehen **nicht** in diesen
                Dateien. Der Agent holt sie im Moment der Aktualisierung von Ihrer
                Zentrale, legt sie kurz ab, und der Aktualisierungsdienst löscht sie als
                Erstes wieder. Auf einem Rechner, an dem gerade nichts aktualisiert
                wird, liegt kein Zugangstoken.

                ### Wenn Sie das nicht möchten

                Manche Häuser wollen jede Änderung selbst am Rechner vornehmen. Dann in
                der `docker-compose.yml` die beiden Dienste `aktualisierung` und
                `dockerzugang` entfernen (oder auskommentieren) und statt `:stabil` eine
                feste Nummer eintragen, etwa `:0.4.0`. Danach:

                ```
                docker compose up -d
                ```

                Ein Aktualisierungsauftrag aus der Zentrale scheitert dann mit einer
                verständlichen Meldung — es geht nichts kaputt.

                ### Zur Sicherheit

                Der Aktualisierungsdienst darf Docker steuern, allerdings **nur
                eingeschränkt**: Container auflisten und neu starten, Abbilder holen.
                Nicht erlaubt sind unter anderem Befehle in fremden Containern und der
                Zugriff auf Datenträger. Angefasst wird ausschließlich der Agent.

                """.formatted(einrichtung, bezeichnung, zentraleUrl);
    }
}
