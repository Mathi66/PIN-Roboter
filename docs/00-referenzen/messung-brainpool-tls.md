# Messung: Brainpool-TLS-Tauglichkeit

- **Datum:** 06.08.2026
- **Zweck:** Grundlage für die offene Sprachwahl in [ADR-0006](../adr/0006-technologie-stack.md)
- **Methode:** Eigener TLS-Server mit **brainpoolP256r1**-Zertifikat und
  ausschließlich Brainpool-Gruppen; Kandidaten als Client dagegen.

## Aufbau

| | |
|---|---|
| Server | `openssl s_server`, OpenSSL **3.6.3** (Homebrew), `-groups brainpoolP256r1` |
| Zertifikatskette | eigene CA auf `brainpoolP256r1`, Serverzertifikat `ecdsa-with-SHA256` auf `brainpoolP256r1` |
| Prüfung | Zertifikatskette wird clientseitig **vollständig geprüft** (kein Abschalten) |

## Ergebnisse

| Client | TLS 1.2 | TLS 1.3 |
|---|---|---|
| **OpenSSL 3.6.3** (Referenz) | ✅ | ✅ |
| **Python 3.14.6** (`ssl`, OpenSSL 3.6.3) | ✅ `ECDHE-ECDSA-AES256-GCM-SHA384` | ❌ `NO_SUITABLE_GROUPS` |
| **Node.js 25.9.0** (OpenSSL 3.6.3) | ⚠️ nicht abschließend (Testlauf ohne Ausgabe, zu wiederholen) | ❌ `ERR_SSL_NO_SUITABLE_GROUPS` |
| **Java 21, Standard-JDK** (Docker `eclipse-temurin:21-jdk`) | ❌ `jdk.tls.namedGroups … contains no supported named groups` | — |
| **Java 21 + BouncyCastle 1.83** (`bcprov` + `bctls`, BCJSSE) | ✅ Handshake erfolgreich | — |
| **Go 1.26** (Standardbibliothek, Docker) | ❌ **scheitert bereits beim Laden des CA-Zertifikats** | ❌ |
| .NET | noch offen | — |

## Befunde

**1. Brainpool funktioniert — aber praktisch nur unter TLS 1.2.**
Unter TLS 1.3 scheitern Python und Node mit `NO_SUITABLE_GROUPS`, obwohl sie
dieselbe OpenSSL-Version verwenden, mit der der Referenzclient erfolgreich ist.
Das deckt sich mit der Recherchelage: Brainpool wurde für TLS 1.3 von der IETF
zurückgestuft; RFC 8734 reicht es nach, wird von den Sprach-Wrappern aber nicht
durchgereicht.

**2. Die erreichte Cipher-Suite entspricht genau der Vorgabe.**
`ECDHE-ECDSA-AES256-GCM-SHA384` ist die Kombination ECC + AES-GCM, die
`[gemSpec_Krypt]` als einzige ECC-Variante zulässt (`[gemSpec_KT] A_24065`,
Hinweis 3: nur `(RSA, AES-CBC)` und `(ECC, AES-GCM)`).

**3. „Nutzt OpenSSL" genügt nicht als Auswahlkriterium.**
Der erste Testlauf scheiterte in beiden Sprachen, obwohl OpenSSL Brainpool
beherrscht — weil die Standardkonfiguration der Sprache diese Gruppen nicht
**anbietet**. Entscheidend ist, ob die Sprache eine Schnittstelle bereitstellt,
um die Kurvenliste zu setzen (Python: `set_ecdh_curve`, Node: `ecdhCurve`).
Eine Plattform ohne diese Möglichkeit ist untauglich, auch wenn OpenSSL darunter
liegt.

**4. Ein Nebenbefund zur Sorgfalt.** Ein Zwischenlauf scheiterte an einer
fehlenden `keyUsage`-Erweiterung im Test-CA — nicht an Brainpool. Ohne genaues
Lesen der Fehlermeldung wäre daraus ein falscher Ausschluss geworden. Für die
spätere Fehlerbehandlung im Produkt heißt das: **Zertifikatsfehler und
Kurvenfehler müssen unterscheidbar gemeldet werden**, sonst sucht der Betreiber
an der falschen Stelle.

### Der Go-Ausschluss ist fundamentaler als erwartet

Go scheitert **nicht** an der TLS-Gruppenaushandlung, sondern schon eine Ebene
darunter: `x509.CertPool.AppendCertsFromPEM` kann ein Zertifikat mit
brainpoolP256r1-Schlüssel **nicht einlesen**. Es kommt gar nicht zu einem
Handshake. Damit ist Go für dieses Produkt endgültig ausgeschlossen — und zwar
belegt, nicht vermutet. Auch ein vorgelagerter TLS-Vermittler (Weg 3 in
ADR-0006) würde das nur verlagern, nicht lösen, sobald Zertifikate der TI-PKI im
Spiel sind.

### Das Standard-JDK genügt nicht — BouncyCastle ist der Unterschied

Bemerkenswert: Java **ohne** BouncyCastle scheitert mit derselben Klarheit wie
Go bei den Gruppen. Erst `bcprov` + `bctls` mit dem BCJSSE-Provider und
`jdk.tls.namedGroups=brainpoolP256r1,brainpoolP384r1` bringen den Handshake
zustande. Das bestätigt exakt die Konfiguration aus dem (archivierten)
gematik-Repository `ecc-brainpool-how-to`.

**Praxisbeleg des POs (06.08.2026):** *Im Konnektor ist auch Brainpool und JDK
verbaut.* Nachgereicht: **Konnektor und HSK verwenden BouncyCastle.**

Damit läuft auf der Gegenseite unserer wichtigsten Verbindung **exakt die
Bibliothek, mit der dieser Test erfolgreich war** — dieselbe Umsetzung von
Brainpool, denselben Kurvenparametern, derselben Auslegung von RFC 5639
folgend. Interoperabilitätsprobleme entstehen erfahrungsgemäß nicht dort, wo
zwei Seiten dieselbe Bibliothek verwenden, sondern an den Rändern
unterschiedlicher Umsetzungen.

Das macht Java mit BouncyCastle vom „gangbaren Weg" zum **Weg mit dem geringsten
Interoperabilitätsrisiko**. Es ist kein zwingender Grund — eine OpenSSL-basierte
Plattform funktioniert nachweislich ebenfalls —, aber es ist das stärkste
einzelne Argument, das in dieser Messreihe aufgetaucht ist.

**Hinweis zur Suite-Auswahl:** Der Testlauf einigte sich auf
`TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256`. Diese Suite ist nach
`[gemSpec_Krypt]` **nicht zulässig** — erlaubt sind nur `(RSA, AES-CBC)` und
`(ECC, AES-GCM)`. Für das Produkt ist die angebotene Suite-Liste daher
ausdrücklich einzuschränken; die Vorgabe der Bibliothek genügt nicht.

## Verifiziert: TLS 1.2 ist Pflicht, TLS 1.3 ist optional

Nachtrag 06.08.2026, geprüft am Volltext `[gemSpec_Krypt] V2.30.0`:

| ID | Wortlaut (gekürzt) |
|---|---|
| **`GS-A_4385`** | Alle Produkttypen, die Übertragungen mittels TLS durchführen, **MÜSSEN die TLS-Version 1.2 unterstützen**. |
| **`A_18467`** | Alle Produkttypen **KÖNNEN** die TLS-Version 1.3 unterstützen, falls sie dabei nur nach `[BSI-TR-02102-2]` empfohlene Konfigurationen verwenden und mindestens `TLS_AES_128_GCM_SHA256` unterstützen. |
| `A_18464` | TLS 1.1 **DÜRFEN NICHT** unterstützt werden. |
| `GS-A_4387` | TLS 1.0 **DÜRFEN NICHT** unterstützt werden. |
| `GS-A_5035` | SSL **DARF NICHT** unterstützt werden. |

**Damit ist die Blockade aufgelöst.** TLS 1.2 ist für jede TI-Komponente
verpflichtend, TLS 1.3 ausdrücklich nur eine Kann-Bestimmung. Unsere Seite darf
die Verbindung also auf **TLS 1.2 festlegen**, und genau dort funktioniert
Brainpool nachweislich (Messung oben).

Die TLS-1.3-Schwäche der Sprach-Wrapper ist damit **kein Ausschlusskriterium
mehr**. Sie bleibt ein Hinweis für später: Sollte die TI Brainpool eines Tages
verbindlich unter TLS 1.3 verlangen, wäre das erneut zu bewerten — derzeit
bewegt sich die gematik jedoch von Brainpool weg, nicht darauf zu.

### Weitere relevante Vorgabe für die Umsetzung

`A_22457`: Ein TLS-Client gegenüber dem eHealth-Kartenterminal **darf bei
beidseitig authentisierten Verbindungen keine Cipher-Suites mit
Authentisierungsalgorithmen anbieten, für die er nicht selbst Schlüsselmaterial
und Zertifikat besitzt.** Praktisch: Die angebotene Suite-Liste muss zu unserem
eigenen Client-Zertifikat passen und darf nicht pauschal alles anbieten.

## Offen

1. **Node.js unter TLS 1.2 wiederholen** — der Lauf lieferte keine Ausgabe.
2. **.NET prüfen** — als einziger Kandidat noch ohne Messwert.
3. **Cipher-Suite-Liste festlegen und erzwingen**, passend zu `[gemSpec_Krypt]`
   und `A_22457`.
4. **Welche TLS-Version spricht der KoCo-Konnektor?** Falls er TLS 1.3 verlangt
   *und* Brainpool-Zertifikate verwendet, ist das eine Kombination, die nach
   dieser Messung kaum ein Wrapper beherrscht. Diese Frage ist an der realen
   Umgebung zu klären und ist für die Sprachwahl entscheidender als jede weitere
   Kandidatenprüfung.

## Reproduzierbarkeit

Die Testzertifikate wurden mit OpenSSL erzeugt und liegen **nicht** im
Repository (private Schlüssel, siehe `.gitignore`). Das Erzeugungs- und
Prüfverfahren wird als Skript Bestandteil des Simulators aus
[ADR-0007](../adr/0007-simulierte-endpunkte-und-teststrategie.md).
