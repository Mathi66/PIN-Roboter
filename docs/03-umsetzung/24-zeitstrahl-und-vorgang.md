# 24 — Geräte-Zeitstrahl und Vorgangsansicht (FA-PRO-050, FA-PRO-060)

**Stand:** umgesetzt und nachgewiesen (09.08.2026) · **Grundlage:** ADR-0005

---

Damit sind die vier Sichten aus ADR-0005 vollständig: Lage-Übersicht,
Geräteliste, **Geräte-Zeitstrahl**, **Vorgangsansicht** — dazu das filterbare
Protokoll mit Excel-Export.

## Warum das gefilterte Protokoll nicht genügt

Beide neuen Sichten beantworten dieselbe Grundfrage — *was ist hier passiert?*
— aus zwei Richtungen: Der Zeitstrahl fragt nach einem **Gerät** über die Zeit,
die Vorgangsansicht nach einem **Vorgang** über die Geräte.

Im Protokoll sucht man Einträge. Hier liest man eine Geschichte. Der
Unterschied ist nicht kosmetisch: Wer wissen will, warum ein Terminal seit
gestern klemmt, will nicht filtern, sondern scrollen.

## Der Zeitstrahl

Erreichbar mit **einem Klick** aus der Geräteliste — die Gerätebezeichnung ist
der Link. Enthält Statuswechsel, Verifikationen, Firmware-Wechsel,
Erreichbarkeitswechsel und Konfigurationsänderungen, gruppiert nach Tagen.

Ohne Tagestrenner verschwimmt bei 200 Einträgen, was gestern und was heute war.

**Zeitraum wählbar** (24 Stunden, 7 Tage, 30 Tage, alles) und **Ereignisarten
ein- und ausblendbar** — als Ankreuzfelder, nicht als Auswahlliste: Sichtbar
ist, was an und was aus ist, ohne die Liste zu öffnen.

Der Artenfilter wirkt nachweislich: 30 von 99 Einträgen bei einer Auswahl. Ein
Filter, der nichts filtert, wäre Zierde.

## Die Vorgangsansicht

Ein Vorgang kann mehrere Geräte betreffen — ein KSR-Lauf, eine Massenaktion,
später ein Firmware-Rollout. Die Ansicht zeigt deshalb **Fortschritt**:
Ereignisse, betroffene Geräte, Fehlschläge. Eine reine Liste beantwortet die
Frage „ist das durch?" nicht.

**Selbsttätige Aktualisierung nur, solange der Vorgang läuft.** Ein
abgeschlossener Vorgang ändert sich nicht mehr; jede weitere Abfrage wäre Last
ohne Nutzen. Erkannt am jüngsten Ereignis: älter als zwei Minuten heißt
abgeschlossen.

## Vier Fehler auf dem Weg

**Der schwerste war meiner.** Ich habe den Verweis auf den Zeitstrahl per
Textersetzung in die Gerätetabelle eingefügt — und die Ersetzung traf **beide**
Tabellen, auch die in der Lage-Übersicht, wo die Argumentliste anders aussieht.
Ergebnis: `MissingFormatArgumentException`, **die Startseite antwortete mit
HTTP 500**.

Genau dieser Fehlertyp ist in diesem Projekt schon einmal aufgetreten (eine
Massenersetzung traf `Alarmverwaltung.beheben`). Zweimal derselbe Griff, zweimal
dasselbe Ergebnis.

**Und der Nachweis, der ihn hätte fangen müssen, hat ihn verdeckt.** Die Prüfung
„jeder Navigationslink führt zu einer Seite" holt ihre Liste aus der
Startseite. War die kaputt, fand sie null Ziele — und meldete „0 tot", also
bestanden. Sie hat jetzt zusätzlich eine feste Liste bekannter Seiten.

> Ein Nachweis, dessen Prüfmenge aus dem Prüfgegenstand stammt, kann bei dessen
> Totalausfall nicht anschlagen.

**Zwei weitere Nachweisfehler**, beide durch frühere Änderungen ausgelöst:

- Der Alarmnachweis zählte „Quittieren"-Knöpfe auf der Startseite. Mit jedem
  neuen Alarmtyp wurde das unzuverlässiger; er meldete eine „Alarmflut", wo nur
  ein zweiter, anderer Alarm dazukam. Jetzt zählt er per Datenbankabfrage genau
  den geprüften Alarm.
- Dabei stellte sich heraus, dass die Datenbank-Hilfsfunktionen erst 150 Zeilen
  **nach** ihrer ersten Verwendung definiert waren. Bash kennt eine Funktion
  erst ab ihrer Definition — der Block lief in `command not found`.

## Nachgewiesen

11 neue Systemnachweise: Verlinkung aus der Geräteliste, Erreichbarkeit,
enthaltene Ereignisarten, wählbarer Zeitraum, ein- und ausblendbare Arten,
wirksamer Filter, Zugriffsschutz; Vorgangsansicht mit allen Ereignissen,
Fortschritt, bedingter Aktualisierung und Rückverweis aus dem Zeitstrahl.

**206 Modultests in der Zentrale, 282 Systemnachweise** — alle grün, axe-core
ohne Befund auf 18 Seiten.
