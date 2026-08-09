# Dokumentation PIN-Roboter

**Wo fange ich an?** — je nachdem, wer Sie sind:

| Sie sind … | Lesen Sie |
|---|---|
| **Anwender** in Praxis oder Klinik | [08-anwenderhandbuch.md](08-anwenderhandbuch.md) |
| **DevOps**, richten eine Instanz ein | [09-devops-installation.md](09-devops-installation.md) |
| **Betreiber** einer Praxis, ohne IT-Personal | [04-betrieb.md](04-betrieb.md) |
| **Entwickler**, arbeiten am Code | [07-entwicklerhandbuch.md](07-entwicklerhandbuch.md) |
| **Architekt** oder Gutachter | [02-architektur.md](02-architektur.md) |
| **Product Owner** | [01a-umsetzungsstand.md](01a-umsetzungsstand.md), [ENTSCHEIDUNGEN-PO.md](ENTSCHEIDUNGEN-PO.md) |
| **Datenschutz** oder Informationssicherheit | [05-sicherheit-datenschutz.md](05-sicherheit-datenschutz.md) |

---

## Alle Dokumente

### Was das Produkt können soll

| Datei | Inhalt |
|---|---|
| [01-anforderungskatalog.md](01-anforderungskatalog.md) | Funktionale und nichtfunktionale Anforderungen mit testbaren Abnahmekriterien |
| [01a-umsetzungsstand.md](01a-umsetzungsstand.md) | Was davon fertig ist — je Anforderung, mit Nachweis |
| [ZIEL-VERSION-1.md](ZIEL-VERSION-1.md) | Was Version 1 umfasst |
| [ENTSCHEIDUNGEN-PO.md](ENTSCHEIDUNGEN-PO.md) | Was der Product Owner entscheiden muss |

### Wie es gebaut ist

| Datei | Inhalt |
|---|---|
| [02-architektur.md](02-architektur.md) | Systemüberblick nach arc42: Kontext, Bausteine, Laufzeit, Verteilung, Querschnitt |
| [07-entwicklerhandbuch.md](07-entwicklerhandbuch.md) | Einstieg in den Code, Konventionen, Testen, Fallen |
| [10-codeanalyse.md](10-codeanalyse.md) | Gemessener Zustand, Refactoring, was bewusst offen bleibt |
| [adr/](adr/) | Architekturentscheidungen mit Kontext, Optionen, Konsequenzen |
| [03-umsetzung/](03-umsetzung/) | Wie ein Feature umgesetzt wurde — und was dabei schiefging |

### Wie man es betreibt und bedient

| Datei | Inhalt |
|---|---|
| [08-anwenderhandbuch.md](08-anwenderhandbuch.md) | Bedienung, ohne IT-Kenntnisse |
| [04-betrieb.md](04-betrieb.md) | Installation und Betrieb für den Praxisbetreiber |
| [09-devops-installation.md](09-devops-installation.md) | Netzschnitt, Secrets, Reverse-Proxy, Härtung, Wiederherstellung |
| [06-ux-konzept.md](06-ux-konzept.md) | Gestaltungskonzept der Oberfläche |

### Sicherheit und Grundlagen

| Datei | Inhalt |
|---|---|
| [05-sicherheit-datenschutz.md](05-sicherheit-datenschutz.md) | Schutzbedarf, technische und organisatorische Maßnahmen, Datenflüsse |
| [00-referenzen/](00-referenzen/) | Quellenverzeichnis, Fachanalysen, Messberichte |

---

## Zwei Regeln für diese Dokumentation

**Sie entsteht mit der Umsetzung, nicht danach.** Ein Feature ist nicht fertig,
bevor seine Umsetzungsbeschreibung steht.

**Sie behauptet nichts.** „Getestet", „implementiert, nicht getestet" und
„geplant" werden nie vermischt. Wo etwas ungeprüft ist, steht es dabei — und wo
eine Messung dahintersteht, ist sie verlinkt.

---

## Der wichtigste offene Punkt

Alle Nachweise laufen gegen den selbstgebauten Simulator. Die in
[ADR-0007](adr/0007-simulierte-endpunkte-und-teststrategie.md) geforderte
**Kalibrierung an echter Hardware** steht aus. Sie wiegt schwerer als jeder
andere offene Punkt: Sie entscheidet, ob das Produkt in der Realität
funktioniert.
