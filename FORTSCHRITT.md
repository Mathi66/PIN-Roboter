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

---

# Iteration 4 — Anmeldung mit zweitem Faktor

Bis hierher konnte jeder im Netz den Not-Aus drücken. Das war der größte offene
Punkt.

## Umgesetzt

| | |
|---|---|
| **TOTP nach RFC 6238** | selbst umgesetzt, **gegen die offiziellen Testvektoren des Standards geprüft** |
| **Argon2id** für Passwörter | Parameter nach OWASP-Empfehlung, selbstbeschreibendes Format |
| **Benutzer, Rollen, Sitzungen** | in der Datenbank, Migrationen 3 und 4 |
| **Kontosperre** | nach 5 Fehlversuchen für 15 Minuten |

## Sicherheitsentscheidungen, die im Code begründet sind

- **Der Algorithmus ist selbst umgesetzt, nicht eingebunden.** Fünfzig Zeilen,
  und der Standard liefert Testvektoren, gegen die sich das beweisen lässt. Eine
  Bibliothek wäre eine ungeprüfte Abhängigkeit mehr an sicherheitskritischer
  Stelle.
- **Vergleiche laufen in konstanter Zeit.** Ein `equals` verrät über die
  Laufzeit, wie viele Stellen stimmen — bei sechs Ziffern ausnutzbar.
- **Unbekannter Benutzer und falsches Passwort ergeben dieselbe Meldung.**
  Unterschiedliche Meldungen verraten, welche Konten existieren. Wird getestet.
- **Ein verbrauchtes Einmalkennwort gilt kein zweites Mal.** Sonst nützt ein
  abgefangener Code bis zu 90 Sekunden lang.
- **Der letzte Administrator ist geschützt** — sonst sperrt sich eine Praxis
  versehentlich selbst aus.
- **Keine Zeichenklassen-Regeln, nur Länge.** Erzwungene Sonderzeichen führen zu
  `Passwort1!` und zu Zetteln am Monitor; das BSI empfiehlt seit 2020 Länge.

## Tests: 35 grün

15 im gemeinsamen Modul (TOTP, Passwort), 10 Sicherungsprüfung, 10
Benutzerverwaltung **gegen eine echte PostgreSQL-Datenbank**.

## Ein Testfehler mit Aussagekraft

Neun Tests schlugen zunächst fehl, weil das gewählte Testpasswort
`EinLangesPruefPasswort` die Zeichenfolge „passwort" enthält — die
Stärkeprüfung hat es zu Recht abgelehnt. Der Schutz funktionierte; der Test war
schlecht gewählt.

## Noch offen in diesem Block

Die **Oberflächen-Integration** (Anmeldemaske, Onboarding-Assistent,
Benutzerverwaltung als Seite) steht noch aus. Die Fachlogik ist fertig und
geprüft, die Oberfläche ist noch ungeschützt.

---

# Iteration 5 — Die Oberfläche ist geschützt

Bis hierher konnte jeder im Netz den Not-Aus drücken.

## Umgesetzt

- **Zugriffsschutz an einer Stelle**, als Sperre mit Ausnahmeliste. Eine neue
  Seite ist damit von sich aus geschützt — andersherum wäre sie offen, bis
  jemand daran denkt. Dasselbe Prinzip wie bei der Mandantentrennung.
- **Anmeldemaske**, **Ersteinrichtungs-Assistent**, **TOTP-Einrichtung** mit
  Schlüssel in Vierergruppen (er wird abgetippt) und `otpauth://`-Link
- **Sitzungscookie** mit `HttpOnly` und `SameSite=Strict`
- Anmeldungen, Fehlversuche und Kontoanlagen im Fachprotokoll — **nie das Passwort**

## Zwei echte Befunde

1. **HTMX-Antworten lieferten HTTP 200 ohne Sitzung.** Der Inhalt war leer, aber
   ein 200 sagt „in Ordnung" und macht jeden Test darauf blind. Jetzt 401 mit
   `HX-Redirect`.
2. **Der Zugriffsschutz sperrte den Standort-Agenten aus.** Er fragt
   `/api/notaus` ab, hat aber keine Sitzung — der Not-Aus erreichte ihn nicht
   mehr. Die Agent-Pfade sind jetzt ausgenommen, **mit einem ausdrücklichen
   Warnhinweis im Code**: Sie sind derzeit ungeschützt. Die richtige Lösung ist
   die Agent-Registrierung (FA-AGT-010/020) — das ist die nächste
   Sicherheitsaufgabe.

## Drei Fehler im Prüfskript, keiner davon im Produkt

- `curl -f` schlägt **nicht bei Umleitungen** an. Die Kontrolle, ob die Anmeldung
  geklappt hat, war dadurch blind und meldete Erfolg bei einem 302.
- Das Skript meldete sich mehrfach an und scheiterte am **Einmalcode-Schutz** —
  der also nachweislich wirkt. Es prüft jetzt zuerst die vorhandene Sitzung.
- Die Integrationstests hinterlassen Benutzer in derselben Datenbank; danach
  griff die Ersteinrichtung nicht mehr. Die Datenbank wird jetzt frisch
  aufgesetzt.

Alle drei sind die Sorte Fehler, die eine Testsuite entwertet: Sie melden Erfolg
oder Misserfolg aus Gründen, die mit dem Produkt nichts zu tun haben.

## Stand: 16 Systemnachweise, 35 Modultests — alle grün

---

# Iteration 6 — Der Agent muss sich ausweisen

Die letzte große Lücke: Bis hierher konnte jeder, der das Netz der Zentrale
erreichte, Gerätezustände melden.

## Umgesetzt (FA-AGT-010 / FA-AGT-020)

**Zwei Geheimnisse mit unterschiedlicher Lebensdauer:**

| | Gültigkeit | Zweck |
|---|---|---|
| **Registrierungsgeheimnis** | **einmalig**, 24 Stunden | Erstanmeldung eines Agenten |
| **Agent-Token** | dauerhaft bis Widerruf | laufender Betrieb |

Ein Geheimnis, das beliebig oft gilt, ist ein Dauerzugang — genau das, was ein
Registrierungsverfahren vermeiden soll. Deshalb wird es beim Einlösen verbraucht.

Beide liegen **nur als Hash** in der Datenbank. Ein Datenbankauszug genügt damit
nicht, um sich als Agent auszugeben. Verwendet wird SHA-256 statt Argon2: Die
Werte sind 256 Bit aus einem sicheren Zufallsgenerator, nicht menschengewählt —
gegen Zufall dieser Länge hilft kein Durchprobieren, und die Prüfung liegt auf
dem heißen Pfad jeder Agentenmeldung.

## Bewusste Entscheidung: Ohne Token arbeitet der Agent weiter

Er überwacht und verifiziert eigenständig; nur die Meldungen an die Zentrale
entfallen, und er sagt das deutlich. **Der TI-Zugang einer Praxis darf nicht
daran hängen, dass eine Verwaltungsschicht erreichbar ist** (FA-AGT-030).

## Nachgewiesen: 21 Systemnachweise

Neu: Agent-Schnittstellen ohne Token gesperrt (3 von 3), erfundenes Geheimnis
abgewiesen, gültiges einlösbar, dasselbe ein zweites Mal abgewiesen, abgelehnte
Registrierungen protokolliert. Zusätzlich geprüft: Das Token überlebt einen
Neustart des Agenten, eine erneute Registrierung ist nicht nötig.

---

# Iteration 7 — Gerätegruppen

Gruppen sind mehr als Ordnung: Sie tragen das **Prüfintervall** und später die
**Rollout-Pläne** (ADR-0004). Bei 5.000 Terminals ist die Last nur darüber
steuerbar — die Notaufnahme braucht 30 Sekunden, das Archiv kommt mit fünf
Minuten aus. Deshalb stehen sie schon in Version 1 im Datenmodell, obwohl der
Rollout eine spätere Stufe ist.

## Umgesetzt (FA-GER-050)

- Gruppen anlegen, Prüfintervall ändern, löschen; Geräte zuordnen
- **Genau eine Vorgabegruppe je Einrichtung**, per Datenbank-Index erzwungen —
  ein neu erkanntes Gerät muss irgendwo landen, bevor jemand es zuordnet
- **Beim Löschen einer Gruppe ziehen ihre Geräte in die Vorgabegruppe um.** Kein
  Gerät darf durch eine Löschung aus der Überwachung fallen.
- Die Vorgabegruppe selbst ist gegen Löschen geschützt
- Untergrenze 10 Sekunden für das Prüfintervall, auf Datenbankebene und in der
  Fachlogik — kürzere Abstände belasten Konnektor und Terminals ohne Nutzen
- Neue Seite „Gruppen" in der Oberfläche, Änderungen nur für Administratoren

## Tests: 43 grün

Acht neue Integrationstests gegen echte PostgreSQL, darunter der wichtigste:
**Beim Löschen einer Gruppe landet das Gerät nachweislich in der Vorgabegruppe**
— nicht im Nichts.

## Systemnachweise: 21, weiterhin alle grün

---

# Iteration 8 — Alarmierung

Bis hierher sah man Probleme nur, wenn man hinschaute. Ein Automat, der nachts
arbeitet, muss von sich aus melden.

## Der tragende Unterschied

**Ein Alarm ist kein Protokolleintrag.** Das Protokoll sammelt Ereignisse — was
geschehen ist. Ein Alarm beschreibt einen *andauernden Zustand* — was gerade
nicht in Ordnung ist. Daraus folgt alles Weitere:

- **Je Objekt und Art höchstens ein offener Alarm**, per Datenbank-Index
  erzwungen. Ohne diese Regel erzeugte ein alle vier Sekunden prüfender Agent
  900 Alarme pro Stunde für dasselbe Problem — und niemand würde mehr hinsehen.
  *Nachgewiesen: 100 Meldungen desselben Zustands ergeben einen Alarm.*
- **Behobene Zustände schließen den Alarm selbsttätig.** Ein Alarm, den jemand
  wegklicken muss, obwohl das Problem längst weg ist, erzieht dazu, Alarme
  wegzuklicken. *Nachgewiesen im laufenden Betrieb: Gerät weg → Alarm, Gerät
  wieder da → Alarm verschwindet.*
- **Quittieren heißt gesehen, nicht behoben.** Ein quittierter Alarm bleibt
  offen, zählt aber nicht mehr als Handlungsbedarf.
- **Jeder Alarm trägt eine Handlungsempfehlung im Klartext.** Eine Meldung, die
  nur sagt was kaputt ist, hilft in einer Praxis niemandem.

## Kritisches zuerst

Die Liste sortiert nach Schweregrad, nicht nach Zeit: Was den Betrieb anhält,
muss zuerst ins Auge fallen. Offene Alarme stehen ganz oben auf der Startseite.

## Nachgewiesen

- **51 Modultests** (8 neue für die Alarmlogik)
- **25 Systemnachweise**, darunter vier neue im laufenden Betrieb:
  Alarm entsteht, nennt eine Handlung, flutet nicht, verschwindet von selbst
