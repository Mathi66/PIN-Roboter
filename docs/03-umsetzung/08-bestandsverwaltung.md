# Umsetzung: Bestandsverwaltung und Demodatensatz

**Anforderungen:** FA-GER-010 (Konnektoren erfassen), FA-GER-020 (Kartenterminals
erfassen), Teile von FA-GER-060 (Bedienbarkeit bei großen Stückzahlen)

**Entscheidung zur Geheimnisablage:** [ADR-0008](../adr/0008-ablage-von-geraete-zugangsdaten.md)

---

## Was hier entsteht

Bis zu dieser Iteration kamen alle Geräte aus dem Simulator oder aus dem
Demodatensatz. Jetzt lassen sich Konnektoren und Kartenterminals **von Hand
erfassen, ändern und löschen** — die Voraussetzung dafür, dass das Produkt an
einer echten Praxis eingerichtet werden kann.

| Baustein | Datei |
|---|---|
| Datenzugriff, Regeln, Erreichbarkeitsprüfung | `Bestandsverwaltung.java` |
| Oberfläche | `Bestandsseiten.java` |
| Verschlüsselte Ablage von Zugangsdaten | `gemeinsam/Geheimnisspeicher.java` |
| Schema | `Datenbank.java`, Migration 10 |

---

## Die vier Regeln, die den Aufbau bestimmen

### 1. Zugangsdaten gehen nur hinein, nie heraus

`FA-GER-010` verlangt: nach dem Speichern *„über die Oberfläche und über jede
Schnittstelle nicht mehr auslesbar"*.

Umgesetzt als **AES-256-GCM** mit Schlüssel aus `GEHEIMNIS_SCHLUESSEL`. Kein
Lesepfad gibt einen Klartext zurück — die Datensätze tragen nur ein
`passwortHinterlegt`-Kennzeichen. Es gibt genau zwei entschlüsselnde Methoden,
und sie heißen `konnektorPasswort` und `terminalAdminPasswort`, damit man sie
bei einer Durchsicht findet.

Warum Verschlüsselung und nicht ein Hash wie beim Anmeldekennwort: Der Wert muss
wieder verwendbar sein, um sich beim Gerät anzumelden. Ein Hash wäre hier
schlicht das falsche Werkzeug.

**Fehlt der Schlüssel**, bleibt die Erfassung vollständig nutzbar — nur ohne
Kennwörter, und die Oberfläche sagt das mit einem Hinweis. Ein Startabbruch
wäre die schlechtere Wahl: Er nähme dem Betreiber die Möglichkeit anzufangen.

Was das nicht leistet und warum trotzdem so entschieden wurde: ADR-0008.

### 2. Ein nicht erreichbares Gerät ist speicherbar

Bei der Ersteinrichtung steht der Konnektor oft noch gar nicht. Eine Erfassung,
die Erreichbarkeit erzwingt, treibt den Betreiber zu Fantasiewerten, die später
niemand mehr korrigiert.

Die Erreichbarkeit wird deshalb **geprüft und gemeldet**, nicht erzwungen:

```
Konnektor gespeichert. Unter 192.0.2.77:443 antwortet innerhalb von
3 Sekunden nichts. Das Gerät ist ausgeschaltet, nicht im Netz, oder
eine Firewall blockiert den Port.
```

Geprüft wird bewusst nur die **Transportebene** (TCP-Verbindung). Ein
vollständiger TLS-Aufbau mit Brainpool-Zertifikaten und Aufrufkontext gehört zum
Agenten. Für die häufigste Frage — *„habe ich mich in der Adresse vertippt?"* —
genügt die einfache Prüfung, und sie antwortet in Sekundenbruchteilen statt in
zehn Sekunden.

Die Meldungen unterscheiden vier Fälle, weil sie zu vier verschiedenen
Handlungen führen: Adresse unlesbar, Name nicht auflösbar, keine Antwort,
Verbindung abgelehnt.

### 3. Löschen darf nicht mehr mitnehmen als gemeint

Das Schema verknüpft Terminals per `ON DELETE CASCADE` mit ihrem Konnektor. Ein
Fehlgriff würde damit **lautlos einen halben Standort** aus der Überwachung
entfernen. Deshalb prüft die Anwendung vorher und verweigert mit Begründung:

```
Dem Konnektor „Praxis Nord“ sind noch 4 Kartenterminal(s) zugeordnet:
Empfang, Behandlung 1, Behandlung 2, Behandlung 3. Bitte diese Geräte
zuerst einem anderen Konnektor zuordnen oder löschen.
```

Die betroffenen Geräte werden **benannt**, nicht nur gezählt — sonst muss der
Administrator selbst suchen, was er übersehen hat.

### 4. PIN-Automatik nur mit SMC-B

`A_24080`: PEviM ist ausschließlich für SMC-B zulässig. Ein Terminal ohne SMC-B
mit aktiver Automatik wäre nicht nur wirkungslos — ein Versuch gegen die falsche
Karte könnte deren Fehlversuchszähler belasten. Die Kombination wird beim
Anlegen **und** beim Ändern abgelehnt, mit Nennung der Anforderungs-ID.

---

## Bedienbarkeit bei großen Stückzahlen

`FA-GER-060` verlangt Bedienbarkeit bei 5.000 Terminals. Die Grundlage:

- **serverseitige Paginierung** — 25 Treffer je Seite, `LIMIT`/`OFFSET` in der
  Abfrage, nicht im Browser
- **Volltextsuche** über Bezeichnung, Kennung und Standort
- **Filter** nach Konnektor
- **Index** auf `lower(bezeichnung)` und `lower(standortangabe)` — bei 5.000
  Geräten entscheidet er darüber, ob die Liste unter einer Sekunde steht
- **obere Grenze von 200** Datensätzen je Anfrage, damit eine manipulierte URL
  nicht den gesamten Bestand zieht

Sortierung, weitere Filter und Massenaktionen kamen im Nachtrag weiter unten
dazu. Offen bleibt allein die **Leistungsmessung** bei 5.000 Geräten.

Die Zählabfrage läuft getrennt von der Datenabfrage. Das ist eine Abfrage mehr,
aber die Alternative — alles laden und im Speicher zählen — ist genau das, was
bei 5.000 Geräten nicht mehr funktioniert.

---

## Demodatensatz: das Informationsmodell des Konnektors

Der MVZ-Verbund bildet jetzt auch die **Arbeitsplatzstruktur** nach, die der
Konnektor führt (`[gemSpec_Kon]`, Aufrufkontext `TUC_KON_000`):

```mermaid
graph TB
    P["Praxis"] --> E["Arbeitsplatz Empfang"]
    P --> B1["Arbeitsplatz Behandlung 1"]
    P --> B2["Arbeitsplatz Behandlung 2"]
    P --> B3["Arbeitsplatz Behandlung 3"]
    E --> KE["Kartenterminal<br/><b>MIT SMC-B</b>"]
    B1 --> K1["Kartenterminal<br/>ohne SMC-B"]
    B2 --> K2["Kartenterminal<br/>ohne SMC-B"]
    B3 --> K3["Kartenterminal<br/>ohne SMC-B"]

    classDef smcb fill:#e2f2e9,stroke:#0f7b3f,color:#101623
    class KE smcb
```

> Für die PIN-Automatik ist damit **genau ein** Terminal je Praxis von Belang.
> Die übrigen werden überwacht, aber nie verifiziert — sie haben nichts
> freizuschalten.


Im gesamten Verbund: **36 Arbeitsplätze, 36 Terminals, davon 9 mit SMC-B** —
eine je Praxis. Alle Terminals haben die Managementschnittstelle freigeschaltet
(Vorgabe PO, 07.08.2026).

Warum die Arbeitsplätze im eigenen Datenmodell stehen und nicht nur im
Konnektor: Der Aufrufkontext entscheidet, **welches** Terminal der Konnektor
anspricht. Ohne die Zuordnung im eigenen Bestand könnte der Agent nicht
bestimmen, mit welcher Arbeitsplatz-Kennung er eine bestimmte SMC-B erreicht.

---

## Nachweise

**Modultests** (23 neue): `GeheimnisspeicherTest` (8), `BestandsverwaltungTest`
(15), `DemodatenTest` (8, aus derselben Iteration).

Bemerkenswert daran:

- **Nonce-Wiederverwendung** wird geprüft — sie würde AES-GCM vollständig
  brechen, und gleiche Chiffrate würden verraten, dass zwei Geräte dasselbe
  Kennwort haben.
- **Manipulation am Chiffrat** muss auffliegen und eine Meldung erzeugen, die
  sagt, was zu tun ist.
- **Kein Klartext in `toString()`** — sonst landet ein Kennwort irgendwann in
  einer Fehlermeldung oder einem Protokolleintrag.
- Die Erreichbarkeitsprüfung wird gegen `192.0.2.0/24` gemessen (nach RFC 5737
  für Dokumentation reserviert, nirgends geroutet) — ein verlässlicher
  Zeitüberschreitungsfall.

**Systemnachweise** (16 neue): Anlegen trotz Nichterreichbarkeit, Kennwort
verschlüsselt in der Datenbank, Kennwort auf keiner Oberflächenseite, A_24080
beim Anlegen abgelehnt, Löschschutz, doppelte Kennung, Protokollierung, kein
Kennwort im Fachprotokoll — dazu 13 Nachweise zum Demodatensatz.

---

## Ein Nachweis, der falsch gebaut war

Zwei Prüfungen (`Not-Aus ist protokolliert`, `abgelehnte Registrierungen werden
protokolliert`) flatterten: mal grün, mal rot, ohne Änderung am Produkt.

Beide prüften in **einem** Griff zwei verschiedene Dinge — ob ein Ereignis im
Protokoll *steht* und ob es in der *Ansicht* erscheint. Bei einem Fehlschlag war
nicht erkennbar, welches von beidem gescheitert war. Beide sind jetzt getrennt,
und im Fehlerfall geben sie eine Diagnose aus (Anzahl in der Datenbank, Anzahl
jüngerer Einträge, HTTP-Status).

Das reiht sich in eine Erfahrung ein, die dieses Projekt mehrfach gemacht hat:
**Etwa die Hälfte der Fehler saß in den Prüfungen, nicht im Produkt.** Ein
Nachweis, der nicht sagt, *was* er misst, ist kein Nachweis.

---

## Sortierung, Filter und Massenaktionen (nachgetragen 07.08.2026)

Damit ist FA-GER-060 vollständig, bis auf die Leistungsmessung bei 5.000 Geräten.

### Sortierung — eine Weißliste, kein Durchreichen

Das Sortierfeld kommt aus der URL und geht **unmittelbar** in die Abfrage ein;
als Parameter binden lässt es sich nicht. Ohne feste Liste wäre das eine
SQL-Einschleusung. Deshalb bildet eine Aufzählung `Sortierung` jeden Wert auf
einen festen Ausdruck ab — ein unbekannter Wert führt zur Vorgabe, nicht zu
einem Fehler und erst recht nicht in die Abfrage.

Jede Sortierung trägt **`t.id` als zweites Feld**. Ohne eindeutige Reihenfolge
wandern Zeilen zwischen den Seiten, sobald zwei Geräte denselben Wert haben —
bei „Firmware 4.2.1" ist das der Regelfall, nicht die Ausnahme.

Die Kopfzeile setzt `aria-sort`, damit Screenreader die aktuelle Sortierung
ansagen (WCAG 2.2 AA).

### Filter

Status, Erreichbarkeit, Modell, Firmware — die Auswahllisten für Modell und
Firmware werden aus dem Bestand erzeugt und bieten nur an, was vorkommt.

Ein Detail mit Wirkung: **Ein nie geprüftes Gerät ist nicht „nicht erreichbar",
sondern unbekannt.** Der Filter „nicht erreichbar" sammelt es deshalb nicht ein —
sonst suchte der Betreiber Fehler, wo keine sind.

### Massenaktionen

Aktivieren, Deaktivieren, Gruppe zuweisen. Zwei Regeln:

**1. Nichts läuft stillschweigend halb durch.** Das Ergebnis zählt geänderte und
übersprungene Geräte getrennt. „Aktion ausgeführt" bei 40 von 50 wäre eine
Falschmeldung — und bei 5.000 Geräten fällt so etwas niemandem auf, wenn man es
nicht ausdrücklich sagt.

**2. Eine Gruppe nimmt nur Geräte ihrer eigenen Einrichtung auf** (FA-GER-050).
Fremde werden übersprungen **und benannt**. Wäre das über eine Massenaktion
aushebelbar, stünde die Mandantentrennung offen.

Die Auswahl wird laufend mitgezählt und vor dem Ausführen bestätigt. Ohne diese
Rückmeldung klickt man auf „Deaktivieren" und weiß nicht, ob 3 oder 30 Geräte
gemeint sind.

### Ein Fehler, den die Tests gefunden haben

Die Hilfsmethode, die Filterwerte in die Abfrage setzt, machte aus allem außer
`Long` eine Zeichenkette. Ein `Boolean` wurde damit zu `"true"` und verglich sich
gegen eine `boolean`-Spalte **ins Leere**.

Der Filter lieferte lautlos null Treffer statt eines Fehlers. Solche Fehler sind
die unangenehmsten: Die Oberfläche sagt „keine Geräte", und niemand merkt, dass
die Frage falsch gestellt war. Gefunden hat ihn ein Test, der nach SMC-B
filterte und drei Treffer erwartete.

Zweiter Befund derselben Klasse: `List.toArray()` liefert `Object[]`, der
PostgreSQL-Treiber braucht `Long[]` — die Massenaktion scheiterte zur Laufzeit
und meldete „0 geändert".
