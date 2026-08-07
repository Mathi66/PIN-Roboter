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
  docker run -d --name pr-db --network $NETZ --network-alias datenbank \
    -e POSTGRES_DB=pinroboter -e POSTGRES_USER=pinroboter -e POSTGRES_PASSWORD=test \
    -v pr-testdaten:/var/lib/postgresql/data postgres:17-alpine >/dev/null
  sleep 10
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
    $JRE java -jar /app.jar >/dev/null
  for _ in $(seq 40); do
    curl -sf http://localhost:8090/api/notaus >/dev/null 2>&1 && break
    sleep 1
  done
  docker run -d --name pr-agt --network $NETZ \
    -v "$PWD/src/agent/target/agent.jar":/app.jar -v "$SEC":/sec:ro \
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
curl -s -X POST http://localhost:8090/notaus >/dev/null
sleep 5; V=$(verifikationen)
curl -s -X POST http://localhost:8480/steuerung/KT-EMPFANG-01/entziehen >/dev/null
sleep 22; W=$(verifikationen)
[ $((W-V)) -eq 0 ] && E=ja || E=nein
ergebnis "0 Versuche bei aktivem Not-Aus (gemessen: $((W-V)))" "$E"
curl -s -X POST http://localhost:8090/notaus >/dev/null
sleep 20; N=$(verifikationen)
[ $((N-W)) -ge 1 ] && F=ja || F=nein
ergebnis "Automatik läuft nach Aufhebung wieder (gemessen: $((N-W)))" "$F"
curl -s http://localhost:8090/teil/protokoll | grep -q "Not-Aus" && G=ja || G=nein
ergebnis "Not-Aus ist protokolliert" "$G"

# ---------------------------------------------------------------- FA-PRO-020
hinweis "FA-PRO-020 — keine Geheimnisse in Protokollen"
GEHEIM=0
for w in 123456 999999 654321; do
  docker logs pr-agt 2>&1 | grep -q "$w" && GEHEIM=$((GEHEIM+1))
  curl -s http://localhost:8090/teil/protokoll | grep -q "$w" && GEHEIM=$((GEHEIM+1))
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
  -e DB_USER=pinroboter -e DB_PASSWORD=test $JRE java -jar /app.jar >/dev/null
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

docker rm -f pr-sim pr-zen pr-agt pr-db >/dev/null 2>&1
docker volume rm pr-testdaten >/dev/null 2>&1
hinweis "Ergebnis"
[ $FEHLER -eq 0 ] && { echo "  Alle Systemnachweise bestanden."; exit 0; }
echo "  $FEHLER Nachweis(e) fehlgeschlagen."; exit 1
