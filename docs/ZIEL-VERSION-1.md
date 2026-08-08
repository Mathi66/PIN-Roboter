# Abbruchbedingung für `/goal` — Version 1 fertigstellen

Dieser Text ist als Argument für `/goal` gedacht. Bei `/goal` ist die Bedingung
zugleich die Arbeitsanweisung: Sie muss anweisen **und** prüfbar sein.

**So wird er verwendet** — Auto-Mode einschalten, dann:

```
/goal <Text aus dem Kasten unten>
```

Ohne Auto-Mode fragt jeder Werkzeugaufruf nach, den die Einstellungen nicht
schon erlauben; unbeaufsichtigt läuft es nur mit beidem.

**Prüfen und beenden:** `/goal` ohne Argument zeigt Bedingung, Laufzeit,
Turnzahl, Tokenverbrauch und die letzte Begründung des Prüfers.
`/goal clear` bricht ab.

---

## Der Text

> Bringe den PIN-Roboter auf einen abnahmefähigen Stand für Version 1.
>
> **Fertig ist es, wenn alle vier Punkte im Gesprächsverlauf belegt sind:**
>
> 1. `bash pruefungen/systemnachweise.sh` endet mit „Alle Systemnachweise
>    bestanden" — die Schlusszeile steht im Verlauf.
> 2. Der Modultestlauf endet mit `Failures: 0, Errors: 0`.
> 3. In `docs/01a-umsetzungsstand.md` trägt **jede** Zeile entweder ✔/✓ mit
>    Verweis auf den Nachweis, oder eine ausdrückliche Begründung
>    „nicht in Version 1" mit Verweis auf einen ADR oder eine PO-Entscheidung.
>    Zeige die Datei am Ende vollständig.
> 4. `git status` ist sauber und alles ist nach `main` gepusht.
>
> **Arbeite in dieser Reihenfolge:**
> KSR-Zustand „aktualisierend" absichern (`gemSpec_Kon 4.3.10`) · dann
> `docs/05-sicherheit-datenschutz.md` um PIN-Übergabe, Organisationsverwaltung
> und Mailversand ergänzen · dann FA-PIN-040, FA-UEB-020, FA-UEB-030 · dann
> FA-AGT-040 und FA-INS-040 · dann jede verbliebene offene Zeile im
> Umsetzungsstand.
>
> **Nicht verhandelbar:**
> Keine Anforderung als erfüllt markieren, ohne sie am laufenden System
> nachgewiesen zu haben — bei FA-UEB-010 stand „erfüllt", während die Funktion
> fehlte; prüfe deshalb auch die bereits als erfüllt geführten Zeilen
> misstrauisch. Keine PIN, keine Zugangsdaten in Logs, Protokoll oder Mail.
> Nach jedem Eingriff in die Oberfläche Kontraste und axe-core prüfen. Nach
> jeder abgeschlossenen Anforderung committen und pushen, mit Eintrag in
> `FORTSCHRITT.md` und `docs/03-umsetzung/`. Deutsch, auch in den
> Zwischenüberlegungen.
>
> **Wenn etwas nicht geht:** Nicht stillschweigend überspringen. Trage es im
> Umsetzungsstand mit Begründung ein und arbeite weiter.
>
> **Stoppe nach 30 Turns** und lege mir vor, was fehlt und warum — auch wenn
> die Bedingung dann nicht erfüllt ist.

---

## Warum die Bedingung so aussieht

**Der Prüfer liest nur mit.** Er führt keine Befehle aus und öffnet keine
Dateien. Alles, was er beurteilen soll, muss vorher im Verlauf gestanden haben
— daher die ausdrücklichen Sätze „die Schlusszeile steht im Verlauf" und „zeige
die Datei am Ende vollständig". Eine Bedingung wie „alles ist fertig" wäre für
ihn nicht entscheidbar und liefe endlos.

**Vier Punkte statt einem.** Ein grüner Nachweislauf allein genügt nicht: Er
wäre auch grün, wenn eine Anforderung gar nicht erst angefasst wurde. Punkt 3
schließt genau diese Lücke.

**Die Turn-Grenze ist kein Nebensatz.** Ohne sie läuft die Schleife, bis das
Ziel erreicht ist — und Version 1 ist groß. 30 Turns sind ein Zwischenhalt,
kein Scheitern.

**Der Satz zu FA-UEB-010** steht da, weil genau das passiert ist: Das
Prüfintervall je Gerätegruppe war gepflegt, gespeichert und in Nachweisen
belegt — und erreichte den Agenten nie. Im Umsetzungsstand stand trotzdem
„erfüllt". Eine Zeile mit Haken ist kein Nachweis.
