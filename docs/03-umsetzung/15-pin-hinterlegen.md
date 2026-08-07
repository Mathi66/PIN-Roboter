# 15 — PIN hinterlegen und löschen (FA-PIN-010, FA-PIN-060)

**Stand:** umgesetzt und am laufenden Stack nachgewiesen (07.08.2026)
**Grundlage:** [ADR-0009](../adr/0009-pin-hinterlegen-ueber-die-oberflaeche.md)

---

## Worum es geht

Bis hierher kam die SMC-B-PIN über eine Umgebungsvariable in den Agenten. Das
heißt: Klartext in einer Datei auf dem Docker-Host, in jeder Sicherung dieser
Datei, sichtbar über `docker inspect`, und jede Änderung verlangt einen
Neustart des Agenten — also eine Unterbrechung der Überwachung. Eine Praxis
ohne IT-Personal kann so nichts hinterlegen.

Jetzt geht es über die Oberfläche. **Ohne dass die Zentrale die PIN je sieht.**

## Der Weg der PIN

```
Browser                       Zentrale                     Standort-Agent
   │                             │                               │
   │  holt öffentlichen ─────────┤                               │
   │  Schlüssel des Agenten      │◄──── meldet ihn mit dem ──────┤
   │                             │      Bestandsbericht          │
   │  verschlüsselt die PIN      │                               │
   │  lokal (RSA-OAEP)           │                               │
   │                             │                               │
   ├── nur das Chiffrat ────────►│  legt Auftrag an              │
   │                             │  (kann ihn nicht lesen)       │
   │                             │◄──── holt Aufträge ab ────────┤
   │                             │  löscht das Chiffrat          │
   │                             │                               │  entschlüsselt,
   │                             │◄──── quittiert ───────────────┤  legt verschlüsselt ab
```

Der Klartext existiert an genau zwei Stellen: im Browser des Bedieners, für
Sekundenbruchteile, und im Agenten. **Nirgends dazwischen.**

## Was dabei nachgewiesen ist

Die folgenden Punkte sind nicht behauptet, sondern in `pruefungen/systemnachweise.sh`
am laufenden Stack gemessen:

| Nachweis | Wie geprüft |
|---|---|
| Der Agent meldet seinen öffentlichen Schlüssel | Spalte in der Datenbank gefüllt |
| Der Schlüssel ist WebCrypto-tauglich | OpenSSL verschlüsselt gegen ihn, der Agent liest es |
| Eine unverschlüsselte PIN wird abgewiesen | POST mit Klartext → Fehlermeldung, kein Auftrag |
| Das Chiffrat verschwindet nach der Zustellung | `SELECT count(*) … WHERE chiffrat IS NOT NULL` = 0 |
| Weder PIN noch Chiffrat im Protokoll | Suche in Fachprotokoll, Zentralen- und Agenten-Ausgabe |
| Die PIN liegt beim Agenten verschlüsselt | Ablagedatei gelesen, PIN nicht enthalten |
| Die Oberfläche zeigt nur das Kennzeichen | HTML enthält „PIN hinterlegt", nicht den Wert |
| Löschen wirkt bis zum Agenten durch | Eintrag in Datenbank und Ablagedatei weg |

## Der Fehler, den nur der Systemnachweis finden konnte

Die erste Fassung verwendete Javas Kurzform:

```java
Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
```

Das sieht richtig aus und ist es nicht. Die Kurzform setzt **nur den
Nachrichten-Hash** auf SHA-256 und lässt die Maskengenerierungsfunktion MGF1
stillschweigend auf **SHA-1**. WebCrypto im Browser verwendet für beides
SHA-256. Ergebnis: Jede PIN kam beim Agenten an und ließ sich nicht
entschlüsseln.

**Der Modultest hat das nicht gefunden** — er verschlüsselte und entschlüsselte
mit derselben Java-Kurzform, war also auf beiden Seiten gleich falsch. Gefunden
hat es der Systemnachweis, weil er gegen **OpenSSL** verschlüsselt, also gegen
eine unabhängige Umsetzung derselben Spezifikation.

Behoben durch ausgeschriebene Parameter:

```java
new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256,
                      PSource.PSpecified.DEFAULT)
```

Und abgesichert durch einen Modultest, der ausdrücklich prüft, dass ein mit
SHA-1-MGF1 erzeugtes Chiffrat **abgelehnt** wird — damit niemand die Kurzform
versehentlich zurückbaut.

> Die Lehre ist allgemein: Ein Test, der beide Seiten einer Schnittstelle mit
> derselben Bibliothek bedient, prüft die Bibliothek gegen sich selbst. Wo eine
> fremde Umsetzung am anderen Ende steht — ein Browser, ein Konnektor, ein
> Kartenterminal — muss der Nachweis das nachstellen.

## JavaScript in genau einer Maske

Die Oberfläche ist server-gerendert (ADR-0006) und kommt sonst ohne JavaScript
aus. Verschlüsseln im Browser geht ohne nicht.

Die Maske sagt das ausdrücklich:

> **Diese Maske braucht JavaScript.** Die PIN wird in Ihrem Browser
> verschlüsselt, bevor sie abgeschickt wird — damit die Zentrale sie nie im
> Klartext sieht. Das geht ohne JavaScript nicht.

Der Hinweis steht in `<noscript>` und ist damit genau dann sichtbar, wenn er
gebraucht wird. Ein Formular, das lautlos nichts tut, wäre die schlechtere
Wahl.

**Das Eingabefeld hat kein `name`-Attribut.** Es kann damit gar nicht
mitgeschickt werden — auch nicht, wenn das Skript ausfällt. Abgeschickt wird
ausschließlich das versteckte Feld, das die Verschlüsselung gefüllt hat. Das
ist geprüft (Nachweis „das PIN-Eingabefeld wird nicht mitgeschickt").

Nach dem Verschlüsseln wird das Eingabefeld sofort geleert.

## Die Notbremse gegen Klartext

Die Zentrale prüft die Länge des Eingangs: Ein RSA-3072-Chiffrat ist immer
384 Byte, in Base64 rund 512 Zeichen. Käme dort eine sechsstellige Zeichenfolge
an, wäre das eine ungeschützte PIN — und das muss auffallen, nicht durchgehen.

Sie wird mit einer verständlichen Meldung abgewiesen und **nicht gespeichert**.

## Beim Agenten

Die PIN liegt in `/daten/pins`, verschlüsselt mit AES-256-GCM. Der Schlüssel
kommt aus `PIN_ABLAGE_SCHLUESSEL` — getrennt vom `GEHEIMNIS_SCHLUESSEL` der
Zentrale, damit wer den einen hat nicht auch den anderen bekommt.

**Ohne diesen Schlüssel wird nichts auf die Platte geschrieben.** Die PIN
bleibt dann nur im Arbeitsspeicher und ist nach einem Neustart weg. Das ist die
richtige Richtung: lieber eine PIN neu hinterlegen als eine im Klartext auf der
Platte.

Ein einzelner unlesbarer Eintrag — etwa nach einem Schlüsselwechsel — nimmt die
anderen nicht mit. Er wird gemeldet und übersprungen.

## Drei Fehler, die dabei aufgefallen sind

**1. Das Volume des Agenten war nicht eingebunden.** `agentdaten` stand in der
Compose-Datei unter `volumes:`, war aber im Dienst `agent` nicht gemountet.
Damit lag das Agent-Token im Container-Layer und war nach jedem Neustart weg
(`NFA-BET-020`). Mit dem Schlüsselpaar wäre das gravierend geworden: Nach jedem
Neustart ein neuer Schlüssel und damit **jede hinterlegte PIN unbrauchbar**.

**2. `PIN_ABLAGE` wurde als Karten-PIN gelesen.** Die Schleife, die
`PIN_<kartenhandle>`-Variablen einliest, nahm auch `PIN_ABLAGE` und
`PIN_ABLAGE_SCHLUESSEL` — sie beginnen nun einmal mit `PIN_`. In der Wirkung
harmlos (kein passendes Kartenhandle), aber ein Konfigurationsschlüssel als
vermeintliche PIN im Speicher ist schlicht falsch. Jetzt ausgenommen.

**3. Die Umgebungs-PIN verdrängte die hinterlegte.** Beim Start wurde erst die
Ablage geladen, dann die Umgebung — und die überschrieb. Ein vergessener
Testeintrag hätte nach jedem Neustart die über die Oberfläche hinterlegte PIN
lautlos ersetzt. Jetzt hat die Ablage Vorrang, und der Agent sagt es beim Start.

Zusätzlich warnt der Agent jetzt beim Start, wenn PINs aus Umgebungsvariablen
stammen — dieser Weg ist nur für Testaufbauten vorgesehen.

## Was das nicht leistet

Wer den **Agenten** vollständig kontrolliert, kommt an die PIN — er muss sie ja
verwenden können. Das ist unvermeidbar und der Grund, warum `A_24114` die
Vorhaltung nur Clients erlaubt, deren Sicherheit die betreibende Organisation
durchsetzen kann.

Der Gewinn liegt woanders: **Die Zentrale ist aus der Kette heraus.** Bei 1.000
Standorten liefert ein Angriff auf die Zentrale keine einzige PIN.
