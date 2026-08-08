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
