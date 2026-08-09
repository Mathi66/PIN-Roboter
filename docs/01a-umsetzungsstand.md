# Umsetzungsstand je Anforderung

**Stand:** 08.08.2026 · Grundlage: [Anforderungskatalog](01-anforderungskatalog.md)
mit 84 Anforderungen · **268 Modultests, 298 Systemnachweise**

> **Diese Liste wurde am 08.08.2026 neu aus dem Katalog erzeugt.** Die vorige
> Fassung trug bei 36 von 84 Zeilen eine Beschriftung, die nicht zur Anforderung
> gehörte — `FA-PIN-060` stand als „PIN löschen", heißt aber **PIN ändern**;
> `NFA-USA-010` stand als „deutsche Oberfläche", heißt **Barrierefreiheit**;
> `NFA-BET-040` stand als „Multi-Arch", heißt **technisches Protokoll**.
>
> Solche Zeilen sind gefährlicher als leere: Sie sehen aus wie Fortschritt. Die
> Titel stammen jetzt wörtlich aus dem Katalog und werden nicht mehr von Hand
> geschrieben.

## Legende

| Zeichen | Bedeutung |
|---|---|
| **✔** | umgesetzt **und** am laufenden Stack nachgewiesen |
| **◐** | teilweise; was fehlt, steht dabei |
| **–** | nicht begonnen; die Begründung steht dabei |

| Merkmal | Bedeutung |
|---|---|
| *(leer)* | ohne zusätzliche Hardware abschließbar |
| 🔌 | braucht den TI-Testaufbau (Konnektor, Kartenterminal, echte SMC-B) |
| 💻 | braucht andere Rechner (Raspberry Pi, Windows-/macOS-Host, Tablet) |

> **Eine Einschränkung, die für jedes ✔ gilt:** Die Nachweise laufen gegen den
> **Simulator**, nicht gegen echte Hardware. Die in
> [ADR-0007](adr/0007-simulierte-endpunkte-und-teststrategie.md) geforderte
> Kalibrierung steht aus. Für Anforderungen mit 🔌 ist das wesentlich — sie sind
> gegen unsere **Annahme** über die Geräte belegt, nicht gegen die Geräte.
>
> **Und ein Haken ist kein Nachweis.** Am 08.08.2026 stand `FA-UEB-010` auf
> „erfüllt", während die Funktion fehlte: Das Prüfintervall je Gerätegruppe
> wurde gepflegt, gespeichert, in Nachweisen belegt — und erreichte den Agenten
> nie. Am selben Tag fanden sich vier weitere solche Fälle. Der Nachweis ist der
> Verweis in der rechten Spalte, nicht das Zeichen in der linken.

## A.1 Geräteverwaltung (FA-GER)

| | Anforderung | | Stand |
|---|---|---|---|
| **✔** | `FA-GER-010` Konnektoren und HSK erfassen <br><span style="opacity:.7">MUSS</span> |  | Konnektoren erfassen, Zugangsdaten verschlüsselt (ADR-0008) |
| **✔** | `FA-GER-020` Kartenterminals erfassen <br><span style="opacity:.7">MUSS</span> |  | Terminals erfassen, PEviM nur mit SMC-B (A_24080) |
| **✔** | `FA-GER-030` Kartenterminals automatisch ermitteln <br><span style="opacity:.7">SOLL</span> |  | Ermittlung über den Konnektor, nichts wird selbsttätig übernommen |
| **✔** | `FA-GER-035` Informationsmodell des Konnektors auslesen <br><span style="opacity:.7">SOLL</span> |  | Informationsmodell, Aufrufkontext zur Auswahl; optional (NFA-QS-035) |
| **✔** | `FA-GER-040` Karten erfassen <br><span style="opacity:.7">MUSS</span> |  | alle Karten, eGK und HBA gekennzeichnet und unangetastet |
| **✔** | `FA-GER-050` Gerätegruppen <br><span style="opacity:.7">MUSS</span> |  | Anlegen, Zuordnen, eigenes Prüfintervall (wirkt nachweislich, FA-UEB-010), Löschen mit Umzug in die Vorgabegruppe. **Seit 09.08.2026 je Einrichtung** — vorher war die Einrichtung hart verdrahtet, alle Gruppen hingen an derselben; ebenso meldete der Agent seine Geräte hart unter „Praxis", womit die Mandantengrenze für Geräte nicht griff. [Umsetzung 19](03-umsetzung/19-pruefintervall-je-gruppe.md) |
| **✔** | `FA-GER-060` Bestandsübersicht bei großen Stückzahlen <br><span style="opacity:.7">MUSS</span> |  | serverseitige Paginierung, Filter, Massenaktionen; am Lasttest gemessen |

## A.2 Überwachung (FA-UEB)

| | Anforderung | | Stand |
|---|---|---|---|
| **✔** | `FA-UEB-010` PIN-Status der SMC-B überwachen <br><span style="opacity:.7">MUSS</span> |  | je Gerätegruppe getaktet — am Simulator gemessen (4 gegen 0 in 40 s) |
| **✔** | `FA-UEB-020` Erreichbarkeit überwachen <br><span style="opacity:.7">MUSS</span> |  | Wechsel protokolliert, bei gleichbleibendem Zustand nichts |
| **✔** | `FA-UEB-030` Firmware-Stand erfassen <br><span style="opacity:.7">MUSS</span> |  | Versionswechsel protokolliert, Flottenübersicht je Modell |
| **✔** | `FA-UEB-040` PEviM-Fähigkeit erkennen <br><span style="opacity:.7">MUSS</span> |  | PEviM-Fähigkeit erkannt und angezeigt |
| **✔** | `FA-UEB-050` Zustand „aktualisierend" respektieren <br><span style="opacity:.7">MUSS</span> |  🔌 | Einzelgerät und KSR-Lauf am Simulator. **Offen:** ob ein echter Konnektor den Zustand im GetPinStatus meldet |

## A.3 PIN-Verwaltung (FA-PIN)

| | Anforderung | | Stand |
|---|---|---|---|
| **✔** | `FA-PIN-010` PIN hinterlegen <br><span style="opacity:.7">MUSS</span> |  | Ende-zu-Ende vom Browser zum Agenten (ADR-0009) |
| **✔** | `FA-PIN-020` Automatische Verifikation <br><span style="opacity:.7">MUSS</span> |  🔌 | Durchstich über Brainpool-TLS am Simulator |
| **✔** | `FA-PIN-030` Schutz vor Kartensperrung <br><span style="opacity:.7">MUSS</span> |  🔌 | LeftTries vor jedem Versuch; genau ein Versuch bei falscher PIN |
| **✔** | `FA-PIN-040` Nicht behebbare Zustände melden <br><span style="opacity:.7">MUSS</span> |  | alle vier Zustände mit Handlung, je 0 Versuche |
| **✔** | `FA-PIN-050` Not-Aus <br><span style="opacity:.7">MUSS</span> |  | Not-Aus hält die Automatik sofort an |
| **✔** | `FA-PIN-060` PIN ändern <br><span style="opacity:.7">SOLL</span> |  | Über `SICCT MODIFY VERIFICATION DATA`: alte und neue PIN verdeckt, mit Bestätigung im Browser; Fehlversuchszähler wird vorher geprüft (FA-PIN-030). Bei Fehlschlag bleibt die hinterlegte PIN unverändert — nachgewiesen. [Umsetzung 23](03-umsetzung/23-pin-aendern.md) |

## A.4 Firmware-Verwaltung (FA-FW)

| | Anforderung | | Stand |
|---|---|---|---|
| **–** | `FA-FW-010` Firmware hochladen <br><span style="opacity:.7">MUSS</span> |  | Firmware-Verwaltung: spätere Stufe laut ADR-0004, Datenmodell steht |
| **–** | `FA-FW-020` Rollout-Pläne je Gerätegruppe <br><span style="opacity:.7">MUSS</span> |  | wie FA-FW-010 |
| **–** | `FA-FW-030` Gesteuerte Ausführung <br><span style="opacity:.7">MUSS</span> |  | wie FA-FW-010 |
| **–** | `FA-FW-040` Kanarienvogel-Prinzip <br><span style="opacity:.7">MUSS</span> |  | wie FA-FW-010 |
| **–** | `FA-FW-050` Parallelität <br><span style="opacity:.7">MUSS</span> |  | wie FA-FW-010 |
| **–** | `FA-FW-060` Nachbehandlung je Gerät <br><span style="opacity:.7">MUSS</span> |  | wie FA-FW-010 |
| **–** | `FA-FW-070` Nicht erreichbare Geräte <br><span style="opacity:.7">MUSS</span> |  | wie FA-FW-010 |
| **–** | `FA-FW-080` Kein Downgrade <br><span style="opacity:.7">MUSS</span> |  | wie FA-FW-010 |
| **–** | `FA-FW-090` Unbekannte Modelle <br><span style="opacity:.7">MUSS</span> |  | wie FA-FW-010 |

## A.5 Organisation (FA-ORG)

| | Anforderung | | Stand |
|---|---|---|---|
| **✔** | `FA-ORG-010` Mehrstufige Organisationsstruktur <br><span style="opacity:.7">MUSS</span> |  | dreistufig, über die Oberfläche pflegbar |
| **✔** | `FA-ORG-020` Mandantentrennung <br><span style="opacity:.7">MUSS</span> |  | Geltungsbereich als Pflichtparameter, 22 Tests inkl. Angriffe |

## A.6 Benutzer (FA-BEN)

| | Anforderung | | Stand |
|---|---|---|---|
| **✔** | `FA-BEN-010` Rollenmodell <br><span style="opacity:.7">MUSS</span> |  | Administrator/Benutzer, zusätzlich nach Ebene |
| **✔** | `FA-BEN-020` Anmeldung mit zweitem Faktor <br><span style="opacity:.7">MUSS</span> |  | Kennwort + TOTP, nicht abschaltbar; gegen RFC-Vektoren geprüft |
| **✔** | `FA-BEN-030` Onboarding <br><span style="opacity:.7">MUSS</span> |  | Einladung mit einmaligem Code, TOTP-Einrichtung mit Prüfschritt |
| **✔** | `FA-BEN-035` Startkonto aus der Konfiguration <br><span style="opacity:.7">SOLL</span> |  | Administratorkonto samt vorgegebenem zweitem Faktor über `START_ADMIN_*`. Der Zugang überlebt einen vollständigen Datenverlust — nachgewiesen. Ein vorhandenes Konto wird nie verändert. [DevOps](09-devops-installation.md) |
| **✔** | `FA-BEN-040` Verlust des zweiten Faktors <br><span style="opacity:.7">MUSS</span> |  | zweiter Faktor zurücksetzbar, protokolliert |
| **✔** | `FA-BEN-050` Benutzerverwaltung bei großer Anzahl <br><span style="opacity:.7">MUSS</span> |  | Live-Suche, Filter, serverseitige Paginierung |

## A.7 Protokollierung (FA-PRO)

| | Anforderung | | Stand |
|---|---|---|---|
| **✔** | `FA-PRO-010` Fachprotokoll <br><span style="opacity:.7">MUSS</span> |  | unveränderlich, nur Rotation darf löschen |
| **✔** | `FA-PRO-020` Keine Geheimnisse im Protokoll <br><span style="opacity:.7">MUSS</span> |  | zentral erzwungen, bei jedem Lauf durchsucht |
| **✔** | `FA-PRO-030` Protokollansicht mit Filtern <br><span style="opacity:.7">MUSS</span> |  | Filter nach Art, Ergebnis, Gerät; Blättern |
| **✔** | `FA-PRO-040` Excel-Export <br><span style="opacity:.7">MUSS</span> |  | Excel-Export, mandantengebunden |
| **✔** | `FA-PRO-050` Geräte-Zeitstrahl <br><span style="opacity:.7">MUSS</span> |  | Chronologische Sicht je Terminal, aus der Geräteliste in einem Klick. Zeitraum wählbar, Ereignisarten ein- und ausblendbar; der Filter wirkt nachweislich (30 von 99). [Umsetzung 24](03-umsetzung/24-zeitstrahl-und-vorgang.md) |
| **✔** | `FA-PRO-060` Vorgangsansicht <br><span style="opacity:.7">MUSS</span> |  | Alle Ereignisse einer Vorgangsnummer gemeinsam, mit Fortschritt (Ereignisse, betroffene Geräte, Fehlschläge). Selbsttätige Aktualisierung nur, solange der Vorgang läuft. [Umsetzung 24](03-umsetzung/24-zeitstrahl-und-vorgang.md) |
| **✔** | `FA-PRO-070` Speicherbudget und Rotation <br><span style="opacity:.7">MUSS</span> |  | Budget, Rotation ab 80 %, Kernfunktion läuft weiter |
| **✔** | `FA-PRO-080` Aufbewahrungsdauer <br><span style="opacity:.7">MUSS</span> |  | Pseudonymisierung nach Frist statt Löschung |

## A.8 Alarmierung (FA-ALA)

| | Anforderung | | Stand |
|---|---|---|---|
| **✔** | `FA-ALA-010` Alarmzustände <br><span style="opacity:.7">MUSS</span> |  | Alarme entstehen und schließen selbsttätig, quittierbar, keine Flut |
| **✔** | `FA-ALA-020` Benachrichtigung per E-Mail <br><span style="opacity:.7">SOLL</span> |  | E-Mail mit Sammelfenster; 50 Alarme ergeben eine Mail |

## A.9 Produktupdate (FA-UPD)

| | Anforderung | | Stand |
|---|---|---|---|
| **✔** | `FA-UPD-010` Neue Versionen erkennen <br><span style="opacity:.7">MUSS</span> |  | Registry-Abfrage ohne Docker-Rechte (ADR-0001), Änderungshinweise auf Deutsch, Sicherheitsupdates hervorgehoben. **Abgelaufene Zugangsdaten erscheinen als sichtbarer Fehlerzustand** — der schwierige Teil, nachgewiesen. [Umsetzung 25](03-umsetzung/25-neue-versionen-erkennen.md) |
| **–** | `FA-UPD-020` Geführte Ausführung <br><span style="opacity:.7">MUSS</span> |  | geführte Ausführung: nicht begonnen |
| **–** | `FA-UPD-030` Unbeaufsichtigte Patch-Updates <br><span style="opacity:.7">KANN</span> |  | unbeaufsichtigte Patch-Updates: KANN-Anforderung, bewusst zurückgestellt |

## A.10 Installation (FA-INS)

| | Anforderung | | Stand |
|---|---|---|---|
| **✔** | `FA-INS-010` Installation über Compose <br><span style="opacity:.7">MUSS</span> |  | `docker compose up`, Migrationen automatisch; seit 07.08. eigens geprüft |
| **✔** | `FA-INS-020` Einrichtungsassistent <br><span style="opacity:.7">MUSS</span> |  | Ersteinrichtung des ersten Administrators mit TOTP |
| **◐** | `FA-INS-030` Betriebsart <br><span style="opacity:.7">MUSS</span> |  | verteilt und zentral mit derselben Software. **Offen:** `.env.example` als Vorlage |
| **✔** | `FA-INS-040` Sicherung und Wiederherstellung <br><span style="opacity:.7">MUSS</span> |  | Wiederherstellung bei jedem Lauf durchgespielt; fehlende PINs werden benannt |

## A.11 Standort-Agent (FA-AGT)

| | Anforderung | | Stand |
|---|---|---|---|
| **✔** | `FA-AGT-010` Registrierung <br><span style="opacity:.7">MUSS</span> |  | einmaliges Geheimnis gegen dauerhaftes Token. **Seit 09.08.2026 in der Oberfläche** (vorher nur über die Kommandozeile) und mit **Wahl der Einrichtung** — sie stammt aus der Registrierung, nicht aus der Meldung des Agenten. [Umsetzung 19](03-umsetzung/19-pruefintervall-je-gruppe.md) |
| **✔** | `FA-AGT-020` Verbindung <br><span style="opacity:.7">MUSS</span> |  | ausgehend aufgebaut, Token-authentisiert |
| **✔** | `FA-AGT-030` Autonomer Weiterbetrieb <br><span style="opacity:.7">MUSS</span> |  | Agent arbeitet ohne Zentrale weiter |
| **✔** | `FA-AGT-040` Versionsunterschiede <br><span style="opacity:.7">MUSS</span> |  | Protokollversion gemeldet, bewertet, angezeigt; kein Abweisen |

## B.1 Sicherheit (NFA-SIC)

| | Anforderung | | Stand |
|---|---|---|---|
| **✔** | `NFA-SIC-010` Umgang mit Geheimnissen <br><span style="opacity:.7">MUSS</span> |  | PIN nie in der Zentrale, Zugangsdaten AES-256-GCM, Kennwörter Argon2id |
| **◐** | `NFA-SIC-020` Transportverschlüsselung <br><span style="opacity:.7">MUSS</span> |  🔌 | TLS 1.2 mit Brainpool zur TI nachgewiesen. **Offen:** TLS auf der eigenen Oberfläche — bisher Sache eines vorgeschalteten Vermittlers |
| **◐** | `NFA-SIC-030` Container-Härtung <br><span style="opacity:.7">MUSS</span> |  | Nicht-Root, minimales Basisimage. **Offen:** read-only Dateisystem, Capabilities streichen |
| **◐** | `NFA-SIC-040` Lieferkette <br><span style="opacity:.7">MUSS</span> |  | Abhängigkeiten gepinnt, Scan-Skript vorhanden. **Offen:** Scan im Bauvorgang verankern, SBOM |
| **✔** | `NFA-SIC-050` Sitzungen <br><span style="opacity:.7">MUSS</span> |  | Sitzung in der Datenbank, Anmeldebremse gegen Erraten |

## B.2 Datenschutz (NFA-DAT)

| | Anforderung | | Stand |
|---|---|---|---|
| **✔** | `NFA-DAT-010` Datensparsamkeit <br><span style="opacity:.7">MUSS</span> |  | keine Patienten- oder Gesundheitsdaten; eGK/HBA technisch ausgeschlossen |
| **◐** | `NFA-DAT-020` Auskunft und Löschung <br><span style="opacity:.7">MUSS</span> |  | Pseudonymisierung nach Frist. **Offen:** Auskunft und Löschbegehren vor Fristablauf |

## B.3 Leistung (NFA-LEI)

| | Anforderung | | Stand |
|---|---|---|---|
| **✔** | `NFA-LEI-010` Antwortzeiten der Oberfläche <br><span style="opacity:.7">MUSS</span> |  | Seitenaufbau unter 1 s am Lasttestbestand gemessen |
| **◐** | `NFA-LEI-020` Überwachungsdurchsatz <br><span style="opacity:.7">MUSS</span> |  | **Ohne Hardware messbar** — der Lasterzeuger schafft 5.000 Terminals. Offen: die 24-Stunden-Messung und die konfigurierbare Lastbegrenzung je Konnektor |
| **–** | `NFA-LEI-030` Ressourcenbedarf am unteren Ende der Skala <br><span style="opacity:.7">MUSS</span> |  💻 | Referenzhardware Raspberry Pi 5 oder i.MX8 — gemessen wurde auf einem M2 Pro |
| **◐** | `NFA-LEI-035` Plattenplatz <br><span style="opacity:.7">MUSS</span> |  | Plattenplatz: Budget und Rotation umgesetzt. **Offen:** Messung über längere Zeit |
| **–** | `NFA-LEI-037` Docker Desktop auf Windows und macOS <br><span style="opacity:.7">MUSS</span> |  💻 | Docker Desktop auf Windows und macOS — nicht erprobt |
| **◐** | `NFA-LEI-040` Skalierungsverhalten <br><span style="opacity:.7">SOLL</span> |  | Taktung je Gruppe gemessen. **Offen:** Lastbegrenzung, siehe NFA-LEI-020 |

## B.4 Verfügbarkeit (NFA-VER)

| | Anforderung | | Stand |
|---|---|---|---|
| **✔** | `NFA-VER-010` Fehlertoleranz gegenüber Fremdsystemen <br><span style="opacity:.7">MUSS</span> |  | Ausfall von Konnektor und Terminal am Simulator geprüft, Wiederaufnahme belegt |
| **✔** | `NFA-VER-020` Neustartfestigkeit <br><span style="opacity:.7">MUSS</span> |  | Neustart der Zentrale, Sitzungen und Protokoll überstehen ihn |
| **◐** | `NFA-VER-030` Health-Checks <br><span style="opacity:.7">MUSS</span> |  | Health-Checks in der Compose-Datei. **Offen:** eigener Endpunkt mit Aussage über die Datenbank |

## B.5 Bedienbarkeit (NFA-USA)

| | Anforderung | | Stand |
|---|---|---|---|
| **◐** | `NFA-USA-010` Barrierefreiheit <br><span style="opacity:.7">MUSS</span> |  | **WCAG 2.2 AA:** axe-core 17 Seiten ohne Befund, Kontraste rechnerisch. **Seit 09.08.2026:** alle 141 Bedienelemente mit Kurzhinweis (`title` und `aria-label`), klickbare Zeilen über echte Links statt stummer Flächen. [Umsetzung 26](03-umsetzung/26-ux-refactoring.md). **Offen:** Tastaturbedienung im Ablauf und Screenreader — beides ohne TI-Hardware prüfbar |
| **✔** | `NFA-USA-020` Sprache und Verständlichkeit <br><span style="opacity:.7">MUSS</span> |  | Deutsch durchgehend, Fehlermeldungen sagen, was zu tun ist. Kurzhinweise erklären die Wirkung, statt die Beschriftung zu wiederholen — maschinell geprüft |
| **◐** | `NFA-USA-030` Schutz vor Fehlbedienung <br><span style="opacity:.7">MUSS</span> |  | Bestätigung bei destruktiven Aktionen — **seit 09.08.2026 nachgewiesen** (kein warnender Knopf ohne Rückfrage), destruktive Knöpfe abgesetzt. Massenaktionen nennen die Menge. **Offen:** systematischer Durchgang aller Vorgänge auf sichtbaren Zustand |
| **◐** | `NFA-USA-040` Darstellung auf Endgeräten <br><span style="opacity:.7">MUSS</span> |  💻 | bricht ab 860 px um, Tabellen rollen. **Offen:** Prüfung auf einem echten Tablet |

## B.6 Betrieb (NFA-BET)

| | Anforderung | | Stand |
|---|---|---|---|
| **✔** | `NFA-BET-010` Konfiguration <br><span style="opacity:.7">MUSS</span> |  | ausschließlich Umgebungsvariablen, keine Secrets im Image |
| **✔** | `NFA-BET-020` Datenhaltung <br><span style="opacity:.7">MUSS</span> |  | Volumes getrennt, Sicherung und Wiederherstellung geprüft |
| **◐** | `NFA-BET-030` Mehrere Prozessorarchitekturen <br><span style="opacity:.7">MUSS</span> |  | **Ohne Hardware machbar:** `docker buildx` erzeugt beide Architekturen. `arm64` läuft, `amd64` nicht gebaut |
| **◐** | `NFA-BET-040` Technisches Protokoll <br><span style="opacity:.7">MUSS</span> |  | Ausgabe auf stdout mit Vorgangsnummern. **Offen:** strukturiertes JSON mit einstellbarem Detailgrad |

## B.7 Qualitätssicherung (NFA-QS)

| | Anforderung | | Stand |
|---|---|---|---|
| **✔** | `NFA-QS-010` Automatisierte Tests <br><span style="opacity:.7">MUSS</span> |  | 249 Modultests, im Bauvorgang verankert |
| **◐** | `NFA-QS-020` Nachweise am Testaufbau <br><span style="opacity:.7">MUSS</span> |  🔌 | 260 Systemnachweise gegen den Simulator. **Offen:** Kalibrierung an echter Hardware (ADR-0007) |
| **–** | `NFA-QS-030` Unterstützte Gerätematrix <br><span style="opacity:.7">MUSS</span> |  🔌 | Gerätematrix: geprüfte Kombinationen aus Konnektor, Modell und Firmware — setzt den Testaufbau voraus |
| **✔** | `NFA-QS-035` Webadmin-Funktionen sind optional <br><span style="opacity:.7">MUSS</span> |  | Webadmin-Funktionen sind optional, Ausfall hält nichts an |
| **◐** | `NFA-QS-040` Dokumentation <br><span style="opacity:.7">MUSS</span> |  | 22 Umsetzungsbeschreibungen, 10 ADRs, Betriebs- und Sicherheitsdokument. **Offen:** Betriebshandbuch für den Endkunden |
