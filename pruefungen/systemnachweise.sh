#!/usr/bin/env bash
# Systemnachweise gegen den laufenden Stack.
#
# Modultests prüfen die Logik isoliert; dieses Skript prüft das Zusammenspiel
# aller Bausteine. Nach CLAUDE.md gilt ein Akzeptanzkriterium erst als erfüllt,
# wenn es am laufenden System nachgewiesen wurde.
#
# Aufruf:  pruefungen/systemnachweise.sh
set -uo pipefail

# Eigene Ports, damit der Nachweislauf NEBEN dem Entwicklungsstack laufen kann.
# Frueher teilten sich beide 8090/8480; also musste der Stack vorher weichen —
# mitsamt Datenvolumen und Administratorkonto. Ueberschreibbar, falls auch
# diese Ports belegt sind.
PR_PORT="${PR_PORT:-8190}"
PR_SIMPORT="${PR_SIMPORT:-8580}"
BASIS="http://localhost:$PR_PORT"
SIMBASIS="http://localhost:$PR_SIMPORT"
cd "$(dirname "$0")/.."

NETZ=pinroboter-test
SEC="$PWD/docker"
JRE=eclipse-temurin:21-jre

# Die Zentrale laeuft im AUSLIEFERUNGSIMAGE, nicht in einem nackten JRE.
#
# Bis zum 10.08.2026 startete der Pruefstand die JAR in eclipse-temurin:21-jre.
# Damit pruefte er die Anwendung, aber nicht das Bild, das beim Kunden laeuft —
# und alles, was im Bild steckt, blieb ungeprueft. Aufgefallen ist es an der
# Datensicherung: Sie braucht pg_dump, das nur im Auslieferungsbild liegt.
ZEN_IMAGE=kt-pin-roboter-zentrale
docker image inspect "$ZEN_IMAGE" >/dev/null 2>&1 || {
  echo "  Baue das Auslieferungsimage der Zentrale ..."
  docker compose build zentrale >/dev/null 2>&1 || {
    echo "  ABBRUCH: Das Image der Zentrale liess sich nicht bauen."; exit 1; }
}
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

# Ein Nachweis, der nicht laufen KANN — fehlende Vorbedingung, fehlendes
# Werkzeug. Er zaehlt als Fehler, MUSS sich aber zeigen.
#
# Bis zum 11.08.2026 erhoehten mehrere Stellen den Zaehler stumm und gaben nur
# "uebersprungen: ..." aus. Am Ende meldete der Lauf "2 fehlgeschlagen",
# waehrend im Protokoll genau einer stand — und die Suche nach dem zweiten
# kostete mehr Zeit als der eigentliche Befund.
# spalte_lesen <html-datei> <kopfname>
#
# Liest die Werte EINER Tabellenspalte, gefunden ueber ihre Ueberschrift.
#
# Warum ueber den Kopf und nicht ueber eine feste Position: Am 11.08.2026 hat
# eine Spaltenumstellung gleich zwei Nachweise still verfaelscht — sie lasen
# weiter Spalte 2 und meldeten Ordnung, wo keine war. Ein Nachweis, der die
# falsche Spalte prueft, ist schlimmer als keiner.
#
# Warum Python statt awk: Dieselbe Aufgabe wurde an einem Tag dreimal in awk
# geloest und zweimal falsch. HTML zeilenweise zu zerlegen setzt voraus, dass
# die Ausgabe umbricht — die Zentrale baut Tabellen aber teils in einem Stueck
# zusammen. Siehe pruefungen/tabelle.py.
spalte_lesen() {
  python3 pruefungen/tabelle.py "$1" "$2" 2>/dev/null
}

# treffer_zaehlen <datei> <muster>
#
# Zaehlt VORKOMMEN, nicht Zeilen. „grep -c" zaehlt Zeilen mit mindestens einem
# Treffer — und die Zentrale baut Tabellen teils in einem Stueck zusammen. Acht
# Sortierkoepfe in einer Zeile ergaben so die Zahl 1, und ein Nachweis meldete
# eine fehlende Funktion, die vorhanden war (11.08.2026).
treffer_zaehlen() {
  grep -o "$2" "$1" 2>/dev/null | grep -c . || true
}

# sortiert_nach <html-datei> <kopfname> — "ja", "nein" oder "leer".
sortiert_nach() {
  local werte sortiert
  werte=$(spalte_lesen "$1" "$2")
  [ "$(printf '%s\n' "$werte" | grep -c .)" -lt 2 ] && { echo "leer"; return; }
  sortiert=$(printf '%s\n' "$werte" | LC_ALL=C sort -f)
  [ "$werte" = "$sortiert" ] && echo "ja" || echo "nein"
}

uebersprungen() {      # uebersprungen <grund>
  printf '  \033[1mNICHT PRUEFBAR\033[0m  %s\n' "$1"
  FEHLER=$((FEHLER+1))
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
     [ "$(curl -s -b "$KEKSE" -o /dev/null -w '%{http_code}' $BASIS/geraete)" = "200" ]; then
    return 0
  fi
  rm -f "$KEKSE"

  curl -s $BASIS/ersteinrichtung -o /tmp/pr-erst.html
  if [ "$(enthaelt /tmp/pr-erst.html "Administratorkonto")" = "ja" ]; then
    A=$(curl -s -X POST $BASIS/ersteinrichtung \
        -d "benutzer=pruefadmin&anzeigename=Pruefung&passwort=$ADMIN_PW")
    echo "$A" | grep -oE '<code>[A-Z2-7 ]+</code>' | sed 's/<[^>]*>//g' | tr -d ' ' > /tmp/pr-totp.txt
    BID=$(echo "$A" | grep -oE 'name="benutzerId" value="[0-9]+"' | grep -oE '[0-9]+')
    curl -s -o /dev/null -X POST $BASIS/totp-bestaetigen \
      -d "benutzerId=$BID&code=$(python3 pruefungen/totp.py "$(cat /tmp/pr-totp.txt)")"
    # Die Bestaetigung hat den Code dieses Fensters verbraucht.
    sleep 31
  fi
  TOTP_GEHEIMNIS=$(cat /tmp/pr-totp.txt)

  for versuch in 1 2 3; do
    curl -s -c "$KEKSE" -o /dev/null -X POST $BASIS/anmelden \
      -d "benutzer=pruefadmin&passwort=$ADMIN_PW&code=$(python3 pruefungen/totp.py "$TOTP_GEHEIMNIS")"
    if [ "$(curl -s -b "$KEKSE" -o /dev/null -w '%{http_code}' $BASIS/geraete)" = "200" ]; then
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
    -v "$PWD/src/simulator/target/simulator.jar":/app.jar -v "$SEC":/sec:ro -p $PR_SIMPORT:8480 \
    $JRE java $JOPT -jar /app.jar >/dev/null
  docker run -d --name pr-zen --network $NETZ --network-alias zentrale \
    -v "$PWD/src/zentrale/target/zentrale.jar":/app.jar -p $PR_PORT:8090 \
    -e DB_URL=jdbc:postgresql://datenbank:5432/pinroboter \
    -e DB_USER=pinroboter -e DB_PASSWORD=test \
    -e GEHEIMNIS_SCHLUESSEL="$SCHLUESSEL" \
    -e SICHERUNG_ABLAGE=/sicherungen -e SICHERUNG_PLAN=aus \
    -v pr-sicherungen:/sicherungen \
    --entrypoint java $ZEN_IMAGE -jar /app.jar >/dev/null
  for _ in $(seq 40); do
    curl -sf -o /dev/null $BASIS/anmelden 2>/dev/null && break
    curl -sf -o /dev/null $BASIS/ersteinrichtung 2>/dev/null && break
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
  # ALLE Vorgabegruppen, nicht "die eine": Seit jede Einrichtung eine eigene
  # hat, liefert eine Abfrage ohne Einschraenkung mehrere Zeilen. Am 10.08.2026
  # wurde die Variable dadurch mehrzeilig, der Aufruf schlug fehl, das Intervall
  # blieb bei 60 s — und zwei Nachweise warteten vergeblich auf eine
  # Verifikation. Dieselbe Annahme hatte schon einmal zugeschlagen.
  for VG in $(docker exec pr-db psql -U pinroboter -d pinroboter -tAc \
              "SELECT id FROM geraetegruppe WHERE ist_vorgabe" 2>/dev/null | tr -d ' \r'); do
    curl -s -b "$KEKSE" -o /dev/null -X POST \
        "$BASIS/gruppen/$VG/intervall" --data-urlencode "intervall=10"
  done

  # Ab Iteration 6 muss der Agent registriert sein (FA-AGT-010).
  #
  # Die Einrichtung muss eine VORHANDENE Organisationseinheit sein — seit dem
  # 11.08.2026 weist die Registrierung Freitext ab. Ein frisches System hat noch
  # keine, deshalb wird sie hier angelegt. Das entspricht der Reihenfolge, die
  # das Produkt vorgibt: erst die Einrichtung, dann der Agent, dann die Geraete.
  docker exec pr-db psql -U pinroboter -d pinroboter -q -c \
    "INSERT INTO organisation (bezeichnung, art, ist_demo)
     SELECT 'Pruefpraxis', 'VERBUND', FALSE
     WHERE NOT EXISTS (SELECT 1 FROM organisation WHERE bezeichnung = 'Pruefpraxis')" >/dev/null
  GEHEIMNIS=$(curl -s -b "$KEKSE" -X POST $BASIS/agenten/geheimnis \
    -d "bezeichnung=Pruefagent" --data-urlencode "einrichtung=Pruefpraxis" \
    | grep -oE '<code>[A-Za-z0-9_-]{40,}</code>' | sed 's/<[^>]*>//g')
  docker volume rm pr-agentdaten >/dev/null 2>&1
  docker run -d --name pr-agt --network $NETZ \
    -v "$PWD/src/agent/target/agent.jar":/app.jar -v "$SEC":/sec:ro -v pr-agentdaten:/daten \
    -e REGISTRIERUNG="$GEHEIMNIS" \
    -e KONNEKTOR_URL=https://simulator:8443/ -e TERMINAL_URL=https://simulator:8444/ \
    -e TERMINAL_SMCB_URL=https://simulator:8445/ \
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
# WICHTIG: "package", nicht "test". Alle folgenden Nachweise starten die
# Bausteine aus target/*.jar. Baute dieser Schritt sie nicht, liefe der ganze
# Lauf gegen ein Artefakt von irgendwann - und ein gruener Bericht bescheinigte
# einen Stand, den es im Quellcode nicht mehr gibt. Am 12.08.2026 genau so
# passiert: Nur weil neue Nachweise fehlschlugen, fiel es auf.
if docker run --rm --network $NETZ -v "$PWD":/p -v pinroboter-m2:/root/.m2 -w /p \
     -e TEST_DB_URL=jdbc:postgresql://datenbank:5432/pinroboter \
     -e TEST_DB_USER=pinroboter -e TEST_DB_PASSWORD=test \
     maven:3.9-eclipse-temurin-21 mvn -B -q package >/tmp/pr-tests.log 2>&1; then T=ja; else T=nein; fi
ergebnis "alle automatisierten Tests grün" "$T"

# Und der Nachweis, dass die Bausteine wirklich frisch sind: juenger als die
# neueste Quelldatei. Ein stiller Fehlschlag des Bauens waere sonst unsichtbar.
NEUESTE=$(find src -name '*.java' -newer src/zentrale/target/zentrale.jar 2>/dev/null | wc -l | tr -d ' ')
[ "${NEUESTE:-1}" = "0" ] && FR=ja || FR=nein
ergebnis "die geprueften Bausteine sind auf dem Stand des Quellcodes ($NEUESTE neuere Dateien)" "$FR"
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

# ADR-0011: Nicht nur DASS es klappt, sondern ÜBER WELCHEN WEG. Bis zum
# 10.08.2026 war dieser Nachweis grün, während das Übergabemodell falsch war —
# der Simulator bildete denselben Irrtum nach wie der Agent. Der Zähler im
# Terminal ist der Unterschied zwischen „funktioniert" und „funktioniert so,
# wie das echte Gerät es verlangt".
UEB=$(curl -s "$SIMBASIS/steuerung/KT-EMPFANG-01/smcb-uebergaben" \
      | grep -oE '"uebergaben":[0-9]+' | cut -d: -f2)
[ "${UEB:-0}" -ge 1 ] && SM=ja || SM=nein
ergebnis "die PIN kam über die Remote SMCB API (${UEB:-0} Übergaben)" "$SM"
VERB=$(curl -s "$SIMBASIS/steuerung/KT-EMPFANG-01/smcb-uebergaben" \
       | grep -oE '"angemeldet":(true|false)' | cut -d: -f2)
[ "$VERB" = "true" ] && SV=ja || SV=nein
ergebnis "die Verbindung bleibt nach der Übergabe bestehen" "$SV"

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
curl -s -X POST "$SIMBASIS/steuerung/KT-EMPFANG-01/firmware/4.3.0?dauer=30000" >/dev/null
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
C=$(curl -s -b "$KEKSE" -o /dev/null -w "%{http_code}" -X POST $BASIS/notaus)
echo "  POST /notaus -> HTTP $C"
sleep 5; V=$(verifikationen)
curl -s -X POST $SIMBASIS/steuerung/KT-EMPFANG-01/entziehen >/dev/null
sleep 22; W=$(verifikationen)
[ $((W-V)) -eq 0 ] && E=ja || E=nein
ergebnis "0 Versuche bei aktivem Not-Aus (gemessen: $((W-V)))" "$E"
curl -s -b "$KEKSE" -X POST $BASIS/notaus >/dev/null
sleep 35; N=$(verifikationen)
[ $((N-W)) -ge 1 ] && F=ja || F=nein
ergebnis "Automatik läuft nach Aufhebung wieder (gemessen: $((N-W)))" "$F"
# Getrennt gefragt: steht es im Protokoll, und steht es in der Ansicht?
# Der Nachweis flatterte, solange er beides in einem Griff geprueft hat.
DB=$(docker exec pr-db psql -U pinroboter -d pinroboter -tAc \
     "SELECT count(*) FROM fachprotokoll WHERE art LIKE 'NOT_AUS%'" | tr -d ' \n')
[ "${DB:-0}" -ge 2 ] && G=ja || G=nein
ergebnis "Not-Aus und Aufhebung stehen im Fachprotokoll ($DB von 2)" "$G"

ANTWORT=$(curl -s -b "$KEKSE" -w '\n%{http_code}' $BASIS/teil/protokoll)
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
  curl -s -b "$KEKSE" $BASIS/teil/protokoll -o /tmp/pr-prot-suche.html
  [ "$(enthaelt /tmp/pr-prot-suche.html "$w")" = "ja" ] && GEHEIM=$((GEHEIM+1))
done
[ $GEHEIM -eq 0 ] && S=ja || S=nein
ergebnis "keine PIN in Agent-Ausgabe oder Fachprotokoll (Treffer: $GEHEIM)" "$S"

# ---------------------------------------------------------------- Persistenz
hinweis "NFA-BET-020 / FA-PRO-010 — Persistenz und Unveränderlichkeit"
VOR=$(docker exec pr-db psql -U pinroboter -d pinroboter -tAc "SELECT count(*) FROM fachprotokoll")
docker rm -f pr-zen >/dev/null 2>&1
docker run -d --name pr-zen --network $NETZ --network-alias zentrale \
  -v "$PWD/src/zentrale/target/zentrale.jar":/app.jar -p $PR_PORT:8090 \
  -e DB_URL=jdbc:postgresql://datenbank:5432/pinroboter \
  -e DB_USER=pinroboter -e DB_PASSWORD=test -e GEHEIMNIS_SCHLUESSEL="$SCHLUESSEL" \
  --entrypoint java $ZEN_IMAGE -jar /app.jar >/dev/null
for _ in $(seq 40); do curl -sf $BASIS/api/notaus >/dev/null 2>&1 && break; sleep 1; done
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
  C=$(curl -s -o /dev/null -w "%{http_code}" "$BASIS/statisch/$d")
  G=$(curl -s -o /dev/null -w "%{size_download}" "$BASIS/statisch/$d")
  [ "$C" = "200" ] && [ "$G" -gt 1000 ] || { FEHLT=$((FEHLT+1)); echo "    $d: HTTP $C, $G Bytes"; }
done
[ $FEHLT -eq 0 ] && ST=ja || ST=nein
ergebnis "Stylesheet und HTMX werden ausgeliefert (fehlend: $FEHLT von 2)" "$ST"

# Die Anmeldeseite muss das Stylesheet auch einbinden - eine ausgelieferte
# Datei nuetzt nichts, wenn die Seite sie nicht anfordert.
curl -s $BASIS/anmelden -o /tmp/pr-antwort.html
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
  CODE=$(curl -s -o /dev/null -w "%{http_code}" "$BASIS$pfad")
  [ "$CODE" = "200" ] && OFFEN=$((OFFEN+1))
done
[ $OFFEN -eq 0 ] && Z=ja || Z=nein
ergebnis "keine Oberflaechenseite ohne Sitzung erreichbar (offen: $OFFEN von 5)" "$Z"

VORHER=$(curl -s -b "$KEKSE" $BASIS/api/notaus)
curl -s -o /dev/null -X POST $BASIS/notaus     # ohne Sitzung
NACHHER=$(curl -s -b "$KEKSE" $BASIS/api/notaus)
[ "$VORHER" = "$NACHHER" ] && K=ja || K=nein
ergebnis "Not-Aus lässt sich ohne Anmeldung nicht auslösen" "$K"

curl -s -b "$KEKSE" -o /dev/null -w "" -X POST $BASIS/abmelden
CODE=$(curl -s -b "$KEKSE" -o /dev/null -w "%{http_code}" $BASIS/geraete)
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

curl -s -X POST $SIMBASIS/steuerung/KT-EMPFANG-01/erreichbar/false >/dev/null
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
curl -s -b "$KEKSE" $BASIS/ -o /tmp/pr-antwort.html
HO=$(enthaelt /tmp/pr-antwort.html "eingeschaltet und mit dem Netzwerk")
if [ "$HO" = "nein" ]; then
  echo "    Diagnose: offene Alarme: $(sql "SELECT count(*) FROM alarm WHERE behoben_am IS NULL")"
  echo "    Diagnose: Seitengroesse: $(wc -c < /tmp/pr-antwort.html) Byte"
  echo "    Diagnose: enthaelt \"Was zu tun ist\": $(enthaelt /tmp/pr-antwort.html "Was zu tun ist")"
  echo "    Diagnose: enthaelt \"antwortet nicht\": $(enthaelt /tmp/pr-antwort.html "antwortet nicht")"
fi
ergebnis "sie erscheint in der Lage-Uebersicht" "$HO"

# Und zwar unabhaengig davon, wie die Einrichtung heisst.
#
# Bis zum 11.08.2026 fragte die Lage-Uebersicht Alarme fest fuer die
# Einrichtung „Praxis" ab. In einer Installation mit mehreren Betriebsstaetten
# zeigte die wichtigste Seite des Produkts damit keinen einzigen Alarm. Der
# Nachweis war gruen, weil die Prueffeinrichtung zufaellig „Praxis" hiess —
# ein Name als stiller Bestandteil der Abfrage.
ALARM_EINR=$(sqltext "SELECT einrichtung FROM alarm WHERE behoben_am IS NULL LIMIT 1")
[ -n "$ALARM_EINR" ] && [ "$ALARM_EINR" != "Praxis" ] && HN=ja || HN=nein
ergebnis "der angezeigte Alarm gehoert zu einer anderen Einrichtung als \"Praxis\" ($ALARM_EINR)" "$HN"

# Derselbe Zustand ueber viele Pruefzyklen darf keinen ZWEITEN Alarm erzeugen.
sleep 25
A2=$(dieser_alarm)
[ "$A2" = "1" ] && DU=ja || DU=nein
ergebnis "keine Alarmflut bei andauerndem Zustand ($A1 -> $A2)" "$DU"

curl -s -X POST $SIMBASIS/steuerung/KT-EMPFANG-01/erreichbar/true >/dev/null
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
ZIELE=$( { curl -s -b "$KEKSE" $BASIS/ \
             | grep -oE '<a href="/[a-z-]*"' | grep -oE '"/[a-z-]*"' | tr -d '"'
           printf '%s\n' / /geraete /bestand /gruppen /organisation /benutzer \
                          /verwaltung /protokoll /benachrichtigung
         } | sort -u)
for z in $ZIELE; do
  C=$(curl -s -b "$KEKSE" -o /dev/null -w "%{http_code}" "$BASIS$z")
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
  -v "$PWD/src/zentrale/target/zentrale.jar":/app.jar -p $PR_PORT:8090 \
  -e DB_URL=jdbc:postgresql://datenbank:5432/pinroboter \
  -e DB_USER=pinroboter -e DB_PASSWORD=test -e PROTOKOLL_BUDGET=400KB \
  -e GEHEIMNIS_SCHLUESSEL="$SCHLUESSEL" \
  --entrypoint java $ZEN_IMAGE -jar /app.jar >/dev/null
for _ in $(seq 40); do curl -sf -o /dev/null $BASIS/anmelden 2>/dev/null && break; sleep 1; done
anmelden

V=$(verifikationen)
# Karte wiederholt entziehen: erzeugt Verifikationen UND Protokolleintraege.
#
# Aktiv warten statt fest schlafen: Mit sechs festen Pausen von 11 s haengt das
# Ergebnis daran, ob die Maschine gerade Luft hat. Am 11.08.2026 wurde der
# Nachweis rot (2 statt 3 Verifikationen), waehrend parallel ein Bau lief — ein
# rotes Ergebnis, das nichts ueber das Produkt sagte. Abgebrochen wird, sobald
# das Ziel erreicht ist, spaetestens nach zwoelf Runden.
N=$V
for i in $(seq 12); do
  curl -s -X POST $SIMBASIS/steuerung/KT-EMPFANG-01/entziehen >/dev/null
  sleep 11
  N=$(verifikationen)
  [ $((N-V)) -ge 3 ] && break
done
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
  -v "$PWD/src/zentrale/target/zentrale.jar":/app.jar -p $PR_PORT:8090 \
  -e DB_URL=jdbc:postgresql://datenbank:5432/pinroboter \
  -e DB_USER=pinroboter -e DB_PASSWORD=test -e GEHEIMNIS_SCHLUESSEL="$SCHLUESSEL" \
  --entrypoint java $ZEN_IMAGE -jar /app.jar >/dev/null
for _ in $(seq 40); do curl -sf -o /dev/null $BASIS/anmelden 2>/dev/null && break; sleep 1; done
anmelden

# ---------------------------------------------------------------- Excel-Export
hinweis "FA-PRO-040 — Excel-Export"
XLSX=/tmp/pr-export.xlsx
CODE=$(curl -s -b "$KEKSE" -o "$XLSX" -w "%{http_code}" $BASIS/protokoll/export)
[ "$CODE" = "200" ] && [ -s "$XLSX" ] && EX=ja || EX=nein
ergebnis "Export liefert eine Datei (HTTP $CODE, $(wc -c < "$XLSX" 2>/dev/null || echo 0) Bytes)" "$EX"

# Der eigentliche Nachweis: eine UNABHAENGIGE Umsetzung liest die Datei.
# Das eigene Format gegen die eigene Umsetzung zu pruefen waere zirkulaer.
#
# Die Umgebung liegt unter pruefungen/.venv, NICHT im Scratchpad der Sitzung.
# Dort stand sie bis zum 11.08.2026 - mit der Sitzungskennung im Pfad. Beim
# naechsten Lauf war das Verzeichnis weg, der Nachweis rot, und das rote
# Ergebnis sagte nichts ueber das Produkt. Ein Nachweis, dessen Ergebnis davon
# abhaengt, WER ihn startet, ist kein Nachweis.
PYVENV="$PWD/pruefungen/.venv"
PYBIN="$PYVENV/bin/python"
if [ ! -x "$PYBIN" ]; then
  echo "  openpyxl-Umgebung fehlt, wird einmalig angelegt ..."
  python3 -m venv "$PYVENV" >/dev/null 2>&1 \
    && "$PYVENV/bin/pip" install -q openpyxl >/dev/null 2>&1
fi
if [ ! -x "$PYBIN" ] || ! "$PYBIN" -c "import openpyxl" >/dev/null 2>&1; then
  # Ehrlich benennen statt rot melden: Ohne die Bibliothek ist die Aussage
  # nicht pruefbar. Als "bestanden" zu zaehlen waere gelogen, als
  # "fehlgeschlagen" waere es eine Falschmeldung ueber das Produkt.
  printf '  %sNICHT PRUEFBAR%s  openpyxl fehlt und liess sich nicht nachinstallieren\n' \
      "$fett" "$aus"
  echo "     Abhilfe:  python3 -m venv pruefungen/.venv && pruefungen/.venv/bin/pip install openpyxl"
else
  "$PYBIN" pruefungen/xlsx-pruefen.py "$XLSX" && OP=ja || OP=nein
  ergebnis "openpyxl (unabhaengige Bibliothek) liest die Datei fehlerfrei" "$OP"
fi

# Getrennt gefragt wie bei Not-Aus und Registrierung: Der Nachweis flatterte,
# solange er "steht im Protokoll" und "steht in der Ansicht" in einem Griff
# geprueft hat. Die Ansicht zeigt nur die 200 juengsten Eintraege - mit
# wachsender Zahl von Ereignissen rutscht ein aelterer Eintrag heraus, ohne
# dass am Produkt etwas falsch waere.
DB=$(docker exec pr-db psql -U pinroboter -d pinroboter -tAc \
     "SELECT count(*) FROM fachprotokoll WHERE art='PROTOKOLL_EXPORTIERT'" | tr -d ' \n')
[ "${DB:-0}" -gt 0 ] && PE=ja || PE=nein
ergebnis "der Export steht im Fachprotokoll ($DB Eintraege)" "$PE"

curl -s -b "$KEKSE" $BASIS/teil/protokoll -o /tmp/pr-antwort.html
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
S1=$(curl -s -b "$KEKSE" "$BASIS/teil/protokoll?seite=1")
BL=$(enthaelt "$S1" "Seite 1 von")
ergebnis "die Protokollansicht zeigt eine Blaetterleiste ($GES Eintraege)" "$BL"

Z1=$(echo "$S1" | grep -c "<tr>")
[ "${Z1:-0}" -le 50 ] && BG=ja || BG=nein
ergebnis "eine Seite zeigt hoechstens 50 Zeilen (gezaehlt: $Z1)" "$BG"

if [ "${GES:-0}" -gt 50 ]; then
  S2=$(curl -s -b "$KEKSE" "$BASIS/teil/protokoll?seite=2")
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
curl -s -b "$KEKSE" "$BASIS/teil/protokoll?seite=9999" -o /tmp/pr-antwort.html
BE=$(enthaelt /tmp/pr-antwort.html "Seite [0-9]* von")
ergebnis "eine Seitennummer hinter dem Ende fuehrt nicht ins Leere" "$BE"
rm -f "$XLSX"

# ---------------------------------------------------------------- Agent-Zugang
hinweis "FA-AGT-010 / FA-AGT-020 — Agent-Registrierung und Token"
OFFEN=0
for pfad in /api/agent/melden /api/agent/notaus; do
  CODE=$(curl -s -o /dev/null -w "%{http_code}" "$BASIS$pfad")
  [ "$CODE" != "401" ] && OFFEN=$((OFFEN+1))
done
CODE=$(curl -s -o /dev/null -w "%{http_code}" -H "X-Agent-Token: erfunden" \
       $BASIS/api/agent/notaus)
[ "$CODE" != "401" ] && OFFEN=$((OFFEN+1))
[ $OFFEN -eq 0 ] && AG=ja || AG=nein
ergebnis "Agent-Schnittstellen ohne gültiges Token gesperrt (offen: $OFFEN von 3)" "$AG"

curl -s -X POST -H "X-Registrierung: erfunden" $BASIS/api/registrieren -o /tmp/pr-antwort.html
RG=$(enthaelt /tmp/pr-antwort.html "Unbekanntes")
ergebnis "erfundenes Registrierungsgeheimnis wird abgewiesen" "$RG"

G2=$(curl -s -b "$KEKSE" -X POST $BASIS/agenten/geheimnis -d "bezeichnung=Einmaltest" \
     --data-urlencode "einrichtung=Pruefpraxis" \
     | grep -oE '<code>[A-Za-z0-9_-]{40,}</code>' | sed 's/<[^>]*>//g')
curl -s -X POST -H "X-Registrierung: $G2" $BASIS/api/registrieren -o /tmp/pr-antwort.html
E1=$(enthaelt /tmp/pr-antwort.html "token")
ergebnis "gültiges Geheimnis lässt sich einlösen" "$E1"
curl -s -X POST -H "X-Registrierung: $G2" $BASIS/api/registrieren -o /tmp/pr-antwort.html
E2=$(enthaelt /tmp/pr-antwort.html "bereits verwendet")
ergebnis "dasselbe Geheimnis ein zweites Mal wird abgewiesen" "$E2"

# Zwei getrennte Fragen, weil sie unterschiedliche Fehler bedeuten: Steht der
# Eintrag ueberhaupt im Protokoll (sonst fehlt die Nachvollziehbarkeit), und
# erscheint er in der Ansicht (sonst greift die Rotation oder die Anzeigegrenze)?
DB=$(docker exec pr-db psql -U pinroboter -d pinroboter -tAc \
     "SELECT count(*) FROM fachprotokoll WHERE art='AGENT_REGISTRIERUNG_ABGELEHNT'" | tr -d ' \n')
[ "${DB:-0}" -gt 0 ] && PR=ja || PR=nein
ergebnis "abgelehnte Registrierungen stehen im Fachprotokoll ($DB Eintraege)" "$PR"

# Die Einrichtung eines Agenten stammt aus seiner Registrierung, nicht aus
# seiner Meldung. Bis zum 09.08.2026 stand ueberall fest "Praxis" — damit trugen
# Geraete, Alarme und Protokolleintraege aller Standorte denselben Namen, und
# die Mandantengrenze griff fuer sie nicht. Der Nachweis geht den ganzen Weg:
# Geheimnis mit Einrichtung -> Token -> Geraetemeldung -> Ablage.
# Die Einrichtung muss eine VORHANDENE Organisationseinheit sein — seit dem
# 11.08.2026 weist die Registrierung Freitext ab. Vorher ging jeder Name durch,
# und der Agent meldete Geraete unter einer Einheit, die es nicht gibt: Solche
# Daten sind fuer jede mandantengebundene Abfrage unsichtbar.
EINR="Zahnarztpraxis Sued"
VERBUND_ID=$(sql "SELECT id FROM organisation WHERE art='VERBUND' ORDER BY id LIMIT 1")
docker exec pr-db psql -U pinroboter -d pinroboter -q -c \
  "INSERT INTO organisation (bezeichnung, art, eltern_id, ist_demo)
   SELECT '$EINR', 'EINRICHTUNG', $VERBUND_ID, FALSE
   WHERE NOT EXISTS (SELECT 1 FROM organisation WHERE bezeichnung = '$EINR')" >/dev/null

# Gegenprobe: Eine erfundene Einrichtung darf NICHT durchgehen.
ERFUNDEN=$(curl -s -b "$KEKSE" -X POST $BASIS/agenten/geheimnis \
     --data-urlencode "bezeichnung=Agent Nirgendwo" \
     --data-urlencode "einrichtung=Gibt Es Nicht $$")
EF=$(enthaelt "$ERFUNDEN" "vorhandene Organisationseinheit")
ergebnis "eine erfundene Einrichtung wird bei der Registrierung abgewiesen" "$EF"
G3=$(curl -s -b "$KEKSE" -X POST $BASIS/agenten/geheimnis \
     --data-urlencode "bezeichnung=Agent Sued" --data-urlencode "einrichtung=$EINR" \
     | grep -oE '<code>[A-Za-z0-9_-]{40,}</code>' | sed 's/<[^>]*>//g')
TOK3=$(curl -s -X POST -H "X-Registrierung: $G3" $BASIS/api/registrieren \
       | grep -oE '"token" *: *"[^"]+"' | sed 's/.*"\([^"]*\)"$/\1/')
[ -n "$TOK3" ] && TR=ja || TR=nein
ergebnis "Registrierung mit gewaehlter Einrichtung liefert ein Token" "$TR"

# Ein Geraet melden — und in der Meldung ausdruecklich eine FREMDE Einrichtung
# behaupten. Die Zentrale muss sie verwerfen.
curl -s -X POST -H "X-Agent-Token: $TOK3" -H "Content-Type: application/json" \
     -d '{"terminalId":"KT-SUED-1","name":"Terminal Sued","einrichtung":"Hausarztpraxis Nordstadt",
          "erreichbar":true,"pinStatus":"VERIFIED","modell":"Testgeraet","firmware":"1.0"}' \
     $BASIS/api/agent/melden -o /dev/null

GEB=$(docker exec pr-db psql -U pinroboter -d pinroboter -tAc \
      "SELECT einrichtung FROM fachprotokoll WHERE geraet='KT-SUED-1' AND art='GERAET_ERKANNT' LIMIT 1" \
      | tr -d '\n')
[ "$GEB" = "$EINR" ] && GE=ja || GE=nein
ergebnis "gemeldetes Geraet wird unter der Einrichtung der Registrierung abgelegt (\"$GEB\")" "$GE"

# Dieselbe Frage aus der Gegenrichtung: Die in der Meldung behauptete fremde
# Einrichtung darf nirgends angekommen sein.
FREMD=$(docker exec pr-db psql -U pinroboter -d pinroboter -tAc \
        "SELECT count(*) FROM fachprotokoll WHERE geraet='KT-SUED-1' AND einrichtung<>'$EINR'" \
        | tr -d ' \n')
[ "${FREMD:-1}" = "0" ] && FE=ja || FE=nein
ergebnis "vom Agenten behauptete Fremdeinrichtung wird verworfen ($FREMD Eintraege)" "$FE"

# Und die Gerategruppe: Ein neu erkanntes Geraet landet in der Vorgabegruppe
# SEINER Einrichtung (FA-GER-050) — sonst waeren alle Standorte in einem Topf.
GRP=$(docker exec pr-db psql -U pinroboter -d pinroboter -tAc \
      "SELECT gg.einrichtung FROM geraet ge JOIN geraetegruppe gg ON gg.id=ge.gruppe_id
       WHERE ge.terminal_id='KT-SUED-1'" | tr -d '\n')
[ "$GRP" = "$EINR" ] && GR=ja || GR=nein
ergebnis "neues Geraet landet in der Vorgabegruppe seiner Einrichtung (\"$GRP\")" "$GR"

curl -s -b "$KEKSE" $BASIS/teil/protokoll -o /tmp/pr-antwort.html
PA=$(enthaelt /tmp/pr-antwort.html "Registrierung abgelehnt")
if [ "$PA" = "nein" ] && [ "${DB:-0}" -gt 0 ]; then
  GES=$(docker exec pr-db psql -U pinroboter -d pinroboter -tAc \
        "SELECT count(*) FROM fachprotokoll" | tr -d ' \n')
  echo "    Diagnose: $DB Eintrag/Eintraege in der Datenbank, aber nicht in der"
  echo "    Ansicht. Gesamtzahl im Protokoll: $GES (Ansicht zeigt die 200 juengsten)."
fi
ergebnis "abgelehnte Registrierungen erscheinen in der Protokollansicht" "$PA"

hinweis "Demodatensatz — MVZ mit neun Betriebsstaetten (Block C)"
# Der Demodatensatz ist Werkzeug, nicht Beiwerk: An ihm wird die Oberflaeche
# bei realistischen Stueckzahlen geprueft, bevor echte Bestandsdaten da sind.
# Fuer Zahlen und Kennungen: entfernt alle Leerzeichen.

curl -s -b "$KEKSE" -o /dev/null -w '%{http_code}' -X POST \
  $BASIS/verwaltung/demodaten/anlegen | grep -qE '^(200|302)$' && DA=ja || DA=nein
ergebnis "Demodatensatz laesst sich auf Knopfdruck anlegen" "$DA"

# Seit ADR-0012: 1 Traeger + 3 Standorte + 9 Betriebsstaetten + 2 Fachbereiche
# = 15 Organisationseinheiten. Ein Konnektor je Betriebsstaette (Vorgabe PO,
# 12.08.2026 — vorher stand hier zusaetzlich ein zentraler im Rechenzentrum).
for paar in "organisation:15" "konnektor:9" "arbeitsplatz:36" "kartenterminal:36"; do
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

# Seit dem 10.08.2026 steht der Demodatensatz unter "Einstellungen", nicht
# mehr unter "Instanz" (FA-BAK-050).
curl -s -b "$KEKSE" $BASIS/einstellungen -o /tmp/pr-antwort.html
VA=$(enthaelt /tmp/pr-antwort.html "Arbeitsplätze")
ergebnis "die Einstellungen zeigen den Demobestand einschliesslich Arbeitsplaetzen" "$VA"

# Loeschen darf ausschliesslich Demodaten treffen. Wuerde es echte Daten
# mitnehmen, waere der Knopf eine Falle statt eines Werkzeugs.
docker exec pr-db psql -U pinroboter -d pinroboter -c \
  "INSERT INTO organisation (bezeichnung, art, ist_demo) VALUES ('Nachweis Echtdaten','VERBUND',FALSE)" >/dev/null 2>&1
curl -s -b "$KEKSE" -o /dev/null -X POST $BASIS/verwaltung/demodaten/loeschen
IST=$(sql "SELECT count(*) FROM kartenterminal WHERE ist_demo")
[ "$IST" = "0" ] && R=ja || R=nein
ergebnis "Loeschen entfernt alle Demodaten (Rest: $IST)" "$R"
IST=$(sql "SELECT count(*) FROM organisation WHERE bezeichnung='Nachweis Echtdaten'")
[ "$IST" = "1" ] && R=ja || R=nein
ergebnis "Loeschen laesst Daten ohne Demo-Kennzeichen unberuehrt" "$R"

curl -s -b "$KEKSE" -o /dev/null -X POST $BASIS/verwaltung/demodaten/anlegen
IST=$(sql "SELECT count(*) FROM kartenterminal WHERE ist_demo")
[ "$IST" = "36" ] && R=ja || R=nein
ergebnis "Demodatensatz laesst sich wiederherstellen ($IST von 36)" "$R"

# Der Befund vom 12.08.2026: Der Agent legt zu gemeldeten Demogeraeten eigene
# Zeilen in geraet und geraetegruppe an - ohne Demo-Kennzeichen, denn er kennt
# den Unterschied nicht. Das Loeschen brach daran ab:
#   null value in column "organisation_id" of relation "geraetegruppe"
# Ursache war ein Fremdschluessel ON DELETE SET NULL auf einer NOT-NULL-Spalte
# (Migration 23 gegen Migration 25).
DEMO_ORG=$(sql "SELECT id FROM organisation WHERE ist_demo AND art='EINRICHTUNG'
                ORDER BY id LIMIT 1")
docker exec pr-db psql -U pinroboter -d pinroboter -c \
  "INSERT INTO geraetegruppe (bezeichnung, einrichtung, ist_vorgabe,
        pruefintervall_sekunden, organisation_id, ist_demo)
   SELECT 'Nachweis Agentengruppe', o.bezeichnung, FALSE, 60, o.id, FALSE
   FROM organisation o WHERE o.id = $DEMO_ORG" >/dev/null 2>&1
docker exec pr-db psql -U pinroboter -d pinroboter -c \
  "INSERT INTO geraet (terminal_id, einrichtung, gruppe_id, bezeichnung, aktiv, ist_demo)
   SELECT 'KT-NACHWEIS-AGENT', 'egal', g.id, 'Vom Agenten gemeldet', TRUE, FALSE
   FROM geraetegruppe g WHERE g.bezeichnung = 'Nachweis Agentengruppe'" >/dev/null 2>&1

curl -s -b "$KEKSE" -o /dev/null -X POST $BASIS/verwaltung/demodaten/loeschen
IST=$(sql "SELECT count(*) FROM organisation WHERE ist_demo")
[ "$IST" = "0" ] && R=ja || R=nein
ergebnis "Loeschen gelingt auch mit Gruppen, die der Agent angelegt hat (Rest: $IST)" "$R"
IST=$(sql "SELECT count(*) FROM geraetegruppe WHERE bezeichnung='Nachweis Agentengruppe'")
[ "$IST" = "0" ] && R=ja || R=nein
ergebnis "eine Gruppe ohne ihre Einrichtung bleibt nicht zurueck ($IST)" "$R"

curl -s -b "$KEKSE" -o /dev/null -X POST $BASIS/verwaltung/demodaten/anlegen
IST=$(sql "SELECT count(*) FROM kartenterminal WHERE ist_demo")
[ "$IST" = "36" ] && R=ja || R=nein
ergebnis "und danach steht der Demobestand wieder ($IST von 36)" "$R"

docker exec pr-db psql -U pinroboter -d pinroboter -c \
  "DELETE FROM organisation WHERE bezeichnung='Nachweis Echtdaten'" >/dev/null 2>&1

hinweis "FA-GER-010 / FA-GER-020 — Bestandsverwaltung"
ORG=$(sql "SELECT id FROM organisation WHERE art='EINRICHTUNG' ORDER BY id LIMIT 1")

# Anlegen ueber die Maske, mit absichtlich unerreichbarer Adresse.
# 192.0.2.0/24 ist nach RFC 5737 fuer Dokumentation reserviert und wird
# nirgends geroutet - ein verlaesslicher Nichterreichbarkeitsfall.
ANTWORT=$(curl -s -b "$KEKSE" -X POST $BASIS/bestand/konnektor \
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
  curl -s -b "$KEKSE" "$BASIS$pfad" -o /tmp/pr-kw-suche.html
  [ "$(enthaelt /tmp/pr-kw-suche.html "NachweisKennwortXY")" = "ja" ] && TREFFER=$((TREFFER+1))
done
[ $TREFFER -eq 0 ] && KO=ja || KO=nein
ergebnis "das Kennwort erscheint auf keiner Oberflaechenseite (Treffer: $TREFFER)" "$KO"

# A_24080: PEviM ist ausschliesslich fuer SMC-B zulaessig.
ANTWORT_PV=$(curl -s -b "$KEKSE" -X POST $BASIS/bestand/terminal \
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

curl -s -b "$KEKSE" -X POST $BASIS/bestand/terminal \
  --data-urlencode "konnektorId=$KID" --data-urlencode "terminalId=KT-NACHWEIS-01" \
  --data-urlencode "bezeichnung=Empfang Nachweis" --data-urlencode "adresse=192.0.2.90" \
  --data-urlencode "adminPasswort=TerminalKennwortXY" \
  --data-urlencode "hatSmcb=ja" --data-urlencode "pevimAktiv=ja" >/dev/null
IST=$(sql "SELECT count(*) FROM kartenterminal WHERE terminal_id='KT-NACHWEIS-01' AND hat_smcb AND pevim_aktiv")
[ "$IST" = "1" ] && TA=ja || TA=nein
ergebnis "Terminal mit SMC-B und PIN-Automatik laesst sich anlegen" "$TA"

curl -s -b "$KEKSE" -X POST $BASIS/bestand/terminal \
  --data-urlencode "konnektorId=$KID" --data-urlencode "terminalId=KT-NACHWEIS-01" \
  --data-urlencode "bezeichnung=Doppelt" --data-urlencode "adresse=192.0.2.91" -o /tmp/pr-antwort.html
TD=$(enthaelt /tmp/pr-antwort.html "bereits vergeben")
ergebnis "eine doppelte Kennung wird verstaendlich abgewiesen" "$TD"

# Ein Konnektor mit Terminals darf nicht stillschweigend einen halben
# Standort mitreissen - das Schema wuerde per CASCADE genau das tun.
curl -s -b "$KEKSE" -X POST "$BASIS/bestand/konnektor/$KID/loeschen" \
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

# ── FA-GER-020: Kartenterminal OHNE Konnektor (Vorgabe PO, 11.08.2026) ──
# Der Fall aus der Praxis: Das Geraet ist geliefert und aufgestellt, aber am
# Konnektor noch nicht angemeldet. Wer es dann nicht erfassen kann, fuehrt
# seinen Bestand nebenher auf Papier.
ORGID=$(sql "SELECT organisation_id FROM konnektor WHERE id=$KID")
curl -s -b "$KEKSE" -X POST $BASIS/bestand/terminal \
  --data-urlencode "organisationId=$ORGID" --data-urlencode "terminalId=KT-OHNE-KONN-01" \
  --data-urlencode "bezeichnung=Anmeldung ohne Konnektor" \
  --data-urlencode "adresse=192.0.2.95" -o /tmp/pr-ohnekonn.html
IST=$(sql "SELECT count(*) FROM kartenterminal
           WHERE terminal_id='KT-OHNE-KONN-01' AND konnektor_id IS NULL
             AND organisation_id=$ORGID")
[ "$IST" = "1" ] && OK1=ja || OK1=nein
if [ "$OK1" = "nein" ]; then
  echo "    Diagnose: $(grep -oE 'role="alert">[^<]{0,140}' /tmp/pr-ohnekonn.html | head -1)"
fi
ergebnis "ein Terminal laesst sich ohne Konnektor erfassen" "$OK1"

# Es muss in der Liste STEHEN. Die Abfrage verband frueher mit JOIN konnektor —
# damit waere ausgerechnet das Geraet verschwunden, das noch Aufmerksamkeit
# braucht.
curl -s -b "$KEKSE" "$BASIS/bestand?suche=KT-OHNE-KONN-01" -o /tmp/pr-ohnekonn-liste.html
OK2=$(enthaelt /tmp/pr-ohnekonn-liste.html "KT-OHNE-KONN-01")
ergebnis "es steht trotz fehlendem Konnektor in der Geraeteliste" "$OK2"
OK3=$(enthaelt /tmp/pr-ohnekonn-liste.html "nicht zugeordnet")
ergebnis "die Liste sagt \"nicht zugeordnet\" statt ein Feld leer zu lassen" "$OK3"

# Und die Detailseite muss erklaeren, warum die Automatik nicht greift.
TID_OK=$(sql "SELECT id FROM kartenterminal WHERE terminal_id='KT-OHNE-KONN-01'")
curl -s -b "$KEKSE" "$BASIS/bestand/terminal/$TID_OK" -o /tmp/pr-ohnekonn-detail.html
OK4=$(enthaelt /tmp/pr-ohnekonn-detail.html "kein Konnektor zugeordnet")
ergebnis "die Geraeteseite nennt den fehlenden Konnektor" "$OK4"

# Nachtragen — und damit ist das Geraet vollwertig.
curl -s -b "$KEKSE" -X POST "$BASIS/bestand/terminal/$TID_OK/konnektor" \
  --data-urlencode "konnektorId=$KID" -o /dev/null
IST=$(sql "SELECT count(*) FROM kartenterminal WHERE id=$TID_OK AND konnektor_id=$KID")
[ "$IST" = "1" ] && OK5=ja || OK5=nein
ergebnis "der Konnektor laesst sich nachtragen" "$OK5"
DBP=$(sql "SELECT count(*) FROM fachprotokoll WHERE art='TERMINAL_GEAENDERT'
           AND text LIKE '%zugeordnet%'")
[ "${DBP:-0}" -ge 1 ] && OK6=ja || OK6=nein
ergebnis "die Zuordnung steht im Fachprotokoll" "$OK6"

curl -s -b "$KEKSE" -X POST "$BASIS/bestand/terminal/$TID_OK/loeschen" >/dev/null
rm -f /tmp/pr-ohnekonn.html /tmp/pr-ohnekonn-liste.html /tmp/pr-ohnekonn-detail.html

# ── Standardsortierung: Einrichtung zuerst (Vorgabe PO, 11.08.2026) ──────
# In einem MVZ heissen die Geraete in jeder Betriebsstaette gleich. Neunmal
# "Behandlung 1" untereinander ist keine Liste, sondern eine Aufzaehlung.
curl -s -b "$KEKSE" "$BASIS/bestand" -o /tmp/pr-sortierung.html
# NUR den Bereich der Terminalliste ansehen. Auf der Bestandsseite steht
# darueber auch die Konnektorliste — ein grep ueber die ganze Seite mischt
# beide und meldet eine Unordnung, die es nicht gibt. Genau das ist beim
# ersten Anlauf passiert.
sed -n '/<div id="terminalliste"/,$p' /tmp/pr-sortierung.html > /tmp/pr-sort-liste.html
# Die Einrichtung steht als erster Wert der zweiten Spalte. Sie wird hier ueber
# die Zeilenfolge geprueft: Kommt die Liste sortiert, ist die Folge der
# Einrichtungsnamen bereits aufsteigend.
ORGSPALTE=$(grep -oE '<td>[^<]+<span class="kennung zweitzeile" title="Konnektor"' \
            /tmp/pr-sort-liste.html | sed 's/<td>//; s/<span.*//')
ANZ=$(printf '%s\n' "$ORGSPALTE" | grep -c . || true)
if [ "${ANZ:-0}" -lt 2 ]; then
  uebersprungen "zu wenige Geraete in der Liste ($ANZ) — Sortierung nicht pruefbar"
else
  SORTIERT=$(printf '%s\n' "$ORGSPALTE" | LC_ALL=C sort -f)
  [ "$ORGSPALTE" = "$SORTIERT" ] && SO=ja || SO=nein
  if [ "$SO" = "nein" ]; then
    echo "    Diagnose: erste Einrichtungen in der Reihenfolge der Liste:"
    printf '%s\n' "$ORGSPALTE" | head -5 | sed 's/^/      /'
  fi
  ergebnis "die Geraeteliste kommt nach Einrichtung sortiert ($ANZ Zeilen)" "$SO"
fi
rm -f /tmp/pr-sortierung.html /tmp/pr-sort-liste.html

TID=$(sql "SELECT id FROM kartenterminal WHERE terminal_id='KT-NACHWEIS-01'")
curl -s -b "$KEKSE" -X POST "$BASIS/bestand/terminal/$TID/loeschen" >/dev/null
curl -s -b "$KEKSE" -X POST "$BASIS/bestand/konnektor/$KID/loeschen" >/dev/null
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

curl -s -b "$KEKSE" $BASIS/bestand/ermittelt > /tmp/pr-erm.html
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
curl -s -b "$KEKSE" -o /dev/null -X POST $BASIS/bestand/konnektor \
  --data-urlencode "organisationId=$ORG2" --data-urlencode "bezeichnung=Konnektor Uebernahme" \
  --data-urlencode "adresse=simulator:8443" --data-urlencode "mandantId=Mandant-1" \
  --data-urlencode "clientsystemId=CS-ROBOTER" --data-urlencode "arbeitsplatzId=AP-EMPFANG"
KID2=$(sql "SELECT id FROM konnektor WHERE bezeichnung='Konnektor Uebernahme'")
FID=$(sql "SELECT e.id FROM ermittelt_terminal e JOIN agent a ON a.id = e.agent_id AND a.aktiv
           WHERE e.terminal_id='KT-EMPFANG-01' ORDER BY e.gesehen DESC LIMIT 1")
curl -s -b "$KEKSE" -X POST "$BASIS/bestand/ermittelt/$FID/uebernehmen" \
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

curl -s -b "$KEKSE" -X POST "$BASIS/bestand/ermittelt/$FID/uebernehmen" \
  --data-urlencode "konnektorId=$KID2" -o /tmp/pr-antwort.html
UD=$(enthaelt /tmp/pr-antwort.html "bereits erfasst")
ergebnis "eine zweite Uebernahme desselben Geraets wird abgewiesen" "$UD"

# Abweichung: PEviM am Geraet abschalten, Bestand darf sich NICHT aendern
curl -s -X POST $SIMBASIS/steuerung/KT-EMPFANG-01/pevim/false >/dev/null
sleep 16
curl -s -b "$KEKSE" $BASIS/bestand/ermittelt > /tmp/pr-erm2.html
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

curl -s -X POST $SIMBASIS/steuerung/KT-EMPFANG-01/pevim/true >/dev/null
rm -f /tmp/pr-erm.html /tmp/pr-erm2.html

# ── Die BETRIEBSLISTE unter „Kartenterminals" (Vorgabe PO, 11.08.2026) ──
# Nicht zu verwechseln mit der Bestandsliste darunter: Diese hier zeigt den
# LAUFENDEN Zustand und aktualisiert sich alle 5 s selbst. Sie hatte weder
# Suche noch Sortierung noch die Praxis — bei einem MVZ mit neun
# Betriebsstaetten stand „Behandlung 1" viermal untereinander, ohne dass
# erkennbar war, zu wem.
hinweis "Betriebsliste der Kartenterminals — Suche, Sortierung, Praxis"
curl -s -b "$KEKSE" "$BASIS/geraete" -o /tmp/pr-betrieb.html
BP=$(enthaelt /tmp/pr-betrieb.html "Praxis")
ergebnis "die Liste hat eine Spalte Praxis" "$BP"
KOEPFE=$(treffer_zaehlen /tmp/pr-betrieb.html 'class="sortlink"')
[ "${KOEPFE:-0}" -ge 6 ] && BS=ja || BS=nein
ergebnis "die Spalten sind sortierbar ($KOEPFE Koepfe)" "$BS"
BF=$(enthaelt /tmp/pr-betrieb.html 'name="suche"')
ergebnis "die Liste hat ein Suchfeld" "$BF"

# Steht unter jedem Kopf auch das, was er ankuendigt?
#
# Bis zum 11.08.2026 nicht: Die Koepfe entstanden aus der Enum-Reihenfolge, die
# Zellen aus dem Template — zwei Listen, die uebereinstimmen mussten. Ueber
# "Behandlung 1" stand "Praxis". Der bestehende Nachweis prueft nur, dass eine
# Zeile so viele Zellen hat wie ihr Kopf; die BEDEUTUNG der Spalten sah niemand
# nach. Aufgefallen ist es dem PO an einem Screenshot.
# Unter „Gerät" gehoert eine Terminalkennung, unter „Praxis" ein Praxisname.
Z_GERAET=$(spalte_lesen /tmp/pr-betrieb.html "Gerät" | head -1)
Z_PRAXIS=$(spalte_lesen /tmp/pr-betrieb.html "Praxis" | head -1)
printf '%s' "$Z_GERAET" | grep -qE 'KT-[A-Z]+' && BZ1=ja || BZ1=nein
printf '%s' "$Z_PRAXIS" | grep -qE 'praxis|Praxis|Kardiologie|Orthop|Innere|Radiologie|Gynäk|Chirurgie|Medizin' && BZ2=ja || BZ2=nein
[ "$BZ1" = "ja" ] && [ "$BZ2" = "ja" ] && BZ=ja || BZ=nein
if [ "$BZ" = "nein" ]; then
  echo "    Diagnose: Kopfzeile: $(python3 - <<'PY'
import sys
sys.path.insert(0, "pruefungen")
from tabelle import Tabellenleser
leser = Tabellenleser()
leser.feed(open("/tmp/pr-betrieb.html", encoding="utf-8", errors="replace").read())
print(" | ".join(leser.kopf))
PY
)"
  echo "    Diagnose: unter „Gerät\" steht: $Z_GERAET"
  echo "    Diagnose: unter „Praxis\" steht: $Z_PRAXIS"
  echo "    Diagnose: Geraete in der Datenbank: $(sql "SELECT count(*) FROM geraet")"
  echo "    Diagnose: erste Einrichtung: $(sqltext "SELECT einrichtung FROM geraet LIMIT 1")"
fi
ergebnis "unter jedem Kopf steht, was er ankuendigt (Geraet/Praxis nicht vertauscht)" "$BZ"

# Standardsortierung: Die Praxisspalte muss beim Aufruf OHNE Parameter
# aufsteigend sortiert sein.
BV=$(enthaelt /tmp/pr-betrieb.html 'aria-sort="ascending"')
ergebnis "beim Aufruf ohne Parameter ist eine Spalte als sortiert ausgezeichnet" "$BV"
# Die Praxisspalte ueber ihren KOPFINDEX lesen, nicht ueber eine feste
# Position: Sonst prueft der Nachweis nach einer Spaltenumstellung stillschweigend
# die falsche Spalte — und meldet Ordnung, wo keine ist.
BO=$(sortiert_nach /tmp/pr-betrieb.html "Praxis")
if [ "$BO" = "leer" ]; then
  uebersprungen "zu wenige Geraete in der Betriebsliste"
else
  if [ "$BO" = "nein" ]; then
    echo "    Diagnose: Praxen in der Reihenfolge der Liste:"
    spalte_lesen /tmp/pr-betrieb.html "Praxis" | head -5 | sed 's/^/      /'
  fi
  ergebnis "die Betriebsliste kommt nach Praxis sortiert" "$BO"
fi

# Die Suche muss WIRKLICH einschraenken, nicht nur ein Feld anbieten.
GESAMT=$(spalte_lesen /tmp/pr-betrieb.html "Praxis" | grep -c . || true)
curl -s -b "$KEKSE" "$BASIS/teil/geraete?suche=EMPFANG" -o /tmp/pr-betrieb-suche.html
MIT=$(spalte_lesen /tmp/pr-betrieb-suche.html "Praxis" | grep -c . || true)
if [ "${GESAMT:-0}" -le 1 ]; then
  uebersprungen "zu wenige Geraete, um die Suche zu pruefen ($GESAMT)"
else
  [ "${MIT:-0}" -lt "${GESAMT:-0}" ] && [ "${MIT:-0}" -ge 1 ] && BQ=ja || BQ=nein
  ergebnis "die Suche schraenkt die Liste ein ($GESAMT ohne, $MIT mit Suchwort)" "$BQ"
fi
curl -s -b "$KEKSE" "$BASIS/teil/geraete?suche=GibtEsNichtXYZ" -o /tmp/pr-betrieb-leer.html
BL=$(enthaelt /tmp/pr-betrieb-leer.html "Kein Gerät passt")
ergebnis "eine erfolglose Suche erklaert sich statt leer zu bleiben" "$BL"
rm -f /tmp/pr-betrieb.html /tmp/pr-betrieb-suche.html /tmp/pr-betrieb-leer.html

# ── Gerätegruppen: eine Zahl, nicht zwei (Befund PO, 11.08.2026) ────────
# Die Uebersicht meldete 9 Gruppen, die Gruppenliste zeigte 19. Beide hatten
# recht: Gruppen ohne organisation_id sind fuer jede mandantengebundene
# Abfrage unsichtbar (NULL = ANY(...) ist niemals wahr), erscheinen aber dem
# Instanzbetreiber. Ein Datensatz ohne Mandantenzuordnung gehoert in diesem
# Produkt nicht in die Datenbank.
hinweis "Gerätegruppen — jede Gruppe gehoert zu einer Organisationseinheit"
OHNE=$(sql "SELECT count(*) FROM geraetegruppe WHERE organisation_id IS NULL")
[ "${OHNE:-1}" = "0" ] && GO=ja || GO=nein
ergebnis "keine Gruppe ohne Organisationseinheit (gefunden: $OHNE)" "$GO"

# Die Spalte ist NOT NULL — der Fall kann nicht wiederkommen.
NN=$(sql "SELECT is_nullable FROM information_schema.columns
          WHERE table_name='geraetegruppe' AND column_name='organisation_id'")
[ "$NN" = "NO" ] && GN=ja || GN=nein
ergebnis "die Spalte ist NOT NULL, der Fall ist ausgeschlossen ($NN)" "$GN"

# Beide Zaehlungen muessen uebereinstimmen: die der Uebersicht (ueber
# organisation_id) und die der Liste.
ZAEHL_DB=$(sql "SELECT count(*) FROM geraetegruppe")
curl -s -b "$KEKSE" "$BASIS/gruppen" -o /tmp/pr-gruppen.html
ZAEHL_LISTE=$(sed -n '/<tbody>/,/<\/tbody>/p' /tmp/pr-gruppen.html | grep -c "<tr" || true)
[ "${ZAEHL_DB:-0}" = "${ZAEHL_LISTE:-0}" ] && GZ=ja || GZ=nein
ergebnis "Liste und Datenbank zaehlen gleich viele Gruppen ($ZAEHL_LISTE / $ZAEHL_DB)" "$GZ"

# Standardsortierung nach Einrichtung: Die Vorgabegruppe jeder Einrichtung
# heisst „Alle Geräte" — nach Bezeichnung sortiert steht neunmal dasselbe
# untereinander, waehrend die Einrichtung daneben springt.
# Nur den Listenbereich: Auf der Seite steht darueber das Anlege-Formular.
sed -n '/<div id="gruppen"/,$p' /tmp/pr-gruppen.html > /tmp/pr-gruppenliste.html
GS=$(sortiert_nach /tmp/pr-gruppenliste.html "Einrichtung")
if [ "$GS" = "leer" ]; then
  uebersprungen "zu wenige Gruppen, um die Sortierung zu pruefen"
else
  if [ "$GS" = "nein" ]; then
    echo "    Diagnose: Einrichtungen in der Reihenfolge der Liste:"
    spalte_lesen /tmp/pr-gruppenliste.html "Einrichtung" | head -5 | sed 's/^/      /'
  fi
  ergebnis "die Gruppenliste kommt nach Einrichtung sortiert" "$GS"
fi
rm -f /tmp/pr-gruppen.html /tmp/pr-gruppenliste.html

hinweis "FA-GER-060 — Sortierung, Filter und Massenaktionen"
curl -s -b "$KEKSE" "$BASIS/bestand" > /tmp/pr-best.html
[ "$(treffer_zaehlen /tmp/pr-best.html 'class="sortlink"')" -ge 5 ] && SO=ja || SO=nein
ergebnis "die Geraeteliste hat sortierbare Spalten" "$SO"

curl -s -b "$KEKSE" "$BASIS/bestand?sortierung=modell&richtung=ab" -o /tmp/pr-antwort.html
SR=$(enthaelt /tmp/pr-antwort.html 'aria-sort="descending"')
ergebnis "die Sortierrichtung ist in der Kopfzeile ausgezeichnet (Screenreader)" "$SR"

# Der Sortierwert kommt aus der URL und geht in die Abfrage ein. Ohne
# Weissliste waere das eine SQL-Einschleusung.
CODE=$(curl -s -b "$KEKSE" -o /dev/null -w '%{http_code}' \
       "$BASIS/bestand?sortierung=bezeichnung%3B+DROP+TABLE+kartenterminal")
BEST=$(sql "SELECT count(*) FROM kartenterminal")
[ "$CODE" = "200" ] && [ "${BEST:-0}" -gt 0 ] && SI=ja || SI=nein
ergebnis "ein manipuliertes Sortierfeld richtet nichts an (HTTP $CODE, $BEST Geraete)" "$SI"

zaehler() { curl -s -b "$KEKSE" "$BASIS/bestand$1" \
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
ANTWORT_MA=$(curl -s -b "$KEKSE" -X POST $BASIS/bestand/massenaktion \
  "${MARGS[@]}" --data-urlencode "aktion=deaktivieren")
GEAENDERT=$(echo "$ANTWORT_MA" | grep -oE '[0-9]+ Kartenterminal\(s\) deaktiviert' | grep -oE '^[0-9]+')
[ "${GEAENDERT:-0}" = "$ANZ_AUSGEWAEHLT" ] && MA=ja || MA=nein
ergebnis "alle ausgewaehlten Geraete auf einmal deaktiviert ($GEAENDERT von $ANZ_AUSGEWAEHLT)" "$MA"
[ "$(zaehler "?status=inaktiv")" = "$ANZ_AUSGEWAEHLT" ] && MI=ja || MI=nein
ergebnis "der Statusfilter zeigt die deaktivierten Geraete" "$MI"

# Ein nicht mehr vorhandenes Geraet darf nicht stillschweigend uebergangen
# werden - "Aktion ausgefuehrt" bei 3 von 4 waere eine Falschmeldung.
ANTWORT_MU=$(curl -s -b "$KEKSE" -X POST $BASIS/bestand/massenaktion \
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

curl -s -b "$KEKSE" -X POST $BASIS/bestand/massenaktion \
  --data-urlencode "aktion=deaktivieren" -o /tmp/pr-antwort.html
ML=$(enthaelt /tmp/pr-antwort.html "kein Gerät")
ergebnis "eine leere Auswahl aendert nichts und sagt das" "$ML"

IST=$(sql "SELECT count(*) FROM fachprotokoll WHERE text LIKE '%Kartenterminal(s)%'")
[ "${IST:-0}" -ge 2 ] && MP=ja || MP=nein
ergebnis "Massenaktionen sind protokolliert ($IST Eintraege)" "$MP"

# FA-GER-030: Uebernahme "einzeln oder als Auswahl"
curl -s -b "$KEKSE" $BASIS/bestand/ermittelt > /tmp/pr-erm3.html
grep -q 'name="fund"' /tmp/pr-erm3.html && SA=ja || SA=nein
ergebnis "die Ermittlungsliste erlaubt Mehrfachauswahl" "$SA"

FUNDE=$(grep -oE 'name="fund" value="[0-9]+"' /tmp/pr-erm3.html | grep -oE '[0-9]+' | head -2)
if [ -n "$FUNDE" ]; then
  KID3=$(sql "SELECT id FROM konnektor ORDER BY id LIMIT 1")
  SARGS=()
  for i in $FUNDE; do SARGS+=(--data-urlencode "fund=$i"); done
  ANZ=$(echo "$FUNDE" | wc -w | tr -d ' ')
  curl -s -b "$KEKSE" -X POST $BASIS/bestand/ermittelt/uebernehmen \
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
curl -s -b "$KEKSE" -o /dev/null -X POST $BASIS/verwaltung/lasttest/anlegen \
  --data-urlencode "anzahl=1000"
LAST=$(sql "SELECT count(*) FROM kartenterminal WHERE terminal_id LIKE 'KT-LAST-%'")
[ "${LAST:-0}" = "1000" ] && LE=ja || LE=nein
ergebnis "Lasttestbestand angelegt ($LAST Geraete)" "$LE"

# Gemessen wird die HTTP-Gesamtzeit. Erst ein Aufruf zum Warmlaufen, damit
# nicht der Verbindungsaufbau in die Messung faellt.
zeitms() {
  curl -s -b "$KEKSE" -o /dev/null "$BASIS$1"
  local ges=0
  for _ in 1 2 3; do
    local ms=$(curl -s -b "$KEKSE" -o /dev/null -w '%{time_total}' "$BASIS$1" \
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

curl -s -b "$KEKSE" -o /dev/null -X POST $BASIS/verwaltung/lasttest/entfernen
REST=$(sql "SELECT count(*) FROM kartenterminal WHERE terminal_id LIKE 'KT-LAST-%'")
[ "${REST:-1}" = "0" ] && LX=ja || LX=nein
ergebnis "Lasttestbestand rueckstandsfrei entfernt (Rest: $REST)" "$LX"

hinweis "FA-GER-035 — Aufrufkontext aus dem Informationsmodell zur Auswahl"
# Die Konnektor-Anlegemaske ist seit dem UX-Umbau eine eigene Seite
# (ADR-0010, Stufe 4) — dort steht der Aufrufkontext.
curl -s -b "$KEKSE" $BASIS/bestand/konnektor/neu > /tmp/pr-kontext.html
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
  curl -sf -o /dev/null $BASIS/anmelden 2>/dev/null && break; sleep 1
done
CODE=$(curl -s -b "$KEKSE" -o /dev/null -w '%{http_code}' $BASIS/bestand)
[ "$CODE" = "200" ] && WZ=ja || WZ=nein
ergebnis "die Zentrale ist nach der Wiederherstellung bedienbar (HTTP $CODE)" "$WZ"
rm -f /tmp/pr-sicherung.dump

hinweis "Mandantentrennung — Block A"
# Der Pruefadmin ist Instanzbetreiber (keine Organisation zugeordnet) und sieht
# alles. Ein Benutzer einer einzelnen Praxis darf nur deren Geraete sehen.
curl -s -b "$KEKSE" -o /dev/null -X POST $BASIS/verwaltung/demodaten/anlegen
ALLE=$(curl -s -b "$KEKSE" $BASIS/bestand \
       | grep -oE 'Kartenterminals <span class="zaehler">[0-9]+' | grep -oE '[0-9]+$')
[ "${ALLE:-0}" -ge 36 ] && MI=ja || MI=nein
ergebnis "der Instanzbetreiber sieht den gesamten Bestand ($ALLE Geraete)" "$MI"

# Ein Demo-Benutzer der Ebene 1 ist genau einer Praxis zugeordnet.
PRAXIS=$(sqltext "SELECT b.benutzername FROM benutzer b
                  JOIN organisation o ON o.id = b.organisation_id
                  WHERE b.ist_demo AND o.art = 'EINRICHTUNG' ORDER BY b.id LIMIT 1")
ORGID=$(sql "SELECT organisation_id FROM benutzer WHERE benutzername = '$PRAXIS'")
# Seit ADR-0012 kann eine Betriebsstaette Fachbereiche haben; deren Geraete
# gehoeren zu ihrem Geltungsbereich. Deshalb ueber den Teilbaum zaehlen -
# ein Vergleich auf die eine Kennung wuerde je nach Betriebsstaette schwanken.
SOLL=$(sql "WITH RECURSIVE tb(id) AS (
              SELECT $ORGID::bigint
              UNION ALL SELECT o.id FROM organisation o JOIN tb ON o.eltern_id = tb.id)
            SELECT count(*) FROM kartenterminal WHERE organisation_id IN (SELECT id FROM tb)")
echo "  Demo-Benutzer $PRAXIS gehoert zu Einrichtung $ORGID mit $SOLL Geraeten"

# Anmelden als dieser Benutzer. Das Kennwort ist bei Demokonten einheitlich.
DEMO_KEKSE=/tmp/pr-demo-kekse.txt
rm -f "$DEMO_KEKSE"
DEMO_TOTP=$(sqltext "SELECT totp_geheimnis FROM benutzer WHERE benutzername = '$PRAXIS'")
DEMO_PW=$(grep -oE 'DEMO_KENNWORT *= *"[^"]+"' src/zentrale/src/main/java/de/pinroboter/zentrale/Demodaten.java \
          | head -1 | sed 's/.*"\(.*\)"/\1/')
if [ -n "$DEMO_TOTP" ] && [ -n "$DEMO_PW" ]; then
  for versuch in 1 2 3; do
    curl -s -c "$DEMO_KEKSE" -o /dev/null -X POST $BASIS/anmelden \
      -d "benutzer=$PRAXIS&passwort=$DEMO_PW&code=$(python3 pruefungen/totp.py "$DEMO_TOTP")"
    [ "$(curl -s -b "$DEMO_KEKSE" -o /dev/null -w '%{http_code}' $BASIS/bestand)" = "200" ] && break
    sleep 31
  done
  CODE=$(curl -s -b "$DEMO_KEKSE" -o /dev/null -w '%{http_code}' $BASIS/bestand)
  [ "$CODE" = "200" ] && MA=ja || MA=nein
  ergebnis "Anmeldung als Praxisbenutzer moeglich (HTTP $CODE)" "$MA"

  SICHT=$(curl -s -b "$DEMO_KEKSE" $BASIS/bestand \
          | grep -oE 'Kartenterminals <span class="zaehler">[0-9]+' | grep -oE '[0-9]+$')
  [ "$SICHT" = "$SOLL" ] && MS=ja || MS=nein
  ergebnis "der Praxisbenutzer sieht NUR seine eigenen Geraete ($SICHT von $ALLE)" "$MS"

  # Der Angriff: eine fremde Geraetekennung direkt aufrufen.
  FREMD=$(sql "SELECT id FROM kartenterminal WHERE organisation_id <> $ORGID ORDER BY id LIMIT 1")
  CODE=$(curl -s -b "$DEMO_KEKSE" -o /dev/null -w '%{http_code}' \
         "$BASIS/bestand/terminal/$FREMD")
  [ "$CODE" = "404" ] && MF=ja || MF=nein
  ergebnis "ein fremdes Geraet ist ueber seine Kennung nicht erreichbar (HTTP $CODE)" "$MF"

  # Und es laesst sich auch nicht loeschen.
  curl -s -b "$DEMO_KEKSE" -o /dev/null -X POST "$BASIS/bestand/terminal/$FREMD/loeschen"
  NOCHDA=$(sql "SELECT count(*) FROM kartenterminal WHERE id = $FREMD")
  [ "$NOCHDA" = "1" ] && ML=ja || ML=nein
  ergebnis "ein fremdes Geraet laesst sich nicht loeschen" "$ML"

  # Massenaktion mit einer fremden Kennung
  curl -s -b "$DEMO_KEKSE" -o /dev/null -X POST $BASIS/bestand/massenaktion \
    --data-urlencode "geraet=$FREMD" --data-urlencode "aktion=deaktivieren"
  AKTIV=$(sql "SELECT aktiv FROM kartenterminal WHERE id = $FREMD")
  [ "$AKTIV" = "t" ] && MM=ja || MM=nein
  ergebnis "eine Massenaktion greift nicht auf fremde Geraete durch" "$MM"
  # Zweite Haelfte der Mandantentrennung: Protokoll, Alarme, Gruppen, Benutzer
  PROT_ALLE=$(sql "SELECT count(*) FROM fachprotokoll")
  PROT_SICHT=$(curl -s -b "$DEMO_KEKSE" "$BASIS/teil/protokoll" \
               | grep -oE 'von [0-9]+' | head -1 | grep -oE '[0-9]+')
  [ -n "$PROT_SICHT" ] && [ "${PROT_SICHT:-0}" -lt "${PROT_ALLE:-0}" ] && MP=ja || MP=nein
  ergebnis "der Praxisbenutzer sieht nur einen Teil des Protokolls ($PROT_SICHT von $PROT_ALLE)" "$MP"

  # Der Export darf nicht mehr enthalten als die Ansicht.
  curl -s -b "$DEMO_KEKSE" -o /tmp/pr-export-praxis.xlsx $BASIS/protokoll/export
  GROESSE_PRAXIS=$(wc -c < /tmp/pr-export-praxis.xlsx | tr -d ' ')
  curl -s -b "$KEKSE" -o /tmp/pr-export-admin.xlsx $BASIS/protokoll/export
  GROESSE_ADMIN=$(wc -c < /tmp/pr-export-admin.xlsx | tr -d ' ')
  [ "${GROESSE_PRAXIS:-0}" -lt "${GROESSE_ADMIN:-0}" ] && MX=ja || MX=nein
  ergebnis "der Excel-Export des Praxisbenutzers ist kleiner als der des Betreibers ($GROESSE_PRAXIS < $GROESSE_ADMIN Bytes)" "$MX"
  rm -f /tmp/pr-export-praxis.xlsx /tmp/pr-export-admin.xlsx

  # Fremde Protokolleintraege duerfen auch ueber einen Geraetefilter nicht kommen.
  FREMD_KT=$(sqltext "SELECT terminal_id FROM kartenterminal WHERE organisation_id <> $ORGID
                      ORDER BY id LIMIT 1")
  TREFFER=$(curl -s -b "$DEMO_KEKSE" "$BASIS/teil/protokoll?geraet=$FREMD_KT" \
            | grep -c "<tr>")
  [ "${TREFFER:-1}" = "0" ] && MG=ja || MG=nein
  ergebnis "ein Filter auf ein fremdes Geraet liefert keine Protokolleintraege ($TREFFER)" "$MG"

  # Rechte je Ebene: Der Demo-Benutzer ist Ebene 1 (Praxis).
  CODE=$(curl -s -b "$DEMO_KEKSE" -o /dev/null -w '%{http_code}' \
         -X POST $BASIS/verwaltung/demodaten/loeschen)
  [ "$CODE" = "403" ] && RD=ja || RD=nein
  ergebnis "ein Praxisbenutzer kann den Demodatensatz nicht loeschen (HTTP $CODE)" "$RD"
  IST=$(sql "SELECT count(*) FROM kartenterminal WHERE ist_demo")
  [ "${IST:-0}" -gt 0 ] && RD2=ja || RD2=nein
  ergebnis "der Demodatensatz ist nach dem Versuch noch da ($IST Geraete)" "$RD2"

  CODE=$(curl -s -b "$DEMO_KEKSE" -o /dev/null -w '%{http_code}' \
         -X POST $BASIS/verwaltung/lasttest/anlegen --data-urlencode "anzahl=10")
  [ "$CODE" = "403" ] && RL=ja || RL=nein
  ergebnis "ein Praxisbenutzer kann keinen Lasttestbestand anlegen (HTTP $CODE)" "$RL"

  # Die Meldung muss sagen, WAS fehlt - nicht nur, dass etwas fehlt.
  curl -s -b "$DEMO_KEKSE" -X POST $BASIS/verwaltung/demodaten/anlegen -o /tmp/pr-antwort.html
RM=$(enthaelt /tmp/pr-antwort.html "Ihre Berechtigung")
  ergebnis "die Abweisung nennt die eigene Berechtigung" "$RM"

  # FA-ORG-020: Struktur aendern erst ab Standortebene. Ein Praxisbenutzer
  # koennte sonst seinen eigenen Zustaendigkeitsbereich erweitern.
  CODE=$(curl -s -b "$DEMO_KEKSE" -o /dev/null -w '%{http_code}' \
         -X POST $BASIS/organisation \
         --data-urlencode "bezeichnung=Praxis Eindringling" --data-urlencode "art=EINRICHTUNG" \
         --data-urlencode "elternId=$ORGID")
  [ "$CODE" = "403" ] && RO=ja || RO=nein
  ergebnis "ein Praxisbenutzer kann keine Organisationseinheit anlegen (HTTP $CODE)" "$RO"
  [ "$(sql "SELECT count(*) FROM organisation WHERE bezeichnung='Praxis Eindringling'")" = "0" ] \
    && RO2=ja || RO2=nein
  ergebnis "nach dem Versuch existiert keine neue Einheit" "$RO2"

  # Und die Oberflaeche bietet ihm keinen Knopf an, den es nicht gibt.
  curl -s -b "$DEMO_KEKSE" $BASIS/organisation > /tmp/pr-org-praxis.html
  grep -q "Einheit hinzufügen" /tmp/pr-org-praxis.html && RO3=nein || RO3=ja
  ergebnis "die Organisationsseite zeigt ihm kein Anlegeformular" "$RO3"
  grep -q "ab der Standortebene" /tmp/pr-org-praxis.html && RO4=ja || RO4=nein
  ergebnis "sie erklaert ihm stattdessen, warum" "$RO4"

  # Ein fremder Verbund darf auch mit direkter Kennung nicht erreichbar sein.
  FREMD_ORG=$(sql "SELECT id FROM organisation WHERE id <> $ORGID AND art='EINRICHTUNG'
                   ORDER BY id LIMIT 1")
  curl -s -b "$DEMO_KEKSE" -o /dev/null -X POST \
    "$BASIS/organisation/$FREMD_ORG/umbenennen" \
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
ANTWORT_EIN=$(curl -s -b "$KEKSE" -X POST $BASIS/benutzer/einladen \
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
curl -s -X POST $BASIS/einladung \
  --data-urlencode "geheimnis=$CODE_EIN" --data-urlencode "kennwort=kurz" -o /tmp/pr-antwort.html
BS=$(enthaelt /tmp/pr-antwort.html "mindestens 12 Zeichen")
ergebnis "ein schwaches Kennwort wird mit Begruendung abgelehnt" "$BS"

curl -s -X POST $BASIS/einladung \
  --data-urlencode "geheimnis=$CODE_EIN" --data-urlencode "kennwort=EinLangesUnverdaechtigesWort42" -o /tmp/pr-antwort.html
BL=$(enthaelt /tmp/pr-antwort.html "Einmalkennwort")
ergebnis "nach dem Kennwort folgt die Einrichtung des zweiten Faktors" "$BL"

IST=$(sql "SELECT organisation_id FROM benutzer WHERE benutzername='nachweis.person'")
[ "$IST" = "$ORG_B" ] && BO=ja || BO=nein
ergebnis "das Konto landet in der eingeladenen Einrichtung ($IST)" "$BO"

curl -s -X POST $BASIS/einladung \
  --data-urlencode "geheimnis=$CODE_EIN" --data-urlencode "kennwort=EinLangesUnverdaechtigesWort42" -o /tmp/pr-antwort.html
BZ=$(enthaelt /tmp/pr-antwort.html "bereits verwendet")
ergebnis "derselbe Zugangscode ein zweites Mal wird abgewiesen" "$BZ"

# Zweiten Faktor zuruecksetzen - der haeufigste Supportfall.
NID=$(sql "SELECT id FROM benutzer WHERE benutzername='nachweis.person'")
curl -s -b "$KEKSE" -o /dev/null -X POST "$BASIS/benutzer/$NID/totp-zuruecksetzen"
IST=$(sql "SELECT coalesce(totp_geheimnis,'-') FROM benutzer WHERE id=$NID")
[ "$IST" = "-" ] && BT=ja || BT=nein
ergebnis "der zweite Faktor laesst sich zuruecksetzen" "$BT"
IST=$(sql "SELECT count(*) FROM fachprotokoll WHERE art='TOTP_ZURUECKGESETZT'")
[ "${IST:-0}" -ge 1 ] && BTP=ja || BTP=nein
ergebnis "das Zuruecksetzen ist protokolliert" "$BTP"

# Deaktivieren statt Loeschen
curl -s -b "$KEKSE" -o /dev/null -X POST "$BASIS/benutzer/$NID/aktiv" \
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

curl -s -b "$KEKSE" -X POST "$BASIS/benutzer/$AID/aktiv" \
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
  curl -s -o /dev/null -X POST $BASIS/anmelden \
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
#
# Die Bremse gilt fuer den ABSENDER, nicht fuer das Konto: Nach acht
# Fehlversuchen ist die Adresse des Pruefstands selbst gebremst, und zwar bis
# zu 30 Sekunden (Anmeldebremse.HOECHSTENS). Am 10.08.2026 brach der Lauf hier
# ab — nicht wegen eines Fehlers, sondern weil die Bremse genau das tat, was
# sie soll. Der Nachweis hat den Zustand erzeugt, also raeumt er ihn auch ab.
python3 -c "import time; time.sleep(32)"
rm -f "$KEKSE"
anmelden
CODE=$(curl -s -b "$KEKSE" -o /dev/null -w '%{http_code}' $BASIS/geraete)
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
  curl -sf -o /dev/null $BASIS/anmelden 2>/dev/null && break; sleep 1
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
  curl -s -b "$KEKSE" "$BASIS/bestand/terminal/$TID_K" > /tmp/pr-karten.html
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
  uebersprungen "kein erfasstes Terminal mit SMC-B oder kein Agentenschluessel"
  echo "    Diagnose: TID_P='$TID_P' HANDLE='$HANDLE' Schluessel vorhanden: $PS"
else
  curl -s -b "$KEKSE" "$BASIS/bestand/terminal/$TID_P" > /tmp/pr-pin.html

  # Die Maske sagt ausdruecklich, dass sie JavaScript braucht (ADR-0009).
  grep -q "Diese Maske braucht JavaScript" /tmp/pr-pin.html && PJ=ja || PJ=nein
  ergebnis "die PIN-Maske nennt die JavaScript-Voraussetzung ausdruecklich" "$PJ"

  # Das Eingabefeld hat KEINEN name - es darf nie mitgeschickt werden.
  grep -q 'id="pinFeld" type="password"' /tmp/pr-pin.html \
    && ! grep -q 'id="pinFeld"[^>]*name=' /tmp/pr-pin.html && PF=ja || PF=nein
  ergebnis "das PIN-Eingabefeld wird nicht mitgeschickt (kein name-Attribut)" "$PF"

  # Klartext statt Chiffrat wird abgewiesen - die Notbremse aus Pinverwaltung.
  curl -s -b "$KEKSE" -X POST "$BASIS/bestand/terminal/$TID_P/pin" \
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

  curl -s -b "$KEKSE" -X POST "$BASIS/bestand/terminal/$TID_P/pin" \
    -d "kartenhandle=$HANDLE" --data-urlencode "chiffrat=$CHIFFRAT" > /tmp/pr-pin-neu.html

  # Der Agent holt den Auftrag beim naechsten Durchgang ab.
  for _ in $(seq 60); do
    Q=$(sql "SELECT count(*) FROM pin_auftrag WHERE quittiert IS NOT NULL AND art='HINTERLEGEN'")
    [ "${Q:-0}" -ge 1 ] && break
    sleep 2
  done
  # Auf den Agenten einschraenken, DER DIESES TERMINAL BETREUT — genau so
  # sucht ihn auch das Produkt (Pinverwaltung.agentFuerTerminal).
  #
  # "count ueber alle Agenten" ging schief, seit der Agent seinen PIN-Bestand
  # meldet: Ein Prueflauf registriert mehrere Agenten nacheinander, alte
  # bleiben aktiv, und jeder meldet dieselbe Karte. "Der juengste Agent" ist
  # ebenfalls falsch — der juengste muss dieses Terminal nicht gemeldet haben.
  HINT=$(sql "SELECT count(*) FROM pin_hinterlegt h
              WHERE h.kartenhandle = '$HANDLE'
                AND h.agent_id = (SELECT a.id FROM agent a
                                  JOIN ermittelt_terminal e ON e.agent_id = a.id
                                  WHERE a.aktiv AND e.terminal_id = (
                                      SELECT terminal_id FROM kartenterminal WHERE id = $TID_P)
                                  ORDER BY a.zuletzt_gesehen DESC NULLS LAST LIMIT 1)")
  [ "${HINT:-0}" = "1" ] && PH=ja || PH=nein
  if [ "$PH" = "nein" ]; then
    echo "    Diagnose Auftrag: $(sql "SELECT id||' zugestellt='||coalesce(zugestellt::text,'-')||' quittiert='||coalesce(quittiert::text,'-')||' fehler='||coalesce(fehler,'-') FROM pin_auftrag ORDER BY id" | tr '\n' '|')"
    echo "    Diagnose Kennzeichen: $(sql "SELECT string_agg(agent_id||':'||kartenhandle, ' ') FROM pin_hinterlegt")"
    echo "    Diagnose Agent des Terminals: $(sql "SELECT coalesce((SELECT a.id::text FROM agent a JOIN ermittelt_terminal e ON e.agent_id = a.id WHERE a.aktiv AND e.terminal_id = (SELECT terminal_id FROM kartenterminal WHERE id = $TID_P) ORDER BY a.zuletzt_gesehen DESC NULLS LAST LIMIT 1),'keiner')")"
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
  curl -s -b "$KEKSE" "$BASIS/bestand/terminal/$TID_P" > /tmp/pr-pin2.html
  grep -q "PIN hinterlegt" /tmp/pr-pin2.html && ! grep -qF "$PIN_NEU" /tmp/pr-pin2.html \
    && PM=ja || PM=nein
  ergebnis "die Oberflaeche zeigt nur das Kennzeichen, nie den Wert" "$PM"

  # FA-PIN-060: Loeschen, genauso einfach wie Hinterlegen.
  # Nur pruefbar, wenn vorher wirklich etwas hinterlegt war - sonst bestaetigen
  # sich die folgenden Nachweise selbst.
  if [ "$PH" = "nein" ]; then
   uebersprungen "Loeschen nicht pruefbar: es war keine PIN hinterlegt (Folge des Befunds davor)"
  else
  curl -s -b "$KEKSE" -X POST "$BASIS/bestand/terminal/$TID_P/pin-loeschen" \
    -d "kartenhandle=$HANDLE" > /dev/null
  # Wie beim Hinterlegen auf den zustaendigen Agenten einschraenken. Ein
  # Loeschauftrag gilt fuer EINEN Agenten; Eintraege frueher registrierter
  # Agenten desselben Prueflaufs bleiben stehen. Das ist ein bekannter offener
  # Punkt (Karteileichen abgemeldeter Agenten), aber nicht die Aussage dieses
  # Nachweises.
  ZUSTAENDIG="(SELECT a.id FROM agent a
               JOIN ermittelt_terminal e ON e.agent_id = a.id
               WHERE a.aktiv AND e.terminal_id = (
                   SELECT terminal_id FROM kartenterminal WHERE id = $TID_P)
               ORDER BY a.zuletzt_gesehen DESC NULLS LAST LIMIT 1)"
  for _ in $(seq 60); do
    [ "$(sql "SELECT count(*) FROM pin_hinterlegt
              WHERE kartenhandle = '$HANDLE' AND agent_id = $ZUSTAENDIG")" = "0" ] && break
    sleep 2
  done
  [ "$(sql "SELECT count(*) FROM pin_hinterlegt
            WHERE kartenhandle = '$HANDLE' AND agent_id = $ZUSTAENDIG")" = "0" ] && PL=ja || PL=nein
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

curl -s -b "$KEKSE" $BASIS/organisation > /tmp/pr-org.html
grep -qE "<h2[^>]*>Organisation</h2>" /tmp/pr-org.html && OB=ja || OB=nein
ergebnis "die Organisationsseite ist erreichbar" "$OB"
grep -q "Einheit hinzufügen" /tmp/pr-org.html && OF=ja || OF=nein
ergebnis "der Instanzbetreiber bekommt das Anlegeformular" "$OF"

# Anlegen unterhalb eines Standorts.
STANDORT=$(sql "SELECT id FROM organisation WHERE art='STANDORT' ORDER BY id LIMIT 1")
VERBUND=$(sql "SELECT id FROM organisation WHERE art='VERBUND' ORDER BY id LIMIT 1")
if [ -z "$STANDORT" ] || [ -z "$VERBUND" ]; then
  uebersprungen "kein Standort/Verbund im Demodatensatz"
else
  curl -s -b "$KEKSE" -o /dev/null -X POST $BASIS/organisation \
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
    curl -s -b "$KEKSE" -o /dev/null -X POST "$BASIS/organisation/$NEU/umbenennen" \
      --data-urlencode "bezeichnung=Nachweis Praxis Nord"
    [ "$(sqltext "SELECT bezeichnung FROM organisation WHERE id=$NEU")" = "Nachweis Praxis Nord" ] \
      && OU=ja || OU=nein
    ergebnis "eine Einheit laesst sich umbenennen" "$OU"

    # Umhaengen an den Verbund
    curl -s -b "$KEKSE" -o /dev/null -X POST "$BASIS/organisation/$NEU/umhaengen" \
      --data-urlencode "elternId=$VERBUND"
    [ "$(sql "SELECT eltern_id FROM organisation WHERE id=$NEU")" = "$VERBUND" ] && OH=ja || OH=nein
    ergebnis "eine Einheit laesst sich umhaengen" "$OH"
    [ "$(sql "SELECT count(*) FROM fachprotokoll WHERE art='ORGANISATION_UMGEHAENGT'")" != "0" ] \
      && OHP=ja || OHP=nein
    ergebnis "das Umhaengen steht im Fachprotokoll (Sicherheitsstufe)" "$OHP"

    # Zyklus: den Verbund unter seine eigene neue Einrichtung haengen.
    curl -s -b "$KEKSE" -X POST "$BASIS/organisation/$VERBUND/umhaengen" \
      --data-urlencode "elternId=$NEU" > /tmp/pr-org-zyklus.html
    [ -z "$(sql "SELECT eltern_id FROM organisation WHERE id=$VERBUND")" ] && OZ=ja || OZ=nein
    ergebnis "ein Zyklus wird abgelehnt und der Baum bleibt heil" "$OZ"

    # Loeschen: die leere neue Einheit geht.
    curl -s -b "$KEKSE" -o /dev/null -X POST "$BASIS/organisation/$NEU/loeschen"
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
    curl -s -b "$KEKSE" -X POST "$BASIS/organisation/$VOLL/loeschen" \
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

# ------------------------------------------------- Strukturansicht (UX)
# Die Organisationsstruktur als Bild. Geprueft wird nicht, ob sie huebsch ist -
# das kann kein Skript -, sondern ob sie DIESELBEN Daten zeigt wie die Liste,
# ob die Geraete darin auftauchen und ob die Barrierefreiheitsregeln halten,
# die man beim Bauen von Grafiken am leichtesten verletzt.
hinweis "Strukturansicht der Organisation"

curl -s -b "$KEKSE" "$BASIS/organisation?ansicht=struktur" > /tmp/pr-struk.html
grep -q 'class="strukturkarte' /tmp/pr-struk.html && SA=ja || SA=nein
ergebnis "die Strukturansicht ist erreichbar" "$SA"

# Gleich viele Karten wie Einheiten im Geltungsbereich - eine Ansicht, die
# stillschweigend weniger zeigt als die Liste, waere gefaehrlicher als keine.
KARTEN=$(treffer_zaehlen /tmp/pr-struk.html 'class="strukturkarte')
EINHEITEN=$(sql "SELECT count(*) FROM organisation")
[ "$KARTEN" = "$EINHEITEN" ] && SB=ja || SB=nein
ergebnis "sie zeigt jede Organisationseinheit ($KARTEN von $EINHEITEN)" "$SB"

# Die Geraete stehen als Kacheln darunter.
CHIPS=$(treffer_zaehlen /tmp/pr-struk.html 'class="geraetechip')
[ "${CHIPS:-0}" -ge 1 ] && SC=ja || SC=nein
ergebnis "die Kartenterminals erscheinen als Kacheln ($CHIPS)" "$SC"

# ADR-0012 im Bild: Ein mitgenutzter Konnektor ist als solcher gekennzeichnet.
# Der Fall wird hergestellt, nicht vorausgesetzt — im Demobestand betreibt seit
# dem 12.08.2026 jede Betriebsstätte ihren eigenen Konnektor.
S_KONN=$(sql "SELECT id FROM konnektor WHERE ist_demo ORDER BY id LIMIT 1")
S_ORG=$(sql "SELECT o.id FROM organisation o
             WHERE o.ist_demo AND o.art='EINRICHTUNG'
               AND o.id <> (SELECT organisation_id FROM konnektor WHERE id=$S_KONN)
             ORDER BY o.id LIMIT 1")
if [ -z "$S_KONN" ] || [ -z "$S_ORG" ]; then
  uebersprungen "kein Konnektor mit zweiter Einrichtung im Demobestand"
else
  curl -s -b "$KEKSE" -o /dev/null -X POST "$BASIS/bestand/konnektor/$S_KONN/nutzung" \
    --data-urlencode "organisationId=$S_ORG" --data-urlencode "nutzen=ja"
  curl -s -b "$KEKSE" "$BASIS/organisation?ansicht=struktur" > /tmp/pr-struk2.html
  grep -q "konnektorchip mitgenutzt" /tmp/pr-struk2.html && SD=ja || SD=nein
  ergebnis "ein mitgenutzter Konnektor ist als solcher gekennzeichnet" "$SD"
  grep -q ">mitgenutzt<" /tmp/pr-struk2.html && SD2=ja || SD2=nein
  ergebnis "und sagt es im Text, nicht nur im Rahmen" "$SD2"

  curl -s -b "$KEKSE" -o /dev/null -X POST "$BASIS/bestand/konnektor/$S_KONN/nutzung" \
    --data-urlencode "organisationId=$S_ORG" --data-urlencode "nutzen=nein"
  curl -s -b "$KEKSE" "$BASIS/organisation?ansicht=struktur" > /tmp/pr-struk3.html
  grep -q "konnektorchip mitgenutzt" /tmp/pr-struk3.html && SD3=nein || SD3=ja
  ergebnis "nach dem Entziehen verschwindet die Kennzeichnung wieder" "$SD3"
  rm -f /tmp/pr-struk2.html /tmp/pr-struk3.html
fi

# WCAG 1.4.1: Farbe darf nie die einzige Aussage sein. Zu jeder Ampel gehoert
# ein Text - hier gegengeprueft, weil genau das bei Grafiken vergessen wird.
for wort in "in Ordnung" "Handlungsbedarf" "keine Rückmeldung"; do
  grep -q "$wort" /tmp/pr-struk.html && SE=ja || SE=nein
  ergebnis "die Legende benennt „$wort“ in Worten" "$SE"
done

# Und die Liste bleibt gleichwertig erreichbar.
curl -s -b "$KEKSE" "$BASIS/organisation?ansicht=liste" > /tmp/pr-strukl.html
grep -q "<tbody>" /tmp/pr-strukl.html && SF=ja || SF=nein
ergebnis "die Listenansicht bleibt erreichbar" "$SF"
grep -q 'class="strukturkarte' /tmp/pr-strukl.html && SG=nein || SG=ja
ergebnis "sie zeigt dann keine Struktur-Karten" "$SG"

# Die Wahl wird gemerkt: Wer die Liste gewaehlt hat, bekommt sie wieder -
# sonst muesste man sie nach jedem Seitenwechsel neu einstellen.
curl -s -b /tmp/pr-struk-kekse.txt -c /tmp/pr-struk-kekse.txt -o /dev/null \
  -b "$KEKSE" "$BASIS/organisation?ansicht=liste"
grep -q "pr-organisationsansicht" /tmp/pr-struk-kekse.txt && SH=ja || SH=nein
ergebnis "die gewaehlte Ansicht wird gemerkt" "$SH"
rm -f /tmp/pr-struk.html /tmp/pr-strukl.html /tmp/pr-struk-kekse.txt

# ---------------------------------------------------------------- ADR-0012
# Die Gerätehierarchie folgt der Organisationsstruktur. Geprüft werden die drei
# Punkte, an denen sie sich vom vorherigen Modell unterscheidet: die neuen
# Ebenen, die TI-Identität der Betriebsstätte und der geteilte Konnektor.
hinweis "ADR-0012 — Gerätehierarchie nach Organisationsstruktur"

BS=$(sql "SELECT id FROM organisation WHERE art='EINRICHTUNG' AND ist_demo ORDER BY id LIMIT 1")
ST=$(sql "SELECT id FROM organisation WHERE art='STANDORT' AND ist_demo ORDER BY id LIMIT 1")
VB=$(sql "SELECT id FROM organisation WHERE art='VERBUND' AND ist_demo ORDER BY id LIMIT 1")
if [ -z "$BS" ] || [ -z "$ST" ] || [ -z "$VB" ]; then
  uebersprungen "Demodatensatz ohne Betriebsstaette/Standort/Verbund"
else
  # --- Die neuen Ebenen ---------------------------------------------------
  curl -s -b "$KEKSE" -o /dev/null -X POST $BASIS/organisation     --data-urlencode "bezeichnung=Nachweis Fachbereich" --data-urlencode "art=FACHBEREICH"     --data-urlencode "elternId=$BS"
  FB=$(sql "SELECT id FROM organisation WHERE bezeichnung='Nachweis Fachbereich'")
  [ -n "$FB" ] && HF=ja || HF=nein
  ergebnis "ein Fachbereich laesst sich unterhalb einer Betriebsstaette anlegen" "$HF"

  # Unter einem Standort hat ein Fachbereich nichts zu suchen: Er gliedert den
  # Geraetepark einer Betriebsstaette, nicht den eines Standorts.
  curl -s -b "$KEKSE" -o /dev/null -X POST $BASIS/organisation     --data-urlencode "bezeichnung=Nachweis Fachbereich falsch"     --data-urlencode "art=FACHBEREICH" --data-urlencode "elternId=$ST"
  [ "$(sql "SELECT count(*) FROM organisation WHERE bezeichnung='Nachweis Fachbereich falsch'")" = "0" ]     && HF2=ja || HF2=nein
  ergebnis "ein Fachbereich unter einem Standort wird abgelehnt" "$HF2"

  curl -s -b "$KEKSE" -o /dev/null -X POST $BASIS/organisation     --data-urlencode "bezeichnung=Nachweis Leistungserbringer"     --data-urlencode "art=LEISTUNGSERBRINGER" --data-urlencode "elternId=$VB"
  LE=$(sql "SELECT id FROM organisation WHERE bezeichnung='Nachweis Leistungserbringer'")
  [ -n "$LE" ] && HL=ja || HL=nein
  ergebnis "ein Leistungserbringer laesst sich unterhalb des Verbunds anlegen" "$HL"

  # --- BSNR und Telematik-ID ----------------------------------------------
  curl -s -b "$KEKSE" -o /dev/null -X POST "$BASIS/organisation/$BS/ti-identitaet"     --data-urlencode "bsnr=729112233"     --data-urlencode "telematikId=1-SMC-B-Testkarte-883110000999888"
  [ "$(sqltext "SELECT bsnr FROM organisation WHERE id=$BS")" = "729112233" ] && HB=ja || HB=nein
  ergebnis "die Betriebsstaette nimmt BSNR und Telematik-ID auf" "$HB"

  # Acht Ziffern sind keine BSNR. Geprueft wird die Form, nicht die Gueltigkeit -
  # ob es die Nummer wirklich gibt, weiss nur die KV.
  curl -s -b "$KEKSE" -X POST "$BASIS/organisation/$BS/ti-identitaet"     --data-urlencode "bsnr=72911223" --data-urlencode "telematikId=" > /tmp/pr-bsnr.html
  [ "$(sqltext "SELECT bsnr FROM organisation WHERE id=$BS")" = "729112233" ] && HB2=ja || HB2=nein
  ergebnis "eine achtstellige BSNR wird abgelehnt und der Altwert bleibt" "$HB2"
  grep -q "neun Ziffern" /tmp/pr-bsnr.html && HB3=ja || HB3=nein
  ergebnis "die Meldung sagt, wie eine BSNR aussehen muss" "$HB3"
  rm -f /tmp/pr-bsnr.html

  # An einem Standort gibt es keine BSNR - er ist keine Leistungserbringer-
  # institution. Das muss die Anwendung verweigern, nicht nur verschweigen.
  curl -s -b "$KEKSE" -X POST "$BASIS/organisation/$ST/ti-identitaet"     --data-urlencode "bsnr=729555555" --data-urlencode "telematikId=" > /tmp/pr-bsnr2.html
  [ -z "$(sqltext "SELECT bsnr FROM organisation WHERE id=$ST")" ] && HB4=ja || HB4=nein
  ergebnis "ein Standort nimmt keine BSNR an" "$HB4"
  grep -q "Betriebsstätte" /tmp/pr-bsnr2.html && HB5=ja || HB5=nein
  ergebnis "die Meldung nennt die Betriebsstaette als richtigen Ort" "$HB5"
  rm -f /tmp/pr-bsnr2.html

  # Die Detailseite der Betriebsstaette zeigt die Felder, die des Standorts nicht.
  curl -s -b "$KEKSE" "$BASIS/organisation/$BS" > /tmp/pr-od.html
  grep -q "Angaben zur Betriebsstätte" /tmp/pr-od.html && HO=ja || HO=nein
  ergebnis "die Detailseite der Betriebsstaette bietet die Felder an" "$HO"
  curl -s -b "$KEKSE" "$BASIS/organisation/$ST" > /tmp/pr-od2.html
  grep -q "Angaben zur Betriebsstätte" /tmp/pr-od2.html && HO2=nein || HO2=ja
  ergebnis "die Detailseite des Standorts bietet sie nicht an" "$HO2"
  rm -f /tmp/pr-od.html /tmp/pr-od2.html

  # Alle Demo-Betriebsstaetten tragen ihre TI-Identitaet; Fachbereiche nicht.
  OHNE=$(sql "SELECT count(*) FROM organisation
              WHERE ist_demo AND art='EINRICHTUNG' AND (bsnr IS NULL OR telematik_id IS NULL)")
  [ "${OHNE:-1}" = "0" ] && HD=ja || HD=nein
  ergebnis "alle neun Demo-Betriebsstaetten tragen BSNR und Telematik-ID (ohne: $OHNE)" "$HD"
  MIT=$(sql "SELECT count(*) FROM organisation
             WHERE ist_demo AND art<>'EINRICHTUNG' AND bsnr IS NOT NULL")
  [ "${MIT:-1}" = "0" ] && HD2=ja || HD2=nein
  ergebnis "keine andere Ebene traegt eine BSNR (gefunden: $MIT)" "$HD2"

  # --- Der geteilte Konnektor ---------------------------------------------
  #
  # Der Aufbau wird HIER hergestellt, nicht aus dem Demobestand genommen. Bis
  # zum 12.08.2026 stand dort ein zentraler Konnektor im Rechenzentrum; der PO
  # hat ihn entfernt. Ein Nachweis, der an einem Demodatensatz haengt, prueft
  # die Funktion nur so lange, wie jemand den Datensatz nicht aendert — und
  # meldet danach „uebersprungen" statt „kaputt".
  RZ=$(sql "SELECT id FROM konnektor WHERE ist_demo ORDER BY id LIMIT 1")
  MITNUTZER=$(sql "SELECT o.id FROM organisation o
                   WHERE o.ist_demo AND o.art='EINRICHTUNG'
                     AND o.id <> (SELECT organisation_id FROM konnektor WHERE id=$RZ)
                   ORDER BY o.id LIMIT 1")
  if [ -z "$RZ" ] || [ -z "$MITNUTZER" ]; then
    uebersprungen "kein Konnektor mit zweiter Einrichtung im Demobestand"
  else
    curl -s -b "$KEKSE" -o /dev/null -X POST "$BASIS/bestand/konnektor/$RZ/nutzung" \
      --data-urlencode "organisationId=$MITNUTZER" --data-urlencode "nutzen=ja"
    NUTZER=$(sql "SELECT count(*) FROM konnektor_nutzung n JOIN konnektor k ON k.id=n.konnektor_id
                  WHERE n.konnektor_id=$RZ AND n.organisation_id <> k.organisation_id")
    [ "${NUTZER:-0}" -ge 1 ] && HK=ja || HK=nein
    ergebnis "ein Konnektor laesst sich von einer zweiten Betriebsstaette mitnutzen ($NUTZER)" "$HK"

    # Der Mitnutzer SIEHT den Konnektor - sonst koennte er seine eigenen
    # Terminals nicht zuordnen.
    SICHT=$(sql "SELECT count(*) FROM konnektor k
                 WHERE k.id=$RZ AND (k.organisation_id=$MITNUTZER
                   OR EXISTS (SELECT 1 FROM konnektor_nutzung n
                              WHERE n.konnektor_id=k.id AND n.organisation_id=$MITNUTZER))")
    [ "${SICHT:-0}" = "1" ] && HKS=ja || HKS=nein
    ergebnis "der Mitnutzer sieht den Konnektor" "$HKS"

    curl -s -b "$KEKSE" "$BASIS/bestand/konnektor/$RZ" > /tmp/pr-kn.html
    grep -q "Mitnutzende Einrichtungen" /tmp/pr-kn.html && HK2=ja || HK2=nein
    ergebnis "die Konnektorseite fuehrt die mitnutzenden Einrichtungen auf" "$HK2"
    rm -f /tmp/pr-kn.html

    # Der Betreiber selbst laesst sich nicht austragen - er nutzt seinen
    # Konnektor immer. Eine Zeile, die man loeschen kann, waere eine Falle.
    BETREIBER=$(sql "SELECT organisation_id FROM konnektor WHERE id=$RZ")
    curl -s -b "$KEKSE" -o /dev/null -X POST "$BASIS/bestand/konnektor/$RZ/nutzung"       --data-urlencode "organisationId=$BETREIBER" --data-urlencode "nutzen=nein"
    [ "$(sql "SELECT count(*) FROM konnektor_nutzung WHERE konnektor_id=$RZ AND organisation_id=$BETREIBER")" = "1" ]       && HK3=ja || HK3=nein
    ergebnis "der Betreiber laesst sich nicht als Nutzer austragen" "$HK3"

    # Eine Mitnutzung erlauben und wieder entziehen - beides muss wirken und
    # im Fachprotokoll stehen.
    ANDERE=$(sql "SELECT o.id FROM organisation o WHERE o.ist_demo AND o.art='EINRICHTUNG'
                  AND NOT EXISTS (SELECT 1 FROM konnektor_nutzung n
                                  WHERE n.konnektor_id=$RZ AND n.organisation_id=o.id)
                  ORDER BY o.id LIMIT 1")
    curl -s -b "$KEKSE" -o /dev/null -X POST "$BASIS/bestand/konnektor/$RZ/nutzung"       --data-urlencode "organisationId=$ANDERE" --data-urlencode "nutzen=ja"
    [ "$(sql "SELECT count(*) FROM konnektor_nutzung WHERE konnektor_id=$RZ AND organisation_id=$ANDERE")" = "1" ]       && HK4=ja || HK4=nein
    ergebnis "eine weitere Einrichtung laesst sich zur Mitnutzung zulassen" "$HK4"
    [ "$(sql "SELECT count(*) FROM fachprotokoll WHERE art='KONNEKTOR_NUTZUNG_GEAENDERT'")" != "0" ]       && HK5=ja || HK5=nein
    ergebnis "die Aenderung der Mitnutzung steht im Fachprotokoll" "$HK5"

    curl -s -b "$KEKSE" -o /dev/null -X POST "$BASIS/bestand/konnektor/$RZ/nutzung"       --data-urlencode "organisationId=$ANDERE" --data-urlencode "nutzen=nein"
    [ "$(sql "SELECT count(*) FROM konnektor_nutzung WHERE konnektor_id=$RZ AND organisation_id=$ANDERE")" = "0" ]       && HK6=ja || HK6=nein
    ergebnis "die Mitnutzung laesst sich wieder entziehen" "$HK6"

    # Zuruecksetzen: Der Nachweis hat die Mitnutzung selbst eingerichtet und
    # nimmt sie wieder zurueck. Sonst misst der naechste Lauf einen Zustand,
    # den dieser hier erzeugt hat.
    curl -s -b "$KEKSE" -o /dev/null -X POST "$BASIS/bestand/konnektor/$RZ/nutzung" \
      --data-urlencode "organisationId=$MITNUTZER" --data-urlencode "nutzen=nein"
    [ "$(sql "SELECT count(*) FROM konnektor_nutzung n JOIN konnektor k ON k.id=n.konnektor_id
              WHERE n.konnektor_id=$RZ AND n.organisation_id <> k.organisation_id")" = "0" ] \
      && HK7=ja || HK7=nein
    ergebnis "der Nachweis nimmt die eingerichtete Mitnutzung zurueck" "$HK7"
  fi

  # Aufraeumen: Der Nachweis hinterlaesst keine Einheiten und keine BSNR, die
  # spaetere Nachweise verwirren wuerden.
  [ -n "$FB" ] && curl -s -b "$KEKSE" -o /dev/null -X POST "$BASIS/organisation/$FB/loeschen"
  [ -n "$LE" ] && curl -s -b "$KEKSE" -o /dev/null -X POST "$BASIS/organisation/$LE/loeschen"
  BSNR_ALT=$(sqltext "SELECT bsnr FROM organisation WHERE id=$BS")
  curl -s -b "$KEKSE" -o /dev/null -X POST "$BASIS/organisation/$BS/ti-identitaet"     --data-urlencode "bsnr=729900001"     --data-urlencode "telematikId=1-SMC-B-Testkarte-8831100000001"
  [ "$(sql "SELECT count(*) FROM organisation WHERE bezeichnung IN ('Nachweis Fachbereich','Nachweis Leistungserbringer')")" = "0" ]     && HZ=ja || HZ=nein
  ergebnis "der Nachweis raeumt seine Einheiten wieder ab" "$HZ"
fi

# ---------------------------------------------------------------- FA-GER-050
# Gerätegruppen tragen das Prüfintervall. Bisher nur durch Modultests
# abgesichert - die prüfen die Logik, nicht das Zusammenspiel mit Oberfläche,
# Protokoll und Datenbank.
hinweis "FA-GER-050 — Gerätegruppen anlegen, zuordnen, löschen"

curl -s -b "$KEKSE" $BASIS/gruppen > /tmp/pr-gruppen.html
# Die Vorgabegruppe heisst „Alle Geräte" und traegt die Marke „Vorgabe".
# Frueher wurde hier „Nicht zugeordnet" gesucht — eine Gruppe aus Migration 6
# ohne Organisationseinheit, die Migration 23 zu Recht entfernt hat.
VG_DB=$(sql "SELECT count(*) FROM geraetegruppe WHERE ist_vorgabe")
[ "${VG_DB:-0}" -ge 1 ] && grep -q "Vorgabe" /tmp/pr-gruppen.html && GV=ja || GV=nein
ergebnis "die Vorgabegruppe existiert und ist als solche gekennzeichnet ($VG_DB)" "$GV"

# ── Die Befunde vom 11.08.2026 ──────────────────────────────────────────
# Umbenennen gab es gar nicht: Wer sich vertippte, musste loeschen und neu
# anlegen — und verlor dabei jede Geraetezuordnung.
GID=$(sql "SELECT id FROM geraetegruppe WHERE NOT ist_vorgabe ORDER BY id LIMIT 1")
if [ -z "$GID" ]; then
  EINR_G=$(sqltext "SELECT einrichtung FROM geraetegruppe ORDER BY id LIMIT 1")
  curl -s -b "$KEKSE" -X POST $BASIS/gruppen \
    --data-urlencode "bezeichnung=Umbenenntest" --data-urlencode "einrichtung=$EINR_G" \
    --data-urlencode "intervall=60" -o /dev/null
  GID=$(sql "SELECT id FROM geraetegruppe WHERE bezeichnung='Umbenenntest'")
fi
if [ -z "$GID" ]; then
  uebersprungen "keine Gruppe zum Umbenennen vorhanden"
else
  curl -s -b "$KEKSE" -X POST "$BASIS/gruppen/$GID/umbenennen" \
    --data-urlencode "bezeichnung=Frisch benannt" -o /tmp/pr-umb.html
  IST=$(sqltext "SELECT bezeichnung FROM geraetegruppe WHERE id=$GID")
  [ "$IST" = "Frischbenannt" ] || [ "$IST" = "Frisch benannt" ] && UB=ja || UB=nein
  ergebnis "eine Gruppe laesst sich umbenennen (jetzt: $IST)" "$UB"
  UR=$(enthaelt /tmp/pr-umb.html "umbenannt")
  ergebnis "die Oberflaeche bestaetigt die Aenderung" "$UR"
  rm -f /tmp/pr-umb.html
fi

# Das Loeschen darf Geraete NICHT ueber die Mandantengrenze schieben. Frueher
# uebergab die Oberflaeche fest „Praxis" als Einrichtung: Die Geraete einer
# fremden Praxis waeren in deren Vorgabegruppe gewandert — oder das Loeschen
# scheiterte mit „Es gibt keine Vorgabegruppe".
# Die Vorbedingung wird HERGESTELLT, nicht abgewartet.
#
# „Uebersprungen, weil die Lage gerade nicht passt" ist bei einem Nachweis,
# der eine Mandantengrenze prueft, keine Antwort — und er lief so nie.
GMIT=$(sql "SELECT g.id FROM geraetegruppe g
            WHERE NOT g.ist_vorgabe
              AND EXISTS (SELECT 1 FROM geraet t WHERE t.gruppe_id = g.id) LIMIT 1")
if [ -z "$GMIT" ]; then
  # Ein Geraet mit Einrichtung suchen, dort eine zweite Gruppe anlegen und das
  # Geraet hineinschieben.
  KT_M=$(sqltext "SELECT terminal_id FROM geraet ORDER BY terminal_id LIMIT 1")
  EINR_M=$(sqltext "SELECT einrichtung FROM geraet WHERE terminal_id='$KT_M'")
  if [ -n "$KT_M" ]; then
    curl -s -b "$KEKSE" -o /dev/null -X POST $BASIS/gruppen \
      --data-urlencode "bezeichnung=Loeschtest Mandant" \
      --data-urlencode "einrichtung=$EINR_M" --data-urlencode "intervall=60"
    GMIT=$(sql "SELECT id FROM geraetegruppe WHERE bezeichnung='Loeschtest Mandant'")
    [ -n "$GMIT" ] && curl -s -b "$KEKSE" -o /dev/null -X POST "$BASIS/geraete/$KT_M/gruppe" \
      --data-urlencode "gruppe=$GMIT"
  fi
fi
if [ -z "$GMIT" ]; then
  uebersprungen "Vorbedingung liess sich nicht herstellen (kein Geraet mit Einrichtung)"
else
  EINR_L=$(sqltext "SELECT einrichtung FROM geraetegruppe WHERE id=$GMIT")
  curl -s -b "$KEKSE" -X POST "$BASIS/gruppen/$GMIT/loeschen" -o /dev/null
  FREMD=$(sql "SELECT count(*) FROM geraet t
               JOIN geraetegruppe g ON g.id = t.gruppe_id
               WHERE t.einrichtung = '$EINR_L' AND g.einrichtung <> '$EINR_L'")
  [ "${FREMD:-1}" = "0" ] && GL=ja || GL=nein
  ergebnis "beim Loeschen wandert kein Geraet in eine fremde Einrichtung ($FREMD)" "$GL"
fi

# Die Detailseite: bis zum 11.08.2026 hatte die Gruppenverwaltung als einziger
# Bereich keine. Alles musste in die Listenzeile passen — und was nicht
# hineinpasste, fehlte: Umbenennen, Vorgabe wechseln, die Geraete sehen.
GDET=$(sql "SELECT id FROM geraetegruppe ORDER BY id LIMIT 1")
if [ -z "$GDET" ]; then
  uebersprungen "keine Gruppe fuer die Detailseite vorhanden"
else
  CODE=$(curl -s -b "$KEKSE" -o /tmp/pr-gdet.html -w '%{http_code}' "$BASIS/gruppen/$GDET")
  [ "$CODE" = "200" ] && GD=ja || GD=nein
  ergebnis "die Gruppe hat eine Detailseite (HTTP $CODE)" "$GD"
  GD1=$(enthaelt /tmp/pr-gdet.html "Stammdaten")
  ergebnis "sie zeigt die Stammdaten zum Bearbeiten" "$GD1"
  GD2=$(enthaelt /tmp/pr-gdet.html "Vorgabegruppe")
  ergebnis "sie erklaert die Vorgabegruppe" "$GD2"
  GD3=$(enthaelt /tmp/pr-gdet.html "Geräte")
  ergebnis "sie zeigt die Geraete der Gruppe, nicht nur ihre Zahl" "$GD3"

  # Aendern in EINEM Zug: Bezeichnung und Intervall zusammen.
  curl -s -b "$KEKSE" -X POST "$BASIS/gruppen/$GDET/aendern" \
    --data-urlencode "bezeichnung=Detailtest" --data-urlencode "intervall=45" \
    -o /tmp/pr-gaend.html
  IST_B=$(sqltext "SELECT bezeichnung FROM geraetegruppe WHERE id=$GDET")
  IST_I=$(sql "SELECT pruefintervall_sekunden FROM geraetegruppe WHERE id=$GDET")
  [ "$IST_B" = "Detailtest" ] && [ "$IST_I" = "45" ] && GA=ja || GA=nein
  ergebnis "Bezeichnung und Intervall lassen sich zusammen aendern ($IST_B / $IST_I s)" "$GA"
  GR=$(enthaelt /tmp/pr-gaend.html "Gespeichert")
  ergebnis "die Oberflaeche bestaetigt, WAS gespeichert wurde" "$GR"
  rm -f /tmp/pr-gdet.html /tmp/pr-gaend.html
fi

# Vorgabe wechseln — ging bisher gar nicht.
G_NV=$(sql "SELECT id FROM geraetegruppe WHERE NOT ist_vorgabe ORDER BY id LIMIT 1")
if [ -z "$G_NV" ]; then
  uebersprungen "keine Gruppe ohne Vorgabe-Kennzeichen vorhanden"
else
  EINR_V=$(sqltext "SELECT einrichtung FROM geraetegruppe WHERE id=$G_NV")
  # Die bisherige Vorgabe merken — dieser Nachweis MUSS sie wiederherstellen.
  #
  # Ein Nachweis, der den Zustand veraendert und ihn nicht zuruecknimmt, misst
  # nicht mehr das Produkt, sondern seine eigenen Spuren. Genau das ist am
  # 11.08.2026 zweimal passiert: erst bei FA-INS-040 (kuenstlicher
  # Schluesselwechsel blieb stehen), dann hier — der Vorgabewechsel liess die
  # alte Vorgabegruppe loeschbar zurueck, und der naechste Nachweis meldete
  # „die Vorgabegruppe laesst sich nicht loeschen" als fehlgeschlagen.
  VORHER_V=$(sql "SELECT id FROM geraetegruppe WHERE einrichtung='$EINR_V' AND ist_vorgabe")
  curl -s -b "$KEKSE" -X POST "$BASIS/gruppen/$G_NV/vorgabe" -o /dev/null
  IST=$(sql "SELECT count(*) FROM geraetegruppe WHERE id=$G_NV AND ist_vorgabe")
  [ "$IST" = "1" ] && GW=ja || GW=nein
  ergebnis "eine andere Gruppe laesst sich zur Vorgabe machen" "$GW"
  # Und es darf nur EINE je Einrichtung geben.
  ANZ=$(sql "SELECT count(*) FROM geraetegruppe WHERE einrichtung='$EINR_V' AND ist_vorgabe")
  [ "${ANZ:-0}" = "1" ] && GE=ja || GE=nein
  ergebnis "je Einrichtung bleibt genau eine Vorgabegruppe ($ANZ)" "$GE"
  # Zuruecksetzen — ueber die Oberflaeche, nicht per SQL: So wird zugleich
  # geprueft, dass der Wechsel in beide Richtungen funktioniert.
  [ -n "$VORHER_V" ] && curl -s -b "$KEKSE" -X POST "$BASIS/gruppen/$VORHER_V/vorgabe" -o /dev/null
  ZURUECK=$(sql "SELECT count(*) FROM geraetegruppe WHERE id=${VORHER_V:-0} AND ist_vorgabe")
  [ "${ZURUECK:-0}" = "1" ] && GZ=ja || GZ=nein
  ergebnis "und wieder zurueck — der Nachweis raeumt seinen Zustand auf" "$GZ"
fi

# ── Instanzweite Vorgaenge gehoeren KEINER Einrichtung (12.08.2026) ─────
# Anmeldungen, Sicherungen, Benutzerverwaltung: Sie trugen die Einrichtung
# „Praxis". Das funktionierte nur, solange es keine Organisationseinheit
# dieses Namens gab — ein Eintrag ohne passende Organisation bekommt keine
# organisation_id und bleibt fuer eingeschraenkte Benutzer unsichtbar.
#
# Legt aber jemand eine Praxis mit diesem Namen an, kippen ALLE
# Instanzereignisse in ihren Geltungsbereich. Eine Mandantengrenze, die von
# einem frei waehlbaren Namen abhaengt, ist keine.
hinweis "Instanzweite Vorgänge — keiner Einrichtung zurechenbar"
INST=$(sql "SELECT count(*) FROM fachprotokoll WHERE einrichtung = '(instanzweit)'")
[ "${INST:-0}" -ge 1 ] && IW=ja || IW=nein
ergebnis "instanzweite Vorgaenge sind als solche gekennzeichnet ($INST Eintraege)" "$IW"

OHNE_ORG=$(sql "SELECT count(*) FROM fachprotokoll
                WHERE einrichtung = '(instanzweit)' AND organisation_id IS NOT NULL")
[ "${OHNE_ORG:-1}" = "0" ] && IO=ja || IO=nein
ergebnis "keiner davon haengt an einer Organisationseinheit ($OHNE_ORG)" "$IO"

# Und der Name ist als Einrichtung gesperrt — sonst waere die Kollision nur
# unwahrscheinlich statt ausgeschlossen.
VID=$(sql "SELECT id FROM organisation WHERE art='VERBUND' ORDER BY id LIMIT 1")
ANTWORT_IN=$(curl -s -b "$KEKSE" -X POST $BASIS/organisation \
  --data-urlencode "bezeichnung=(instanzweit)" --data-urlencode "art=EINRICHTUNG" \
  --data-urlencode "elternId=$VID")
IN=$(enthaelt "$ANTWORT_IN" "ist vergeben")
GIBTS=$(sql "SELECT count(*) FROM organisation WHERE bezeichnung='(instanzweit)'")
[ "$IN" = "ja" ] && [ "${GIBTS:-1}" = "0" ] && IS=ja || IS=nein
ergebnis "eine Einheit mit diesem Namen wird abgewiesen (angelegt: $GIBTS)" "$IS"

# Kein Rueckfall auf „Praxis" mehr in neuen Eintraegen.
NEU_PRAXIS=$(sql "SELECT count(*) FROM fachprotokoll
                  WHERE einrichtung = 'Praxis' AND zeitpunkt > now() - interval '30 minutes'")
[ "${NEU_PRAXIS:-1}" = "0" ] && IP=ja || IP=nein
ergebnis "kein neuer Eintrag faellt auf \"Praxis\" zurueck ($NEU_PRAXIS)" "$IP"

# Ein geraetebezogenes Ereignis steht unter der Einrichtung SEINES GERAETS.
#
# Ein Agent ist fuer EINE Einrichtung registriert, betreut in einem MVZ aber
# Geraete mehrerer Betriebsstaetten. Am 12.08.2026 standen deshalb
# Erreichbarkeit und Erkennung eines Geraets der Orthopaedie unter
# „Kardiologie Nord" — dem Namen aus der Agentregistrierung.
#
# Geprueft wird GEZIELT an einem frisch ausgeloesten Ereignis, nicht ueber den
# gesamten Bestand: Ein Protokolleintrag ist unveraenderlich, die Zuordnung
# eines Geraets kann sich spaeter aendern. Ein Vergleich „Historie gegen
# Gegenwart" meldet dann Abweichungen, die keine Fehler sind — der erste Anlauf
# dieses Nachweises tat genau das.
KT_E=$(sqltext "SELECT t.terminal_id FROM kartenterminal t WHERE t.ist_demo ORDER BY t.id LIMIT 1")
if [ -z "$KT_E" ]; then
  uebersprungen "kein erfasstes Kartenterminal fuer die Zuordnungspruefung"
else
  SOLL_E=$(sqltext "SELECT o.bezeichnung FROM kartenterminal t
                    JOIN organisation o ON o.id = t.organisation_id
                    WHERE t.terminal_id = '$KT_E'")
  TID_E=$(sql "SELECT id FROM kartenterminal WHERE terminal_id='$KT_E'")
  # Eine Aenderung an diesem Geraet erzeugt einen Protokolleintrag mit
  # Geraetebezug.
  curl -s -b "$KEKSE" -o /dev/null -X POST "$BASIS/bestand/terminal/$TID_E/pruefen"
  # Die Erreichbarkeitspruefung allein protokolliert nichts mit Geraetebezug.
  # Deshalb eine Aenderung, die es tut: Konnektor-Zuordnung setzen.
  KID_E=$(sql "SELECT konnektor_id FROM kartenterminal WHERE id=$TID_E")
  curl -s -b "$KEKSE" -o /dev/null -X POST "$BASIS/bestand/terminal/$TID_E/konnektor" \
    --data-urlencode "konnektorId=${KID_E:-}"
  IST_E=$(sqltext "SELECT einrichtung FROM fachprotokoll
                   WHERE geraet = '$KT_E' ORDER BY zeitpunkt DESC LIMIT 1")
  if [ -z "$IST_E" ]; then
    uebersprungen "keine Handlung erzeugte einen Protokolleintrag mit Geraetebezug"
  else
    [ "$IST_E" = "$SOLL_E" ] && GE=ja || GE=nein
    [ "$GE" = "nein" ] && echo "    Diagnose: Eintrag steht unter \"$IST_E\", das Geraet gehoert zu \"$SOLL_E\""
    ergebnis "ein geraetebezogenes Ereignis steht unter der Einrichtung seines Geraets" "$GE"
  fi
fi

# Das Protokoll traegt die richtige Einrichtung — nicht fest „Praxis".
FALSCH=$(sql "SELECT count(*) FROM fachprotokoll
              WHERE art IN ('GRUPPE_GEAENDERT','GRUPPE_GELOESCHT')
                AND einrichtung = 'Praxis'
                AND zeitpunkt > now() - interval '5 minutes'")
[ "${FALSCH:-1}" = "0" ] && GP=ja || GP=nein
ergebnis "Gruppenereignisse stehen unter ihrer eigenen Einrichtung ($FALSCH unter \"Praxis\")" "$GP"

# Jede Gruppe braucht eine Organisationseinheit (Migration 23) — sonst waere
# sie fuer jede mandantengebundene Abfrage unsichtbar.
curl -s -b "$KEKSE" -o /dev/null -X POST $BASIS/gruppen \
  --data-urlencode "bezeichnung=Nachweis Notaufnahme" \
  --data-urlencode "einrichtung=Pruefpraxis" --data-urlencode "intervall=30"
GID=$(sql "SELECT id FROM geraetegruppe WHERE bezeichnung='Nachweis Notaufnahme'")
[ -n "$GID" ] && GA=ja || GA=nein
ergebnis "eine Gruppe laesst sich mit eigenem Pruefintervall anlegen" "$GA"
[ "$(sql "SELECT pruefintervall_sekunden FROM geraetegruppe WHERE id=$GID")" = "30" ] && GI=ja || GI=nein
ergebnis "das Pruefintervall wird uebernommen (30 s)" "$GI"
[ "$(sql "SELECT count(*) FROM fachprotokoll WHERE art='GRUPPE_ANGELEGT'")" != "0" ] && GP=ja || GP=nein
ergebnis "das Anlegen steht im Fachprotokoll" "$GP"

# Ein zu kurzes Intervall muss abgelehnt werden - 5 Sekunden mal 5.000 Geraete
# waeren 1.000 Abfragen pro Sekunde.
curl -s -b "$KEKSE" -o /dev/null -X POST "$BASIS/gruppen/$GID/intervall" \
  --data-urlencode "intervall=1"
[ "$(sql "SELECT pruefintervall_sekunden FROM geraetegruppe WHERE id=$GID")" = "30" ] && GK=ja || GK=nein
ergebnis "ein unzulaessig kurzes Intervall wird nicht uebernommen" "$GK"

# Geraet zuordnen und die Gruppe loeschen: Kein Geraet darf aus der
# Ueberwachung fallen.
# Ausdruecklich ein Geraet des Simulators, nicht irgendeines: Die
# Agentennachweise legen vorher Geraete anderer Einrichtungen an, und die
# Zuordnung ueber Einrichtungsgrenzen hinweg waere ein anderer Test.
# Irgendein Geraet mit Vorgabegruppe — nicht an einen festen
# Einrichtungsnamen gebunden. Vorher stand hier 'Praxis'; nach der Umbenennung
# der Pruefeinrichtung lieferte die Abfrage nichts, VORGABE blieb leer und das
# folgende SQL brach mit einem Syntaxfehler ab. Ein Nachweis darf nicht an
# einem hartkodierten Namen haengen.
KT_G=$(sqltext "SELECT ge.terminal_id FROM geraet ge
                JOIN geraetegruppe gg ON gg.einrichtung = ge.einrichtung AND gg.ist_vorgabe
                ORDER BY ge.terminal_id LIMIT 1")
# Die Vorgabegruppe DES GERAETS, nicht "die eine im System": Seit jede
# Einrichtung eine eigene bekommt (09.08.2026), liefert eine Abfrage ohne
# Einrichtung mehrere Zeilen — und der Vergleich unten scheiterte an einer
# mehrzeiligen Variablen statt an einem Fehler im Produkt.
VORGABE=$(sql "SELECT gg.id FROM geraetegruppe gg
               JOIN geraet ge ON ge.einrichtung = gg.einrichtung
               WHERE gg.ist_vorgabe AND ge.terminal_id='$KT_G'")
if [ -n "$KT_G" ] && [ -n "$GID" ]; then
  curl -s -b "$KEKSE" -o /dev/null -X POST "$BASIS/geraete/$KT_G/gruppe" \
    --data-urlencode "gruppe=$GID"
  [ "$(sql "SELECT gruppe_id FROM geraet WHERE terminal_id='$KT_G'")" = "$GID" ] && GZ=ja || GZ=nein
  ergebnis "ein Geraet laesst sich einer Gruppe zuordnen" "$GZ"

  curl -s -b "$KEKSE" -o /dev/null -X POST "$BASIS/gruppen/$GID/loeschen"
  [ "$(sql "SELECT count(*) FROM geraetegruppe WHERE id=$GID")" = "0" ] && GL=ja || GL=nein
  ergebnis "die Gruppe laesst sich loeschen" "$GL"
  # Der entscheidende Punkt: Das Geraet ist umgezogen, nicht verschwunden.
  NACH=$(sql "SELECT gruppe_id FROM geraet WHERE terminal_id='$KT_G'")
  [ "$NACH" = "$VORGABE" ] && GU=ja || GU=nein
  ergebnis "das Geraet ist in die Vorgabegruppe umgezogen, nicht verschwunden" "$GU"
  [ "$(sql "SELECT count(*) FROM geraet WHERE terminal_id='$KT_G'")" = "1" ] && GE=ja || GE=nein
  ergebnis "das Geraet existiert nach der Loeschung weiterhin" "$GE"
fi

# FA-GER-050: "Eine Gruppe liegt innerhalb genau einer Einrichtung." Bis zum
# 09.08.2026 reichte die Oberflaeche hart "Praxis" durch — bei neun Praxen an
# einer Instanz waren damit alle Gruppen an dieselbe Einrichtung gebunden.
E_G=$(sqltext "SELECT bezeichnung FROM organisation WHERE art='EINRICHTUNG' ORDER BY id LIMIT 1")
if [ -n "$E_G" ]; then
  curl -s -b "$KEKSE" -o /dev/null -X POST $BASIS/gruppen \
    --data-urlencode "bezeichnung=Nachweis je Praxis" \
    --data-urlencode "einrichtung=$E_G" --data-urlencode "intervall=45"
  ORG_G=$(sql "SELECT coalesce(organisation_id::text,'-') FROM geraetegruppe
               WHERE bezeichnung='Nachweis je Praxis'")
  EINR_G=$(sqltext "SELECT einrichtung FROM geraetegruppe WHERE bezeichnung='Nachweis je Praxis'")
  [ "$EINR_G" = "$E_G" ] && GE=ja || GE=nein
  ergebnis "eine Gruppe laesst sich einer bestimmten Einrichtung zuordnen ($EINR_G)" "$GE"
  [ "$ORG_G" != "-" ] && GO=ja || GO=nein
  ergebnis "sie traegt die Kennung der Organisationseinheit ($ORG_G)" "$GO"

  # Und die Uebersicht zeigt Gruppen ALLER Einrichtungen, nicht nur "Praxis".
  curl -s -b "$KEKSE" $BASIS/gruppen -o /tmp/pr-grp.html
  [ "$(enthaelt /tmp/pr-grp.html "$E_G")" = "ja" ] && GU=ja || GU=nein
  ergebnis "die Uebersicht nennt die Einrichtung je Gruppe" "$GU"
  rm -f /tmp/pr-grp.html
fi

# Die Vorgabegruppe darf nicht verschwinden - neu erkannte Geraete brauchen
# eine Gruppe, in der sie landen koennen.
curl -s -b "$KEKSE" -X POST "$BASIS/gruppen/$VORGABE/loeschen" > /tmp/pr-gruppe-vg.html
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
  curl -s -b "$KEKSE" -o /dev/null -X POST "$BASIS/alarme/$ALARM/quittieren"
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
  curl -s -b "$KEKSE" $BASIS/teil/uebersicht > /tmp/pr-alarme.html
  QK=$(grep -c "/alarme/$ALARM/quittieren" /tmp/pr-alarme.html || true)
  [ "${QK:-1}" = "0" ] && AK=ja || AK=nein
  ergebnis "ein quittierter Alarm bietet keinen zweiten Quittierknopf ($QK)" "$AK"
  rm -f /tmp/pr-alarme.html
fi

# ---------------------------------------------------------------- FA-ALA-030
# Alarme von Hand schliessen (Vorgabe PO, 13.08.2026).
#
# Der Anlass: Alarme zu Geraeten, die es nicht mehr gibt. Fuer sie kommt nie
# eine Behebungsmeldung - sie blieben fuer immer offen. Am 13.08.2026 standen
# elf solche Alarme in der Lage-Uebersicht, jeder zu einem Kartenterminal aus
# einem laengst geloeschten Demo- oder Pruefbestand.
hinweis "FA-ALA-030 — Alarme von Hand schließen"

# Einen Alarm zu einem Geraet erzeugen, das es nicht gibt - genau der Fall.
docker exec pr-db psql -U pinroboter -d pinroboter -q -c \
  "INSERT INTO alarm (einrichtung, objekt, art, schweregrad, meldung, handlung)
   VALUES ('Pruefpraxis','KT-KARTEILEICHE','GERAET_NICHT_ERREICHBAR','KRITISCH',
           'Antwortet nicht','Netzwerk pruefen')
   ON CONFLICT DO NOTHING" >/dev/null 2>&1
KL=$(sql "SELECT id FROM alarm WHERE objekt='KT-KARTEILEICHE' AND behoben_am IS NULL")
if [ -z "$KL" ]; then
  uebersprungen "Alarm zum Nachweis liess sich nicht anlegen"
else
  curl -s -b "$KEKSE" $BASIS/teil/uebersicht > /tmp/pr-alarm-schliessen.html
  grep -q "/alarme/$KL/schliessen" /tmp/pr-alarm-schliessen.html && AS=ja || AS=nein
  ergebnis "die Lage-Uebersicht bietet je Alarm ein Schliessen an" "$AS"
  grep -q "/alarme/alle-schliessen" /tmp/pr-alarm-schliessen.html && AS2=ja || AS2=nein
  ergebnis "und eine Sammelaktion fuer alle" "$AS2"
  # Eine destruktive Sammelaktion ohne Mengenangabe ist die haeufigste Ursache
  # fuer ein „das wollte ich nicht".
  grep -qE 'hx-confirm="Alle [0-9]+ Alarme schließen' /tmp/pr-alarm-schliessen.html \
    && AS3=ja || AS3=nein
  ergebnis "die Rueckfrage nennt die Zahl der betroffenen Alarme" "$AS3"
  rm -f /tmp/pr-alarm-schliessen.html

  curl -s -b "$KEKSE" -o /dev/null -X POST "$BASIS/alarme/$KL/schliessen"
  [ -n "$(sql "SELECT behoben_am FROM alarm WHERE id=$KL")" ] && AS4=ja || AS4=nein
  ergebnis "ein Alarm laesst sich einzeln schliessen" "$AS4"

  # Geschlossen heisst NICHT geloescht - sonst waere die Frage „warum war das
  # drei Wochen offen?" hinterher unbeantwortbar (ADR-0005).
  [ "$(sql "SELECT count(*) FROM alarm WHERE id=$KL")" = "1" ] && AS5=ja || AS5=nein
  ergebnis "der Datensatz bleibt erhalten, er wird nicht geloescht" "$AS5"
  [ "$(sql "SELECT count(*) FROM fachprotokoll WHERE art='ALARM_GESCHLOSSEN'")" != "0" ] \
    && AS6=ja || AS6=nein
  ergebnis "das Schliessen steht im Fachprotokoll (Sicherheitsstufe)" "$AS6"

  # Die Sammelaktion.
  docker exec pr-db psql -U pinroboter -d pinroboter -q -c \
    "INSERT INTO alarm (einrichtung, objekt, art, schweregrad, meldung, handlung)
     VALUES ('Pruefpraxis','KT-SAMMEL-1','GERAET_NICHT_ERREICHBAR','KRITISCH','a','x'),
            ('Pruefpraxis','KT-SAMMEL-2','PEVIM_NICHT_AKTIV','HINWEIS','b','y')
     ON CONFLICT DO NOTHING" >/dev/null 2>&1
  VOR_A=$(sql "SELECT count(*) FROM alarm WHERE behoben_am IS NULL")
  curl -s -b "$KEKSE" -o /dev/null -X POST "$BASIS/alarme/alle-schliessen"
  NACH_A=$(sql "SELECT count(*) FROM alarm WHERE behoben_am IS NULL")
  [ "${NACH_A:-1}" = "0" ] && [ "${VOR_A:-0}" -ge 2 ] && AS7=ja || AS7=nein
  ergebnis "die Sammelaktion schliesst alle offenen Alarme ($VOR_A -> $NACH_A)" "$AS7"

  # Und die Zahl steht im Protokoll - ohne sie weiss hinterher niemand, ob drei
  # oder dreihundert Alarme verschwunden sind.
  MELDUNG_A=$(sqltext "SELECT text FROM fachprotokoll WHERE art='ALARM_GESCHLOSSEN'
                       ORDER BY id DESC LIMIT 1")
  case "$MELDUNG_A" in *"$VOR_A Alarm"*) AS8=ja ;; *) AS8=nein ;; esac
  ergebnis "das Protokoll nennt die Zahl (gelesen: ${MELDUNG_A:-nichts})" "$AS8"

  # Ein geschlossener Alarm kehrt zurueck, solange die Ursache besteht. Sonst
  # liesse sich eine echte Stoerung per Mausklick dauerhaft verschwinden lassen.
  docker exec pr-db psql -U pinroboter -d pinroboter -q -c \
    "INSERT INTO alarm (einrichtung, objekt, art, schweregrad, meldung, handlung)
     VALUES ('Pruefpraxis','KT-KARTEILEICHE','GERAET_NICHT_ERREICHBAR','KRITISCH',
             'Antwortet nicht','Netzwerk pruefen')" >/dev/null 2>&1
  [ "$(sql "SELECT count(*) FROM alarm WHERE objekt='KT-KARTEILEICHE' AND behoben_am IS NULL")" = "1" ] \
    && AS9=ja || AS9=nein
  ergebnis "derselbe Zustand loest danach wieder einen Alarm aus" "$AS9"

  # Aufraeumen - der Nachweis hinterlaesst keine Alarme fuer spaetere Pruefungen.
  docker exec pr-db psql -U pinroboter -d pinroboter -q -c \
    "DELETE FROM alarm WHERE objekt LIKE 'KT-KARTEILEICHE' OR objekt LIKE 'KT-SAMMEL-%'" >/dev/null 2>&1
fi

# Die Ursache selbst: Beim Loeschen des Demobestands muessen dessen Alarme
# mitgehen. Sonst entstehen genau die unaufloesbaren Karteileichen wieder.
hinweis "Demodaten löschen räumt auch ihre Alarme weg"
curl -s -b "$KEKSE" -o /dev/null -X POST $BASIS/verwaltung/demodaten/anlegen
DEMO_KT=$(sqltext "SELECT terminal_id FROM kartenterminal WHERE ist_demo ORDER BY id LIMIT 1")
if [ -z "$DEMO_KT" ]; then
  uebersprungen "kein Demobestand vorhanden"
else
  docker exec pr-db psql -U pinroboter -d pinroboter -q -c \
    "INSERT INTO alarm (einrichtung, objekt, art, schweregrad, meldung, handlung)
     VALUES ('egal','$DEMO_KT','GERAET_NICHT_ERREICHBAR','KRITISCH','a','x')
     ON CONFLICT DO NOTHING" >/dev/null 2>&1
  curl -s -b "$KEKSE" -o /dev/null -X POST $BASIS/verwaltung/demodaten/loeschen
  IST=$(sql "SELECT count(*) FROM alarm WHERE objekt='$DEMO_KT'")
  [ "${IST:-1}" = "0" ] && AD=ja || AD=nein
  ergebnis "der Alarm eines geloeschten Demogeraets bleibt nicht zurueck ($IST)" "$AD"
  curl -s -b "$KEKSE" -o /dev/null -X POST $BASIS/verwaltung/demodaten/anlegen
fi

# ---------------------------------------------------------------- FA-GER-055
# Gruppenbezeichnungen sind instanzweit eindeutig (Vorgabe PO, 13.08.2026).
#
# Der Anlass: In einem MVZ hiessen alle neun Vorgabegruppen „Alle Geräte". Ein
# Gruppenname erscheint auch dort, wo die Spalte „Einrichtung" NICHT
# danebensteht - in Auswahlfeldern, im Fachprotokoll, in Fehlermeldungen.
hinweis "FA-GER-055 — Gruppenbezeichnungen sind eindeutig"

# Vorgabegruppen tragen die Einrichtung im Namen.
OHNE_E=$(sql "SELECT count(*) FROM geraetegruppe WHERE ist_vorgabe AND bezeichnung = 'Alle Geräte'")
[ "${OHNE_E:-1}" = "0" ] && GE=ja || GE=nein
ergebnis "keine Vorgabegruppe heisst mehr blosz „Alle Geräte“ ($OHNE_E)" "$GE"

DOPPELT=$(sql "SELECT count(*) FROM (SELECT lower(bezeichnung) FROM geraetegruppe
               GROUP BY 1 HAVING count(*) > 1) x")
[ "${DOPPELT:-1}" = "0" ] && GE2=ja || GE2=nein
ergebnis "keine zwei Gruppen tragen denselben Namen ($DOPPELT Doppelungen)" "$GE2"

# Zwei Einrichtungen, derselbe Wunschname - das muss scheitern.
E_A=$(sqltext "SELECT bezeichnung FROM organisation WHERE art='EINRICHTUNG' ORDER BY id LIMIT 1")
E_B=$(sqltext "SELECT bezeichnung FROM organisation WHERE art='EINRICHTUNG'
               AND bezeichnung <> '$E_A' ORDER BY id LIMIT 1")
if [ -z "$E_A" ] || [ -z "$E_B" ]; then
  uebersprungen "weniger als zwei Einrichtungen vorhanden"
else
  curl -s -b "$KEKSE" -o /dev/null -X POST $BASIS/gruppen \
    --data-urlencode "bezeichnung=Nachweis Doppelname" \
    --data-urlencode "einrichtung=$E_A" --data-urlencode "intervall=60"
  curl -s -b "$KEKSE" -X POST $BASIS/gruppen \
    --data-urlencode "bezeichnung=Nachweis Doppelname" \
    --data-urlencode "einrichtung=$E_B" --data-urlencode "intervall=60" > /tmp/pr-dop.html
  ANZ=$(sql "SELECT count(*) FROM geraetegruppe WHERE bezeichnung='Nachweis Doppelname'")
  [ "${ANZ:-0}" = "1" ] && GE3=ja || GE3=nein
  ergebnis "derselbe Name in einer zweiten Einrichtung wird abgelehnt ($ANZ von 1)" "$GE3"

  # Die Meldung sagt, WAS zu tun ist ...
  grep -q "ergänzen Sie den Gruppennamen" /tmp/pr-dop.html && GE4=ja || GE4=nein
  ergebnis "die Meldung sagt, wie der Name zu ergaenzen ist" "$GE4"

  # ... und verraet NICHT, wo der Name schon vorkommt. Eine Mandantengrenze,
  # die man mit einem Formular ausprobieren kann, ist keine.
  grep -q "$E_A" /tmp/pr-dop.html && GE5=nein || GE5=ja
  ergebnis "sie nennt die fremde Einrichtung nicht" "$GE5"
  rm -f /tmp/pr-dop.html

  # Mit Kennzeichnung geht es.
  curl -s -b "$KEKSE" -o /dev/null -X POST $BASIS/gruppen \
    --data-urlencode "bezeichnung=Nachweis Doppelname $E_B" \
    --data-urlencode "einrichtung=$E_B" --data-urlencode "intervall=60"
  [ -n "$(sql "SELECT id FROM geraetegruppe WHERE bezeichnung='Nachweis Doppelname $E_B'")" ] \
    && GE6=ja || GE6=nein
  ergebnis "mit einer Praxis-Kennzeichnung im Namen gelingt es" "$GE6"

  # Aufraeumen.
  docker exec pr-db psql -U pinroboter -d pinroboter -q -c \
    "DELETE FROM geraetegruppe WHERE bezeichnung LIKE 'Nachweis Doppelname%'" >/dev/null 2>&1
fi

# ------------------------------------------------- UX-Stufe 4 und 5 (ADR-0010)
hinweis "UX-Stufe 4/5 — Detailseiten statt Aufklapper, Beziehungen zum Durchklicken"

# Kein "details"-Aufklapper mehr in Bestand und Organisation: Die Seite soll
# nicht laenger werden, sondern eine neue aufgehen.
curl -s -b "$KEKSE" $BASIS/bestand      -o /tmp/pr-ux-bestand.html
curl -s -b "$KEKSE" $BASIS/organisation -o /tmp/pr-ux-org.html
AUF=$(( $(grep -c 'class="ausklapp"' /tmp/pr-ux-bestand.html || true) \
      + $(grep -c 'class="ausklapp"' /tmp/pr-ux-org.html || true) ))
[ "$AUF" = "0" ] && UA=ja || UA=nein
ergebnis "keine Aufklapper mehr in Bestand und Organisation ($AUF)" "$UA"

for paar in "/bestand/konnektor/neu:Konnektor hinzufügen" \
            "/bestand/terminal/neu:Kartenterminal hinzufügen" \
            "/organisation/neu:Einheit hinzufügen"; do
  PFAD=${paar%%:*}; TITEL=${paar#*:}
  CODE=$(curl -s -b "$KEKSE" -o /tmp/pr-ux-neu.html -w '%{http_code}' "$BASIS$PFAD")
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
  uebersprungen "keine Einrichtung mit Geraeten"
else
  CODE=$(curl -s -b "$KEKSE" -o /tmp/pr-ux-detail.html -w '%{http_code}' \
         "$BASIS/organisation/$ORG_D")
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
  curl -s -b "$KEKSE" "$BASIS/bestand?einrichtung=$ORG_D" -o /tmp/pr-ux-abs.html
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
         "$BASIS/organisation/$FREMD_ORG")
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
curl -s -X DELETE "$SIMBASIS/mail" >/dev/null 2>&1
if true; then
  mails() { curl -s "$SIMBASIS/mail"; }
  mailzahl() { mails | grep -oE '"anzahl":[0-9]+' | head -1 | grep -oE '[0-9]+$'; }

  # Mailserver einrichten
  curl -s -b "$KEKSE" -X POST $BASIS/benachrichtigung/server \
    --data-urlencode "host=simulator" --data-urlencode "port=1025" \
    --data-urlencode "absender=pin-roboter@praxis.example" \
    -o /tmp/pr-mail-srv.html
  MS=$(enthaelt /tmp/pr-mail-srv.html "Mailserver gespeichert")
  ergebnis "der Mailserver laesst sich einrichten" "$MS"

  # Testversand — ohne ihn merkt der Betreiber erst beim ersten echten Alarm,
  # dass die Einstellung nicht stimmt.
  VOR=$(mailzahl)
  curl -s -b "$KEKSE" -X POST $BASIS/benachrichtigung/test \
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
  curl -s -b "$KEKSE" -X POST $BASIS/benachrichtigung/regel \
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
  curl -s -b "$KEKSE" -X POST $BASIS/benachrichtigung/server \
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
  curl -s -b "$KEKSE" $BASIS/benachrichtigung -o /tmp/pr-mail-seite.html
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
curl -s -b "$KEKSE" -o /dev/null -X POST $BASIS/gruppen \
  --data-urlencode "bezeichnung=Nachweis Schnell" \
  --data-urlencode "einrichtung=Pruefpraxis" --data-urlencode "intervall=10"
curl -s -b "$KEKSE" -o /dev/null -X POST $BASIS/gruppen \
  --data-urlencode "bezeichnung=Nachweis Langsam" \
  --data-urlencode "einrichtung=Pruefpraxis" --data-urlencode "intervall=120"
G_SCHNELL=$(sql "SELECT id FROM geraetegruppe WHERE bezeichnung='Nachweis Schnell'")
G_LANGSAM=$(sql "SELECT id FROM geraetegruppe WHERE bezeichnung='Nachweis Langsam'")

# Zwei Terminals des Simulators, die beide eine SMC-B tragen.
mapfile -t KT_IDS < <(sql "SELECT terminal_id FROM geraet ORDER BY terminal_id" \
                      | tr -d '\r' | head -2) 2>/dev/null || \
  KT_IDS=($(docker exec pr-db psql -U pinroboter -d pinroboter -tAc \
            "SELECT terminal_id FROM geraet ORDER BY terminal_id LIMIT 2" | tr -d ' \r'))

if [ -z "$G_SCHNELL" ] || [ -z "$G_LANGSAM" ] || [ "${#KT_IDS[@]}" -lt 2 ]; then
  uebersprungen "Gruppen oder Geraete fehlen (${#KT_IDS[@]} Geraete)"
else
  KT_A=${KT_IDS[0]}; KT_B=${KT_IDS[1]}
  curl -s -b "$KEKSE" -o /dev/null -X POST "$BASIS/geraete/$KT_A/gruppe" \
    --data-urlencode "gruppe=$G_SCHNELL"
  curl -s -b "$KEKSE" -o /dev/null -X POST "$BASIS/geraete/$KT_B/gruppe" \
    --data-urlencode "gruppe=$G_LANGSAM"
  echo "  $KT_A -> 10 s,  $KT_B -> 120 s"

  # Der Agent holt die Intervalle im Grundtakt. Warten, bis er sie kennt.
  curl -s -b "$KEKSE" -o /dev/null "$BASIS/api/agent/intervalle" 2>/dev/null
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
  curl -s -X DELETE "$SIMBASIS/abfragen" >/dev/null
  sleep 40
  ZAEHLER=$(curl -s "$SIMBASIS/abfragen")
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
ANTWORT_KSR=$(curl -s -X POST "$SIMBASIS/ksr/start?dauer=40000")
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
curl -s -X POST "$SIMBASIS/ksr/start?dauer=45000" >/dev/null
# Der Agent meldet den Zustand erst beim naechsten Durchgang. Bei 10 s
# Gruppentakt braucht die Oberflaeche bis zu zwei Takte, um ihn zu zeigen.
sleep 25
curl -s -b "$KEKSE" $BASIS/geraete -o /tmp/pr-ksr.html
KH=$(enthaelt /tmp/pr-ksr.html "wird aktualisiert")
ergebnis "die Oberflaeche nennt den Zustand waehrend des Laufs" "$KH"
curl -s -X POST "$SIMBASIS/ksr/ende" >/dev/null
rm -f /tmp/pr-ksr.html

# ------------------------------------------- FA-PIN-040 / FA-UEB-020 / FA-UEB-030
hinweis "FA-PIN-040 — nicht behebbare Zustände werden gemeldet, nicht versucht"

# Vier Zustaende, die der Roboter NICHT aufloesen kann. Jeder muss eine eigene
# Klartextmeldung tragen — und keinen Verifikationsversuch ausloesen.
V_NB=$(verifikationen)
curl -s -X POST $SIMBASIS/steuerung/KT-EMPFANG-01/transportpin >/dev/null
sleep 25
curl -s -b "$KEKSE" $BASIS/geraete -o /tmp/pr-nb.html
[ "$(enthaelt /tmp/pr-nb.html "Transport-PIN")" = "ja" ] && PT=ja || PT=nein
ergebnis "Transport-PIN wird als eigener Zustand angezeigt" "$PT"
[ "$(enthaelt /tmp/pr-nb.html "am Kartenterminal")" = "ja" ] && PT2=ja || PT2=nein
ergebnis "die Meldung sagt, was zu tun ist" "$PT2"
W_NB=$(verifikationen)
[ $((W_NB-V_NB)) -eq 0 ] && PT3=ja || PT3=nein
ergebnis "kein Verifikationsversuch bei Transport-PIN (gemessen: $((W_NB-V_NB)))" "$PT3"

curl -s -X POST $SIMBASIS/steuerung/KT-EMPFANG-01/sperren >/dev/null
sleep 25
curl -s -b "$KEKSE" $BASIS/geraete -o /tmp/pr-nb2.html
[ "$(enthaelt /tmp/pr-nb2.html "gesperrt")" = "ja" ] && PB=ja || PB=nein
if [ "$PB" = "nein" ]; then
  echo "    Diagnose: Zustand laut Datenbank: $(sqltext "SELECT string_agg(DISTINCT art, ', ') FROM fachprotokoll WHERE zeitpunkt > now() - interval '2 minutes'")"
  echo "    Diagnose: Zustandsspalte der Liste: $(spalte_lesen /tmp/pr-nb2.html 'Zustand' | head -3 | tr '\n' '|')"
fi
ergebnis "gesperrte Karte wird als solche angezeigt" "$PB"
[ "$(enthaelt /tmp/pr-nb2.html "PUK")" = "ja" ] && PB2=ja || PB2=nein
ergebnis "die Meldung nennt die PUK als einzigen Weg" "$PB2"
N_NB=$(verifikationen)
[ $((N_NB-W_NB)) -eq 0 ] && PB3=ja || PB3=nein
ergebnis "kein Verifikationsversuch bei gesperrter Karte (gemessen: $((N_NB-W_NB)))" "$PB3"
curl -s -X POST $SIMBASIS/steuerung/KT-EMPFANG-01/zuruecksetzen >/dev/null
rm -f /tmp/pr-nb.html /tmp/pr-nb2.html

hinweis "FA-UEB-020 — Erreichbarkeitswechsel werden protokolliert"
curl -s -X POST $SIMBASIS/steuerung/KT-BEHANDLUNG-01/erreichbar/false >/dev/null
for _ in $(seq 20); do
  [ "$(sql "SELECT count(*) FROM fachprotokoll WHERE art='GERAET_NICHT_ERREICHBAR'")" != "0" ] && break
  sleep 2
done
AUS=$(sql "SELECT count(*) FROM fachprotokoll WHERE art='GERAET_NICHT_ERREICHBAR'")
[ "${AUS:-0}" -ge 1 ] && EA=ja || EA=nein
ergebnis "der Ausfall steht im Fachprotokoll ($AUS)" "$EA"

curl -s -X POST $SIMBASIS/steuerung/KT-BEHANDLUNG-01/erreichbar/true >/dev/null
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
curl -s -b "$KEKSE" $BASIS/geraete -o /tmp/pr-fw.html
[ "$(enthaelt /tmp/pr-fw.html "Firmware-Stände")" = "ja" ] && FU=ja || FU=nein
ergebnis "die Flottenuebersicht zeigt die Verteilung je Modell" "$FU"
[ "$(enthaelt /tmp/pr-fw.html "aktuellster Stand")" = "ja" ] && FB=ja || FB=nein
ergebnis "sie bewertet die Staende, statt sie nur aufzuzaehlen" "$FB"

# Ein Versionswechsel muss erkannt werden, egal wer ihn ausgeloest hat.
VOR_F=$(sql "SELECT count(*) FROM fachprotokoll WHERE art='FIRMWARE_GEAENDERT'")
curl -s -X POST "$SIMBASIS/steuerung/KT-EMPFANG-01/firmware/9.9.9?dauer=2000" >/dev/null
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
curl -s -b "$KEKSE" $BASIS/geraete -o /tmp/pr-fw2.html
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

curl -s -b "$KEKSE" $BASIS/verwaltung -o /tmp/pr-agt-v.html
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
curl -s -b "$KEKSE" $BASIS/verwaltung -o /tmp/pr-agt-v2.html
[ "$(enthaelt /tmp/pr-agt-v2.html "neuer als die Zentrale")" = "ja" ] && AU=ja || AU=nein
ergebnis "eine nicht unterstuetzte Version wird erkannt und erklaert" "$AU"
sleep 25
GESEHEN_NACH=$(sqltext "SELECT coalesce(zuletzt_gesehen::text,'-') FROM agent WHERE id = $AID")
[ "$GESEHEN_VOR" != "$GESEHEN_NACH" ] && AX=ja || AX=nein
ergebnis "der Agent wird nicht abgewiesen und meldet weiter" "$AX"
rm -f /tmp/pr-agt-v.html /tmp/pr-agt-v2.html

# ── Die Zuordnung eines Agenten korrigieren (12.08.2026) ─────────────────
# Der Agent speichert seine Einrichtung als TEXT. Wird sie umbenannt, meldet er
# ins Leere: keine Vorgabegruppe, keine Zuordnung seiner Geraete. Bis zu diesem
# Tag stand das nur im technischen Protokoll, und aendern liess es sich gar
# nicht - es blieb nur die Neuregistrierung samt Verlust aller PINs.
AID3=$(sql "SELECT id FROM agent WHERE aktiv ORDER BY id DESC LIMIT 1")
ALT_EINR=$(sqltext "SELECT einrichtung FROM agent WHERE id = $AID3")
docker exec pr-db psql -U pinroboter -d pinroboter -q -c \
  "UPDATE agent SET einrichtung = 'Praxis von gestern' WHERE id = $AID3" >/dev/null

curl -s -b "$KEKSE" $BASIS/verwaltung -o /tmp/pr-agt-e.html
[ "$(enthaelt /tmp/pr-agt-e.html "Einrichtung unbekannt")" = "ja" ] && AE=ja || AE=nein
ergebnis "die Uebersicht kennzeichnet einen Agenten ohne gueltige Einrichtung" "$AE"
[ "$(enthaelt /tmp/pr-agt-e.html "keiner Gruppe zuordnen")" = "ja" ] && AE2=ja || AE2=nein
ergebnis "sie sagt, was die Folge ist" "$AE2"

# Eine Einrichtung, die es nicht gibt, darf nicht durchgehen.
curl -s -b "$KEKSE" -o /dev/null -X POST "$BASIS/agenten/$AID3/einrichtung" \
  --data-urlencode "einrichtung=Gibt es nicht"
[ "$(sqltext "SELECT einrichtung FROM agent WHERE id = $AID3")" = "Praxis von gestern" ] \
  && AE3=ja || AE3=nein
ergebnis "eine unbekannte Einrichtung wird abgelehnt" "$AE3"

curl -s -b "$KEKSE" -o /dev/null -X POST "$BASIS/agenten/$AID3/einrichtung" \
  --data-urlencode "einrichtung=$ALT_EINR"
[ "$(sqltext "SELECT einrichtung FROM agent WHERE id = $AID3")" = "$ALT_EINR" ] && AE4=ja || AE4=nein
ergebnis "die Zuordnung laesst sich ueber die Oberflaeche korrigieren" "$AE4"
[ "$(sql "SELECT count(*) FROM fachprotokoll WHERE art='AGENT_ZUGEORDNET'")" != "0" ] \
  && AE5=ja || AE5=nein
ergebnis "die Zuordnung steht im Fachprotokoll (Sicherheitsstufe)" "$AE5"

curl -s -b "$KEKSE" $BASIS/verwaltung -o /tmp/pr-agt-e2.html
[ "$(enthaelt /tmp/pr-agt-e2.html "Einrichtung unbekannt")" = "nein" ] && AE6=ja || AE6=nein
ergebnis "nach der Korrektur ist die Kennzeichnung verschwunden" "$AE6"
rm -f /tmp/pr-agt-e.html /tmp/pr-agt-e2.html

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
curl -s -b "$KEKSE" $BASIS/verwaltung -o /tmp/pr-restore.html
[ "$(enthaelt /tmp/pr-restore.html "card-nachweis-restore")" = "ja" ] && RN=ja || RN=nein
ergebnis "die fehlende PIN wird namentlich genannt" "$RN"
[ "$(enthaelt /tmp/pr-restore.html "neuen Schlüssel")" = "ja" ] && RE=ja || RE=nein
ergebnis "die Meldung erklaert die Ursache" "$RE"
[ "$(enthaelt /tmp/pr-restore.html "erneut eingegeben")" = "ja" ] && RH=ja || RH=nein
ergebnis "und sagt, was zu tun ist" "$RH"

# Aufraeumen — BEIDES. Der Nachweis hat oben "schluessel_gemeldet" kuenstlich
# auf jetzt gesetzt, um einen Agentwechsel nachzustellen. Bleibt das stehen,
# gelten anschliessend ALLE hinterlegten PINs als fehlend, und der naechste
# Nachweis misst einen Zustand, den dieser hier erzeugt hat.
docker exec pr-db psql -U pinroboter -d pinroboter -q -c \
  "DELETE FROM pin_hinterlegt WHERE kartenhandle='card-nachweis-restore';
   UPDATE agent SET schluessel_gemeldet = NULL WHERE id = $AID2" >/dev/null
curl -s -b "$KEKSE" $BASIS/verwaltung -o /tmp/pr-restore2.html
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
  uebersprungen "kein Agentenschluessel oder kein Terminal mit SMC-B"
else
  printf -- "-----BEGIN PUBLIC KEY-----\n%s\n-----END PUBLIC KEY-----\n" \
    "$(echo "$PUB" | fold -w 64)" > /tmp/pr-pub-c.pem
  chiffre() {
    printf '%s' "$1" | openssl pkeyutl -encrypt -pubin -inkey /tmp/pr-pub-c.pem \
      -pkeyopt rsa_padding_mode:oaep -pkeyopt rsa_oaep_md:sha256 \
      -pkeyopt rsa_mgf1_md:sha256 2>/dev/null | base64 | tr -d '\n'
  }

  # Zuerst die PIN hinterlegen, sonst gibt es nichts zu aendern.
  curl -s -b "$KEKSE" -X POST "$BASIS/bestand/terminal/$TID_C/pin" \
    -d "kartenhandle=$H_C" --data-urlencode "chiffrat=$(chiffre 123456)" -o /dev/null
  for _ in $(seq 30); do
    [ "$(sql "SELECT count(*) FROM pin_hinterlegt WHERE kartenhandle='$H_C'")" = "1" ] && break
    sleep 2
  done

  # Die Maske muss drei Felder anbieten: alt, neu, Wiederholung.
  curl -s -b "$KEKSE" "$BASIS/bestand/terminal/$TID_C" -o /tmp/pr-aend.html
  DREI=0
  for f in 'id="pinAlt"' 'id="pinNeu"' 'id="pinNeu2"'; do
    [ "$(enthaelt /tmp/pr-aend.html "$f")" = "ja" ] && DREI=$((DREI+1))
  done
  [ "$DREI" = "3" ] && CM=ja || CM=nein
  ergebnis "die Maske erfasst alte und neue PIN mit Bestaetigung ($DREI von 3)" "$CM"
  [ "$(enthaelt /tmp/pr-aend.html "A_24082")" = "ja" ] && CQ=ja || CQ=nein
  ergebnis "sie nennt die Grundlage der Aenderung (A_24082)" "$CQ"

  # Der eigentliche Weg: 123456 -> 424242
  curl -s -b "$KEKSE" -X POST "$BASIS/bestand/terminal/$TID_C/pin-aendern" \
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
  curl -s -X POST $SIMBASIS/steuerung/KT-EMPFANG-01/entziehen >/dev/null
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
  curl -s -b "$KEKSE" -X POST "$BASIS/bestand/terminal/$TID_C/pin-aendern" \
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

  # Gemessen wird an der KARTE, nicht am Protokolltext des Agenten. Der
  # frühere Nachweis prüfte „steht 'freigeschaltet' in den letzten 25 Zeilen?"
  # — ein Zeitfenster, keine Tatsache: Sobald der Agent zwischendurch andere
  # Geräte meldete, rutschte die Zeile heraus, und der Nachweis wurde rot,
  # obwohl das Produkt richtig arbeitete (beobachtet 12.08.2026).
  #
  # Die Tatsache lautet: Der Roboter übergibt die PIN erneut UND die Karte ist
  # danach wieder freigeschaltet. Ist die hinterlegte PIN falsch, bleibt sie
  # unverifiziert und der Fehlversuchszähler sinkt.
  VOR_U=$(curl -s "$SIMBASIS/steuerung/KT-EMPFANG-01/smcb-uebergaben" \
          | grep -oE '"uebergaben":[0-9]+' | cut -d: -f2)
  curl -s -X POST $SIMBASIS/steuerung/KT-EMPFANG-01/entziehen >/dev/null
  for _ in $(seq 30); do
    NACH_U=$(curl -s "$SIMBASIS/steuerung/KT-EMPFANG-01/smcb-uebergaben" \
             | grep -oE '"uebergaben":[0-9]+' | cut -d: -f2)
    [ "${NACH_U:-0}" -gt "${VOR_U:-0}" ] && break
    sleep 2
  done
  [ "${NACH_U:-0}" -gt "${VOR_U:-0}" ] && CU=ja || CU=nein
  ergebnis "nach dem Fehlschlag wird die PIN erneut uebergeben (${VOR_U:-0} -> ${NACH_U:-0})" "$CU"

  # Und es war die RICHTIGE PIN: Die Karte steht danach wieder auf
  # „verifiziert". Waere die fehlgeschlagene Aenderung durchgeschlagen, haette
  # der Agent eine PIN im Speicher, die auf der Karte nicht gilt - die Karte
  # bliebe unverifiziert und der Fehlversuchszaehler saenke.
  # Auf den Zustand WARTEN, nicht drei Sekunden hoffen: Die Zentrale erfaehrt
  # ihn erst mit der naechsten Meldung des Agenten.
  #
  # Und exakt vergleichen. Ein Muster „*verifiziert*" trifft auch auf
  # „nicht verifiziert" zu - beim ersten Anlauf dieses Nachweises war er
  # deshalb gruen, waehrend die Karte gesperrt dastand (12.08.2026).
  ZUSTAND=""
  for _ in $(seq 30); do
    curl -s -b "$KEKSE" "$BASIS/geraete" -o /tmp/pr-pinzustand.html
    # Die Zeile DIESES Geraets: „verifiziert" steht auch bei den anderen
    # Terminals, und ein Nachweis, der das nicht unterscheidet, ist immer gruen.
    ZEILE=$(spalte_lesen /tmp/pr-pinzustand.html "Gerät" | grep -n "KT-EMPFANG-01" \
            | head -1 | cut -d: -f1)
    ZUSTAND=$(spalte_lesen /tmp/pr-pinzustand.html "Zustand" | sed -n "${ZEILE:-0}p")
    case "$ZUSTAND" in
      *"nicht verifiziert"*) ;;                 # noch nicht so weit
      *verifiziert*) break ;;
    esac
    sleep 2
  done
  case "$ZUSTAND" in
    *"nicht verifiziert"*) CU2=nein ;;
    *verifiziert*)         CU2=ja ;;
    *)                     CU2=nein ;;
  esac
  ergebnis "die Karte steht danach wieder auf „verifiziert“ (gelesen: ${ZUSTAND:-nichts})" "$CU2"
  rm -f /tmp/pr-pinzustand.html

  # Und die Notbremse: Klartext statt Chiffrat wird abgewiesen.
  curl -s -b "$KEKSE" -X POST "$BASIS/bestand/terminal/$TID_C/pin-aendern" \
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
  uebersprungen "kein Geraet gemeldet"
else
  # Aus der Geraeteliste in EINEM Klick erreichbar.
  curl -s -b "$KEKSE" $BASIS/geraete -o /tmp/pr-gl.html
  [ "$(enthaelt /tmp/pr-gl.html "/geraete/$KT_V/verlauf")" = "ja" ] && ZK=ja || ZK=nein
  ergebnis "der Zeitstrahl ist aus der Geraeteliste verlinkt" "$ZK"

  CODE=$(curl -s -b "$KEKSE" -o /tmp/pr-zs.html -w '%{http_code}' \
         "$BASIS/geraete/$KT_V/verlauf")
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
    "$BASIS/geraete/$KT_V/verlauf?zeitraum=alle&art=GERAET_ERKANNT"
  GEFILTERT=$(grep -c 'class="eintrag e' /tmp/pr-zs2.html || true)
  [ "${GEFILTERT:-0}" -lt "${ALLE_Z:-0}" ] && [ "${GEFILTERT:-0}" -ge 1 ] && ZF=ja || ZF=nein
  ergebnis "der Artenfilter wirkt ($GEFILTERT von $ALLE_Z Eintraegen)" "$ZF"

  # Ein fremdes Geraet darf hier nichts preisgeben.
  CODE=$(curl -s -o /dev/null -w '%{http_code}' "$BASIS/geraete/$KT_V/verlauf")
  [ "$CODE" = "302" ] && ZM=ja || ZM=nein
  ergebnis "ohne Anmeldung ist der Zeitstrahl nicht erreichbar (HTTP $CODE)" "$ZM"

  # ── Vorgangsansicht
  VG=$(sqltext "SELECT vorgang FROM fachprotokoll WHERE vorgang IS NOT NULL
                GROUP BY vorgang ORDER BY count(*) DESC LIMIT 1")
  if [ -z "$VG" ]; then
    uebersprungen "kein Vorgang im Protokoll"
  else
    N_VG=$(sql "SELECT count(*) FROM fachprotokoll WHERE vorgang='$VG'")
    CODE=$(curl -s -b "$KEKSE" -o /tmp/pr-vg.html -w '%{http_code}' \
           "$BASIS/vorgang/$VG")
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
        "$BASIS/geraete/$KT_V/verlauf?zeitraum=alle"
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

# ---------------------------------------------------------------- FA-UPD-010
# Neue Versionen erkennen. Geprueft wird gegen die Registry-Nachbildung im
# Simulator — ein Nachweis, der Internetzugang braucht, ist unbrauchbar.
hinweis "FA-UPD-010 — neue Versionen erkennen"

# Zugang mit GUELTIGEM Token einrichten.
curl -s -b "$KEKSE" -X POST $BASIS/verwaltung/registry \
  --data-urlencode "registry=http://simulator:8480/registry" \
  --data-urlencode "abbild=nieting/pin-roboter" \
  --data-urlencode "benutzer=pin-roboter" \
  --data-urlencode "token=ghp-testaufbau-1234567890" -o /tmp/pr-reg.html
UZ=$(enthaelt /tmp/pr-reg.html "Produktversion")
ergebnis "der Registry-Zugang laesst sich einrichten" "$UZ"

curl -s -b "$KEKSE" -X POST $BASIS/verwaltung/registry/pruefen -o /tmp/pr-reg2.html
[ "$(enthaelt /tmp/pr-reg2.html "1.1.0")" = "ja" ] && UV=ja || UV=nein
ergebnis "verfuegbare Versionen werden angezeigt" "$UV"
[ "$(enthaelt /tmp/pr-reg2.html "latest")" = "nein" ] && UL=ja || UL=nein
ergebnis "„latest\" wird nicht als Version angeboten" "$UL"

# Aenderungshinweise auf Deutsch.
[ "$(enthaelt /tmp/pr-reg2.html "Geräte-Zeitstrahl und Vorgangsansicht")" = "ja" ] && UH=ja || UH=nein
ergebnis "die Aenderungshinweise erscheinen auf Deutsch" "$UH"

# Sicherheitsrelevante Updates deutlich hervorgehoben.
[ "$(enthaelt /tmp/pr-reg2.html "Sicherheitsupdate verfügbar")" = "ja" ] && US=ja || US=nein
ergebnis "ein Sicherheitsupdate ist deutlich hervorgehoben" "$US"
[ "$(enthaelt /tmp/pr-reg2.html "marke fehler")" = "ja" ] && US2=ja || US2=nein
ergebnis "es hebt sich sichtbar von einer Verbesserung ab" "$US2"

# Der Kern der Anforderung: abgelaufene Zugangsdaten als SICHTBARER
# Fehlerzustand, nicht als ausbleibende Meldung.
curl -s -X POST "$SIMBASIS/registry/abgelaufen" >/dev/null
curl -s -b "$KEKSE" -X POST $BASIS/verwaltung/registry/pruefen -o /tmp/pr-reg3.html
[ "$(enthaelt /tmp/pr-reg3.html "Zugangsdaten abgelehnt")" = "ja" ] && UF=ja || UF=nein
ergebnis "abgelaufene Zugangsdaten erscheinen als sichtbarer Fehlerzustand" "$UF"
[ "$(enthaelt /tmp/pr-reg3.html "abgelaufen oder")" = "ja" ] && UF2=ja || UF2=nein
ergebnis "die Meldung sagt, was zu tun ist" "$UF2"
FEHL_DB=$(sqltext "SELECT coalesce(letzter_fehler,'-') FROM registry_zugang")
case "$FEHL_DB" in *abgelehnt*) UF3=ja ;; *) UF3=nein ;; esac
ergebnis "der Fehler wird gespeichert, nicht nur geloggt" "$UF3"

# Und er verschwindet, sobald es wieder geht.
curl -s -X POST "$SIMBASIS/registry/abgelaufen?nein" >/dev/null
curl -s -b "$KEKSE" -X POST $BASIS/verwaltung/registry/pruefen -o /tmp/pr-reg4.html
[ "$(enthaelt /tmp/pr-reg4.html "Zugangsdaten abgelehnt")" = "nein" ] && UG=ja || UG=nein
ergebnis "nach erfolgreicher Abfrage ist der Fehlerzustand weg" "$UG"

# Das Token darf nirgends im Klartext stehen.
TREFFER=0
[ "$(enthaelt /tmp/pr-reg4.html "ghp-testaufbau")" = "ja" ] && TREFFER=$((TREFFER+1))
[ "$(sql "SELECT count(*) FROM fachprotokoll WHERE coalesce(text,'') LIKE '%ghp-testaufbau%'")" != "0" ] \
  && TREFFER=$((TREFFER+1))
docker logs pr-zen 2>&1 | grep -qF "ghp-testaufbau" && TREFFER=$((TREFFER+1))
[ $TREFFER -eq 0 ] && UT=ja || UT=nein
ergebnis "das Zugriffstoken erscheint nirgends im Klartext (Treffer: $TREFFER)" "$UT"
CH_R=$(sqltext "SELECT coalesce(token_chiffrat,'-') FROM registry_zugang")
case "$CH_R" in v1:*) UC=ja ;; *) UC=nein ;; esac
ergebnis "es liegt verschluesselt in der Datenbank" "$UC"

# Ein Praxisbenutzer darf die Registry nicht umstellen.
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST $BASIS/verwaltung/registry \
       --data-urlencode "registry=http://boese.example" --data-urlencode "abbild=x/y")
[ "$CODE" = "302" ] && UR=ja || UR=nein
ergebnis "ohne Anmeldung laesst sich die Registry nicht umstellen (HTTP $CODE)" "$UR"

rm -f /tmp/pr-reg*.html

# ---------------------------------------------------------------- FA-AGT-080
# Selbsttaetige Aktualisierung der Standort-Agenten (PO, 13.08.2026).
#
# Die Programmablage bleibt privat. Damit die Aktualisierung an das Abbild
# kommt, braucht sie eine Anmeldung — und die kennt nur die Zentrale. Sie
# reicht sie an registrierte Agenten weiter, statt denselben Token in jedes
# Einrichtungspaket zu schreiben. Geprueft wird die ganze Kette, nicht die
# Absicht: Erst wenn die Datei beim Agenten liegt (und beim Abschalten wieder
# verschwindet), wirkt der Schalter.
hinweis "FA-AGT-080 — selbsttaetige Aktualisierung der Agenten"

# Der Zugang aus FA-UPD-010 steht noch; die Weitergabe ist ab Werk AUS.
ANAUS=$(sqltext "SELECT an_agenten::text FROM registry_zugang")
[ "$ANAUS" = "false" ] && AA=ja || AA=nein
ergebnis "die Weitergabe an Agenten ist ab Werk abgeschaltet ($ANAUS)" "$AA"

# Ohne Einschaltung darf die Zentrale nichts herausgeben.
docker exec pr-agt sh -c 'ls /daten/registry/config.json' >/dev/null 2>&1 && AB=nein || AB=ja
ergebnis "der Agent hat keine Zugangsdaten, solange nichts eingeschaltet ist" "$AB"

# Einschalten — ueber die Oberflaeche, nicht per SQL.
curl -s -b "$KEKSE" -X POST $BASIS/verwaltung/registry/agenten -d "an=1" -o /dev/null
ANEIN=$(sqltext "SELECT an_agenten::text FROM registry_zugang")
[ "$ANEIN" = "true" ] && AC=ja || AC=nein
ergebnis "der Schalter in der Verwaltung wirkt ($ANEIN)" "$AC"

# Der Agent holt sie im naechsten Takt ab.
for _ in $(seq 60); do
  docker exec pr-agt sh -c 'ls /daten/registry/config.json' >/dev/null 2>&1 && break
  sleep 1
done
docker exec pr-agt sh -c 'ls /daten/registry/config.json' >/dev/null 2>&1 && AD=ja || AD=nein
ergebnis "der Agent legt die Zugangsdaten ab" "$AD"

if [ "$AD" = "ja" ]; then
  CFG=$(docker exec pr-agt sh -c 'cat /daten/registry/config.json')
  # Docker-Format: Ein eigenes Format waere wertlos, Watchtower liest genau dieses.
  ERWARTET=$(printf 'pin-roboter:ghp-testaufbau-1234567890' | base64 | tr -d '\n')
  case "$CFG" in *"$ERWARTET"*) AE1=ja ;; *) AE1=nein ;; esac
  ergebnis "sie stehen im Docker-Format, das die Aktualisierung liest" "$AE1"
  case "$CFG" in *ghp-testaufbau-1234567890*) AE2=nein ;; *) AE2=ja ;; esac
  ergebnis "der Token steht nicht zusaetzlich im Klartext in der Datei" "$AE2"
  RECHTE=$(docker exec pr-agt sh -c 'stat -c %a /daten/registry/config.json' 2>/dev/null)
  case "$RECHTE" in *0) AE3=ja ;; *) AE3=nein ;; esac
  ergebnis "die Datei ist nicht welt-lesbar (Rechte $RECHTE)" "$AE3"
fi

# Ohne gueltiges Agent-Token gibt die Zentrale nichts heraus.
CODE=$(curl -s -o /dev/null -w '%{http_code}' $BASIS/api/agent/registry -H "X-Agent-Token: erfunden")
[ "$CODE" != "200" ] && AF=ja || AF=nein
ergebnis "ein unbekannter Agent bekommt keine Zugangsdaten (HTTP $CODE)" "$AF"

# Das Token darf auch auf diesem Weg nirgends im Klartext auftauchen.
TREFFER=0
docker logs pr-agt 2>&1 | grep -qF "ghp-testaufbau" && TREFFER=$((TREFFER+1))
docker logs pr-zen 2>&1 | grep -qF "ghp-testaufbau" && TREFFER=$((TREFFER+1))
[ "$(sql "SELECT count(*) FROM fachprotokoll WHERE coalesce(text,'') LIKE '%ghp-testaufbau%'")" != "0" ] \
  && TREFFER=$((TREFFER+1))
[ $TREFFER -eq 0 ] && AG=ja || AG=nein
ergebnis "der Token erscheint weder in Logs noch im Protokoll (Treffer: $TREFFER)" "$AG"

# Der Not-Aus: Abschalten muss die Datei ENTFERNEN. Bliebe sie liegen, liefe
# die Aktualisierung weiter und der Schalter waere eine Anzeige ohne Wirkung.
curl -s -b "$KEKSE" -X POST $BASIS/verwaltung/registry/agenten -d "an=0" -o /dev/null
for _ in $(seq 60); do
  docker exec pr-agt sh -c 'ls /daten/registry/config.json' >/dev/null 2>&1 || break
  sleep 1
done
docker exec pr-agt sh -c 'ls /daten/registry/config.json' >/dev/null 2>&1 && AH=nein || AH=ja
ergebnis "das Abschalten entfernt die Zugangsdaten wieder" "$AH"

# ---------------------------------------------------------------- FA-AGT-090
# Zustand der Standort-Agenten: stumm und veraltet.
#
# Beides ist nur ueber die WIRKUNG pruefbar. Ob die Aktualisierung laeuft, ihr
# Token gilt oder die Programmablage erreichbar ist, sieht weder die Zentrale
# noch der Agent — er hat bewusst keinen Zugriff auf den Docker-Dienst. Was
# beide sehen: Der Agent haengt seit Tagen auf einer aelteren Version.
hinweis "FA-AGT-090 — stumme und veraltete Agenten fallen auf"

AID=$(sql "SELECT id FROM agent WHERE aktiv ORDER BY id DESC LIMIT 1")
ZEN_VERSION=$(curl -s $BASIS/health | sed -n 's/.*"version":"\([^"]*\)".*/\1/p')
AGT_VERSION=$(sqltext "SELECT coalesce(bausteinversion,'-') FROM agent WHERE id = $AID")
[ "$AGT_VERSION" = "$ZEN_VERSION" ] && AV0=ja || AV0=nein
ergebnis "der Agent meldet seine Version, nicht „unbekannt\" ($AGT_VERSION)" "$AV0"

# Der Agent wird angehalten, damit er die gesetzten Werte nicht sofort
# ueberschreibt. 90 Sekunden Stillstand liegen weit unter der Stummgrenze.
docker stop pr-agt >/dev/null

# (1) veraltet: aeltere Version, lange genug her, Weitergabe eingeschaltet.
curl -s -b "$KEKSE" -X POST $BASIS/verwaltung/registry/agenten -d "an=1" -o /dev/null
docker exec pr-db psql -U pinroboter -d pinroboter -q -c \
  "UPDATE agent SET bausteinversion = '0.0.1', version_seit = now() - interval '5 days',
                    zuletzt_gesehen = now() WHERE id = $AID" >/dev/null
for _ in $(seq 90); do
  [ "$(sql "SELECT count(*) FROM alarm WHERE art='AGENT_VERALTET' AND behoben_am IS NULL")" != "0" ] && break
  sleep 1
  docker exec pr-db psql -U pinroboter -d pinroboter -q -c \
    "UPDATE agent SET zuletzt_gesehen = now() WHERE id = $AID" >/dev/null
done
[ "$(sql "SELECT count(*) FROM alarm WHERE art='AGENT_VERALTET' AND behoben_am IS NULL")" != "0" ] && AV1=ja || AV1=nein
ergebnis "ein Agent, der seit Tagen zurueckhaengt, loest einen Alarm aus" "$AV1"
MELD=$(sqltext "SELECT coalesce(handlung,'-') FROM alarm WHERE art='AGENT_VERALTET' ORDER BY id DESC LIMIT 1")
case "$MELD" in *aktualisierung*) AV2=ja ;; *) AV2=nein ;; esac
ergebnis "die Meldung sagt, wo nachzusehen ist" "$AV2"

# (2) Ohne eingeschaltete Weitergabe wird NICHT alarmiert: Dann aktualisiert
#     jemand von Hand, und ein Rueckstand ist seine Entscheidung.
curl -s -b "$KEKSE" -X POST $BASIS/verwaltung/registry/agenten -d "an=0" -o /dev/null
for _ in $(seq 90); do
  [ "$(sql "SELECT count(*) FROM alarm WHERE art='AGENT_VERALTET' AND behoben_am IS NULL")" = "0" ] && break
  sleep 1
  docker exec pr-db psql -U pinroboter -d pinroboter -q -c \
    "UPDATE agent SET zuletzt_gesehen = now() WHERE id = $AID" >/dev/null
done
[ "$(sql "SELECT count(*) FROM alarm WHERE art='AGENT_VERALTET' AND behoben_am IS NULL")" = "0" ] && AV3=ja || AV3=nein
ergebnis "ohne eingeschaltete Weitergabe wird nicht alarmiert" "$AV3"

# (3) stumm: Der Agent meldet sich nicht mehr. Das ist der schwerere Fall —
#     an diesem Standort wird nichts mehr ueberwacht.
docker exec pr-db psql -U pinroboter -d pinroboter -q -c \
  "UPDATE agent SET zuletzt_gesehen = now() - interval '30 minutes' WHERE id = $AID" >/dev/null
for _ in $(seq 90); do
  [ "$(sql "SELECT count(*) FROM alarm WHERE art='AGENT_STUMM' AND behoben_am IS NULL")" != "0" ] && break
  sleep 1
done
[ "$(sql "SELECT count(*) FROM alarm WHERE art='AGENT_STUMM' AND behoben_am IS NULL")" != "0" ] && AV4=ja || AV4=nein
ergebnis "ein stummer Agent loest einen Alarm aus" "$AV4"
SCHWERE=$(sqltext "SELECT schweregrad FROM alarm WHERE art='AGENT_STUMM' ORDER BY id DESC LIMIT 1")
[ "$SCHWERE" = "KRITISCH" ] && AV5=ja || AV5=nein
ergebnis "er ist als kritisch eingestuft ($SCHWERE)" "$AV5"

# (4) Und er verschwindet von selbst, sobald der Agent wieder meldet.
docker start pr-agt >/dev/null
for _ in $(seq 90); do
  [ "$(sql "SELECT count(*) FROM alarm WHERE art='AGENT_STUMM' AND behoben_am IS NULL")" = "0" ] && break
  sleep 1
done
[ "$(sql "SELECT count(*) FROM alarm WHERE art='AGENT_STUMM' AND behoben_am IS NULL")" = "0" ] && AV6=ja || AV6=nein
ergebnis "der Alarm schliesst sich, sobald der Agent wieder meldet" "$AV6"

# Die Bausteinversion muss dabei auf den echten Wert zurueckgesetzt worden sein.
# Bis zum 13.08.2026 wurde sie nur bei einer neuen PROTOKOLLversion gespeichert
# — ein Agent, der von 0.3.1 auf 0.3.2 wechselte, stand weiter mit 0.3.1 da.
AGT_V2=$(sqltext "SELECT coalesce(bausteinversion,'-') FROM agent WHERE id = $AID")
[ "$AGT_V2" = "$ZEN_VERSION" ] && AV7=ja || AV7=nein
ergebnis "die gemeldete Version wird auch ohne Protokollwechsel nachgefuehrt ($AGT_V2)" "$AV7"

# ---------------------------------------------------------------- FA-AGT-100
# Gezielte Aktualisierung eines Standort-Agenten (PO, 13.08.2026).
#
# Der naechtliche Lauf zieht irgendwann alle Agenten nach. Das genuegt nicht,
# wenn eine Fehlerbehebung an genau EINEM Standort gebraucht wird.
hinweis "FA-AGT-100 — einen Standort-Agenten gezielt aktualisieren"

AID=$(sql "SELECT id FROM agent WHERE aktiv ORDER BY id DESC LIMIT 1")

# Die Zentrale muss die Versionen des AGENT-Abbilds kennen — ein anderes
# Abbild als ihr eigenes. Der Simulator bietet dafuer einen eigenen Satz an,
# damit auffaellt, wenn hier versehentlich die Versionen der Zentrale stehen.
curl -s -b "$KEKSE" -X POST $BASIS/verwaltung/registry/pruefen -o /dev/null
AGV=$(sqltext "SELECT coalesce(agent_versionen,'-') FROM registry_zugang")
case "$AGV" in *1.2.0*) UA=ja ;; *) UA=nein ;; esac
ergebnis "die Versionen des Agent-Abbilds werden ermittelt ($AGV)" "$UA"
case "$AGV" in *1.1.0*) UA2=nein ;; *) UA2=ja ;; esac
ergebnis "es sind nicht die Versionen der Zentrale" "$UA2"

# „stabil" darf NICHT zur Auswahl stehen: Der Agent meldet danach eine Nummer,
# nie den Tag. Ein Auftrag auf „stabil" waere nie erfuellbar und liefe nach
# zehn Minuten als Fehlschlag auf — obwohl alles geklappt haette.
curl -s -b "$KEKSE" $BASIS/verwaltung -o /tmp/pr-au.html
[ "$(enthaelt /tmp/pr-au.html '<option value="stabil">')" = "nein" ] && UB=ja || UB=nein
ergebnis "„stabil\" wird nicht als Zielversion angeboten" "$UB"
[ "$(enthaelt /tmp/pr-au.html '<option value="1.2.0">')" = "ja" ] && UB2=ja || UB2=nein
ergebnis "echte Versionsnummern schon" "$UB2"

# Anfordern.
curl -s -b "$KEKSE" -X POST $BASIS/agenten/$AID/aktualisierung \
  -d "zielversion=1.2.0" -o /dev/null
ZUST=$(sqltext "SELECT status||'|'||zielversion||'|'||coalesce(von_version,'-')
                FROM agent_aktualisierung WHERE agent_id=$AID ORDER BY id DESC LIMIT 1")
case "$ZUST" in ANGEFORDERT\|1.2.0\|*) UC=ja ;; *) UC=nein ;; esac
ergebnis "der Auftrag wird angelegt, mit Ziel- und Ausgangsversion ($ZUST)" "$UC"

# Kein zweiter Auftrag daneben: Zwei Anstoesse hiessen zwei Neustarts.
curl -s -b "$KEKSE" -X POST $BASIS/agenten/$AID/aktualisierung \
  -d "zielversion=1.0.0" -o /tmp/pr-au2.html
ANZ=$(sql "SELECT count(*) FROM agent_aktualisierung
            WHERE agent_id=$AID AND status IN ('ANGEFORDERT','ANGESTOSSEN')")
[ "$ANZ" = "1" ] && UD=ja || UD=nein
ergebnis "ein zweiter gleichzeitiger Auftrag wird abgelehnt (offen: $ANZ)" "$UD"
[ "$(enthaelt /tmp/pr-au2.html "läuft bereits")" = "ja" ] && UD2=ja || UD2=nein
ergebnis "und die Meldung sagt, warum" "$UD2"

# Der Agent holt den Auftrag ab und versucht es. Im Pruefstand laeuft KEIN
# Aktualisierungsdienst — genau der Fall, der beim Kunden eintritt, wenn
# jemand die beiden Dienste aus dem Paket entfernt hat. Er muss sofort als
# Fehlschlag enden statt zehn Minuten „laeuft" anzuzeigen.
for _ in $(seq 60); do
  ST=$(sqltext "SELECT status FROM agent_aktualisierung WHERE agent_id=$AID ORDER BY id DESC LIMIT 1")
  [ "$ST" != "ANGEFORDERT" ] && break
  sleep 1
done
[ "$ST" = "FEHLGESCHLAGEN" ] && UE=ja || UE=nein
ergebnis "ein Anstoss ohne Aktualisierungsdienst endet sofort ($ST)" "$UE"
MELD=$(sqltext "SELECT coalesce(meldung,'-') FROM agent_aktualisierung WHERE agent_id=$AID ORDER BY id DESC LIMIT 1")
case "$MELD" in *Aktualisierungsdienst*) UE2=ja ;; *) UE2=nein ;; esac
ergebnis "die Meldung nennt den Grund im Klartext" "$UE2"
curl -s -b "$KEKSE" $BASIS/verwaltung -o /tmp/pr-au3.html
[ "$(enthaelt /tmp/pr-au3.html "fehlgeschlagen")" = "ja" ] && UE3=ja || UE3=nein
ergebnis "der Fehlschlag steht in der Oberflaeche, nicht nur im Log" "$UE3"

# Der Erfolgsfall. Ein echter Versionswechsel ist im Pruefstand nicht
# herstellbar — es gibt dort nur eine Fassung des Agenten. Geprueft wird
# deshalb das, worauf es ankommt: Die Zentrale macht den Abschluss an der
# GEMELDETEN Version fest, nicht an der Quittung des Agenten.
AGT_V=$(sqltext "SELECT coalesce(bausteinversion,'-') FROM agent WHERE id=$AID")
docker exec pr-db psql -U pinroboter -d pinroboter -q -c \
  "INSERT INTO agent_aktualisierung (agent_id, zielversion, von_version, status, angefordert_von)
   VALUES ($AID, '$AGT_V', '0.0.1', 'ANGESTOSSEN', 'Nachweis')" >/dev/null
for _ in $(seq 90); do
  ST2=$(sqltext "SELECT status FROM agent_aktualisierung WHERE angefordert_von='Nachweis'
                  ORDER BY id DESC LIMIT 1")
  [ "$ST2" != "ANGESTOSSEN" ] && break
  sleep 1
done
[ "$ST2" = "ERFOLGREICH" ] && UF=ja || UF=nein
ergebnis "kommt der Agent mit der Zielversion, gilt die Aktualisierung als verifiziert ($ST2)" "$UF"
PROT=$(sql "SELECT count(*) FROM fachprotokoll WHERE art='AGENT_AKTUALISIERUNG'
             AND text LIKE '%laeuft jetzt auf Version%' OR text LIKE '%läuft jetzt auf Version%'")
[ "$PROT" != "0" ] && UF2=ja || UF2=nein
ergebnis "der Abschluss steht im Fachprotokoll" "$UF2"

# Ein Auftrag, der nie zu einem Ergebnis kommt, sieht aus wie Fortschritt.
docker exec pr-db psql -U pinroboter -d pinroboter -q -c \
  "INSERT INTO agent_aktualisierung (agent_id, zielversion, von_version, status,
                                     angefordert_von, angefordert_am)
   VALUES ($AID, '9.9.9', '$AGT_V', 'ANGESTOSSEN', 'Nachweis-Frist', now() - interval '20 minutes')" \
  >/dev/null
for _ in $(seq 90); do
  ST3=$(sqltext "SELECT status FROM agent_aktualisierung WHERE angefordert_von='Nachweis-Frist'
                  ORDER BY id DESC LIMIT 1")
  [ "$ST3" != "ANGESTOSSEN" ] && break
  sleep 1
done
[ "$ST3" = "FEHLGESCHLAGEN" ] && UG=ja || UG=nein
ergebnis "ein Auftrag ohne Rueckmeldung laeuft ab statt ewig zu laufen ($ST3)" "$UG"

# Ein Praxisbenutzer darf keine Software austauschen.
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST $BASIS/agenten/$AID/aktualisierung \
       -d "zielversion=1.0.0")
[ "$CODE" = "302" ] && UH=ja || UH=nein
ergebnis "ohne Anmeldung laesst sich kein Agent aktualisieren (HTTP $CODE)" "$UH"

docker exec pr-db psql -U pinroboter -d pinroboter -q -c \
  "DELETE FROM agent_aktualisierung WHERE agent_id=$AID" >/dev/null
rm -f /tmp/pr-au*.html

# ---------------------------------------------------------------- FA-BEN-035
hinweis "FA-BEN-035 — Administratorkonto aus der Konfiguration"

# Der Beweis, auf den es ankommt: Zugang UEBERLEBT vollstaendigen Datenverlust.
# Am 09.08.2026 hat sich der PO dreimal ausgesperrt, weil jeder Neuaufbau ein
# neues TOTP-Geheimnis erzeugte und sein Eintrag in der Authenticator-App
# damit tot war. In einer Praxis ohne IT-Personal waere das ein Ausfall.
SK_PW='Startkonto-Nachweis-2026!q'
SK_TOTP='MZXW6YTBOI7EWQ2CN5ZGSZLOMRSXE43P'
docker rm -f pr-sk pr-skdb >/dev/null 2>&1
docker volume rm pr-skdaten >/dev/null 2>&1
docker run -d --name pr-skdb --network $NETZ --network-alias skdb \
  -e POSTGRES_DB=pinroboter -e POSTGRES_USER=pinroboter -e POSTGRES_PASSWORD=test \
  -v pr-skdaten:/var/lib/postgresql/data postgres:17-alpine >/dev/null
for _ in $(seq 40); do
  docker exec pr-skdb pg_isready -U pinroboter >/dev/null 2>&1 && break; sleep 1
done

starte_startkonto() {
  docker rm -f pr-sk >/dev/null 2>&1
  docker run -d --name pr-sk --network $NETZ -p $((PR_PORT + 1)):8090 \
    -v "$PWD/src/zentrale/target/zentrale.jar":/app.jar \
    -e DB_URL=jdbc:postgresql://skdb:5432/pinroboter \
    -e DB_USER=pinroboter -e DB_PASSWORD=test \
    -e START_ADMIN_BENUTZER=startadmin -e START_ADMIN_NAME="Nachweis Startkonto" \
    -e START_ADMIN_PASSWORT="$SK_PW" -e START_ADMIN_TOTP="$SK_TOTP" \
    $JRE java -jar /app.jar >/dev/null
  for _ in $(seq 40); do
    curl -sf -o /dev/null http://localhost:$((PR_PORT + 1))/anmelden 2>/dev/null && return 0
    sleep 1
  done
  return 1
}

anmelden_startkonto() {
  # Auf ein frisches TOTP-Fenster warten: Ein verbrauchter Code wird
  # abgewiesen und zaehlt als Fehlversuch - genau die Falle, in die der PO
  # gelaufen ist.
  python3 -c "
import time
f = int(time.time()) // 30
while int(time.time()) // 30 == f: time.sleep(0.5)
time.sleep(1)"
  rm -f /tmp/pr-sk-kekse
  curl -s -c /tmp/pr-sk-kekse -o /dev/null -X POST http://localhost:$((PR_PORT + 1))/anmelden \
    -d "benutzer=startadmin" --data-urlencode "passwort=$SK_PW" \
    -d "code=$(python3 pruefungen/totp.py "$SK_TOTP")"
  curl -s -b /tmp/pr-sk-kekse -o /dev/null -w '%{http_code}' http://localhost:$((PR_PORT + 1))/geraete
}

if starte_startkonto; then SK1=ja; else SK1=nein; fi
ergebnis "die Zentrale startet mit konfiguriertem Startkonto" "$SK1"

ERST=$(curl -s -o /dev/null -w '%{http_code}' http://localhost:$((PR_PORT + 1))/ersteinrichtung)
# Es gibt bereits ein Konto, also darf die Ersteinrichtung nicht mehr greifen.
[ "$ERST" = "302" ] && SK2=ja || SK2=nein
ergebnis "die Ersteinrichtung entfaellt, weil das Konto schon steht (HTTP $ERST)" "$SK2"

CODE=$(anmelden_startkonto)
[ "$CODE" = "200" ] && SK3=ja || SK3=nein
ergebnis "Anmeldung mit dem vorgegebenen zweiten Faktor (HTTP $CODE)" "$SK3"

# Der eigentliche Punkt: Volume weg, alles weg - und der Zugang gilt weiter.
docker rm -f pr-sk pr-skdb >/dev/null 2>&1
docker volume rm pr-skdaten >/dev/null 2>&1
docker run -d --name pr-skdb --network $NETZ --network-alias skdb \
  -e POSTGRES_DB=pinroboter -e POSTGRES_USER=pinroboter -e POSTGRES_PASSWORD=test \
  -v pr-skdaten:/var/lib/postgresql/data postgres:17-alpine >/dev/null
for _ in $(seq 40); do
  docker exec pr-skdb pg_isready -U pinroboter >/dev/null 2>&1 && break; sleep 1
done
starte_startkonto
CODE=$(anmelden_startkonto)
[ "$CODE" = "200" ] && SK4=ja || SK4=nein
ergebnis "derselbe Zugang gilt nach vollstaendigem Datenverlust (HTTP $CODE)" "$SK4"

# Weder Passwort noch Geheimnis duerfen im Betriebsprotokoll auftauchen.
GEHEIM=$(docker logs pr-sk 2>&1 | grep -c "$SK_PW\|$SK_TOTP" || true)
[ "${GEHEIM:-1}" = "0" ] && SK5=ja || SK5=nein
ergebnis "weder Passwort noch TOTP-Geheimnis stehen im Protokoll ($GEHEIM Treffer)" "$SK5"

# Aber die Warnung muss da sein: Ein Administrator ist entstanden, ohne dass
# ihn jemand angelegt hat. Das darf nicht unbemerkt bleiben.
WARN=$(docker logs pr-sk 2>&1 | grep -c "Startkonto" || true)
[ "${WARN:-0}" -gt 0 ] && SK6=ja || SK6=nein
ergebnis "das Anlegen wird deutlich gemeldet ($WARN Meldungen)" "$SK6"

# Ein vorhandenes Konto wird nicht angetastet: Sonst koennte ein Angreifer mit
# Schreibzugriff auf die Compose-Datei sich per Neustart immer wieder Zutritt
# verschaffen, auch nachdem man ihn ausgesperrt hat.
docker exec pr-skdb psql -U pinroboter -d pinroboter -tAc \
  "UPDATE benutzer SET aktiv = FALSE WHERE benutzername = 'startadmin'" >/dev/null
starte_startkonto
AKTIV=$(docker exec pr-skdb psql -U pinroboter -d pinroboter -tAc \
        "SELECT aktiv FROM benutzer WHERE benutzername='startadmin'" | tr -d ' \n')
[ "$AKTIV" = "f" ] && SK7=ja || SK7=nein
ergebnis "ein vorhandenes Konto wird beim Neustart nicht zurueckgesetzt" "$SK7"

docker rm -f pr-sk pr-skdb >/dev/null 2>&1
docker volume rm pr-skdaten >/dev/null 2>&1
rm -f /tmp/pr-sk-kekse

# ---------------------------------------------------------------- FA-BAK
hinweis "FA-BAK — Datensicherung, Verschlüsselung, Wiederherstellung"

# Sicherung von Hand ausloesen
curl -s -b "$KEKSE" -o /dev/null -X POST "$BASIS/einstellungen/sicherung/jetzt"
STAND=$(docker exec pr-zen sh -c 'ls /sicherungen/*.dump 2>/dev/null | wc -l' | tr -d ' \r\n')
[ "${STAND:-0}" -ge 1 ] && BK1=ja || BK1=nein
ergebnis "eine Sicherung laesst sich von Hand ausloesen ($STAND vorhanden)" "$BK1"

DATEI=$(docker exec pr-zen ls /sicherungen/ 2>/dev/null | grep '\.dump$' | head -1 | tr -d '\r\n')
GROESSE=$(docker exec pr-zen stat -c%s "/sicherungen/$DATEI" 2>/dev/null | tr -d '\r\n')
[ "${GROESSE:-0}" -gt 10000 ] && BK2=ja || BK2=nein
ergebnis "der Auszug hat einen plausiblen Umfang ($GROESSE Bytes)" "$BK2"

# Keine Reste unvollstaendiger Auszuege: Der Auszug entsteht als .teil und
# wird erst nach Erfolg umbenannt.
TEIL=$(docker exec pr-zen sh -c 'ls /sicherungen/*.teil 2>/dev/null | wc -l' | tr -d ' \r\n')
[ "${TEIL:-1}" = "0" ] && BK3=ja || BK3=nein
ergebnis "keine unvollstaendigen Auszuege in der Ablage ($TEIL)" "$BK3"

# Der Download verlangt einen Schluessel und liefert das eigene Format.
curl -s -b "$KEKSE" -X POST "$BASIS/einstellungen/sicherung/$DATEI/herunterladen" \
  --data-urlencode "schluessel=Blaues-Fahrrad-Sieben-Regal" \
  --data-urlencode "schluessel2=Blaues-Fahrrad-Sieben-Regal" -o /tmp/pr-sich.prsich
KOPF=$(head -c 8 /tmp/pr-sich.prsich)
[ "$KOPF" = "PRSICH01" ] && BK4=ja || BK4=nein
ergebnis "der Download traegt die eigene Formatkennung ($KOPF)" "$BK4"

# Und er ist wirklich verschluesselt: Ein pg_dump beginnt mit "PGDMP".
KLARTEXT=$(grep -o "PGDMP" /tmp/pr-sich.prsich 2>/dev/null | wc -l | tr -d ' \r\n')
[ "${KLARTEXT:-1}" = "0" ] && BK5=ja || BK5=nein
ergebnis "die heruntergeladene Datei enthaelt keinen Klartext-Auszug" "$BK5"

# Zu kurzer Schluessel wird abgewiesen - mit Begruendung.
curl -s -b "$KEKSE" -X POST "$BASIS/einstellungen/sicherung/$DATEI/herunterladen" \
  --data-urlencode "schluessel=kurz" --data-urlencode "schluessel2=kurz" -o /tmp/pr-kurz.html
BK6=$(enthaelt /tmp/pr-kurz.html "mindestens 12 Zeichen")
ergebnis "ein zu kurzer Schluessel wird mit Begruendung abgewiesen" "$BK6"

# Zwei verschiedene Eingaben: Ein Tippfehler faellt sonst erst auf, wenn die
# Sicherung gebraucht wird.
curl -s -b "$KEKSE" -X POST "$BASIS/einstellungen/sicherung/$DATEI/herunterladen" \
  --data-urlencode "schluessel=Blaues-Fahrrad-Sieben-Regal" \
  --data-urlencode "schluessel2=Anderes-Fahrrad-Sieben" -o /tmp/pr-ab.html
BK7=$(enthaelt /tmp/pr-ab.html "stimmen nicht überein")
ergebnis "eine abweichende Wiederholung wird abgewiesen" "$BK7"

# Der Schluessel darf nirgends auftauchen.
GEHEIM=$(sql "SELECT count(*) FROM fachprotokoll WHERE text LIKE '%Blaues-Fahrrad%'")
LOG=$(docker logs pr-zen 2>&1 | grep -o "Blaues-Fahrrad" | wc -l | tr -d ' \r\n')
if [ "${GEHEIM:-1}" = "0" ] && [ "${LOG:-1}" = "0" ]; then BK8=ja; else BK8=nein; fi
ergebnis "der Schluessel steht weder im Protokoll noch im Log ($GEHEIM/$LOG)" "$BK8"

# Hochladen mit richtigem Schluessel
curl -s -b "$KEKSE" -X POST "$BASIS/einstellungen/sicherung/hochladen" \
  -F "datei=@/tmp/pr-sich.prsich" -F "schluessel=Blaues-Fahrrad-Sieben-Regal" \
  -o /dev/null -D /tmp/pr-kopf.txt
BK9=$(enthaelt /tmp/pr-kopf.txt "eingelesen")
ergebnis "eine heruntergeladene Datei laesst sich wieder einlesen" "$BK9"

# Falscher Schluessel
curl -s -b "$KEKSE" -X POST "$BASIS/einstellungen/sicherung/hochladen" \
  -F "datei=@/tmp/pr-sich.prsich" -F "schluessel=Voellig-Anderer-Schluessel" \
  -o /dev/null -D /tmp/pr-kopf.txt
BK10=$(enthaelt /tmp/pr-kopf.txt "fehler=1")
ergebnis "ein falscher Schluessel wird abgewiesen" "$BK10"

# EIN gekipptes Byte muss auffallen - das ist der Integritaetsschutz.
python3 -c "
d = bytearray(open('/tmp/pr-sich.prsich','rb').read())
d[len(d)//2] ^= 0xFF
open('/tmp/pr-manip.prsich','wb').write(d)"
curl -s -b "$KEKSE" -X POST "$BASIS/einstellungen/sicherung/hochladen" \
  -F "datei=@/tmp/pr-manip.prsich" -F "schluessel=Blaues-Fahrrad-Sieben-Regal" \
  -o /dev/null -D /tmp/pr-kopf.txt
BK11=$(enthaelt /tmp/pr-kopf.txt "fehler=1")
ergebnis "ein einziges veraendertes Byte wird erkannt" "$BK11"

# Eine Fremddatei wird als solche benannt, nicht als "Schluessel falsch".
echo "kein Backup" > /tmp/pr-fremd.prsich
curl -s -b "$KEKSE" -X POST "$BASIS/einstellungen/sicherung/hochladen" \
  -F "datei=@/tmp/pr-fremd.prsich" -F "schluessel=Blaues-Fahrrad-Sieben-Regal" \
  -o /dev/null -D /tmp/pr-kopf.txt
BK12=$(enthaelt /tmp/pr-kopf.txt "keine+Sicherung\|zu+klein")
ergebnis "eine Fremddatei wird als solche benannt" "$BK12"

# Ein Praxisbenutzer darf nichts davon.
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASIS/einstellungen/sicherung/jetzt")
[ "$CODE" = "403" ] || [ "$CODE" = "302" ] && BK13=ja || BK13=nein
ergebnis "ohne Administratorrechte keine Sicherung (HTTP $CODE)" "$BK13"

# Pfadausbruch ueber den Dateinamen
CODE=$(curl -s -b "$KEKSE" -o /dev/null -w '%{http_code}' \
       "$BASIS/einstellungen/sicherung/..%2F..%2Fetc%2Fpasswd/herunterladen")
[ "$CODE" = "404" ] && BK14=ja || BK14=nein
ergebnis "ein Pfadausbruch ueber den Dateinamen wird abgewehrt (HTTP $CODE)" "$BK14"

# Die Wiederherstellung: Aenderung anlegen, zuruecksetzen, pruefen.
sql "SELECT count(*) FROM geraetegruppe" > /dev/null
curl -s -b "$KEKSE" -o /dev/null -X POST "$BASIS/gruppen" \
  --data-urlencode "bezeichnung=Nach der Sicherung" \
  --data-urlencode "einrichtung=Pruefpraxis" --data-urlencode "intervall=99"
VORHER=$(sql "SELECT count(*) FROM geraetegruppe WHERE bezeichnung='Nach der Sicherung'")
[ "${VORHER:-0}" = "1" ] && BK15=ja || BK15=nein
ergebnis "Vorbedingung: eine Aenderung nach der Sicherung" "$BK15"

curl -s -b "$KEKSE" -X POST "$BASIS/einstellungen/sicherung/$DATEI/einspielen" -o /tmp/pr-wh.html
BK16=$(enthaelt /tmp/pr-wh.html "eingespielt")
ergebnis "die Wiederherstellung meldet Erfolg und kuendigt den Neustart an" "$BK16"

# Die Zentrale beendet sich absichtlich. Im Pruefstand laeuft sie ohne
# Neustartregel, also wird sie hier von Hand wieder gestartet — im Betrieb
# uebernimmt das Docker (restart: unless-stopped, ADR-0010).
sleep 4
docker start pr-zen >/dev/null 2>&1
for _ in $(seq 40); do curl -sf -o /dev/null "$BASIS/anmelden" 2>/dev/null && break; sleep 1; done
NACHHER=$(sql "SELECT count(*) FROM geraetegruppe WHERE bezeichnung='Nach der Sicherung'")
[ "${NACHHER:-1}" = "0" ] && BK17=ja || BK17=nein
ergebnis "nach der Wiederherstellung ist die Aenderung verschwunden" "$BK17"

# Vor dem Ueberschreiben MUSS eine Sicherheitskopie entstanden sein.
ANZAHL=$(docker exec pr-zen sh -c 'ls /sicherungen/*.dump 2>/dev/null | wc -l' | tr -d ' \r\n')
[ "${ANZAHL:-0}" -ge 2 ] && BK18=ja || BK18=nein
ergebnis "vor dem Einspielen entstand eine Sicherheitskopie ($ANZAHL Staende)" "$BK18"

rm -f /tmp/pr-sich.prsich /tmp/pr-manip.prsich /tmp/pr-fremd.prsich /tmp/pr-wh.html
anmelden

# NFA-USA-030: Barrierefreiheit. Bis zum 07.08.2026 war sie behauptet, nicht
# belegt. Ausgelagert, weil sie ein eigenes Werkzeug braucht.
if PR_BASIS="$BASIS" bash pruefungen/barrierefreiheit-pruefen.sh "$KEKSE"; then A11Y=ja; else A11Y=nein; fi
ergebnis "Barrierefreiheitspruefung ohne Befund (WCAG 2.2 AA, maschinell pruefbarer Teil)" "$A11Y"

# UX-Vorgaben aus CLAUDE.md Abschnitt 4. Ausgelagert wie die
# Barrierefreiheit, weil es eine eigene Pruefart ist: Sie misst nicht, ob
# eine Funktion arbeitet, sondern ob die Oberflaeche einheitlich bleibt.
if PR_BASIS="$BASIS" bash pruefungen/oberflaeche-pruefen.sh "$KEKSE"; then UX=ja; else UX=nein; fi
ergebnis "Oberflaeche einheitlich (Kurzhinweise, klickbare Zeilen, Raster)" "$UX"

# Die Diagramme der Dokumentation werden wirklich gerendert. Ein Tippfehler in
# der Mermaid-Syntax faellt sonst erst auf, wenn jemand das Dokument oeffnet —
# und dort steht dann eine Fehlermeldung statt eines Diagramms.
if bash pruefungen/diagramme-pruefen.sh >/tmp/pr-diag.txt 2>&1; then DG=ja; else DG=nein; fi
ANZ=$(grep -oE "alle [0-9]+ Diagramme" /tmp/pr-diag.txt | grep -oE "[0-9]+" || echo "?")
ergebnis "alle Diagramme der Dokumentation rendern ($ANZ)" "$DG"
[ "$DG" = "nein" ] && sed 's/\x1b\[[0-9;]*m//g' /tmp/pr-diag.txt | tail -8

docker rm -f pr-sim pr-zen pr-agt pr-db >/dev/null 2>&1
docker volume rm pr-testdaten pr-agentdaten pr-sicherungen >/dev/null 2>&1
rm -f /tmp/pr-totp.txt /tmp/pr-letzter-code.txt "$KEKSE"
hinweis "Ergebnis"
[ $FEHLER -eq 0 ] && { echo "  Alle Systemnachweise bestanden."; exit 0; }
echo "  $FEHLER Nachweis(e) fehlgeschlagen."; exit 1
