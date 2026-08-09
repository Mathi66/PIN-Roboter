# Entwicklerhandbuch

**Zielgruppe:** Wer an diesem Quelltext arbeitet — heute oder in zwei Jahren.

**Stand:** 10.08.2026. Zahlen und Strukturen sind gemessen, nicht erinnert.

---

## 1. In zehn Minuten lauffähig

```bash
git clone <repository> && cd KT-PIN-Roboter
openssl rand -base64 32 > /tmp/schluessel
printf 'DB_PASSWORD=%s\nGEHEIMNIS_SCHLUESSEL=%s\n' \
    "$(openssl rand -base64 24)" "$(cat /tmp/schluessel)" > .env
docker compose up -d --build
```

Oberfläche: http://localhost:8090 — beim ersten Start führt sie durch die
Ersteinrichtung des ersten Administrators.

Bauen ohne Docker-Neubau des Stacks:

```bash
docker run --rm -v "$PWD":/p -v pinroboter-m2:/root/.m2 -w /p \
  maven:3.9-eclipse-temurin-21 mvn -B -q -DskipTests package
```

Das gemountete Maven-Volume `pinroboter-m2` ist der Grund, warum der zweite
Bau eine Minute statt fünf braucht.

---

## 2. Aufbau des Repositories

```
src/
  gemeinsam/    von Zentrale und Agent geteilt: Totp, Ereignisart, PinStatus,
                Ampel, Geheimnisspeicher, Krypto
  zentrale/     Oberfläche, Datenhaltung, Protokoll, Steuerung
  agent/        Standort-Agent: spricht mit Konnektor und Kartenterminals
  simulator/    Nachbildung von Konnektor, Kartenterminal, Registry und
                Mailauffang — nur für Test und Abnahme
pruefungen/     Systemnachweise (Shell), Barrierefreiheit, Oberfläche
docs/           siehe docs/README-Struktur in CLAUDE.md Abschnitt 6
```

Maven-Multimodul, Java 21. Der Agent hängt von `gemeinsam` ab, die Zentrale
ebenso; **Zentrale und Agent kennen einander nicht** außer über die
HTTP-Schnittstelle.

### Kennzahlen (10.08.2026)

| | |
|---|---|
| Java-Dateien (Hauptcode) | 62 |
| Zeilen Hauptcode | ~18.000 |
| Testklassen | 26 |
| Modultests | 264 |
| Systemnachweise | 329 |
| Datenbankmigrationen | 21 |

---

## 3. Die Zentrale

### 3.1 Schichten

```
Zentrale.java        Routen, Zustand, Hintergrundaufgaben
    │
    ├── *seiten.java     HTML-Erzeugung (Anmeldeseiten, Bestandsseiten,
    │                    Benutzerseiten, Organisationsseiten, Verlaufsseiten,
    │                    Benachrichtigungsseiten)
    ├── Bedienelemente   gemeinsame Bausteine: Knöpfe, Sortierköpfe, Suchleiste
    │
    ├── *verwaltung.java Fachlogik + SQL (Bestands-, Benutzer-, Gruppen-,
    │                    Organisations-, Agenten-, Alarmverwaltung, Pinverwaltung)
    │
    └── Datenbank.java   Verbindung und Migrationen
```

Es gibt **keine ORM-Schicht**. SQL steht in den `*verwaltung`-Klassen, immer als
`PreparedStatement`. Das ist Absicht: Bei 21 Tabellen und überschaubaren
Abfragen kostet ein ORM mehr Verständnis, als es spart — und es verdeckt genau
die Stelle, an der die Mandantentrennung greifen muss.

### 3.2 Routen

`starten()` bündelt 81 Routen in zehn thematische Methoden:

| Methode | Bereich |
|---|---|
| `routenAnmeldung` | Anmelden, Ersteinrichtung, zweiter Faktor, Einladungen |
| `routenUebersicht` | Lage, Geräte, Alarme, Not-Aus, HTMX-Einschübe |
| `routenProtokoll` | Fachprotokoll, Excel-Export |
| `routenGruppen` | Gerätegruppen, Prüfintervall |
| `routenBestand` | Konnektoren, Terminals, Zugangsdaten, PIN |
| `routenOrganisation` | Mandantenstruktur |
| `routenBenutzer` | Konten, Rollen, Sperren |
| `routenBenachrichtigung` | Mailserver, Regeln, Testversand |
| `routenVerwaltung` | Demodaten, Lasttest, Registry, Agenten-Registrierung |
| `routenAgentSchnittstelle` | die JSON-Schnittstelle zum Agenten |

> **Die Reihenfolge innerhalb eines Bündels ist bedeutsam.** Javalin nimmt die
> zuerst passende Route: `/bestand/terminal/neu` muss vor
> `/bestand/terminal/{id}` stehen, sonst wird „neu" als Kennung gelesen. Die
> Reihenfolge der Bündel untereinander ist frei, weil sich die Präfixe nicht
> überschneiden.

Bis zum 09.08.2026 standen alle 81 Routen in **einer** Methode mit 1450 Zeilen.

### 3.3 Zustand im Speicher

`Zentrale` hält eine `Map<String, Geraetezustand> geraete` — den zuletzt
gemeldeten Zustand jedes Terminals. Das ist **kein Cache der Datenbank**,
sondern der einzige Ort dieser Information: Zustände werden nicht persistiert,
nur Zustands*änderungen* landen im Fachprotokoll.

Grund: Bei 5.000 Terminals im 60-Sekunden-Takt entstünden sonst rund 7,2
Millionen Zeilen pro Tag ([ADR-0005](adr/0005-protokollierung-und-nachvollziehbarkeit.md)).
Nach einem Neustart der Zentrale ist die Karte leer und füllt sich binnen eines
Prüfintervalls wieder.

---

## 4. Die Regeln, die dieser Code erzwingt

Drei Dinge werden **nicht durch Sorgfalt**, sondern durch den Compiler oder
zentrale Stellen sichergestellt. Wer hier etwas ändert, sollte wissen, warum:

### 4.1 Mandantentrennung: `Geltungsbereich`

Jede Abfrage, die Daten liefert, nimmt einen `Geltungsbereich` entgegen —
**als Pflichtparameter**, nicht als Option.

```java
public List<Gruppe> alle(Geltungsbereich bereich) { … }
```

Der Grund steht in CLAUDE.md: Mandantentrennung, die in jeder Abfrage von Hand
gemacht wird, wird irgendwann vergessen; das ist die häufigste schwere
Sicherheitslücke in mandantenfähigen Anwendungen. Wer eine neue Abfrage
schreibt, kann den Parameter nicht weglassen, ohne dass es auffällt.

`Geltungsbereich.bedingung("spalte")` liefert das SQL-Fragment. Es steht in
jeder Abfrage **an derselben Stelle** — direkt hinter `WHERE 1=1` —, damit es
bei einer Durchsicht sofort auffällt, wenn es fehlt.

### 4.2 Einrichtung: Pflichtparameter in `pruefe()`

Alarme und Protokolleinträge tragen die Einrichtung. Sie stammt **aus der
Registrierung des Agenten, nicht aus seiner Meldung** — sonst könnte ein
übernommener Agent Geräte in einer fremden Einrichtung unterschieben.

`pruefe()`, der gemeinsame Weg aller Gerätealarme, hat sie als
Pflichtparameter, damit ein vergessener Aufruf nicht übersetzt.

### 4.3 Kurzhinweise: Pflichtparameter in `Bedienelemente`

Ein Knopf ohne Erklärung lässt sich mit `Bedienelemente.absenden(...)` nicht
bauen. Die Klasse weist außerdem Echos ab (`title="Löschen"` auf einem Knopf
„Löschen"). Vorher fehlten 142 von 154 Kurzhinweisen — nicht aus
Nachlässigkeit, sondern weil jede Seite ihre Knöpfe selbst baute.

### 4.4 Was niemals ins Protokoll darf

PINs, Terminal-Kennwörter, Zugangsdaten, TOTP-Geheimnisse. Das wird **zentral**
erzwungen (`Fachprotokoll`), nicht an jeder Einzelstelle geprüft. Ein
Systemnachweis sucht in Protokoll und Containerlog nach unverwechselbaren
Testkennwörtern.

---

## 5. Kryptographie

| Zweck | Verfahren | Wo |
|---|---|---|
| PIN von der Oberfläche zum Agenten | RSA-OAEP 3072, SHA-256 für Hash **und** MGF1 | ADR-0009 |
| PIN-Ablage im Agenten | AES-256-GCM | ADR-0009 |
| Zugangsdaten, Mailkennwort | AES-256-GCM | ADR-0008 |
| Passwörter | Argon2id | `Passwort.java` |
| TLS zum Konnektor | TLS 1.2, `brainpoolP256r1`, BouncyCastle | ADR-0006 |

> **Eine Falle, die uns getroffen hat:** Javas Kurzform
> `OAEPWithSHA-256AndMGF1Padding` setzt **nur den Nachrichten-Hash** auf
> SHA-256; MGF1 bleibt auf SHA-1. Der Modultest war auf beiden Seiten gleich
> falsch und blieb grün. Gefunden hat es erst der Systemnachweis gegen OpenSSL.
> Deshalb steht überall ein **expliziter** `OAEPParameterSpec`.

Lehre daraus, die über die Krypto hinausgeht: Ein Test, der beide Seiten selbst
implementiert, prüft die Übereinkunft mit sich selbst. Gegen etwas Fremdes
prüfen, wo es geht.

---

## 6. Die Oberfläche

**Server-gerendertes HTML mit HTMX.** Kein React, kein Bauschritt für das
Frontend, keine JavaScript-Abhängigkeiten außer HTMX (mitgeliefert, nicht aus
einem CDN).

### Regeln

1. **Abstände kommen aus dem Raster** `--r1` bis `--r6` (4/8/12/16/24/32 px).
   Keine erfundenen Werte, keine Abstände in `style`-Attributen.
2. **Jedes Bedienelement trägt einen Kurzhinweis**, der die Wirkung nennt statt
   die Beschriftung zu wiederholen.
3. **Listen mit Detailseite öffnen per Zeile.** Der Name in der ersten Zelle ist
   ein echter `<a class="zeilenziel">` — nur so bleibt genau ein Ziel für
   Tastatur und Screenreader. Der Zeilenklick in `oberflaeche.js` ist reine
   Mausbequemlichkeit; ohne JavaScript funktioniert die Liste unverändert.
4. **Kein `display:flex` auf `<td>`.** Es nimmt die Zelle aus dem
   Tabellenlayout: Die Trennlinie bricht ab, der Inhalt rutscht neben die
   Tabelle. Ausrichten über `text-align` und `inline-flex` der Kinder.
5. **Destruktives fragt nach** — `data-nachfrage` am Formular oder `hx-confirm`.
6. **Sortierung serverseitig, wo geblättert wird.** Eine Sortierung im Browser
   über 25 sichtbare von 5.000 Zeilen ist eine Lüge.

Alle sechs Regeln prüft `pruefungen/oberflaeche-pruefen.sh` maschinell.

### Selbstaktualisierung

`hx-trigger="every Ns"` nur dort, wo sich etwas **von selbst** ändert: Lage,
Alarme, Protokoll. **Nicht** in Verwaltungslisten — eine Liste, die sich unter
der Hand austauscht, verliert Tastaturfokus und halb getippte Eingaben.

---

## 7. Datenhaltung

PostgreSQL 17. Schema und Migrationen stehen **im Java-Code**
(`Datenbank.java`, Methode `migration(nr, sql)`), nicht in SQL-Dateien.

Warum: Die Migration läuft beim Start der Zentrale, idempotent und in
Reihenfolge. Ein separates Werkzeug (Flyway, Liquibase) wäre eine weitere
Abhängigkeit für eine Aufgabe, die 40 Zeilen braucht.

**Eine Migration wird nie geändert, nur ergänzt.** Nummer 6 sieht heute so aus
wie am ersten Tag; wer das Schema ändert, schreibt Nummer 22.

### Zwei Volumes

`betriebsdaten` und Protokolldaten sind getrennt (ADR-0005): unterschiedliches
Wachstum, unterschiedliche Aufbewahrung, unterschiedliches Ausfallrisiko. **Ein
volles Protokoll darf die Kernfunktion nie anhalten**, muss aber sichtbar
alarmieren — das erledigt `Speicherwaechter`.

---

## 8. Testen

### Modultests

```bash
docker run --rm --network kt-pin-roboter_default \
  -v "$PWD":/p -v pinroboter-m2:/root/.m2 -w /p \
  -e TEST_DB_URL=jdbc:postgresql://datenbank:5432/pinroboter \
  -e TEST_DB_USER=pinroboter -e TEST_DB_PASSWORD="$DB_PASSWORD" \
  maven:3.9-eclipse-temurin-21 mvn -B test
```

Tests, die eine Datenbank brauchen, tragen
`@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", …)` und laufen ohne sie
nicht — sie schlagen dann nicht fehl, sie laufen gar nicht erst.

### Systemnachweise

```bash
bash pruefungen/systemnachweise.sh
```

Startet Simulator, Zentrale, Agent und Datenbank in **eigenen Containern auf
eigenen Ports** (8190, 8580) und prüft gegen den laufenden Stack. Eine parallel
laufende Entwicklungsinstanz bleibt unberührt.

> Bis zum 09.08.2026 nutzte er dieselben Ports wie `docker-compose.yml`. Der
> Stack musste also vorher weichen — mitsamt Datenvolumen und
> Administratorkonto. Das hat den PO dreimal ausgesperrt. **Wer hier Ports
> ändert, bedenkt diesen Fall.**

### Was ein Nachweis leisten muss

Aus wiederholter Erfahrung in diesem Projekt: Etwa die Hälfte der roten
Nachweise lag am Nachweis, nicht am Produkt. Die drei häufigsten Fehler:

| Fehler | Beispiel aus diesem Projekt |
|---|---|
| **Unspezifisch messen** | `grep -c 'class="eintrag'` traf auch `eintrag-streifen`; „Knöpfe zählen" statt „Zustand prüfen" |
| **Prüfmenge aus dem Prüfgegenstand ziehen** | Der Navigationsnachweis holte seine Liste aus der Startseite — als die kaputt war, meldete er „0 tote Verweise: bestanden" |
| **Annahmen, die nur zufällig gelten** | `SELECT id FROM geraetegruppe WHERE ist_vorgabe` nahm an, es gebe genau eine. Bei Mandantenfähigkeit gibt es eine je Einrichtung. |

Und: `grep -c` zählt **Zeilen**, nicht Treffer. Ein StringBuilder schreibt eine
ganze Tabelle in eine Zeile — dann meldet der Nachweis „1", wo 13 stehen.

### Barrierefreiheit

```bash
bash pruefungen/barrierefreiheit-pruefen.sh <keksdatei>
```

axe-core über 17 Seiten plus rechnerische Kontrastprüfung. Was es **nicht**
abdeckt: Tastaturbedienung im Ablauf, Screenreader-Verständlichkeit, Zustände
nach einer Nutzerhandlung. Ein Werkzeug findet einen Teil der Barrieren, diesen
aber zuverlässig.

---

## 9. Der Simulator

Nachbildungen von Konnektor, Kartenterminal, OCI-Registry und SMTP-Auffang.

**Warum eigene Nachbildungen statt fertiger Werkzeuge:** MailHog als
Auffangserver ließ den Nachweislauf beim Ziehen des Images hängen. Ein Prüflauf,
der Internetzugang braucht, ist als Nachweis unbrauchbar — er wird rot, wenn das
Netz klemmt, und sagt dann nichts über das Produkt.

Der Simulator kennt bewusst auch **Fehlerzustände**: gesperrte Karte,
abgelaufenes Registry-Token, laufendes KSR-Update. Ein Simulator, der nur den
guten Fall kann, prüft die Hälfte.

---

## 10. Konventionen

**Sprache:** Deutsch — Kommentare, Dokumentation, Commit-Messages,
Oberflächentexte, Log-Meldungen. Bezeichner im Code ebenfalls deutsch
(`gruppenTabelle`, `einrichtungDesAgenten`), außer bei etablierten Fachbegriffen.

**Kommentare erklären das Warum, nicht das Was.** Ein Kommentar, der die
Codezeile wiederholt, wird gelöscht. Ein Kommentar, der erklärt, warum eine
naheliegende Lösung nicht funktioniert, bleibt für immer.

**Commit-Messages** nennen die Wirkung, nicht die Dateien: was war falsch, was
ist jetzt anders, warum so.

**Definition of Done** steht in CLAUDE.md Abschnitt 7. Kurzfassung: Tests grün,
Fehlerfälle behandelt, Umsetzungsbeschreibung ergänzt, im Docker-Stack aus dem
Repository verifiziert — nicht angenommen.

---

## 11. Fallen, die schon zugeschnappt sind

Eine unvollständige Liste aus der Projektgeschichte. Jede hat Stunden gekostet:

| Falle | Was passierte |
|---|---|
| `curl \| grep -q` mit `set -o pipefail` | grep beendet früh, curl bekommt SIGPIPE, die Pipeline gilt als fehlgeschlagen. 25 Nachweise waren betroffen. Abhilfe: `grep -c`. |
| `catch (SQLException ignored) {}` | Verdeckte ein `WHERE 1=1AND` aus einem Java-Textblock ohne Trennzeichen. Acht Blöcke melden jetzt. |
| `/daten` gehörte Root | Container läuft als Nicht-Root, konnte Token und Schlüssel nicht schreiben. Der Agent starb daran — jetzt Warnung statt Abbruch. |
| Textersetzung traf zwei Tabellen | `MissingFormatArgumentException`, Startseite HTTP 500 — und der Navigationsnachweis verdeckte es. |
| Test-PIN `123456` in einer Demo-ICCSN | Falscher Positivbefund im Geheimnisnachweis. Seitdem unverwechselbare Testkennwörter. |
| `docker compose down -v` vor dem Nachweislauf | Löschte das Administratorkonto samt TOTP-Geheimnis. Behoben durch eigene Ports und FA-BEN-035. |

---

## 12. Wo was steht

| Frage | Datei |
|---|---|
| Was soll das Produkt können? | `docs/01-anforderungskatalog.md` |
| Was ist davon fertig? | `docs/01a-umsetzungsstand.md` |
| Wie ist es gebaut? | `docs/02-architektur.md` |
| Warum so und nicht anders? | `docs/adr/` |
| Wie wurde ein Feature umgesetzt? | `docs/03-umsetzung/` |
| Wie betreibt man es? | `docs/04-betrieb.md`, `docs/09-devops-installation.md` |
| Wie bedient man es? | `docs/08-anwenderhandbuch.md` |
| Was ist der Schutzbedarf? | `docs/05-sicherheit-datenschutz.md` |
| Wie sieht die Oberfläche aus und warum? | `docs/06-ux-konzept.md` |
| Was ist wann passiert? | `FORTSCHRITT.md` |
