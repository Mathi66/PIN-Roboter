# 39 — Aktualisierung nur auf Auftrag, ohne Token am Standort

**Stand:** umgesetzt (14.08.2026) · **Anforderung:** `FA-AGT-080`, `FA-AGT-100`
· **Vorgabe:** PO, 14.08.2026 · löst [Umsetzung 35](35-agent-aktualisierung.md) ab

---

## Wie es dazu kam

Zwei Rückfragen des PO beim Lesen des Handbuchs:

> „Ich dachte, eine selbstständige Aktualisierung des Agenten ist nicht
> möglich. Der Server gibt dem Agenten den Auftrag zur Aktualisierung und
> übermittelt ihm dafür die Zugangsdaten zum privaten Repo?"

Und, kurz darauf:

> „Wir wollten doch explizit nicht, dass der Agent Zugriffstoken lokal
> vorhält."

Beide Male hatte er recht — und beide Male hing es zusammen.

## Was vorher war

| | bis 13.08.2026 |
|---|---|
| Nächtlicher Lauf | Watchtower, 3:30 Uhr, zieht `stabil` |
| Auf Auftrag | zusätzlich möglich (Umsetzung 36) |
| Zugangsdaten | **dauerhaft** als `config.json` am Standort |
| Weitergabe | ständig, an jeden registrierten Agenten |

Der dauerhafte Token war kein Versehen — er war die **Folge** des nächtlichen
Laufs. Watchtower musste um halb vier allein an die Anmeldung kommen, ohne dass
jemand etwas anstößt. Das war in der Dokumentation als Einschränkung benannt
(„Gewonnen ist nicht Unsichtbarkeit, sondern Austauschbarkeit"), aber es blieb
eine Einschränkung.

## Die erste Entscheidung: kein nächtlicher Lauf

Vorgelegt mit dem fachlichen Bedenken: Der nächtliche Lauf zieht `stabil`. Der
Tag wandert zwar nur bei ausdrücklicher Freigabe — aber wenn eine fehlerhafte
Fassung freigegeben wird, trifft es **in derselben Nacht alle Standorte
gleichzeitig**. Eine Staffelung gibt es nicht. Genau davor warnt CLAUDE.md unter
„unkontrollierte Updates".

**Entscheidung des PO: nur auf Auftrag.**

Damit fällt der Grund für den dauerhaften Token weg. Er wird nur noch in dem
Moment gebraucht, in dem ein Mensch das Update ausgelöst hat.

## Die zweite Entscheidung: kein Watchtower

> „Vielleicht ist Watchtower dafür nicht die günstigste Lösung, da er ja nichts
> überwachen soll. Wir brauchen ja nur ein Update-Skript, das auf Weisung der
> Zentrale das Installationsimage holt und installiert."

Richtig. Und es löst zugleich das Token-Problem: Watchtower liest seine
Anmeldung **nur** aus einer `config.json`, die dafür auf der Platte liegen muss.
Ein eigenes Skript kann den Token über die **Standardeingabe** annehmen.

Von drei Wegen fiel die Wahl auf den mittleren:

| Weg | Bewertung |
|---|---|
| Watchtower behalten | erprobt, aber Überwachungswerkzeug ohne Überwachungsaufgabe — und die Datei bleibt |
| **eigener Dienst mit `docker compose`** | **gewählt** — compose kennt die Konfiguration, es muss nichts nachgebaut werden |
| eigener Dienst über die Docker-API | volle Kontrolle, aber Volumes, Netze, Ports und Labels selbst nachbauen. Genau daran scheitern solche Eigenbauten; ein Fehler macht den Agenten in einer Praxis unbrauchbar. Abgeraten. |

## Wie es jetzt läuft

```
Administrator ──► Auftrag in der Zentrale
                       │
Agent (Prüftakt) ──────┤ holt den Auftrag
                       │ holt die Zugangsdaten  ← nur jetzt, nur bei Auftrag
                       │ quittiert
                       ▼
                  /auftrag/auftrag        4 Zeilen, Rechte 600
                       │
Aktualisierer ─────────┤ liest und LÖSCHT als Erstes
                       │ docker login (Token über stdin)
                       │ docker compose pull agent
                       │ docker compose up -d agent   ← Agent wird ersetzt
                       │ docker logout
                       ▼
Agent (neu) ───────────┤ räumt beim Start einen liegengebliebenen Auftrag weg
                       │ meldet seine Version
                       ▼
              ERFOLGREICH  oder  FEHLGESCHLAGEN
```

### Der Briefkasten statt eines Netzwerkdienstes

Zuerst war es ein HTTP-Aufruf an Watchtower mit gemeinsamem Kennwort. Eine Datei
in einem geteilten Volume ist einfacher **und** dichter: kein Dienst, der auf
Anfragen lauscht, kein Kennwort, das zueinander passen muss, keine
Schnittstelle, die jemand im Container-Netz erreichen könnte.

Das Kennwort `AKTUALISIERUNG_TOKEN` in der `.env` ist damit entfallen.

### Der Token, dreifach begrenzt

1. **Die Zentrale gibt ihn nur bei einem offenen Auftrag heraus.** Ein Agent,
   der ohne Anlass fragt, bekommt `403`.
2. **Er liegt Sekunden, nicht dauerhaft.** Der Aktualisierer löscht die
   Auftragsdatei als Erstes, noch vor dem Anmelden.
3. **Er steht in keiner Prozessliste.** `docker login --password-stdin`, nicht
   als Befehlszeilenargument. Danach `docker logout`.

Bleibt trotzdem etwas liegen — weil der Aktualisierungsdienst gar nicht läuft —,
räumt der Agent beim nächsten Start auf und **sagt das auch**.

**Ehrlich zur Grenze:** Wer in dem Moment, in dem ein Update läuft,
Administrator des Praxisrechners ist, kommt an den Token. Gewonnen ist die
Zeit — und auf einem Rechner, an dem niemand aktualisiert, liegt überhaupt
nichts.

### Das Skript liegt offen

`aktualisieren.sh` ist Teil des Einrichtungspakets. Wer es liest, weiß danach
genau, was auf seinem Rechner passiert — bei einem fremden Abbild ist das nicht
so. Es sind dieselben vier Befehle, die auch in der Anleitung für die
Aktualisierung von Hand stehen.

Angefasst wird ausschließlich der Dienst `agent`. Ohne diese Einschränkung
beendete sich der Aktualisierer mitten im eigenen Befehl.

### Der Socket-Vermittler bleibt

`compose` braucht mehr als Watchtower: zusätzlich `NETWORKS`, `VOLUMES` und
`INFO`. Nicht freigegeben bleibt, worauf es ankommt — `EXEC` (Befehle in fremden
Containern), `SECRETS`, `CONFIGS`, `SWARM`.

## Ein Fund am laufenden System

Der erste Anlauf scheiterte mit:

> Der Auftrag ließ sich nicht ablegen: /auftrag/auftrag — ist /auftrag
> beschreibbar?

Docker legt ein leeres Volume als `root` an; der Agent läuft als `10001`. Die
Lösung ist dieselbe, die für `/daten` seit jeher gilt: Das Verzeichnis wird
**im Abbild** angelegt und dem Agenten übereignet — Docker übernimmt beim
Mounten die Rechte des Mountpunkts aus dem Abbild.

Aufgefallen ist das nur, weil die Kette am laufenden Stack durchgespielt wurde.
Kein Modultest hätte es gefunden.

## Nachgewiesen

`RegistrierzugangTest` (12 Prüfungen, neu geschrieben): Zugangsdaten kommen an
und werden nirgends gespeichert; der Token ist ein `char[]` und löschbar; ohne
Auftrag liefert die Zentrale nichts; abgeschaltete Weitergabe wird verständlich
gemeldet; der Auftrag landet mit Rechten `rw-------` im Briefkasten; ohne
Aktualisierungsdienst wird kein Token geschrieben und der Handweg genannt; ein
liegengebliebener Auftrag wird beim Start entfernt; ein Fehlschlag erreicht den
neuen Agenten, ein Erfolg meldet nichts.

`AgentenpaketTest` (20 Prüfungen): kein Token und kein `DOCKER_CONFIG` im Paket;
Briefkasten getrennt vom Datenverzeichnis, und der Aktualisierer sieht `/daten`
nirgends; kein Zeitplan, kein periodischer Lauf, kein Watchtower; das Skript
nimmt den Token über die Standardeingabe, löscht den Auftrag zuerst und meldet
sich hinterher ab; angefasst wird nur der Agent.

Am laufenden Stack durchgespielt: ohne Auftrag kein Token am Standort und `403`
von der Zentrale; mit Auftrag Datei mit Rechten `600` und richtigem Inhalt;
nach einem Neustart ist sie weg, mit sichtbarer Meldung.
