# 40 — Übergabenotiz je Version

**Stand:** eingeführt 17.08.2026 · **Anlass:** Rückmeldung DevOps zum
Rollout 0.4.0 auf labs (16.08.2026)

---

## Warum es diese Notiz gibt

> „Bitte in der Übergabe je Version weiterhin angeben: Schema-Änderung, neue
> Env, neue Volumes/Rechte, Prüfschritte, Rückfall-Version." — DevOps,
> 16.08.2026

Beim Rollout auf labs kostete jeder fehlende dieser Punkte Arbeit: Der Sprung
von PostgreSQL 16 auf 17 tauchte erst auf, als der Container den Start
verweigerte. Der Reverse Proxy zeigte auf einen Containernamen, den die
gelieferte Datei nicht vergibt — lokal sah alles gesund aus, von außen kam
nichts an.

Beide Punkte sind **keine Änderungen von 0.3.0 auf 0.4.0**. Sie entstanden
dadurch, dass labs bisher mit einer selbst gebauten Compose-Datei lief
(PostgreSQL 16, Containername `pin-roboter`) und nun auf die gelieferte
umgestiegen ist. Genau das ist die Lehre: **Der Umstieg auf die gelieferte
Datei ist selbst ein Migrationsschritt** und gehört mit denselben Angaben
beschrieben wie ein Versionssprung.

**Ohne ausgefüllte Notiz wird keine Version übergeben.** Was hier nicht steht,
findet DevOps beim Rollout, und dann unter Zeitdruck.

## Vorlage

```markdown
# Übergabe <Baustein> <Version> — <Datum>

## Abbilder
ghcr.io/mathi66/…:<version>       (je Baustein, mit Digest aus dem Lauf)

## Schema-Änderungen
Migrationen <von>–<bis>. Je Migration eine Zeile, was sie tut.
Besonders kennzeichnen: alles, was Daten oder Spalten ENTFERNT — das ist
der Punkt, ab dem der Rückweg nur noch über Volume oder Sicherung führt.

## Neue oder geänderte Umgebungsvariablen
Name, Zweck, Pflicht/wahlfrei, Verhalten ohne Angabe.

## Neue Volumes, geänderte Rechte, geänderte Namen
Volumes, Containernamen, Netze, Nutzer-IDs, Fremdabbilder samt Fassung
(z. B. postgres). Jede Änderung an einem dieser Punkte ist eine Änderung
an der Schnittstelle nach draußen.

## Prüfschritte nach dem Rollout
Konkrete Befehle mit erwarteter Ausgabe, nicht „prüfen, ob es läuft".

## Rückfall-Version
Welche, und bis zu welchem Punkt sie noch trägt.
```

---

# Übergabe Zentrale 0.4.1 — 17.08.2026

**Nur die Zentrale.** Agent und Simulator bleiben unverändert auf 0.4.0 — am
Quelltext beider hat sich seit dem Tag nichts geändert (ADR-0013: getrennte
Versionierung, damit nicht ein Baustein den anderen mitzieht).

## Abbild

```
ghcr.io/mathi66/pin-roboter:0.4.1   sha256:87504833f7aa…
```

## Was behoben ist

Auf einer Installation **ohne registrierten Agenten** zeigte die Tafel
„Standort-Agenten" nur den Hinweis, dass keiner registriert sei — das
Anlegeformular darunter fehlte. Es ließ sich also kein Agent anlegen, genau in
dem Moment, in dem man das Formular braucht. Der Hinweistext verwies zudem auf
„Bestand"; dort war das Formular nie.

Auf labs am 17.08.2026 aufgefallen. Lokal nicht sichtbar, weil dort längst ein
Agent registriert ist.

## Schema-Änderungen

**Keine.** Migrationsstand bleibt 33.

## Neue oder geänderte Umgebungsvariablen

**Keine.**

## Neue Volumes, geänderte Rechte, geänderte Namen

**Keine.**

## Prüfschritte nach dem Rollout

```bash
# 1. Version
curl -s https://<host>/health
#    erwartet: {"status":"ok","db":true,"version":"0.4.1"}

# 2. Der eigentliche Punkt: Auf „Instanz“ muss unter „Standort-Agenten“
#    das Anlegeformular stehen, auch wenn noch kein Agent registriert ist.
#    Sichtbar sind dann die Felder „Bezeichnung“ und „Einrichtung“ sowie
#    die Knöpfe „Einrichtungspaket herunterladen“ und „Nur Zugangscode“.
```

> Steht dort stattdessen „Zuerst eine Einrichtung anlegen", fehlt eine
> Organisationseinheit — das ist richtiges Verhalten, kein Fehler: Ohne
> Einrichtung wäre der Agent niemandem zuzuordnen.

## Rückfall-Version

**0.4.0.** Ohne Einschränkung — es gibt keine Migration zwischen den beiden
Fassungen. `VERSION_ZENTRALE=0.4.0` und neu starten genügt.

---

# Übergabe Zentrale / Agent / Simulator 0.4.0 — 16.08.2026

## Abbilder

```
ghcr.io/mathi66/pin-roboter:0.4.0             sha256:27b4f370da35…
ghcr.io/mathi66/pin-roboter-agent:0.4.0       sha256:cc3311337d44…
ghcr.io/mathi66/pin-roboter-simulator:0.4.0   sha256:c43da17d4a2c…
```

Der **Simulator ist neu** und wird erstmals ausgeliefert. Er gehört nur in die
Laborumgebung, nicht in eine Praxis oder Klinik.

## Schema-Änderungen

Migrationen **28 bis 33** (0.3.x endete bei 27):

| Nr. | Was |
|---|---|
| 28 | `registry_zugang` erweitert |
| 29 | `agent` erweitert — Bausteinversion getrennt von der Protokollversion |
| 30 | neue Tabelle `agent_aktualisierung` (Aufträge, Fristen, Ergebnis) |
| 31 | `registry_zugang` erweitert |
| 32 | `agent_aktualisierung` erweitert |
| **33** | **`registry_zugang.an_agenten` entfernt** — Spalte fällt weg |

> **Ab Migration 33 gibt es keinen Rückweg über die Versionsnummer mehr.**
> Die Spalte, die 0.3.x erwartet, existiert nicht mehr.

## Neue Umgebungsvariablen

| Name | Baustein | Zweck | Ohne Angabe |
|---|---|---|---|
| `AUFTRAG_VERZEICHNIS` | Agent | Briefkasten für Aktualisierungsaufträge | Selbstaktualisierung nicht verfügbar; der Agent sagt das im Klartext |
| `OBERFLAECHE_ADRESSE` | Agent | Bindeadresse der Agentenanzeige | `127.0.0.1` im Container — über ein Port-Mapping dann **nicht** erreichbar |

## Neue Volumes, geänderte Namen

| Was | Wert | Anmerkung |
|---|---|---|
| Volume `auftrag` | am Agenten unter `/auftrag` | getrennt von `agentdaten`, damit ein Aktualisierungsdienst nicht an Token, Schlüssel und PINs kommt |
| Verzeichnis `/auftrag` im Abbild | Eigentümer UID 10001 | im Dockerfile angelegt und übereignet; sonst „Der Auftrag ließ sich nicht ablegen" |

**Beim Umstieg auf `docker-compose.labor.yml` zusätzlich zu beachten** — das
sind keine Änderungen von 0.3.0 auf 0.4.0, sondern Unterschiede zu einer selbst
gebauten Datei:

| Punkt | Wert in der gelieferten Datei |
|---|---|
| PostgreSQL | `postgres:17-alpine` — ein 16er-Datenverzeichnis wird **nicht** angenommen, es braucht Dump und Rückspielen |
| Projektname | `pin-roboter` → Volumes `pin-roboter_betriebsdaten`, `_sicherungen`, `_agentdaten`, `_auftrag` |
| Containernamen | `pinroboter-zentrale`, `-agent`, `-simulator`, `-datenbank` |
| Proxy-Netz | nicht enthalten — Zusatz `docker-compose.proxy.yml` mit `PROXY_NETZ=<netz>` |

## Prüfschritte nach dem Rollout

```bash
# 1. Version — muss 0.4.0 melden, auch von außen über den Proxy
curl -s https://<host>/health
#    erwartet: {"status":"ok","db":true,"version":"0.4.0"}

# 2. Der Simulator darf von außen nicht erreichbar sein
docker port pinroboter-simulator
#    erwartet: KEINE Ausgabe

# 3. Migrationsstand
docker exec pinroboter-datenbank psql -U pinroboter -d pinroboter \
  -c "SELECT max(version) FROM schema_stand;"
#    erwartet: 33

# 4. Zeilenzahl je Tabelle gegen den Stand vor dem Rollout
docker exec pinroboter-datenbank psql -U pinroboter -d pinroboter \
  -c "SELECT relname, n_live_tup FROM pg_stat_user_tables ORDER BY relname;"

# 5. Alle Dienste als Nicht-Root
for c in zentrale agent simulator; do docker exec pinroboter-$c id -u; done
#    erwartet: dreimal 10001
```

## Rückfall-Version

**0.3.2** — aber **nur, bevor die Zentrale in 0.4.0 einmal gestartet ist.**
Danach ist Migration 33 gelaufen und die Spalte `an_agenten` weg.

Ab diesem Punkt führt der Rückweg ausschließlich über das alte Volume oder
eine Sicherung. Deshalb: alte Volumes stehen lassen, bis die Installation
abgenommen ist.

## Nach dem Rollout offen

- Der Agent auf labs ist **noch nicht registriert**. Zugangscode in der
  Oberfläche unter *Instanz → Standort-Agenten* erzeugen, als `REGISTRIERUNG`
  eintragen, Agent neu starten. Der Code gilt einmalig und läuft nach 24
  Stunden ab.
