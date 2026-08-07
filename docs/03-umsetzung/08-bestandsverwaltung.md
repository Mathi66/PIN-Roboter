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

`FA-GER-060` verlangt Bedienbarkeit bei 5.000 Terminals. Vollständig umgesetzt
ist die Anforderung noch nicht (Massenaktionen und Sortierung fehlen), die
Grundlage steht aber:

- **serverseitige Paginierung** — 25 Treffer je Seite, `LIMIT`/`OFFSET` in der
  Abfrage, nicht im Browser
- **Volltextsuche** über Bezeichnung, Kennung und Standort
- **Filter** nach Konnektor
- **Index** auf `lower(bezeichnung)` und `lower(standortangabe)` — bei 5.000
  Geräten entscheidet er darüber, ob die Liste unter einer Sekunde steht
- **obere Grenze von 200** Datensätzen je Anfrage, damit eine manipulierte URL
  nicht den gesamten Bestand zieht

Die Zählabfrage läuft getrennt von der Datenabfrage. Das ist eine Abfrage mehr,
aber die Alternative — alles laden und im Speicher zählen — ist genau das, was
bei 5.000 Geräten nicht mehr funktioniert.

---

## Demodatensatz: das Informationsmodell des Konnektors

Der MVZ-Verbund bildet jetzt auch die **Arbeitsplatzstruktur** nach, die der
Konnektor führt (`[gemSpec_Kon]`, Aufrufkontext `TUC_KON_000`):

```
Praxis
 ├── Arbeitsplatz Empfang       → 1 Kartenterminal MIT SMC-B
 ├── Arbeitsplatz Behandlung 1  → 1 Kartenterminal ohne SMC-B
 ├── Arbeitsplatz Behandlung 2  → 1 Kartenterminal ohne SMC-B
 └── Arbeitsplatz Behandlung 3  → 1 Kartenterminal ohne SMC-B
```

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
