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

## Warum dieses Verzeichnis erst nachträglich entstand

Die Definition of Done in `CLAUDE.md` verlangt eine Umsetzungsbeschreibung je
Feature. Über zehn Entwicklungsiterationen hinweg wurde sie nicht geschrieben —
ein Verstoß gegen die eigene Vorgabe, festgehalten am 07.08.2026.

Der Grund ist typisch und deshalb erwähnenswert: Jede einzelne Iteration endete
mit einem grünen Testlauf, und die Dokumentation fühlte sich wie der
verzichtbare Teil an. Das Ergebnis waren 6.300 Zeilen Code, deren Begründungen
nur in Commit-Nachrichten und Quelltextkommentaren standen — auffindbar für
den, der sie geschrieben hat, und für sonst niemanden.
