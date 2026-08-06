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
| Go, .NET, Java | noch offen — über Docker zu prüfen | — |

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

## Offen

1. **Node.js unter TLS 1.2 wiederholen** — der Lauf lieferte keine Ausgabe.
2. **Go, .NET und Java über Docker prüfen** — für Go wird der Fehlschlag erwartet
   und ist zu belegen, nicht anzunehmen.
3. **Welche TLS-Version spricht der KoCo-Konnektor?** Falls er TLS 1.3 verlangt
   *und* Brainpool-Zertifikate verwendet, ist das eine Kombination, die nach
   dieser Messung kaum ein Wrapper beherrscht. Diese Frage ist an der realen
   Umgebung zu klären und ist für die Sprachwahl entscheidender als jede weitere
   Kandidatenprüfung.

## Reproduzierbarkeit

Die Testzertifikate wurden mit OpenSSL erzeugt und liegen **nicht** im
Repository (private Schlüssel, siehe `.gitignore`). Das Erzeugungs- und
Prüfverfahren wird als Skript Bestandteil des Simulators aus
[ADR-0007](../adr/0007-simulierte-endpunkte-und-teststrategie.md).
