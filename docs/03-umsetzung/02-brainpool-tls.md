# Umsetzung: Brainpool-TLS

**Grundlage:** ADR-0006, [Messbericht](../00-referenzen/messung-brainpool-tls.md)

Diese Datei ist die wichtigste dieses Verzeichnisses. Die hier beschriebenen
Stolpersteine haben in der Entwicklung **mehrere Stunden** gekostet, und keiner
davon steht in einer Dokumentation. Wer das nachbaut, verliert dieselbe Zeit —
oder liest hier.

---

## Warum Brainpool überhaupt

Die Geräteidentitäten der TI stammen von Chipkarten: der Konnektor authentisiert
sich mit einem Zertifikat der **gSMC-K**, das Kartenterminal mit `C.SMKT.AUT`
von der **gSMC-KT**. Beide sind auf `brainpoolP256r1` festgelegt — fixiert in den
Objektsystemen der Karten (`[gemSpec_Krypt]`).

**Der entscheidende Punkt:** Der *Schlüsselaustausch* lässt sich am Konnektor auf
NIST-Kurven umstellen. Die *Zertifikate* nicht. Wer nur ECDHE umstellt, muss
weiterhin Brainpool-Signaturen prüfen können. Damit ist Brainpool eine
MUSS-Anforderung, obwohl `[gemSpec_Krypt] GS-A_4384` es formal nur als SOLL führt.

## Die vier Stolpersteine

### 1. Go kann es nicht — und zwar fundamental

Nicht die TLS-Gruppenaushandlung scheitert, sondern schon das Einlesen:
`x509.CertPool.AppendCertsFromPEM` kann ein Zertifikat mit
brainpoolP256r1-Schlüssel **nicht parsen**. Es kommt gar nicht zu einem
Handshake. Ein vorgelagerter TLS-Vermittler löst das nicht, sobald Zertifikate
der TI-PKI im Spiel sind.

### 2. Das Standard-JDK genügt nicht

`jdk.tls.namedGroups(brainpoolP256r1) contains no supported named groups`.
Erst BouncyCastle bringt die Kurven mit.

### 3. Java sperrt Brainpool in der Standard-Sicherheitsrichtlinie

`jdk.tls.disabledAlgorithms` und `jdk.certpath.disabledAlgorithms` enthalten
`include jdk.disabled.namedCurves`, und dort stehen die Brainpool-Kurven.
BouncyCastle meldet dann:

```
WARNING: 'jdk.tls.namedGroups' contains disabled NamedGroup: brainpoolP256r1
SEVERE:  'jdk.tls.namedGroups' contained no usable NamedGroup values
```

**Lösung:** eine eigene Sicherheitsrichtlinie, geladen beim JVM-Start:

```
java -Djava.security.properties=/app/brainpool.security -jar anwendung.jar
```

Datei: [`docker/brainpool.security`](../../docker/brainpool.security).

**Zur Laufzeit gesetzte Security-Properties kommen zu spät** — der Provider hat
die Kurvenliste dann bereits gelesen. Das war Stolperstein 3a und kostete einen
eigenen Anlauf.

### 4. BCJSSE muss ausdrücklich auf BouncyCastle verwiesen werden

**Das war der teuerste Fehler.**

```java
new BouncyCastleJsseProvider()        // FALSCH — nutzt den JDK-Standardprovider
new BouncyCastleJsseProvider(bc)      // richtig
```

Ohne Argument nutzt BCJSSE den JDK-Standardprovider als Krypto-Unterbau, und der
kennt Brainpool nicht. Die Fehlermeldung lautet dann wieder
`contains disabled NamedGroup` — **obwohl die Sicherheitsrichtlinie die Kurven
längst freigegeben hat.** Sie weist damit in die völlig falsche Richtung: Man
sucht bei der Richtlinie und findet nichts, weil das Problem beim Provider liegt.

## Die richtige Reihenfolge

```java
1. Sicherheitsrichtlinie beim JVM-Start   -Djava.security.properties=…
2. BouncyCastleProvider registrieren      Security.addProvider(bc)
3. BCJSSE MIT Verweis registrieren        new BouncyCastleJsseProvider(bc)
4. ERST JETZT jdk.tls.namedGroups setzen  System.setProperty(…)
5. SSLContext.getInstance("TLSv1.2", "BCJSSE")
```

Wird Schritt 4 vor Schritt 2/3 ausgeführt, lehnt das Standard-JDK die Liste ab.

## Cipher-Suites ausdrücklich einschränken

Ohne Einschränkung einigten sich Client und Server im Test auf
`TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256` — eine Suite, die
`[gemSpec_Krypt]` **nicht zulässt**. Erlaubt sind nur `(RSA, AES-CBC)` und
`(ECC, AES-GCM)`. Die Vorgabe der Bibliothek genügt also nicht.

Zusätzlich `A_22457`: Gegenüber dem Kartenterminal dürfen bei beidseitiger
Authentisierung nur Suites angeboten werden, für deren Authentisierungsverfahren
man selbst Schlüsselmaterial besitzt.

## TLS 1.2, nicht 1.3

`GS-A_4385` macht TLS 1.2 verpflichtend, `A_18467` führt TLS 1.3 als
Kann-Bestimmung. Unter TLS 1.3 scheitert Brainpool in den geprüften
Bibliotheken. Also 1.2 — zulässig und funktionierend.

## Ein Nebenbefund zur Fehlersuche

Ein Zwischenlauf scheiterte an einer fehlenden `keyUsage`-Erweiterung im
Test-CA. Die Fehlermeldung sah aus wie ein Kurvenproblem. Daraus folgt eine
Anforderung ans Produkt: **Zertifikatsfehler und Kurvenfehler müssen
unterscheidbar gemeldet werden** — sonst sucht der Betreiber an der falschen
Stelle, so wie ich es getan habe.
