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
