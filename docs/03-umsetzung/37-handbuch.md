# 37 — Das Handbuch in der Oberfläche

**Stand:** umgesetzt (13.08.2026) · **Anforderung:** `FA-DOK-010`
· **Vorgabe:** PO, 13.08.2026

---

## Der Auftrag

> „Ich möchte die Anwenderdokumentation mit in die Weboberfläche des
> PIN-Roboter als eigenen Punkt aufnehmen. Dafür brauchen wir ein umfangreiches
> technisches Dokument, das die Architektur, das System und die Bedienung
> beschreibt. […] Es soll ein interaktives Dokument mit guter Bedienung und
> durchsuchbar sein."

## Zwei Entscheidungen vorweg

**Im Produkt, nicht als Beilage.** Ein Handbuch, das man erst suchen muss, wird
nicht gelesen. In einer Praxis unter Zeitdruck entscheidet sich in Sekunden, ob
jemand nachschlägt oder rät. Der zweite Grund wiegt schwerer: Ein beiliegendes
PDF beschreibt nach der ersten Aktualisierung eine andere Software als die, die
läuft. Dieses Handbuch liegt im selben Quellbaum wie das Verhalten, das es
beschreibt, und wird mit ihm zusammen ausgeliefert.

**Gegliedert nach Leserkreis, nicht nach Systemteil.** Hier lesen drei
verschiedene Menschen:

| Teil | Für wen | Inhalt |
|---|---|---|
| **Verstehen** | wer entscheidet, ob eingesetzt wird | Problem, Verfahren, Aufbau, Mandanten, Sicherheit |
| **Bedienen** | Personal in der Praxis | erste fünf Minuten, Lage, Alarme, PINs, Gruppen, Protokoll |
| **Betreiben** | Administrator, Dienstleister | Inbetriebnahme, Agenten, Sicherung, Störungen, Begriffe |

Eine Gliederung nach Systemteilen („Zentrale", „Agent", „Datenbank") wäre für
uns bequemer und für jeden Leser falsch: Niemand hat eine Frage zu einem
Systemteil, alle haben Fragen zu ihrer Aufgabe.

## Die Suche

Serverseitig. Zwei Gründe:

1. **Sie funktioniert ohne JavaScript.** Barrierefreiheit ist kein Zusatz
   (WCAG 2.2 AA), und die Suche ist der wichtigste Zugang zu einem Handbuch.
2. Sie zwingt nicht dazu, den gesamten Text jeder Seite mitzuladen.

Mit JavaScript reagiert dasselbe Feld beim Tippen und tauscht nur den
Inhaltsbereich. Möglich macht das `hx-select`: Der Server liefert **immer** die
vollständige Seite, HTMX nimmt sich daraus den Ausschnitt. Damit gibt es
**einen** Weg statt zweier, die auseinanderlaufen können — das Muster ist im
Projekt schon bei der Geräteliste erprobt.

Zwei Eigenschaften machen den Unterschied zwischen einer Suche und einer Liste:

- **Titeltreffer wiegen schwerer** als Fundstellen im Fließtext. Wer
  „Datensicherung" sucht, will das Kapitel, das so heißt.
- **Jeder Treffer zeigt seine Umgebung**, mit hervorgehobener Fundstelle. Eine
  Trefferliste ohne Kontext zwingt dazu, jeden Eintrag zu öffnen.

Kein Suchindex, keine Bibliothek: Es sind sechzehn Kapitel. Mehr Technik, als
der Inhalt trägt, wäre eine Abhängigkeit für ein Problem, das nicht existiert.

## Die Zeichnungen

Fünf eingebettete Vektorgrafiken: der Kreislauf aus Prüfen und Freischalten,
die Zustände einer SMC-B, der Aufbau aus Zentrale und Agent, die
Organisationshierarchie und der Weg der PIN.

**Eingebettet, nicht nachgeladen.** Zentrale und Agent stehen in Netzen ohne
Internetzugang; ein fehlendes Bild ist schlimmer als keines. Sie nehmen die
Farbtokens der Oberfläche an, skalieren, und ihr Text ist echter Text — er
lässt sich markieren, vergrößern und vorlesen. Jede trägt `role="img"`, eine
Beschreibung, die dasselbe sagt wie das Bild (nicht „Diagramm", sondern der
Inhalt), und eine Bildunterschrift, die die Aussage trägt.

## Gestaltung

Alle Farben, Abstände und Radien kommen aus den vorhandenen Tokens. Das
Handbuch sieht deshalb nicht „auch schön" aus, sondern wie derselbe Bau.

Zwei Dinge sind eigens für langes Lesen gemacht:

- **Das Verzeichnis läuft mit** (`position: sticky`). Wer in Kapitel 14 liest,
  soll nicht scrollen müssen, um zu sehen, wo er ist.
- **Die Zeilenlänge ist auf 68 Zeichen begrenzt.** Darüber verliert das Auge
  beim Zeilenwechsel den Anschluss — auf einem breiten Schirm wird ein Text
  sonst unlesbar, gerade weil Platz da ist.

Auf dem Tablet wandert das Verzeichnis nach oben und wird gedrängt dargestellt;
die Kurzbeschreibungen entfallen dort, damit das Kapitel noch sichtbar beginnt.
Für den Druck wird die Navigation ausgeblendet.

## Was gemessen wurde

| | Ergebnis |
|---|---|
| axe-core, 24 Seiten (inkl. vier Handbuchseiten) | ohne Befund |
| Kontraste, rechnerisch | alle Farbpaare über dem Grenzwert |
| Textüberlappung in den fünf Zeichnungen, im Browser gemessen | keine |
| Tablet (768 px) | kein seitliches Rollen, eine Spalte |
| Alle 16 Kapitel abgerufen | HTTP 200, kein unaufgelöster Platzhalter |

## Nachgewiesen

`HandbuchTest` (16 Prüfungen): eindeutige Kennungen, jeder Teil besetzt, keine
unaufgelösten Platzhalter, **ausgeglichene HTML-Elemente**, **paarweise
geschlossene typografische Anführungszeichen**, jede Zeichnung mit
Beschreibung und Bildunterschrift, interne Verweise zeigen auf vorhandene
Kapitel, Suche findet und ordnet, Auszug enthält die Fundstelle, zu kurze
Eingaben suchen nicht, Blättern endet an den Rändern, die Trefferanzeige
escapet die Eingabe, die Hervorhebung behält die Schreibweise.

**Drei dieser Tests wurden durch Sabotage gegengeprüft** — kaputtes
Anführungszeichen, offenes `<div>`, Verweis ins Leere. Alle drei schlagen an.

### Warum ausgerechnet Anführungszeichen einen eigenen Test bekommen

Am 13.08.2026 ist mir dreimal derselbe Fehler passiert: ein deutsches Zitat mit
typografischem Anfang und **geradem** Ende — `„Wort"`. In Java schließt das
einen String, in einem HTML-Attribut zerlegt es die Seite. Beim Schreiben von
sechzehn Kapiteln entstanden davon neunzehn Stück auf einmal.

Ein Test, der zählt, ist die einzige Antwort, die nicht auf Sorgfalt setzt.
