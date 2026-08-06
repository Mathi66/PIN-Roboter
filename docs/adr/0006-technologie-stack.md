# ADR-0006: Technologie-Stack

- **Status:** Vorschlag — Freigabe durch den Product Owner erbeten
- **Datum:** 06.08.2026 (Neufassung nach Messung)
- **Betrifft:** Umsetzung, Betrieb, Wartbarkeit
- **Ersetzt:** die erste Fassung vom selben Tag (Vorschlag Go — durch Messung widerlegt)

---

## Entscheidungsvorschlag

| Ebene | Wahl |
|---|---|
| Sprache, beide Bausteine | **Java (LTS)** |
| Kryptographie und TLS | **BouncyCastle** (`bcprov`, `bctls`, BCJSSE), TLS **1.2** mit Brainpool |
| Oberfläche | **server-gerendertes HTML + HTMX + Server-Sent Events** |
| Datenbank Zentrale | **PostgreSQL** |
| Datenbank Agent | **eingebettet** (SQLite oder gleichwertig) |
| Konnektor-Anbindung | **SOAP-Client** gegen die veröffentlichten Schnittstellenbeschreibungen |
| Agentenprotokoll | **WebSocket über TLS**, gegenseitig authentifiziert |
| Bauumgebung | **GitHub Actions**, Multi-Arch, Cosign-Signatur, SBOM |
| Basisimage | schlankes JRE-Image, **Nicht-Root** |

---

## Wie diese Entscheidung zustande kam

Sie ist das Ergebnis einer Korrektur. Der erste Vorschlag lautete **Go** — mit
dem Argument, SOAP sei bei sechs festen Operationen keine Auswahlfrage, und der
Ausschlag gebe der schlanke Agent. Dieser Vorschlag war **falsch**, weil er die
TLS-Kurvenunterstützung nicht geprüft hatte.

Der Hinweis des POs auf **Brainpool-TLS** hat das aufgedeckt. Die anschließende
Messung ([Bericht](../00-referenzen/messung-brainpool-tls.md)) hat die Frage
entschieden — nicht durch Abwägung, sondern durch Messwerte.

---

## Die Beweiskette

| # | Befund | Beleg |
|---|---|---|
| 1 | **Brainpool ist zwingend**, nicht optional. Die Geräteidentitäten von Konnektor (gSMC-K) und Kartenterminal (gSMC-KT) sind auf `brainpoolP256r1` festgelegt — in den Objektsystemen der Chipkarten fixiert. Der Schlüsselaustausch ließe sich auf NIST umstellen, die Zertifikatsprüfung nicht. | `[gemSpec_Krypt]`, `[gemSpec_Kon]`, `[gemSpec_KT] TIP1-A_3227`; Praxisangabe PO |
| 2 | **TLS 1.2 ist Pflicht, TLS 1.3 nur Kann.** Damit dürfen wir uns auf TLS 1.2 festlegen — genau die Version, unter der Brainpool nachweislich funktioniert. | `GS-A_4385`, `A_18467` |
| 3 | **Go ist ausgeschlossen** — und zwar fundamental: Die Standardbibliothek kann ein Zertifikat mit Brainpool-Schlüssel **nicht einmal einlesen**. Es kommt zu keinem Handshake. Ein vorgelagerter TLS-Vermittler löst das nicht, sobald TI-Zertifikate im Spiel sind. | Messung 06.08.2026, Go 1.26 |
| 4 | **Das Standard-JDK genügt nicht** — es meldet „no supported named groups". | Messung, Temurin 21 |
| 5 | **Java + BouncyCastle funktioniert** — Handshake über TLS 1.2 mit `brainpoolP256r1` erfolgreich. | Messung, BouncyCastle 1.83 |
| 6 | **Konnektor und HSK verwenden selbst BouncyCastle.** Auf beiden Seiten der wichtigsten Verbindung läuft damit dieselbe Umsetzung von RFC 5639. | Praxisangabe PO, 06.08.2026 |
| 7 | **Die Entwickler sind Experten in Java und BouncyCastle.** | Angabe PO, 06.08.2026 |
| 8 | Java bietet zudem **erstklassige SOAP-Unterstützung** — das Problem, das den ersten Vorschlag überhaupt schwierig machte, löst sich nebenbei. | — |

**Punkt 6 und 7 sind die stärksten.** Interoperabilitätsprobleme entstehen nicht
dort, wo beide Seiten dieselbe Bibliothek verwenden, sondern an den Rändern
unterschiedlicher Umsetzungen — wir vermeiden diese Ränder vollständig. Und ein
Stack, den das Team beherrscht, wird über Jahre gepflegt; einer, den es sich
erarbeiten muss, wird es vielleicht.

> **Anmerkung zum Verfahren:** In der ersten Fassung hatte ich gefragt, ob es
> Kenntnisstände gibt, die berücksichtigt werden sollten. Die Antwort lautete
> zunächst, die Entwicklung solle frei vorschlagen. Dass die Expertise nun
> benannt wurde, ist kein Widerspruch, sondern eine Ergänzung — und sie hätte
> die Entscheidung auch dann getragen, wenn sie das einzige Argument geblieben
> wäre. Hier fällt sie mit allen übrigen zusammen.

---

## Der eine ernsthafte Einwand: Speicherbedarf

`NFA-LEI-030` verlangt, dass der **vollständige Stack** in der Einzelpraxis mit
**≤ 2 GB Arbeitsspeicher** auskommt — bei 3 Terminals, auf handelsüblicher
Praxishardware, Start in unter 60 Sekunden.

Das ist mit Java erreichbar, aber **nicht geschenkt**. Im verteilten Betrieb
laufen Zentrale, Agent und Datenbank auf derselben Maschine. Erforderlich sind
daher:

- **Feste Speicherobergrenzen je Container**, nicht die Vorgaben der Laufzeit
- Ein **auf geringen Verbrauch ausgelegter Speicherbereiniger**, kein auf
  Durchsatz optimierter
- **Schlankes JRE-Image** statt vollständigem JDK; nur benötigte Module
- **Messung als Abnahmekriterium**, nicht als Hoffnung

Sollte die Messung ergeben, dass 2 GB nicht reichen, sind in dieser Reihenfolge
zu prüfen: vorab übersetztes Programmabbild (GraalVM Native Image) für den
Agenten, dann Anhebung der Grenze in Abstimmung mit dem PO. **Der Stack wird
deswegen nicht getauscht** — die Brainpool-Anforderung wiegt schwerer als der
Speicherbedarf, und die Alternativen lösen ihn nicht besser.

---

## Was aus der ersten Fassung unverändert gilt

Diese Festlegungen waren sprachunabhängig begründet und bleiben bestehen:

- **Oberfläche vom Server**, kein separates JavaScript-Rahmenwerk. Hauptgrund
  ist die Nachweispflicht für WCAG 2.2 AA; große Listen werden ohnehin
  serverseitig gefiltert und paginiert.
- **PostgreSQL** in der Zentrale, **eingebettete Datenbank** im Agenten — die
  Bausteine haben keine gemeinsame Datenschicht.
- **WebSocket über TLS**, ausgehend vom Agenten, damit im Kundennetz keine
  eingehenden Freigaben nötig sind.
- **Signierte Multi-Arch-Images**, kein `:latest`, SBOM, Schwachstellenprüfung
  im Bau.
- **Kapselung** von Terminal- und Konnektor-Anbindung als zentrale
  Erweiterungsstelle.

Zum SOAP-Client: Das Argument der ersten Fassung — sechs feste Operationen, ein
handgeschriebener Client ist kontrollierbarer als generierter Code — bleibt
gültig, wird aber **entschärft**, weil Java hier ausgereifte Werkzeuge bietet.
Die Entscheidung zwischen generiert und handgeschrieben wird bei der Umsetzung
getroffen; maßgeblich ist, dass der Aufruf, der eine PIN prüft, nachvollziehbar
bleibt.

---

## Verbindliche Vorgaben für die Umsetzung

1. **TLS auf Version 1.2 festlegen** für die Verbindungen zu Konnektor und
   Kartenterminal. TLS 1.3 ist dort nicht zu erwarten und mit Brainpool
   nachweislich problematisch.
2. **Cipher-Suite-Liste ausdrücklich einschränken.** Der Testlauf einigte sich
   auf eine ChaCha20-Poly1305-Suite, die `[gemSpec_Krypt]` **nicht zulässt** —
   erlaubt sind nur `(RSA, AES-CBC)` und `(ECC, AES-GCM)`. Die Vorgabe der
   Bibliothek genügt nicht.
3. **`A_22457` beachten:** Gegenüber dem Kartenterminal dürfen bei beidseitiger
   Authentisierung nur Suites angeboten werden, für deren
   Authentisierungsverfahren wir selbst Schlüssel und Zertifikat besitzen.
4. **Zertifikatsfehler und Kurvenfehler unterscheidbar melden.** In der Messung
   führte eine fehlende Zertifikatserweiterung beinahe zu einem falschen
   Ausschluss. Im Betrieb würde derselbe Effekt den Betreiber an der falschen
   Stelle suchen lassen.
5. **BouncyCastle-Version festschreiben** und Aktualisierungen bewusst
   nachziehen — es ist die sicherheitskritischste Abhängigkeit des Produkts.

---

## Offene Punkte

1. **.NET und Node.js** wurden nicht abschließend gemessen. Das ist nach der
   Beweislage nicht mehr entscheidungsrelevant und wird nicht nachgeholt.
2. **Java oder Kotlin?** Der Vorschlag lautet **Java**, weil die Expertise dort
   benannt ist. Kotlin wäre technisch gleichwertig und auf derselben Laufzeit —
   die Entscheidung kann das Team treffen, sie berührt diese Architektur nicht.
3. **Speichermessung** gegen `NFA-LEI-030`, sobald ein lauffähiges Grundgerüst
   existiert.
