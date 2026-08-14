# ADR-0013 — Zentrale und Standort-Agent werden getrennt versioniert

**Status:** angenommen · **Datum:** 14.08.2026 · **Entscheider:** PO

---

## Kontext

Bis heute trugen beide Bausteine dieselbe Nummer. Sie stammte aus einem
gemeinsamen Eltern-POM, und jede Veröffentlichung erhöhte beide zugleich.

Die ursprüngliche Begründung stand im Veröffentlichungs-Workflow:

> Gleiche Version wie die Zentrale, auch wenn sich nur eines geändert hat:
> DevOps steuert beide über eine gemeinsame Variable, und zwei Bausteine mit
> auseinanderlaufenden Nummern sind im Störungsfall nicht zuzuordnen.

Der PO hat dem widersprochen (14.08.2026):

> Eine gleichlaufende Versionierung beider Komponenten ist für die Zukunft
> unpraktisch. Wir werden am Agenten öfter nacharbeiten müssen, da wir mit den
> KT und Konnektoren noch Erfahrung sammeln werden.

Das trifft den Kern. Der Agent spricht mit **fremden Geräten**, deren Verhalten
wir erst kennenlernen: Kartenterminals verschiedener Hersteller und
Firmwarestände, Konnektoren, der Hochleistungskonnektor. Jede Eigenheit, die
dort auffällt, führt zu einer Nacharbeit am Agenten — und zu keiner Zeile in
der Zentrale.

## Problem

Bei gemeinsamer Nummer entsteht bei jeder Agent-Korrektur eine neue Version der
Zentrale, in der sich nichts geändert hat. Drei Folgen:

1. **Unnötige Aktualisierungen.** Ein Betreiber sieht eine neue Fassung der
   Zentrale und spielt sie ein — für nichts.
2. **Die Nummer sagt nichts mehr.** Aus „0.7.3" lässt sich nicht ablesen, ob
   sich an der Zentrale etwas getan hat.
3. **Änderungshinweise werden unehrlich.** Sie müssten bei jeder Fassung
   beider Bausteine dieselbe Liste tragen, obwohl nur einer betroffen ist.

## Entscheidung

**Zentrale und Agent tragen eigene Versionen und werden getrennt
veröffentlicht.**

| | |
|---|---|
| Eltern-POM | Bauklammer, Version `1`. **Keine Produktversion.** |
| `src/zentrale/pom.xml` | Produktversion der Zentrale |
| `src/agent/pom.xml` | Produktversion des Agenten |
| `gemeinsam`, `simulator` | folgen der Bauklammer; sie werden nie ausgeliefert |
| Git-Tags | `zentrale-0.4.0`, `agent-0.4.0` |
| Image-Tags | unverändert `…/pin-roboter:0.4.0` und `…/pin-roboter-agent:0.4.0` |

Ein Tag veröffentlicht **nur seinen Baustein**. Wer beide will, setzt zwei Tags.
Die Freigabe als `stabil` erfolgt ebenfalls je Baustein.

## Was die beiden zusammenhält

Nicht die Nummer — die **Protokollversion**. Sie ist der Vertrag zwischen
Zentrale und Agent und existiert seit `FA-AGT-040` genau für diesen Zweck:

- Der Agent nennt bei jeder Meldung die Protokollversion, die er spricht.
- Die Zentrale kennt eine unterstützte Spanne und bewertet, was ankommt.
- Ein Agent außerhalb der Spanne wird **nicht abgewiesen** — das hieße: keine
  PIN-Verifikation an diesem Standort.

Damit war die Entkopplung technisch immer schon vorbereitet. Was fehlte, war
die Konsequenz in der Versionierung.

## Konsequenzen

### Was besser wird

- Eine Nacharbeit am Agenten kostet keine Fassung der Zentrale.
- Die Nummer eines Bausteins bedeutet wieder etwas.
- Änderungshinweise betreffen genau den Baustein, an dem sie hängen.

### Was schwieriger wird

- **Der Einwand des ursprünglichen Kommentars bleibt gültig:** Im Störungsfall
  muss man zwei Nummern erfragen statt einer. Die Agentenliste unter „Instanz"
  zeigt deshalb je Agent seine Fassung, und `/health` meldet die der Zentrale.
- **Zwei Tags statt einem.** Wer beide veröffentlicht, muss daran denken.
- Die Übergabedokumentation für DevOps nennt jetzt zwei Variablen.

### Was angepasst werden musste

Der Alarm **„Agent veraltet"** (`FA-AGT-090`) verglich die Agentversion mit der
Version der Zentrale. Das war schon vorher fragwürdig und wäre jetzt schlicht
falsch. Verglichen wird nun mit der **neuesten verfügbaren Agent-Fassung** aus
der Programmablage; ist keine bekannt, wird nicht alarmiert — ohne Maßstab wäre
jede Meldung geraten.

## Verworfene Alternativen

**Weiter gemeinsam versionieren, Agent-Korrekturen sammeln.** Hieße, eine
bekannte Störung an einem Standort liegen zu lassen, bis sich genug angesammelt
hat. Bei einem Produkt, das den TI-Zugang sichert, nicht vertretbar.

**Nur der Agent bekommt eine eigene Nummer, die Zentrale bleibt führend.** Eine
halbe Lösung: Sie erklärt nicht, was die Zentralennummer dann noch bedeutet, und
verlagert die Verwirrung nur.

**Ein gemeinsamer Release-Tag, der beide Nummern trägt.** Klingt ordentlich,
erzeugt aber eine dritte Zählung, die niemand pflegt.
