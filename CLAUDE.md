# CLAUDE.md — Projektvorgaben KT-PIN-Roboter

Diese Datei ist die verbindliche Arbeitsgrundlage für jede Claude-Code-Session in
diesem Projekt. Sie wird bei jeder Session automatisch geladen.

---

## 1. Projektkontext

| Punkt | Festlegung |
|---|---|
| Produkt | **PIN-Roboter** — Webapplikation |
| Auslieferung | Als **Docker-Container / Docker-Compose-Stack**, Installation beim Kunden (On-Premise) |
| Einsatzort | **Arztpraxen und Kliniken** |
| Nutzergruppe | Medizinisches und administratives Personal — **keine IT-Fachleute** |
| Betreiber-Umgebung | Praxis-/Klinik-IT, i. d. R. ohne dedizierte DevOps-Ressourcen |

**Konsequenz aus dem Einsatzort:** Die Software läuft in einer Umgebung, in der
Gesundheitsdaten verarbeitet werden können. Datenschutz (DSGVO), Nachvollziehbarkeit
und Betriebssicherheit sind keine Optionen, sondern Grundanforderungen — auch dann,
wenn eine konkrete Anforderung nicht ausdrücklich gestellt wurde.

---

## 2. Rollenverteilung

### Product Owner (Mathias Nieting)
- Gibt fachliche Vorgaben und Prioritäten vor.
- Nimmt Funktionen ab.
- **Ist kein Entwickler** → technische Entscheidungen werden nicht an ihn delegiert.

### Claude (Entwickler / Architekt)
Trägt die **volle Verantwortung** für:
- **Architektur** — Definition, Begründung, Dokumentation, Weiterentwicklung
- **Technische und technologische Qualität** — Code, Sicherheit, Performance, Wartbarkeit
- **Dokumentation** — Pflichtenhefte, Architektur-, Umsetzungs- und Betriebsdokumentation
- **UX/Usability** — modernes, barrierearmes, fehlertolerantes Interface

---

## 3. Verbindliche Arbeitsprinzipien

### 3.1 Fakten statt Vermutungen
- **Nicht raten.** Jede technische Aussage muss durch Quellcode, Konfiguration,
  Testlauf, Messung oder eine benannte, aktuelle Primärquelle (offizielle Doku,
  Spezifikation, RFC, Gesetzestext) belegt sein.
- Bei Unsicherheit gilt eine feste Reihenfolge:
  1. **Nachsehen** (Code lesen, ausführen, messen, Doku recherchieren)
  2. Wenn nicht ermittelbar: **Annahme explizit kennzeichnen** ("Annahme: …, zu
     verifizieren durch …") und trotzdem weiterarbeiten
  3. Nur wenn die Antwort die Arbeit fachlich unmöglich macht: **beim PO nachfragen**
- Versionen, Kompatibilitäten und Lizenzen werden **geprüft**, nicht erinnert.
- Statusaussagen sind ehrlich: "getestet", "implementiert, nicht getestet" und
  "geplant" werden nie vermischt. Fehlgeschlagene Tests werden mit Ausgabe berichtet.

### 3.2 Qualität ist nicht verhandelbar
- Kein Feature gilt als fertig ohne: Tests, Fehlerbehandlung, Dokumentation.
- Sicherheitsrelevante Entscheidungen werden dokumentiert und begründet.
- Keine ungeprüften Abhängigkeiten: jede neue Dependency braucht einen Grund,
  eine geprüfte Lizenz und eine Aussage zur Pflege (letztes Release, Maintainer).
- Technische Schulden werden nicht stillschweigend aufgenommen — sie werden im
  Architektur-Log als Eintrag festgehalten.

### 3.3 Projektsprache: Deutsch (verbindlich)
**Deutsch ist die Projektsprache.** Das gilt ausnahmslos für:
- alle Antworten und Erklärungen an den PO
- **alle sichtbaren Denkprozesse / Zwischenüberlegungen**
- Commit-Messages, Code-Kommentare, Dokumentation, Pflichtenhefte, ADRs
- Oberflächentexte, Fehlermeldungen, Log-Meldungen für Betreiber

Ausnahme nur dort, wo Technik es erzwingt: Bezeichner im Code, Schlüsselwörter,
etablierte Fachbegriffe ohne sinnvolle Übersetzung (z. B. "Container", "Commit",
"Health-Check"). Diese werden bei erster Verwendung kurz erklärt.

### 3.4 Kommunikation mit dem PO
- Sachlich, ohne unnötigen Fachjargon.
- Technische Entscheidungen werden **in Wirkung übersetzt**: was bedeutet das für
  Nutzer, Betrieb, Kosten, Risiko?
- Entscheidungen des POs werden umgesetzt. Fachliche Bedenken werden einmal klar
  benannt — danach wird geliefert.
- Nachfragen nur, wenn zwei plausible Lesarten zu **materiell unterschiedlicher
  Arbeit** führen. Routineentscheidungen trifft Claude selbst und nennt sie.

---

## 4. UX-Vorgaben (verbindlich)

Zielbild: Software, die in einer Praxis unter Zeitdruck, mit wechselndem Personal
und ohne Schulung bedienbar ist.

1. **Aufgabenorientiert** — Oberfläche folgt dem Arbeitsablauf, nicht der Datenbank.
2. **Fehlertolerant** — jede destruktive Aktion ist bestätigungspflichtig und/oder
   rückgängig machbar. Fehlermeldungen sagen, *was zu tun ist*, nicht nur was schiefging.
3. **Rückmeldung immer** — jeder Vorgang hat sichtbaren Zustand (lädt / fertig / Fehler).
4. **Barrierefreiheit: WCAG 2.2 Level AA** als Mindeststandard — Tastaturbedienbarkeit
   vollständig, Kontrast ≥ 4.5:1, sinnvolle Fokusreihenfolge, Screenreader-Labels.
5. **Responsiv** — Desktop-Arbeitsplatz ist Primärfall, Tablet muss funktionieren.
6. **Deutsch als Oberflächensprache**, medizinisch/organisatorisch korrekte Terminologie.
7. **Performance als UX-Merkmal** — spürbare Interaktion < 100 ms, Seitenaufbau < 1 s
   im LAN. Zielwerte werden gemessen, nicht geschätzt.
8. **Nachvollziehbarkeit** — der Roboter handelt selbsttätig; jede seiner
   Handlungen muss im Nachhinein erklärbar sein. Vier Sichten: Lage-Übersicht,
   Geräte-Zeitstrahl, Vorgangsansicht, filterbares Protokoll mit Excel-Export.
   Fehlermeldungen benennen **was zu tun ist**, nicht nur was schiefging;
   technische Codes wie `4078` werden übersetzt und bleiben nur aufklappbar
   sichtbar. → [ADR-0005](docs/adr/0005-protokollierung-und-nachvollziehbarkeit.md)

> **Protokoll-Grundregel:** Protokolliert werden **Zustandsänderungen und
> Handlungen, nicht Abfragen**. Bei 5.000 Terminals im 60-Sekunden-Takt entstehen
> sonst rund 7,2 Millionen Einträge pro Tag, und das Protokoll wird unbrauchbar.
> Getrennt geführt werden **Fachprotokoll** (nicht änderbar, in der Oberfläche
> sichtbar, exportierbar) und **technisches Protokoll** (Diagnose, rotierend).
> Niemals im Protokoll: PINs, Terminal-Kennwörter, Zugangsdaten, TOTP-Geheimnisse
> — zentral erzwungen, nicht durch Sorgfalt an jeder Einzelstelle.
>
> **Speicherbegrenzung:** Protokolldaten liegen in einem **eigenen Volume**,
> getrennt von den Betriebsdaten. Beim Setup wird je Datenart ein
> **Speicherbudget** eingestellt; ab **80 % Füllstand rotiert** das Protokoll,
> gestaffelt nach Wichtigkeit (technische Diagnose zuerst, sicherheitsrelevante
> Ereignisse zuletzt). Keine echte Partition — die ist auf Windows und macOS
> nicht durchsetzbar. **Ein volles Protokoll darf die Kernfunktion niemals
> anhalten**, muss aber deutlich sichtbar alarmieren.

---

## 5. Technische Leitplanken

- **Deployment:** reproduzierbar per `docker compose up`. Keine manuellen
  Nachinstallationsschritte beim Kunden.
- **Konfiguration:** ausschließlich über Environment-Variablen / gemountete
  Konfigurationsdateien. Keine Secrets im Image, keine Secrets im Repository.
- **Datenhaltung:** persistente Daten liegen in Volumes, nie im Container-Layer.
  Backup- und Restore-Weg ist dokumentiert und getestet.
- **Update-Pfad:** versionierte Images, Migrationen laufen automatisch und
  idempotent. Ein Downgrade-Szenario ist beschrieben.
- **Betrieb:** Health-Checks, strukturiertes Logging, kein Klartext von
  Patienten-/Personendaten in Logs.
- **Sicherheit:** Container laufen als Nicht-Root-User, minimale Basisimages,
  regelmäßiger Abhängigkeits-Scan, Authentifizierung und Rollen von Anfang an
  eingeplant, Transportverschlüsselung vorgesehen.
- **Reproduzierbarkeit:** Abhängigkeiten sind gepinnt (Lockfiles im Repo).

> **Technologie-Stack (Vorschlag, Freigabe ausstehend):** **Java (LTS)** mit
> **BouncyCastle** für Kryptographie und TLS, server-gerendertes HTML mit HTMX,
> PostgreSQL in der Zentrale, eingebettete Datenbank im Agenten.
> → [ADR-0006](docs/adr/0006-technologie-stack.md)
>
> Entscheidend war **Brainpool-TLS**: Die Geräteidentitäten von Konnektor und
> Kartenterminal stammen von Chipkarten und sind auf `brainpoolP256r1` festgelegt.
> Gemessen am 06.08.2026: Go scheitert bereits beim Einlesen solcher Zertifikate,
> das Standard-JDK an den Kurvengruppen — **Java mit BouncyCastle funktioniert**.
> Konnektor und HSK verwenden dieselbe Bibliothek.
> → [Messbericht](docs/00-referenzen/messung-brainpool-tls.md)
>
> **Verbindlich für die Umsetzung:** TLS auf **1.2** festlegen; Cipher-Suites
> ausdrücklich auf `(RSA, AES-CBC)` und `(ECC, AES-GCM)` einschränken
> (`[gemSpec_Krypt]`); `A_22457` beachten; Zertifikats- und Kurvenfehler
> unterscheidbar melden.

---

## 6. Dokumentationsstruktur

Dokumentation entsteht **mit** der Umsetzung, nicht danach. Ablage im Repository:

```
docs/
  README.md                  Einstieg: welches Dokument für wen
  00-referenzen/             Quellenverzeichnis, Fachanalysen, Messberichte
  01-anforderungskatalog.md  Funktionale und nichtfunktionale Anforderungen
                             mit testbaren Akzeptanzkriterien
  01a-umsetzungsstand.md     Was davon fertig ist — je Anforderung, mit Nachweis
  02-architektur.md          Systemüberblick nach arc42
  03-umsetzung/              Umsetzungsbeschreibungen je Feature
  04-betrieb.md              Installation und Betrieb für den Praxisbetreiber
  05-sicherheit-datenschutz.md  Schutzbedarf, TOMs, Datenflüsse personenbezogener Daten
  06-ux-konzept.md           Gestaltungskonzept der Oberfläche
  07-entwicklerhandbuch.md   Einstieg in den Code, Konventionen, Testen, Fallen
  08-anwenderhandbuch.md     Bedienung, ohne IT-Kenntnisse
  09-devops-installation.md  Netzschnitt, Secrets, Proxy, Härtung, Wiederherstellung
  10-codeanalyse.md          Gemessener Zustand, Refactoring, bewusst Offenes
  adr/                       Architecture Decision Records (nummeriert, unveränderlich)
```

**ADR-Regel:** Jede Architekturentscheidung wird als ADR festgehalten mit
Kontext, Optionen, Entscheidung, Konsequenzen. Einmal geschrieben werden ADRs
nicht überschrieben, sondern durch neue ADRs abgelöst (Status: `superseded by`).

**Pflichtenheft-Regel:** Jede Funktion braucht vor der Umsetzung überprüfbare
Abnahmekriterien. Ohne Abnahmekriterium keine Abnahme.

---

## 7. Definition of Done

Ein Feature ist fertig, wenn **alle** Punkte erfüllt sind:

- [ ] Abnahmekriterien aus dem Pflichtenheft erfüllt und nachweisbar
- [ ] Automatisierte Tests vorhanden und grün
- [ ] Fehlerfälle behandelt, Nutzer bekommt verständliche Rückmeldung
- [ ] UX-Vorgaben aus Abschnitt 4 eingehalten (inkl. Tastaturbedienung)
- [ ] Umsetzungsbeschreibung in `docs/03-umsetzung/` ergänzt
- [ ] Läuft im Docker-Stack aus dem Repository — verifiziert, nicht angenommen
- [ ] Keine Secrets, keine personenbezogenen Daten in Code, Logs oder Repo
- [ ] Dem PO in verständlicher Form zur Abnahme vorgelegt

---

## 8. Fachlicher Auftrag — geklärt am 06.08.2026

Der **PIN-Roboter überwacht eHealth-Kartenterminals und stellt den verifizierten
Zustand der eingelegten SMC-B automatisiert wieder her**, indem er die PIN über
die Managementschnittstelle des Terminals übergibt.

Das zugrunde liegende Verfahren ist von der gematik spezifiziert:
**PEviM — „PIN-Eingabe via Managementschnittstelle"**, `[gemSpec_KT] 2.4.9`.
Es ist ausdrücklich **nur für den Kartentyp SMC-B** zulässig (`A_24080`).

Vollständige Analyse mit allen Anforderungs-IDs:
[docs/00-referenzen/analyse-ti-kartenterminals.md](docs/00-referenzen/analyse-ti-kartenterminals.md)

Festlegungen daraus:
- **Keine Patienten-/Gesundheitsdaten** werden verarbeitet (Vorgabe PO). Der
  Schutzbedarf ergibt sich stattdessen aus der **Verarbeitung der SMC-B-PIN**.
- Anzubindende Fremdsysteme: **Kartenterminal**, **Konnektor**,
  **Highspeed-Konnektor (HSK)**.
- Kartentypen eGK und HBA sind **außerhalb des Scope** und technisch ausgeschlossen.

### Nutzer, Rollen und Anmeldung (PO, 06.08.2026)

**Größenordnungen — Richtwerte, keine hart durchgesetzten Grenzen:**

| Szenario | Administratoren | Benutzer |
|---|---|---|
| Einzelpraxis / kleine Einrichtung | ~4 | ~25 |
| HSK-Betrieb (Klinik, Rechenzentrum) | bis **150** | bis **1.000** |

Das Produkt setzt diese Zahlen **nicht technisch durch**. Es muss stattdessen so
gebaut sein, dass es bei 1.000 Benutzern nicht unbedienbar wird — das betrifft
Suche, Filter, Massenoperationen und serverseitige Paginierung in der
Benutzerverwaltung.

**Anmeldung:** Benutzername + Passwort + **TOTP** (zeitbasiertes Einmalkennwort).
Verpflichtend, nicht abschaltbar. Daraus folgt ein **Onboarding-Prozess**:
Ersteinrichtung des ersten Administrators, Einladung weiterer Konten,
TOTP-Einrichtung mit Verifikationsschritt, Wiederherstellung bei Verlust des
zweiten Faktors.

### Mandantenfähigkeit: Serviceorganisationen (PO, 06.08.2026)

Bei 1.000 Benutzern und 150 Administratoren ist eine flache Benutzerliste nicht
mehr handhabbar. Es wird eine **Zwischenschicht von Serviceorganisationen**
eingezogen — typischerweise IT-Dienstleister, die mehrere Einrichtungen betreuen.

Damit ist das Produkt **mandantenfähig**. Vorgesehene Struktur:

```
Instanz
 └── Serviceorganisation        (IT-Dienstleister)
      └── Einrichtung/Mandant   (Praxis, MVZ, Klinik, Standort)
           └── Konnektor / HSK
                └── Kartenterminal → SMC-B
```

Konsequenzen, die von Anfang an zu berücksichtigen sind:

- **Berechtigungen sind hierarchisch und mandantengebunden.** Jede Abfrage muss
  auf den Geltungsbereich des Aufrufers eingeschränkt sein. Mandantentrennung
  wird zentral erzwungen, nicht in jeder einzelnen Abfrage von Hand — sonst wird
  sie irgendwann vergessen. Das ist die häufigste schwere Sicherheitslücke in
  mandantenfähigen Anwendungen.
- **Nachrüsten ist unverhältnismäßig teuer.** Mandantenfähigkeit gehört ins
  Datenmodell der ersten Version, auch wenn die erste Installation sie nicht
  nutzt.
- **Abgrenzung zum Konnektor-Mandanten:** Der Konnektor kennt bereits eigene
  Mandanten (`MandantId`, `ClientSystemId`, `WorkplaceId` im Aufrufkontext, siehe
  Fachanalyse). Diese sind **nicht identisch** mit unseren Mandanten. Die
  Zuordnung ist explizit zu modellieren und darf nicht stillschweigend
  gleichgesetzt werden.
- **Onboarding wird mehrstufig:** Instanzbetreiber legt Serviceorganisation an,
  diese legt Einrichtungen an, diese Benutzer.

### Betriebsmodell (PO, 06.08.2026)

**Beide Betriebsarten mit derselben Software:**
- **verteilt** — eine Instanz je Einrichtung, im Praxis-/Kliniknetz
- **zentral** — eine Instanz je Serviceorganisation für viele Einrichtungen

Daraus folgt der Schnitt in zwei Bausteine — **Zentrale** und
**Standort-Agent** — mit ausgehendem Verbindungsaufbau durch den Agenten, damit
keine eingehenden Firewall-Freigaben im Kundennetz nötig sind. Begründung,
Ausfallverhalten und offene Fragen:
[ADR-0002](docs/adr/0002-betriebsmodell-und-systemtopologie.md).

### Betrieb und Auslieferung (PO, 06.08.2026)

- **Zielplattformen:** Docker unter **Linux, Windows und macOS**.
  → Konsequenz: keine Abhängigkeit von Linux-spezifischen Host-Eigenheiten;
  Pfade, Volumes und Netzwerkzugriff müssen auf allen drei Plattformen
  funktionieren. Das ist explizit zu testen, nicht anzunehmen.
- **Registry:** **GHCR** (GitHub Container Registry).
- **Update-Mechanismus:** **Watchtower** (automatische Container-Updates).
- **Multi-Arch:** Images für `linux/amd64` und `linux/arm64` (Apple Silicon).

> **Fachlicher Vorbehalt zu Watchtower — Entscheidung liegt beim PO.**
> Watchtower ist bequem, bringt in einer Praxis-/Klinikumgebung aber zwei Risiken:
> 1. **Unkontrollierte Updates:** Ein fehlerhaftes Image legt den TI-Zugang
>    ohne Vorwarnung lahm. Gegenmaßnahme: nur stabile, geprüfte Tags ausrollen,
>    kein `:latest`; Update-Zeitfenster außerhalb der Sprechzeiten.
> 2. **Rechteausweitung:** Watchtower benötigt Zugriff auf den Docker-Socket —
>    das entspricht Root-Rechten auf dem Host. Gegenmaßnahme: Socket-Proxy mit
>    eingeschränkten Rechten statt direkter Socket-Einbindung; Watchtower nur
>    auf die Container dieses Produkts beschränken.
>
> Diese Maßnahmen sind Bestandteil der Umsetzung, sofern der PO nicht widerspricht.

### Regulatorik (PO, 06.08.2026)

**Reine Organisationssoftware.** Keine Einstufung als Medizinprodukt, kein
MDR-Verfahren. Der Schutzbedarf ergibt sich ausschließlich aus der Verarbeitung
der SMC-B-PIN, nicht aus Patientendaten.

### Mengengerüst (PO, 06.08.2026)

Das Produkt muss **skalierbar** sein und dieselbe Codebasis von der Einzelpraxis
bis zum Klinikverbund tragen:

| | Minimum | Maximum |
|---|---|---|
| Konnektoren / HSK-Instanzen | **1** | **1.000 HSK** |
| Kartenterminals | **3** | **5.000** |

Einsatzszenarien: Einzelpraxis, MVZ, Klinik.

> **Architektonische Konsequenz — früh zu berücksichtigen, nicht nachträglich:**
> Zwischen Minimum und Maximum liegt der Faktor ~1.600. Daraus folgt zwingend:
> - **Nebenläufige, entkoppelte Überwachung** mit Warteschlange und
>   Lastbegrenzung statt einfacher Schleife. Bei 5.000 Terminals und einem
>   Prüfintervall von 60 s entstehen rund 83 Abfragen pro Sekunde — die
>   Antwortzeiten von Konnektor und Terminal sind dabei der Engpass, nicht unsere
>   Anwendung.
> - **Relationale Datenbank** mit ordentlicher Indizierung; Konfiguration in
>   Dateien scheidet aus.
> - **Prüfintervall pro Gerätegruppe konfigurierbar**, damit die Last steuerbar
>   bleibt.
> - Gleichzeitig muss der Stack in einer **Einzelpraxis auf schwacher Hardware**
>   sparsam laufen. Ressourcenbedarf ist zu **messen**, für beide Enden der
>   Skala.
> - Die Oberfläche muss 5.000 Geräte **bedienbar** darstellen: Suche, Filter,
>   Gruppierung nach Standort, serverseitige Paginierung. Eine flache Liste
>   funktioniert hier nicht.

### Testumgebung (PO, 06.08.2026)

**Steht vollständig bereit** (Konnektor/HSK, Kartenterminals, Test-SMC-B).

Damit gilt ohne Ausnahme: Jede Aussage über das Verhalten gegenüber Konnektor und
Kartenterminal wird **am Testaufbau verifiziert**, bevor sie als erledigt gemeldet
wird. Vermutungen über das Verhalten realer Geräte sind nicht mehr zulässig —
sie sind messbar.

**Erste Aufgabe am Testaufbau:** Bestandsaufnahme erstellen — Terminal-Modelle,
Firmware-Stände, PEviM-Unterstützung und -Aktivierbarkeit, VPN-Verfahren
(IPsec/WireGuard), Konnektor-/HSK-Modell und -Version. Diese Aufnahme ist die
Grundlage mehrerer offener Architekturfragen und kostet wenig Aufwand.

### Unterstützte Konnektoren (PO, 06.08.2026)

**Version 1 unterstützt ausschließlich KoCo Konnektor und KoCo HSK**
(KoCo Connector GmbH, Tochter der CGM Deutschland AG). Beide sind in den
relevanten Punkten identisch und gelten als **eine** Anbindung.

Zwei Schnittstellen sind dabei streng zu trennen:

| | Fachschnittstelle (Kartendienst) | Webadmin-Schnittstelle |
|---|---|---|
| Spezifiziert von | **gematik**, `[gemSpec_Kon] 4.1.5.5` | niemandem — herstellerspezifisch |
| Trägt | die **Kernfunktion** | Komfort: Informationsmodell auslesen, Terminals erkennen |
| Verfügbar | grundsätzlich herstellerübergreifend | nur KoCo |

> Die Kernfunktion wird **herstellerneutral** über die Fachschnittstelle gebaut,
> auch wenn Version 1 nur KoCo unterstützt. Das kostet jetzt nahezu nichts und
> erspart später einen Umbau. Funktionen, die auf der Webadmin-Schnittstelle
> beruhen, sind **immer optional** (NFA-QS-035) — sonst wäre das Produkt an einen
> Konnektorhersteller gekettet.

### Weitere Anbindungs- und Verwaltungsfunktionen

- **VPN-Anbindung externer Kartenterminals** — optionale Ausbaustufe, **nicht**
  erste Version. Primär gilt: Terminals sind direkt über IP erreichbar.
  Kartenterminals bringen laut PO in aktueller Firmware herstellerseitig einen
  VPN-Client mit (IPsec, teils WireGuard); die gematik spezifiziert das nicht.
  → [ADR-0003](docs/adr/0003-optionale-vpn-anbindung-externer-kartenterminals.md)
- **Firmware-Verwaltung der Kartenterminals** — beschlossen, gestuft umgesetzt.
  Der Hersteller liefert die Firmware an den Administrator, dieser lädt sie hoch;
  wir beziehen und verteilen keine Firmware selbst. Der Administrator legt
  **Rollout-Pläne je Gerätegruppe** an (z. B. Abteilung A: Mi und Fr,
  01:00–04:00); der Roboter arbeitet sie ab und setzt im nächsten freigegebenen
  Fenster fort. Notwendig für Kliniken und Apotheken im 24-Stunden-Betrieb.
  → [ADR-0004](docs/adr/0004-firmware-verwaltung-der-kartenterminals.md)

  **Gerätegruppen und Zeitfenster gehören ins Datenmodell der ersten Version**,
  auch wenn der Rollout selbst eine spätere Stufe ist.

  Zwei Regeln, die nicht verhandelbar sind:
  - **Ein begonnenes Geräteupdate wird zu Ende geführt**, auch wenn das Fenster
    ausläuft. Das Fenster begrenzt den *Start* weiterer Geräte. Ein
    abgebrochenes Firmware-Update ist der einzige Weg, ein Terminal doch zu
    beschädigen.
  - **Nach jedem Update:** Erreichbarkeit, Firmware-Stand und PIN-Status prüfen
    und die SMC-B wieder verifizieren. Der Neustart nach dem Update entzieht der
    Karte den verifizierten Zustand — das zu beheben ist der Kernzweck dieses
    Produkts.

> **Harte Anforderung an die Kernfunktion, aus `[gemSpec_Kon] 4.3.10` abgeleitet:**
> Der Konnektor aktualisiert Kartenterminals über seinen KSR-Client; der
> Kartenterminaldienst kennt dafür den Zustand **„aktualisierend"**
> (Ereignisse `KSR/UPDATE/START` … `KSR/UPDATE/END`, `Target=KT`).
> Der PIN-Roboter **muss** diesen Zustand erkennen und in dieser Zeit **keine
> PIN-Verifikation versuchen** — sonst belastet er im ungünstigen Fall den
> Fehlversuchszähler der SMC-B.

### Noch offen (PO)

1. **Firmware-Bezugsweg** für die spätere Ausbaustufe: Bestehen Herstellerkontakte,
   über die zugelassene Firmware legal bezogen und weitergegeben werden darf?
   Siehe ADR-0004.

### Sicherheitsleitplanke (nicht verhandelbar)

Der PIN-Roboter muss die SMC-B-PIN vorhalten. `A_24114` erlaubt das nur für
Clients, deren Sicherheit die betreibende Organisation durchsetzen kann. Daraus
folgen zwingend: keine Klartext-Persistenz, keine PIN in Logs/Backups/Metriken,
TLS mit geprüften Zertifikaten, Rollentrennung, Audit-Log, **Auswertung von
`LeftTries` vor jedem Versuch mit Abbruch statt Wiederholung** (sonst sperrt der
Roboter die Karte) und ein wirksamer Not-Aus.
