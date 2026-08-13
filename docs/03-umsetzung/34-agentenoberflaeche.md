# 34 — Oberfläche des Standort-Agenten

**Stand:** umgesetzt und am laufenden Stack nachgewiesen (13.08.2026)
· **Vorgabe:** PO, 13.08.2026

---

## Wozu

Bis hierher war der einzige Einblick in den Agenten `docker compose logs`:
Java-Meldungen, Zeitstempel in UTC, Ausnahmestapel. Bei der Inbetriebnahme
scheitert man an Kleinigkeiten — falsche Konnektor-Adresse, fehlender
Vertrauensanker, ein Aufrufkontext, den der Konnektor ablehnt — und sieht nur
`malformed PEM data: no header found`.

Die Oberfläche beantwortet drei Fragen: **Läuft er? Was fehlt noch? Was ist
zuletzt passiert?**

## Was sie zeigt

| Abschnitt | Inhalt |
|---|---|
| Kopf | arbeitsfähig ja/nein, Einrichtung, Version, Not-Aus |
| Inbetriebnahme | fünf Schritte mit ✓/× — und **was zu tun ist**, wenn etwas fehlt |
| Zustand | Terminals, SMC-B, hinterlegte PINs, offene Verbindungen, Grundtakt |
| Adresse der Zentrale | einziges änderbares Feld, siehe unten |
| Geräte | je Kartenterminal der letzte bekannte Zustand |
| Letzte Meldungen | Ringpuffer im Arbeitsspeicher, 60 Einträge |

Die Fehlermeldungen des Konnektors werden **übersetzt**: Aus
`malformed PEM data` wird „Der Vertrauensanker passt nicht. CA_URL muss auf das
Zertifikat der ausstellenden Stelle zeigen — Ihr Konnektor bietet es zum
Herunterladen an."

## Die Entscheidungen

**Nur auf diesem Rechner.** Der Agent baut Verbindungen ausschließlich nach
außen auf, damit im Praxisnetz keine Freigabe nötig ist (ADR-0002). Eine
Oberfläche, die im ganzen Netz antwortet, weichte das auf.

> **Ein Fehler dabei, der erwähnenswert ist.** Der erste Versuch band im
> Container an `127.0.0.1` — und war von außen nicht erreichbar. Docker leitet
> ein Port-Mapping an die Adresse des Containers, nicht an dessen Loopback.
> Richtig ist: im Container an `0.0.0.0` binden
> (`OBERFLAECHE_ADRESSE`) und die Beschränkung dem Mapping überlassen
> (`ports: ["127.0.0.1:8091:8091"]`). Dann ist der Port **auf dem Host** nur
> lokal erreichbar.

**Keine Geheimnisse.** Keine PIN, kein Token, kein Schlüssel — auch nicht
gekürzt. Was man ansehen kann, wird irgendwann abfotografiert.

**Ohne Fremdbibliothek.** `com.sun.net.httpserver` gehört zum JDK; ein
Webframework wären zusätzliche Megabyte in einem Baustein, der neben dem
TI-Zugang läuft. Das CSS steht im Code — der Agent lädt **nichts** nach, auch
nicht von der eigenen Zentrale: Ist sie nicht erreichbar, wäre die Seite genau
dann unbrauchbar, wenn man sie braucht.

## Die Adresse der Zentrale ist änderbar

Ursprünglich sollte die Oberfläche rein lesend sein — Konfiguration kommt aus
Umgebungsvariablen. Der PO hat das mit einem Argument gekippt, das sticht:

> „Wenn ausgelieferte Agenten bei einem Serverumzug nicht verloren gehen, muss
> das in der Oberfläche konfigurierbar sein."

Zieht die Zentrale um, zeigen alle ausgelieferten Agenten ins Leere. Über die
`.env` nachzuziehen hieße, an jedem Praxisrechner die Datei zu bearbeiten und
den Container neu zu starten. Bei 150 betreuten Einrichtungen ist das keine
Option.

**Wie es zusammenspielt:**

```
ZENTRALE_URL (Umgebung)  →  Vorgabe bei der Inbetriebnahme
/daten/zentrale-url      →  überschreibt sie, wenn vorhanden
```

Die Datei gewinnt — sie ist die jüngere, bewusste Entscheidung an diesem
Standort. Die Oberfläche zeigt trotzdem **beide** Werte, sonst rätselt jemand,
warum seine Änderung an der `.env` nichts bewirkt.

**Drei Sicherungen:**

1. **Probe vor der Übernahme.** Nur was unter `/health` mit `"status":"ok"`
   antwortet, wird gesetzt. Ein Tippfehler schnitte den Agenten sonst dauerhaft
   von seiner Zentrale ab — und die einzige Stelle zum Korrigieren wäre wieder
   der Praxisrechner.
2. **POST, kein GET.** Ein GET wäre von einem Verlaufseintrag auslösbar.
3. **Im Ringpuffer vermerkt**, mit altem und neuem Wert.

Kein zusätzliches Risiko: Die Oberfläche ist nur lokal erreichbar, und wer
lokalen Zugriff hat, kann den Container ohnehin anhalten. Ein Umlenken auf eine
fremde Zentrale nützt nichts — das Token gilt nur bei der echten, und PINs sind
für den öffentlichen Schlüssel *dieses* Agenten verschlüsselt.

## Ein Befund beim Bauen

`Zentralenadresse` rief für die Probe `TlsClient.fuerZentrale()`. Der statische
Initialisierer von `TlsClient` registriert BouncyCastle und schaltet Brainpool
frei — **schon das Anfassen der Klasse löst ihn aus**. Im Test gab das einen
`ExceptionInInitializerError` bei einer Sache, die mit Kryptographie nichts zu
tun hat.

Im Betrieb hieße dasselbe: Scheitert die Brainpool-Freischaltung, wäre auch die
Verbindung zur Zentrale tot — und damit die Möglichkeit, den Fehler zu melden.
Der Client für die Zentrale steht deshalb jetzt in einer eigenen Klasse
`Zentraleverbindung`, ohne statischen Block. Die Trennung der beiden Strecken
war begonnen, aber nicht zu Ende geführt.

## Nachgewiesen

Modultests (`ZentralenadresseTest`): Umgebungsvariable als Vorgabe, Datei
sticht sie, unlesbare Datei verhindert den Start nicht, Eingabe ohne Schema
abgelehnt, leere Eingabe, fremdes Schema (`file:`, `ftp:`), **nicht erreichbare
Adresse wird nicht übernommen und nichts gespeichert**.

`AgentenpaketTest`: Das Compose legt die Oberfläche auf `127.0.0.1`, die
Anleitung nennt sie und den Umzug.

Am laufenden Stack: Oberfläche erreichbar, `/health` liefert
`{"status":"ok","registriert":true,"terminals":36}`, ein Umzug auf eine gültige
Adresse wird übernommen und **überlebt den Neustart**, eine tote Adresse und
ein antwortender Fremddienst (HTTP 404) werden mit Klartext abgelehnt.
