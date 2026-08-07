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

---

# Nachtrag 07.08.2026 — zweite Sitzung

## Wesentlicher Fortschritt: Die Server-Seite funktioniert nachweislich

Ein **echter Fehler gefunden und behoben**: Der Simulator legte seinen privaten
Schlüssel mit einem **leeren Passwort** in den KeyStore. Der KeyManager gab ihn
daraufhin nicht heraus, und der Server meldete `no selectable cipher suite` —
ein Fehler, der nach einem Problem mit den Cipher-Suites aussieht und in
Wahrheit eins der Schlüsselverwaltung ist. Mit einem echten Passwort findet der
KeyManager den Schlüssel (`Serverschluessel fuer ECDHE_ECDSA: server`).

**Nachweis, dass der Simulator korrekt arbeitet:**
```
$ openssl s_client -connect localhost:8443 -CAfile ca.pem -groups brainpoolP256r1 -tls1_2
  Verification: OK
  Protocol: TLSv1.2
```
Ein OpenSSL-Client handelt erfolgreich über **brainpoolP256r1** mit dem
Simulator. Damit ist belegt: Brainpool-TLS funktioniert in diesem Aufbau, und
die Stack-Entscheidung aus ADR-0006 trägt.

## Was noch offen ist — eng eingegrenzt

Der **Agent als Client** kommt weiterhin nicht durch:
```
WARNING: 'jdk.tls.namedGroups' contains disabled NamedGroup: brainpoolP256r1
SEVERE:  'jdk.tls.namedGroups' contained no usable NamedGroup values
```

Die Brainpool-Kurven sind im Agenten gesperrt, im Simulator nicht — **obwohl
beide dieselbe Sicherheitsrichtlinie per `-Djava.security.properties` bekommen**.
Genau dieser Unterschied ist der Schlüssel zur Lösung.

Zwei Hypothesen wurden geprüft und **widerlegt**:
1. ~~Die Freischaltung zur Laufzeit überschreibt die Datei-Werte~~ — eine
   Schutzabfrage änderte nichts; die erwartete Meldung erschien nicht, also
   greift die Datei im Agenten gar nicht erst.
2. ~~Die Cipher-Suite-Liste ist zu eng~~ — ein OpenSSL-Client mit 46 angebotenen
   Suites scheiterte am selben Punkt, ein Client mit Brainpool-Gruppe kam durch.

**Nächster Schritt:** Prüfen, warum die Richtlinie im Agenten nicht greift.
Konkret: im Agenten beim Start `Security.getProperty("jdk.disabled.namedCurves")`
und `jdk.tls.disabledAlgorithms` ausgeben und mit dem Simulator vergleichen. Der
Unterschied zwischen beiden Prozessen ist die Antwort — beide bekommen dieselbe
Datei, verhalten sich aber verschieden.

Ein Verdacht bleibt: Der Simulator lädt BouncyCastle über
`BrainpoolZertifikate` (statischer Block) **vor** dem TLS-Aufbau, der Agent erst
in `TlsClient`. Möglicherweise ist nicht die Richtlinie das Problem, sondern
erneut die Reihenfolge der Provider-Registrierung.

## Bewertung

Der Durchstich ist **nicht fertig**, aber deutlich näher: Von „beide Seiten
scheitern, Ursache unklar" auf „Server nachweislich in Ordnung, Client-Problem
auf eine Frage eingegrenzt". Der behobene KeyStore-Fehler war zudem ein echter
Fund, der sonst später im Produkt aufgetreten wäre.

---

# 07.08.2026 — Durchstich erbracht, Abarbeitung läuft

## Erledigt

| Anforderung | Nachweis |
|---|---|
| **Durchstich PEviM** | Agent schaltet SMC-B selbsttätig frei, über Brainpool-TLS. Null Handshake-Fehler. |
| **FA-PIN-030** | **Am laufenden System nachgewiesen:** falsche PIN → genau 1 Versuch bei 9 Gelegenheiten, `LeftTries` 3→2, Automatik angehalten, Meldung in Klartext. |
| **NFA-BET-010** | PINs kommen über Umgebungsvariablen (`PIN_<kartenhandle>`), nicht mehr aus dem Code. Werte werden nie ausgegeben. |
| 10 Modultests | grün |

## Der Befund, der Tage gekostet hat

`new BouncyCastleJsseProvider()` **ohne Argument** nutzt den JDK-Standardprovider
als Krypto-Unterbau — und der kennt Brainpool nicht. Die Folge ist die Meldung
`'jdk.tls.namedGroups' contains disabled NamedGroup: brainpoolP256r1`, obwohl die
Sicherheitsrichtlinie die Kurven längst freigegeben hat. Richtig ist
`new BouncyCastleJsseProvider(bcProvider)`.

Die Fehlermeldung weist in die völlig falsche Richtung. Das gehört in die
Umsetzungsbeschreibung, sonst kostet es jeden, der das nachbaut, dieselbe Zeit.

---

# Iteration 2 — Systemnachweise

Neu: **`pruefungen/systemnachweise.sh`** prüft den laufenden Stack gegen die
Akzeptanzkriterien. Modultests prüfen Logik isoliert; dieses Skript prüft das
Zusammenspiel. Nach CLAUDE.md gilt ein Kriterium erst als erfüllt, wenn es am
laufenden System nachgewiesen wurde — dieses Skript macht den Nachweis
wiederholbar statt einmalig.

## Ergebnis: 10 von 10 bestanden

| Anforderung | Nachweis |
|---|---|
| Durchstich | SMC-B wird selbsttätig freigeschaltet, 0 TLS-Fehler |
| **FA-PIN-030** | genau 1 Versuch bei ~10 Gelegenheiten; Restversuche in Klartext |
| **FA-UEB-050** | **0** Versuche während 30 s Firmware-Update (~7 Gelegenheiten), danach Wiederherstellung |
| **FA-PIN-050** | 0 Versuche bei aktivem Not-Aus, Wiederanlauf nach Aufhebung, protokolliert |
| **FA-PRO-020** | keine PIN in Agent-Ausgabe oder Fachprotokoll |

## Ein verworfener Scheinnachweis

Der erste Anlauf für FA-UEB-050 „bestand" — war aber wertlos: Das simulierte
Update dauerte 2 Sekunden, das Prüfintervall 4. Der Agent hatte den kritischen
Moment mit hoher Wahrscheinlichkeit gar nicht getroffen.

Behoben durch eine konfigurierbare Update-Dauer im Simulator. Jetzt läuft das
Update 30 Sekunden bei 4 Sekunden Intervall — der Agent prüft **zwangsläufig**
rund sieben Mal währenddessen. Erst damit ist das Ergebnis eine Aussage und
kein Zufall.

Das ist die Art Fehler, die einen Test wertlos macht, ohne dass er rot wird.

---

# Iteration 3 — Persistenz

Die Zentrale hielt alles im Arbeitsspeicher. Ein Fachprotokoll, das einen
Neustart nicht übersteht, ist als Nachweis wertlos — und Neustarts sind durch
Produktupdates der Regelfall, nicht die Ausnahme.

## Umgesetzt

- **PostgreSQL** in der Zentrale (ADR-0006), im Compose-Stack mit eigenem Volume
- **Migrationen laufen automatisch und idempotent** (FA-INS-010) — der Betreiber
  hat kein IT-Personal, das eine Datenbank von Hand einrichtet
- **Indizes von Anfang an** auf Zeit, Einrichtung, Gerät, Ereignisart, Vorgang.
  FA-PRO-030 verlangt < 2 s bei 5 Millionen Zeilen; ohne Indizes unerreichbar.
- **Unveränderlichkeit auf Datenbankebene**: Ein Trigger weist Änderungen und
  Löschungen einzelner Einträge ab. Die Rotation nach Speicherbudget ist der
  einzige zulässige Löschweg und muss sich ausdrücklich dafür kennzeichnen.
- **Fehlende Zugangsdaten** führen zu einer verständlichen Meldung statt zu einem
  Absturz (NFA-BET-010)

## Bewusst NICHT persistiert: der Gerätezustand

Er ist flüchtig und wird vom Agenten binnen eines Prüfintervalls neu gemeldet.
Ihn zu speichern hieße, nach einem Neustart einen womöglich veralteten Zustand
anzuzeigen — schlechter als ehrlich „noch unbekannt".

## Systemnachweise: 13 von 13

Neu hinzugekommen: Protokoll überlebt den **Containeraustausch** (nicht nur den
Neustart), Migrationen sind idempotent, Fachprotokoll ist weder änderbar noch
löschbar.

## Zwei Testfehler, die nichts mit dem Produkt zu tun hatten

1. **Feste Wartezeiten** machten das Skript wetterfühlig — es wurde rot, weil die
   Datenbank beim ersten Start länger braucht. Ersetzt durch aktives Warten.
2. **Zu unscharfe Abbruchbedingung**: Die Warteschleife brach schon bei
   „Karte *nicht* freigeschaltet — starte Verifikation" ab, also *vor* dem
   Ergebnis. Sie wartet jetzt auf den abgeschlossenen Zyklus.

Beides ist die Sorte Fehler, die Vertrauen in eine Testsuite zerstört: Sie wird
rot, ohne dass etwas kaputt ist — und irgendwann schaut niemand mehr hin.
