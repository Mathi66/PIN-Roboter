# 20 — Der konnektorweite KSR-Lauf (gemSpec_Kon 4.3.10)

**Stand:** umgesetzt und gemessen (08.08.2026) · **Grundlage:** FA-UEB-050,
`[gemSpec_Kon] 4.3.10`, Sicherheitsleitplanke CLAUDE.md

---

## Worum es geht

Der Konnektor aktualisiert Kartenterminals selbst, über seinen KSR-Client. Der
Kartenterminaldienst kennt dafür den Zustand **„aktualisierend"**, angezeigt
durch die Ereignisse `KSR/UPDATE/START` bis `KSR/UPDATE/END` mit `Target=KT`.

CLAUDE.md nennt die Folge unmissverständlich:

> Der PIN-Roboter **muss** diesen Zustand erkennen und in dieser Zeit **keine
> PIN-Verifikation versuchen** — sonst belastet er im ungünstigen Fall den
> Fehlversuchszähler der SMC-B.

Drei Fehlversuche sperren die Karte. Sie lässt sich dann nur mit der PUK
entsperren — in einer Praxis unter Zeitdruck ein halber Tag Ausfall.

## Was schon da war und was fehlte

Die Auswertung selbst gab es: `Sicherungspruefung` lehnt eine Verifikation ab,
wenn `terminalAktualisiert` gilt, und der Agent liest das Kennzeichen
`Updating` aus `GetPinStatus`.

**Geprüft war aber nur der Einzelfall:** ein Firmware-Update an *einem*
Terminal, ausgelöst über die Steuerung des Simulators. Der KSR-Lauf ist etwas
anderes — er betrifft **mehrere Geräte gleichzeitig**, oft alle eines
Standorts. Ein einziges übersehenes Gerät reicht, um eine Karte zu belasten.

## Umgesetzt

**Simulator:** Ein konnektorweiter KSR-Lauf über die Steuerung.

```
POST /ksr/start?dauer=<ms>    alle Terminals werden „aktualisierend"
POST /ksr/ende                Zustand sofort beenden
```

Nach Ablauf entzieht er allen Karten die Verifikation — genau wie ein echter
Neustart nach dem Update. Das wiederherzustellen ist der Kernzweck dieses
Produkts.

**Ein Fehler dabei gefunden:** Im frühen Rückgabepfad von `durchgang()` — wenn
ein Terminal nicht erreichbar ist oder keine Karte trägt — wurde
`aktualisierend` gar nicht gemeldet. Der zuletzt gemeldete Wert blieb in der
Zentrale stehen. Die Oberfläche hätte dauerhaft ein Update angezeigt, das
längst vorbei ist. Jetzt wird das Kennzeichen dort ausdrücklich auf `false`
gesetzt.

## Nachgewiesen

| | Ergebnis |
|---|---|
| Der KSR-Lauf betrifft mehrere Geräte gleichzeitig | 4 Terminals |
| Verifikationsversuche während des Laufs | **0** bei ~4 Gelegenheiten |
| Wiederherstellung danach | erfolgt |
| Die Oberfläche nennt den Zustand | „wird aktualisiert" |

Der Nachweis startet den Stack vorher neu: Der vorangehende Nachweis zu
FA-PIN-060 löscht die hinterlegte PIN, und ohne PIN könnte der Roboter nach dem
Lauf nichts wiederherstellen — der Nachweis hätte eine Funktion als kaputt
gemeldet, die in Ordnung ist.

## Was damit **nicht** belegt ist

Der Agent erkennt den Zustand daran, dass der Konnektor ihn im
`GetPinStatus` als `Updating` mitliefert. **Ob ein echter KoCo-Konnektor das
tut, ist offen.** Denkbar ist auch, dass er den Zustand ausschließlich über den
Ereignisdienst meldet (`KSR/UPDATE/START`) und die Statusabfrage unverändert
lässt. Dann würde der Roboter den Lauf nicht bemerken.

Das ist keine Kleinigkeit, sondern der Unterschied zwischen „abgesichert" und
„vermutlich abgesichert". Es gehört auf die Liste für die Kalibrierung am
Testaufbau (ADR-0007) und ist dort mit einem einzigen Versuch zu klären: einen
KSR-Lauf auslösen und beobachten, was `GetPinStatus` während dieser Zeit
liefert.

Bis dahin gilt: Am Simulator abgesichert, an echter Hardware zu bestätigen.
