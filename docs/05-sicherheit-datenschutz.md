# Sicherheit und Datenschutz

**Stand:** 07.08.2026 · Grundlage: CLAUDE.md (Sicherheitsleitplanke),
[ADR-0002](adr/0002-betriebsmodell-und-systemtopologie.md),
[ADR-0008](adr/0008-ablage-von-geraete-zugangsdaten.md)

> Dieses Dokument beschreibt den **umgesetzten** Stand und benennt Lücken. Es ist
> keine Datenschutz-Folgenabschätzung — die obliegt dem Betreiber als
> Verantwortlichem im Sinne der DSGVO. Es liefert ihm aber die Angaben, die er
> dafür braucht.

---

## 1. Schutzbedarf — woher er kommt

**Der PIN-Roboter verarbeitet keine Patienten- oder Gesundheitsdaten.** Das ist
eine ausdrückliche Festlegung des Product Owners vom 06.08.2026 und im
Datenmodell durchgesetzt: Es gibt kein Feld, das medizinische Angaben aufnehmen
könnte.

Der Schutzbedarf ergibt sich stattdessen aus **einem einzigen Wert**: der
**SMC-B-PIN**.

Warum sie so schwer wiegt:

| | |
|---|---|
| Ihr Missbrauch | sperrt die Karte nach drei Fehlversuchen |
| Folge einer Sperrung | Die Einrichtung verliert ihren TI-Zugang. Kein eRezept, keine eAU, kein KIM. |
| Behebung | Nur mit dem PUK, physisch vor Ort, durch berechtigtes Personal |
| Bei zentralem Betrieb | Eine Zentrale mit allen PINs wäre ein Ziel, dessen Kompromittierung gleichzeitig hunderte Praxen lahmlegt |

Die gematik regelt das ausdrücklich: `A_24114` erlaubt die Vorhaltung der PIN nur
für Clients, deren Sicherheit die betreibende Organisation durchsetzen kann.

**Einstufung:** Reine Organisationssoftware, **kein Medizinprodukt**, kein
MDR-Verfahren (Festlegung PO, 06.08.2026).

---

## 2. Personenbezogene Daten — welche, wo, wie lange

| Datum | Wo | Zweck | Rechtsgrundlage (Einschätzung) |
|---|---|---|---|
| Benutzername, Anzeigename | Zentrale, Tabelle `benutzer` | Anmeldung, Zurechnung von Handlungen | Art. 6 Abs. 1 lit. b/f DSGVO — Beschäftigungsverhältnis, berechtigtes Interesse an Nachvollziehbarkeit |
| Kennwort-Hash (Argon2id) | Zentrale | Anmeldung | wie oben |
| TOTP-Geheimnis | Zentrale | zweiter Faktor | wie oben |
| Anzeigename im Fachprotokoll | Zentrale, `fachprotokoll.ausloeser` | **Nachvollziehbarkeit**: welche Person hat gehandelt | Art. 6 Abs. 1 lit. f — der Roboter handelt selbsttätig, seine Auslösung muss zurechenbar sein |
| Sitzungskennung | Zentrale, `sitzung` | Anmeldung aufrechterhalten | Art. 6 Abs. 1 lit. b |

**Nicht** verarbeitet: Patientendaten, Gesundheitsdaten, eGK-Inhalte,
HBA-Inhalte. eGK und HBA sind technisch ausgeschlossen (`A_24080` erlaubt PEviM
nur für SMC-B).

**Speicherdauer:** Benutzerkonten so lange, wie das Konto besteht.
Protokolleinträge bis zur Rotation — die greift nach Speicherbudget, nicht nach
Frist.

> **Lücke, offen:** Es gibt **keine Löschfrist** für Protokolleinträge und keine
> Funktion, einzelne Personenbezüge nachträglich zu entfernen. Das Fachprotokoll
> ist absichtlich unveränderlich (`FA-PRO-010`) — genau das kollidiert mit einem
> Löschbegehren nach Art. 17 DSGVO. **Der Konflikt ist real und ungelöst.**
> Naheliegende Auflösung: eine Frist, nach der der Anzeigename im Protokoll
> durch eine Kennung ersetzt wird (Pseudonymisierung statt Löschung), damit die
> Nachvollziehbarkeit der *Handlung* bleibt. Nicht umgesetzt.

---

## 3. Die Geheimnisse und wo sie liegen

Drei Arten, streng unterschieden:

| | SMC-B-PIN | Geräte-Zugang | Anmeldekennwort |
|---|---|---|---|
| Beispiel | PIN.SMC | Konnektor-Anmeldung, Terminal-Admin | Kennwort eines Benutzers |
| In der Zentrale | **nie** | verschlüsselt (AES-256-GCM) | nur als Argon2id-Hash |
| Beim Agenten | ja | ja (bezogen) | nein |
| Wiederherstellbar | — | ja, mit dem Schlüssel | nein, bewusst |
| Begründung | `A_24114`, PO 06.08.2026 | ADR-0008 | Stand der Technik |

**Warum die PIN nie in der Zentrale liegt:** Sie ist der eine Wert, dessen
Missbrauch die Einrichtung handlungsunfähig macht. Bei zentralem Betrieb für
viele Einrichtungen wäre eine zentrale PIN-Ablage nicht verantwortbar.
Ausführlich: ADR-0002, ADR-0008.

**Was die Verschlüsselung der Geräte-Zugänge nicht leistet:** Wer Datenbank
*und* Schlüssel hat, kann entschlüsseln. Das ist unvermeidbar, solange der Wert
wieder verwendbar sein muss. Der Schutz richtet sich gegen Sicherungen,
Datenauszüge und versehentliche Ausgaben — nicht gegen vollen Systemzugriff.
Auch das steht so in ADR-0008, statt beschönigt zu werden.

---

## 4. Technische und organisatorische Maßnahmen

### Umgesetzt und nachgewiesen

| Maßnahme | Umsetzung | Nachweis |
|---|---|---|
| **Zugangskontrolle** | Benutzername + Kennwort + TOTP, nicht abschaltbar | Systemnachweis: keine Oberflächenseite ohne Sitzung erreichbar |
| **Kennwortstärke** | eigene Prüfung, lehnt schwache und naheliegende Kennwörter ab | Modultests |
| **Kennwortablage** | Argon2id | Modultests |
| **Zweiter Faktor** | TOTP nach RFC 6238, gegen die offiziellen Testvektoren geprüft | Modultests gegen RFC-Vektoren |
| **Einmalkennwort gilt einmal** | verbrauchte Codes werden abgewiesen | Systemnachweis |
| **Rollentrennung** | Administrator / Benutzer; schreibende Bestandsaktionen nur für Administratoren | Routenprüfung |
| **Mandantentrennung** | `Geltungsbereich` als Pflichtparameter jeder mandantengebundenen Abfrage — Bestand, Ermittlung, Protokoll, Export, Alarme, Gruppen, Benutzerliste. Keine Zuordnung heißt „sieht nichts" | 22 Tests inkl. Angriffe über manipulierte Kennungen; Systemnachweise mit echter Anmeldung |
| **Agent-Authentisierung** | einmalig gültiges Registrierungsgeheimnis → dauerhaftes Token | Systemnachweise: erfundenes und wiederverwendetes Geheimnis werden abgewiesen |
| **Transportverschlüsselung TI** | TLS 1.2 mit Brainpool (BouncyCastle), Cipher-Suites nach `[gemSpec_Krypt]` eingeschränkt | Durchstich über Brainpool-TLS |
| **Zugangsdaten nicht auslesbar** | AES-256-GCM, kein Lesepfad gibt Klartext | Systemnachweise: kein Kennwort auf einer Oberflächenseite, keins im Protokoll |
| **Keine Geheimnisse in Protokollen** | zentral erzwungen, nicht durch Sorgfalt an Einzelstellen | Systemnachweis sucht PIN und Kennwörter in Agent-Ausgabe und Fachprotokoll |
| **Keine Geheimnisse im Repository** | `.gitignore`, Konfiguration nur über Umgebung | — |
| **Unveränderliches Fachprotokoll** | Änderung und Löschung sind unterbunden; nur die Rotation darf löschen | Systemnachweis und Modultest |
| **Nachvollziehbarkeit** | jede Handlung mit Auslöser, Zeitpunkt, Gerät, Ergebnis | 4 Sichten, Excel-Export |
| **Nicht-Root im Container** | Dockerfile | — |
| **Not-Aus** | hält die Automatik sofort an | Systemnachweis: 0 Versuche bei aktivem Not-Aus |
| **Kartenschutz** | `LeftTries` wird vor jedem Versuch geprüft; Abbruch statt Wiederholung | `FA-PIN-030`: genau **ein** Versuch bei falscher PIN, verpflichtend vor jeder Auslieferung |
| **Verfügbarkeit** | Agent arbeitet ohne Zentrale weiter; volles Protokoll hält die Kernfunktion nicht an | Systemnachweise |

### Offen — ehrlich benannt

1. **Mandantentrennung: umgesetzt für Bestand und Ermittlung, offen für
   Protokoll und Alarme.**

   Seit dem 07.08.2026 erzwingt ein `Geltungsbereich` die Trennung bei
   Konnektoren, Kartenterminals, Organisationsauswahl, Filterwerten,
   Massenaktionen und der Bestandsermittlung — **zentral, nicht in jeder
   Abfrage von Hand**: Die Methoden verlangen ihn als Pflichtparameter, wer ihn
   weglässt bekommt einen Übersetzungsfehler.
   Nachweis: `MandantentrennungTest` (16 Tests, darunter Angriffe über
   manipulierte Kennungen), dazu Systemnachweise über HTTP.

   **Seit dem 07.08.2026 gilt sie auch für Fachprotokoll, Alarme,
   Gerätegruppen und die Benutzerliste** (Migration 12 trägt eine
   `organisation_id` nach, mit Übernahme aus dem Textfeld). Der Excel-Export
   ist mit eingeschränkt — er wäre sonst der bequemste Weg an fremde Daten.
   Nachweis: 22 Tests, dazu Systemnachweise mit echter Anmeldung.

   **Was bleibt:** Rollen und Rechte sind noch nicht nach Ebene verfeinert (was
   darf Praxis, Standort, Verbund?), und neue Funde aus der Bestandsermittlung
   haben noch keine Zuordnung — die Grenze greift dort bei der Übernahme.
2. **Kein TLS auf der eigenen Oberfläche.** Die Zentrale spricht HTTP. Für den
   Zugriff über ein Netzsegment hinaus gehört ein TLS-Vermittler davor — nicht
   umgesetzt, nicht dokumentiert.
3. **Keine Löschfrist / Pseudonymisierung im Protokoll** (Abschnitt 2).
4. **Kein Abhängigkeits-Scan im Bauvorgang.** CLAUDE.md verlangt ihn. Die
   Abhängigkeiten sind gepinnt, aber nicht automatisch auf Schwachstellen
   geprüft.
5. **Keine Begrenzung fehlgeschlagener Anmeldungen.** Fehlversuche werden
   protokolliert, aber ein Konto wird nicht gesperrt und ein Absender nicht
   gebremst. Bei TOTP als zweitem Faktor ist das Risiko begrenzt, aber es ist
   eine Lücke.
6. **Kein Vier-Augen-Prinzip** für kritische Aktionen (PIN hinterlegen,
   Not-Aus aufheben). Nicht gefordert, aber in einer Klinik erwägbar.
7. **Kalibrierung an echter Hardware** (ADR-0007). Alle Aussagen über das
   Verhalten von Konnektor und Terminals beruhen auf dem Simulator.

---

## 5. Datenflüsse

```
   Browser ──HTTP──► Zentrale ──JDBC──► PostgreSQL
   (Praxis)          (Praxis/RZ)         (Volumes)
                          ▲
                          │ HTTP, Token
                          │ ausgehend aufgebaut
                     Standort-Agent  ──TLS 1.2/Brainpool──► Konnektor
                     (Praxisnetz)                            │
                          │                                  ▼
                          └──TLS 1.2/Brainpool────────► Kartenterminal
                                                        (PEviM, PIN)
```

**Was über welche Verbindung geht:**

| Verbindung | Inhalt | Enthält Geheimnisse? |
|---|---|---|
| Browser → Zentrale | Bedienung, Kennwörter bei der Eingabe | ja, beim Eintragen — deshalb gehört TLS davor |
| Agent → Zentrale | Gerätezustände, Ereignisse, ermittelter Bestand | **nein**, keine PIN, kein Kennwort |
| Zentrale → Agent | Not-Aus-Zustand (Abruf durch den Agenten) | nein |
| Agent → Konnektor | Aufrufkontext, Kartenhandle, PIN-Status | Zugangsdaten des Konnektors |
| Agent → Terminal | PEviM, **PIN** | **ja, die PIN** |

**Keine Verbindung nach außen.** Der PIN-Roboter ruft keine Dienste im Internet
auf; es gibt keine Telemetrie, keine Fehlerberichte an Dritte, keine
Auftragsverarbeitung. Die einzige externe Quelle ist die Container-Registry
beim Aktualisieren — und die wird bewusst angestoßen.

---

## 6. Was der Betreiber tun muss

Diese Punkte kann die Software nicht für ihn erledigen:

1. **`GEHEIMNIS_SCHLUESSEL` erzeugen und sichern** — aber nicht in derselben
   Datei wie den Datenbankauszug ([Betrieb, Abschnitt 3](04-betrieb.md)).
2. **Sicherungen an einen anderen Ort bringen** und die Wiederherstellung
   mindestens einmal selbst erproben.
3. **TLS-Vermittler** einrichten, wenn die Oberfläche über das lokale
   Netzsegment hinaus erreichbar sein soll.
4. **Konten von ausgeschiedenem Personal deaktivieren.** Die Software erinnert
   nicht daran.
5. **Verzeichnis von Verarbeitungstätigkeiten** ergänzen — Abschnitt 2 liefert
   die Angaben.
6. **Zugriff auf den Docker-Host beschränken.** Er ist der Punkt, an dem alle
   technischen Maßnahmen dieses Dokuments endeten.
