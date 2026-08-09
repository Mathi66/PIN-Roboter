# 19 — Das Prüfintervall je Gerätegruppe wirkt (FA-UEB-010)

**Stand:** umgesetzt und gemessen (08.08.2026)

---

## Der Befund

Der Auftrag lautete, die Wirkung des gruppenweisen Prüfintervalls
nachzuweisen. Beim Nachsehen stellte sich heraus: Es gab keine Wirkung
nachzuweisen.

Der Agent nahm einen **globalen Wert** aus seiner Umgebung:

```java
int intervall = Integer.parseInt(env("PRUEFINTERVALL_SEKUNDEN", "10"));
...
Thread.sleep(intervallSekunden * 1000L);
```

Die Gerätegruppen trugen seit Migration 1 eine Spalte
`pruefintervall_sekunden`, die Oberfläche ließ sie pflegen, und die
Systemnachweise belegten, dass sie gespeichert wird. **Nur erreichte sie den
Agenten nie.** Die Einstellung war folgenlos.

Im Umsetzungsstand stand FA-UEB-010 auf „erfüllt". Das war falsch — belegt war
die Gruppenpflege, nicht die Überwachung.

> Das ist die Sorte Lücke, die eine Statuszeile nicht zeigt: Zwei Teile sind
> je für sich fertig und geprüft, und niemand hat geprüft, ob sie verbunden
> sind.

## Die Umsetzung

**Zentrale:** `/api/agent/intervalle` liefert je Terminal-Kennung das Intervall
seiner Gruppe.

**Agent:** Ruft die Liste in jedem Grundtakt ab und führt je Terminal einen
eigenen Zeitpunkt der letzten Prüfung. Die Schleife wacht im Grundtakt auf und
prüft dann nur die Geräte, deren Zeit gekommen ist.

Zwei Festlegungen dabei:

- **Der Grundtakt ist die Untergrenze.** Feiner als er kann kein Gerät geprüft
  werden — er ist der Takt, in dem die Schleife überhaupt aufwacht.
- **Beim ersten Durchgang wird sofort geprüft.** Ein neu erkanntes Gerät soll
  nicht bis zum Ablauf seines Intervalls unbekannt bleiben; bei fünf Minuten
  wären das fünf Minuten ohne Zustand.

Bleibt die Zentrale stumm, gilt weiter, was zuletzt bekannt war — ein Ausfall
der Zentrale hält die Überwachung nicht an (FA-AGT-030).

## Gemessen, nicht behauptet

Ob ein Agent ein Intervall einhält, lässt sich nicht am Quelltext ablesen,
sondern nur daran, **wie oft er tatsächlich fragt**. Der Simulator zählt
deshalb `GetPinStatus` je Kartenhandle, und der Nachweis liest die Zählung aus:

```
KT-ARCHIV-01     -> Gruppe mit  10 s
KT-BEHANDLUNG-02 -> Gruppe mit 120 s

In 40 s: KT-ARCHIV-01 = 4 Abfragen,  KT-BEHANDLUNG-02 = 0 Abfragen
```

Vier gegen null in vierzig Sekunden. Bei 5.000 Terminals ist genau das der
Hebel, mit dem die Last steuerbar bleibt.

## Was der Umbau nebenbei umgeworfen hat

Fünf andere Nachweise fielen sofort um — alle mit „gemessen: 0". Kein Zufall:
Die Vorgabegruppe steht auf **60 Sekunden**, und die Nachweise warteten 20 bis
26 Sekunden auf eine Verifikation. Vorher galt der Grundtakt von 4 Sekunden für
alles, jetzt der Gruppentakt.

Das ist kein Fehler im Produkt und auch keiner in den Nachweisen, sondern die
sichtbar gewordene Folge einer richtigen Änderung. Behoben, indem der
Nachweislauf die Vorgabegruppe auf **10 Sekunden** setzt — das kleinste
Intervall, das die Datenbank zulässt (`CHECK ... >= 10`, als Lastschutz
gewollt). Die Nachweise takten damit so schnell, wie das Produkt es überhaupt
erlaubt, und die Wartezeiten wurden entsprechend verlängert.

## Nachgewiesen

- Der Agent erfährt die Intervalle von der Zentrale
- Das schnelle Gerät wird im 10-Sekunden-Takt abgefragt (4 in 40 s)
- Das langsame wird geschont (0 in 40 s)
- Die Gruppeneinstellung wirkt sich messbar aus

**249 Modultests, 228 Systemnachweise** — alle grün.

---

## Nachtrag 09.08.2026 — eine Gruppe je Einrichtung

Beim Anlegen von Gruppen für neun Praxen fiel auf: Die Oberfläche reichte die
Einrichtung **hart verdrahtet** als `"Praxis"` durch.

```java
String fehler = gruppen.anlegen(bez, "Praxis", intervall);
```

Damit hingen alle Gruppen an derselben Einrichtung — unabhängig davon, wofür
sie gedacht waren. FA-GER-050 verlangt aber ausdrücklich:

> Eine Gruppe liegt innerhalb genau einer Einrichtung.

Bei einer Einzelpraxis fällt das nicht auf. Bei neun Praxen an einer Instanz —
dem Fall, für den die Mandantenfähigkeit gebaut ist — ist es der Unterschied
zwischen brauchbar und nutzlos: Die Gruppe trägt das Prüfintervall, und das
soll je Standort verschieden sein können.

Die Maske bietet jetzt die Einrichtungen des eigenen Geltungsbereichs zur
Auswahl, die Übersicht nennt sie je Gruppe, und die Gruppe trägt die Kennung
der Organisationseinheit — womit die Mandantengrenze auch hier greift.

Drei zusätzliche Systemnachweise.

---

## Nachtrag 09.08.2026 — der Agent kennt seine Einrichtung

Der Nachtrag oben behob die Verdrahtung in der Oberfläche. Eine Ebene tiefer
saß dieselbe Sorte Fehler noch einmal, und dort wog sie schwerer.

### Was falsch war

Der Standort-Agent meldete seine Geräte hart unter `"Praxis"`:

```java
gruppen.geraetSicherstellen(id, "Praxis", name);
protokoll.anfuegen("Praxis", id, Ereignisart.GERAET_ERKANNT, …);
```

Folge: Egal an welchem Standort ein Agent lief — seine Terminals, Alarme und
Protokolleinträge landeten unter demselben Namen. In der Einzelpraxis richtig,
im Klinikverbund falsch. Konkret hieß das:

- Alle Geräte fielen in **eine** Vorgabegruppe. Die neun eben angelegten
  Praxis-Gruppen blieben leer, und das Prüfintervall je Standort (FA-UEB-010)
  hatte nichts zu steuern.
- **Die Mandantengrenze griff für Geräte nicht.** Ein Administrator der
  Kardiologie hätte die Terminals der Zahnarztpraxis gesehen — sie trugen ja
  seine Einrichtung. Genau der Fehler, vor dem CLAUDE.md warnt: „die häufigste
  schwere Sicherheitslücke in mandantenfähigen Anwendungen".

### Woher die Einrichtung jetzt kommt

**Aus der Registrierung, nicht aus der Meldung.** Das ist die entscheidende
Richtung. Ein Agent *könnte* seine Einrichtung selbst mitschicken — dann würde
ein übernommener oder nachgebauter Agent Geräte in einer fremden Einrichtung
unterschieben. Der Administrator legt sie stattdessen beim Erzeugen des
Registrierungscodes fest; das ist der einzige Zeitpunkt, an dem ein Mensch
weiß, wo das Gerät steht.

```java
private String einrichtungDesAgenten(Context c) {
    var a = (Agentenverwaltung.Agent) c.attribute("agent");
    return a == null || a.einrichtung() == null || a.einrichtung().isBlank()
            ? "Praxis" : a.einrichtung();
}
```

Damit sie nicht wieder an einer Stelle vergessen wird, hat `pruefe()` — der
gemeinsame Weg aller Geräte­alarme — die Einrichtung als **Pflichtparameter**
bekommen. Ein vergessener Aufruf übersetzt jetzt nicht mehr; das erledigt der
Compiler statt der Sorgfalt.

Zwei Stellen bleiben bewusst instanzweit und tragen einen Kommentar:

| Stelle | Warum |
|---|---|
| Speicherplatz-Alarm | betrifft die Instanz, nicht einen Standort |
| abgelehnte Registrierung | es gibt keine belegte Einrichtung — und ein Angreifer darf den Ablageort nicht über sein Geheimnis wählen |

### Die Oberfläche dazu

Das Registrierungsgeheimnis ließ sich bisher **nur über die Kommandozeile**
erzeugen; in der Oberfläche fehlte die Maske ganz. Ein Betreiber ohne
IT-Personal konnte damit keinen zweiten Standort anbinden — für ein Produkt,
das ausdrücklich für Praxen ohne DevOps gebaut wird, ein Auslieferungsmangel.

Die Agententafel trägt jetzt ein Formular mit Bezeichnung und
Einrichtungsauswahl. Die Auswahl kommt aus dem Geltungsbereich des Aufrufers —
ein Standortadministrator sieht nur seine eigenen Einrichtungen.

### Nachweise

Vier zusätzliche Systemnachweise, die den ganzen Weg gehen: Geheimnis mit
Einrichtung → Token → Gerätemeldung → Ablage. Der wichtigste davon prüft die
Angriffsrichtung: Die Meldung behauptet ausdrücklich eine **fremde**
Einrichtung, und der Nachweis verlangt, dass davon nichts ankommt.

### Was der Umbau freigelegt hat

Der Nachweis fiel beim ersten Lauf um — und zwar zu Recht. Migration 6 legt die
Vorgabegruppe **nur für die Einrichtung `"Praxis"`** an. Meldete ein Agent an
einem anderen Standort ein Gerät, fand `geraetSicherstellen` keine Gruppe:

```java
var vorgabe = vorgabegruppe(einrichtung);
if (vorgabe.isEmpty()) return false;      // und niemand sah hin
```

Folge: Das Terminal stand im Fachprotokoll, aber **in keiner Gerätetabelle** —
nicht in der Geräteverwaltung sichtbar, keiner Gruppe zugeordnet, also ohne
Prüfintervall. Ein lautloser Ausfall genau der Kernfunktion.

Solange alle Agenten „Praxis" meldeten, konnte das nicht auffallen. Der Umbau
hat den Fehler nicht verursacht, sondern sichtbar gemacht.

Zwei Änderungen:

- Die **Vorgabegruppe entsteht beim ersten Bedarf** (`vorgabegruppeAnlegen`),
  idempotent über `ON CONFLICT DO NOTHING`. Melden sich zwei Agenten derselben
  Einrichtung gleichzeitig, endet der Wettlauf am eindeutigen Index
  `geraetegruppe_vorgabe_idx`, und der Verlierer liest die inzwischen
  vorhandene Gruppe.
- Der stille `return false` **meldet sich**. Ein Gerät, das nicht zugeordnet
  werden kann, darf kein wortloser Ausfall bleiben.

### Ein zweiter Fund in den Nachweisen selbst

Zwei bestehende Nachweise fielen um, sobald es mehr als eine Einrichtung gab:

```bash
VORGABE=$(sql "SELECT id FROM geraetegruppe WHERE ist_vorgabe")
```

Sie nahmen an, es gebe **genau eine** Vorgabegruppe im System. Das war nie
richtig — die Mandantenfähigkeit sieht eine je Einrichtung vor —, es hielt nur
zufällig, solange alles „Praxis" hieß. Die Abfrage lieferte plötzlich zwei
Zeilen, und der Vergleich scheiterte an einer mehrzeiligen Variablen statt an
einem Fehler im Produkt. Beide fragen jetzt nach der Vorgabegruppe **des
betreffenden Geräts**.
