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

BASIS="${PR_BASIS:-http://localhost:8090}"
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
#
# Die Kennungen kommen aus der DATENBANK, nicht aus der Geraeteseite: Je
# nachdem, an welcher Stelle des Prüflaufs diese Prüfung startet, ist die Liste
# gerade leer — und dann blieben zwei der vier Nachvollziehbarkeitssichten
# ungeprüft, ohne dass es jemandem auffiel. Genau das ist am 12.08.2026
# passiert, sichtbar erst durch die Meldung „NICHT GEPRUEFT" weiter unten.
psql_() { docker exec pr-db psql -U pinroboter -d pinroboter -tAc "$1" 2>/dev/null | tr -d ' \n'; }

KT_A=$(psql_ "SELECT terminal_id FROM kartenterminal ORDER BY id LIMIT 1")
[ -z "$KT_A" ] && KT_A=$(psql_ "SELECT terminal_id FROM geraet ORDER BY id LIMIT 1")
if [ -n "$KT_A" ]; then
  curl -s -b "$KEKSE" "$BASIS/geraete/$KT_A/verlauf" > "$SEITEN/zeitstrahl.html"
fi

# Eine Vorgangsnummer aus dem Fachprotokoll. Die Protokollansicht verlinkt
# nicht darauf, deshalb direkt aus der Tabelle.
VG_A=$(psql_ "SELECT vorgang FROM fachprotokoll WHERE vorgang IS NOT NULL
              ORDER BY id DESC LIMIT 1")
if [ -n "$VG_A" ]; then
  curl -s -b "$KEKSE" "$BASIS/vorgang/$VG_A" > "$SEITEN/vorgang.html"
  [ -s "$SEITEN/vorgang.html" ] || rm -f "$SEITEN/vorgang.html"
fi
[ -s "$SEITEN/zeitstrahl.html" ] || rm -f "$SEITEN/zeitstrahl.html"
# Die Strukturansicht der Organisation - eine eigene Seite mit vielen Grafiken
# und Farbaussagen. Genau dort entstehen Barrieren, wenn man nicht hinsieht.
curl -s -b "$KEKSE" "$BASIS/organisation?ansicht=struktur" > "$SEITEN/organisation-struktur.html"

# ADR-0012: Die Detailseite einer Betriebsstaette traegt zusaetzlich die Felder
# fuer BSNR und Telematik-ID. Die erste Detailseite im Baum ist der Traeger und
# hat sie nicht - deshalb gezielt die erste Einheit suchen, die sie zeigt.
for OID in $(curl -s -b "$KEKSE" $BASIS/organisation \
             | grep -oE '/organisation/[0-9]+' | sort -u | head -20); do
  curl -s -b "$KEKSE" "$BASIS$OID" > /tmp/pr-a11y-org.html
  if grep -q "Angaben zur Betriebsstätte" /tmp/pr-a11y-org.html; then
    cp /tmp/pr-a11y-org.html "$SEITEN/organisation-betriebsstaette.html"
    break
  fi
done
rm -f /tmp/pr-a11y-org.html

# Die Konnektor-Detailseite mit der Tafel der mitnutzenden Einrichtungen.
KID_A=$(curl -s -b "$KEKSE" $BASIS/bestand \
        | grep -oE '/bestand/konnektor/[0-9]+' | head -1)
[ -n "$KID_A" ] && curl -s -b "$KEKSE" "$BASIS$KID_A" > "$SEITEN/konnektor-detail.html"

curl -s -b "$KEKSE" $BASIS/bestand/konnektor/neu > "$SEITEN/konnektor-neu.html"
curl -s -b "$KEKSE" $BASIS/bestand/terminal/neu  > "$SEITEN/terminal-neu.html"

# Bedingte Seiten (Zeitstrahl, Vorgang) entstehen nur, wenn es Daten dafuer
# gibt. Bisher verschwanden sie stillschweigend - und die Zahl geprüfter Seiten
# schwankte, ohne dass jemand wusste, warum. Eine ungeprüfte Seite muss man
# sehen, sonst hält man die Prüfung für vollständiger, als sie ist.
for erwartet in zeitstrahl vorgang organisation-betriebsstaette konnektor-detail \
                organisation-struktur; do
  [ -s "$SEITEN/$erwartet.html" ] || \
    printf '  \033[33mNICHT GEPRUEFT\033[0m  %s — die Seite war im Prüfaufbau nicht erreichbar\n' \
      "$erwartet"
done

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
