# 22 — Versionsunterschiede und Wiederherstellung

**Stand:** umgesetzt und nachgewiesen (08.08.2026)
**Anforderungen:** FA-AGT-040, FA-INS-040

---

## Zwei falsch beschriftete Zeilen

Im Umsetzungsstand stand:

| Kennung | dort beschriftet als | heißt im Katalog wirklich |
|---|---|---|
| FA-AGT-040 | „Agentenverwaltung in der Oberfläche" | **Versionsunterschiede** |
| FA-INS-040 | „Health-Checks" | **Sicherung und Wiederherstellung** |

Beide Male war eine plausibel klingende, aber falsche Anforderung als „fast
fertig" geführt. Derselbe Fall wie zuvor bei FA-ALA-020, wo das Quittieren von
Alarmen unter der E-Mail-Benachrichtigung stand.

> Eine falsch beschriftete Zeile ist gefährlicher als eine leere: Sie sieht aus
> wie Fortschritt. Für die Abnahme heißt das — der Katalog ist die Grundlage,
> nicht die Statusliste.

## FA-AGT-040 — Versionsunterschiede

**Warum das nötig ist.** Agent und Zentrale werden getrennt aktualisiert. Bei
1.000 Standorten ist eine gemischte Landschaft der Normalfall: Ein Agent in
einer Praxis, die über Nacht ausgeschaltet war, meldet sich Tage später mit
einer älteren Fassung. Watchtower rollt nicht überall gleichzeitig aus.

Ohne Versionsangabe zeigt sich der Unterschied als seltsames Verhalten — ein
Feld fehlt, eine Einstellung bleibt wirkungslos — und niemand findet die
Ursache. Genau das ist bei FA-UEB-010 passiert, nur aus einem anderen Grund.

**Umgesetzt:** Der Agent meldet die Protokollversion, die er spricht. Die
Zentrale bewertet sie gegen eine unterstützte Spanne (derzeit 1 bis 2) und
zeigt das Ergebnis unter „Instanz" an — mit Klartext, was zu tun ist.

**Die wichtigste Festlegung:** Ein Agent außerhalb der Spanne wird
**nicht abgewiesen.**

Ein abgewiesener Agent hieße: keine PIN-Verifikation an diesem Standort. Ein
veralteter Agent, der seine Kernaufgabe noch erfüllt, ist besser als gar
keiner. Er wird angezeigt, nicht ausgesperrt — nachgewiesen ist beides: dass
die Abweichung erkannt und erklärt wird, **und** dass er weiterarbeitet.

Protokolliert wird nur der **Wechsel**, nicht jede Meldung. Bei 1.000 Agenten
im Minutentakt stünde sonst nichts anderes mehr im Protokoll (ADR-0005).

## FA-INS-040 — Sicherung und Wiederherstellung

Drei der vier Akzeptanzpunkte waren erfüllt: Das Verfahren ist dokumentiert,
die Wiederherstellung wird bei **jedem** Nachweislauf durchgespielt (Volume
löschen, aus dem Auszug zurückspielen, Zählungen vergleichen), und die
Sicherung enthält keine Klartext-Geheimnisse.

Der vierte fehlte:

> Nach Wiederherstellung ist ersichtlich, **welche PINs neu zu hinterlegen
> sind**.

**Der Fall, um den es geht.** Die Zentrale wird wiederhergestellt, der Agent
aber neu aufgesetzt. Sein Schlüsselpaar ist dann ein anderes — die PINs in
seiner Ablage sind unlesbar oder gar nicht mehr da. Die Zentrale kann das nicht
direkt wissen; sie sieht nur, dass sich der Agent mit einem **neuen
öffentlichen Schlüssel** gemeldet hat, und zwar später als die PIN hinterlegt
wurde. Genau das ist das Kennzeichen.

Ohne diese Anzeige bliebe die Praxis im Glauben, alles sei versorgt — bis die
erste Karte unverifiziert bleibt und niemand weiß, warum.

Die Tafel erscheint **nur, wenn tatsächlich etwas fehlt**. Eine dauerhaft leere
Anzeige „alles in Ordnung" erzieht dazu, sie zu übersehen.

## Ein Nachweis, der einen Fehler meldete, wo keiner war

Der Nachweis „die Sicherung enthält keine Geheimnisse im Klartext" suchte unter
anderem nach `123456` — der Test-PIN. Er fand sie und schlug an. In der
ICCSN einer Demo-eGK: `80276883110000123456`.

Eine sechsstellige Ziffernfolge findet in einem vollständigen SQL-Auszug
zwangsläufig Zufallstreffer — Kartennummern, Kennungen, Zeitstempel. Der
Nachweis prüft jetzt unverwechselbare Kennwörter und zusätzlich die **Form**
jeder Geheimnisspalte: Sie muss mit `v1:` beginnen, also ein Chiffrat sein.

> Ein Nachweis, der Fehler meldet, wo keine sind, ist schlimmer als keiner. Wer
> ihn dreimal so erlebt, glaubt ihm beim vierten Mal nicht — und beim vierten
> Mal stimmt er.

## Nachgewiesen

12 neue Systemnachweise: Versionsmeldung, Protokolleintrag nur bei Wechsel,
Anzeige in der Oberfläche, Erkennung einer nicht unterstützten Version bei
gleichzeitigem Weiterarbeiten; die namentliche Anzeige fehlender PINs mit
Ursache und Handlung, das Ausbleiben der Tafel im Normalfall, keine Kennwörter
im Klartext, jede Geheimnisspalte als Chiffrat, kein PIN-Chiffrat in der
Sicherung.
