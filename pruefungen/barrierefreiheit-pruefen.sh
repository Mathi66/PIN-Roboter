#!/usr/bin/env bash
# Barrierefreiheit der Oberflaeche (NFA-USA-030, WCAG 2.2 Level AA).
#
# Bis zum 07.08.2026 war die Anforderung BEHAUPTET, nicht belegt. Dieses Skript
# holt die ausgelieferten Seiten und prueft sie mit axe-core; die Kontraste
# werden rechnerisch geprueft, weil jsdom nicht rendert.
#
# Setzt eine laufende Zentrale unter localhost:8090 und eine Sitzung in
# /tmp/pr-kekse.txt voraus - beides stellt systemnachweise.sh bereit.
#
# Aufruf:  pruefungen/barrierefreiheit-pruefen.sh
set -uo pipefail

BASIS="${PR_BASIS:-$BASIS}"
cd "$(dirname "$0")/.."

KEKSE=${1:-/tmp/pr-kekse.txt}
SEITEN=/tmp/pr-a11y-seiten
FEHLER=0

hinweis() { printf '\n\033[1m%s\033[0m\n' "$*"; }
ergebnis() {
  if [ "$2" = "ja" ]; then printf '  \033[32mBESTANDEN\033[0m  %s\n' "$1"
  else printf '  \033[31mFEHLGESCHLAGEN\033[0m  %s\n' "$1"; FEHLER=$((FEHLER+1)); fi
}

hinweis "NFA-USA-030 — Barrierefreiheit (WCAG 2.2 AA)"

# Abhaengigkeiten nur beim ersten Lauf holen.
if [ ! -d pruefungen/barrierefreiheit/node_modules ]; then
  echo "  Pruefwerkzeug einrichten ..."
  docker run --rm -v "$PWD/pruefungen/barrierefreiheit":/p -v pinroboter-npm:/root/.npm \
    -w /p node:22-alpine npm install --no-audit --no-fund >/dev/null 2>&1
fi
if [ ! -d pruefungen/barrierefreiheit/node_modules ]; then
  printf '  \033[33mUEBERSPRUNGEN\033[0m  Das Pruefwerkzeug ist nicht beschaffbar (kein Netz?).\n'
  echo "    Die Barrierefreiheit ist damit NICHT geprueft."
  exit 0
fi

hinweis "Kontraste (rechnerisch, WCAG 1.4.3 und 1.4.11)"
if docker run --rm -v "$PWD/pruefungen/barrierefreiheit":/p \
     -v "$PWD/src/zentrale/src/main/resources/statisch/stil.css":/stil.css:ro \
     -w /p node:22-alpine node kontraste.mjs /stil.css > /tmp/pr-kontraste.txt 2>&1; then
  ergebnis "alle Farbpaare erreichen den Grenzwert" "ja"
else
  ergebnis "Kontraste unter dem Grenzwert" "nein"
  grep '!!' /tmp/pr-kontraste.txt | sed 's/^/    /'
fi

hinweis "Struktur (axe-core gegen die ausgelieferten Seiten)"
rm -rf "$SEITEN"; mkdir -p "$SEITEN"
curl -s $BASIS/anmelden  > "$SEITEN/anmelden.html"
curl -s $BASIS/einladung > "$SEITEN/einladung.html"
for paar in "uebersicht:/" "geraete:/geraete" "bestand:/bestand" "gruppen:/gruppen" \
            "benutzer:/benutzer" "verwaltung:/verwaltung" "protokoll:/protokoll" \
            "ermittelt:/bestand/ermittelt" "organisation:/organisation"; do
  curl -s -b "$KEKSE" "$BASIS${paar#*:}" > "$SEITEN/${paar%%:*}.html"
done
# Zwei Zustaende, die im Grundaufruf nicht entstehen und deshalb sonst
# ungeprueft blieben: die Terminalmaske mit PIN-Formular (FA-PIN-010) und die
# aufgeklappte Bearbeitungszeile der Organisation (FA-ORG-020).
TID_A=$(curl -s -b "$KEKSE" $BASIS/bestand \
        | grep -oE '/bestand/terminal/[0-9]+' | head -1)
[ -n "$TID_A" ] && curl -s -b "$KEKSE" "$BASIS$TID_A" > "$SEITEN/terminalmaske.html"
# Die Detailseite einer Organisationseinheit und die Anlegemasken sind eigene
# Seiten (UX-Konzept Stufe 4/5) und muessen einzeln geprueft werden.
OID_A=$(curl -s -b "$KEKSE" $BASIS/organisation \
        | grep -oE '/organisation/[0-9]+' | head -1)
[ -n "$OID_A" ] && curl -s -b "$KEKSE" "$BASIS$OID_A" \
        > "$SEITEN/organisation-detail.html"
curl -s -b "$KEKSE" $BASIS/organisation/neu      > "$SEITEN/organisation-neu.html"
curl -s -b "$KEKSE" $BASIS/benachrichtigung      > "$SEITEN/benachrichtigung.html"
# Zeitstrahl und Vorgangsansicht (FA-PRO-050/060) sind eigene Seiten.
KT_A=$(curl -s -b "$KEKSE" $BASIS/geraete | grep -oE '/geraete/[A-Z0-9-]+/verlauf' | head -1)
[ -n "$KT_A" ] && curl -s -b "$KEKSE" "$BASIS$KT_A" > "$SEITEN/zeitstrahl.html"
# Die Vorgangsnummer aus dem Zeitstrahl holen — die Protokollansicht verlinkt
# nicht darauf. Eine leere Datei waere ein Befund ohne Seite dahinter.
VG_A=$(grep -oE '/vorgang/[a-zA-Z0-9-]+' "$SEITEN/zeitstrahl.html" 2>/dev/null | head -1)
if [ -n "$VG_A" ]; then
  curl -s -b "$KEKSE" "$BASIS$VG_A" > "$SEITEN/vorgang.html"
  [ -s "$SEITEN/vorgang.html" ] || rm -f "$SEITEN/vorgang.html"
fi
[ -s "$SEITEN/zeitstrahl.html" ] || rm -f "$SEITEN/zeitstrahl.html"
curl -s -b "$KEKSE" $BASIS/bestand/konnektor/neu > "$SEITEN/konnektor-neu.html"
curl -s -b "$KEKSE" $BASIS/bestand/terminal/neu  > "$SEITEN/terminal-neu.html"

ANZ=$(ls "$SEITEN" | wc -l | tr -d ' ')

if docker run --rm -v "$PWD/pruefungen/barrierefreiheit":/p -v "$SEITEN":/seiten:ro \
     -w /p node:22-alpine node pruefen.mjs /seiten > /tmp/pr-a11y.txt 2>&1; then
  ergebnis "$ANZ Seiten ohne Befund" "ja"
else
  ergebnis "Befunde in der Oberflaeche" "nein"
  grep -E '^\s+\[' /tmp/pr-a11y.txt | sed 's/^/  /' | head -12
fi

hinweis "Was diese Pruefung NICHT abdeckt"
echo "  - Tastaturbedienung im Ablauf (Reihenfolge, Fallen) - braucht einen Browser"
echo "  - Screenreader-Verstaendlichkeit - braucht einen Menschen"
echo "  - Zustaende nach einer Nutzerhandlung"
echo "  Ein Werkzeug findet nur einen Teil der Barrieren, diesen aber zuverlaessig."

rm -rf "$SEITEN"
[ $FEHLER -eq 0 ] && exit 0 || exit 1
