# ADR-0010 — Datensicherung: Format, Verschlüsselung, Wiederherstellung

**Status:** angenommen · **Datum:** 10.08.2026 · **Betrifft:** FA-BAK-010 bis 050

---

## Kontext

Bis heute war die Sicherung ein Kapitel im Betriebshandbuch mit Befehlen zum
Abtippen. Das setzt jemanden voraus, der sie regelmäßig ausführt — in einer
Arztpraxis ohne IT-Personal gibt es den nicht.

Was verloren geht, wenn die Datenbank verschwindet: Bestand,
Organisationsstruktur, Konten, hinterlegte Zugangsdaten und das gesamte
Fachprotokoll. Letzteres ist der Nachweis darüber, was der Roboter mit den
SMC-B getan hat. Es lässt sich nicht rekonstruieren.

Der PO hat am 10.08.2026 eine eingebaute Sicherung beauftragt: drei rollierende
Pläne, Download und Upload verschlüsselt mit einem Schlüssel, den der Benutzer
eingibt und der danach vergessen wird, sowie gezieltes Wiederherstellen mit
Neustart.

---

## Entscheidung 1: `pg_dump` statt eigenem Format

**Gewählt:** Der Auszug entsteht mit `pg_dump -Fc` (Custom Format), die
Wiederherstellung mit `pg_restore`. Der PostgreSQL-Client liegt im Image der
Zentrale.

**Verworfen — eigenes Format über JDBC:** Wir kennen unser Schema vollständig;
ein Auszug per `COPY … TO STDOUT` je Tabelle wäre machbar und käme ohne neue
Abhängigkeit aus.

**Warum trotzdem `pg_dump`:** Bei einer Sicherung ist das Risiko unsymmetrisch.
Ein Format, das sich im Ernstfall nicht zurückspielen lässt, ist schlimmer als
gar keine Sicherung — es erzeugt Sicherheit, die nicht da ist. `pg_dump` ist
seit Jahrzehnten erprobt und beherrscht Sequenzen, Fremdschlüssel-Reihenfolge,
`NULL`-Behandlung und Datentypen, die eine eigene Umsetzung erst nach und nach
richtig bekäme. Ein selbstgebautes Backup-Format ist in einem Produkt für
Gesundheitseinrichtungen das falsche Risiko.

**Konsequenz — Versionskopplung:** Der Client muss mindestens so neu sein wie
der Server. Das Basisimage (Ubuntu 26.04) liefert `postgresql-client-18`, die
Datenbank läuft auf 17.

> **Gemessen am 10.08.2026, nicht angenommen:** `pg_dump` 18.4 gegen Server 17
> erzeugte einen Auszug von 163 KB, `pg_restore` spielte ihn vollständig zurück,
> die Daten waren unverändert. Wer die Datenbank später anhebt, muss den Client
> mit anheben — das gehört in die Freigabemitteilung.

**Kosten:** rund 30 MB im Image der Zentrale.

---

## Entscheidung 2: AES-256-GCM mit abgeleitetem Schlüssel

**Gewählt:** Der Benutzer gibt beim Download eine Passphrase ein. Daraus wird
mit **Argon2id** ein Schlüssel abgeleitet (eigener Zufallssalt je Datei), und
die Datei wird mit **AES-256-GCM** verschlüsselt.

**Warum GCM:** Es verschlüsselt und schützt die Integrität in einem Schritt. Die
Anforderung verlangt beides; zwei getrennte Verfahren (Verschlüsselung plus
HMAC) hätten eine zusätzliche Stelle, an der man es falsch zusammensetzen kann.
Dieselbe Wahl wie in [ADR-0008](0008-ablage-von-geraete-zugangsdaten.md) und
[ADR-0009](0009-pin-hinterlegen-ueber-die-oberflaeche.md) — ein Verfahren im
ganzen Produkt, nicht drei.

**Warum Argon2id und nicht die Passphrase direkt:** Eine Passphrase ist keine
32 Byte Zufall. Ohne Schlüsselableitung mit Rechenaufwand wäre eine
heruntergeladene Datei mit einem Wörterbuchangriff zu öffnen. Argon2id ist im
Produkt bereits für Kennwörter im Einsatz.

**Warum ein Salt je Datei:** Sonst ergäbe dieselbe Passphrase immer denselben
Schlüssel, und eine einmal gebrochene Datei öffnete alle.

**Der Kopf der Datei bleibt unverschlüsselt** — Formatkennung, Version,
Salt, Nonce, Zeitpunkt der Sicherung. Er wird als *Additional Authenticated
Data* in die GCM-Prüfung einbezogen: lesbar, aber nicht veränderbar. So kann die
Oberfläche vor dem Entschlüsseln sagen, was für eine Datei das ist, statt bei
einer falschen Datei nur „Schlüssel falsch" zu melden.

**Der Schlüssel wird nirgends gespeichert.** Nicht in der Datenbank, nicht im
Protokoll, nicht über den Vorgang hinaus im Speicher: Er wird als `char[]`
geführt und nach Gebrauch überschrieben. Das ist die ausdrückliche Vorgabe des
PO — und es bedeutet, dass ein verlorener Schlüssel die Datei endgültig
unbrauchbar macht. Die Oberfläche sagt das **vor** dem Download.

---

## Entscheidung 3: Neustart nach der Wiederherstellung

**Gewählt:** Nach dem Einspielen beendet sich die Zentrale, und Docker startet
sie neu (`restart: unless-stopped`).

**Warum nicht weiterlaufen:** Die Zentrale hält Zustand im Arbeitsspeicher —
gemeldete Gerätezustände, Sitzungen, offene PIN-Aufträge. Dieser Zustand gehört
zum *alten* Datenstand. Nach einer Wiederherstellung verweist er auf Geräte, die
es nicht mehr gibt, oder auf Aufträge, die nie erteilt wurden. Ein Neustart ist
der einzige Weg, der garantiert keinen Mischzustand hinterlässt.

**Warum das vertretbar ist:** Die Wiederherstellung ist ein bewusster,
seltener, angekündigter Eingriff. Die Kernfunktion — Überwachung und
Freischaltung — läuft im **Agenten** und ist vom Neustart der Zentrale nicht
betroffen ([ADR-0002](0002-betriebsmodell-und-systemtopologie.md)). Der
Betreiber verliert seine Oberfläche für einige Sekunden, nicht die Funktion.

**Konsequenz:** `restart: unless-stopped` wird für die Zentrale zur
Voraussetzung. Ohne die Neustartregel bliebe sie nach dem Einspielen aus. Das
steht in [09-devops-installation.md](../09-devops-installation.md) und ist in
`docker-compose.yml` gesetzt.

**Eine Sicherheitsleine davor:** Vor dem Überschreiben entsteht automatisch eine
Sicherung des aktuellen Zustands. Eine versehentliche Wiederherstellung darf
nicht der letzte Stand sein.

---

## Entscheidung 4: Eigenes Volume für Sicherungen

Sicherungen liegen in `sicherungen`, getrennt von `betriebsdaten`.

Eine Sicherung, die im selben Volume liegt wie die Datenbank, ist keine: Der
häufigste Verlustfall ist ein versehentliches `docker compose down -v` — genau
das, was in diesem Projekt am 09.08.2026 dreimal passiert ist und den PO
ausgesperrt hat.

**Was das nicht leistet:** Beide Volumes liegen auf demselben Wirt. Gegen einen
Festplattenausfall hilft nur, die Dateien herunterzuladen und woanders
aufzubewahren — dafür gibt es FA-BAK-020. Die Oberfläche sagt das, statt eine
Sicherheit vorzugeben, die nicht besteht.

---

## Konsequenzen

**Gut:**
- Der Betreiber muss nichts tun, um gesichert zu sein.
- Eine Sicherung verlässt die Instanz nur verschlüsselt.
- Die Wiederherstellung hinterlässt keinen Mischzustand.

**Preis:**
- Rund 30 MB mehr im Image der Zentrale.
- Versionskopplung zwischen Client und Server.
- Ein verlorener Schlüssel macht eine heruntergeladene Datei endgültig
  unbrauchbar — das ist gewollt, muss dem Betreiber aber klar sein.
- Die Zentrale braucht die Neustartregel.

**Offen:**
- **Sicherung des Agenten** ist damit nicht abgedeckt. Sein Volume enthält
  Token, Schlüsselpaar und die PIN-Ablage. Ein Verlust bedeutet: neu
  registrieren und PINs neu hinterlegen — unangenehm, aber ohne Datenverlust.
  Ob das genügt, ist eine Frage an den PO.
- **Kein Auslagern an einen Zielort** (SFTP, Netzlaufwerk, Objektspeicher). Der
  Download ist Handarbeit. Für eine Serviceorganisation mit dreißig Instanzen
  wäre das zu wenig.
