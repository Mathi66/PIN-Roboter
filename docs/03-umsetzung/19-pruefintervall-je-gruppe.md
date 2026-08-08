# 19 — Das Prüfintervall je Gerätegruppe wirkt (FA-UEB-010)

**Stand:** umgesetzt und gemessen (08.08.2026)

---

## Der Befund

Der Auftrag lautete, die Wirkung des gruppenweisen Prüfintervalls
nachzuweisen. Beim Nachsehen stellte sich heraus: Es gab keine Wirkung
nachzuweisen.

Der Agent nahm einen **globalen Wert** aus seiner Umgebung:

```java
int intervall = Integer.parseInt(env("PRUEFINTERVALL_SEKUNDEN", "10"));
...
Thread.sleep(intervallSekunden * 1000L);
```

Die Gerätegruppen trugen seit Migration 1 eine Spalte
`pruefintervall_sekunden`, die Oberfläche ließ sie pflegen, und die
Systemnachweise belegten, dass sie gespeichert wird. **Nur erreichte sie den
Agenten nie.** Die Einstellung war folgenlos.

Im Umsetzungsstand stand FA-UEB-010 auf „erfüllt". Das war falsch — belegt war
die Gruppenpflege, nicht die Überwachung.

> Das ist die Sorte Lücke, die eine Statuszeile nicht zeigt: Zwei Teile sind
> je für sich fertig und geprüft, und niemand hat geprüft, ob sie verbunden
> sind.

## Die Umsetzung

**Zentrale:** `/api/agent/intervalle` liefert je Terminal-Kennung das Intervall
seiner Gruppe.

**Agent:** Ruft die Liste in jedem Grundtakt ab und führt je Terminal einen
eigenen Zeitpunkt der letzten Prüfung. Die Schleife wacht im Grundtakt auf und
prüft dann nur die Geräte, deren Zeit gekommen ist.

Zwei Festlegungen dabei:

- **Der Grundtakt ist die Untergrenze.** Feiner als er kann kein Gerät geprüft
  werden — er ist der Takt, in dem die Schleife überhaupt aufwacht.
- **Beim ersten Durchgang wird sofort geprüft.** Ein neu erkanntes Gerät soll
  nicht bis zum Ablauf seines Intervalls unbekannt bleiben; bei fünf Minuten
  wären das fünf Minuten ohne Zustand.

Bleibt die Zentrale stumm, gilt weiter, was zuletzt bekannt war — ein Ausfall
der Zentrale hält die Überwachung nicht an (FA-AGT-030).

## Gemessen, nicht behauptet

Ob ein Agent ein Intervall einhält, lässt sich nicht am Quelltext ablesen,
sondern nur daran, **wie oft er tatsächlich fragt**. Der Simulator zählt
deshalb `GetPinStatus` je Kartenhandle, und der Nachweis liest die Zählung aus:

```
KT-ARCHIV-01     -> Gruppe mit  10 s
KT-BEHANDLUNG-02 -> Gruppe mit 120 s

In 40 s: KT-ARCHIV-01 = 4 Abfragen,  KT-BEHANDLUNG-02 = 0 Abfragen
```

Vier gegen null in vierzig Sekunden. Bei 5.000 Terminals ist genau das der
Hebel, mit dem die Last steuerbar bleibt.

## Was der Umbau nebenbei umgeworfen hat

Fünf andere Nachweise fielen sofort um — alle mit „gemessen: 0". Kein Zufall:
Die Vorgabegruppe steht auf **60 Sekunden**, und die Nachweise warteten 20 bis
26 Sekunden auf eine Verifikation. Vorher galt der Grundtakt von 4 Sekunden für
alles, jetzt der Gruppentakt.

Das ist kein Fehler im Produkt und auch keiner in den Nachweisen, sondern die
sichtbar gewordene Folge einer richtigen Änderung. Behoben, indem der
Nachweislauf die Vorgabegruppe auf **10 Sekunden** setzt — das kleinste
Intervall, das die Datenbank zulässt (`CHECK ... >= 10`, als Lastschutz
gewollt). Die Nachweise takten damit so schnell, wie das Produkt es überhaupt
erlaubt, und die Wartezeiten wurden entsprechend verlängert.

## Nachgewiesen

- Der Agent erfährt die Intervalle von der Zentrale
- Das schnelle Gerät wird im 10-Sekunden-Takt abgefragt (4 in 40 s)
- Das langsame wird geschont (0 in 40 s)
- Die Gruppeneinstellung wirkt sich messbar aus

**249 Modultests, 228 Systemnachweise** — alle grün.
