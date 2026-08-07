# ADR-0008: Ablage von Geräte-Zugangsdaten in der Zentrale

- **Status:** **Angenommen** (07.08.2026) — umgesetzt, Freigabe des PO steht aus
- **Datum:** 07.08.2026
- **Betrifft:** Sicherheit, Datenmodell, Betrieb
- **Ergänzt:** [ADR-0002](0002-betriebsmodell-und-systemtopologie.md) (Vertrauensgrenze)

---

## Kontext

Bei der Umsetzung der Bestandsverwaltung (`FA-GER-010`, `FA-GER-020`) trat ein
Widerspruch zutage, der vorher niemandem aufgefallen war.

ADR-0002 zieht die Trennlinie zwischen Zentrale und Agent entlang der
**Vertrauensgrenze**: Geheimnisse liegen beim Agenten. Der Quelltext der
Zentrale sagte das wörtlich — *„kennt bewusst keine SMC-B-PIN und keine
Terminal-Zugänge"*.

Der Anforderungskatalog verlangt aber etwas anderes:

| Anforderung | Wortlaut |
|---|---|
| `FA-GER-010` | Konnektoren werden **mit Zugangsdaten** erfasst. Diese sind nach dem Speichern nicht mehr auslesbar. |
| `FA-GER-020` | Kartenterminals werden **mit Administrationszugang** erfasst. Ebenso nicht mehr auslesbar. |

Erfasst wird in der Zentrale — dort sitzt der Administrator. Damit muss
entschieden werden, was mit diesen Daten geschieht.

---

## Die Entscheidung

**Es werden zwei Arten von Geheimnissen unterschieden, und sie werden
verschieden behandelt.**

| | **SMC-B-PIN** | **Geräte-Zugang** (Konnektor-Anmeldung, Terminal-Admin) |
|---|---|---|
| Liegt in der Zentrale | **nein, niemals** | ja, verschlüsselt |
| Liegt beim Agenten | ja | ja (vom Agenten bezogen) |
| Grundlage | `A_24114`, PO-Vorgabe 06.08.2026 | `FA-GER-010`, `FA-GER-020` |
| Bei Verlust | Karte muss neu freigeschaltet werden | Zugangsdaten neu hinterlegen |
| Schaden bei Offenlegung | **Sperrung der SMC-B, Stillstand der Praxis** | Zugriff auf ein Gerät im Praxisnetz |

Die Unterscheidung ist nicht willkürlich. Sie folgt dem Schaden:

Die **PIN** ist der eine Wert, dessen Missbrauch die Einrichtung handlungsunfähig
macht — drei Fehlversuche und die SMC-B ist gesperrt. Sie bleibt deshalb dort,
wo sie gebraucht wird, und nur dort. Bei zentralem Betrieb für viele
Einrichtungen wäre eine Zentrale mit allen PINs ein Ziel, dessen Kompromittierung
gleichzeitig hunderte Praxen lahmlegt. Das ist nicht verantwortbar.

Ein **Geräte-Zugang** ist demgegenüber ein gewöhnliches Betriebsgeheimnis. Er
gehört geschützt, aber sein Verlust ist reparabel und trifft ein Gerät, nicht
den Kartenbetrieb.

---

## Wie der Schutz umgesetzt ist

**Verschlüsselung, nicht Hashing.** Ein Hash genügt hier nicht: Anders als beim
Anmeldekennwort eines Benutzers muss der Wert wieder verwendbar sein, um sich
beim Gerät anzumelden.

- **AES-256 im GCM-Modus**, je Wert ein frisch gezogener 12-Byte-Nonce.
  GCM erkennt Veränderungen am Chiffrat — ein manipulierter Datenbankeintrag
  führt zu einem Fehler, nicht zu stillem Fehlverhalten.
- **Der Schlüssel steht in der Umgebungsvariablen `GEHEIMNIS_SCHLUESSEL`**, nicht
  im Abbild und nicht im Repository (CLAUDE.md 5).
- **Kein Lesepfad gibt einen Klartext zurück.** Die Datensätze der
  Bestandsverwaltung tragen nur ein `passwortHinterlegt`-Kennzeichen. Es gibt
  genau zwei Methoden, die entschlüsseln, und sie heißen so, dass man sie in
  einer Prüfung des Quelltextes findet.
- **Fehlt der Schlüssel**, bleibt die Bestandserfassung vollständig nutzbar —
  nur ohne gespeicherte Kennwörter, und die Oberfläche sagt das. Ein
  Startabbruch wäre hier die schlechtere Wahl: Er nähme dem Betreiber die
  Möglichkeit, überhaupt anzufangen.

---

## Was dieser Schutz nicht leistet

**Wer Datenbank und Schlüssel gleichzeitig hat, kann entschlüsseln.** Das ist
unvermeidbar, solange der Wert wieder verwendbar sein muss, und es soll hier
nicht beschönigt werden.

Der Schutz richtet sich gegen die Fälle, die im Praxisalltag tatsächlich
eintreten:

- Datenbanksicherungen, die auf einem Netzlaufwerk oder in einer Cloud landen
- Datenauszüge zur Fehlersuche, die an einen Dienstleister gehen
- versehentliche Ausgaben in Protokollen, Fehlermeldungen oder Metriken
- ein Blick in die Datenbank durch jemanden, der Lesezugriff hat, aber keinen
  Zugriff auf die Konfiguration des Containers

Er richtet sich **nicht** gegen einen Angreifer mit vollem Zugriff auf den
Docker-Host. Gegen den hilft an dieser Stelle nichts — wohl aber die
Entscheidung, dass die **PIN dort gar nicht erst liegt**.

**Betriebliche Folge:** Der Schlüssel gehört in die Sicherung, aber **nicht in
dieselbe Datei wie der Datenbankauszug**. Liegen beide zusammen, schützt die
Verschlüsselung vor nichts. Das ist in `docs/04-betrieb.md` festzuhalten,
sobald diese Datei entsteht.

---

## Verworfene Alternativen

**1. Zugangsdaten ausschließlich beim Agenten, Zentrale nur als Durchreiche.**
Sauberer im Sinne von ADR-0002, aber unbrauchbar: Im zentralen Betriebsmodell
verwaltet eine Serviceorganisation bis zu 1.000 Konnektoren. Der Bestand muss
dort vollständig sein, sonst ist eine Ersteinrichtung vor Inbetriebnahme des
Agenten nicht möglich — genau der Fall, den `FA-GER-010` ausdrücklich erlaubt
(„nicht erreichbarer Konnektor kann trotzdem gespeichert werden").

**2. Zugangsdaten im Klartext, dafür strengere Rechteprüfung.** Verstößt gegen
das Akzeptanzkriterium „nicht mehr auslesbar" und gegen die Sicherheitsleitplanke
aus CLAUDE.md. Nicht diskussionswürdig.

**3. Externer Schlüsseldienst (Vault o. ä.).** Kryptographisch besser, aber
unvereinbar mit der Auslieferungsform: Eine Einzelpraxis soll `docker compose up`
ausführen und fertig sein, ohne einen zweiten Dienst zu betreiben. Bleibt als
Option für große Installationen offen — die Schnittstelle
(`Geheimnisspeicher`) ist so geschnitten, dass ein anderer Bezugsweg für den
Schlüssel eingesetzt werden kann, ohne die Aufrufer zu ändern.

---

## Konsequenzen

- Der Klassenkommentar der Zentrale ist berichtigt. Er behauptete etwas, das
  nicht mehr stimmte — und das ist gefährlicher als gar kein Kommentar.
- `docker-compose.yml` führt `GEHEIMNIS_SCHLUESSEL` mit Erzeugungshinweis.
- **Offen für den PO:** Ob Geräte-Zugangsdaten überhaupt zentral gehalten werden
  sollen, ist letztlich eine Risikoentscheidung des Betreibers. Die hier
  getroffene Wahl ist begründet, aber revidierbar — bei Widerspruch entfällt die
  Speicherung, und die Zugangsdaten werden bei jedem Vorgang neu abgefragt.
  Das wäre im Dauerbetrieb allerdings nicht praktikabel, weil der Roboter
  selbsttätig handelt und niemanden fragen kann.
