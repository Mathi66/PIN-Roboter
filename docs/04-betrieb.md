# Betrieb

Diese Anleitung richtet sich an die Person, die den PIN-Roboter in einer Praxis
oder Klinik installiert und betreibt. Sie setzt **keine Docker-Kenntnisse**
voraus, benennt aber, wo welche entstehen.

**Stand:** 07.08.2026. Alle Abläufe unten wurden durchgeführt, nicht behauptet —
wo etwas ungetestet ist, steht es dabei.

---

## 1. Was installiert wird

Drei Bausteine, alle als Container:

| Baustein | Aufgabe | Wo er läuft |
|---|---|---|
| **Datenbank** | Bestand, Benutzer, Protokoll | beim Betreiber |
| **Zentrale** | Oberfläche, Protokoll, Steuerung | beim Betreiber |
| **Standort-Agent** | spricht mit Konnektor und Terminals, kennt die PIN | im Netz der Einrichtung |

Bei einer Einzelpraxis laufen alle drei auf demselben Gerät. Bei einer
Serviceorganisation liegt die Zentrale zentral, je Einrichtung ein Agent.
Begründung: [ADR-0002](adr/0002-betriebsmodell-und-systemtopologie.md).

### Voraussetzungen

| | |
|---|---|
| Betriebssystem | Linux, Windows oder macOS |
| Software | Docker mit Compose (Docker Desktop unter Windows/macOS) |
| Referenzhardware | Linux-Appliance der Klasse Raspberry Pi 5 oder NXP i.MX8 QuadMax, 8 GB RAM ([ADR-0006](adr/0006-technologie-stack.md)) |
| Speicherplatz | mindestens 20 GB, davon der größte Teil für das Protokoll |
| Netz | Der Agent muss Konnektor und Kartenterminals erreichen. **Eingehende Freigaben sind nicht nötig** — der Agent baut ausgehend auf. |

---

## 2. Installation

```bash
git clone https://github.com/Mathi66/PIN-Roboter.git
cd PIN-Roboter
cp .env.example .env       # noch nicht vorhanden, siehe Abschnitt 3
docker compose up -d
```

Danach ist die Oberfläche unter `http://localhost:8090` erreichbar. Beim ersten
Aufruf führt sie durch die **Ersteinrichtung**: erstes Administratorkonto,
Kennwort, Einmalkennwort-Schlüssel für den zweiten Faktor.

> **Der Einmalkennwort-Schlüssel erscheint genau einmal.** Er wird als Text und
> als QR-Bild angezeigt. Wer ihn nicht notiert oder in die
> Authentifizierungs-App überträgt, kommt nicht mehr hinein — es gibt bewusst
> keine Hintertür.

Der Start dauert bis zur Bedienbarkeit unter einer Minute
(`NFA-LEI-030`). Die Datenbankmigrationen laufen automatisch und sind
idempotent — ein zweiter Start ändert nichts.

---

## 3. Konfiguration

**Ausschließlich über Umgebungsvariablen.** Keine Angabe steht im Abbild, keine
im Repository (`NFA-BET-010`). Vorgesehener Weg: eine Datei `.env` neben der
`docker-compose.yml`.

### Zentrale

| Variable | Pflicht | Bedeutung |
|---|---|---|
| `DB_PASSWORD` | **ja** | Kennwort der Datenbank. Fehlt sie, bricht der Start mit einer erklärenden Meldung ab — nicht mit einem Stapelabbild. |
| `GEHEIMNIS_SCHLUESSEL` | dringend empfohlen | Verschlüsselt die hinterlegten Geräte-Zugangsdaten. Siehe unten. |
| `PROTOKOLL_BUDGET` | nein | Speicherbudget des Protokolls, z. B. `2GB`. Vorgabe: 2 GB. |
| `PORT` | nein | Vorgabe 8090. |

### Standort-Agent

| Variable | Pflicht | Bedeutung |
|---|---|---|
| `REGISTRIERUNG` | beim ersten Start | Einmalig gültiges Geheimnis, in der Zentrale unter „Agenten" erzeugt. Danach kann der Eintrag entfallen. |
| `KONNEKTOR_URL` | ja | Adresse der Konnektor-Fachschnittstelle |
| `TERMINAL_URL` | ja | Adresse der Terminal-Managementschnittstelle |
| `TERMINAL_SMCB_URL` | nein | Wo die Remote SMCB API liegt — dort fragt das Terminal die PIN an. Ohne Angabe dieselbe Adresse wie `TERMINAL_URL`, und das ist am echten Gerät der Normalfall. |
| `WEBADMIN_URL` | nein | Webadmin-Schnittstelle des Konnektors. Herstellerspezifisch und **immer optional** (`NFA-QS-035`) — ohne sie bleibt alles von Hand erfassbar. |
| `PRUEFINTERVALL_SEKUNDEN` | nein | Vorgabe 10 s. Im Betrieb üblicherweise 60 s. |
| `PIN_ABLAGE_SCHLUESSEL` | dringend empfohlen | Verschlüsselt die hinterlegten SMC-B-PINs. Siehe unten. |
| `PIN_<kartenhandle>` | **nein — nur Testaufbau** | Setzt eine PIN direkt. **Für den Betrieb ungeeignet:** Die PIN steht damit im Klartext auf dem Host und in jeder Sicherung. Der Agent warnt beim Start. Im Betrieb über die Oberfläche hinterlegen. |

### Vor dem ersten Start: `.env` anlegen

Neben der `docker-compose.yml`:

```
DB_PASSWORD=<eigenes Kennwort>
GEHEIMNIS_SCHLUESSEL=<openssl rand -base64 32>
PIN_ABLAGE_SCHLUESSEL=<openssl rand -base64 32>
```

Die Datei ist per `.gitignore` vom Repository ausgeschlossen und gehört in die
Sicherung — aber **nicht in dieselbe Datei wie der Datenbankauszug**, sonst
schützt die Verschlüsselung vor nichts.

### Der Schlüssel für die PIN-Ablage des Agenten

Erzeugen:

```bash
openssl rand -base64 32
```

Das Ergebnis gehört in `.env` als `PIN_ABLAGE_SCHLUESSEL=…`.

**Wofür er ist:** Er verschlüsselt die hinterlegten SMC-B-PINs im Volume des
Agenten (AES-256-GCM). Er ist **bewusst ein anderer** als der
`GEHEIMNIS_SCHLUESSEL` der Zentrale — wer den einen erlangt, bekommt damit
nicht auch den anderen.

**Fehlt er,** bleiben hinterlegte PINs nur im Arbeitsspeicher und sind nach
einem Neustart des Agenten weg. Der Roboter arbeitet weiter, kann die
betroffenen Karten nach dem Neustart aber nicht mehr verifizieren, bis die PIN
erneut hinterlegt wird. Der Agent sagt das beim Start.

**Wird er getauscht,** sind die abgelegten PINs unlesbar. Der Agent meldet das
je Karte und arbeitet mit den übrigen weiter; die betroffenen PINs sind neu zu
hinterlegen.

### Der Schlüssel für Zugangsdaten

Erzeugen:

```bash
openssl rand -base64 32
```

Das Ergebnis gehört in `.env` als `GEHEIMNIS_SCHLUESSEL=…`.

**Wofür er ist:** Er verschlüsselt die Zugangsdaten von Konnektor und
Terminal-Administration (AES-256-GCM). Ohne ihn bleibt die Bestandserfassung
vollständig nutzbar — nur Kennwörter lassen sich nicht speichern, und die
Oberfläche sagt das mit einem Hinweis. Der Roboter kann sich dann nicht
selbsttätig anmelden.

**Was er nicht ist:** kein Schutz gegen jemanden mit vollem Zugriff auf den
Docker-Host. Begründung und Grenzen: [ADR-0008](adr/0008-ablage-von-geraete-zugangsdaten.md).

> **Drei Regeln, deren Verletzung teuer ist:**
>
> 1. **Der Schlüssel gehört in die Sicherung** — sonst sind nach einer
>    Wiederherstellung alle hinterlegten Kennwörter unbrauchbar und müssen von
>    Hand neu eingetragen werden.
> 2. **Aber nicht in dieselbe Datei wie der Datenbankauszug.** Liegen beide
>    zusammen, schützt die Verschlüsselung vor nichts.
> 3. **Ein geänderter Schlüssel macht die gespeicherten Kennwörter unlesbar.**
>    Die Anwendung meldet das verständlich und verlangt eine Neueingabe; sie
>    stürzt nicht ab.
>
> Die **SMC-B-PIN** ist hiervon nicht betroffen: Sie liegt ausschließlich beim
> Agenten und erreicht die Zentrale nie.

---

## 4. Sicherung und Wiederherstellung

> **Seit dem 10.08.2026 macht das Produkt das selbst.** Unter **Einstellungen →
> Datensicherung** laufen drei Pläne automatisch (täglich, wöchentlich,
> monatlich), Stände lassen sich verschlüsselt herunterladen und wieder
> einspielen. Sie müssen dafür nichts abtippen.
>
> Die Befehle unten bleiben für den Fall, dass die Oberfläche nicht erreichbar
> ist — etwa weil die Zentrale nicht startet.


**Was gesichert werden muss:**

| | Wo | Warum |
|---|---|---|
| Datenbank | Volume `betriebsdaten` | Bestand, Benutzer, Protokoll |
| `.env` mit `GEHEIMNIS_SCHLUESSEL` | neben der Compose-Datei | ohne ihn sind Kennwörter nach dem Restore unlesbar |
| Agent-Token | Volume `agentdaten` | sonst muss der Agent neu registriert werden |
| Schlüsselpaar des Agenten | Volume `agentdaten` | **bei Verlust ist jede hinterlegte PIN unbrauchbar** und muss neu eingegeben werden |
| `.env` mit `PIN_ABLAGE_SCHLUESSEL` | neben der Compose-Datei | ohne ihn sind die hinterlegten PINs nach dem Restore unlesbar |

### Sicherung ziehen

```bash
docker compose exec -T datenbank pg_dump -U pinroboter -d pinroboter -Fc > pinroboter-$(date +%F).dump
```

> **Das `>` am Ende ist wesentlich.** Bei der ersten Erprobung dieses Ablaufs
> wurde die Sicherung mit `-f /tmp/…` **im Container** abgelegt — und ging mit
> dem Container verloren, genau im Moment, in dem sie gebraucht wurde. Die
> Sicherung muss auf dem Host landen, und von dort auf ein anderes Medium.

### Wiederherstellung

```bash
docker compose up -d datenbank
docker compose cp pinroboter-2026-08-07.dump datenbank:/tmp/s.dump
docker compose exec datenbank pg_restore -U pinroboter -d pinroboter \
  --clean --if-exists /tmp/s.dump
docker compose restart zentrale
```

`--clean --if-exists` entfernt vorhandene Objekte vorher. Die Meldungen
`does not exist, skipping` beim Wiederherstellen in eine leere Datenbank sind
normal.

### Durchgeführt und belegt (07.08.2026)

Der Ablauf wurde vollständig erprobt: Volume gelöscht, aus der Sicherung
wiederhergestellt.

| | vorher | nachher | |
|---|---:|---:|---|
| Kartenterminals | 36 | 36 | ✓ |
| Protokolleinträge | 5 | 5 | ✓ |
| Benutzerkonten | 17 | 17 | ✓ |
| Tabellen | 15 | 15 | ✓ |
| Kennwort-Chiffrat | `v1:8zFuziQ6qha+yP8mrQRz1…` | identisch | ✓ |

Nach dem Neustart der Zentrale: Oberfläche erreichbar (HTTP 200), der Konnektor
wieder im Bestand, sein Kennwort als hinterlegt erkannt — also mit demselben
Schlüssel weiter entschlüsselbar.

**Was dabei noch nicht geprüft wurde:** die Wiederherstellung auf eine *andere*
PostgreSQL-Hauptversion, und der Fall, dass die Sicherung älter als eine
Migration ist (siehe Abschnitt 5).

---

## 5. Aktualisierung

**Versionierte Abbilder aus GHCR.** Kein `:latest` — ein Abbild, dessen Inhalt
sich unter demselben Namen ändert, macht jede Fehlersuche unmöglich.

```bash
docker compose pull
docker compose up -d
```

Die Migrationen laufen beim Start automatisch und idempotent. Sie sind
**vorwärtskompatibel gebaut**: Neue Spalten kommen hinzu, bestehende werden
nicht umgedeutet.

### Vor jeder Aktualisierung

1. **Sicherung ziehen** (Abschnitt 4). Nicht optional.
2. **Zeitfenster außerhalb der Sprechzeiten** wählen. Während des Neustarts
   verifiziert der Roboter nicht — eine SMC-B, die in diesem Moment ihren
   verifizierten Zustand verliert, bleibt bis zum nächsten Prüfzyklus offen.
3. **Nicht Zentrale und Agent gleichzeitig** aktualisieren, wenn es vermeidbar
   ist. Der Agent arbeitet ohne Zentrale weiter (`FA-AGT-030`) — umgekehrt gilt
   das nicht.

### Rückkehr zur vorherigen Version

```bash
docker compose down
# In der .env oder docker-compose.yml die vorherige Version eintragen
docker compose up -d
```

> **Ehrlich zum Downgrade:** Solange keine Migration dazwischenliegt, ist die
> Rückkehr unproblematisch — die älteren Abbilder finden ihr Schema vor.
> **Liegt eine Migration dazwischen, ist die Rückkehr nur über die Sicherung
> möglich**: Die alte Version kennt neue Spalten nicht, und die Migrationen
> haben absichtlich keinen Rückweg. Ein automatischer Rückbau wäre gefährlicher
> als sein Nutzen — er müsste Daten verwerfen, um das alte Schema herzustellen.
>
> **Deshalb: Sicherung vor der Aktualisierung.** Sie ist der Rückweg.
>
> Dieses Verhalten ist **nicht** durch einen Test belegt. Ein Nachweis, der eine
> Version zurückrollt, braucht zwei veröffentlichte Abbildversionen; die gibt es
> noch nicht. Der Punkt bleibt offen.

### Zu Watchtower

CLAUDE.md nennt Watchtower als Update-Mechanismus, mit zwei Vorbehalten. Beide
sind hier festzuhalten, weil sie den Betrieb betreffen:

- **Unkontrollierte Updates:** Ein fehlerhaftes Abbild legt den TI-Zugang ohne
  Vorwarnung lahm. Gegenmaßnahme: nur geprüfte Versionsmarken ausrollen, kein
  `:latest`, Zeitfenster außerhalb der Sprechzeiten.
- **Rechteausweitung:** Watchtower braucht den Docker-Socket — das entspricht
  Root-Rechten auf dem Host. Gegenmaßnahme: Socket-Proxy mit eingeschränkten
  Rechten, und Watchtower nur auf die Container dieses Produkts beschränken.

**Watchtower ist derzeit nicht Bestandteil der Compose-Datei.** Wer ihn
einsetzt, sollte beide Gegenmaßnahmen mit einrichten.

---

## 6. Laufender Betrieb

### Woran man sieht, dass es läuft

| | |
|---|---|
| Oberfläche | Übersicht zeigt „Agent verbunden" |
| Protokoll | Einträge kommen an; Zustandsänderungen, keine Abfragen |
| Health-Check | `docker compose ps` zeigt alle Container als `healthy` |

### Wenn etwas nicht stimmt

Die Oberfläche ist die erste Stelle, nicht das Protokoll der Container. Sie
übersetzt technische Meldungen und sagt, **was zu tun ist**. Erst wenn sie nicht
erreichbar ist, hilft:

```bash
docker compose logs zentrale --tail 50
docker compose logs agent --tail 50
```

### Speicher

Das Protokoll ist der einzige unbegrenzt wachsende Teil. Ab **80 % des Budgets**
rotiert es gestaffelt: technische Diagnose zuerst, sicherheitsrelevante
Ereignisse zuletzt. Ab 90 % erscheint ein Alarm in der Oberfläche.

**Ein volles Protokoll hält die Kernfunktion nicht an** (`FA-PRO-070`, am
laufenden System nachgewiesen). Es alarmiert aber deutlich.

### Not-Aus

In der Kopfzeile jeder Seite. Er hält die PIN-Automatik **sofort** an, ohne
einen laufenden Vorgang abzuschneiden. Zu verwenden, wenn unklar ist, ob der
Roboter richtig handelt — er ist reversibel und ausdrücklich dafür gedacht.

---

### Wenn der Agent nicht schreiben kann

Meldet der Agent beim Start

> WARNUNG: Der private Schlüssel konnte nicht gespeichert werden

dann gehört sein Datenverzeichnis nicht ihm. Der Container läuft als
Nicht-Root (`NFA-SIC-030`); ein Volume, das root gehört, ist für ihn nicht
beschreibbar. Prüfen:

```bash
docker compose exec agent ls -ld /daten
```

Erwartet wird `roboter roboter`. Steht dort `root`, ist das Volume mit einem
älteren Image angelegt worden — dann `docker compose down`, das Volume
`agentdaten` entfernen und neu starten. Der Agent registriert sich danach neu.

**Der Agent läuft in diesem Zustand weiter** — die Überwachung der SMC-B hängt
nicht an dieser Datei. Verloren gehen nur die über die Oberfläche hinterlegten
PINs bei jedem Neustart.

## 7. Was diese Anleitung nicht abdeckt

Ehrlich benannt:

1. **Betrieb hinter einem Vermittler (Reverse Proxy) mit TLS.** Die Zentrale
   spricht derzeit HTTP; für den Zugriff über ein Netzsegment hinaus gehört ein
   TLS-Vermittler davor. Nicht beschrieben, nicht getestet.
2. **Windows und macOS.** Die Zielplattformen sind benannt (CLAUDE.md), die
   Installation wurde dort **nicht** erprobt. Pfade und Volume-Verhalten
   unterscheiden sich.
3. **`arm64`.** Die Referenzhardware ist `arm64`; gemessen und getestet wurde
   bisher auf `arm64` (Apple Silicon) unter Docker Desktop, nicht auf einer
   Linux-Appliance.
4. **Mehrere Agenten an einer Zentrale.** Technisch vorgesehen, in der
   Oberfläche noch nicht je Standort gruppiert.
5. **Downgrade über eine Migration hinweg** (Abschnitt 5).
