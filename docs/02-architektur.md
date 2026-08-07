# Architekturkonzept PIN-Roboter

- **Stand:** 07.08.2026
- **Status:** **In Umsetzung** — die Bausteinsicht entspricht dem gebauten Zustand
- **Grundlage:** [Anforderungskatalog](01-anforderungskatalog.md), ADR-0001 bis ADR-0007

> **Verhältnis zu den anderen Dokumenten.** Dieses Konzept beschreibt Bausteine,
> Verantwortlichkeiten und Abläufe. **Wie** sie gebaut sind und warum so, steht in
> [docs/03-umsetzung/](03-umsetzung/); wo beide auseinanderlaufen, gilt das
> Umsetzungsverzeichnis, und dieses Dokument ist nachzuziehen.
>
> Der Entwurf entstand technologieneutral. Die Stack-Entscheidung (ADR-0006) hat
> ihn **ausgefüllt, nicht verändert** — die Bausteine, die Vertrauensgrenze und
> die Abläufe sind unverändert geblieben. Das ist ein gutes Zeichen für den
> Schnitt.

## Umsetzungsstand (07.08.2026)

| Baustein | Stand |
|---|---|
| **Standort-Agent** | Überwachung, Sicherungslogik, PEviM-Ablauf, Registrierung — läuft |
| **Zentrale** | Oberfläche, Anmeldung mit TOTP, Fachprotokoll, Alarme, Gruppen, Export, Bestandsverwaltung, Benutzerverwaltung — läuft |
| **Simulator** | Konnektor und Terminals mit Brainpool-TLS — läuft, ersetzt echte Hardware **nicht** |
| Herstellerkapselung | angelegt, bisher nur eine Umsetzung (CHERRY-artig) |
| Bestandsverwaltung | Konnektoren und Terminals erfassen, ändern, löschen (FA-GER-010/020) — läuft |
| Geräte-Zugangsdaten | verschlüsselt in der Zentrale (AES-GCM), Abgrenzung zur PIN in ADR-0008 |
| Bedienbarkeit bei 5.000 Geräten | vollständig; **gemessen** bei 5.000 Terminals und 5 Mio Protokolleinträgen ([Messbericht](00-referenzen/messung-leistung-mengengeruest.md)) — erste Seite 14 ms, Zielwert 1 s |
| Automatische Terminal-Ermittlung | Agent ermittelt, Zentrale gleicht ab, Übernahme einzeln oder als Auswahl — nur nach Bestätigung (FA-GER-030, FA-GER-035) |
| Informationsmodell des Konnektors | wird gelesen; Aufrufkontext steht **zur Auswahl** statt abgetippt (FA-GER-035), Freitext bleibt möglich |
| Mandantentrennung | **durchgesetzt** für Bestand, Ermittlung, Protokoll, Export, Alarme, Gruppen und Benutzerliste — zentral über `Geltungsbereich` als Pflichtparameter ([Umsetzung](03-umsetzung/10-mandantentrennung.md)). Ergänzt um Rechte je Ebene ([Umsetzung](03-umsetzung/11-rechte-je-ebene.md)) |
| Betriebsdokumentation | [docs/04-betrieb.md](04-betrieb.md) — Wiederherstellung durchgeführt und als Nachweis automatisiert |
| Sicherheit und Datenschutz | [docs/05-sicherheit-datenschutz.md](05-sicherheit-datenschutz.md) — TOMs belegt, Lücken benannt |
| Benutzerverwaltung | Einladungen statt gesetzter Kennwörter, zweiter Faktor zurücksetzbar, Deaktivieren statt Löschen ([Umsetzung](03-umsetzung/12-benutzerverwaltung.md)) |
| Update-Manager | **nicht umgesetzt** |
| Firmware-Rollout | **nicht umgesetzt** (V2 laut ADR-0004) |
| Protokollansicht | Paginierung, 50 Zeilen je Seite; bei 5 Mio Einträgen 110 ms. **Grenze:** sehr tiefes Blättern (ab ~Seite 75.000) überschreitet 1 s |

Umgesetzter Stack: **Java 21**, BouncyCastle, Javalin, PostgreSQL, HTMX —
rund 14.700 Zeilen, **197 Modultests, 149 Systemnachweise** — im letzten Vollauf alle grün.

> Umsetzungsstand je Anforderung: [docs/01a-umsetzungsstand.md](01a-umsetzungsstand.md).
> Was der PO entscheiden muss: [docs/ENTSCHEIDUNGEN-PO.md](ENTSCHEIDUNGEN-PO.md).

> **Unverändert offen und weiterhin der wichtigste Punkt:** Alle Nachweise
> laufen gegen den selbstgebauten Simulator. Die in
> [ADR-0007](adr/0007-simulierte-endpunkte-und-teststrategie.md) geforderte
> Kalibrierung an echter Hardware steht aus.

---

## 1. Aufgabe und Qualitätsziele

Der PIN-Roboter überwacht eHealth-Kartenterminals und stellt den verifizierten
Zustand der eingelegten SMC-B automatisiert wieder her (Verfahren PEviM,
`[gemSpec_KT] 2.4.9`). Ergänzend verwaltet er Firmware-Stände und rollt Firmware
nach Zeitplan aus.

Die fünf Qualitätsziele in der Reihenfolge ihres Gewichts. Bei Zielkonflikten
gewinnt das höher stehende:

| # | Ziel | Woran es sich entscheidet |
|---|---|---|
| 1 | **Keine Kartensperrung** | Das System darf eine SMC-B unter keinen Umständen selbst sperren (FA-PIN-030). Alles andere ist nachrangig. |
| 2 | **Vertraulichkeit der Geheimnisse** | PIN und Terminal-Zugänge sind nirgends im Klartext greifbar (NFA-SIC-010). |
| 3 | **Betriebssicherheit** | Der TI-Zugang der Einrichtung darf nicht an diesem Produkt scheitern. |
| 4 | **Nachvollziehbarkeit** | Jede selbsttätige Handlung ist im Nachhinein erklärbar (ADR-0005). |
| 5 | **Skalierbarkeit** | Dieselbe Software trägt 3 und 5.000 Terminals (NFA-LEI). |

**Bedienbarkeit** ist kein eigenes Ziel dieser Liste, sondern Bedingung für 3 und
4: Ein System, dessen Zustand niemand versteht, ist weder betriebssicher noch
nachvollziehbar.

---

## 2. Randbedingungen

| | |
|---|---|
| Auslieferung | Docker, ein Compose-Stack, Registry GHCR (privat) |
| Plattformen | Linux, Windows, macOS · `linux/amd64` und `linux/arm64` |
| Betrieb | On-Premise beim Kunden, Betreiber ohne IT-Personal |
| Fremdsysteme | Konnektor / HSK (SOAP), Kartenterminal (HTTPS-Managementschnittstelle) |
| Verbindlich | gematik-Spezifikationen; PEviM nur für SMC-B (`A_24080`) |
| Sprache | Deutsch in Oberfläche, Meldungen und Dokumentation |

---

## 3. Kontextabgrenzung

```
                 ┌──────────────────────────────────────────┐
   Administrator │                                          │
   Benutzer   ───┤            PIN-ROBOTER                   │
   (Browser)     │                                          │
                 └───┬───────────────┬──────────────┬───────┘
                     │ SOAP          │ HTTPS        │ HTTPS
                     ▼               ▼              ▼
              ┌────────────┐  ┌─────────────┐  ┌─────────┐
              │ Konnektor  │  │Kartenterminal│  │  GHCR   │
              │  bzw. HSK  │  │              │  │ (Images)│
              └─────┬──────┘  └──────┬───────┘  └─────────┘
                    │ SICCT/TLS      │
                    └────────────────┘
                          ▼
                     ┌─────────┐
                     │  SMC-B  │
                     └─────────┘
```

### Externe Schnittstellen

| Partner | Richtung | Zweck | Wesentliche Operationen |
|---|---|---|---|
| **Konnektor / HSK** | ausgehend, SOAP | Status ermitteln, Verifikation auslösen, Bestand ermitteln | `GetPinStatus`, `VerifyPin`, `GetCards`, `GetCardTerminals`, `GetResourceInformation`; später `ChangePin` |
| **Kartenterminal** | ausgehend, HTTPS | PIN liefern (Rolle `SMC-B_Nutzer`), Firmware-Stand lesen, Firmware einspielen | herstellerspezifische Management-/REST-Schnittstelle |
| **GHCR** | ausgehend, HTTPS | Produktversionen erkennen und beziehen | Registry-API, authentifiziert |
| **SMTP** | ausgehend | Benachrichtigungen | optional |
| **Browser** | eingehend, HTTPS | Bedienung | — |

**Bemerkenswert:** Das Produkt hat **genau eine eingehende Schnittstelle** — die
Weboberfläche. Alles andere baut es selbst auf. Das ist eine bewusste
Architektureigenschaft: Sie hält die Angriffsfläche klein und erspart im
Kundennetz eingehende Firewall-Freigaben.

---

## 4. Lösungsstrategie

| Problem | Lösungsansatz |
|---|---|
| Zwei Betriebsarten, eine Software | Schnitt entlang der **Netzgrenze**: Zentrale und Standort-Agent (ADR-0002) |
| Terminals in fremden Netzen | Agent baut die Verbindung **ausgehend** auf |
| PIN darf das Praxisnetz nicht verlassen | PIN liegt **ausschließlich beim Agenten**, nie in der Zentrale |
| 3 bis 5.000 Terminals | Last liegt beim Agenten; die Zentrale sieht nur Zustandsmeldungen |
| Karte darf nie gesperrt werden | Verifikation ist **kein wiederholbarer Vorgang** — Sonderweg in der Fehlerbehandlung |
| Mandantentrennung darf nicht vergessen werden | zentral erzwungen, nicht je Abfrage |
| Protokoll darf nicht explodieren | nur Zustandsänderungen, eigenes Speicherbudget (ADR-0005) |

---

## 5. Bausteinsicht

### 5.1 Ebene 1 — Die beiden Bausteine

```
┌───────────────────────────── ZENTRALE ─────────────────────────────┐
│  Weboberfläche · Identität & Rechte · Mandanten & Bestand          │
│  Regelwerk & Zeitpläne · Fachprotokoll · Alarmierung               │
│  Update-Manager · Agentenvermittlung                               │
│  ── kennt KEINE SMC-B-PIN und KEINE Terminal-Zugänge ──            │
└───────────────────────────────▲────────────────────────────────────┘
                                │  Agentenprotokoll
                                │  (ausgehend vom Agenten,
                                │   beidseitig authentifiziert)
┌───────────────────────────────┴──── STANDORT-AGENT ────────────────┐
│  Auftragsausführung · Konnektor-Anbindung (SOAP)                   │
│  Terminal-Anbindung (HTTPS, herstellerspezifisch gekapselt)        │
│  Geheimnisspeicher · Ereignispuffer · lokale Ablaufsteuerung       │
└────────────────────────────────────────────────────────────────────┘
```

**Die Trennlinie ist die Vertrauensgrenze.** Sie verläuft nicht nach technischer
Schichtung, sondern danach, wo Geheimnisse liegen dürfen: Die Zentrale steuert
und protokolliert, der Agent besitzt und handelt.

| | **Zentrale** | **Standort-Agent** |
|---|---|---|
| Kennt Geheimnisse | nein | ja (PIN, Terminal-Zugänge) |
| Spricht mit Geräten | nein | ja |
| Hält Bestand und Historie | ja | nur was er lokal braucht |
| Läuft ohne den anderen | ja (eingeschränkt) | **ja, vollständig** (FA-AGT-030) |
| Anzahl je Installation | 1 | 1 bis n |

### 5.2 Zentrale — Komponenten

| Komponente | Verantwortung |
|---|---|
| **Weboberfläche** | Darstellung, Bedienung, Barrierefreiheit |
| **Identität & Rechte** | Anmeldung, TOTP, Sitzungen, Rollen, Onboarding |
| **Mandanten & Bestand** | Serviceorganisationen, Einrichtungen, Gruppen, Geräte, Karten |
| **Regelwerk & Zeitpläne** | Prüfintervalle, Rollout-Pläne, Zeitfensterberechnung, Not-Aus |
| **Auftragsvergabe** | erzeugt Aufträge für Agenten, verfolgt deren Zustand |
| **Fachprotokoll** | Ereignisse aufnehmen, abfragen, exportieren, rotieren |
| **Alarmierung** | Alarmzustände führen, benachrichtigen, quittieren |
| **Update-Manager** | Versionen erkennen, Freigabe, Ausführung, Rollback (ADR-0001) |
| **Agentenvermittlung** | Registrierung, Verbindungen, Versionsabgleich |

### 5.3 Standort-Agent — Komponenten

| Komponente | Verantwortung |
|---|---|
| **Auftragsausführung** | Aufträge annehmen, ausführen, Ergebnis melden |
| **Ablaufsteuerung** | Reihenfolge, Nebenläufigkeit, Lastbegrenzung je Konnektor |
| **Konnektor-Anbindung** | SOAP-Aufrufe, Aufrufkontext, Fehlerübersetzung |
| **Terminal-Anbindung** | HTTPS-Sitzung, Rolle `SMC-B_Nutzer`, PIN-Lieferung, Firmware |
| **Geheimnisspeicher** | verschlüsselte Ablage von PIN und Zugängen |
| **Ereignispuffer** | Ereignisse zwischenspeichern, nach Wiederverbindung nachliefern |
| **Sicherungslogik** | `LeftTries` prüfen, Not-Aus beachten, Zustand „aktualisierend" beachten |

> **Die Sicherungslogik sitzt bewusst im Agenten, nicht in der Zentrale.** Sie
> muss auch dann greifen, wenn die Verbindung zur Zentrale gerade fehlt. Eine
> Schutzfunktion, die eine Netzverbindung voraussetzt, ist keine.

### 5.4 Herstellerkapselung — auf beiden Seiten

Herstellerspezifisches Verhalten tritt an **zwei** Stellen auf. Beide werden nach
demselben Muster hinter einer einheitlichen inneren Schnittstelle gekapselt:

```
                    Ablaufsteuerung
                    │              │
        ┌───────────┘              └───────────┐
        ▼                                      ▼
┌─────────────────────────┐    ┌───────────────────────────────┐
│  Terminal-Anbindung     │    │  Konnektor-Anbindung          │
│  anmelden · pinLiefern  │    │  pinStatus · pinVerifizieren  │
│  firmwareLesen          │    │  kartenErmitteln              │
│  firmwareEinspielen     │    │  terminalsErmitteln           │
├───────┬────────┬────────┤    ├───────────────┬───────────────┤
│CHERRY │Ingenico│  …     │    │ Fachschnitt-  │  Webadmin     │
│       │        │        │    │ stelle (gema- │  (hersteller- │
│       │        │        │    │ tik, generisch│   spezifisch) │
└───────┴────────┴────────┘    └───────────────┴───────────────┘
```

**Terminal-Seite:** Ein neuer Hersteller ist eine neue Umsetzung der Schnittstelle
plus ein Eintrag in der Gerätematrix (NFA-QS-030) — **ohne Eingriff in die
Ablaufsteuerung**.

**Konnektor-Seite:** Hier ist zusätzlich zwischen zwei Schnittstellenarten zu
unterscheiden (ADR-0006):

- Die **Fachschnittstelle** (Kartendienst, `[gemSpec_Kon] 4.1.5.5`) ist von der
  gematik spezifiziert und im Grundsatz herstellerübergreifend gleich. Sie trägt
  die Kernfunktion. Dass ein bestimmter Hersteller sich spezifikationsgemäß
  verhält, gilt erst nach Messung am Testaufbau als belegt.
- Die **Webadmin-Schnittstelle** ist herstellerspezifisch und steht uns nur für
  **KoCo Konnektor und KoCo HSK** zur Verfügung (in den relevanten Punkten
  identisch, daher **eine** Anbindung). Funktionen, die darauf beruhen, sind
  optional: Bei einem Konnektor ohne verfügbare Webadmin-Anbindung entfallen sie
  und werden in der Oberfläche als nicht verfügbar ausgewiesen — die
  Kernfunktion bleibt davon unberührt.

Diese Trennung ist die wichtigste Erweiterungsstelle des Systems. Sie entscheidet
darüber, ob ein weiterer Hersteller ein überschaubarer Zusatz ist oder ein
Umbau.

---

## 6. Laufzeitsicht

### 6.1 Automatische Verifikation — der Kernablauf

```
Agent                          Konnektor        Terminal
  │                                │                │
  │─ GetPinStatus ────────────────▶│───────────────▶│
  │◀─ VERIFIABLE, LeftTries=3 ─────│                │
  │                                │                │
  │  ┌─────────── Sicherungsprüfung (lokal) ──────┐ │
  │  │ Not-Aus aktiv?        → Abbruch            │ │
  │  │ Zustand aktualisierend? → Abbruch          │ │
  │  │ Automatik angehalten? → Abbruch            │ │
  │  │ LeftTries verringert? → Abbruch + Alarm    │ │
  │  │ PIN hinterlegt?       → sonst Meldung      │ │
  │  └────────────────────────────────────────────┘ │
  │                                │                │
  │─ Sitzung als SMC-B_Nutzer ─────┼───────────────▶│
  │─ VerifyPin (PIN.SMC) ─────────▶│                │
  │                                │─ SICCT PERFORM│
  │                                │  VERIFICATION ▶│
  │─ PIN über Managementschnittstelle ─────────────▶│
  │◀─ PinResult ───────────────────│                │
  │                                │                │
  │─ GetPinStatus (Bestätigung) ──▶│───────────────▶│
  │◀─ VERIFIED ────────────────────│                │
  │                                │                │
  └─ Ereignisse an die Zentrale (eine Vorgangsnummer)
```

**Zwei Dinge sind an diesem Ablauf nicht verhandelbar:**

1. Die **Sitzung am Terminal muss vor dem `VerifyPin` bestehen.** `A_24081`
   verlangt, dass die Rolle `SMC-B_Nutzer` beim Eintreffen des SICCT-Kommandos
   bereits authentisiert ist. Die Reihenfolge ist Teil der Spezifikation, nicht
   der Bequemlichkeit.
2. Die **abschließende Statusabfrage ist Pflicht.** Ein `PinResult = OK` ist die
   Antwort auf einen Aufruf; erst `VERIFIED` ist der bestätigte Zustand.

### 6.2 Verhalten bei `PinResult = REJECTED`

```
REJECTED  →  Automatik für diese Karte ANHALTEN
          →  Alarm mit verbleibenden Versuchen
          →  Ereignis protokollieren
          →  KEIN zweiter Versuch
```

Das ist der einzige Ort im System, an dem ein Fehlschlag **nicht** wiederholt
wird. Überall sonst gilt Wiederholung mit wachsendem Abstand — hier wäre sie der
direkte Weg zur gesperrten Karte. Der Sonderweg ist im Code als solcher zu
kennzeichnen und durch einen verpflichtenden Test abgesichert (FA-PIN-030).

### 6.3 Firmware-Rollout im Zeitfenster

```
Fenster öffnet
   │
   ├─ Kanarienvogel: erstes Gerät, immer einzeln
   │     Update → Neustart abwarten → Version prüfen
   │     → PIN-Status prüfen → SMC-B verifizieren
   │     └─ Fehlschlag → Rollout der Gruppe STOPPT + Alarm
   │
   ├─ übrige Geräte, bis zur eingestellten Parallelität
   │     je Gerät dieselbe Nachbehandlung
   │     nicht erreichbar → überspringen, Versuch zählen
   │
   ├─ vor jedem Start: passt die erwartete Dauer noch ins Fenster?
   │     nein → keine weiteren Geräte starten
   │
   └─ Fenster endet
         laufende Geräteupdates werden ZU ENDE GEFÜHRT
         Rest bleibt in der Warteschlange → nächstes Fenster
```

Der Rollout ist ein **langlebiger Vorgang über mehrere Fenster**, kein einmaliger
Lauf. Sein Zustand ist persistent und übersteht einen Neustart (NFA-VER-020).

### 6.4 Ausfall der Zentrale

```
Verbindung bricht ab
   │
   ├─ Agent arbeitet WEITER: Überwachung, Verifikation, Sicherungslogik
   ├─ Ereignisse werden gepuffert (begrenzt, älteste Routine zuerst verworfen)
   ├─ Kein neuer Rollout wird begonnen (Freigabe kommt aus der Zentrale)
   │
   └─ Verbindung kehrt zurück
         Ereignisse werden vollständig und geordnet nachgeliefert
         Zustandsabgleich, dann Normalbetrieb
```

Begründung: Die Kernfunktion sichert den TI-Zugang und darf nicht von der
Zentrale abhängen. Ein Firmware-Rollout ist dagegen ein geplanter Eingriff — den
ohne Aufsicht zu beginnen wäre falsch.

---

## 7. Verteilungssicht

### Verteilter Betrieb — Einzelpraxis, MVZ

```
┌──── Praxisnetz ──── ein Compose-Stack ─────┐
│  Zentrale · Agent · Datenbank              │
└───────────────┬────────────────────────────┘
                │ lokales Netz
      Konnektor ─── Kartenterminals ─── SMC-B
```
Ein Startbefehl, eine Adresse, eine Oberfläche. Die Ebene der
Serviceorganisationen bleibt im Datenmodell vorhanden, in der Oberfläche jedoch
ausgeblendet (FA-INS-030).

### Zentraler Betrieb — Serviceorganisation, Klinik

```
┌── Rechenzentrum ──┐
│  Zentrale         │
│  Datenbank        │
└─────────▲─────────┘
          │ ausgehend vom jeweiligen Agenten
   ┌──────┼───────┬──────────────┐
 Agent  Agent   Agent        Agent
Praxis1 Praxis2  MVZ3     Klinik Abt. A
   │      │       │            │
   └─ je lokal: Konnektor/HSK + Terminals + SMC-B
```

Nur die Zentrale muss erreichbar sein; in den Kundennetzen ist **keine einzige
eingehende Freigabe** nötig. Die Last der Geräteabfragen bleibt verteilt — das
ist der Grund, warum 5.000 Terminals beherrschbar sind.

---

## 8. Querschnittskonzepte

### 8.1 Mandantentrennung

Jedes mandantenbezogene Objekt trägt die Einrichtungskennung. Die Einschränkung
auf den Geltungsbereich des Aufrufers wird an **einer zentralen Stelle** in der
Datenzugriffsschicht erzwungen — nicht in einzelnen Abfragen.

**Begründung:** Die häufigste schwere Lücke mandantenfähiger Anwendungen ist die
eine vergessene Filterbedingung. Hier ginge es um SMC-B-Daten fremder Praxen.
Eine Abfrage ohne Geltungsbereich muss deshalb **technisch unmöglich** sein, nicht
nur unüblich. Der Nachweis erfolgt für jeden Zugriffspunkt automatisiert
(FA-ORG-020) — einschließlich Protokoll und Export.

### 8.2 Umgang mit Geheimnissen

| Geheimnis | Liegt beim | Sichtbar für |
|---|---|---|
| SMC-B-PIN | Agent | niemanden nach dem Speichern |
| Terminal-Administrationszugang | Agent | niemanden nach dem Speichern |
| Konnektor-Zugang | Agent | niemanden nach dem Speichern |
| GHCR-Token | Zentrale | niemanden nach dem Speichern |
| Benutzerpasswort | Zentrale | niemandem (nur Prüfwert gespeichert) |
| TOTP-Geheimnis | Zentrale | einmalig bei der Einrichtung |

Regeln:
- Verschlüsselte Ablage; der Schlüssel liegt weder im Image noch im Repository.
- **Einbahnstraße:** Es existiert keine Schnittstelle, die ein Geheimnis
  zurückliefert. Auch nicht für Administratoren.
- Die Unterdrückung in Protokollen und Fehlermeldungen ist **zentral** umgesetzt.
  Eine neue Protokollstelle kann sie nicht umgehen — sonst wäre sie eine Frage
  der Sorgfalt, und Sorgfalt versagt irgendwann.

### 8.3 Nebenläufigkeit und Lastbegrenzung

- Geräteabfragen laufen **nebenläufig mit Obergrenze je Konnektor**. Ein langsamer
  Konnektor darf andere nicht ausbremsen (NFA-LEI-020).
- Die Warteschlange ist **fair**: Kein Gerät verhungert, weil andere ständig
  Fehler erzeugen.
- Prüfintervalle werden **zeitlich gestreut**, damit nicht alle 5.000 Geräte
  gleichzeitig abgefragt werden.
- Aufträge sind **idempotent**: Ein doppelt zugestellter Auftrag darf keine zweite
  Verifikation auslösen.

### 8.4 Fehlerbehandlung

| Fehlerart | Verhalten |
|---|---|
| Fremdsystem nicht erreichbar | Wiederholung mit wachsendem Abstand, Obergrenze, Alarm |
| Zeitüberschreitung | wie oben; laufende Vorgänge sauber beenden |
| **Fehlgeschlagene PIN-Verifikation** | **keine Wiederholung**, anhalten, alarmieren |
| Ungültige Konfiguration | beim Start abbrechen mit verständlicher Meldung |
| Unerwarteter Zustand am Gerät | anhalten und melden, nicht raten |

Jede Fehlermeldung existiert **zweifach**: technisch für das Diagnoseprotokoll,
verständlich für die Oberfläche (NFA-USA-020). Die Übersetzung ist eine
gepflegte Zuordnung, kein Zufallsprodukt — auch für Konnektor-Fehlercodes wie
`4078`.

### 8.5 Zeit

- Alle Zeitpunkte werden intern in **UTC** geführt, in der Oberfläche lokal
  dargestellt.
- Rollout-Zeitfenster werden in der **Zeitzone der Einrichtung** ausgewertet —
  eine Klinik in einer anderen Zeitzone als das Rechenzentrum ist im zentralen
  Betrieb ein realer Fall.
- Sommer-/Winterzeit: Ein Fenster, das durch die Umstellung ausfällt oder doppelt
  auftritt, ist definiert zu behandeln und zu testen (FA-FW-020).
- Ein Zeitversatz zwischen Agent und Zentrale wird erkannt und gemeldet — TOTP
  und Zeitfenster hängen daran.

### 8.6 Datenmodell — tragende Beziehungen

```
Serviceorganisation ─1:n─ Einrichtung ─1:n─ Gerätegruppe ─1:n─ Kartenterminal
                                │                 │                 │
                                │                 │                 └─1:n─ Karte (SMC-B)
                                ├─1:n─ Konnektor/HSK ──────────────────────┘
                                ├─1:n─ Standort-Agent
                                └─1:n─ Benutzer (mit Rolle und Geltungsbereich)

Gerätegruppe ─1:n─ Rollout-Plan ─1:n─ Zeitfenster
Rollout ─1:n─ Rollout-Auftrag (je Gerät, mit Zustand und Versuchszählung)
Protokollereignis ─n:1─ Einrichtung, optional ─n:1─ Gerät, ─n:1─ Vorgang
```

Bewusste Festlegungen:
- **Die Einrichtung ist der Mandantenanker.** Jedes fachliche Objekt hängt direkt
  oder indirekt an ihr.
- **Gerätegruppen und Zeitfenster gehören in Version 1**, auch wenn der Rollout
  später kommt. Nachrüsten wäre ein Umbau des halben Modells.
- **Protokollereignisse liegen physisch getrennt** von den Betriebsdaten
  (eigenes Volume, FA-PRO-070) — unterschiedliches Wachstum, unterschiedliche
  Aufbewahrung, unterschiedliches Ausfallrisiko.
- **Ein Terminal kann später über verschiedene Wege erreichbar sein** (direkt
  oder über Tunnel, ADR-0003). Heute ist das ein Feld, später eine Funktion.

### 8.7 Protokollierung

Zwei getrennte Ströme (ADR-0005): das **Fachprotokoll** in der Datenbank, nur
anfügbar, mit Vorgangsnummer und fester Ereignisliste — und das **technische
Protokoll** als strukturierte Ausgabe. Die Vorgangsnummer verbindet beide, damit
der Support eine fachliche Meldung bis in die technischen Details verfolgen kann.

---

## 9. Was diese Architektur bewusst nicht tut

| Nicht getan | Warum |
|---|---|
| Feingliedrige Aufteilung in viele Dienste | Der Betreiber hat kein IT-Personal. Zwei Bausteine sind das Minimum, das die Netzgrenze erzwingt — jeder weitere erhöht die Betriebslast ohne Gegenwert. |
| Nachrichtenbroker als eigene Komponente | Bei 5.000 Geräten und Sekunden-Intervallen nicht erforderlich; er würde eine dritte Instanz in jede Praxis bringen. |
| Eigene Kryptographie | Ausschließlich etablierte, geprüfte Verfahren und Bibliotheken. |
| Zwischenspeicher als eigene Instanz | Erst wenn eine Messung ihn rechtfertigt. |
| PIN in der Zentrale | Bewusste Entscheidung aus ADR-0002 — sie kostet Bequemlichkeit und spart im Schadensfall die PINs aller betreuten Praxen. |

---

## 10. Risiken

| Risiko | Wirkung | Gegenmaßnahme |
|---|---|---|
| PEviM-Kette verhält sich real anders als spezifiziert | Kernfunktion trägt nicht | **Technischer Durchstich am Testaufbau vor allem anderen** |
| Herstellerschnittstellen weichen stark voneinander ab | Aufwand je Hersteller unterschätzt | Kapselung (5.4); früh zwei Hersteller prüfen, nicht einen |
| Fehlerhafte Sicherungslogik sperrt eine Karte | schwerster denkbarer Schaden | Sonderweg im Code, verpflichtender Test vor jeder Auslieferung |
| Mandantentrennung an einer Stelle vergessen | fremde Praxisdaten sichtbar | zentrale Durchsetzung, Test je Zugriffspunkt |
| Leistungsziele bei 5.000 Geräten verfehlt | Produkt für Kliniken untauglich | Messreihe früh, nicht am Ende (NFA-LEI-040) |
| Drei Zielplattformen erzeugen Sonderfälle | Supportlast | von Beginn an auf allen drei bauen und testen |

---

## 11. Offene Punkte

1. **ADR-0006 Technologie-Stack** — steht aus.
2. **Datenhaltung im zentralen Betrieb** — eine Datenbank für alle Mandanten oder
   getrennte Schemata? Entscheidung nach Messung und Datenschutzbewertung.
3. **Agentenprotokoll** — konkrete Ausgestaltung; hängt an der Stack-Entscheidung.
4. **Referenzhardware** für die Leistungsmessungen.
