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
        // Feste Version, kein :latest. Der Agent soll sich nicht unbemerkt
        // ändern; ein Update ist eine Entscheidung, kein Nebeneffekt.
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
                    image: ghcr.io/mathi66/pin-roboter-agent:%s
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
                    environment:
                      OBERFLAECHE_ADRESSE: "0.0.0.0"
                    volumes:
                      # Token, Schlüsselpaar und die verschlüsselte PIN-Ablage.
                      # OHNE dieses Volume verliert der Agent nach jedem Neustart
                      # seine Anmeldung — und jede hinterlegte PIN wird unbrauchbar,
                      # weil sie für ein Schlüsselpaar verschlüsselt ist, das es dann
                      # nicht mehr gibt.
                      - agentdaten:/daten
                    # Der Agent soll auch auf schwacher Hardware mitlaufen.
                    mem_limit: 512m
                    security_opt: [no-new-privileges:true]
                    cap_drop: [ALL]
                    logging:
                      driver: json-file
                      options: { max-size: "10m", max-file: "5" }

                volumes:
                  agentdaten:
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
                „Von Ihnen auszufüllen" brauchen Ihre Werte:

                | Eintrag | Woher |
                |---|---|
                | `KONNEKTOR_URL` | Verwaltungsoberfläche des Konnektors oder Unterlagen Ihres Dienstleisters |
                | `TERMINAL_URL` | Adresse Ihrer Kartenterminals |
                | `CA_URL` | Zertifikat der ausstellenden Stelle, meist vom Konnektor zum Herunterladen |
                | `MANDANT_ID`, `CLIENTSYSTEM_ID`, `ARBEITSPLATZ_ID` | Aufrufkontext, steht im Konnektor |

                Alles andere ist bereits eingetragen.

                ## Schritt 2 — Zugang zur Programmablage (nur einmalig)

                Das Programm liegt in einer **Ablage im Internet** (GitHub Container
                Registry). Ist sie nicht öffentlich, braucht Ihr Rechner einmalig
                Zugangsdaten. Ihr Dienstleister nennt Ihnen Benutzernamen und Token:

                ```
                docker login ghcr.io
                ```

                Sie erkennen, dass dieser Schritt nötig ist, an der Meldung
                `unauthorized` oder `denied` beim nächsten Schritt. Läuft Schritt 3
                ohne Fehler durch, war er nicht nötig.

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
                Standort-Agenten** mit dem heutigen Datum unter „Zuletzt gesehen".

                Mit `Strg+C` verlassen Sie die Anzeige; der Agent läuft weiter.

                ---

                ## Wenn etwas nicht klappt

                | Meldung | Bedeutung |
                |---|---|
                | `unauthorized` / `denied` beim Herunterladen | Die Programmablage ist nicht öffentlich. Schritt 2 ausführen; Zugangsdaten bekommen Sie von Ihrem Dienstleister. |
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
                <http://127.0.0.1:8091> im Abschnitt „Adresse der Zentrale" ein. Der Agent
                prüft sie, bevor er sie übernimmt — antwortet dort keine betriebsbereite
                Zentrale, bleibt alles beim Alten.

                Die Änderung bleibt über einen Neustart erhalten. Sie müssen dafür weder
                die Datei `.env` bearbeiten noch den Container neu aufsetzen.

                ## Später aktualisieren

                ```
                docker compose pull
                docker compose up -d
                ```

                Die Version steht in der `docker-compose.yml`. Ihre Zentrale meldet,
                wenn Agent und Zentrale nicht mehr zusammenpassen.
                """.formatted(einrichtung, bezeichnung, zentraleUrl);
    }
}
