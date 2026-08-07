# Umsetzung: Fachprotokoll, Export und Speicherbudget

**Anforderungen:** FA-PRO-010/020/030/040/070, NFA-BET-020

---

## Die Grundregel

**Protokolliert werden Zustandsänderungen und Handlungen, keine Abfragen.**

Bei 5.000 Terminals im Minutentakt entstünden sonst 7,2 Millionen Einträge am
Tag. Eine Prüfung, die den erwarteten Zustand bestätigt, aktualisiert nur das
Feld „zuletzt geprüft" am Gerät.

Das ist keine Sparmaßnahme, sondern der Unterschied zwischen einem Protokoll, in
dem man etwas findet, und einem, in dem man ertrinkt.

## Unveränderlichkeit auf Datenbankebene

Ein Trigger weist `UPDATE` und `DELETE` einzelner Einträge ab:

```
FEHLER: Das Fachprotokoll ist nur anfuegbar.
```

Die Rotation ist der **einzige** zulässige Löschweg und muss sich dafür
ausdrücklich kennzeichnen (`SET pinroboter.rotation = 'an'`). Eine versehentlich
hinzugefügte Löschanweisung an anderer Stelle scheitert.

## Speicherbudget

Gemessen wird die **tatsächliche Größe** (`pg_total_relation_size`), nicht die
Zeilenzahl: Eine Zeile mit langer Fehlerbeschreibung belegt ein Vielfaches einer
knappen Statusmeldung.

| Füllstand | Verhalten |
|---|---|
| 80 % | Rotation beginnt |
| 90 % | Alarm |
| 95 % | verschärfte Rotation |

**Gestaffelt nach Wichtigkeit:** technische Diagnose zuerst, Routineereignisse
danach, sicherheitsrelevante zuletzt.

### Zwei Fallen, die die Tests aufgedeckt haben

**Rotation ohne Wirkung.** `pg_total_relation_size` schrumpft nicht durch
`DELETE` allein — der Platz bleibt bis zum `VACUUM` belegt. Die Schleife sah
keinen Fortschritt und löschte weiter, bis eine Stufe leer war. Die Staffelung
wäre damit wirkungslos gewesen. **Lösung:** `VACUUM` nach jedem Löschblock.

**Zu kleines Budget hätte alles vernichtet.** Eine leere Tabelle ist nicht
0 Byte groß — allein die fünf Indizes belegen Platz. Ist das Budget kleiner, ist
das Ziel **nie** erreichbar. **Lösung:** Ein Rotationslauf entfernt höchstens die
Hälfte der Einträge und meldet „Budget zu klein". Eine Fehlkonfiguration darf
Daten kosten, aber nicht alle.

### Die wichtigste Regel

**Ein volles Protokoll hält die Kernfunktion nicht an.** Nachgewiesen mit 400 KB
Budget: sechs Verifikationen liefen weiter, während rotiert wurde.

## Excel-Export

Format `.xlsx`, selbst geschrieben (ECMA-376). Apache POI wiegt rund 10 MB für
ein Vielfaches des Benötigten — gegen NFA-LEI-030 schwer zu rechtfertigen.

**Kein CSV:** Bei deutschen Excel-Einstellungen zerlegt es regelmäßig die
Spalten und verstümmelt Umlaute.

Enthalten sind: Deckblatt mit Filtern und Zeilenzahl, fixierte Kopfzeile,
Autofilter, echte Datums- und Zahlenwerte. Steuerzeichen werden gefiltert — sie
sind in XML unzulässig, und ein einziges Byte in einem Gerätenamen machte die
Datei unöffenbar.

Der Nachweis läuft über **openpyxl**, eine unabhängige Umsetzung. Das eigene
Format gegen die eigene Umsetzung zu prüfen wäre zirkulär.

## Offen

- **FA-PRO-040:** Große Exporte laufen noch nicht im Hintergrund, und die
  Warnung vor der Excel-Zeilengrenze fehlt.
- **FA-PRO-080:** Aufbewahrungsdauer ist noch nicht konfigurierbar.


---

## Blättern in der Protokollansicht (nachgetragen 07.08.2026)

Bis zu diesem Tag zeigte die Ansicht die **200 jüngsten Einträge** und konnte
nicht zurückblättern. Solange wenig geschah, fiel das nicht auf. Mit den
Ereignissen aus Bestandspflege und -ermittlung rutschten ältere Einträge
schneller aus dem Blick — aufgefallen ist es, als zwei Nachweise anfingen zu
flattern, die einen Eintrag von wenigen Minuten zuvor suchten.

**Für ADR-0005 war das eine echte Lücke.** Der Roboter handelt selbsttätig;
*jede* seiner Handlungen muss im Nachhinein auffindbar sein, nicht nur die der
letzten Stunde. Der Excel-Export reichte weiter zurück, die Ansicht nicht — wer
einen Vorgang von gestern nachvollziehen wollte, musste exportieren. Das ist
keine gute Antwort.

### Umsetzung

Serverseitige Paginierung wie in der Bestandsliste, **50 Zeilen je Seite**:

- Zählabfrage und Datenabfrage getrennt, beide mit denselben Filtern. Eine
  Anfrage mehr, aber die Alternative — alles laden und im Speicher zählen — ist
  genau das, was bei 200.000 Einträgen nicht mehr funktioniert.
- Sortiert nach `(zeitpunkt DESC, id DESC)`. Die `id` ist nicht schmückendes
  Beiwerk: Ohne eindeutige Sortierung wandern Zeilen zwischen Seiten, sobald
  zwei Einträge denselben Zeitstempel tragen — bei einem Roboter, der mehrere
  Geräte gleichzeitig bearbeitet, ist das der Normalfall.
- Obergrenze von 500 Zeilen je Anfrage, damit eine manipulierte URL nicht das
  gesamte Protokoll zieht.
- Wer hinter das Ende blättert — etwa weil ein Filter enger wurde —, landet auf
  der letzten vorhandenen Seite statt auf einer leeren.

### Die Seitennummer und die Auffrischung

Die Ansicht frischt sich alle fünf Sekunden auf. Ohne Gegenmaßnahme würde das
jeden, der zurückblättert, nach fünf Sekunden auf Seite 1 zurückwerfen.

Gelöst über ein verstecktes Feld `f-seite` in der Filterleiste: Es wird bei
jeder Abfrage mitgesendet, und die Blätterknöpfe setzen es, bevor sie die
Abfrage auslösen. Die Seite bleibt damit stehen.

Was bleibt: Auf Seite 2 und weiter **wandern Einträge langsam nach hinten**,
weil oben neue dazukommen. Das ist bei einer Live-Ansicht unvermeidbar. Die
Blätterleiste sagt es deshalb ausdrücklich — *„neue Einträge erscheinen auf
Seite 1"* —, statt den Benutzer rätseln zu lassen.

### Ein Test, der unterwegs etwas anderes bewiesen hat

Der erste Anlauf des Paginierungstests räumte am Ende seine Testeinträge weg —
und scheiterte. **Zu Recht:** Das Fachprotokoll ist unveränderlich (FA-PRO-010),
ein `DELETE` wird abgewiesen; nur die Rotation darf löschen.

Der Test befüllt jetzt nur, wenn noch nichts da ist, und der gescheiterte
Löschversuch ist als eigener Nachweis stehengeblieben.
