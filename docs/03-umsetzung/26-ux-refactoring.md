# 26 — UX-Refactoring: Einheitlichkeit erzwingen statt pflegen

**Stand:** 09.08.2026 · **Anlass:** Kritik des PO an der Oberfläche

---

## Der Auftrag

> „Abstände der Kacheln sind unregelmäßig, Bedienbuttons sind unnötig wenn ich
> eine Zeile drücken kann. Sind alle Listen sortierbar und durchsuchbar. Sind
> die Anordnung der Buttons richtig in ihrer Position. Sind auf jedem Button
> auch Tooltips die den Button beschreiben?"

## Vermessen statt begutachtet

Der erste Schritt war nicht das Umbauen, sondern das Zählen. Über alle 14
Seiten hinweg:

| Befund | vorher | nachher |
|---|---:|---:|
| Bedienelemente ohne Kurzhinweis | **142 von 154** | **0 von 141** |
| klickbare Tabellenzeilen | **0 von 97** | **57** |
| Listen mit Sortierung | 1 von 6 | 4 von 4 mit Bedarf |
| Listen mit Suche | 2 von 6 | 4 von 4 mit Bedarf |
| verschiedene Abstandswerte | **37** | **6** (ein Raster) |
| Abstände in Inline-Styles | 12 | 0 |

## Die Ursache war nicht Nachlässigkeit

142 fehlende Kurzhinweise entstehen nicht dadurch, dass jemand 142-mal
unaufmerksam war. Sie entstehen, weil es **keinen gemeinsamen Baustein gab**:
Jede Seite baute ihre Knöpfe und Tabellen selbst, also wich jede ein wenig ab.

> Einheitlichkeit, die von Sorgfalt abhängt, zerfällt mit jeder neuen Seite.

Deshalb war die Antwort nicht, 142 `title`-Attribute nachzutragen, sondern
`Bedienelemente.java` einzuführen — und dort ist **der Kurzhinweis
Pflichtparameter**. Ein Knopf ohne Erklärung lässt sich damit nicht bauen. Das
erledigt der Compiler, nicht die Sorgfalt. Dieselbe Bauweise wie beim
`Geltungsbereich` in den Abfragen und der Einrichtung in `pruefe()`.

Die Klasse weist zusätzlich **Echos** ab: `title="Löschen"` auf einem Knopf
„Löschen" ist kein Hinweis, sondern eine doppelte Screenreader-Ansage.

## Die Abstände

37 Werte zwischen `.1rem` und `2.2rem`, jeder nach Augenmaß gesetzt. Nebeneinander
atmeten Kacheln unterschiedlich weit — sichtbar unruhig, ohne dass man den Grund
benennen konnte.

Jetzt gilt eine Vierer-Reihe: `--r1` bis `--r6` (4/8/12/16/24/32 px). Sie ist
nicht schöner als andere Werte, aber sie ist **eine**, und Gleichmaß entsteht
nur aus Wiederholung. Darüber liegen drei abgeleitete Größen, die überall gleich
sein müssen: `--karte-x`, `--karte-y`, `--zelle-y`.

**Der sichtbarste Einzelfehler** war ein anderer: Die Kachel „Brauchen
Aufmerksamkeit" hat eine zweizeilige Überschrift, ihre Nachbarn eine einzeilige.
Also stand ihre Zahl tiefer als die der anderen. `grid-auto-rows:1fr` plus
`margin-top:auto` auf der Zahl legt alle Zahlen auf eine Linie.

## Die klickbaren Zeilen

Bis zum Umbau trug jede Zeile einen „Öffnen"-Knopf. Bei 25 Zeilen sind das
25 Knöpfe, die alle dasselbe sagen — Lärm, der das Auge von den Daten abzieht,
und ein zusätzliches Ziel, das getroffen werden will.

Jetzt öffnet die ganze Zeile. **Barrierefrei bleibt das nur mit einem echten
Link in der ersten Zelle:** Er trägt den Namen, ist mit der Tastatur erreichbar,
und ein Screenreader liest „Link: Kardiologie Nord" statt einer stummen Fläche.
Der Zeilenklick ist reine Mausbequemlichkeit obendrauf — fällt JavaScript aus,
funktioniert die Liste unverändert.

Drei Fälle bleiben ausdrücklich unberührt: Klicks auf Bedienelemente in der
Zeile (sonst löste „Löschen" zugleich das Öffnen aus), Klicks mit Sondertaste
oder mittlerer Maustaste (neuer Tab), und Klicks, die eine Textauswahl beenden
(wer eine Kennung markiert, will sie kopieren).

## Sortierung und Suche

| Liste | Sortierung | Suche |
|---|---|---|
| Gerätegruppen | 4 Spalten, im Speicher | Bezeichnung, Einrichtung |
| Kartenterminals | 6 Spalten, in der Datenbank | Volltext über Bestandsfelder |
| Konnektoren | 4 Spalten, im Speicher | Bezeichnung, Einrichtung, Adresse |
| Protokoll | Zeitrichtung | **Gerätekennung** |
| Konten | serverseitig geblättert | vorhanden |

**Wo im Speicher sortiert wird, ist das ehrlich** — diese Listen werden
vollständig angezeigt, es gibt keine Blätterung, die eine Sortierung über nur
die sichtbaren 25 Zeilen zur Lüge machen könnte. Bei den 5.000 Kartenterminals
ist das anders; dort sortiert die Datenbank.

**Das Protokoll sucht bewusst nur über die Gerätekennung**, nicht über den
Beschreibungstext. Auf `geraet` liegt ein Index, auf `text` nicht. Eine
Volltextsuche über Millionen Zeilen ohne Index wäre ein Suchfeld, das die
Oberfläche anhält — angeboten, aber unbenutzbar.

## Anordnung der Knöpfe

Eine Regel, damit Knöpfe nicht auf jeder Seite woanders stehen:

- **Die Haupthandlung steht rechts** am Ende der Reihe — dort endet die
  Lesebewegung.
- Nebenhandlungen links davon, nach Häufigkeit.
- **Destruktives wird abgesetzt** (`margin-left:auto` in der Zeilenaktion).
  Der häufigste Fehlgriff in Oberflächen ist der Nachbarknopf.

Zwei Knöpfe haben dabei ihren Platz gewechselt: „Ermittelte Geräte ansehen"
stand allein in einem Absatz unter dem Seitenkopf und sah aus wie vergessen —
jetzt steht er im Kopf bei den anderen. Und der „Speichern"-Knopf je
Gruppenzeile ist ganz verschwunden: Das Intervallfeld speichert, sobald es den
Fokus verlässt.

## Was noch verschwunden ist

Die **Selbstaktualisierung der Gruppenliste alle 10 Sekunden**. Eine
Verwaltungsliste, die sich unter der Hand austauscht, verliert Tastaturfokus und
halb getippte Eingaben. Gruppen ändern sich ohnehin nur durch eine Handlung.

## Nachweise

`pruefungen/oberflaeche-pruefen.sh`, 19 Prüfpunkte, eingebunden in den
Hauptlauf. Er misst nicht Schönheit, sondern Einheitlichkeit — und hält damit
fest, was sonst mit der nächsten Seite wieder zerfiele:

- jedes Bedienelement trägt einen Kurzhinweis, und keiner ist ein Echo
- Listen haben klickbare Zeilen, und daneben keinen „Öffnen"-Knopf mehr
- Sortierung **ändert die Reihenfolge tatsächlich** (nicht nur: wird angeboten)
- Suche **schränkt die Liste ein**, und ein leeres Ergebnis erklärt sich
- Abstände kommen aus dem Raster, auch in den Java-Seiten
- jede destruktive Aktion fragt nach

Zusätzlich unverändert grün: axe-core über 17 Seiten ohne Befund,
Kontrastprüfung über dem Grenzwert.

## Grenzen dieser Prüfung

Ein Werkzeug misst Einheitlichkeit, nicht Angemessenheit. Ob ein Kurzhinweis
fachlich richtig ist, ob sich die Knopfanordnung richtig anfühlt und wie die
Seite bei 5.000 Geräten wirkt, liest nur ein Mensch.

**Offen und bewusst so belassen:** Die Konnektorliste hat keine Blätterung. Bei
den im Mengengerüst genannten 1.000 HSK-Instanzen überträgt die Seite 1.000
Zeilen. Das ist nicht die Sortierung, sondern die fehlende Blätterung — im
Umsetzungsstand vermerkt, statt hier halb gelöst.
