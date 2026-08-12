# UX-Konzept — Neuausrichtung der Oberfläche

**Stand:** 07.08.2026 · **Anlass:** Rückmeldung des PO nach der ersten Vorführung
**Prototyp:** [`docs/06-ux/prototyp.html`](06-ux/prototyp.html) — im Browser öffnen
**Status:** Konzept, zur Abnahme vorgelegt. Umsetzung noch nicht begonnen.

---

## Die Rückmeldung

Der PO hat fünf Punkte benannt:

1. Bedienbarkeit und Optik sind nicht gut.
2. Bei Suchmasken muss man „Suchen" drücken — das soll beim Tippen geschehen.
3. Das Bearbeiten von Organisationen sieht nicht gut aus.
4. Man kann die Datenbeziehungen einer Organisation nicht sehen und sich nicht durchklicken.
5. Die Fenster wirken unruhig; Seiten werden immer länger, statt dass neue aufgehen.

Gewünscht: schwebende Boxen, Glaseffekt, modern.

Alle fünf Punkte treffen zu. Nachfolgend, was jeweils die Ursache ist und wie
das Konzept sie behebt.

---

## 1. Die Unruhe hat eine technische Ursache

Die Übersichtsseite steht in `Zentrale.java` so im Gerüst:

```html
<div hx-get="/teil/uebersicht" hx-trigger="every 3s" hx-swap="innerHTML">
```

Das tauscht **alle drei Sekunden den kompletten Seiteninhalt** aus. Bei jedem
Austausch können Scrollposition, Tastaturfokus und eine getroffene Auswahl
springen. Der Eindruck von Unruhe entsteht nicht durch die Gestaltung, sondern
weil sich die Seite tatsächlich ständig neu aufbaut.

**Neu:** Nur die Bereiche aktualisieren sich, die sich wirklich ändern — die
Alarmliste, die Zustandsmarken, die Kennzahlen. Jeder als eigener kleiner
Einschub mit eigenem Takt. Der Rahmen bleibt stehen.

Das ist unabhängig von der Optik und sollte auch dann gemacht werden, wenn das
Konzept sonst abgelehnt wird.

## 2. Suche filtert beim Tippen

Der Suchknopf verschwindet. Technisch mit HTMX:

```html
<input name="suche" hx-get="/teil/geraete" hx-trigger="input changed delay:250ms"
       hx-target="#geraeteliste" hx-sync="closest form:replace">
```

Die 250 Millisekunden sind kein Zögern, sondern notwendig: Bei 5.000 Geräten
wäre eine Serverabfrage pro Tastendruck spürbar. Gewartet wird ab dem
**letzten** Zeichen, nicht ab dem ersten — wer flüssig tippt, löst genau eine
Abfrage aus.

Die Trefferzahl steht daneben und wird per `aria-live="polite"` angesagt, damit
auch ein Screenreader-Nutzer mitbekommt, dass sich die Liste geändert hat.

## 3 und 4. Organisationen: eigene Seite mit allen Beziehungen

Die heutige Bearbeitung klappt eine Zeile in der Tabelle auf und schiebt drei
Formulare hinein. Das ist der Grund, warum es unaufgeräumt wirkt — und es
beantwortet die eigentliche Frage nicht.

**Neu:** Ein Klick auf eine Einheit öffnet ihre **eigene Seite**. Ganz oben
steht, was zu ihr gehört, als Kacheln zum Weiterklicken:

| | |
|---|---|
| 8 Kartenterminals | → Geräteliste, auf diese Einheit gefiltert |
| 7 SMC-B-Karten | → 6 verifiziert, 1 gesperrt |
| 6 Konten | → Kontenliste, davon 2 Administratoren |
| 1 Konnektor | → KoCo Konnektor |
| 3 Gerätegruppen | → Prüfintervalle 30–300 s |
| 412 Protokolleinträge | → Protokoll, auf diese Einheit gefiltert |

Darunter die wichtigsten Geräte und die letzten Vorgänge. Umbenennen und
Umhängen sind Knöpfe im Kopf der Seite, nicht Formulare in einer Tabellenzeile.

Damit ist die Frage „was hängt eigentlich an dieser Praxis?" mit einem Blick
beantwortet — und jede Antwort ist ein Absprungpunkt.

## 5. Neue Seiten statt wachsender Seiten

Jedes Aufklappen verschwindet: die `<details>`-Ausklapper im Bestand, die
Bearbeitungszeile in der Organisation, die eingebetteten Formulare.

An ihre Stelle treten **echte Seiten mit eigener Adresse**:

```
/bestand                       Liste
/bestand/terminal/42           ein Gerät
/organisation                  Struktur
/organisation/7                eine Einheit
```

Das hat drei Vorteile, die über die Optik hinausgehen: Die Seite bleibt kurz,
jede Ansicht ist als Lesezeichen speicherbar und verlinkbar, und der
Zurück-Knopf des Browsers tut, was man erwartet. Jede Detailseite trägt oben
einen sichtbaren Rückweg.

---

## Die Gestaltung

### Glasoptik mit garantiertem Kontrast

Der Wunsch nach Glaseffekt zieht gegen `NFA-USA-030` (WCAG 2.2 Level AA,
verbindlich): Transparente Flächen über wechselndem Hintergrund haben keinen
berechenbaren Kontrast, und ohne berechenbaren Kontrast lässt sich AA nicht
nachweisen.

**Die Regel, die beides zusammenbringt:**

> Unter jeder Glasfläche liegt eine **deckende** Farbe mit geprüftem Kontrast.
> Der Unschärfeeffekt ist Zugabe, nie Voraussetzung.

Konkret:

```css
.glas {                       /* Zusage: deckend, geprüft */
  background: var(--glas-fest);
  border: 1px solid var(--glas-kante);
}
@supports (backdrop-filter: blur(1px)) {
  .glas {                     /* Zugabe: Glas, wo der Browser es kann */
    background: var(--glas);  /* ≥ 76 % Deckkraft */
    backdrop-filter: blur(22px) saturate(150%);
  }
}
@media (prefers-reduced-transparency: reduce) {
  .glas { background: var(--glas-fest) !important; backdrop-filter: none !important; }
}
```

Dazu kommt: Der Hintergrund hinter dem Glas ist ein **ruhiger, kontrastarmer
Verlauf** — keine Fotos, keine harten Kanten. Damit bleibt der Textkontrast in
einem engen, berechenbaren Band, statt vom Zufall abzuhängen.

Wer Transparenz im Betriebssystem abgeschaltet hat, bekommt volle Deckung ohne
Nachfrage.

### Farbe

Die semantischen Farben — grün, amber, rot — werden **unverändert übernommen**.
Sie sind mit `pruefungen/barrierefreiheit/kontraste.mjs` gemessen; sie neu zu
erfinden hieße, diesen Nachweis wegzuwerfen.

Neu sind nur Grundflächen und Akzent:

| Rolle | Hell | Dunkel |
|---|---|---|
| Papier | `#e8ecf3` | `#0c1017` |
| Glas (deckend) | `#ffffff` | `#181f2d` |
| Text | `#101623` | `#e7ecf4` |
| Akzent | `#1e3a8f` | `#93b4ff` |

Die Neutralen haben einen leichten Blaustich in Richtung des Akzents — ein
reines Mittelgrau wirkt unentschieden. Der Akzent ist ein Königsblau, bewusst
weit weg von Grün, Amber und Rot: Er darf nie mit einer Zustandsfarbe
verwechselbar sein.

### Schrift

**Keine Webfonts.** Zwei Gründe: Die Anwendung läuft On-Premise ohne
Internetzugang, und „Seitenaufbau < 1 s im LAN" ist eine Vorgabe aus
Abschnitt 4 der CLAUDE.md. Stattdessen drei Rollen aus dem Systemstack:

- **Überschriften** — `system-ui`, Gewicht 650–700, `letter-spacing: -.025em`
- **Fließtext** — `system-ui`, 15 px / 1.55
- **Maschinenkennungen** — `ui-monospace` mit `tabular-nums`

Die dritte Rolle ist keine Spielerei: `KT-KARD-EMP` und
`80276001011000000017` sind Gerätekennungen und ICCSN. In einer
Proportionalschrift sind sie schwer zu vergleichen und schwer abzutippen.

### Zustand ist Form, nicht nur Farbe

Jede Zustandsmarke trägt einen Punkt, einen Text und eine Hintergrundfläche.
Farbe ist nie das einzige Unterscheidungsmerkmal. Die Kennzahlkarten tragen
zusätzlich einen Verlaufsbalken — „34 verifiziert" sagt weniger als „34 von 36,
davon 1 gesperrt".

### Bewegung

Genau ein Effekt: ein schwacher Lichtschein, der langsam hinter dem Glas
wandert (46 Sekunden je Richtung). Er macht das Glas als Glas erkennbar. Bei
`prefers-reduced-motion` steht er still.

Alles andere bewegt sich nicht. Eine Oberfläche, die in einer Praxis unter
Zeitdruck bedient wird, darf keine Aufmerksamkeit für sich selbst verlangen.

---

## Zur Technologiefrage

Der PO hat angemerkt, die Webtechnologien seien nicht modern.

Der altbackene Eindruck kam nicht von HTMX, sondern vom Gestaltungskonzept —
der Prototyp verwendet dieselbe Technologie und sieht anders aus. Ein Wechsel
auf React oder Vue würde:

- die Barrierefreiheitsnachweise verkomplizieren (axe gegen serverseitig
  erzeugtes HTML ist ungleich einfacher als gegen einen Komponentenbaum),
- einen Bau- und Bündelungsschritt in die Auslieferung bringen,
- die Ladezeit erhöhen statt senken,
- und die Zusage aus ADR-0009 berühren, dass genau **eine** Maske JavaScript
  braucht.

**Empfehlung: bei server-gerendertem HTML mit HTMX bleiben** (ADR-0006). Das
Konzept ist damit vollständig umsetzbar — der Prototyp ist der Beleg.

Sollte der PO das anders sehen, wäre das eine Architekturentscheidung und
gehört als eigener ADR festgehalten, der ADR-0006 ablöst.

---

## Was nachgewiesen ist

Der Prototyp wurde mit demselben Werkzeug geprüft wie die laufende Oberfläche:

```bash
docker run --rm -v "$PWD/pruefungen/barrierefreiheit":/p -v /tmp/pr-proto:/seiten:ro \
  -w /p node:22-alpine node pruefen.mjs /seiten
```

Ergebnis: **0 Befunde.** Ein Befund war vorhanden und wurde behoben — die
Kennzahlkarten hatten Balken und Fußtext neben `dt`/`dd` statt darin, was die
Definitionsliste für Hilfsmittel unlesbar macht.

**Noch nicht geprüft** und vor der Abnahme der Umsetzung nachzuholen:

- Kontraste rechnerisch gegen die neuen Farbwerte (`kontraste.mjs` prüft bisher
  `stil.css`, nicht den Prototyp)
- Tastaturbedienung im Ablauf — braucht einen echten Browser
- Verständlichkeit mit einem Screenreader — braucht einen Menschen

---

## Umsetzung in Stufen

Damit nicht alles gleichzeitig aufgerissen wird und jede Stufe für sich
abnehmbar bleibt:

| Stufe | Inhalt | Aufwand |
|---|---|---|
| 1 | Gezielte Teilaktualisierung statt Komplettaustausch alle 3 s | klein |
| 2 | Live-Suche in Bestand, Konten, Protokoll | klein |
| 3 | Neues Farb- und Flächenkonzept (`stil.css`), Seitenleiste statt Reiterband | mittel |
| 4 | Detailseiten: Terminal, Organisation, Konto — Aufklapper entfernen | mittel |
| 5 | Organisations-Detailseite mit allen Beziehungen | mittel |

Stufe 1 und 2 wirken sofort und sind unabhängig vom Rest. Sie sollten in jedem
Fall gemacht werden.

## Zur Entscheidung

1. **Gestaltung** — trägt die Richtung des Prototyps?
2. **Technologie** — bleibt es bei HTMX (Empfehlung) oder soll ein Wechsel
   geprüft werden?
3. **Reihenfolge** — Stufen 1–5 wie vorgeschlagen, oder anders priorisiert?


---

## Nachtrag 12.08.2026 — die Struktur als Bild

**Vorgabe des PO:** eine grafische Darstellung der Organisationsstruktur mit
Sinnbildern für die Entitäten und Geräte, „was Schönes fürs Auge und mit hoher
Usability".

### Warum eine zweite Sicht und nicht eine neue

Die Tabelle beantwortet gut: *Welche Einheiten gibt es?* Sie beantwortet
schlecht: *Wie hängt das zusammen, und wo klemmt es?* Seit
[ADR-0012](adr/0012-geraetehierarchie-nach-organisationsstruktur.md) sind fünf
Ebenen möglich; eine eingerückte Liste ist dann nicht mehr auf einen Blick zu
erfassen.

Umgekehrt ist die Tabelle das bessere Werkzeug, wenn man **sucht** statt
**versteht** — und für Screenreader und kleine Bildschirme die nüchternere
Darstellung. Beide bleiben deshalb erhalten, gleichwertig erreichbar über einen
Umschalter. Die gewählte Ansicht wird im Kekserl gemerkt, damit sie einen
Umweg über eine Detailseite überlebt.

### Was auf einer Karte steht

```
┌──────────────────────────────────────────────────────────────┐
│ [Sinnbild]  Hausarztpraxis Nordstadt              [Ampel]    │
│             Betriebsstätte · BSNR 729900001                  │
│             🖳 6 Kartenterminals  ▭ 1 Konnektor  👤 2 Konten  │
└──────────────────────────────────────────────────────────────┘
```

Die **Ampel gilt für den ganzen Teilbaum** und zeigt den *schlechtesten*
Zustand darin. Ein Mittelwert wäre gefährlich: Neunundneunzig gesunde Geräte
dürfen ein ausgefallenes nicht grün färben. Wer auf die oberste Karte schaut,
sieht damit, ob irgendwo im Haus etwas rot ist — ohne zu blättern.

Darunter hängen die **Konnektoren** und die **Kartenterminals** als Kacheln.
Der Konnektor steht zuerst, weil er das Bindeglied ist: Ohne ihn gibt es keine
PIN-Automatik, egal wie gesund die Terminals aussehen. Ein **mitgenutzter**
Konnektor ist gestrichelt umrandet und sagt es auch im Text — er gehört jemand
anderem, und wer das nicht sieht, sucht die Zugangsdaten an der falschen Stelle.

Bei den Terminals unterscheidet das Sinnbild, ob eine **SMC-B** darin steckt.
Nur diese Geräte nimmt die Automatik überhaupt in die Hand (`A_24080`) — sonst
wundert man sich über fünfunddreißig „stille" Terminals.

### Die Entscheidungen dahinter

**HTML und CSS, kein gezeichnetes Diagramm.** Ein SVG-Organigramm sieht auf
einem Bildschirm gut aus und versagt auf jedem anderen: Es bricht nicht um,
lässt sich nicht mit der Tastatur bedienen, skaliert nicht mit der
Schriftgröße, und ein Screenreader findet darin keine Reihenfolge. Die Karten
sind gewöhnliche Verweise in verschachtelten Listen; die Linien dazwischen
zeichnet CSS. Damit ist die Ansicht ohne Zusatzaufwand tastaturbedienbar,
vorlesbar und mit Strg+F durchsuchbar.

**Kein JavaScript zum Auf- und Zuklappen.** Tiefe Ebenen stecken in
`<details>` — einem Browserelement, das Tastatur und Screenreader von sich aus
beherrschen. Ab der dritten Ebene wird zugeklappt; wieviel darin steckt, steht
am zugeklappten Element, sonst klappt man blind auf.

**Grenzen werden genannt, nicht verschwiegen.** Ab dreizehn Geräten je Einheit
tritt an die Stelle der Kacheln ein Verweis in die Geräteliste — mit der
vollständigen Zahl darin. Ein stilles Abschneiden sähe aus wie Vollständigkeit.

**Eigene Symbolsammlung.** Alle Piktogramme liegen in `Symbole.java`: Raster
24×24, Strichstärke 1,7, `currentColor`, immer `aria-hidden`. Vorher standen
SVG-Pfade dort, wo man sie gerade brauchte — dieselbe Ursache wie bei den 142
fehlenden Kurzhinweisen. Keine Bilddateien und keine Icon-Schrift: Das Produkt
läuft on-premise ohne Internet, ein Symbol von einem fremden Server wäre
zugleich Datenabfluss und Ausfallrisiko.

**Farbe ist nie die einzige Aussage** (WCAG 1.4.1). Jede Ampel trägt ein eigenes
Sinnbild und einen Text; die Legende benennt die vier Zustände in Worten. Bei
`prefers-reduced-motion` entfällt das Anheben der Karte unter dem Zeiger.

### Was das nicht ist

Keine frei bewegliche Zeichenfläche mit Zoom und verschiebbaren Knoten. Das wäre
hübscher anzusehen und in einer Praxis unbedienbar: Wer unter Zeitdruck
nachsieht, warum ein Terminal nicht freigeschaltet ist, will nicht erst ein
Diagramm zurechtrücken.
