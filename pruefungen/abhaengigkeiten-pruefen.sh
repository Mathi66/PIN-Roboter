#!/usr/bin/env bash
# Abhaengigkeits-Scan (CLAUDE.md 3.2: "regelmaessiger Abhaengigkeits-Scan").
#
# Prueft die gepinnten Abhaengigkeiten gegen die Schwachstellendatenbank der
# OSS Index (Sonatype). Kein Konto noetig, keine Anmeldung, keine Daten
# verlassen das Haus ausser den Paketnamen.
#
# Warum nicht OWASP Dependency-Check: Der laedt beim ersten Lauf mehrere
# hundert Megabyte NVD-Daten und braucht seit 2023 einen API-Schluessel. Das
# waere eine Huerde bei jedem Bauvorgang und ein Geheimnis mehr im Repository.
#
# Aufruf:  pruefungen/abhaengigkeiten-pruefen.sh
#          pruefungen/abhaengigkeiten-pruefen.sh --offline   (nur auflisten)
set -uo pipefail
cd "$(dirname "$0")/.."

hinweis() { printf '\n\033[1m%s\033[0m\n' "$*"; }
FEHLER=0

hinweis "Abhaengigkeiten des PIN-Roboters"
# Ueber ALLE Module aggregieren.
#
# Zwei Fallen, beide beim ersten Anlauf hineingetappt:
#
# 1. Ein Lauf im Wurzelmodul listet nur die Module selbst.
# 2. dependency:list je Modul scheitert, solange die eigenen Module nicht im
#    lokalen Repository liegen - "Could not find artifact de.pinroboter:
#    gemeinsam". Maven bricht dann fuer dieses Modul ab, und die Ausgabe
#    enthaelt nur die Abhaengigkeiten der Module, die vorher durchliefen.
#    Ergebnis waren 4 statt gut 20 Eintraege.
#
# Deshalb: erst installieren, dann listen.
echo "  Module ins lokale Repository legen ..."
docker run --rm -v "$PWD":/p -v pinroboter-m2:/root/.m2 -w /p \
  maven:3.9-eclipse-temurin-21 mvn -B -q install -DskipTests 2>&1 | grep -E "^\[ERROR\]" | head -3

: > /tmp/pr-deps.txt
for modul in gemeinsam zentrale agent simulator; do
  docker run --rm -v "$PWD":/p -v pinroboter-m2:/root/.m2 -w /p \
    maven:3.9-eclipse-temurin-21 mvn -B -pl "src/$modul" dependency:list \
    -DincludeScope=runtime 2>/dev/null \
    | grep -oE '[a-z0-9._\-]+:[a-z0-9._\-]+:jar:[0-9][^: ]*' >> /tmp/pr-deps.txt
done
sort -u -o /tmp/pr-deps.txt /tmp/pr-deps.txt
grep -v '^de\.pinroboter:' /tmp/pr-deps.txt > /tmp/pr-deps2.txt && mv /tmp/pr-deps2.txt /tmp/pr-deps.txt

ANZAHL=$(wc -l < /tmp/pr-deps.txt | tr -d ' ')
echo "  $ANZAHL Abhaengigkeiten zur Laufzeit:"
sed 's/^/    /' /tmp/pr-deps.txt

# Vollstaendigkeit pruefen, BEVOR etwas als bestanden gemeldet wird.
#
# Beim ersten Anlauf meldete der Scan "bestanden" - und hatte 4 von rund 20
# Abhaengigkeiten geprueft. Javalin und der PostgreSQL-Treiber fehlten, weil
# dependency:list sie in dieser Aufrufform nicht ausgibt. Ein Scan, der die
# Haelfte uebersieht und Erfolg meldet, ist schlimmer als keiner: Er erzeugt
# eine Sicherheit, die es nicht gibt.
hinweis "Ist die Erfassung vollstaendig?"
ERWARTET="io.javalin org.postgresql org.bouncycastle"
FEHLEND=""
for e in $ERWARTET; do
  grep -q "^$e:" /tmp/pr-deps.txt || FEHLEND="$FEHLEND $e"
done
if [ -z "$FEHLEND" ]; then
  printf '  \033[32mBESTANDEN\033[0m  alle erwarteten Kernbibliotheken sind erfasst\n'
else
  printf '  \033[31mFEHLGESCHLAGEN\033[0m  nicht erfasst:%s\n' "$FEHLEND"
  echo "    Der Scan ist damit UNVOLLSTAENDIG. Sein Ergebnis sagt nichts aus,"
  echo "    solange diese Bibliotheken fehlen."
  FEHLER=$((FEHLER+1))
fi

# Alle Versionen muessen fest sein - eine Bereichsangabe macht den Bau
# unreproduzierbar (CLAUDE.md 5, "Abhaengigkeiten sind gepinnt").
hinweis "Sind alle Versionen fest?"
OFFEN=$(grep -cE ':(LATEST|RELEASE|\[|\()' /tmp/pr-deps.txt || true)
if [ "${OFFEN:-0}" = "0" ]; then
  printf '  \033[32mBESTANDEN\033[0m  alle %s Versionen sind fest angegeben\n' "$ANZAHL"
else
  printf '  \033[31mFEHLGESCHLAGEN\033[0m  %s Abhaengigkeiten ohne feste Version\n' "$OFFEN"
  FEHLER=$((FEHLER+1))
fi

if [ "${1:-}" = "--offline" ]; then
  hinweis "Ergebnis"
  echo "  Nur aufgelistet (--offline). Kein Schwachstellenabgleich."
  exit $FEHLER
fi

hinweis "Abgleich mit der Schwachstellendatenbank (OSS Index)"
# Die Koordinaten in das von OSS Index erwartete Format bringen.
python3 - <<'PY' > /tmp/pr-purls.json
import json, pathlib
zeilen = pathlib.Path("/tmp/pr-deps.txt").read_text().split()
purls = []
for z in zeilen:
    t = z.split(":")
    if len(t) >= 4:
        purls.append("pkg:maven/%s/%s@%s" % (t[0], t[1], t[3]))
print(json.dumps({"coordinates": sorted(set(purls))}))
PY

ANTWORT=$(curl -s -m 60 -X POST https://ossindex.sonatype.org/api/v3/component-report \
          -H "Content-Type: application/json" -d @/tmp/pr-purls.json || echo "")
if [ -z "$ANTWORT" ] || ! echo "$ANTWORT" | head -c 1 | grep -q '\['; then
  printf '  \033[33mUEBERSPRUNGEN\033[0m  Die Schwachstellendatenbank ist nicht erreichbar.\n'
  echo "    Der Bauvorgang laeuft weiter - ein Netzausfall darf ihn nicht anhalten."
  echo "    Der Abgleich ist dann aber NICHT erfolgt und muss nachgeholt werden."
  exit $FEHLER
fi

TREFFER=$(echo "$ANTWORT" | python3 -c '
import json, sys
daten = json.load(sys.stdin)
gefunden = 0
for k in daten:
    for v in k.get("vulnerabilities", []):
        gefunden += 1
        print("    %s  %s (CVSS %s)" % (k["coordinates"], v.get("title","?"), v.get("cvssScore","?")))
        if v.get("reference"): print("      %s" % v["reference"])
print("ANZAHL=%d" % gefunden)
' | tee /tmp/pr-cve.txt | grep -oE 'ANZAHL=[0-9]+' | cut -d= -f2)

grep -v '^ANZAHL=' /tmp/pr-cve.txt
if [ "${TREFFER:-0}" = "0" ]; then
  printf '  \033[32mBESTANDEN\033[0m  keine bekannten Schwachstellen in %s Abhaengigkeiten\n' "$ANZAHL"
else
  printf '  \033[31mFEHLGESCHLAGEN\033[0m  %s bekannte Schwachstelle(n)\n' "$TREFFER"
  echo "    Jede einzelne bewerten: betrifft sie einen Weg, den wir nutzen?"
  echo "    Wenn ja: Version anheben. Wenn nein: im Architektur-Log begruenden."
  FEHLER=$((FEHLER+1))
fi

hinweis "Ergebnis"
[ $FEHLER -eq 0 ] && { echo "  Abhaengigkeiten in Ordnung."; exit 0; }
echo "  $FEHLER Punkt(e) zu klaeren."; exit 1
