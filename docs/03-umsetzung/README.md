# Umsetzungsbeschreibungen

Hier steht, **wie** die Anforderungen aus dem
[Anforderungskatalog](../01-anforderungskatalog.md) tatsächlich umgesetzt sind —
und vor allem: **warum so und nicht anders**.

Der Unterschied zum Architekturkonzept: Dort steht der Entwurf, hier der
gebaute Zustand. Wo beide auseinanderlaufen, gilt dieses Verzeichnis, und das
Architekturkonzept ist nachzuziehen.

## Verzeichnis

| Datei | Anforderungen |
|---|---|
| [01-pin-verifikation.md](01-pin-verifikation.md) | FA-PIN-020/030, FA-UEB-010/050 — die Kernfunktion |
| [02-brainpool-tls.md](02-brainpool-tls.md) | ADR-0006 — die Stolpersteine, die Tage gekostet haben |
| [03-anmeldung.md](03-anmeldung.md) | FA-BEN-010/020/030, NFA-SIC-010/050 |
| [04-protokoll-und-speicher.md](04-protokoll-und-speicher.md) | FA-PRO-010/020/040/070 |
| [05-alarme.md](05-alarme.md) | FA-ALA-010 |
| [06-agent-anbindung.md](06-agent-anbindung.md) | FA-AGT-010/020/030 |
| [07-pruefungen.md](07-pruefungen.md) | NFA-QS-010/020 — und was Tests wertlos macht |
| [08-bestandsverwaltung.md](08-bestandsverwaltung.md) | FA-GER-010/020, Demodatensatz |
| [09-bestandsermittlung.md](09-bestandsermittlung.md) | FA-GER-030/035 — Bestand über den Konnektor |
| [10-mandantentrennung.md](10-mandantentrennung.md) | FA-ORG-010, NFA-SIC-040 |
| [11-rechte-je-ebene.md](11-rechte-je-ebene.md) | FA-BEN-040, Rollen und Geltungsbereich |
| [12-benutzerverwaltung.md](12-benutzerverwaltung.md) | FA-BEN-010 bis 035, Einladungen |
| [13-barrierefreiheit.md](13-barrierefreiheit.md) | NFA-USA-040 — WCAG 2.2 AA |
| [14-karten-erfassen.md](14-karten-erfassen.md) | FA-KRT-010 |
| [15-pin-hinterlegen.md](15-pin-hinterlegen.md) | FA-PIN-010, ADR-0009 |
| [16-organisationsstruktur.md](16-organisationsstruktur.md) | FA-ORG-020 |
| [17-oberflaeche-neu.md](17-oberflaeche-neu.md) | ADR-0010 — Oberfläche Stufe 1 bis 3 |
| [18-benachrichtigung.md](18-benachrichtigung.md) | FA-ALA-020 — E-Mail mit Sammelfenster |
| [19-pruefintervall-je-gruppe.md](19-pruefintervall-je-gruppe.md) | FA-UEB-010 |
| [20-ksr-lauf.md](20-ksr-lauf.md) | FA-UEB-050, `gemSpec_Kon` 4.3.10 |
| [21-zustaende-erreichbarkeit-firmware.md](21-zustaende-erreichbarkeit-firmware.md) | FA-UEB-020/030/040 |
| [22-versionen-und-wiederherstellung.md](22-versionen-und-wiederherstellung.md) | NFA-BET-030 |
| [23-pin-aendern.md](23-pin-aendern.md) | FA-PIN-060, `A_24082` |
| [24-zeitstrahl-und-vorgang.md](24-zeitstrahl-und-vorgang.md) | FA-PRO-050/060 |
| [25-neue-versionen-erkennen.md](25-neue-versionen-erkennen.md) | FA-UPD-010 |
| [26-ux-refactoring.md](26-ux-refactoring.md) | NFA-USA-010 bis 040 |
| [27-ux-nacharbeit.md](27-ux-nacharbeit.md) | NFA-USA-010 — Befunde des PO |
| [28-datensicherung.md](28-datensicherung.md) | FA-BAK-010 bis 050, ADR-0010 |
| [29-remote-smcb-api.md](29-remote-smcb-api.md) | **FA-PIN-020/030 — die PIN-Übergabe, neu gebaut nach ADR-0011** |

## Warum dieses Verzeichnis erst nachträglich entstand

Die Definition of Done in `CLAUDE.md` verlangt eine Umsetzungsbeschreibung je
Feature. Über zehn Entwicklungsiterationen hinweg wurde sie nicht geschrieben —
ein Verstoß gegen die eigene Vorgabe, festgehalten am 07.08.2026.

Der Grund ist typisch und deshalb erwähnenswert: Jede einzelne Iteration endete
mit einem grünen Testlauf, und die Dokumentation fühlte sich wie der
verzichtbare Teil an. Das Ergebnis waren 6.300 Zeilen Code, deren Begründungen
nur in Commit-Nachrichten und Quelltextkommentaren standen — auffindbar für
den, der sie geschrieben hat, und für sonst niemanden.
