# Umsetzung: Barrierefreiheit

**Anforderung:** NFA-USA-030 — WCAG 2.2 Level AA als Mindeststandard
(CLAUDE.md 4.4)

---

## Der Ausgangspunkt: behauptet, nicht belegt

Die Vorgaben wurden beim Bauen von Anfang an beachtet — sichtbarer Fokus,
Beschriftungen über den Feldern, `aria-sort` in sortierbaren Spalten, Tabellen
mit `scope`, ausreichend große Bedienelemente.

**Geprüft wurde nie.** In `docs/01a-umsetzungsstand.md` stand die Anforderung
deshalb als eine der drei Hauptlücken: *„nicht belegt, sondern behauptet"*.

## Was jetzt geprüft wird

Zwei Werkzeuge, weil eines nicht reicht:

### 1. Struktur — axe-core gegen die ausgelieferten Seiten

Geprüft werden **zehn Seiten**, so wie der Server sie ausliefert: Anmeldung,
Einladung, Übersicht, Geräte, Bestand, Gruppen, Benutzer, Verwaltung, Protokoll,
ermittelte Geräte.

Abgedeckt: ARIA-Rollen, Beschriftungen von Bedienelementen,
Überschriftenhierarchie, Tabellenköpfe, Formularzuordnungen, doppelte
Kennungen, Sprachauszeichnung.

### 2. Kontraste — rechnerisch

jsdom rendert nicht und kann Kontraste nicht messen. Statt die Lücke offen zu
lassen, wird gerechnet: Die Farbpaare stehen als CSS-Variablen fest, und das
Kontrastverhältnis ist eine Formel.

**Das ist belastbarer als ein Screenshot-Vergleich** — es misst den Wert, nicht
den Eindruck. Geprüft werden zwölf Paare in **beiden Farbschemata**, hell und
dunkel.

---

## Zwei echte Befunde

### Der Rand von Bedienelementen war zu schwach

| | vorher | nötig |
|---|---:|---:|
| Rand gegen Karte | **1,29:1** | 3,0:1 |

WCAG 2.2, Kriterium **1.4.11** verlangt 3:1 für alles, was zur Erkennung eines
Bedienelements nötig ist — also für die Begrenzung von Eingabefeldern und
Knöpfen. Der vorhandene `--rand` war für dekorative Trennlinien gedacht und
dafür auch richtig; als Umrandung eines Eingabefelds war er zu blass.

Behoben durch eine zweite Variable `--rand-bedien` (jetzt **5,33:1** hell,
**5,86:1** dunkel), verwendet für Eingabefelder, Auswahllisten und Knöpfe. Die
dekorativen Ränder bleiben unverändert — sie tragen keine Information, die zur
Bedienung nötig wäre.

### Ein Eingabefeld ohne Beschriftung

In der Gruppenansicht stand je Zeile ein Feld für das Prüfintervall — mit einem
`s` daneben als Einheit, aber **ohne Beschriftung**. Für einen Screenreader ist
das ein namenloses Textfeld: Man hört „Eingabefeld", nicht wofür.

Behoben durch ein verstecktes Label je Zeile, das die Gruppe benennt:
*„Prüfintervall für Empfang in Sekunden"*. Das `s` daneben ist jetzt
`aria-hidden`, weil es sonst doppelt vorgelesen würde.

**Beide Befunde waren durch Hinsehen nicht zu finden** — der Kontrast, weil
1,29:1 auf einem guten Bildschirm noch erkennbar aussieht; das fehlende Label,
weil die Einheit daneben den Eindruck einer Beschriftung erzeugt.

---

## Was diese Prüfung nicht abdeckt

Ehrlich benannt, damit niemand mehr hineinliest:

1. **Tastaturbedienung im Ablauf.** Reihenfolge, Fallen, Fokusverwaltung nach
   einer HTMX-Aktualisierung — das braucht einen echten Browser. **Offen.**
2. **Screenreader-Verständlichkeit.** Ob eine Ansage *sinnvoll* ist, entscheidet
   kein Werkzeug. **Offen.**
3. **Zustände nach einer Nutzerhandlung.** Geprüft wird die ausgelieferte Seite,
   nicht was nach dem Aufklappen eines Formulars entsteht.

> Ein Werkzeug findet nur einen Teil der Barrieren — nach verbreiteter
> Einschätzung etwa ein Drittel. Es ersetzt keine Prüfung mit einem Menschen.
> Was es findet, findet es aber zuverlässig und bei jedem Lauf.

---

## Verankert, damit es nicht verfällt

`pruefungen/barrierefreiheit-pruefen.sh` läuft als Teil der Systemnachweise. Ein
neuer Befund macht den Lauf rot.

Ist das Werkzeug nicht beschaffbar (kein Netz), **überspringt** das Skript und
sagt das ausdrücklich — statt „bestanden" zu melden. Dieselbe Regel wie beim
Abhängigkeits-Scan: Eine übersprungene Prüfung ist keine bestandene.
