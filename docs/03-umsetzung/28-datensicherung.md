# 28 — Datensicherung, Einstellungen, Wiederherstellung

**Stand:** 10.08.2026 · **Anforderungen:** FA-BAK-010 bis 050 · **Grundlage:** [ADR-0010](../adr/0010-datensicherung-und-wiederherstellung.md)

---

## Der Auftrag

Vorgabe des PO: ein Menü „Einstellungen" mit Datensicherung und der Rubrik
„Systemtest", drei rollierende Sicherungspläne, verschlüsselter Download und
Upload mit einem Schlüssel, der danach vergessen wird, und gezieltes
Wiederherstellen mit Neustart.

Bis dahin war die Sicherung ein Kapitel im Betriebshandbuch mit Befehlen zum
Abtippen. Das setzt jemanden voraus, der sie regelmäßig ausführt — in einer
Praxis ohne IT-Personal gibt es den nicht.

> Was verloren geht, wenn die Datenbank verschwindet: Bestand,
> Organisationsstruktur, Konten, verschlüsselte Zugangsdaten und das gesamte
> Fachprotokoll. Letzteres ist der Nachweis darüber, was der Roboter mit den
> SMC-B getan hat, und lässt sich nicht rekonstruieren.

---

## Was gebaut wurde

### Drei Pläne, unabhängig voneinander

| Plan | behält | Rotation |
|---|---:|---|
| täglich | 7 | wöchentlich rollierend |
| wöchentlich | 5 | monatlich rollierend |
| monatlich | 12 | jährlich rollierend |

Ein Stand zählt zu genau einem Plan. Der wöchentliche ist **keine Kopie** des
täglichen, sondern ein eigener Auszug — sonst hinge die längere Aufbewahrung
daran, dass der kürzere Plan nicht ausfällt.

Geprüft wird stündlich, nicht zur vollen Uhrzeit: Eine Instanz, die nachts aus
ist, würde sonst nie sichern. Fällig ist ein Plan, wenn sein letzter Stand
älter als sein Abstand ist — damit holt er nach dem Einschalten auf, statt
einen Tag zu überspringen.

**Der älteste Stand wird entfernt, nie der neueste** — auch nicht bei knappem
Speicher. Eine Rotation, die im Zweifel den frischesten Stand wegwirft, hat
ihren Zweck verfehlt.

**Eine fehlgeschlagene Sicherung ist ein Alarm**, keine Logzeile. Eine
Sicherung, von der niemand weiß, dass sie ausblieb, ist keine.

### Der Auszug entsteht als `.teil`

Und wird erst nach Erfolg umbenannt. Sonst läge nach einem Abbruch eine
unvollständige Datei in der Ablage, die aussieht wie eine Sicherung — und im
Ernstfall zur Wiederherstellung gewählt würde.

### Verschlüsselung beim Download

```
Magie      8 Byte   "PRSICH01"
Salt      16 Byte   je Datei neu
Nonce     12 Byte   je Datei neu
Zeit       8 Byte   Zeitpunkt der Sicherung
Chiffrat   Rest     AES-256-GCM
```

Der Kopf bleibt lesbar, geht aber als *Additional Authenticated Data* in die
GCM-Prüfung ein: **lesbar, aber nicht veränderbar**. So kann die Oberfläche vor
dem Entschlüsseln sagen, was für eine Datei das ist, statt bei einer
versehentlich gewählten Fremddatei nur „Schlüssel falsch" zu melden.

Der Schlüssel entsteht mit **Argon2id** aus der Passphrase — eigener Salt je
Datei, damit nicht eine gebrochene Datei alle öffnet.

**Falscher Schlüssel und veränderte Datei führen zur gleichen Meldung.** Das
ist Absicht: Die Unterscheidung wäre für einen Angreifer nützlicher als für den
Betreiber — er erführe, ob er beim Raten näher kommt.

### Der Schlüssel wird vergessen

Er kommt als `char[]` herein und wird vor der Rückkehr überschrieben, auch im
Fehlerfall. Nicht in der Datenbank, nicht im Protokoll, nicht über den Vorgang
hinaus im Speicher.

Das bedeutet: **Geht er verloren, ist die Datei endgültig unbrauchbar.** Die
Oberfläche sagt das **vor** dem Download, auf einer eigenen Seite — nicht in
einem Aufklapper neben dem Knopf, der überblättert würde.

### Wiederherstellung mit Neustart

Vor dem Überschreiben entsteht eine Sicherung des aktuellen Zustands. Eine
versehentliche Wiederherstellung darf nicht der letzte Stand sein.

`pg_restore` läuft mit `--single-transaction`: ganz oder gar nicht. Ein halb
eingespieltes Backup wäre schlimmer als keines.

Danach beendet sich die Zentrale, und Docker startet sie neu. Der Grund steht
in ADR-0010: Sie hält Zustand im Arbeitsspeicher — gemeldete Geräte, Sitzungen,
offene Aufträge —, und der gehört zum *alten* Datenstand. **Die Kernfunktion
läuft im Agenten weiter** und ist vom Neustart nicht betroffen.

Erst wird geantwortet, dann beendet: Sonst sieht der Betreiber einen
Verbindungsabbruch statt einer Bestätigung.

### Einstellungen als eigener Bereich

Demodatensatz und Leistungsmessung sind von „Instanz" nach „Einstellungen →
Systemtest" umgezogen. Unter „Instanz" geht es um den **laufenden Betrieb** —
Agenten, Speicher, Produktversion. In den Einstellungen um Dinge, die man
selten und bewusst tut.

---

## Was am laufenden System geprüft wurde

18 Systemnachweise, alle grün:

| Prüfung | Ergebnis |
|---|---|
| Sicherung von Hand | 151 KB |
| keine unvollständigen Auszüge | 0 |
| Formatkennung im Download | `PRSICH01` |
| kein Klartext-Auszug in der Datei | bestätigt |
| zu kurzer Schlüssel | abgewiesen mit Begründung |
| abweichende Wiederholung | abgewiesen |
| Schlüssel in Protokoll oder Log | 0 / 0 |
| Rückweg: hochladen | eingelesen |
| falscher Schlüssel | abgewiesen |
| **ein einziges verändertes Byte** | **erkannt** |
| Fremddatei | als solche benannt |
| ohne Administratorrechte | HTTP 302 |
| Pfadausbruch über den Dateinamen | HTTP 404 |
| Wiederherstellung | Änderung verschwunden |
| Sicherheitskopie davor | entstanden |

---

## Was dabei gefunden wurde

### Der Prüfstand testete nicht das Auslieferungsimage

Er startete die JAR in einem nackten `eclipse-temurin:21-jre`. Damit prüfte er
die Anwendung, aber **nichts von dem, was im Bild steckt**. Aufgefallen ist es
an `pg_dump`: Der Nachweis schlug fehl, obwohl die Funktion am laufenden Stack
nachweislich arbeitete.

Das widersprach der eigenen Definition of Done — „Läuft im Docker-Stack aus dem
Repository, verifiziert, nicht angenommen". Der Prüfstand läuft jetzt gegen das
Bild, das beim Kunden landet.

### Zwei Orte für dieselbe Datei

`docker compose build` baut **im Container**, `mvn package` **lokal**. Der
Prüfstand mountet die lokale JAR. Nach einer Korrektur, die nur über
`compose build` ging, lief im Prüfstand noch der alte Stand — und schlug mit
einem Fehler fehl, den ich am laufenden System bereits behoben hatte.

### Der Port fehlte

Beim Zerlegen der JDBC-Adresse blieb `datenbank:5432` als Rechnername stehen.
`pg_dump` meldete „could not translate host name". Der Port muss über `-p`
gehen, nicht im Hostnamen — gefunden beim ersten Versuch am laufenden System.

---

## Grenzen, die bleiben

- **Der Agent wird nicht gesichert.** Sein Volume enthält Token, Schlüsselpaar
  und PIN-Ablage. Verlust bedeutet: neu registrieren, PINs neu hinterlegen —
  unangenehm, aber ohne Datenverlust. Ob das genügt, ist eine Frage an den PO.
- **Kein Auslagern an einen Zielort.** Der Download ist Handarbeit; für eine
  Serviceorganisation mit dreißig Instanzen zu wenig.
- **Beide Volumes liegen auf demselben Wirt.** Gegen einen Festplattenausfall
  hilft nur der Download. Die Oberfläche sagt das, statt eine Sicherheit
  vorzugeben, die nicht besteht.
