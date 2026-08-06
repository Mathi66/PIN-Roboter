# ADR-0005: Protokollierung und Nachvollziehbarkeit

- **Status:** **Angenommen** (Product Owner, 06.08.2026)
- **Datum:** 06.08.2026
- **Betrifft:** UX, Datenmodell, Datenschutz, Betrieb

---

## Kontext

Vorgabe des POs vom 06.08.2026:
> Wir brauchen ein sehr gutes UX, um alles nachvollziehen zu können. Weiterhin
> ein sehr gut strukturiertes Log, das man ansehen und filtern kann und auch als
> Excel-Datei herunterladen kann.

Das ist mehr als eine Komfortanforderung. Der PIN-Roboter **handelt selbsttätig**:
Er gibt PINs ein und rollt Firmware aus. Zwei Konsequenzen:

1. **Vertrauen entsteht nur durch Sichtbarkeit.** Ein Automat, dessen Handeln
   man nicht nachvollziehen kann, wird abgeschaltet — spätestens bei der ersten
   unerklärten Störung.
2. **Im Störungsfall muss die Ursache in Minuten auffindbar sein.** „Warum war
   die SMC-B heute früh nicht verifiziert?" ist die Frage, die dieses Produkt
   beantworten können muss. Wer sie nicht beantworten kann, hat den TI-Zugang
   nicht im Griff.

---

## Die Mengenfalle

Bei 5.000 Terminals und einem Prüfintervall von 60 Sekunden entstehen rund
**7,2 Millionen Statusabfragen pro Tag**. Würde jede davon protokolliert, wäre das
Protokoll nach einer Woche unbenutzbar und die Datenbank unbeherrschbar.

**Grundregel: Protokolliert werden Zustandsänderungen und Handlungen, nicht
Abfragen.** Eine Statusabfrage, die den erwarteten Zustand bestätigt, ist kein
Ereignis — sie aktualisiert nur den Zeitstempel „zuletzt geprüft" am Gerät.

Das ist keine Sparmaßnahme, sondern der Unterschied zwischen einem Protokoll, in
dem man etwas findet, und einem, in dem man ertrinkt.

---

## Zwei getrennte Protokolle

Ein einziges Protokoll kann nicht beides sein. Deshalb zwei, mit
unterschiedlichem Zweck, Publikum und Aufbewahrung:

| | **Fachprotokoll** (Audit) | **Technisches Protokoll** (Diagnose) |
|---|---|---|
| Frage | Was ist passiert und wer war es? | Warum hat die Technik sich so verhalten? |
| Publikum | Administratoren, Benutzer, Revision | Support, Entwicklung |
| Inhalt | fachliche Ereignisse in verständlicher Sprache | Verbindungsfehler, Zeitüberschreitungen, technische Details |
| Ort | Datenbank, in der Oberfläche sichtbar | Container-Ausgabe, strukturiert (JSON) |
| Änderbarkeit | **nicht änderbar**, nur anfügen | rotierend, begrenzt aufbewahrt |
| Export | ja, Excel | nein (Support-Paket separat) |

Die Vorgabe des POs betrifft das **Fachprotokoll**. Das technische Protokoll ist
davon unberührt und bleibt für den Support.

---

## Das Fachprotokoll

### Aufbau eines Ereignisses

Jedes Ereignis ist **strukturiert**, nicht Freitext. Freitext lässt sich nicht
filtern, nicht auswerten und nicht sauber übersetzen. Feste Felder:

| Feld | Inhalt |
|---|---|
| Zeitpunkt | mit Zeitzone, in der Anzeige lokal |
| Mandant / Einrichtung | für die Mandantentrennung verpflichtend |
| Gerätegruppe, Kartenterminal, Karte | soweit betroffen |
| Ereignisart | feste Liste (siehe unten) |
| Ergebnis | Erfolg / Fehlgeschlagen / Abgebrochen / Übersprungen |
| Auslöser | Automatik oder benannter Benutzer |
| Details | strukturierte Zusatzfelder je Ereignisart |
| Vorgangsnummer | verbindet zusammengehörige Ereignisse, z. B. einen Rollout |

**Die Vorgangsnummer ist der Schlüssel zur Nachvollziehbarkeit.** Ein
nächtlicher Rollout über 24 Terminals erzeugt hunderte Ereignisse. Ohne
gemeinsame Klammer sind sie eine Liste; mit ihr sind sie ein Vorgang, den man
aufklappen und verstehen kann.

### Was protokolliert wird

**PIN-Verwaltung**
- SMC-B-Status hat sich geändert (verifiziert → nicht verifiziert und umgekehrt)
- Verifikation ausgelöst, mit Ergebnis
- Verifikation fehlgeschlagen, mit verbleibenden Versuchen
- **Abbruch wegen sinkender Restversuche** — das wichtigste Ereignis überhaupt
- Karte gesperrt
- PIN hinterlegt oder geändert (wer, wann, für welche Karte — **niemals der Wert**)

**Firmware**
- Firmware hochgeladen (wer, welche Datei, Prüfsumme, für welche Modelle)
- Rollout-Plan angelegt oder geändert
- Rollout gestartet, Fenster geöffnet, Fenster geschlossen
- Gerät aktualisiert (von Version, auf Version, Dauer)
- Gerät übersprungen (nicht erreichbar, wievielter Versuch)
- Gerät nach Fehlversuchen aus dem Rollout genommen
- Kanarienvogel fehlgeschlagen, Rollout gestoppt
- Anschließende Wiederverifikation der SMC-B, mit Ergebnis

**Geräte und Verbindungen**
- Terminal erstmals erkannt, nicht mehr erreichbar, wieder erreichbar
- Konnektor/HSK nicht erreichbar
- Standort-Agent verbunden oder getrennt
- PEviM am Gerät nicht aktiv (verhindert die Automatik — muss sichtbar sein)

**Benutzer und Sicherheit**
- Anmeldung erfolgreich oder fehlgeschlagen, TOTP-Einrichtung, Sperrung
- Benutzer angelegt, geändert, Rolle geändert, gelöscht
- Serviceorganisation oder Einrichtung angelegt oder geändert
- Not-Aus betätigt
- Änderungen an Zugangsdaten (Terminal-Administration, GHCR)

**Produktupdates** — Freigabe, Ergebnis, Rollback (siehe ADR-0001)

### Was niemals im Protokoll steht

SMC-B-PINs, Terminal-Administrationskennwörter, GHCR-Zugangsdaten, Passwörter,
TOTP-Geheimnisse — weder im Klartext noch verschleiert, weder im Fachprotokoll
noch im technischen Protokoll, noch in Fehlermeldungen, Stapelüberwachungen oder
Exportdateien. **Technisch erzwungen an einer zentralen Stelle**, nicht durch
Sorgfalt bei jeder einzelnen Protokollzeile.

---

## UX: Nachvollziehbarkeit in der Oberfläche

Ein Protokoll allein ist noch keine Nachvollziehbarkeit. Vier Sichten, die
aufeinander verweisen:

**1. Lage-Übersicht** — die Startseite. Was ist gerade nicht in Ordnung? Keine
Zahlenfriedhöfe, sondern: wie viele Karten unverifiziert, wie viele Geräte nicht
erreichbar, welche Rollouts laufen, welche Alarme sind offen. Jeder Punkt führt
direkt zur betroffenen Liste.

**2. Geräte-Zeitstrahl** — je Kartenterminal die Frage „was ist mit diesem Gerät
passiert?" chronologisch beantwortet: PIN-Status, Firmware-Wechsel,
Erreichbarkeit, Verifikationen. Das ist die Sicht, die im Störungsfall zuerst
geöffnet wird.

**3. Vorgangsansicht** — ein Rollout oder eine Verifikationsserie als
zusammenhängender Vorgang: Fortschritt, was lief, was steht aus, was ist
fehlgeschlagen. Bei laufenden Vorgängen live.

**4. Protokollansicht** — die vollständige, filterbare Liste (unten).

### Verständlichkeit statt Fehlercodes

Fehlermeldungen benennen **was zu tun ist**, nicht nur was schiefging.

> Nicht: `PinResult=REJECTED, LeftTries=2`
> Sondern: **„Die hinterlegte PIN wurde vom Kartenterminal abgelehnt. Noch
> 2 Versuche, danach sperrt die Karte. Die Automatik wurde für diese Karte
> angehalten. Bitte die hinterlegte PIN prüfen."**
> Technische Details bleiben aufklappbar verfügbar — für den Support, nicht als
> Erstinformation.

Das gilt besonders für die Fehlercodes des Konnektors: `4078` („PIN-Eingabe über
das Clientsystem ist nicht zugelassen") ist für eine Praxismitarbeiterin
bedeutungslos und muss übersetzt werden.

---

## Filtern

Verpflichtende Filter, kombinierbar: Zeitraum (mit sinnvollen Vorgaben — heute,
7 Tage, 30 Tage), Mandant/Einrichtung, Gerätegruppe, einzelnes Terminal,
Ereignisart, Ergebnis, Auslöser (Automatik/Benutzer), Vorgangsnummer, Volltext
über die Detailfelder.

Umsetzungsvorgaben:
- **Filterung und Sortierung serverseitig**, mit Paginierung. Bei Millionen
  Zeilen scheidet Filtern im Browser aus.
- **Der aktive Filter steht in der Adresszeile**, damit eine Ansicht per Link
  weitergegeben werden kann („schau dir das an").
- **Die Mandantentrennung ist kein Filter**, sondern eine nicht abschaltbare
  Einschränkung. Sie wird zentral erzwungen — siehe `CLAUDE.md`.
- **Indizierung von Anfang an** auf Zeit, Mandant, Gerät und Ereignisart. Ohne
  sie ist die Ansicht bei realen Datenmengen unbrauchbar.

---

## Excel-Export

Format **`.xlsx`**, nicht CSV — CSV führt bei deutschen Excel-Einstellungen
regelmäßig zu zerschossenen Spalten und verstümmelten Umlauten.

Anforderungen:
- **Exportiert wird genau die gefilterte Ansicht** — was man sieht, bekommt man.
- Erste Zeile mit Spaltenüberschriften, **fixiert**; Autofilter gesetzt.
- Datums- und Zahlenspalten als echte Datums- und Zahlenwerte, nicht als Text.
- **Ein Deckblatt** mit Exportzeitpunkt, ausführendem Benutzer, angewendeten
  Filtern und Zeilenzahl. Ohne diese Angaben ist eine Exportdatei als Nachweis
  wertlos.
- **Große Exporte laufen im Hintergrund**: Die Datei wird erzeugt, der Benutzer
  erhält eine Benachrichtigung und lädt sie herunter. Ein synchroner Export über
  hunderttausende Zeilen läuft in eine Zeitüberschreitung.
- **Excel-Zeilengrenze beachten** (1.048.576 Zeilen je Blatt). Bei Überschreitung
  wird der Benutzer **vorher** gewarnt und zum Einschränken des Zeitraums
  aufgefordert — nicht stillschweigend abgeschnitten.
- Der Export ist selbst ein protokollpflichtiges Ereignis: Wer hat wann welche
  Daten exportiert? Ein Export kann personenbezogene Daten enthalten.

---

## Speicherverwaltung: Wie viel Platz braucht das wirklich?

Vorgabe des POs vom 06.08.2026:
> Ich habe Angst, dass uns der Speicher platzt. Am besten wäre eine separate
> Log-Partition, deren Größe beim Setup je nach Umgebung eingestellt wird. Das
> Log fängt an zu rotieren, wenn 80 % der Partition voll sind.

Die Sorge ist berechtigt und die Zielrichtung richtig. Zwei Dinge sind dabei zu
trennen: **wie groß das Problem tatsächlich ist** und **wie die Begrenzung
technisch durchgesetzt wird**.

### Größenordnung — überschlagen, nicht geraten

Ein Fachprotokoll-Eintrag belegt strukturiert und indiziert grob **500 Byte**.
Entscheidend ist die Ereignisrate, und die hängt an der oben festgelegten
Grundregel (nur Zustandsänderungen, keine Abfragen):

| Szenario | Ereignisse/Tag | Speicher/Tag | Speicher/Jahr |
|---|---|---|---|
| Einzelpraxis, 3 Terminals | ~20 | 10 KB | ~4 MB |
| Klinik, 5.000 Terminals, Normalbetrieb | ~25.000 | ~12 MB | **~4,5 GB** |
| Klinik, Tag mit Flotten-Rollout | + ~40.000 | + ~20 MB | einmalig |
| Klinik, Störungstag (Konnektor weg) | bis ~10-fach | ~120 MB | selten |

> **Annahme, ausdrücklich als solche gekennzeichnet:** ~5 protokollwürdige
> Ereignisse je Terminal und Tag im Normalbetrieb. Dieser Wert ist **am
> Testaufbau und im Pilotbetrieb zu messen** und danach zu korrigieren. Die
> Größenordnung — Gigabyte pro Jahr, nicht Terabyte — ist dabei belastbar, der
> genaue Wert nicht.

**Ergebnis:** Selbst die größte Ausbaustufe erzeugt im Normalbetrieb wenige
Gigabyte pro Jahr. Das ist beherrschbar. Der eigentliche Speicherfresser ist
nicht das Fachprotokoll, sondern das **technische Protokoll** bei Dauerstörungen
— und genau dort ist eine harte Grenze am wichtigsten.

Die Sorge ist also richtig, aber die Lösung muss nicht groß dimensioniert sein.
Sie muss **zuverlässig greifen**.

### Warum keine echte Partition

Eine Partition anzulegen ist Sache des Host-Betriebssystems, nicht des
Containers. Bei drei Zielplattformen (Linux, Windows, macOS) scheidet das als
Grundmechanismus aus: Unter Docker Desktop liegt der Speicher in einer verwalteten
virtuellen Maschine, auf die wir keinen Partitionszugriff haben. Ein Mechanismus,
der nur unter Linux funktioniert, ist für dieses Produkt keiner.

Hinzu kommt ein inhaltlicher Punkt: Wer den **freien Platz der Partition** misst,
misst auch alles, was andere darauf ablegen. Das Protokoll würde dann rotieren,
weil jemand anderes die Platte füllt — oder eben nicht rotieren, obwohl es selbst
längst zu groß ist.

### Umsetzung: drei Ebenen

**Ebene 1 — Speicherbudget in der Anwendung (der Hauptmechanismus)**

Beim Setup wird je Datenart ein **Speicherbudget in Gigabyte** eingestellt,
passend zur Umgebung. Die Anwendung überwacht **ihre eigene Belegung** und
handelt selbst:

| Füllstand | Verhalten |
|---|---|
| **80 %** | Rotation beginnt: älteste Einträge werden gelöscht, damit der Füllstand unter 80 % bleibt. Hinweis in der Oberfläche. |
| **90 %** | Warnung als Alarm, mit Empfehlung: Budget erhöhen oder Aufbewahrungsdauer verkürzen. |
| **95 %** | Verschärfte Rotation. Die **Kernfunktionen laufen weiter** (siehe unten). |

Das entspricht genau der Vorgabe des POs — nur bezogen auf ein eigenes Budget
statt auf eine Partition, und damit auf allen drei Plattformen funktionsfähig.

**Ebene 2 — getrennte Ablage**

Protokolldaten liegen in einem **eigenen Volume**, getrennt von den Betriebsdaten
(Geräte, Benutzer, Konfiguration). Damit reißt ein volllaufendes Protokoll die
Betriebsdatenbank nicht mit. Das ist der wesentliche Nutzen, den die
Partitionsidee anstrebt — und er ist mit Volumes plattformunabhängig zu haben.

Betreiber, die unter Linux zusätzlich eine echte Partition oder ein eigenes
Dateisystem für dieses Volume bereitstellen wollen, können das tun. Wir
**unterstützen** es, aber wir **verlassen uns nicht darauf**.

**Ebene 3 — Beobachtung des tatsächlichen Speichers als Sicherheitsnetz**

Zusätzlich wird der real verfügbare Platz überwacht. Wird er knapp — etwa weil
etwas anderes auf dem Host die Platte füllt —, greift die verschärfte Rotation,
unabhängig vom eigenen Budget, und es wird alarmiert.

### Gestufte Rotation: Nicht alle Einträge sind gleich wertvoll

Beim Löschen wird **nach Wichtigkeit** gestaffelt, nicht nur nach Alter:

1. **Zuerst:** technische Diagnosedaten
2. **Dann:** fachliche Routineereignisse (erfolgreiche Verifikationen,
   Erreichbarkeitswechsel)
3. **Zuletzt:** sicherheitsrelevante Ereignisse — Anmeldungen, Rechteänderungen,
   PIN-Hinterlegungen, Not-Aus, gesperrte Karten, fehlgeschlagene Rollouts

Bevor die dritte Stufe erreicht wird, **fordert das System aktiv zum Export
auf** und meldet deutlich, dass die Nachweiskette sonst lückenhaft wird. Ein
Audit-Eintrag, der stillschweigend gelöscht wird, ist ein doppelter Schaden: Die
Information ist weg, und niemand weiß es.

### Die wichtigste Regel

> **Ein volles Protokoll darf niemals die Kernfunktion anhalten.**
>
> Wenn der Speicher knapp wird, rotiert das Protokoll — der PIN-Roboter
> verifiziert weiter. Eine Praxis darf nicht deshalb den TI-Zugang verlieren,
> weil eine Protokolldatei voll ist. Umgekehrt darf er auch nicht still ohne
> Protokoll weiterlaufen: Der Zustand ist deutlich sichtbar zu machen und zu
> alarmieren.

### Vorgabewerte beim Setup

Der Einrichtungsassistent schlägt anhand der Umgebungsgröße vor und erklärt die
Wirkung in Klartext („reicht bei Ihrer Gerätezahl für etwa X Monate"):

| Umgebung | Fachprotokoll | Technisches Protokoll |
|---|---|---|
| Einzelpraxis / MVZ | 1 GB | 1 GB |
| Klinik / Serviceorganisation | 20 GB | 5 GB |

Die Werte sind **Vorschläge auf Basis der obigen Überschlagsrechnung** und nach
der Messung im Pilotbetrieb zu korrigieren.

---

## Aufbewahrung und Datenschutz

Das Fachprotokoll enthält personenbezogene Daten — es hält fest, welcher Benutzer
wann was getan hat. Auch ohne Patientendaten gilt die DSGVO.

- **Aufbewahrungsdauer konfigurierbar**, mit fachlich begründetem Vorgabewert.
  Ältere Einträge werden automatisch gelöscht.
- **Verpflichtend zu klären:** Welche Aufbewahrungsdauer ist fachlich richtig?
  Vorschlag: 12 Monate für fachliche Ereignisse, 90 Tage für technische
  Diagnosedaten. **Diese Werte sind ein Vorschlag, keine Rechtsauskunft** — sie
  gehören vor der Auslieferung von jemandem geprüft, der das beurteilen darf.
- Das Fachprotokoll ist **nur anfügbar**. Es gibt keine Funktion zum Ändern oder
  Löschen einzelner Einträge — sonst ist es als Nachweis wertlos.
- **Aufbewahrung und Export sind mandantengetrennt.** Eine Serviceorganisation
  exportiert nie Daten einer fremden Einrichtung.

---

## Konsequenzen

- Das Fachprotokoll ist eine **eigene, sorgfältig entworfene Datenstruktur**, kein
  Nebenprodukt der Anwendungsprotokollierung. Es gehört ins Datenmodell der ersten
  Version.
- **Ereignisarten sind eine gepflegte, feste Liste.** Neue Funktionen bringen neue
  Ereignisarten mit — das ist Teil der Definition of Done.
- Es wird ein **Mengenwachstums-Test** benötigt: Verhalten der Protokollansicht
  bei mehreren Millionen Einträgen, gemessen, nicht geschätzt.
- Die Aufbewahrungsdauer braucht vor Auslieferung eine datenschutzrechtliche
  Bewertung.

## Offene Frage an den PO

**Aufbewahrungsdauer:** Sind 12 Monate für fachliche Ereignisse und 90 Tage für
technische Diagnosedaten angemessen, oder gibt es Vorgaben aus dem Umfeld der
Kunden (Revision, Zertifizierung, Verträge), die längere Fristen verlangen?
