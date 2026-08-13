# 33 — Einrichtungspaket für den Standort-Agenten

**Stand:** umgesetzt und am laufenden Stack nachgewiesen (13.08.2026)
· **Vorgabe:** PO, 13.08.2026

---

## Die Frage und die Antwort daneben

> „Können wir im PIN-Roboter das Image zum Download anbieten mit Anleitung, wie
> man es sich lokal installieren und starten kann?"

Das Image selbst anzubieten wäre falsch gewesen:

- Es sind **50 MB**, die die Zentrale erst beschaffen müsste — sie hat keinen
  Zugriff auf einen Docker-Dienst.
- Ein so verteilter Agent bekäme **nie ein Update**.
- Es gäbe zwei Bezugswege zum selben Baustein. Die laufen irgendwann
  auseinander.

Angeboten wird deshalb nicht die Software, sondern **das, was man sonst abtippt**:
eine fertige `docker-compose.yml`, eine vorausgefüllte `.env` und eine Anleitung.
Das Image kommt beim ersten Start aus GHCR.

## Was im Paket steckt

```
pin-roboter-agent/
├── docker-compose.yml   Image mit fester Version, Volume, mem_limit, cap_drop
├── .env                 Zentralen-Adresse und Zugangscode bereits eingetragen
└── ANLEITUNG.md         vier Schritte, Werte-Tabelle, Fehlertabelle
```

## Drei Entscheidungen

**Die Adresse der Zentrale kommt aus der Anfrage.** Die Zentrale kennt ihren
eigenen öffentlichen Namen nicht: Sie lauscht auf einem Port im Container,
davor stehen Proxy, DNS und TLS. Was der Browser benutzt, steht nur in `Host`
und `X-Forwarded-Proto`. Deshalb entsteht das Paket beim Herunterladen und
nicht beim Bauen des Images.

**Der Download ist ein POST.** Er erzeugt ein Registrierungsgeheimnis; bei
einem GET würde jedes Vorschaubild und jeder Verlaufseintrag gültige
Geheimnisse in die Datenbank schreiben.

**Das Geheimnis steht in der Datei.** Es gilt einmalig und 24 Stunden — genau
dafür ist es gebaut. Die Alternative wäre, einen vierzigstelligen Zufallswert
abzutippen; das endet in Tippfehlern und in Geheimnissen, die per E-Mail
wandern. Die Anleitung sagt, dass die Zeile nach der Registrierung gelöscht
werden kann. Der Download steht im Fachprotokoll.

**Was NICHT vorausgefüllt wird:** Konnektor-Adresse, Terminal-Adresse und
Vertrauensanker bleiben leer beziehungsweise als erkennbares Beispiel stehen.
Eine erfundene Adresse, die plausibel aussieht, kostet mehr Zeit als ein leeres
Feld.

## Der Ende-zu-Ende-Nachweis

Am 13.08.2026 tatsächlich durchgeführt: Paket heruntergeladen, entpackt, drei
Adressen eingetragen, `docker compose up -d`. Ergebnis im Protokoll des Agenten:

```
  TLS bereit (TLS 1.2, Brainpool über BouncyCastle)
  Registrierung erfolgreich, Token erhalten
  Schlüsselpaar bereit (PIN-Übergabe Ende-zu-Ende)
  Bestand gemeldet: 36 Terminals, Informationsmodell gelesen
```

**Dabei fiel ein Fehler auf, den kein Modultest gefunden hätte:** Der erste
Startversuch scheiterte mit `unauthorized`. Das GHCR-Repository ist **privat**
— eine Praxis kann das Image ohne Zugangsdaten gar nicht ziehen. Die Anleitung
behandelt diesen Fall jetzt in einem eigenen Schritt und in der Fehlertabelle.

> **Offene Entscheidung für den PO:** Soll das Agent-Image öffentlich sein? Es
> enthält keine Geheimnisse — bei Client-Software ist das üblich, und es erspart
> jeder Praxis einen `docker login` mit einem Token, das jemand verwalten muss.
> Solange es privat bleibt, braucht jeder Betreiber Zugangsdaten von euch.

## Nachgewiesen

Modultests (`AgentenpaketTest`, ohne Datenbank): drei Dateien, Zentralen-Adresse
und Zugangscode eingetragen, auszufüllende Felder leer, **keine PIN und kein
Kennwort im Paket**, feste Version statt `:latest`, brauchbares Compose auch
ohne Versionsangabe, Volume vorhanden, Anleitung zugeschnitten, Hinweis auf
Ablauf und Löschbarkeit des Codes, Sonderzeichen in Namen, Hinweis auf eine
nicht öffentliche Programmablage.

Ende zu Ende am laufenden Stack: Download liefert `application/zip` mit
`Cache-Control: no-store`, ein Agent aus diesem Paket registriert sich und
meldet seinen Bestand.
