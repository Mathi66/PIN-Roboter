# Umsetzung: Standort-Agent

**Anforderungen:** FA-AGT-010/020/030, ADR-0002

---

## Die Trennlinie ist die Vertrauensgrenze

| | Zentrale | Agent |
|---|---|---|
| kennt Geheimnisse | **nein** | ja (PIN, Terminal-Zugänge) |
| spricht mit Geräten | nein | ja |
| läuft ohne den anderen | eingeschränkt | **vollständig** |

Die Trennung verläuft nicht nach technischer Schichtung, sondern danach, wo
Geheimnisse liegen dürfen. Die Zentrale steuert und protokolliert, der Agent
besitzt und handelt.

## Zwei Geheimnisse, zwei Lebensdauern

| | Gültigkeit |
|---|---|
| **Registrierungsgeheimnis** | einmalig, 24 Stunden |
| **Agent-Token** | dauerhaft bis Widerruf |

Ein Geheimnis, das beliebig oft gilt, ist ein Dauerzugang — genau das, was ein
Registrierungsverfahren vermeiden soll. Es wird beim Einlösen verbraucht;
nachgewiesen ist, dass ein zweiter Versuch abgewiesen wird.

Beide liegen nur als **SHA-256-Hash** in der Datenbank. Kein Argon2: Die Werte
sind 256 Bit aus einem sicheren Zufallsgenerator, nicht menschengewählt — gegen
Zufall dieser Länge hilft kein Durchprobieren, und die Prüfung liegt auf dem
heißen Pfad jeder Agentenmeldung.

## Ohne Token arbeitet der Agent weiter

Er überwacht und verifiziert eigenständig; nur die Meldungen an die Zentrale
entfallen, und er sagt das deutlich.

**Der TI-Zugang einer Praxis darf nicht daran hängen, dass unsere
Verwaltungsschicht erreichbar oder korrekt eingerichtet ist.** Ein
Sicherheitsmechanismus, der im Fehlerfall die Kernfunktion mitnimmt, ist an
dieser Stelle das falsche Werkzeug.

## Ein Fehler aus der Entwicklung

Als der Zugriffsschutz für die Oberfläche eingebaut wurde, sperrte er **den
eigenen Agenten aus**: Er fragt `/api/notaus` ab, hat aber keine Sitzung — der
Not-Aus erreichte ihn nicht mehr. Aufgefallen ist es nur, weil ein
Systemnachweis prüft, dass bei aktivem Not-Aus keine Verifikation läuft.

Ein Schutzmechanismus kann eine Sicherheitsfunktion abschalten, ohne dass es
irgendwo rot wird.

## Offen

- **Gegenseitig authentifizierte Verbindung** statt Token im HTTP-Kopf
  (ADR-0002 sieht WebSocket über TLS vor)
- **FA-AGT-030 Ereignispuffer:** Bei ausgefallener Zentrale werden Ereignisse
  derzeit verworfen statt zwischengespeichert
- **FA-AGT-040 Versionsunterschiede** zwischen Agent und Zentrale
