# Umsetzung: Mandantentrennung

**Anforderung:** CLAUDE.md Abschnitt 8 („Berechtigungen sind hierarchisch und
mandantengebunden"), NFA-SIC-*

---

## Warum das die wichtigste Sicherheitsklasse ist

CLAUDE.md sagt es unmissverständlich:

> *„Mandantentrennung wird zentral erzwungen, nicht in jeder einzelnen Abfrage
> von Hand — sonst wird sie irgendwann vergessen. Das ist die häufigste schwere
> Sicherheitslücke in mandantenfähigen Anwendungen."*

Bis zu dieser Iteration existierte die dreistufige Struktur nur im Datenmodell.
Benutzer trugen eine `organisation_id` — **und keine einzige Abfrage schränkte
darauf ein.** In `docs/05-sicherheit-datenschutz.md` stand sie als schwerste
offene Lücke.

## Wie das Vergessen verhindert wird

**Nicht durch Sorgfalt, sondern durch den Übersetzer.**

Jede Methode, die mandantengebundene Daten liest oder ändert, verlangt einen
`Geltungsbereich` als **Pflichtparameter**. Wer ihn weglässt, bekommt keinen
ungefilterten Zugriff — er bekommt einen Übersetzungsfehler.

Beim Umbau war das unmittelbar sichtbar: Nach der Änderung der Signaturen
meldete der Übersetzer **44 Stellen**, an denen die Grenze fehlte. Jede einzelne
wäre bei einer Umsetzung „von Hand in jeder Abfrage" ein Kandidat gewesen,
vergessen zu werden.

```java
// Geht nicht mehr:
bestand.konnektoren();

// Geht:
bestand.konnektoren(bereich(c));
```

Der Geltungsbereich entsteht an **genau einer Stelle** — `Zentrale.bereich(c)`,
abgeleitet aus dem angemeldeten Benutzer.

## Die Hierarchie

```
Verbund            → sieht alles darunter
 └── Standort      → sieht seine Einrichtungen
      └── Einrichtung → sieht nur sich selbst
```

Ermittelt wird **transitiv nach unten**, mit einer rekursiven Abfrage. Nach oben
gibt es keine Sicht: Eine Praxis sieht ihren Standort nicht, und schon gar nicht
die Nachbarpraxis.

## Vier Entscheidungen, die den Ausschlag geben

### 1. Keine Zuordnung heißt „sieht nichts", nicht „sieht alles"

```java
if (organisationId == null) return keiner();
```

Das ist die ganze Sicherheit dieser Klasse. Ein Fehler in der Zuordnung, ein
Datenbankausfall beim Ermitteln, ein unbekannter Wert — alles führt zu
**leerem** Zugriff. Die umgekehrte Richtung wäre eine offene Tür.

Auch der Datenbankfehler: `catch → return keiner()`. Ein Verbindungsproblem darf
keine Mandantengrenze aufheben.

### 2. Ein fremder Datensatz „existiert nicht"

Ein Zugriff außerhalb des Bereichs liefert ein **leeres Ergebnis**, keinen
Zugriffsfehler:

```
Dieses Kartenterminal gibt es nicht mehr.
```

Dieselbe Meldung wie bei einem gelöschten Datensatz. Ein „Zugriff verweigert"
würde verraten, **dass es diesen Datensatz gibt** — und bei durchnummerierten
Kennungen ließe sich damit ein fremder Bestand abzählen.

### 3. Auch Schreibpfade prüfen, nicht nur Leselisten

Die Einrichtung eines neuen Konnektors kommt aus einem **Formularfeld** und ist
manipulierbar. Eine Mandantengrenze, die nur in der Auswahlliste besteht, ist
keine. Deshalb prüft `konnektorAnlegen` die Zielorganisation gegen den Bereich.

Dasselbe bei Massenaktionen: Die Kennungen kommen als Liste aus dem Formular.
Ohne Bereichsgrenze würde eine präparierte Anfrage fremde Geräte deaktivieren —
**lautlos**, denn die Liste zeigt sie ja nicht.

### 4. Auch Filterwerte sind Daten

Eine Auswahlliste, die alle Gerätemodelle des Hauses zeigt, verrät den
Gerätebestand anderer Mandanten — ohne einen einzigen Datensatz preiszugeben.
`vorhandeneWerte` ist deshalb ebenfalls eingeschränkt.

---

## Nachweise

**`MandantentrennungTest`, 15 Tests** gegen einen Aufbau mit zwei Standorten und
drei Praxen. Geprüft wird aus vier Blickwinkeln: Praxis, Standort, Verbund und
ein Benutzer ohne Zuordnung.

Die Angriffe, die geprüft werden:

| Angriff | Erwartung |
|---|---|
| Fremde Gerätekennung direkt aufrufen | leeres Ergebnis, keine Auskunft |
| Fremdes Gerät ändern | abgelehnt, Datensatz unverändert |
| Fremdes Gerät löschen | abgelehnt, Datensatz noch da |
| Konnektor in fremder Einrichtung anlegen | abgelehnt mit Begründung |
| Terminal an fremden Konnektor hängen | abgelehnt |
| Massenaktion mit fremder Kennung | nur eigene geändert, Rest gezählt und gemeldet |
| Suche über den ganzen Bestand | findet nur Eigenes |
| Filter auf fremdes Modell | null Treffer |
| Filter über fremde Konnektor-Kennung | null Treffer |

**Systemnachweise** prüfen dasselbe über HTTP mit echter Anmeldung: Ein
Demo-Benutzer der Ebene 1 meldet sich an und sieht nur die Geräte seiner Praxis;
eine fremde Kennung liefert HTTP 404; Löschen und Massenaktion greifen nicht durch.

---

## Ein Fehler, den das stille `catch` verborgen hat

Beim Umbau entstand ein SQL-Syntaxfehler: Zwei Textblöcke wurden ohne
Trennzeichen verkettet, aus `WHERE 1=1` und `ORDER BY …` wurde `WHERE 1=1ORDER BY`.

Die Abfrage scheiterte — und `catch (SQLException ignored) { }` schluckte es.
Das Ergebnis war eine **leere Liste statt einer Fehlermeldung**. Die Oberfläche
hätte „keine Geräte" gesagt, und niemand hätte erfahren, warum.

Gefunden haben es die Tests. Das `ignored` ist jetzt eine Meldung auf der
Fehlerausgabe. **Ein verschluckter Fehler ist schlimmer als ein lauter** — beim
Lauten weiß man wenigstens, dass etwas nicht stimmt.

---

## Zweite Hälfte: Protokoll, Alarme, Gruppen, Benutzerliste

Nachgetragen am 07.08.2026. Alle vier führten die Einrichtung als **Textfeld**.
Eine Mandantengrenze über einen Textvergleich wäre brüchig: Zwei Einrichtungen
mit gleichem Namen in verschiedenen Verbünden fielen zusammen, und eine
Umbenennung zerriss die Zuordnung stillschweigend.

**Migration 12** trägt eine Spalte `organisation_id` nach, mit Datenübernahme aus
dem Textfeld — aber nur, **wo der Name eindeutig ist**. Mehrdeutige Namen bleiben
ohne Zuordnung: lieber eine Lücke, die auffällt, als eine falsche Zuordnung, die
niemand bemerkt.

### Das Textfeld bleibt

Es zu ersetzen wäre eine nachträgliche Änderung an Einträgen, die per
`FA-PRO-010` **unveränderlich** sind. Der Text ist der Inhalt des Nachweises, die
neue Spalte trägt die Grenze. Nebeneffekt mit Wert: Der Name bleibt lesbar, auch
wenn die Organisation längst entfernt wurde — genau das ist bei einem Nachweis
erwünscht.

### Ein Befund: Der Fremdschlüssel hätte das Löschen unmöglich gemacht

Der erste Entwurf gab der Spalte einen Fremdschlüssel mit
`ON DELETE SET NULL`. Das schlug beim ersten Testlauf fehl:

```
ERROR: Das Fachprotokoll ist nur anfuegbar.
       Aenderung oder Loeschung einzelner Eintraege ist unzulaessig.
```

Ein `SET NULL` ist ein `UPDATE` — und das blockiert der Schutz des Protokolls,
zu Recht. **Die Folge wäre gewesen: Eine Organisation, auf die ein
Protokolleintrag verweist, ließe sich niemals mehr löschen.** Beim Kunden wäre
das als „Einrichtung kann nicht entfernt werden" aufgefallen, ohne erkennbaren
Zusammenhang.

Das Fachprotokoll trägt die Spalte deshalb **ohne Fremdschlüssel**. Bei Alarmen
und Gruppen ist `SET NULL` unproblematisch — sie sind nicht unveränderlich.

### Was jetzt zusätzlich geprüft ist

| Angriff | Erwartung |
|---|---|
| Protokoll einer fremden Praxis lesen | nur eigene Einträge |
| Excel-Export | enthält nicht mehr als die Ansicht |
| Alarme einer fremden Praxis | leer |
| Gerätegruppen einer fremden Praxis | leer |
| Benutzerliste | nur Konten des eigenen Bereichs |
| Protokollfilter auf ein fremdes Gerät | keine Treffer |

Ein Eintrag **ohne** Organisationszuordnung — Anmeldungen, Rotation,
Instanzverwaltung — bleibt eingeschränkten Benutzern verborgen und erscheint nur
für den Instanzbetreiber. Diese Vorgänge haben keinen Einrichtungsbezug; sie
gehen den Betreiber an, nicht eine einzelne Praxis.

---

## Was noch offen ist

Ehrlich benannt:

1. **Neue Funde aus der Ermittlung** haben noch keine Organisationszuordnung —
   sie gehören ja noch niemandem. Die Grenze greift bei der *Übernahme*, weil
   der gewählte Konnektor im Bereich liegen muss. Bei mehreren Agenten je
   Zentrale muss der Fund an die Einrichtung des meldenden Agenten gebunden
   werden.
2. **Rollen und Rechte je Ebene** sind noch nicht verfeinert: Was darf ein
   Benutzer der Praxis, was der des Standorts, was der des Verbunds? Derzeit
   entscheidet nur die Rolle (Administrator / Benutzer), nicht die Ebene.
