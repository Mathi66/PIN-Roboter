# 31 — Die Organisationsstruktur als Bild

**Stand:** umgesetzt und am laufenden Stack nachgewiesen (12.08.2026)
· **Vorgabe:** PO, 12.08.2026
· Gestaltungskonzept: [06-ux-konzept.md](../06-ux-konzept.md), Nachtrag 12.08.2026

---

## Was es löst

Seit [ADR-0012](../adr/0012-geraetehierarchie-nach-organisationsstruktur.md)
sind fünf Ebenen möglich: Trägergesellschaft → Leistungserbringer → Standort →
Betriebsstätte → Fachbereich. Eine eingerückte Tabelle bildet das zwar ab, aber
niemand erfasst sie auf einen Blick — und sie beantwortet die eigentliche Frage
nicht: **Wo klemmt es?**

## Der Aufbau

| Baustein | Aufgabe |
|---|---|
| `Symbole.java` | alle Piktogramme an einer Stelle, einheitliches Raster |
| `Strukturansicht.java` | Karten, Kacheln, Legende, Summierung über den Teilbaum |
| `Organisationsseiten.ansichtsumschalter` | Struktur ⇄ Liste |
| `Zentrale.bilanzen` / `.strukturgeraete` / `.strukturkonnektoren` | Daten sammeln |
| `Bestandsverwaltung.strukturgeraete` / `.strukturkonnektoren` | je **eine** Abfrage für alles |

## Die drei Stellen, an denen es hätte schiefgehen können

**1. Eine Abfrage je Einheit.** Bei einem Klinikverbund wären das hunderte
Abfragen für eine Seite. Es sind drei: Baum, Geräte, Konnektoren. Die Zuordnung
geschieht anschließend im Speicher.

**2. Die Summierung über den Teilbaum.** Der Baum kommt in
Tiefensuchreihenfolge, also wird er **rückwärts** durchlaufen — dann hat jedes
Kind seine Summe bereits, wenn sein Elternteil an die Reihe kommt. Eine Schleife
statt Rekursion, ein Durchlauf statt einem je Ebene. Die Methode
`Strukturansicht.summieren` ist bewusst herausgelöst: Sie braucht keine
Datenbank und ist deshalb in `StrukturansichtTest` direkt prüfbar.

**3. Die Bewertung eines Teilbaums.** Es gilt der **schlechteste** Zustand
darin, nicht der Durchschnitt. Ein Mittelwert würde neunundneunzig gesunde
Geräte über ein ausgefallenes legen. Und eine Einheit ohne Geräte ist
`UNBEKANNT`, nicht `OK` — „noch nie gesehen" ist etwas anderes als „geprüft und
gut".

## Barrierefreiheit

Die Ansicht ist ein Baum aus verschachtelten Listen mit gewöhnlichen Verweisen,
keine Zeichenfläche. Daraus folgt ohne Zusatzaufwand: Tastaturbedienung,
Vorlesbarkeit, Strg+F, Umbruch, Skalierung mit der Schriftgröße.

- Auf- und Zuklappen über `<details>` — kein JavaScript, keine ARIA-Basteleien.
- Jede Ampel hat Sinnbild **und** Text (WCAG 1.4.1); die Legende benennt alle
  vier Zustände in Worten.
- Piktogramme sind `aria-hidden` — die Bedeutung steht daneben (WCAG 1.1.1).
- Die Baumlinien sind `::before`-Dekoration; die Struktur trägt die
  Listenverschachtelung.
- Bei `prefers-reduced-motion` entfällt die Bewegung unter dem Zeiger.

Die Seite ist in `pruefungen/barrierefreiheit-pruefen.sh` aufgenommen und läuft
im Vollauf mit.

## Ein Fehler beim Bauen, der erwähnenswert ist

```
ERROR: trailing junk after numeric literal at or near "1ORDER"
```

Ein Java-Textblock entfernt den Zeilenumbruch direkt nach den öffnenden
Anführungszeichen. Aus

```java
"WHERE 1=1" + bereich.bedingung(...) + """
ORDER BY ..."""
```

wurde bei leerer Bedingung `WHERE 1=1ORDER BY`. Die Ansicht zeigte daraufhin
**still keine Konnektoren** — der Fehler stand nur im technischen Protokoll.
Die anderen Abfragen im Code setzen an dieser Stelle ein Leerzeichen; diese
nicht. Behoben, mit Kommentar an der Stelle.

## Grenzen, die genannt statt verschwiegen werden

Ab dreizehn Geräten je Einheit tritt an die Stelle der Kacheln ein Verweis in
die Geräteliste — **mit der vollständigen Zahl darin**. Ein stilles Abschneiden
sähe aus wie Vollständigkeit. Dieselbe Regel gilt für zugeklappte Zweige: Am
zugeklappten Element steht, wieviele Einheiten darin stecken.

## Nachgewiesen

Modultests (`StrukturansichtTest`, ohne Datenbank): Summierung über den
Teilbaum, schlechtester Zustand gewinnt, Rangfolge der Ampel, leer ist nicht
gut, Klartext für Screenreader, Wurzel ohne Elternteil.

Systemnachweise am laufenden Stack: Die Ansicht ist erreichbar, zeigt **jede**
Einheit des Geltungsbereichs (Abgleich gegen die Datenbank), zeigt die
Kartenterminals als Kacheln, kennzeichnet einen mitgenutzten Konnektor in
Rahmen **und** Text, benennt die Zustände in Worten, und die Listenansicht
bleibt gleichwertig erreichbar samt gemerkter Wahl.
