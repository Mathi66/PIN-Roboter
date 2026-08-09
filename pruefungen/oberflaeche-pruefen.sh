#!/usr/bin/env bash
# ══════════════════════════════════════════════════════════════════════════
#  Einheitlichkeit der Oberfläche (UX-Vorgaben, CLAUDE.md Abschnitt 4)
#
#  Am 09.08.2026 wurde die Oberfläche vermessen statt begutachtet:
#
#    142 von 154 Bedienelementen ohne Kurzhinweis
#      0 von  97 Tabellenzeilen klickbar
#      1 von   6 Listen sortierbar
#     37 verschiedene Abstandswerte
#
#  Die Zahlen waren nicht die Krankheit, sondern das Symptom: Es gab keinen
#  gemeinsamen Baustein, also wich jede Seite ein wenig ab. Behoben wurde das
#  durch Bedienelemente.java und ein Abstandsraster.
#
#  DIESES SKRIPT hält das Ergebnis fest. Ohne Nachweis zerfällt Einheitlichkeit
#  mit der nächsten Seite — sie hängt sonst an Sorgfalt, und Sorgfalt ermüdet.
#
#  Aufruf:  bash pruefungen/oberflaeche-pruefen.sh <keksdatei>
# ══════════════════════════════════════════════════════════════════════════
set -uo pipefail

KEKSE="${1:?Keksdatei mit angemeldeter Sitzung erwartet}"
BASIS="${PR_BASIS:-http://localhost:8090}"
ABLAGE=$(mktemp -d)
trap 'rm -rf "$ABLAGE"' EXIT

FEHLER=0
gruen=$'\e[32m'; rot=$'\e[31m'; fett=$'\e[1m'; aus=$'\e[0m'

hinweis() { printf '\n%s%s%s\n' "$fett" "$1" "$aus"; }
ergebnis() {
  if [ "$2" = "ja" ]; then printf '  %sBESTANDEN%s  %s\n' "$gruen" "$aus" "$1"
  else printf '  %sFEHLGESCHLAGEN%s  %s\n' "$rot" "$aus" "$1"; FEHLER=$((FEHLER+1)); fi
}

SEITEN=(
  "/" "/geraete" "/gruppen" "/protokoll" "/organisation" "/benutzer"
  "/bestand" "/verwaltung" "/benachrichtigung" "/bestand/ermittelt"
  "/organisation/neu" "/bestand/terminal/neu" "/bestand/konnektor/neu"
)

hinweis "Oberfläche — Einheitlichkeit über alle Seiten"

for pfad in "${SEITEN[@]}"; do
  name=$(echo "$pfad" | tr '/' '_')
  curl -s -b "$KEKSE" "$BASIS$pfad" -o "$ABLAGE/$name.html"
done

# ── 1. Kurzhinweise ───────────────────────────────────────────────────────
# Jedes Bedienelement muss sagen, was es tut. title erreicht die Maus,
# aria-label den Screenreader — eines von beidem ist Pflicht.
OHNE=$(python3 - "$ABLAGE" <<'PY'
import re, sys, glob, os
fehlend = []
for f in glob.glob(os.path.join(sys.argv[1], "*.html")):
    h = open(f, encoding="utf-8").read()
    for b in re.findall(r'<button\b[^>]*>|<a\b[^>]*class="[^"]*knopf[^"]*"[^>]*>', h):
        if 'title=' not in b and 'aria-label' not in b:
            fehlend.append(os.path.basename(f) + ": " + b[:60])
print(len(fehlend))
for x in fehlend[:5]: print("     ", x, file=sys.stderr)
PY
)
[ "${OHNE:-1}" = "0" ] && A=ja || A=nein
ergebnis "jedes Bedienelement traegt einen Kurzhinweis ($OHNE ohne)" "$A"

# ── 2. Kurzhinweis sagt mehr als die Beschriftung ─────────────────────────
# Ein title="Löschen" auf einem Knopf „Löschen" ist ein Echo, kein Hinweis:
# Er kostet eine Screenreader-Ansage und hilft niemandem.
ECHOS=$(python3 - "$ABLAGE" <<'PY'
import re, sys, glob, os, html
n = 0
for f in glob.glob(os.path.join(sys.argv[1], "*.html")):
    h = open(f, encoding="utf-8").read()
    for m in re.finditer(r'<(button|a)\b([^>]*)>(.*?)</\1>', h, re.S):
        attr, text = m.group(2), re.sub(r'<[^>]*>', '', m.group(3)).strip()
        if 'knopf' not in attr and m.group(1) == 'a': continue
        t = re.search(r'title="([^"]*)"', attr)
        if t and text and html.unescape(t.group(1)).strip().lower() == text.lower():
            n += 1
print(n)
PY
)
[ "${ECHOS:-1}" = "0" ] && B=ja || B=nein
ergebnis "kein Kurzhinweis wiederholt nur die Beschriftung ($ECHOS Echos)" "$B"

# ── 3. Klickbare Zeilen ───────────────────────────────────────────────────
# Eine Liste, deren Einträge eine eigene Seite haben, oeffnet per Zeile —
# statt je Zeile einen „Öffnen"-Knopf zu tragen.
for paar in "/gruppen:gruppen:5" "/organisation:organisation:5" "/bestand:bestand:10"; do
  pfad=$(echo "$paar" | cut -d: -f1); name="_$(echo "$paar" | cut -d: -f2)"
  mindZ=$(echo "$paar" | cut -d: -f3)
  ZEILEN=$(grep -o 'class="klickbar' "$ABLAGE/$name.html" 2>/dev/null | wc -l | tr -d ' ')
  [ "${ZEILEN:-0}" -ge "$mindZ" ] && K=ja || K=nein
  ergebnis "$pfad hat klickbare Zeilen ($ZEILEN, mindestens $mindZ)" "$K"
done

# Und der Gegentest: In einer klickbaren Zeile darf kein „Öffnen"-Knopf mehr
# stehen. Zwei Wege zum selben Ziel sind einer zu viel.
OEFFNEN=$(cat "$ABLAGE"/*.html | grep -o '>Öffnen<' | wc -l | tr -d ' ')
[ "${OEFFNEN:-1}" = "0" ] && O=ja || O=nein
ergebnis "keine „Öffnen\"-Knoepfe mehr neben klickbaren Zeilen ($OEFFNEN)" "$O"

# ── 4. Sortierung und Suche ───────────────────────────────────────────────
for paar in "/gruppen:gruppen:4" "/bestand:bestand:8"; do
  pfad=$(echo "$paar" | cut -d: -f1); name="_$(echo "$paar" | cut -d: -f2)"
  mind=$(echo "$paar" | cut -d: -f3)
  SORT=$(grep -o 'aria-sort' "$ABLAGE/$name.html" 2>/dev/null | wc -l | tr -d ' ')
  [ "${SORT:-0}" -ge "$mind" ] && S=ja || S=nein
  ergebnis "$pfad hat sortierbare Spalten ($SORT, mindestens $mind)" "$S"
  SUCHE=$(grep -o 'type="search"' "$ABLAGE/$name.html" 2>/dev/null | wc -l | tr -d ' ')
  [ "${SUCHE:-0}" -gt 0 ] && Q=ja || Q=nein
  ergebnis "$pfad hat ein Suchfeld ($SUCHE)" "$Q"
done

# Die Sortierung muss auch WIRKEN, nicht nur angeboten werden.
ERSTE_AUF=$(curl -s -b "$KEKSE" "$BASIS/teil/gruppen?sortier=einrichtung&richtung=auf" \
            | grep -oE '<td>[^<]+</td>' | head -1)
ERSTE_AB=$(curl -s -b "$KEKSE" "$BASIS/teil/gruppen?sortier=einrichtung&richtung=ab" \
           | grep -oE '<td>[^<]+</td>' | head -1)
if [ -n "$ERSTE_AUF" ] && [ "$ERSTE_AUF" != "$ERSTE_AB" ]; then SW=ja; else SW=nein; fi
ergebnis "die Sortierrichtung aendert die Reihenfolge tatsaechlich" "$SW"

# Und die Suche muss filtern, nicht nur ein Feld zeigen.
VOLL=$(curl -s -b "$KEKSE" "$BASIS/teil/gruppen" | grep -o 'class="klickbar' | wc -l | tr -d ' ')
ENG=$(curl -s -b "$KEKSE" "$BASIS/teil/gruppen?suche=Kardio" | grep -o 'class="klickbar' | wc -l | tr -d ' ')
if [ "${VOLL:-0}" -gt "${ENG:-0}" ] && [ "${ENG:-0}" -gt 0 ]; then QW=ja; else QW=nein; fi
ergebnis "die Suche schraenkt die Liste ein ($VOLL ohne, $ENG mit Suchwort)" "$QW"

# Ein leeres Ergebnis muss erklaert werden, nicht nur leer sein.
LEER=$(curl -s -b "$KEKSE" "$BASIS/teil/gruppen?suche=gibtesnicht" | grep -c 'class="leer"' || true)
[ "${LEER:-0}" -gt 0 ] && L=ja || L=nein
ergebnis "eine erfolglose Suche erklaert sich statt leer zu bleiben" "$L"

# ── 5. Abstandsraster ─────────────────────────────────────────────────────
# Alle Abstaende kommen aus der Rasterreihe. Ein roher rem-Wert in padding,
# gap oder margin ist der Anfang der naechsten Unregelmaessigkeit.
CSS=src/zentrale/src/main/resources/statisch/stil.css
ROH=$(grep -oE "(padding|gap|margin)[a-z-]*:[^;]*[0-9]+(\.[0-9]+)?rem" "$CSS" | wc -l | tr -d ' ')
# Genau eine Ausnahme ist begruendet: der Einzug fuer das Suchsymbol haengt
# an dessen Groesse, nicht am Raster.
[ "${ROH:-9}" -le 1 ] && R=ja || R=nein
ergebnis "Abstaende kommen aus dem Raster ($ROH rohe Werte, hoechstens 1 erlaubt)" "$R"

RASTER=$(grep -cE "^  --r[1-6]:" "$CSS")
[ "${RASTER:-0}" = "6" ] && RD=ja || RD=nein
ergebnis "das Abstandsraster ist vollstaendig definiert ($RASTER von 6 Stufen)" "$RD"

# ── 6. Keine Abstaende in Inline-Styles ───────────────────────────────────
# Ein style="padding:..." im Java-Code umgeht das Raster und ist von aussen
# nicht auffindbar — genau so entstanden die 37 Werte.
INLINE=$(grep -ohE 'style="[^"]*"' src/zentrale/src/main/java/de/pinroboter/zentrale/*.java \
         | grep -cE "(padding|gap|margin)" || true)
[ "${INLINE:-9}" -le 3 ] && I=ja || I=nein
ergebnis "kaum Abstaende in Inline-Styles ($INLINE, hoechstens 3 geduldet)" "$I"

# ── 7. Destruktives ist bestaetigungspflichtig ────────────────────────────
# UX-Vorgabe 2: jede destruktive Aktion ist bestaetigungspflichtig.
OHNE_FRAGE=$(python3 - "$ABLAGE" <<'PY'
import re, sys, glob, os
n = 0
for f in glob.glob(os.path.join(sys.argv[1], "*.html")):
    h = open(f, encoding="utf-8").read()
    for m in re.finditer(r'<form\b([^>]*)>(.*?)</form>', h, re.S):
        attr, leib = m.group(1), m.group(2)
        if 'warnend' not in leib: continue
        if 'data-nachfrage' in attr or 'hx-confirm' in leib or 'onsubmit' in attr: continue
        n += 1
print(n)
PY
)
[ "${OHNE_FRAGE:-1}" = "0" ] && D=ja || D=nein
ergebnis "jede destruktive Aktion fragt nach ($OHNE_FRAGE ohne Rueckfrage)" "$D"

hinweis "Was diese Pruefung NICHT abdeckt"
cat <<'ENDE'
  - Ob ein Kurzhinweis fachlich richtig ist - das liest nur ein Mensch
  - Ob die Anordnung der Knoepfe sich richtig anfuehlt
  - Wie die Seite bei 5.000 Geraeten aussieht
  Sie prueft Einheitlichkeit, nicht Schoenheit.
ENDE

exit $FEHLER
