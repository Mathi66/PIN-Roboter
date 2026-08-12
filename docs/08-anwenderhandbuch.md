# Anwenderhandbuch

**Für alle, die mit dem PIN-Roboter arbeiten** — in der Praxis, im MVZ, in der
Klinik. Sie brauchen keine IT-Kenntnisse. Wo doch einmal etwas technisch wird,
steht dabei, wen Sie fragen können.

**Stand:** 10.08.2026

---

## 1. Was der PIN-Roboter für Sie tut

In Ihrer Einrichtung stecken **SMC-B-Karten** (Praxisausweise) in
Kartenterminals. Damit die Karte für die Telematikinfrastruktur nutzbar ist,
muss sie **freigeschaltet** sein — dafür wird ihre PIN eingegeben.

Diese Freischaltung geht verloren: bei einem Stromausfall, einem Neustart des
Terminals, einem Firmware-Update. Dann muss jemand hingehen und die PIN am Gerät
neu eingeben. In einer Klinik mit hundert Terminals ist das ein halber
Arbeitstag — und bis dahin funktioniert das Kartenlesen an diesen Plätzen nicht.

**Der PIN-Roboter erledigt das selbsttätig.** Er überwacht die Terminals und
stellt den freigeschalteten Zustand wieder her, sobald er verloren geht. Sie
hinterlegen die PIN einmal; danach greift er ein, ohne dass jemand hingeht.

### Was er ausdrücklich nicht tut

- **Er verarbeitet keine Patientendaten.** Er sieht keine Versichertendaten,
  keine Befunde, keine Abrechnung.
- **Er arbeitet nur mit SMC-B.** Elektronische Gesundheitskarten und
  Heilberufsausweise rührt er nicht an — das ist technisch ausgeschlossen und
  regulatorisch so vorgeschrieben.
- **Er rät nie eine PIN.** Vor jedem Versuch prüft er, wie viele Versuche die
  Karte noch hat. Steht sie kurz vor der Sperre, bricht er ab und meldet es,
  statt es zu versuchen.

---

## 2. Anmelden

Sie brauchen drei Dinge:

1. **Benutzername**
2. **Passwort**
3. **Einmalkennwort** — sechs Ziffern aus einer App auf Ihrem Telefon

Das Einmalkennwort ist Pflicht und lässt sich nicht abschalten. Der Grund: Wer
sich anmelden kann, kann PINs hinterlegen. Ein gestohlenes Passwort allein soll
dafür nicht reichen.

### Beim ersten Mal

Nach der Anmeldung zeigt Ihnen das System einen **QR-Code** und einen
Buchstabencode. Richten Sie damit eine Authenticator-App ein (zum Beispiel
FreeOTP, Aegis oder die App Ihrer Einrichtung). Danach müssen Sie einmal einen
Code eingeben, um zu bestätigen, dass es funktioniert hat.

> **Der Code wird genau einmal angezeigt.** Wenn Sie ihn verlieren, bevor die
> App eingerichtet ist, muss ein Administrator Ihren zweiten Faktor
> zurücksetzen. Das ist kein Beinbruch, kostet aber einen Anruf.

### Wenn die Anmeldung nicht klappt

| Meldung | Was zu tun ist |
|---|---|
| „Benutzername, Passwort oder Einmalkennwort stimmen nicht." | Prüfen Sie, ob der Code noch aktuell ist — er wechselt alle 30 Sekunden. Warten Sie auf den nächsten. |
| „Dieses Einmalkennwort wurde bereits verwendet." | Jeder Code gilt nur einmal. Warten Sie auf den nächsten. |
| „Das Konto ist vorübergehend gesperrt." | Nach fünf Fehlversuchen sperrt das Konto für einige Minuten. Warten oder einen Administrator bitten. |

> **Senden Sie nie denselben Code zweimal.** Jeder abgelehnte Versuch zählt als
> Fehlversuch, und fünf davon sperren das Konto. Wenn ein Code nicht durchgeht,
> nehmen Sie den nächsten — nicht denselben noch einmal.

Wenn die Anmeldung plötzlich für **alle** nicht mehr geht, liegt es meist an der
Uhrzeit: Die Einmalkennwörter sind zeitabhängig. Sagen Sie Ihrer IT Bescheid.

---

## 3. Die Oberfläche

Links steht die Navigation, getrennt in **Betrieb** (Tagesgeschäft) und
**Verwaltung** (Einrichten und Pflegen).

| Bereich | Wofür |
|---|---|
| **Lage** | Der Blick am Morgen: Was braucht Aufmerksamkeit? |
| **Kartenterminals** | Alle Geräte mit ihrem aktuellen Zustand — sortiert nach Einrichtung |
| **Gerätegruppen** | Geräte bündeln und festlegen, wie oft geprüft wird |
| **Benachrichtigung** | Wer bekommt eine E-Mail, wenn etwas nicht stimmt |
| **Protokoll** | Was ist wann passiert |
| **Bestand** | Konnektoren und Terminals erfassen, PIN hinterlegen |
| **Organisation** | Ihre Struktur: Träger, Standorte, Betriebsstätten, Fachbereiche |
| **Konten** | Benutzer und ihre Rechte |
| **Instanz** | Technisches: Agenten, Speicher, Versionen |

**Not-Aus** steht immer sichtbar am Ende der Navigation. Ein Klick hält die
Automatik sofort an — siehe Abschnitt 10.

### Bedienung

- **Suchfelder suchen beim Tippen.** Kein „Suchen"-Knopf.
- **Spaltenüberschriften sortieren.** Ein zweiter Klick dreht die Richtung um.
- **Eine Zeile anklicken öffnet den Eintrag.** Sie müssen keinen Knopf treffen.
- **Jeder Knopf erklärt sich**, wenn Sie mit der Maus darauf verweilen.
- **Alles ist mit der Tastatur bedienbar.** Tab wechselt das Feld, Enter löst
  aus.

---

## 4. Die Lage-Seite

Vier Zahlen, die zusammen die Frage beantworten: *Muss ich jetzt etwas tun?*

| Kachel | Bedeutung |
|---|---|
| **Karten freigeschaltet** | läuft, nichts zu tun |
| **Brauchen Aufmerksamkeit** | Etwas stimmt nicht, aber der Roboter arbeitet daran |
| **Handlungsbedarf** | Ein Mensch muss eingreifen — hier zuerst hinsehen |
| **Ohne Automatik (PEviM)** | Diese Geräte kann der Roboter nicht bedienen (siehe unten) |

Darunter steht **„Was zu tun ist"** — die offenen Alarme im Klartext. Steht dort
„nichts offen", ist wirklich nichts offen.

### „Ohne Automatik (PEviM)"

PEviM ist das Verfahren, mit dem die PIN über die Verwaltungsschnittstelle des
Terminals übergeben wird. Nicht jedes Gerät kann das, und bei manchen muss es
erst freigeschaltet werden. Diese Geräte überwacht der Roboter zwar, aber er
kann die PIN nicht selbst eingeben — dort muss weiterhin jemand hingehen.

Ob ein Gerät es kann, hängt vom Modell und vom Firmwarestand ab. Ihre IT kann
das prüfen.

---

## 5. Ein Gerät einrichten

Drei Schritte, in dieser Reihenfolge:

### Schritt 1: Konnektor erfassen

**Bestand → Konnektor hinzufügen.** Sie brauchen Adresse und Zugangsdaten des
Konnektors. Diese Angaben bekommen Sie von Ihrer IT oder Ihrem Dienstleister.

Nutzen mehrere Praxen denselben Konnektor — etwa wenn er beim Dienstleister im
Rechenzentrum steht —, erfasst ihn **die Einrichtung, die ihn betreibt**. Auf
seiner Seite steht unten „Mitnutzende Einrichtungen"; dort trägt der Betreiber
ein, wer ihn mitbenutzen darf.

> Die mitnutzenden Praxen sehen den Konnektor dann und können ihm ihre eigenen
> Kartenterminals zuordnen. **Ändern oder löschen kann ihn nur der Betreiber** —
> sonst könnte eine Praxis den Konnektor verstellen, an dem andere hängen.

### Schritt 2: Kartenterminal erfassen

Zwei Wege:

- **Ermittelte Geräte** — der Roboter fragt den Konnektor, welche Terminals er
  kennt, und Sie übernehmen sie mit einem Klick. Das ist der übliche Weg.
- **Kartenterminal hinzufügen** — von Hand, wenn ein Gerät nicht gemeldet wird.

Beim Erfassen von Hand ist die **Einrichtung** die Pflichtangabe: zu welcher
Praxis oder Betriebsstätte gehört das Gerät? Ein **Konnektor** kann angegeben
werden, muss aber nicht.

> **Warum das wichtig ist:** Ein neues Gerät steht oft schon in der Praxis,
> bevor es am Konnektor angemeldet wurde. Sie können es trotzdem erfassen — dann
> steht es im Bestand statt auf einem Zettel.
>
> Solange kein Konnektor zugeordnet ist, **schaltet der Roboter die Karte in
> diesem Gerät nicht frei**. Das ist keine Störung: Die Freischaltung wird vom
> Konnektor ausgelöst, und ohne ihn gibt es nichts auszulösen. Auf der
> Geräteseite steht das oben, und die Zuordnung lässt sich dort direkt
> nachtragen, sobald das Gerät am Konnektor angemeldet ist.

### Schritt 3: PIN hinterlegen

Öffnen Sie das Terminal (Zeile anklicken) und tragen Sie die PIN der
eingelegten SMC-B ein.

> **Was mit Ihrer PIN passiert:** Sie wird im Browser verschlüsselt, bevor sie
> abgeschickt wird. Die Zentrale kann sie **nicht lesen** — sie reicht nur ein
> verschlüsseltes Paket an den Agenten weiter, der es öffnen kann. Im Protokoll
> steht, *dass* eine PIN hinterlegt wurde, nie *welche*.

Ab jetzt arbeitet der Roboter selbstständig.

---

### Ihre Struktur abbilden

**Organisation** zeigt Ihre Einrichtung als Baum. Fünf Ebenen sind möglich, und
**Sie brauchen nicht alle**:

| Ebene | Wofür |
|---|---|
| Trägergesellschaft | die Dachorganisation, z. B. bei einem MVZ |
| Leistungserbringer | das MVZ selbst, sofern es als solches zugelassen ist |
| Standort | ein Gebäude oder Ortsteil mit mehreren Betriebsstätten |
| **Betriebsstätte** | die Praxis mit eigener BSNR und eigener SMC-B |
| Fachbereich | eine Abteilung innerhalb einer Betriebsstätte |

Eine Einzelpraxis kommt mit zwei Ebenen aus. Die Zwischenebenen sind für
größere Häuser gedacht — legen Sie nur an, was Sie wirklich unterscheiden.

**Die Betriebsstätte ist die wichtigste Ebene.** Sie ist es, die in der
Telematikinfrastruktur als Leistungserbringer geführt wird und eine eigene
SMC-B hat. Auf ihrer Seite können Sie **Betriebsstättennummer (BSNR)** und
**Telematik-ID** eintragen. Beides ist freiwillig — tragen Sie es nach, wenn
Ihnen die Angaben vorliegen. Die BSNR stimmen Sie mit Ihrer Kassenärztlichen
Vereinigung ab.

Kartenterminals ordnen Sie der untersten Ebene zu, die passt: dem Fachbereich,
wenn es einen gibt, sonst der Betriebsstätte.

## 6. Gerätegruppen

Eine Gruppe bündelt Geräte und legt fest, **wie oft geprüft wird**.

Warum das wichtig ist: Die Notaufnahme braucht vielleicht alle 30 Sekunden eine
Prüfung, das Archiv kommt mit fünf Minuten aus. Häufiger prüfen heißt schneller
reagieren, belastet aber Konnektor und Terminals.

**Gerätegruppen → Bezeichnung, Einrichtung, Intervall → Gruppe anlegen.**

Jede Einrichtung hat eine **Vorgabegruppe**. Neu erkannte Geräte landen dort
automatisch, damit sie überwacht werden, bevor jemand sie zuordnet. Die
Vorgabegruppe lässt sich nicht löschen.

Das Intervall ändern Sie direkt in der Liste — es speichert, sobald Sie das Feld
verlassen. Die Untergrenze liegt bei 10 Sekunden.

---

## 7. Wenn etwas nicht stimmt

### Alarme

Ein Alarm sagt, **was zu tun ist** — nicht nur, was schiefging. Technische Codes
sind übersetzt; das Original bleibt aufklappbar sichtbar, falls Ihre IT es
braucht.

Wenn Sie sich gekümmert haben, **quittieren** Sie den Alarm. Er verschwindet aus
„Was zu tun ist", bleibt aber im Protokoll — mit Zeitpunkt und Ihrem Namen.

### Häufige Meldungen

| Meldung | Was dahintersteckt | Was Sie tun |
|---|---|---|
| Gerät nicht erreichbar | Terminal aus, Netzwerkkabel, Neustart | Gerät prüfen; meldet sich es wieder, löst sich der Alarm von selbst |
| PIN wird benötigt | Karte nicht freigeschaltet, keine PIN hinterlegt | PIN hinterlegen (Abschnitt 5) |
| Nur noch ein Versuch übrig | Die Karte steht kurz vor der Sperre | **Nichts selbst versuchen.** Der Roboter hat bereits abgebrochen. Die richtige PIN muss neu hinterlegt werden. |
| Karte gesperrt | Zu viele Fehlversuche | Die Karte muss mit der PUK entsperrt werden — das geht nur am Gerät |
| Konnektor nicht erreichbar | Konnektor aus oder Netz gestört | Ihre IT informieren |
| Firmware-Update läuft | Der Konnektor aktualisiert gerade Terminals | Nichts tun. Der Roboter wartet ab und versucht keine PIN-Eingabe. |

> **Warum der Roboter bei „ein Versuch übrig" aufhört:** Wäre die hinterlegte
> PIN falsch, würde ein weiterer Versuch die Karte sperren. Eine gesperrte SMC-B
> lässt sich nur mit der PUK entsperren, und danach ist die Karte womöglich für
> Stunden nicht nutzbar. Abbrechen und melden ist immer die bessere Wahl.

### Benachrichtigung per E-Mail

**Benachrichtigung** legt fest, wer bei welchem Schweregrad eine E-Mail bekommt.

Ein **Sammelfenster** verhindert Mailfluten: Fällt ein Konnektor mit 200
Terminals aus, bekommen Sie eine Nachricht, nicht 200. In der Mail stehen nie
PINs oder personenbezogene Daten.

---

## 8. Nachvollziehen, was passiert ist

Vier Sichten, je nach Frage:

| Ihre Frage | Wo Sie nachsehen |
|---|---|
| Was ist gerade los? | **Lage** |
| Was ist mit diesem einen Gerät passiert? | Gerät öffnen → **Zeitstrahl** |
| Wie lief dieser eine Vorgang? | Protokolleintrag → **Vorgang** |
| Was ist an diesem Tag passiert? | **Protokoll**, mit Filtern |

Das Protokoll lässt sich nach Zeitraum, Ereignisart, Ergebnis und Gerät filtern
und **als Excel-Datei herunterladen** — für Auswertungen oder als Nachweis.

**Protokolliert werden Handlungen und Zustandsänderungen, keine Abfragen.** Dass
der Roboter alle 60 Sekunden nachsieht, steht nicht drin; dass sich etwas
geändert hat, immer.

**Einträge lassen sich nicht ändern und nicht löschen** — auch nicht von einem
Administrator. Das ist technisch abgesichert, nicht nur eine Regel.

### Zwei Einträge, die leicht zu verwechseln sind

| Eintrag | Bedeutung |
|---|---|
| **PIN an das Kartenterminal übergeben** | Das Gerät hat nach der PIN gefragt, der Roboter hat sie geliefert. Das heißt noch nicht, dass die Karte sie akzeptiert hat. |
| **Karte freigeschaltet** | Der Zustand ist nachgeprüft und bestätigt. **Das** ist der Erfolg. |

Kommt der erste Eintrag ohne den zweiten, hat die Karte die PIN abgelehnt — dann
steht der Grund direkt daneben.

Ein dritter Eintrag ist wichtiger, als sein Wortlaut vermuten lässt:
**PIN-Anfrage des Kartenterminals abgelehnt.** Er bedeutet, dass der Roboter
eine Anfrage bewusst *nicht* beantwortet hat — etwa weil zu wenige Versuche übrig
waren, der Not-Aus lief oder gerade ein Firmware-Update stattfand. Der Grund
steht dabei. Diese Einträge sind kein Fehler des Systems, sondern der Schutz bei
der Arbeit.

---

## 9. Datensicherung

**Einstellungen → Datensicherung.** Das Produkt sichert seine Daten von selbst:
täglich, wöchentlich und monatlich. Sie müssen dafür nichts tun.

Vier Zahlen zeigen, ob Sie gesichert sind — je Plan, wann zuletzt und wie
viele Stände vorhanden sind. Fehlt eine Sicherung zu lange, erscheint das als
Handlungsbedarf.

### Eine Sicherung aus dem Haus schaffen

Die Sicherungen liegen auf demselben Rechner wie die Datenbank. **Gegen einen
Festplattenausfall hilft das nicht.** Dafür laden Sie eine Sicherung herunter
und bewahren sie woanders auf — auf einem Stick im Tresor, auf einem
Netzlaufwerk, was in Ihrer Einrichtung üblich ist.

Beim Herunterladen fragt das System nach einem **Schlüssel**. Damit wird die
Datei verschlüsselt.

> **Dieser Schlüssel wird nirgends gespeichert.** Nicht bei uns, nicht in der
> Datenbank, nicht im Protokoll. Wenn Sie ihn verlieren, kann die Datei von
> niemandem mehr geöffnet werden — auch nicht vom Hersteller. Notieren Sie ihn
> dort, wo Sie auch Ihre anderen Zugangsdaten aufbewahren.

Eine Folge mehrerer Wörter ist leichter zu merken und sicherer als etwas Kurzes
mit Sonderzeichen. Mindestens zwölf Zeichen.

### Eine Sicherung zurückspielen

**Einstellungen → Vorhandene Stände → Einspielen.** Das ersetzt den aktuellen
Datenbestand durch den gewählten Stand.

Was dabei passiert:

1. Vom **aktuellen** Zustand wird zuerst eine Sicherung angelegt — falls Sie
   sich vertan haben.
2. Der gewählte Stand wird eingespielt, ganz oder gar nicht.
3. Die Zentrale **startet neu**. Das dauert einige Sekunden, danach müssen Sie
   sich neu anmelden.

Der Neustart ist Absicht: Sonst blieben Reste des alten Datenstandes im
Speicher, und die Anzeige zeigte Dinge, die es nicht mehr gibt.

**Die Überwachung der Kartenterminals läuft währenddessen weiter.** Sie
passiert im Standort-Agenten und ist vom Neustart der Zentrale nicht betroffen.

---

## 10. Not-Aus

Der Knopf **Not-Aus** hält die Automatik sofort an. Es werden keine Karten mehr
freigeschaltet, bis Sie ihn wieder aufheben.

Wann Sie ihn brauchen:

- Sie vermuten, dass etwas nicht stimmt, und wollen erst nachsehen.
- Es wird an Konnektor oder Terminals gearbeitet.
- Ein Prüfer oder Datenschutzbeauftragter verlangt es.

Der Not-Aus **hält nur die Automatik an**. Die Überwachung läuft weiter, Sie
sehen also weiterhin, was passiert. Das Aktivieren und Aufheben steht im
Protokoll.

---

## 11. Benutzer und Rechte

| Rolle | Darf |
|---|---|
| **Benutzer** | ansehen, Alarme quittieren |
| **Administrator** | zusätzlich: Geräte erfassen, PINs hinterlegen, Konten verwalten |

Die Rechte hängen zusätzlich an der **Ebene**: Wer für eine Einrichtung
zuständig ist, sieht nur deren Geräte, Alarme und Protokolleinträge. Wer für
einen Standort zuständig ist, sieht alle Einrichtungen darunter.

### Ein neues Konto anlegen

**Konten → Einladung erzeugen.** Die eingeladene Person setzt ihr Passwort
selbst und richtet den zweiten Faktor ein. **Sie geben nie ein Passwort für
jemand anderen ein** — dann kennen es zwei.

### Wenn jemand sein Telefon verliert

**Konten → Zweiter Faktor** bei der betreffenden Person. Sie richtet ihn bei der
nächsten Anmeldung neu ein. Das dürfen nur Administratoren, und es steht im
Protokoll.

### Wenn ein Agent „Einrichtung unbekannt" meldet

Unter **Verwaltung → Standort-Agenten** kann bei einem Agenten der Hinweis
**„Einrichtung unbekannt"** stehen. Dann meldet er gegen eine
Organisationseinheit, die es nicht (mehr) gibt — meist, weil sie umbenannt
wurde. Die Folge: Seine Geräte landen in keiner Gerätegruppe und werden nicht
überwacht.

**Zu tun:** In derselben Zeile unter „Zuordnung ändern" die richtige Einrichtung
wählen und auf **Zuordnen** klicken. Der Hinweis verschwindet, und der Agent
ordnet seine Geräte beim nächsten Melden wieder zu.

Die Änderung steht im Protokoll: Sie verschiebt, wer die Geräte dieses Agenten
sieht.

### Wenn jemand die Einrichtung verlässt

**Deaktivieren**, nicht löschen. Das Konto kann sich nicht mehr anmelden, seine
Einträge im Protokoll bleiben aber zuordenbar. Ein gelöschtes Konto würde die
Nachvollziehbarkeit zerstören.

---

## 12. Fragen, die häufig kommen

**Sieht der PIN-Roboter Patientendaten?**
Nein. Er spricht mit Kartenterminals über deren Verwaltungsschnittstelle und mit
dem Konnektor über den Kartendienst. Versichertendaten kommen dabei nicht vor.

**Kann jemand die hinterlegte PIN auslesen?**
Nicht aus der Zentrale und nicht aus dem Protokoll. Die PIN wird im Browser
verschlüsselt und liegt beim Agenten in verschlüsselter Form. Wer physischen
Zugriff auf den Agenten **und** den Schlüssel hat, kommt heran — deshalb gehört
der Agent in eine gesicherte Umgebung. Das ist keine Nachlässigkeit, sondern die
Bedingung, unter der die gematik das Vorhalten der PIN überhaupt erlaubt.

**Was passiert, wenn die Zentrale ausfällt?**
Der Agent arbeitet weiter. Sie sehen nur nichts und können nichts ändern, bis
sie wieder läuft.

**Was passiert, wenn der Agent ausfällt?**
Dann wird nicht mehr überwacht und nicht mehr freigeschaltet. Die Zentrale
merkt, dass er sich nicht mehr meldet, und alarmiert.

**Warum sehe ich Geräte einer anderen Praxis nicht?**
Weil Sie für Ihre Einrichtung zuständig sind. Das ist keine Einstellung, die
sich versehentlich lösen lässt — die Grenze wird bei jeder Abfrage erzwungen.

**Kann ich das Prüfintervall auf eine Sekunde stellen?**
Nein, die Untergrenze liegt bei 10 Sekunden. Kürzere Abstände belasten Konnektor
und Terminals, ohne etwas zu verbessern — der Zustand ändert sich nicht so
schnell.

---

## 13. Wenn Sie nicht weiterkommen

1. **Lage** ansehen — steht dort ein Alarm mit Handlungshinweis?
2. **Protokoll** ansehen — was ist zuletzt passiert?
3. Wenn beides nichts sagt: Ihre IT oder Ihren Dienstleister fragen. Nennen Sie
   dabei Uhrzeit und Gerätekennung; damit findet sich der Vorgang sofort.

Bei Verdacht, dass etwas grundsätzlich nicht stimmt: **Not-Aus** drücken. Er
hält nur die Automatik an, richtet also keinen Schaden an — und Sie gewinnen
Zeit zum Nachsehen.
