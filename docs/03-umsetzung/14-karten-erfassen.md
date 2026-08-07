# Umsetzung: Karten erfassen

**Anforderung:** FA-GER-040 · **Regel:** `A_24080` — PEviM ist ausschließlich
für die SMC-B zulässig

---

## Warum eGK und HBA überhaupt auftauchen

Sie stecken in der Praxis **regelmäßig mit im Terminal**: die eGK während der
Behandlung, der HBA für die Signatur. Der Konnektor meldet sie, und der Roboter
sieht sie bei jeder Abfrage.

Sie zu verschweigen wäre bequem gewesen — sie sind für die Kernfunktion
irrelevant. Aber:

1. **Der Betreiber soll sehen, was in seinem Gerät steckt.** Eine Liste, die nur
   die SMC-B zeigt, erweckt den Eindruck, das Terminal sei sonst leer.
2. **Er soll sehen, dass der Roboter sie in Ruhe lässt.** Das ist eine Zusage,
   und eine Zusage, die man nicht prüfen kann, beruhigt niemanden.

## Wie die Kennzeichnung aussieht

| Kartentyp | Anzeige | Verwaltung |
|---|---|---|
| SMC-B | „SMC-B (Institutionsausweis)" | ✓ vom Roboter verwaltet |
| eGK | „eGK (Gesundheitskarte)" | nicht verwaltet |
| HBA | „HBA (Heilberufsausweis)" | nicht verwaltet |

Die Kürzel werden **ausgeschrieben**. „EGK" sagt einer Praxiskraft nichts,
„eGK (Gesundheitskarte)" schon — und die Oberfläche richtet sich an Menschen
ohne IT-Ausbildung (CLAUDE.md 1).

Unter der Liste steht, **warum**:

> **eGK und HBA werden nicht verwaltet.** Die automatische PIN-Eingabe über die
> Managementschnittstelle ist ausschließlich für die SMC-B zulässig
> (gematik A_24080). Der Roboter erfasst diese Karten, zeigt sie an — und fasst
> sie nie an.

Eine Regel durchzusetzen genügt nicht; wer nicht weiß, warum eine Karte
ausgelassen wird, ruft beim Dienstleister an.

---

## Das Ablaufdatum

Das Zertifikat einer SMC-B läuft ab. Danach nimmt die Karte keine Verifikation
mehr an, und der Austausch braucht **Vorlauf bei der Kartenherausgabe** — das
sind Wochen, keine Tage.

Deshalb drei Zustände statt eines Datums:

| Zustand | Anzeige |
|---|---|
| mehr als 90 Tage | schlichtes Datum |
| weniger als 90 Tage | „läuft bald ab", gelb |
| überschritten | „abgelaufen", rot |

**Ohne gemeldetes Datum wird nicht geraten**: Dann steht „nicht gemeldet". Ein
erfundenes Datum wäre schlimmer als keines — es würde Sicherheit vortäuschen.

Warum 90 Tage: Drei Monate sind knapp für eine Kartenbestellung, aber machbar.
Ein kürzeres Fenster käme zu spät, ein längeres würde die Warnung entwerten,
weil sie ständig zu sehen wäre.

---

## Der Simulator musste erweitert werden

Er meldete nur SMC-B. Ein Simulator, der den zu prüfenden Fall gar nicht kennt,
kann ihn nicht prüfen — nach [ADR-0007](../adr/0007-simulierte-endpunkte-und-teststrategie.md)
Regel 1 gehört jeder Fall nachgestellt, der in der Wirklichkeit vorkommt.

Jetzt steckt in `KT-EMPFANG-01` neben der SMC-B auch eine eGK und ein HBA, jede
mit eigenem Ablaufdatum. Die SMC-B dort läuft **2027-06-30** ab — nah genug, dass
die Anzeige den Fall zeigt, weit genug, dass sie nicht dauernd rot ist.

---

## Nachweise

**Modultests** (5 neue in `ErmittlungTest`): alle Karten erfasst, nur SMC-B als
verwaltet eingestuft, ablaufende und abgelaufene Zertifikate erkannt,
verständliche Typbezeichnungen, keine Dubletten bei erneuter Meldung.

**Systemnachweise:** Der Agent meldet die Karten, eGK und HBA werden erfasst,
**keine Nicht-SMC-B gilt als verwaltet**, das Ablaufdatum kommt an, und der
Roboter erwähnt eGK und HBA in seinem Protokoll **null Mal** — er fasst sie
nicht an. Dazu: Die Oberfläche nennt `A_24080` und schreibt die Kartentypen aus.
