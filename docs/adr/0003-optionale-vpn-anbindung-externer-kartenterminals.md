# ADR-0003: Optionale VPN-Anbindung externer Kartenterminals

- **Status:** Vorschlag — als **optionale Ausbaustufe** vorgemerkt, nicht Teil der ersten Version
- **Datum:** 06.08.2026
- **Betrifft:** Architektur, Netzwerk, Produktstrategie, Scope

---

## Kontext

Idee des POs vom 06.08.2026: Das Produkt könnte als **VPN-Sternpunkt** dienen.
Extern stehende Kartenterminals tunneln sich dorthin ein und erhalten darüber
die Verbindung zum Netz, in dem Konnektor oder HSK steht.

Vorgabe des POs zur Priorität, ausdrücklich festgehalten:
> **Primär sind die Kartenterminals direkt über IP zu erreichen.**
> Die VPN-Anbindung ist eine **optionale** zusätzliche Anbindungsart.

Der Bedarf ist real: Nebenbetriebsstätten, ausgelagerte Standorte, mobile
Einheiten, MVZ mit getrennten Netzen und TI-as-a-Service-Betreiber haben genau
dieses Problem.

---

## Faktenlage

### Aus den Spezifikationen (geprüft am 06.08.2026)

| Befund | Beleg |
|---|---|
| Die gematik **spezifiziert keine VPN-Funktion für Kartenterminals**. Die Begriffe VPN, IPsec, WireGuard, OpenVPN kommen in der Spezifikation nicht vor. | `[gemSpec_KT] V3.17.1`, Volltextsuche ohne Treffer |
| Die Strecke Terminal↔Konnektor ist bereits auf Anwendungsebene abgesichert: TLS mit gegenseitiger Authentifizierung, Pairing über die gSMC-KT und ein ausgehandeltes gemeinsames Geheimnis (`ShS.KT.AUT`). | `[gemSpec_KT] 2.5`, `TIP1-A_3227` |
| Die Konnektor-Architektur geht davon aus, dass **allein der Konnektor** mit den **in lokalen Netzen** angeschlossenen Kartenterminals kommuniziert. | `[gemSpec_Kon] V5.25.0`, Architekturkapitel |
| Der VPN-Begriff der Konnektor-Spezifikation meint durchgängig den **VPN-Zugangsdienst zur TI** (`ID.NK.VPN`, `TUC_KON_321/322`) — nicht die Anbindung von Kartenterminals. | `[gemSpec_Kon] 4.2.4` |

### Aus der Praxis (Angabe des POs, 06.08.2026)

> **Alle Kartenterminals verfügen in aktueller Firmware über einen VPN-Client
> (IPsec, manche zusätzlich WireGuard). Das sind herstellerindividuelle Lösungen,
> die nicht von der gematik stammen. Die Terminals tunneln sich damit in das Netz
> ein, in dem der Konnektor oder HSK steht.**

Diese Angabe erklärt den scheinbaren Widerspruch: Die Funktion existiert am
Markt, sie ist nur nicht Gegenstand der gematik-Spezifikation. Sie ist damit
**herstellerspezifisch** — ohne spezifizierte Zusicherung, ohne einheitliches
Verhalten, und an den Firmware-Stand gebunden.

**Bewertung:** Der Ansatz widerspricht keiner Anforderung. Ein VPN dehnt ein
lokales Netz aus; die Spezifikation verlangt ein lokales Netz, nicht dessen
physische Konzentration an einem Ort. Die Absicherung der Nutzdaten leistet
ohnehin die TLS-Schicht — das VPN ist eine zusätzliche Schicht, kein Ersatz.

**Zu verifizieren am Testaufbau** (steht bereit, daher messbar statt vermutet):
welche Modelle welche Verfahren beherrschen, ab welchem Firmware-Stand, und wie
sich der Tunnel automatisiert konfigurieren lässt.

---

## Zwei Realisierungswege

### Weg A — das Terminal tunnelt selbst *(nach PO-Angabe der Regelfall)*
Der VPN-Client des Terminals baut den Tunnel zu unserem Sternpunkt auf.

- **Pro:** kein zusätzliches Gerät am entfernten Standort. Das Terminal steht
  danach netzwerktechnisch so da, als hinge es im Netz des Konnektors — genau
  das, was `[gemSpec_Kon]` voraussetzt. Nach PO-Angabe von allen aktuellen
  Terminals unterstützt.
- **Contra:** herstellerspezifisch, damit modell-, firmware- und
  herstellerabhängig. Wir müssten je Hersteller Konfiguration und Verhalten
  einzeln unterstützen und dauerhaft nachhalten. **Der Firmware-Stand wird zur
  harten Voraussetzung** — was die Firmware-Überwachung aus
  [ADR-0004](0004-firmware-verwaltung-der-kartenterminals.md) von einer
  Komfortfunktion zu einer Grundlage dieser Ausbaustufe macht.
- **Bindung an ein Kernrisiko:** Ein Terminal, dessen Tunnel nicht steht, ist für
  den Konnektor **nicht erreichbar** — die SMC-B bleibt unverifiziert. Der
  Sternpunkt wird damit betriebskritisch.

### Weg B — der Standort-Agent ist der Tunnel-Endpunkt
Der in [ADR-0002](0002-betriebsmodell-und-systemtopologie.md) ohnehin vorgesehene
**Standort-Agent** steht im entfernten Netz und baut den Tunnel von dort auf. Das
Terminal bleibt unverändert und spricht **lokal** mit seiner Umgebung.

- **Pro:** funktioniert mit **jedem** Terminal, unabhängig von Hersteller, Modell
  und Firmware. Keine Konfiguration am Terminal, keine Firmware-Abhängigkeit.
- **Contra:** am entfernten Standort muss ein kleiner Rechner laufen.

### Einordnung
Die Wege schließen sich nicht aus. **Weg A** ist der schlankere, wenn dort ohnehin
keine Hardware stehen soll; **Weg B** ist der robustere und deckt auch ältere
Geräte ab. Sinnvoll ist, den Sternpunkt so zu bauen, dass er **beide** Arten von
Gegenstellen annimmt — die Gegenstelle ist aus seiner Sicht in beiden Fällen nur
ein Tunnel-Endpunkt.

---

## Empfehlung

1. **Sternpunkt für beide Wege auslegen**, wenn die Funktion gebaut wird.
   Standardverfahren, keine Eigenentwicklung von Kryptographie: IPsec und
   WireGuard sind etabliert und in erprobten Implementierungen verfügbar.
2. **Nicht in der ersten Version.** Begründung unten.
3. **Früh und billig Wissen schaffen:** Am bereitstehenden Testaufbau erfassen,
   welche Modelle, Firmware-Stände und Verfahren tatsächlich vorliegen. Das
   Ergebnis entscheidet über die Ausgestaltung und kostet fast nichts.

### Warum nicht in der ersten Version

Das ist keine Ablehnung — die Idee ist gut und differenzierend. Drei sachliche
Gründe:

1. **Sie verändert, was das Produkt ist.** Mit einem VPN-Sternpunkt werden wir
   von einer Fachanwendung zu einem Stück Netzwerkinfrastruktur. Fällt der
   Sternpunkt aus, verlieren **alle** angebundenen Standorte die Verbindung zu
   ihrem Konnektor — nicht nur die PIN-Automatik, sondern den TI-Zugang
   insgesamt. Das ist eine erheblich größere Zusage als die des Kernprodukts und
   verlangt Redundanz, Überwachung und Bereitschaft.
2. **Sie vergrößert die Angriffsfläche deutlich.** Der Sternpunkt ist der
   Netzknoten, hinter dem Konnektoren und HSK stehen — ein hochwertiges Ziel, in
   einem Produkt, das ohnehin schon SMC-B-PINs verarbeitet. Beides gleichzeitig
   in Version 1 sauber abzusichern, ist unnötiges Risiko.
3. **Der Kernnutzen steht ohne sie.** Überwachung und automatische
   PIN-Verifikation funktionieren mit direkter IP-Erreichbarkeit vollständig —
   der ausdrückliche Regelfall laut PO.

Die Architektur aus ADR-0002 hält die Tür bewusst offen: Weil der Agent bereits
ein ausgehend verbindender Baustein im Kundennetz ist, ist die VPN-Funktion
später eine **Erweiterung** und kein Umbau.

---

## Konsequenzen

- Die erste Version setzt **direkte IP-Erreichbarkeit** von Konnektor/HSK und
  Kartenterminals voraus. Das gehört klar in die Systemvoraussetzungen.
- Der Standort-Agent wird so entworfen, dass ein Netz-Transport später
  **ergänzt** werden kann, ohne die Fachlogik anzufassen.
- Das Datenmodell sieht von Anfang an vor, dass ein Terminal **über
  unterschiedliche Wege erreichbar** sein kann. Das ist heute ein Feld, später
  eine Funktion — teuer wird es nur, wenn es fehlt.
- **Firmware-Stände werden erfasst** (ADR-0004). Für Weg A sind sie die
  Voraussetzung, nicht ein Nebenaspekt.
- Vor einer Umsetzung zu klären: Betreiberverantwortung und Haftung bei Ausfall
  des Sternpunkts, Verfügbarkeitszusage, Redundanz.
