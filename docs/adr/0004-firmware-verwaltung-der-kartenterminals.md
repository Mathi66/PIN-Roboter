# ADR-0004: Firmware-Verwaltung der Kartenterminals

- **Status:** Vorschlag — Entscheidung des Product Owners ausstehend
- **Datum:** 06.08.2026
- **Betrifft:** Funktionsumfang, Architektur, Betrieb, Haftung

---

## Kontext

Frage des POs vom 06.08.2026: Akzeptieren Kartenterminals Firmware-Updates über
die Web-/Managementschnittstelle? Falls ja, könnte der PIN-Roboter das
Firmware-Management übernehmen.

Der Bedarf ist offensichtlich: Bei bis zu 5.000 Terminals ist Firmware-Pflege von
Hand nicht leistbar. Hinzu kommt eine unmittelbare Abhängigkeit — **PEviM
(unser Kernfeature) und der VPN-Client (ADR-0003) sind an Firmware-Stände
gebunden.** Ohne Kenntnis der Firmware wissen wir nicht, was ein Gerät kann.

---

## Faktenlage (geprüft am 06.08.2026, `[gemSpec_KT] V3.17.1`)

### Firmware-Updates aus dem LAN sind ausdrücklich vorgesehen

| ID | Inhalt (sinngemäß) |
|---|---|
| `TIP1-A_2965` | Das Terminal **muss** eine sichere Update-Möglichkeit der Firmware besitzen — einschließlich Downgrade auf eine über das Firmware-Gruppen-Konzept zugelassene Version. |
| `TIP1-A_3152` | Der Hersteller **muss** eine Update-Komponente bereitstellen, mit der die Firmware **innerhalb des LANs** aktualisiert werden kann. |
| `TIP1-A_3153` | Der Hersteller **muss** mindestens eine von zwei Varianten umsetzen: **Push** — eine Update-Komponente auf einem **Drittsystem innerhalb des LANs** spielt Updates ein; **Pull** — die Terminals holen sich Updates von einem Update-Server, der auf einem **Drittsystem innerhalb des LANs** liegen kann. |
| — | Realisierung und Mechanismus sind ausdrücklich **herstellerspezifisch**. Ergänzend unterstützt das Terminal die Update-Funktionen der SICCT-Spezifikation, wodurch eine ansteuernde Komponente das Terminal aktualisieren kann (z. B. der KSR-Dienst der TI). |
| `TIP1-A_3188` | Nach einem Update **müssen** alle Konfigurationen erhalten bleiben — Terminalname, IP-Adresse, Pairing-Informationen. |
| `TIP1-A_6481` | Der Hersteller **muss** jede zugelassene Firmware-Version umgehend als Update-Paket im Konfigurationsdienst (KSR) ablegen. |

**Die Position „Drittsystem innerhalb des LANs" ist exakt die des Standort-Agenten
aus [ADR-0002](0002-betriebsmodell-und-systemtopologie.md).** Der Weg ist also
nicht nur möglich, sondern vorgesehen.

### Das Terminal schützt sich selbst

| ID | Inhalt (sinngemäß) |
|---|---|
| `TIP1-A_2976` | Einspielen erst **nach erfolgreicher Prüfung von Integrität und Authentizität** der Firmware. |
| `TIP1-A_2977` | Fehlerhafte oder nicht authentische Übertragung wird abgewiesen. |
| `TIP1-A_3245` | Bei fehlerhafter Übertragung **darf** die installierte Firmware **nicht** verändert werden. |
| `TIP1-A_2978` | Übernahme als aktive Firmware erst nach vollständiger, korrekter Speicherung. |
| `TIP1-A_3182`, `TIP1-A_3185` | Der Sicherheitsanker liegt auslese- und schreibgeschützt im Terminal. |

Das ist eine wichtige Entlastung: **Wir können ein Terminal nicht durch ein
fehlerhaftes oder manipuliertes Paket zerstören** — es weist es ab. Das Risiko
liegt nicht im Einspielen selbst, sondern im Drumherum (siehe unten).

### Downgrades sind reglementiert

`TIP1-A_3170`: Downgrades sind nur im Rahmen des **Firmware-Gruppen-Konzepts**
zulässig, und der Administrator muss vorher auf die Konsequenzen hingewiesen
werden und abbrechen können. Ein Rollback ist also **kein freier Versionswechsel**.

### Der Konnektor macht das bereits

`[gemSpec_Kon] V5.25.0`, Kapitel 4.3.10: Der Konnektor besitzt einen
**KSR-Client zur Software- und Konfigurationsaktualisierung für Konnektor
**und Kartenterminals**. Der Kartenterminaldienst kennt einen eigenen Zustand
**„aktualisierend"**, ausgelöst durch den Konnektor, begrenzt durch die Ereignisse
`KSR/UPDATE/START` und `KSR/UPDATE/END` mit `Target=KT`.

**Das ist der wichtigste Befund dieser Analyse** — und er betrifft das
Kernprodukt unabhängig von jeder Firmware-Funktion:

> Der PIN-Roboter **muss** den Zustand „aktualisierend" erkennen und in dieser
> Zeit **keine PIN-Verifikation versuchen**. Ein Verifikationsversuch gegen ein
> gerade aktualisierendes Terminal führt im besten Fall zu einem Fehler, im
> schlechteren zu einem Fehlversuch, der den Fehlversuchszähler der SMC-B
> belastet. Das ist eine harte Anforderung an die Kernfunktion, nicht an eine
> Zusatzfunktion.

---

## Bewertung

Die Frage zerfällt in zwei sehr unterschiedliche Teile:

### Teil 1: Firmware-Stände **kennen** — unkritisch, hoher Nutzen

Das Auslesen der Firmware-Version ist eine reine Leseoperation
(`TIP1-A_3939` fordert die Darstellung der Firmware-Gruppen-Version, `A_19423`
das Ablesen der Version interner Firmware).

Nutzen:
- **Wir brauchen es ohnehin.** Ob ein Terminal PEviM beherrscht, hängt am
  Firmware-Stand. Ohne diese Information kann der PIN-Roboter nicht einmal
  verlässlich sagen, warum ein Gerät nicht automatisierbar ist.
- Flottenübersicht über bis zu 5.000 Geräte: Wer ist veraltet, wer uneinheitlich?
  Das ist für Klinik-IT ein eigenständiger Wert.
- Voraussetzung für die VPN-Ausbaustufe (ADR-0003).

**Risiko: praktisch keines.**

### Teil 2: Firmware **einspielen** — machbar, aber mit erheblichen Folgen

Technisch zulässig und vorgesehen (`TIP1-A_3153`, Push-Verfahren). Aber:

1. **Herstellerspezifisch.** Es gibt kein einheitliches Verfahren. Jeder
   Hersteller bedeutet eine eigene Implementierung, eigene Tests, eigene
   Pflege — dauerhaft, bei jeder Firmware-Generation.
2. **Woher kommt die Firmware?** `TIP1-A_6481` legt sie im KSR ab — das ist ein
   TI-Dienst, den ein Drittsystem nicht ohne Weiteres erreicht. Der Bezug wäre
   also über die Hersteller zu klären, jeweils einzeln und rechtlich sauber
   (Weitergabe, Zulassungsstand, Haftung). **Das ist der eigentliche Engpass —
   kein technischer, sondern ein organisatorischer.**
3. **Zuständigkeitskonflikt mit dem Konnektor.** Der Konnektor rollt über seinen
   KSR-Client selbst Terminal-Updates aus. Zwei Instanzen, die unabhängig
   voneinander dasselbe Gerät aktualisieren, sind eine Quelle schwer
   diagnostizierbarer Störungen.
4. **Verantwortungsübernahme.** Wer Firmware ausrollt, haftet für die Folgen.
   Ein Terminal, das nach einem Update nicht mehr korrekt pairt, legt den
   TI-Zugang lahm — und wir haben es angefasst.
5. **Nur zugelassene Stände.** Das Firmware-Gruppen-Konzept muss abgebildet
   werden, sonst rollen wir Nicht-Zugelassenes aus.

---

## Empfehlung: in drei Stufen

| Stufe | Inhalt | Einordnung |
|---|---|---|
| **1 — Kennen** | Firmware-Version je Terminal auslesen, anzeigen, Flottenübersicht, Abweichungen und veraltete Stände melden. Ableitung: „Dieses Gerät unterstützt PEviM (nicht)." | **Erste Version.** Wird ohnehin gebraucht. |
| **2 — Respektieren** | Zustand „aktualisierend" erkennen; währenddessen keine Verifikationsversuche. Firmware-Wechsel im Protokoll festhalten, damit Störungen zeitlich zuzuordnen sind. | **Erste Version, verpflichtend.** Betrifft die Kernfunktion. |
| **3 — Einspielen** | Firmware-Verteilung über den Standort-Agenten im Push-Verfahren, herstellerweise nachgezogen, mit Wartungsfenster, Freigabe und Protokoll. | **Spätere Ausbaustufe**, erst nach Klärung von Bezugsweg, Haftung und Konnektor-Abgrenzung. |

Stufe 1 und 2 liefern den größten Teil des Nutzens bei nahezu keinem Risiko.
Stufe 3 ist ein eigenes Produktthema, kein Nebenprodukt.

---

## Offene Fragen an den PO

1. **Bezugsweg der Firmware:** Bestehen Herstellerkontakte, über die zugelassene
   Firmware-Pakete legal bezogen und weitergegeben werden dürfen? Ohne
   gesicherten Bezugsweg ist Stufe 3 nicht umsetzbar — das ist die entscheidende
   Frage, und sie ist organisatorisch, nicht technisch.
2. **Abgrenzung zum Konnektor:** Soll unser Produkt die Firmware-Hoheit
   übernehmen und der KSR-Weg des Konnektors dann abgeschaltet werden? Beides
   parallel zu betreiben ist nicht empfehlenswert.
3. **Haftung:** Ist die Übernahme der Verantwortung für ausgerollte Firmware
   gewollt — auch für den Fall, dass ein Update ein Terminal betriebsunfähig
   macht?
