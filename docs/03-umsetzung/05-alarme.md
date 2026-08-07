# Umsetzung: Alarmierung

**Anforderung:** FA-ALA-010

---

## Der tragende Unterschied

**Ein Alarm ist kein Protokolleintrag.**

| | Protokoll | Alarm |
|---|---|---|
| beschreibt | was geschehen ist | was gerade nicht in Ordnung ist |
| Anzahl | beliebig viele | je Objekt und Art **genau einer** |
| verschwindet | nie | wenn der Zustand behoben ist |

Daraus folgt alles Weitere.

## Keine Alarmflut

Ein Datenbank-Index erzwingt je Objekt und Art höchstens einen offenen Alarm.
Ohne diese Regel erzeugte ein alle vier Sekunden prüfender Agent **900 Alarme
pro Stunde** für dasselbe Problem — und nach dem dritten Tag würde niemand mehr
hinsehen.

Der Test meldet denselben Zustand hundertmal und prüft, dass genau ein Alarm
entsteht.

## Behobene Zustände schließen sich selbst

`alarmzustaendeAbleiten()` behandelt jeden Zustand in **beide Richtungen**:
liegt er vor → melden, liegt er nicht mehr vor → schließen.

Ein Alarm, den jemand wegklicken muss, obwohl das Problem längst weg ist,
erzieht dazu, Alarme wegzuklicken.

## Quittieren heißt gesehen, nicht behoben

Ein quittierter Alarm bleibt offen und sichtbar, zählt aber nicht mehr als
Handlungsbedarf. Das ist der Unterschied zwischen „ich kümmere mich" und
„erledigt".

## Jeder Alarm sagt, was zu tun ist

```
Die SMC-B ist gesperrt. Der TI-Zugang über dieses Gerät ist unterbrochen.
→ Die Karte lässt sich nur mit der PUK entsperren. Ein weiterer
  PIN-Versuch hilft nicht.
```

Eine Meldung, die nur sagt, was kaputt ist, hilft in einer Praxis niemandem.

## Sortierung nach Schweregrad, nicht nach Zeit

Was den Betrieb anhält, muss zuerst ins Auge fallen.

## Offen

- **FA-ALA-020:** Benachrichtigung per E-Mail mit Sammelfenster, damit ein
  Konnektorausfall nicht 5.000 Nachrichten erzeugt.
