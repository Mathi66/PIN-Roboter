# ADR-0006: Technologie-Stack

- **Status:** **Überarbeitung nötig** — die Sprachwahl ist bis zur Messung am Testaufbau **offen** (siehe Abschnitt „Brainpool-TLS")
- **Datum:** 06.08.2026
- **Betrifft:** Umsetzung, Betrieb, Wartbarkeit, Personalbindung

> ⚠️ **Wichtiger Nachtrag vom 06.08.2026.** Der PO hat auf die Anforderung
> **Brainpool-TLS** hingewiesen. Die Prüfung ergab: **Go kann das nicht** — die
> Sprachwahl unten ist damit nicht haltbar, solange nicht geklärt ist, ob und wo
> Brainpool tatsächlich benötigt wird. Alle übrigen Festlegungen (Oberfläche,
> Datenhaltung, Agentenprotokoll, Bauumgebung, Kapselung) bleiben unberührt, da
> sie sprachunabhängig sind. Details und Vorgehen: Abschnitt „Brainpool-TLS" am
> Ende dieses Dokuments.

---

## Entscheidungsvorschlag in einem Bild

| Ebene | Wahl |
|---|---|
| Sprache, beide Bausteine | **Go** |
| Oberfläche | **Server-gerendertes HTML + HTMX + Server-Sent Events** |
| Datenbank Zentrale | **PostgreSQL** |
| Datenbank Agent | **SQLite** (eingebettet) |
| Konnektor-Anbindung | **handgeschriebener SOAP-Client** gegen die veröffentlichten Schnittstellenbeschreibungen |
| Agentenprotokoll | **WebSocket über TLS**, gegenseitig authentifiziert mit Zertifikaten |
| Bauumgebung | **GitHub Actions**, Multi-Arch, Cosign-Signatur, SBOM |
| Basisimage | **distroless / statisch**, Nicht-Root |

**Eine Sprache für alles.** Das ist die tragende Entscheidung; alles Weitere folgt
daraus oder stützt sie.

---

## Was der Stack leisten muss

Aus [Anforderungskatalog](../01-anforderungskatalog.md) und
[Architektur](../02-architektur.md), nach Gewicht:

| # | Anforderung | Härtegrad |
|---|---|---|
| 1 | Zwei Bausteine, einer davon ein **schlanker Agent** in fremden Netzen | Architektur, ADR-0002 |
| 2 | **SOAP** gegen Konnektor/HSK | zwingend, gematik |
| 3 | **≤ 2 GB Arbeitsspeicher** für den gesamten Stack in der Einzelpraxis | NFA-LEI-030 |
| 4 | **5.000 Geräte nebenläufig** überwachen, ohne dass ein langsamer Konnektor andere blockiert | NFA-LEI-020 |
| 5 | **Linux, Windows, macOS · amd64 und arm64** | NFA-BET-030 |
| 6 | **WCAG 2.2 AA** nachweisbar | NFA-USA-010 |
| 7 | Wartbar über viele Jahre, in einem **kleinen Team** | Produktumfeld |
| 8 | Kleine Angriffsfläche, prüfbare Lieferkette | NFA-SIC-030/040 |

Punkt 7 verdient eine ehrliche Bemerkung: Dieses Produkt wird auf absehbare Zeit
von sehr wenigen Personen entwickelt. Ein Stack, der zwei Ökosysteme, zwei
Bauketten und zwei Testwelten erfordert, verdoppelt nicht den Aufwand — er
vervielfacht ihn, weil beide dauerhaft aktuell gehalten werden müssen. Das ist
hier ein Auswahlkriterium erster Ordnung, kein Nebenaspekt.

---

## Geprüfte Faktenlage (06.08.2026)

| Befund | Quelle |
|---|---|
| Die Kommunikation mit dem Konnektor erfolgt über **SOAP**. Die veröffentlichten Schnittstellenbeschreibungen sind Grundlage der Implementierung; **ein Primärsystem muss ohne zur Laufzeit bezogene WSDLs auskommen.** | `[gemILF_PS]` |
| Wir benötigen nur **wenige Operationen**: `GetPinStatus`, `VerifyPin`, `GetCards`, `GetCardTerminals`, `GetResourceInformation`; später `ChangePin`. | `[gemSpec_Kon] 4.1.5.5` |
| Go: Releases etwa halbjährlich, Fehlerbehebungen für die **zwei jüngsten** Hauptversionen; aktuell 1.26.5 (07.07.2026). Das **Go-1-Kompatibilitätsversprechen** hält Aktualisierungen praktisch aufwandsarm. | go.dev |
| .NET 10 ist LTS mit **drei Jahren** Unterstützung; SOAP über WCF-Client/CoreWCF ist erstklassig unterstützt. | dotnet.microsoft.com |
| Für Go existiert **kein einheitliches SOAP-Framework**, sondern mehrere Abspaltungen von `gowsdl` unterschiedlicher Pflegequalität. Der Weg ohne Generator (Standardbibliothek `encoding/xml`) ist verbreitet und dokumentiert. | pkg.go.dev, GitHub |
| Eine vergleichbare Überwachungslösung (`cocard`, MIT) spricht dieselben Konnektor-Operationen aus einem Ökosystem **ohne** erstklassige SOAP-Unterstützung an. | github.com/swobspace/cocard |

---

## Die Kernfrage: SOAP

Das ist der Punkt, an dem die Entscheidung hängt — und an dem der erste Reflex
in die Irre führt.

**Der Reflex:** „SOAP ist zwingend, also nehmen wir die Plattform mit der besten
SOAP-Unterstützung." Das wäre .NET oder Java.

**Der Blick auf den tatsächlichen Bedarf:** Wir sprechen **eine einzige,
spezifizierte, stabile Schnittstelle** mit **sechs Operationen** an. Das ist kein
Integrationsprojekt mit dutzenden wechselnden Diensten. Es ist ein festes,
überschaubares, gut dokumentiertes Protokoll.

Ein generierter Client bringt hier wenig Nutzen und zwei konkrete Nachteile:

1. **Abhängigkeit von einem Generator-Ökosystem**, das im Go-Umfeld fragmentiert
   ist — bei .NET wäre dieser Punkt gelöst, aber dann handelt man sich die
   Nachteile aus dem Vergleich unten ein.
2. **Undurchsichtigkeit an der kritischsten Stelle des Produkts.** Der Aufruf,
   der eine PIN gegen eine Karte prüft, ist die Stelle, an der ein Fehler eine
   Karte sperrt. Dort will ich **jede Zeile verstehen**, nicht generierten Code
   aus einem Fremdprojekt.

Ein handgeschriebener Client für sechs Operationen ist überschaubar — wenige
hundert Zeilen, vollständig testbar, gegen den Testaufbau verifizierbar. Der
Aufwand ist einmalig, das Verständnis dauerhaft.

> **Damit ist SOAP kein Auswahlkriterium mehr, sondern eine Aufgabe.** Das
> verschiebt die Entscheidung auf die Kriterien, die im Betrieb tatsächlich
> wehtun: Ressourcenbedarf, Nebenläufigkeit, Auslieferungsgröße und
> Wartungslast.

---

## Vergleich der Kandidaten

| Kriterium | **Go** | .NET 10 | Java / Kotlin | Node/TypeScript | Python |
|---|---|---|---|---|---|
| SOAP-Unterstützung | selbst zu schreiben | **exzellent** | **exzellent** | mäßig | gut (zeep) |
| Speicherbedarf Agent | **sehr gering** | mittel | hoch | mittel | mittel |
| Auslieferungsgröße | **~15–25 MB** | ~90–120 MB | ~150–250 MB | ~120 MB | ~100 MB |
| Nebenläufigkeit 5.000 Geräte | **sehr gut** | gut | gut | mäßig | schwach |
| Mehrere Architekturen | **trivial** | gut | gut | gut | mittelmäßig |
| Eine Sprache für beide Bausteine | **ja** | ja | ja | ja | ja |
| Aktualisierungslast über Jahre | **sehr gering** | mittel | mittel | **hoch** | mittel |
| Angriffsfläche (Laufzeit im Container) | **minimal** | Laufzeitumgebung | JVM | Laufzeitumgebung | Interpreter |

**Ausgeschieden:**
- **Python** — Nebenläufigkeit bei 5.000 Geräten und Ressourcenbedarf sprechen
  dagegen (Kriterien 3 und 4).
- **Node/TypeScript** — schwache SOAP-Lage bei gleichzeitig hoher
  Abhängigkeits- und Aktualisierungslast. Für ein Produkt mit langer Lebensdauer
  im Medizinumfeld die ungünstigste Kombination.
- **Java/Kotlin** — beste SOAP-Unterstützung, aber der Speicherbedarf kollidiert
  mit Kriterium 3. Eine JVM je Praxis, dazu eine je Agent, ist für Praxishardware
  die falsche Größenordnung.

**Verbleibend: Go gegen .NET.** Beide könnten das Produkt tragen. Der Ausschlag
gibt der **Agent**: Er läuft in fremden Netzen, auf unbekannter Hardware,
potenziell tausendfach. Ein statisch gelinktes Programm von rund 20 MB ohne
Laufzeitumgebung ist dort in Auslieferung, Ressourcenbedarf und Angriffsfläche
in einer anderen Klasse als ein Container mit Laufzeitumgebung.

Dazu kommt das **Go-1-Kompatibilitätsversprechen**: Der auf den ersten Blick
kürzere Unterstützungszeitraum (rund ein Jahr gegenüber drei Jahren bei .NET LTS)
täuscht, weil Go-Aktualisierungen erfahrungsgemäß ohne Anpassungen durchlaufen.
Was zählt, ist nicht die Länge des Zeitraums, sondern die Mühe des Wechsels.

---

## Zwei Schnittstellen zum Konnektor — und nur eine ist spezifiziert

Vorgabe des POs vom 06.08.2026:
> Die Webadmin-Schnittstelle kann ich nur für den **KoCo Konnektor** und den
> **KoCo HSK** bereitstellen. Beide sind in diesen Punkten **identische
> SOAP-Schnittstellen**.

Hersteller: **KoCo Connector GmbH**, Tochterunternehmen der CGM Deutschland AG;
Produkt KoCoBox MED+, gematik-Erstzulassung Ende 2017.

Das trennt zwei Dinge, die leicht verwechselt werden:

| | **Fachschnittstelle (Kartendienst)** | **Webadmin-Schnittstelle** |
|---|---|---|
| Zweck | `GetPinStatus`, `VerifyPin`, Bestand ermitteln | Konnektor administrieren |
| Spezifiziert von | **gematik**, `[gemSpec_Kon] 4.1.5.5` | **niemandem** — herstellerspezifisch |
| Herstellerübergreifend gleich | im Grundsatz **ja** | **nein** |
| Verfügbar für uns | grundsätzlich alle Konnektoren | **nur KoCo** (Konnektor und HSK, identisch) |
| Gebraucht für | die Kernfunktion | ergänzende Verwaltungsaufgaben |

**Zweck der Webadmin-Schnittstelle** (Präzisierung des POs, 06.08.2026):
> Wir brauchen sie, um das **Informationsmodell des Konnektors auszulesen** und
> die **verbundenen Kartenterminals zu erkennen**. Damit wird die Konfiguration
> der Kartenterminals einfacher.

Das ist mehr als Bequemlichkeit: Aus dem Informationsmodell stammen
**Mandanten-ID, Clientsystem-ID und Arbeitsplatz-ID** — die Werte, die jeder
Aufruf der Fachschnittstelle benötigt. Von Hand abgetippt sind sie die häufigste
und am schlechtesten diagnostizierbare Fehlerquelle bei der Inbetriebnahme.
Ausgelesen werden sie zur Auswahlliste. Siehe FA-GER-035.

**Scope-Festlegung des POs:** Version 1 unterstützt **ausschließlich KoCo
Konnektor und KoCo HSK**.

**Folgerungen:**

1. **Die Kernfunktion wird dennoch herstellerneutral gebaut**, über die
   gematik-spezifizierte Fachschnittstelle. Nicht weil Version 1 es bräuchte,
   sondern weil es jetzt fast nichts kostet und später einen Umbau erspart. Dass
   andere Hersteller sich dort gleich verhalten, ist eine **Erwartung aus der
   Spezifikation, kein Nachweis** — belegt ist nur, was am Testaufbau gemessen
   wurde.
2. **Die Webadmin-Funktionen sind zunächst KoCo-exklusiv.** Sie werden deshalb
   von Beginn an so gekapselt, dass Konnektoren anderer Hersteller ohne diese
   Funktionen betrieben werden können — mit klarer Anzeige, was dort nicht zur
   Verfügung steht.
3. **KoCo Konnektor und KoCo HSK gelten als eine Anbindung**, weil sie in den
   relevanten Punkten identisch sind. Das halbiert den Aufwand für die
   Erstunterstützung und ist ein guter Ausgangspunkt: Einbox-Konnektor und
   Highspeed-Konnektor decken zusammen Praxis- und Klinikbetrieb ab.
4. **Die Kapselung gilt damit auf beiden Seiten**, Konnektor wie Kartenterminal
   — dieselbe Erweiterungsstelle, dasselbe Muster (siehe Architektur 5.4).

---

## Oberfläche: server-gerendert statt Einzelseitenanwendung

**Vorschlag: HTML vom Server, HTMX für Teilaktualisierungen, Server-Sent Events
für Live-Fortschritt.** Kein React, kein Vue, kein eigener Frontend-Build.

Begründung:

1. **Barrierefreiheit.** WCAG 2.2 AA ist mit echtem, server-geliefertem HTML
   erheblich einfacher **nachweisbar** zu erreichen als mit einem
   JavaScript-Rahmenwerk, bei dem Fokusverwaltung, Rollen und Live-Bereiche von
   Hand nachgebaut werden müssen. Das ist eine MUSS-Anforderung mit Prüfpflicht —
   kein Bereich für Mehraufwand aus Gewohnheit.
2. **Große Listen sind ohnehin serverseitig.** 5.000 Geräte und Millionen
   Protokollzeilen werden serverseitig gefiltert und paginiert (FA-GER-060,
   FA-PRO-030). Der Hauptvorteil einer Einzelseitenanwendung — Daten im Browser
   halten und dort verarbeiten — entfällt hier vollständig.
3. **Eine Sprache, ein Build.** Kein zweites Ökosystem, keine zweite
   Abhängigkeitskette, keine zweite Aktualisierungslast (Kriterium 7).
4. **Kleinere Angriffsfläche** und deutlich weniger ausgelieferter Code.
5. **Live-Ansichten sind gelöst.** Rollout-Fortschritt und Statuswechsel laufen
   über Server-Sent Events — ein Standardmechanismus des Browsers, keine
   Zusatzbibliothek.

**Was das kostet:** Sehr aufwendige interaktive Ansichten wären in React
komfortabler. In diesem Produkt gibt es keine solche Ansicht — die anspruchsvollste
ist der Geräte-Zeitstrahl, und der ist server-renderbar. Sollte sich das ändern,
lässt sich eine einzelne Seite gezielt mit JavaScript anreichern, ohne die
Architektur umzustellen.

---

## Datenhaltung: zwei Wahlen, zwei Begründungen

**Zentrale: PostgreSQL.** Relational, ausgereift, exzellente Indizierung für die
Protokollabfragen (FA-PRO-030: unter 2 Sekunden bei 5 Millionen Einträgen),
strukturierte Detailfelder als JSON-Spalten, saubere Partitionierung des
Protokolls nach Zeit, für arm64 verfügbar. Für den zentralen Betrieb mit vielen
Mandanten ohne Alternative.

**Agent: SQLite, eingebettet.** Der Agent hat eine einzige Instanz, geringe
Datenmenge (Geheimnisse, Aufträge, Ereignispuffer) und keine Nebenläufigkeit über
Prozessgrenzen. Eine eingebettete Datenbank spart dort **einen kompletten
Container je Standort** — bei bis zu 1.000 Agenten ist das kein Detail. Kein
separater Prozess bedeutet auch: nichts, was unabhängig ausfallen kann.

> Die Wahl unterschiedlicher Datenbanken ist bewusst und **kein** Bruch: Die
> beiden Bausteine haben keine gemeinsame Datenschicht. Der Agent hält lokalen
> Zustand, die Zentrale den Bestand. Eine gemeinsame Abstraktion über beide wäre
> künstlich und würde beide Seiten schlechter machen.

---

## Agentenprotokoll

**WebSocket über TLS, ausgehend vom Agenten, beidseitig mit Zertifikaten
authentifiziert.**

- **Ausgehend über Port 443** — kommt durch Firewalls und Proxys in Kundennetzen
  (FA-AGT-020). Das ist der praktische Grund gegen gRPC: Es ist technisch
  eleganter, scheitert aber häufiger an dazwischenliegenden Proxys.
- **Gegenseitige Authentifizierung:** Bei der Registrierung (FA-AGT-010) erhält
  der Agent ein eigenes Zertifikat. Danach authentifiziert sich jede Seite
  gegenüber der anderen — ein gestohlenes Registrierungsgeheimnis nützt nach der
  einmaligen Verwendung nichts mehr.
- **Versionierte Nachrichten** (FA-AGT-040), damit Agent und Zentrale
  unterschiedliche Stände haben dürfen — im zentralen Betrieb der Normalfall.

---

## Bauumgebung und Auslieferung

| | |
|---|---|
| Bauen und Testen | GitHub Actions, bei jeder Änderung |
| Images | Multi-Arch (`linux/amd64`, `linux/arm64`) |
| Basis | distroless bzw. `scratch` mit statischem Programm, **Nicht-Root** |
| Signatur | Cosign, schlüssellos über die Actions-Identität (ADR-0001) |
| Stückliste | SBOM je Image, veröffentlicht |
| Schwachstellenprüfung | im Bau; kritische Befunde brechen den Bau ab |
| Tags | versioniert, auf Digest festgeschrieben; **kein `:latest`** |

**Tests:** Standardtests der Sprache; Datenbanktests gegen eine echte
PostgreSQL-Instanz im Container statt gegen Attrappen; Oberflächentests
einschließlich automatisierter Barrierefreiheitsprüfung; ein eigener Testsatz für
die Konnektor- und Terminal-Anbindung gegen den realen Testaufbau (NFA-QS-020).

---

## Was diese Wahl kostet

Ehrlich benannt:

1. **Der SOAP-Client ist Eigenarbeit.** Einmaliger Aufwand, dauerhaft zu pflegen,
   wenn sich die Schnittstelle ändert. Bewusst in Kauf genommen — siehe oben.
2. **HTMX ist weniger verbreitet als React.** Das Risiko ist gering, weil das
   Ergebnis gewöhnliches HTML ist: Es gibt keine Bindung an ein Rahmenwerk, aus
   der man sich später lösen müsste.
3. **Go ist eine bewusst kleine Sprache.** Manches ist ausführlicher zu schreiben
   als anderswo. Im Gegenzug ist der Code auch nach Jahren und für Dritte lesbar
   — bei einem Produkt mit dieser Lebensdauer eher Vorteil als Nachteil.
4. **Zwei Datenbanken** im Gesamtprodukt. Abgegrenzt, weil die Bausteine keine
   gemeinsame Datenschicht haben.

---

## Was zu tun ist, bevor der erste Code entsteht

1. **Schnittstellenbeschreibungen beschaffen.** Die veröffentlichten WSDLs für
   den Kartendienst des Konnektors vom gematik-Fachportal laden und in
   `docs/00-referenzen/` mit Versionsstand erfassen.
2. **Terminal-Schnittstelle klären.** Die Hersteller-Dokumentation der
   Management-/REST-Schnittstelle für die Modelle des Testaufbaus anfordern —
   sie war öffentlich nicht abrufbar (siehe ADR-0004).
3. **Technischer Durchstich.** Der in Abschnitt 10 des Architekturkonzepts als
   größtes Risiko benannte Punkt: die PEviM-Kette am realen Testaufbau
   nachweisen, bevor irgendetwas darauf gebaut wird.

---

## Brainpool-TLS — der Punkt, der die Sprachwahl umwirft

Vorgabe des POs vom 06.08.2026:
> **Wichtig: Wir müssen auch mit Brainpool-TLS klarkommen.**

### Was die Spezifikationen sagen (geprüft am Volltext)

| Befund | Quelle |
|---|---|
| Beim ephemeren ECDH-Schlüsselaustausch **MÜSSEN** die Kurven **P-256 und P-384** unterstützt werden. **brainpoolP256r1 und brainpoolP384r1 SOLLEN** unterstützt werden. | `[gemSpec_Krypt] V2.30.0`, `GS-A_4384-*` |
| Die TLS-Konfiguration der Clientsystem-Verbindung am Konnektor kennt ausdrücklich die Werte `[RSA, ECC-NIST, ECC-BRAINPOOL]`. | `[gemSpec_Kon] V5.25.0` |
| **Neue** Brainpool-Zertifikate dürfen nicht mehr angelegt werden; die **Weiternutzung bestehender ist ausdrücklich zulässig**. | `[gemSpec_Kon] V5.25.0` |
| Für künftige Fachdienste sollen NIST-Kurven statt Brainpool verwendet werden — **ausdrücklich begründet mit der „deutlich besseren Unterstützung der NIST-Kurvenparameter durch verschiedene Standard-Kryptographie-Softwarebibliotheken"**. | `[gemSpec_Krypt] V2.30.0` |
| Wo Brainpool nur schwer möglich ist, ist der Verzicht wegen der SOLL-Bestimmung zulässig. | `[gemSpec_Krypt] V2.30.0` |
| Die Chipkarten der TI verwenden für ihre ECC-Schlüssel durchgehend `brainpoolP256r1`; das ist in den Objektsystemen fixiert. | `[gemSpec_Krypt] V2.30.0` |

**Einordnung:** Formal ist Brainpool für TLS eine SOLL-, keine MUSS-Anforderung,
und die gematik bewegt sich davon weg. **Praktisch ist das kein Freibrief:** Ein
Konnektor im Feld kann auf `ECC-BRAINPOOL` konfiguriert sein, und
Bestandszertifikate bleiben zulässig. Trifft unser Produkt auf eine solche
Installation und kann Brainpool nicht, ist es dort schlicht nicht einsetzbar.
Die Vorgabe des POs ist damit berechtigt.

### Was das für die Kandidaten bedeutet

| Plattform | Brainpool in TLS | Beleg |
|---|---|---|
| **Go** | **nein.** `crypto/tls` unterstützt es nicht; die Maintainer lehnen die Aufnahme ab. Die verfügbare Drittbibliothek arbeitet **nicht in konstanter Zeit** und ist damit für sicherheitskritischen Einsatz ungeeignet. Go bringt eine eigene TLS-Umsetzung mit und profitiert nicht von OpenSSL. | golang-dev, pkg.go.dev |
| **Java + BouncyCastle** | **ja, der von der gematik selbst gegangene Weg.** Das Repository `gematik/ecc-brainpool-how-to` zeigt genau diese Konfiguration (BouncyCastleProvider + BouncyCastleJsseProvider, `jdk.tls.namedGroups`). Einschränkung: Für **TLS 1.3** mit Brainpool sind bei BouncyCastle Handshake-Fehler dokumentiert. | github.com/gematik/ecc-brainpool-how-to (**archiviert 22.04.2025**), bcgit/bc-java #2077 |
| **OpenSSL-basierte Plattformen** (Python, Node, Ruby, Rust, C/C++) | **ja**, OpenSSL beherrscht Brainpool. Für **TLS 1.3** zuverlässig erst ab **OpenSSL 3.2**; unter TLS 1.2 gibt es dokumentierte Stolperstellen. | openssl/openssl #21346, #24207 |
| **.NET** | **nicht belegt.** Auf Linux setzt .NET auf OpenSSL auf, was dafür spricht — eine belastbare Aussage konnte die Recherche jedoch **nicht** liefern. Als „geht" darf das nicht gelten. | — |

Bemerkenswert: Das einzige Repository, in dem die gematik selbst zeigt, wie
Brainpool-TLS geht, deckt **Java, Netty, Android und iOS** ab — **nicht** Go,
.NET, Python oder Node. Und es ist seit April 2025 **archiviert**. Das ist ein
Hinweis auf die Marktlage: Der Weg ist gangbar, aber schmal.

### Konsequenz

**Der Go-Vorschlag ist in dieser Form nicht haltbar.** Das war eine Lücke in
meiner Analyse: Ich habe SOAP als Kernfrage identifiziert und die
TLS-Kurvenunterstützung nicht geprüft. Sie ist die härtere Randbedingung, weil
sie sich nicht durch Eigenarbeit auflösen lässt — eine TLS-Implementierung
schreibt man nicht selbst.

Drei gangbare Wege:

**Weg 1 — Java oder Kotlin mit BouncyCastle.** Der von der gematik dokumentierte
Weg. Nachteil: Speicherbedarf gegenüber der 2-GB-Grenze für Praxishardware
(NFA-LEI-030); zu prüfen wäre, ob ein vorab übersetztes Programmabbild das löst.
Zusätzlich sind die BouncyCastle-Probleme mit TLS 1.3 zu bewerten.

**Weg 2 — eine OpenSSL-basierte Plattform.** Brainpool kommt dann von OpenSSL
statt aus der Sprache. Voraussetzung: OpenSSL 3.2 oder neuer im Image, was wir
kontrollieren, da wir das Image bauen.

**Weg 3 — Go behalten, TLS für die Konnektor-Strecke über einen
OpenSSL-basierten Vermittler.** Bewahrt alle Go-Vorteile, fügt aber eine
Komponente **an der sicherheitskritischsten Stelle** hinzu und macht das Argument
„ein einziges statisches Programm" zunichte. Nur zu erwägen, wenn Brainpool
ausschließlich auf einer klar abgegrenzten Strecke auftritt.

### Nächster Schritt: messen, nicht abwägen

Die Testumgebung steht. Die Frage ist damit in Minuten beantwortbar statt in
Stunden diskutierbar:

1. **Welche Kurven und Cipher-Suites bietet der KoCo-Konnektor tatsächlich an?**
   Ein Verbindungsaufbau mit einem Standardwerkzeug gegen den Konnektor zeigt die
   angebotene Kurvenliste und die Konfiguration von `TlsKeyCrypt/Algorithm`.
2. **Dasselbe für die Managementschnittstelle der Kartenterminals.**
3. **Lässt sich der KoCo-Konnektor auf `ECC-NIST` konfigurieren** — und ist das in
   Kundeninstallationen realistisch, oder muss mit `ECC-BRAINPOOL` gerechnet
   werden?

Erst danach ist entscheidbar, ob Brainpool auf **allen** Strecken, auf **einer**
oder auf **keiner** gebraucht wird. Davon hängt ab, ob Weg 1, 2 oder 3 richtig
ist. Eine Sprachfestlegung vor dieser Messung wäre genau das Raten, das in diesem
Projekt ausgeschlossen ist.

**Unberührt bleiben:** Oberfläche vom Server, Datenhaltung, Agentenprotokoll,
Bauumgebung, Kapselung von Terminal- und Konnektor-Anbindung, der selbst
geschriebene SOAP-Client. Diese Festlegungen sind sprachunabhängig und behalten
ihre Begründung.

---

## Externe Vorgaben

Keine. Der PO hat am 06.08.2026 festgelegt: **Die Entwicklung schlägt vor, der
Product Owner gibt nach Prüfung frei.** Es bestehen keine Bindungen an einen
Dienstleister, eine bestehende Systemlandschaft oder vorhandene Kenntnisstände.

Damit ist die Wahl ausschließlich fachlich zu begründen — was oben geschieht.
Die Verantwortung für die Tragfähigkeit über die Lebensdauer des Produkts liegt
bei der Entwicklung.
