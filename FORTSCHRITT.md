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


---

# Iteration 14 — Blättern im Protokoll (ADR-0005)

Die im letzten Durchgang benannte Lücke ist geschlossen: Die Protokollansicht
zeigte nur die 200 jüngsten Einträge und konnte nicht zurück. Wer einen Vorgang
von gestern nachvollziehen wollte, musste exportieren.

**Warum das mehr als Komfort ist:** Der Roboter handelt selbsttätig. *Jede*
seiner Handlungen muss im Nachhinein auffindbar sein — nicht nur die der letzten
Stunde. Genau das verspricht ADR-0005.

## Umgesetzt

Serverseitige Paginierung wie in der Bestandsliste, 50 Zeilen je Seite, mit
Zählung („Eintrag 51 bis 100 von 137") und Blätterknöpfen.

Zwei Details, an denen so etwas sonst scheitert:

- **Sortiert nach Zeitpunkt *und* laufender Nummer.** Ohne die Nummer wandern
  Zeilen zwischen Seiten, sobald zwei Einträge denselben Zeitstempel tragen —
  bei einem Roboter, der mehrere Geräte gleichzeitig bearbeitet, ist das der
  Normalfall. Ein eigener Nachweis prüft, dass keine Zeile auf zwei Seiten steht.
- **Die Seitennummer überlebt die Auffrischung.** Die Ansicht aktualisiert sich
  alle fünf Sekunden; ohne Gegenmaßnahme wäre man nach fünf Sekunden zurück auf
  Seite 1.

Was bleibt: Auf Seite 2 und weiter wandern Einträge langsam nach hinten, weil
oben neue dazukommen. Das ist bei einer Live-Ansicht unvermeidbar — die
Blätterleiste sagt es deshalb ausdrücklich, statt den Benutzer rätseln zu lassen.

## Ein Test, der unterwegs etwas anderes bewiesen hat

Der erste Anlauf räumte am Ende seine Testeinträge weg — und scheiterte. **Zu
Recht:** Das Fachprotokoll ist unveränderlich (FA-PRO-010), nur die Rotation darf
löschen. Der gescheiterte Löschversuch ist als eigener Nachweis stehengeblieben.

## Stand: 117 Modultests, 83 Systemnachweise — **alle grün**

Damit ist auch der zuvor offene Punkt bestätigt: Die getrennten
Protokollnachweise (Not-Aus, Registrierung, Export) sind im Vollauf grün. Das
Flattern ist weg, weil jeder Nachweis jetzt genau eine Sache misst.

---

# Iteration 15 — Sortierung, Filter, Massenaktionen (FA-GER-060)

Die Geräteliste ist jetzt bei großen Stückzahlen wirklich bedienbar: sortierbare
Spalten, Filter nach Status, Erreichbarkeit, Modell und Firmware, dazu
Mehrfachauswahl mit Massenaktionen. Alles serverseitig.

## Drei Entscheidungen, die den Unterschied machen

**Sortierung über eine feste Liste, nicht über die URL.** Ein Sortierfeld lässt
sich nicht als Parameter binden — es geht direkt in die Abfrage. Ohne Weißliste
wäre das eine Einfallstür. Ein unbekannter Wert führt jetzt zur Vorgabe.

**Jede Sortierung hat ein zweites Feld.** Sonst wandern Zeilen zwischen den
Seiten, sobald zwei Geräte denselben Wert haben — bei „Firmware 4.2.1" der
Regelfall.

**Massenaktionen zählen ehrlich.** Geändert und übersprungen werden getrennt
gemeldet. „Aktion ausgeführt" bei 40 von 50 Geräten wäre eine Falschmeldung, und
bei 5.000 Geräten merkt das niemand, wenn man es nicht sagt.

Dazu: Eine Gerätegruppe nimmt nur Geräte ihrer eigenen Einrichtung auf. Fremde
werden übersprungen und benannt — wäre das über eine Massenaktion aushebelbar,
stünde die Mandantentrennung offen.

## Auch die Übernahme geht jetzt als Auswahl

FA-GER-030 erlaubt „einzeln oder als Auswahl". Bisher ging nur einzeln; jetzt
lassen sich mehrere ermittelte Geräte in einem Schritt übernehmen — weiterhin nur
nach ausdrücklicher Bestätigung.

## Zwei echte Fehler, die die Tests gefunden haben

**Der gefährlichere:** Die Hilfsmethode für Filterwerte machte aus jedem
`Boolean` eine Zeichenkette. Der Vergleich gegen eine `boolean`-Spalte lief damit
ins Leere — der Filter lieferte **lautlos null Treffer statt eines Fehlers**. Die
Oberfläche hätte „keine Geräte" gesagt, und niemand hätte gemerkt, dass die Frage
falsch gestellt war.

**Der zweite:** Das ID-Array für Massenaktionen war untypisiert; der
Datenbanktreiber braucht `Long[]`. Die Aktion scheiterte zur Laufzeit und meldete
„0 geändert".

Beide wären ohne Test bis in den Betrieb durchgerutscht — sie erzeugen keinen
sichtbaren Fehler, sondern ein falsches, plausibel aussehendes Ergebnis.

## Stand: 131 Modultests, 95 Systemnachweise

Im Vollauf waren 94 von 95 grün. Der eine Fehlschlag lag **im Nachweis**, nicht
im Produkt: Meine SQL-Hilfsfunktion entfernte alle Leerzeichen, aus
`CHERRY ST-1506` wurde `CHERRYST-1506` — und danach fand der Filter
erwartungsgemäß nichts. Der Modellfilter selbst ist durch einen Modultest und
eine Messung am laufenden System belegt (36 von 36 Treffern).

Behoben durch eine zweite Hilfsfunktion für Textwerte. **Diese Korrektur ist
noch nicht in einem Vollauf bestätigt** — sie geht in den nächsten Lauf.

Damit ist FA-GER-060 bis auf die Leistungsmessung erfüllt. Die steht als
Nächstes an: erste Seite bei 5.000 Terminals unter einer Sekunde — gemessen,
nicht geschätzt.


---

# Iteration 16 — Leistungsmessung am Mengengerüst (NFA-LEI-010)

Bisher stand im Anforderungskatalog ein Zielwert, den niemand nachgemessen hatte:
*„Seitenaufbau unter 1 Sekunde bei 5.000 Terminals"*. Jetzt ist er gemessen.

## Der Lasterzeuger steht in der Oberfläche

Unter **Verwaltung → Leistungsmessung** lassen sich 5.000 Testgeräte auf
Knopfdruck anlegen (dauert 257 ms) und rückstandsfrei entfernen.

**Warum im Produkt und nicht nur im Prüfskript:** Der Betreiber soll die Messung
auf *seiner* Hardware wiederholen können. Ein Zielwert, der nur auf der
Entwicklungsmaschine gilt, sagt über einen Raspberry Pi 5 in einer Praxis
nichts aus — und genau der ist die Referenzhardware.

## Die Zahlen

Bei **5.000 Kartenterminals**:

| | gemessen | Ziel |
|---|---:|---:|
| Bestandsliste, erste Seite | **14 ms** | < 1.000 ms |
| Suche über Bezeichnung | 12 ms | < 1.000 ms |
| Sortierung nach Modell | 11 ms | < 1.000 ms |
| Filter kombiniert | 8 ms | < 1.000 ms |

Bei **5.000.004 Protokolleinträgen** (2,1 GB):

| | gemessen | Ziel |
|---|---:|---:|
| Protokollseite, erste Seite | **110 ms** | < 2.000 ms |
| Gefiltert nach Ereignisart | 141 ms | < 2.000 ms |
| Gefiltert nach Gerät | 6 ms | < 2.000 ms |

**Beide Zielwerte erfüllt**, mit Faktor 14 bis 140 Abstand.

## Ein Befund, der nicht unterschlagen wird

**Sehr tiefes Blättern überschreitet den Zielwert.** Gemessen:

| Seite | Antwortzeit |
|---:|---:|
| 10.000 | 258 ms |
| 50.000 | 683 ms |
| **100.000** | **1.310 ms** |

Ursache, am Abfrageplan abgelesen statt vermutet: `OFFSET` muss alle
übersprungenen Zeilen lesen — bei Seite 100.000 sind das 4,9 Millionen Zeilen
für 50 angezeigte.

Im Betrieb ist der Fall nicht erreichbar (man müsste 100.000-mal klicken), aber
es ist eine echte Grenze. Sie steht im Messbericht, mit der Lösung, falls sie je
gebraucht wird: Schlüsselsatz-Blättern statt `OFFSET`.

## Was die Messung ausdrücklich nicht abdeckt

Gemessen auf einem **M2 Pro**, Zielhardware ist ein Raspberry Pi 5 oder i.MX8.
Nicht gemessen: 1.000 Benutzer im Bestand, der Überwachungsdurchsatz über
24 Stunden (NFA-LEI-020), echtes LAN, gleichzeitige Benutzer.

Das steht so im [Messbericht](docs/00-referenzen/messung-leistung-mengengeruest.md)
— damit niemand mehr hineinliest, als drinsteht.

## Stand: 138 Modultests, 102 Systemnachweise — alle grün

Auch der Modellfilter-Nachweis, der zuletzt an einer Hilfsfunktion scheiterte,
ist bestätigt. Die Zielwerte aus NFA-LEI-010 werden jetzt bei **jedem** Lauf
nachgemessen (mit 1.000 Geräten, damit der Bauvorgang kurz bleibt) — nicht nur
einmalig von Hand.


---

# Iteration 17 — Aufrufkontext zur Auswahl, Betrieb und Sicherheit

## FA-GER-035 ist abgeschlossen

Bisher wurde das Informationsmodell des Konnektors nur *angezeigt*. Jetzt stehen
Mandant, Clientsystem und Arbeitsplatz **zur Auswahl** — das war der eigentliche
Zweck der Anforderung.

**Warum das mehr als Komfort ist:** Ein Tippfehler in einem dieser drei Werte
zeigt sich später nur als unspezifischer Zugriffsfehler. Niemand sucht ihn an
der richtigen Stelle, und die Inbetriebnahme steht.

Umgesetzt als Eingabefeld mit Vorschlagsliste, nicht als Auswahlfeld. Damit
bleibt ein Wert eintragbar, den der Konnektor nicht meldet (NFA-QS-035), es
braucht kein JavaScript, und für Screenreader ist es ein normales Textfeld.

Ohne Informationsmodell erscheinen Freitextfelder mit dem Hinweis, dass die
Werte zur Auswahl stehen, sobald ein Agent das Modell gelesen hat.

**Ein echter Fehler im Zerleger:** Er nahm das erste Vorkommen von
`"clientsysteme"` — und das steht *innerhalb* des Mandanten und ist nur eine
Kennungsliste ohne Namen. Jetzt werden alle Vorkommen geprüft und das erste
genommen, das vollständige Einträge liefert.

## docs/04-betrieb.md — mit durchgeführter Wiederherstellung

Installation, Konfiguration, Sicherung, Aktualisierung. Der
Wiederherstellungsweg wurde **tatsächlich durchgeführt**, nicht behauptet:
Volume gelöscht, aus der Sicherung zurückgeholt.

| | vorher | nachher |
|---|---:|---:|
| Kartenterminals | 36 | 36 |
| Benutzerkonten | 17 | 17 |
| Kennwort-Chiffrat | `v1:8zFuziQ6qha…` | identisch |

Danach war die Zentrale bedienbar und das Kennwort weiter entschlüsselbar.

**Ein Fehler beim ersten Versuch, der in die Doku gehört:** Die Sicherung wurde
mit `-f /tmp/…` *im Container* abgelegt — und ging mit ihm verloren, genau in dem
Moment, in dem sie gebraucht wurde. Die Anleitung sagt das jetzt ausdrücklich.

**Der ganze Ablauf ist als Systemnachweis automatisiert.** Eine Anleitung, die
niemand nachvollzieht, verrottet.

Ehrlich zum Downgrade: Ohne Migration dazwischen unproblematisch, mit Migration
nur über die Sicherung — die Migrationen haben absichtlich keinen Rückweg. Das
ist **nicht** durch einen Test belegt; dafür bräuchte es zwei veröffentlichte
Abbildversionen.

## docs/05-sicherheit-datenschutz.md

Schutzbedarf, personenbezogene Daten, TOMs mit Nachweis, Datenflüsse.

Zwei Lücken, die ich ausdrücklich benenne statt sie zu glätten:

**Die Mandantentrennung ist im Datenmodell, aber nicht durchgesetzt.** Solange
eine Instanz eine Einrichtung betreut, ohne Wirkung — bei einer
Serviceorganisation mit mehreren Mandanten wäre es die schwerste Lücke des
Produkts. Das muss **vor** dem ersten Mehrmandantenbetrieb erledigt sein.

**Das unveränderliche Protokoll kollidiert mit Art. 17 DSGVO.** Ein
Löschbegehren lässt sich nicht erfüllen. Naheliegende Auflösung wäre eine Frist,
nach der der Anzeigename durch eine Kennung ersetzt wird — Pseudonymisierung
statt Löschung, damit die Nachvollziehbarkeit der Handlung bleibt. Nicht
umgesetzt.

## Stand: 140 Modultests, 111 Systemnachweise — alle grün

---

# Iteration 18 — Mandantentrennung (Block A)

Die in der Sicherheitsdokumentation benannte **schwerste Lücke** ist geschlossen
— jedenfalls für Geräte, Konnektoren und die Ermittlung.

## Der Kern: Der Übersetzer erzwingt sie

Jede Methode, die mandantengebundene Daten liest oder ändert, verlangt einen
`Geltungsbereich` als **Pflichtparameter**. Wer ihn weglässt, bekommt keinen
ungefilterten Zugriff, sondern einen Übersetzungsfehler.

Beim Umbau war das unmittelbar sichtbar: Nach der Änderung der Signaturen
meldete der Übersetzer **44 Stellen**, an denen die Grenze fehlte. Jede einzelne
wäre bei einer Umsetzung „von Hand in jeder Abfrage" ein Kandidat gewesen,
vergessen zu werden. Genau davor warnt CLAUDE.md.

## Vier Entscheidungen, die den Ausschlag geben

**Keine Zuordnung heißt „sieht nichts", nicht „sieht alles".** Ein Fehler in der
Zuordnung, ein Datenbankausfall, ein unbekannter Wert — alles führt zu leerem
Zugriff. Die umgekehrte Richtung wäre eine offene Tür.

**Ein fremder Datensatz „existiert nicht".** Dieselbe Meldung wie bei einem
gelöschten. Ein „Zugriff verweigert" würde verraten, dass es ihn gibt — und bei
durchnummerierten Kennungen ließe sich ein fremder Bestand abzählen.

**Auch Schreibpfade prüfen.** Die Einrichtung kommt aus einem Formularfeld. Eine
Grenze, die nur in der Auswahlliste besteht, ist keine.

**Auch Filterwerte sind Daten.** Eine Modell-Auswahlliste verrät den
Gerätebestand anderer Mandanten, ohne einen einzigen Datensatz preiszugeben.

## Geprüft wird der Angriff, nicht die Anzeige

15 Tests gegen zwei Standorte und drei Praxen: fremde Kennung aufrufen, ändern,
löschen, Konnektor in fremder Einrichtung anlegen, Massenaktion mit fremder
Kennung, Suche über den ganzen Bestand, Filter auf fremdes Modell. Dazu
Systemnachweise über HTTP mit echter Anmeldung eines Praxisbenutzers.

## Ein Fehler, den das stille `catch` verborgen hat

Beim Umbau entstand ein SQL-Syntaxfehler — `WHERE 1=1ORDER BY`, zwei Textblöcke
ohne Trennzeichen. `catch (SQLException ignored) { }` schluckte ihn, und das
Ergebnis war eine **leere Liste statt einer Fehlermeldung**.

Gefunden haben es die Tests. Das `ignored` ist jetzt eine Meldung.
**Ein verschluckter Fehler ist schlimmer als ein lauter.**

## Was noch offen ist — und warum

**Fachprotokoll und Alarme sind noch nicht eingeschränkt.** Beide führen die
Einrichtung als **Textfeld**, nicht als Verweis. Eine Einschränkung über
Textvergleich wäre brüchig; sauber wird es erst mit einer Migration. **Bis dahin
sieht jeder angemeldete Benutzer das gesamte Protokoll** — das ist die
verbleibende Lücke, und sie steht so auch in der Sicherheitsdokumentation.

## Ein Fehler, den erst der Vollauf zeigte — und der still war

Nach dem Umbau sah der Administrator **gar nichts mehr**. 25 Nachweise fielen um,
alle mit „0 Geräte".

Ursache: `rs.wasNull()` gilt für die **zuletzt gelesene Spalte**. Meine Prüfung
stand hinter weiteren `getXxx`-Aufrufen und bezog sich damit auf die falsche.
Ein Administrator ohne Organisation bekam die Kennung **0 statt null**, galt
deshalb nicht als Instanzbetreiber — und sah nichts.

Das ist die unangenehme Sorte Fehler: Die Anwendung stürzt nicht ab, sie leert
sich still. Ohne den Vollauf wäre er erst beim Kunden aufgefallen, und dort
hätte niemand die Ursache erraten.

Ein eigener Test deckt die Falle jetzt ab.


---

# Iteration 19 — Mandantentrennung vollständig

Die zweite Hälfte ist erledigt: **Fachprotokoll, Excel-Export, Alarme,
Gerätegruppen und Benutzerliste** liegen jetzt ebenfalls hinter der
Mandantengrenze.

Alle vier führten die Einrichtung als **Textfeld**. Eine Grenze über einen
Textvergleich wäre brüchig gewesen: Zwei Einrichtungen mit gleichem Namen in
verschiedenen Verbünden fielen zusammen, und eine Umbenennung hätte die
Zuordnung stillschweigend zerrissen. Migration 12 trägt eine `organisation_id`
nach — mit Übernahme aus dem Textfeld, aber nur **wo der Name eindeutig ist**.
Mehrdeutige bleiben ohne Zuordnung: lieber eine Lücke, die auffällt, als eine
falsche Zuordnung, die niemand bemerkt.

**Das Textfeld bleibt.** Es zu ersetzen wäre eine nachträgliche Änderung an
Einträgen, die unveränderlich sind (FA-PRO-010). Nebeneffekt mit Wert: Der Name
bleibt lesbar, auch wenn die Organisation längst entfernt wurde.

## Ein Befund: Der Fremdschlüssel hätte das Löschen unmöglich gemacht

Der erste Entwurf gab der Spalte einen Fremdschlüssel mit `ON DELETE SET NULL`.
Das ist ein `UPDATE` — und das blockiert der Schutz des Protokolls, zu Recht.

**Die Folge wäre gewesen: Eine Organisation, auf die ein Protokolleintrag
verweist, ließe sich niemals mehr löschen.** Beim Kunden wäre das als
„Einrichtung kann nicht entfernt werden" aufgefallen, ohne erkennbaren
Zusammenhang. Das Fachprotokoll trägt die Spalte jetzt ohne Fremdschlüssel.

## Am laufenden System belegt

Ein Praxisbenutzer sieht **3 von 163 Protokolleinträgen**, sein Excel-Export ist
**4.162 statt 11.673 Bytes**, ein Filter auf ein fremdes Gerät liefert nichts.
Der Export war dabei der wichtigste Punkt: Er wäre sonst der bequemste Weg an
fremde Daten gewesen.

## Stand: 162 Modultests, 118 von 120 Systemnachweisen

Die beiden Fehlschläge betreffen Nachweisdetails (erwartete Meldungstexte), nicht
die geprüften Zusagen — beide sind durch Modultests und die jeweils benachbarten
Nachweise belegt. **Sie sind noch nicht bereinigt** und gehen in die nächste
Iteration.


---

# Iteration 20 — Rechte je Ebene

Bisher entschied allein die **Rolle**: Administrator oder Benutzer. Der
Geltungsbereich kam dazu und regelt, *welche Daten* jemand sieht. Was fehlte,
ist die zweite Frage: **welche Art von Handlung** ihm zusteht.

| | regelt |
|---|---|
| Geltungsbereich | welche Daten — Praxis A sieht nur ihre Geräte |
| Rechte | welche Handlung — Praxis A legt keine Einrichtungen an |

## Die Regeln, an einer Stelle

| Handlung | Wer darf | Warum |
|---|---|---|
| Bestand pflegen | jeder Administrator | Wer eine Praxis betreut, verwaltet dort Geräte |
| Benutzer verwalten | jeder Administrator | Sonst hängt jede Personalie zentral — bei 1.000 Benutzern untragbar |
| **Organisationen anlegen** | **ab Standort** | Sonst könnte ein Praxisadministrator seinen eigenen Bereich erweitern — die Mandantengrenze wäre von innen aufhebbar |
| **Demodatensatz, Lasttest** | **nur Instanzbetreiber** | Beide wirken quer über alle Mandanten |
| Not-Aus | jeder Administrator | Ein Sicherheitsmittel darf nicht an einer Genehmigung hängen |

Am laufenden System belegt: Ein Praxisbenutzer bekommt HTTP 403 auf Demodatensatz
und Lasttest, der Demodatensatz bleibt unverändert.

## Die Abweisung sagt, was fehlt

> Das Anlegen des Demodatensatzes ist Ihnen nicht gestattet.
> **Ihre Berechtigung: Administrator · Einrichtung.**

Ein blankes „Nur Administratoren" lässt jemanden mit Administratorrolle ratlos
zurück, wenn in Wahrheit die *Ebene* nicht reicht — er sucht dann bei seiner
Rolle statt bei seiner Zuständigkeit.

## Zwei Befunde am Not-Aus

**Er prüfte gar keine Rechte.** Jeder angemeldete Benutzer konnte ihn auslösen,
auch ohne Administratorrolle. Behoben.

**Er protokollierte nicht, wer ihn ausgelöst hat** — dort stand nur „Benutzer".
Bei einem Mittel, das den TI-Zugang anhält, ist das zu wenig. Jetzt mit Namen.

Der Not-Aus wirkt weiterhin **instanzweit**, nicht je Einrichtung. Das ist
bewusst so belassen: Ihn für Praxisadministratoren zu sperren wäre bei einem
Sicherheitsmittel die schlechtere Wahl. Als offener Punkt vermerkt.

## Stand: 170 Modultests, 124 Systemnachweise — alle grün

Auch die beiden Nachweise, die zuletzt umkippten, sind grün. Sie waren
zustandsabhängig formuliert (feste Zahlen, erwartete Wortlaute); jetzt prüfen sie
die Zusage und geben im Fehlerfall die tatsächliche Antwort aus.


---

# Iteration 21 — Benutzerverwaltung (Block B)

## Die eine Entscheidung, die alles andere bestimmt

**Ein Administrator legt kein Kennwort fest — er lädt ein.**

Ein Administrator, der ein Kennwort setzt, *kennt* es. Damit ist jede spätere
Handlung dieses Kontos nicht mehr eindeutig zurechenbar. Für ein System, das
selbsttätig handelt und dessen Auslösung nachvollziehbar sein muss, ist das ein
Widerspruch im Fundament.

Der Eingeladene setzt sein Kennwort selbst und richtet den zweiten Faktor ein.
Der Administrator sieht es nie.

## Der Zugangscode

32 Byte Zufall, in der Datenbank nur als SHA-256-Hash, einmalig und auf 7 Tage
befristet. **Nicht im Protokoll** — er wäre sonst für jeden lesbar, der das
Protokoll einsehen darf. Am laufenden System belegt: 0 Treffer im Protokoll,
0 im Klartext in der Datenbank.

Ein schwaches Kennwort verbraucht die Einladung **nicht** — sonst wäre sie durch
einen Tippfehler unbrauchbar. Ein gültiges verbraucht sie *vor* dem Anlegen,
sonst ließe sich ein Geheimnis durch absichtliche Fehlversuche beliebig oft
prüfen.

## Zweiter Faktor und Deaktivieren

Das Zurücksetzen ist der häufigste Supportfall: Telefon weg, Zugang weg. **Der
Administrator sieht das neue Geheimnis nicht** — es erscheint erst der
betroffenen Person bei ihrer nächsten Anmeldung. Sonst könnte er sich dauerhaft
als sie ausgeben.

**Deaktivieren statt Löschen:** Ein gelöschtes Konto reißt seine Spur im
Protokoll mit. Der letzte handlungsfähige Administrator lässt sich nicht
deaktivieren — sonst sperrt sich eine Praxis selbst aus.

## Ein Nachweis, der die Lage zerstörte, die er prüfen wollte

Der erste Anlauf nahm an, der Prüfadmin sei der einzige Administrator. Der
Demodatensatz legt aber drei weitere an — die Schutzregel griff also zu Recht
nicht, und **der Nachweis deaktivierte dabei den Prüfadmin selbst**. Hätte er
nicht am Ende des Laufs gestanden, wären alle folgenden Nachweise gescheitert,
ohne dass die Ursache erkennbar gewesen wäre.

Jetzt stellt er die Ausgangslage her (alle anderen Administratoren
deaktivieren), prüft, und stellt sie wieder her. Dazu ein Modultest, der
dasselbe isoliert prüft.

## Eine bewusste Abweichung

Filterung und Blättern der Benutzerliste laufen **im Speicher**, nicht in der
Abfrage. Bei 1.000 Konten ist das vertretbar, und die Alternative wäre eine
weitere Abfrage mit Mandantengrenze gewesen — eine Stelle mehr, an der sie
fehlen könnte. Bei der Geräteliste mit 5.000 Einträgen wurde genau deshalb
anders entschieden. Das steht so in der Umsetzungsbeschreibung.

## Stand: 182 Modultests, 138 Systemnachweise — alle grün


---

# Iteration 23 — Nachweise, Abhängigkeits-Scan, Umsetzungsstand

## Der Abhängigkeits-Scan war unvollständig — Ursache gefunden

Er erfasste 4 von 31 Abhängigkeiten. Ursache: `dependency:list` je Modul
scheitert, solange die eigenen Module nicht im lokalen Repository liegen
(`Could not find artifact de.pinroboter:gemeinsam`). Maven bricht für dieses
Modul ab, und die Ausgabe enthält nur die Abhängigkeiten der Module, die vorher
durchliefen.

Behoben durch ein vorgeschaltetes `install`. Jetzt **31 Abhängigkeiten**, alle
mit fester Version, Javalin und PostgreSQL-Treiber inbegriffen.

Das Skript prüft seit dem letzten Durchgang **zuerst die Vollständigkeit** und
meldet einen Fehlschlag, wenn Kernbibliotheken fehlen — ein Scan, der die Hälfte
übersieht und Erfolg meldet, erzeugt eine Sicherheit, die es nicht gibt.

## Neue Systemnachweise

Anmeldebremse und Pseudonymisierung sind jetzt am laufenden System belegt:
Fehlversuche werden gebremst, die Bremse protokolliert ihr Greifen (nicht jeden
Versuch), keine Absenderadresse im Protokoll, eine richtige Anmeldung bleibt
möglich. Und: 3 alte Einträge verlieren ihren Personenbezug, an ihre Stelle
tritt eine Kennung, die Handlung bleibt vollständig.

## Umsetzungsstand je Anforderung

**docs/01a-umsetzungsstand.md** macht sichtbar, was belegt ist und was nicht —
mit getrennten Zeichen für „am laufenden System nachgewiesen" und „nur
Modultests". Von 84 Anforderungen sind **38 belegt**, 14 nur durch Modultests,
14 teilweise, 18 nicht begonnen.

Die drei wichtigsten Lücken, ehrlich benannt: die Kalibrierung an echter
Hardware, der Ressourcenbedarf auf der Referenzhardware, und WCAG 2.2 AA ohne
Prüfwerkzeug — dort ist die Anforderung nicht belegt, sondern behauptet.

## Was der PO entscheiden muss

**docs/ENTSCHEIDUNGEN-PO.md** fasst es zusammen: formale Freigabe von ADR-0006
und ADR-0007, die Risikoentscheidung aus ADR-0008 (Geräte-Zugangsdaten in der
Zentrale), die offene Frage zum Firmware-Bezugsweg — und der wichtigste Punkt:
die Kalibrierung am Testaufbau, die wenig kostet und viel klärt.

## Stand: 197 Modultests, 146 Systemnachweise — alle grün


---

# Iteration 24 — Barrierefreiheit: belegt statt behauptet

WCAG 2.2 AA stand im Umsetzungsstand als eine der drei Hauptlücken: *„nicht
belegt, sondern behauptet"*. Die Vorgaben wurden beim Bauen beachtet — geprüft
wurde nie.

## Zwei Werkzeuge, weil eines nicht reicht

**axe-core** gegen zehn ausgelieferte Seiten: ARIA, Beschriftungen,
Überschriften, Tabellenköpfe, Formularzuordnungen.

**Kontraste rechnerisch**, weil jsdom nicht rendert. Zwölf Farbpaare in beiden
Farbschemata. Das misst den Wert statt des Eindrucks und ist damit belastbarer
als ein Screenshot-Vergleich.

## Zwei echte Befunde — beide durch Hinsehen nicht zu finden

**Der Rand von Bedienelementen hatte 1,29:1.** WCAG 1.4.11 verlangt 3:1 für
alles, was zur Erkennung eines Bedienelements nötig ist. Der vorhandene Rand war
für dekorative Trennlinien gedacht und dafür richtig — als Umrandung eines
Eingabefelds zu blass. Jetzt eine eigene Variable mit 5,33:1 (hell) und 5,86:1
(dunkel).

**Ein Eingabefeld ohne Beschriftung** in der Gruppenansicht. Daneben stand ein
`s` als Einheit — für einen Screenreader ist das ein namenloses Textfeld: Man
hört „Eingabefeld", nicht wofür. Jetzt mit verstecktem Label je Zeile, und das
`s` ist `aria-hidden`, damit es nicht doppelt vorgelesen wird.

Beide waren visuell unauffällig. 1,29:1 sieht auf einem guten Bildschirm noch
erkennbar aus, und die Einheit neben dem Feld erzeugt den Eindruck einer
Beschriftung.

## Ehrlich zur Reichweite

Ein Werkzeug findet nur einen Teil der Barrieren. **Offen bleiben:**
Tastaturbedienung im Ablauf (braucht einen Browser) und
Screenreader-Verständlichkeit (braucht einen Menschen). Das steht so im Skript,
in der Umsetzungsbeschreibung und im Umsetzungsstand.

Ist das Werkzeug nicht beschaffbar, überspringt die Prüfung und sagt das —
statt „bestanden" zu melden. Eine übersprungene Prüfung ist keine bestandene.

## Stand: 197 Modultests, 149 Systemnachweise — alle grün


---

# Iteration 25 — Karten erfassen (FA-GER-040) und ADR-0009

## eGK und HBA werden angezeigt, nicht verschwiegen

Sie stecken in der Praxis regelmäßig mit im Terminal. Sie wegzulassen wäre
bequem gewesen — sie sind für die Kernfunktion irrelevant. Zwei Gründe dagegen:
Der Betreiber soll sehen, **was in seinem Gerät steckt**, und er soll sehen,
**dass der Roboter sie in Ruhe lässt**. Eine Zusage, die man nicht prüfen kann,
beruhigt niemanden.

Die Kürzel werden ausgeschrieben: „eGK (Gesundheitskarte)" statt „EGK". Und
darunter steht, warum sie nicht verwaltet werden — mit Nennung von `A_24080`.
Eine Regel durchzusetzen genügt nicht; wer nicht weiß, warum eine Karte
ausgelassen wird, ruft beim Dienstleister an.

## Das Ablaufdatum in drei Zuständen

Mehr als 90 Tage: schlichtes Datum. Weniger: „läuft bald ab". Überschritten:
„abgelaufen". **Ohne gemeldetes Datum wird nicht geraten** — dann steht „nicht
gemeldet". Ein erfundenes Datum würde Sicherheit vortäuschen.

Der Austausch einer SMC-B braucht Vorlauf bei der Kartenherausgabe; drei Monate
sind knapp, aber machbar.

## Der Simulator kannte den Fall nicht

Er meldete nur SMC-B. Ein Simulator, der den zu prüfenden Fall gar nicht kennt,
kann ihn nicht prüfen. Jetzt stecken in `KT-EMPFANG-01` auch eine eGK und ein
HBA, jede mit eigenem Ablaufdatum.

## ADR-0009 — der Weg für die PIN-Hinterlegung

Die PIN kommt derzeit über eine **Umgebungsvariable** in den Agenten. Für einen
Prototyp vertretbar, für den Betrieb untragbar: Klartext in einer Datei, in
jeder Sicherung, sichtbar über `docker inspect`, und jede Änderung verlangt
einen Neustart.

Der Konflikt: Die Oberfläche läuft in der Zentrale — die PIN darf sie aber
**nie erreichen**. Bei 1.000 Standorten wäre eine Zentrale mit allen PINs ein
Ziel, dessen Kompromittierung hunderte Praxen lahmlegt.

**Entschieden: Ende-zu-Ende.** Der Agent erzeugt ein Schlüsselpaar und meldet
den öffentlichen Teil. Der Browser verschlüsselt die PIN damit lokal; die
Zentrale reicht nur das Chiffrat weiter und kann es nicht lesen.

Das erfordert JavaScript in genau einer Maske — bisher kommt die Oberfläche
ohne aus, und das war eine bewusste Wahl. Die Einschränkung wird in der Maske
ausgesprochen, statt sie stillschweigend hinzunehmen.

## FA-PIN-010 und FA-PIN-060 — umgesetzt und nachgewiesen

Die PIN lässt sich jetzt über die Oberfläche hinterlegen und löschen. Der
Browser verschlüsselt sie mit dem öffentlichen Schlüssel des Standort-Agenten;
die Zentrale reicht nur das Chiffrat weiter, kann es nicht lesen und löscht es
nach der Zustellung. Beim Agenten liegt sie mit AES-256-GCM verschlüsselt.

In der Oberfläche steht nur das Kennzeichen „PIN hinterlegt", nie der Wert —
auch für einen Administrator nicht. Fünfzehn neue Systemnachweise messen genau
das: dass das Chiffrat verschwindet, dass weder PIN noch Chiffrat in irgendein
Protokoll geraten, dass die Ablagedatei die PIN nicht im Klartext enthält.

### Der Fehler, den nur der Systemnachweis finden konnte

Javas `OAEPWithSHA-256AndMGF1Padding` klingt eindeutig und ist es nicht: Die
Kurzform setzt nur den Nachrichten-Hash auf SHA-256 und lässt die
Maskengenerierung MGF1 stillschweigend auf SHA-1. WebCrypto im Browser nimmt
für beides SHA-256. Jede PIN kam beim Agenten an und ließ sich nicht
entschlüsseln.

**Der Modultest konnte das nicht finden** — er war auf beiden Seiten gleich
falsch, weil er dieselbe Java-Kurzform zum Ver- und Entschlüsseln nutzte.
Gefunden hat es der Systemnachweis, der gegen OpenSSL verschlüsselt, also gegen
eine unabhängige Umsetzung. Ein Test, der beide Seiten einer Schnittstelle mit
derselben Bibliothek bedient, prüft die Bibliothek gegen sich selbst.

### Drei Fehler nebenbei

Das Volume des Agenten war in der Compose-Datei deklariert, aber **nicht
eingebunden** — Token und Schlüsselpaar hätten keinen Neustart überlebt, und
damit wäre jede hinterlegte PIN unbrauchbar geworden. `PIN_ABLAGE` wurde von
der Umgebungs-Schleife als vermeintliche Karten-PIN eingelesen. Und eine
Umgebungs-PIN aus dem Testaufbau hätte die über die Oberfläche hinterlegte nach
jedem Neustart lautlos verdrängt — jetzt hat die Ablage Vorrang, und der Agent
warnt, wenn PINs aus Umgebungsvariablen stammen.

## FA-ORG-020 — die Struktur über die Oberfläche verwalten

Verbünde, Standorte und Einrichtungen lassen sich jetzt anlegen, umbenennen,
umhängen und löschen. **Erst ab Standortebene** — wer eine Praxis betreut, legt
keine weiteren Praxen an.

Das ist die heikelste Verwaltungsfunktion des Produkts, und der Grund ist nicht
offensichtlich: Der Geltungsbereich eines Benutzers ergibt sich aus dem Baum.
Wer den Baum ändern darf, ändert Geltungsbereiche — auch seinen eigenen. Ein
Standortadministrator, der eine fremde Praxis unter sich hängen könnte, hätte
Zugriff auf deren Geräte, Protokolle und Konten, ohne dass an der
Mandantentrennung selbst etwas kaputt wäre.

Deshalb sind die Prüfungen hier überwiegend Angriffsversuche: eine fremde
Einheit übernehmen, die eigene aus dem Bereich schieben, unter einer fremden
Einheit anlegen, einen Zyklus bauen. Jeder Fall einzeln — denn beim Lesen des
Codes meint man leicht, eine Prüfung genüge, und tatsächlich braucht das
Umhängen zwei.

**Löschen wird abgelehnt statt nachgefragt.** Der Fremdschlüssel steht auf
CASCADE; ein Löschen risse Teilbaum, Konten und Geräte mit. Gelöscht wird nur,
was leer ist, und die Meldung sagt, was im Weg steht.

Nebenbei aufgefallen: Die Barrierefreiheitsprüfung sah nur Seiten im
Grundzustand. Die Terminalmaske mit PIN-Formular und die aufgeklappte
Bearbeitungszeile der Organisation waren damit ungeprüft. Beide sind jetzt
drin — 13 Seiten statt 10.

## FA-GER-050 und das Quittieren — und was dabei herauskam

Beide waren bisher nur durch Modultests abgesichert. Der Auftrag war, sie mit
Systemnachweisen zu unterlegen. Dabei kamen zwei Dinge zutage, die kein
Modultest hätte finden können.

**Der Quittierknopf zeigte ins Leere.** Die Oberfläche bot ihn an, die Route
`/alarme/{id}/quittieren` gab es nicht. `Alarmverwaltung.quittieren` war
geprüft — nur nie angebunden. Eine Funktion, die nur in ihrer Klasse existiert,
ist für den Benutzer keine. Die Route ist ergänzt, die Quittierung wird
protokolliert, wie die Akzeptanz von FA-ALA-010 es verlangt.

**Das Quittieren prüfte den Geltungsbereich nicht.** Ein Praxisbenutzer hätte
einen fremden Alarm stillstellen können. Der Geltungsbereich ist jetzt
Pflichtparameter, wie überall sonst — und ein Modultest hält fest, dass ein
leerer Bereich nichts quittieren kann.

**Falsche Beschriftung im Umsetzungsstand.** Dort stand das Quittieren unter
FA-ALA-020. Im Anforderungskatalog ist FA-ALA-020 aber die
E-Mail-Benachrichtigung — SMTP, Empfänger je Einrichtung, Sammelfenster gegen
5.000 Mails bei einem Konnektorausfall. **Die ist nicht begonnen.** Quittieren
gehört zu FA-ALA-010. Richtiggestellt, damit der Stand nicht mehr sagt, etwas
sei erledigt, das es nicht ist.

Bei FA-GER-050 war der wichtigste Nachweis nicht das Anlegen, sondern das
Löschen: Ein Gerät darf durch eine gelöschte Gruppe nicht aus der Überwachung
fallen. Geprüft ist, dass es in die Vorgabegruppe umzieht und weiter existiert —
und dass die Vorgabegruppe selbst sich nicht löschen lässt.

## Der Stack aus dem Repository war nicht startbar

Beim Hochfahren für die Vorführung kam heraus, was 230 Modultests und 204
Systemnachweise nicht gefunden hatten:

**Die `docker-compose.yml` war über zwei Commits hinweg kein gültiges YAML.**
Der Datenbank-Block war in das `depends_on` des Agenten gerutscht, und
`zentrale: { condition: … }` stand als Waise am Dateiende. `docker compose up`
brach mit einem Parserfehler ab.

**Das Datenverzeichnis des Agenten gehörte root.** Der Container läuft als
Nicht-Root (NFA-SIC-030). Ein frisches Volume erbt Eigentümer und Rechte des
Mountpunkts aus dem Image — und den gab es dort nicht. Weder Token noch
privater Schlüssel waren speicherbar.

**Und mein eigener Code beendete den Agenten deswegen.** Eine nicht
speicherbare Schlüsseldatei warf eine Ausnahme bis in `main`. Falsch: Die
Kernfunktion dieses Produkts ist die Überwachung der SMC-B, und die hängt nicht
an dieser Datei (FA-AGT-030). Der Agent warnt jetzt deutlich, nennt die
Ursache samt Prüfbefehl und arbeitet mit einem flüchtigen Schlüsselpaar weiter.

### Warum das keine Prüfung gefunden hat

Die Systemnachweise starten jeden Container einzeln mit `docker run`. Das ist
schnell und gut isoliert — und prüft die Auslieferung nicht. Die Definition of
Done verlangt „Läuft im Docker-Stack aus dem Repository — verifiziert, nicht
angenommen". Genau dieser Punkt war über mehrere Iterationen abgehakt, ohne
ausgeführt zu werden.

`pruefungen/stack-pruefen.sh` schließt die Lücke: gültiges YAML mit allen vier
Diensten, Volumes an den Stellen, an denen Zustand liegt, keine fest
eingetragenen Geheimnisse, und im gebauten Image gehört `/daten` dem
Container-Benutzer 10001.

## Stand: 230 Modultests, 204 Systemnachweise, 6 Stack-Prüfungen — alle grün

## UX-Stufe 4 und 5 — Detailseiten und Beziehungen

Vier Aufklapper sind verschwunden. Anlegemasken und die Bearbeitung einer
Organisationseinheit sind jetzt Seiten mit eigener Adresse; Lesezeichen,
Verlinkung und der Zurück-Knopf funktionieren.

`/organisation/{id}` beantwortet zuerst die Frage, die jemand wirklich hat:
was gehört zu dieser Praxis? Sieben Kacheln als Absprungpunkte — Terminals,
SMC-B, Konnektoren, Konten, Gruppen, Alarme, Protokoll. Gezählt über den
gesamten Teilbaum, sonst zeigte ein Standort überall Null.

### Ein Fehler, der als „keine Treffer" auftrat

Der Absprung lieferte 0 statt 5 Geräten. Ursache war ein Java-Textblock ohne
trennendes Leerzeichen: `WHERE 1=1AND t.organisation_id …`. Gefunden habe ich
ihn erst, nachdem ich `catch (SQLException ignored) { }` durch eine Meldung
ersetzt hatte — bis dahin sah der Datenbankfehler exakt aus wie ein leeres
Ergebnis, und ich habe an der falschen Stelle gesucht. Acht solcher Blöcke in
der Bestandsverwaltung melden jetzt.

Dieselbe Kombination hat in diesem Projekt schon einmal Zeit gekostet. Der
teurere Teil ist nicht der Tippfehler, sondern die verschluckte Ausnahme.

## Stand: 230 Modultests, 212 Systemnachweise — alle grün

## FA-ALA-020 — Benachrichtigung per E-Mail

Der Kern der Anforderung ist nicht der Versand, sondern das **Sammelfenster**.
Fällt ein Konnektor mit 5.000 Terminals aus, entstehen binnen Sekunden
tausende Alarme. Eine Mail je Alarm hätte den Absender als Spam markiert, den
Empfänger zum Filtern gebracht — und dann sähe er auch die eine wichtige Mail
nicht mehr.

Nachgewiesen gegen einen echten Auffangserver: **50 Alarme ergeben eine Mail**,
und die fasst zusammen statt fünfzigmal dieselbe Zeile zu wiederholen.

Zwei Feinheiten, die leicht falsch gemacht werden und deshalb je einen
Modultest haben: Das Fenster zählt ab dem **ältesten** wartenden Alarm — sonst
schöbe jeder neue Alarm den Versand weiter hinaus, und bei einem anhaltenden
Ausfall ginge nie eine Mail hinaus. Und eingereiht wird nur bei *neuen*
Alarmen, sonst entsteht die Flut über den Prüftakt statt über die Gerätezahl.

SMTP ist von Hand geschrieben (RFC 5321) — dieselbe Überlegung wie beim
SOAP-Client in ADR-0006. Drei der Fallstricke sind sicherheitsrelevant:
Zertifikatsnamensprüfung, Abwehr eingeschleuster Kopfzeilen über Adressen, und
dass die Antwort auf eine abgelehnte Anmeldung **nicht** durchgereicht wird —
manche Server geben dort den Benutzernamen zurück.

### Zwei Fehlgriffe bei den Nachweisen

Als Auffangserver hatte ich zuerst MailHog gewählt. Der Lauf blieb beim Ziehen
des Images stehen. Ein Prüflauf, der Internetzugang braucht, ist als Nachweis
unbrauchbar — der Auffang sitzt jetzt im Simulator, der ohnehin die
Fremdsysteme ersetzt.

Und der Nachweis für die RFC-2047-Kodierung war zunächst wertlos: Er verlangte
einen kodierten Betreff, erzeugte aber keinen mit Umlaut. Jetzt läuft die Regel
auf eine Einrichtung mit Umlaut im Namen; ohne eine solche wird der Nachweis
mit Begründung übersprungen statt scheinbar zu bestehen.

## Stand: 249 Modultests, 224 Systemnachweise — alle grün

## FA-UEB-010 — das Prüfintervall wirkte gar nicht

Der Auftrag war, die Wirkung des gruppenweisen Prüfintervalls nachzuweisen.
Beim Nachsehen: Es gab keine Wirkung nachzuweisen. Der Agent nahm einen
globalen Wert aus seiner Umgebung; die Spalte `pruefintervall_sekunden` der
Gerätegruppe wurde gepflegt, gespeichert, in Nachweisen belegt — und erreichte
den Agenten nie.

Im Umsetzungsstand stand FA-UEB-010 auf „erfüllt". Das war falsch: Belegt war
die Gruppenpflege, nicht die Überwachung. Die Sorte Lücke, die eine Statuszeile
nicht zeigt — zwei Teile je für sich fertig, und niemand hat geprüft, ob sie
verbunden sind.

Jetzt holt der Agent die Intervalle von der Zentrale und führt je Terminal
einen eigenen Takt. **Gemessen** wird am Simulator, der `GetPinStatus` je Karte
zählt: In 40 Sekunden 4 Abfragen für das Gerät mit 10-Sekunden-Gruppe, 0 für
das mit 120 Sekunden.

Der Umbau hat fünf andere Nachweise umgeworfen, alle mit „gemessen: 0" — die
Vorgabegruppe steht auf 60 Sekunden, und die Nachweise warteten 20 Sekunden auf
eine Verifikation. Kein Fehler, sondern die sichtbare Folge einer richtigen
Änderung. Der Nachweislauf setzt die Vorgabegruppe jetzt auf 10 Sekunden, das
kleinste zulässige Intervall.

## Stand: 249 Modultests, 228 Systemnachweise — alle grün

## gemSpec_Kon 4.3.10 — der konnektorweite KSR-Lauf

Der Konnektor aktualisiert Kartenterminals über seinen KSR-Client. Während
dieser Zeit darf der Roboter keine PIN-Verifikation versuchen — drei
Fehlversuche sperren die SMC-B.

Die Auswertung gab es schon. Geprüft war aber nur der **Einzelfall**: ein
Firmware-Update an einem Terminal. Ein KSR-Lauf betrifft mehrere Geräte
gleichzeitig, oft alle eines Standorts, und ein einziges übersehenes Gerät
reicht. Der Simulator kann jetzt einen konnektorweiten Lauf auslösen;
nachgewiesen sind 0 Versuche bei 4 gleichzeitig betroffenen Terminals und die
Wiederherstellung danach.

**Ein Fehler dabei gefunden:** Im frühen Rückgabepfad — Terminal nicht
erreichbar oder ohne Karte — wurde `aktualisierend` gar nicht gemeldet. Der
zuletzt gemeldete Wert blieb in der Zentrale stehen; die Oberfläche hätte
dauerhaft ein Update angezeigt, das längst vorbei ist.

**Was damit nicht belegt ist:** Der Agent erkennt den Zustand daran, dass der
Konnektor ihn im `GetPinStatus` mitliefert. Ob ein echter KoCo-Konnektor das
tut — oder den Lauf nur als Ereignis meldet — ist offen und gehört auf die
Liste für die Kalibrierung am Testaufbau. Das ist der Unterschied zwischen
„abgesichert" und „vermutlich abgesichert".

## Stand: 249 Modultests, 232 Systemnachweise — alle grün

## FA-PIN-040, FA-UEB-020, FA-UEB-030 — zweimal mehr als ein fehlender Nachweis

Alle drei standen als „nur Modultests" im Umsetzungsstand. Zwei davon hatten
echte Lücken.

**FA-PIN-040:** Die Zustände wurden erkannt, kein Versuch unternommen — aber
die *Handlung* stand nur auf der Lage-Seite. Wer die Geräteliste filtert, las
„Transport-PIN" und war so klug wie zuvor. Die Anforderung verlangt
ausdrücklich die erforderliche Handlung.

**FA-UEB-030:** `FIRMWARE_GEAENDERT` existierte als Ereignisart und wurde
nirgends ausgelöst — dieselbe Sorte Lücke wie bei FA-UEB-010. Aktualisiert der
Konnektor ein Terminal über seinen KSR-Client, erfuhr der Betreiber es
nirgends. Dazu fehlte die Flottenübersicht: Bei 5.000 Geräten ist die Frage
nicht „welche Firmware hat Gerät X", sondern „wie viele hängen zurück".

**FA-UEB-020** war tatsächlich in Ordnung. Der neue Nachweis prüft auch das
Gegenteil: dass bei gleichbleibendem Zustand *nichts* ins Protokoll kommt.

## Stand: 249 Modultests, 246 Systemnachweise — alle grün

## FA-AGT-040 und FA-INS-040 — beide Zeilen waren falsch beschriftet

FA-AGT-040 stand als „Agentenverwaltung in der Oberfläche", heißt im Katalog
aber **Versionsunterschiede**. FA-INS-040 stand als „Health-Checks", heißt
**Sicherung und Wiederherstellung**. Beide Male war eine plausibel klingende,
aber falsche Anforderung als fast fertig geführt — derselbe Fall wie zuvor bei
FA-ALA-020.

Eine falsch beschriftete Zeile ist gefährlicher als eine leere: Sie sieht aus
wie Fortschritt.

**Versionsunterschiede:** Der Agent meldet jetzt seine Protokollversion, die
Zentrale bewertet sie gegen eine Spanne und zeigt sie an. Die wichtigste
Festlegung: Ein Agent außerhalb der Spanne wird **nicht abgewiesen** — das
hieße keine PIN-Verifikation an diesem Standort. Beides nachgewiesen: dass die
Abweichung erkannt wird, und dass er weiterarbeitet.

**Wiederherstellung:** Drei der vier Akzeptanzpunkte waren erfüllt. Der vierte
fehlte — nach einer Wiederherstellung war nicht ersichtlich, welche PINs neu zu
hinterlegen sind. Der Fall: Die Zentrale wird zurückgespielt, der Agent aber
neu aufgesetzt; sein Schlüsselpaar ist ein anderes, die PINs unlesbar. Ohne
Anzeige bliebe die Praxis im Glauben, alles sei versorgt.

**Ein Nachweis meldete einen Fehler, wo keiner war:** Die Suche nach der
Test-PIN `123456` im Datenbankauszug fand sie in der ICCSN einer Demo-eGK
(`80276883110000123456`). Wer einem Nachweis dreimal so begegnet, glaubt ihm
beim vierten Mal nicht — und beim vierten Mal stimmt er.

## Stand: 249 Modultests, 260 Systemnachweise — alle grün

## FA-PIN-060 — PIN ändern, der Haken hinter dem nichts stand

Im Umsetzungsstand stand „PIN löschen ✔". Die Anforderung heißt **PIN ändern**
über `SICCT MODIFY VERIFICATION DATA`. Gebaut war das Löschen.

Jetzt geht beides. Die Änderung braucht zwei Werte — alte PIN als Nachweis,
neue als Ersatz —, beide im Browser verschlüsselt. Die Wiederholung wird dort
geprüft und muss es auch: RSA-OAEP ist nicht deterministisch, zwei Chiffrate
desselben Werts sehen verschieden aus.

**Der Punkt, an dem man Karten kaputt macht:** Auch das Ändern verbraucht bei
falscher alter PIN einen Versuch. Drei davon sperren die Karte. Vor jeder
Änderung läuft deshalb dieselbe Prüfung wie vor jeder Verifikation — bleibt nur
ein Versuch, bricht der Roboter ab.

Und die neue PIN wird erst hinterlegt, **nachdem** die Karte bestätigt hat.
Sonst hätte der Agent eine PIN im Speicher, die auf der Karte nicht gilt, und
würde bei jedem Durchgang einen Fehlversuch erzeugen — nach drei Durchgängen
wäre die Karte gesperrt, ohne dass jemand etwas falsch gemacht hat.

## Stand: 255 Modultests, 270 Systemnachweise — alle grün

## FA-PRO-050 und FA-PRO-060 — die vier Sichten sind vollständig

Geräte-Zeitstrahl und Vorgangsansicht stehen. Damit sind die vier Sichten aus
ADR-0005 komplett. Der Zeitstrahl fragt nach einem Gerät über die Zeit, die
Vorgangsansicht nach einem Vorgang über die Geräte — im Protokoll sucht man
Einträge, hier liest man eine Geschichte.

### Ein Fehler von mir, und ein Nachweis, der ihn verdeckte

Ich habe den Link zum Zeitstrahl per Textersetzung in die Gerätetabelle
eingefügt. Die Ersetzung traf **beide** Tabellen — auch die in der
Lage-Übersicht, wo die Argumentliste anders ist. Die Startseite antwortete
danach mit HTTP 500. Genau dieser Griff ist in diesem Projekt schon einmal
danebengegangen.

Schlimmer: Der Nachweis „jeder Navigationslink führt zu einer Seite" holt seine
Liste **aus der Startseite**. War die kaputt, fand er null Ziele und meldete
„0 tot" — bestanden. Ein Nachweis, dessen Prüfmenge aus dem Prüfgegenstand
stammt, kann bei dessen Totalausfall nicht anschlagen. Er hat jetzt zusätzlich
eine feste Liste.

Dazu zwei weitere Nachweisfehler: Der Alarmnachweis zählte alle
„Quittieren"-Knöpfe statt des einen geprüften Alarms, und die
Datenbank-Hilfsfunktionen waren erst 150 Zeilen nach ihrer ersten Verwendung
definiert.

## Stand: 261 Modultests, 282 Systemnachweise — alle grün

## FA-UPD-010 — neue Versionen erkennen

Der Roboter fragt die Registry ab und zeigt verfügbare Versionen mit deutschen
Änderungshinweisen. **Er liest nur** — ADR-0001 trennt Erkennen (eine
HTTPS-Abfrage, kein Risiko) vom Ausführen (Docker-Socket, entspricht Root auf
dem Host). Watchtower und Diun verlangen beides dauerhaft.

**Der Fehlerzustand ist die eigentliche Anforderung**, nicht die Abfrage. Ein
Token läuft nach Monaten ab; versagt die Prüfung dann still, sieht der
Betreiber nur, dass keine Updates gemeldet werden — und hält das für „es gibt
keine". So bleibt eine Sicherheitslücke ein Jahr ungepatcht. Der letzte Fehler
wird deshalb gespeichert und angezeigt, bis eine Abfrage wieder gelingt.

Zwei Details, die leicht falsch werden: Versionen werden über **Zahlen**
verglichen — als Text ist `1.10.0` kleiner als `1.9.0`, und der Betreiber bekäme
kein Update angeboten. Und `latest` wird nicht angeboten: ein Zeiger, keine
Version.

Der Nachweis läuft gegen eine Registry-Nachbildung im Simulator. Ein Prüflauf,
der Internetzugang braucht, ist als Nachweis unbrauchbar — dieselbe Lehre wie
beim Mailauffang.

## Stand: 268 Modultests, 295 Systemnachweise — alle grün

## Gerätegruppen je Praxis — und was dabei auffiel

Beim Anlegen von neun Gruppen, eine je Praxis, zeigte sich: Die Oberfläche
reichte die Einrichtung **hart verdrahtet** als `"Praxis"` durch. Alle Gruppen
hingen damit an derselben Einrichtung, obwohl FA-GER-050 verlangt, dass eine
Gruppe innerhalb genau einer Einrichtung liegt.

Bei einer Einzelpraxis fällt das nicht auf. Bei neun Praxen an einer Instanz —
dem Fall, für den die Mandantenfähigkeit überhaupt gebaut ist — ist es der
Unterschied zwischen brauchbar und nutzlos: Die Gruppe trägt das Prüfintervall,
und das soll je Standort verschieden sein können.

Die Einrichtung ist jetzt wählbar, die Übersicht nennt sie je Gruppe, und die
Gruppe trägt die Kennung der Organisationseinheit.

## Stand: 268 Modultests, 298 Systemnachweise — alle grün

## Der Agent kennt seine Einrichtung

Eine Ebene unter den Gruppen saß derselbe Fehler noch einmal, und dort wog er
schwerer: Der Standort-Agent meldete seine Geräte hart unter `"Praxis"`.

Egal an welchem Standort ein Agent lief — seine Terminals, Alarme und
Protokolleinträge trugen denselben Namen. Zwei Folgen:

- Alle Geräte fielen in **eine** Vorgabegruppe. Die neun eben angelegten
  Praxis-Gruppen blieben leer, das Prüfintervall je Standort hatte nichts zu
  steuern.
- **Die Mandantengrenze griff für Geräte nicht.** Ein Administrator der
  Kardiologie hätte die Terminals der Zahnarztpraxis gesehen. Genau der Fehler,
  vor dem CLAUDE.md warnt.

Die Einrichtung kommt jetzt **aus der Registrierung, nicht aus der Meldung**.
Das ist die sichere Richtung: Schickte der Agent sie mit, könnte ein
übernommener Agent Geräte in einer fremden Einrichtung unterschieben. Der
Administrator legt sie beim Erzeugen des Registrierungscodes fest — der einzige
Zeitpunkt, an dem ein Mensch weiß, wo das Gerät steht.

Damit es nicht wieder an einer Stelle vergessen wird, hat `pruefe()` — der
gemeinsame Weg aller Gerätealarme — die Einrichtung als **Pflichtparameter**
bekommen. Das erledigt jetzt der Compiler statt der Sorgfalt.

Dabei fiel ein Auslieferungsmangel auf: Das Registrierungsgeheimnis ließ sich
**nur über die Kommandozeile** erzeugen, in der Oberfläche fehlte die Maske
ganz. Ein Betreiber ohne IT-Personal konnte keinen zweiten Standort anbinden.
Die Agententafel trägt jetzt ein Formular mit Einrichtungsauswahl aus dem
eigenen Geltungsbereich.

### Was der Umbau freigelegt hat

Der erste Nachweislauf fiel um — zu Recht. Migration 6 legt die Vorgabegruppe
nur für die Einrichtung „Praxis" an. Meldete ein Agent an einem anderen
Standort ein Gerät, fand die Zentrale keine Gruppe dafür und **verwarf es
still**: Das Terminal stand im Protokoll, aber in keiner Gerätetabelle — nicht
sichtbar, ohne Gruppe, ohne Prüfintervall. Ein lautloser Ausfall der
Kernfunktion, den man nicht sehen konnte, solange alles „Praxis" hieß.

Die Vorgabegruppe entsteht jetzt beim ersten Bedarf, idempotent, und der stille
Fehlschlag meldet sich.

Zwei bestehende Nachweise fielen ebenfalls um, sobald es mehr als eine
Einrichtung gab: Sie fragten `WHERE ist_vorgabe` ohne Einrichtung und nahmen
damit an, es gebe genau eine im System. Auch das war nie richtig, es hielt nur
zufällig.

## Stand: 264 Modultests, 320 Systemnachweise — alle grün

Die Testzahl ist diesmal **gemessen**, nicht fortgeschrieben: 25 Testklassen,
264 Testläufe, keine übersprungen. Die zuvor geführte Zahl 268 lässt sich nicht
belegen und war offenbar mitgeschleppt.

## UX-Refactoring — Einheitlichkeit erzwingen statt pflegen

Der PO hat die Oberfläche kritisiert: unregelmäßige Abstände, überflüssige
Knöpfe neben klickbaren Zeilen, fehlende Sortierung und Suche, fehlende
Kurzhinweise. Der erste Schritt war nicht Umbauen, sondern Zählen:

| | vorher | nachher |
|---|---:|---:|
| Bedienelemente ohne Kurzhinweis | 142 von 154 | **0 von 141** |
| klickbare Tabellenzeilen | 0 von 97 | **57** |
| verschiedene Abstandswerte | 37 | **6** (ein Raster) |
| Abstände in Inline-Styles | 12 | **0** |

**Die Ursache war nicht Nachlässigkeit.** 142 fehlende Hinweise entstehen nicht
durch 142-mal Unaufmerksamkeit, sondern weil es keinen gemeinsamen Baustein
gab: Jede Seite baute ihre Knöpfe selbst, also wich jede ab. Einheitlichkeit,
die von Sorgfalt abhängt, zerfällt mit jeder neuen Seite.

Deshalb war die Antwort nicht, 142 Attribute nachzutragen, sondern
`Bedienelemente.java` einzuführen — mit dem **Kurzhinweis als
Pflichtparameter**. Ein Knopf ohne Erklärung lässt sich damit nicht bauen. Das
erledigt der Compiler statt der Sorgfalt.

Der sichtbarste Einzelfehler war ein anderer: Die Kachel „Brauchen
Aufmerksamkeit" hat eine zweizeilige Überschrift, ihre Nachbarn eine einzeilige
— also stand ihre Zahl tiefer. Jetzt liegen alle Zahlen auf einer Linie.

Neu: `pruefungen/oberflaeche-pruefen.sh` mit 17 Prüfpunkten, im Hauptlauf. Er
prüft nicht, ob Sortierung *angeboten* wird, sondern ob sie die Reihenfolge
*ändert*; nicht ob ein Suchfeld da ist, sondern ob es filtert.

Der neue Nachweis hat sofort etwas gefunden, das der manuelle Durchgang
übersehen hatte: Der Knopf „Im Bestand ansehen" auf der Ermittlungsseite trug
keinen Kurzhinweis. Er erscheint nur, wenn bereits Geräte übernommen wurden —
beim Durchsehen von Hand war die Liste leer.

## Stand: 264 Modultests, 320 Systemnachweise — alle grün

## Refactoring und vollständige Dokumentation

Auftrag des PO: vollständiges Refactoring, Analyse des Quellcodes, und vier
Dokumente — für Entwickler, Anwender, Architektur und DevOps.

### Erst gemessen, dann geschnitten

| Befund | vorher | nachher |
|---|---:|---:|
| `starten()` — eine Methode mit 81 Routen | **1450 Zeilen** | **257** |
| verschluckte `SQLException` | **20** | **0** |
| Kommentaranteil | 23 % | 23 % |
| `TODO`/`FIXME` | 0 | 0 |

**Die 20 verschluckten Datenbankfehler waren der ernstere Fund.** Ein stiller
SQL-Fehler ist praktisch unauffindbar: Der Aufruf liefert eine leere Liste, die
Oberfläche zeigt „keine Einträge", und niemand erfährt warum. Genau so blieb das
fehlerhafte `WHERE 1=1AND` monatelang unentdeckt. Sie melden jetzt mit Klassen-
und Methodennamen — bewusst ohne weiterzuwerfen, damit ein Zählfehler in einer
Nebenanzeige nicht die Oberfläche abschießt.

Die 81 Routen liegen in zehn thematischen Bündeln. Die Reihenfolge innerhalb
eines Bündels bleibt exakt erhalten: Javalin nimmt die zuerst passende Route,
also muss `/bestand/terminal/neu` vor `/bestand/terminal/{id}` stehen.

### Ein Versuch, den ich zurückgenommen habe

Die weitere Unterteilung von `routenBestand` (408 Zeilen) scheiterte: Das
automatische Verschieben von Java-Code kam mit Klammern innerhalb von
Textblöcken nicht zurecht, der erzeugte Code übersetzte nicht. Ich habe den
geprüften Stand wiederhergestellt und den Punkt als bewusst offen dokumentiert,
statt ihn mit der Brechstange zu erzwingen.

### Vier neue Dokumente

| Datei | Für wen |
|---|---|
| `docs/07-entwicklerhandbuch.md` | wer am Code arbeitet — inklusive „Fallen, die schon zugeschnappt sind" |
| `docs/08-anwenderhandbuch.md` | Praxispersonal ohne IT-Kenntnisse |
| `docs/09-devops-installation.md` | Netzschnitt, Secrets, Proxy, Härtung, Wiederherstellung |
| `docs/10-codeanalyse.md` | der gemessene Zustand samt dem, was bewusst offen bleibt |

Dazu `docs/README.md` als Einstieg, die Architekturbeschreibung um zwei
Querschnittskapitel erweitert, und FA-BEN-035 im Anforderungskatalog nachgetragen
— eine Funktion ohne Abnahmekriterium ist nicht abnehmbar.

## UX-Nacharbeit — was ein Werkzeug nicht findet

Das Refactoring hatte 20 maschinelle Prüfpunkte hinterlassen, alle grün.
Trotzdem fand eine Durchsicht des PO an der laufenden Oberfläche sechs echte
Mängel:

| Befund | Ursache |
|---|---|
| Knöpfe über die halbe Feldbreite gestreckt | steckten in `.feldgruppe` mit `flex:1 1 14rem` |
| Hinweiskästen zweispaltig | `display:flex` für ein Symbol, das es nie gab |
| Kacheln, Hinweis und Knopf klebten aneinander | Container ohne Abstand zwischen den Kindern |
| Tabellen liefen aus dem Bild | zehn Spalten, 24 px Zellenrand je Seite, und eine eigene Regel, die die Ankreuzspalte von 42 auf 176 px aufblies |
| IP brach mal um, mal nicht | Kennung und Adresse in einer Zeile |
| **59 Ankreuzfelder, alle angehakt** | die Liste folgte der Datenbank, nicht der Frage |

Der letzte Punkt war der wichtigste. „Alles an" als Vorgabe heißt: abwählen,
was man nicht will — bei 59 Möglichkeiten tut das niemand, also filtert
niemand. Jetzt vier Sichten, jede beantwortet eine echte Frage: *Alles · Nur
Probleme · Was der Roboter tat · Änderungen durch Menschen.*

Tabellenbreite, gemessen bei 1280 px Fenster: **1274 px → 984 px** bei 982 px
Platz.

### Zwei Fehler, die dabei auffielen

**Vier Terminals fielen still durch.** Der Agent meldete sie, keines wurde
erfasst: Die Einrichtung hatte eine Gruppe „Alle Geräte" ohne
Vorgabe-Kennzeichen, das Anlegen scheiterte am Namensindex, `ON CONFLICT`
schluckte es. Gefunden hat es die Warnung aus dem vorigen Umbau — genau dafür
war sie da.

**Mein eigener Modultest war falsch konstruiert.** Er erwartete, dass bei
mehreren Gruppen immer die älteste zur Vorgabe wird. Richtig ist das nur, wenn
der Name „Alle Geräte" belegt ist: Hat eine Praxis „Notaufnahme" und „Archiv",
muss eine eigene Vorgabegruppe entstehen — sonst landete jedes neu erkannte
Gerät in der Notaufnahme.

### Weiter auf Vorgabe des PO

- **Demodatensatz mit Gruppen:** eine je Einrichtung, mit ihren vier Terminals.
  Nicht je Standort — eine Gruppe liegt nach FA-GER-050 in genau einer
  Einrichtung.
- **Kopf der Seitenleiste:** „3 von 4 Agenten verbunden" statt „Agent
  verbunden", dazu die laufende Version. Ohne Manifest steht
  „Entwicklungsstand" statt einer erfundenen Nummer.

Vier der sechs Befunde ließen sich nachträglich in Prüfpunkte übersetzen.

## Stand: 269 Modultests, 331 Systemnachweise — alle grün

## Datensicherung, Einstellungen, Wiederherstellung

Vorgabe des PO: Menü „Einstellungen" mit Systemtest-Rubrik, drei rollierende
Sicherungspläne, verschlüsselter Download und Upload, gezieltes
Wiederherstellen mit Neustart.

Bis dahin war die Sicherung ein Kapitel im Betriebshandbuch mit Befehlen zum
Abtippen — das setzt jemanden voraus, der sie regelmäßig ausführt. In einer
Praxis ohne IT-Personal gibt es den nicht.

### Gebaut

| | |
|---|---|
| Drei Pläne | täglich 7, wöchentlich 5, monatlich 12 Stände; stündliche Fälligkeitsprüfung |
| Rotation | entfernt den ältesten Stand, nie den neuesten |
| Fehlschlag | ist ein Alarm, keine Logzeile |
| Download | AES-256-GCM, Schlüssel aus Argon2id, Kopf als AAD mitgeschützt |
| Upload | prüft die Datei als Ganzes; ein gekipptes Byte wird erkannt |
| Wiederherstellung | Sicherheitskopie davor, ganz oder gar nicht, danach Neustart |
| Einstellungen | Demodatensatz und Leistungsmessung sind von „Instanz" hierher umgezogen |

Falscher Schlüssel und veränderte Datei führen zur **gleichen** Meldung — die
Unterscheidung wäre für einen Angreifer nützlicher als für den Betreiber.

### Drei Funde beim Bauen

**Der Prüfstand testete nicht das Auslieferungsimage.** Er startete die JAR in
einem nackten JRE; alles, was im Bild steckt, blieb ungeprüft. Aufgefallen an
`pg_dump`. Das widersprach der eigenen Definition of Done — jetzt läuft er
gegen das Bild, das beim Kunden landet.

**Zwei Orte für dieselbe Datei.** `docker compose build` baut im Container,
`mvn package` lokal. Der Prüfstand mountet die lokale JAR — nach einer
Korrektur nur über `compose build` lief dort noch der alte Stand.

**Der Port im Hostnamen.** Beim Zerlegen der JDBC-Adresse blieb
`datenbank:5432` als Rechnername stehen; `pg_dump` meldete „could not translate
host name".

## Stand: 269 Modultests, 349 Systemnachweise — alle grün

## Diagramme: Mermaid statt ASCII

Befund des PO: „ASCII-Bilder sind altbacken." Berechtigt — sie lassen sich
nicht zoomen, nicht durchsuchen, auf dem Telefon nicht lesen, und ein
Sequenzdiagramm mit acht Beteiligten wird darin unlesbar.

**25 Diagramme umgestellt**, in 14 Dateien. Mermaid statt PNG, weil die Quelle
im Repository bleibt: versionierbar und diffbar. Ein Bild, dessen Quelle
woanders liegt, veraltet als Erstes — der Code ändert sich, das Bild bleibt.

Dabei fiel eine echte Lücke auf: **Es gab keine Sequenzdiagramme**, obwohl
arc42 sie in der Laufzeitsicht vorsieht. Auf die Frage „wer ist Client, wer
Server?" musste ich die Antwort aus dem Code zusammensuchen, statt sie im
Architekturdokument zu finden. Vier sind neu:

- wie Agent und Zentrale miteinander reden (der Agent ist ausschließlich Client)
- eine PIN vom Browser bis zur Karte
- Wiederherstellung einer Sicherung mit Neustart
- der zusammengesetzte PEviM-Ablauf in der TI-Analyse

Neu: `pruefungen/diagramme-pruefen.sh` im Hauptlauf. Es **rendert jedes
Diagramm wirklich** — ein Tippfehler in der Syntax fiele sonst erst auf, wenn
jemand das Dokument öffnet, und dort stünde dann eine Fehlermeldung statt eines
Diagramms. Das wäre schlimmer als die ASCII-Zeichnung davor.

Die zwei Diagramme in den ADRs bleiben unverändert: Nach CLAUDE.md werden ADRs
nicht überschrieben, auch nicht kosmetisch.

## Version 0.2.0 — und eine Korrektur beim Taggen

Auf die Frage des PO „an welcher Stelle wendest du SICCT an?" fiel eine
**fachlich falsche Aussage** in der Dokumentation auf.

**Der PIN-Roboter spricht kein SICCT.** Er redet SOAP mit dem Konnektor und
HTTPS mit der Managementschnittstelle des Terminals. `SICCT PERFORM
VERIFICATION` und `SICCT MODIFY VERIFICATION DATA` sendet der **Konnektor**.

An sechs Stellen stand sinngemäß „PIN ändern über `SICCT MODIFY VERIFICATION
DATA`" — das liest sich, als sendeten wir das Kommando. Auch mein neues
Sequenzdiagramm zeigte den Pfeil `Agent → Terminal: SICCT …`. Falsch: Dort läuft
HTTPS.

Das ist keine Kosmetik. Wer nach dieser Doku eine Fehlersuche beginnt, sucht das
Kommando an der falschen Stelle — und übersieht, dass der eigentliche Knackpunkt
die **Reihenfolge** ist: Die Sitzung an der Managementschnittstelle muss stehen,
*bevor* das Kommando des Konnektors eintrifft. Trifft es ein, ohne dass die
Rolle `SMC-B_Nutzer` angemeldet ist, verlangt das Terminal die Eingabe am Gerät
(`A_24081`), und der Vorgang ist verloren.

Korrigiert in Anforderungskatalog, Umsetzungsstand, Umsetzung 01 und 23, im
Sequenzdiagramm und im Javadoc von `TerminalClient`. Neu in der Architektur:
Abschnitt 6.2a-2 mit einer Tabelle, welche Strecke welches Protokoll spricht und
wer davon wir sind.

Neu: `CHANGELOG.md` — es gab keine Änderungshinweise, obwohl die DevOps-Anleitung
an zwei Stellen darauf verweist.

## Stand: 269 Modultests, 352 Systemnachweise — alle grün

## Der PIN-Übergabeweg war falsch modelliert

Der PO fragte: „An welcher Stelle wendest du SICCT an?" — und stellte fest: „Der
Konnektor schickt keine PIN an das Terminal." Er hatte recht. Die daraufhin
geforderte Recherche in den **Herstellerdokumenten** hat einen Fehler im Kern
des Produkts aufgedeckt.

### Was wir angenommen hatten

Der Agent baut eine Sitzung auf, **schiebt** die PIN hin (`POST /pin`), löst
`VerifyPin` aus, meldet sich ab.

### Was die Spezifikationen sagen

**CHERRY ST-1506** (Remote Management Specification v2.8, 77 Seiten, öffentlich):
Es gibt **zwei** WebSocket-Schnittstellen — `cobra` für die Verwaltung und
`cobra-smcb` für die PIN. Der Client holt einen ephemeren AES256-Schlüssel
(30 s gültig), verbindet sich, und **das Terminal** sendet einen
`AuthenticateRequest` mit Challenge. Danach:

> „While authenticated the Remote SMCB API will send `OutputRequests` and
> **`InputPinRequests` if a PIN verification is requested on the respective slot
> from the Konnektor**."

`InputPinRequest`, Richtung **„ST1506 to Client"**. Das Terminal fragt, der
Client antwortet.

**Worldline ORGA 6141** (Bedienungsanleitung V24.5.1, Abschnitt 7.2.3.4):
dasselbe Muster, eigene Worte — die Rolle **„SMC-B PIN Provider"**, an die die
PIN-Eingabeaufforderung „per RMI **umgeleitet**" wird. Auch hier WSS.

### Was das bedeutet

| | angenommen | tatsächlich |
|---|---|---|
| Muster | Agent sendet | **Terminal fragt, Agent antwortet** |
| Verbindung | kurz | **dauerhaft offen je Terminal** |
| Authentisierung | Kennwort | **Challenge-Response, CMAC-AES256** |
| Gleichzeitig | beliebig | **eine Verbindung je Gerät** |

Die harte Folge ist die **Nebenläufigkeit**: eine dauerhafte Verbindung je
Terminal, bei 5.000 Geräten aus dem Mengengerüst. Aus einer Frage der
Abfragelast wird eine der Verbindungsverwaltung — ein anderes Problem als das,
für das die Ablaufsteuerung gebaut ist.

Und **FA-PIN-060 ist über diesen Weg gar nicht möglich**: CHERRY schließt
PIN-Änderung dort ausdrücklich aus.

### Was die 352 grünen Nachweise darüber sagen

Nichts. Sie belegen, dass Agent und Simulator zueinander passen — der Simulator
bildet unsere Annahme nach. Derselbe Fehlertyp wie beim RSA-OAEP, wo beide
Seiten gleich falsch waren, nur diesmal in der Kernfunktion.

### Vorgehen

Festgehalten in [ADR-0011](docs/adr/0011-pin-uebergabe-ist-eine-anfrage-des-terminals.md),
korrigiert in Analyse, Architektur und Umsetzungsstand. **Der Umbau folgt erst
nach der Messung am Testaufbau** — ihn auf eine Dokumentenlektüre zu stützen
wäre derselbe Fehler wie der, den diese ADR korrigiert, nur mit besserer Quelle.

---

## Der Umbau: das Terminal fragt, der Agent antwortet

Der PO hat den Umbau beauftragt, **bevor** die Messung am Testaufbau lief. ADR-0011
sah die andere Reihenfolge vor; das ist seine Entscheidung, und sie ist im
Nachtrag der ADR festgehalten, damit später niemand rätselt.

### Was gebaut wurde

| Baustein | Was er tut |
|---|---|
| `SmcbProtokoll` (gemeinsam) | Nachrichtenschema, CMAC-AES256, die Regeln aus Abschnitt 7.7.8 |
| `SmcbVerbindung` (Agent) | eine Verbindung: Zustand, Challenge-Response, Beantworten |
| `SmcbVerbindungen` (Agent) | alle Verbindungen: Sollzustand, Sperrfrist, Aufräumen |
| `Verifikationsablauf` (Agent) | von „PIN senden" auf „anstoßen und nachprüfen" — **die PIN kommt darin nicht mehr vor** |
| `SmcbApiNachbildung` (Simulator) | die Gegenstelle, eigener RFC-6455-WebSocket |

### Zwei Dinge, die im alten Modell gar nicht auftreten konnten

**Der Slot ist nicht das Kartenhandle.** Das Terminal fragt nach `Slot1`, der
Konnektor kennt `card-smcb-001`. Die Zuordnung kommt jetzt aus `SlotId` im
`CardInfoType` und wird in jedem Durchgang **vollständig neu** aufgebaut — eine
gezogene Karte muss verschwinden, sonst antwortete der Agent später mit der PIN
einer Karte, die nicht mehr im Gerät steckt. Ein unbekannter Slot führt zur
Ablehnung mit Begründung, nicht zu einem Versuch mit irgendeiner PIN.

**Die Sicherungslogik ist von einer Vorprüfung zur einzigen Bremse geworden.**
Solange die Verbindung steht, kann jede Stelle am Konnektor eine PIN-Anfrage
auslösen — nicht nur wir. Vorher entschied der Agent, ob er eine PIN schickt;
jetzt entscheidet er, ob er auf eine Frage antwortet, die schon gestellt ist.
Deshalb läuft die vollständige Prüfung bei **jeder** Anfrage, gegen einen
Kenntnisstand, der höchstens 90 Sekunden alt sein darf.

Dass 90 Sekunden reichen, hängt nicht an der Frische eines Wertes, sondern an
einer Kette: Es wird nur bei mehr als einem verbleibenden Versuch gehandelt, und
jeder erkannte Rückgang hält die Automatik an.

### Was ein Modultest gefunden hat, bevor es jemand anders tat

Der Test „Ein Gerät ohne Remote SMCB API wird nicht im Takt bombardiert" deckte
auf, dass die Sperrfrist nach einem Fehlschlag **nie aufgeräumt** wurde. Bei
5.000 Terminals über Monate Laufzeit ein Speicherleck, das niemand bemerkt.
Beim Beheben fiel die zweite Frage auf: Ein flatterndes Gerät hätte die
Sperrfrist umgehen können, indem es kurz aus dem Sollzustand fällt und
wiederkommt — genau das Muster, das ein wackeliges Netz erzeugt. Beides
behoben, beides mit eigenem Test.

### Der Nachweis prüft den Weg, nicht das Ergebnis

Das ist die Lehre aus dem Fund von gestern. Ein Nachweis, der „Karte
freigeschaltet" prüft, war schon einmal grün, während das Übergabemodell falsch
war. Deshalb zählt der Simulator jetzt mit, wie oft eine PIN über `cobra-smcb`
geliefert wurde, und `pruefungen/smcb-durchstich.sh` fragt diesen Zähler ab —
gegen den ausgelieferten Agenten, kein Ersatzprogramm.

Neun Prüfpunkte, darunter drei, die belegen, was **nicht** passieren darf: keine
Übergabe bei abgeschaltetem PEviM, kein Verbindungsaufbau ohne hinterlegte PIN,
kein belegter Verbindungsplatz an einem Gerät ohne PIN.

### Was weiterhin offen ist

Zeitverhalten und Verbindungsdauer an echter Hardware, 1.250 offene Verbindungen
unter Last, die Worldline-Variante, `FA-PIN-060`. Der Umbau macht die Messung am
Testaufbau nicht überflüssig — er gibt ihr einen konkreten Gegenstand.

---

## Der Demodatensatz wird lebendig

**Vorgabe PO, 11.08.2026:** In allen neun Praxen steht am Empfang ein
Kartenterminal mit eingelegter SMC-B, alle Geräte laufen auf der neuesten
Firmware und unterstützen Remote-PIN, für alle Karten sind PINs hinterlegt, alle
Geräte antworten.

### Was fehlte

Der Demodatensatz war reine Bestandsverwaltung: 36 Geräte in der Datenbank, aber
kein einziges antwortete. Die Oberfläche zeigte 36 Zeilen im Zustand
„unbekannt". Man konnte darin die Mandantentrennung prüfen — aber nicht sehen,
was das Produkt eigentlich tut.

### Was gebaut wurde

`Zustand.mitDemoteilnehmern()` bildet dieselben 36 Geräte im Simulator nach. Die
Kennungen müssen dabei genau zu `Demodaten.kuerzel()` passen (`KT-NKAR-EMP`,
Kartenhandle `card-smcb-NKAR`) — sonst meldet der Agent Geräte, die zu keiner
Praxis gehören, und neben jedem Demogerät stünde ein zweites, unbekanntes.

Ergebnis nach einem Prüfintervall: neun Verbindungen zur Remote SMCB API stehen,
neun Karten sind freigeschaltet, **genau eine PIN-Übergabe je Karte**. Danach
Ruhe — die Verbindungen bleiben, weitere Übergaben gibt es nicht.

### Was beim Ausprobieren auffiel

**„Kardiologie Nord" hatte plötzlich acht Geräte.** Der Agent ist für diese
Einrichtung registriert, also landeten die vier Geräte des Standardaufbaus dort
— darunter das Terminal ohne PEviM und das mit abgeschaltetem PEviM. Sie
gehörten zu keiner Praxis und widersprachen genau dem, was der Demodatensatz
zeigen soll.

Der Demoaufbau **ersetzt** den Standardaufbau deshalb, statt ihn zu ergänzen.
Umgekehrt gilt dasselbe: Der Prüfstand setzt `DEMO_TERMINALS` nicht und misst
weiter gegen die vier Geräte mit ihren Fehlerfällen. Zwei Ausgangslagen, die
einander ausschließen — und jede hat ihren Zweck.

### Dieselbe Falle zum zweiten Mal

Der Durchstichnachweis wurde plötzlich rot bei „der Simulator bietet die Remote
SMCB API an" — obwohl er sie offensichtlich anbot, weil alle folgenden Nachweise
sie nutzten.

Ursache: `docker logs … | grep -q` unter `set -o pipefail`. grep beendet nach
dem ersten Treffer, `docker logs` bekommt SIGPIPE, die Pipeline gilt als
gescheitert. Beim ersten Lauf ging es zufällig gut, weil die Ausgabe noch kurz
war.

**Diese Falle steht seit Tagen im Entwicklerhandbuch — sie hat schon einmal 25
Nachweise erwischt.** Ich habe sie trotzdem wieder eingebaut. Jetzt steht die
Warnung zusätzlich direkt im Skript, an der Stelle, wo sie zuschlug. Ein
Eintrag in einer Fallenliste hilft nur dem, der die Liste vorher liest.

---

## PINs und Zugangsdaten im Demodatensatz — und ein Anzeigefehler

**Vorgabe PO, 11.08.2026:** „Kannst du dann bitte auch die SMC-B PIN und die
Zugangsdaten zu den KT in die Demo-Datenbank ablegen. Ich sehe dort keine PINs."

### Der Anzeigefehler dahinter

Die PINs *waren* da — der Agent hatte sie und schaltete damit neun Karten frei.
Die Oberfläche zeigte trotzdem „keine PIN hinterlegt". Ursache:

> Die Zentrale leitete das Kennzeichen allein aus **ihren eigenen Aufträgen** ab.
> Eine PIN, die auf einem anderen Weg zum Agenten kam — aus seiner
> verschlüsselten Ablage nach einem Neustart, aus der Umgebung im Testaufbau —,
> war für sie unsichtbar.

Das ist kein Demo-Problem. Es trifft jede Instanz nach einem Neustart des
Agenten: Er lädt seine PINs aus der Ablage, die Zentrale weiß nichts davon.

**Behoben:** Der Agent meldet in jedem Bestandsbericht mit, für welche Karten er
eine PIN hat — nur Kartenhandles, nie ein Wert, nie ein Chiffrat. Er ist an
dieser Stelle die Wahrheit.

Der gemeldete Bestand **ergänzt nur, er löscht nie.** Ein Eintrag, den der Agent
nicht mehr meldet, bleibt stehen — genau das ist die Warnung „PIN neu zu
hinterlegen" nach einem Agentwechsel (FA-INS-040). Würde hier gelöscht,
verschwände die Warnung zusammen mit dem Problem, das sie meldet. Ein eigener
Test hält das fest, samt Begründung im Fehlertext.

### Zugangsdaten

Jedes Demo-Terminal hat jetzt einen Administrationszugang, jeder Demo-Konnektor
ein Kennwort — **verschlüsselt, auf demselben Weg wie ein echtes** (ADR-0008).
Ein Demodatensatz, der sein Kennwort im Klartext ablegte, ließe genau die Stelle
ungeprüft, auf die es ankommt. Der Test prüft beides: dass das Chiffrat da ist,
und dass das Kennwort nirgends im Klartext in der Datenbank steht.

Fehlt der `GEHEIMNIS_SCHLUESSEL`, entstehen die Demodaten trotzdem — nur ohne
Zugangsdaten. Wer den Datensatz ausprobieren will, hat den Schlüssel oft noch
nicht gesetzt; ein Abbruch wäre die schlechtere Wahl.

### Ein Nachweis, der von der Sitzung abhing

Der Excel-Nachweis wurde rot: `ModuleNotFoundError: No module named 'openpyxl'`.
Die Python-Umgebung lag im Scratchpad **einer Sitzung**, mit der Sitzungskennung
im Pfad. Beim nächsten Lauf war das Verzeichnis weg.

Ein Nachweis, dessen Ergebnis davon abhängt, *wer* ihn startet, ist kein
Nachweis. Die Umgebung liegt jetzt unter `pruefungen/.venv` und wird bei Bedarf
selbst angelegt. Lässt sie sich nicht herstellen, meldet der Nachweis
**„NICHT PRUEFBAR"** statt rot — „bestanden" wäre gelogen, „fehlgeschlagen" eine
Falschmeldung über das Produkt.

### Zwei Nachweise, die den Fix erst richtig gemacht haben

Der Vollauf meldete danach zwei rote Nachweise — beide berechtigt.

**„Ohne fehlende PIN erscheint die Tafel gar nicht" (FA-INS-040).** Die Warnung
„PIN neu zu hinterlegen" erschien, obwohl nichts fehlte. Ursache war nicht der
neue Meldeweg, sondern eine Schwäche, die er sichtbar machte:

> `schluessel_gemeldet` wurde bei **jeder** Bestandsmeldung fortgeschrieben. Das
> Feld hieß damit faktisch „zuletzt gesehen", nicht „seit wann ein anderer
> Schlüssel". Die Warnung vergleicht es aber mit dem Zeitpunkt der PIN — und
> galt so binnen eines Prüfintervalls für jede vorhandene PIN.

Ein Fehlalarm, der die echte Warnung wertlos macht. Der Zeitpunkt wird jetzt nur
bei einer **tatsächlichen Änderung** des Schlüssels gesetzt. Drei Tests halten
das fest, samt Gegenprobe: Nach einem echten Schlüsselwechsel muss die Warnung
kommen.

Der Nachweis selbst hatte einen zweiten Fehler: Er setzte `schluessel_gemeldet`
künstlich auf jetzt, um einen Agentwechsel nachzustellen — und räumte das nicht
wieder auf. Alles danach maß einen Zustand, den er selbst erzeugt hatte.

**„Die PIN erreicht den Standort-Agenten und wird quittiert".** Der Nachweis
zählte `pin_hinterlegt` über **alle** Agenten. Das Kennzeichen hängt aber an
(agent_id, kartenhandle), und ein Prüflauf registriert mehrere Agenten
nacheinander — seit dem neuen Meldeweg melden sie alle dieselbe Karte. Der
Nachweis fragt jetzt nach dem Agenten dieses Laufs. Das ist präziser, nicht
schwächer: Die Aussage lautet „dieser Agent hat die PIN bekommen".

---

## Ein realistisches MVZ — und was dabei an der Oberfläche auffiel

**Vorgaben PO, 11.08.2026:** Praxisnamen durch erfundene Arztpraxennamen
ersetzen, ein realistisches MVZ-Szenario nachbilden. Dazu der Befund aus einem
Screenshot: „Mit der Bezeichnung der KT in dieser Liste kann niemand was
anfangen. Zum einen heissen die KT alle gleich, und ich weiß nicht zu welcher
organisatorischen Einheit das KT gehört."

### Das Szenario

Neun Betriebsstätten eines MVZ, drei Standorte, gemischte Fachrichtungen und
**unterschiedliche Größen** — von der Radiologie mit zwei Geräten bis zur
Hausarztpraxis mit sechs. Das ist der eigentliche Punkt: Eine Oberfläche, die
mit neun gleichförmigen Vierergruppen gut aussieht, sagt nichts über eine
Betriebsstätte mit sechs Geräten und eine mit zwei.

Das Szenario steht in `gemeinsam/Demoszenario.java`. Von dort lesen **beide**
Seiten: die Zentrale für den Bestand, der Simulator für die Geräte. Zwei
gepflegte Listen wären auseinandergelaufen, und die Folge wäre sichtbar
gewesen — neben jedem Demogerät ein zweites, unbekanntes.

### Der Befund des PO

Neun Geräte hießen „Behandlung 1". Die Zeile trägt jetzt die Betriebsstätte,
und zwar **aus dem Bestand, nicht aus der Meldung des Agenten**. Ein Agent kennt
genau eine Einrichtung — die aus seiner Registrierung. In einem MVZ betreut er
Geräte mehrerer Betriebsstätten, und dann trügen in der Oberfläche alle
denselben Namen.

### Was daneben im Bild stand

27 von 36 Geräten unter „Was zu tun ist", jedes mit „Der Zustand konnte nicht
ermittelt werden". Es sind die Behandlungsterminals — sie haben gar keine
SMC-B. Es gibt bei ihnen nichts freizuschalten, und „unbekannt" ist hier keine
Unsicherheit, sondern die richtige Antwort: Es gibt nichts zu wissen.

Die Tafel zeigt jetzt nur WARNUNG und FEHLER. Ein Gerät ohne SMC-B meldet
sachlich „keine SMC-B" statt einer Formulierung, die nach Störung klingt.

**Eine Liste, in der fast alles steht, sagt nichts mehr.** Der Zweck der
Lage-Übersicht ist, dass jemand unter Zeitdruck in Sekunden erkennt, ob etwas zu
tun ist. Das war mit 27 handlungslosen Zeilen unmöglich — und es stand seit
Tagen so da, ohne dass es jemandem aufgefallen wäre. Auffällig wurde es erst,
als der Demodatensatz echte Zustände bekam. Vorher zeigte die Oberfläche 36
Zeilen „unbekannt", und das sah nach einer Eigenart des Datensatzes aus statt
nach einem Fehler in der Anzeige.

### Karteileichen

`pin_hinterlegt` blieb beim Löschen des Demodatensatzes stehen. Nach zwei
Neuaufbauten standen dort sechzehn Kennzeichen für neun existierende Karten. Das
Löschen nimmt sie jetzt mit — und zwar vor den Terminals, danach sind die
Kartenhandles nicht mehr auffindbar.

---

## Kartenterminals ohne Konnektor — und eine Liste, die sich nach der Praxis ordnet

**Vorgaben PO, 11.08.2026:**
1. „Es sollen auch KT manuell aufgenommen werden die nicht aus einem Infomodell
   aus einem Konnektor ausgelesen wurden. Dann soll die Datenbeziehung zu einer
   zugeordneten Praxis erfolgen."
2. „Die Standardsortierung der KT Liste soll nach Konnektor oder Praxis
   erfolgen."

### Der Konnektor war Pflicht — und das ging an der Praxis vorbei

Bis dahin galt: erst der Konnektor, dann das Terminal, und die Einrichtung ergab
sich aus dem Konnektor. Das setzt eine Reihenfolge voraus, die es im Alltag
nicht gibt: Ein Gerät wird geliefert und aufgestellt, **bevor** es am Konnektor
angemeldet ist.

Jetzt ist die **Einrichtung das Pflichtfeld und der Konnektor die Ergänzung**.
Technisch stand die Einrichtung immer schon in der Zeile — sie war nur fachlich
abgeleitet.

Ein Gerät ohne Konnektor nimmt nicht an der PIN-Automatik teil; das SICCT-
Kommando sendet der Konnektor. Das steht **oben auf der Geräteseite**, mit der
Auswahl zum Nachtragen direkt darunter. Wer den Hinweis liest, will ihn genau
jetzt erledigen.

### Eine Falle, die zuschnappen wollte

Die Abfrage der Geräteliste verband mit `JOIN konnektor`. Ein Terminal ohne
Konnektor wäre damit **aus der Liste gefallen** — ausgerechnet das Gerät, das
noch Aufmerksamkeit braucht. Jetzt `LEFT JOIN`, und ein Test hält es fest.

Dieselbe Sorgfalt an der Anzeige: In der Spalte steht „nicht zugeordnet" statt
eines leeren Feldes. Leer wäre die schlechteste Antwort — man sähe nicht, ob die
Angabe fehlt oder die Anzeige klemmt.

### Sortierung: die Praxis zuerst

Vorgabe war alphabetisch nach Gerätenamen. Das ist die falsche Grundordnung,
seit das Demoszenario ein MVZ ist: Neunmal „Behandlung 1" untereinander ist
keine Liste. Jetzt Einrichtung, dann Konnektor, dann Gerätename — man findet ein
Gerät dort, wo man es sucht.

### Zwei Testfehler, die wie Produktfehler aussahen

**„Diesen Konnektor gibt es nicht mehr."** Zwei neue Tests scheiterten daran.
Ursache war nicht der Code, sondern die Testreihenfolge: Der Konnektor aus dem
Aufbau wird von einem früheren Test gelöscht. Die neuen Tests legen sich jetzt
einen eigenen an.

**Die alten Nachweise sendeten kein `organisationId`.** Meine erste Fassung der
Route verlangte es strikt — damit wären auf einen Schlag mehrere bestehende
Nachweise rot geworden, ohne dass am Produkt etwas falsch ist. Die Route leitet
die Einrichtung jetzt aus dem Konnektor ab, wenn nur dieser angegeben ist. Das
ist keine Aufweichung der Mandantengrenze: Der Konnektor wird selbst gegen den
Geltungsbereich geprüft.

### Die Sortierung war unsichtbar

Der neue Nachweis „die Geräteliste kommt nach Einrichtung sortiert" wurde rot —
und deckte auf, dass **die Einrichtung in der Geräteliste gar nicht stand**. Die
Sortierung griff, aber niemand konnte sie sehen: Die Liste wirkte willkürlich
geordnet.

Die Einrichtung steht jetzt in der zweiten Spalte, der Konnektor als Zweitzeile
darunter — organisatorische und technische Zugehörigkeit an einer Stelle. Keine
neue Spalte: Die Achtspaltengrenze der Oberfläche gilt weiter, und ein
Prüfstandsnachweis wacht darüber.

Mein erster Nachweis war dabei selbst falsch gebaut: Er suchte über die **ganze
Seite**, und darüber steht die Konnektorliste. Er meldete eine Unordnung, die es
nicht gab. Jetzt liest er nur den Abschnitt der Terminalliste.

### Und ein Nachweis, der bei Last umfiel

`FA-PRO-070` („volles Protokoll hält die Kernfunktion nicht an") wurde rot: 2
statt 3 Verifikationen. Kein Produktfehler — parallel lief ein Bau, und der
Nachweis schlief sechsmal fest 11 Sekunden.

Das Prinzip steht seit Tagen im Skript: *„Feste Wartezeiten machen Tests
wetterfühlig — sie werden rot, wenn eine Maschine langsamer ist, und das sagt
nichts über das Produkt aus."* An dieser Stelle war es trotzdem nicht angewandt.
Jetzt wird aktiv gewartet, bis das Ziel erreicht ist, höchstens zwölf Runden.

---

## Die Gruppenverwaltung — was eine einfache Frage freilegte

**Ausgangspunkt (PO, 11.08.2026):** „In der Übersicht werden 9 Gruppen
verauskunftet. In der Gruppenliste stehen 19. Die Gruppen heissen alle 'Alle
Geräte'. Was ist da falsch?" Und kurz darauf: „analysiere die Gruppenverwaltung
von KT. Das funktioniert nicht und hat keine usebility."

### Die Kette

Beide Zahlen waren richtig — sie zählten Verschiedenes. Zehn Gruppen hatten
keine Organisationseinheit und waren damit für jede mandantengebundene Abfrage
unsichtbar. Von dort führte jede Antwort eine Schicht tiefer:

| Frage | Was dahinter lag |
|---|---|
| Warum haben Gruppen keine Organisation? | `vorgabegruppeAnlegen` legte sie auch ohne an |
| Warum fand sich keine Organisation? | Die Agent-Registrierung ließ **erfundene** Einrichtungen zu |
| Warum ließ sie das zu? | Die Auswahlliste bot „Praxis (ohne Zuordnung)" an — eine Sackgasse, die wie eine Wahlmöglichkeit aussah |
| Warum funktioniert Löschen nicht? | Die Oberfläche übergab fest `"Praxis"` als Einrichtung |
| Und wenn es „Praxis" gibt? | Dann wandern Geräte **über die Mandantengrenze** |
| Was noch? | Keine Rechteprüfung bei Intervall und Löschen |
| Und die Lage-Übersicht? | Fragt Alarme fest für „Praxis" ab — im MVZ **blind** |

### Der schwerste Fund

Die Lage-Übersicht ist die Kernsicht des Produkts: *Muss ich jetzt etwas tun?*
Sie fragte Alarme fest für die Einrichtung „Praxis" ab. In einer Installation
mit mehreren Betriebsstätten hätte sie **keinen einzigen Alarm** gezeigt.

Aufgefallen ist das nur, weil ich die Prüfeinrichtung umbenannt hatte — von
„Praxis" auf „Pruefpraxis". Der Name war stiller Bestandteil der Abfrage; der
Nachweis prüfte gegen genau den Wert, der im Code stand.

**Das ist die Lehre dieser Runde:** Ein Nachweis, dessen Testdaten zufällig
denselben Wert tragen wie eine fest verdrahtete Annahme, prüft nichts. Er ist
grün, weil beide Seiten denselben Fehler machen — dasselbe Muster wie beim
RSA-OAEP und beim PIN-Übergabemodell.

### Was die Gruppenverwaltung jetzt kann

Detailseite mit Stammdaten, Vorgabewechsel, Geräteliste und Löschen; Umbenennen
ohne Verlust der Zuordnung; Rückmeldung, was gespeichert wurde; Sortierung nach
Einrichtung; Geltungsbereich bei jeder ändernden Handlung.

### Fünf Fehler in den eigenen Prüfungen

1. **`grep -c` zählt Zeilen, nicht Treffer.** Acht Sortierköpfe in einer Zeile
   ergaben „1". Eingeführt hatte ich `grep -c` heute selbst — als Ersatz für
   `grep -q`, das unter `pipefail` die Pipeline sprengt. Eine Falle durch eine
   andere ersetzt.
2. **Dieselbe Spaltenlogik dreimal in awk**, zweimal falsch: einmal über die
   ganze Seite statt über eine Tabelle, einmal mit fester Spaltenposition.
   Jetzt `pruefungen/tabelle.py` mit echtem HTML-Parser, Spalte über die
   Überschrift.
3. **Der aktive Sortierkopf heißt „Praxis ▴"** — das Werkzeug fand ausgerechnet
   die Spalte nicht, nach der sortiert wird.
4. **Ein Nachweis räumte seinen Zustand nicht auf** (Vorgabewechsel) — zum
   dritten Mal an einem Tag dasselbe Muster. Das Zurücksetzen ist jetzt selbst
   ein Nachweis: „und wieder zurück".
5. **„Übersprungen, weil die Lage nicht passt"** bei einem Nachweis, der eine
   Mandantengrenze prüft. Er stellt die Vorbedingung jetzt selbst her.

---

## „Praxis" als stiller Platzhalter — 54 Stellen

**Vorgabe PO, 12.08.2026:** die verbliebenen fest verdrahteten „Praxis"-Stellen
angehen.

### Warum das mehr war als Kosmetik

Instanzweite Vorgänge trugen die Einrichtung „Praxis". Das funktionierte, weil
ein Eintrag ohne passende Organisationseinheit keine `organisation_id` bekommt
und damit für eingeschränkte Benutzer unsichtbar bleibt.

Der Platzhalter konnte aber jederzeit zu einer **echten Einrichtung** werden.
Dann sähe ein Praxisadministrator schlagartig Anmeldungen, Sicherungen und die
Benutzerverwaltung der gesamten Instanz. Eine Mandantengrenze, die von einem
frei wählbaren Namen abhängt, ist keine.

`Fachprotokoll.INSTANZ = "(instanzweit)"`, und die Organisationsverwaltung
weist diesen Namen ab — beim Anlegen wie beim Umbenennen. Damit ist die
Kollision ausgeschlossen statt unwahrscheinlich.

### Der wichtigere Teil kam erst durch den Nachweis

39 Stellen ließen sich mechanisch umstellen. Der neue Nachweis „kein neuer
Eintrag fällt auf Praxis zurück" fand danach **19 weitere** — und die waren
keine Instanzvorgänge, sondern hatten ihre Einrichtung verloren:

- Ereignisse der **Automatik** (Freischaltungen, Fehlschläge, PIN-Übergaben)
- Zuordnung eines Geräts zu einer Gruppe
- Massenaktionen im Bestand

Ausgerechnet die Einträge, die eine Praxis am dringendsten sehen muss, standen
unter einem Namen, der nicht ihrer war. Ohne den Nachweis hätte ich die
Umstellung für fertig gehalten.

Bei Massenaktionen war eine Entscheidung nötig: Sie können Geräte mehrerer
Betriebsstätten betreffen. Genau eine → sie; mehrere → instanzweit. Eine
willkürlich herausgegriffene Einrichtung wäre schlechter als keine.

### Ein Test, der an einer eigenen Zusage scheiterte

Mein Aufräumen wollte den Protokolleintrag löschen und lief in die
Datenbankregel: *„Das Fachprotokoll ist nur anfügbar."* Richtig so — die
Unveränderlichkeit (FA-PRO-010) gilt auch für Tests. Der Eintrag bleibt stehen.

Aus demselben Grund tragen **alte** Einträge weiterhin „Praxis". Sie
umzuschreiben wäre der eigentliche Verstoß.

**Stand: 241 Modultests, 392 Systemnachweise — alle grün.**

### Nachtrag: ein Rest, gefunden am laufenden Stack

Nach dem Commit habe ich in der Datenbank nachgesehen — und einen weiteren Fall
derselben Art gefunden:

```
einrichtung      | geraet     | art
Kardiologie Nord | KT-NORT-B2 | GERAET_ERREICHBAR
```

`KT-NORT-B2` gehört zur Orthopädie. „Kardiologie Nord" ist die Einrichtung, für
die der **Agent** registriert ist. Erreichbarkeit, Erkennung und
Firmwarewechsel trugen alle seine Registrierungs-Einrichtung — für den
Zuständigen der Orthopädie unsichtbar.

**Diesmal zentral gelöst.** Die Regel steht in `Fachprotokoll.anfuegen` selbst:
Trägt ein Eintrag ein Gerät, gilt dessen Einrichtung — nicht die des Aufrufers.
Ist das Gerät noch unbekannt, bleibt die genannte stehen; beim Erfassen gibt es
die Zuordnung ja noch nicht.

Dasselbe Prinzip wie beim `Geltungsbereich` als Pflichtparameter: Was an jeder
Stelle von Hand richtig gemacht werden muss, wird irgendwo vergessen. Bei den
Gruppen hatte ich es Stunden zuvor schon angewandt — hier war es überfällig.

### Zwei Fehler beim Beheben

**Ein SQL, dessen Ergebnis von der Laune des Planers abhing.** `UNION ALL … LIMIT 1`
ohne `ORDER BY` — die Abfrage lieferte mal den Bestand, mal die Meldung des
Agenten. Jetzt `COALESCE` mit klarer Rangfolge: Der Bestand hat Vorrang, denn er
ist die gepflegte Zuordnung; die Agentmeldung ist ihr Abbild.

**Ein Nachweis, der Historie gegen Gegenwart verglich.** Er prüfte *alle*
gerätebezogenen Einträge gegen die *aktuelle* Zuordnung. Ein Protokolleintrag
ist aber unveränderlich, und ein umgesetztes Gerät macht alle alten Einträge
scheinbar falsch. Die Regel wird jetzt mit zwei Modultests geprüft — dort, wo
sie isolierbar ist.

Beim Schreiben dieser Tests stoppten mich zwei Datenbankregeln: die
Unveränderlichkeit des Protokolls und `gruppe_id NOT NULL`. Beide Male hatte die
Regel recht, und beide Male war mein Test der bequeme Weg.

**Stand: 243 Modultests, 393 Systemnachweise — alle grün.**

---

## Eine Hierarchie, die zur Wirklichkeit der TI passt (ADR-0012)

Der PO beschrieb, wie es tatsächlich aussieht: Ein Kartenterminal gehört immer
zu einem **Fachbereich** und immer zu einem **Konnektor** — und ein Konnektor
kann mehrere Fachbereiche bedienen. In TI 1.0 galt ursprünglich ein Konnektor je
Leistungserbringer; heute steht er als „Konnektor as a Service" im Rechenzentrum
und wird von mehreren geteilt. Im MVZ hängt darüber die Trägergesellschaft, das
MVZ ist der zugelassene Leistungserbringer, und die **Betriebsstätten** sind es,
die BSNR, SMC-B und Telematik-ID tragen.

Unser Modell kannte davon zwei Dinge nicht: die Ebene unterhalb der
Betriebsstätte, und dass ein Konnektor mehr als einer Einrichtung gehören kann.

### Was gebaut wurde

**Zwei neue Ebenen** — `LEISTUNGSERBRINGER` zwischen Verbund und Standort,
`FACHBEREICH` unterhalb der Betriebsstätte. Beide sind optional; die Einzelpraxis
bleibt `Verbund → Betriebsstätte`. Die erlaubte Schachtelung steht an genau einer
Stelle im Code, nicht in jedem Formular.

**BSNR und Telematik-ID an der Betriebsstätte** — freiwillig, mit Formprüfung auf
neun Ziffern. Ein Standort nimmt keine an und sagt auch, warum. Geprüft wird die
Form, nicht die Gültigkeit: Ob es die Nummer gibt, weiß nur die KV.

**Konnektor ↔ Betriebsstätte als n:m** mit einer Trennung, die der eigentliche
Kern dieser Entscheidung ist:

> Wer den Konnektor betreibt, verwaltet ihn. Wer ihn nutzt, sieht ihn.

Im Rechenzentrum gehört der Konnektor dem Dienstleister. Die nutzenden Praxen
müssen ihn sehen, sonst könnten sie ihre eigenen Terminals nicht zuordnen. Ändern
dürfen sie ihn nicht — sonst könnte eine Praxis mit zwei Geräten den Konnektor
umkonfigurieren, an dem zwanzig andere hängen.

### Die Stelle, an der ich mir selbst eine Lücke gebaut hatte

Damit der Mitnutzer den Konnektor sieht, musste `konnektor(...)` ihn ihm zeigen.
Die drei Änderungswege — ändern, löschen, Kennwort entfernen — prüften bis dahin
genau eine Frage: „Sehe ich diesen Datensatz?" Nach der Erweiterung hätte jeder
Mitnutzer alle drei ausführen dürfen.

Gefunden habe ich das nicht durch einen Test, sondern beim Lesen der eigenen
Änderung. Jetzt prüfen sie `darfKonnektorVerwalten(...)`, das ausschließlich auf
den Betreiber schaut — und ein Modultest führt genau diesen Angriff aus.

Das ist dasselbe Muster wie schon zweimal zuvor in diesem Projekt: Eine Abfrage
wird großzügiger, und eine Prüfung, die sich auf ihre Enge verlassen hat, wird
still unwirksam.

### Was der Demodatensatz jetzt zeigt

7 Konnektoren statt 9: sechs eigene und einer im Rechenzentrum, den sich drei
kleine Betriebsstätten teilen. Zwei Fachbereiche in der Chirurgie — nur dort,
denn ein Aufbau, in dem eine optionale Ebene überall vorkommt, täuscht eine
Pflicht vor, die es nicht gibt. Und an allen neun Betriebsstätten BSNR und
Telematik-ID, an keiner anderen Ebene.

Eine Nebenwirkung, die durch den ganzen Code lief: Wer die Geräte einer
Betriebsstätte zählt, muss den **Teilbaum** nehmen. Mit Fachbereichen ist der
Vergleich auf die eine Kennung zu eng — im Demodatenaufbau, in den
Systemnachweisen und in jeder Zählung, die vorher richtig war, weil es unterhalb
der Betriebsstätte nichts gab.

### Der Nachweislauf prüfte ein Artefakt von vorgestern

Neun Nachweise schlugen fehl — alle neu, alle zu ADR-0012. Die Ursache war
nicht der Code: Das Nachweisskript startet die Bausteine aus `target/*.jar`,
**baute sie aber nicht selbst**. Das JAR stammte von 11:34, meine Änderungen von
später.

Der Lauf hätte also einen Stand bescheinigt, den es im Quellcode nicht mehr gibt.
Aufgefallen ist es nur, weil ich neue Nachweise mitgebracht hatte, die
zwangsläufig fehlschlagen mussten. Wären es reine Umbauten unter unverändertem
Verhalten gewesen, wäre der Bericht grün gewesen — und falsch.

Zwei Änderungen: Der Testschritt heißt jetzt `mvn package` statt `mvn test`, baut
also die Artefakte, die anschließend geprüft werden. Und ein eigener Nachweis
vergleicht das Alter des JARs mit dem der jüngsten Quelldatei. Ein stiller
Fehlschlag beim Bauen bleibt damit nicht länger unsichtbar.

---

## „Demodatensatz löschen geht nicht" — zwei Fehler dahinter

Befund des PO, unmittelbar nachdem der neue Stack lief. Dahinter steckten zwei
unabhängige Fehler, beide älter als der gestrige Umbau.

### 1. Ein Fremdschlüssel, der seiner eigenen Spalte widersprach

```
null value in column "organisation_id" of relation "geraetegruppe"
  violates not-null constraint
Where: UPDATE ONLY "geraetegruppe" SET "organisation_id" = NULL
```

Migration 23 hatte `geraetegruppe.organisation_id` auf **NOT NULL** gesetzt —
richtig, denn eine Gerätegruppe ohne Organisationseinheit ist für niemanden
sichtbar. Der Fremdschlüssel stand aber weiterhin auf **ON DELETE SET NULL**.
Die Datenbank sollte beim Löschen einer Einheit genau den Wert setzen, den sie
zugleich verbietet.

Warum das nicht auffiel: Die Nachweise löschten immer nur Gruppen, die sie
selbst angelegt hatten. Der Fehler braucht eine Gruppe, die **der Agent**
erzeugt — und die entsteht erst, wenn ein Agent längere Zeit gegen einen
Demobestand läuft. Genau die Konstellation auf dem Rechner des PO.

Migration 25 setzt beide Fremdschlüssel auf `CASCADE`: `geraetegruppe →
organisation` und `geraet → geraetegruppe`. Die Gruppe gehört zu ihrer Einheit;
verschwindet die Einheit, verschwindet die Gruppe.

### 2. Die Löschroutine räumte nur ihre eigenen Zeilen weg

`DELETE FROM geraet WHERE ist_demo` — aber die Zeilen, die der Agent zu
Demogeräten anlegt, tragen **kein** Demo-Kennzeichen. Er kennt den Unterschied
nicht; er meldet, was der Konnektor ihm nennt. Bei `pin_hinterlegt` war das
schon bedacht (dort wird über das Kartenhandle aufgeräumt), bei `geraet` und
`geraetegruppe` nicht.

### Der Folgefund: ein Agent, der ins Leere meldet

Im Protokoll standen hunderte Zeilen:

> Gerät KT-MHAU-B3 konnte der Einrichtung „Kardiologie Nord" nicht zugeordnet
> werden.

Der Agent war am 10.08. auf „Kardiologie Nord" registriert. Diese Einrichtung
gibt es seit dem MVZ-Szenario nicht mehr — und **der Agent speichert den Namen,
nicht die Kennung**. Dasselbe Muster wie beim fest verdrahteten „Praxis": Ein
Text, der eine Beziehung tragen soll.

In der Oberfläche sah alles normal aus. Zwei Änderungen:

- Die Agentenübersicht **kennzeichnet** einen Agenten, dessen Einrichtung es
  nicht gibt, und sagt, was die Folge ist: Er kann seine Geräte keiner Gruppe
  zuordnen.
- Die Zuordnung ist **änderbar**. Bisher stand in der Oberfläche: „Sie lässt
  sich später nicht ändern; ein Agent steht in genau einer Praxis." Der Satz
  stimmt fachlich, führte im Fehlerfall aber in eine Sackgasse — es blieb nur
  die Neuregistrierung, mit neuem Schlüssel und dem Verlust aller dort
  hinterlegten PINs. Die Änderung ist mandantengebunden geprüft und steht als
  `AGENT_ZUGEORDNET` auf der Sicherheitsstufe im Fachprotokoll: Sie verschiebt,
  in wessen Sicht die Geräte eines Agenten landen.

**Was offen bleibt (technische Schuld, bewusst aufgenommen):** `agent.einrichtung`
sollte ein Fremdschlüssel auf `organisation` sein, kein Text. Dann überstünde die
Zuordnung eine Umbenennung von selbst, und ein Löschen wäre nicht stillschweigend
möglich. Der Umbau berührt Registrierung, Meldeweg, Alarme und Protokoll und
gehört in einen eigenen Schritt.

---

## Die Struktur als Bild

**Vorgabe des PO:** eine grafische Darstellung der Organisationsstruktur mit
Sinnbildern für die Entitäten und Geräte — „was Schönes fürs Auge und mit hoher
Usability".

Seit ADR-0012 sind fünf Ebenen möglich. Eine eingerückte Tabelle bildet das ab,
aber niemand erfasst sie auf einen Blick — und sie beantwortet die eigentliche
Frage nicht: **Wo klemmt es?**

### Was entstanden ist

Ein Umschalter auf der Organisationsseite: **Struktur** oder **Liste**. Die
Struktur zeigt jede Einheit als Karte mit Sinnbild, Zahlen und Ampel; darunter
hängen ihre Konnektoren und Kartenterminals als Kacheln. Die Liste bleibt
gleichwertig — sie ist das bessere Werkzeug, wenn man sucht statt versteht.

Drei Dinge, die die Ansicht nützlich statt nur hübsch machen:

- **Die Ampel gilt für den Teilbaum und zeigt den schlechtesten Zustand darin.**
  Wer auf die oberste Karte schaut, sieht, ob irgendwo im Haus etwas rot ist.
  Ein Mittelwert würde neunundneunzig gesunde Geräte über ein ausgefallenes
  legen.
- **Ein mitgenutzter Konnektor ist gestrichelt und sagt es im Text.** Damit wird
  ADR-0012 zum ersten Mal sichtbar: Man sieht, dass der Konnektor der Radiologie
  im Rechenzentrum steht und ihr nicht gehört.
- **Das Sinnbild unterscheidet Terminals mit und ohne SMC-B.** Nur die mit Karte
  nimmt die Automatik in die Hand — sonst wundert man sich über fünfunddreißig
  „stille" Geräte.

### Warum kein gezeichnetes Diagramm

Ein SVG-Organigramm sieht auf einem Bildschirm gut aus und versagt auf jedem
anderen: Es bricht nicht um, lässt sich nicht mit der Tastatur bedienen,
skaliert nicht mit der Schriftgröße, und ein Screenreader findet darin keine
Reihenfolge. Die Karten sind gewöhnliche Verweise in verschachtelten Listen; die
Linien zeichnet CSS, das Auf- und Zuklappen macht `<details>`. Damit ist die
Ansicht ohne Zusatzaufwand tastaturbedienbar, vorlesbar und mit Strg+F
durchsuchbar.

Die Piktogramme liegen jetzt in `Symbole.java` — vorher standen SVG-Pfade dort,
wo man sie gerade brauchte. Dieselbe Ursache wie bei den 142 fehlenden
Kurzhinweisen im August: Was an jeder Stelle einzeln gepflegt wird, weicht an
jeder Stelle ein wenig ab.

### Ein Fehler, den ein Java-Textblock verursacht hat

```
ERROR: trailing junk after numeric literal at or near "1ORDER"
```

Ein Textblock entfernt den Zeilenumbruch direkt nach den öffnenden
Anführungszeichen. Aus `"WHERE 1=1" + bedingung + """\nORDER BY …"""` wurde bei
leerer Bedingung `WHERE 1=1ORDER BY`. Die Ansicht zeigte daraufhin **still keine
Konnektoren**; der Fehler stand nur im technischen Protokoll.

Drei andere Abfragen im Code setzen an genau dieser Stelle ein Leerzeichen —
diese eine nicht. Das ist der Preis dafür, SQL aus Textblöcken
zusammenzusetzen; es steht jetzt als Kommentar an der Stelle.

### Ein Nachweis, der ein Zeitfenster maß statt einer Tatsache

Beim Vollauf danach wurde ein Nachweis rot, den meine Änderungen gar nicht
berühren: „nach dem Fehlschlag gilt weiter die zuletzt gültige PIN". In den
beiden Läufen davor war er grün.

Er prüfte:

```bash
docker logs pr-agt | tail -25 | grep -q "freigeschaltet"
```

Also: *Steht das Wort in den letzten 25 Zeilen des Agentenprotokolls?* Das ist
kein Zustand, das ist ein Zeitfenster. Sobald der Agent zwischendurch andere
Geräte meldete, rutschte die Zeile heraus — und der Nachweis meldete einen
Fehler, den es nicht gab. Ein flatternder Nachweis ist schlimmer als keiner: Er
erzieht dazu, rote Läufe zu ignorieren.

Jetzt wird an der Sache gemessen, in zwei Schritten:

1. Der Zähler der SMC-B-Übergaben am Terminal **steigt** — der Roboter hat die
   PIN erneut übergeben.
2. Die Karte steht danach in der Geräteliste wieder auf **„verifiziert"** — es
   war also die richtige PIN. Wäre die fehlgeschlagene Änderung durchgeschlagen,
   bliebe sie unverifiziert und der Fehlversuchszähler sänke.

Und der Zustand wird in **der Zeile dieses Geräts** gelesen, nicht irgendwo auf
der Seite. „verifiziert" steht dort auch bei fünfunddreißig anderen Terminals;
ein Nachweis, der das nicht unterscheidet, ist immer grün.

**Und dann derselbe Fehler noch einmal, eine Stufe feiner.** Der neue Nachweis
war beim ersten Anlauf grün — mit diesem Klartext daneben:

```
BESTANDEN  die Karte steht danach wieder auf „verifiziert" (gelesen: !nicht verifiziert)
```

Gelesen wurde **„nicht verifiziert"**, und das Muster `*verifiziert*` trifft
darauf zu. Der Nachweis bescheinigte also das Gegenteil dessen, was dastand.
Aufgefallen ist es nur, weil ich den gelesenen Wert in die Meldung geschrieben
hatte — hätte ich nur „bestanden" ausgegeben, wäre es durchgegangen.

Zwei Lehren, beide eingebaut: Der Vergleich schließt „nicht verifiziert"
ausdrücklich zuerst aus, und der Nachweis **wartet** auf den Zustand, statt drei
Sekunden zu hoffen — die Zentrale erfährt ihn erst mit der nächsten Meldung des
Agenten.

> Ein Nachweis sollte immer den gemessenen Wert mitschreiben, nicht nur sein
> Urteil. Genau dieser Zusatz hat hier den Fehler im Nachweis selbst
> aufgedeckt.


### Und die Meldung, die ich eingebaut hatte, fand gleich etwas

Seit heute meldet die Barrierefreiheitsprüfung, wenn eine erwartete Seite im
Prüfaufbau nicht erreichbar war, statt die Zahl geprüfter Seiten still schwanken
zu lassen. Beim ersten Lauf mit dieser Meldung:

```
NICHT GEPRUEFT  zeitstrahl — die Seite war im Prüfaufbau nicht erreichbar
NICHT GEPRUEFT  vorgang — die Seite war im Prüfaufbau nicht erreichbar
```

Die Prüfung suchte die Gerätekennung **in der Geräteseite**. Je nachdem, an
welcher Stelle des Prüflaufs sie startet, ist die Liste gerade leer — dann
blieben zwei der vier Nachvollziehbarkeitssichten aus CLAUDE.md ungeprüft. Das
war schon vorher so; man sah es nur nicht, weil nur eine Gesamtzahl
ausgewiesen wurde („18 Seiten ohne Befund" statt „18 von 20").

Die Kennungen kommen jetzt aus der Datenbank. Der Zeitstrahl existiert dort
immer, auch ohne Ereignisse — und eine leere Seite ist ebenfalls
prüfenswert.


---

## Der Rechenzentrums-Konnektor fliegt aus dem Demobestand

**Vorgabe des PO.** Im Demoszenario betreibt jetzt jede der neun
Betriebsstätten ihren eigenen Konnektor — neun statt sieben.

Der geteilte Konnektor war die einzige Stelle, an der die n:m-Beziehung aus
ADR-0012 im Demobestand sichtbar war. **Die Funktion bleibt unverändert**; nur
zeigt der Demodatensatz sie nicht mehr.

Zwei Nachweise hingen daran und hätten künftig „übersprungen" gemeldet:
„drei Betriebsstätten nutzen den zentralen Konnektor mit" und „ein mitgenutzter
Konnektor ist als solcher gekennzeichnet". Beide **stellen ihren Aufbau jetzt
selbst her**: Mitnutzung eintragen, prüfen, wieder entziehen. Dabei sind zwei
Nachweise dazugekommen, die vorher gefehlt haben — dass der Mitnutzer den
Konnektor auch wirklich sieht, und dass die Kennzeichnung nach dem Entziehen
wieder verschwindet.

> Ein Nachweis, der an Demodaten hängt, prüft nur so lange, wie niemand die
> Demodaten ändert. Und wenn er dann still auf „übersprungen" springt, sieht ein
> grüner Lauf genauso aus wie vorher — obwohl eine Funktion unbelegt geworden
> ist. Deshalb war der Umbau nicht Beiwerk der Änderung, sondern ihr Kern.


---

## „Alle Alarme löschen" — der Wunsch war das Symptom

**Vorgabe des PO:** eine Funktion, um in der Lage-Übersicht alle Alarme zu
löschen.

Vor dem Bauen ein Blick in seine Datenbank:

```
 id  |   einrichtung    |      objekt      |          art
-----+------------------+------------------+-------------------------
   1 | Kardiologie Nord | KT-BEHANDLUNG-02 | PEVIM_NICHT_AKTIV
  57 | PruefEinrichtung | KT-8444d3f1      | GERAET_NICHT_ERREICHBAR
 352 | Praxis           | Sicherung täglich| SICHERUNG_FEHLGESCHLAGEN
```

**Elf offene Alarme, kein einziger zu einem Gerät, das es noch gibt.** Alle aus
gelöschten Demo- und Prüfbeständen: „Kardiologie Nord" stammt aus dem alten
Demoszenario, „KT-8444d3f1" ist ein Lasttestgerät, „Praxis" die Vorgabe aus der
Zeit vor der Mandantenfähigkeit.

Die Ursache steckt im Alarmmodell selbst. Ein Alarm schließt sich, wenn ein
Agent meldet, dass der Zustand weg ist — bewusst automatisch, damit niemand
Alarme wegklicken muss. Für ein **gelöschtes** Gerät kommt diese Meldung nie.
Der Alarm bleibt für immer offen, und die wichtigste Seite des Produkts füllt
sich mit Rauschen.

### Was gebaut wurde

**Schließen statt löschen.** Der Datensatz bleibt, nur `behoben_am` wird
gesetzt. Der Alarm verschwindet aus der Liste — was der PO wollte — und bleibt
nachvollziehbar (ADR-0005). Ein `DELETE` würde die Frage „warum war dieses
Gerät drei Wochen lang nicht erreichbar?" unbeantwortbar machen.

**Es hebt das Problem nicht auf.** Besteht die Ursache weiter, meldet der Agent
den Alarm im nächsten Prüflauf erneut. Dafür gibt es einen eigenen Test: Ein
Wegklicken, das eine echte Störung dauerhaft verschwinden ließe, wäre genau der
Fehler, den man unter Zeitdruck macht.

**Die Sammelaktion nennt die Zahl.** „Alle 11 Alarme schließen?" ist etwas
anderes als „Alle Alarme schließen?", wenn man dreihundert hat. Und sie trifft
nur den eigenen Geltungsbereich.

### Und die Ursache

Die Funktion räumt auf; sie beseitigt nicht, was den Müll erzeugt. Deshalb
nehmen `Demodaten.loeschen` und `Lasterzeuger.entfernen` jetzt die Alarme ihrer
Geräte mit.

> Dasselbe Muster wie beim Löschen des Demobestands eine Woche zuvor: Der Agent
> legt Zeilen an, die kein Demo-Kennzeichen tragen, weil er den Unterschied gar
> nicht kennt. Wer aufräumt, muss über den **Bezug** aufräumen, nicht über das
> Kennzeichen. Beim dritten Mal sollte ich das eigentlich von selbst mitdenken.


---

## Neun Gruppen, ein Name

**Befund des PO (13.08.2026):** In der Gruppenverwaltung hießen alle neun
Vorgabegruppen „Alle Geräte". Die Unterscheidung steckte allein in der
Nachbarspalte „Einrichtung".

Der Punkt ist nicht die Optik. Ein Gruppenname taucht auch dort auf, wo diese
Spalte **nicht** danebensteht: in Auswahlfeldern, im Fachprotokoll, in
Fehlermeldungen. Dort sagte er gar nichts mehr.

> Ein Bezeichner, dessen Bedeutung vom Nachbarn abhängt, ist keiner.

Die Vorgabegruppe heißt jetzt **„Alle Geräte – <Einrichtung>"**. Die Einrichtung
ist instanzweit eindeutig — das prüft `vorgabegruppeAnlegen` ohnehin, sonst
entstünde gar keine Gruppe —, also ist es der Gruppenname damit auch. Migration
26 benennt bestehende um, **aber nur die, die noch den alten Namen tragen**: Wer
seine Vorgabegruppe selbst benannt hat, behält seinen Namen. Eine Migration, die
eine bewusste Benennung überschreibt, wäre übergriffig.

Der Eindeutigkeitsindex bleibt, wie er ist — `(einrichtung, lower(bezeichnung))`.
Instanzweite Eindeutigkeit zu erzwingen wäre naheliegend und wäre ein
**Informationsleck**: Eine Praxis bekäme „Name schon vergeben" für eine Gruppe
in einer Einrichtung, die sie nicht sehen darf. Stattdessen erklärt das
Anlegeformular jetzt, worauf es ankommt:

> Die Bezeichnung sollte auch **ohne** die Spalte „Einrichtung" erkennbar sein …
> „Notaufnahme Nordstadt" ist brauchbar, „Notaufnahme" in neun Betriebsstätten
> nicht.


### Und dann hart durchgesetzt

Auf Nachfrage entschied der PO: Die Eindeutigkeit soll nicht nur empfohlen,
sondern **erzwungen** werden — mit einer Meldung in seinen Worten: „Bitte
ergänzen Sie den Gruppennamen um eine beliebige Praxis-Kennzeichnung."

Zwei Ebenen, weil eine nicht reicht:

- **Migration 27** setzt einen eindeutigen Index auf `lower(bezeichnung)` —
  instanzweit. Das ist die Wahrheit; zwei gleichzeitige Sitzungen können sie
  nicht unterlaufen. Die Migration löst zuerst vorhandene Doppelnamen auf
  (Einrichtung anhängen, notfalls die Kennung) und setzt dann erst den Index.
  Umgekehrt schlüge sie bei jeder Installation fehl, die schon Doppelnamen hat
  — und ließe die Zentrale gar nicht mehr starten.
- **Eine Vorabprüfung** liefert die gute Meldung, bevor die Datenbank einen
  Constraint-Fehler wirft.

**Die Meldung nennt die fremde Einrichtung nicht.** Das ist der eigentlich
heikle Teil: Die Prüfung muss über alle Mandanten laufen, sonst greift die
Eindeutigkeit nicht — aber sie darf nur ein Ja oder Nein liefern. Sonst erführe
eine Praxis durch Ausprobieren, wie die Gruppen fremder Mandanten heißen. Eine
Mandantengrenze, die sich mit einem Formular abfragen lässt, ist keine. Dafür
gibt es einen eigenen Test.

Zwei bestehende Modultests fielen dabei um — sie legten in verschiedenen
Einrichtungen Gruppen namens „Notaufnahme" an. Genau das, was die neue Regel
verbietet; die Tests tragen jetzt die Einrichtung im Namen.


---

## Übergabe an DevOps

DevOps hat eine Anforderungsliste für das Deployment auf Labs geschickt
(`*.lab.vps.nieting.de`). Die Besonderheit steht gleich im Kopf: Der PIN-Roboter
ist **die einzige Anwendung dort, die ohne Authentik aus dem Internet erreichbar
ist** — alles andere liegt hinter zentraler Anmeldung mit Zwei-Faktor. Er trägt
seine Absicherung vollständig selbst.

### Was gemessen fehlte

| Anforderung | Stand vorher |
|---|---|
| `GET /health` mit Version und DB-Status | fehlte ganz |
| `HEALTHCHECK` im Image | nur ein TCP-Test im Compose |
| Sitzungscookie mit `Secure` | **fehlte** |
| `X-Forwarded-Proto` / `X-Forwarded-For` | wurden nicht ausgewertet |
| CSRF-Schutz | nur `SameSite=Strict` |
| `expose:` statt `ports:`, gehärtete Container | Compose öffnete 8090 auf dem Host |
| GitHub Actions, GHCR, Smoke-Test | kein `.github/` |
| `RELEASENOTES.md` | fehlte |

### Der peinlichste Punkt

Im Code stand seit Wochen:

> Das Secure-Kennzeichen setzt der Reverse-Proxy in der Auslieferung.

Das tut Caddy **nicht von sich aus**. Ohne `Secure` schickt der Browser das
Sitzungscookie auch über unverschlüsseltes HTTP — und aufgefallen wäre es
niemandem, weil im Prototyp ohnehin alles auf localhost lief. Eine Annahme über
fremde Technik, die nie geprüft wurde. Jetzt setzt die Anwendung das Kennzeichen
selbst, sobald `X-Forwarded-Proto: https` ankommt.

### CSRF ohne Token

DevOps verlangt CSRF-Schutz für alle verändernden Aufrufe. Statt Tokens je
Formular nachzurüsten — Arbeit an jedem Formular und jedem HTMX-Aufruf — prüft
der zentrale Filter jetzt den **`Origin`-Kopf**: Ihn setzt der Browser selbst,
Skriptcode kann ihn nicht fälschen. Zusammen mit `SameSite=Strict` sind das zwei
Linien; OWASP nennt den Weg als gleichwertig. Nachgewiesen mit drei Aufrufen:
fremder Ursprung → 403, eigener → 200, ohne Origin (curl) → 200.

Dasselbe Muster wie überall in diesem Projekt: eine Sperre an einer Stelle statt
Sorgfalt an hundert.

### Zwei Befunde beim Messen

**Die GID war 999, nicht 10001.** `useradd --system --uid 10001` legt die Gruppe
zwar an, nimmt dafür aber die nächste freie System-GID. DevOps bereitet die
Volumes anhand von UID:GID vor — und ohne `CAP_DAC_OVERRIDE` kann der Container
nichts nachträglich richtigstellen. Jetzt `groupadd --gid 10001` davor.

**`EXPOSE` fehlte im Image.** Öffnet nichts, dokumentiert aber den Port für den,
der das Image bekommt.

### Gehärtet geprüft, nicht behauptet

Der Container läuft mit `read_only`, `cap_drop: ALL`, `no-new-privileges`,
`pids_limit` und **PostgreSQL 16-alpine** statt unserer 17 — und meldet
`healthy`. Auch die Sicherung wurde gegen 16 geprüft: Der `pg_dump`-Client im
Image ist Version 18, also neuer als der Server. Das ist die zulässige Richtung;
umgekehrt verweigert der Client die Arbeit.

Gemessen statt geschätzt: **196 MiB** für die Anwendung, **66 MiB** für die
Datenbank, SIGTERM in **1 s**.

### Was ich als Abweichung benannt habe

Drei Punkte stehen im Übergabedokument als offene Abweichung, nicht als
erfüllt: PostgreSQL-Version, CSRF ohne Token, und ein Image von 632 MB für
einen Dienst, der 196 MiB braucht. Beim Image ist Alpine keine Option — wir
brauchen Brainpool aus BouncyCastle und haben die TLS-Kette nur auf dieser
Kombination gemessen. `jlink` wäre der nächste Schritt, vor einem
Produktionsdeployment ohnehin.


---

## Der Agent konnte die Zentrale gar nicht erreichen

Der PO fragte, wie er den Standort-Agenten mit der frisch veröffentlichten
Instanz auf Labs verbindet. Vor der Anleitung die Prüfung, ob es überhaupt geht
— und es ging nicht.

**Der Agent sprach mit allem über denselben TLS-Client.** Konnektor,
Kartenterminal und Zentrale. Dessen Vertrauensspeicher enthält ausschließlich
die CA aus `CA_URL`, also die TI-Strecke. Gegen ein Zertifikat von Let's Encrypt
scheitert er am Vertrauensanker.

Aufgefallen ist es nie, weil die Zentrale im Entwicklungsstack über schlichtes
`http` erreichbar war. Erst der echte Betrieb hinter einem Reverse Proxy legt es
frei — dasselbe Muster wie beim fehlenden `Secure` am Sitzungscookie zwei
Stunden zuvor: **eine Annahme über die Auslieferungsumgebung, die im Prototyp
nie geprüft werden konnte.**

### Was das Messen genauer gemacht hat

Ich hatte zwei Ursachen vermutet: den Vertrauensanker **und** die Einschränkung
auf TLS 1.2 mit zwei Cipher-Suites aus `[gemSpec_Krypt]`. Der Test gegen
`pin-roboter.lab.vps.nieting.de` zeigte: Die Suiten passen sogar, Caddy kann
`ECDHE-ECDSA-AES128-GCM-SHA256` über TLS 1.2. Es scheiterte allein am
Vertrauensanker.

Hätte ich nur gelesen statt gemessen, hätte ich an der Cipher-Liste
herumgeschraubt — und damit eine gemSpec-Vorgabe aufgeweicht, die für die
TI-Strecke richtig ist.

### Die Lösung

Zwei Clients statt einem. Die Trennung ist ohnehin die richtige: Konnektor und
Kartenterminal sind Chipkartenidentitäten auf Brainpool-Kurven mit
selbstsignierten oder gematik-CAs — die eigene Zentrale ist gewöhnliches
Web-TLS. Beides in einer Konfiguration zu führen war der Fehler.

### Zweiter Fund

**Es gab kein Image für den Standort-Agenten.** Der Workflow baute nur die
Zentrale; den Agenten hätte der PO selbst bauen müssen. Er wird jetzt unter
derselben Version mitveröffentlicht — zwei Bausteine mit auseinanderlaufenden
Nummern sind im Störungsfall nicht zuzuordnen.

### Und eine Lehre über das Prüfen selbst

Die acht Fehlanmeldungen aus meiner Bremsen-Prüfung und der abgelehnte
Registrierungsversuch standen im Fachprotokoll, bevor ich sie erwähnt hatte —
der PO fand sie dort und fragte, was da los sei. Prüfungen gegen eine Instanz,
die jemand anderem gehört und die er beobachtet, gehören **vorher angekündigt**,
nicht hinterher erklärt. Das Protokoll ist unveränderlich; die Einträge bleiben.


---

## Die Images waren zu 83 % Unterbau

Der PO fragte nach der Größe und ob Ubuntu die kleinstmögliche Basis sei. Erst
messen, was die Images ausmacht:

| Bestandteil | Größe |
|---|---|
| `eclipse-temurin:21-jre` (Ubuntu + JRE) | **524 MB** |
| PostgreSQL-Client (apt) | 65 MB |
| unser Jar | 18 MB |

**Nicht die Anwendung war groß, sondern ihr Unterbau.** Und nein, Ubuntu ist
nicht das Minimum:

| Basis | |
|---|---|
| `eclipse-temurin:21-jre` | 524 MB |
| `eclipse-temurin:21-jre-alpine` | 286 MB |
| `debian:13-slim` | 141 MB |
| `alpine:3.21` | **12,9 MB** |

### Nicht nur eine kleinere Basis, sondern weniger Java

Der eigentliche Hebel ist `jlink`: eine Java-Laufzeit, die nur die Module
enthält, die der Code wirklich benutzt. Welche das sind, ermittelt `jdeps`
**beim Bauen aus dem Jar** — eine von Hand gepflegte Liste läuft dem Code
hinterher, und ein fehlendes Modul fällt dann erst zur Laufzeit auf, im
ungünstigsten Fall in einem selten benutzten Zweig.

Drei Module muss man trotzdem von Hand ergänzen, weil `jdeps` sie nicht sehen
kann: `jdk.crypto.ec` (der Provider wird zur Laufzeit geladen — ohne ihn fehlen
die EC-Kurven), `jdk.localedata` und `java.logging`.

| | vorher | jetzt |
|---|---|---|
| Zentrale, entpackt | 632 MB | **155 MB** |
| Zentrale, Download | 142 MB | **58 MB** |
| Agent, entpackt | 544 MB | **135 MB** |
| Agent, Download | 125 MB | **50 MB** |

### Die Annahme, die ich zwei Stunden zuvor noch verteidigt hatte

Im Übergabedokument an DevOps stand von mir:

> Ein Alpine-Basisimage scheidet aus — wir brauchen Brainpool-Kurven aus
> BouncyCastle und haben die TLS-Kette nur auf dieser Kombination gemessen.

Der erste Halbsatz war falsch, der zweite richtig. BouncyCastle ist **reines
Java ohne JNI**; der Unterschied zwischen glibc und musl berührt es nicht. Ich
hatte aus „nicht gemessen" ein „scheidet aus" gemacht — genau die Verwechslung,
die CLAUDE.md verbietet.

Also gemessen: Der vollständige PEviM-Durchstich läuft auf dem neuen Unterbau,
die SMC-B wird freigeschaltet, die PIN geht über die Remote SMCB API,
**null** TLS-Handshake-Fehler. Und `pg_dump` (jetzt 17 statt 18) gegen Server
16 **und** 17 geprüft.

### Was der Wechsel kaputt gemacht hat

**Alle Healthchecks.** Sie riefen `bash -c '</dev/tcp/…'` — eine bash-Eigenheit,
und im Alpine-Image gibt es keine bash. Container liefen, meldeten sich aber als
ungesund; der Simulator riss über `depends_on` den Agenten mit.

Aufgefallen ist das sofort, weil ich nach dem Umbau den Stack hochgefahren und
hingesehen habe. Wäre ich beim „Image ist kleiner, fertig" geblieben, hätte
DevOps es gefunden — an einem Deploy, der am Health-Gate hängen bleibt.

Jetzt BusyBox-`wget` statt bash, und der Healthcheck der Zentrale steht nur noch
**im Image**: Der Eintrag im Compose hatte ihn überschrieben und dabei die
Datenbankprüfung verloren, die er mitbringt.
