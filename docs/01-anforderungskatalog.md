# Anforderungskatalog PIN-Roboter

- **Stand:** 06.08.2026
- **Status:** **Abgenommen** durch den Product Owner am 06.08.2026

---

## Wie dieses Dokument zu lesen ist

### Kennung
`FA-<Bereich>-<Nr>` für funktionale, `NFA-<Bereich>-<Nr>` für nichtfunktionale
Anforderungen. Kennungen werden **nie wiederverwendet**. Entfällt eine
Anforderung, bleibt sie mit dem Vermerk *entfallen* stehen.

### Priorität
| | Bedeutung |
|---|---|
| **MUSS** | Ohne diese Anforderung ist das Produkt nicht auslieferbar. |
| **SOLL** | Wichtig; Weglassen mindert den Wert spürbar und ist zu begründen. |
| **KANN** | Wünschenswert, verhandelbar. |

### Stufe
`V1` = erste auslieferbare Version · `V2` = darauf folgende Ausbaustufe
`V1‑vorb.` = in V1 nur im Datenmodell vorzubereiten, Funktion später

### Herkunft
`PO` = Vorgabe des Product Owners · `ADR-n` = Architekturentscheidung ·
`gemSpec` = zwingend aus der gematik-Spezifikation, mit Anforderungs-ID ·
`PO ✓ (freigegeben 06.08.2026)` = von der Entwicklung vorgeschlagen und vom
Product Owner abgenommen

> **Alle Anforderungen dieses Katalogs sind abgenommen.** Die 13 ursprünglich als
> Vorschlag der Entwicklung gekennzeichneten Anforderungen wurden am 06.08.2026
> freigegeben. Neue Vorschläge erhalten wieder die Kennzeichnung `Vorschlag` und
> gelten erst nach ausdrücklicher Freigabe.

### Akzeptanzkriterien
Jede Anforderung trägt Kriterien, die **beobachtbar oder messbar** sind. Wo ein
Zielwert steht, ist er zu messen, nicht zu schätzen. Formulierungen wie
„performant", „benutzerfreundlich" oder „sicher" sind in diesem Dokument
unzulässig — sie sind nicht prüfbar.

> **Grundsatz:** Kein Akzeptanzkriterium gilt als erfüllt, solange es nicht am
> Testaufbau nachgewiesen wurde. Aussagen aus Spezifikationen sind Grundlage der
> Umsetzung, aber kein Nachweis.

---

# Teil A — Funktionale Anforderungen

## A.1 Bestandsverwaltung (FA-GER)

### FA-GER-010 — Konnektoren und HSK erfassen · MUSS · V1 · PO
Konnektoren und Highspeed-Konnektoren werden mit Adresse, Zugangsdaten und
Aufrufkontext (Mandant-ID, Clientsystem-ID, Arbeitsplatz-ID) erfasst.

*Akzeptanz:*
- Ein Konnektor lässt sich anlegen, ändern, deaktivieren und löschen.
- Beim Anlegen prüft das System die Erreichbarkeit und meldet das Ergebnis
  innerhalb von 10 Sekunden; ein nicht erreichbarer Konnektor kann trotzdem
  gespeichert werden (Ersteinrichtung vor Inbetriebnahme).
- Zugangsdaten sind nach dem Speichern über die Oberfläche und über jede
  Schnittstelle **nicht mehr auslesbar**.
- Löschen ist nur möglich, wenn keine Terminals zugeordnet sind; andernfalls
  erscheint ein erklärender Hinweis mit Verweis auf die betroffenen Geräte.

### FA-GER-020 — Kartenterminals erfassen · MUSS · V1 · PO
Kartenterminals werden mit Adresse, Bezeichnung, Standort, Zuordnung zu
**Einrichtung** und Gerätegruppe sowie Administrationszugang erfasst. Die
Zuordnung zu einem Konnektor ist möglich, aber **nicht Voraussetzung**.

*Akzeptanz:*
- Anlegen, Ändern, Deaktivieren, Löschen möglich.
- Erreichbarkeitsprüfung beim Anlegen mit Ergebnis binnen 10 Sekunden.
- Auslesbar und angezeigt werden mindestens: Modell, Firmware-Version,
  Seriennummer soweit verfügbar.
- Administrationszugangsdaten sind nach dem Speichern nicht mehr auslesbar.
- Ein Terminal ist genau einem Mandanten und genau einer Gerätegruppe zugeordnet.
- **Ein Terminal lässt sich ohne Konnektor erfassen** und direkt einer
  Einrichtung zuordnen (Vorgabe PO, 11.08.2026). Es wird dann überwacht und
  verwaltet, nimmt aber nicht an der PIN-Automatik teil — das muss die
  Oberfläche im Klartext sagen, nicht stillschweigend geschehen.
- **Der Konnektor lässt sich nachtragen**, sobald das Gerät dort angemeldet ist.
- Terminal und Konnektor müssen zur **selben Einrichtung** gehören; eine
  Zuordnung über Mandantengrenzen hinweg wird mit Begründung abgelehnt.
- **Standardsortierung der Geräteliste ist die Einrichtung**, danach Konnektor,
  danach Gerätename. Grund: In einem MVZ heißen die Geräte in jeder
  Betriebsstätte gleich; alphabetisch nach Gerätenamen entsteht eine Liste, in
  der neunmal „Behandlung 1" untereinander steht.

### FA-GER-030 — Kartenterminals automatisch ermitteln · SOLL · V1 · PO ✓ (freigegeben 06.08.2026)
Über den Konnektor (`GetCardTerminals`) werden vorhandene Terminals ermittelt
und zur Übernahme angeboten.

*Akzeptanz:*
- Die Abfrage listet alle dem Konnektor bekannten Terminals mit Name und Adresse.
- Bereits erfasste Geräte werden als solche erkannt und nicht doppelt angelegt.
- Die Übernahme erfolgt erst nach ausdrücklicher Bestätigung, einzeln oder als
  Auswahl — nie automatisch.

### FA-GER-035 — Informationsmodell des Konnektors auslesen · SOLL · V1 · PO
Über die Webadmin-Schnittstelle des Konnektors wird dessen Informationsmodell
ausgelesen, um die Einrichtung der Kartenterminals zu vereinfachen.

**Warum das mehr ist als Komfort:** Jeder Aufruf der Fachschnittstelle benötigt
einen Aufrufkontext aus **Mandanten-ID, Clientsystem-ID und Arbeitsplatz-ID**
(`[gemSpec_Kon]`, `TUC_KON_000`). Diese Werte stammen aus der Konfiguration des
Konnektors. Werden sie von Hand abgetippt, ist das die häufigste und am
schwersten zu diagnostizierende Fehlerquelle bei der Inbetriebnahme — ein
Tippfehler äußert sich als Zugriffsfehler ohne erkennbaren Zusammenhang. Aus dem
Informationsmodell werden sie stattdessen **zur Auswahl angeboten**.

*Akzeptanz:*
- Ausgelesen und dargestellt werden mindestens: Mandanten, Clientsysteme,
  Arbeitsplätze, konfigurierte Kartenterminals und deren Zuordnung zueinander.
- Bei der Erfassung eines Konnektors werden Mandanten-, Clientsystem- und
  Arbeitsplatz-ID **aus dem Informationsmodell zur Auswahl angeboten**; die
  manuelle Eingabe bleibt als Rückfallweg möglich.
- Erkannte Terminals werden mit Zuordnung zum Arbeitsplatz zur Übernahme
  angeboten (Zusammenspiel mit FA-GER-030); die Übernahme erfolgt nur nach
  Bestätigung.
- Ist die Webadmin-Schnittstelle nicht verfügbar oder nicht konfiguriert, bleiben
  alle Angaben manuell erfassbar; die Oberfläche weist auf die entfallende
  Unterstützung hin (NFA-QS-035).
- Ein erneutes Auslesen erkennt Abweichungen zwischen Konnektor-Konfiguration
  und erfasstem Bestand und zeigt sie an, ohne selbsttätig zu ändern.

### FA-GER-040 — Karten erfassen · MUSS · V1 · PO
Zu jedem Terminal werden die eingelegten Karten erfasst; für den Betrieb
relevant ist ausschließlich die **SMC-B**.

*Akzeptanz:*
- Angezeigt werden Kartentyp, Kartenhandle, Steckplatz und, sofern verfügbar,
  Ablaufdatum des Zertifikats.
- Karten der Typen eGK und HBA werden erkannt und **ausdrücklich als nicht
  verwaltet gekennzeichnet** (gemSpec `A_24080`).
- Ein Kartenwechsel im Steckplatz wird spätestens beim nächsten Prüfintervall
  erkannt und protokolliert.

### FA-GER-050 — Gerätegruppen · MUSS · V1 · PO
Terminals werden zu Gruppen zusammengefasst (z. B. Abteilungen). Gruppen tragen
Prüfintervalle, Rollout-Pläne und Benachrichtigungsregeln.

*Akzeptanz:*
- Gruppen sind anlegbar, umbenennbar, löschbar; Terminals sind zuordenbar,
  einzeln und als Mehrfachauswahl.
- Jedes Terminal gehört zu genau einer Gruppe; es existiert eine Vorgabegruppe.
- Eine Gruppe liegt innerhalb genau einer Einrichtung.
- Das Löschen einer Gruppe mit zugeordneten Terminals ist nur mit Umzug der
  Geräte möglich — **in die Vorgabegruppe ihrer eigenen Einrichtung**. Ein Umzug
  über die Mandantengrenze ist unzulässig (ergänzt 11.08.2026).
- **Jede Gruppe gehört zu einer Organisationseinheit.** Ohne sie wäre sie für
  jede mandantengebundene Abfrage unsichtbar und ließe sich niemandem zeigen.
  Technisch erzwungen (`NOT NULL`, Migration 23).
- **Jede ändernde Handlung prüft den Geltungsbereich** — Umbenennen, Intervall,
  Vorgabe, Löschen. Eine manipulierte Kennung darf keine fremde Gruppe treffen.
- **Die Vorgabegruppe ist wechselbar**; je Einrichtung gibt es genau eine.

> **Nachtrag 11.08.2026:** „Umbenennbar" stand seit jeher hier, war aber **nie
> umgesetzt**. Wer sich vertippt hatte, musste die Gruppe löschen und neu
> anlegen — und verlor dabei ihre Gerätezuordnung. Der Umsetzungsstand führte
> die Anforderung trotzdem als erfüllt: Kein Nachweis hat je versucht,
> umzubenennen. Eine Akzeptanzliste nützt nur so viel, wie geprüft wird.

### FA-GER-060 — Bestandsübersicht bei großen Stückzahlen · MUSS · V1 · PO
Die Geräteübersicht bleibt bei 5.000 Terminals bedienbar.

*Akzeptanz:*
- Serverseitige Paginierung, Sortierung und Filterung nach Mandant, Einrichtung,
  Gruppe, Status, Modell, Firmware-Stand.
- Volltextsuche über Bezeichnung, Adresse und Standort.
- Erste Seite ist bei 5.000 Datensätzen in **< 1 Sekunde** dargestellt
  (Messbedingungen siehe NFA-LEI-010).
- Mehrfachauswahl mit Massenaktionen: Gruppe zuweisen, Prüfintervall setzen,
  aktivieren/deaktivieren.

---

## A.2 Überwachung (FA-UEB)

### FA-UEB-010 — PIN-Status der SMC-B überwachen · MUSS · V1 · PO, ADR-0002
Der PIN-Status jeder verwalteten SMC-B wird zyklisch über die Konnektor-Operation
`GetPinStatus` mit `PinTyp = PIN.SMC` ermittelt.

*Akzeptanz:*
- Alle sechs Statuswerte werden unterschieden und angezeigt: `VERIFIED`,
  `VERIFIABLE`, `TRANSPORT_PIN`, `EMPTY_PIN`, `BLOCKED`, `DISABLED`.
- Bei `VERIFIABLE` wird die Zahl verbleibender Versuche (`LeftTries`) angezeigt.
- Das Prüfintervall ist je Gruppe konfigurierbar; Vorgabewert 60 Sekunden,
  Untergrenze 10 Sekunden.
- Ein Statuswechsel wird protokolliert; eine Prüfung ohne Wechsel erzeugt
  **keinen** Protokolleintrag, sondern aktualisiert nur den Zeitstempel
  „zuletzt geprüft" (ADR-0005).

### FA-UEB-020 — Erreichbarkeit überwachen · MUSS · V1 · PO ✓ (freigegeben 06.08.2026)
Erreichbarkeit von Konnektoren/HSK, Terminals und Standort-Agenten wird
überwacht.

*Akzeptanz:*
- Jede Komponente trägt einen der Zustände: erreichbar, nicht erreichbar,
  unbekannt.
- Ein Wechsel wird protokolliert und löst die konfigurierte Benachrichtigung aus.
- Bei nicht erreichbarem Konnektor werden die dahinterliegenden Terminals als
  „Status unbekannt" gekennzeichnet, **nicht** als fehlerhaft.

### FA-UEB-030 — Firmware-Stand erfassen · MUSS · V1 · ADR-0004
Firmware-Version und Firmware-Gruppen-Version jedes Terminals werden ausgelesen
und vorgehalten.

*Akzeptanz:*
- Die Version ist je Gerät sichtbar und filterbar.
- Eine Flottenübersicht zeigt die Verteilung der Stände je Modell.
- Ein Versionswechsel wird erkannt und protokolliert, unabhängig davon, wer ihn
  ausgelöst hat.

### FA-UEB-040 — PEviM-Fähigkeit erkennen · MUSS · V1 · gemSpec `A_24074`, ADR-0004
Das System stellt je Terminal fest, ob die PIN-Eingabe über die
Managementschnittstelle unterstützt und aktiviert ist.

*Akzeptanz:*
- Je Gerät wird einer der Zustände angezeigt: unterstützt und aktiv, unterstützt
  aber nicht aktiv, nicht unterstützt, unbekannt.
- Bei „nicht aktiv" nennt die Oberfläche den Grund und die erforderliche
  Handlung (Aktivierung nur durch die Rolle Administrator, `A_24078`).
- Geräte ohne PEviM werden dennoch überwacht; nur die automatische Verifikation
  entfällt und wird als nicht verfügbar ausgewiesen.

### FA-UEB-050 — Zustand „aktualisierend" respektieren · MUSS · V1 · gemSpec, ADR-0004
Läuft am Terminal ein Firmware-Update, werden keine Verifikationsversuche
unternommen.

*Akzeptanz:*
- Der Zustand wird erkannt (Konnektor-Ereignisse `KSR/UPDATE/START` bis
  `KSR/UPDATE/END` mit `Target=KT`) und angezeigt.
- Während dieses Zustands erfolgt **kein** `VerifyPin`.
- Der Nachweis erfolgt am Testaufbau durch ein ausgelöstes Firmware-Update mit
  gleichzeitig anstehender Verifikation; im Protokoll ist das Aussetzen belegt.

---

## A.3 PIN-Verwaltung und automatische Verifikation (FA-PIN)

> Kernfunktion des Produkts. Verfahrensgrundlage: PEviM,
> `[gemSpec_KT] 2.4.9` (`A_24074`–`A_24157`), ausschließlich für SMC-B
> (`A_24080`).

### FA-PIN-010 — PIN hinterlegen · MUSS · V1 · PO, ADR-0002
Zu jeder verwalteten SMC-B kann eine PIN hinterlegt werden.

*Akzeptanz:*
- Die Eingabe erfolgt verdeckt, mit optionaler Sichtbarschaltung während der
  Eingabe und Bestätigungsfeld.
- Die PIN wird **ausschließlich beim Standort-Agenten** abgelegt; die Zentrale
  speichert sie nicht (ADR-0002).
- Nach dem Speichern ist die PIN über keine Oberfläche und keine Schnittstelle
  auslesbar. *Nachweis:* Prüfung von Datenbank, Schnittstellenantworten,
  Protokollen und Sicherung auf das Nichtvorhandensein des Wertes.
- Hinterlegen und Ändern werden protokolliert — mit Benutzer und Zeitpunkt,
  **ohne Wert**.
- Eine hinterlegte PIN kann gelöscht werden; danach ruht die Automatik für diese
  Karte und dies wird angezeigt.

### FA-PIN-020 — Automatische Verifikation · MUSS · V1 · PO
Wird eine SMC-B als `VERIFIABLE` erkannt und ist eine PIN hinterlegt, stellt das
System den verifizierten Zustand selbsttätig wieder her.

*Akzeptanz — der Ablauf ist vollständig nachzuweisen:*
1. `GetPinStatus` liefert `VERIFIABLE`.
2. Am Terminal besteht eine **angemeldete Verbindung** in der Rolle
   `SMC-B_Nutzer` (`A_24081`) — bei der Remote SMCB API die dauerhaft offene
   WebSocket-Verbindung.
3. `VerifyPin` mit `PinTyp = PIN.SMC` wird am Konnektor ausgelöst.
4. **Das Terminal fragt die PIN an** (`InputPinRequest`), und das System
   antwortet. Nachzuweisen ist der Weg, nicht nur das Ergebnis: Es ist zu
   belegen, dass die PIN über dieses Protokoll geliefert wurde ([ADR-0011](adr/0011-pin-uebergabe-ist-eine-anfrage-des-terminals.md)).
5. `GetPinStatus` liefert anschließend `VERIFIED`.
- Schritt 4 wurde am 10.08.2026 berichtigt. Zuvor stand hier, das System
  *liefere* die PIN — das entspricht keinem echten Gerät.
- Schritt 5 ist verpflichtend: Ohne erneute Abfrage gilt die Verifikation als
  **nicht** bestätigt.
- Die Gesamtdauer wird gemessen und protokolliert.
- Der Vorgang ist über eine Vorgangsnummer im Protokoll zusammenhängend
  nachvollziehbar.

### FA-PIN-030 — Schutz vor Kartensperrung · MUSS · V1 · PO
Das System darf eine SMC-B unter keinen Umständen selbst sperren.

*Akzeptanz:*
- Vor jedem Verifikationsversuch wird `LeftTries` ausgewertet.
- Nach **einem** fehlgeschlagenen Versuch (`PinResult = REJECTED`) wird die
  Automatik für diese Karte **angehalten** und alarmiert. Es erfolgt **kein**
  zweiter Versuch mit derselben hinterlegten PIN.
- Die Automatik läuft erst wieder an, nachdem ein Benutzer die PIN erneut
  hinterlegt oder den Halt ausdrücklich aufgehoben hat.
- *Nachweis:* Am Testaufbau wird bewusst eine falsche PIN hinterlegt. Erwartet:
  genau ein Versuch, `LeftTries` sinkt um genau 1, Automatik hält an, Alarm
  wird ausgelöst. **Dieser Test ist verpflichtend vor jeder Auslieferung.**
- Bei `PinStatus = BLOCKED` erfolgt kein Versuch; es wird alarmiert mit dem
  Hinweis, dass nur eine Entsperrung per PUK hilft.

### FA-PIN-040 — Nicht behebbare Zustände melden · MUSS · V1 · PO ✓ (freigegeben 06.08.2026)
Zustände, die das System nicht selbst auflösen kann, werden als Handlungsbedarf
ausgewiesen.

*Akzeptanz:*
- `TRANSPORT_PIN`, `EMPTY_PIN`, `BLOCKED` und `DISABLED` erzeugen je eine
  eigene, in Klartext erklärte Meldung mit der erforderlichen Handlung.
- Es wird kein Verifikationsversuch unternommen.

### FA-PIN-050 — Not-Aus · MUSS · V1 · PO, ADR-0004
Die Automatik lässt sich sofort anhalten.

*Akzeptanz:*
- Der Not-Aus ist von jeder Seite der Oberfläche in höchstens zwei Klicks
  erreichbar.
- Nach Auslösung startet **kein** neuer Verifikations- oder Rollout-Vorgang;
  bereits laufende Geräteupdates werden zu Ende geführt (ADR-0004).
- Geltungsbereich wählbar: gesamte Installation, Einrichtung oder Gerätegruppe.
- Zustand und Auslöser sind dauerhaft sichtbar, bis der Halt aufgehoben wird.
- Auslösen und Aufheben werden protokolliert.

### FA-PIN-060 — PIN ändern · SOLL · V2 · gemSpec `A_24082`
Die SMC-B-PIN kann geändert werden, ohne dass jemand am Gerät steht.

**Wer dabei was tut:** Der Roboter sendet `SICCT MODIFY VERIFICATION DATA`
**nicht selbst** — das tut der Konnektor. Der Roboter hinterlegt vorher alte und
neue PIN an der Managementschnittstelle des Terminals und löst das Kommando über
den Konnektor aus. Das Terminal entscheidet dann nach `A_24082`, ob es die Werte
von dort nimmt oder ein PIN-Pad verlangt.

*Akzeptanz:*
- Alte und neue PIN werden verdeckt erfasst, die neue mit Bestätigung.
- Nach erfolgreicher Änderung wird die hinterlegte PIN automatisch aktualisiert.
- Schlägt die Änderung fehl, bleibt die bisherige hinterlegte PIN unverändert.

---

## A.4 Firmware-Verwaltung (FA-FW)

> Grundlage: ADR-0004. Der Hersteller liefert die Firmware an den Administrator,
> dieser lädt sie hoch. Wir beziehen und verteilen keine Firmware selbst.

### FA-FW-010 — Firmware hochladen · MUSS · V2 · PO
Ein Administrator lädt vom Hersteller erhaltene Firmware-Pakete hoch.

*Akzeptanz:*
- Erfasst werden: Datei, Hersteller, Modell(e), Zielversion, Prüfsumme.
- Die Prüfsumme wird berechnet und angezeigt; hochladender Benutzer und
  Zeitpunkt werden protokolliert.
- Pakete für nicht unterstützte Modelle werden abgewiesen mit Nennung der
  unterstützten Modelle.

### FA-FW-020 — Rollout-Pläne je Gerätegruppe · MUSS · V2 · PO
Je Gerätegruppe wird ein Plan mit freigegebenen Zeitfenstern hinterlegt.

*Akzeptanz:*
- Ein Fenster besteht aus Wochentagen und Uhrzeitbereich (Beispiel: Mi und Fr,
  01:00–04:00); mehrere Fenster je Gruppe sind möglich.
- Fenster über Mitternacht werden korrekt behandelt.
- Zeitzone und Sommer-/Winterzeit werden korrekt behandelt. *Nachweis:* Test mit
  einem Fenster an einem Umstellungstag, in beide Richtungen.
- Der Plan zeigt die nächste Ausführungszeit im Klartext an.

### FA-FW-030 — Gesteuerte Ausführung · MUSS · V2 · PO
Der Rollout wird ausschließlich innerhalb freigegebener Fenster ausgeführt.

*Akzeptanz:*
- Außerhalb eines Fensters wird **kein** Geräteupdate gestartet.
- **Ein begonnenes Geräteupdate wird zu Ende geführt**, auch wenn das Fenster
  endet. *Nachweis:* Test mit einem Update, das absichtlich über das Fensterende
  hinausläuft.
- Vor dem Start eines weiteren Geräts wird geprüft, ob die erwartete Dauer noch
  in das Fenster passt; die Erwartung stützt sich auf gemessene Werte.
- Nicht abgearbeitete Geräte verbleiben in der Warteschlange und werden im
  nächsten freigegebenen Fenster fortgesetzt.

### FA-FW-040 — Kanarienvogel-Prinzip · MUSS · V2 · PO ✓ (freigegeben 06.08.2026)
Das erste Gerät einer Gruppe wird einzeln aktualisiert und vollständig geprüft.

*Akzeptanz:*
- Das erste Gerät läuft immer sequenziell, unabhängig von der eingestellten
  Parallelität.
- Erst nach erfolgreicher Prüfung (erreichbar, Zielversion aktiv, SMC-B wieder
  verifiziert) folgen die übrigen Geräte.
- Bei Fehlschlag stoppt der Rollout für die gesamte Gruppe automatisch und
  alarmiert.

### FA-FW-050 — Parallelität · MUSS · V2 · PO
Die Anzahl gleichzeitig aktualisierter Terminals ist je Gruppe einstellbar.

*Akzeptanz:*
- Vorgabewert 1; einstellbar bis zu einer nach Messung festgelegten Obergrenze.
- Die Oberfläche erklärt die Wirkung in Klartext (Dauer gegen Last und Risiko).
- Die eingestellte Zahl wird zur Laufzeit nicht überschritten. *Nachweis:*
  Protokollauswertung eines Rollouts über mindestens 10 Geräte.

### FA-FW-060 — Nachbehandlung je Gerät · MUSS · V2 · ADR-0004
Nach jedem Geräteupdate wird der Betriebszustand wiederhergestellt und geprüft.

*Akzeptanz:* Nach jedem Update in dieser Reihenfolge nachgewiesen —
Erreichbarkeit, aktive Firmware entspricht der Zielversion, PIN-Status geprüft,
SMC-B wieder verifiziert, Ergebnis protokolliert.
- Weicht ein Schritt ab, wird der Rollout angehalten und alarmiert; es wird
  **nicht** mit dem nächsten Gerät fortgefahren.

### FA-FW-070 — Nicht erreichbare Geräte · MUSS · V2 · PO
Im Fenster nicht erreichbare Geräte werden übersprungen und später erneut
versucht.

*Akzeptanz:*
- Überspringen wird protokolliert, mit Zählung der Versuche.
- Nach einer konfigurierbaren Anzahl erfolgloser Versuche wird das Gerät aus dem
  laufenden Rollout genommen und gemeldet.
- Der Rollout läuft für die übrigen Geräte weiter.

### FA-FW-080 — Kein Downgrade · MUSS · V2 · PO
Firmware-Downgrades werden nicht ausgerollt.

*Akzeptanz:*
- Ist die hochgeladene Version älter als die installierte, wird der Rollout für
  dieses Gerät **aktiv verweigert** und begründet.
- *Nachweis:* Versuch mit einer älteren Version wird abgewiesen und
  protokolliert.

### FA-FW-090 — Unbekannte Modelle · MUSS · V2 · PO
Auf nicht freigegebenen Modellen findet kein Rollout statt.

*Akzeptanz:*
- Das System führt eine Liste freigegebener Kombinationen aus Hersteller, Modell
  und Firmware-Stand.
- Für Geräte außerhalb dieser Liste wird der Rollout verweigert und begründet.

---

## A.5 Organisation und Mandanten (FA-ORG)

### FA-ORG-010 — Mehrstufige Organisationsstruktur · MUSS · V1 · PO
Struktur: Instanz → Serviceorganisation → Einrichtung → Gerätegruppe → Terminal.

*Akzeptanz:*
- Serviceorganisationen und Einrichtungen sind anlegbar, änderbar, deaktivierbar.
- Jedes Objekt ist eindeutig genau einer Einrichtung zugeordnet.
- Auch die Einzelinstallation nutzt dieselbe Struktur, in der Oberfläche jedoch
  ohne unnötige Ebenen (siehe FA-INS-030).

### FA-GER-055 — Gruppenbezeichnungen sind eindeutig · MUSS · V1 · PO 13.08.2026
Die Bezeichnung einer Gerätegruppe ist **instanzweit** eindeutig, nicht nur je
Einrichtung.

*Anlass:* In einem MVZ mit neun Betriebsstätten hießen alle neun
Vorgabegruppen „Alle Geräte". Ein Gruppenname erscheint auch dort, wo die
Spalte „Einrichtung" nicht danebensteht — in Auswahlfeldern, im Fachprotokoll,
in Fehlermeldungen. Dort sagte er nichts mehr.

*Akzeptanz:*
- Ein Name, den es andernorts schon gibt, wird abgelehnt — auch bei
  abweichender Groß-/Kleinschreibung.
- **Die Meldung nennt die fremde Einrichtung nicht.** Sie sagt nur, dass der
  Name belegt ist, und wie er zu ergänzen ist. Eine Mandantengrenze, die sich
  mit einem Formular ausprobieren lässt, ist keine.
- Die Regel wird durch einen eindeutigen Index erzwungen, nicht nur durch eine
  Vorabprüfung — zwei gleichzeitige Sitzungen dürfen sie nicht unterlaufen.
- Vorgabegruppen tragen die Einrichtung im Namen.
- Beim Umbenennen ist die eigene Gruppe von der Prüfung ausgenommen.

### FA-ORG-030 — Struktur grafisch darstellen · SOLL · V1 · PO 12.08.2026
Die Organisationsstruktur ist als Bild darstellbar, mit Sinnbildern für die
Einheiten und die daran hängenden Geräte.

*Akzeptanz:*
- Die grafische Ansicht zeigt **jede** Einheit des Geltungsbereichs — geprüft
  durch Abgleich der Kartenzahl gegen die Datenbank. Eine Ansicht, die
  stillschweigend weniger zeigt als die Liste, ist gefährlicher als keine.
- Konnektoren und Kartenterminals erscheinen unter ihrer Einheit; ein
  **mitgenutzter** Konnektor ist als solcher gekennzeichnet (ADR-0012).
- Jede Zustandsaussage trägt neben der Farbe ein Sinnbild **und** Text
  (WCAG 1.4.1). Die Zustände sind in einer Legende benannt.
- Die Ansicht ist vollständig mit der Tastatur bedienbar und besteht die
  axe-core-Prüfung ohne Befund.
- Eine gleichwertige Listenansicht bleibt erreichbar; die Wahl wird gemerkt.
- Begrenzungen der Darstellung (nicht gezeigte Geräte, zugeklappte Zweige)
  nennen die vollständige Zahl.

---

### FA-ORG-020 — Mandantentrennung · MUSS · V1 · ADR-0002, PO
Kein Benutzer sieht oder verändert Daten außerhalb seines Geltungsbereichs.

*Akzeptanz:*
- Die Einschränkung wird **zentral erzwungen**, nicht je Abfrage einzeln.
- *Nachweis durch automatisierte Tests:* Für **jeden** lesenden und schreibenden
  Zugriffspunkt wird geprüft, dass ein Benutzer der Einrichtung A auf Daten der
  Einrichtung B weder lesend noch schreibend zugreifen kann — auch nicht durch
  direkte Angabe fremder Kennungen.
- Ein neuer Zugriffspunkt ohne diesen Test gilt als nicht fertiggestellt.
- Der Test umfasst ausdrücklich Protokoll und Excel-Export.

---

## A.6 Benutzer, Rollen, Anmeldung (FA-BEN)

### FA-BEN-010 — Rollenmodell · MUSS · V1 · PO
Mindestens die Rollen Administrator (Benutzerverwaltung) und Benutzer
(Kartenterminal-Verwaltung), jeweils mit Geltungsbereich.

*Akzeptanz:*
- Jede Rolle hat einen dokumentierten Satz von Berechtigungen.
- Rechte zum Hinterlegen von PINs und zum Freigeben von Produktupdates sind
  gesondert und nicht Bestandteil der Basisrolle Benutzer.
- Rollenzuweisungen werden protokolliert.
- Es existiert **immer mindestens ein** handlungsfähiger Administrator; der
  letzte lässt sich nicht löschen oder herabstufen.

### FA-BEN-020 — Anmeldung mit zweitem Faktor · MUSS · V1 · PO
Anmeldung ausschließlich mit Benutzername, Passwort und TOTP.

*Akzeptanz:*
- Ohne gültigen TOTP-Code erfolgt keine Anmeldung; der zweite Faktor ist nicht
  abschaltbar.
- Bei der Einrichtung wird der Code einmal verifiziert, bevor er aktiv wird.
- Zeitversatz von ±1 Zeitfenster wird toleriert, mehr nicht.
- Ein verbrauchter Code ist nicht erneut verwendbar.
- Nach einer konfigurierbaren Zahl von Fehlversuchen wird das Konto zeitweise
  gesperrt; jeder Fehlversuch wird protokolliert.

### FA-BEN-030 — Onboarding · MUSS · V1 · PO
Geführter Prozess für Ersteinrichtung und Aufnahme weiterer Konten.

*Akzeptanz:*
- Bei leerem System führt ein Assistent durch die Anlage des ersten
  Administrators einschließlich TOTP-Einrichtung.
- Vor Abschluss dieses Schritts ist keine andere Funktion erreichbar.
- Weitere Konten werden per zeitlich begrenzter Einladung angelegt; das Passwort
  setzt der eingeladene Benutzer selbst.
- Der Einladungslink ist einmalig verwendbar und läuft nach konfigurierbarer
  Frist ab.

### FA-BEN-035 — Administratorkonto aus der Konfiguration · SOLL · V1 · Claude
Ein Zugang, der einen Datenverlust überlebt.

**Anlass:** Der Zugang hängt sonst an einem Konto, das einmalig bei der
Ersteinrichtung entsteht — mit einem TOTP-Geheimnis, das genau einmal angezeigt
wird. Geht das Datenvolumen verloren (Neuaufbau, misslungene Wiederherstellung),
entsteht bei der nächsten Ersteinrichtung ein **anderes** Geheimnis; der Eintrag
in der Authenticator-App ist tot, und niemand kommt mehr hinein. Im Projekt
dreimal eingetreten; in einer Praxis ohne IT-Personal wäre es ein Ausfall mit
Anruf beim Hersteller.

Diese Anforderung ergänzt FA-BEN-040, ersetzt sie nicht: Dort geht es um den
Verlust des zweiten Faktors *eines Benutzers*, hier um den Verlust *aller
Konten*.

*Akzeptanz:*
- Sind `START_ADMIN_BENUTZER` und ein Passwort gesetzt, entsteht das Konto beim
  Start, **falls es fehlt**.
- Ist `START_ADMIN_TOTP` gesetzt, gilt dieses Geheimnis sofort; eine erneute
  TOTP-Einrichtung entfällt.
- **Ein vorhandenes Konto wird nicht verändert** — weder Passwort noch Rolle
  noch zweiter Faktor, auch nicht der Aktivzustand. Nachweis: Ein deaktiviertes
  Konto bleibt nach einem Neustart deaktiviert.
- Nach vollständigem Verlust des Datenvolumens gilt derselbe Zugang unverändert
  weiter. Nachweis am laufenden System, nicht als Behauptung.
- Weder Passwort noch TOTP-Geheimnis erscheinen im Betriebs- oder Fachprotokoll.
- Das Anlegen erscheint als deutliche Warnung im Betriebsprotokoll und als
  Eintrag im Fachprotokoll: Ein Administrator ist entstanden, ohne dass ihn
  jemand angelegt hat.
- Das Passwort ist aus einer Datei lesbar (Docker-Secret), damit es nicht in
  `docker inspect` und nicht in der Prozessliste erscheint.
- Eine fehlerhafte Konfiguration bricht den Start **nicht** ab, sondern warnt.
  Die Überwachung der Kartenterminals ist die Kernfunktion; sie darf nicht an
  einem Konto scheitern.

> **Sicherheitsvorbehalt, der zu dokumentieren ist:** Wer die Konfiguration
> lesen kann, kennt beide Faktoren — der zweite ist dann keiner mehr. Das ist
> ein bewusster Tausch: Wiedereinstieg nach Datenverlust gegen die Trennung der
> Faktoren. Der Betreiber entscheidet, ob er ihn eingeht, und wird in
> [09-devops-installation.md](09-devops-installation.md) darauf hingewiesen.

### FA-BEN-040 — Verlust des zweiten Faktors · MUSS · V1 · PO
Verfahren zur Wiederherstellung ohne Hintertür.

*Akzeptanz:*
- Ein Administrator kann den zweiten Faktor eines anderen Benutzers zurücksetzen;
  der Vorgang wird protokolliert und dem Betroffenen angezeigt.
- Für den Fall, dass **kein** Administrator mehr anmeldefähig ist, existiert ein
  dokumentiertes Wiederherstellungsverfahren mit direktem Zugriff auf den
  Server, das ein Vier-Augen-Prinzip nicht erfordert, aber unauslöschlich
  protokolliert wird.
- Das Verfahren ist im Betriebshandbuch beschrieben und am Testaufbau
  nachgewiesen.

### FA-BEN-050 — Benutzerverwaltung bei großer Anzahl · MUSS · V1 · PO
Bedienbar bei bis zu 1.000 Benutzern und 150 Administratoren.

*Akzeptanz:*
- Serverseitige Suche, Filterung nach Rolle, Einrichtung und Zustand,
  Paginierung.
- Massenaktionen für mindestens: deaktivieren, Rolle ändern, Einrichtung
  zuordnen.
- Die Liste ist bei 1.000 Benutzern in **< 1 Sekunde** dargestellt.

---

## A.7 Protokollierung und Auswertung (FA-PRO)

> Grundlage: ADR-0005.

### FA-PRO-010 — Fachprotokoll · MUSS · V1 · PO
Alle Zustandsänderungen und Handlungen werden strukturiert protokolliert.

*Akzeptanz:*
- Jeder Eintrag enthält: Zeitpunkt mit Zeitzone, Mandant, betroffenes Objekt,
  Ereignisart aus fester Liste, Ergebnis, Auslöser, Vorgangsnummer,
  strukturierte Detailfelder.
- Ereignisarten sind eine gepflegte Liste; Freitext als alleiniger Inhalt ist
  unzulässig.
- Einträge sind **nur anfügbar** — es existiert keine Funktion zum Ändern oder
  Löschen einzelner Einträge.
- Eine Prüfung ohne Zustandsänderung erzeugt **keinen** Eintrag.
- *Nachweis:* 24 Stunden Betrieb mit 100 Geräten erzeugen weniger als 1.000
  Einträge im Normalbetrieb.

### FA-PRO-020 — Keine Geheimnisse im Protokoll · MUSS · V1 · PO, ADR-0005
*Akzeptanz:*
- Automatisierter Test: Nach einem Durchlauf mit hinterlegten Testgeheimnissen
  (PIN, Terminal-Kennwort, GHCR-Token, TOTP-Geheimnis) enthält **keine**
  Protokollzeile, Fehlermeldung, Stapelüberwachung, Exportdatei oder Sicherung
  einen dieser Werte.
- Die Unterdrückung ist zentral umgesetzt; eine neue Protokollstelle kann sie
  nicht umgehen.

### FA-PRO-030 — Protokollansicht mit Filtern · MUSS · V1 · PO
*Akzeptanz:*
- Filter, kombinierbar: Zeitraum (mit Vorgaben heute / 7 / 30 Tage), Mandant,
  Einrichtung, Gruppe, Gerät, Ereignisart, Ergebnis, Auslöser, Vorgangsnummer,
  Volltext.
- Filterung und Sortierung erfolgen serverseitig mit Paginierung.
- Der aktive Filter steht in der Adresszeile und ist per Link teilbar.
- Bei 5 Millionen Einträgen liefert eine gefilterte Abfrage in **< 2 Sekunden**.

### FA-PRO-040 — Excel-Export · MUSS · V1 · PO
*Akzeptanz:*
- Format `.xlsx`; exportiert wird genau die gefilterte Ansicht.
- Kopfzeile fixiert, Autofilter gesetzt; Datums- und Zahlenwerte als echte
  Datums- und Zahlenformate.
- Deckblatt mit Exportzeitpunkt, ausführendem Benutzer, angewendeten Filtern und
  Zeilenzahl.
- Exporte über einer konfigurierbaren Zeilenzahl laufen im Hintergrund; der
  Benutzer wird benachrichtigt.
- Bei drohender Überschreitung der Excel-Zeilengrenze (1.048.576) wird **vor**
  dem Export gewarnt und zum Einschränken aufgefordert; **kein stilles
  Abschneiden**.
- Der Export wird selbst protokolliert.
- Umlaute und Sonderzeichen werden korrekt dargestellt. *Nachweis:* Öffnen in
  Excel unter Windows mit deutscher Ländereinstellung.

### FA-PRO-050 — Geräte-Zeitstrahl · MUSS · V1 · PO
Je Terminal eine chronologische Sicht aller Ereignisse.

*Akzeptanz:*
- Erreichbar aus der Geräteliste in einem Klick.
- Enthält PIN-Statuswechsel, Verifikationen, Firmware-Wechsel,
  Erreichbarkeitswechsel, Konfigurationsänderungen.
- Zeitraum wählbar; Ereignisarten ein- und ausblendbar.

### FA-PRO-060 — Vorgangsansicht · MUSS · V1 · PO
Zusammengehörige Ereignisse werden als ein Vorgang dargestellt.

*Akzeptanz:*
- Alle Ereignisse mit gleicher Vorgangsnummer sind gemeinsam abrufbar.
- Ein laufender Vorgang zeigt Fortschritt, erledigte, ausstehende und
  fehlgeschlagene Geräte.
- Die Ansicht aktualisiert sich ohne manuelles Neuladen.

### FA-PRO-070 — Speicherbudget und Rotation · MUSS · V1 · PO, ADR-0005
*Akzeptanz:*
- Je Datenart (Fachprotokoll, technisches Protokoll) ist ein Budget in Gigabyte
  einstellbar; Vorgabewerte nach Umgebungsgröße.
- Ab **80 %** Füllstand beginnt die Rotation; ab 90 % wird alarmiert.
- Rotation erfolgt gestaffelt: technische Diagnose zuerst, fachliche
  Routineereignisse danach, sicherheitsrelevante Ereignisse zuletzt.
- Vor Erreichen der letzten Stufe fordert das System zum Export auf.
- **Ein volles Protokoll hält die Kernfunktion nicht an.** *Nachweis:* Test mit
  künstlich gefülltem Budget — Verifikationen laufen weiter, Rotation greift,
  Alarm wird ausgelöst.
- Protokolldaten liegen in einem eigenen Volume, getrennt von Betriebsdaten.

### FA-PRO-080 — Aufbewahrungsdauer · MUSS · V1 · ADR-0005
*Akzeptanz:*
- Je Datenart konfigurierbar; ältere Einträge werden automatisch gelöscht.
- Vorgabewerte: 12 Monate fachlich, 90 Tage technisch — **vor Auslieferung
  datenschutzrechtlich zu bewerten**.
- Löschläufe werden protokolliert (Anzahl, Zeitraum — keine Inhalte).

---

## A.8 Alarmierung (FA-ALA)

### FA-ALA-010 — Alarmzustände · MUSS · V1 · PO ✓ (freigegeben 06.08.2026)
*Akzeptanz:*
- Alarm entsteht mindestens bei: fehlgeschlagener Verifikation, angehaltener
  Automatik, gesperrter Karte, nicht erreichbarem Konnektor/Terminal/Agent,
  fehlgeschlagenem Rollout, Speicherbudget über 90 %, ungültigen
  GHCR-Zugangsdaten.
- Jeder Alarm trägt Schweregrad, Zeitpunkt, betroffenes Objekt und eine
  Handlungsempfehlung im Klartext.
- Alarme sind quittierbar; Quittierung wird protokolliert.
- Ein behobener Zustand schließt den Alarm automatisch.

### FA-ALA-030 — Alarme von Hand schließen · MUSS · V1 · PO 13.08.2026
Alarme lassen sich einzeln und als Sammelaktion aus der Lage-Übersicht
entfernen.

*Anlass:* Ein Alarm wird geschlossen, wenn ein Agent meldet, dass der Zustand
behoben ist. Für ein Gerät, das es **nicht mehr gibt**, kommt diese Meldung nie
— der Alarm bleibt für immer offen und macht die Lage-Übersicht unbrauchbar.

*Akzeptanz:*
- Ein Alarm lässt sich einzeln schließen, und alle offenen des eigenen
  Geltungsbereichs auf einmal.
- **Geschlossen heißt nicht gelöscht.** Der Datensatz bleibt erhalten
  (ADR-0005); nur `behoben_am` wird gesetzt.
- **Es hebt das Problem nicht auf.** Besteht die Ursache weiter, meldet der
  Agent den Alarm im nächsten Prüflauf erneut — nachgewiesen.
- Die Sammelaktion fragt zurück und **nennt dabei die Zahl** der betroffenen
  Alarme.
- Die Sammelaktion trifft ausschließlich den eigenen Geltungsbereich.
- Schließen wird protokolliert, bei der Sammelaktion **mit der Anzahl**.
- Beim Löschen von Demo- und Lasttestbestand gehen deren Alarme mit — sonst
  entstehen die unauflösbaren Karteileichen erneut.

### FA-ALA-020 — Benachrichtigung per E-Mail · SOLL · V1 · PO ✓ (freigegeben 06.08.2026)
*Akzeptanz:*
- SMTP konfigurierbar, mit Testversand aus der Oberfläche.
- Empfänger und Schweregrad-Schwelle je Einrichtung einstellbar.
- Zusammenfassung statt Einzelmeldung bei Massenereignissen (konfigurierbares
  Sammelfenster), damit ein Konnektorausfall nicht 5.000 E-Mails erzeugt.

---

## A.9 Produktupdate (FA-UPD)

> Grundlage: ADR-0001.

### FA-UPD-010 — Neue Versionen erkennen · MUSS · V1 · PO, ADR-0001
*Akzeptanz:*
- Das System fragt GHCR regelmäßig authentifiziert ab und zeigt verfügbare
  Versionen mit Änderungshinweisen auf Deutsch an.
- Sicherheitsrelevante Updates sind deutlich hervorgehoben.
- **Ungültige oder abgelaufene Zugangsdaten erscheinen als sichtbarer
  Fehlerzustand**, nicht als ausbleibende Meldung.

### FA-UPD-020 — Geführte Ausführung · MUSS · V1 · ADR-0001
*Akzeptanz:*
- Nur Administratoren können ein Update freigeben; Ausführung sofort oder im
  Wartungsfenster.
- Vor dem Start wird die Signatur des Images geprüft; ohne gültige Signatur
  erfolgt kein Start.
- Wird der Health-Check nach dem Start nicht innerhalb einer Frist grün, erfolgt
  **automatischer Rollback** auf die vorherige Version. *Nachweis:* Test mit
  einem absichtlich fehlerhaften Image.
- Freigabe, Ausführung und Ergebnis werden protokolliert.

### FA-UPD-030 — Unbeaufsichtigte Patch-Updates · KANN · V2 · ADR-0001
*Akzeptanz:* Optional aktivierbar, ausschließlich für Patch-Versionen,
ausschließlich im Wartungsfenster; Funktions- und Hauptversionen bleiben immer
bestätigungspflichtig.

---

## A.10 Installation und Betrieb (FA-INS)

### FA-INS-010 — Installation über Compose · MUSS · V1 · PO
*Akzeptanz:*
- Installation mit **einem** Befehl (`docker compose up -d`) nach Ausfüllen einer
  Konfigurationsdatei.
- Keine manuellen Nachinstallationsschritte.
- Funktioniert nachgewiesen unter Linux, Windows und macOS.
- Datenbankmigrationen laufen beim Start automatisch und idempotent.

### FA-INS-020 — Einrichtungsassistent · MUSS · V1 · PO ✓ (freigegeben 06.08.2026)
*Akzeptanz:*
- Nach der Erstinstallation führt ein Assistent durch: Administrator anlegen mit
  TOTP, Betriebsart wählen, Speicherbudgets setzen, ersten Konnektor und erstes
  Terminal erfassen, Verbindung prüfen.
- Jeder Schritt ist überspringbar und später nachholbar; der Fortschritt bleibt
  erhalten.
- Vorgabewerte werden mit ihrer Wirkung erklärt, nicht nur gesetzt.

### FA-INS-030 — Betriebsart · MUSS · V1 · ADR-0002
*Akzeptanz:*
- Beim Setup wird zwischen verteiltem und zentralem Betrieb gewählt.
- Im verteilten Betrieb laufen Zentrale und Agent im selben Compose-Stack; für
  den Betreiber bleibt es **eine** Installation.
- Im verteilten Betrieb blendet die Oberfläche die Ebene der
  Serviceorganisationen aus, ohne sie im Datenmodell zu entfernen.

### FA-INS-040 — Sicherung und Wiederherstellung · MUSS · V1 · PO
*Akzeptanz:*
- Dokumentiertes Verfahren für Sicherung und Wiederherstellung aller
  persistenten Daten.
- **Die Wiederherstellung ist am Testaufbau nachgewiesen**, nicht nur
  beschrieben.
- Die Sicherung enthält keine Geheimnisse im Klartext.
- Nach Wiederherstellung ist ersichtlich, welche PINs neu zu hinterlegen sind.

---

## A.12 Datensicherung (FA-BAK)

Vorgabe des PO vom 10.08.2026. Bis dahin war die Sicherung ein
Betriebshandbuch-Kapitel mit Befehlen zum Abtippen
([04-betrieb.md](04-betrieb.md), Abschnitt 4). Das setzt jemanden voraus, der
sie regelmäßig ausführt — in einer Praxis ohne IT-Personal gibt es den nicht.

> **Warum das schwerer wiegt als eine Bequemlichkeit:** Geht die Datenbank
> verloren, sind Bestand, Organisationsstruktur, Konten und das gesamte
> Fachprotokoll weg. Das Protokoll ist der Nachweis darüber, was der Roboter
> mit den SMC-B getan hat — es lässt sich nicht rekonstruieren.

### FA-BAK-010 — Automatische Sicherung nach Plan · MUSS · V1 · PO
Die Zentrale sichert ihre Datenbank selbsttätig, ohne Zutun des Betreibers.

*Akzeptanz:*
- Drei Pläne laufen unabhängig voneinander:

  | Plan | Behält | Rotation |
  |---|---|---|
  | täglich | 7 Stände | wöchentlich rollierend |
  | wöchentlich | 4–5 Stände | monatlich rollierend |
  | monatlich | 12 Stände | jährlich rollierend |

- Ist ein Plan über seine Zahl hinaus gefüllt, wird der **älteste** Stand
  entfernt — nie der neueste, auch nicht bei knappem Speicher.
- Jede Sicherung und jede Rotation erscheint im Fachprotokoll.
- **Eine fehlgeschlagene Sicherung ist ein Alarm**, keine Logzeile. Eine
  Sicherung, von der niemand weiß, dass sie ausblieb, ist keine Sicherung.
- Die Sicherungen liegen in einem **eigenen Volume**, getrennt von den
  Betriebsdaten. Ein Volume, das mit der Datenbank verschwindet, sichert nichts.
- Der laufende Betrieb wird nicht angehalten. Die Überwachung der
  Kartenterminals läuft während einer Sicherung weiter.

### FA-BAK-020 — Herunterladen und Hochladen · MUSS · V1 · PO
Sicherungen verlassen die Instanz nur verschlüsselt.

*Akzeptanz:*
- Eine Sicherung lässt sich über die Oberfläche herunterladen. Dabei ist
  **zwingend ein Schlüssel einzugeben**; ohne ihn gibt es keinen Download.
- Die Datei ist mit diesem Schlüssel **verschlüsselt und integritätsgeschützt**.
  Eine veränderte Datei wird beim Hochladen erkannt und abgewiesen — nicht
  teilweise eingelesen.
- Der Schlüssel wird **nirgends gespeichert**: nicht in der Datenbank, nicht im
  Protokoll, nicht im Arbeitsspeicher über den Vorgang hinaus. Nach Abschluss
  wird er überschrieben.
- **Geht der Schlüssel verloren, ist die Datei unbrauchbar.** Die Oberfläche
  sagt das vor dem Download, nicht danach.
- Eine hochgeladene Datei mit falschem Schlüssel wird mit verständlicher
  Meldung abgewiesen — ohne preiszugeben, ob der Schlüssel oder die Datei
  falsch war.
- Der Vorgang steht im Fachprotokoll: wer, wann, welche Sicherung. Niemals der
  Schlüssel.

### FA-BAK-030 — Wiederherstellung · MUSS · V1 · PO
Ein Stand lässt sich gezielt aktivieren.

*Akzeptanz:*
- Der Administrator wählt eine Sicherung und bestätigt ausdrücklich; die
  Rückfrage nennt Zeitpunkt und Umfang des Standes.
- **Vor dem Überschreiben entsteht eine Sicherung des aktuellen Zustands.** Eine
  versehentliche Wiederherstellung darf nicht der letzte Stand sein.
- Nach dem Einspielen **startet die Zentrale neu**. Zustände im Arbeitsspeicher
  — gemeldete Geräte, Sitzungen, offene Aufträge — gehören zum alten Datenstand
  und wären danach widersprüchlich.
- Angemeldete Benutzer werden abgemeldet; das ist Folge des Neustarts und wird
  vorher angekündigt.
- Schlägt das Einspielen fehl, bleibt der bisherige Stand unverändert. Ein halb
  eingespieltes Backup ist schlimmer als keines.
- Der Vorgang steht im Fachprotokoll — und zwar so, dass er **nach** der
  Wiederherstellung noch auffindbar ist.

### FA-BAK-040 — Sichtbarkeit · MUSS · V1 · PO
Der Betreiber sieht ohne Nachfragen, ob er gesichert ist.

*Akzeptanz:*
- Die Oberfläche zeigt je Plan den letzten Stand mit Zeitpunkt und Größe.
- Fehlt eine Sicherung länger als ihr Plan vorsieht, erscheint das als
  Handlungsbedarf — nicht als fehlender Eintrag in einer Liste.
- Der belegte Speicherplatz ist ersichtlich.

### FA-BAK-050 — Einstellungen als eigener Bereich · SOLL · V1 · PO
Systemnahe Funktionen liegen zusammen, getrennt vom Tagesgeschäft.

*Akzeptanz:*
- Ein Menüpunkt „Einstellungen" bündelt Datensicherung und die Rubrik
  „Systemtest" (Demodatensatz, Leistungsmessung).
- Demodatensatz und Leistungsmessung sind aus „Instanz" verschwunden; „Instanz"
  behält Agenten, Speicher und Produktversion.
- Alles ist nur für Administratoren erreichbar.

---

## A.11 Standort-Agent (FA-AGT)

> Grundlage: ADR-0002.

### FA-AGT-010 — Registrierung · MUSS · V1 · ADR-0002
*Akzeptanz:*
- Ein neuer Agent wird über ein einmalig gültiges, zeitlich begrenztes
  Registrierungsgeheimnis genau einer Einrichtung zugeordnet.
- Ein Agent ohne gültige Registrierung erhält keine Daten und keine Aufträge.
- *Nachweis:* Ein Agent mit ungültigem oder bereits verbrauchtem Geheimnis wird
  abgewiesen; der Versuch wird protokolliert.

### FA-AGT-020 — Verbindung · MUSS · V1 · ADR-0002
*Akzeptanz:*
- Die Verbindung wird **vom Agenten ausgehend** aufgebaut; im Kundennetz sind
  keine eingehenden Freigaben erforderlich. *Nachweis:* Betrieb hinter einer
  Firewall, die eingehende Verbindungen vollständig blockiert.
- Beidseitig authentifiziert und verschlüsselt.
- Ein Verbindungsabbruch wird binnen 60 Sekunden erkannt und angezeigt.

### FA-AGT-030 — Autonomer Weiterbetrieb · MUSS · V1 · ADR-0002
*Akzeptanz:*
- Bei ausgefallener Verbindung zur Zentrale setzt der Agent Überwachung und
  automatische Verifikation **eigenständig fort**.
- Ereignisse werden zwischengespeichert und nach Wiederherstellung vollständig
  und in der richtigen Reihenfolge nachgeliefert.
- Die Puffergröße ist begrenzt und konfigurierbar; bei Überlauf werden die
  ältesten Routineereignisse zuerst verworfen, sicherheitsrelevante zuletzt.
- *Nachweis:* Trennung der Verbindung für 24 Stunden; Verifikationen laufen
  weiter, nach Wiederverbindung ist das Protokoll vollständig.

### FA-AGT-040 — Versionsunterschiede · MUSS · V1 · ADR-0002
*Akzeptanz:*
- Agent und Zentrale können unterschiedliche Versionen haben; das Protokoll ist
  versioniert.
- Eine nicht unterstützte Kombination wird erkannt, angezeigt und führt nicht zu
  Fehlverhalten.
- Die unterstützte Versionsspanne ist dokumentiert.

---

# Teil B — Nichtfunktionale Anforderungen

## B.1 Sicherheit (NFA-SIC)

### NFA-SIC-010 — Umgang mit Geheimnissen · MUSS · V1 · gemSpec `A_24114`, PO
*Akzeptanz:*
- SMC-B-PINs, Terminal-Administrationszugänge, GHCR-Token, Passwörter und
  TOTP-Geheimnisse werden **niemals im Klartext** persistiert.
- Der Schlüssel zur Entschlüsselung liegt weder im Image noch im Repository.
- Kein Geheimnis ist über Oberfläche, Schnittstelle, Protokoll, Fehlermeldung,
  Metrik oder Sicherung auslesbar.
- Passwörter werden mit einem für Passwörter vorgesehenen, rechenaufwendigen
  Verfahren gespeichert.
- *Nachweis:* Automatisierter Test durchsucht Datenbank, Protokolle, Sicherungen
  und Schnittstellenantworten nach hinterlegten Testgeheimnissen — kein Treffer.

### NFA-SIC-020 — Transportverschlüsselung · MUSS · V1 · PO ✓ (freigegeben 06.08.2026)
*Akzeptanz:*
- Alle Verbindungen — Browser zur Zentrale, Agent zur Zentrale, Agent zu
  Konnektor und Terminal — sind verschlüsselt.
- Zertifikate werden geprüft. Ist eine Ausnahme im Kundenumfeld unvermeidbar
  (selbstsignierte Gerätezertifikate), erfolgt sie **nur je Gerät, ausdrücklich
  und protokolliert** — nie global und nie unbemerkt.

### NFA-SIC-030 — Container-Härtung · MUSS · V1 · PO
*Akzeptanz:*
- Alle Container laufen als **Nicht-Root-Benutzer**.
- Dateisystem, soweit möglich, schreibgeschützt; beschreibbare Pfade
  ausdrücklich benannt.
- Keine unnötigen Rechte; kein direkter Docker-Socket-Zugriff außer im
  Updater-Baustein, dort ausschließlich über einen Proxy mit minimalen Rechten.
- Basisimages minimal; Abhängigkeiten mit festgeschriebenen Versionen.
- *Nachweis:* Automatisierter Schwachstellen-Scan in der Bauumgebung; Bau
  scheitert bei kritischen Befunden.

### NFA-SIC-040 — Lieferkette · MUSS · V1 · ADR-0001
*Akzeptanz:*
- Images werden beim Bau signiert; die Signatur wird vor Inbetriebnahme geprüft.
- Ein Stücklistennachweis (SBOM) wird je Image erzeugt und veröffentlicht.
- Keine `:latest`-Tags im Betrieb; Auslieferung mit festgeschriebenem Digest.

### NFA-SIC-050 — Sitzungen · MUSS · V1 · PO ✓ (freigegeben 06.08.2026)
*Akzeptanz:*
- Sitzungen laufen nach konfigurierbarer Untätigkeit ab (Vorgabe 30 Minuten).
- Abmelden beendet die Sitzung serverseitig.
- Sitzungsmerkmale sind gegen Auslesen durch Skripte und gegen Übertragung über
  unverschlüsselte Verbindungen geschützt; Schutz gegen seitenübergreifende
  Anfragefälschung ist wirksam.
- Ein Administrator kann fremde Sitzungen beenden.

---

## B.2 Datenschutz (NFA-DAT)

### NFA-DAT-010 — Datensparsamkeit · MUSS · V1 · PO
*Akzeptanz:*
- Es werden **keine Patienten- oder Gesundheitsdaten** verarbeitet oder
  gespeichert. *Nachweis:* Datenmodell und Schnittstellen dokumentiert und
  geprüft.
- Personenbezogen sind ausschließlich Benutzerkonten und Protokolleinträge über
  Benutzerhandlungen.

### NFA-DAT-020 — Auskunft und Löschung · MUSS · V1 · PO ✓ (freigegeben 06.08.2026)
*Akzeptanz:*
- Zu einem Benutzerkonto lassen sich alle gespeicherten personenbezogenen Daten
  ausgeben.
- Ein Konto ist löschbar; Protokolleinträge bleiben als Nachweis erhalten, der
  Personenbezug wird jedoch nach Ablauf der Aufbewahrungsfrist entfernt.
- Das Verfahren ist dokumentiert und vor Auslieferung datenschutzrechtlich zu
  bewerten.

---

## B.3 Leistung und Skalierung (NFA-LEI)

> **Messbedingungen für alle Werte dieses Abschnitts:** Referenzumgebung, Umfang
> und Messverfahren werden im Testkonzept festgelegt und im Ergebnisbericht
> dokumentiert. Ohne dokumentierte Messung gilt eine Anforderung als nicht
> erfüllt.

### NFA-LEI-010 — Antwortzeiten der Oberfläche · MUSS · V1 · PO
*Akzeptanz:*
- Spürbare Reaktion auf eine Eingabe in **< 100 ms**.
- Seitenaufbau in **< 1 s** im lokalen Netz, gemessen bei 5.000 Terminals und
  1.000 Benutzern im Bestand.
- Gefilterte Protokollabfrage in **< 2 s** bei 5 Millionen Einträgen.

### NFA-LEI-020 — Überwachungsdurchsatz · MUSS · V1 · PO
*Akzeptanz:*
- 5.000 Terminals werden im eingestellten Intervall zuverlässig geprüft.
- Bei einem Intervall von 60 s liegt der Anteil verspäteter Prüfungen
  (Verzug > 20 % des Intervalls) unter **1 %** über 24 Stunden.
- Die Last ist begrenzt: Die Zahl gleichzeitiger Anfragen je Konnektor ist
  konfigurierbar und wird eingehalten.
- Antwortet ein Konnektor langsam, verzögert das **nicht** die Prüfung anderer
  Konnektoren.

### NFA-LEI-030 — Ressourcenbedarf am unteren Ende der Skala · MUSS · V1 · PO

**Referenzhardware (PO, 07.08.2026):** eine **Linux-Appliance** der Klasse
**Raspberry Pi 5** oder **NXP i.MX8 QuadMax mit 8 GB RAM** — beide `arm64`.
Ergänzend **Windows und macOS mit Docker Desktop**.

> Der Konnektor wird hier ausdrücklich nur als **Vergleichsmaßstab für die
> Hardware-Klasse** genannt. Die Software läuft **nicht auf dem Konnektor**,
> sondern auf einer eigenen Appliance.

*Akzeptanz:*
- Der **vollständige Stack** (Zentrale, Agent, Datenbank) läuft in der
  Einzelpraxis-Konfiguration — 3 Terminals, 5 Benutzer — mit **≤ 3 GB
  Arbeitsspeicher** im Dauerbetrieb.
- Der **Standort-Agent allein** benötigt **≤ 512 MB**. Er ist der Baustein, der
  auf fremder oder besonders schwacher Hardware mitlaufen können muss.
- Der Stack startet auf der Referenzhardware in **< 60 Sekunden** bis zur
  Bedienbarkeit.
- Der Ruhebedarf an Rechenleistung liegt im Mittel unter **5 % eines Kerns**.
- **Nachweis auf `arm64`**, nicht nur auf Entwicklungshardware.

### NFA-LEI-035 — Plattenplatz · MUSS · V1 · Vorschlag
Der Bedarf an Massenspeicher ist vorhersagbar und begrenzt.

*Akzeptanz:*
- Einzelpraxis: **≤ 8 GB** insgesamt, einschließlich Images, Rollback-Stand,
  Betriebsdaten und Protokollbudget.
- Klinik / Serviceorganisation: **≤ 60 GB** bei Vorgabe-Protokollbudget;
  darüber hinausgehender Bedarf entsteht nur durch bewusst erhöhte Budgets oder
  die Firmware-Ablage.
- Der Bedarf wird im Einrichtungsassistenten **vor** der Installation angezeigt.
- Das Protokollbudget begrenzt den einzigen unbegrenzt wachsenden Anteil
  (FA-PRO-070).

### NFA-LEI-037 — Docker Desktop auf Windows und macOS · MUSS · V1 · PO
Unter Windows und macOS läuft der Stack in der virtuellen Maschine von Docker
Desktop, deren Speicher gesondert zugeteilt wird.

*Akzeptanz:*
- Die erforderliche Zuteilung ist in der Betriebsdokumentation benannt und wird
  bei der Einrichtung geprüft.
- Ist die Zuteilung zu gering, meldet das System das **verständlich vor dem
  Start** — nicht durch einen abbrechenden Container.
- Der Nachweis erfolgt auf **beiden** Plattformen, nicht nur auf einer.

### NFA-LEI-040 — Skalierungsverhalten · SOLL · V1 · PO ✓ (freigegeben 06.08.2026)
*Akzeptanz:*
- Der Ressourcenbedarf wächst höchstens linear mit der Gerätezahl.
- Messreihe für 3, 100, 1.000 und 5.000 Terminals liegt dokumentiert vor.

---

## B.4 Verfügbarkeit und Robustheit (NFA-VER)

### NFA-VER-010 — Fehlertoleranz gegenüber Fremdsystemen · MUSS · V1 · PO ✓ (freigegeben 06.08.2026)
*Akzeptanz:*
- Ausfall oder Zeitüberschreitung von Konnektor oder Terminal führt **nicht** zum
  Ausfall der Anwendung.
- Wiederholungen erfolgen mit wachsendem Abstand und Obergrenze — **außer bei
  PIN-Verifikationen**, dort gilt FA-PIN-030 (kein zweiter Versuch).
- Nach Rückkehr eines Fremdsystems nimmt die Überwachung selbsttätig wieder auf.

### NFA-VER-020 — Neustartfestigkeit · MUSS · V1 · PO ✓ (freigegeben 06.08.2026)
*Akzeptanz:*
- Nach Neustart des Stacks ist der Betrieb ohne manuellen Eingriff wieder
  aufgenommen.
- Unterbrochene Vorgänge (Rollout) werden erkannt und in definiertem Zustand
  fortgesetzt oder sauber abgebrochen — nie in unklarem Zwischenzustand.
- *Nachweis:* Harter Abbruch während eines laufenden Rollouts.

### NFA-VER-030 — Health-Checks · MUSS · V1 · PO
*Akzeptanz:*
- Jeder Container bietet einen Health-Check, der den tatsächlichen
  Funktionszustand prüft, nicht nur das Laufen des Prozesses.
- Der Zustand ist in der Oberfläche und maschinenlesbar abrufbar.

---

## B.5 Bedienbarkeit und Barrierefreiheit (NFA-USA)

### NFA-USA-010 — Barrierefreiheit · MUSS · V1 · PO
*Akzeptanz:*
- **WCAG 2.2 Stufe AA** eingehalten.
- Alle Funktionen sind vollständig mit der Tastatur bedienbar; die Fokusreihenfolge
  ist sinnvoll und der Fokus immer sichtbar.
- Kontrastverhältnis mindestens 4,5:1 für Text.
- Bedienelemente tragen Beschriftungen für Screenreader.
- *Nachweis:* automatisierte Prüfung ohne Verstöße **und** ein dokumentierter
  manueller Durchgang der Hauptabläufe nur mit Tastatur.

### NFA-USA-020 — Sprache und Verständlichkeit · MUSS · V1 · PO
*Akzeptanz:*
- Oberfläche, Meldungen und Hilfetexte vollständig auf Deutsch.
- Jede Fehlermeldung nennt **was zu tun ist**, nicht nur was geschah.
- Technische Codes (z. B. Konnektor-Fehler `4078`) werden übersetzt; das Original
  bleibt aufklappbar sichtbar.
- *Nachweis:* Prüfliste aller Fehlermeldungen; keine Meldung besteht nur aus
  einem Code oder einer englischen Ausnahmebeschreibung.

### NFA-USA-030 — Schutz vor Fehlbedienung · MUSS · V1 · PO
*Akzeptanz:*
- Jede nicht umkehrbare Aktion erfordert eine Bestätigung, die die Folge benennt
  und die Zahl betroffener Objekte angibt.
- Massenaktionen zeigen vor der Ausführung die betroffene Menge.
- Jeder Vorgang hat sichtbaren Zustand: läuft, fertig, fehlgeschlagen.

### NFA-USA-040 — Darstellung auf Endgeräten · MUSS · V1 · PO
*Akzeptanz:*
- Vollständig nutzbar auf Desktop (ab 1280 px) und Tablet (ab 768 px).
- Kein horizontales Scrollen der Seite; breite Tabellen scrollen innerhalb ihres
  Bereichs.
- Unterstützte Browser: jeweils aktuelle Version von Chrome, Edge und Firefox
  sowie Safari; die unterstützten Versionen sind dokumentiert.

---

## B.6 Betrieb und Wartbarkeit (NFA-BET)

### NFA-BET-010 — Konfiguration · MUSS · V1 · PO
*Akzeptanz:*
- Vollständig über Umgebungsvariablen oder eingebundene Konfigurationsdateien.
- Keine Geheimnisse im Image, keine im Repository.
- Fehlende oder ungültige Pflichtangaben führen beim Start zu einer
  **verständlichen** Fehlermeldung mit Nennung des betroffenen Werts.

### NFA-BET-020 — Datenhaltung · MUSS · V1 · PO
*Akzeptanz:*
- Persistente Daten liegen ausschließlich in Volumes, nie im Container-Layer.
- Protokolldaten liegen in einem eigenen Volume (FA-PRO-070).
- Ein Containeraustausch führt zu keinem Datenverlust. *Nachweis:* Test.

### NFA-BET-030 — Mehrere Prozessorarchitekturen · MUSS · V1 · PO
*Akzeptanz:* Images liegen für `linux/amd64` und `linux/arm64` vor und sind auf
beiden nachweislich lauffähig.

### NFA-BET-040 — Technisches Protokoll · MUSS · V1 · ADR-0005
*Akzeptanz:*
- Strukturiert (JSON) auf die Standardausgabe, mit einstellbarem Detailgrad.
- Enthält Vorgangsnummern zur Verknüpfung mit dem Fachprotokoll.
- Enthält keine Geheimnisse (NFA-SIC-010).

---

## B.7 Qualitätssicherung (NFA-QS)

### NFA-QS-010 — Automatisierte Tests · MUSS · V1 · PO
*Akzeptanz:*
- Für jede funktionale Anforderung existiert mindestens ein automatisierter Test,
  der ihre Akzeptanzkriterien prüft; die Zuordnung Anforderung → Test ist
  dokumentiert.
- Die Testsuite läuft in der Bauumgebung bei jeder Änderung; ein Fehlschlag
  verhindert die Auslieferung.

### NFA-QS-020 — Nachweise am Testaufbau · MUSS · V1 · PO
*Akzeptanz:*
- Alle Aussagen über das Verhalten von Konnektor und Kartenterminal sind am
  realen Testaufbau nachgewiesen und mit Datum, Gerät und Firmware-Stand
  dokumentiert.
- Verpflichtend geprüfte Fehlerfälle: falsche PIN (FA-PIN-030), Terminal während
  eines Vorgangs nicht erreichbar, Konnektor antwortet nicht, Firmware-Update
  über das Fensterende hinaus, Terminal kommt nach Update nicht zurück.

### NFA-QS-030 — Unterstützte Gerätematrix · MUSS · V1 · PO
*Akzeptanz:*
- Es existiert eine gepflegte Liste geprüfter Kombinationen aus Konnektor/HSK,
  Terminal-Modell und Firmware-Stand.
- Nicht geprüfte Kombinationen werden erkannt und in der Oberfläche
  gekennzeichnet; für sie findet kein Firmware-Rollout statt (FA-FW-090).
- **Version 1 unterstützt ausschließlich KoCo Konnektor und KoCo HSK**
  (Festlegung PO, 06.08.2026). Beide sind in den relevanten Punkten identisch und
  gelten daher als **eine** Anbindung.
- Konnektoren anderer Hersteller werden in Version 1 **nicht unterstützt**. Wird
  ein solcher erkannt, weist die Oberfläche darauf hin, statt ein unbestimmtes
  Verhalten zu zeigen.
- Die Kernfunktion wird dennoch **herstellerneutral** über die
  gematik-spezifizierte Fachschnittstelle umgesetzt (NFA-QS-035). Das kostet
  jetzt nahezu nichts und hält die spätere Aufnahme weiterer Hersteller offen.

### NFA-QS-035 — Webadmin-Funktionen sind optional · MUSS · V1 · PO, ADR-0006
Funktionen, die auf der herstellerspezifischen Webadmin-Schnittstelle des
Konnektors beruhen, dürfen für die Kernfunktion nicht erforderlich sein.

*Akzeptanz:*
- Überwachung und automatische PIN-Verifikation funktionieren **ausschließlich
  über die gematik-spezifizierte Fachschnittstelle**. *Nachweis:* Betrieb gegen
  einen Konnektor ohne konfigurierte Webadmin-Anbindung.
- Ist keine Webadmin-Anbindung verfügbar, werden die betroffenen Funktionen in
  der Oberfläche als nicht verfügbar gekennzeichnet — mit Angabe des Grundes,
  nicht durch stilles Fehlen.
- Die Webadmin-Anbindung ist gekapselt; ein weiterer Hersteller ist eine
  zusätzliche Umsetzung ohne Eingriff in die Ablaufsteuerung.

### NFA-QS-040 — Dokumentation · MUSS · V1 · PO
*Akzeptanz:*
- Zur Auslieferung liegen vor: Installations- und Betriebshandbuch,
  Sicherheits- und Datenschutzdokumentation, Anwenderhilfe — vollständig auf
  Deutsch.
- Jede Anforderung dieses Katalogs ist entweder umgesetzt oder mit Begründung als
  zurückgestellt gekennzeichnet.

---

# Teil C — Abgrenzung

Ausdrücklich **nicht** Gegenstand des Produkts:

| Thema | Begründung |
|---|---|
| PIN-Verwaltung für eGK und HBA | PEviM ist auf SMC-B beschränkt (`A_24080`) — technisch vom Terminal verhindert |
| Verarbeitung von Patienten- oder Gesundheitsdaten | Vorgabe PO |
| Ersatz für Konnektor, HSK oder Primärsystem | Der PIN-Roboter ist ein Clientsystem am Konnektor und ein Management-Client am Terminal |
| Medizinprodukt-Funktionen | reine Organisationssoftware, Vorgabe PO |
| Bezug und Verteilung von Firmware durch uns | Hersteller liefert an Administrator, dieser lädt hoch (ADR-0004) |
| Firmware-Downgrades | Vorgabe PO (FA-FW-080) |
| VPN-Sternpunkt | optionale spätere Ausbaustufe (ADR-0003) |

---

# Teil D — Offene Punkte

| # | Punkt | Betrifft |
|---|---|---|
| ~~1~~ | ~~Abnahme der vorgeschlagenen Anforderungen~~ — **erledigt 06.08.2026** | — |
| 2 | Datenschutzrechtliche Bewertung der Aufbewahrungsfristen | FA-PRO-080, NFA-DAT-020 |
| 3 | **Technologie-Stack** — ADR-0006 in Überarbeitung: Brainpool-TLS ist zwingend, damit ist Go ausgeschlossen | Umsetzung |
| 4 | Obergrenze der Parallelität beim Rollout — nach Messung festzulegen | FA-FW-050 |
| 5 | Referenzhardware für die Leistungsmessungen | NFA-LEI |
| 6 | Bestandsaufnahme der Testumgebung (Modelle, Firmware, PEviM, VPN) | NFA-QS-020, NFA-QS-030 |
