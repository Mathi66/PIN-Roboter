#!/usr/bin/env bash
# Prüft den Stack AUS DEM REPOSITORY — also genau das, was beim Kunden läuft.
#
# Warum dieses Skript existiert: Die Systemnachweise starten die Container
# einzeln mit "docker run". Das ist schnell und gut isoliert, prüft aber die
# Auslieferung NICHT. Am 07.08.2026 fiel auf, dass
#
#   - die docker-compose.yml über zwei Commits hinweg gar kein gültiges YAML war
#     (der Datenbank-Block war in das depends_on des Agenten gerutscht), und
#   - das Datenverzeichnis des Agenten root gehörte, der Container aber als
#     Nicht-Root läuft — Token und privater Schlüssel waren nicht speicherbar.
#
# Beides hätte kein Modultest und kein Systemnachweis gefunden. Die Definition
# of Done verlangt "Läuft im Docker-Stack aus dem Repository — verifiziert,
# nicht angenommen". Dieses Skript ist diese Verifikation.
#
# Aufruf:  pruefungen/stack-pruefen.sh
set -uo pipefail
cd "$(dirname "$0")/.."

FEHLER=0
hinweis() { printf '\n\033[1m%s\033[0m\n' "$*"; }
ergebnis() {
  if [ "$2" = "ja" ]; then printf '  \033[32mBESTANDEN\033[0m  %s\n' "$1"
  else printf '  \033[31mFEHLGESCHLAGEN\033[0m  %s\n' "$1"; FEHLER=$((FEHLER+1)); fi
}

hinweis "Auslieferung — docker-compose.yml aus dem Repository"

# 1. Ist die Datei überhaupt gültiges YAML mit den erwarteten Diensten?
DIENSTE=$(docker compose config --services 2>/dev/null | sort | tr '\n' ' ')
[ "$DIENSTE" = "agent datenbank simulator zentrale " ] && S=ja || S=nein
ergebnis "die Compose-Datei ist gültig und definiert alle vier Dienste ($DIENSTE)" "$S"
if [ "$S" = "nein" ]; then
  docker compose config 2>&1 | head -5 | sed 's/^/    /'
  echo "  Ohne gültige Compose-Datei sind die weiteren Prüfungen sinnlos."
  exit 1
fi

# 2. Jeder Dienst, der Zustand hält, braucht ein Volume. Im Container-Layer
#    wären Token, Schlüssel und Datenbank nach jedem Neustart weg (NFA-BET-020).
for paar in "agent:/daten" "datenbank:/var/lib/postgresql/data"; do
  D=${paar%%:*}; P=${paar#*:}
  # JSON statt YAML: PyYAML ist nicht ueberall vorhanden, json ist eingebaut.
  docker compose config --format json 2>/dev/null \
    | python3 -c "
import sys, json
c = json.load(sys.stdin)
v = c['services']['$D'].get('volumes') or []
ziele = [x['target'] if isinstance(x, dict) else str(x).split(':')[1] for x in v]
sys.exit(0 if '$P' in ziele else 1)" && V=ja || V=nein
  ergebnis "Dienst „$D“ hat ein Volume auf $P" "$V"
done

# 3. Keine Geheimnisse mit festem Wert in der Datei (NFA-BET-010).
HART=$(grep -nE '^\s+(GEHEIMNIS_SCHLUESSEL|PIN_ABLAGE_SCHLUESSEL|DB_PASSWORD):\s*"?[A-Za-z0-9+/=]{16,}' \
       docker-compose.yml | grep -v '\${' | wc -l | tr -d ' ')
[ "$HART" = "0" ] && G=ja || G=nein
ergebnis "keine fest eingetragenen Geheimnisse in der Compose-Datei ($HART)" "$G"

hinweis "Rechte im Laufzeit-Image"

# 4. Das Datenverzeichnis des Agenten muss dem Container-Benutzer gehören.
#    Ein frisches Volume erbt Eigentümer und Rechte des Mountpunkts aus dem
#    Image — gehört er root, kann der Nicht-Root-Container nichts ablegen.
if docker compose build agent >/dev/null 2>&1; then
  BILD=$(docker compose config --format json 2>/dev/null \
         | python3 -c "import sys,json;print(json.load(sys.stdin)['services']['agent'].get('image','kt-pin-roboter-agent'))")
  BESITZER=$(docker run --rm --entrypoint sh "$BILD" -c 'stat -c "%u:%g" /daten' 2>/dev/null)
  [ "$BESITZER" = "10001:10001" ] && R=ja || R=nein
  ergebnis "/daten gehört dem Container-Benutzer 10001 (ist: ${BESITZER:-nicht vorhanden})" "$R"

  # 5. Und der Container läuft nicht als root (NFA-SIC-030).
  NUTZER=$(docker run --rm --entrypoint sh "$BILD" -c 'id -u' 2>/dev/null)
  [ "$NUTZER" = "10001" ] && N=ja || N=nein
  ergebnis "der Agent läuft als Nicht-Root (UID ${NUTZER:-unbekannt})" "$N"
else
  ergebnis "das Agent-Image lässt sich bauen" "nein"
fi

hinweis "Ergebnis"
[ $FEHLER -eq 0 ] && { echo "  Der Stack aus dem Repository ist auslieferbar."; exit 0; }
echo "  $FEHLER Prüfung(en) fehlgeschlagen."; exit 1
