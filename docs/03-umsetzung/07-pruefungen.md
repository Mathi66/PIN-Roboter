# Umsetzung: Prüfungen

**Anforderungen:** NFA-QS-010 (automatisierte Tests), NFA-QS-020 (Nachweise am
Testaufbau)

---

## Zwei Ebenen

| | Was | Wo |
|---|---|---|
| **Modultests** | Logik isoliert, 65 Stück | `mvn test` |
| **Systemnachweise** | Zusammenspiel am laufenden Stack, 37 Stück | `pruefungen/systemnachweise.sh` |

Modultests prüfen, ob eine Klasse tut, was sie soll. Systemnachweise prüfen, ob
das *Produkt* tut, was der Anforderungskatalog verlangt. Nach CLAUDE.md gilt ein
Akzeptanzkriterium erst mit dem zweiten als erfüllt.

## Was ein Test beweisen können muss

Drei Tests dieses Projekts prüfen gegen etwas, das **nicht von uns stammt** —
das ist der stärkste verfügbare Nachweis:

- **TOTP** gegen die offiziellen Testvektoren aus RFC 6238, Anhang B
- **Excel-Export** gegen `openpyxl`, eine unabhängige Umsetzung des Formats
- **Brainpool-TLS** gegen einen OpenSSL-Client

Wo das nicht geht, wird gegen die Spezifikation geprüft und die
Anforderungs-ID im Test genannt.

## Was Tests wertlos macht — aus eigener Erfahrung

Etwa die **Hälfte der Fehler** dieser Entwicklung saß nicht im Produkt, sondern
in den Prüfungen. Die sind gefährlicher, weil sie einen grünen Haken erzeugen,
der nichts bedeutet.

### Grün aus dem falschen Grund

Der erste Nachweis für FA-UEB-050 bestand — und war wertlos: Das simulierte
Firmware-Update dauerte 2 Sekunden, das Prüfintervall 4. Der Agent hatte den
kritischen Moment mit hoher Wahrscheinlichkeit gar nicht getroffen. Der Test
hätte auch bestanden, wenn die Schutzlogik komplett gefehlt hätte.

**Behoben:** Update dauert jetzt 30 Sekunden bei 4 Sekunden Intervall — rund
sieben erzwungene Prüfungen währenddessen.

### Ein blinder Wächter

Die Kontrolle, ob die Anmeldung im Prüfskript geklappt hat, nutzte `curl -f`.
Das schlägt bei 4xx und 5xx an — **nicht bei einer Umleitung**. Eine
fehlgeschlagene Anmeldung, die auf `/anmelden` umleitet, lief als Erfolg durch.

### Abhängigkeit von der Ausführungsreihenfolge

Zwei Speichertests hingen daran, wie viel ohnehin schon in der Datenbank lag.
Sie bemessen das Budget jetzt **nach** dem Befüllen.

### Feste Wartezeiten

Das Skript wurde rot, weil die Datenbank beim ersten Start länger braucht. Es
wartet jetzt aktiv auf beobachtbare Zustände.

### Was gar nicht geprüft wurde

Kein Nachweis hat je angesehen, ob die Oberfläche ihr **Stylesheet** bekommt.
Sie lief wochenlang ungestylt, weil `/statisch/stil.css` mit 404 beantwortet
wurde — und alle 34 Nachweise blieben grün. Aufgefallen ist es erst, als der
Product Owner die Seite im Browser öffnete.

Daraus folgt der wertvollste der neuen Nachweise: **keine Warnungen beim Start**.
Javalin hatte den Konfigurationsfehler gemeldet und lief trotzdem weiter. Diese
Prüfung fängt die ganze Fehlerklasse ab, nicht nur den einen Fall.

## Ausführen

```bash
pruefungen/systemnachweise.sh
```

Startet Datenbank, Simulator, Zentrale und Agent aus dem Nichts, führt die
Testsuite gegen eine frische Datenbank aus, weist 37 Systemeigenschaften nach
und räumt auf. Dauer rund 15 Minuten.
