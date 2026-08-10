# Installation einer Instanz — Anweisung für DevOps

**Zielgruppe:** Personen mit Docker- und Linux-Kenntnissen, die eine Instanz für
eine Einrichtung oder eine Serviceorganisation aufsetzen und betreiben.

**Abgrenzung:** [04-betrieb.md](04-betrieb.md) richtet sich an den Betreiber
einer Praxis ohne IT-Fachwissen. Dieses Dokument geht tiefer: Netzwerkschnitt,
Secrets, Reverse-Proxy, Härtung, Wiederherstellung, Betriebsüberwachung.

**Stand:** 10.08.2026. Werte stammen aus `docker-compose.yml` und dem Quelltext,
nicht aus der Erinnerung.

---

## 1. Systemüberblick in einem Absatz

Vier Container: **Datenbank** (PostgreSQL 17), **Zentrale** (Java 21, Javalin,
Oberfläche und Steuerung), **Standort-Agent** (Java 21, spricht mit Konnektor und
Kartenterminals) und optional der **Simulator** (nur für Test und Abnahme, nie
produktiv). Der Agent baut die Verbindung zur Zentrale **ausgehend** auf — in das
Netz der Einrichtung ist keine eingehende Freigabe nötig
([ADR-0002](adr/0002-betriebsmodell-und-systemtopologie.md)).

```
Internet/WAN
     │  (ausgehend, HTTPS)
     ▼
┌──────────────┐        ┌────────────┐
│  Zentrale    │◀──────▶│  Datenbank │      Rechenzentrum / Praxis-Server
│  :8090       │        │  :5432     │
└──────────────┘        └────────────┘
     ▲
     │  Agent meldet sich (ausgehend)
┌──────────────┐
│ Standort-    │──▶ Konnektor / HSK  (TLS 1.2, brainpoolP256r1)
│ Agent        │──▶ Kartenterminals  (Managementschnittstelle, PEviM)
└──────────────┘        Praxisnetz
```

---

## 2. Voraussetzungen

| | Minimum | Empfohlen |
|---|---|---|
| Docker Engine | 24.0 | aktuell |
| Compose | v2 | v2 |
| RAM | 2 GB (Einzelpraxis) | 8 GB (ab ~500 Terminals) |
| Plattenplatz | 20 GB | 50 GB + Protokollbudget |
| Architektur | `linux/amd64` oder `linux/arm64` | — |

**Betriebssysteme:** Linux, Windows und macOS. Pfadangaben und
Volume-Einbindungen sind so gewählt, dass sie auf allen dreien funktionieren.

**Uhrzeit:** Der Host muss eine synchronisierte Uhr haben (NTP/chrony). Die
Anmeldung nutzt zeitbasierte Einmalkennwörter; eine Abweichung über ±30 Sekunden
sperrt alle Benutzer aus. Das ist der häufigste Grund für „Anmeldung
funktioniert plötzlich nicht mehr" nach einem Serverumzug.

---

## 3. Netzwerkschnitt

| Von | Nach | Port | Protokoll | Pflicht |
|---|---|---|---|---|
| Arbeitsplatz | Zentrale | 8090/tcp | HTTP (hinter Proxy: HTTPS) | ja |
| Zentrale | Datenbank | 5432/tcp | PostgreSQL | ja |
| Agent | Zentrale | 8090/tcp | HTTP(S), **ausgehend** | ja |
| Agent | Konnektor/HSK | 443/tcp | TLS 1.2, Brainpool | ja |
| Agent | Kartenterminal | 443/tcp | TLS, Managementschnittstelle | ja |
| Zentrale | SMTP-Relay | 25/465/587 | SMTP(S) | nur für FA-ALA-020 |
| Zentrale | GHCR | 443/tcp | HTTPS | nur für FA-UPD-010 |

**Die Zentrale darf nicht unverschlüsselt aus einem unsicheren Netz erreichbar
sein.** Sie spricht selbst HTTP; TLS gehört vor sie (Abschnitt 7).

---

## 4. Installation

### 4.1 Ablage anlegen

```bash
mkdir -p /opt/pin-roboter && cd /opt/pin-roboter
```

Aus dem Repository benötigt werden `docker-compose.yml` und — falls lokal
gebaut wird — die Verzeichnisse `src/` und `pruefungen/`.

### 4.2 Geheimnisse erzeugen

**Vor dem ersten Start.** Drei unabhängige Werte, keiner davon geraten:

```bash
openssl rand -base64 24 > /opt/pin-roboter/secrets/db_passwort
openssl rand -base64 32 > /opt/pin-roboter/secrets/geheimnis_schluessel
openssl rand -base64 32 > /opt/pin-roboter/secrets/pin_ablage_schluessel
chmod 600 /opt/pin-roboter/secrets/*
```

| Wert | Wofür | Verlust bedeutet |
|---|---|---|
| `DB_PASSWORD` | Datenbankzugang | Datenbank nicht mehr erreichbar |
| `GEHEIMNIS_SCHLUESSEL` | Zugangsdaten der Geräte und des Mailservers (AES-256-GCM, [ADR-0008](adr/0008-ablage-von-geraete-zugangsdaten.md)) | alle hinterlegten Kennwörter unbrauchbar, müssen neu erfasst werden |
| `PIN_ABLAGE_SCHLUESSEL` | PIN-Ablage **im Agenten** ([ADR-0009](adr/0009-pin-hinterlegen-ueber-die-oberflaeche.md)) | hinterlegte PINs unbrauchbar, müssen neu hinterlegt werden |

> **Getrennt aufbewahren.** Die Schlüssel gehören in die Sicherung — aber
> **nicht in dieselbe Datei wie der Datenbankauszug**. Liegen Chiffrat und
> Schlüssel zusammen, schützt die Verschlüsselung vor nichts.

### 4.3 `.env` anlegen

```bash
cat > /opt/pin-roboter/.env <<'ENDE'
DB_PASSWORD=<Inhalt von secrets/db_passwort>
GEHEIMNIS_SCHLUESSEL=<Inhalt von secrets/geheimnis_schluessel>
PIN_ABLAGE_SCHLUESSEL=<Inhalt von secrets/pin_ablage_schluessel>
PROTOKOLL_BUDGET=2G
ENDE
chmod 600 /opt/pin-roboter/.env
```

`.env` ist in `.gitignore` und gehört **nie** in ein Repository.

### 4.4 Administratorkonto festlegen (empfohlen)

Ohne diese Angaben entsteht der erste Administrator einmalig über die
Ersteinrichtung im Browser — mit einem TOTP-Geheimnis, das genau einmal
angezeigt wird. **Geht das Datenvolumen verloren, ist der Zugang verloren:** Bei
der nächsten Ersteinrichtung entsteht ein anderes Geheimnis, und der Eintrag in
der Authenticator-App passt nicht mehr.

```bash
# TOTP-Geheimnis erzeugen (Base32, wie Authenticator-Apps es erwarten)
openssl rand -base64 20 | base32 | tr -d '=' | head -c 32; echo
```

```bash
cat >> /opt/pin-roboter/.env <<'ENDE'
START_ADMIN_BENUTZER=admin
START_ADMIN_NAME=Administration
START_ADMIN_PASSWORT_DATEI=/run/secrets/start_admin_passwort
START_ADMIN_TOTP=<das erzeugte Base32-Geheimnis>
ENDE
```

Verhalten:

- Das Konto entsteht beim Start, **falls es fehlt**.
- Ein **vorhandenes Konto wird nie verändert** — kein Zurücksetzen von Passwort,
  Rolle oder zweitem Faktor bei jedem Neustart. Sonst könnte jemand mit
  Schreibzugriff auf die Compose-Datei sich per Neustart immer wieder Zutritt
  verschaffen, auch nachdem man ihn ausgesperrt hat.
- Das Anlegen erscheint im Betriebs- **und** Fachprotokoll. Passwort und
  Geheimnis erscheinen nirgends.

> **Sicherheitsvorbehalt, den der Betreiber trägt:** Wer die Konfiguration lesen
> kann, kennt **beide Faktoren** — der zweite ist dann keiner mehr. Zwei
> Gegenmaßnahmen: Passwort über `START_ADMIN_PASSWORT_DATEI` aus einem
> Docker-Secret lesen (erscheint nicht in `docker inspect` und nicht in der
> Prozessliste), und die Variablen nach der Einrichtung wieder entfernen.

### 4.5 Starten

```bash
docker compose up -d
```

Erwartetes Ergebnis:

```bash
docker compose ps --format "{{.Service}}: {{.Status}}"
```

Datenbank, Zentrale und Simulator melden `healthy`. Der Agent hat keinen
Health-Check — sein Zustand steht in der Oberfläche unter „Instanz".

Die Datenbankmigrationen (21 Stück, Stand 09.08.2026) laufen beim Start der
Zentrale automatisch und sind idempotent. Ein zweiter Start wiederholt sie nicht.

---

## 5. Konfigurationsreferenz

### Zentrale

| Variable | Vorgabe | Bedeutung |
|---|---|---|
| `PORT` | `8090` | Port der Oberfläche im Container |
| `DB_URL` | `jdbc:postgresql://datenbank:5432/pinroboter` | Datenbankadresse |
| `DB_USER` | `pinroboter` | Datenbankbenutzer |
| `DB_PASSWORD` | — | **Pflicht.** Fehlt sie, bricht der Start mit verständlicher Meldung ab (Exitcode 2). |
| `GEHEIMNIS_SCHLUESSEL` | leer | 32 Byte Base64. Fehlt er, bleibt die Bestandserfassung nutzbar; nur Kennwörter lassen sich nicht speichern. Die Oberfläche sagt das. |
| `PROTOKOLL_BUDGET` | `2G` | Speicherbudget des Fachprotokolls. Ab 80 % Füllstand rotiert es gestaffelt (ADR-0005). |
| `START_ADMIN_*` | leer | siehe 4.4 |

### Standort-Agent

| Variable | Bedeutung |
|---|---|
| `ZENTRALE_URL` | Adresse der Zentrale, **ausgehend** erreichbar |
| `REGISTRIERUNG` | Einmaliger Code aus der Oberfläche (Instanz → Agenten). Gilt 24 h, genau einmal. |
| `TOKEN_DATEI` | Ablage des dauerhaften Tokens nach der Registrierung |
| `KONNEKTOR_URL` | Adresse des Konnektors oder HSK |
| `MANDANT_ID`, `CLIENTSYSTEM_ID`, `ARBEITSPLATZ_ID` | Aufrufkontext des Konnektors (`[gemSpec_Kon]`). **Nicht identisch mit unseren Mandanten.** |
| `PIN_ABLAGE` | Pfad der verschlüsselten PIN-Ablage |
| `PIN_ABLAGE_SCHLUESSEL` | Schlüssel dafür (ADR-0009) |
| `PRUEFINTERVALL_SEKUNDEN` | Vorgabe, wenn keine Gruppe etwas anderes sagt |

---

## 6. Agenten anbinden

1. In der Oberfläche: **Instanz → Agenten → Bezeichnung und Einrichtung wählen
   → Zugangscode erzeugen.**
2. Den Code in die Konfiguration des Agenten als `REGISTRIERUNG` eintragen.
3. Agent starten. Er tauscht den Code gegen ein dauerhaftes Token und legt es
   unter `TOKEN_DATEI` ab.

> **Die Einrichtung wird beim Erzeugen des Codes festgelegt, nicht vom Agenten
> gemeldet.** Das ist die sichere Richtung: Ein übernommener oder nachgebauter
> Agent könnte sonst Geräte in einer fremden Einrichtung unterschieben. Alles,
> was der Agent meldet — Geräte, Alarme, Protokolleinträge — gehört zu dieser
> Einrichtung.

Der Code gilt **einmalig** und läuft nach 24 Stunden ab. Ein zweiter Versuch mit
demselben Code wird abgewiesen und im Fachprotokoll als Angriffshinweis
vermerkt.

---

## 7. TLS und Reverse-Proxy

Die Zentrale spricht HTTP. In jeder Installation, die über `localhost`
hinausgeht, gehört ein Proxy davor. Beispiel für Caddy:

```
pin-roboter.example.internal {
    encode gzip
    reverse_proxy zentrale:8090
}
```

Für nginx:

```nginx
server {
    listen 443 ssl http2;
    server_name pin-roboter.example.internal;

    ssl_certificate     /etc/ssl/certs/pin-roboter.pem;
    ssl_certificate_key /etc/ssl/private/pin-roboter.key;
    ssl_protocols       TLSv1.2 TLSv1.3;

    location / {
        proxy_pass         http://zentrale:8090;
        proxy_set_header   Host              $host;
        proxy_set_header   X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header   X-Forwarded-Proto $scheme;
        # Die Oberfläche nutzt HTMX mit kurzen Abfragen; Pufferung stört nicht,
        # aber der Zeitrahmen muss den Excel-Export großer Protokolle tragen.
        proxy_read_timeout 300s;
    }
}
```

Danach `8090` **nicht mehr** nach außen veröffentlichen: In
`docker-compose.yml` den Portabschnitt der Zentrale entfernen und den Proxy in
dasselbe Docker-Netz hängen.

Das Sitzungscookie trägt `HttpOnly` und `SameSite=Strict`. `Secure` setzt die
Anwendung nicht selbst, weil sie ihre eigene Erreichbarkeit nicht kennt — der
Proxy soll es ergänzen.

---

## 8. Härtung

**Bereits im Image umgesetzt:**

- Container laufen als **Nicht-Root** (UID 10001).
- Datenverzeichnisse gehören diesem Benutzer; ein Start als Root ist nicht nötig.
- Abhängigkeiten sind über Lockfiles gepinnt.

**Vom Betreiber zu ergänzen:**

```yaml
    read_only: true
    tmpfs:
      - /tmp
    cap_drop:
      - ALL
    security_opt:
      - no-new-privileges:true
```

Nicht möglich für die Datenbank (`read_only` bricht PostgreSQL) und für den
Agenten, sofern er die PIN-Ablage schreibt.

**Ressourcengrenzen** — verhindern, dass ein Ausreißer den Host mitnimmt:

```yaml
    deploy:
      resources:
        limits:
          memory: 1g
          cpus: "1.5"
```

---

## 9. Sicherung und Wiederherstellung

### 9.0 Die eingebaute Sicherung (seit 10.08.2026)

Die Zentrale sichert selbsttätig — drei Pläne, in einem eigenen Volume
`sicherungen`. Konfiguration:

| Variable | Vorgabe | Bedeutung |
|---|---|---|
| `SICHERUNG_ABLAGE` | `/sicherungen` | Ablageort im Container |
| `SICHERUNG_PLAN` | `an` | `aus` schaltet die Zeitpläne ab; von Hand geht es weiterhin |

| Plan | behält | Rotation |
|---|---:|---|
| täglich | 7 | wöchentlich rollierend |
| wöchentlich | 5 | monatlich rollierend |
| monatlich | 12 | jährlich rollierend |

**Zwei Voraussetzungen, ohne die es nicht funktioniert:**

1. **`restart: unless-stopped` für die Zentrale.** Nach einer Wiederherstellung
   beendet sie sich absichtlich, damit kein Zustand aus dem alten Datenstand im
   Arbeitsspeicher zurückbleibt ([ADR-0010](adr/0010-datensicherung-und-wiederherstellung.md)).
   Ohne die Regel bliebe sie danach aus.
2. **Das Volume `sicherungen`** muss eingehängt sein. Fehlt es, liegen die
   Sicherungen im Container-Layer und verschwinden beim nächsten Update.

**Die Versionskopplung:** Der `pg_dump` im Image muss mindestens so neu sein wie
die Datenbank. Aktuell Client 18, Server 17 — geprüft. Wer die Datenbank
anhebt, muss das Basisimage mit anheben.

**Der Download ist der Teil, der nicht automatisch geht.** Beide Volumes liegen
auf demselben Wirt; gegen einen Festplattenausfall hilft nur, Sicherungen
herunterzuladen und woanders aufzubewahren. Der Schlüssel dafür wird nirgends
gespeichert — **geht er verloren, ist die Datei unbrauchbar.**

### 9.1 Von Hand, wenn die Oberfläche nicht erreichbar ist


### Was gesichert werden muss

| Gegenstand | Wo | Ohne ihn |
|---|---|---|
| Betriebsdaten | Volume `betriebsdaten` | alles weg |
| Agentendaten | Volume `agentdaten` | Token und PIN-Ablage weg, Agent muss neu registriert werden |
| `.env` und Secrets | Host | Chiffrate unbrauchbar |

### Sicherung

```bash
docker compose exec -T datenbank pg_dump -U pinroboter -Fc pinroboter > sicherung-$(date +%F).dump
```

Der Auszug enthält **verschlüsselte** Zugangsdaten. Ohne
`GEHEIMNIS_SCHLUESSEL` ist er allein wertlos — das ist beabsichtigt, macht den
Schlüssel aber zum Teil der Sicherungsstrategie.

### Wiederherstellung

```bash
docker compose up -d datenbank
docker compose exec -T datenbank psql -U pinroboter -c "DROP DATABASE IF EXISTS pinroboter"
docker compose exec -T datenbank psql -U pinroboter -c "CREATE DATABASE pinroboter"
docker compose exec -T datenbank pg_restore -U pinroboter -d pinroboter < sicherung-2026-08-09.dump
docker compose up -d
```

Danach zwingend prüfen: Anmeldung, ein hinterlegtes Gerätekennwort
(entschlüsselbar?), Protokollzugriff.

**Eine Wiederherstellung ohne vorherigen Probelauf ist keine Sicherung.**

---

## 10. Aktualisierung

```bash
docker compose pull
docker compose up -d
```

Migrationen laufen automatisch und idempotent. **Vor jeder Aktualisierung eine
Sicherung ziehen** — eine Migration lässt sich nicht zurücknehmen.

Rückkehr zur vorherigen Version: gewünschten Tag in `.env` eintragen und erneut
`docker compose up -d`. Das funktioniert nur, solange keine Migration lief, die
das Schema unvereinbar geändert hat; die Freigabemitteilung sagt es je Version.

**Kein `:latest` in Produktion.** Ein unbeabsichtigtes Update legt im
ungünstigen Fall den TI-Zugang ohne Vorwarnung lahm.

Zu Watchtower: siehe [04-betrieb.md](04-betrieb.md), Abschnitt 5. Kurzfassung —
nur mit Socket-Proxy statt direktem Docker-Socket, nur auf die Container dieses
Produkts beschränkt, und nur mit geprüften Tags.

---

## 11. Betriebsüberwachung

| Frage | Wo |
|---|---|
| Laufen die Container? | `docker compose ps` — Datenbank, Zentrale, Simulator melden `healthy` |
| Ist der Agent verbunden? | Oberfläche, Kopf der Seitenleiste |
| Gibt es offene Alarme? | Oberfläche → Lage |
| Füllt sich das Protokoll? | Oberfläche → Instanz → Speicher |
| Was ist zuletzt passiert? | Oberfläche → Protokoll, oder `docker compose logs zentrale` |

Health-Check der Zentrale:

```bash
curl -fsS http://localhost:8090/anmelden > /dev/null && echo "erreichbar"
```

**Im Log erscheinen nie:** PINs, Terminal-Kennwörter, Zugangsdaten,
TOTP-Geheimnisse. Das wird zentral erzwungen, nicht an jeder Einzelstelle. Wenn
dort doch etwas auftaucht, ist das ein Fehler und gehört gemeldet.

---

## 12. Abnahme der Installation

Nach dem Aufsetzen sollte der Nachweislauf durchlaufen — er prüft den Stack, den
das Repository baut, nicht eine Behauptung darüber:

```bash
bash pruefungen/systemnachweise.sh
```

Er startet **eigene Container auf eigenen Ports** (8190, 8580) und lässt eine
laufende Instanz unberührt. Erwartet werden 0 Fehlschläge.

Für die Barrierefreiheit und die Einheitlichkeit der Oberfläche gibt es zwei
gesonderte Läufe, die der Hauptlauf mit aufruft:

```bash
bash pruefungen/barrierefreiheit-pruefen.sh <keksdatei>
bash pruefungen/oberflaeche-pruefen.sh <keksdatei>
```

---

## 13. Häufige Störungen

| Erscheinung | Ursache | Abhilfe |
|---|---|---|
| Anmeldung schlägt trotz richtigem Kennwort fehl | Uhr des Hosts oder des Telefons weicht ab | NTP prüfen; ein TOTP-Code gilt 30 s und **nur einmal** |
| „Dieses Einmalkennwort wurde bereits verwendet" | Code zweimal gesendet | nächsten Code abwarten, nicht denselben wiederholen |
| Konto gesperrt | fünf Fehlversuche | Sperre läuft ab; oder anderer Administrator setzt den zweiten Faktor zurück |
| Kennwörter lassen sich nicht speichern | `GEHEIMNIS_SCHLUESSEL` fehlt | Schlüssel setzen, Container neu starten |
| Agent startet, meldet aber nichts | Registrierungscode verbraucht oder abgelaufen | neuen Code erzeugen |
| Zentrale startet nicht, Exitcode 2 | `DB_PASSWORD` fehlt | `.env` prüfen |
| Geräte erscheinen nicht in der Geräteverwaltung | Agent meldet unter einer Einrichtung ohne Vorgabegruppe | seit 09.08.2026 behoben — die Vorgabegruppe entsteht beim ersten Bedarf |

---

## 14. Was diese Anleitung nicht abdeckt

- **Hochverfügbarkeit.** Es gibt keine Cluster-Fähigkeit; die Zentrale ist eine
  Instanz. Ausfallzeit bedeutet: keine Oberfläche, kein Protokolleingang. Die
  Kernfunktion des Agenten läuft davon unabhängig weiter.
- **Automatische Zertifikatsverwaltung** für den Reverse-Proxy.
- **Betrieb unter Kubernetes.** Möglich, aber nicht erprobt — die Aussagen
  dieser Anleitung gelten für Compose.
- **Lastverhalten am oberen Ende des Mengengerüsts** (5.000 Terminals) auf
  konkreter Kundenhardware. Gemessen wurde mit dem Lasterzeuger, nicht am realen
  Bestand.
