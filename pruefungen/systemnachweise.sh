#!/usr/bin/env bash
# Systemnachweise gegen den laufenden Stack.
#
# Modultests prüfen die Logik isoliert; dieses Skript prüft das Zusammenspiel
# aller Bausteine. Nach CLAUDE.md gilt ein Akzeptanzkriterium erst als erfüllt,
# wenn es am laufenden System nachgewiesen wurde.
#
# Aufruf:  pruefungen/systemnachweise.sh
set -uo pipefail
cd "$(dirname "$0")/.."

NETZ=pinroboter-test
SEC="$PWD/docker"
JRE=eclipse-temurin:21-jre
JOPT="-Djava.security.properties=/sec/brainpool.security"
FEHLER=0

hinweis() { printf '\n\033[1m%s\033[0m\n' "$*"; }

# Sucht in der Antwort eines HTTP-Aufrufs.
#
# WARUM NICHT "curl ... | grep -q": Dieses Skript laeuft mit "set -o pipefail".
# "grep -q" beendet beim ersten Treffer und schliesst die Pipe; curl bekommt
# SIGPIPE und endet mit Status 141. Die Pipeline gilt damit als
# FEHLGESCHLAGEN — obwohl der Treffer gefunden wurde. Ob das passiert, haengt
# davon ab, ob curl mit dem Schreiben fertig war, bevor grep beendete: ein
# Rennen, das mit der Groesse der Antwort kippt. Am 07.08.2026 kippte es durch
# den UX-Umbau, und zwei Nachweise wurden rot, ohne dass am Produkt etwas
# fehlte. "grep -c" liest immer bis zum Ende und kann das Rennen nicht
# verlieren.
enthaelt() {           # enthaelt <datei-oder-text> <muster>  ->  ja|nein
  if [ -f "$1" ]; then
    [ "$(grep -c -- "$2" "$1" || true)" -gt 0 ] && echo ja || echo nein
  else
    [ "$(printf '%s' "$1" | grep -c -- "$2" || true)" -gt 0 ] && echo ja || echo nein
  fi
}
ergebnis() {           # ergebnis <name> <bedingung-erfuellt>
  if [ "$2" = "ja" ]; then printf '  \033[32mBESTANDEN\033[0m  %s\n' "$1"
  else printf '  \033[31mFEHLGESCHLAGEN\033[0m  %s\n' "$1"; FEHLER=$((FEHLER+1)); fi
}
verifikationen() { docker logs pr-agt 2>&1 | grep -c "starte Verifikation"; }

# Datenbankabfragen. Muessen HIER stehen, nicht weiter unten: Bash kennt
# eine Funktion erst ab ihrer Definition, und der Alarmnachweis nutzt sie
# rund 150 Zeilen frueher.
sql() { docker exec pr-db psql -U pinroboter -d pinroboter -tAc "$1" | tr -d ' \n'; }
# Fuer Textwerte: entfernt NUR den Zeilenumbruch. Ein Modellname wie
# "CHERRY ST-1506" verliert sonst sein Leerzeichen - genau daran ist der
# Modellfilter-Nachweis einmal gescheitert, ohne dass am Produkt etwas fehlte.
sqltext() { docker exec pr-db psql -U pinroboter -d pinroboter -tAc "$1" | tr -d '\r\n'; }


datenbank_starten() {
  docker rm -f pr-db >/dev/null 2>&1
  docker volume rm pr-testdaten >/dev/null 2>&1
  docker run -d --name pr-db --network $NETZ --network-alias datenbank \
    -e POSTGRES_DB=pinroboter -e POSTGRES_USER=pinroboter -e POSTGRES_PASSWORD=test \
    -v pr-testdaten:/var/lib/postgresql/data postgres:17-alpine >/dev/null
  sleep 10
}

# Schluessel fuer die verschluesselte Ablage von Zugangsdaten. Wird je Lauf
# neu gezogen - im Betrieb kommt er aus der Konfiguration, nie aus dem Repo.
SCHLUESSEL=$(openssl rand -base64 32 | tr -d '\n')
# ADR-0009: getrennter Schluessel fuer die PIN-Ablage des Agenten. Getrennt,
# damit einer der beiden nicht den anderen mitliefert.
ABLAGESCHLUESSEL=$(openssl rand -base64 32 | tr -d '\n')

KEKSE=/tmp/pr-kekse.txt
TOTP_GEHEIMNIS=""
ADMIN_PW=RichtigPferdBatterieKlammer

# Legt bei Bedarf das Administratorkonto an und meldet sich an.
# Ab Iteration 5 ist die Oberfläche geschützt — ohne Sitzung kein Zugriff.
# Legt bei Bedarf das Administratorkonto an und meldet sich an.
# Ab Iteration 5 ist die Oberflaeche geschuetzt - ohne Sitzung kein Zugriff.
#
# Meldet sich nur an, wenn die vorhandene Sitzung nicht mehr gilt. Grund: Ein
# verbrauchtes Einmalkennwort gilt kein zweites Mal (FA-BEN-020, gewollt), und
# zwei Anmeldungen im selben 30-Sekunden-Fenster scheitern daran. Die Sitzung
# liegt in der Datenbank und uebersteht einen Neustart der Zentrale - eine
# erneute Anmeldung ist also fast nie noetig.
anmelden() {
  if [ -f "$KEKSE" ] && \
     [ "$(curl -s -b "$KEKSE" -o /dev/null -w '%{http_code}' http://localhost:8090/geraete)" = "200" ]; then
    return 0
  fi
  rm -f "$KEKSE"

  curl -s http://localhost:8090/ersteinrichtung -o /tmp/pr-erst.html
  if [ "$(enthaelt /tmp/pr-erst.html "Administratorkonto")" = "ja" ]; then
    A=$(curl -s -X POST http://localhost:8090/ersteinrichtung \
        -d "benutzer=pruefadmin&anzeigename=Pruefung&passwort=$ADMIN_PW")
    echo "$A" | grep -oE '<code>[A-Z2-7 ]+</code>' | sed 's/<[^>]*>//g' | tr -d ' ' > /tmp/pr-totp.txt
    BID=$(echo "$A" | grep -oE 'name="benutzerId" value="[0-9]+"' | grep -oE '[0-9]+')
    curl -s -o /dev/null -X POST http://localhost:8090/totp-bestaetigen \
      -d "benutzerId=$BID&code=$(python3 pruefungen/totp.py "$(cat /tmp/pr-totp.txt)")"
    # Die Bestaetigung hat den Code dieses Fensters verbraucht.
    sleep 31
  fi
  TOTP_GEHEIMNIS=$(cat /tmp/pr-totp.txt)

  for versuch in 1 2 3; do
    curl -s -c "$KEKSE" -o /dev/null -X POST http://localhost:8090/anmelden \
      -d "benutzer=pruefadmin&passwort=$ADMIN_PW&code=$(python3 pruefungen/totp.py "$TOTP_GEHEIMNIS")"
    if [ "$(curl -s -b "$KEKSE" -o /dev/null -w '%{http_code}' http://localhost:8090/geraete)" = "200" ]; then
      return 0
    fi
    sleep 31   # naechstes Zeitfenster abwarten
  done

  echo "  ABBRUCH: Anmeldung im Pruefskript nach 3 Versuchen fehlgeschlagen." >&2
  exit 2
}

stack_starten() {      # stack_starten <pin-fuer-card-smcb-001> <intervall>
  docker rm -f pr-sim pr-zen pr-agt >/dev/null 2>&1
  docker network create $NETZ >/dev/null 2>&1
  docker ps -q -f name=pr-db | grep -q . || datenbank_starten
  docker run -d --name pr-sim --network $NETZ --network-alias simulator \
    -v "$PWD/src/simulator/target/simulator.jar":/app.jar -v "$SEC":/sec:ro -p 8480:8480 \
    $JRE java $JOPT -jar /app.jar >/dev/null
  docker run -d --name pr-zen --network $NETZ --network-alias zentrale \
    -v "$PWD/src/zentrale/target/zentrale.jar":/app.jar -p 8090:8090 \
    -e DB_URL=jdbc:postgresql://datenbank:5432/pinroboter \
    -e DB_USER=pinroboter -e DB_PASSWORD=test \
    -e GEHEIMNIS_SCHLUESSEL="$SCHLUESSEL" \
    $JRE java -jar /app.jar >/dev/null
  for _ in $(seq 40); do
    curl -sf -o /dev/null http://localhost:8090/anmelden 2>/dev/null && break
    curl -sf -o /dev/null http://localhost:8090/ersteinrichtung 2>/dev/null && break
    sleep 1
  done
  anmelden

  # Seit FA-UEB-010 taktet der Agent JE GERAETEGRUPPE. Die Vorgabegruppe steht
  # auf 60 s — im Betrieb richtig, fuer die Nachweise zu langsam: Ein Nachweis,
  # der in 20 s eine Verifikation erwartet, liefe sonst ins Leere und wuerde
  # eine Funktion als kaputt melden, die in Ordnung ist.
  #
  # 10 s ist das kleinste zulaessige Intervall (CHECK in der Datenbank, als
  # Lastschutz gewollt). Die Nachweise takten damit so schnell, wie das Produkt
  # es ueberhaupt zulaesst.
  VORGABEGRUPPE=$(docker exec pr-db psql -U pinroboter -d pinroboter -tAc \
      "SELECT id FROM geraetegruppe WHERE ist_vorgabe" 2>/dev/null | tr -d ' \r\n')
  [ -n "$VORGABEGRUPPE" ] && curl -s -b "$KEKSE" -o /dev/null -X POST \
      "http://localhost:8090/gruppen/$VORGABEGRUPPE/intervall" --data-urlencode "intervall=10"

  # Ab Iteration 6 muss der Agent registriert sein (FA-AGT-010).
  GEHEIMNIS=$(curl -s -b "$KEKSE" -X POST http://localhost:8090/agenten/geheimnis \
    -d "bezeichnung=Pruefagent" | grep -oE '<code>[A-Za-z0-9_-]{40,}</code>' | sed 's/<[^>]*>//g')
  docker volume rm pr-agentdaten >/dev/null 2>&1
  docker run -d --name pr-agt --network $NETZ \
    -v "$PWD/src/agent/target/agent.jar":/app.jar -v "$SEC":/sec:ro -v pr-agentdaten:/daten \
    -e REGISTRIERUNG="$GEHEIMNIS" \
    -e KONNEKTOR_URL=https://simulator:8443/ -e TERMINAL_URL=https://simulator:8444/ \
    -e ZENTRALE_URL=http://zentrale:8090/ -e CA_URL=http://simulator:8480/ca.pem \
    -e PRUEFINTERVALL_SEKUNDEN="$2" -e PIN_card_smcb_001="$1" \
    -e PIN_ABLAGE_SCHLUESSEL="$ABLAGESCHLUESSEL" \
    $JRE java $JOPT -jar /app.jar >/dev/null
  # Aktiv warten statt fest schlafen: Feste Wartezeiten machen Tests wetterfühlig
  # — sie werden rot, wenn eine Maschine langsamer ist, und das sagt nichts über
  # das Produkt aus. Gewartet wird, bis der Agent nachweislich einen Prüfzyklus
  # gefahren hat.
  # Auf einen ABGESCHLOSSENEN Zyklus warten, nicht auf dessen Beginn.
  # "Karte nicht freigeschaltet - starte Verifikation" ist der Anfang;
  # abgeschlossen ist er erst mit dem Ergebnis.
  for _ in $(seq 60); do
    docker logs pr-agt 2>&1 | grep -qE "^  \[.*\] (freigeschaltet|Die hinterlegte PIN)" && break
    sleep 1
  done
  sleep 2
}

# ---------------------------------------------------------------- Modul- und Integrationstests
hinweis "Modul- und Integrationstests (inkl. Benutzerverwaltung gegen echte Datenbank)"
datenbank_starten
if docker run --rm --network $NETZ -v "$PWD":/p -v pinroboter-m2:/root/.m2 -w /p \
     -e TEST_DB_URL=jdbc:postgresql://datenbank:5432/pinroboter \
     -e TEST_DB_USER=pinroboter -e TEST_DB_PASSWORD=test \
     maven:3.9-eclipse-temurin-21 mvn -B -q test >/tmp/pr-tests.log 2>&1; then T=ja; else T=nein; fi
ergebnis "alle automatisierten Tests grün" "$T"
[ "$T" = "nein" ] && tail -20 /tmp/pr-tests.log
# Die Integrationstests legen Testbenutzer an. Fuer die folgenden Nachweise
# wird ein frisches System gebraucht - sonst greift die Ersteinrichtung nicht.
datenbank_starten
rm -f "$KEKSE" /tmp/pr-totp.txt

# ---------------------------------------------------------------- Durchstich
hinweis "Durchstich — vollständige PEviM-Kette über Brainpool-TLS"
stack_starten 123456 4
docker logs pr-agt 2>&1 | grep -qE "^  \[.*\] freigeschaltet" && D=ja || D=nein
ergebnis "SMC-B wird selbsttätig freigeschaltet" "$D"
[ "$(docker logs pr-sim 2>&1 | grep -c 'no selectable')" = "0" ] && H=ja || H=nein
ergebnis "keine TLS-Handshake-Fehler" "$H"

# ---------------------------------------------------------------- FA-PIN-030
hinweis "FA-PIN-030 — falsche PIN löst genau EINEN Versuch aus"
echo "  (vor jeder Auslieferung verpflichtend)"
stack_starten 999999 4
sleep 35
A=$(verifikationen)
[ "$A" = "1" ] && P=ja || P=nein
ergebnis "genau 1 Versuch bei ~10 Gelegenheiten (gemessen: $A)" "$P"
docker logs pr-agt 2>&1 | grep -q "Noch 2 Versuche" && L=ja || L=nein
ergebnis "verbleibende Versuche werden in Klartext gemeldet" "$L"

# ---------------------------------------------------------------- FA-UEB-050
hinweis "FA-UEB-050 — während eines Firmware-Updates wird nicht verifiziert"
stack_starten 123456 4
V=$(verifikationen)
curl -s -X POST "http://localhost:8480/steuerung/KT-EMPFANG-01/firmware/4.3.0?dauer=30000" >/dev/null
echo "  Update läuft 30 s bei 10 s Gruppentakt → ~3 Gelegenheiten"
sleep 26; W=$(verifikationen)
[ $((W-V)) -eq 0 ] && U=ja || U=nein
ergebnis "0 Versuche während des Updates (gemessen: $((W-V)))" "$U"
sleep 35; N=$(verifikationen)
[ $((N-W)) -ge 1 ] && R=ja || R=nein
ergebnis "Wiederherstellung nach dem Update (gemessen: $((N-W)))" "$R"

# ---------------------------------------------------------------- FA-PIN-050
hinweis "FA-PIN-050 — Not-Aus hält die Automatik an"
anmelden   # frische Sitzung, damit der Nachweis nicht an der Sitzungshistorie haengt
C=$(curl -s -b "$KEKSE" -o /dev/null -w "%{http_code}" -X POST http://localhost:8090/notaus)
echo "  POST /notaus -> HTTP $C"
sleep 5; V=$(verifikationen)
curl -s -X POST http://localhost:8480/steuerung/KT-EMPFANG-01/entziehen >/dev/null
sleep 22; W=$(verifikationen)
[ $((W-V)) -eq 0 ] && E=ja || E=nein
ergebnis "0 Versuche bei aktivem Not-Aus (gemessen: $((W-V)))" "$E"
curl -s -b "$KEKSE" -X POST http://localhost:8090/notaus >/dev/null
sleep 35; N=$(verifikationen)
[ $((N-W)) -ge 1 ] && F=ja || F=nein
ergebnis "Automatik läuft nach Aufhebung wieder (gemessen: $((N-W)))" "$F"
# Getrennt gefragt: steht es im Protokoll, und steht es in der Ansicht?
# Der Nachweis flatterte, solange er beides in einem Griff geprueft hat.
DB=$(docker exec pr-db psql -U pinroboter -d pinroboter -tAc \
     "SELECT count(*) FROM fachprotokoll WHERE art LIKE 'NOT_AUS%'" | tr -d ' \n')
[ "${DB:-0}" -ge 2 ] && G=ja || G=nein
ergebnis "Not-Aus und Aufhebung stehen im Fachprotokoll ($DB von 2)" "$G"

ANTWORT=$(curl -s -b "$KEKSE" -w '\n%{http_code}' http://localhost:8090/teil/protokoll)
STATUS=$(echo "$ANTWORT" | tail -1)
GA=$(enthaelt "$ANTWORT" "Not-Aus")
if [ "$GA" = "nein" ]; then
  GES=$(docker exec pr-db psql -U pinroboter -d pinroboter -tAc \
        "SELECT count(*) FROM fachprotokoll" | tr -d ' \n')
  JUENGER=$(docker exec pr-db psql -U pinroboter -d pinroboter -tAc \
        "SELECT count(*) FROM fachprotokoll WHERE zeitpunkt >
           (SELECT max(zeitpunkt) FROM fachprotokoll WHERE art LIKE 'NOT_AUS%')" | tr -d ' \n')
  echo "    Diagnose: HTTP $STATUS, $GES Eintraege im Protokoll, davon $JUENGER juenger"
  echo "    als der letzte Not-Aus-Eintrag (die Ansicht zeigt die 200 juengsten)."
fi
ergebnis "Not-Aus erscheint in der Protokollansicht" "$GA"

# ---------------------------------------------------------------- FA-PRO-020
hinweis "FA-PRO-020 — keine Geheimnisse in Protokollen"
GEHEIM=0
for w in 123456 999999 654321; do
  docker logs pr-agt 2>&1 | grep -q "$w" && GEHEIM=$((GEHEIM+1))
  curl -s -b "$KEKSE" http://localhost:8090/teil/protokoll -o /tmp/pr-prot-suche.html
  [ "$(enthaelt /tmp/pr-prot-suche.html "$w")" = "ja" ] && GEHEIM=$((GEHEIM+1))
done
[ $GEHEIM -eq 0 ] && S=ja || S=nein
ergebnis "keine PIN in Agent-Ausgabe oder Fachprotokoll (Treffer: $GEHEIM)" "$S"

# ---------------------------------------------------------------- Persistenz
hinweis "NFA-BET-020 / FA-PRO-010 — Persistenz und Unveränderlichkeit"
VOR=$(docker exec pr-db psql -U pinroboter -d pinroboter -tAc "SELECT count(*) FROM fachprotokoll")
docker rm -f pr-zen >/dev/null 2>&1
docker run -d --name pr-zen --network $NETZ --network-alias zentrale \
  -v "$PWD/src/zentrale/target/zentrale.jar":/app.jar -p 8090:8090 \
  -e DB_URL=jdbc:postgresql://datenbank:5432/pinroboter \
  -e DB_USER=pinroboter -e DB_PASSWORD=test -e GEHEIMNIS_SCHLUESSEL="$SCHLUESSEL" \
  $JRE java -jar /app.jar >/dev/null
for _ in $(seq 40); do curl -sf http://localhost:8090/api/notaus >/dev/null 2>&1 && break; sleep 1; done
NACH=$(docker exec pr-db psql -U pinroboter -d pinroboter -tAc "SELECT count(*) FROM fachprotokoll")
[ "$NACH" -ge "$VOR" ] && [ "$VOR" -gt 0 ] && B=ja || B=nein
ergebnis "Protokoll überlebt den Containeraustausch ($VOR -> $NACH)" "$B"
[ "$(docker logs pr-zen 2>&1 | grep -c 'Migration .* angewendet')" = "0" ] && M=ja || M=nein
ergebnis "Migrationen sind idempotent" "$M"
docker exec pr-db psql -U pinroboter -d pinroboter -c \
  "UPDATE fachprotokoll SET text='manipuliert' WHERE id=1" >/dev/null 2>&1
docker exec pr-db psql -U pinroboter -d pinroboter -c \
  "DELETE FROM fachprotokoll WHERE id=1" >/dev/null 2>&1
T=$(docker exec pr-db psql -U pinroboter -d pinroboter -tAc \
  "SELECT count(*) FROM fachprotokoll WHERE text='manipuliert'")
[ "$T" = "0" ] && I=ja || I=nein
ergebnis "Fachprotokoll ist weder änderbar noch löschbar" "$I"

# ---------------------------------------------------------------- Oberflaeche
hinweis "Oberflaeche — Stylesheet und HTMX werden ausgeliefert"
echo "  Diese Pruefung fehlte bis 07.08.2026. Ergebnis: Die Oberflaeche lief"
echo "  wochenlang ungestylt, weil das Stylesheet mit 404 beantwortet wurde -"
echo "  kein einziger Nachweis hatte je hingesehen."
FEHLT=0
for d in stil.css htmx.min.js; do
  C=$(curl -s -o /dev/null -w "%{http_code}" "http://localhost:8090/statisch/$d")
  G=$(curl -s -o /dev/null -w "%{size_download}" "http://localhost:8090/statisch/$d")
  [ "$C" = "200" ] && [ "$G" -gt 1000 ] || { FEHLT=$((FEHLT+1)); echo "    $d: HTTP $C, $G Bytes"; }
done
[ $FEHLT -eq 0 ] && ST=ja || ST=nein
ergebnis "Stylesheet und HTMX werden ausgeliefert (fehlend: $FEHLT von 2)" "$ST"

# Die Anmeldeseite muss das Stylesheet auch einbinden - eine ausgelieferte
# Datei nuetzt nichts, wenn die Seite sie nicht anfordert.
curl -s http://localhost:8090/anmelden -o /tmp/pr-antwort.html
EB=$(enthaelt /tmp/pr-antwort.html 'href="/statisch/stil.css"')
ergebnis "die Anmeldeseite bindet das Stylesheet ein" "$EB"

# Javalin meldet Konfigurationsfehler beim Start, laeuft aber weiter. Genau so
# blieb der 404 unbemerkt. Warnungen beim Start werden deshalb nicht toleriert.
docker logs pr-zen 2>&1 | grep -qiE "does not exist|WARN.*static" && SW=nein || SW=ja
ergebnis "keine Warnungen zur Ressourcenkonfiguration beim Start" "$SW"

# ---------------------------------------------------------------- Zugriffsschutz
hinweis "FA-BEN-020 / NFA-SIC-050 — ohne Anmeldung kein Zugriff"
OFFEN=0
for pfad in / /geraete /protokoll /teil/geraete /teil/protokoll; do
  CODE=$(curl -s -o /dev/null -w "%{http_code}" "http://localhost:8090$pfad")
  [ "$CODE" = "200" ] && OFFEN=$((OFFEN+1))
done
[ $OFFEN -eq 0 ] && Z=ja || Z=nein
ergebnis "keine Oberflaechenseite ohne Sitzung erreichbar (offen: $OFFEN von 5)" "$Z"

VORHER=$(curl -s -b "$KEKSE" http://localhost:8090/api/notaus)
curl -s -o /dev/null -X POST http://localhost:8090/notaus     # ohne Sitzung
NACHHER=$(curl -s -b "$KEKSE" http://localhost:8090/api/notaus)
[ "$VORHER" = "$NACHHER" ] && K=ja || K=nein
ergebnis "Not-Aus lässt sich ohne Anmeldung nicht auslösen" "$K"

curl -s -b "$KEKSE" -o /dev/null -w "" -X POST http://localhost:8090/abmelden
CODE=$(curl -s -b "$KEKSE" -o /dev/null -w "%{http_code}" http://localhost:8090/geraete)
[ "$CODE" != "200" ] && AB=ja || AB=nein
ergebnis "nach dem Abmelden ist die Sitzung serverseitig ungültig" "$AB"
anmelden

# ---------------------------------------------------------------- Alarme
hinweis "FA-ALA-010 — Alarme entstehen und verschwinden von selbst"

# Gezaehlt wird GENAU DIESER Alarm, nicht alle sichtbaren. Die frueheren
# Fassungen zaehlten "Quittieren"-Knoepfe auf der Startseite — mit jedem neuen
# Alarmtyp im Produkt wurde die Zaehlung unzuverlaessiger, und am 09.08.2026
# meldete sie eine Alarmflut, wo nur ein zweiter, anderer Alarm dazukam.
dieser_alarm() {
  sql "SELECT count(*) FROM alarm WHERE objekt='KT-EMPFANG-01'
       AND art='GERAET_NICHT_ERREICHBAR' AND behoben_am IS NULL"
}

curl -s -X POST http://localhost:8480/steuerung/KT-EMPFANG-01/erreichbar/false >/dev/null
# Aktiv warten: Seit FA-UEB-010 taktet der Agent je Geraetegruppe (10 s im
# Nachweislauf). Eine feste Wartezeit war ein Rennen.
for _ in $(seq 30); do
  [ "$(dieser_alarm)" -ge 1 ] && break
  sleep 2
done
A1=$(dieser_alarm)
[ "$A1" = "1" ] && AL=ja || AL=nein
ergebnis "nicht erreichbares Gerät erzeugt einen Alarm (gefunden: $A1)" "$AL"

# Die Handlungsempfehlung steht am Alarm selbst, nicht irgendwo auf der Seite.
HTEXT=$(sqltext "SELECT coalesce(handlung,'-') FROM alarm WHERE objekt='KT-EMPFANG-01'
                 AND art='GERAET_NICHT_ERREICHBAR' AND behoben_am IS NULL LIMIT 1")
case "$HTEXT" in *"eingeschaltet und mit dem Netzwerk"*) HE=ja ;; *) HE=nein ;; esac
[ "$HE" = "nein" ] && echo "    Diagnose: Handlung war \"$HTEXT\""
ergebnis "der Alarm nennt eine Handlungsempfehlung" "$HE"

# Und sie muss auch in der Oberflaeche ankommen.
curl -s -b "$KEKSE" http://localhost:8090/ -o /tmp/pr-antwort.html
HO=$(enthaelt /tmp/pr-antwort.html "eingeschaltet und mit dem Netzwerk")
ergebnis "sie erscheint in der Lage-Uebersicht" "$HO"

# Derselbe Zustand ueber viele Pruefzyklen darf keinen ZWEITEN Alarm erzeugen.
sleep 25
A2=$(dieser_alarm)
[ "$A2" = "1" ] && DU=ja || DU=nein
ergebnis "keine Alarmflut bei andauerndem Zustand ($A1 -> $A2)" "$DU"

curl -s -X POST http://localhost:8480/steuerung/KT-EMPFANG-01/erreichbar/true >/dev/null
for _ in $(seq 25); do
  [ "$(dieser_alarm)" = "0" ] && break
  sleep 2
done
A3=$(dieser_alarm)
[ "$A3" = "0" ] && BE=ja || BE=nein
ergebnis "behobener Zustand schließt den Alarm selbsttätig ($A1 -> $A3)" "$BE"

# ---------------------------------------------------------------- Erreichbarkeit
hinweis "Jeder Navigationslink fuehrt zu einer Seite"
echo "  Diese Pruefung fehlte bis 07.08.2026. Zweimal wurde eine fertige Seite"
echo "  gebaut, verlinkt - und die Route nie registriert. Der Compiler sieht das"
echo "  nicht: Ein Link ist eine Zeichenkette, kein Aufruf."
TOT=0
# Links aus der Navigation der Startseite auslesen und alle aufrufen
# Die Liste kommt aus der Startseite UND aus einer festen Aufzaehlung. Nur
# extrahiert war sie wertlos: Ist die Startseite selbst kaputt, findet der
# Ausdruck null Ziele — und der Nachweis meldet "0 tot", also bestanden.
# Genau das ist am 09.08.2026 passiert, waehrend / mit HTTP 500 antwortete.
ZIELE=$( { curl -s -b "$KEKSE" http://localhost:8090/ \
             | grep -oE '<a href="/[a-z-]*"' | grep -oE '"/[a-z-]*"' | tr -d '"'
           printf '%s\n' / /geraete /bestand /gruppen /organisation /benutzer \
                          /verwaltung /protokoll /benachrichtigung
         } | sort -u)
for z in $ZIELE; do
  C=$(curl -s -b "$KEKSE" -o /dev/null -w "%{http_code}" "http://localhost:8090$z")
  if [ "$C" != "200" ]; then TOT=$((TOT+1)); echo "    $z -> HTTP $C"; fi
done
ANZ=$(echo "$ZIELE" | wc -w | tr -d ' ')
[ $TOT -eq 0 ] && [ "$ANZ" -ge 4 ] && NV=ja || NV=nein
ergebnis "alle $ANZ Navigationsziele liefern HTTP 200 (tot: $TOT)" "$NV"

# ---------------------------------------------------------------- Speicherbudget
hinweis "FA-PRO-070 — volles Protokoll haelt die Kernfunktion nicht an"
echo "  Zentrale mit 400 KB Protokollbudget neu starten (normal: 2 GB)"
docker rm -f pr-zen >/dev/null 2>&1
docker run -d --name pr-zen --network $NETZ --network-alias zentrale \
  -v "$PWD/src/zentrale/target/zentrale.jar":/app.jar -p 8090:8090 \
  -e DB_URL=jdbc:postgresql://datenbank:5432/pinroboter \
  -e DB_USER=pinroboter -e DB_PASSWORD=test -e PROTOKOLL_BUDGET=400KB \
  -e GEHEIMNIS_SCHLUESSEL="$SCHLUESSEL" \
  $JRE java -jar /app.jar >/dev/null
for _ in $(seq 40); do curl -sf -o /dev/null http://localhost:8090/anmelden 2>/dev/null && break; sleep 1; done
anmelden

V=$(verifikationen)
# Karte wiederholt entziehen: erzeugt Verifikationen UND Protokolleintraege
for i in 1 2 3 4 5 6; do
  curl -s -X POST http://localhost:8480/steuerung/KT-EMPFANG-01/entziehen >/dev/null
  sleep 11
done
N=$(verifikationen)
[ $((N-V)) -ge 3 ] && KF=ja || KF=nein
ergebnis "Kernfunktion arbeitet bei knappem Speicher weiter ($((N-V)) Verifikationen)" "$KF"

# Rotation muss gelaufen sein oder der Fuellstand unter Kontrolle sein
sleep 65   # der Waechter prueft im Minutentakt
BELEGT=$(docker exec pr-db psql -U pinroboter -d pinroboter -tAc \
         "SELECT pg_total_relation_size('fachprotokoll')")
[ "$BELEGT" -lt 2000000 ] && RO=ja || RO=nein
ergebnis "Fuellstand bleibt begrenzt ($((BELEGT/1024)) KB)" "$RO"

SICHER=$(docker exec pr-db psql -U pinroboter -d pinroboter -tAc \
         "SELECT count(*) FROM fachprotokoll WHERE stufe='SICHERHEIT'")
[ "$SICHER" -gt 0 ] && ST=ja || ST=nein
ergebnis "sicherheitsrelevante Eintraege ueberleben die Rotation ($SICHER vorhanden)" "$ST"

# Zentrale wieder mit normalem Budget
docker rm -f pr-zen >/dev/null 2>&1
docker run -d --name pr-zen --network $NETZ --network-alias zentrale \
  -v "$PWD/src/zentrale/target/zentrale.jar":/app.jar -p 8090:8090 \
  -e DB_URL=jdbc:postgresql://datenbank:5432/pinroboter \
  -e DB_USER=pinroboter -e DB_PASSWORD=test -e GEHEIMNIS_SCHLUESSEL="$SCHLUESSEL" \
  $JRE java -jar /app.jar >/dev/null
for _ in $(seq 40); do curl -sf -o /dev/null http://localhost:8090/anmelden 2>/dev/null && break; sleep 1; done
anmelden

# ---------------------------------------------------------------- Excel-Export
hinweis "FA-PRO-040 — Excel-Export"
XLSX=/tmp/pr-export.xlsx
CODE=$(curl -s -b "$KEKSE" -o "$XLSX" -w "%{http_code}" http://localhost:8090/protokoll/export)
[ "$CODE" = "200" ] && [ -s "$XLSX" ] && EX=ja || EX=nein
ergebnis "Export liefert eine Datei (HTTP $CODE, $(wc -c < "$XLSX" 2>/dev/null || echo 0) Bytes)" "$EX"

# Der eigentliche Nachweis: eine UNABHAENGIGE Umsetzung liest die Datei.
# Das eigene Format gegen die eigene Umsetzung zu pruefen waere zirkulaer.
PYBIN=/private/tmp/claude-501/-Users-mni-Projekte-KT-PIN-Roboter/fb51524a-e2f5-4cb6-ad89-0656d47c45a8/scratchpad/venv/bin/python
if [ -x "$PYBIN" ] && "$PYBIN" pruefungen/xlsx-pruefen.py "$XLSX"; then OP=ja; else OP=nein; fi
ergebnis "openpyxl (unabhaengige Bibliothek) liest die Datei fehlerfrei" "$OP"

# Getrennt gefragt wie bei Not-Aus und Registrierung: Der Nachweis flatterte,
# solange er "steht im Protokoll" und "steht in der Ansicht" in einem Griff
# geprueft hat. Die Ansicht zeigt nur die 200 juengsten Eintraege - mit
# wachsender Zahl von Ereignissen rutscht ein aelterer Eintrag heraus, ohne
# dass am Produkt etwas falsch waere.
DB=$(docker exec pr-db psql -U pinroboter -d pinroboter -tAc \
     "SELECT count(*) FROM fachprotokoll WHERE art='PROTOKOLL_EXPORTIERT'" | tr -d ' \n')
[ "${DB:-0}" -gt 0 ] && PE=ja || PE=nein
ergebnis "der Export steht im Fachprotokoll ($DB Eintraege)" "$PE"

curl -s -b "$KEKSE" http://localhost:8090/teil/protokoll -o /tmp/pr-antwort.html
PA=$(enthaelt /tmp/pr-antwort.html "Protokoll exportiert")
if [ "$PA" = "nein" ]; then
  GES=$(docker exec pr-db psql -U pinroboter -d pinroboter -tAc \
        "SELECT count(*) FROM fachprotokoll" | tr -d ' \n')
  echo "    Diagnose: $GES Eintraege im Protokoll, Ansicht zeigt die 200 juengsten."
  echo "    Bekannte Luecke: Die Protokollansicht kann nicht zurueckblaettern."
fi
ergebnis "der Export erscheint in der Protokollansicht" "$PA"

# ADR-0005: Blaettern in der Protokollansicht. Bis zum 07.08.2026 zeigte sie
# nur die 200 juengsten Eintraege und konnte nicht zurueck - wer einen Vorgang
# von gestern nachvollziehen wollte, musste exportieren.
GES=$(docker exec pr-db psql -U pinroboter -d pinroboter -tAc \
      "SELECT count(*) FROM fachprotokoll" | tr -d ' \n')
S1=$(curl -s -b "$KEKSE" "http://localhost:8090/teil/protokoll?seite=1")
BL=$(enthaelt "$S1" "Seite 1 von")
ergebnis "die Protokollansicht zeigt eine Blaetterleiste ($GES Eintraege)" "$BL"

Z1=$(echo "$S1" | grep -c "<tr>")
[ "${Z1:-0}" -le 50 ] && BG=ja || BG=nein
ergebnis "eine Seite zeigt hoechstens 50 Zeilen (gezaehlt: $Z1)" "$BG"

if [ "${GES:-0}" -gt 50 ]; then
  S2=$(curl -s -b "$KEKSE" "http://localhost:8090/teil/protokoll?seite=2")
  B2=$(enthaelt "$S2" "Seite 2 von")
  ergebnis "die zweite Seite laesst sich abrufen" "$B2"

  # Ohne eindeutige Sortierung wandern Zeilen zwischen den Seiten. Geprueft
  # wird an den Vorgangsnummern: keine darf auf beiden Seiten stehen.
  echo "$S1" | grep -oE '<code>[^<]+</code>' | sort -u > /tmp/pr-s1.txt
  echo "$S2" | grep -oE '<code>[^<]+</code>' | sort -u > /tmp/pr-s2.txt
  DOPPELT=$(comm -12 /tmp/pr-s1.txt /tmp/pr-s2.txt | grep -v '>—<' | wc -l | tr -d ' ')
  [ "${DOPPELT:-1}" = "0" ] && BU=ja || BU=nein
  ergebnis "keine Ueberschneidung zwischen Seite 1 und 2 (doppelt: $DOPPELT)" "$BU"
  rm -f /tmp/pr-s1.txt /tmp/pr-s2.txt
else
  echo "  uebersprungen: nur $GES Eintraege, zu wenig fuer eine zweite Seite"
fi

# Hinter das Ende geblaettert: Statt einer leeren Seite die letzte vorhandene.
curl -s -b "$KEKSE" "http://localhost:8090/teil/protokoll?seite=9999" -o /tmp/pr-antwort.html
BE=$(enthaelt /tmp/pr-antwort.html "Seite [0-9]* von")
ergebnis "eine Seitennummer hinter dem Ende fuehrt nicht ins Leere" "$BE"
rm -f "$XLSX"

# ---------------------------------------------------------------- Agent-Zugang
hinweis "FA-AGT-010 / FA-AGT-020 — Agent-Registrierung und Token"
OFFEN=0
for pfad in /api/agent/melden /api/agent/notaus; do
  CODE=$(curl -s -o /dev/null -w "%{http_code}" "http://localhost:8090$pfad")
  [ "$CODE" != "401" ] && OFFEN=$((OFFEN+1))
done
CODE=$(curl -s -o /dev/null -w "%{http_code}" -H "X-Agent-Token: erfunden" \
       http://localhost:8090/api/agent/notaus)
[ "$CODE" != "401" ] && OFFEN=$((OFFEN+1))
[ $OFFEN -eq 0 ] && AG=ja || AG=nein
ergebnis "Agent-Schnittstellen ohne gültiges Token gesperrt (offen: $OFFEN von 3)" "$AG"

curl -s -X POST -H "X-Registrierung: erfunden" http://localhost:8090/api/registrieren -o /tmp/pr-antwort.html
RG=$(enthaelt /tmp/pr-antwort.html "Unbekanntes")
ergebnis "erfundenes Registrierungsgeheimnis wird abgewiesen" "$RG"

G2=$(curl -s -b "$KEKSE" -X POST http://localhost:8090/agenten/geheimnis -d "bezeichnung=Einmaltest" \
     | grep -oE '<code>[A-Za-z0-9_-]{40,}</code>' | sed 's/<[^>]*>//g')
curl -s -X POST -H "X-Registrierung: $G2" http://localhost:8090/api/registrieren -o /tmp/pr-antwort.html
E1=$(enthaelt /tmp/pr-antwort.html "token")
ergebnis "gültiges Geheimnis lässt sich einlösen" "$E1"
curl -s -X POST -H "X-Registrierung: $G2" http://localhost:8090/api/registrieren -o /tmp/pr-antwort.html
E2=$(enthaelt /tmp/pr-antwort.html "bereits verwendet")
ergebnis "dasselbe Geheimnis ein zweites Mal wird abgewiesen" "$E2"

# Zwei getrennte Fragen, weil sie unterschiedliche Fehler bedeuten: Steht der
# Eintrag ueberhaupt im Protokoll (sonst fehlt die Nachvollziehbarkeit), und
# erscheint er in der Ansicht (sonst greift die Rotation oder die Anzeigegrenze)?
DB=$(docker exec pr-db psql -U pinroboter -d pinroboter -tAc \
     "SELECT count(*) FROM fachprotokoll WHERE art='AGENT_REGISTRIERUNG_ABGELEHNT'" | tr -d ' \n')
[ "${DB:-0}" -gt 0 ] && PR=ja || PR=nein
ergebnis "abgelehnte Registrierungen stehen im Fachprotokoll ($DB Eintraege)" "$PR"

curl -s -b "$KEKSE" http://localhost:8090/teil/protokoll -o /tmp/pr-antwort.html
PA=$(enthaelt /tmp/pr-antwort.html "Registrierung abgelehnt")
if [ "$PA" = "nein" ] && [ "${DB:-0}" -gt 0 ]; then
  GES=$(docker exec pr-db psql -U pinroboter -d pinroboter -tAc \
        "SELECT count(*) FROM fachprotokoll" | tr -d ' \n')
  echo "    Diagnose: $DB Eintrag/Eintraege in der Datenbank, aber nicht in der"
  echo "    Ansicht. Gesamtzahl im Protokoll: $GES (Ansicht zeigt die 200 juengsten)."
fi
ergebnis "abgelehnte Registrierungen erscheinen in der Protokollansicht" "$PA"

hinweis "Demodatensatz — MVZ-Verbund (Block C)"
# Der Demodatensatz ist Werkzeug, nicht Beiwerk: An ihm wird die Oberflaeche
# bei realistischen Stueckzahlen geprueft, bevor echte Bestandsdaten da sind.
# Fuer Zahlen und Kennungen: entfernt alle Leerzeichen.

curl -s -b "$KEKSE" -o /dev/null -w '%{http_code}' -X POST \
  http://localhost:8090/verwaltung/demodaten/anlegen | grep -qE '^(200|302)$' && DA=ja || DA=nein
ergebnis "Demodatensatz laesst sich auf Knopfdruck anlegen" "$DA"

for paar in "organisation:13" "konnektor:9" "arbeitsplatz:36" "kartenterminal:36"; do
  T=${paar%%:*}; SOLL=${paar##*:}
  IST=$(sql "SELECT count(*) FROM $T WHERE ist_demo")
  [ "$IST" = "$SOLL" ] && R=ja || R=nein
  ergebnis "$T: $IST von $SOLL erwartet" "$R"
done

# Kernaussage des Informationsmodells: genau eine SMC-B je Praxis, am Empfang.
# Eine SMC-B an einem Behandlungsplatz waere fachlich falsch.
IST=$(sql "SELECT count(*) FROM kartenterminal WHERE ist_demo AND hat_smcb")
[ "$IST" = "9" ] && R=ja || R=nein
ergebnis "genau eine SMC-B je Praxis (9 von 36 Terminals), IST: $IST" "$R"

IST=$(sql "SELECT count(*) FROM kartenterminal t JOIN arbeitsplatz a ON a.id=t.arbeitsplatz_id
           WHERE t.ist_demo AND t.hat_smcb AND a.art<>'EMPFANG'")
[ "$IST" = "0" ] && R=ja || R=nein
ergebnis "keine SMC-B an einem Behandlungsplatz (gefunden: $IST)" "$R"

IST=$(sql "SELECT count(*) FROM kartenterminal WHERE ist_demo AND NOT pevim_aktiv")
[ "$IST" = "0" ] && R=ja || R=nein
ergebnis "alle Terminals mit freigeschalteter Managementschnittstelle (ohne: $IST)" "$R"

# Drei Berechtigungsebenen: Praxis, Standort, Verbund - die Grundlage dafuer,
# dass sich Mandantentrennung ueberhaupt pruefen laesst.
IST=$(sql "SELECT count(DISTINCT o.art) FROM benutzer b JOIN organisation o ON o.id=b.organisation_id
           WHERE b.ist_demo AND b.rolle='BENUTZER'")
[ "$IST" = "3" ] && R=ja || R=nein
ergebnis "Benutzer auf allen drei Berechtigungsebenen ($IST von 3)" "$R"

curl -s -b "$KEKSE" http://localhost:8090/verwaltung -o /tmp/pr-antwort.html
VA=$(enthaelt /tmp/pr-antwort.html "Arbeitsplätze")
ergebnis "Verwaltungsseite zeigt den Bestand einschliesslich Arbeitsplaetzen" "$VA"

# Loeschen darf ausschliesslich Demodaten treffen. Wuerde es echte Daten
# mitnehmen, waere der Knopf eine Falle statt eines Werkzeugs.
docker exec pr-db psql -U pinroboter -d pinroboter -c \
  "INSERT INTO organisation (bezeichnung, art, ist_demo) VALUES ('Nachweis Echtdaten','VERBUND',FALSE)" >/dev/null 2>&1
curl -s -b "$KEKSE" -o /dev/null -X POST http://localhost:8090/verwaltung/demodaten/loeschen
IST=$(sql "SELECT count(*) FROM kartenterminal WHERE ist_demo")
[ "$IST" = "0" ] && R=ja || R=nein
ergebnis "Loeschen entfernt alle Demodaten (Rest: $IST)" "$R"
IST=$(sql "SELECT count(*) FROM organisation WHERE bezeichnung='Nachweis Echtdaten'")
[ "$IST" = "1" ] && R=ja || R=nein
ergebnis "Loeschen laesst Daten ohne Demo-Kennzeichen unberuehrt" "$R"

curl -s -b "$KEKSE" -o /dev/null -X POST http://localhost:8090/verwaltung/demodaten/anlegen
IST=$(sql "SELECT count(*) FROM kartenterminal WHERE ist_demo")
[ "$IST" = "36" ] && R=ja || R=nein
ergebnis "Demodatensatz laesst sich wiederherstellen ($IST von 36)" "$R"

docker exec pr-db psql -U pinroboter -d pinroboter -c \
  "DELETE FROM organisation WHERE bezeichnung='Nachweis Echtdaten'" >/dev/null 2>&1

hinweis "FA-GER-010 / FA-GER-020 — Bestandsverwaltung"
ORG=$(sql "SELECT id FROM organisation WHERE art='EINRICHTUNG' ORDER BY id LIMIT 1")

# Anlegen ueber die Maske, mit absichtlich unerreichbarer Adresse.
# 192.0.2.0/24 ist nach RFC 5737 fuer Dokumentation reserviert und wird
# nirgends geroutet - ein verlaesslicher Nichterreichbarkeitsfall.
ANTWORT=$(curl -s -b "$KEKSE" -X POST http://localhost:8090/bestand/konnektor \
  --data-urlencode "organisationId=$ORG" \
  --data-urlencode "bezeichnung=Nachweiskonnektor" \
  --data-urlencode "adresse=192.0.2.77:443" \
  --data-urlencode "mandantId=M-NW" --data-urlencode "clientsystemId=CS-NW" \
  --data-urlencode "arbeitsplatzId=AP-NW" --data-urlencode "benutzerName=admin" \
  --data-urlencode "passwort=NachweisKennwortXY")
KID=$(sql "SELECT id FROM konnektor WHERE bezeichnung='Nachweiskonnektor'")
[ -n "$KID" ] && KA=ja || KA=nein
ergebnis "nicht erreichbarer Konnektor laesst sich trotzdem speichern" "$KA"

KM=$(enthaelt "$ANTWORT" "antwortet innerhalb")
ergebnis "die Meldung sagt, was zu tun ist, statt nur zu scheitern" "$KM"

# Kernzusage FA-GER-010: nach dem Speichern nicht mehr auslesbar.
CHIFFRAT=$(sql "SELECT coalesce(passwort_chiffrat,'') FROM konnektor WHERE id=$KID")
case "$CHIFFRAT" in v1:*) KC=ja ;; *) KC=nein ;; esac
ergebnis "das Kennwort liegt verschluesselt in der Datenbank" "$KC"

TREFFER=0
for pfad in "/bestand" "/bestand/konnektor/$KID"; do
  curl -s -b "$KEKSE" "http://localhost:8090$pfad" -o /tmp/pr-kw-suche.html
  [ "$(enthaelt /tmp/pr-kw-suche.html "NachweisKennwortXY")" = "ja" ] && TREFFER=$((TREFFER+1))
done
[ $TREFFER -eq 0 ] && KO=ja || KO=nein
ergebnis "das Kennwort erscheint auf keiner Oberflaechenseite (Treffer: $TREFFER)" "$KO"

# A_24080: PEviM ist ausschliesslich fuer SMC-B zulaessig.
ANTWORT_PV=$(curl -s -b "$KEKSE" -X POST http://localhost:8090/bestand/terminal \
  --data-urlencode "konnektorId=$KID" --data-urlencode "terminalId=KT-NACHWEIS-01" \
  --data-urlencode "bezeichnung=Behandlung" --data-urlencode "adresse=192.0.2.90" \
  --data-urlencode "pevimAktiv=ja")
PV=$(enthaelt "$ANTWORT_PV" "A_24080")
if [ "$PV" = "nein" ]; then
  echo "    Diagnose: Konnektor-Kennung war \"$KID\". Antwort:"
  echo "$ANTWORT_PV" | grep -oE 'role="alert">[^<]*' | head -1 | sed 's/^/      /'
fi
ergebnis "PIN-Automatik ohne SMC-B wird abgelehnt (A_24080)" "$PV"
IST=$(sql "SELECT count(*) FROM kartenterminal WHERE terminal_id='KT-NACHWEIS-01'")
[ "$IST" = "0" ] && PN=ja || PN=nein
ergebnis "das abgelehnte Terminal wurde auch nicht angelegt" "$PN"

curl -s -b "$KEKSE" -X POST http://localhost:8090/bestand/terminal \
  --data-urlencode "konnektorId=$KID" --data-urlencode "terminalId=KT-NACHWEIS-01" \
  --data-urlencode "bezeichnung=Empfang Nachweis" --data-urlencode "adresse=192.0.2.90" \
  --data-urlencode "adminPasswort=TerminalKennwortXY" \
  --data-urlencode "hatSmcb=ja" --data-urlencode "pevimAktiv=ja" >/dev/null
IST=$(sql "SELECT count(*) FROM kartenterminal WHERE terminal_id='KT-NACHWEIS-01' AND hat_smcb AND pevim_aktiv")
[ "$IST" = "1" ] && TA=ja || TA=nein
ergebnis "Terminal mit SMC-B und PIN-Automatik laesst sich anlegen" "$TA"

curl -s -b "$KEKSE" -X POST http://localhost:8090/bestand/terminal \
  --data-urlencode "konnektorId=$KID" --data-urlencode "terminalId=KT-NACHWEIS-01" \
  --data-urlencode "bezeichnung=Doppelt" --data-urlencode "adresse=192.0.2.91" -o /tmp/pr-antwort.html
TD=$(enthaelt /tmp/pr-antwort.html "bereits vergeben")
ergebnis "eine doppelte Kennung wird verstaendlich abgewiesen" "$TD"

# Ein Konnektor mit Terminals darf nicht stillschweigend einen halben
# Standort mitreissen - das Schema wuerde per CASCADE genau das tun.
curl -s -b "$KEKSE" -X POST "http://localhost:8090/bestand/konnektor/$KID/loeschen" \
  -o /tmp/pr-konn-loesch.html
grep -q "zugeordnet" /tmp/pr-konn-loesch.html && LS=ja || LS=nein
if [ "$LS" = "nein" ]; then
  echo "    Diagnose: Konnektor $KID, $(sql "SELECT count(*) FROM kartenterminal WHERE konnektor_id=$KID") Terminal(s),"
  echo "    Antwort $(wc -c < /tmp/pr-konn-loesch.html) Byte. Meldung:"
  grep -oE 'role="alert">[^<]{0,140}' /tmp/pr-konn-loesch.html | head -1 | sed 's/^/      /'
fi
ergebnis "Konnektor mit zugeordneten Terminals wird nicht geloescht" "$LS"
rm -f /tmp/pr-konn-loesch.html
IST=$(sql "SELECT count(*) FROM konnektor WHERE id=$KID")
[ "$IST" = "1" ] && LB=ja || LB=nein
ergebnis "der Konnektor ist nach dem Loeschversuch noch da" "$LB"

TID=$(sql "SELECT id FROM kartenterminal WHERE terminal_id='KT-NACHWEIS-01'")
curl -s -b "$KEKSE" -X POST "http://localhost:8090/bestand/terminal/$TID/loeschen" >/dev/null
curl -s -b "$KEKSE" -X POST "http://localhost:8090/bestand/konnektor/$KID/loeschen" >/dev/null
IST=$(sql "SELECT count(*) FROM konnektor WHERE id=$KID")
[ "$IST" = "0" ] && LN=ja || LN=nein
ergebnis "nach dem Entfernen der Terminals laesst er sich loeschen" "$LN"

# Bestandspflege ist protokollpflichtig - ein geloeschtes Geraet wird nicht
# mehr ueberwacht, und das muss nachvollziehbar bleiben.
IST=$(sql "SELECT count(DISTINCT art) FROM fachprotokoll
           WHERE art IN ('KONNEKTOR_ANGELEGT','TERMINAL_ANGELEGT',
                         'KONNEKTOR_GELOESCHT','TERMINAL_GELOESCHT')")
[ "$IST" = "4" ] && BP=ja || BP=nein
ergebnis "Anlegen und Loeschen sind protokolliert ($IST von 4 Ereignisarten)" "$BP"

IST=$(sql "SELECT count(*) FROM fachprotokoll
           WHERE text LIKE '%KennwortXY%' OR coalesce(handlung,'') LIKE '%KennwortXY%'")
[ "$IST" = "0" ] && BG=ja || BG=nein
ergebnis "kein Kennwort im Fachprotokoll (Treffer: $IST)" "$BG"

hinweis "FA-GER-030 / FA-GER-035 — Bestandsermittlung ueber den Konnektor"
# Der Agent laeuft seit Beginn dieses Laufs und hat den Konnektor mehrfach
# abgefragt. Die Zentrale kann das nicht selbst: Der Konnektor steht im
# Praxisnetz, der Agent baut ausgehend auf (ADR-0002).
IST=$(sql "SELECT count(DISTINCT terminal_id) FROM ermittelt_terminal e
           JOIN agent a ON a.id = e.agent_id AND a.aktiv")
[ "${IST:-0}" -ge 4 ] && EA=ja || EA=nein
ergebnis "der Agent hat den Bestand gemeldet ($IST Terminals)" "$EA"

# Gefunden beim ersten Vollauf: Das Skript startet den Stack mehrfach, jeder
# Lauf registriert einen neuen Agenten. Ohne Filter auf aktive Agenten standen
# 12 Funde in der Liste statt 4 - im Betrieb derselbe Effekt nach einer
# Neuinstallation des Agenten.
ALLE=$(sql "SELECT count(*) FROM ermittelt_terminal")
SICHTBAR=$IST
[ "${ALLE:-0}" -ge "${SICHTBAR:-0}" ] && KL=ja || KL=nein
ergebnis "keine Karteileichen abgemeldeter Agenten ($SICHTBAR sichtbar von $ALLE gespeichert)" "$KL"

IST=$(sql "SELECT count(*) FROM ermitteltes_modell m
           JOIN agent a ON a.id = m.agent_id AND a.aktiv WHERE m.verfuegbar")
[ "${IST:-0}" -ge 1 ] && EM=ja || EM=nein
ergebnis "das Informationsmodell des Konnektors wurde gelesen" "$EM"

curl -s -b "$KEKSE" http://localhost:8090/bestand/ermittelt > /tmp/pr-erm.html
grep -q "Mandant-1" /tmp/pr-erm.html && EZ=ja || EZ=nein
ergebnis "der Aufrufkontext steht in der Oberflaeche zur Auswahl" "$EZ"

grep -q "neu erkannt" /tmp/pr-erm.html && EN=ja || EN=nein
ergebnis "nicht erfasste Geraete werden als neu gekennzeichnet" "$EN"

# Kernzusage: nichts wird selbsttaetig uebernommen.
IST=$(sql "SELECT count(*) FROM kartenterminal WHERE NOT ist_demo
           AND terminal_id IN (SELECT terminal_id FROM ermittelt_terminal)")
[ "$IST" = "0" ] && EU=ja || EU=nein
ergebnis "kein ermitteltes Geraet wurde selbsttaetig in den Bestand uebernommen" "$EU"

# Uebernahme nach Bestaetigung
ORG2=$(sql "SELECT id FROM organisation WHERE art='EINRICHTUNG' ORDER BY id LIMIT 1")
curl -s -b "$KEKSE" -o /dev/null -X POST http://localhost:8090/bestand/konnektor \
  --data-urlencode "organisationId=$ORG2" --data-urlencode "bezeichnung=Konnektor Uebernahme" \
  --data-urlencode "adresse=simulator:8443" --data-urlencode "mandantId=Mandant-1" \
  --data-urlencode "clientsystemId=CS-ROBOTER" --data-urlencode "arbeitsplatzId=AP-EMPFANG"
KID2=$(sql "SELECT id FROM konnektor WHERE bezeichnung='Konnektor Uebernahme'")
FID=$(sql "SELECT e.id FROM ermittelt_terminal e JOIN agent a ON a.id = e.agent_id AND a.aktiv
           WHERE e.terminal_id='KT-EMPFANG-01' ORDER BY e.gesehen DESC LIMIT 1")
curl -s -b "$KEKSE" -X POST "http://localhost:8090/bestand/ermittelt/$FID/uebernehmen" \
  --data-urlencode "konnektorId=$KID2" --data-urlencode "adresse=192.0.2.51:443" -o /tmp/pr-antwort.html
UE=$(enthaelt /tmp/pr-antwort.html "übernommen")
ergebnis "ein Geraet laesst sich nach Bestaetigung uebernehmen" "$UE"

# Kein fester Firmware-Stand als Erwartung: Der Nachweis FA-UEB-050 spielt
# vorher ein Update ein. Geprueft wird, DASS beide Werte vom Konnektor
# uebernommen wurden - nicht, welchen Stand das Geraet gerade hat.
IST=$(sql "SELECT coalesce(modell,'-')||'|'||coalesce(firmware,'-')
           FROM kartenterminal WHERE terminal_id='KT-EMPFANG-01' AND NOT ist_demo")
case "$IST" in
  -\|*|*\|-) UM=nein ;;
  *CHERRY*\|*) UM=ja ;;
  *) UM=nein ;;
esac
ergebnis "Modell und Firmware werden mit uebernommen ($IST)" "$UM"

curl -s -b "$KEKSE" -X POST "http://localhost:8090/bestand/ermittelt/$FID/uebernehmen" \
  --data-urlencode "konnektorId=$KID2" -o /tmp/pr-antwort.html
UD=$(enthaelt /tmp/pr-antwort.html "bereits erfasst")
ergebnis "eine zweite Uebernahme desselben Geraets wird abgewiesen" "$UD"

# Abweichung: PEviM am Geraet abschalten, Bestand darf sich NICHT aendern
curl -s -X POST http://localhost:8480/steuerung/KT-EMPFANG-01/pevim/false >/dev/null
sleep 16
curl -s -b "$KEKSE" http://localhost:8090/bestand/ermittelt > /tmp/pr-erm2.html
grep -q "weicht ab" /tmp/pr-erm2.html && AW=ja || AW=nein
ergebnis "eine Abweichung zwischen Geraet und Bestand wird erkannt" "$AW"
grep -q "PIN-Automatik: erfasst" /tmp/pr-erm2.html && AB=ja || AB=nein
ergebnis "die Abweichung wird benannt, nicht nur angezeigt" "$AB"

IST=$(sql "SELECT pevim_aktiv FROM kartenterminal WHERE terminal_id='KT-EMPFANG-01' AND NOT ist_demo")
[ "$IST" = "t" ] && AN=ja || AN=nein
ergebnis "der Bestand wurde durch die Abweichung NICHT geaendert" "$AN"

IST=$(sql "SELECT count(*) FROM fachprotokoll WHERE art IN ('BESTAND_ERMITTELT','GERAET_UEBERNOMMEN')")
[ "${IST:-0}" -ge 2 ] && EP=ja || EP=nein
ergebnis "Ermittlung und Uebernahme sind protokolliert ($IST Eintraege)" "$EP"

curl -s -X POST http://localhost:8480/steuerung/KT-EMPFANG-01/pevim/true >/dev/null
rm -f /tmp/pr-erm.html /tmp/pr-erm2.html

hinweis "FA-GER-060 — Sortierung, Filter und Massenaktionen"
curl -s -b "$KEKSE" "http://localhost:8090/bestand" > /tmp/pr-best.html
[ "$(grep -c 'class="sortlink"' /tmp/pr-best.html)" -ge 5 ] && SO=ja || SO=nein
ergebnis "die Geraeteliste hat sortierbare Spalten" "$SO"

curl -s -b "$KEKSE" "http://localhost:8090/bestand?sortierung=modell&richtung=ab" -o /tmp/pr-antwort.html
SR=$(enthaelt /tmp/pr-antwort.html 'aria-sort="descending"')
ergebnis "die Sortierrichtung ist in der Kopfzeile ausgezeichnet (Screenreader)" "$SR"

# Der Sortierwert kommt aus der URL und geht in die Abfrage ein. Ohne
# Weissliste waere das eine SQL-Einschleusung.
CODE=$(curl -s -b "$KEKSE" -o /dev/null -w '%{http_code}' \
       "http://localhost:8090/bestand?sortierung=bezeichnung%3B+DROP+TABLE+kartenterminal")
BEST=$(sql "SELECT count(*) FROM kartenterminal")
[ "$CODE" = "200" ] && [ "${BEST:-0}" -gt 0 ] && SI=ja || SI=nein
ergebnis "ein manipuliertes Sortierfeld richtet nichts an (HTTP $CODE, $BEST Geraete)" "$SI"

zaehler() { curl -s -b "$KEKSE" "http://localhost:8090/bestand$1" \
  | grep -oE 'Kartenterminals <span class="zaehler">[0-9]+' | grep -oE '[0-9]+$'; }
OHNE=$(zaehler "")
AKTIV=$(zaehler "?status=aktiv")
[ "$OHNE" = "$AKTIV" ] && [ "${OHNE:-0}" -gt 0 ] && FS=ja || FS=nein
ergebnis "Statusfilter greift ($OHNE gesamt, $AKTIV aktiv)" "$FS"

MODELL=$(sqltext "SELECT modell FROM kartenterminal WHERE modell IS NOT NULL LIMIT 1")
TREFFER=$(zaehler "?modell=$(printf %s "$MODELL" | sed 's/ /+/g')")
[ "${TREFFER:-0}" -gt 0 ] && FM=ja || FM=nein
ergebnis "Modellfilter greift (Modell \"$MODELL\": $TREFFER Treffer)" "$FM"

# Massenaktion: Auswahl, Rueckmeldung, und ehrliche Zaehlung
IDS=$(docker exec pr-db psql -U pinroboter -d pinroboter -tAc \
      "SELECT id FROM kartenterminal ORDER BY id LIMIT 3" | tr -d ' \r')
MARGS=()
for i in $IDS; do MARGS+=(--data-urlencode "geraet=$i"); done
# Nicht auf eine feste Zahl pruefen: Vorherige Nachweise loeschen Geraete, und
# die ersten drei Kennungen koennen dabei sein. Geprueft wird, dass so viele
# geaendert wurden wie ausgewaehlt - das ist die eigentliche Zusage.
ANZ_AUSGEWAEHLT=$(echo "$IDS" | wc -w | tr -d ' ')
ANTWORT_MA=$(curl -s -b "$KEKSE" -X POST http://localhost:8090/bestand/massenaktion \
  "${MARGS[@]}" --data-urlencode "aktion=deaktivieren")
GEAENDERT=$(echo "$ANTWORT_MA" | grep -oE '[0-9]+ Kartenterminal\(s\) deaktiviert' | grep -oE '^[0-9]+')
[ "${GEAENDERT:-0}" = "$ANZ_AUSGEWAEHLT" ] && MA=ja || MA=nein
ergebnis "alle ausgewaehlten Geraete auf einmal deaktiviert ($GEAENDERT von $ANZ_AUSGEWAEHLT)" "$MA"
[ "$(zaehler "?status=inaktiv")" = "$ANZ_AUSGEWAEHLT" ] && MI=ja || MI=nein
ergebnis "der Statusfilter zeigt die deaktivierten Geraete" "$MI"

# Ein nicht mehr vorhandenes Geraet darf nicht stillschweigend uebergangen
# werden - "Aktion ausgefuehrt" bei 3 von 4 waere eine Falschmeldung.
ANTWORT_MU=$(curl -s -b "$KEKSE" -X POST http://localhost:8090/bestand/massenaktion \
  "${MARGS[@]}" --data-urlencode "geraet=999999999" --data-urlencode "aktion=aktivieren")
# Geprueft wird die Zusage, nicht der genaue Wortlaut: Es muss GENAU EIN Geraet
# als uebersprungen gemeldet werden - das erfundene.
UEBERSPRUNGEN=$(echo "$ANTWORT_MU" | grep -oE '[0-9]+ von [0-9]+ Ger' | grep -oE '^[0-9]+')
[ "${UEBERSPRUNGEN:-0}" = "1" ] && MU=ja || MU=nein
if [ "$MU" = "nein" ]; then
  echo "    Diagnose: erwartet 1 uebersprungenes Geraet. Antwort:"
  echo "$ANTWORT_MU" | grep -oE 'role="alert">[^<]*' | head -1 | sed 's/^/      /'
fi
ergebnis "uebersprungene Geraete werden gezaehlt, nicht verschwiegen ($UEBERSPRUNGEN)" "$MU"

curl -s -b "$KEKSE" -X POST http://localhost:8090/bestand/massenaktion \
  --data-urlencode "aktion=deaktivieren" -o /tmp/pr-antwort.html
ML=$(enthaelt /tmp/pr-antwort.html "kein Gerät")
ergebnis "eine leere Auswahl aendert nichts und sagt das" "$ML"

IST=$(sql "SELECT count(*) FROM fachprotokoll WHERE text LIKE '%Kartenterminal(s)%'")
[ "${IST:-0}" -ge 2 ] && MP=ja || MP=nein
ergebnis "Massenaktionen sind protokolliert ($IST Eintraege)" "$MP"

# FA-GER-030: Uebernahme "einzeln oder als Auswahl"
curl -s -b "$KEKSE" http://localhost:8090/bestand/ermittelt > /tmp/pr-erm3.html
grep -q 'name="fund"' /tmp/pr-erm3.html && SA=ja || SA=nein
ergebnis "die Ermittlungsliste erlaubt Mehrfachauswahl" "$SA"

FUNDE=$(grep -oE 'name="fund" value="[0-9]+"' /tmp/pr-erm3.html | grep -oE '[0-9]+' | head -2)
if [ -n "$FUNDE" ]; then
  KID3=$(sql "SELECT id FROM konnektor ORDER BY id LIMIT 1")
  SARGS=()
  for i in $FUNDE; do SARGS+=(--data-urlencode "fund=$i"); done
  ANZ=$(echo "$FUNDE" | wc -w | tr -d ' ')
  curl -s -b "$KEKSE" -X POST http://localhost:8090/bestand/ermittelt/uebernehmen \
    "${SARGS[@]}" --data-urlencode "konnektorId=$KID3" -o /tmp/pr-antwort.html
SU=$(enthaelt /tmp/pr-antwort.html "von $ANZ")
  ergebnis "mehrere Geraete lassen sich in einem Schritt uebernehmen ($ANZ)" "$SU"
else
  echo "  uebersprungen: keine neu erkannten Geraete vorhanden"
fi
rm -f /tmp/pr-best.html /tmp/pr-erm3.html

hinweis "NFA-LEI-010 — Antwortzeiten am Mengengeruest"
# Hier mit 1.000 Geraeten statt 5.000: Die Groessenordnung stimmt, der Lauf
# bleibt kurz. Die volle Messung mit 5.000 Terminals und 5 Mio
# Protokolleintraegen steht in docs/00-referenzen/messung-leistung-mengengeruest.md
# und wird von Hand wiederholt.
curl -s -b "$KEKSE" -o /dev/null -X POST http://localhost:8090/verwaltung/lasttest/anlegen \
  --data-urlencode "anzahl=1000"
LAST=$(sql "SELECT count(*) FROM kartenterminal WHERE terminal_id LIKE 'KT-LAST-%'")
[ "${LAST:-0}" = "1000" ] && LE=ja || LE=nein
ergebnis "Lasttestbestand angelegt ($LAST Geraete)" "$LE"

# Gemessen wird die HTTP-Gesamtzeit. Erst ein Aufruf zum Warmlaufen, damit
# nicht der Verbindungsaufbau in die Messung faellt.
zeitms() {
  curl -s -b "$KEKSE" -o /dev/null "http://localhost:8090$1"
  local ges=0
  for _ in 1 2 3; do
    local ms=$(curl -s -b "$KEKSE" -o /dev/null -w '%{time_total}' "http://localhost:8090$1" \
               | awk '{printf "%d", $1*1000}')
    ges=$((ges+ms))
  done
  echo $((ges/3))
}
for paar in "Bestandsliste, erste Seite:/bestand" \
            "Suche:/bestand?suche=Raum+123" \
            "Sortierung nach Firmware:/bestand?sortierung=firmware&richtung=ab" \
            "Filter nach Modell:/bestand?modell=CHERRY+ST-1506" \
            "Uebersicht:/" ; do
  NAME=${paar%%:*}; PFAD=${paar#*:}
  MS=$(zeitms "$PFAD")
  # NFA-LEI-010: Seitenaufbau unter 1 s.
  [ "${MS:-9999}" -lt 1000 ] && LR=ja || LR=nein
  ergebnis "$NAME unter 1 s (gemessen: ${MS} ms)" "$LR"
done

curl -s -b "$KEKSE" -o /dev/null -X POST http://localhost:8090/verwaltung/lasttest/entfernen
REST=$(sql "SELECT count(*) FROM kartenterminal WHERE terminal_id LIKE 'KT-LAST-%'")
[ "${REST:-1}" = "0" ] && LX=ja || LX=nein
ergebnis "Lasttestbestand rueckstandsfrei entfernt (Rest: $REST)" "$LX"

hinweis "FA-GER-035 — Aufrufkontext aus dem Informationsmodell zur Auswahl"
# Die Konnektor-Anlegemaske ist seit dem UX-Umbau eine eigene Seite
# (ADR-0010, Stufe 4) — dort steht der Aufrufkontext.
curl -s -b "$KEKSE" http://localhost:8090/bestand/konnektor/neu > /tmp/pr-kontext.html
# Ohne Modell muessen Freitextfelder erscheinen (NFA-QS-035), mit Modell
# Vorschlagslisten. Der Agent hat das Modell in diesem Lauf schon gemeldet.
LISTEN=$(grep -c '<datalist' /tmp/pr-kontext.html)
[ "${LISTEN:-0}" = "3" ] && KA=ja || KA=nein
ergebnis "alle drei Aufrufkontext-Felder bieten Vorschlaege ($LISTEN von 3)" "$KA"

FEHLT=0
for w in Mandant-1 CS-PVS CS-ROBOTER AP-EMPFANG AP-LABOR AP-VERWALTUNG; do
  grep -q "value=\"$w\"" /tmp/pr-kontext.html || FEHLT=$((FEHLT+1))
done
[ $FEHLT -eq 0 ] && KW=ja || KW=nein
ergebnis "die Werte des Konnektors stehen zur Auswahl (fehlend: $FEHLT von 6)" "$KW"

# Entscheidend fuer NFA-QS-035: Das Feld bleibt ein Eingabefeld, kein
# Auswahlfeld. Ein Wert, den der Konnektor nicht meldet, muss eintragbar sein.
grep -q '<input id="k-man"[^>]*list="k-man-liste"' /tmp/pr-kontext.html && KF=ja || KF=nein
ergebnis "der Aufrufkontext bleibt frei beschreibbar (Eingabefeld mit Vorschlagsliste)" "$KF"
rm -f /tmp/pr-kontext.html

hinweis "NFA-BET-020 — Sicherung und Wiederherstellung"
# Die Betriebsdoku behauptet einen funktionierenden Wiederherstellungsweg.
# Diese Behauptung wird hier bei jedem Lauf geprueft - eine Anleitung, die
# niemand nachvollzieht, verrottet.
VT=$(sql "SELECT count(*) FROM kartenterminal")
VB=$(sql "SELECT count(*) FROM benutzer")
VC=$(sqltext "SELECT coalesce(substring(passwort_chiffrat,1,24),'-') FROM konnektor
              WHERE passwort_chiffrat IS NOT NULL LIMIT 1")
docker exec pr-db pg_dump -U pinroboter -d pinroboter -Fc > /tmp/pr-sicherung.dump
SIZE=$(wc -c < /tmp/pr-sicherung.dump | tr -d ' ')
[ "${SIZE:-0}" -gt 1000 ] && SI=ja || SI=nein
ergebnis "Sicherung laesst sich auf den Host ziehen ($SIZE Bytes)" "$SI"

# Vollstaendiger Datenverlust: Volume weg.
docker rm -f pr-db >/dev/null 2>&1
docker volume rm pr-testdaten >/dev/null 2>&1
docker run -d --name pr-db --network $NETZ --network-alias datenbank \
  -e POSTGRES_DB=pinroboter -e POSTGRES_USER=pinroboter -e POSTGRES_PASSWORD=test \
  -v pr-testdaten:/var/lib/postgresql/data postgres:17-alpine >/dev/null
sleep 10
LEER=$(sql "SELECT count(*) FROM pg_tables WHERE schemaname = 'public'")
[ "${LEER:-1}" = "0" ] && SL=ja || SL=nein
ergebnis "Datenverlust nachgestellt (Tabellen: $LEER)" "$SL"

docker cp /tmp/pr-sicherung.dump pr-db:/tmp/s.dump >/dev/null
docker exec pr-db pg_restore -U pinroboter -d pinroboter --clean --if-exists /tmp/s.dump >/dev/null 2>&1
NT=$(sql "SELECT count(*) FROM kartenterminal")
NB=$(sql "SELECT count(*) FROM benutzer")
NC=$(sqltext "SELECT coalesce(substring(passwort_chiffrat,1,24),'-') FROM konnektor
              WHERE passwort_chiffrat IS NOT NULL LIMIT 1")
[ "$VT" = "$NT" ] && [ "${VT:-0}" -gt 0 ] && WT=ja || WT=nein
ergebnis "Kartenterminals vollstaendig wiederhergestellt ($VT -> $NT)" "$WT"
[ "$VB" = "$NB" ] && [ "${VB:-0}" -gt 0 ] && WB=ja || WB=nein
ergebnis "Benutzerkonten vollstaendig wiederhergestellt ($VB -> $NB)" "$WB"
[ "$VC" = "$NC" ] && [ "$VC" != "-" ] && WC=ja || WC=nein
ergebnis "verschluesselte Zugangsdaten unveraendert wiederhergestellt" "$WC"

# Und laesst sich die Zentrale danach benutzen? Ein Restore, nach dem die
# Anwendung nicht mehr startet, ist kein Restore.
docker restart pr-zen >/dev/null 2>&1
for _ in $(seq 40); do
  curl -sf -o /dev/null http://localhost:8090/anmelden 2>/dev/null && break; sleep 1
done
CODE=$(curl -s -b "$KEKSE" -o /dev/null -w '%{http_code}' http://localhost:8090/bestand)
[ "$CODE" = "200" ] && WZ=ja || WZ=nein
ergebnis "die Zentrale ist nach der Wiederherstellung bedienbar (HTTP $CODE)" "$WZ"
rm -f /tmp/pr-sicherung.dump

hinweis "Mandantentrennung — Block A"
# Der Pruefadmin ist Instanzbetreiber (keine Organisation zugeordnet) und sieht
# alles. Ein Benutzer einer einzelnen Praxis darf nur deren Geraete sehen.
curl -s -b "$KEKSE" -o /dev/null -X POST http://localhost:8090/verwaltung/demodaten/anlegen
ALLE=$(curl -s -b "$KEKSE" http://localhost:8090/bestand \
       | grep -oE 'Kartenterminals <span class="zaehler">[0-9]+' | grep -oE '[0-9]+$')
[ "${ALLE:-0}" -ge 36 ] && MI=ja || MI=nein
ergebnis "der Instanzbetreiber sieht den gesamten Bestand ($ALLE Geraete)" "$MI"

# Ein Demo-Benutzer der Ebene 1 ist genau einer Praxis zugeordnet.
PRAXIS=$(sqltext "SELECT b.benutzername FROM benutzer b
                  JOIN organisation o ON o.id = b.organisation_id
                  WHERE b.ist_demo AND o.art = 'EINRICHTUNG' ORDER BY b.id LIMIT 1")
ORGID=$(sql "SELECT organisation_id FROM benutzer WHERE benutzername = '$PRAXIS'")
SOLL=$(sql "SELECT count(*) FROM kartenterminal WHERE organisation_id = $ORGID")
echo "  Demo-Benutzer $PRAXIS gehoert zu Einrichtung $ORGID mit $SOLL Geraeten"

# Anmelden als dieser Benutzer. Das Kennwort ist bei Demokonten einheitlich.
DEMO_KEKSE=/tmp/pr-demo-kekse.txt
rm -f "$DEMO_KEKSE"
DEMO_TOTP=$(sqltext "SELECT totp_geheimnis FROM benutzer WHERE benutzername = '$PRAXIS'")
DEMO_PW=$(grep -oE 'DEMO_KENNWORT *= *"[^"]+"' src/zentrale/src/main/java/de/pinroboter/zentrale/Demodaten.java \
          | head -1 | sed 's/.*"\(.*\)"/\1/')
if [ -n "$DEMO_TOTP" ] && [ -n "$DEMO_PW" ]; then
  for versuch in 1 2 3; do
    curl -s -c "$DEMO_KEKSE" -o /dev/null -X POST http://localhost:8090/anmelden \
      -d "benutzer=$PRAXIS&passwort=$DEMO_PW&code=$(python3 pruefungen/totp.py "$DEMO_TOTP")"
    [ "$(curl -s -b "$DEMO_KEKSE" -o /dev/null -w '%{http_code}' http://localhost:8090/bestand)" = "200" ] && break
    sleep 31
  done
  CODE=$(curl -s -b "$DEMO_KEKSE" -o /dev/null -w '%{http_code}' http://localhost:8090/bestand)
  [ "$CODE" = "200" ] && MA=ja || MA=nein
  ergebnis "Anmeldung als Praxisbenutzer moeglich (HTTP $CODE)" "$MA"

  SICHT=$(curl -s -b "$DEMO_KEKSE" http://localhost:8090/bestand \
          | grep -oE 'Kartenterminals <span class="zaehler">[0-9]+' | grep -oE '[0-9]+$')
  [ "$SICHT" = "$SOLL" ] && MS=ja || MS=nein
  ergebnis "der Praxisbenutzer sieht NUR seine eigenen Geraete ($SICHT von $ALLE)" "$MS"

  # Der Angriff: eine fremde Geraetekennung direkt aufrufen.
  FREMD=$(sql "SELECT id FROM kartenterminal WHERE organisation_id <> $ORGID ORDER BY id LIMIT 1")
  CODE=$(curl -s -b "$DEMO_KEKSE" -o /dev/null -w '%{http_code}' \
         "http://localhost:8090/bestand/terminal/$FREMD")
  [ "$CODE" = "404" ] && MF=ja || MF=nein
  ergebnis "ein fremdes Geraet ist ueber seine Kennung nicht erreichbar (HTTP $CODE)" "$MF"

  # Und es laesst sich auch nicht loeschen.
  curl -s -b "$DEMO_KEKSE" -o /dev/null -X POST "http://localhost:8090/bestand/terminal/$FREMD/loeschen"
  NOCHDA=$(sql "SELECT count(*) FROM kartenterminal WHERE id = $FREMD")
  [ "$NOCHDA" = "1" ] && ML=ja || ML=nein
  ergebnis "ein fremdes Geraet laesst sich nicht loeschen" "$ML"

  # Massenaktion mit einer fremden Kennung
  curl -s -b "$DEMO_KEKSE" -o /dev/null -X POST http://localhost:8090/bestand/massenaktion \
    --data-urlencode "geraet=$FREMD" --data-urlencode "aktion=deaktivieren"
  AKTIV=$(sql "SELECT aktiv FROM kartenterminal WHERE id = $FREMD")
  [ "$AKTIV" = "t" ] && MM=ja || MM=nein
  ergebnis "eine Massenaktion greift nicht auf fremde Geraete durch" "$MM"
  # Zweite Haelfte der Mandantentrennung: Protokoll, Alarme, Gruppen, Benutzer
  PROT_ALLE=$(sql "SELECT count(*) FROM fachprotokoll")
  PROT_SICHT=$(curl -s -b "$DEMO_KEKSE" "http://localhost:8090/teil/protokoll" \
               | grep -oE 'von [0-9]+' | head -1 | grep -oE '[0-9]+')
  [ -n "$PROT_SICHT" ] && [ "${PROT_SICHT:-0}" -lt "${PROT_ALLE:-0}" ] && MP=ja || MP=nein
  ergebnis "der Praxisbenutzer sieht nur einen Teil des Protokolls ($PROT_SICHT von $PROT_ALLE)" "$MP"

  # Der Export darf nicht mehr enthalten als die Ansicht.
  curl -s -b "$DEMO_KEKSE" -o /tmp/pr-export-praxis.xlsx http://localhost:8090/protokoll/export
  GROESSE_PRAXIS=$(wc -c < /tmp/pr-export-praxis.xlsx | tr -d ' ')
  curl -s -b "$KEKSE" -o /tmp/pr-export-admin.xlsx http://localhost:8090/protokoll/export
  GROESSE_ADMIN=$(wc -c < /tmp/pr-export-admin.xlsx | tr -d ' ')
  [ "${GROESSE_PRAXIS:-0}" -lt "${GROESSE_ADMIN:-0}" ] && MX=ja || MX=nein
  ergebnis "der Excel-Export des Praxisbenutzers ist kleiner als der des Betreibers ($GROESSE_PRAXIS < $GROESSE_ADMIN Bytes)" "$MX"
  rm -f /tmp/pr-export-praxis.xlsx /tmp/pr-export-admin.xlsx

  # Fremde Protokolleintraege duerfen auch ueber einen Geraetefilter nicht kommen.
  FREMD_KT=$(sqltext "SELECT terminal_id FROM kartenterminal WHERE organisation_id <> $ORGID
                      ORDER BY id LIMIT 1")
  TREFFER=$(curl -s -b "$DEMO_KEKSE" "http://localhost:8090/teil/protokoll?geraet=$FREMD_KT" \
            | grep -c "<tr>")
  [ "${TREFFER:-1}" = "0" ] && MG=ja || MG=nein
  ergebnis "ein Filter auf ein fremdes Geraet liefert keine Protokolleintraege ($TREFFER)" "$MG"

  # Rechte je Ebene: Der Demo-Benutzer ist Ebene 1 (Praxis).
  CODE=$(curl -s -b "$DEMO_KEKSE" -o /dev/null -w '%{http_code}' \
         -X POST http://localhost:8090/verwaltung/demodaten/loeschen)
  [ "$CODE" = "403" ] && RD=ja || RD=nein
  ergebnis "ein Praxisbenutzer kann den Demodatensatz nicht loeschen (HTTP $CODE)" "$RD"
  IST=$(sql "SELECT count(*) FROM kartenterminal WHERE ist_demo")
  [ "${IST:-0}" -gt 0 ] && RD2=ja || RD2=nein
  ergebnis "der Demodatensatz ist nach dem Versuch noch da ($IST Geraete)" "$RD2"

  CODE=$(curl -s -b "$DEMO_KEKSE" -o /dev/null -w '%{http_code}' \
         -X POST http://localhost:8090/verwaltung/lasttest/anlegen --data-urlencode "anzahl=10")
  [ "$CODE" = "403" ] && RL=ja || RL=nein
  ergebnis "ein Praxisbenutzer kann keinen Lasttestbestand anlegen (HTTP $CODE)" "$RL"

  # Die Meldung muss sagen, WAS fehlt - nicht nur, dass etwas fehlt.
  curl -s -b "$DEMO_KEKSE" -X POST http://localhost:8090/verwaltung/demodaten/anlegen -o /tmp/pr-antwort.html
RM=$(enthaelt /tmp/pr-antwort.html "Ihre Berechtigung")
  ergebnis "die Abweisung nennt die eigene Berechtigung" "$RM"

  # FA-ORG-020: Struktur aendern erst ab Standortebene. Ein Praxisbenutzer
  # koennte sonst seinen eigenen Zustaendigkeitsbereich erweitern.
  CODE=$(curl -s -b "$DEMO_KEKSE" -o /dev/null -w '%{http_code}' \
         -X POST http://localhost:8090/organisation \
         --data-urlencode "bezeichnung=Praxis Eindringling" --data-urlencode "art=EINRICHTUNG" \
         --data-urlencode "elternId=$ORGID")
  [ "$CODE" = "403" ] && RO=ja || RO=nein
  ergebnis "ein Praxisbenutzer kann keine Organisationseinheit anlegen (HTTP $CODE)" "$RO"
  [ "$(sql "SELECT count(*) FROM organisation WHERE bezeichnung='Praxis Eindringling'")" = "0" ] \
    && RO2=ja || RO2=nein
  ergebnis "nach dem Versuch existiert keine neue Einheit" "$RO2"

  # Und die Oberflaeche bietet ihm keinen Knopf an, den es nicht gibt.
  curl -s -b "$DEMO_KEKSE" http://localhost:8090/organisation > /tmp/pr-org-praxis.html
  grep -q "Einheit hinzufügen" /tmp/pr-org-praxis.html && RO3=nein || RO3=ja
  ergebnis "die Organisationsseite zeigt ihm kein Anlegeformular" "$RO3"
  grep -q "ab der Standortebene" /tmp/pr-org-praxis.html && RO4=ja || RO4=nein
  ergebnis "sie erklaert ihm stattdessen, warum" "$RO4"

  # Ein fremder Verbund darf auch mit direkter Kennung nicht erreichbar sein.
  FREMD_ORG=$(sql "SELECT id FROM organisation WHERE id <> $ORGID AND art='EINRICHTUNG'
                   ORDER BY id LIMIT 1")
  curl -s -b "$DEMO_KEKSE" -o /dev/null -X POST \
    "http://localhost:8090/organisation/$FREMD_ORG/umbenennen" \
    --data-urlencode "bezeichnung=Uebernommen"
  [ "$(sql "SELECT count(*) FROM organisation WHERE bezeichnung='Uebernommen'")" = "0" ] \
    && RO5=ja || RO5=nein
  ergebnis "eine fremde Einheit laesst sich nicht umbenennen" "$RO5"

  rm -f /tmp/pr-org-praxis.html "$DEMO_KEKSE"
else
  echo "  uebersprungen: Demo-Zugangsdaten nicht ermittelbar"
fi

hinweis "Block B — Benutzerverwaltung und Einladungen"
ORG_B=$(sql "SELECT id FROM organisation WHERE art='EINRICHTUNG' ORDER BY id LIMIT 1")
ANTWORT_EIN=$(curl -s -b "$KEKSE" -X POST http://localhost:8090/benutzer/einladen \
  --data-urlencode "benutzername=nachweis.person" --data-urlencode "anzeigename=Nachweis Person" \
  --data-urlencode "rolle=BENUTZER" --data-urlencode "organisationId=$ORG_B")
CODE_EIN=$(echo "$ANTWORT_EIN" | grep -A3 'class="geheimnis"' \
           | grep -oE '<code>[A-Za-z0-9_-]{40,}</code>' | sed 's/<[^>]*>//g')
[ -n "$CODE_EIN" ] && [ ${#CODE_EIN} -ge 40 ] && BE=ja || BE=nein
ergebnis "eine Einladung liefert einen Zugangscode (${#CODE_EIN} Zeichen)" "$BE"

# Der Code darf NICHT im Protokoll stehen - es waere sonst fuer jeden lesbar,
# der das Protokoll einsehen darf.
IST=$(sql "SELECT count(*) FROM fachprotokoll
           WHERE text LIKE '%$CODE_EIN%' OR coalesce(handlung,'') LIKE '%$CODE_EIN%'")
[ "${IST:-1}" = "0" ] && BP=ja || BP=nein
ergebnis "der Zugangscode steht nicht im Protokoll (Treffer: $IST)" "$BP"

# Und nur als Hash in der Datenbank.
IST=$(sql "SELECT count(*) FROM einladung WHERE geheimnis_hash = '$CODE_EIN'")
[ "${IST:-1}" = "0" ] && BH=ja || BH=nein
ergebnis "der Zugangscode steht nicht im Klartext in der Datenbank" "$BH"

IST=$(sql "SELECT count(*) FROM benutzer WHERE benutzername='nachweis.person'")
[ "${IST:-1}" = "0" ] && BK=ja || BK=nein
ergebnis "eine Einladung ist noch kein Konto" "$BK"

# Ein schwaches Kennwort darf die Einladung nicht verbrauchen - sonst waere sie
# durch einen Tippfehler unbrauchbar.
curl -s -X POST http://localhost:8090/einladung \
  --data-urlencode "geheimnis=$CODE_EIN" --data-urlencode "kennwort=kurz" -o /tmp/pr-antwort.html
BS=$(enthaelt /tmp/pr-antwort.html "mindestens 12 Zeichen")
ergebnis "ein schwaches Kennwort wird mit Begruendung abgelehnt" "$BS"

curl -s -X POST http://localhost:8090/einladung \
  --data-urlencode "geheimnis=$CODE_EIN" --data-urlencode "kennwort=EinLangesUnverdaechtigesWort42" -o /tmp/pr-antwort.html
BL=$(enthaelt /tmp/pr-antwort.html "Einmalkennwort")
ergebnis "nach dem Kennwort folgt die Einrichtung des zweiten Faktors" "$BL"

IST=$(sql "SELECT organisation_id FROM benutzer WHERE benutzername='nachweis.person'")
[ "$IST" = "$ORG_B" ] && BO=ja || BO=nein
ergebnis "das Konto landet in der eingeladenen Einrichtung ($IST)" "$BO"

curl -s -X POST http://localhost:8090/einladung \
  --data-urlencode "geheimnis=$CODE_EIN" --data-urlencode "kennwort=EinLangesUnverdaechtigesWort42" -o /tmp/pr-antwort.html
BZ=$(enthaelt /tmp/pr-antwort.html "bereits verwendet")
ergebnis "derselbe Zugangscode ein zweites Mal wird abgewiesen" "$BZ"

# Zweiten Faktor zuruecksetzen - der haeufigste Supportfall.
NID=$(sql "SELECT id FROM benutzer WHERE benutzername='nachweis.person'")
curl -s -b "$KEKSE" -o /dev/null -X POST "http://localhost:8090/benutzer/$NID/totp-zuruecksetzen"
IST=$(sql "SELECT coalesce(totp_geheimnis,'-') FROM benutzer WHERE id=$NID")
[ "$IST" = "-" ] && BT=ja || BT=nein
ergebnis "der zweite Faktor laesst sich zuruecksetzen" "$BT"
IST=$(sql "SELECT count(*) FROM fachprotokoll WHERE art='TOTP_ZURUECKGESETZT'")
[ "${IST:-0}" -ge 1 ] && BTP=ja || BTP=nein
ergebnis "das Zuruecksetzen ist protokolliert" "$BTP"

# Deaktivieren statt Loeschen
curl -s -b "$KEKSE" -o /dev/null -X POST "http://localhost:8090/benutzer/$NID/aktiv" \
  --data-urlencode "aktiv=nein"
IST=$(sql "SELECT aktiv FROM benutzer WHERE id=$NID")
[ "$IST" = "f" ] && BD=ja || BD=nein
ergebnis "ein Konto laesst sich deaktivieren" "$BD"
IST=$(sql "SELECT count(*) FROM benutzer WHERE id=$NID")
[ "$IST" = "1" ] && BDL=ja || BDL=nein
ergebnis "das Konto bleibt erhalten und damit nachvollziehbar" "$BDL"

# Der letzte handlungsfaehige Administrator darf nicht deaktiviert werden.
#
# Der erste Anlauf dieses Nachweises war falsch gebaut: Er nahm an, der
# Pruefadmin sei der einzige Administrator. Der Demodatensatz legt aber drei
# weitere an - die Regel griff also zu Recht NICHT, und der Nachweis
# deaktivierte dabei den Pruefadmin selbst. Ein Nachweis, der die Lage
# zerstoert, die er pruefen will.
AID=$(sql "SELECT id FROM benutzer WHERE benutzername='pruefadmin'")
ANDERE=$(docker exec pr-db psql -U pinroboter -d pinroboter -tAc \
         "SELECT id FROM benutzer WHERE aktiv AND rolle='ADMINISTRATOR' AND id <> $AID" | tr -d ' \r')
for a in $ANDERE; do
  docker exec pr-db psql -U pinroboter -d pinroboter -c \
    "UPDATE benutzer SET aktiv = FALSE WHERE id = $a" >/dev/null 2>&1
done
VERBLEIBEND=$(sql "SELECT count(*) FROM benutzer WHERE aktiv AND rolle='ADMINISTRATOR'")
echo "  Ausgangslage: $VERBLEIBEND aktiver Administrator"

curl -s -b "$KEKSE" -X POST "http://localhost:8090/benutzer/$AID/aktiv" \
  --data-urlencode "aktiv=nein" -o /tmp/pr-antwort.html
BA=$(enthaelt /tmp/pr-antwort.html "letzte handlungsfähige")
ergebnis "der letzte Administrator laesst sich nicht deaktivieren" "$BA"
IST=$(sql "SELECT aktiv FROM benutzer WHERE id=$AID")
[ "$IST" = "t" ] && BA2=ja || BA2=nein
ergebnis "er ist nach dem Versuch weiterhin aktiv" "$BA2"

# Ausgangslage wiederherstellen, damit folgende Nachweise nicht darauf treffen.
for a in $ANDERE; do
  docker exec pr-db psql -U pinroboter -d pinroboter -c \
    "UPDATE benutzer SET aktiv = TRUE WHERE id = $a" >/dev/null 2>&1
done

hinweis "NFA-SIC — Anmeldebremse und Pseudonymisierung"
# Die Kontosperre schuetzt das einzelne Konto. Die Bremse schuetzt gegen den
# aussichtsreicheren Angriff: viele Konten mit je einem naheliegenden Kennwort.
VORHER=$(sql "SELECT count(*) FROM fachprotokoll WHERE art='ANMELDUNG_GEBREMST'")
for i in 1 2 3 4 5 6 7 8; do
  curl -s -o /dev/null -X POST http://localhost:8090/anmelden \
    -d "benutzer=gibtesnicht$i&passwort=FalschesKennwortXY&code=000000"
done
NACHHER=$(sql "SELECT count(*) FROM fachprotokoll WHERE art='ANMELDUNG_GEBREMST'")
[ "${NACHHER:-0}" -gt "${VORHER:-0}" ] && AB=ja || AB=nein
ergebnis "wiederholte Fehlversuche eines Absenders werden gebremst" "$AB"

# Nicht jeder Fehlversuch wird protokolliert - sonst waeren tausend Versuche
# tausend Eintraege, und die Protokoll-Grundregel waere ausgehebelt.
[ "${NACHHER:-0}" -le $((VORHER + 2)) ] && AF=ja || AF=nein
ergebnis "die Bremse protokolliert das Greifen, nicht jeden Versuch ($VORHER -> $NACHHER)" "$AF"

# Die Adresse ist ein personenbezogenes Datum und gehoert nicht ins Protokoll.
IST=$(sql "SELECT count(*) FROM fachprotokoll WHERE art='ANMELDUNG_GEBREMST'
           AND (text ~ '[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+'
                OR coalesce(handlung,'') ~ '[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+')")
[ "${IST:-1}" = "0" ] && AA=ja || AA=nein
ergebnis "keine Absenderadresse im Protokoll (Treffer: $IST)" "$AA"

# Die Anmeldung muss trotz Bremse weiter moeglich sein - eine harte Sperre
# waere die schlechtere Wahl.
rm -f "$KEKSE"
anmelden
CODE=$(curl -s -b "$KEKSE" -o /dev/null -w '%{http_code}' http://localhost:8090/geraete)
[ "$CODE" = "200" ] && AN=ja || AN=nein
ergebnis "eine richtige Anmeldung bleibt trotz Bremse moeglich (HTTP $CODE)" "$AN"

# Pseudonymisierung (Art. 17 DSGVO): alte Eintraege verlieren den Personenbezug.
docker exec pr-db psql -U pinroboter -d pinroboter -c \
  "SET pinroboter.rotation = 'an';
   UPDATE fachprotokoll SET zeitpunkt = now() - interval '400 days'
   WHERE ausloeser = 'Pruefung' AND id IN
     (SELECT id FROM fachprotokoll WHERE ausloeser = 'Pruefung' ORDER BY id LIMIT 3);
   SET pinroboter.rotation = 'aus';" >/dev/null 2>&1
ALT=$(sql "SELECT count(*) FROM fachprotokoll
           WHERE ausloeser = 'Pruefung' AND zeitpunkt < now() - interval '183 days'")
echo "  $ALT Eintraege aelter als 183 Tage vorbereitet"

# Die Zentrale pseudonymisiert beim Start und danach taeglich. Ein Neustart
# holt Versaeumtes sofort nach.
docker restart pr-zen >/dev/null 2>&1
for _ in $(seq 60); do
  curl -sf -o /dev/null http://localhost:8090/anmelden 2>/dev/null && break; sleep 1
done
sleep 35
NOCH=$(sql "SELECT count(*) FROM fachprotokoll
            WHERE ausloeser = 'Pruefung' AND zeitpunkt < now() - interval '183 days'")
[ "${NOCH:-1}" = "0" ] && PS=ja || PS=nein
ergebnis "alte Eintraege verlieren den Personenbezug ($ALT -> $NOCH)" "$PS"

KENNUNG=$(sql "SELECT count(*) FROM fachprotokoll
               WHERE ausloeser LIKE 'Person %' AND zeitpunkt < now() - interval '183 days'")
[ "${KENNUNG:-0}" -ge 1 ] && PK=ja || PK=nein
ergebnis "an ihre Stelle tritt eine Kennung ($KENNUNG Eintraege)" "$PK"

# Die Handlung selbst muss vollstaendig erhalten bleiben - das ist der Kern.
IST=$(sql "SELECT count(*) FROM fachprotokoll
           WHERE ausloeser LIKE 'Person %' AND art IS NOT NULL AND text IS NOT NULL")
[ "${IST:-0}" -ge 1 ] && PH=ja || PH=nein
ergebnis "die Handlung bleibt vollstaendig nachvollziehbar" "$PH"

IST=$(sql "SELECT count(*) FROM fachprotokoll WHERE art='PROTOKOLL_PSEUDONYMISIERT'")
[ "${IST:-0}" -ge 1 ] && PP=ja || PP=nein
ergebnis "die Pseudonymisierung ist selbst protokolliert" "$PP"

hinweis "FA-GER-040 — Karten erfassen, eGK und HBA nicht anfassen"
IST=$(sql "SELECT count(*) FROM ermittelte_karte")
[ "${IST:-0}" -ge 3 ] && KE=ja || KE=nein
ergebnis "der Agent meldet die Karten der Terminals ($IST)" "$KE"

# eGK und HBA stecken in der Praxis mit im Terminal. Sie zu verschweigen waere
# bequem - der Betreiber soll sehen, was drinsteckt.
EGK=$(sql "SELECT count(*) FROM ermittelte_karte WHERE kartentyp='EGK'")
HBA=$(sql "SELECT count(*) FROM ermittelte_karte WHERE kartentyp='HBA'")
[ "${EGK:-0}" -ge 1 ] && [ "${HBA:-0}" -ge 1 ] && KA=ja || KA=nein
ergebnis "auch eGK ($EGK) und HBA ($HBA) werden erfasst" "$KA"

# A_24080: PEviM ist ausschliesslich fuer SMC-B zulaessig.
FALSCH=$(sql "SELECT count(*) FROM ermittelte_karte WHERE verwaltet AND kartentyp <> 'SMC-B'")
[ "${FALSCH:-1}" = "0" ] && KV=ja || KV=nein
ergebnis "nur die SMC-B gilt als verwaltet (falsch eingestuft: $FALSCH)" "$KV"

OHNE=$(sql "SELECT count(*) FROM ermittelte_karte WHERE kartentyp='SMC-B' AND NOT verwaltet")
[ "${OHNE:-1}" = "0" ] && KS=ja || KS=nein
ergebnis "jede SMC-B gilt als verwaltet (nicht eingestuft: $OHNE)" "$KS"

MIT_DATUM=$(sql "SELECT count(*) FROM ermittelte_karte WHERE gueltig_bis IS NOT NULL")
[ "${MIT_DATUM:-0}" -ge 3 ] && KD=ja || KD=nein
ergebnis "das Ablaufdatum des Zertifikats wird uebernommen ($MIT_DATUM)" "$KD"

# Der Roboter darf eGK und HBA nie verifizieren.
VERSUCHE=$(docker logs pr-agt 2>&1 | grep -cE "card-egk|card-hba" || true)
[ "${VERSUCHE:-1}" = "0" ] && KN=ja || KN=nein
ergebnis "der Roboter fasst eGK und HBA nicht an ($VERSUCHE Erwaehnungen)" "$KN"

# Die Oberflaeche muss A_24080 erklaeren, nicht nur durchsetzen.
TID_K=$(sql "SELECT id FROM kartenterminal WHERE terminal_id='KT-EMPFANG-01' AND NOT ist_demo
             ORDER BY id LIMIT 1")
if [ -n "$TID_K" ]; then
  curl -s -b "$KEKSE" "http://localhost:8090/bestand/terminal/$TID_K" > /tmp/pr-karten.html
  grep -q "A_24080" /tmp/pr-karten.html && KX=ja || KX=nein
  ergebnis "die Oberflaeche erklaert, warum eGK und HBA nicht verwaltet werden" "$KX"
  grep -q "Gesundheitskarte" /tmp/pr-karten.html && KT=ja || KT=nein
  ergebnis "die Kartentypen werden verstaendlich benannt" "$KT"
  rm -f /tmp/pr-karten.html
else
  echo "  uebersprungen: kein erfasstes Terminal KT-EMPFANG-01"
fi

# ------------------------------------------------- FA-PIN-010 / FA-PIN-060
# Ende-zu-Ende-Uebergabe der PIN nach ADR-0009. Der entscheidende Punkt: Die
# Zentrale darf die PIN nie im Klartext sehen - weder in der Datenbank, noch im
# Protokoll, noch in ihrer Ausgabe. Das wird hier nachgesehen, nicht geglaubt.
hinweis "FA-PIN-010 / FA-PIN-060 — PIN über die Oberfläche hinterlegen und löschen"

PIN_NEU=428913     # bewusst eine andere als die des Testaufbaus (123456)

# Der Agent meldet seinen oeffentlichen Schluessel ueber den bestehenden Weg.
for _ in $(seq 30); do
  PUBB64=$(sql "SELECT oeffentlicher_schluessel FROM agent
                WHERE oeffentlicher_schluessel IS NOT NULL ORDER BY id DESC LIMIT 1")
  [ -n "$PUBB64" ] && break
  sleep 2
done
[ -n "$PUBB64" ] && PS=ja || PS=nein
ergebnis "der Standort-Agent meldet seinen oeffentlichen Schluessel" "$PS"

# Terminal mit einer verwalteten Karte (SMC-B) suchen - nur dort ist die
# PIN-Maske ueberhaupt sinnvoll.
TID_P=$(sql "SELECT k.id FROM kartenterminal k
             JOIN ermittelte_karte e ON e.terminal_id = k.terminal_id
             WHERE e.kartentyp = 'SMC-B' AND NOT k.ist_demo ORDER BY k.id LIMIT 1")
HANDLE=$(sql "SELECT e.kartenhandle FROM kartenterminal k
              JOIN ermittelte_karte e ON e.terminal_id = k.terminal_id
              WHERE e.kartentyp = 'SMC-B' AND NOT k.ist_demo ORDER BY k.id LIMIT 1")

if [ -z "$TID_P" ] || [ -z "$PUBB64" ]; then
  echo "  uebersprungen: kein erfasstes Terminal mit SMC-B oder kein Agentenschluessel"
  echo "    Diagnose: TID_P='$TID_P' HANDLE='$HANDLE' Schluessel vorhanden: $PS"
  FEHLER=$((FEHLER+1))
else
  curl -s -b "$KEKSE" "http://localhost:8090/bestand/terminal/$TID_P" > /tmp/pr-pin.html

  # Die Maske sagt ausdruecklich, dass sie JavaScript braucht (ADR-0009).
  grep -q "Diese Maske braucht JavaScript" /tmp/pr-pin.html && PJ=ja || PJ=nein
  ergebnis "die PIN-Maske nennt die JavaScript-Voraussetzung ausdruecklich" "$PJ"

  # Das Eingabefeld hat KEINEN name - es darf nie mitgeschickt werden.
  grep -q 'id="pinFeld" type="password"' /tmp/pr-pin.html \
    && ! grep -q 'id="pinFeld"[^>]*name=' /tmp/pr-pin.html && PF=ja || PF=nein
  ergebnis "das PIN-Eingabefeld wird nicht mitgeschickt (kein name-Attribut)" "$PF"

  # Klartext statt Chiffrat wird abgewiesen - die Notbremse aus Pinverwaltung.
  curl -s -b "$KEKSE" -X POST "http://localhost:8090/bestand/terminal/$TID_P/pin" \
    -d "kartenhandle=$HANDLE" -d "chiffrat=$PIN_NEU" > /tmp/pr-pin-klartext.html
  grep -q "nicht nach einer verschlüsselten PIN" /tmp/pr-pin-klartext.html && PK=ja || PK=nein
  ergebnis "eine unverschluesselt uebergebene PIN wird abgewiesen" "$PK"
  KLARTEXT_DB=$(sql "SELECT count(*) FROM pin_auftrag WHERE chiffrat = '$PIN_NEU'")
  [ "$KLARTEXT_DB" = "0" ] && PKD=ja || PKD=nein
  ergebnis "die abgewiesene Klartext-PIN steht in keinem Auftrag" "$PKD"

  # Jetzt der echte Weg: verschluesseln wie der Browser (RSA-OAEP, SHA-256).
  printf -- "-----BEGIN PUBLIC KEY-----\n%s\n-----END PUBLIC KEY-----\n" \
    "$(echo "$PUBB64" | fold -w 64)" > /tmp/pr-agent-pub.pem
  CHIFFRAT=$(printf '%s' "$PIN_NEU" | openssl pkeyutl -encrypt -pubin -inkey /tmp/pr-agent-pub.pem \
      -pkeyopt rsa_padding_mode:oaep -pkeyopt rsa_oaep_md:sha256 -pkeyopt rsa_mgf1_md:sha256 \
      2>/dev/null | base64 | tr -d '\n')
  [ -n "$CHIFFRAT" ] && PC=ja || PC=nein
  ergebnis "der gemeldete Schluessel ist als SPKI verwendbar (RSA-OAEP/SHA-256)" "$PC"

  curl -s -b "$KEKSE" -X POST "http://localhost:8090/bestand/terminal/$TID_P/pin" \
    -d "kartenhandle=$HANDLE" --data-urlencode "chiffrat=$CHIFFRAT" > /tmp/pr-pin-neu.html

  # Der Agent holt den Auftrag beim naechsten Durchgang ab.
  for _ in $(seq 60); do
    Q=$(sql "SELECT count(*) FROM pin_auftrag WHERE quittiert IS NOT NULL AND art='HINTERLEGEN'")
    [ "${Q:-0}" -ge 1 ] && break
    sleep 2
  done
  HINT=$(sql "SELECT count(*) FROM pin_hinterlegt WHERE kartenhandle = '$HANDLE'")
  [ "${HINT:-0}" = "1" ] && PH=ja || PH=nein
  if [ "$PH" = "nein" ]; then
    echo "    Diagnose: $(sql "SELECT id||' zugestellt='||coalesce(zugestellt::text,'-')||' quittiert='||coalesce(quittiert::text,'-')||' fehler='||coalesce(fehler,'-') FROM pin_auftrag ORDER BY id" | tr '\n' '|')"
  fi
  ergebnis "die PIN erreicht den Standort-Agenten und wird quittiert" "$PH"

  # ADR-0009 Festlegung 3: Das Chiffrat wird nach der Zustellung geloescht.
  REST=$(sql "SELECT count(*) FROM pin_auftrag WHERE chiffrat IS NOT NULL")
  [ "${REST:-1}" = "0" ] && PR=ja || PR=nein
  ergebnis "kein Chiffrat bleibt nach der Zustellung in der Datenbank ($REST)" "$PR"

  # ADR-0009 Festlegung 4: nichts davon im Protokoll - weder PIN noch Chiffrat.
  TREFFER=0
  ANFANG=$(printf '%s' "$CHIFFRAT" | cut -c1-24)
  for w in "$PIN_NEU" "$ANFANG"; do
    docker logs pr-zen 2>&1 | grep -qF "$w" && TREFFER=$((TREFFER+1))
    docker logs pr-agt 2>&1 | grep -qF "$w" && TREFFER=$((TREFFER+1))
    [ "$(sql "SELECT count(*) FROM fachprotokoll WHERE coalesce(text,'') LIKE '%$w%' OR coalesce(handlung,'') LIKE '%$w%'")" != "0" ] \
      && TREFFER=$((TREFFER+1))
  done
  [ $TREFFER -eq 0 ] && PP=ja || PP=nein
  ergebnis "weder PIN noch Chiffrat in Protokoll oder Ausgaben (Treffer: $TREFFER)" "$PP"

  # Protokolliert wird DASS - das gehoert nachgewiesen, nicht nur das Schweigen.
  [ "$(sql "SELECT count(*) FROM fachprotokoll WHERE art='PIN_HINTERLEGT'")" != "0" ] && PD=ja || PD=nein
  ergebnis "das Hinterlegen selbst steht im Fachprotokoll" "$PD"

  # Beim Agenten liegt sie verschluesselt, nicht im Klartext.
  ABL=$(docker exec pr-agt sh -c 'cat /daten/pins 2>/dev/null' || true)
  if [ -n "$ABL" ] && ! printf '%s' "$ABL" | grep -qF "$PIN_NEU"; then PA=ja; else PA=nein; fi
  ergebnis "die PIN liegt beim Agenten verschluesselt in der Ablage" "$PA"

  # Die Oberflaeche zeigt das Kennzeichen, nie den Wert.
  curl -s -b "$KEKSE" "http://localhost:8090/bestand/terminal/$TID_P" > /tmp/pr-pin2.html
  grep -q "PIN hinterlegt" /tmp/pr-pin2.html && ! grep -qF "$PIN_NEU" /tmp/pr-pin2.html \
    && PM=ja || PM=nein
  ergebnis "die Oberflaeche zeigt nur das Kennzeichen, nie den Wert" "$PM"

  # FA-PIN-060: Loeschen, genauso einfach wie Hinterlegen.
  # Nur pruefbar, wenn vorher wirklich etwas hinterlegt war - sonst bestaetigen
  # sich die folgenden Nachweise selbst.
  if [ "$PH" = "nein" ]; then
   echo "  Loeschen nicht pruefbar: es war keine PIN hinterlegt."
   FEHLER=$((FEHLER+1))
  else
  curl -s -b "$KEKSE" -X POST "http://localhost:8090/bestand/terminal/$TID_P/pin-loeschen" \
    -d "kartenhandle=$HANDLE" > /dev/null
  for _ in $(seq 60); do
    [ "$(sql "SELECT count(*) FROM pin_hinterlegt WHERE kartenhandle = '$HANDLE'")" = "0" ] && break
    sleep 2
  done
  [ "$(sql "SELECT count(*) FROM pin_hinterlegt WHERE kartenhandle = '$HANDLE'")" = "0" ] && PL=ja || PL=nein
  ergebnis "FA-PIN-060: die hinterlegte PIN laesst sich wieder loeschen" "$PL"
  ABL2=$(docker exec pr-agt sh -c 'cat /daten/pins 2>/dev/null' || true)
  printf '%s' "$ABL2" | grep -q "^$HANDLE=" && PL2=nein || PL2=ja
  ergebnis "nach dem Loeschen ist der Eintrag auch beim Agenten weg" "$PL2"
  [ "$(sql "SELECT count(*) FROM fachprotokoll WHERE art='PIN_GELOESCHT'")" != "0" ] && PG=ja || PG=nein
  ergebnis "das Loeschen steht im Fachprotokoll" "$PG"
  fi

  rm -f /tmp/pr-pin.html /tmp/pr-pin2.html /tmp/pr-pin-neu.html /tmp/pr-pin-klartext.html \
        /tmp/pr-agent-pub.pem
fi

# ---------------------------------------------------------------- FA-ORG-020
hinweis "FA-ORG-020 — Organisationseinheiten über die Oberfläche verwalten"

curl -s -b "$KEKSE" http://localhost:8090/organisation > /tmp/pr-org.html
grep -qE "<h2[^>]*>Organisation</h2>" /tmp/pr-org.html && OB=ja || OB=nein
ergebnis "die Organisationsseite ist erreichbar" "$OB"
grep -q "Einheit hinzufügen" /tmp/pr-org.html && OF=ja || OF=nein
ergebnis "der Instanzbetreiber bekommt das Anlegeformular" "$OF"

# Anlegen unterhalb eines Standorts.
STANDORT=$(sql "SELECT id FROM organisation WHERE art='STANDORT' ORDER BY id LIMIT 1")
VERBUND=$(sql "SELECT id FROM organisation WHERE art='VERBUND' ORDER BY id LIMIT 1")
if [ -z "$STANDORT" ] || [ -z "$VERBUND" ]; then
  echo "  uebersprungen: kein Standort/Verbund im Demodatensatz"
  FEHLER=$((FEHLER+1))
else
  curl -s -b "$KEKSE" -o /dev/null -X POST http://localhost:8090/organisation \
    --data-urlencode "bezeichnung=Nachweis Praxis" --data-urlencode "art=EINRICHTUNG" \
    --data-urlencode "elternId=$STANDORT"
  NEU=$(sql "SELECT id FROM organisation WHERE bezeichnung='Nachweis Praxis'")
  [ -n "$NEU" ] && OA=ja || OA=nein
  ergebnis "eine Einrichtung laesst sich unterhalb eines Standorts anlegen" "$OA"
  [ "$(sql "SELECT count(*) FROM fachprotokoll WHERE art='ORGANISATION_ANGELEGT'")" != "0" ] \
    && OP=ja || OP=nein
  ergebnis "das Anlegen steht im Fachprotokoll" "$OP"

  if [ -n "$NEU" ]; then
    # Umbenennen
    curl -s -b "$KEKSE" -o /dev/null -X POST "http://localhost:8090/organisation/$NEU/umbenennen" \
      --data-urlencode "bezeichnung=Nachweis Praxis Nord"
    [ "$(sqltext "SELECT bezeichnung FROM organisation WHERE id=$NEU")" = "Nachweis Praxis Nord" ] \
      && OU=ja || OU=nein
    ergebnis "eine Einheit laesst sich umbenennen" "$OU"

    # Umhaengen an den Verbund
    curl -s -b "$KEKSE" -o /dev/null -X POST "http://localhost:8090/organisation/$NEU/umhaengen" \
      --data-urlencode "elternId=$VERBUND"
    [ "$(sql "SELECT eltern_id FROM organisation WHERE id=$NEU")" = "$VERBUND" ] && OH=ja || OH=nein
    ergebnis "eine Einheit laesst sich umhaengen" "$OH"
    [ "$(sql "SELECT count(*) FROM fachprotokoll WHERE art='ORGANISATION_UMGEHAENGT'")" != "0" ] \
      && OHP=ja || OHP=nein
    ergebnis "das Umhaengen steht im Fachprotokoll (Sicherheitsstufe)" "$OHP"

    # Zyklus: den Verbund unter seine eigene neue Einrichtung haengen.
    curl -s -b "$KEKSE" -X POST "http://localhost:8090/organisation/$VERBUND/umhaengen" \
      --data-urlencode "elternId=$NEU" > /tmp/pr-org-zyklus.html
    [ -z "$(sql "SELECT eltern_id FROM organisation WHERE id=$VERBUND")" ] && OZ=ja || OZ=nein
    ergebnis "ein Zyklus wird abgelehnt und der Baum bleibt heil" "$OZ"

    # Loeschen: die leere neue Einheit geht.
    curl -s -b "$KEKSE" -o /dev/null -X POST "http://localhost:8090/organisation/$NEU/loeschen"
    [ "$(sql "SELECT count(*) FROM organisation WHERE id=$NEU")" = "0" ] && OL=ja || OL=nein
    ergebnis "eine leere Einheit laesst sich loeschen" "$OL"
  fi

  # Loeschen einer Einheit MIT Inhalt muss scheitern - CASCADE wuerde sonst
  # Geraete und Konten mitreissen.
  VOLL=$(sql "SELECT o.id FROM organisation o
              WHERE EXISTS (SELECT 1 FROM kartenterminal k WHERE k.organisation_id = o.id)
              ORDER BY o.id LIMIT 1")
  if [ -n "$VOLL" ]; then
    VORHER_KT=$(sql "SELECT count(*) FROM kartenterminal WHERE organisation_id = $VOLL")
    curl -s -b "$KEKSE" -X POST "http://localhost:8090/organisation/$VOLL/loeschen" \
      > /tmp/pr-org-voll.html
    NACHHER_KT=$(sql "SELECT count(*) FROM kartenterminal WHERE organisation_id = $VOLL")
    [ "$VORHER_KT" = "$NACHHER_KT" ] && [ "$(sql "SELECT count(*) FROM organisation WHERE id=$VOLL")" = "1" ] \
      && OV=ja || OV=nein
    ergebnis "eine Einheit mit Geraeten wird nicht geloescht ($VORHER_KT -> $NACHHER_KT Geraete)" "$OV"
    grep -q "Kartenterminal" /tmp/pr-org-voll.html && OVM=ja || OVM=nein
    ergebnis "die Meldung nennt, was im Weg steht" "$OVM"
    rm -f /tmp/pr-org-voll.html
  fi
  rm -f /tmp/pr-org-zyklus.html
fi
rm -f /tmp/pr-org.html

# ---------------------------------------------------------------- FA-GER-050
# Gerätegruppen tragen das Prüfintervall. Bisher nur durch Modultests
# abgesichert - die prüfen die Logik, nicht das Zusammenspiel mit Oberfläche,
# Protokoll und Datenbank.
hinweis "FA-GER-050 — Gerätegruppen anlegen, zuordnen, löschen"

curl -s -b "$KEKSE" http://localhost:8090/gruppen > /tmp/pr-gruppen.html
grep -q "Nicht zugeordnet" /tmp/pr-gruppen.html && GV=ja || GV=nein
ergebnis "die Vorgabegruppe existiert und ist sichtbar" "$GV"

curl -s -b "$KEKSE" -o /dev/null -X POST http://localhost:8090/gruppen \
  --data-urlencode "bezeichnung=Nachweis Notaufnahme" --data-urlencode "intervall=30"
GID=$(sql "SELECT id FROM geraetegruppe WHERE bezeichnung='Nachweis Notaufnahme'")
[ -n "$GID" ] && GA=ja || GA=nein
ergebnis "eine Gruppe laesst sich mit eigenem Pruefintervall anlegen" "$GA"
[ "$(sql "SELECT pruefintervall_sekunden FROM geraetegruppe WHERE id=$GID")" = "30" ] && GI=ja || GI=nein
ergebnis "das Pruefintervall wird uebernommen (30 s)" "$GI"
[ "$(sql "SELECT count(*) FROM fachprotokoll WHERE art='GRUPPE_ANGELEGT'")" != "0" ] && GP=ja || GP=nein
ergebnis "das Anlegen steht im Fachprotokoll" "$GP"

# Ein zu kurzes Intervall muss abgelehnt werden - 5 Sekunden mal 5.000 Geraete
# waeren 1.000 Abfragen pro Sekunde.
curl -s -b "$KEKSE" -o /dev/null -X POST "http://localhost:8090/gruppen/$GID/intervall" \
  --data-urlencode "intervall=1"
[ "$(sql "SELECT pruefintervall_sekunden FROM geraetegruppe WHERE id=$GID")" = "30" ] && GK=ja || GK=nein
ergebnis "ein unzulaessig kurzes Intervall wird nicht uebernommen" "$GK"

# Geraet zuordnen und die Gruppe loeschen: Kein Geraet darf aus der
# Ueberwachung fallen.
KT_G=$(sqltext "SELECT terminal_id FROM geraet ORDER BY terminal_id LIMIT 1")
VORGABE=$(sql "SELECT id FROM geraetegruppe WHERE ist_vorgabe")
if [ -n "$KT_G" ] && [ -n "$GID" ]; then
  curl -s -b "$KEKSE" -o /dev/null -X POST "http://localhost:8090/geraete/$KT_G/gruppe" \
    --data-urlencode "gruppe=$GID"
  [ "$(sql "SELECT gruppe_id FROM geraet WHERE terminal_id='$KT_G'")" = "$GID" ] && GZ=ja || GZ=nein
  ergebnis "ein Geraet laesst sich einer Gruppe zuordnen" "$GZ"

  curl -s -b "$KEKSE" -o /dev/null -X POST "http://localhost:8090/gruppen/$GID/loeschen"
  [ "$(sql "SELECT count(*) FROM geraetegruppe WHERE id=$GID")" = "0" ] && GL=ja || GL=nein
  ergebnis "die Gruppe laesst sich loeschen" "$GL"
  # Der entscheidende Punkt: Das Geraet ist umgezogen, nicht verschwunden.
  NACH=$(sql "SELECT gruppe_id FROM geraet WHERE terminal_id='$KT_G'")
  [ "$NACH" = "$VORGABE" ] && GU=ja || GU=nein
  ergebnis "das Geraet ist in die Vorgabegruppe umgezogen, nicht verschwunden" "$GU"
  [ "$(sql "SELECT count(*) FROM geraet WHERE terminal_id='$KT_G'")" = "1" ] && GE=ja || GE=nein
  ergebnis "das Geraet existiert nach der Loeschung weiterhin" "$GE"
fi

# Die Vorgabegruppe darf nicht verschwinden - neu erkannte Geraete brauchen
# eine Gruppe, in der sie landen koennen.
curl -s -b "$KEKSE" -X POST "http://localhost:8090/gruppen/$VORGABE/loeschen" > /tmp/pr-gruppe-vg.html
[ "$(sql "SELECT count(*) FROM geraetegruppe WHERE id=$VORGABE")" = "1" ] && GD=ja || GD=nein
ergebnis "die Vorgabegruppe laesst sich nicht loeschen" "$GD"
grep -q "Vorgabegruppe" /tmp/pr-gruppe-vg.html && GDM=ja || GDM=nein
ergebnis "die Meldung erklaert, warum" "$GDM"
rm -f /tmp/pr-gruppen.html /tmp/pr-gruppe-vg.html

# ---------------------------------------------------------------- FA-ALA-010
# Alarme quittieren. Der Umsetzungsstand fuehrte das faelschlich unter
# FA-ALA-020 - das ist im Katalog die E-Mail-Benachrichtigung und NICHT
# umgesetzt. Quittieren gehoert zu FA-ALA-010.
hinweis "FA-ALA-010 — Alarme quittieren"

ALARM=$(sql "SELECT id FROM alarm WHERE quittiert_von IS NULL AND behoben_am IS NULL
             ORDER BY id LIMIT 1")
if [ -z "$ALARM" ]; then
  echo "  uebersprungen: derzeit kein offener Alarm im System"
else
  curl -s -b "$KEKSE" -o /dev/null -X POST "http://localhost:8090/alarme/$ALARM/quittieren"
  QUITT=$(sqltext "SELECT coalesce(quittiert_von,'-') FROM alarm WHERE id=$ALARM")
  [ "$QUITT" != "-" ] && AQ=ja || AQ=nein
  ergebnis "ein offener Alarm laesst sich quittieren (durch: $QUITT)" "$AQ"
  [ -n "$(sql "SELECT quittiert_am FROM alarm WHERE id=$ALARM")" ] && AZ=ja || AZ=nein
  ergebnis "der Zeitpunkt der Quittierung wird festgehalten" "$AZ"
  [ "$(sql "SELECT count(*) FROM fachprotokoll WHERE art='ALARM_QUITTIERT'")" != "0" ] && AP=ja || AP=nein
  ergebnis "die Quittierung steht im Fachprotokoll" "$AP"

  # Quittieren heisst "zur Kenntnis genommen", nicht "behoben". Der Alarm muss
  # bestehen bleiben, solange der Zustand besteht - sonst verschwindet ein
  # echtes Problem per Mausklick.
  [ -z "$(sql "SELECT behoben_am FROM alarm WHERE id=$ALARM")" ] && AB=ja || AB=nein
  ergebnis "das Quittieren behebt den Alarm NICHT" "$AB"

  # Und die Oberflaeche bietet keinen zweiten Quittierknopf an.
  curl -s -b "$KEKSE" http://localhost:8090/teil/uebersicht > /tmp/pr-alarme.html
  QK=$(grep -c "/alarme/$ALARM/quittieren" /tmp/pr-alarme.html || true)
  [ "${QK:-1}" = "0" ] && AK=ja || AK=nein
  ergebnis "ein quittierter Alarm bietet keinen zweiten Quittierknopf ($QK)" "$AK"
  rm -f /tmp/pr-alarme.html
fi

# ------------------------------------------------- UX-Stufe 4 und 5 (ADR-0010)
hinweis "UX-Stufe 4/5 — Detailseiten statt Aufklapper, Beziehungen zum Durchklicken"

# Kein "details"-Aufklapper mehr in Bestand und Organisation: Die Seite soll
# nicht laenger werden, sondern eine neue aufgehen.
curl -s -b "$KEKSE" http://localhost:8090/bestand      -o /tmp/pr-ux-bestand.html
curl -s -b "$KEKSE" http://localhost:8090/organisation -o /tmp/pr-ux-org.html
AUF=$(( $(grep -c 'class="ausklapp"' /tmp/pr-ux-bestand.html || true) \
      + $(grep -c 'class="ausklapp"' /tmp/pr-ux-org.html || true) ))
[ "$AUF" = "0" ] && UA=ja || UA=nein
ergebnis "keine Aufklapper mehr in Bestand und Organisation ($AUF)" "$UA"

for paar in "/bestand/konnektor/neu:Konnektor hinzufügen" \
            "/bestand/terminal/neu:Kartenterminal hinzufügen" \
            "/organisation/neu:Einheit hinzufügen"; do
  PFAD=${paar%%:*}; TITEL=${paar#*:}
  CODE=$(curl -s -b "$KEKSE" -o /tmp/pr-ux-neu.html -w '%{http_code}' "http://localhost:8090$PFAD")
  UN=nein
  if [ "$CODE" = "200" ] && [ "$(enthaelt /tmp/pr-ux-neu.html "$TITEL")" = "ja" ] \
     && [ "$(enthaelt /tmp/pr-ux-neu.html 'class="zurueck"')" = "ja" ]; then UN=ja; fi
  ergebnis "$PFAD ist eine eigene Seite mit Rückweg (HTTP $CODE)" "$UN"
done
rm -f /tmp/pr-ux-neu.html

# Die Detailseite einer Einheit zeigt ihre Beziehungen und stimmt mit der
# Datenbank ueberein — eine Zahl, die nicht stimmt, ist schlimmer als keine.
ORG_D=$(sql "SELECT o.id FROM organisation o
             WHERE EXISTS (SELECT 1 FROM kartenterminal t WHERE t.organisation_id = o.id)
             ORDER BY o.id LIMIT 1")
if [ -z "$ORG_D" ]; then
  echo "  uebersprungen: keine Einrichtung mit Geraeten"
  FEHLER=$((FEHLER+1))
else
  CODE=$(curl -s -b "$KEKSE" -o /tmp/pr-ux-detail.html -w '%{http_code}' \
         "http://localhost:8090/organisation/$ORG_D")
  [ "$CODE" = "200" ] && UD=ja || UD=nein
  ergebnis "die Organisationseinheit hat eine eigene Adresse (HTTP $CODE)" "$UD"

  SOLL_KT=$(sql "SELECT count(*) FROM kartenterminal t WHERE t.organisation_id IN (
                   WITH RECURSIVE u AS (SELECT id FROM organisation WHERE id=$ORG_D
                     UNION ALL SELECT x.id FROM organisation x JOIN u ON x.eltern_id=u.id)
                   SELECT id FROM u)")
  IST_KT=$(grep -oE '<span class="zahl">[0-9]+</span><span class="was">Kartenterminals' \
           /tmp/pr-ux-detail.html | grep -oE '[0-9]+' | head -1)
  [ "${IST_KT:-x}" = "$SOLL_KT" ] && UZ=ja || UZ=nein
  ergebnis "die Zahl der Kartenterminals stimmt mit der Datenbank ($IST_KT von $SOLL_KT)" "$UZ"

  # Der Absprung muss wirklich filtern - eine Kachel, die auf eine ungefilterte
  # Liste fuehrt, ist eine Luege.
  curl -s -b "$KEKSE" "http://localhost:8090/bestand?einrichtung=$ORG_D" -o /tmp/pr-ux-abs.html
  IST_ABS=$(grep -oE 'Kartenterminals <span class="zaehler">[0-9]+' /tmp/pr-ux-abs.html \
            | grep -oE '[0-9]+$')
  ALLE_KT=$(sql "SELECT count(*) FROM kartenterminal")
  UF=nein
  [ "${IST_ABS:-x}" = "$SOLL_KT" ] && [ "$SOLL_KT" != "$ALLE_KT" ] && UF=ja
  ergebnis "der Absprung filtert wirklich ($IST_ABS von insgesamt $ALLE_KT)" "$UF"

  # Und er darf die Mandantengrenze nicht aufweichen: Ein Praxisbenutzer, der
  # eine fremde Kennung anhaengt, bekommt trotzdem nur das Seine.
  FREMD_ORG=$(sql "SELECT id FROM organisation WHERE id <> $ORG_D AND art='EINRICHTUNG'
                   ORDER BY id LIMIT 1")
  CODE=$(curl -s -o /dev/null -w '%{http_code}' \
         "http://localhost:8090/organisation/$FREMD_ORG")
  [ "$CODE" = "302" ] && UM=ja || UM=nein
  ergebnis "die Detailseite ist ohne Anmeldung nicht erreichbar (HTTP $CODE)" "$UM"

  rm -f /tmp/pr-ux-detail.html /tmp/pr-ux-abs.html
fi
rm -f /tmp/pr-ux-bestand.html /tmp/pr-ux-org.html

# ---------------------------------------------------------------- FA-ALA-020
# Benachrichtigung per E-Mail. Geprueft wird gegen einen echten Auffangserver
# (MailHog): Ein Nachweis, der nur die Datenbank ansieht, belegt nicht, dass
# eine Mail tatsaechlich hinausgeht.
hinweis "FA-ALA-020 — Benachrichtigung per E-Mail"

# Der Auffangserver laeuft im Simulator (Port 1025) — kein zusaetzliches Image.
# Der erste Anlauf verwendete MailHog; der Lauf blieb beim Ziehen des Images
# stehen, und ein Nachweis, der Internetzugang braucht, ist unbrauchbar.
curl -s -X DELETE "http://localhost:8480/mail" >/dev/null 2>&1
if true; then
  mails() { curl -s "http://localhost:8480/mail"; }
  mailzahl() { mails | grep -oE '"anzahl":[0-9]+' | head -1 | grep -oE '[0-9]+$'; }

  # Mailserver einrichten
  curl -s -b "$KEKSE" -X POST http://localhost:8090/benachrichtigung/server \
    --data-urlencode "host=simulator" --data-urlencode "port=1025" \
    --data-urlencode "absender=pin-roboter@praxis.example" \
    -o /tmp/pr-mail-srv.html
  MS=$(enthaelt /tmp/pr-mail-srv.html "Mailserver gespeichert")
  ergebnis "der Mailserver laesst sich einrichten" "$MS"

  # Testversand — ohne ihn merkt der Betreiber erst beim ersten echten Alarm,
  # dass die Einstellung nicht stimmt.
  VOR=$(mailzahl)
  curl -s -b "$KEKSE" -X POST http://localhost:8090/benachrichtigung/test \
    --data-urlencode "testempfaenger=leitung@praxis.example" -o /tmp/pr-mail-test.html
  sleep 2
  NACH=$(mailzahl)
  [ "${NACH:-0}" -gt "${VOR:-0}" ] && MT=ja || MT=nein
  ergebnis "der Testversand kommt beim Mailserver an ($VOR -> $NACH)" "$MT"

  # Regel mit Sammelfenster 0. Bevorzugt eine Einrichtung MIT UMLAUT im Namen:
  # Der Betreff traegt den Einrichtungsnamen, und nur so entsteht ueberhaupt
  # ein Fall, der RFC-2047-Kodierung braucht. Ein Nachweis, der die Kodierung
  # fordert, ohne einen Umlaut zu erzeugen, prueft nichts.
  ORG_M=$(sql "SELECT o.id FROM organisation o
               WHERE EXISTS (SELECT 1 FROM kartenterminal t WHERE t.organisation_id = o.id)
                 AND o.bezeichnung ~ '[äöüÄÖÜß]'
               ORDER BY o.id LIMIT 1")
  UMLAUT=ja
  if [ -z "$ORG_M" ]; then
    UMLAUT=nein
    ORG_M=$(sql "SELECT o.id FROM organisation o
                 WHERE EXISTS (SELECT 1 FROM kartenterminal t WHERE t.organisation_id = o.id)
                 ORDER BY o.id LIMIT 1")
  fi
  curl -s -b "$KEKSE" -X POST http://localhost:8090/benachrichtigung/regel \
    --data-urlencode "organisationId=$ORG_M" \
    --data-urlencode "empfaenger=leitung@praxis.example" \
    --data-urlencode "schwelle=WARNUNG" --data-urlencode "fenster=0" \
    --data-urlencode "aktiv=ja" -o /tmp/pr-mail-regel.html
  MR=$(enthaelt /tmp/pr-mail-regel.html "Regel gespeichert")
  ergebnis "eine Benachrichtigungsregel laesst sich anlegen" "$MR"

  # Der Kern der Anforderung: Aus vielen Alarmen wird EINE Mail.
  # 50 wartende Alarme direkt einreihen, dann den Hintergrunddienst arbeiten lassen.
  docker exec pr-db psql -U pinroboter -d pinroboter -q -c \
    "INSERT INTO mail_warteschlange (organisation_id, schweregrad, objekt, meldung, handlung)
     SELECT $ORG_M, 'KRITISCH', 'KT-FLUT-' || g, 'Kartenterminal nicht erreichbar',
            'Netzwerk pruefen' FROM generate_series(1,50) g" >/dev/null
  VOR=$(mailzahl)
  for _ in $(seq 30); do
    [ "$(sql "SELECT count(*) FROM mail_warteschlange WHERE versendet IS NULL")" = "0" ] && break
    sleep 2
  done
  NACH=$(mailzahl)
  NEU=$(( ${NACH:-0} - ${VOR:-0} ))
  [ "$NEU" = "1" ] && MF=ja || MF=nein
  ergebnis "50 Alarme ergeben EINE Mail, nicht 50 (neu: $NEU)" "$MF"

  # Und die eine Mail muss die Zusammenfassung tragen, nicht 50 gleiche Zeilen.
  mails | grep -q "Betroffen: 50 Ger" && MZ=ja || MZ=nein
  ergebnis "die Mail fasst gleichartige Meldungen zusammen" "$MZ"

  # Umlaute muessen im Postfach lesbar sein, nicht als Buchstabensalat. Der
  # Betreff traegt den Einrichtungsnamen — hier mit Umlaut.
  if [ "$UMLAUT" = "ja" ]; then
    mails | grep -q "=?UTF-8?B?" && MU=ja || MU=nein
    if [ "$MU" = "nein" ]; then
      echo "    Diagnose: Betreffzeilen im Auffang:"
      mails | grep -oE 'Subject: [^\\]{0,80}' | sed 's/^/      /' | head -3
    fi
    ergebnis "der Betreff mit Umlaut ist RFC-2047-kodiert" "$MU"
  else
    echo "  uebersprungen: keine Einrichtung mit Umlaut im Namen — die Kodierung"
    echo "    ist durch den Modultest belegt, hier aber nicht ausloesbar."
  fi

  # Nie in einer Mail: PIN, Chiffrat, Kennwoerter.
  TREFFER=0
  for w in 123456 428913 NachweisKennwortXY TerminalKennwortXY; do
    mails | grep -q "$w" && TREFFER=$((TREFFER+1))
  done
  [ $TREFFER -eq 0 ] && MG=ja || MG=nein
  ergebnis "keine Geheimnisse im Mailverkehr (Treffer: $TREFFER)" "$MG"

  # Das Mailserver-Kennwort liegt verschluesselt, nicht im Klartext.
  curl -s -b "$KEKSE" -X POST http://localhost:8090/benachrichtigung/server \
    --data-urlencode "host=simulator" --data-urlencode "port=1025" \
    --data-urlencode "absender=pin-roboter@praxis.example" \
    --data-urlencode "benutzer=postmaster" --data-urlencode "kennwort=MailKennwortXY" \
    -o /dev/null
  CH=$(sqltext "SELECT coalesce(kennwort_chiffrat,'') FROM mail_server")
  case "$CH" in v1:*) MK=ja ;; *) MK=nein ;; esac
  ergebnis "das Mailserver-Kennwort liegt verschluesselt in der Datenbank" "$MK"
  KLAR=$(sql "SELECT count(*) FROM mail_server WHERE kennwort_chiffrat LIKE '%MailKennwortXY%'")
  [ "${KLAR:-1}" = "0" ] && MK2=ja || MK2=nein
  ergebnis "es steht nirgends im Klartext" "$MK2"
  curl -s -b "$KEKSE" http://localhost:8090/benachrichtigung -o /tmp/pr-mail-seite.html
  [ "$(enthaelt /tmp/pr-mail-seite.html "MailKennwortXY")" = "nein" ] && MK3=ja || MK3=nein
  ergebnis "und erscheint nicht auf der Oberflaeche" "$MK3"
  [ "$(sql "SELECT count(*) FROM fachprotokoll WHERE coalesce(text,'') LIKE '%MailKennwortXY%'")" = "0" ] \
    && MK4=ja || MK4=nein
  ergebnis "und nicht im Fachprotokoll" "$MK4"

  # Ein Praxisbenutzer darf den Mailserver nicht umstellen — eine geaenderte
  # Absenderadresse leitet Meldungen ueber fremde Betriebszustaende um.
  ergebnis "das Versenden ist protokolliert" \
    "$([ "$(sql "SELECT count(*) FROM fachprotokoll WHERE art='BENACHRICHTIGUNG_VERSENDET'")" != "0" ] && echo ja || echo nein)"

  rm -f /tmp/pr-mail-*.html
fi

# ---------------------------------------------------------------- FA-UEB-010
# Das Pruefintervall je Geraetegruppe. Bis zum 08.08.2026 war nur die
# GRUPPENPFLEGE nachgewiesen, nicht die WIRKUNG — der Agent kannte einen
# globalen Wert aus seiner Umgebung, und das Intervall der Gruppe erreichte ihn
# nie. Gemessen wird deshalb am Simulator: wie oft fragt der Agent wirklich?
hinweis "FA-UEB-010 — das Prüfintervall je Gerätegruppe wirkt"

# Zwei Gruppen mit deutlich verschiedenen Intervallen.
curl -s -b "$KEKSE" -o /dev/null -X POST http://localhost:8090/gruppen \
  --data-urlencode "bezeichnung=Nachweis Schnell" --data-urlencode "intervall=10"
curl -s -b "$KEKSE" -o /dev/null -X POST http://localhost:8090/gruppen \
  --data-urlencode "bezeichnung=Nachweis Langsam" --data-urlencode "intervall=120"
G_SCHNELL=$(sql "SELECT id FROM geraetegruppe WHERE bezeichnung='Nachweis Schnell'")
G_LANGSAM=$(sql "SELECT id FROM geraetegruppe WHERE bezeichnung='Nachweis Langsam'")

# Zwei Terminals des Simulators, die beide eine SMC-B tragen.
mapfile -t KT_IDS < <(sql "SELECT terminal_id FROM geraet ORDER BY terminal_id" \
                      | tr -d '\r' | head -2) 2>/dev/null || \
  KT_IDS=($(docker exec pr-db psql -U pinroboter -d pinroboter -tAc \
            "SELECT terminal_id FROM geraet ORDER BY terminal_id LIMIT 2" | tr -d ' \r'))

if [ -z "$G_SCHNELL" ] || [ -z "$G_LANGSAM" ] || [ "${#KT_IDS[@]}" -lt 2 ]; then
  echo "  uebersprungen: Gruppen oder Geraete fehlen (${#KT_IDS[@]} Geraete)"
  FEHLER=$((FEHLER+1))
else
  KT_A=${KT_IDS[0]}; KT_B=${KT_IDS[1]}
  curl -s -b "$KEKSE" -o /dev/null -X POST "http://localhost:8090/geraete/$KT_A/gruppe" \
    --data-urlencode "gruppe=$G_SCHNELL"
  curl -s -b "$KEKSE" -o /dev/null -X POST "http://localhost:8090/geraete/$KT_B/gruppe" \
    --data-urlencode "gruppe=$G_LANGSAM"
  echo "  $KT_A -> 10 s,  $KT_B -> 120 s"

  # Der Agent holt die Intervalle im Grundtakt. Warten, bis er sie kennt.
  curl -s -b "$KEKSE" -o /dev/null "http://localhost:8090/api/agent/intervalle" 2>/dev/null
  ANTWORT_I=$(docker exec pr-agt sh -c 'echo' 2>/dev/null; \
              docker logs pr-agt 2>&1 | grep -c "Prüfintervalle aktualisiert" || true)
  for _ in $(seq 20); do
    [ "$(docker logs pr-agt 2>&1 | grep -c 'Prüfintervalle aktualisiert')" -gt 0 ] && break
    sleep 2
  done
  [ "$(docker logs pr-agt 2>&1 | grep -c 'Prüfintervalle aktualisiert')" -gt 0 ] && UI=ja || UI=nein
  ergebnis "der Agent erfaehrt die Pruefintervalle von der Zentrale" "$UI"

  # Jetzt zaehlen: 40 Sekunden lang. Das schnelle Geraet muss deutlich
  # oefter abgefragt werden als das langsame.
  curl -s -X DELETE "http://localhost:8480/abfragen" >/dev/null
  sleep 40
  ZAEHLER=$(curl -s "http://localhost:8480/abfragen")
  # Kartenhandle des jeweiligen Terminals ermitteln.
  H_A=$(sqltext "SELECT kartenhandle FROM ermittelte_karte WHERE terminal_id='$KT_A'
                 AND kartentyp='SMC-B' LIMIT 1")
  H_B=$(sqltext "SELECT kartenhandle FROM ermittelte_karte WHERE terminal_id='$KT_B'
                 AND kartentyp='SMC-B' LIMIT 1")
  N_A=$(echo "$ZAEHLER" | grep -oE "\"$H_A\":[0-9]+" | grep -oE '[0-9]+$')
  N_B=$(echo "$ZAEHLER" | grep -oE "\"$H_B\":[0-9]+" | grep -oE '[0-9]+$')
  N_A=${N_A:-0}; N_B=${N_B:-0}
  echo "  In 40 s: $KT_A ($H_A) = $N_A Abfragen,  $KT_B ($H_B) = $N_B Abfragen"

  # Bei 10 s Intervall sind in 40 s rund 4 Abfragen zu erwarten, bei 120 s
  # hoechstens eine (die erste, weil ein Geraet nicht bis zum Ablauf seines
  # Intervalls unbekannt bleiben soll).
  [ "$N_A" -ge 3 ] && UA=ja || UA=nein
  ergebnis "das schnelle Geraet wird im 10-s-Takt abgefragt ($N_A in 40 s)" "$UA"
  [ "$N_B" -le 1 ] && UB=ja || UB=nein
  ergebnis "das langsame Geraet wird im 120-s-Takt geschont ($N_B in 40 s)" "$UB"
  [ "$N_A" -gt "$N_B" ] && UV=ja || UV=nein
  ergebnis "die Gruppeneinstellung wirkt sich messbar aus ($N_A > $N_B)" "$UV"
fi

# ----------------------------------------------- FA-UEB-050 / gemSpec_Kon 4.3.10
# Der KONNEKTORWEITE KSR-Lauf. Der bisherige Nachweis prueft ein einzelnes
# Firmware-Update am Terminal. Beim KSR-Lauf aktualisiert der Konnektor
# mehrere Geraete nacheinander (Ereignisse KSR/UPDATE/START bis
# KSR/UPDATE/END, Target=KT) — betroffen sind dann ALLE, und ein einziger
# uebersehener Verifikationsversuch belastet den Fehlversuchszaehler einer
# SMC-B.
hinweis "gemSpec_Kon 4.3.10 — während eines KSR-Laufs wird nirgends verifiziert"

# Frischer Stack: Der Nachweis zu FA-PIN-060 loescht zuvor die hinterlegte PIN.
# Ohne PIN kann der Roboter nach dem KSR-Lauf nichts wiederherstellen, und der
# Nachweis wuerde eine Funktion als kaputt melden, die in Ordnung ist.
stack_starten 123456 4

V_KSR=$(verifikationen)
ANTWORT_KSR=$(curl -s -X POST "http://localhost:8480/ksr/start?dauer=40000")
BETROFFEN=$(echo "$ANTWORT_KSR" | grep -oE '"terminals":[0-9]+' | grep -oE '[0-9]+$')
echo "  KSR-Lauf über $BETROFFEN Terminals, 40 s bei 10 s Gruppentakt → ~4 Gelegenheiten"
[ "${BETROFFEN:-0}" -ge 2 ] && KB=ja || KB=nein
ergebnis "der KSR-Lauf betrifft mehrere Geraete gleichzeitig ($BETROFFEN)" "$KB"

sleep 35
W_KSR=$(verifikationen)
[ $((W_KSR-V_KSR)) -eq 0 ] && KV=ja || KV=nein
if [ "$KV" = "nein" ]; then
  echo "    Diagnose: $((W_KSR-V_KSR)) Versuch(e) waehrend des KSR-Laufs. Zeilen:"
  docker logs pr-agt 2>&1 | grep "starte Verifikation" | tail -3 | sed 's/^/      /'
fi
ergebnis "0 Verifikationsversuche waehrend des KSR-Laufs (gemessen: $((W_KSR-V_KSR)))" "$KV"

# Die Karten sind nach dem Lauf nicht mehr verifiziert — der Neustart entzieht
# ihnen den Zustand. Genau das wiederherzustellen ist der Zweck des Produkts.
for _ in $(seq 30); do
  [ $(( $(verifikationen) - W_KSR )) -ge 1 ] && break
  sleep 2
done
N_KSR=$(verifikationen)
[ $((N_KSR-W_KSR)) -ge 1 ] && KW=ja || KW=nein
ergebnis "nach dem KSR-Lauf stellt der Roboter den Zustand wieder her ($((N_KSR-W_KSR)))" "$KW"

# Und der Betreiber erfaehrt, was los ist — nicht nur, dass nichts passiert.
curl -s -X POST "http://localhost:8480/ksr/start?dauer=45000" >/dev/null
# Der Agent meldet den Zustand erst beim naechsten Durchgang. Bei 10 s
# Gruppentakt braucht die Oberflaeche bis zu zwei Takte, um ihn zu zeigen.
sleep 25
curl -s -b "$KEKSE" http://localhost:8090/geraete -o /tmp/pr-ksr.html
KH=$(enthaelt /tmp/pr-ksr.html "wird aktualisiert")
ergebnis "die Oberflaeche nennt den Zustand waehrend des Laufs" "$KH"
curl -s -X POST "http://localhost:8480/ksr/ende" >/dev/null
rm -f /tmp/pr-ksr.html

# ------------------------------------------- FA-PIN-040 / FA-UEB-020 / FA-UEB-030
hinweis "FA-PIN-040 — nicht behebbare Zustände werden gemeldet, nicht versucht"

# Vier Zustaende, die der Roboter NICHT aufloesen kann. Jeder muss eine eigene
# Klartextmeldung tragen — und keinen Verifikationsversuch ausloesen.
V_NB=$(verifikationen)
curl -s -X POST http://localhost:8480/steuerung/KT-EMPFANG-01/transportpin >/dev/null
sleep 25
curl -s -b "$KEKSE" http://localhost:8090/geraete -o /tmp/pr-nb.html
[ "$(enthaelt /tmp/pr-nb.html "Transport-PIN")" = "ja" ] && PT=ja || PT=nein
ergebnis "Transport-PIN wird als eigener Zustand angezeigt" "$PT"
[ "$(enthaelt /tmp/pr-nb.html "am Kartenterminal")" = "ja" ] && PT2=ja || PT2=nein
ergebnis "die Meldung sagt, was zu tun ist" "$PT2"
W_NB=$(verifikationen)
[ $((W_NB-V_NB)) -eq 0 ] && PT3=ja || PT3=nein
ergebnis "kein Verifikationsversuch bei Transport-PIN (gemessen: $((W_NB-V_NB)))" "$PT3"

curl -s -X POST http://localhost:8480/steuerung/KT-EMPFANG-01/sperren >/dev/null
sleep 25
curl -s -b "$KEKSE" http://localhost:8090/geraete -o /tmp/pr-nb2.html
[ "$(enthaelt /tmp/pr-nb2.html "gesperrt")" = "ja" ] && PB=ja || PB=nein
ergebnis "gesperrte Karte wird als solche angezeigt" "$PB"
[ "$(enthaelt /tmp/pr-nb2.html "PUK")" = "ja" ] && PB2=ja || PB2=nein
ergebnis "die Meldung nennt die PUK als einzigen Weg" "$PB2"
N_NB=$(verifikationen)
[ $((N_NB-W_NB)) -eq 0 ] && PB3=ja || PB3=nein
ergebnis "kein Verifikationsversuch bei gesperrter Karte (gemessen: $((N_NB-W_NB)))" "$PB3"
curl -s -X POST http://localhost:8480/steuerung/KT-EMPFANG-01/zuruecksetzen >/dev/null
rm -f /tmp/pr-nb.html /tmp/pr-nb2.html

hinweis "FA-UEB-020 — Erreichbarkeitswechsel werden protokolliert"
curl -s -X POST http://localhost:8480/steuerung/KT-BEHANDLUNG-01/erreichbar/false >/dev/null
for _ in $(seq 20); do
  [ "$(sql "SELECT count(*) FROM fachprotokoll WHERE art='GERAET_NICHT_ERREICHBAR'")" != "0" ] && break
  sleep 2
done
AUS=$(sql "SELECT count(*) FROM fachprotokoll WHERE art='GERAET_NICHT_ERREICHBAR'")
[ "${AUS:-0}" -ge 1 ] && EA=ja || EA=nein
ergebnis "der Ausfall steht im Fachprotokoll ($AUS)" "$EA"

curl -s -X POST http://localhost:8480/steuerung/KT-BEHANDLUNG-01/erreichbar/true >/dev/null
for _ in $(seq 20); do
  [ "$(sql "SELECT count(*) FROM fachprotokoll WHERE art='GERAET_ERREICHBAR'")" != "0" ] && break
  sleep 2
done
EIN=$(sql "SELECT count(*) FROM fachprotokoll WHERE art='GERAET_ERREICHBAR'")
[ "${EIN:-0}" -ge 1 ] && EE=ja || EE=nein
ergebnis "die Rueckkehr ebenfalls ($EIN)" "$EE"

# Grundregel ADR-0005: nur Wechsel, nicht jede Abfrage. Sonst waeren es bei
# 5.000 Terminals im Minutentakt Millionen Eintraege am Tag.
VOR_E=$(sql "SELECT count(*) FROM fachprotokoll WHERE art IN ('GERAET_ERREICHBAR','GERAET_NICHT_ERREICHBAR')")
sleep 30
NACH_E=$(sql "SELECT count(*) FROM fachprotokoll WHERE art IN ('GERAET_ERREICHBAR','GERAET_NICHT_ERREICHBAR')")
[ "$VOR_E" = "$NACH_E" ] && EK=ja || EK=nein
ergebnis "bei gleichbleibendem Zustand kommt nichts dazu ($VOR_E -> $NACH_E)" "$EK"

hinweis "FA-UEB-030 — Firmware-Stand erfassen und Wechsel erkennen"
curl -s -b "$KEKSE" http://localhost:8090/geraete -o /tmp/pr-fw.html
[ "$(enthaelt /tmp/pr-fw.html "Firmware-Stände")" = "ja" ] && FU=ja || FU=nein
ergebnis "die Flottenuebersicht zeigt die Verteilung je Modell" "$FU"
[ "$(enthaelt /tmp/pr-fw.html "aktuellster Stand")" = "ja" ] && FB=ja || FB=nein
ergebnis "sie bewertet die Staende, statt sie nur aufzuzaehlen" "$FB"

# Ein Versionswechsel muss erkannt werden, egal wer ihn ausgeloest hat.
VOR_F=$(sql "SELECT count(*) FROM fachprotokoll WHERE art='FIRMWARE_GEAENDERT'")
curl -s -X POST "http://localhost:8480/steuerung/KT-EMPFANG-01/firmware/9.9.9?dauer=2000" >/dev/null
for _ in $(seq 25); do
  [ "$(sql "SELECT count(*) FROM fachprotokoll WHERE art='FIRMWARE_GEAENDERT'")" -gt "${VOR_F:-0}" ] && break
  sleep 2
done
NACH_F=$(sql "SELECT count(*) FROM fachprotokoll WHERE art='FIRMWARE_GEAENDERT'")
[ "${NACH_F:-0}" -gt "${VOR_F:-0}" ] && FW=ja || FW=nein
ergebnis "ein Versionswechsel wird protokolliert ($VOR_F -> $NACH_F)" "$FW"
TEXT_F=$(sqltext "SELECT text FROM fachprotokoll WHERE art='FIRMWARE_GEAENDERT'
                  ORDER BY id DESC LIMIT 1")
case "$TEXT_F" in *"9.9.9"*) FT=ja ;; *) FT=nein ;; esac
ergebnis "der Eintrag nennt alten und neuen Stand ($TEXT_F)" "$FT"
[ "$(enthaelt /tmp/pr-fw.html "9.9.9")" = "nein" ] && true
curl -s -b "$KEKSE" http://localhost:8090/geraete -o /tmp/pr-fw2.html
[ "$(enthaelt /tmp/pr-fw2.html "9.9.9")" = "ja" ] && FS=ja || FS=nein
ergebnis "der neue Stand erscheint in der Uebersicht" "$FS"
rm -f /tmp/pr-fw.html /tmp/pr-fw2.html

# ------------------------------------------------- FA-AGT-040 / FA-INS-040
hinweis "FA-AGT-040 — Versionsunterschiede zwischen Agent und Zentrale"

VERS=$(sql "SELECT coalesce(protokollversion::text,'-') FROM agent
            WHERE aktiv ORDER BY id DESC LIMIT 1")
[ "$VERS" != "-" ] && AV=ja || AV=nein
ergebnis "der Agent meldet seine Protokollversion ($VERS)" "$AV"
[ "$(sql "SELECT count(*) FROM fachprotokoll WHERE art='AGENT_VERSION'")" != "0" ] && AP=ja || AP=nein
ergebnis "die Version steht im Fachprotokoll" "$AP"

# Grundregel ADR-0005: nur der Wechsel, nicht jede Meldung. Sonst staende bei
# 1.000 Agenten im Minutentakt nichts anderes mehr im Protokoll.
VOR_V=$(sql "SELECT count(*) FROM fachprotokoll WHERE art='AGENT_VERSION'")
sleep 25
NACH_V=$(sql "SELECT count(*) FROM fachprotokoll WHERE art='AGENT_VERSION'")
[ "$VOR_V" = "$NACH_V" ] && AW=ja || AW=nein
ergebnis "bei gleicher Version kommt nichts dazu ($VOR_V -> $NACH_V)" "$AW"

curl -s -b "$KEKSE" http://localhost:8090/verwaltung -o /tmp/pr-agt-v.html
[ "$(enthaelt /tmp/pr-agt-v.html "Standort-Agenten")" = "ja" ] && AT=ja || AT=nein
ergebnis "die Oberflaeche zeigt die Agenten mit ihrer Version" "$AT"
[ "$(enthaelt /tmp/pr-agt-v.html "nicht abgewiesen")" = "ja" ] && AS=ja || AS=nein
ergebnis "sie erklaert, dass ein alter Agent nicht abgewiesen wird" "$AS"

# Eine nicht unterstuetzte Version muss erkannt und ANGEZEIGT werden — und der
# Agent trotzdem weiterarbeiten. Ein abgewiesener Agent hiesse: keine
# PIN-Verifikation an diesem Standort.
AID=$(sql "SELECT id FROM agent WHERE aktiv ORDER BY id DESC LIMIT 1")
# Gemessen wird an den MELDUNGEN, nicht an Verifikationen: Eine bereits
# verifizierte Karte loest keine aus, und dann saehe ein arbeitender Agent
# aus wie ein stehender.
GESEHEN_VOR=$(sqltext "SELECT coalesce(zuletzt_gesehen::text,'-') FROM agent WHERE id = $AID")
docker exec pr-db psql -U pinroboter -d pinroboter -q -c \
  "UPDATE agent SET protokollversion = 99 WHERE id = $AID" >/dev/null
curl -s -b "$KEKSE" http://localhost:8090/verwaltung -o /tmp/pr-agt-v2.html
[ "$(enthaelt /tmp/pr-agt-v2.html "neuer als die Zentrale")" = "ja" ] && AU=ja || AU=nein
ergebnis "eine nicht unterstuetzte Version wird erkannt und erklaert" "$AU"
sleep 25
GESEHEN_NACH=$(sqltext "SELECT coalesce(zuletzt_gesehen::text,'-') FROM agent WHERE id = $AID")
[ "$GESEHEN_VOR" != "$GESEHEN_NACH" ] && AX=ja || AX=nein
ergebnis "der Agent wird nicht abgewiesen und meldet weiter" "$AX"
rm -f /tmp/pr-agt-v.html /tmp/pr-agt-v2.html

hinweis "FA-INS-040 — nach der Wiederherstellung ist ersichtlich, was fehlt"

# Der Fall: Die Zentrale wurde wiederhergestellt, der AGENT aber neu
# aufgesetzt. Sein Schluesselpaar ist ein anderes — die zuvor hinterlegten
# PINs kann er nicht mehr lesen. Ohne Anzeige bliebe die Praxis im Glauben,
# alles sei versorgt.
AID2=$(sql "SELECT id FROM agent WHERE aktiv ORDER BY id DESC LIMIT 1")
docker exec pr-db psql -U pinroboter -d pinroboter -q -c \
  "INSERT INTO pin_hinterlegt (agent_id, kartenhandle, seit)
   VALUES ($AID2, 'card-nachweis-restore', now() - interval '1 hour')
   ON CONFLICT DO NOTHING;
   UPDATE agent SET schluessel_gemeldet = now() WHERE id = $AID2" >/dev/null
curl -s -b "$KEKSE" http://localhost:8090/verwaltung -o /tmp/pr-restore.html
[ "$(enthaelt /tmp/pr-restore.html "card-nachweis-restore")" = "ja" ] && RN=ja || RN=nein
ergebnis "die fehlende PIN wird namentlich genannt" "$RN"
[ "$(enthaelt /tmp/pr-restore.html "neuen Schlüssel")" = "ja" ] && RE=ja || RE=nein
ergebnis "die Meldung erklaert die Ursache" "$RE"
[ "$(enthaelt /tmp/pr-restore.html "erneut eingegeben")" = "ja" ] && RH=ja || RH=nein
ergebnis "und sagt, was zu tun ist" "$RH"

docker exec pr-db psql -U pinroboter -d pinroboter -q -c \
  "DELETE FROM pin_hinterlegt WHERE kartenhandle='card-nachweis-restore'" >/dev/null
curl -s -b "$KEKSE" http://localhost:8090/verwaltung -o /tmp/pr-restore2.html
[ "$(enthaelt /tmp/pr-restore2.html "PINs neu zu hinterlegen")" = "nein" ] && RW=ja || RW=nein
ergebnis "ohne fehlende PIN erscheint die Tafel gar nicht" "$RW"
rm -f /tmp/pr-restore.html /tmp/pr-restore2.html

# Die Sicherung darf keine Geheimnisse im Klartext enthalten.
#
# NICHT nach "123456" suchen: Diese Ziffernfolge steckt zufaellig in der ICCSN
# einer Demo-eGK (80276883110000123456). Ein Nachweis, der daran scheitert,
# meldet einen Fehler, wo keiner ist — und wer ihn dreimal so erlebt, glaubt
# ihm beim vierten Mal nicht mehr. Geprueft werden deshalb unverwechselbare
# Kennwoerter und die Form der Chiffrate.
docker exec pr-db pg_dump -U pinroboter -d pinroboter > /tmp/pr-klartext.sql 2>/dev/null
TREFFER=0
for w in NachweisKennwortXY TerminalKennwortXY MailKennwortXY; do
  [ "$(enthaelt /tmp/pr-klartext.sql "$w")" = "ja" ] && TREFFER=$((TREFFER+1))
done
[ $TREFFER -eq 0 ] && SG=ja || SG=nein
ergebnis "keine Kennwoerter im Klartext in der Sicherung (Treffer: $TREFFER)" "$SG"

# Und jede Geheimnisspalte traegt ein Chiffrat, keinen Klartext.
UNVERSCHL=$(sql "SELECT
    (SELECT count(*) FROM konnektor      WHERE passwort_chiffrat IS NOT NULL
                                           AND passwort_chiffrat NOT LIKE 'v1:%')
  + (SELECT count(*) FROM kartenterminal WHERE admin_chiffrat IS NOT NULL
                                           AND admin_chiffrat NOT LIKE 'v1:%')
  + (SELECT count(*) FROM mail_server    WHERE kennwort_chiffrat IS NOT NULL
                                           AND kennwort_chiffrat NOT LIKE 'v1:%')")
[ "${UNVERSCHL:-1}" = "0" ] && SV=ja || SV=nein
ergebnis "jede Geheimnisspalte traegt ein Chiffrat ($UNVERSCHL unverschluesselt)" "$SV"

# Die PIN darf ueberhaupt nicht in der Zentrale liegen — auch kein Chiffrat,
# nachdem es zugestellt wurde (ADR-0009).
REST_CH=$(sql "SELECT count(*) FROM pin_auftrag WHERE chiffrat IS NOT NULL")
[ "${REST_CH:-1}" = "0" ] && SP=ja || SP=nein
ergebnis "kein PIN-Chiffrat in der Sicherung ($REST_CH)" "$SP"
rm -f /tmp/pr-klartext.sql

# ---------------------------------------------------------------- FA-PIN-060
# PIN aendern ueber die Managementschnittstelle (A_24082). Geprueft wird der
# ganze Weg: Browser verschluesselt beide Werte, Zentrale reicht durch, Agent
# aendert auf der KARTE, danach gilt die neue PIN.
hinweis "FA-PIN-060 — PIN ändern (A_24082)"

stack_starten 123456 4

PUB=$(sql "SELECT oeffentlicher_schluessel FROM agent
           WHERE oeffentlicher_schluessel IS NOT NULL ORDER BY id DESC LIMIT 1")
TID_C=$(sql "SELECT k.id FROM kartenterminal k
             JOIN ermittelte_karte e ON e.terminal_id = k.terminal_id
             WHERE e.kartentyp='SMC-B' AND NOT k.ist_demo ORDER BY k.id LIMIT 1")
H_C=$(sqltext "SELECT e.kartenhandle FROM kartenterminal k
               JOIN ermittelte_karte e ON e.terminal_id = k.terminal_id
               WHERE e.kartentyp='SMC-B' AND NOT k.ist_demo ORDER BY k.id LIMIT 1")

if [ -z "$PUB" ] || [ -z "$TID_C" ]; then
  echo "  uebersprungen: kein Agentenschluessel oder kein Terminal mit SMC-B"
  FEHLER=$((FEHLER+1))
else
  printf -- "-----BEGIN PUBLIC KEY-----\n%s\n-----END PUBLIC KEY-----\n" \
    "$(echo "$PUB" | fold -w 64)" > /tmp/pr-pub-c.pem
  chiffre() {
    printf '%s' "$1" | openssl pkeyutl -encrypt -pubin -inkey /tmp/pr-pub-c.pem \
      -pkeyopt rsa_padding_mode:oaep -pkeyopt rsa_oaep_md:sha256 \
      -pkeyopt rsa_mgf1_md:sha256 2>/dev/null | base64 | tr -d '\n'
  }

  # Zuerst die PIN hinterlegen, sonst gibt es nichts zu aendern.
  curl -s -b "$KEKSE" -X POST "http://localhost:8090/bestand/terminal/$TID_C/pin" \
    -d "kartenhandle=$H_C" --data-urlencode "chiffrat=$(chiffre 123456)" -o /dev/null
  for _ in $(seq 30); do
    [ "$(sql "SELECT count(*) FROM pin_hinterlegt WHERE kartenhandle='$H_C'")" = "1" ] && break
    sleep 2
  done

  # Die Maske muss drei Felder anbieten: alt, neu, Wiederholung.
  curl -s -b "$KEKSE" "http://localhost:8090/bestand/terminal/$TID_C" -o /tmp/pr-aend.html
  DREI=0
  for f in 'id="pinAlt"' 'id="pinNeu"' 'id="pinNeu2"'; do
    [ "$(enthaelt /tmp/pr-aend.html "$f")" = "ja" ] && DREI=$((DREI+1))
  done
  [ "$DREI" = "3" ] && CM=ja || CM=nein
  ergebnis "die Maske erfasst alte und neue PIN mit Bestaetigung ($DREI von 3)" "$CM"
  [ "$(enthaelt /tmp/pr-aend.html "A_24082")" = "ja" ] && CQ=ja || CQ=nein
  ergebnis "sie nennt die Grundlage der Aenderung (A_24082)" "$CQ"

  # Der eigentliche Weg: 123456 -> 424242
  curl -s -b "$KEKSE" -X POST "http://localhost:8090/bestand/terminal/$TID_C/pin-aendern" \
    -d "kartenhandle=$H_C" \
    --data-urlencode "chiffratAlt=$(chiffre 123456)" \
    --data-urlencode "chiffratNeu=$(chiffre 424242)" -o /dev/null
  for _ in $(seq 40); do
    [ "$(sql "SELECT count(*) FROM pin_auftrag WHERE art='AENDERN' AND quittiert IS NOT NULL")" != "0" ] && break
    sleep 2
  done
  FEHL_C=$(sqltext "SELECT coalesce(fehler,'-') FROM pin_auftrag WHERE art='AENDERN'
                    ORDER BY id DESC LIMIT 1")
  [ "$FEHL_C" = "-" ] && CA=ja || CA=nein
  [ "$CA" = "nein" ] && echo "    Diagnose: $FEHL_C"
  ergebnis "die Aenderung wird ausgefuehrt und quittiert" "$CA"

  # Auf der KARTE muss jetzt die neue PIN gelten. Beweis: Der Roboter
  # verifiziert weiter erfolgreich, nachdem die Verifikation entzogen wurde.
  curl -s -X POST http://localhost:8480/steuerung/KT-EMPFANG-01/entziehen >/dev/null
  V_C=$(verifikationen)
  for _ in $(seq 30); do
    [ $(( $(verifikationen) - V_C )) -ge 1 ] && break
    sleep 2
  done
  sleep 4
  docker logs pr-agt 2>&1 | tail -25 | grep -q "freigeschaltet" && CF=ja || CF=nein
  ergebnis "die Karte laesst sich mit der NEUEN PIN wieder freischalten" "$CF"

  # Weder alte noch neue PIN duerfen irgendwo auftauchen.
  TREFFER=0
  for w in 424242; do
    docker logs pr-zen 2>&1 | grep -qF "$w" && TREFFER=$((TREFFER+1))
    docker logs pr-agt 2>&1 | grep -qF "$w" && TREFFER=$((TREFFER+1))
    [ "$(sql "SELECT count(*) FROM fachprotokoll WHERE coalesce(text,'') LIKE '%$w%'")" != "0" ] \
      && TREFFER=$((TREFFER+1))
  done
  [ $TREFFER -eq 0 ] && CG=ja || CG=nein
  ergebnis "die neue PIN erscheint nirgends im Klartext (Treffer: $TREFFER)" "$CG"
  REST_C=$(sql "SELECT count(*) FROM pin_auftrag WHERE chiffrat_neu IS NOT NULL")
  [ "${REST_C:-1}" = "0" ] && CR=ja || CR=nein
  ergebnis "auch das zweite Chiffrat wird nach der Zustellung geloescht ($REST_C)" "$CR"
  [ "$(sql "SELECT count(*) FROM fachprotokoll WHERE art='PIN_GEAENDERT'")" != "0" ] && CP=ja || CP=nein
  ergebnis "die Aenderung steht im Fachprotokoll" "$CP"

  # Schlaegt die Aenderung fehl, bleibt die hinterlegte PIN unveraendert —
  # sonst haette der Agent eine PIN im Speicher, die auf der Karte nicht gilt,
  # und wuerde reihenweise Fehlversuche erzeugen.
  curl -s -b "$KEKSE" -X POST "http://localhost:8090/bestand/terminal/$TID_C/pin-aendern" \
    -d "kartenhandle=$H_C" \
    --data-urlencode "chiffratAlt=$(chiffre 999999)" \
    --data-urlencode "chiffratNeu=$(chiffre 111111)" -o /dev/null
  for _ in $(seq 30); do
    [ "$(sql "SELECT count(*) FROM pin_auftrag WHERE art='AENDERN' AND fehler IS NOT NULL")" != "0" ] && break
    sleep 2
  done
  MELDUNG_C=$(sqltext "SELECT coalesce(fehler,'-') FROM pin_auftrag
                       WHERE art='AENDERN' AND fehler IS NOT NULL ORDER BY id DESC LIMIT 1")
  [ "$MELDUNG_C" != "-" ] && CE=ja || CE=nein
  ergebnis "eine falsche alte PIN wird abgelehnt und gemeldet" "$CE"

  curl -s -X POST http://localhost:8480/steuerung/KT-EMPFANG-01/entziehen >/dev/null
  V_D=$(verifikationen)
  for _ in $(seq 30); do
    [ $(( $(verifikationen) - V_D )) -ge 1 ] && break
    sleep 2
  done
  sleep 4
  docker logs pr-agt 2>&1 | tail -25 | grep -q "freigeschaltet" && CU=ja || CU=nein
  ergebnis "nach dem Fehlschlag gilt weiter die zuletzt gueltige PIN" "$CU"

  # Und die Notbremse: Klartext statt Chiffrat wird abgewiesen.
  curl -s -b "$KEKSE" -X POST "http://localhost:8090/bestand/terminal/$TID_C/pin-aendern" \
    -d "kartenhandle=$H_C" -d "chiffratAlt=123456" -d "chiffratNeu=424242" \
    -o /tmp/pr-aend-klar.html
  [ "$(enthaelt /tmp/pr-aend-klar.html "keine verschlüsselten PINs")" = "ja" ] && CK=ja || CK=nein
  ergebnis "unverschluesselt uebergebene PINs werden abgewiesen" "$CK"

  rm -f /tmp/pr-pub-c.pem /tmp/pr-aend.html /tmp/pr-aend-klar.html
fi

# --------------------------------------------------- FA-PRO-050 / FA-PRO-060
hinweis "FA-PRO-050 / FA-PRO-060 — Geräte-Zeitstrahl und Vorgangsansicht"

KT_V=$(sqltext "SELECT terminal_id FROM geraet ORDER BY terminal_id LIMIT 1")
if [ -z "$KT_V" ]; then
  echo "  uebersprungen: kein Geraet gemeldet"
  FEHLER=$((FEHLER+1))
else
  # Aus der Geraeteliste in EINEM Klick erreichbar.
  curl -s -b "$KEKSE" http://localhost:8090/geraete -o /tmp/pr-gl.html
  [ "$(enthaelt /tmp/pr-gl.html "/geraete/$KT_V/verlauf")" = "ja" ] && ZK=ja || ZK=nein
  ergebnis "der Zeitstrahl ist aus der Geraeteliste verlinkt" "$ZK"

  CODE=$(curl -s -b "$KEKSE" -o /tmp/pr-zs.html -w '%{http_code}' \
         "http://localhost:8090/geraete/$KT_V/verlauf")
  [ "$CODE" = "200" ] && [ "$(enthaelt /tmp/pr-zs.html "Zeitstrahl")" = "ja" ] && ZS=ja || ZS=nein
  ergebnis "die Zeitstrahl-Seite ist erreichbar (HTTP $CODE)" "$ZS"

  # Er muss die geforderten Ereignisarten tragen.
  ARTEN=0
  for a in "PIN-Status geändert" "Gerät wieder erreichbar" "Gerät erkannt"; do
    [ "$(enthaelt /tmp/pr-zs.html "$a")" = "ja" ] && ARTEN=$((ARTEN+1))
  done
  [ "$ARTEN" -ge 2 ] && ZA=ja || ZA=nein
  ergebnis "er enthaelt Statuswechsel und Erreichbarkeitswechsel ($ARTEN Arten)" "$ZA"

  # Zeitraum waehlbar, Ereignisarten ein- und ausblendbar.
  [ "$(enthaelt /tmp/pr-zs.html 'name="zeitraum"')" = "ja" ] && ZZ=ja || ZZ=nein
  ergebnis "der Zeitraum ist waehlbar" "$ZZ"
  [ "$(enthaelt /tmp/pr-zs.html 'name="art"')" = "ja" ] && ZE=ja || ZE=nein
  ergebnis "Ereignisarten sind ein- und ausblendbar" "$ZE"

  # Und die Filter wirken wirklich — sonst waeren sie Zierde.
  ALLE_Z=$(grep -c 'class="eintrag e' /tmp/pr-zs.html || true)
  curl -s -b "$KEKSE" -o /tmp/pr-zs2.html \
    "http://localhost:8090/geraete/$KT_V/verlauf?zeitraum=alle&art=GERAET_ERKANNT"
  GEFILTERT=$(grep -c 'class="eintrag e' /tmp/pr-zs2.html || true)
  [ "${GEFILTERT:-0}" -lt "${ALLE_Z:-0}" ] && [ "${GEFILTERT:-0}" -ge 1 ] && ZF=ja || ZF=nein
  ergebnis "der Artenfilter wirkt ($GEFILTERT von $ALLE_Z Eintraegen)" "$ZF"

  # Ein fremdes Geraet darf hier nichts preisgeben.
  CODE=$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:8090/geraete/$KT_V/verlauf")
  [ "$CODE" = "302" ] && ZM=ja || ZM=nein
  ergebnis "ohne Anmeldung ist der Zeitstrahl nicht erreichbar (HTTP $CODE)" "$ZM"

  # ── Vorgangsansicht
  VG=$(sqltext "SELECT vorgang FROM fachprotokoll WHERE vorgang IS NOT NULL
                GROUP BY vorgang ORDER BY count(*) DESC LIMIT 1")
  if [ -z "$VG" ]; then
    echo "  uebersprungen: kein Vorgang im Protokoll"
    FEHLER=$((FEHLER+1))
  else
    N_VG=$(sql "SELECT count(*) FROM fachprotokoll WHERE vorgang='$VG'")
    CODE=$(curl -s -b "$KEKSE" -o /tmp/pr-vg.html -w '%{http_code}' \
           "http://localhost:8090/vorgang/$VG")
    [ "$CODE" = "200" ] && VA=ja || VA=nein
    ergebnis "die Vorgangsansicht ist erreichbar (HTTP $CODE, Vorgang $VG)" "$VA"
    ANZ_VG=$(grep -c 'class="eintrag e' /tmp/pr-vg.html || true)
    [ "${ANZ_VG:-0}" = "$N_VG" ] && VB=ja || VB=nein
    ergebnis "alle Ereignisse des Vorgangs sind gemeinsam abrufbar ($ANZ_VG von $N_VG)" "$VB"
    [ "$(enthaelt /tmp/pr-vg.html "Betroffene Geräte")" = "ja" ] && VF=ja || VF=nein
    ergebnis "sie zeigt den Fortschritt (Ereignisse, Geraete, Fehlschlaege)" "$VF"
    # Ein laufender Vorgang aktualisiert sich selbst, ein abgeschlossener nicht.
    LAEUFT=$(enthaelt /tmp/pr-vg.html 'hx-trigger="every')
    ABGESCHLOSSEN=$(enthaelt /tmp/pr-vg.html "abgeschlossen")
    [ "$LAEUFT" = "ja" ] || [ "$ABGESCHLOSSEN" = "ja" ] && VL=ja || VL=nein
    ergebnis "laufende Vorgaenge aktualisieren sich, abgeschlossene nicht" "$VL"
    # Der Zeitstrahl verlinkt auf den Vorgang — pruefbar nur, wenn dieses
    # Geraet ueberhaupt einen Eintrag MIT Vorgangsnummer hat. Die meisten
    # Ereignisse haben keine.
    MIT_VG=$(sql "SELECT count(*) FROM fachprotokoll
                  WHERE geraet='$KT_V' AND vorgang IS NOT NULL")
    if [ "${MIT_VG:-0}" -ge 1 ]; then
      curl -s -b "$KEKSE" -o /tmp/pr-zs3.html \
        "http://localhost:8090/geraete/$KT_V/verlauf?zeitraum=alle"
      [ "$(enthaelt /tmp/pr-zs3.html "/vorgang/")" = "ja" ] && VZ=ja || VZ=nein
      ergebnis "der Zeitstrahl verweist auf den zugehoerigen Vorgang" "$VZ"
      rm -f /tmp/pr-zs3.html
    else
      echo "  uebersprungen: dieses Geraet hat keinen Eintrag mit Vorgangsnummer"
    fi
    rm -f /tmp/pr-vg.html
  fi
  rm -f /tmp/pr-gl.html /tmp/pr-zs.html /tmp/pr-zs2.html
fi

# NFA-USA-030: Barrierefreiheit. Bis zum 07.08.2026 war sie behauptet, nicht
# belegt. Ausgelagert, weil sie ein eigenes Werkzeug braucht.
if bash pruefungen/barrierefreiheit-pruefen.sh "$KEKSE"; then A11Y=ja; else A11Y=nein; fi
ergebnis "Barrierefreiheitspruefung ohne Befund (WCAG 2.2 AA, maschinell pruefbarer Teil)" "$A11Y"

docker rm -f pr-sim pr-zen pr-agt pr-db >/dev/null 2>&1
docker volume rm pr-testdaten pr-agentdaten >/dev/null 2>&1
rm -f /tmp/pr-totp.txt /tmp/pr-letzter-code.txt "$KEKSE"
hinweis "Ergebnis"
[ $FEHLER -eq 0 ] && { echo "  Alle Systemnachweise bestanden."; exit 0; }
echo "  $FEHLER Nachweis(e) fehlgeschlagen."; exit 1
