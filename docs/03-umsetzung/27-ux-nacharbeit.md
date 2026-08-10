# 27 — UX-Nacharbeit nach Durchsicht des PO

**Stand:** 10.08.2026 · **Anlass:** Sechs Befunde des PO an der laufenden Oberfläche

Das UX-Refactoring ([Umsetzung 26](26-ux-refactoring.md)) hat Einheitlichkeit
maschinell abgesichert. Was ein Werkzeug **nicht** prüfen kann, ist
Angemessenheit — ob eine Auswahl bedienbar ist, ob ein Knopf am richtigen Platz
sitzt. Dafür braucht es einen Menschen, der hinsieht.

Alle sechs Befunde kamen aus einer Durchsicht der laufenden Oberfläche.

---

## 1. Gestreckte Knöpfe in Masken

**Befund:** „Umbenennen" war so breit wie das Eingabefeld daneben.

**Ursache:** Der Knopf steckte in einer `.feldgruppe` — die hat
`flex: 1 1 14rem` und wächst auf die halbe Reihenbreite. Dazu ein unsichtbares
Platzhalter-Label, das ihn auf Höhe der *Feldbeschriftung* schob statt auf die
Grundlinie des Feldes.

**Behoben:** `.feldaktion` — natürliche Breite, unten bündig. Drei Stellen.

**Nachweis:** „Kein Knopf steckt in einer gestreckten Feldgruppe."

---

## 2. Hinweiskästen mit zwei Spalten

**Befund:** In „Beim Entfernen werden ausschließlich Demodaten gelöscht" stand
die fette Überschrift links und der Fließtext rechts daneben.

**Ursache:** `.hinweis` hatte `display:flex` — gedacht für ein Symbol neben dem
Text. Ein Symbol gibt es nirgends; stattdessen wurde jedes
`<strong>Überschrift</strong> Fließtext` zu zwei Spalten.

**Behoben:** `display:block`.

> Eine Regel, die für einen Fall gedacht ist, der nie eintritt, richtet Schaden
> in allen anderen an.

---

## 3. Karteninhalte ohne senkrechten Rhythmus

**Befund:** Kachelblock, Hinweis und Knopf klebten aneinander.

**Ursache:** Der Container setzte nur Innenabstand, keinen Abstand *zwischen*
seinen Kindern.

**Behoben:** `.karte-leib` ist jetzt eine Spalte mit `gap` — und die Knöpfe
darin behalten über `align-self:flex-start` ihre natürliche Breite. Dieselbe
Falle wie bei `.feldgruppe`, nur eine Ebene höher.

---

## 4. Tabellen liefen aus dem Bild

**Befund:** Zweimal gemeldet — im Bestand und in der Terminalliste waren die
letzten Spalten abgeschnitten.

**Messung** bei 1280 px Fensterbreite:

| | Tabelle | Platz | Überlauf |
|---|---:|---:|---:|
| vorher | 1274 px | 982 px | **292 px** |
| nachher | 984 px | 982 px | **2 px** |

**Drei Ursachen, alle gemessen statt vermutet:**

1. **Zu viele Spalten.** Die Terminalliste hatte zehn. Kennung und Adresse sind
   jetzt Zeilen unter dem Namen, nicht eigene Spalten — man liest sie, wenn man
   ein bestimmtes Gerät sucht, nicht beim Überfliegen. Die Konnektorliste
   verlor den Aufrufkontext (er steht auf der Detailseite und im Tooltip).
2. **Der Zellenabstand.** 24 px je Seite kosten bei acht Spalten **384 px** —
   mehr als ein Drittel der Breite, nur für Luft. Innen jetzt 12 px, außen
   weiterhin am Kartenrand ausgerichtet.
3. **Ein eigener Fehler:** Die Regel `td.geraet, th:first-child { min-width:11rem }`
   traf auch die Ankreuzspalte und blies sie von 42 auf **176 px** auf.

**Nachweis:** „Keine Liste hat mehr als acht Spalten" — eine Faustregel aus der
Messung, weil sich Pixelbreiten ohne Browser nicht prüfen lassen.

---

## 5. Netzwerkadresse in eigener Zeile

**Befund des PO:** „IP-Adresse soll immer unter dem KT-Namen stehen."

Kennung und Adresse standen zusammen in einer Zeile und brachen je nach Länge
des Namens mal um und mal nicht. Die Liste wirkte unruhig, ohne dass sich sagen
ließ, woran es liegt.

Jetzt drei feste Zeilen: Name, Kennung, Adresse.

---

## 6. Die Häkchenwand im Zeitstrahl

**Befund des PO:** „Das hat keine Usability, so viele blaue Haken."

Der Zeitstrahl zeigte **59 Ankreuzfelder, alle angehakt**. Zwei Dinge waren
daran falsch:

- **Die Vorgabe „alles an" verlangt Abwählen.** Wer nur Freischaltungen sehen
  will, müsste 52 Häkchen entfernen. Das tut niemand — also filtert niemand.
- **Die Liste folgte der Datenbank, nicht der Frage.** Ein Anwender fragt „gab
  es Probleme?", nicht „möchte ich `PEVIM_NICHT_AKTIV` sehen?".

**Jetzt vier Sichten**, jede beantwortet eine echte Frage:

| Sicht | Beantwortet |
|---|---|
| **Alles** | — (Vorgabe) |
| **Nur Probleme** | Gab es Ausfälle, Fehlschläge, gesperrte Karten? |
| **Was der Roboter tat** | Hat er freigeschaltet, und wann? |
| **Änderungen durch Menschen** | Wer hat was erfasst, geändert, gelöscht? |

Die Zuordnung sitzt im Enum `Ereignisart` (`bereich()`, `istProblem()`,
`istAutomatik()`), nicht in der Seitenklasse — sonst müsste jede Seite sie
erneut treffen und könnte sie anders treffen.

**„Änderungen durch Menschen" ist bewusst als Rest definiert** (weder Automatik
noch Problem). Sonst fiele eine neu hinzugefügte Ereignisart aus allen Sichten
heraus und wäre nur über die Feinauswahl zu finden.

Die Feinauswahl bleibt erreichbar — nach Themenbereich gruppiert, zugeklappt.
Sie ist die Ausnahme, nicht der Regelweg. Und die Sicht wirkt sofort beim
Anklicken, wie die Live-Suche; „Anwenden" bleibt für den Zeitraum und für den
Fall ohne JavaScript.

**Barrierefreiheit:** Die Auswahlfelder bleiben im Markup und werden nur optisch
versteckt — Screenreader lesen weiterhin eine Optionsgruppe, die Pfeiltasten
funktionieren. Die gewählte Sicht trägt zusätzlich zum Rahmen ein Häkchen im
Namen: Farbe ist nie das einzige Merkmal.

---

## Was dabei nebenbei gefunden wurde

### Der Demodatensatz hatte keine Gruppen

Vorgabe des PO: „Pro Standort eine Gruppe mit den jeweiligen Kartenterminals."

Vorher legte der Demodatensatz 36 Terminals an, aber keine Gruppen — die
Gruppenseite zeigte eine leere Liste. Wer die Funktion „Prüfintervall je Gruppe"
beurteilen soll, sah nichts, was er beurteilen könnte.

Jetzt: **eine Gruppe je Einrichtung** mit ihren vier Terminals, als
Vorgabegruppe. Nicht je *Standort* — eine Gruppe liegt nach FA-GER-050 in genau
einer Einrichtung; „Standort Nord" mit den Terminals dreier Praxen wäre
technisch keine Gruppe, sondern drei.

Beim ersten Versuch scheiterte der **gesamte** Demodatensatz, weil der Name
„Alle Geräte" in einer Einrichtung schon vergeben war: Der eindeutige
Namensindex warf, die Transaktion rollte zurück. Eine vorhandene Gruppe wird
jetzt verwendet statt verdoppelt — und behält ihr fehlendes Demo-Kennzeichen,
damit das Entfernen der Demodaten sie nicht mitnimmt.

### Geräte fielen still durch

Am laufenden Stand meldete der Agent vier Terminals, von denen **keines**
erfasst wurde. Die Einrichtung hatte eine Gruppe „Alle Geräte", aber ohne
Vorgabe-Kennzeichen; das Anlegen der Vorgabegruppe scheiterte am Namensindex,
`ON CONFLICT` schluckte es, und jedes gemeldete Gerät fiel durch.

Gefunden hat es die Warnung, die beim vorigen Umbau eingebaut wurde:

```
Geraet KT-EMPFANG-01 konnte der Einrichtung Kardiologie Nord nicht zugeordnet werden.
```

> Genau dafür war sie da. Ohne sie hätte niemand bemerkt, dass vier Terminals
> im Protokoll stehen, aber in keiner Geräteliste.

Behoben: Ist der Name belegt, wird die **älteste** vorhandene Gruppe der
Einrichtung zur Vorgabe erklärt — deterministisch, und es trifft die Absicht:
Wer eine Gruppe angelegt hat, will Geräte dort haben.

### Anzahl der Agenten und Version

Vorgabe des PO. Der Kopf der Seitenleiste zeigte „Agent verbunden" — bei einer
Serviceorganisation mit dreißig Standorten sagt das nichts. Jetzt: **„3 von 4
Agenten verbunden"** und die laufende Version.

„Verbunden" heißt: in den letzten zwei Minuten gemeldet. Der Grundtakt liegt bei
10 s und das längste Prüfintervall im Betrieb bei 60 s — zwei Minuten lassen
einen ausgefallenen Takt zu, ohne einen abgehängten Agenten als verbunden
auszuweisen.

Die Version stand vorher auf „unbekannt", weil das Manifest sie nicht enthielt.
Im Entwicklungslauf ohne Manifest steht jetzt „Entwicklungsstand" statt einer
erfundenen Nummer: **Eine Versionsangabe, die nicht stimmt, ist schlimmer als
keine** — nach ihr wird gefragt, wenn etwas nicht funktioniert.

---

## Lehre

Das Refactoring hatte 20 maschinelle Prüfpunkte hinterlassen, und alle waren
grün. Trotzdem fand eine Durchsicht sechs echte Mängel.

> Ein Werkzeug prüft Einheitlichkeit. Ob etwas **bedienbar** ist, sieht nur
> jemand, der es benutzt.

Umgekehrt gilt aber auch: Von den sechs Befunden ließen sich vier nachträglich
in Prüfpunkte übersetzen. Die bleiben jetzt.
