# 17 — Neue Oberfläche, Stufe 1 bis 3 (ADR-0010)

**Stand:** umgesetzt und nachgewiesen (07.08.2026)
**Grundlage:** [UX-Konzept](../06-ux-konzept.md), [ADR-0010](../adr/0010-ux-neuausrichtung.md)

---

## Was umgesetzt ist

| Stufe | Inhalt | Stand |
|---|---|---|
| 1 | Gezielte Teilaktualisierung statt Komplettaustausch | ✔ |
| 2 | Live-Suche in Bestand und Konten | ✔ |
| 3 | Neues Farb- und Flächenkonzept, Seitenleiste statt Reiterband | ✔ |
| 4 | Detailseiten statt Aufklapper | ✔ |
| 5 | Organisations-Detailseite mit allen Beziehungen | ✔ |

## Stufe 1 — die Unruhe hatte eine Ursache

Die Übersicht lag in einem Container mit `hx-trigger="every 3s"` und tauschte
**den gesamten Seiteninhalt** aus. Dabei können Scrollposition, Tastaturfokus
und eine getroffene Auswahl springen.

Jetzt aktualisieren sich zwei Einschübe getrennt — `/teil/alarme` und
`/teil/lage` — und der Rahmen bleibt stehen. Der Takt ist von 3 s auf 5 s
gesenkt: Der Zustand ändert sich ohnehin nur im Prüfintervall der Gerätegruppe
(im Betrieb 60 s). Häufiger zu fragen erzeugt nur Unruhe und Last.

## Stufe 2 — Live-Suche ohne Knopf

Das Formular trägt:

```html
hx-get="/bestand" hx-target="#terminalliste" hx-select="#terminalliste"
hx-swap="outerHTML" hx-push-url="true"
hx-trigger="input changed delay:250ms from:#t-suche, change"
```

`hx-select` ist der Kniff: Der Server rendert die Seite wie bisher, HTMX
extrahiert daraus nur den Listenbereich. **Kein serverseitiger Umbau nötig**,
und Filterleiste, Fokus und Cursorposition bleiben erhalten — nachgewiesen.

Die 250 ms zählen ab dem **letzten** Zeichen. Wer flüssig tippt, löst genau eine
Abfrage aus; bei 5.000 Geräten wäre eine pro Tastendruck spürbar.

Die Trefferzahl steht **innerhalb** des getauschten Bereichs mit
`aria-live="polite"`. Beim ersten Versuch lag sie außerhalb und blieb auf dem
alten Wert stehen — beim Tippen sichtbar, weil dort „36 Treffer" stand, während
die Liste 9 Zeilen zeigte.

## Stufe 3 — Gestaltung

Seitenleiste statt Reiterband: Bei acht Arbeitsbereichen war das Band zu voll.
Senkrecht ist Platz, und die Bereiche lassen sich gruppieren — „Betrieb" und
„Verwaltung" sind zwei verschiedene Anlässe, die Oberfläche zu öffnen.

Alle Seitenköpfe kommen jetzt aus **einer** Methode (`Zentrale.seitenkopf`
bzw. `detailkopf`). Vorher trug jede Seite ihre Überschrift selbst, mit leicht
unterschiedlichen Abständen — auch das gehörte zum unruhigen Eindruck.

Die Klassennamen im HTML wurden **nicht** umbenannt. Die Stildatei gibt den
bestehenden Namen (`.tafel`, `.knopf`, `.marke`) die neue Optik. Das hielt den
Umbau auf die Stellen begrenzt, an denen sich wirklich Struktur ändert.

## Der Fund im Prüfskript

Zwei Nachweise wurden nach dem Umbau rot, ohne dass am Produkt etwas fehlte:
„Konnektor mit zugeordneten Terminals wird nicht gelöscht" und „die
Organisationsseite ist erreichbar".

Der zweite war einfach: Die Überschrift trägt jetzt `tabindex="-1"` für die
Fokusverwaltung beim Seitenwechsel, das Muster `<h2>Organisation</h2>` passte
nicht mehr.

**Der erste war ein Fehler im Prüfskript, der seit jeher darin steckte.** Das
Skript läuft mit `set -o pipefail`. Bei

```bash
curl -s ... | grep -q "zugeordnet" && LS=ja || LS=nein
```

beendet `grep -q` beim ersten Treffer und schließt die Pipe. curl bekommt
SIGPIPE und endet mit Status 141 — die Pipeline gilt als **fehlgeschlagen**,
obwohl der Treffer gefunden wurde.

Ob das passiert, ist ein Rennen: Es hängt davon ab, ob curl mit dem Schreiben
fertig war, bevor grep beendete. Bei kleinen Antworten gewinnt curl. Die neue
Oberfläche macht die Seiten größer — und das Rennen kippte.

Behoben durch eine Hilfsfunktion `enthaelt`, die mit `grep -c` arbeitet:
`grep -c` liest immer bis zum Ende und kann das Rennen nicht verlieren.
25 Stellen umgestellt.

> Die Lehre: Ein Prüfskript, dessen Ergebnis von der Größe einer Antwort
> abhängt, prüft nicht das Produkt. Solche Nachweise flattern irgendwann — und
> dann sucht jemand den Fehler an der falschen Stelle.

## Nachgewiesen

- **Kontraste** rechnerisch gegen die neue Stildatei: 0 unter dem Grenzwert,
  hell und dunkel (`kontraste.mjs`)
- **axe-core** gegen 13 ausgelieferte Seiten: 0 Befunde
- **Live-Suche** am laufenden System: Filterung greift, Trefferzahl stimmt,
  Fokus bleibt im Feld, die Adresse wird mitgeführt
- **204 Systemnachweise, 230 Modultests** — alle grün

Nicht geprüft und weiterhin offen: Tastaturbedienung im Ablauf und
Verständlichkeit mit einem Screenreader.

---

## Stufe 4 — keine Aufklapper mehr

Vier `<details>`-Ausklapper sind verschwunden. An ihrer Stelle stehen Seiten
mit eigener Adresse:

| vorher | jetzt |
|---|---|
| Ausklapper „Konnektor hinzufügen" unter der Liste | `/bestand/konnektor/neu` |
| Ausklapper „Kartenterminal hinzufügen" | `/bestand/terminal/neu` |
| Ausklapper „Einheit hinzufügen" | `/organisation/neu` |
| aufklappende Tabellenzeile zum Bearbeiten | `/organisation/{id}` |

Jede trägt oben einen sichtbaren Rückweg. Lesezeichen, Verlinkung und der
Zurück-Knopf des Browsers funktionieren jetzt — vorher führte er aus der Liste
heraus statt aus der Maske.

**Die Reihenfolge der Routen ist hier nicht egal:** Javalin prüft in
Deklarationsreihenfolge. Stünde `/bestand/konnektor/{id}` vor
`/bestand/konnektor/neu`, läse `{id}` den Wert `neu`, und `Long.parseLong`
würde werfen. Die `/neu`-Routen stehen deshalb zuerst.

## Stufe 5 — die Organisations-Detailseite

`/organisation/{id}` beantwortet zuerst die Frage, die jemand tatsächlich hat:
**Was gehört zu dieser Praxis?** Sieben Kacheln, jede ein Absprungpunkt —
Kartenterminals, SMC-B-Karten, Konnektoren, Konten, Gerätegruppen, offene
Alarme, Protokolleinträge. Erst darunter die Änderungsmasken.

Gezählt wird über den **gesamten Teilbaum** einschließlich der Einheit selbst.
Bei einem Standort hängen die Geräte an den Einrichtungen darunter; nur die
direkte Zuordnung zu zählen ergäbe dort überall Null.

Damit die Absprünge wirklich filtern, hat der Bestandsfilter einen neuen
Parameter `einrichtungId` bekommen, und die Kontenliste versteht
`?organisation=<id>`. Beide schränken auf den Teilbaum ein.

## Zwei Fehler auf dem Weg

**Der Absprung lieferte 0 Treffer.** Die neue Filterbedingung stand in einem
Java-Textblock. Ein Textblock entfernt die gemeinsame Einrückung, und der
Zeilenumbruch direkt nach den öffnenden Anführungszeichen gehört nicht zum
Inhalt. Herausgekommen ist `WHERE 1=1AND t.organisation_id …`.

Gefunden hat das nicht der Fehler selbst, sondern dass er **sichtbar** wurde:
Die Abfrage lag in einem `catch (SQLException ignored) { }`. Ein verschluckter
Datenbankfehler sieht aus wie „keine Treffer" — und genau danach hatte ich
zuerst gesucht. Acht solcher Blöcke in `Bestandsverwaltung` melden jetzt.

> Dieselbe Kombination — Textblock ohne Trenner plus verschluckte Ausnahme —
> hat in diesem Projekt schon einmal Zeit gekostet. Der zweite Teil ist der
> teurere: Ein Fehler, der als leeres Ergebnis erscheint, führt die Suche
> zuverlässig in die falsche Richtung.

**Der Nachweis für FA-GER-035 suchte am alten Ort.** Er prüft den Aufrufkontext
in der Konnektor-Anlegemaske und holte dafür `/bestand`. Die Maske ist jetzt
eine eigene Seite. Angepasst.

## Nachgewiesen

- **keine Aufklapper mehr** in Bestand und Organisation (gezählt)
- **jede Anlegemaske** ist eine eigene Seite mit Rückweg (drei Nachweise)
- **die Zahlen der Detailseite stimmen mit der Datenbank überein** — eine Zahl,
  die nicht stimmt, ist schlimmer als keine
- **der Absprung filtert wirklich** (5 von 37 statt 37 von 37)
- **axe-core: 16 Seiten ohne Befund**, Kontraste 0 unter dem Grenzwert
- **230 Modultests, 212 Systemnachweise** — alle grün
