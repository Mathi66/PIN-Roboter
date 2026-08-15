# 38 — Hilfe kommt aus dem Handbuch

**Stand:** Mechanik umgesetzt, Anwendung begonnen (14.08.2026)
· **Anforderung:** `FA-DOK-020` · **Vorgabe:** PO, 14.08.2026
· baut auf [Umsetzung 37](37-handbuch.md)

---

## Der Auftrag

> „Ich möchte an allen Kacheln und Eingabefeldern ein hübsches Info-Button-Feld,
> bei dem ein Popup mit dem jeweiligen Handbucheintrag aufgeht. Ich möchte die
> Info dann direkt aus dem Handbuch haben, nicht dass wir die Inhalte an zwei
> Stellen pflegen müssen. Damit können alle Informationstexte in der Oberfläche
> entfallen, sodass es clean aussieht."

## Das eigentliche Problem

Der sichtbare Anlass ist die Optik: Eine Seite mit acht Feldern trug acht
Absätze, die beim **ersten** Mal helfen und danach jeden Tag im Weg stehen. Wer
die Oberfläche kennt, liest sie ohnehin nicht mehr.

Schwerer wiegt der unsichtbare Teil. Derselbe Sachverhalt stand **zweimal** im
Produkt — als Absatz neben dem Bedienelement und noch einmal im Handbuch. Zwei
Fassungen laufen auseinander, sobald sich etwas ändert, und niemand merkt es,
weil beide für sich plausibel bleiben. Genau dieses Muster hat in diesem Projekt
schon dreimal zugeschlagen: die Fehlerübersetzung, die es im Agenten gab und in
der Zentrale nicht; der Manifest-Eintrag, den nur die Zentrale hatte; die
Versionsprüfung, die zwei Abbilder kennen musste und eines abfragte.

## Die Lösung: markierte Abschnitte

Im Handbuchtext markiert `<section data-hilfe="…">` die Stellen, die auch als
Kurzhilfe taugen:

```html
<h2>Der Aufrufkontext des Konnektors</h2>
<section data-hilfe="aufrufkontext">
  <p>Jeder Aufruf an den Konnektor trägt drei Angaben …</p>
</section>
```

Die Oberfläche setzt daneben einen Knopf:

```java
Bedienelemente.info("aufrufkontext", "den Aufrufkontext")
```

Fertig. **Wer das Handbuch ändert, ändert damit jede Hilfe, die daraus liest.**

Auch der **Titel** des Fensters ist keine zweite Pflegestelle: Er ist die
Überschrift, unter der der Abschnitt im Handbuch steht.

## Der Knopf

Klein, rund, in gedämpfter Farbe, direkt neben dem, was er erklärt. Er soll
auffindbar sein, wenn man ihn sucht, und übersehbar, wenn nicht. Erst beim
Überfahren nimmt er den Akzent an.

**Er ist ein echter Link.** Ohne JavaScript öffnet er das Kapitel im Handbuch —
eine Erklärung darf nicht an einer Browsereinstellung hängen. Mit JavaScript
wird daraus ein Fenster.

## Das Fenster

Ein natives `<dialog>`. Das bringt mit, was man sonst nachbauen müsste: Esc
schließt, der Fokus bleibt darin gefangen, dahinter ist nichts bedienbar, und
ein Screenreader kündigt es als Dialog an.

Drei Teile: **woher** der Text stammt (Kapitel mit Symbol), der Text selbst, und
ein Weg **ins volle Kapitel**. Der letzte ist wichtig — eine Kurzhilfe
beantwortet die Frage von jetzt, aber manchmal ist die eigentliche Frage eine
größere.

Die Breite ist auf 46 rem begrenzt, nicht auf den verfügbaren Platz: Ein
Erklärtext über 900 px ist unlesbar, gerade weil Raum da ist.

### Der Fokus, dreifach abgesichert

Beim Schließen muss der Fokus dorthin zurück, wo er herkam. Sonst landet er am
Seitenanfang, und wer mit der Tastatur arbeitet, sucht seine Stelle wieder — bei
einer Hilfe besonders ärgerlich, denn man wollte ja weitermachen.

Der naheliegende Weg wäre ein Listener auf `close`. **Der feuert in dieser
Engine nicht zuverlässig** — am 14.08.2026 gemessen: `dialog.close()` schließt,
das Ereignis bleibt aus. Deshalb setzen alle drei Wege, die ein Mensch geht, den
Fokus selbst zurück: Schließen-Knopf, Esc (`cancel`) und Klick daneben.

## Was bleibt und was geht

Nicht jeder kurze Text ist Ballast. Die Trennlinie:

| bleibt am Feld | wandert ins Handbuch |
|---|---|
| Format, Grenze, Pflichtangabe | warum es die Einstellung gibt |
| welcher Weg gerade offen ist | was bei falscher Wahl passiert |
| „0 bedeutet: sofort einzeln" | „Fällt ein Konnektor aus, entstehen hunderte Alarme …" |

Ein Beispiel aus der Benachrichtigung: Der Absatz über das Sammelfenster war
310 Zeichen lang und erklärte, warum es die Einstellung braucht. Übrig blieb
ein Satz — was `0` bedeutet. Der Rest steht jetzt im Kapitel „Alarme“ und
erscheint auf Knopfdruck.

## Stand der Anwendung

**Fertig:**

| Seite | ersetzt |
|---|---|
| Instanz → Produktversion | Token-Erklärung (ADR-Verweis, „liest nur") |
| Instanz → Selbsttätige Aktualisierung | zwei Absätze zur Weitergabe |
| Instanz → Standort-Agenten | Absatz zur Protokollversionsspanne |
| Benachrichtigung | Absatz zum Sammelfenster |
| Konnektor anlegen/bearbeiten | zwei Absätze zum Aufrufkontext |
| Informationsmodell | zwei Absätze zur Webadmin-Schnittstelle |

**Die Handbuchabschnitte für die übrigen Stellen sind geschrieben** (15.08.2026).
Zwölf neue Abschnitte decken die verbliebenen 19 Erklärtexte ab:

| Abschnitt | Kapitel | ersetzt Text in |
|---|---|---|
| `vorgabegruppe` | Gerätegruppen | Gruppenverwaltung (2 Texte) |
| `gruppe-einrichtung` | Gerätegruppen | Gruppenverwaltung |
| `pin-hinterlegen` | PINs hinterlegen | Bestandsseiten (2 Texte) |
| `pin-aendern` | PINs hinterlegen | Bestandsseiten |
| `bezeichnung-eindeutig` | Organisation | Organisationsverwaltung |
| `mail-verschluesselung` | Alarme | Benachrichtigung |
| `nur-smcb` | Verfahren | Bestandsseiten |
| `konnektor-adresse` | **Bestand pflegen** (neu) | Bestandsseiten |
| `konnektor-mitnutzung` | Bestand pflegen | Bestandsseiten (2 Texte) |
| `geraet-ohne-konnektor` | Bestand pflegen | Bestandsseiten |
| `firmwarestand` | Bestand pflegen | Bestandsübersicht |
| `demodaten` | Inbetriebnahme | Instanz-Seite (2 Texte) |

Dazu ein **neues Kapitel „Bestand pflegen"** im Teil *Betreiben* — Konnektoren,
Kartenterminals und Firmware hatten bisher keinen Ort im Handbuch, obwohl
gerade dort die meisten Erklärtexte hingen.

**Noch offen:** Das Ersetzen in der Oberfläche selbst. Solange beides
nebeneinander steht, gibt es die Doppelung, die diese Funktion abschaffen soll —
allerdings jetzt mit einer Quelle, aus der die Oberfläche schöpfen kann.

## Neue Handbuchabschnitte

Für die ersetzten Texte sind fünf Abschnitte dazugekommen, die es vorher nicht
gab: **Aufrufkontext**, **Webadmin-Schnittstelle**, **Programmablage**,
**Betriebsstätte** und die Erweiterung zu **Einrichtungspaket** und
**Sammelfenster**. Zusammen mit den fünf Abschnitten zur Agenten-Aktualisierung
sind es elf.

Das Handbuch ist dadurch nicht nur Nachschlagewerk, sondern die **einzige**
Stelle, an der das Produkt sich erklärt.

## Nachgewiesen

`HandbuchTest` (21 Prüfungen, davon 5 neu):

- **Jeder Info-Knopf im Code findet seinen Abschnitt.** Der Test liest den
  Quelltext nach `Bedienelemente.info("…")` ab und prüft jede Kennung gegen das
  Handbuch. Eine Liste von Konstanten wäre eine zweite Pflegestelle gewesen —
  genau das, was diese Funktion abschafft.
- Jeder Abschnitt liefert Inhalt, Titel und Kapitelbezug und ist nicht
  verschachtelt (sonst schnitte die Extraktion am falschen `</section>` ab).
- Unbekannte Kennungen liefern nichts statt irgendetwas.
- Das Fenster nennt Herkunft und führt ins Kapitel.
- Der Knopf trägt ein echtes `href` und eine Beschriftung.

**Der erste Test wurde durch Sabotage gegengeprüft**: Kennung im Code auf
`gibt-es-nicht` geändert — er schlägt an und nennt Datei und Kennung.

Systemnachweis `FA-DOK-020`: Alle Info-Knöpfe von dreizehn ausgelieferten
Seiten abgerufen und einzeln geprüft; derselbe Satz im Fenster und im Kapitel;
ohne JavaScript liefert derselbe Pfad die Handbuchseite; eine unbekannte
Kennung führt trotzdem ins Handbuch.

axe-core über 24 Seiten ohne Befund, Kontraste bestanden.
