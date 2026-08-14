# Release-Notes PIN-Roboter

Für DevOps. Nicht die Funktionen stehen hier im Vordergrund, sondern das,
wonach beim Deploy gehandelt wird: Schemaänderungen, Umgebungsvariablen,
Volumes, Prüfschritte, Rückfall.

**Versionsschema seit 0.4.0** ([ADR-0013](docs/adr/0013-getrennte-versionierung-von-zentrale-und-agent.md)):
Zentrale und Agent tragen **eigene** Nummern und werden getrennt veröffentlicht.

| Git-Tag | Image |
|---|---|
| `zentrale-X.Y.Z` | `ghcr.io/mathi66/pin-roboter:X.Y.Z` |
| `agent-X.Y.Z` | `ghcr.io/mathi66/pin-roboter-agent:X.Y.Z` |

Ein Tag veröffentlicht **nur seinen Baustein**. Tags sind unveränderlich; ein
Fehler bekommt eine neue Nummer. Kein `:latest`. Bis 0.3.2 galt `vX.Y.Z` für
beide zugleich.

---

## Zentrale 0.4.0 und Agent 0.4.0 — Aktualisierung auf Auftrag, Handbuch, Hilfe

| Angabe | Wert |
|---|---|
| Images | `ghcr.io/mathi66/pin-roboter:0.4.0`, `ghcr.io/mathi66/pin-roboter-agent:0.4.0` |
| Schema-Änderung | **ja** — Migrationen 28 bis 33, laufen automatisch |
| Neue Env (Agent) | `AUFTRAG_VERZEICHNIS` (Vorgabe `/auftrag`) |
| Entfallene Env | `REGISTRY_VERZEICHNIS`, `AKTUALISIERUNG_TOKEN` |
| Neue Volumes | `auftrag` beim Agenten; `registryzugang` entfällt |
| Prüfung danach | `/health` meldet `{"status":"ok","db":true,"version":"0.4.0"}` |
| Rückfall | `0.3.2` — die Migrationen sind additiv, nur Migration 33 entfernt eine ungenutzte Spalte |

**Für DevOps wesentlich**

- **Zwei Tags statt einem.** Wer beide Bausteine veröffentlichen will, setzt
  `zentrale-0.4.0` **und** `agent-0.4.0`.
- **Das Einrichtungspaket enthält eine vierte Datei** (`aktualisieren.sh`) und
  zwei zusätzliche Dienste (`aktualisierung`, `dockerzugang`). Der
  Socket-Vermittler braucht jetzt auch `NETWORKS`, `VOLUMES` und `INFO`.
- **Kein Watchtower mehr**, kein nächtlicher Lauf. Aktualisiert wird nur auf
  Auftrag aus der Zentrale.
- **Am Standort liegt kein Zugangstoken**, solange dort nichts aktualisiert
  wird.

**Was Anwender sehen**

- Handbuch als eigener Punkt in der Oberfläche, durchsuchbar
- Info-Knöpfe an Kacheln und Feldern, die den passenden Handbuchabschnitt zeigen
- Agentenliste mit Version, verfügbaren Fassungen und gezielter Aktualisierung
- Alarme für stumme und zurückhängende Agenten

---

## 0.3.2 — Images auf ein Viertel verkleinert

**Nur der Unterbau, keine Funktionsänderung.**

| Angabe | Wert |
|---|---|
| Images | `ghcr.io/mathi66/pin-roboter:0.3.2`, `ghcr.io/mathi66/pin-roboter-agent:0.3.2` |
| Schema-Änderung | **keine** |
| Neue Env | keine |
| Neue Volumes | keine |
| Prüfung danach | `/health` meldet `{"status":"ok","db":true,"version":"0.3.2"}` |
| Rückfall | `0.3.1` |

**Was sich ändert**

| | vorher | jetzt |
|---|---|---|
| Zentrale, entpackt | 632 MB | **155 MB** |
| Zentrale, Download | 142 MB | **58 MB** |
| Agent, entpackt | 544 MB | **135 MB** |
| Agent, Download | 125 MB | **50 MB** |

Gemessen war: Basisimage `eclipse-temurin:21-jre` 524 MB, PostgreSQL-Client
65 MB, eigenes Jar 18 MB. Nicht die Anwendung war groß, sondern ihr Unterbau.

Ersetzt durch eine **per jlink zugeschnittene Java-Laufzeit auf Alpine**. Die
Modulliste wird beim Bauen mit `jdeps` aus dem Jar ermittelt, nicht von Hand
gepflegt — eine gepflegte Liste läuft dem Code hinterher.

**Für den Betrieb zu beachten**

- Im Image gibt es **keine bash mehr**. Wer eigene Healthchecks oder
  `docker exec`-Aufrufe mit `bash -c` verwendet, muss auf `sh` wechseln. Der
  Healthcheck im Image nutzt jetzt BusyBox-`wget`.
- Der **PostgreSQL-Client ist Version 17** statt 18 (Alpine-Paket). Geprüft
  gegen Server 16 **und** 17: Auszug und Wiederherstellung funktionieren. Wer
  den Server auf 18 anhebt, muss den Client mitanheben.

**Nachgemessen, nicht angenommen:** Der vollständige PEviM-Durchstich läuft auf
dem neuen Unterbau — SMC-B wird freigeschaltet, die PIN geht über die Remote
SMCB API, **null** TLS-Handshake-Fehler. BouncyCastle ist reines Java; der
Wechsel von glibc auf musl berührt die Brainpool-Kurven nicht. Das war die
Annahme — jetzt ist es gemessen.

---

## 0.3.1 — Der Standort-Agent erreicht eine Zentrale hinter TLS

**Fehlerbehebung.** Für die Zentrale auf Labs ändert sich nichts an Betrieb oder
Konfiguration; ein Deploy ist trotzdem sinnvoll, weil die Versionen der beiden
Bausteine zusammenpassen sollen.

| Angabe | Wert |
|---|---|
| Images | `ghcr.io/mathi66/pin-roboter:0.3.1` **und neu** `ghcr.io/mathi66/pin-roboter-agent:0.3.1` |
| Schema-Änderung | **keine** |
| Neue Env | keine |
| Neue Volumes | keine für die Zentrale. Der Agent braucht `/daten` (Token, Schlüsselpaar, PIN-Ablage) — er läuft aber im Praxisnetz, nicht auf Labs. |
| Prüfung danach | `/health` meldet `{"status":"ok","db":true,"version":"0.3.1"}` |
| Rückfall | `0.3.0` — genügt, da keine Schemaänderung |

**Was behoben ist**

Der Standort-Agent sprach mit *allem* über denselben TLS-Client: Konnektor,
Kartenterminal **und** Zentrale. Dessen Vertrauensspeicher enthält
ausschließlich die CA aus `CA_URL` — die TI-Strecke. Steht die Zentrale hinter
einem Reverse Proxy mit einem Zertifikat von Let's Encrypt, scheitert die
Verbindung am Vertrauensanker. Im Entwicklungsstack fiel das nie auf, weil die
Zentrale dort über schlichtes `http` erreichbar war.

Der Agent nutzt für die Zentrale jetzt einen eigenen Client mit dem
System-Vertrauensspeicher. Die Einschränkung auf TLS 1.2 und zwei Cipher-Suites
aus `[gemSpec_Krypt]` gilt weiterhin für Konnektor und Kartenterminal — dort
gehört sie hin.

> Gemessen gegen `pin-roboter.lab.vps.nieting.de`: Die Cipher-Suites hätten
> sogar gepasst (Caddy kann TLS 1.2 mit `ECDHE-ECDSA-AES128-GCM-SHA256`). Es
> scheiterte allein am Vertrauensanker.

**Neu: Image für den Standort-Agenten**

Bis 0.3.0 baute der Workflow nur die Zentrale — den Agenten gab es lediglich als
Quelltext. Er wird jetzt unter derselben Version mitveröffentlicht.

---

## 0.3.0 — Erstinbetriebnahme auf Labs

**Erste Auslieferung an DevOps.** Alle Angaben zur Ersteinrichtung stehen in
[docs/09a-uebergabe-devops.md](docs/09a-uebergabe-devops.md).

| Angabe | Wert |
|---|---|
| Image | `ghcr.io/mathi66/pin-roboter:0.3.0`, `linux/amd64` |
| Schema-Änderung | **ja** — läuft automatisch und idempotent beim Start. Bei einer leeren Datenbank entsteht das gesamte Schema (Migrationen 1–27). |
| Umkehrbar | **nein.** Die Migrationen sind vorwärtsgerichtet. Bei einer Erstinstallation ist das gegenstandslos; ab der nächsten Version steht hier, ob ein Rückweg über den Image-Tag genügt. |
| Neue Env | alle — siehe Übergabedokument, Abschnitt 2. Pflicht: `DB_PASSWORD`, `GEHEIMNIS_SCHLUESSEL`. |
| Neue Volumes | `pinroboter-db` (Datenbank), `pinroboter-sicherungen` (Sicherungsdateien) |
| Neue Pfade mit anderer Authentifizierung | `/health` (ohne Anmeldung, keine Bestandsdaten), `/api/agent/*` und `/api/registrieren` (Token des Standort-Agenten, **keine** Sitzung) |
| Prüfung danach | `curl -s https://pin-roboter.lab.vps.nieting.de/health` liefert `{"status":"ok","db":true,"version":"0.3.0"}`; Anmeldeseite erreichbar; im Protokoll steht `Migration 27 angewendet` |
| Rückfall | keiner — Erstinstallation. Bei Fehlschlag: Stack anhalten, Volume `pinroboter-db` verwerfen, erneut starten. |

**Was in dieser Version besonders zu beachten ist**

- Der **erste Administrator** wird über `START_ADMIN_*` angelegt. Ohne diese
  Variablen entsteht das Konto über die Ersteinrichtungsseite — wer sie zuerst
  aufruft, wird Administrator. Auf einer öffentlich erreichbaren Instanz ist das
  ein Wettlauf. Deshalb: Variablen setzen, Stack starten, Konto prüfen, **erst
  dann** den DNS-Namen veröffentlichen.
- `GEHEIMNIS_SCHLUESSEL` ist Pflicht und **muss in die Sicherung** — aber nicht
  in dieselbe Datei wie der Datenbankauszug. Geht er verloren, sind die
  gespeicherten Geräte-Zugangsdaten unbrauchbar.
- Die Anwendung setzt das `Secure`-Kennzeichen am Sitzungscookie selbst, sobald
  `X-Forwarded-Proto: https` ankommt. Bitte im Caddy-Block belassen (Caddy setzt
  den Kopf von sich aus).
- **Verändernde Aufrufe werden gegen den `Origin`-Kopf geprüft** (403 bei
  fremdem Ursprung). Wenn im Proxy einmal ein anderer Hostname davorgesetzt
  wird, muss der `Host`-Kopf durchgereicht werden — sonst lehnt die Anwendung
  eigene Formulare ab.

**Wesentliche Änderungen gegenüber 0.2.0** (für die Abnahme, nicht für den Deploy)

- Gerätehierarchie nach Organisationsstruktur: fünf Ebenen, BSNR und
  Telematik-ID an der Betriebsstätte, ein Konnektor kann von mehreren
  Betriebsstätten mitgenutzt werden (ADR-0012, Migration 24).
- Strukturansicht der Organisation mit Sinnbildern und Zustandsampel.
- Alarme lassen sich einzeln und gesammelt schließen (FA-ALA-030).
- Die Zuordnung eines Standort-Agenten ist korrigierbar.
- Gruppenbezeichnungen sind instanzweit eindeutig (Migrationen 26, 27).
- Sicherheit: `Secure` am Sitzungscookie, `X-Forwarded-For` ausgewertet,
  CSRF-Prüfung über den `Origin`-Kopf.

---

## 0.2.0 — intern, nicht ausgeliefert

Stand vom 11.08.2026. Der Tag existiert im Repository, wurde aber nie nach GHCR
veröffentlicht: Die Betriebsschnittstellen aus der DevOps-Liste (`/health`,
`HEALTHCHECK`, gehärtetes Compose, Workflow) kamen erst mit 0.3.0 dazu.
