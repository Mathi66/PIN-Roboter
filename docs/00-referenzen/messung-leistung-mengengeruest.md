# Messung: Leistung am vollen Mengengerüst

- **Datum:** 07.08.2026
- **Zweck:** Nachweis der Zielwerte aus `NFA-LEI-010` — bisher waren sie
  behauptet, nicht gemessen
- **Grundsatz:** Zielwerte werden gemessen, nicht geschätzt (CLAUDE.md 4.7)

---

## Messbedingungen

| | |
|---|---|
| Hardware | Apple M2 Pro, 16 GB RAM |
| Docker | 29.4.3, 12 CPUs, 8 GB für Container |
| Datenbank | PostgreSQL 17.10 im Container, Standardkonfiguration |
| Zentrale | Java 21 (Temurin JRE), Standard-Speichereinstellungen |
| Netz | Container zu Container über eine Docker-Brücke, kein echtes LAN |
| Datenbestand | **5.000 Kartenterminals** an 100 Konnektoren; **5.000.004 Protokolleinträge** (2.161 MB) |
| Messung | `curl`, Gesamtzeit der HTTP-Antwort, 10 Durchläufe je Fall (Protokoll: 5) |

> **Diese Messung gilt nicht für die Referenzhardware.** Die Zielplattform ist
> eine Linux-Appliance der Klasse Raspberry Pi 5 oder NXP i.MX8 QuadMax
> (ADR-0006) — deutlich langsamer als ein M2 Pro. Die Werte unten zeigen, dass
> die **Abfragen und Indizes tragen**; sie ersetzen keine Messung auf der
> Zielhardware. Dafür ist der Lasterzeuger in die Verwaltungsoberfläche
> eingebaut: Der Betreiber kann dieselbe Messung auf seinem Gerät wiederholen.

Der Bestand wird über `Lasterzeuger` erzeugt. Alles ist als Demo gekennzeichnet
und rückstandsfrei entfernbar; echte Bestandsdaten bleiben unberührt. Das
Anlegen von 5.000 Terminals dauerte **257 ms**.

---

## Ergebnis 1: Geräteliste bei 5.000 Terminals

Zielwert `NFA-LEI-010`: **Seitenaufbau < 1 s**.

| Fall | Mittel | min | max |
|---|---:|---:|---:|
| Bestandsliste, erste Seite | **14 ms** | 8 | 46 |
| Seite 100 (tief geblättert) | 11 ms | 11 | 13 |
| Suche über Bezeichnung | 12 ms | 12 | 13 |
| Suche über Standort | 11 ms | 11 | 13 |
| Sortierung nach Modell, absteigend | 11 ms | 11 | 12 |
| Sortierung nach Firmware | 10 ms | 9 | 11 |
| Filter Modell | 7 ms | 7 | 9 |
| Filter Status | 7 ms | 7 | 8 |
| Filter kombiniert + Sortierung | 8 ms | 8 | 10 |
| Übersicht (Startseite) | 2 ms | 2 | 5 |

**Zielwert erfüllt**, mit Faktor 70 bis 140 Abstand. Selbst wenn die
Referenzhardware zehnmal langsamer ist, bleibt reichlich Reserve.

---

## Ergebnis 2: Protokoll bei 5 Millionen Einträgen

Zielwert `NFA-LEI-010`: **gefilterte Protokollabfrage < 2 s**.

| Fall | Mittel | min | max |
|---|---:|---:|---:|
| Protokollseite, erste Seite | **110 ms** | 94 | 169 |
| Teilansicht, erste Seite | 93 ms | 92 | 95 |
| Gefiltert nach Ereignisart | **141 ms** | 135 | 154 |
| Gefiltert nach Ergebnis | 127 ms | 126 | 128 |
| Gefiltert nach Gerät | 6 ms | 4 | 14 |
| Kombiniert Gerät + Ereignisart | 3 ms | 3 | 4 |

**Zielwert erfüllt**, mit Faktor 14 Abstand im ungünstigsten Fall.

Auffällig: Der Filter nach Gerät ist **zwanzigmal schneller** als der nach
Ereignisart. Der Grund steht im Abfrageplan — für `geraet` greift
`fachprotokoll_geraet_idx`, für eine Ereignisart, die 20 % aller Zeilen trifft,
entscheidet sich PostgreSQL für einen parallelen Durchlauf:

```
Parallel Seq Scan on fachprotokoll  (cost=0.00..125960.51 rows=519768)
Execution Time: 142.025 ms
```

Das ist **richtig so** und kein Mangel: Bei einer Million Treffern ist ein
Index-Durchlauf langsamer als ein sequenzieller. Ein zusätzlicher Index würde
hier nichts verbessern und das Schreiben verlangsamen.

---

## Befund: Sehr tiefes Blättern überschreitet den Zielwert

| Seite | angezeigter Eintrag | Antwortzeit |
|---:|---:|---:|
| 1.000 | 50.000 | 163 ms |
| 10.000 | 500.000 | 258 ms |
| 50.000 | 2.500.000 | 683 ms |
| **100.000** | **5.000.000** | **1.310 ms** |

**Ab etwa Seite 75.000 (Eintrag 3,75 Millionen) wird der Zielwert von 1 Sekunde
überschritten.**

Ursache, gemessen statt vermutet:

```
Limit  (cost=450236.15..450236.24 rows=1)
  ->  Incremental Sort  (actual rows=4917504 loops=1)
```

`OFFSET` muss alle übersprungenen Zeilen tatsächlich lesen und sortieren — bei
Seite 100.000 sind das 4,9 Millionen Zeilen für 50 angezeigte.

### Bewertung

**Im Betrieb ist der Fall nicht erreichbar** — über die Vor/Zurück-Knöpfe müsste
jemand 100.000-mal klicken. Er entsteht nur, wenn die Seitennummer in der URL
von Hand gesetzt wird.

**Trotzdem ist es eine echte Grenze**, und sie wird hier festgehalten statt
verschwiegen. Wer weit zurück muss, filtert (nach Gerät: 6 ms) oder exportiert.

### Was dagegen hilft, falls es je nötig wird

**Schlüsselsatz-Blättern** (keyset pagination): Statt `OFFSET 5000000` merkt sich
die Blätterleiste den letzten gesehenen Zeitpunkt und fragt
`WHERE (zeitpunkt, id) < (…)`. Das ist auf jeder Seite gleich schnell, verhindert
aber den Sprung zu einer beliebigen Seitennummer.

Der Umbau lohnt erst, wenn jemand ihn braucht. **Als offener Punkt vermerkt**,
nicht als stillschweigende Schuld.

---

## Was diese Messung nicht abdeckt

Ehrlich benannt, damit niemand mehr hineinliest, als drinsteht:

1. **Referenzhardware.** Gemessen auf einem M2 Pro, Ziel ist ein Raspberry Pi 5
   oder i.MX8. Der Lasterzeuger steht in der Oberfläche bereit, damit die
   Messung dort wiederholt werden kann — **das ist noch nicht geschehen.**
2. **1.000 Benutzer im Bestand.** `NFA-LEI-010` nennt sie neben den 5.000
   Terminals. Die Benutzerliste wurde **nicht** unter dieser Last gemessen.
3. **`NFA-LEI-020` — Überwachungsdurchsatz.** 5.000 Terminals tatsächlich im
   60-Sekunden-Takt zu prüfen, mit weniger als 1 % verspäteter Prüfungen über
   24 Stunden, ist eine eigene Messung über einen ganzen Tag. Sie steht aus.
4. **Echtes LAN.** Gemessen wurde zwischen Containern auf einem Host. Ein
   Praxisnetz bringt Latenz dazu, die hier fehlt.
5. **Gleichzeitige Benutzer.** Alle Messungen sind Einzelabrufe.

---

## Reproduzieren

```bash
# Zentrale starten, anmelden, dann in der Oberfläche:
#   Verwaltung → Leistungsmessung → „5000 Testgeräte anlegen“
# Danach die Messung mit curl, oder über die Systemnachweise:
pruefungen/systemnachweise.sh
```

Die Systemnachweise enthalten seit dem 07.08.2026 einen eigenen Abschnitt, der
1.000 Terminals anlegt und die erste Seite gegen den Zielwert misst. Die volle
Messung mit 5.000 Terminals und 5 Millionen Protokolleinträgen bleibt manuell —
sie dauert mehrere Minuten und würde jeden Bauvorgang unnötig verlängern.
