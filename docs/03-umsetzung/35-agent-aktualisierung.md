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

## Was noch offen ist

**Das GHCR-Repository ist privat** (Befund 13.08.2026). Solange das so bleibt,
braucht jeder Praxisrechner Zugangsdaten — auch für die Aktualisierung, nicht
nur für den ersten Start. Bei einem öffentlichen Agent-Image entfällt das. Die
Entscheidung liegt beim PO.

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
