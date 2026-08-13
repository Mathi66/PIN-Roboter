# Release-Notes PIN-Roboter

Für DevOps. Nicht die Funktionen stehen hier im Vordergrund, sondern das,
wonach beim Deploy gehandelt wird: Schemaänderungen, Umgebungsvariablen,
Volumes, Prüfschritte, Rückfall.

Versionsschema: Git-Tag `vX.Y.Z` → Image `ghcr.io/mathi66/pin-roboter:X.Y.Z`
(ohne führendes `v`). Tags sind unveränderlich; ein Fehler bekommt eine neue
Nummer. Kein `:latest`.

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
