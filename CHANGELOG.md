# Änderungshinweise

Was sich je Version geändert hat — und was ein Betreiber beim Aktualisieren
beachten muss.

> **Vor jeder Aktualisierung eine Sicherung ziehen.** Migrationen laufen
> automatisch und lassen sich nicht zurücknehmen. Seit 0.2.0 geht das über
> **Einstellungen → Datensicherung**.

---

## 0.2.0 — 10.08.2026

Die erste Version mit eingebauter Datensicherung. Schwerpunkt daneben:
Mandantenfähigkeit bis zum Gerät, Bedienbarkeit, Wiedereinstieg nach
Datenverlust.

### Neu

**Datensicherung** (FA-BAK-010 bis 050,
[ADR-0010](docs/adr/0010-datensicherung-und-wiederherstellung.md))
Drei rollierende Pläne — täglich 7, wöchentlich 5, monatlich 12 Stände. Download
und Upload verschlüsselt mit AES-256-GCM; der Schlüssel wird eingegeben und
nirgends gespeichert. Wiederherstellung mit Sicherheitskopie davor und Neustart
der Zentrale.

**Administratorkonto aus der Konfiguration** (FA-BEN-035)
Der Zugang überlebt einen vollständigen Datenverlust: Konto und TOTP-Geheimnis
entstehen beim Start neu, der Eintrag in der Authenticator-App bleibt gültig.

**Menüpunkt „Einstellungen"**
Datensicherung und die Rubrik „Systemtest" (Demodatensatz, Leistungsmessung).
Letztere sind aus „Instanz" hierher umgezogen.

**PIN ändern über die Oberfläche** (FA-PIN-060, `A_24082`)
Weder alte noch neue PIN erreichen die Zentrale im Klartext.

**Geräte-Zeitstrahl und Vorgangsansicht** (FA-PRO-050/060)
Damit sind alle vier Sichten aus ADR-0005 vorhanden.

**Neue Versionen erkennen** (FA-UPD-010)
Die Zentrale fragt die Registry ab und zeigt verfügbare Stände samt
Änderungshinweisen. Die geführte Ausführung (FA-UPD-020) fehlt weiterhin.

**Benachrichtigung per E-Mail** (FA-ALA-020)
Mit Sammelfenster gegen Mailfluten: Fällt ein Konnektor mit 200 Terminals aus,
kommt eine Nachricht, nicht 200.

**Prüfintervall je Gerätegruppe** (FA-UEB-010) — wirkt nachweislich im Agenten.

**KSR-Zustand „aktualisierend"** (`[gemSpec_Kon] 4.3.10`)
Während ein Konnektor Terminals aktualisiert, wird keine PIN-Verifikation
versucht.

### Behoben

**Die Mandantengrenze griff für Geräte nicht.**
Der Agent meldete Geräte, Alarme und Protokolleinträge hart unter „Praxis" —
ein Administrator der einen Einrichtung hätte die Terminals der anderen gesehen.
Die Einrichtung stammt jetzt aus der Registrierung des Agenten, nicht aus seiner
Meldung.

**Geräte fielen still durch.**
Meldete ein Agent ein Gerät in einer Einrichtung ohne Vorgabegruppe, wurde es
verworfen — im Protokoll sichtbar, aber in keiner Geräteliste. Die
Vorgabegruppe entsteht jetzt beim ersten Bedarf.

**20 verschluckte Datenbankfehler.**
`catch (SQLException ignored) {}` an 20 Stellen. Ein stiller SQL-Fehler ist
unauffindbar; so blieb ein fehlerhaftes `WHERE` monatelang unentdeckt.

**Bedienbarkeit** ([Umsetzung 26](docs/03-umsetzung/26-ux-refactoring.md),
[27](docs/03-umsetzung/27-ux-nacharbeit.md))
142 von 154 Bedienelementen hatten keinen Kurzhinweis, keine Tabellenzeile war
klickbar, das Protokoll bot 59 Ankreuzfelder statt einer Auswahl. Alles
überarbeitet und maschinell abgesichert.

### Für Betreiber wichtig

| Änderung | Was zu tun ist |
|---|---|
| **Neues Volume `sicherungen`** | Die Compose-Datei aus dem Repository übernehmen. Ohne das Volume liegen Sicherungen im Container-Layer und verschwinden beim nächsten Update. |
| **`restart: unless-stopped`** für die Zentrale | Ebenfalls aus der Compose-Datei. Ohne die Regel bleibt die Zentrale nach einer Wiederherstellung aus — sie beendet sich dort absichtlich. |
| **Bild der Zentrale wächst um ~30 MB** | Der PostgreSQL-Client für die Sicherung. Nichts zu tun. |
| **Migrationen 21 und 22** | Laufen automatisch. Vorher sichern. |
| `START_ADMIN_*` (empfohlen) | Siehe [09-devops-installation.md](docs/09-devops-installation.md), Abschnitt 4.4. Ohne diese Angaben ist der Zugang nach einem Datenverlust verloren. |

**Versionskopplung:** Der `pg_dump` im Bild muss mindestens so neu sein wie die
Datenbank — aktuell Client 18, Server 17. Wer die Datenbank anhebt, muss das
Basisimage mit anheben.

### Stand der Prüfungen

| | |
|---|---|
| Modultests | 269 |
| Systemnachweise | 352 |
| Barrierefreiheit | axe-core über 17 Seiten ohne Befund, Kontraste über dem Grenzwert |
| Diagramme | 25, alle gerendert geprüft |

> **Unverändert offen und weiterhin der wichtigste Punkt:** Alle Nachweise
> laufen gegen den mitgelieferten Simulator. Die in
> [ADR-0007](docs/adr/0007-simulierte-endpunkte-und-teststrategie.md) geforderte
> Kalibrierung an echter Hardware steht aus. Diese Version ist **nicht für den
> Produktivbetrieb an echten Kartenterminals freigegeben.**

---

## 0.1.0 — 07.08.2026

Erste Fassung. Überwachung und PEviM-Freischaltung über Brainpool-TLS,
Bestandsverwaltung, Benutzerverwaltung mit TOTP, Mandantentrennung,
Fachprotokoll mit Excel-Export, Alarme, Gerätegruppen.
