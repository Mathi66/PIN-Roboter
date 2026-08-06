# Prototyp — Arbeitsstand vom 07.08.2026, früh

**Auftrag (PO):** Über Nacht autonom einen lauffähigen Prototypen bauen.

---

## Ehrlicher Stand: der Durchstich läuft noch NICHT

Ich habe den Zuschnitt nicht vollständig geschafft. Was fehlt, ist genau der
letzte Schritt — und er ist gut eingegrenzt.

| | Stand |
|---|---|
| Projektstruktur, Maven-Aufbau, Docker | **fertig** |
| Gemeinsame Modelle (PIN-Status, Ampel, Ereignisarten) | **fertig** |
| Simulator: Brainpool-PKI, SOAP, PEviM, Fehlversuchszähler | **fertig, startet** |
| Agent: Sicherungslogik, Clients, Verifikationsablauf | **fertig, startet** |
| Zentrale: Oberfläche, Fachprotokoll | **fertig, startet** |
| **10 Tests, darunter FA-PIN-030** | **grün** |
| **TLS-Handshake Agent ↔ Simulator** | **scheitert — siehe unten** |
| Nachweis des vollständigen PEviM-Ablaufs | offen, blockiert durch TLS |

Alle drei Bausteine starten sauber. Die Zentrale ist unter
`http://localhost:8090` bedienbar, zeigt aber keine Geräte, weil der Agent
den Konnektor nicht erreicht.

---

## Der offene Punkt, genau eingegrenzt

**Symptom (Simulator-Protokoll):**
```
handshake_failure(40); found no selectable cipher suite among the 3 offered:
  TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384
  TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256
```

Der Client bietet zwei ECDSA-Suites an, der Server hat ein
ECDSA-Zertifikat auf `brainpoolP256r1` — und wählt trotzdem keine aus.

**Was bereits gefunden und behoben ist** (beides nur durch Ausführen sichtbar,
in keiner Dokumentation beschrieben):

1. **Java sperrt Brainpool in der Standard-Sicherheitsrichtlinie.**
   `jdk.tls.disabledAlgorithms` enthält `include jdk.disabled.namedCurves`,
   dort stehen die Brainpool-Kurven. BouncyCastle meldet dann
   `contained no usable NamedGroup values`. → `BrainpoolFreischaltung`
2. **Die Reihenfolge ist entscheidend.** Die Freischaltung muss vor jeder
   Provider-Nutzung laufen; `jdk.tls.namedGroups` dagegen erst *nach* der
   Registrierung von BouncyCastle — sonst lehnt das Standard-JDK die Liste ab.

**Was noch offen ist:** Die Server-Seite (`HttpsServer` mit
`HttpsConfigurator`) übernimmt die gesetzten Parameter offenbar nicht
vollständig. Nächster Schritt: prüfen, ob `getDefaultSSLParameters()` im
Konfigurator die Brainpool-Gruppen überhaupt enthält, und die Gruppen dort
explizit über `SSLParameters.setNamedGroups(...)` setzen statt nur die
Cipher-Suites.

**Einordnung:** Das ist ein Konfigurationsdetail des Simulators, kein Befund
gegen die Stack-Entscheidung. Die Vorabmessung vom 06.08. hat belegt, dass
Java mit BouncyCastle Brainpool-TLS beherrscht — dort war BouncyCastle auf
*beiden* Seiten im Client-Modus. Hier ist die Server-Seite neu.

---

## Selbst getroffene Entscheidungen — bitte prüfen

| Entscheidung | Begründung |
|---|---|
| **Java 21** statt Kotlin | ADR-0006, Expertise des Teams |
| **Javalin** statt Spring Boot | Der Agent muss ≤ 512 MB bleiben (NFA-LEI-030) |
| **SOAP von Hand** statt erzeugt | vier Operationen; der Aufruf, der über eine PIN entscheidet, bleibt lesbar |
| **`com.sun.net.httpserver`** im Simulator | keine zusätzliche Abhängigkeit; rückblickend die Ursache des offenen TLS-Problems — mit Javalin/Jetty wäre die TLS-Konfiguration möglicherweise geradliniger |
| **HTMX eingebettet** statt aus dem Netz | keine externen Abhängigkeiten zur Laufzeit |

---

## Was der Prototyp bereits zeigt

- **Die Sicherungslogik ist fertig und geprüft.** Zehn Tests, darunter der vor
  jeder Auslieferung verpflichtende Nachweis aus FA-PIN-030: Ein gesunkener
  Fehlversuchszähler hält die Automatik an, statt zu wiederholen.
- **Der Simulator bildet die unbequemen Fälle ab**, nicht nur den Erfolgsfall:
  ein Gerät mit PEviM aktiv, eines mit PEviM vorhanden aber nicht aktiviert,
  eines ganz ohne PEviM, dazu Fehlversuchszähler, Sperrung, Transport-PIN und
  Firmware-Update mit Verlust des verifizierten Zustands.
- **Die Oberfläche ist angelegt** nach den vier Sichten aus ADR-0005, mit
  Hell-/Dunkelmodus, Tastaturbedienung, Statusanzeige nie allein über Farbe.

## Nächste Schritte

1. TLS-Server-Parameter im Simulator korrigieren (oben beschrieben)
2. Vollständigen PEviM-Ablauf nachweisen
3. `docker compose up --build` als Ganzes prüfen
4. Fehlerfall-Tests gegen den laufenden Stack: falsche PIN, Terminal weg,
   Firmware-Update während der Verifikation
