# 25 — Neue Versionen erkennen (FA-UPD-010)

**Stand:** umgesetzt und nachgewiesen (09.08.2026) · **Grundlage:** ADR-0001

---

## Erkennen ohne Docker-Rechte

ADR-0001 trennt zwei Dinge, die alle fertigen Werkzeuge vermischen:

| | braucht | Risiko |
|---|---|---|
| **Erkennen**, ob eine neue Version vorliegt | eine HTTPS-Abfrage an die Registry | keines |
| **Ausführen** des Updates | Zugriff auf den Docker-Socket | entspricht Root auf dem Host |

Watchtower und Diun verlangen beides dauerhaft. Trennt man die Schritte, ist
der überwiegende Teil des Nutzens ohne jedes Sicherheitsrisiko zu haben — und
genau das ist umgesetzt. **Der Roboter liest nur.**

## Der Fehlerzustand ist die eigentliche Anforderung

Die Akzeptanz sagt es ausdrücklich:

> Ungültige oder abgelaufene Zugangsdaten erscheinen als **sichtbarer
> Fehlerzustand**, nicht als ausbleibende Meldung.

Das ist der schwierige Teil, nicht die Abfrage. Ein Token läuft nach Monaten
ab. Versagt die Prüfung dann still, sieht der Betreiber nur, dass keine Updates
mehr gemeldet werden — und hält das für „es gibt keine". **So bleibt eine
Sicherheitslücke ein Jahr lang ungepatcht**, ohne dass jemand etwas falsch
gemacht hat.

Deshalb wird der letzte Fehler **in der Datenbank gespeichert** und oben in der
Tafel angezeigt, bis eine Abfrage wieder gelingt. Nachgewiesen ist beides: dass
er erscheint, und dass er nach einer erfolgreichen Abfrage verschwindet.

Die Meldung sagt, was zu tun ist:

> Die Registry hat die Zugangsdaten abgelehnt (HTTP 401). Das Token ist
> vermutlich abgelaufen oder zurückgezogen. Bitte ein neues erzeugen und hier
> hinterlegen — bis dahin werden keine neuen Versionen erkannt.

## Zwei Details, die leicht falsch werden

**Versionen werden über Zahlen verglichen, nicht als Zeichenketten.** Als Text
verglichen ist `1.10.0` *kleiner* als `1.9.0` — der Betreiber bekäme kein
Update angeboten, obwohl eines vorliegt. Das fällt erst bei der zehnten
Nebenversion auf, also spät. Ein Modultest hält es fest, mit der Gegenprobe,
dass die Zeichenkettenordnung tatsächlich das Gegenteil sagt.

**`latest` wird nicht als Version angeboten.** Es ist ein Zeiger, keine
Version; es anzuzeigen hieße, ein Update auf „dasselbe nochmal"
vorzuschlagen.

## Änderungshinweise auf Deutsch

Eine Registry liefert keine Änderungshinweise — sie kennt nur Tags. Die
Hinweise kommen deshalb aus einer eigenen Quelle neben den Abbildern, mit einem
Kennzeichen `sicherheit` je Version. Es steuert die Hervorhebung: Ein
Sicherheitsupdate muss sich von einer Verbesserung unterscheiden lassen, **ohne
dass man den Text liest**.

Ist die Quelle nicht erreichbar, bleibt die Versionsliste brauchbar — nur ohne
Text. Ein fehlender Hinweis darf die Meldung nicht verhindern.

## Nachweis ohne Internet

Der echte Weg führt zu GHCR, also ins Netz. Ein Prüflauf, der Internetzugang
braucht, ist als Nachweis unbrauchbar — er wird rot, wenn das Netz klemmt, und
sagt dann nichts über das Produkt. Dieselbe Lehre wie bei FA-ALA-020, wo der
erste Anlauf mit MailHog am Image-Ziehen scheiterte.

Der Simulator bildet deshalb den Ausschnitt der OCI-Registry-Schnittstelle
nach, den ADR-0001 nennt: Tagliste und Zugangsprüfung. Über
`POST /registry/abgelaufen` lässt sich der Ablauf des Tokens auslösen — sonst
wäre die wichtigste Akzeptanz nicht prüfbar.

## Nachgewiesen

13 Systemnachweise: Einrichtung, Anzeige der Versionen, `latest` ausgeschlossen,
deutsche Änderungshinweise, hervorgehobenes Sicherheitsupdate, abgelaufene
Zugangsdaten als sichtbarer Fehler mit Handlungsanweisung, gespeicherter Fehler,
Verschwinden nach Erfolg, kein Token im Klartext (Oberfläche, Protokoll, Logs),
Verschlüsselung in der Datenbank, Zugriffsschutz.

Dazu 7 Modultests zum Versionsvergleich. **213 Modultests in der Zentrale,
295 Systemnachweise** — alle grün, axe-core ohne Befund auf 18 Seiten.

## Was damit nicht geht

**Das Update selbst.** `FA-UPD-020` (geführte Ausführung) ist nicht begonnen —
dafür braucht es Docker-Rechte, und ADR-0001 beschreibt dafür einen eigenen,
eng begrenzten Weg. Bis dahin meldet der Roboter, was verfügbar ist; einspielen
muss es ein Mensch.
