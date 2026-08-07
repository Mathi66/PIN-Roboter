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
