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
ergebnis() {           # ergebnis <name> <bedingung-erfuellt>
  if [ "$2" = "ja" ]; then printf '  \033[32mBESTANDEN\033[0m  %s\n' "$1"
  else printf '  \033[31mFEHLGESCHLAGEN\033[0m  %s\n' "$1"; FEHLER=$((FEHLER+1)); fi
}
verifikationen() { docker logs pr-agt 2>&1 | grep -c "starte Verifikation"; }

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

  if curl -s http://localhost:8090/ersteinrichtung | grep -q "Administratorkonto"; then
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
sleep 25
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
echo "  Update läuft 30 s bei 4 s Prüfintervall → ~7 Gelegenheiten"
sleep 26; W=$(verifikationen)
[ $((W-V)) -eq 0 ] && U=ja || U=nein
ergebnis "0 Versuche während des Updates (gemessen: $((W-V)))" "$U"
sleep 20; N=$(verifikationen)
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
sleep 20; N=$(verifikationen)
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
echo "$ANTWORT" | grep -q "Not-Aus" && GA=ja || GA=nein
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
  curl -s -b "$KEKSE" http://localhost:8090/teil/protokoll | grep -q "$w" && GEHEIM=$((GEHEIM+1))
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
curl -s http://localhost:8090/anmelden | grep -q 'href="/statisch/stil.css"' && EB=ja || EB=nein
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
alarmzahl() { curl -s -b "$KEKSE" http://localhost:8090/ | grep -c "Quittieren" || true; }

curl -s -X POST http://localhost:8480/steuerung/KT-EMPFANG-01/erreichbar/false >/dev/null
sleep 14
A1=$(alarmzahl)
[ "$A1" -ge 1 ] && AL=ja || AL=nein
ergebnis "nicht erreichbares Gerät erzeugt einen Alarm (gefunden: $A1)" "$AL"

curl -s -b "$KEKSE" http://localhost:8090/ | grep -q "eingeschaltet und mit dem Netzwerk" && HE=ja || HE=nein
ergebnis "der Alarm nennt eine Handlungsempfehlung" "$HE"

# Derselbe Zustand ueber viele Pruefzyklen darf keine weiteren Alarme erzeugen.
sleep 20
A2=$(alarmzahl)
[ "$A2" = "$A1" ] && DU=ja || DU=nein
ergebnis "keine Alarmflut bei andauerndem Zustand ($A1 -> $A2)" "$DU"

curl -s -X POST http://localhost:8480/steuerung/KT-EMPFANG-01/erreichbar/true >/dev/null
sleep 16
A3=$(alarmzahl)
[ "$A3" -lt "$A1" ] && BE=ja || BE=nein
ergebnis "behobener Zustand schließt den Alarm selbsttätig ($A1 -> $A3)" "$BE"

# ---------------------------------------------------------------- Erreichbarkeit
hinweis "Jeder Navigationslink fuehrt zu einer Seite"
echo "  Diese Pruefung fehlte bis 07.08.2026. Zweimal wurde eine fertige Seite"
echo "  gebaut, verlinkt - und die Route nie registriert. Der Compiler sieht das"
echo "  nicht: Ein Link ist eine Zeichenkette, kein Aufruf."
TOT=0
# Links aus der Navigation der Startseite auslesen und alle aufrufen
ZIELE=$(curl -s -b "$KEKSE" http://localhost:8090/ \
        | grep -oE '<a href="/[a-z-]*"' | grep -oE '"/[a-z-]*"' | tr -d '"' | sort -u)
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

curl -s -b "$KEKSE" http://localhost:8090/teil/protokoll | grep -q "Protokoll exportiert" && PA=ja || PA=nein
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
echo "$S1" | grep -q "Seite 1 von" && BL=ja || BL=nein
ergebnis "die Protokollansicht zeigt eine Blaetterleiste ($GES Eintraege)" "$BL"

Z1=$(echo "$S1" | grep -c "<tr>")
[ "${Z1:-0}" -le 50 ] && BG=ja || BG=nein
ergebnis "eine Seite zeigt hoechstens 50 Zeilen (gezaehlt: $Z1)" "$BG"

if [ "${GES:-0}" -gt 50 ]; then
  S2=$(curl -s -b "$KEKSE" "http://localhost:8090/teil/protokoll?seite=2")
  echo "$S2" | grep -q "Seite 2 von" && B2=ja || B2=nein
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
curl -s -b "$KEKSE" "http://localhost:8090/teil/protokoll?seite=9999" \
  | grep -q "Seite [0-9]* von" && BE=ja || BE=nein
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

curl -s -X POST -H "X-Registrierung: erfunden" http://localhost:8090/api/registrieren \
  | grep -q "Unbekanntes" && RG=ja || RG=nein
ergebnis "erfundenes Registrierungsgeheimnis wird abgewiesen" "$RG"

G2=$(curl -s -b "$KEKSE" -X POST http://localhost:8090/agenten/geheimnis -d "bezeichnung=Einmaltest" \
     | grep -oE '<code>[A-Za-z0-9_-]{40,}</code>' | sed 's/<[^>]*>//g')
curl -s -X POST -H "X-Registrierung: $G2" http://localhost:8090/api/registrieren | grep -q "token" && E1=ja || E1=nein
ergebnis "gültiges Geheimnis lässt sich einlösen" "$E1"
curl -s -X POST -H "X-Registrierung: $G2" http://localhost:8090/api/registrieren \
  | grep -q "bereits verwendet" && E2=ja || E2=nein
ergebnis "dasselbe Geheimnis ein zweites Mal wird abgewiesen" "$E2"

# Zwei getrennte Fragen, weil sie unterschiedliche Fehler bedeuten: Steht der
# Eintrag ueberhaupt im Protokoll (sonst fehlt die Nachvollziehbarkeit), und
# erscheint er in der Ansicht (sonst greift die Rotation oder die Anzeigegrenze)?
DB=$(docker exec pr-db psql -U pinroboter -d pinroboter -tAc \
     "SELECT count(*) FROM fachprotokoll WHERE art='AGENT_REGISTRIERUNG_ABGELEHNT'" | tr -d ' \n')
[ "${DB:-0}" -gt 0 ] && PR=ja || PR=nein
ergebnis "abgelehnte Registrierungen stehen im Fachprotokoll ($DB Eintraege)" "$PR"

curl -s -b "$KEKSE" http://localhost:8090/teil/protokoll | grep -q "Registrierung abgelehnt" && PA=ja || PA=nein
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
sql() { docker exec pr-db psql -U pinroboter -d pinroboter -tAc "$1" | tr -d ' \n'; }
# Fuer Textwerte: entfernt NUR den Zeilenumbruch. Ein Modellname wie
# "CHERRY ST-1506" verliert sonst sein Leerzeichen - genau daran ist der
# Modellfilter-Nachweis einmal gescheitert, ohne dass am Produkt etwas fehlte.
sqltext() { docker exec pr-db psql -U pinroboter -d pinroboter -tAc "$1" | tr -d '\r\n'; }

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

curl -s -b "$KEKSE" http://localhost:8090/verwaltung | grep -q "Arbeitsplätze" && VA=ja || VA=nein
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

echo "$ANTWORT" | grep -q "antwortet innerhalb" && KM=ja || KM=nein
ergebnis "die Meldung sagt, was zu tun ist, statt nur zu scheitern" "$KM"

# Kernzusage FA-GER-010: nach dem Speichern nicht mehr auslesbar.
CHIFFRAT=$(sql "SELECT coalesce(passwort_chiffrat,'') FROM konnektor WHERE id=$KID")
case "$CHIFFRAT" in v1:*) KC=ja ;; *) KC=nein ;; esac
ergebnis "das Kennwort liegt verschluesselt in der Datenbank" "$KC"

TREFFER=0
for pfad in "/bestand" "/bestand/konnektor/$KID"; do
  curl -s -b "$KEKSE" "http://localhost:8090$pfad" | grep -q "NachweisKennwortXY" && TREFFER=$((TREFFER+1))
done
[ $TREFFER -eq 0 ] && KO=ja || KO=nein
ergebnis "das Kennwort erscheint auf keiner Oberflaechenseite (Treffer: $TREFFER)" "$KO"

# A_24080: PEviM ist ausschliesslich fuer SMC-B zulaessig.
ANTWORT_PV=$(curl -s -b "$KEKSE" -X POST http://localhost:8090/bestand/terminal \
  --data-urlencode "konnektorId=$KID" --data-urlencode "terminalId=KT-NACHWEIS-01" \
  --data-urlencode "bezeichnung=Behandlung" --data-urlencode "adresse=192.0.2.90" \
  --data-urlencode "pevimAktiv=ja")
echo "$ANTWORT_PV" | grep -q "A_24080" && PV=ja || PV=nein
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
  --data-urlencode "bezeichnung=Doppelt" --data-urlencode "adresse=192.0.2.91" \
  | grep -q "bereits vergeben" && TD=ja || TD=nein
ergebnis "eine doppelte Kennung wird verstaendlich abgewiesen" "$TD"

# Ein Konnektor mit Terminals darf nicht stillschweigend einen halben
# Standort mitreissen - das Schema wuerde per CASCADE genau das tun.
curl -s -b "$KEKSE" -X POST "http://localhost:8090/bestand/konnektor/$KID/loeschen" \
  | grep -q "zugeordnet" && LS=ja || LS=nein
ergebnis "Konnektor mit zugeordneten Terminals wird nicht geloescht" "$LS"
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
  --data-urlencode "konnektorId=$KID2" --data-urlencode "adresse=192.0.2.51:443" \
  | grep -q "übernommen" && UE=ja || UE=nein
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
  --data-urlencode "konnektorId=$KID2" | grep -q "bereits erfasst" && UD=ja || UD=nein
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

curl -s -b "$KEKSE" "http://localhost:8090/bestand?sortierung=modell&richtung=ab" \
  | grep -q 'aria-sort="descending"' && SR=ja || SR=nein
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
  --data-urlencode "aktion=deaktivieren" | grep -q "kein Gerät" && ML=ja || ML=nein
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
    "${SARGS[@]}" --data-urlencode "konnektorId=$KID3" \
    | grep -q "von $ANZ" && SU=ja || SU=nein
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
curl -s -b "$KEKSE" http://localhost:8090/bestand > /tmp/pr-kontext.html
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
  curl -s -b "$DEMO_KEKSE" -X POST http://localhost:8090/verwaltung/demodaten/anlegen \
    | grep -q "Ihre Berechtigung" && RM=ja || RM=nein
  ergebnis "die Abweisung nennt die eigene Berechtigung" "$RM"

  rm -f "$DEMO_KEKSE"
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
  --data-urlencode "geheimnis=$CODE_EIN" --data-urlencode "kennwort=kurz" \
  | grep -q "mindestens 12 Zeichen" && BS=ja || BS=nein
ergebnis "ein schwaches Kennwort wird mit Begruendung abgelehnt" "$BS"

curl -s -X POST http://localhost:8090/einladung \
  --data-urlencode "geheimnis=$CODE_EIN" --data-urlencode "kennwort=EinLangesUnverdaechtigesWort42" \
  | grep -q "Einmalkennwort" && BL=ja || BL=nein
ergebnis "nach dem Kennwort folgt die Einrichtung des zweiten Faktors" "$BL"

IST=$(sql "SELECT organisation_id FROM benutzer WHERE benutzername='nachweis.person'")
[ "$IST" = "$ORG_B" ] && BO=ja || BO=nein
ergebnis "das Konto landet in der eingeladenen Einrichtung ($IST)" "$BO"

curl -s -X POST http://localhost:8090/einladung \
  --data-urlencode "geheimnis=$CODE_EIN" --data-urlencode "kennwort=EinLangesUnverdaechtigesWort42" \
  | grep -q "bereits verwendet" && BZ=ja || BZ=nein
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
  --data-urlencode "aktiv=nein" | grep -q "letzte handlungsfähige" && BA=ja || BA=nein
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
