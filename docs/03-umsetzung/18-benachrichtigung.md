# 18 — Benachrichtigung per E-Mail (FA-ALA-020)

**Stand:** umgesetzt (08.08.2026) · **Grundlage:** FA-ALA-020, ADR-0008

---

## Das Sammelfenster ist die Anforderung, nicht der Versand

Der Anforderungskatalog nennt drei Punkte: SMTP konfigurierbar mit Testversand,
Empfänger und Schwelle je Einrichtung, und

> Zusammenfassung statt Einzelmeldung bei Massenereignissen (konfigurierbares
> Sammelfenster), damit ein Konnektorausfall nicht 5.000 E-Mails erzeugt.

Der dritte Punkt ist der schwierige. Fällt ein Konnektor mit 5.000
angeschlossenen Terminals aus, entstehen binnen Sekunden tausende Alarme. Eine
Mail je Alarm hätte drei Folgen, und alle drei sind schlecht:

1. Der Mailserver stuft den Absender als Spam ein.
2. Der Empfänger richtet einen Filter ein — und sieht danach auch die **eine**
   wichtige Mail nicht mehr.
3. Die eigentliche Information — „ein Konnektor ist ausgefallen" — geht in
   tausend Einzelmeldungen unter.

Deshalb: Neue Alarme kommen in eine Warteschlange, und nach Ablauf des Fensters
geht **eine** Mail mit einer Zusammenfassung hinaus. Vorgabe fünf Minuten,
einstellbar von 0 Sekunden bis 24 Stunden.

### Zwei Feinheiten, die leicht falsch gemacht werden

**Das Fenster zählt ab dem ältesten wartenden Alarm, nicht ab dem jüngsten.**
Zählte es ab dem jüngsten, schöbe jeder neu hinzukommende Alarm den Versand
weiter hinaus — bei einem anhaltenden Ausfall ginge nie eine Mail hinaus. Genau
der Fall, für den die Benachrichtigung da ist. Ein Modultest hält das fest.

**Eingereiht wird nur bei *neuen* Alarmen.** `Alarmverwaltung.melden` gibt
zurück, ob der Alarm neu war; ein wiederkehrender Alarm desselben Zustands
erzeugt keinen zweiten Eintrag. Sonst entstünde die Flut auf anderem Weg — über
den Prüftakt statt über die Zahl der Geräte.

### Der Mailtext fasst zusammen

400 Terminals mit derselben Meldung ergeben nicht 400 Zeilen, sondern eine:

```
— Kritisch: Kartenterminal nicht erreichbar
  Betroffen: 400 Geräte (KT-1, KT-2, KT-3, KT-4, KT-5, …)
  Zu tun: Netzwerkverbindung und Stromversorgung prüfen
```

Der Betreff nennt Zahl und höchsten Schweregrad — wer zwanzig Mails im Postfach
hat, entscheidet danach, welche er zuerst öffnet.

## Was nie in einer Mail steht

**Keine PIN, kein Chiffrat, keine Zugangsdaten, keine personenbezogenen Daten.**
Eine Mail verlässt das Haus, läuft über fremde Server und liegt danach in einem
Postfach, dessen Schutz wir nicht kennen. Die Nachricht nennt nur: was passiert
ist, an welchem Gerät, und was zu tun ist.

Der Text sagt das auch selbst — die letzte Zeile jeder Nachricht:

> Sie enthält bewusst keine Zugangsdaten und keine personenbezogenen Daten.

Nachgewiesen wird es gegen den Auffangserver: Der Systemnachweis durchsucht
**alle** dort eingegangenen Mails nach den Test-PINs und -Kennwörtern dieses
Laufs.

## SMTP von Hand statt Bibliothek

`Mailversand` spricht SMTP direkt (RFC 5321). Begründung:

Dieses Produkt braucht genau eine Sache — eine Textnachricht an einen
Mailserver übergeben. Kein Empfang, keine Anhänge, kein MIME-Multipart.
`jakarta.mail` plus `angus-mail` plus `jakarta.activation` wären drei
Abhängigkeiten für einen Ausschnitt, den man in 250 Zeilen liest. Dieselbe
Überlegung wie beim handgeschriebenen SOAP-Client in ADR-0006.

**Was dabei nicht übersehen werden durfte:**

| Fallstrick | Behandlung |
|---|---|
| Umlaute im Betreff | RFC 2047, Base64-Variante — sonst Buchstabensalat im Postfach |
| Zeile mit führendem Punkt | verdoppeln (RFC 5321, 4.5.2), sonst endet die Nachricht dort |
| EHLO nach STARTTLS | wiederholen (RFC 3207) — die Fähigkeitsliste vor der Verschlüsselung ist nicht vertrauenswürdig |
| Zertifikatsname | `setEndpointIdentificationAlgorithm("HTTPS")` — sonst wird jedes gültige Zertifikat akzeptiert, auch ein fremdes |
| Kopfzeilen-Einschleusung | Adressen mit Zeilenumbruch oder Komma werden abgewiesen |
| Zugangsdaten in Meldungen | die `AUTH`-Zeile steht nicht im Verlauf, und die Serverantwort auf abgelehnte Anmeldung wird **nicht** durchgereicht — manche Server geben den Benutzernamen zurück |

Verschlüsselung ist Vorgabe. Abschaltbar ist sie, weil ein Mailserver im
eigenen Praxisnetz sie manchmal nicht anbietet — und die Oberfläche sagt dann,
was das bedeutet.

## Vererbung der Regeln

Eine Regel gilt für die Einheit und alles darunter. Ein Dienstleister stellt
seine Adresse einmal am Verbund ein und bekommt alles; eine einzelne Praxis
kann davon abweichen. Ohne Vererbung müssten bei 1.000 Einrichtungen 1.000
Regeln gepflegt werden.

Gesucht wird von der Einheit **aufwärts**, die nächstgelegene gewinnt.

## Der Testversand

Ohne ihn merkt der Betreiber erst beim ersten echten Alarm, dass die
Einstellung nicht stimmt — also genau dann, wenn die Nachricht gebraucht wird.
Der SMTP-Verlauf ist einklappbar sichtbar; er hilft bei der Fehlersuche, gehört
aber nicht in den Vordergrund.

## Ein gescheiterter Versand verwirft nichts

Kommt die Mail nicht durch, bleiben die Alarme in der Warteschlange und werden
beim nächsten Durchlauf erneut versucht. Sie zu verwerfen wäre die schlechtere
Wahl: Eine nicht zugestellte Meldung über eine gesperrte Karte ist genau die,
die man braucht.

## Nachgewiesen

Modultests (`BenachrichtigungTest`, gegen eine echte Datenbank): Sammelfenster,
Zählung ab dem ältesten Alarm, Sofortversand bei 0, Schwelle, abgeschaltete
Regel, Vererbung, Mandantentrennung, Grenzen des Fensters, Zusammenfassung im
Text, RFC-2047-Betreff, Punkt-Stopfen, Abwehr von Kopfzeilen-Einschleusung.

Systemnachweise gegen einen echten Auffangserver (MailHog): Einrichtung,
Testversand kommt an, Betreff RFC-2047-kodiert, **50 Alarme ergeben eine Mail**,
die Mail fasst zusammen, keine Geheimnisse im Mailverkehr, das
Mailserver-Kennwort liegt verschlüsselt und erscheint weder auf der Oberfläche
noch im Protokoll.
