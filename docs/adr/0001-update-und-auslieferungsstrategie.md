# ADR-0001: Update- und Auslieferungsstrategie

- **Status:** Vorschlag — Entscheidung des Product Owners ausstehend
- **Datum:** 06.08.2026
- **Betrifft:** Auslieferung, Betrieb, Sicherheit

---

## Kontext

Der PIN-Roboter wird als Docker-Container in Arztpraxen und Kliniken betrieben.
Zielplattformen sind Docker unter **Linux, Windows und macOS**, Registry ist
**GHCR**. Die Betreiber haben in der Regel **kein IT-Personal**.

Daraus entsteht ein Zielkonflikt:

- **Ohne Automatik** werden Updates faktisch nie eingespielt. Sicherheitslücken
  bleiben jahrelang offen. Das ist der Normalfall in Praxis-IT, kein Sonderfall.
- **Mit blinder Automatik** kann ein fehlerhaftes Image den TI-Zugang ohne
  Vorwarnung und ohne anwesenden Fachmann lahmlegen — mitten im Sprechbetrieb.

Erschwerend: Der PIN-Roboter ist eine **betriebskritische Komponente**. Fällt er
aus, bleibt die SMC-B unverifiziert und die Praxis verliert den TI-Zugang.

### Ursprüngliche Vorgabe und deren Prüfung

Vorgabe des POs war **Watchtower**. Die Prüfung am 06.08.2026 ergab:

1. **`containrrr/watchtower` ist seit dem 17.12.2025 archiviert.** Die Maintainer
   haben die Entwicklung eingestellt. Es gibt keine Sicherheitspatches mehr.
2. Der aktivste Nachfolger ist der Fork **`nicholas-fedor/watchtower`**. Er wird
   gepflegt, ist aber ein von einer Einzelperson getragenes Projekt. Die
   ursprünglichen Maintainer weisen ausdrücklich darauf hin, dass sie die Forks
   nicht bewerten können.
3. Watchtower benötigt **Schreibzugriff auf den Docker-Socket**. Wer diesen
   Zugriff hat, kann auf dem Host praktisch alles tun — das entspricht
   Administratorrechten auf dem gesamten Rechner.
4. Auf **Docker Desktop unter Windows und macOS** ist die Socket-Einbindung
   fehleranfällig (abweichende Socket-Pfade, Probleme mit Windows-Pfaden). Bei
   drei Zielplattformen ist das eine dauerhafte Supportlast.

**Bewertung:** Punkt 1 in Verbindung mit Punkt 3 ist ein Ausschlusskriterium.
Ein nicht mehr gewartetes Fremdwerkzeug mit Root-äquivalenten Rechten auf einem
Rechner in einer Arztpraxis ist technisch nicht verantwortbar.

---

## Die entscheidende Einsicht

**Update-Erkennung und Update-Ausführung sind zwei verschiedene Dinge mit
völlig unterschiedlichem Rechtebedarf.**

- **Erkennen**, ob eine neue Version vorliegt, ist eine reine HTTPS-Abfrage an
  die Registry (OCI Registry API, Manifest-Digest). Dafür wird **kein
  Docker-Zugriff und kein Socket** benötigt.
- **Ausführen** des Updates braucht Docker-Rechte.

Alle bestehenden Werkzeuge vermischen beides und verlangen deshalb dauerhaft
hohe Rechte. Trennt man die Schritte, lässt sich der überwiegende Teil des
Nutzens ohne jedes Sicherheitsrisiko erreichen.

---

## Betrachtete Optionen

### A) Watchtower-Fork (`nicholas-fedor/watchtower`)
- **Pro:** geringster Umstellungsaufwand, bekannte Konfiguration.
- **Contra:** dauerhafter Socket-Schreibzugriff; Abhängigkeit von einem
  Einzelpersonen-Fork; kein Health-Gate, kein Rollback; keine Signaturprüfung;
  auf Windows/macOS fummelig; Updates ohne Ankündigung mitten im Betrieb.

### B) Diun (nur Benachrichtigung) + manuelles Update
- **Pro:** benötigt nur **lesenden** Socket-Zugriff, deutlich risikoärmer;
  aktiv gepflegt; der Betreiber behält die Kontrolle.
- **Contra:** aktualisiert nicht — jemand muss es tun. In einer Praxis ohne
  IT-Personal bedeutet das faktisch: es passiert nicht. Zusätzlicher
  Fremdcontainer, zusätzliche Konfiguration, Meldungen außerhalb unserer
  Oberfläche und nicht auf Deutsch.

### C) Host-Skript per geplanter Aufgabe (`docker compose pull && up -d`)
- **Pro:** **kein Container** erhält Docker-Rechte — sicherheitstechnisch die
  sauberste Variante.
- **Contra:** für jede Plattform ein eigener Mechanismus (systemd-Timer,
  Aufgabenplanung, launchd). Installation und Wartung liegen beim Betreiber.
  Kein Health-Gate, kein Rollback, keine Rückmeldung in der Oberfläche.

### D) Produkteigener Update-Manager mit geführter Ausführung *(Empfehlung)*
Der PIN-Roboter bringt die Update-Fähigkeit selbst mit, aufgeteilt nach
Rechtebedarf:

1. **Erkennung (ohne Sonderrechte):** Die Anwendung fragt GHCR regelmäßig per
   HTTPS ab, ob für den konfigurierten Kanal eine neue Version vorliegt. Sie
   zeigt in der Oberfläche an: Version, Änderungshinweise auf Deutsch,
   Sicherheitsrelevanz.
2. **Freigabe:** Ein Administrator entscheidet — sofort, im nächsten
   Wartungsfenster oder gar nicht. Sicherheitsrelevante Updates werden deutlich
   hervorgehoben.
3. **Ausführung:** Ein schlanker, mitgelieferter **Updater-Sidecar** führt das
   Update durch. Er erhält Docker-Zugriff **nur über einen Socket-Proxy mit
   minimalen Rechten**, beschränkt auf die Container dieses Produkts.
4. **Absicherung:** Vor dem Start wird die **Signatur des Images geprüft**
   (Cosign/Sigstore, schlüssellos über die GitHub-Actions-Identität signiert).
   Nach dem Start muss der Health-Check innerhalb einer Frist grün werden —
   sonst **automatischer Rollback** auf die vorherige Version, die lokal
   vorgehalten wird.
5. **Optional unbeaufsichtigt:** Betreiber, die es wünschen, können reine
   Patch-Updates im Wartungsfenster automatisch einspielen lassen. Funktions-
   und Major-Updates bleiben immer bestätigungspflichtig.

- **Pro:** Sicherheit und Bedienbarkeit gleichzeitig; ein einziges Produkt statt
  Fremdcontainer; deutschsprachige Oberfläche; Rollback; Signaturprüfung;
  Wartungsfenster; funktioniert auf allen drei Plattformen gleich; keine
  Abhängigkeit von einem fremden Einzelpersonen-Projekt.
- **Contra:** Eigenentwicklung. Der Updater-Sidecar braucht Docker-Zugriff — der
  ist jedoch über den Proxy eingeschränkt und liegt in unserer Verantwortung
  statt in der eines archivierten Fremdprojekts.

---

## Empfehlung

**Option D.**

Begründung in einem Satz: Sie ist die einzige Option, die gleichzeitig
verhindert, dass Updates nie eingespielt werden, **und** dass ein Update den
Praxisbetrieb unangekündigt lahmlegt.

Der Mehraufwand ist überschaubar, weil der aufwendige Teil — die Erkennung —
ohne Sonderrechte auskommt und in der Anwendung selbst liegt. Wenn der Aufwand
zunächst begrenzt bleiben soll, ist ein sinnvoller Zwischenschritt: **Stufe 1
(Erkennung und Anzeige in der Oberfläche) zuerst**, Ausführung zunächst per
mitgeliefertem Befehl, Sidecar später nachrüsten. Die Erkennung ist der Teil mit
dem größten Nutzen pro Aufwand.

---

## Maßnahmen, die unabhängig von der Wahl gelten

Diese Punkte sind Bestandteil der Umsetzung in jedem Fall:

1. **Keine `:latest`-Tags im Betrieb.** Ausgeliefert werden versionierte Tags,
   festgenagelt auf den Image-Digest.
2. **Signierte Images.** Signatur beim Bau in GitHub Actions (Cosign,
   schlüssellos über OIDC), Prüfung vor dem Einsatz.
3. **Getrennte Kanäle:** `stable` (geprüft) und `security` (dringende Patches).
   Kein Kunde bekommt ungetestete Stände.
4. **Health-Check und Rollback** — eine neue Version, die nicht gesund startet,
   wird automatisch zurückgenommen.
5. **Migrationen laufen automatisch, idempotent und rückwärtskompatibel**, damit
   ein Rollback nicht an der Datenbank scheitert.
6. **Multi-Arch-Images** für `linux/amd64` und `linux/arm64`.
7. **Update-Vorgänge im Audit-Log**: wer hat wann welche Version freigegeben,
   mit welchem Ergebnis.

---

## Offener Punkt für den PO

**Wird das Image auf GHCR öffentlich oder privat sein?**

- **Öffentlich:** Kunden können ohne Zugangsdaten aktualisieren. Einfachster
  Betrieb. Das Image ist dann aber für jeden abrufbar.
- **Privat:** Jede Installation benötigt Zugangsdaten für GHCR. Das bedeutet
  Verteilung, Rotation und Widerruf von Zugangstoken beim Kunden — spürbarer
  organisatorischer Aufwand, dafür Kontrolle über die Verbreitung.

Diese Entscheidung betrifft alle Optionen gleichermaßen und sollte vor der
Umsetzung fallen.

---

## Konsequenzen

- Der Update-Mechanismus wird **Produktbestandteil** und damit Teil unserer
  Qualitätsverantwortung — inklusive Tests auf allen drei Zielplattformen.
- Die Build-Pipeline (GitHub Actions) muss Signierung, Multi-Arch-Bau und
  Kanaltrennung leisten. Das ist früh einzurichten, nicht nachträglich.
- Die Weboberfläche erhält einen Bereich „Updates" mit eigener Berechtigung —
  nur Administratoren dürfen freigeben.
