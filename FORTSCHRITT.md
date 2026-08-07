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

---

# Iteration 9 — Excel-Export

Deine ausdrückliche Anforderung. Umgesetzt als **echtes `.xlsx`**, nicht als CSV:
CSV zerlegt bei deutschen Excel-Einstellungen regelmäßig die Spalten und
verstümmelt Umlaute. Ein Export, den der Empfänger erst reparieren muss, ist
keiner.

## Selbst geschrieben statt Apache POI

POI wiegt rund 10 MB und brächte ein Vielfaches dessen mit, was hier gebraucht
wird — zwei Blätter, Text, Zahlen, Datumswerte. Gegen die Speichergrenzen aus
NFA-LEI-030 (Agent ≤ 512 MB, Stack ≤ 3 GB) wäre das schwer zu rechtfertigen. Ein
`.xlsx` ist ein ZIP mit dokumentierten XML-Dateien (ECMA-376).

## Der Nachweis, der zählt

Ein selbst geschriebenes Dateiformat gegen die eigene Umsetzung zu prüfen wäre
zirkulär. Deshalb liest **openpyxl** — eine unabhängige Umsetzung — jede
exportierte Datei im Systemnachweis:

```
openpyxl liest die Datei: 79 Protokollzeilen, 8 Spalten,
Kopfzeile fixiert, Autofilter gesetzt
```

Geprüft werden dabei auch: Deckblatt mit Filtern und Zeilenzahl, Zeitpunkte als
**echte Datumswerte** (nicht Text — sonst kann niemand damit rechnen oder
sortieren), und dass **keine Geheimnisse** in der Datei stehen.

## Details, die sonst später auffallen

- **Kopfzeile fixiert, Autofilter gesetzt** — erwartet jeder, der eine Tabelle
  mit mehr als zwei Bildschirmseiten öffnet
- **Steuerzeichen werden gefiltert.** Sie sind in XML unzulässig; ein einziges
  Byte in einem Gerätenamen machte die Datei unöffenbar.
- **Zeilenweises Schreiben**, nichts wird vorher im Speicher gesammelt
- **Der Export ist selbst protokollpflichtig** — er kann personenbezogene Daten
  enthalten
- Ein **benannter Standardstil**, weil Lesewerkzeuge sonst beim Öffnen warnen

## Ein Fehler, der beinahe durchgerutscht wäre

Der Export-Endpunkt war nie registriert — mein Ersetzungsmuster hatte nicht
gegriffen, und der Build lief trotzdem grün, weil nur ein Link ins Leere zeigte.
Erst der Systemnachweis mit `HTTP 404` hat es aufgedeckt. Ein Knopf, der nichts
tut, ist im Compiler unsichtbar.

## Stand: 59 Modultests, 28 Systemnachweise — alle grün

---

# Iteration 10 — Speicherbudget und Rotation

Deine Sorge von gestern Abend: „Ich habe Angst, dass uns der Speicher platzt."

## Umgesetzt (FA-PRO-070)

- **Gemessen wird die tatsächliche Größe**, nicht die Zeilenzahl. Eine Zeile mit
  langer Fehlerbeschreibung belegt ein Vielfaches einer knappen Statusmeldung.
- Schwellen: **80 % Rotation, 90 % Alarm, 95 % verschärfte Rotation**
- **Gestaffelt nach Wichtigkeit** — technische Diagnose zuerst,
  sicherheitsrelevante Ereignisse zuletzt
- Der Füllstand wird **im Minutentakt** geprüft, nicht nur beim Schreiben. Ein
  Protokoll, das nachts vollläuft, muss auch nachts rotieren.
- Konfiguration als lesbare Angabe: `PROTOKOLL_BUDGET=2GB`

## Am laufenden System nachgewiesen

Die Zentrale wird mit **400 KB Budget** gestartet (normal 2 GB), dann wird die
Karte wiederholt entzogen:

| | |
|---|---|
| Kernfunktion arbeitet weiter | **6 Verifikationen** trotz vollem Protokoll |
| Füllstand bleibt begrenzt | 152 KB |
| Sicherheitsrelevante Einträge überleben | 13 vorhanden |

## Zwei echte Fehler, die die Tests gefunden haben

**1. Rotation ohne Wirkung.** `pg_total_relation_size` schrumpft nicht durch
`DELETE` allein — der Platz bleibt bis zum `VACUUM` belegt. Die Schleife sah
keinen Fortschritt und löschte weiter, bis eine Stufe leer war. Die Staffelung
wäre damit wirkungslos gewesen: Erst wenn alle Routineereignisse weg sind, hätte
sie aufgehört — und dann wären die Nachweise dran gewesen.

**2. Ein zu kleines Budget hätte das ganze Protokoll vernichtet.** Eine leere
Tabelle ist nicht 0 Byte groß; allein die fünf Indizes belegen Platz. Ist das
Budget kleiner, ist das Rotationsziel **nie** erreichbar. Jetzt gilt: Ein
Rotationslauf entfernt höchstens die Hälfte der Einträge, und der Zustand wird
als „Budget zu klein" gemeldet. Eine Fehlkonfiguration darf Daten kosten, aber
nicht alle.

**Und ein Testfehler:** Zwei Tests hingen daran, wie viel ohnehin schon in der
Datenbank lag — je nach Ausführungsreihenfolge grün oder rot. Sie bemessen das
Budget jetzt nach dem Befüllen.

## Stand: 65 Modultests, 34 Systemnachweise — alle grün

---

# Iteration 11 — Informationsmodell im Demodatensatz

Deine Vorgabe: *„das Infomodell der Konnektoren simulieren wir ebenfalls, je
Praxis 3 Behandler Arbeitsplätze mit eigenem KT ohne SMCB und 1 Arbeitsplatz im
Empfang mit KT und SMCB"* — dazu: alle Terminals mit freigeschalteter
Adminschnittstelle.

## Umgesetzt

Je Praxis entstehen jetzt vier Arbeitsplätze mit je einem Kartenterminal:

```
Praxis
 ├── Empfang        → Terminal MIT SMC-B
 ├── Behandlung 1   → Terminal ohne SMC-B
 ├── Behandlung 2   → Terminal ohne SMC-B
 └── Behandlung 3   → Terminal ohne SMC-B
```

Im gesamten Verbund: **36 Arbeitsplätze, 36 Terminals, davon 9 mit SMC-B** —
genau eine je Praxis, alle mit freigeschalteter Managementschnittstelle.

**Warum die Arbeitsplätze im eigenen Datenmodell stehen** und nicht nur im
Konnektor: Der Aufrufkontext (Mandant / Clientsystem / Arbeitsplatz) entscheidet,
*welches* Terminal der Konnektor anspricht. Ohne diese Zuordnung im eigenen
Bestand könnte der Agent nicht bestimmen, mit welcher Kennung er eine bestimmte
SMC-B erreicht.

## Nachgewiesen

8 Modultests und 13 Systemnachweise, darunter die beiden, auf die es ankommt:

- **Genau eine SMC-B je Praxis, und zwar am Empfang** — keine an einem
  Behandlungsplatz
- **Löschen fasst ausschließlich Demodaten an.** Der Nachweis legt vorher einen
  Datensatz ohne Demo-Kennzeichen an und prüft danach, dass er noch da ist.

---

# Iteration 12 — Bestandsverwaltung (FA-GER-010, FA-GER-020)

Bis hierher kamen alle Geräte aus dem Simulator oder dem Demodatensatz. Jetzt
lassen sich **Konnektoren und Kartenterminals von Hand erfassen** — die
Voraussetzung dafür, das Produkt an einer echten Praxis einzurichten.

Neuer Navigationspunkt **Bestand**: oben die Konnektoren, darunter die Terminals
mit Suche, Filter und Seitenblättern.

## Vier Regeln, die die Umsetzung bestimmen

**1. Zugangsdaten gehen nur hinein, nie heraus.** Verschlüsselt mit AES-256-GCM,
Schlüssel aus der Umgebung (`GEHEIMNIS_SCHLUESSEL`), nie im Abbild und nie im
Repository. Kein Lesepfad gibt einen Klartext zurück — die Oberfläche zeigt nur,
*dass* ein Kennwort hinterlegt ist.

**2. Ein nicht erreichbares Gerät ist speicherbar.** Bei der Ersteinrichtung
steht der Konnektor oft noch gar nicht. Erzwungene Erreichbarkeit treibt zu
Fantasiewerten, die später niemand korrigiert. Also: prüfen und melden, nicht
erzwingen.

**3. Löschen darf nicht mehr mitnehmen als gemeint.** Das Schema würde die
Terminals eines Konnektors stillschweigend mitlöschen — ein Fehlgriff hätte einen
halben Standort aus der Überwachung entfernt. Die Anwendung verweigert und
**benennt die betroffenen Geräte**, statt sie nur zu zählen.

**4. PIN-Automatik nur mit SMC-B** (`A_24080`). Ein Versuch gegen die falsche
Karte könnte deren Fehlversuchszähler belasten. Beim Anlegen *und* beim Ändern
abgelehnt, mit Nennung der Anforderungs-ID.

## Eine Architekturfrage, die dabei aufbrach

Der Quelltext der Zentrale behauptete: *„kennt bewusst keine SMC-B-PIN und keine
Terminal-Zugänge"*. Der Anforderungskatalog verlangt aber, Zugangsdaten in der
Zentrale zu erfassen. Beides ging nicht.

Aufgelöst in **[ADR-0008](docs/adr/0008-ablage-von-geraete-zugangsdaten.md)**:
Zwei Arten von Geheimnissen, verschieden behandelt.

- Die **SMC-B-PIN** liegt niemals in der Zentrale. Ihr Missbrauch sperrt die
  Karte und legt die Praxis still; bei zentralem Betrieb wären das hunderte
  Praxen gleichzeitig.
- **Geräte-Zugänge** liegen verschlüsselt in der Zentrale. Ihr Verlust ist
  reparabel und trifft ein Gerät, nicht den Kartenbetrieb.

Der ADR benennt auch, was der Schutz **nicht** leistet: Wer Datenbank und
Schlüssel gleichzeitig hat, kann entschlüsseln. Er schützt gegen Sicherungen,
Datenauszüge und versehentliche Ausgaben — nicht gegen vollen Systemzugriff.

**Betrieblich wichtig:** Der Schlüssel gehört in die Sicherung, aber nicht in
dieselbe Datei wie der Datenbankauszug. Sonst schützt die Verschlüsselung vor
nichts.

## Zwei Nachweise, die falsch gebaut waren

`Not-Aus ist protokolliert` und `abgelehnte Registrierungen werden protokolliert`
flatterten — mal grün, mal rot, ohne Änderung am Produkt. Beide prüften in
*einem* Griff zwei verschiedene Dinge: ob ein Ereignis im Protokoll steht, und ob
es in der Ansicht erscheint. Bei einem Fehlschlag war nicht erkennbar, welches
von beidem gescheitert war.

Jetzt getrennt, und im Fehlerfall geben sie eine Diagnose aus. Das ist dieselbe
Erfahrung wie schon mehrfach: **Ein Nachweis, der nicht sagt, was er misst, ist
kein Nachweis.**

## Zwei echte Fehler, die die Tests gefunden haben

- **Die Seitengröße war nach unten auf 10 geklemmt** — eine willkürliche Zahl,
  die kleinere Seiten unmöglich machte. Der Schutz gehört nach oben (gegen eine
  manipulierte URL, die 5.000 Datensätze zieht), nach unten reicht „größer null".
- Der Test für den Löschschutz erwartete einen bestimmten Gerätenamen in der
  Meldung. Aufgezählt werden aber die ersten fünf alphabetisch — der Test war zu
  eng, nicht das Produkt falsch.

## Was von FA-GER-060 noch fehlt

Serverseitige Paginierung, Volltextsuche, Filter und der passende Index stehen.
**Nicht** umgesetzt sind Sortierung nach Spalten, Filter nach Status/Modell/
Firmware und Massenaktionen (Gruppe zuweisen, Intervall setzen, aktivieren).
Die Anforderung gilt damit als **teilweise erfüllt**, nicht als erledigt.

## Stand nach Iteration 12: 97 Modultests, 64 Systemnachweise — alle grün

---

# Iteration 13 — Bestandsermittlung über den Konnektor (FA-GER-030, FA-GER-035)

Bis hierher musste jedes Gerät von Hand erfasst werden — mit abgetippter
Terminal-Kennung und abgetipptem Aufrufkontext. Jetzt ermittelt der Roboter
selbst, was am Konnektor hängt, und **schlägt es zur Übernahme vor**.

## Warum der Agent das tut und nicht die Zentrale

Der Konnektor steht im Praxisnetz. Nach ADR-0002 baut nur der Agent ausgehend
auf, damit im Kundennetz keine Firewall-Freigaben nötig sind. Die Zentrale
könnte den Konnektor also gar nicht erreichen — im zentralen Betriebsmodell
erst recht nicht.

Der Agent fragt bei jedem Prüfdurchlauf ab und meldet **nur, wenn sich etwas
geändert hat**. Bei 5.000 Terminals im Minutentakt wäre eine vollständige
Meldung je Zyklus rund 7 Millionen Datensätze am Tag — dieselbe Überlegung wie
bei der Protokoll-Grundregel.

## Die Regel, an der alles hängt

**Es wird nichts selbsttätig übernommen.** Der Befund liegt in eigenen Tabellen,
streng getrennt vom Bestand. Die Oberfläche zeigt vier Lagen:

| Lage | Bedeutung |
|---|---|
| **neu erkannt** | Konnektor kennt es, Bestand nicht → Übernehmen anbieten |
| **erfasst** | deckungsgleich |
| **weicht ab** | erfasst, aber die Angaben unterscheiden sich → nur anzeigen |
| **nicht mehr gemeldet** | im Bestand, Konnektor kennt es nicht mehr → nur anzeigen |

Der Grund ist nicht Vorsicht um ihrer selbst willen: Ein zurückgesetzter
Konnektor würde sonst einen gepflegten Bestand überschreiben — mit Zugangsdaten,
Gruppenzuordnungen und Standortangaben, **die er selbst gar nicht kennt und die
niemand wiederherstellen kann**.

Am laufenden System nachgewiesen: PEviM am Gerät abgeschaltet → die Oberfläche
meldet „PIN-Automatik: erfasst ‚ja', Gerät meldet ‚nein'", der Bestand bleibt
unverändert.

## Das Informationsmodell — der eigentliche Zeitgewinn

Jeder Aufruf beim Konnektor braucht Mandanten-ID, Clientsystem-ID und
Arbeitsplatz-ID. Abgetippt sind das die häufigsten Einrichtungsfehler überhaupt,
weil ein Tippfehler sich nur als unspezifischer Zugriffsfehler zeigt. Jetzt
werden die Werte ausgelesen und angezeigt.

**Diese Schnittstelle ist herstellerspezifisch** und deshalb immer optional
(NFA-QS-035). Fällt sie aus, weist die Oberfläche auf die entfallende
Unterstützung hin, und alles bleibt von Hand erfassbar. Nichts hält an.

Der Simulator bildet ein KoCo-artiges Modell nach — nach ADR-0007 eine
**Annahme**, die beim ersten Hardwarekontakt zu prüfen ist. Absichtlich keine
saubere Welt: ein Arbeitsplatz mit zwei Terminals, einer ganz ohne.

## A_24080 auch hier

Der Konnektor kann „PEviM aktiv" melden, obwohl keine SMC-B steckt. Beim
Übernehmen wird das korrigiert. **Die Regel setzen wir durch, nicht der
Konnektor.**

## Ein Fehler, den erst der Vollauf gezeigt hat

Der erste vollständige Nachweislauf meldete **12 ermittelte Terminals**, obwohl
der Simulator nur vier hat. Grund: Das Prüfskript startet den Stack mehrfach,
jeder Lauf registriert einen neuen Agenten — und die Funde wurden je Agent
gespeichert.

**Das ist kein Testartefakt.** Im Betrieb passiert dasselbe, sobald ein Agent neu
installiert wird: Dubletten in der Liste, und Geräte erscheinen als „nicht mehr
gemeldet", die längst ein anderer Agent betreut.

Behoben: Je Terminal-Kennung zählt nur der jüngste Fund, und nur aktive Agenten
werden berücksichtigt. Dazu ein zweiter Befund — die Modellanzeige nahm den
„ersten" Agenten aus der Liste, was nach einem Austausch das Modell eines
abgemeldeten Agenten zeigte. Jetzt gilt das zuletzt gemeldete.

Beide haben eigene Tests bekommen. Wieder bestätigt: **Der Vollauf findet
Fehler, die Modultests nicht sehen können** — weil erst er die Zeitachse und
die Wiederholung mitbringt.

## Eine Lücke, die dabei sichtbar wurde

Die **Protokollansicht zeigt nur die 200 jüngsten Einträge** und kann nicht
zurückblättern. Solange wenig passierte, fiel das nicht auf. Mit den neuen
Ermittlungs- und Bestandsereignissen rutschen ältere Einträge jetzt schneller
aus dem Blick — ein Nachweis, der einen Eintrag von vor zehn Minuten suchte,
fand ihn nicht mehr.

**Für ADR-0005 (Nachvollziehbarkeit) ist das relevant:** Der Excel-Export reicht
weiter zurück, die Ansicht selbst nicht. Wer einen Vorgang von gestern
nachvollziehen will, muss exportieren. Das ist keine gute Antwort und gehört
behoben — Blättern in der Protokollansicht ist auf die Liste gesetzt, aber in
dieser Iteration nicht umgesetzt.

Die betroffenen Nachweise prüfen jetzt getrennt, ob ein Ereignis **im Protokoll
steht** und ob es **in der Ansicht erscheint**. Damit misst der Nachweis, was er
behauptet — und die Lücke ist sichtbar statt zufällig rot.

## Stand nach Iteration 13: 110 Modultests, 77 Systemnachweise

Im letzten Vollauf waren 76 von 77 grün; der eine Fehlschlag war der oben
beschriebene Export-Nachweis, der inzwischen getrennt ist. **Diese Trennung ist
noch nicht in einem Vollauf bestätigt** — sie geht in den nächsten Lauf.
