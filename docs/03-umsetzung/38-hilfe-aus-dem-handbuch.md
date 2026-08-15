# 38 — Hilfe kommt aus dem Handbuch

**Stand:** umgesetzt (14.08.2026), Oberfläche vollständig umgestellt und
Handbuchabdeckung gemessen (15.08.2026)
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

### Ein „display" an der falschen Stelle

**Gemeldet am 15.08.2026:** „Wenn ich die Info schließe, landet sie auf der
Webseite." Der Erklärtext stand nach dem Schließen als gewöhnlicher Kasten
mitten in der Seite, ohne Abdunklung, an der falschen Stelle.

Die Ursache ist eine Zeile CSS. Der Browser blendet ein geschlossenes
`<dialog>` über seine **eigene** Regel aus:

```css
dialog:not([open]) { display: none }   /* Regel des Browsers */
```

Autorenregeln schlagen Browserregeln. Ein schlichtes

```css
.hilfefenster { display: flex }
```

hebt die Ausblendung also auf — und zwar in **jedem** Zustand. Der Dialog
funktioniert weiter (`showModal()` öffnet ihn modal, `close()` schließt ihn),
nur sichtbar bleibt er trotzdem.

Die Behebung ist, das `display` an `[open]` zu hängen:

```css
.hilfefenster { /* Maße, Rahmen, Farben */ }
.hilfefenster[open] { display: flex; flex-direction: column; }
```

**Im Browser gemessen** (15.08.2026, `getComputedStyle`):

| Zustand | mit der alten Regel | jetzt |
|---|---|---|
| geschlossen | `flex` — steht in der Seite | `none` |
| geöffnet | `flex`, `:modal` trifft zu | `flex`, `:modal` trifft zu |
| nach dem Schließen | `flex` — bleibt stehen | `none` |

Der Test `geschlossenesFensterIstUnsichtbar` hält das fest: Er liest die
CSS-Datei und lässt keine `display`-Angabe an einem Fenster-Selektor ohne
`[open]` durch. **Durch Sabotage gegengeprüft** — und dabei gleich verbessert:
Der erste Entwurf zitierte einen Kommentar als Selektor, weil er Kommentare
nicht entfernt hatte. Eine Fehlermeldung, die auf die falsche Zeile zeigt, ist
kaum besser als keine.

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

**Umgestellt am 15.08.2026.** In der Oberfläche steht damit **kein
Erklärabsatz über 150 Zeichen** mehr; gemessen mit demselben Ausdruck, der die
Kandidaten gefunden hat: von 24 auf 0.

Was an den Feldern geblieben ist, ist der feldbezogene Rest — zwei Beispiele:

| vorher | jetzt |
|---|---|
| „Rechnername oder IP-Adresse, wahlweise mit Port. Die Erreichbarkeit wird beim Speichern geprüft; ein noch nicht angeschlossener Konnektor lässt sich trotzdem erfassen." | „Rechnername oder IP-Adresse, wahlweise mit Port." + Knopf |
| „Ohne Konnektor wird das Gerät erfasst und verwaltet, nimmt aber nicht an der PIN-Automatik teil: Die Verifikation löst der Konnektor aus. …" | „Ohne Konnektor nimmt das Gerät **nicht an der PIN-Automatik teil**." + Knopf |

Zwei Stellen behalten bewusst eine dynamische Angabe, weil sie zum Fall gehört
und nicht ins Handbuch passt: „Betrieben von **X**" beim fremden Konnektor und
„Die Einrichtung **Y** lässt sich nicht ändern" bei einer Gerätegruppe. Der
Grund dahinter steht im Handbuch, die Tatsache am Feld.

**Ein Abschnitt kam beim Umstellen dazu:** `organisationsebenen`. Der Text an
der Maske erklärte nicht nur die Betriebsstätte, sondern die ganze Hierarchie —
Trägergesellschaft, Standort, Leistungserbringer, Fachbereich und die Regel,
dass eine Einzelpraxis zwei Ebenen braucht. Das stand im Handbuch nirgends.
Ohne den neuen Abschnitt wäre beim Kürzen wieder Wissen verschwunden.

## Neue Handbuchabschnitte

Für die ersetzten Texte sind fünf Abschnitte dazugekommen, die es vorher nicht
gab: **Aufrufkontext**, **Webadmin-Schnittstelle**, **Programmablage**,
**Betriebsstätte** und die Erweiterung zu **Einrichtungspaket** und
**Sammelfenster**. Zusammen mit den fünf Abschnitten zur Agenten-Aktualisierung
sind es elf.

Das Handbuch ist dadurch nicht nur Nachschlagewerk, sondern die **einzige**
Stelle, an der das Produkt sich erklärt.

## Der Info-Knopf als Vollständigkeitsprüfer

> „Jetzt bitte noch an allen Funktionen, Konfigurationen und Statusfenstern ein
> „i" mit Handbucheintrag. Damit kannst du prüfen, ob auch alles im Projekt eine
> Entsprechung und Beschreibung im Handbuch hat." — PO, 15.08.2026

Das dreht die Funktion um. Der Knopf ist nicht mehr nur eine Bequemlichkeit für
den Anwender, sondern ein **Messwerkzeug**: Wo kein Abschnitt existiert, lässt
sich kein Knopf setzen — und die Lücke im Handbuch fällt sofort auf, statt
jahrelang niemandem zu begegnen.

`pruefungen/handbuchabdeckung.py` misst das. Es liest die Tafelüberschriften
aus dem Quelltext, sucht je Tafel ein `Bedienelemente.info(…)` und prüft jede
gefundene Kennung gegen das Handbuch:

```
pruefungen/handbuchabdeckung.py            Bericht
pruefungen/handbuchabdeckung.py --offen    nur die Lücken, für die Arbeit
```

**Warum die Überschrift und nicht das Feld:** Ein Knopf je Eingabefeld wäre eine
Nadelwand. Die Tafel ist die Einheit, in der ein Mensch denkt — „was ist
Datensicherung", nicht „was bedeutet dieses eine Feld".

Zwei Dinge misst das Skript neben der Quote mit:

- **Dubletten.** Dieselbe Tafel erscheint oft zweimal im Code — mit Daten und
  ohne. Sie zählt als erledigt nur, wenn **beide** Fassungen den Knopf tragen.
- **Tote Knöpfe.** Eine Kennung, die es im Handbuch nicht gibt, ist ein Fehler
  und lässt den Lauf scheitern (Rückgabewert 1).

Auch **Seitenköpfe** zählen mit. Nicht jede Hauptfunktion hat eine Tafel: Das
Protokoll besteht aus Filterleiste und Tabelle. Dafür nimmt `seitenkopf(…)` eine
vierte Angabe, die Hilfe-Kennung — sonst bliebe eine Hauptfunktion ohne
Erklärung.

### Was die Messung gefunden hat

**Stand: 42 von 42 Tafeln (100 %), 51 Handbuchabschnitte.** Der Weg dahin hat
zwei echte Lücken aufgedeckt:

| Lücke | Was fehlte |
|---|---|
| **Kapitel „Konten und Rollen"** | Die gesamte Benutzerverwaltung war im Handbuch nicht beschrieben — Rollen, Einladungen, die einmalige Anzeige des Einladungslinks, der Verlust des zweiten Faktors. |
| **Abschnitt „Filtern und ausgeben"** | Das Protokoll hatte kein Kapitel zu seiner eigenen Bedienung, obwohl es die Sicht ist, die bei einer Rückfrage zuerst gebraucht wird. |

Dazu 21 weitere Abschnitte für Tafeln, die zwar erklärt waren, aber nur
mündlich in der Oberfläche: `was-zu-tun-ist`, `geraete-live`,
`alarme-bearbeiten`, `bestandslisten`, `bestandsabgleich`, `geraetezugang`,
`karten-im-terminal`, `zeitstrahl`, `mailserver`, `benachrichtigungsregeln`,
`einheit-aendern`, `einheit-inhalt`, `geraetegruppen`, `pins-neu-hinterlegen`,
`systemtest`, `sicherung-erstellen`, `sicherung-wiederherstellen` und weitere.

**Nicht mitgezählt:** Das Handbuch erklärt sich nicht selbst, und die
Anmeldeseite steht vor jeder Anmeldung — dort gibt es kein Handbuch.

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

**Abdeckungsprüfung durch Sabotage gegengeprüft** (15.08.2026): Kennung am
Seitenkopf des Protokolls auf `gibt-es-nicht` geändert — das Skript meldet
„Protokoll: Kennung „gibt-es-nicht" gibt es im Handbuch nicht" und endet mit 1.

axe-core über 26 Seiten ohne Befund, Kontraste bestanden (15.08.2026). Der
Zeitstrahl ist im Prüfaufbau nicht erreichbar — er braucht ein Gerät mit
Verlauf; das meldet der Bericht als „nicht geprüft", nicht als bestanden.
