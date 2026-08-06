# ADR-0002: Betriebsmodell und Systemtopologie

- **Status:** Vorschlag — technische Ausgestaltung, Bestätigung des PO erbeten
- **Datum:** 06.08.2026
- **Betrifft:** Architektur, Netzwerk, Sicherheit, Datenschutz

---

## Kontext

Der PO hat am 06.08.2026 entschieden: **dieselbe Software muss in zwei
Betriebsarten laufen.**

| Betriebsart | Wer betreibt | Größenordnung |
|---|---|---|
| **Verteilt** | die Einrichtung selbst | 1 Konnektor, ab 3 Kartenterminals |
| **Zentral** | eine Serviceorganisation für viele Einrichtungen | bis 1.000 HSK, bis 5.000 Terminals |

Der technische Kern des Problems: Der PIN-Roboter muss **Konnektor und
Kartenterminals über das Netzwerk erreichen**. Diese stehen immer im Netz der
Einrichtung. Im verteilten Betrieb ist das trivial. Im zentralen Betrieb liegen
Anwendung und Geräte in **verschiedenen Netzen**, oft hinter Firewalls, die
eingehende Verbindungen nicht zulassen — und das aus gutem Grund.

Zwei Betriebsarten mit zwei getrennten Codebasen zu lösen, ist keine Option:
doppelter Pflegeaufwand, doppelte Fehlerquellen, auseinanderlaufende Funktionen.

---

## Vorschlag: eine Software, zwei Bausteine

Die Anwendung wird in zwei Bausteine geschnitten, die **nach Nähe zum Gerät**
getrennt sind:

| Baustein | Aufgabe | Standort |
|---|---|---|
| **Zentrale** | Weboberfläche, Benutzer und Rollen, Mandanten, Datenhaltung, Regeln, Protokoll, Update-Manager | dort, wo verwaltet wird |
| **Standort-Agent** | spricht mit Konnektor (SOAP) und Kartenterminals (HTTPS); überwacht und verifiziert | immer im Netz der Einrichtung |

Die beiden Betriebsarten unterscheiden sich dann **nur noch in der Verteilung**,
nicht in der Software:

```
VERTEILT (Einzelpraxis)              ZENTRAL (Serviceorganisation)

┌── ein Compose-Stack ──┐            ┌─ Rechenzentrum ─┐
│  Zentrale             │            │   Zentrale      │
│  Standort-Agent       │            └────────▲────────┘
└───────────┬───────────┘                     │ ausgehende Verbindung
            │ lokales Netz               ┌────┴────┬──────────┐
     Konnektor + Terminals            Agent A   Agent B    Agent C
                                    (Praxis 1) (Praxis 2)  (MVZ 3)
                                         │         │          │
                                    Konnektor + Terminals je Standort
```

**Entscheidend für die Praxistauglichkeit:** Der Agent baut die Verbindung zur
Zentrale **von sich aus auf** (ausgehend). Damit sind **keine eingehenden
Firewall-Freigaben** im Praxisnetz nötig — das ist der Unterschied zwischen
„installierbar" und „scheitert am IT-Dienstleister des Kunden".

---

## Warum dieser Schnitt

1. **Eine Codebasis, beide Betriebsarten.** Im verteilten Fall laufen beide
   Bausteine im selben Compose-Stack auf einer Maschine — für den Betreiber
   unsichtbar, es bleibt eine Installation mit einem Startbefehl.
2. **Der Schnitt folgt einer echten Grenze**, nämlich der Netzgrenze. Solche
   Schnitte halten; willkürliche Schnitte erzeugen später Reibung.
3. **Skalierung wird beherrschbar.** Die Last der Geräteabfragen verteilt sich
   auf die Agenten. Die Zentrale sieht nur noch Zustandsmeldungen — das ist der
   Unterschied, der 5.000 Terminals überhaupt möglich macht.
4. **Ausfallverhalten wird besser.** Fällt die Verbindung zur Zentrale aus, kann
   der Agent weiterarbeiten und den TI-Betrieb der Praxis aufrechterhalten. Genau
   das ist im Störungsfall gefordert.

---

## Der Punkt, der vor der Umsetzung geklärt werden muss

**Im zentralen Betrieb verlässt die SMC-B-PIN das Praxisnetz.**

Das ist keine technische Nebenwirkung, sondern eine Grundsatzfrage:

- `A_24114` erlaubt PEviM nur für Clients, die unter der Kontrolle des Nutzers
  oder **seiner Organisation** stehen und deren Sicherheit diese durchsetzen
  kann. Betreibt eine Serviceorganisation die Zentrale, ist sie diese
  Organisation — das ist vertretbar, aber es muss **vertraglich getragen** sein
  (Auftragsverarbeitung nach DSGVO), nicht stillschweigend passieren.
- Die Praxis muss wissen und zustimmen, dass ihre SMC-B-PIN beim Dienstleister
  liegt. Das gehört in die Betriebsdokumentation und in den Onboarding-Prozess.

**Technische Empfehlung, die diese Frage entschärft:**
Die PIN wird **beim Standort-Agenten** vorgehalten, nicht in der Zentrale. Die
Zentrale steuert, protokolliert und zeigt an — sie kennt die PIN nicht. Damit
verlässt der PIN-Wert das Praxisnetz nie, in beiden Betriebsarten, und der
Schaden eines Einbruchs in die Zentrale ist um Größenordnungen kleiner: er
betrifft dann nicht die PINs sämtlicher betreuter Praxen.

Der Preis: PIN-Eingabe und -Änderung müssen bis zum Agenten durchgereicht
werden, und ein Agent-Verlust bedeutet erneute PIN-Hinterlegung. Beides ist
beherrschbar und aus meiner Sicht der Sicherheitsgewinn deutlich wert.

---

## Konsequenzen

- **Zwei Container-Images** (Zentrale, Agent) statt einem. Beide über GHCR,
  beide signiert, beide im Update-Manager aus ADR-0001 berücksichtigt. Für den
  Betreiber im verteilten Fall bleibt es **eine** Installation.
- Das **Protokoll zwischen Agent und Zentrale** wird ein tragendes Bauteil:
  gegenseitig authentifiziert, verschlüsselt, versioniert (Agent und Zentrale
  können unterschiedliche Versionen haben — das ist im zentralen Betrieb der
  Normalfall, nicht die Ausnahme).
- **Agent-Registrierung** braucht ein sicheres Verfahren: Wie wird ein neuer
  Agent einer Einrichtung zugeordnet, ohne dass sich ein fremder Agent
  einklinken kann?
- **Offline-Verhalten des Agenten** ist zu spezifizieren: Wie lange arbeitet er
  autonom weiter, was tut er bei fehlender Zentrale?

## Offene Fragen an den PO

1. **Soll die PIN ausschließlich beim Standort-Agenten liegen** (Empfehlung), oder
   ist eine zentrale Verwaltung der PINs fachlich gewünscht?
2. Gibt es beim zentralen Betrieb bereits bestehende Netzverbindungen zu den
   Kunden (VPN, Rechenzentrumsanbindung), auf die wir aufsetzen können?
