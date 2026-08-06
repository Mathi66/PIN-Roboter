# ADR-0004: Firmware-Verwaltung der Kartenterminals

- **Status:** **Angenommen** (Product Owner, 06.08.2026) — Umsetzung gestuft
- **Datum:** 06.08.2026
- **Betrifft:** Funktionsumfang, Architektur, Betrieb, Haftung

---

## Entscheidung

Der PIN-Roboter übernimmt die **Firmware-Verwaltung der Kartenterminals**,
einschließlich des gesteuerten Ausrollens nach **Rollout-Plänen je Gerätegruppe**.

Festlegungen des POs:

1. **Bezugsweg:** Die Firmware wird **vom Hersteller an den Administrator**
   verteilt. Der Administrator lädt sie in den PIN-Roboter. Wir beziehen und
   verteilen keine Firmware selbst.
2. **Der Administrator erstellt Rollout-Pläne**, damit der Praxis- und
   Klinikbetrieb nicht unterbrochen wird. Der Roboter führt die Updates gemäß
   Plan aus.
3. **Gerätegruppen** (z. B. Abteilungen) erhalten **eigene Rollout-Pläne**.
4. **Wiederaufnahme:** Was in einem Zeitfenster nicht fertig wird, läuft im
   nächsten freigegebenen Fenster weiter.
5. **Das Risiko wird bewusst getragen** — im Gegenzug gilt eine erhöhte
   Testpflicht.

---

## Kontext und Motivation

Bei bis zu 5.000 Terminals ist Firmware-Pflege von Hand nicht leistbar. Der
eigentliche Bedarf ist aber ein anderer: **Kliniken und Apotheken arbeiten rund
um die Uhr.** Es gibt kein allgemeines Wartungsfenster — es gibt nur
abteilungsweise Zeiträume, in denen gerade nicht gearbeitet wird.

Beispiel des POs:
> Abteilung A hat 24 Kartenterminals und ist jeden Mittwoch und Freitag von
> 01:00 bis 04:00 Uhr nicht besetzt. In dieser Zeit darf der Roboter
> aktualisieren. Was er nicht schafft, macht er im nächsten freigegebenen
> Zeitfenster.

Genau das kann heute niemand: Der Konnektor rollt Firmware aus, **kennt aber
keinen Rollout-Plan** (Feststellung des POs: „Der Konnektor ist stumpf beim
Updaten"). Er aktualisiert, wann es ihm passt — in einer Klinik mitten im
Nachtdienst.

---

## Faktenlage (geprüft am 06.08.2026, `[gemSpec_KT] V3.17.1`)

### Updates aus dem LAN sind ausdrücklich vorgesehen

| ID | Inhalt (sinngemäß) |
|---|---|
| `TIP1-A_2965` | Das Terminal **muss** eine sichere Update-Möglichkeit besitzen — auch Downgrade auf eine über das Firmware-Gruppen-Konzept zugelassene Version. |
| `TIP1-A_3152` | Der Hersteller **muss** eine Update-Komponente bereitstellen, mit der die Firmware **innerhalb des LANs** aktualisiert werden kann. |
| `TIP1-A_3153` | Mindestens eine von zwei Varianten: **Push** — eine Update-Komponente auf einem **Drittsystem innerhalb des LANs** spielt ein; **Pull** — die Terminals holen von einem Update-Server, der auf einem **Drittsystem innerhalb des LANs** liegen kann. Realisierung ausdrücklich **herstellerspezifisch**. |
| `TIP1-A_3188` | Nach dem Update bleiben **alle Konfigurationen erhalten** — Terminalname, IP-Adresse, Pairing-Informationen. |
| `TIP1-A_3170` | Downgrades nur im Rahmen des Firmware-Gruppen-Konzepts, mit Hinweis auf die Konsequenzen und Abbruchmöglichkeit. |

**Die Position „Drittsystem innerhalb des LANs" ist exakt die des Standort-Agenten
aus [ADR-0002](0002-betriebsmodell-und-systemtopologie.md).**

### Das Terminal schützt sich selbst

`TIP1-A_2976` (Einspielen nur nach erfolgreicher Prüfung von Integrität und
Authentizität), `TIP1-A_2977` (fehlerhafte Übertragung wird abgewiesen),
`TIP1-A_3245` (bei fehlerhafter Übertragung bleibt die installierte Firmware
unverändert), `TIP1-A_2978` (Übernahme erst nach vollständiger, korrekter
Speicherung), `TIP1-A_3182`/`TIP1-A_3185` (Sicherheitsanker auslese- und
schreibgeschützt im Gerät).

**Folge:** Ein fehlerhaftes oder manipuliertes Paket kann ein Terminal nicht
zerstören — es wird abgewiesen. Das Risiko liegt nicht im Einspielen, sondern im
Zeitpunkt und in der Nachbehandlung.

### Abgrenzung zum Konnektor

`[gemSpec_Kon] V5.25.0` Kapitel 4.3.10: Der Konnektor besitzt einen **KSR-Client**
zur Aktualisierung von Konnektor **und Kartenterminals**. Der
Kartenterminaldienst kennt dafür den Zustand **„aktualisierend"**
(`KSR/UPDATE/START` … `KSR/UPDATE/END`, `Target=KT`).

Die Abgrenzung ist nach Feststellung des POs praktisch gelöst:
> **Ohne den Admin-Key des Kartenterminals kann der Konnektor nicht aktualisieren.**

Damit ist die Zuständigkeit steuerbar: Wer den Admin-Zugang hält, aktualisiert.
Für unser Produkt ist das ohnehin gegeben — es benötigt die Terminal-Anmeldung
bereits für PEviM (Rolle `SMC-B_Nutzer`, die laut `A_24075` mit der
Administrator-Rolle identisch sein darf).

> **Bleibt trotzdem verpflichtend:** Der PIN-Roboter **muss** den Zustand
> „aktualisierend" erkennen und in dieser Zeit **keine PIN-Verifikation
> versuchen** — sonst belastet er im ungünstigen Fall den Fehlversuchszähler der
> SMC-B. Das gilt unabhängig davon, wer das Update ausgelöst hat.

---

## Der Zusammenhang, der das Produkt zusammenhält

Ein Firmware-Update erfordert in aller Regel einen Neustart des Terminals. Damit
verliert die eingelegte SMC-B ihren verifizierten Zustand — **genau das Problem,
für das der PIN-Roboter gebaut wird.**

Daraus folgt ein Ablauf, den keine andere Komponente leisten kann:

```
Zeitfenster beginnt
  └─ Firmware einspielen        (Terminal aktualisiert, startet neu)
  └─ warten bis erreichbar
  └─ Firmware-Stand prüfen      (hat das Update funktioniert?)
  └─ PIN-Status prüfen          (GetPinStatus → VERIFIABLE?)
  └─ SMC-B wieder verifizieren  (PEviM)
  └─ Erfolg protokollieren      → nächstes Gerät
```

Der Konnektor kann Schritt 1 auslösen. Die Schritte danach kann er nicht — er
kennt weder Plan noch PIN. **Firmware-Rollout und PIN-Wiederherstellung gehören
fachlich zusammen**, und dass beides in einem Produkt liegt, ist kein
Funktionsanhäufung, sondern der eigentliche Grund, warum es funktioniert.

---

## Fachliches Konzept: Gruppen und Rollout-Pläne

### Gerätegruppen
- Ein Terminal gehört zu genau einer Gruppe (z. B. „Abteilung A", „Notaufnahme",
  „Apotheke Nachtdienst").
- Gruppen liegen innerhalb einer Einrichtung und damit innerhalb eines Mandanten.
- Jede Gruppe hat einen eigenen Rollout-Plan.

### Rollout-Plan
- **Freigegebene Zeitfenster** als wiederkehrende Regel: Wochentage plus
  Uhrzeitbereich (Beispiel: Mi und Fr, 01:00–04:00).
- **Zeitzone und Sommerzeit** sind ausdrücklich zu berücksichtigen — ein Fenster,
  das durch die Zeitumstellung wandert oder doppelt auftritt, ist eine reale
  Fehlerquelle.
- **Wiederaufnahme:** Nicht abgearbeitete Geräte bleiben in der Warteschlange und
  kommen im nächsten freigegebenen Fenster an die Reihe. Ein Rollout ist ein
  Vorgang über mehrere Fenster hinweg, kein einmaliger Lauf.
- **Kein Abbruch mitten im Gerät:** Ein bereits gestartetes Update wird zu Ende
  geführt, auch wenn das Fenster ausläuft. Was das Fenster begrenzt, ist der
  **Start** weiterer Geräte, nicht der Abschluss des laufenden. Ein abgebrochenes
  Firmware-Update ist der einzige Weg, ein Terminal doch noch zu beschädigen.
- **Restzeitprüfung:** Vor dem Start eines weiteren Geräts wird geprüft, ob die
  erwartete Dauer noch in das Fenster passt. Die Dauer wird aus tatsächlichen
  Messwerten gelernt, nicht geschätzt.
- **Not-Aus:** Ein laufender Rollout muss sich anhalten lassen. Bereits laufende
  Geräteupdates laufen aus, weitere starten nicht.

### Sicherheitsnetze
- **Kanarienvogel-Prinzip:** Das erste Gerät einer Gruppe wird einzeln
  aktualisiert und vollständig verifiziert, bevor die übrigen folgen. Scheitert
  es, stoppt der Rollout automatisch. Das begrenzt einen fehlerhaften
  Firmware-Stand auf ein Gerät statt auf 24.
- **Nach jedem Gerät:** Erreichbarkeit, Firmware-Stand und PIN-Status prüfen.
  Weicht etwas ab, wird angehalten und alarmiert — nicht weitergemacht.
- **Vollständiges Protokoll:** wer welche Firmware wann für welche Gruppe
  freigegeben hat, welches Gerät wann von welchem Stand auf welchen wechselte,
  mit Ergebnis.

---

## Umsetzung in Stufen

| Stufe | Inhalt | Einordnung |
|---|---|---|
| **1 — Kennen** | Firmware-Version je Terminal auslesen, Flottenübersicht, abweichende und veraltete Stände melden, Ableitung der PEviM-Fähigkeit | **erste Version** — wird ohnehin gebraucht |
| **2 — Respektieren** | Zustand „aktualisierend" erkennen, in dieser Zeit nicht verifizieren; Firmware-Wechsel protokollieren | **erste Version, verpflichtend** — betrifft die Kernfunktion |
| **3 — Ausrollen** | Firmware-Upload durch den Administrator, Gruppen, Rollout-Pläne, gesteuerte Ausführung über den Standort-Agenten, anschließende PIN-Wiederherstellung | **eigene Ausbaustufe**, unmittelbar nach dem Kernprodukt |

**Zur Reihenfolge:** Stufe 3 setzt auf der Geräteanbindung, der Gruppenstruktur
und dem Zeitplanwerk auf, die im Kernprodukt entstehen. Sie danach zu bauen ist
deutlich billiger als parallel — nicht, weil sie unwichtig wäre, sondern weil
sie sonst dieselben Bausteine ein zweites Mal bräuchte.

Vorzubereiten ist sie aber von Anfang an: **Gerätegruppen und Zeitfenster gehören
ins Datenmodell der ersten Version.** Sie werden dort ohnehin gebraucht — auch
die PIN-Automatik profitiert von abteilungsweisen Regeln.

---

## Risiko und Testpflicht

Der PO hat das Risiko ausdrücklich anerkannt und die Konsequenz gezogen:
**„Das ist ein Risiko, darum muss alles gut getestet werden."**

Daraus folgt verbindlich:

1. **Kein Firmware-Rollout ohne vorherigen vollständigen Durchlauf am
   Testaufbau** — je Hersteller und Modell, das unterstützt werden soll.
2. **Getestet wird auch der Fehlerfall**, nicht nur der Erfolgsfall: Terminal
   kommt nach dem Update nicht zurück, Netzabbruch während der Übertragung,
   Fenster läuft während des Updates aus, SMC-B lässt sich danach nicht
   verifizieren.
3. **Unterstützte Modelle und Firmware-Stände werden ausdrücklich benannt.**
   Was nicht getestet ist, wird nicht unterstützt — und der Roboter verweigert
   den Rollout auf unbekannten Modellen, statt es zu versuchen.
4. **Messwerte statt Annahmen:** Update-Dauer je Modell wird gemessen und in die
   Fensterplanung übernommen.

---

## Konsequenzen

- Der PIN-Roboter hält **Terminal-Administrationszugänge** vor — ein zweites
  Geheimnis neben der SMC-B-PIN, mit denselben Schutzanforderungen (keine
  Klartext-Persistenz, nie in Logs, Backups oder Metriken, nur beim
  Standort-Agenten).
- **Gerätegruppen und Zeitfenster** gehören ins Datenmodell der ersten Version.
- Die Firmware-Ablage braucht **Prüfsummen und Herkunftsnachweis**: Welcher
  Administrator hat welche Datei wann hochgeladen?
- Der Standort-Agent wird zur **Update-Komponente im Sinne von `TIP1-A_3153`**.
  Der herstellerspezifische Einspielmechanismus wird je Hersteller gekapselt, so
  dass weitere Hersteller ergänzt werden können, ohne die Ablaufsteuerung
  anzufassen.

## Entschiedene Detailfragen (PO, 06.08.2026)

### 1. Parallelität — konfigurierbar durch den Administrator

Mehrere Terminals einer Gruppe dürfen gleichzeitig aktualisiert werden. Die
**Anzahl gleichzeitiger Updates ist je Gruppe vom Administrator einstellbar**.

Umsetzungsvorgaben:
- **Vorgabewert 1** (sequenziell). Wer erhöht, tut das bewusst.
- Der **Kanarienvogel bleibt immer sequenziell**: Das erste Gerät einer Gruppe
  wird einzeln aktualisiert und verifiziert, unabhängig von der eingestellten
  Parallelität. Erst danach greift sie.
- Die Oberfläche muss die Wirkung erklären, nicht nur eine Zahl abfragen: höhere
  Werte verkürzen den Rollout, erhöhen aber die Last auf Netz und Konnektor und
  vergrößern den Schaden eines fehlerhaften Firmware-Stands.
- Eine **technische Obergrenze** wird nach Messung am Testaufbau festgelegt.

### 2. Nicht erreichbare Geräte — überspringen, erneut versuchen, dann melden

Ist ein Terminal im Zeitfenster nicht erreichbar (abgeschaltet, in Benutzung),
wird es **übersprungen und im nächsten freigegebenen Fenster erneut versucht**.
Nach einer **konfigurierbaren Anzahl erfolgloser Versuche** wird es gemeldet und
aus dem laufenden Rollout genommen, bis ein Administrator entscheidet.

Begründung für die Meldung: Ein Gerät, das dauerhaft nicht erreichbar ist, ist
selbst ein Befund — es ist entweder defekt, ausgemustert oder falsch erfasst.
Ein stiller Endlosversuch würde das verdecken.

> *Auslegungshinweis:* Die Antwort des POs lautete „ja" auf eine
> Entweder-oder-Frage. Ausgelegt als **beides** — erneut versuchen **und** nach
> n Versuchen melden —, da nur diese Kombination fachlich vollständig ist. Falls
> anders gemeint, ist dies zu korrigieren.

### 3. Downgrades — nicht unterstützt

Der PIN-Roboter **rollt keine Firmware-Downgrades aus**, obwohl `TIP1-A_3170`
sie eingeschränkt erlaubt.

Konsequenzen:
- Der Roboter **prüft vor dem Rollout die Versionsrichtung** und verweigert den
  Vorgang, wenn die hochgeladene Firmware älter ist als der installierte Stand.
  Das ist eine aktive Schutzfunktion, keine bloße Auslassung.
- Ein Downgrade bleibt ein **manueller Vorgang** über die Werkzeuge des
  Herstellers, mit dem dort vorgesehenen Hinweis auf die Konsequenzen.
- Der Rollout hat damit **kein Firmware-Rollback**. Umso wichtiger sind das
  Kanarienvogel-Prinzip und die Testpflicht: Ein fehlerhafter Stand lässt sich
  nicht durch Zurückrollen heilen, sondern nur dadurch, dass er gar nicht erst
  auf die ganze Flotte gelangt.
