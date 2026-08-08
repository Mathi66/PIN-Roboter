# 21 — Nicht behebbare Zustände, Erreichbarkeit, Firmware-Stand

**Stand:** umgesetzt und nachgewiesen (08.08.2026)
**Anforderungen:** FA-PIN-040, FA-UEB-020, FA-UEB-030

---

Alle drei standen im Umsetzungsstand als „nur Modultests" oder „kein eigener
Nachweis". Beim Nachsehen war zweimal mehr als ein fehlender Nachweis offen.

## FA-PIN-040 — die Meldung war halb

Der Roboter erkannte `TRANSPORT_PIN`, `EMPTY_PIN`, `BLOCKED` und `DISABLED`
korrekt und unternahm keinen Verifikationsversuch. Die Anforderung verlangt
aber mehr:

> …erzeugen je eine eigene, in Klartext erklärte Meldung **mit der
> erforderlichen Handlung**.

Die Handlung stand nur auf der Lage-Seite. Wer die **Geräteliste** filterte —
und bei 5.000 Terminals tut man das —, las dort „Transport-PIN" und war so klug
wie zuvor.

Jetzt trägt die Zeile den Hinweis mit: „Die Erst-PIN wurde noch nicht geändert.
Das muss einmalig am Kartenterminal geschehen." Nur bei Handlungsbedarf — eine
Zeile „alles in Ordnung" unter jedem funktionierenden Gerät macht die Liste
unlesbar.

**Nachgewiesen:** beide Zustände sichtbar, beide mit Handlung, und je **0**
Verifikationsversuche.

## FA-UEB-020 — war in Ordnung, aber unbelegt

Der Wechsel erreichbar ↔ nicht erreichbar wurde bereits protokolliert. Neu ist
der Nachweis, und er prüft drei Dinge:

| | |
|---|---|
| Der Ausfall steht im Protokoll | ✔ |
| Die Rückkehr ebenfalls | ✔ |
| **Bei gleichbleibendem Zustand kommt nichts dazu** | 38 → 38 über 30 s |

Der dritte ist der wichtigste. Die Grundregel aus ADR-0005 lautet: nur
Zustandsänderungen protokollieren, keine Abfragen. Bei 5.000 Terminals im
Minutentakt wären es sonst Millionen Einträge am Tag, und das Protokoll wäre
wertlos.

## FA-UEB-030 — zwei Teile fehlten

`FIRMWARE_GEAENDERT` existierte als Ereignisart und wurde **nirgends
ausgelöst** — dieselbe Sorte Lücke wie bei FA-UEB-010: ein Teil vorhanden, nie
verbunden.

Das ist nicht nebensächlich. Aktualisiert der Konnektor ein Terminal über
seinen KSR-Client, erfährt der Betreiber es sonst nirgends. Eine Firmware, die
sich unbemerkt ändert, macht jede spätere Fehlersuche zum Ratespiel. Jetzt wird
der Wechsel protokolliert, **unabhängig davon, wer ihn ausgelöst hat** — genau
so steht es in der Akzeptanz.

Ebenfalls gefehlt hat die **Flottenübersicht**. Bei 5.000 Geräten ist die Frage
nicht „welche Firmware hat Gerät X", sondern „wie viele hängen zurück". Die
Übersicht zeigt je Modell die Verteilung der Stände und bewertet sie:

| Modell | Stand | Geräte | Bewertung |
|---|---|---|---|
| CHERRY ST-1506 | 4.3.0 | 28 | ✓ aktuellster Stand |
| | 4.2.1 | 4 | liegt zurück |

Als „aktuellster Stand" gilt die höchste vorkommende Version je Modell — einen
**Sollstand kennt der Roboter nicht**. Die Firmware kommt vom Hersteller des
Geräts, nicht von uns (ADR-0004), und eine erfundene Sollversion würde
Sicherheit vortäuschen.

## Nachgewiesen

14 neue Systemnachweise. **249 Modultests, 246 Systemnachweise** — alle grün,
axe-core ohne Befund auf 17 Seiten.
