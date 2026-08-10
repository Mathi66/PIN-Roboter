# ADR-0011 — Die PIN-Übergabe ist eine Anfrage des Terminals, kein Auftrag an es

**Status:** angenommen · **Datum:** 10.08.2026
**Löst ab:** die Annahme über den Übergabeweg in
[ADR-0007](0007-simulierte-endpunkte-und-teststrategie.md) und in
[der TI-Analyse](../00-referenzen/analyse-ti-kartenterminals.md), Abschnitt 4
**Betrifft:** FA-PIN-010, FA-PIN-020, FA-PIN-060, NFA-LEI-010, ADR-0009

---

## Anlass

Der PO fragte am 10.08.2026: *„An welcher Stelle wendest du SICCT an?"* — und
stellte fest: *„Der Konnektor schickt keine PIN an das Terminal."* Er hat recht.
Die daraufhin geforderte Recherche in den Herstellerdokumenten hat ergeben, dass
**unser Übergabemodell falsch ist**.

Das ist genau der Fall, für den ADR-0007 den Vorbehalt formuliert hat: Der
Simulator bildet unsere Annahme nach, nicht die Wirklichkeit. Alle Nachweise
waren grün — sie belegten nur, dass Agent und Simulator zueinander passen.

## Was wir angenommen hatten

Der Agent baut eine Sitzung zur Managementschnittstelle auf, **schiebt** die PIN
hin (`POST /api/{terminal}/pin`), löst über den Konnektor `VerifyPin` aus und
meldet sich ab. Ein kurzer Auftrag, danach ist die Verbindung wieder frei.

## Was die Hersteller tatsächlich machen

### CHERRY ST-1506

Quelle: **ST-1506 Remote Management Specification v2.8**, Cherry Digital Health
GmbH, 24.03.2025, öffentlich („Distribution Status: unrestricted").

Es gibt **zwei getrennte WebSocket-Schnittstellen**:

| | Remote Management API | **Remote SMCB API** |
|---|---|---|
| Protokollname | `cobra` | **`cobra-smcb`** |
| Zweck | Konfiguration | **PIN-Eingabe** |
| Gleichzeitig | mehrere | **höchstens eine** |

Der Ablauf nach Abschnitt 7.4:

1. Client holt über die Management-API per `SmcbAuthentication` einen
   **ephemeren AES256-Schlüssel**. Er ist **30 Sekunden** gültig.
2. Client verbindet sich auf `cobra-smcb`.
3. **Das Terminal** sendet einen `AuthenticateRequest` mit einer Challenge.
4. Client antwortet mit `CMAC(Key, Challenge)` über AES256.
5. Zustand `Authenticated`. Die Verbindung **bleibt offen**.

Und dann der Kern, wörtlich:

> „While authenticated the Remote SMCB API will send `OutputRequests` and
> **`InputPinRequests` if a PIN verification is requested on the respective slot
> from the Konnektor**."

`InputPinRequest` trägt die Richtungsangabe **„Direction: ST1506 to Client"**.
Das Terminal fragt, der Client antwortet — mit Angaben wie `Slot`, `Atr`,
`MinLen`, `MaxLen`, `TimeoutFirst`, `TimeoutOther`, `TimeoutAll`. Wird die Karte
während der Anfrage gezogen, sendet das Terminal `Cancel`.

**Und eine Einschränkung, die eine Anforderung berührt:**

> „`MessageType` allows only the option `Validate` since **PIN modification
> operations are never requested via the Remote PIN API**."

### Worldline / Ingenico ORGA 6141 online (ORGA Neo)

Quelle: **Bedienungsanleitung ORGA 6141 online, Firmware 3.9.0, V24.5.1**,
Abschnitt 7.2.3.4.

Dasselbe Muster, andere Worte:

> „Der SMC-PIN-Provider ist eine **Rolle einer externen Instanz**, zu welcher
> eine lokale PIN-Eingabeaufforderung für eine gesteckte SMC-B per RMI
> **umgeleitet** werden kann. […] Statt den SMC-B-PIN-Eingabe-Dialog lokal am
> Terminal durchzuführen, wird eine Meldung am Display dargestellt, welche
> informiert, dass der **PIN-Eingabe-Request per RMI an den externen
> SMC-PIN-Provider umgeleitet** wurde."

Das RMI nutzt ebenfalls **WSS (RFC 6455)** und ein typsicheres RPC. Die Rollen
sind benannt: RMI-Administrator, **SMC-B PIN Provider**, Anonymous. Der Zugang
erfolgt über eine achtstellige, am Gerät vereinbarte Provider-PIN.

Beide Hersteller bestätigen auch den Vorbehalt aus `A_24114` ausdrücklich:

> „Nur bei der Verwendung der sicheren PIN-Eingabe am Gerät befindet sich das
> Kartenterminal im sicheren zertifizierten Betriebszustand. Der ADMIN und
> Nutzer/SMC-B-PIN-Provider gewährleisten, dass […] die entfernte Eingabe der
> PIN der SMC-B ausschließlich an Clients geschieht, die dieser oder seine
> Organisation unter Kontrolle hat."

## Entscheidung

**Der Agent wird zum PIN-Provider: ein Dienst, der bereitsteht und antwortet —
kein Auftraggeber, der etwas hinschiebt.**

| | bisher (falsch) | künftig |
|---|---|---|
| Muster | Agent sendet PIN | **Terminal fragt, Agent antwortet** |
| Verbindung | kurz, dann abmelden | **dauerhaft offen je Terminal** |
| Authentisierung | Sitzung mit Kennwort | **Challenge-Response, CMAC-AES256** |
| Gleichzeitigkeit | beliebig | **eine Verbindung je Terminal** |
| Zeitverhalten | keins | **Zeitfenster kommen mit der Anfrage** |
| Abbruch | nicht vorgesehen | **`Cancel` beim Kartenziehen** |

Die Richtung des Verbindungsaufbaus bleibt: **Der Agent baut auf**, das Terminal
öffnet keine Verbindung zu ihm. Die Aussage „der Agent braucht keine eingehende
Freigabe" gilt weiter — er muss die Verbindung aber **halten**, statt sie zu
benutzen und zu schließen.

### Die harte Folge: Nebenläufigkeit

Eine dauerhafte Verbindung **je Terminal**, und je Gerät nur eine gleichzeitig.
Das Mengengerüst nennt **5.000 Terminals**. Damit wird aus einer Frage der
Abfragelast eine Frage der Verbindungsverwaltung — ein anderes Problem als das,
für das die Ablaufsteuerung heute gebaut ist.

Zwei Auswege, beide noch zu bewerten:

1. **Verbindung nur halten, wo eine SMC-B steckt.** Im Demobestand ist das
   *ein* Terminal je Praxis — bei 5.000 Geräten also eher 1.250 als 5.000
   Verbindungen. Das ist kein Zufall, sondern die Wirklichkeit einer Praxis: Die
   SMC-B steckt an einer Stelle.
2. **Verbindung nur halten, solange etwas zu erwarten ist.** Der Konnektor löst
   die Verifikation aus; der Agent weiß das, weil er sie selbst anstößt. Ob das
   Zeitfenster reicht (Schlüssel 30 s gültig) und ob das Terminal dann noch
   fragt, ist am Testaufbau zu messen — **nicht zu vermuten**.

### FA-PIN-060 (PIN ändern) ist so nicht umsetzbar

CHERRY schließt es über diesen Weg ausdrücklich aus. Die Anforderung ist auf
**„nicht über die Remote-Schnittstelle möglich"** zurückzustufen, bis geklärt
ist, ob Worldline es anders hält oder ob es einen anderen Weg gibt. Die gebaute
Funktion bleibt im Code, ist aber **nicht als nachgewiesen zu führen**.

## Konsequenzen

**Umbauumfang** — erheblich, betrifft die Kernfunktion:

- `TerminalClient`: von Request/Response auf eine langlebige WebSocket-Verbindung
- neu: Challenge-Response mit CMAC-AES256, Schlüsselbeschaffung über die
  Management-API, 30-Sekunden-Fenster
- neu: Zustandsmodell je Verbindung (`Disconnected`, `Connected`,
  `Authenticated`)
- `Verifikationsablauf`: von „PIN senden" auf „Anfrage beantworten"
- `Ablaufsteuerung`: Verbindungsverwaltung statt Abfragetakt
- Simulator: `cobra-smcb` nachbilden, samt `InputPinRequest`, `Cancel` und
  Zeitfenstern
- die Sicherungslogik (`LeftTries`, Not-Aus, KSR-Zustand) bleibt inhaltlich
  gültig, muss aber **vor dem Beantworten** greifen statt vor dem Senden

**Was gültig bleibt:**

- Die PIN-Ablage im Agenten und die Ende-zu-Ende-Verschlüsselung aus
  [ADR-0009](0009-pin-hinterlegen-ueber-die-oberflaeche.md) — der Weg von der
  Oberfläche zum Agenten ist unberührt.
- Die Sicherungsleitplanke: `LeftTries` prüfen, Abbruch statt Wiederholung.
- Die Herstellerkapselung (Architektur 5.4) — sie wird von einer
  Vorsichtsmaßnahme zur **Notwendigkeit**: CHERRY und Worldline haben
  verschiedene Nachrichtenformate für dasselbe Muster.

**Was noch offen ist:**

- Die vollständige Worldline-RMI-Spezifikation. Der öffentlich verlinkte PDF-Pfad
  liefert 404; die Bedienungsanleitung beschreibt das Verfahren, nicht die
  Nachrichten. **Ohne sie ist der Worldline-Zweig nicht baubar.**
- Ob das Terminal die Verbindung dauerhaft erwartet oder ein Aufbau kurz vor der
  Verifikation genügt.
- Zeitverhalten unter Last: 1.250 offene Verbindungen auf der Referenzhardware
  aus ADR-0006.

## Warum das nicht sofort umgebaut wird

Der Umbau ist groß und betrifft die Kernfunktion. Ihn auf Grundlage einer
**Dokumentenlektüre** zu machen, wäre derselbe Fehler wie der, den diese ADR
korrigiert — nur mit einer besseren Quelle. Die Reihenfolge ist:

1. **Diese ADR und die Korrekturen in der Dokumentation** (sofort — damit
   niemand auf ein falsches Modell aufbaut)
2. **Messung am Testaufbau**: Wie verhält sich ein echtes ST-1506? Hält es die
   Verbindung? Wann kommt `InputPinRequest`? Was passiert bei Zeitüberschreitung?
3. **Dann** der Umbau, gegen einen Simulator, der nach der Messung kalibriert ist

Der Testaufbau steht laut PO bereit. Damit ist Schritt 2 keine Wartezeit,
sondern der nächste Arbeitsschritt.

> **Bis dahin gilt:** Die PIN-Übergabe ist **nicht** als funktionsfähig gegenüber
> echter Hardware zu führen. Der Umsetzungsstand ist entsprechend zu
> korrigieren, und die Freigabemitteilung zu 0.2.0 sagt es bereits: nicht für den
> Produktivbetrieb an echten Kartenterminals.

---

## Nachtrag vom 10.08.2026 — der Umbau ist erfolgt

Der PO hat den Umbau **vor** der Messung am Testaufbau beauftragt. Die
Entscheidung dieser ADR ändert sich dadurch nicht; die Reihenfolge oben schon.
Das ist hier festgehalten, damit später niemand rätselt, warum Schritt 3 vor
Schritt 2 kam.

**Was gebaut wurde**

| Baustein | Datei | Inhalt |
|---|---|---|
| Protokollkern | `gemeinsam/SmcbProtokoll.java` | Nachrichtenschema, CMAC-AES256, Regeln aus 7.7.8 |
| Verbindung | `agent/SmcbVerbindung.java` | Zustandsmodell, Challenge-Response, Beantwortung von `InputPinRequest` |
| Verwaltung | `agent/SmcbVerbindungen.java` | eine Verbindung je Gerät, Auf- und Abbau nach Sollzustand, Sperrfrist |
| Ablauf | `agent/Verifikationsablauf.java` | von „PIN senden" auf „Konnektor anstoßen und auswerten" |
| Gegenstelle | `simulator/SmcbApiNachbildung.java` | eigener RFC-6455-WebSocket, weil der HTTP-Server des JDK nicht umschalten kann |

**Was der Umbau zusätzlich zutage gefördert hat**

Zwei Punkte, die im Modell „der Agent schiebt" gar nicht auftreten konnten:

1. **Slot statt Kartenhandle.** Das Terminal fragt unter Angabe eines
   Steckplatzes, der Konnektor spricht von Kartenhandles. Die Zuordnung kommt
   jetzt aus `SlotId` im `CardInfoType` und wird in jedem Durchgang neu
   aufgebaut. Ein unbekannter Slot führt zur **Ablehnung mit Begründung**, nicht
   zu einem Versuch mit irgendeiner PIN.
2. **Fremd ausgelöste Anfragen.** Solange die Verbindung steht, kann jede Stelle
   am Konnektor eine PIN-Anfrage auslösen — nicht nur wir. Die Sicherungslogik
   ist damit von einer Vorprüfung zur **einzigen Bremse** geworden und läuft
   deshalb bei jeder Anfrage erneut, gegen einen Kenntnisstand, der höchstens
   90 Sekunden alt sein darf.

**Nachgewiesen** — `pruefungen/smcb-durchstich.sh`, neun Prüfpunkte, alle grün.
Entscheidend ist dabei nicht, dass die Karte freigeschaltet wird, sondern **auf
welchem Weg**: Der Simulator zählt die über `cobra-smcb` gelieferten PINs mit.
Ein Nachweis, der nur das Ergebnis prüft, war schon einmal grün, während das
Modell falsch war.

**Was weiterhin offen bleibt** — unverändert die Liste oben, insbesondere:
Zeitverhalten und Verbindungsdauer an echter Hardware, die Worldline-Variante,
`FA-PIN-060`. Der Umbau macht die Messung am Testaufbau nicht überflüssig, er
gibt ihr einen konkreten Gegenstand.
