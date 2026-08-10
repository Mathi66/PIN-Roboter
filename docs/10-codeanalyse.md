# Codeanalyse und Refactoring

**Stand:** 10.08.2026 · **Anlass:** Auftrag des PO — „Vollständiges Refactoring,
Analyse des aktuellen Quellcodes"

Dieses Dokument hält fest, was gemessen wurde, was daraufhin geändert wurde und
was **bewusst offen bleibt**. Zahlen sind gezählt, nicht geschätzt.

---

## 1. Ausgangslage

| Kennzahl | Wert |
|---|---|
| Java-Dateien (Hauptcode) | 62 |
| Zeilen Hauptcode | 19.100 |
| davon Kommentar | 23 % |
| Testklassen | 26 |
| Modultests | 269 |
| Systemnachweise | 349 |
| Datenbankmigrationen | 22 |
| `TODO`/`FIXME` im Code | **0** |

### Verteilung auf die Module

| Modul | Dateien | Zeilen |
|---|---:|---:|
| `gemeinsam` | 8 | 702 |
| `zentrale` | 33 | 13.749 |
| `agent` | 9 | 1.809 |
| `simulator` | 12 | 1.584 |

Die Zentrale trägt 78 % des Codes. Das entspricht der Aufgabenteilung: Der Agent
tut eine Sache (überwachen und PIN übergeben), die Zentrale trägt Oberfläche,
Datenhaltung, Protokoll, Rechte und Mandantentrennung.

> **Nachtrag 10.08.2026, nach dem Umbau der PIN-Übergabe ([ADR-0011](adr/0011-pin-uebergabe-ist-eine-anfrage-des-terminals.md)).**
> Die Zahlen oben sind die Ausgangslage der Analyse und bleiben als solche
> stehen. Gemessen nach dem Umbau:
>
> | Modul | Dateien | Zeilen | Veränderung |
> |---|---:|---:|---|
> | `gemeinsam` | 10 | 1.289 | +2 Dateien — Protokollkern, damit beide Seiten dieselbe Rechnung nutzen |
> | `zentrale` | 35 | 15.044 | unberührt |
> | `agent` | 11 | 2.661 | +2 Dateien — Verbindung und Verwaltung |
> | `simulator` | 13 | 2.044 | +1 Datei — die Gegenstelle |
>
> Der Agent wächst um knapp die Hälfte. Das ist der Preis dafür, dass aus einem
> Vorgang mit Anfang und Ende ein **Zustand** geworden ist: Eine offene
> Verbindung muss aufgebaut, überwacht, abgebaut und wiederhergestellt werden.
> Der `Verifikationsablauf` ist dabei um ein Drittel **geschrumpft** — er tut
> jetzt weniger, und die PIN kommt darin nicht mehr vor.

---

## 2. Befunde

### 2.1 Die Gottmethode — behoben

`Zentrale.starten()` umfasste **1450 Zeilen** und registrierte **81 Routen**.
Anmeldung, PIN-Ablage und Agentenschnittstelle lagen in derselben Methode. Eine
Änderung an einer Route erforderte, sich durch alles andere zu lesen; ein Blick
auf „was kann die Zentrale eigentlich" war unmöglich.

**Behoben:** Zehn thematische Bündel.

| Methode | Bereich |
|---|---|
| `routenAnmeldung` | Anmelden, Ersteinrichtung, zweiter Faktor, Einladungen |
| `routenUebersicht` | Lage, Geräte, Alarme, Not-Aus, HTMX-Einschübe |
| `routenProtokoll` | Fachprotokoll, Excel-Export |
| `routenGruppen` | Gerätegruppen, Prüfintervall |
| `routenBestand` | Konnektoren, Terminals, Zugangsdaten, PIN |
| `routenOrganisation` | Mandantenstruktur |
| `routenBenutzer` | Konten, Rollen, Sperren |
| `routenBenachrichtigung` | Mailserver, Regeln, Testversand |
| `routenVerwaltung` | Demodaten, Lasttest, Registry, Agenten |
| `routenAgentSchnittstelle` | JSON-Schnittstelle zum Agenten |

`starten()`: **1450 → 257 Zeilen.**

> **Wichtig für jede spätere Änderung:** Die Reihenfolge **innerhalb** eines
> Bündels ist bedeutsam — Javalin nimmt die zuerst passende Route, also muss
> `/bestand/terminal/neu` vor `/bestand/terminal/{id}` stehen. Die Reihenfolge
> der Bündel untereinander ist frei, weil sich die Präfixe nicht überschneiden.

### 2.2 Verschluckte Datenbankfehler — behoben

**20 Stellen** mit `catch (SQLException ignored) { }`. Ein stiller SQL-Fehler
ist praktisch unauffindbar: Der Aufruf liefert eine leere Liste, die Oberfläche
zeigt „keine Einträge", und niemand erfährt, warum.

Genau so blieb ein `WHERE 1=1AND` — entstanden aus einem Java-Textblock ohne
Trennzeichen — monatelang unentdeckt.

**Behoben:** Jede Stelle meldet jetzt auf `stderr` mit Klassen- und
Methodennamen. Bewusst **nicht** weitergeworfen: Ein Zählfehler in einer
Nebenanzeige soll die Oberfläche nicht abstürzen lassen. Melden statt schweigen
ist der Mittelweg — der Aufruf schlägt weiterhin nicht fehl, aber der Fehler ist
auffindbar.

Verbleibend und **richtig so**: `catch (NumberFormatException ignored)` beim
Lesen von Parametern aus der Adresszeile. Ein unbekannter Wert führt dort
absichtlich zur Vorgabe, nicht zu einem Fehler — ein Benutzer soll eine kaputte
Verknüpfung nicht als Absturz erleben.

### 2.3 Uneinheitliche Oberfläche — behoben

Siehe [Umsetzung 26](03-umsetzung/26-ux-refactoring.md). Kurzfassung:

| | vorher | nachher |
|---|---:|---:|
| Bedienelemente ohne Kurzhinweis | 142 von 154 | 0 von 141 |
| klickbare Tabellenzeilen | 0 von 97 | 57 |
| verschiedene Abstandswerte | 37 | 6 |
| Abstände in Inline-Styles | 12 | 0 |

Die Ursache war strukturell: kein gemeinsamer Baustein. Behoben durch
`Bedienelemente` mit **Pflicht-Kurzhinweis** und ein Abstandsraster.

### 2.4 Prüfstand und Entwicklungsstack teilten sich Ports — behoben

Beide nutzten 8090 und 8480. Der Nachweislauf verlangte also, dass der Stack
vorher weicht — mit `docker compose down -v`, was das Datenvolumen und damit das
Administratorkonto löschte. Der PO hat sich dadurch dreimal ausgesperrt.

**Behoben** auf beiden Ebenen: Der Nachweislauf läuft auf 8190/8580 und lässt
eine laufende Instanz unberührt (nachgewiesen: Vollauf parallel zum Stack). Und
`Startkonto` (FA-BEN-035) lässt den Zugang einen Datenverlust überleben.

---

## 3. Was bewusst offen bleibt

### 3.1 `Zentrale.java` ist mit 3.471 Zeilen weiterhin die größte Klasse

Die Routen sind gebündelt, aber die HTML-Erzeugung für Lage, Gruppen, Protokoll
und Verwaltung liegt noch dort — anders als bei Bestand, Benutzern,
Organisation, Verlauf und Benachrichtigung, die eigene `*seiten`-Klassen haben.

**Warum nicht sofort erledigt:** Diese Methoden greifen auf zehn bis fünfzehn
Felder der Zentrale zu (`geraete`, `alarme`, `versionen`, `speicher`, …). Ein
Auslagern mit fünfzehn Parametern verschiebt Zeilen, ohne etwas zu verbessern.
Richtig wäre ein Datenträger je Seite (ein `record`, den die Zentrale füllt und
die Seitenklasse liest) — das ist ein eigener Arbeitsschritt, kein Beiwerk.

**Nächster Schritt, wenn es angegangen wird:** `Verwaltungsseiten` zuerst; die
Seite hat die klarste Abgrenzung und die wenigsten Abhängigkeiten.

### 3.2 `routenBestand` mit 408 Zeilen

Eine weitere Unterteilung nach Gegenstand (Konnektor / ermittelt / Terminal /
PIN) wurde **versucht und zurückgenommen**: Das automatische Verschieben von
Java-Code scheiterte an Klammern innerhalb von Textblöcken — der erzeugte Code
übersetzte nicht. Von Hand ist es machbar, bringt aber wenig gegenüber dem
Risiko; 21 Routen auf 408 Zeilen sind etwa 19 Zeilen je Route und damit normal.

**Lehre, die über diesen Fall hinausgeht:** Java-Code mit eingebetteten
Textblöcken lässt sich nicht zuverlässig über Klammerzählung umstellen. Wer so
etwas automatisiert, prüft danach mit dem Compiler — nicht mit dem Auge.

### 3.3 Klassen ohne eigene Testklasse

17 von 33 Klassen der Zentrale haben keine gleichnamige Testklasse. Das ist
weniger dramatisch, als die Zahl klingt:

- Die `*seiten`-Klassen erzeugen HTML und werden über **Systemnachweise** gegen
  die ausgelieferte Seite geprüft — das ist die aussagekräftigere Prüfung. Ein
  Modultest, der HTML mit erwartetem HTML vergleicht, prüft eine Zeichenkette.
- `Datenbank`, `Ereignis`, `Geraetezustand` sind Träger ohne Logik.
- Echte Lücken: **`Pinverwaltung`** und **`Fachprotokoll`** — beide tragen
  Logik, die eine Prüfung verdient hätte, und beide sind sicherheitsrelevant.
  Sie sind über Systemnachweise abgedeckt, aber nicht über schnelle Modultests.

### 3.4 Keine Blätterung in der Konnektorliste

Das Mengengerüst nennt bis zu 1.000 HSK-Instanzen. Sortierung und Suche gibt es
seit dem 09.08.2026; die Liste wird aber vollständig übertragen. Bei 1.000
Konnektoren ist das zu viel.

Der Umbau ist überschaubar (die Terminalliste macht es bereits vor), gehört aber
zu FA-GER-010 und nicht in ein UX-Refactoring.

---

## 4. Was gut ist und so bleiben soll

Damit ein späterer Umbau nicht versehentlich zerstört, was trägt:

**Compilererzwungene Sicherheit.** Drei Stellen setzen darauf, dass ein Fehler
nicht übersetzt, statt darauf, dass ihn jemand bemerkt:

- `Geltungsbereich` als Pflichtparameter jeder Abfrage (Mandantentrennung)
- Einrichtung als Pflichtparameter in `pruefe()` (Alarme)
- Kurzhinweis als Pflichtparameter in `Bedienelemente` (Bedienbarkeit)

Alle drei sind aus konkreten Fehlern entstanden. Wer sie zu Optionen macht,
holt die Fehler zurück.

**Kein ORM.** SQL steht sichtbar in den `*verwaltung`-Klassen. Bei 21 Tabellen
und überschaubaren Abfragen kostet ein ORM mehr Verständnis, als es spart — und
es verdeckt genau die Stelle, an der die Mandantentrennung greifen muss.

**Migrationen im Java-Code, nie geändert, nur ergänzt.** Migration 6 sieht heute
aus wie am ersten Tag.

**23 % Kommentaranteil, null `TODO`.** Die Kommentare erklären überwiegend das
*Warum* — insbesondere, warum eine naheliegende Lösung nicht funktioniert. Das
ist die Sorte Kommentar, die nicht veraltet.

**Der Simulator kennt Fehlerzustände.** Gesperrte Karte, abgelaufenes Token,
laufendes KSR-Update. Ein Simulator, der nur den guten Fall kann, prüft die
Hälfte.

---

## 5. Empfohlene Reihenfolge für die nächsten Schritte

1. **Modultests für `Pinverwaltung` und `Fachprotokoll`** — die einzigen echten
   Testlücken mit Sicherheitsbezug.
2. **Blätterung in der Konnektorliste** — sonst bricht das obere Ende des
   Mengengerüsts.
3. **`Verwaltungsseiten` auslagern**, mit Datenträger statt Parameterliste. Dann
   dieselbe Bauweise für Lage, Gruppen und Protokoll.
4. **Kalibrierung an echter Hardware** ([ADR-0007](adr/0007-simulierte-endpunkte-und-teststrategie.md))
   — unverändert der wichtigste offene Punkt des ganzen Projekts. Alle Nachweise
   laufen bislang gegen den selbstgebauten Simulator. Besonders offen: ob ein
   echter KoCo-Konnektor den KSR-Zustand im `GetPinStatus` meldet oder nur als
   Ereignis.

Punkt 4 wiegt schwerer als 1 bis 3 zusammen. Alles andere ist Handwerk; dieser
Punkt entscheidet, ob das Produkt in der Realität funktioniert.
