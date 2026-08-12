# 16 — Organisationseinheiten verwalten (FA-ORG-020)

**Stand:** umgesetzt und am laufenden Stack nachgewiesen (07.08.2026)

---

## Warum das die heikelste Verwaltungsfunktion ist

Der Geltungsbereich eines Benutzers ergibt sich **aus dem Baum**: seine Einheit
und alles darunter. Wer den Baum ändern darf, ändert damit Geltungsbereiche —
auch seinen eigenen.

Ein Standortadministrator, der eine fremde Praxis unter seinen Standort hängen
könnte, hätte damit Zugriff auf deren Geräte, Protokolle und Konten. **Ohne
einen einzigen Fehler in der Mandantentrennung selbst** — er hätte sie schlicht
von innen erweitert.

Diese Funktion ist deshalb überwiegend gegen Angriffe gebaut, nicht gegen
Bedienfehler. Die Tests spiegeln das: Die Mehrzahl sind Angriffsversuche.

## Die vier Regeln

**1. Erst ab Standortebene.** Wer eine Praxis betreut, legt keine weiteren
Praxen an. `Rechte.darfOrganisationVerwalten()` — dieselbe Regel, die schon vor
dieser Umsetzung stand, jetzt an einer Stelle durchgesetzt, an der sie greift.

**2. Das Elternteil muss im eigenen Bereich liegen.** Sonst ließen sich
Geschwister oder Einheiten oberhalb anlegen — und damit der eigene Bereich
erweitern.

**3. Beim Umhängen müssen Einheit *und* Ziel im eigenen Bereich liegen.**

> Nur das Ziel zu prüfen erlaubt die **Übernahme** fremder Einheiten. Nur die
> Einheit zu prüfen erlaubt das **Wegschieben** aus dem Bereich. Beide Fälle
> sind einzeln nachgewiesen, weil man beim Lesen des Codes leicht meint, eine
> Prüfung genüge.

**4. Kein Zyklus.** Eine Einheit unter einen eigenen Nachfahren zu hängen
trennt den Teilbaum vom Baum ab. Er wäre danach für niemanden mehr sichtbar —
auch nicht für den Instanzbetreiber, denn dessen Sicht beginnt bei den Wurzeln.
Der Nachweis prüft nicht nur die Ablehnung, sondern dass der Baum danach
**vollständig** ist.

Dazu kommt die fachliche Hierarchie: Ein Standort gehört nicht unter eine
Einrichtung. Die Auswahlfelder bieten deshalb nur zulässige Ziele an — ein
Auswahlfeld voller Einträge, die beim Absenden abgelehnt werden, ist eine
Falle, keine Hilfe.

## Löschen: abgelehnt statt nachgefragt

Der Fremdschlüssel `organisation.eltern_id` steht auf `ON DELETE CASCADE`, und
Konnektoren, Terminals und Konten hängen ebenso daran. Ein Löschen risse also
den ganzen Teilbaum mit — in einer Praxis unter Zeitdruck ein Klick zu viel.

Gelöscht wird deshalb nur, was **leer** ist. Die Meldung sagt, was im Weg
steht:

> „Praxis Nord" lässt sich nicht löschen: Daran hängen noch 2 Konto/Konten,
> 7 Kartenterminal(s). Bitte diese zuerst verschieben oder entfernen.

Die oberste Einheit des eigenen Bereichs lässt sich nie löschen — sie ist der
Mandant selbst.

## Die Anzeige

Der Baum wird **eingerückt in einer Tabelle** dargestellt, nicht verschachtelt.
Bei hunderten Einrichtungen bleibt eine Tabelle durchsuchbar und
tastaturbedienbar; verschachtelte Listen werden es nicht. Die Einrückung ist
rein visuell — für Screenreader steht die Ebene im Text, sonst ginge die
Struktur dort verloren.

Wer die Struktur sehen, aber nicht ändern darf, bekommt **keinen fehlenden
Knopf, sondern eine Erklärung**:

> Sie sehen Ihre Struktur, können sie aber nicht ändern. Das Anlegen und
> Umhängen von Einheiten ist ab der Standortebene möglich — damit niemand
> seinen eigenen Zuständigkeitsbereich erweitern kann.

Das ist nachgewiesen: Der Praxisbenutzer bekommt kein Anlegeformular *und* die
Begründung.

## Protokollierung nach Wirkung, nicht nach Vorgang

| Ereignis | Stufe | Warum |
|---|---|---|
| `ORGANISATION_ANGELEGT` | Sicherheit | schafft einen neuen Geltungsbereich |
| `ORGANISATION_UMGEHAENGT` | **Sicherheit** | verschiebt, wer was sieht |
| `ORGANISATION_GELOESCHT` | Sicherheit | entzieht Zuständigkeiten |
| `ORGANISATION_UMBENANNT` | Routine | ändert nur die Beschriftung |

Das Umhängen ist der Vorgang, der bei einer späteren Untersuchung erklären
muss, warum jemand Zugriff auf etwas hatte. Er gehört auf die
Sicherheitsstufe, damit er die Rotation am längsten überlebt (ADR-0005).

## Nachgewiesen

Modultests (`OrganisationsverwaltungTest`, gegen eine echte Datenbank):
Übernahme fremder Einheiten, Wegschieben aus dem Bereich, Anlegen unter einer
fremden Einheit, Zyklus, Selbstzuordnung, Art-Verletzungen, Löschsperren,
Benutzer ohne Bereich.

Systemnachweise gegen den laufenden Stack: Anlegen, Umbenennen, Umhängen,
Zyklusablehnung mit unversehrtem Baum, Löschen einer leeren und Verweigerung
bei einer belegten Einheit, Protokolleinträge — und aus der Sitzung eines
Praxisbenutzers: HTTP 403 beim Anlegen, kein Anlegeformular, die Begründung in
der Oberfläche, keine Umbenennung einer fremden Einheit.

Die Organisationsseite und ihre aufgeklappte Bearbeitungszeile sind in die
Barrierefreiheitsprüfung aufgenommen. Bei der Gelegenheit auch die
Terminalmaske mit dem PIN-Formular — beides sind Zustände, die im Grundaufruf
einer Seite nicht entstehen und deshalb bis dahin ungeprüft blieben.

---

## Nachtrag 12.08.2026 — die Hierarchie folgt der Organisationsstruktur

Grundlage: [ADR-0012](../adr/0012-geraetehierarchie-nach-organisationsstruktur.md).
Vorgabe des PO vom 12.08.2026. Umgesetzt in Migration 24.

### Zwei neue Ebenen

| Ebene | erlaubt darunter |
|---|---|
| `VERBUND` (Trägergesellschaft) | `LEISTUNGSERBRINGER`, `STANDORT`, `EINRICHTUNG` |
| `LEISTUNGSERBRINGER` **(neu)** | `STANDORT`, `EINRICHTUNG` |
| `STANDORT` | `EINRICHTUNG` |
| `EINRICHTUNG` (Betriebsstätte) | `FACHBEREICH` **(neu)** |
| `FACHBEREICH` **(neu)** | — |

Alle Zwischenebenen bleiben **optional**. Eine Einzelpraxis ist weiterhin
`VERBUND → EINRICHTUNG`. Ein Modell, das für drei Geräte vier Ebenen erzwingt,
wäre eine Zumutung.

Durchgesetzt wird die Schachtelung in `Organisationsverwaltung.erlaubtUnter` —
an einer Stelle, nicht an jedem Formular. Die Auswahlfelder bieten deshalb nur
zulässige Ziele an, und der Server lehnt trotzdem ab, wenn jemand am Formular
vorbei sendet. Beides ist nachgewiesen.

### Die Betriebsstätte trägt ihre TI-Identität

`bsnr` und `telematik_id` stehen an der Organisationseinheit, gefüllt **nur bei
`EINRICHTUNG`**: Sie ist die Leistungserbringerinstitution, sie führt die SMC-B.
Ein Standort nimmt keine BSNR an — die Anwendung verweigert das mit einer
Meldung, die den richtigen Ort nennt, statt das Feld nur zu verstecken.

Beide Angaben sind **freiwillig**. Beim Einrichten liegen sie oft noch nicht vor,
und eine Pflichtangabe, die niemand hat, erzeugt Fantasiewerte, die später
niemand korrigiert.

Geprüft wird die **Form** der BSNR — neun Ziffern, Leerzeichen erlaubt —, nicht
ihre Gültigkeit. Ob es die Nummer wirklich gibt, weiß nur die KV. Eine Prüfung,
die mehr behauptet, als sie kann, ist schlimmer als keine.

Ereignisart: `ORGANISATION_TI_IDENTITAET`, Stufe **Sicherheit**. Eine falsche
BSNR führt zur falschen SMC-B-Zuordnung; das muss nachvollziehbar bleiben.

### Ein Konnektor, mehrere Betriebsstätten

Die Tabelle `konnektor_nutzung` macht die Beziehung n:m. Der Konnektor behält
zusätzlich **eine betreibende Organisation**:

> **Wer den Konnektor betreibt, verwaltet ihn. Wer ihn nutzt, sieht ihn.**

| Handlung | Betreiber | Mitnutzer |
|---|---|---|
| Konnektor sehen, eigene Terminals zuordnen | ja | ja |
| Bezeichnung, Adresse, Zugangsdaten ändern | ja | **nein** |
| Löschen, Kennwort entfernen, Mitnutzer eintragen | ja | **nein** |

**Die Falle dabei** — und der Grund, warum dazu ein eigener Modultest gehört:
`Bestandsverwaltung.konnektor(...)` zeigt den Konnektor seit ADR-0012 auch dem
Mitnutzer. Die Änderungswege prüften bis dahin nur „sehe ich ihn?". Damit hätte
jeder Mitnutzer den Konnektor umkonfigurieren können, an dem zwanzig andere
hängen. Sie prüfen jetzt `darfKonnektorVerwalten(...)`, das ausschließlich auf
`konnektor.organisation_id` schaut.

Der Betreiber lässt sich **nicht** als Nutzer austragen: Er nutzt seinen
Konnektor immer. Die Zeile ist eine Feststellung, keine Option — deshalb hat sie
keinen Knopf, und der Versuch über die Route wird abgewiesen.

### Das Kartenterminal hängt an der untersten passenden Ebene

Gibt es Fachbereiche, gehört das Gerät einem davon; sonst der Betriebsstätte.
Technisch bleibt es dieselbe Spalte `kartenterminal.organisation_id` — die Ebene
ergibt sich aus der Art der Einheit. Kein zweites Feld „Fachbereich": Zwei
Spalten, die dasselbe meinen und auseinanderlaufen können, sind schlechter als
eine.

**Folge für jede Zählung:** Wer die Geräte einer Betriebsstätte zählt, muss den
**Teilbaum** nehmen, nicht die eine Kennung. Der Geltungsbereich tut das ohnehin;
der Demodatenaufbau und die Systemnachweise mussten nachgezogen werden.

### Was der Demodatensatz jetzt zeigt

- 1 Träger, 3 Standorte, 9 Betriebsstätten, **2 Fachbereiche** (nur in der
  Chirurgie — die Ebene ist optional, und ein Aufbau, in dem sie überall
  vorkommt, täuscht eine Pflicht vor, die es nicht gibt),
- **7 Konnektoren** statt 9: sechs eigene, dazu ein zentraler im Rechenzentrum,
  den sich drei kleine Betriebsstätten teilen,
- BSNR und Telematik-ID an allen neun Betriebsstätten, an keiner anderen Ebene.

Alle Werte sind erfunden; die Form stimmt, der Wert nicht.
