# ADR-0007: Simulierte Endpunkte und Teststrategie

- **Status:** **Umgesetzt** (07.08.2026) — Simulator in Betrieb, Kalibrierung an echter Hardware steht aus
- **Datum:** 06.08.2026
- **Betrifft:** Vorgehen, Qualitätssicherung, Zeitplan

---

> **Nachtrag 07.08.2026:** Der Simulator ist gebaut und trägt die gesamte
> Prüfung: 37 Systemnachweise laufen gegen ihn. **Die in Regel 2 geforderte
> Kalibrierung an echter Hardware steht weiterhin aus** — damit steht auch die
> dort beschriebene Gefahr weiterhin im Raum, gegen eine Fiktion zu entwickeln.
> Das ist der wichtigste offene Punkt des Projekts.

## Kontext

Vorschlag des POs vom 06.08.2026:
> Wir sollten die physischen Endpunkte erst einmal simulieren. Damit können wir
> die interne Struktur vorantreiben und einen Betrieb nachstellen.
> Erfahrungsgemäß braucht das Zeit zum Reifen. Wir könnten auch schneller einen
> Prototyp vorstellen.

**Bewertung: richtig, und aus einem stärkeren Grund als dem genannten.**

---

## Warum simulierte Endpunkte nicht nur Beschleunigung sind

Der offensichtliche Nutzen — früher Prototyp, unabhängig von Hardware — trifft
zu. Entscheidender sind aber drei Dinge, die sich mit realer Hardware **gar
nicht** prüfen lassen:

1. **Mengengerüst.** `NFA-LEI-020` verlangt den Nachweis für **5.000 Terminals**.
   Der Testaufbau hat eine Handvoll Geräte. Ein Simulator, der 5.000 Terminals
   und mehrere Konnektoren nachstellt, ist der **einzige** Weg, diese Anforderung
   überhaupt zu prüfen.
2. **Fehlerfälle.** `NFA-QS-020` verlangt geprüfte Fehlerfälle: Terminal fällt
   mitten im Vorgang aus, Konnektor antwortet nicht, Firmware-Update läuft über
   das Fenster hinaus, Karte kommt nach Neustart nicht zurück. Diese Fälle real
   herbeizuführen ist mühsam, unzuverlässig reproduzierbar und teilweise
   schädlich.
3. **Der gefährlichste Test von allen.** `FA-PIN-030` — der Nachweis, dass eine
   falsche PIN genau **einen** Versuch auslöst und die Automatik dann anhält —
   verbrennt real bei jedem Durchlauf einen Fehlversuch der Karte. Am Simulator
   läuft er beliebig oft, in jedem Bauvorgang. **Dieser Test ist verpflichtend
   vor jeder Auslieferung; ohne Simulator ist er praktisch nicht durchführbar.**

Der Simulator ist damit kein Behelf für die Anfangszeit, sondern ein
**dauerhafter Produktbestandteil** der Qualitätssicherung.

---

## Die Gefahr, die dabei entsteht

Ein Simulator bildet ab, **was wir glauben, wie sich die Geräte verhalten**. Ist
diese Annahme falsch, entwickeln wir monatelang gegen eine Fiktion — und merken
es beim ersten Kontakt mit echter Hardware. Das ist genau das Raten, das in
diesem Projekt ausgeschlossen ist.

Diese Gefahr ist real, aber beherrschbar. Zwei Regeln:

**Regel 1 — Der Simulator wird aus der Spezifikation abgeleitet, nicht aus der
Vorstellung.** Jedes nachgestellte Verhalten trägt einen Verweis auf die
Anforderung, aus der es stammt (`A_24081`, `TIP1-A_4587`, `TIP1-A_4589` …). Wo
die Spezifikation nichts sagt, wird das ausdrücklich als **Annahme** markiert und
in einer Liste geführt — diese Liste ist die Prüfliste für den ersten
Hardwarekontakt.

**Regel 2 — Kalibrierung so früh wie möglich.** Die **risikofreie Lesestufe** am
realen Gerät (Status abfragen, Karten und Terminals ermitteln, Informationsmodell
lesen — reine Leseoperationen, keine Veränderung) wird durchgeführt, **sobald
Zugang besteht**, nicht erst am Ende. Ihre Aufzeichnungen werden zum Maßstab, an
dem der Simulator korrigiert wird.

> **Ohne Regel 2 wird aus einer guten Idee ein teurer Irrweg.** Der Simulator
> darf die Begegnung mit der Wirklichkeit verschieben, nicht ersetzen.

---

## Der Simulator spricht Brainpool

Ein Nebeneffekt mit erheblichem Wert: Der simulierte Konnektor wird mit einem
**Brainpool-Zertifikat** betrieben. Damit ist die TLS-Frage aus
[ADR-0006](0006-technologie-stack.md) **ohne jede Hardware** entscheidbar — die
Kandidatenplattformen laufen gegen einen echten Brainpool-Endpunkt statt gegen
Literaturangaben.

Das löst die derzeit einzige offene Architekturentscheidung, und zwar sofort.

---

## Was der Simulator können muss

| Anforderung | Grund |
|---|---|
| Die Konnektor-Operationen `GetPinStatus`, `VerifyPin`, `GetCards`, `GetCardTerminals`, `GetResourceInformation` mit **allen** spezifizierten Rückgabewerten | vollständige Zustandsabdeckung |
| **Alle** PIN-Zustände: `VERIFIED`, `VERIFIABLE`, `TRANSPORT_PIN`, `EMPTY_PIN`, `BLOCKED`, `DISABLED` — und einen echten Fehlversuchszähler | `FA-PIN-030` prüfbar machen |
| Die Terminal-Managementschnittstelle mit PEviM-Verhalten nach `A_24081` | Kernablauf |
| **Bösartig sein können:** Verzögerungen, Aussetzer, Verbindungsabbrüche, unerwartete Antworten, Zustand „aktualisierend" | Ein Simulator, der nur den Erfolgsfall kennt, erzeugt trügerische Sicherheit |
| **Skalieren:** mehrere Konnektoren, bis 5.000 Terminals, konfigurierbare Antwortzeiten | `NFA-LEI-020` |
| **Brainpool-TLS** anbieten, wahlweise auch NIST | `ADR-0006`, realistische Kryptographie |
| Über eine Steuerschnittstelle in Zustände versetzbar sein | reproduzierbare Tests im Bauvorgang |

---

## Vorgehen

| Schritt | Inhalt | Hardware nötig |
|---|---|---|
| **1** | Simulator (Grundgerüst) mit Brainpool-TLS | nein |
| **2** | **Stack-Entscheidung**: Kandidaten gegen den Simulator prüfen → ADR-0006 abschließen | nein |
| **3** | Kernfunktion gegen den Simulator: Überwachung, Verifikation, Sicherungslogik | nein |
| **4** | **Kalibrierung**: Lesestufe am realen Gerät, Simulator korrigieren | **ja, risikofrei** |
| **5** | Oberfläche, Mandanten, Benutzer, Protokoll → **vorführbarer Prototyp** | nein |
| **6** | **Vollständiger Durchstich** am realen Gerät: PEviM-Kette mit Test-SMC-B | **ja** |

Schritt 4 ist bewusst **vor** dem Prototyp eingeplant. Er kostet wenig, ist
gefahrlos und verhindert, dass ein vorzeigbarer Prototyp auf falschen Annahmen
steht.

Schritt 6 bleibt der Punkt, an dem sich entscheidet, ob das Produkt trägt. Er
wird durch den Simulator **vorbereitet**, nicht ersetzt — und er wird durch ihn
deutlich wahrscheinlicher gelingen, weil bis dahin jeder Fehlerfall
durchgespielt ist.

---

## Konsequenzen

- Der Simulator wird als eigenes, gepflegtes Bauteil geführt — mit Tests,
  Dokumentation und Versionierung wie das Produkt selbst. Er wird **nicht**
  ausgeliefert.
- Die **Annahmenliste** (Regel 1) ist ein verbindliches Arbeitsdokument. Jeder
  Eintrag wird beim Hardwarekontakt bestätigt oder korrigiert.
- Kein Akzeptanzkriterium gilt allein durch einen Simulatorlauf als erfüllt. Der
  Grundsatz aus dem Anforderungskatalog bleibt: **Nachweis am Testaufbau.** Der
  Simulator liefert Reproduzierbarkeit und Abdeckung, die Hardware liefert
  Gültigkeit.
- Ein früher Prototyp ist ausdrücklich erwünscht — er ist beim aktuellen Stand
  jedoch als **Prototyp** zu kennzeichnen, solange Schritt 4 aussteht.
