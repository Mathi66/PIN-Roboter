# ADR-0010: Neuausrichtung der Oberfläche, HTMX bleibt

- **Status:** **Angenommen** (07.08.2026), Umsetzung begonnen
- **Betrifft:** UX, Architektur (Bestätigung von [ADR-0006](0006-technologie-stack.md))
- **Grundlage:** Rückmeldung des PO nach der ersten Vorführung,
  [UX-Konzept](../06-ux-konzept.md), [Prototyp](../06-ux/prototyp.html)

---

## Kontext

Nach der ersten Vorführung hat der PO fünf Punkte beanstandet: Bedienbarkeit
und Optik, den Suchknopf, die Bearbeitung von Organisationen, fehlende
Datenbeziehungen zum Durchklicken, und dass Seiten immer länger werden statt
neue zu öffnen. Gewünscht: schwebende Boxen, Glaseffekt, modern.

Er hat außerdem angemerkt, die verwendeten Webtechnologien seien nicht modern.

## Optionen

### A — Wechsel auf React oder Vue

**Dagegen:** Der altbackene Eindruck kam nicht von HTMX. Ein Prototyp mit
derselben Technologie sieht anders aus — das war der Beleg. Ein Wechsel würde:

- die Barrierefreiheitsnachweise verkomplizieren (axe gegen serverseitig
  erzeugtes HTML ist ungleich einfacher als gegen einen Komponentenbaum),
- einen Bau- und Bündelungsschritt in die Auslieferung bringen,
- die Ladezeit erhöhen statt senken (Vorgabe: Seitenaufbau < 1 s im LAN),
- und die Zusage aus [ADR-0009](0009-pin-hinterlegen-ueber-die-oberflaeche.md)
  berühren, dass genau **eine** Maske JavaScript braucht.

### B — Gestaltung neu, Technologie bleibt ✔

Neues Farb- und Flächenkonzept, Seitenleiste, Live-Suche, Detailseiten. Alles
mit server-gerendertem HTML und HTMX.

## Entscheidung

**Option B.** Der PO hat den Prototyp abgenommen und einen Wechsel auf React
ausdrücklich abgelehnt (07.08.2026). [ADR-0006](0006-technologie-stack.md)
bleibt damit in Kraft.

## Konsequenzen

- **Glasoptik mit garantiertem Kontrast:** Unter jeder Glasfläche liegt eine
  deckende Farbe mit geprüftem Kontrast; `backdrop-filter` ist Zugabe. Bei
  `prefers-reduced-transparency` volle Deckung. Der Hintergrund bleibt ein
  ruhiger, kontrastarmer Verlauf, damit der Textkontrast berechenbar bleibt.
  Ohne diese Regel wäre `NFA-USA-030` (WCAG 2.2 AA) nicht mehr nachweisbar.
- **Die semantischen Farben bleiben unverändert** — sie sind gemessen. Neu sind
  nur Grundflächen und Akzent.
- **Keine Webfonts.** Die Anwendung läuft On-Premise ohne Internetzugang, und
  der Seitenaufbau soll unter 1 s bleiben. Drei Rollen aus dem Systemstack,
  Gerätekennungen in Monospace mit `tabular-nums`.
- **Kein Timer tauscht mehr eine ganze Seite aus.** Aktualisiert werden nur die
  Einschübe, die sich wirklich ändern.
- **Detailseiten haben eigene Adressen.** Lesezeichen und der Zurück-Knopf des
  Browsers funktionieren.

## Was noch aussteht

Stufe 4 und 5 des Konzepts: die verbleibenden Aufklapper durch Detailseiten
ersetzen und die Organisations-Detailseite mit allen Beziehungen bauen.
