# 32 — Alarme von Hand schließen (FA-ALA-030)

**Stand:** umgesetzt und am laufenden Stack nachgewiesen (13.08.2026)
· **Vorgabe:** PO, 13.08.2026

---

## Der Anlass

Der PO wollte „in der Lage-Übersicht alle Alarme löschen" können. Ein Blick in
seine Datenbank zeigte, warum:

```
 id  |   einrichtung    |      objekt      |          art
-----+------------------+------------------+-------------------------
   1 | Kardiologie Nord | KT-BEHANDLUNG-02 | PEVIM_NICHT_AKTIV
  57 | PruefEinrichtung | KT-8444d3f1      | GERAET_NICHT_ERREICHBAR
 …   | PruefEinrichtung | KT-…             | …
 352 | Praxis           | Sicherung täglich| SICHERUNG_FEHLGESCHLAGEN
```

**Elf offene Alarme — und kein einziger zu einem Gerät, das es noch gibt.** Alle
stammten aus gelöschten Demo- oder Prüfbeständen: „Kardiologie Nord" ist eine
Einrichtung aus dem alten Demoszenario, „KT-8444d3f1" ein Lasttestgerät,
„Praxis" die Vorgabe aus der Zeit vor der Mandantenfähigkeit.

**Die Ursache liegt im Alarmmodell.** `Alarmverwaltung.beheben` wird ausgelöst,
wenn ein Agent meldet, dass der Zustand weg ist — bewusst automatisch, damit
niemand Alarme wegklicken muss. Für ein Gerät, das gelöscht wurde, kommt diese
Meldung nie. Der Alarm bleibt für immer offen, und die wichtigste Seite des
Produkts füllt sich mit Rauschen.

## Warum es „schließen" heißt und nicht „löschen"

Der Datensatz bleibt; gesetzt wird nur `behoben_am`. Damit verschwindet der
Alarm aus der Liste der offenen — genau das, was der PO wollte — und bleibt
nachvollziehbar (ADR-0005). Ein `DELETE` würde die Frage „warum war dieses
Gerät drei Wochen lang nicht erreichbar?" unbeantwortbar machen.

**Es hebt das Problem nicht auf.** Besteht die Ursache weiter, meldet der Agent
den Alarm im nächsten Prüflauf erneut, und er steht wieder da. Das ist gewollt
und eigens nachgewiesen: Ein Wegklicken, das eine echte Störung dauerhaft
verschwinden ließe, wäre genau der Fehler, den man unter Zeitdruck macht.

| | Quittieren | Schließen | Beheben |
|---|---|---|---|
| Ausgelöst von | Benutzer | Benutzer | Agentmeldung |
| Bedeutung | „zur Kenntnis genommen" | „soll nicht mehr in der Liste stehen" | „Zustand ist weg" |
| Aus der Liste | nein | **ja** | ja |
| Kommt zurück | — | ja, wenn die Ursache besteht | ja, wenn sie wiederkehrt |
| Recht | jeder im Bereich | `darfBestandPflegen` | — |
| Protokollstufe | Routine | **Sicherheit** | Routine |

Schließen steht auf der Sicherheitsstufe, Quittieren nicht: Hier entscheidet
ein Mensch, dass ein Alarm nicht mehr sichtbar ist. Das muss die Rotation
überleben.

## Die Sammelaktion

Ein Knopf „Alle schließen" im Kopf der Alarmtafel, mit Rückfrage — und die
Rückfrage **nennt die Zahl**:

> Alle 11 Alarme schließen? Sie verschwinden aus der Liste und bleiben im
> Protokoll. Störungen, die weiter bestehen, meldet der Roboter erneut.

„Alle Alarme schließen?" ohne Mengenangabe ist etwas anderes, wenn man
dreihundert hat. Destruktive Sammelaktionen ohne Zahl sind die häufigste
Ursache für ein „das wollte ich nicht".

Die Aktion trifft **nur den eigenen Geltungsbereich**. Eine Sammelaktion, die
versehentlich fremde Mandanten stillstellt, wäre schlimmer als gar keine.

Ins Protokoll geht die Zahl mit: Ohne sie weiß hinterher niemand, ob drei oder
dreihundert Alarme verschwunden sind.

## Die Ursache selbst

Die Funktion räumt auf; sie beseitigt nicht, was den Müll erzeugt. Deshalb
nehmen `Demodaten.loeschen` und `Lasterzeuger.entfernen` jetzt die Alarme ihrer
Geräte mit. Beides ist eigens nachgewiesen — sonst entstehen dieselben
Karteileichen beim nächsten Demodurchlauf erneut.

> Dasselbe Muster wie beim Löschen des Demobestands eine Woche zuvor: Der Agent
> legt Zeilen an, die kein Demo-Kennzeichen tragen, weil er den Unterschied
> nicht kennt. Wer aufräumt, muss über den **Bezug** aufräumen, nicht über das
> Kennzeichen.

## Nachgewiesen

Modultests (`AlarmverwaltungTest`, gegen echte Datenbank): einzeln schließen,
Datensatz bleibt erhalten, Rückkehr bei fortbestehender Ursache, kein Schließen
außerhalb des Geltungsbereichs, Sammelaktion mit korrekter Anzahl,
Sammelaktion eines leeren Bereichs trifft nichts.

Systemnachweise am laufenden Stack: Knopf je Alarm und Sammelaktion sind da,
die Rückfrage nennt die Zahl, Einzelschließen wirkt, der Datensatz bleibt, das
Fachprotokoll trägt den Eintrag samt Anzahl, derselbe Zustand löst danach
wieder einen Alarm aus — und das Löschen des Demobestands lässt keinen Alarm
zurück.
