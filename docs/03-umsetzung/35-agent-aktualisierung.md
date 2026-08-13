# 35 — Selbsttätige Aktualisierung des Standort-Agenten

**Stand:** umgesetzt (13.08.2026) · **Vorgabe:** PO, 13.08.2026
· Grundsatzentscheidung: CLAUDE.md, Abschnitt „Betrieb und Auslieferung"

---

## Der Anlass

> „Gerade am Anfang wird sehr häufig eine neue Version ausgerollt werden, um
> Probleme und Fehler zu beheben."

Ein Agent läuft im Praxisnetz. Wenn eine Fehlerbehebung nur ankommt, indem
jemand an jeden Rechner geht, kommt sie nicht an.

## Der Widerspruch, der aufzulösen war

Im Einrichtungspaket stand zuerst eine **feste Version**, mit der Begründung:
„Ein Update ist eine Entscheidung, kein Nebeneffekt." Das bleibt richtig — aber
auf einen gleitenden Tag zu verzichten heißt, die Entscheidung hundertfach vor
Ort zu treffen statt einmal zentral.

**Die Auflösung: ein Tag namens `stabil`, der nicht von selbst wandert.**

| | wandert wann | wer entscheidet |
|---|---|---|
| `:latest` | bei jeder Veröffentlichung | niemand |
| `:0.4.0` | nie | jeder Betreiber einzeln |
| **`:stabil`** | **bei ausdrücklicher Freigabe** | **wir, einmal** |

Der Workflow [`stabil-freigeben.yml`](../../.github/workflows/veroeffentlichen.yml)
hängt den Tag um. Er baut **nichts** neu — dasselbe Abbild bekommt einen
zweiten Namen — und verlangt das Wort `ausrollen` als Bestätigung, weil dieser
Klick in jede Praxis wirkt.

## Was im Einrichtungspaket dazugekommen ist

Zwei Dienste neben dem Agenten:

```
agent          → Label watchtower.enable=true
aktualisierung → Watchtower, nachts um 3:30, nur gelabelte Container
dockerzugang   → Vermittler zum Docker-Dienst
```

**Die drei Vorkehrungen aus CLAUDE.md, umgesetzt:**

1. **Nur der Agent.** `WATCHTOWER_LABEL_ENABLE` plus Label am Agenten — was
   sonst auf dem Rechner läuft, wird nicht angefasst. In einer Praxis steht dort
   womöglich noch ganz anderes.
2. **Nur nachts.** `0 30 3 * * *` in Ortszeit. Ein Neustart während der
   Sprechzeit unterbräche die Überwachung.
3. **Kein direkter Docker-Socket.** Wer ihn hat, ist faktisch Administrator des
   Rechners. Watchtower spricht stattdessen mit einem Vermittler
   (`docker-socket-proxy`), der nur Container und Abbilder freigibt — **nicht**
   `exec`, `volumes`, `networks`, `secrets`, `swarm`. Der Socket selbst ist nur
   dort eingebunden, nur lesend.

> **Ehrlich zum Gewinn:** Ein Vermittler, der `POST` auf Container erlaubt, ist
> kein Bollwerk — wer ihn erreicht, kann Container starten und stoppen. Er nimmt
> aber `exec` aus dem Spiel, also das Ausführen beliebiger Befehle in fremden
> Containern, und den Zugriff auf Datenträger. Das ist der Unterschied zwischen
> „kann diesen Stapel stören" und „ist Administrator des Rechners".

## Abschaltbar

Manche Häuser wollen jede Änderung selbst bestimmen. Die Anleitung im Paket
sagt, wie: die beiden Dienste entfernen und statt `:stabil` eine feste Nummer
eintragen. Kein verstecktes Verhalten und keine Einbahnstraße.

## Die Zugangsdaten kommen von der Zentrale

Der PO hat entschieden: **Das Repository bleibt privat.** Damit braucht jeder
Praxisrechner eine Anmeldung — nicht nur beim ersten Start, sondern bei jeder
Aktualisierung.

Der naheliegende Weg wäre, den Token ins Einrichtungspaket zu schreiben. Dann
stünde **derselbe Token in jeder Praxis**: in einer `.env`, sichtbar in
`docker inspect`, tauschbar nur durch einen Besuch vor Ort. Bei 150 betreuten
Einrichtungen ist ein Tokenwechsel damit praktisch unmöglich — und ein Token,
den man nicht wechseln kann, ist ein Token, den man nicht sperrt.

**Stattdessen holt der Agent ihn bei der Zentrale ab.** Sie kennt ihn ohnehin,
denn sie prüft damit selbst auf neue Versionen.

```
Zentrale                          Standort-Agent            Aktualisierung
  registry_zugang                   /api/agent/registry
  (Token verschlüsselt,     ──────► nur mit Agent-Token
   ADR-0008)                                │
  an_agenten = true/false                   ▼
                                    /registry/config.json ──► DOCKER_CONFIG
                                    (eigenes Volume, ro)
```

**Vier Eigenschaften, auf die es ankommt:**

1. **Ab Werk aus.** Wer einen Zugang hinterlegt, damit die Zentrale nach
   Updates sieht, hat damit nicht der Weitergabe an jeden registrierten Agenten
   zugestimmt. Das muss jemand einschalten und dabei wissen, was er tut.
2. **Eine Stelle zum Pflegen.** Ein neuer Token wird einmal hinterlegt; die
   Agenten übernehmen ihn im nächsten Takt. Kein Besuch vor Ort.
3. **Widerruf wirkt.** Ein widerrufener Agent bekommt nichts mehr — ein
   kompromittierter Praxisrechner lässt sich ausschließen, ohne den Token
   überall zu tauschen.
4. **Der Schalter ist der Not-Aus.** Abschalten heißt: Der Agent **löscht** die
   Datei. Ohne Anmeldung holt die Aktualisierung kein Abbild. Es gibt keinen
   zweiten Mechanismus, der das erzwingen müsste — es fehlt schlicht, was es
   dafür braucht.

**Eigenes Volume, nicht das Datenverzeichnis.** In `/daten` liegen Token,
privater Schlüssel und die PIN-Ablage. Watchtower geht das nichts an, auch nicht
lesend. Es bekommt ausschließlich `registryzugang:/registry:ro`.

**Ehrlich zum Gewinn:** Der Token landet trotzdem auf dem Praxisrechner; anders
kann die Aktualisierung ihn nicht benutzen. Gewonnen ist nicht Unsichtbarkeit,
sondern **Austauschbarkeit** — eine Stelle statt hundertfünfzig. Deshalb die
Empfehlung in der Oberfläche, ihn auf `read:packages` zu beschränken: Mehr
braucht ein Agent nie.

## Wenn es nicht greift, fällt es auf

Eine Aktualisierung, die stillschweigend nicht stattfindet, ist schlimmer als
gar keine: Man verlässt sich auf sie. Der abgelaufene Token, der angehaltene
Dienst, die nicht erreichbare Programmablage — nichts davon meldet sich.

**Prüfbar ist nur die Wirkung.** Ob Watchtower läuft, sieht weder die Zentrale
noch der Agent — der hat bewusst keinen Zugriff auf den Docker-Dienst. Was beide
sehen: Der Agent hängt zurück.

Deshalb prüft die Zentrale im Minutentakt (`agentenzustaendePruefen`):

| Befund | Schwere | Bedingung |
|---|---|---|
| **Agent stumm** | kritisch | meldet seit über 15 min nichts |
| **Agent veraltet** | Warnung | seit über 3 Tagen ältere Version als die Zentrale **und** Weitergabe eingeschaltet |

Ohne eingeschaltete Weitergabe wird **nicht** alarmiert: Dann aktualisiert
jemand von Hand, und ein Rückstand ist seine Entscheidung. Ein stummer Agent
wird nicht zusätzlich als veraltet geführt — das wäre eine zweite Meldung für
dieselbe Ursache. Beide Alarme schließen sich von selbst.

Vor Ort zeigt die Prüfliste des Agenten denselben Sachverhalt aus der anderen
Richtung: „Selbsttätige Aktualisierung — keine Zugangsdaten", mit dem Hinweis,
wo sie einzuschalten sind und wie man bis dahin von Hand aktualisiert.

### Zwei Fehler, die dabei aufgefallen sind

**Die Agentversion war immer „unbekannt".** Dem Agent-JAR fehlte der
`Implementation-Version`-Eintrag im Manifest; die Zentrale hatte ihn seit dem
10.08.2026, beim Agenten wurde er nicht nachgezogen. Die Spalte in der
Agentenliste zeigte seit ihrer Einführung nichts Verwertbares. Aufgefallen erst,
als die Version gebraucht wurde.

**Die Version wurde nur bei einem Protokollwechsel gespeichert.**
`versionMerken` brach ab, sobald die *Protokoll*version gleich blieb — und die
ändert sich fast nie. Ein Agent, der von 0.3.1 auf 0.3.2 wechselte, stand
weiter mit 0.3.1 in der Liste. Beide Fehler zusammen hätten die neue Überwachung
zu einer Anzeige gemacht, die nie ausschlägt.

## Was noch offen ist

**Keine Staffelung.** Alle Agenten ziehen dieselbe Nacht nach. Bei 150
Einrichtungen wäre ein gestaffelter Rollout (erst zehn, dann der Rest) die
sichere Variante — dafür bräuchte es zwei Tags, etwa `stabil` und `vorab`. Das
ist eine eigene Ausbaustufe; für den Anfang mit wenigen Standorten ist es
verhältnismäßig, es nicht zu tun.

**Kein Rückweg von selbst.** Stellt sich eine freigegebene Fassung als
fehlerhaft heraus, hängt man `stabil` auf die vorige Version zurück — die
Agenten ziehen sie in der nächsten Nacht. Innerhalb einer Nacht bleibt nur der
Weg über den Rechner.

## Nachgewiesen

`AgentenpaketTest`: Das Compose zieht `:stabil` und nie `:latest`, die
Aktualisierung ist auf den Agenten begrenzt und hat ein Zeitfenster, Watchtower
bekommt den Socket nicht direkt (`EXEC: "0"`, genau eine Einbindezeile, nur
lesend), und die Anleitung erklärt Zeitpunkt, Freigabe und das Abschalten.

`RegistrierzugangTest` (9 Prüfungen, Agent): Die Anmeldung landet im
Docker-Format auf der Platte und ist nicht welt-lesbar; ein Tokenwechsel wird
übernommen; **Abschalten entfernt die Datei**; unvollständige Angaben zählen wie
abgeschaltet; eine ältere Zentrale (HTTP 404) und ein Netzwerkausfall lassen den
vorhandenen Stand unangetastet; ohne Änderung entsteht keine Meldung.

`AgentenaktualisierungTest` (6 Prüfungen, Zentrale): Ab Werk gibt die Zentrale
nichts heraus, auch mit hinterlegtem Zugang; eingeschaltet liefert sie den
Klartext; Abschalten wirkt sofort; der Token liegt als `v1:`-Chiffrat in der
Datenbank; eine halbe Anmeldung wird nicht ausgeliefert.

Systemnachweise `FA-AGT-080` und `FA-AGT-090` am laufenden Stack: die vollständige
Kette vom Schalter in der Verwaltung bis zur Datei im Agentencontainer und
zurück, der Token in keinem Log und keinem Protokoll, ein unbekannter Agent ohne
Zugangsdaten — und beide Alarme provoziert und wieder aufgelöst.
