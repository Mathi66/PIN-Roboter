#!/usr/bin/env bash
# ══════════════════════════════════════════════════════════════════════════
#  Durchstich der Remote SMCB API (ADR-0011)
#
#  Prüft das Muster, das die Herstellerspezifikation vorgibt und das dieses
#  Produkt bis zum 10.08.2026 FALSCH hatte:
#
#      Das Terminal FRAGT die PIN an. Der Agent ANTWORTET.
#
#  Bis dahin schob der Agent die PIN hin. Der Simulator bildete diesen Irrtum
#  nach — alle Nachweise waren grün und sagten über echte Hardware nichts.
#  Daraus folgt die Bauart dieses Nachweises: Er prüft nicht, DASS die Karte
#  freigeschaltet wird, sondern ÜBER WELCHEN WEG. Dafür zählt der Simulator
#  die über „cobra-smcb" gelieferten PINs mit.
#
#  Geprüft wird der ausgelieferte Agent, kein Ersatzprogramm.
#
#  Grundlage: ST-1506 Remote Management Specification 2.8, Kapitel 6–8.
#
#  Aufruf:  bash pruefungen/smcb-durchstich.sh
# ══════════════════════════════════════════════════════════════════════════
set -uo pipefail

NETZ=pr-smcb-netz
SIM=pr-smcb-sim
AGT=pr-smcb-agt
JRE=eclipse-temurin:21-jre
JOPT="-Djava.security.properties=/sec/brainpool.security"
SEC="$PWD/docker"
STEUERUNG=http://localhost:8481
FEHLER=0

# Achtung: NIE "docker logs … | grep -q" mit set -o pipefail. grep beendet
# nach dem ersten Treffer, docker logs bekommt SIGPIPE, und die Pipeline
# gilt als fehlgeschlagen — abhaengig davon, wie lang die Ausgabe gerade
# ist. Dieselbe Falle hat schon einmal 25 Nachweise erwischt. Deshalb
# ueberall "grep -c" und ein Vergleich.
gruen=$'\e[32m'; rot=$'\e[31m'; fett=$'\e[1m'; aus=$'\e[0m'
hinweis() { printf '\n%s%s%s\n' "$fett" "$1" "$aus"; }
ergebnis() {
  if [ "$2" = "ja" ]; then printf '  %sBESTANDEN%s  %s\n' "$gruen" "$aus" "$1"
  else printf '  %sFEHLGESCHLAGEN%s  %s\n' "$rot" "$aus" "$1"; FEHLER=$((FEHLER+1)); fi
}

aufraeumen() {
  docker rm -f "$SIM" "$AGT" >/dev/null 2>&1
  docker network rm "$NETZ" >/dev/null 2>&1
}
trap aufraeumen EXIT
aufraeumen

# uebergaben <terminalId> — wie oft kam eine PIN über cobra-smcb an
uebergaben() {
  curl -s "$STEUERUNG/steuerung/$1/smcb-uebergaben" \
    | grep -oE '"uebergaben":[0-9]+' | cut -d: -f2
}
verbunden() {
  curl -s "$STEUERUNG/steuerung/$1/smcb-uebergaben" \
    | grep -oE '"angemeldet":(true|false)' | cut -d: -f2
}

starten() {   # starten <pin> [zusatz-umgebung...]
  docker rm -f "$AGT" >/dev/null 2>&1
  docker run -d --name "$AGT" --network "$NETZ" \
    -v "$PWD/src/agent/target/agent.jar":/app.jar -v "$SEC":/sec:ro \
    -e KONNEKTOR_URL=https://simulator:8443/ -e TERMINAL_URL=https://simulator:8444/ \
    -e TERMINAL_SMCB_URL=https://simulator:8445/ \
    -e CA_URL=http://simulator:8480/ca.pem \
    -e PRUEFINTERVALL_SEKUNDEN=5 -e PIN_card_smcb_001="$1" \
    "${@:2}" \
    $JRE java $JOPT -jar /app.jar >/dev/null
  for _ in $(seq 60); do
    [ "$(docker logs "$AGT" 2>&1 | grep -cE "^  \[.*\] (freigeschaltet|Die hinterlegte PIN|Das Kartenterminal)")" != "0" ] && break
    sleep 1
  done
  sleep 2
}

hinweis "Remote SMCB API — das Terminal fragt, der Agent antwortet"

docker network create "$NETZ" >/dev/null 2>&1
docker run -d --name "$SIM" --network "$NETZ" --network-alias simulator \
  -v "$PWD/src/simulator/target/simulator.jar":/app.jar -v "$SEC":/sec:ro \
  -p 8481:8480 $JRE java $JOPT -jar /app.jar >/dev/null

for _ in $(seq 40); do
  curl -sf -o /dev/null "$STEUERUNG/ca.pem" && break
  sleep 1
done
[ "$(docker logs "$SIM" 2>&1 | grep -c "Remote SMCB API")" != "0" ] && S1=ja || S1=nein
ergebnis "der Simulator bietet die Remote SMCB API an" "$S1"

# ── Der Kernfall ────────────────────────────────────────────────────────
starten 123456
[ "$(docker logs "$AGT" 2>&1 | grep -c "Verbindung zur Remote SMCB API steht")" != "0" ] && V=ja || V=nein
ergebnis "der Agent meldet sich über Challenge-Response an" "$V"

[ "$(verbunden KT-EMPFANG-01)" = "true" ] && A=ja || A=nein
ergebnis "das Terminal sieht eine angemeldete Verbindung" "$A"

U=$(uebergaben KT-EMPFANG-01)
[ "${U:-0}" -ge 1 ] && P=ja || P=nein
ergebnis "die PIN kam über cobra-smcb, nicht über den alten Weg (${U:-0}x)" "$P"

[ "$(docker logs "$AGT" 2>&1 | grep -cE "^  \[.*\] freigeschaltet")" != "0" ] && F=ja || F=nein
ergebnis "die SMC-B ist dadurch freigeschaltet" "$F"

# ── Nur EIN Gerät, obwohl zwei PEviM aktiv haben ────────────────────────
# KT-LABOR-01 hat PEviM aktiv, aber keine hinterlegte PIN. Eine Verbindung
# dorthin wäre nutzlos und sperrte den einzigen Verbindungsplatz.
[ "$(verbunden KT-LABOR-01)" = "false" ] && L=ja || L=nein
ergebnis "kein Verbindungsplatz belegt, wo keine PIN hinterlegt ist" "$L"

# ── Ohne Remote SMCB API keine Verifikation ─────────────────────────────
hinweis "Was ausdruecklich NICHT gehen darf"
VOR=$(uebergaben KT-EMPFANG-01)
curl -s -X POST "$STEUERUNG/steuerung/KT-EMPFANG-01/pevim/false" >/dev/null
docker rm -f "$AGT" >/dev/null 2>&1
curl -s -X POST "$STEUERUNG/steuerung/KT-EMPFANG-01/entziehen" >/dev/null
starten 123456
sleep 12
NACH=$(uebergaben KT-EMPFANG-01)
[ "$((NACH-VOR))" -eq 0 ] && Q=ja || Q=nein
ergebnis "bei abgeschaltetem PEviM keine PIN-Uebergabe ($((NACH-VOR)))" "$Q"
curl -s -X POST "$STEUERUNG/steuerung/KT-EMPFANG-01/pevim/true" >/dev/null

# ── Ohne hinterlegte PIN wird abgesagt, nicht geschwiegen ───────────────
docker rm -f "$AGT" >/dev/null 2>&1
docker run -d --name "$AGT" --network "$NETZ" \
  -v "$PWD/src/agent/target/agent.jar":/app.jar -v "$SEC":/sec:ro \
  -e KONNEKTOR_URL=https://simulator:8443/ -e TERMINAL_URL=https://simulator:8444/ \
  -e TERMINAL_SMCB_URL=https://simulator:8445/ \
  -e CA_URL=http://simulator:8480/ca.pem -e PRUEFINTERVALL_SEKUNDEN=5 \
  $JRE java $JOPT -jar /app.jar >/dev/null
sleep 15
# Ohne PIN darf der Agent gar nicht erst verbinden — das ist der Sollzustand.
[ "$(verbunden KT-EMPFANG-01)" = "false" ] && O=ja || O=nein
ergebnis "ohne hinterlegte PIN wird gar nicht erst verbunden" "$O"

# ── Die PIN darf nirgends auftauchen ────────────────────────────────────
hinweis "FA-PRO-020 — keine Geheimnisse in Protokollen"
G=0
[ "$(docker logs "$SIM" 2>&1 | grep -c "123456")" != "0" ] && G=$((G+1))
[ "$(docker logs "$AGT" 2>&1 | grep -c "123456")" != "0" ] && G=$((G+1))
[ "$G" = "0" ] && GG=ja || GG=nein
ergebnis "die PIN steht weder im Terminal- noch im Agentprotokoll ($G)" "$GG"

hinweis "Was dieser Nachweis NICHT zeigt"
cat <<'ENDE'
  Er prueft das MUSTER gegen eine Nachbildung, die nach der Spezifikation
  gebaut ist — nicht gegen ein echtes ST-1506. Offen bleiben Zeitverhalten,
  Verbindungsdauer, Fehlerbilder und die Frage, ob das Geraet die Verbindung
  ueber Stunden haelt. Das entscheidet erst die Messung am Testaufbau.
ENDE

exit $FEHLER
