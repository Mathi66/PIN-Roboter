# ADR-0009: PIN hinterlegen über die Oberfläche

- **Status:** **Angenommen** (07.08.2026), umgesetzt und nachgewiesen
  → [Umsetzung 15](../03-umsetzung/15-pin-hinterlegen.md)
- **Betrifft:** Sicherheit, Architektur, Bedienung
- **Grundlage:** FA-PIN-010, Sicherheitsleitplanke CLAUDE.md,
  [ADR-0002](0002-betriebsmodell-und-systemtopologie.md),
  [ADR-0008](0008-ablage-von-geraete-zugangsdaten.md)

---

## Das Problem

Die SMC-B-PIN kommt derzeit über eine **Umgebungsvariable** in den Agenten:

```yaml
PIN_card_smcb_001: "123456"
```

Das ist für einen Prototyp vertretbar und für den Betrieb untragbar:

- Die PIN steht **im Klartext** in einer Datei auf dem Docker-Host.
- Sie landet in jeder Sicherung dieser Datei.
- `docker inspect` zeigt sie jedem, der den Host bedienen darf.
- Sie zu ändern verlangt einen **Neustart des Agenten** — und damit eine
  Unterbrechung der Überwachung.
- Eine Praxis ohne IT-Personal kann sie nicht selbst hinterlegen.

`FA-PIN-010` verlangt deshalb: Hinterlegen über die Oberfläche.

## Der Konflikt

Die Oberfläche läuft in der **Zentrale**. Die Sicherheitsleitplanke aus
CLAUDE.md und ADR-0002 sagen aber:

> Die PIN liegt ausschließlich beim Agenten und erreicht die Zentrale **nie**.

Beim zentralen Betriebsmodell verwaltet eine Zentrale bis zu 1.000 Konnektoren.
Eine Zentrale, die alle PINs kennt, wäre ein Ziel, dessen Kompromittierung
gleichzeitig hunderte Praxen lahmlegt — jede mit drei Fehlversuchen von der
Kartensperre entfernt.

**Beides gleichzeitig geht nicht auf dem naheliegenden Weg.**

---

## Betrachtete Wege

### Weg 1 — Eigene Oberfläche am Agenten

Der Administrator ruft den Agenten direkt auf und gibt die PIN dort ein.

**Dagegen:** Bricht das Bedienmodell. Der Agent hat bewusst keine Oberfläche; er
steht im Praxisnetz, oft ohne Bildschirm, und ist von außen nicht erreichbar.
Bei 1.000 Standorten müsste sich jemand mit 1.000 Adressen anmelden.

### Weg 2 — Die Zentrale reicht durch, ohne zu speichern

Die PIN geht durch den Arbeitsspeicher der Zentrale und wird sofort
weitergeleitet.

**Dagegen:** Sie *erreicht* die Zentrale. Ein Speicherabbild, ein Fehlerbericht
mit Stapelabbild, ein versehentliches Protokollieren — und sie ist dort. Die
Zusage „die PIN erreicht die Zentrale nie" wäre gebrochen, und zwar an einer
Stelle, die niemand mehr prüfen kann.

### Weg 3 — Ende-zu-Ende: Der Browser verschlüsselt für den Agenten ✔

Der Agent erzeugt beim Start ein Schlüsselpaar und meldet den **öffentlichen**
Teil an die Zentrale. Der Browser holt ihn, verschlüsselt die PIN damit lokal
(WebCrypto, RSA-OAEP) und schickt nur das **Chiffrat**. Die Zentrale reicht es
weiter, ohne es lesen zu können. Der Agent entschlüsselt mit seinem privaten
Schlüssel, der sein Gerät nie verlässt.

```
Browser ──[PIN mit öffentlichem Schlüssel verschlüsselt]──► Zentrale
                                                              │ reicht weiter,
                                                              │ kann nicht lesen
                                                              ▼
                                                            Agent
                                                    entschlüsselt, legt ab
```

**Dafür:** Die Zusage bleibt buchstäblich erfüllt. Die Zentrale sieht nie
Klartext — nicht im Arbeitsspeicher, nicht in einer Sicherung, nicht in einem
Fehlerbericht. Die Bedienung bleibt an einer Stelle.

**Dagegen:** Erfordert JavaScript im Browser. Bisher kommt die Oberfläche ohne
aus (ADR-0006: server-gerendertes HTML), und das war eine bewusste Wahl wegen
der Nachweispflicht für WCAG.

---

## Entscheidung

**Weg 3.** Der JavaScript-Einsatz ist vertretbar, weil er auf **eine** Maske
begrenzt bleibt und dort einen Zweck erfüllt, den Serverseitigkeit nicht leisten
kann.

Zur Barrierefreiheit: Die Maske bleibt ein gewöhnliches Formular mit
Beschriftung und Fokus. Nur das Absenden geht durch eine Verschlüsselung. **Ohne
JavaScript funktioniert sie nicht** — das ist eine echte Einschränkung und wird
in der Maske ausgesprochen, statt sie stillschweigend hinzunehmen.

### Festlegungen für die Umsetzung

1. **Schlüsselpaar beim Agenten**, erzeugt beim ersten Start, privater Teil im
   Datenverzeichnis des Agenten. RSA-4096 oder ECIES — die Wahl fällt bei der
   Umsetzung, entscheidend ist, dass WebCrypto es beherrscht.
   → *Umgesetzt als **RSA-OAEP 3072 Bit**. ECIES scheidet aus: WebCrypto
   beherrscht für asymmetrische Verschlüsselung ausschließlich RSA-OAEP.
   3072 statt 4096, weil `BSI TR-02102-1` das als ausreichend führt und 4096
   im Browser spürbar länger rechnet, ohne hier etwas zu gewinnen.*
2. **Der öffentliche Schlüssel wird gemeldet** wie der Bestand — über den
   bestehenden ausgehenden Weg.
3. **Die Zentrale speichert das Chiffrat nur bis zur Zustellung.** Danach wird
   es gelöscht. Sie kann es ohnehin nicht lesen, aber ein Chiffrat, das ewig
   liegt, ist ein Angriffsziel für später.
4. **Nichts davon im Protokoll** — weder PIN noch Chiffrat. Protokolliert wird,
   *dass* eine PIN hinterlegt wurde, für welches Kartenhandle und von wem.
5. **Beim Agenten verschlüsselt ablegen**, mit einem Schlüssel aus seiner
   Konfiguration — dieselbe Bauart wie in ADR-0008, aber getrennter Schlüssel.
6. **Löschen muss genauso einfach sein** wie Hinterlegen (FA-PIN-060). Eine PIN,
   die sich nur schwer entfernen lässt, bleibt liegen.

### Was das nicht leistet

Wer den **Agenten** vollständig kontrolliert, kommt an die PIN — er muss sie ja
verwenden können. Das ist unvermeidbar und der Grund, warum `A_24114` die
Vorhaltung nur Clients erlaubt, deren Sicherheit die betreibende Organisation
durchsetzen kann.

Der Gewinn liegt woanders: **Die Zentrale ist aus der Kette heraus.** Bei 1.000
Standorten bedeutet das, dass ein Angriff auf die Zentrale keine einzige PIN
liefert.

---

## Konsequenzen

- Der Weg über `PIN_*`-Umgebungsvariablen bleibt **für Testaufbauten** bestehen,
  wird aber im Betriebshandbuch ausdrücklich als ungeeignet für den Produktivbetrieb
  gekennzeichnet.
- Die Oberfläche braucht eine Stelle, an der sichtbar ist, **welche SMC-B eine
  hinterlegte PIN hat** — ohne sie anzuzeigen. Wie beim Geräte-Zugang: nur das
  Kennzeichen, nie der Wert.
- **Offen für den PO:** Ob der JavaScript-Einsatz akzeptabel ist. Die Alternative
  wäre Weg 1 (eigene Oberfläche am Agenten) mit dem beschriebenen Bruch im
  Bedienmodell.

---

## Nachtrag zur Umsetzung (07.08.2026)

Umgesetzt wie entschieden. Ein Punkt hat sich bei der Umsetzung als
Stolperstelle erwiesen und gehört hierher, weil er die Wahl von RSA-OAEP
betrifft:

**Javas Kurzform `OAEPWithSHA-256AndMGF1Padding` ist nicht WebCrypto-kompatibel.**
Sie setzt nur den Nachrichten-Hash auf SHA-256 und lässt MGF1 auf SHA-1;
WebCrypto verwendet für beides SHA-256. Die Parameter müssen ausgeschrieben
werden. Gefunden hat das der Systemnachweis gegen OpenSSL — der Modultest
konnte es nicht finden, weil er beide Seiten mit derselben Java-Kurzform
bediente.

**Offen bleibt** die Frage an den PO aus dem Abschnitt „Konsequenzen": ob der
JavaScript-Einsatz in dieser einen Maske akzeptabel ist. Die Umsetzung liegt
vor und ist nutzbar; sollte der PO widersprechen, wäre Weg 1 (eigene Oberfläche
am Agenten) die Alternative — mit dem dort beschriebenen Bruch im Bedienmodell.
