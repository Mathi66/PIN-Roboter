# Umsetzungsstand je Anforderung

**Stand:** 07.08.2026 · Grundlage: [Anforderungskatalog](01-anforderungskatalog.md)
mit 84 Anforderungen

> **Wozu dieses Dokument:** Es macht sichtbar, was belegt ist und was nicht —
> die Grundlage für die Abnahme. Nach CLAUDE.md gilt ein Akzeptanzkriterium erst
> als erfüllt, wenn es **am laufenden System nachgewiesen** wurde. „Implementiert"
> und „nachgewiesen" sind hier getrennte Spalten, weil sie verschiedene Dinge
> bedeuten.

## Legende

| Zeichen | Bedeutung |
|---|---|
| **✓** | umgesetzt **und** durch Systemnachweis am laufenden Stack belegt |
| **○** | umgesetzt, aber nur durch Modultests belegt — kein Systemnachweis |
| **◐** | teilweise umgesetzt; was fehlt, steht dabei |
| **–** | nicht begonnen |

> **Eine Einschränkung, die für alle ✓ gilt:** Die Nachweise laufen gegen den
> **Simulator**, nicht gegen echte Hardware. Die in
> [ADR-0007](adr/0007-simulierte-endpunkte-und-teststrategie.md) geforderte
> Kalibrierung steht aus. Für Anforderungen, die das Verhalten von Konnektor und
> Kartenterminal betreffen (FA-GER-030/035, FA-UEB-*, FA-PIN-*), ist das eine
> wesentliche Einschränkung — sie sind gegen unsere **Annahme** über die Geräte
> belegt, nicht gegen die Geräte.

---

## A.1 Geräteverwaltung (FA-GER)

| | Anforderung | Stand | Anmerkung |
|---|---|---|---|
| ✓ | FA-GER-010 | Konnektoren erfassen | Anlegen, Ändern, Löschen mit Löschschutz; Zugangsdaten verschlüsselt und nicht auslesbar; Erreichbarkeitsprüfung |
| ✓ | FA-GER-020 | Kartenterminals erfassen | dito; A_24080 durchgesetzt |
| ✓ | FA-GER-030 | Terminals automatisch ermitteln | Agent ermittelt, Übernahme einzeln oder als Auswahl, nie automatisch |
| ✓ | FA-GER-035 | Informationsmodell auslesen | Aufrufkontext steht zur Auswahl; ohne Webadmin bleibt alles von Hand erfassbar |
| ◐ | FA-GER-040 | Karten erfassen | Kartenhandle und ICCSN werden übernommen. **Offen:** Ablaufdatum des Zertifikats, ausdrückliche Kennzeichnung von eGK/HBA als „nicht verwaltet" |
| ○ | FA-GER-050 | Gerätegruppen | Anlegen, Zuordnen, Vorgabegruppe, Löschen mit Umzug — Modultests, kein eigener Systemnachweis |
| ✓ | FA-GER-060 | Bestandsübersicht bei 5.000 Geräten | Paginierung, Suche, Sortierung, Filter, Massenaktionen; gemessen: erste Seite 14 ms |

## A.2 Überwachung (FA-UEB)

| | Anforderung | Stand | Anmerkung |
|---|---|---|---|
| ✓ | FA-UEB-010 | PIN-Status überwachen | zyklisch über `GetPinStatus` |
| ○ | FA-UEB-020 | Erreichbarkeit überwachen | im Durchstich enthalten, kein eigener Nachweis |
| ○ | FA-UEB-030 | Firmware-Stand erfassen | wird gemeldet und angezeigt |
| ✓ | FA-UEB-040 | PEviM-Zustand erkennen | Terminals ohne aktives PEviM werden benannt, mit Handlungsempfehlung |
| ✓ | FA-UEB-050 | Kein Versuch während Firmware-Update | nachgewiesen: 0 Versuche bei 30 s Update |

## A.3 PIN-Verwaltung (FA-PIN)

| | Anforderung | Stand | Anmerkung |
|---|---|---|---|
| ◐ | FA-PIN-010 | PIN hinterlegen | Der Agent nimmt sie über die Umgebung entgegen. **Offen:** Hinterlegen über die Oberfläche mit verschlüsselter Ablage beim Agenten |
| ✓ | FA-PIN-020 | PIN automatisch verifizieren | vollständiger PEviM-Durchstich über Brainpool-TLS |
| ✓ | FA-PIN-030 | **Falsche PIN löst genau einen Versuch aus** | vor jeder Auslieferung verpflichtend; nachgewiesen: 1 Versuch bei ~10 Gelegenheiten |
| ○ | FA-PIN-040 | Fehlversuchszähler auswerten | Sicherungsprüfung mit 10 Modultests |
| ✓ | FA-PIN-050 | Not-Aus | nachgewiesen: 0 Versuche bei aktivem Not-Aus. **Einschränkung:** wirkt instanzweit, nicht je Einrichtung |
| – | FA-PIN-060 | PIN löschen | nicht begonnen |

## A.4 Firmware-Verwaltung (FA-FW)

| | Anforderung | Stand |
|---|---|---|
| – | FA-FW-010 bis FA-FW-090 | **Nicht begonnen.** Laut [ADR-0004](adr/0004-firmware-verwaltung-der-kartenterminals.md) eine spätere Stufe; Gerätegruppen und Zeitfenster stehen bereits im Datenmodell |

## A.5 Organisation (FA-ORG)

| | Anforderung | Stand | Anmerkung |
|---|---|---|---|
| ✓ | FA-ORG-010 | Mandanten/Einrichtungen | dreistufig, Trennung zentral erzwungen |
| ◐ | FA-ORG-020 | Serviceorganisationen | Datenmodell und Geltungsbereich stehen. **Offen:** Anlegen und Umhängen von Organisationseinheiten über die Oberfläche |

## A.6 Benutzer und Anmeldung (FA-BEN)

| | Anforderung | Stand | Anmerkung |
|---|---|---|---|
| ✓ | FA-BEN-010 | Konten verwalten | Einladung statt gesetztem Kennwort; letzter Administrator geschützt |
| ✓ | FA-BEN-020 | Anmeldung mit TOTP | verpflichtend; Einmalkennwort gilt einmal |
| ✓ | FA-BEN-030 | Zweiter Faktor zurücksetzbar | Administrator sieht das neue Geheimnis nicht |
| ✓ | FA-BEN-040 | Konten deaktivieren | Deaktivieren statt Löschen |
| ◐ | FA-BEN-050 | Rollen und Rechte | Rolle **und** Ebene; Mandantengrenze durchgesetzt. **Offen:** feinere Rechte je Funktion |

## A.7 Protokollierung (FA-PRO)

| | Anforderung | Stand | Anmerkung |
|---|---|---|---|
| ✓ | FA-PRO-010 | Unveränderliches Fachprotokoll | auf Datenbankebene erzwungen |
| ✓ | FA-PRO-020 | Keine Geheimnisse im Protokoll | nachgewiesen für PIN, Kennwörter, Einladungscodes |
| ✓ | FA-PRO-030 | Filterbare Ansicht | gemessen: 110 ms bei 5 Mio Einträgen |
| ✓ | FA-PRO-040 | Excel-Export | gegen `openpyxl` geprüft, mandantengebunden |
| ○ | FA-PRO-050 | Vier Sichten | Übersicht, Geräteliste, Protokoll, Alarme |
| ◐ | FA-PRO-060 | Vorgangsansicht | Vorgangsnummer wird geführt und angezeigt. **Offen:** eigene Ansicht je Vorgang |
| ✓ | FA-PRO-070 | Speicherbudget und Rotation | nachgewiesen: Kernfunktion läuft bei vollem Protokoll weiter |
| ✓ | FA-PRO-080 | Technisches Protokoll getrennt | rotierend, getrennt vom Fachprotokoll |

## A.8 Alarme (FA-ALA)

| | Anforderung | Stand |
|---|---|---|
| ✓ | FA-ALA-010 | Alarme entstehen und verschwinden selbsttätig, keine Alarmflut |
| ○ | FA-ALA-020 | Quittieren — Modultests |

## A.9 Updates (FA-UPD)

| | Anforderung | Stand |
|---|---|---|
| ◐ | FA-UPD-010 | Update-Pfad beschrieben ([Betrieb](04-betrieb.md)), Migrationen laufen automatisch und idempotent. **Offen:** Downgrade-Nachweis — dafür braucht es zwei veröffentlichte Abbildversionen |
| – | FA-UPD-020, FA-UPD-030 | nicht begonnen |

## A.10 Installation (FA-INS)

| | Anforderung | Stand | Anmerkung |
|---|---|---|---|
| ✓ | FA-INS-010 | Migrationen automatisch und idempotent | nachgewiesen |
| ✓ | FA-INS-020 | Ersteinrichtung | erster Administrator mit TOTP |
| ◐ | FA-INS-030 | Konfiguration über Umgebung | vollständig. **Offen:** eine `.env.example` als Vorlage |
| ○ | FA-INS-040 | Health-Checks | in der Compose-Datei |

## A.11 Standort-Agent (FA-AGT)

| | Anforderung | Stand |
|---|---|---|
| ✓ | FA-AGT-010 | Registrierung mit einmaligem Geheimnis |
| ✓ | FA-AGT-020 | Token-Authentisierung |
| ✓ | FA-AGT-030 | Agent arbeitet ohne Zentrale weiter |
| ○ | FA-AGT-040 | Agentenverwaltung in der Oberfläche |

## B. Nichtfunktionale Anforderungen

| | Anforderung | Stand | Anmerkung |
|---|---|---|---|
| ✓ | NFA-SIC-010 | Geheimnisse nicht im Klartext | PIN nie in der Zentrale; Geräte-Zugänge AES-GCM |
| ✓ | NFA-SIC-020 | Transportverschlüsselung | TLS 1.2 mit Brainpool, Suites nach `[gemSpec_Krypt]` |
| ✓ | NFA-SIC-030 | Rollen und Mandantentrennung | zentral erzwungen, 22 Tests gegen Angriffe |
| ◐ | NFA-SIC-040 | Härtung | Nicht-Root, gepinnte Abhängigkeiten, Scan-Skript. **Offen:** Scan im Bauvorgang verankern; kein TLS auf der eigenen Oberfläche |
| ✓ | NFA-SIC-050 | Sitzungen | Ablauf nach Untätigkeit, serverseitig ungültig nach Abmeldung |
| ◐ | NFA-DAT-010 | Datenschutz | Pseudonymisierung nach Frist. **Offen:** Löschbegehren vor Fristablauf |
| ✓ | NFA-DAT-020 | Keine Patientendaten | im Datenmodell durchgesetzt |
| ✓ | NFA-LEI-010 | Antwortzeiten | [gemessen](00-referenzen/messung-leistung-mengengeruest.md) bei 5.000 Geräten und 5 Mio Protokolleinträgen |
| – | NFA-LEI-020 | Überwachungsdurchsatz über 24 h | **nicht gemessen** — eine eigene Messung über einen ganzen Tag |
| – | NFA-LEI-030/035/037 | Ressourcenbedarf auf Referenzhardware | **nicht gemessen** — gemessen wurde auf einem M2 Pro, nicht auf Raspberry Pi 5 oder i.MX8 |
| ○ | NFA-LEI-040 | Nebenläufigkeit | im Agenten angelegt |
| – | NFA-VER-010/020/030 | Verfügbarkeit über längere Zeiträume | nicht gemessen |
| ✓ | NFA-USA-010 | Deutsche Oberfläche, Fachsprache | durchgehend |
| ✓ | NFA-USA-020 | Fehlermeldungen sagen, was zu tun ist | durchgehend, an vielen Stellen nachgewiesen |
| ◐ | NFA-USA-030 | WCAG 2.2 AA | Tastaturbedienung, Fokus, `aria-sort`, Beschriftungen, Kontraste. **Offen: kein Nachweis mit einem Prüfwerkzeug oder Screenreader** |
| ○ | NFA-USA-040 | Responsiv | Desktop und Tablet, nicht systematisch geprüft |
| ✓ | NFA-BET-010 | Konfiguration nur über Umgebung | fehlende Pflichtangabe bricht mit erklärender Meldung ab |
| ✓ | NFA-BET-020 | Persistenz, Sicherung, Wiederherstellung | Restore durchgeführt und als Nachweis automatisiert |
| ◐ | NFA-BET-030 | Zielplattformen | Linux/`arm64` läuft. **Windows und macOS nicht erprobt** |
| – | NFA-BET-040 | Multi-Arch-Abbilder, GHCR, Signatur, SBOM | nicht begonnen |
| ✓ | NFA-QS-010 | Automatisierte Tests | 197 Modultests |
| ✓ | NFA-QS-020 | Nachweise am Testaufbau | Systemnachweise gegen den laufenden Stack |
| ○ | NFA-QS-030 | Dokumentation mit der Umsetzung | 12 Umsetzungsbeschreibungen, 8 ADRs |
| ✓ | NFA-QS-035 | Herstellerspezifisches ist optional | Webadmin-Ausfall hält nichts an |
| – | NFA-QS-040 | Bauvorgang mit Signatur und SBOM | nicht begonnen |

---

## Zusammenfassung

| Stand | Anzahl |
|---|---:|
| **✓** belegt am laufenden System | 38 |
| **○** umgesetzt, nur Modultests | 14 |
| **◐** teilweise | 14 |
| **–** nicht begonnen | 18 |

**Die drei wichtigsten Lücken**, nach Gewicht:

1. **Kalibrierung an echter Hardware** (ADR-0007). Jedes ✓ bei den Geräte- und
   PIN-Anforderungen gilt gegen den Simulator — also gegen unsere Annahme über
   die Geräte, nicht gegen die Geräte. Der Testaufbau steht laut PO bereit.
2. **Ressourcenbedarf auf der Referenzhardware** (NFA-LEI-030). Gemessen wurde
   auf einem M2 Pro; die Zielplattform ist ein Raspberry Pi 5 oder i.MX8.
3. **WCAG 2.2 AA ohne Prüfwerkzeug.** Die Vorgaben wurden beim Bauen beachtet,
   aber nie mit einem Werkzeug oder Screenreader gegengeprüft. Damit ist die
   Anforderung nicht belegt, sondern behauptet.
