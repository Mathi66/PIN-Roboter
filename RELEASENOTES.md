# Release-Notes PIN-Roboter

Für DevOps. Nicht die Funktionen stehen hier im Vordergrund, sondern das,
wonach beim Deploy gehandelt wird: Schemaänderungen, Umgebungsvariablen,
Volumes, Prüfschritte, Rückfall.

Versionsschema: Git-Tag `vX.Y.Z` → Image `ghcr.io/mathi66/pin-roboter:X.Y.Z`
(ohne führendes `v`). Tags sind unveränderlich; ein Fehler bekommt eine neue
Nummer. Kein `:latest`.

---

## 0.2.0 — Erstinbetriebnahme auf Labs

**Erste Auslieferung an DevOps.** Alle Angaben zur Ersteinrichtung stehen in
[docs/09a-uebergabe-devops.md](docs/09a-uebergabe-devops.md).

| Angabe | Wert |
|---|---|
| Image | `ghcr.io/mathi66/pin-roboter:0.2.0`, `linux/amd64` |
| Schema-Änderung | **ja** — läuft automatisch und idempotent beim Start. Bei einer leeren Datenbank wird das gesamte Schema angelegt (Migrationen 1–27). |
| Umkehrbar | **nein.** Die Migrationen sind vorwärtsgerichtet. Ein Rückweg über einen älteren Image-Tag ist bei 0.2.0 gegenstandslos (Erstinstallation); ab 0.3.0 steht hier je Version, ob ein Rückweg genügt. |
| Neue Env | alle — siehe Übergabedokument, Abschnitt 2 |
| Neue Volumes | `pinroboter-db` (Datenbank), `pinroboter-sicherungen` (Sicherungsdateien) |
| Neue Pfade mit anderer Authentifizierung | `/health` (ohne Anmeldung, keine Bestandsdaten), `/api/agent/*` und `/api/registrieren` (Token des Standort-Agenten, **keine** Sitzung) |
| Prüfung danach | `curl -s https://pin-roboter.lab.vps.nieting.de/health` liefert `{"status":"ok","db":true,"version":"0.2.0"}`; die Anmeldeseite ist erreichbar; im Protokoll der Zentrale steht `Migration 27 angewendet` |
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
  `X-Forwarded-Proto: https` ankommt. Fehlt der Kopf, fehlt das Kennzeichen —
  bitte im Caddy-Block belassen (Caddy setzt ihn von sich aus).
