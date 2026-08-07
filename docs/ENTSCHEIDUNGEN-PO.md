# Was zu entscheiden ist

**An:** Mathias Nieting (Product Owner) · **Stand:** 07.08.2026

Diese Liste ist kurz gehalten. Alles, was ich selbst entscheiden konnte, habe
ich entschieden und begründet. Hier steht nur, was Ihnen zusteht.

---

## 1. Zwei Architekturentscheidungen brauchen Ihre Freigabe

Beide sind seit dem 07.08. umgesetzt und tragen — die formale Freigabe steht
aus. Sie wurde durch die Beauftragung der autonomen Umsetzung überholt.

### ADR-0006 — Technologie-Stack: Java mit BouncyCastle

**Worum es geht:** Java 21, BouncyCastle für Kryptographie, server-gerendertes
HTML, PostgreSQL.

**Warum so:** Nicht aus Vorliebe, sondern durch Messung. Der erste Vorschlag
lautete Go — er war **falsch**, weil er die TLS-Kurven nicht geprüft hatte. Ihr
Hinweis auf Brainpool hat das aufgedeckt. Go kann ein Brainpool-Zertifikat nicht
einmal einlesen; das Standard-JDK scheitert an den Kurvengruppen; Java mit
BouncyCastle funktioniert. Konnektor und HSK verwenden dieselbe Bibliothek.

**Was passiert, wenn Sie nicht freigeben:** Der Stack wäre neu zu wählen, und
rund 13.000 Zeilen wären neu zu schreiben. Ich sehe keinen fachlichen Grund
dafür, muss die Entscheidung aber nicht allein tragen.

**→ [ADR-0006](adr/0006-technologie-stack.md)**

### ADR-0007 — Simulierte Endpunkte

**Worum es geht:** Die gesamte Prüfung läuft gegen einen selbstgebauten
Simulator von Konnektor und Kartenterminals.

**Warum so:** Drei Nachweise sind mit echter Hardware nicht führbar — 5.000
Terminals, reproduzierbare Fehlerfälle, und vor allem `FA-PIN-030`: Der Nachweis,
dass eine falsche PIN genau **einen** Versuch auslöst, verbrennt real bei jedem
Durchlauf einen Fehlversuch der Karte.

**→ [ADR-0007](adr/0007-simulierte-endpunkte-und-teststrategie.md)**

---

## 2. Eine Risikoentscheidung, die Ihnen gehört

### ADR-0008 — Geräte-Zugangsdaten in der Zentrale

**Die Lage:** Der Anforderungskatalog verlangt, Konnektor- und
Terminal-Zugangsdaten zu erfassen. Ich habe entschieden, sie **verschlüsselt in
der Zentrale** abzulegen (AES-256-GCM, Schlüssel aus der Konfiguration).

**Die SMC-B-PIN ist davon ausdrücklich ausgenommen** — sie liegt ausschließlich
beim Agenten und erreicht die Zentrale nie.

**Was das nicht leistet, ohne Beschönigung:** Wer Datenbank *und* Schlüssel hat,
kann entschlüsseln. Der Schutz richtet sich gegen Sicherungen, Datenauszüge und
versehentliche Ausgaben — nicht gegen vollen Zugriff auf den Docker-Host.

**Ihre Entscheidung:** Ist das für eine Klinik-IT tragbar? Die Alternative wäre,
Zugangsdaten gar nicht zu speichern und bei jedem Vorgang abzufragen — das ist
bei einem selbsttätig handelnden Roboter nicht praktikabel, er kann niemanden
fragen.

**→ [ADR-0008](adr/0008-ablage-von-geraete-zugangsdaten.md)**

---

## 3. Eine offene Frage aus ADR-0004

**Firmware-Bezugsweg:** Bestehen Herstellerkontakte, über die zugelassene
Firmware legal bezogen und an Kunden weitergegeben werden darf?

Die Antwort entscheidet, ob die Firmware-Verwaltung (9 Anforderungen, derzeit
nicht begonnen) überhaupt in der geplanten Form gebaut werden kann.

**→ [ADR-0004](adr/0004-firmware-verwaltung-der-kartenterminals.md)**

---

## 4. Der wichtigste offene Punkt — und er kostet wenig

### Kalibrierung am echten Testaufbau

**146 Systemnachweise sind grün. Alle laufen gegen den Simulator.**

Der Simulator bildet ab, was *ich glaube*, wie sich die Geräte verhalten. Ist
diese Annahme falsch, ist ein Teil der Nachweise wertlos — und das merkt man
beim ersten Kontakt mit echter Hardware.

ADR-0007 nennt dagegen eine Regel: **Kalibrierung so früh wie möglich.** Die
risikofreie Lesestufe — Status abfragen, Karten und Terminals ermitteln,
Informationsmodell lesen — verändert **nichts** am Gerät und braucht keine
PIN. Sie kostet wenig und beantwortet die Fragen, die derzeit offen sind:

- Antwortet der KoCo-Konnektor mit TLS 1.2 und Brainpool, wie gemessen?
- Stimmt die Struktur des Informationsmodells mit meiner Annahme überein?
- Welche Terminal-Modelle, Firmware-Stände, PEviM-Zustände liegen wirklich vor?
- Heißen die SOAP-Felder so, wie der Simulator sie nachbildet?

**Was ich dafür brauche:** Zugang zum Testaufbau und die Erlaubnis, ausschließlich
lesende Operationen auszuführen.

**Was ich liefere:** einen Messbericht wie den zur Brainpool-Tauglichkeit, dazu
die Korrektur des Simulators an jeder Stelle, an der er von der Wirklichkeit
abweicht.

---

## Wo das Produkt steht

| | |
|---|---|
| Modultests | **197**, alle grün |
| Systemnachweise am laufenden Stack | **146**, alle grün |
| Anforderungen belegt (✓) | 38 von 84 |
| teilweise oder nur Modultests | 28 |
| nicht begonnen | 18 (überwiegend Firmware-Verwaltung und Bauvorgang) |

Der vollständige Stand je Anforderung: **[docs/01a-umsetzungsstand.md](01a-umsetzungsstand.md)**

**Was läuft:** Der Roboter hält eine SMC-B über PEviM und Brainpool-TLS
selbsttätig verifiziert. Bestand, Benutzer, Mandanten, Protokoll, Alarme,
Sicherung und Wiederherstellung sind gebaut und belegt.

**Was fehlt am meisten:** die Wirklichkeit.
