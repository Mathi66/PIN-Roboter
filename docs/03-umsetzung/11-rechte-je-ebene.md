# Umsetzung: Rechte je Ebene

**Anforderung:** CLAUDE.md Abschnitt 8 („Berechtigungen sind hierarchisch und
mandantengebunden"), Block A

---

## Zwei Fragen, nicht eine

Bis zum 07.08.2026 entschied allein die **Rolle**: Administrator oder Benutzer.
Der `Geltungsbereich` kam dazu und regelt, **welche Daten** jemand sieht.

Was fehlte, ist die zweite Frage: **welche Art von Handlung** ihm zusteht.

| | regelt | Beispiel |
|---|---|---|
| `Geltungsbereich` | welche Daten | Praxis A sieht nur ihre Geräte |
| `Rechte` | welche Handlung | Praxis A legt keine Einrichtungen an |

Erst beides zusammen ergibt die Berechtigung. Ein Praxisadministrator darf die
Geräte seiner Praxis verwalten — aber keinen Demodatensatz über die ganze
Instanz ausrollen.

## Die Ebenen

```
Instanzbetreiber   Administrator ohne Organisationszuordnung
 └── Verbund
      └── Standort
           └── Einrichtung
```

Die Ebene ergibt sich aus der **Art** der zugeordneten Organisationseinheit,
nicht aus einem eigenen Feld. Ein zusätzliches Feld wäre eine zweite Wahrheit,
die irgendwann auseinanderläuft.

## Die Regeln

| Handlung | Wer darf | Warum |
|---|---|---|
| **Bestand pflegen** | jeder Administrator, jede Ebene | Wer eine Praxis betreut, verwaltet dort auch Geräte — das ist der Normalfall |
| **Benutzer verwalten** | jeder Administrator, jede Ebene | Sonst hängt jede Personalie an einer zentralen Stelle; bei 1.000 Benutzern trägt das nicht |
| **Organisationen anlegen** | **erst ab Standort** | Ein Praxisadministrator könnte sonst seinen eigenen Geltungsbereich erweitern — die Mandantengrenze wäre von innen aufhebbar |
| **Demodatensatz, Lasttest** | **nur Instanzbetreiber** | Beide legen Daten quer über alle Mandanten an oder entfernen sie |
| **Not-Aus** | jeder Administrator | Ein Sicherheitsmittel darf nicht an einer Genehmigung hängen |

**Ein Benutzer ohne Zuordnung darf nichts** — nicht alles. Dieselbe Richtung wie
beim Geltungsbereich, und aus demselben Grund: Ein Fehler in der Zuordnung darf
nie zu uneingeschränktem Zugriff führen. Auch eine gelöschte Organisationseinheit
lässt keine Rechte übrig.

## Warum eine eigene Klasse

Damit die Regeln an **einer** Stelle stehen und sich vorlesen lassen. Über
mehrere Routen verstreute `if`-Abfragen sind das, was Berechtigungsmodelle
unprüfbar macht — man findet sie beim Nachlesen nicht mehr alle.

## Die Abweisung sagt, was fehlt

```
Das Anlegen des Demodatensatzes ist Ihnen nicht gestattet.
Ihre Berechtigung: Administrator · Einrichtung.
```

Ein blankes „Nur Administratoren" lässt jemanden mit Administratorrolle ratlos
zurück, wenn in Wahrheit die **Ebene** nicht reicht. Er sucht dann an der
falschen Stelle — bei seiner Rolle statt bei seiner Zuständigkeit.

---

## Ein Befund: Der Not-Aus wirkt instanzweit

Der Not-Aus ist ein globaler Schalter. Bei mehreren Mandanten an einer Zentrale
hält ein **Praxisadministrator damit auch fremde Standorte an**.

Das ist bewusst so belassen, nicht übersehen. Die Alternative wäre, ihn für
Praxisadministratoren zu sperren — und bei einem Sicherheitsmittel ist das die
schlechtere Wahl: Wer den Verdacht hat, dass der Roboter falsch handelt, muss ihn
sofort anhalten können.

Zwei Dinge sind dafür geändert:

- Der Not-Aus **prüft jetzt überhaupt Rechte** — vorher konnte ihn jeder
  angemeldete Benutzer auslösen, auch ohne Administratorrolle.
- Er protokolliert **mit Namen**. Bisher stand dort nur „Benutzer"; wer ihn
  ausgelöst hatte, war nicht nachvollziehbar. Bei einem Mittel, das den
  TI-Zugang anhält, ist das zu wenig.

Der eigentliche Umbau — ein Not-Aus je Einrichtung — bleibt offen und steht in
`docs/05-sicherheit-datenschutz.md`. Er berührt auch den Agenten, der den
Zustand global abfragt.

---

## Nachweise

**`RechteTest`, 8 Tests**: Ebene aus der Organisationsart, kein Instanzbetreiber
ohne Administratorrolle, keine Rechte bei gelöschter Einheit, Organisationen erst
ab Standort, instanzweite Werkzeuge nur für den Betreiber.

**Systemnachweise** über HTTP mit echter Anmeldung eines Praxisbenutzers: Er
bekommt HTTP 403 auf Demodatensatz und Lasttest, der Demodatensatz ist danach
unverändert, und die Abweisung nennt seine eigene Berechtigung.
