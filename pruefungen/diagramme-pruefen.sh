#!/usr/bin/env bash
# ══════════════════════════════════════════════════════════════════════════
#  Diagramme in der Dokumentation (Mermaid)
#
#  Bis zum 10.08.2026 waren alle Diagramme ASCII-Zeichnungen. Der PO nannte sie
#  altbacken — zu Recht: Sie lassen sich nicht zoomen, nicht durchsuchen, auf
#  dem Telefon nicht lesen, und ein Sequenzdiagramm mit acht Beteiligten wird
#  darin unlesbar.
#
#  Mermaid statt PNG: Die Quelle bleibt im Repository, versionierbar und
#  diffbar. Ein Bild, dessen Quelle woanders liegt, veraltet als Erstes — der
#  Code ändert sich, das Bild bleibt.
#
#  DIESE PRUEFUNG rendert jedes Diagramm wirklich. Ein Tippfehler in der
#  Mermaid-Syntax fällt sonst erst auf, wenn jemand das Dokument öffnet — und
#  dann steht dort statt eines Diagramms eine Fehlermeldung. Das wäre schlimmer
#  als die ASCII-Zeichnung vorher.
#
#  Aufruf:  bash pruefungen/diagramme-pruefen.sh
# ══════════════════════════════════════════════════════════════════════════
set -uo pipefail

ABLAGE=$(mktemp -d)
trap 'rm -rf "$ABLAGE"' EXIT

gruen=$'\e[32m'; rot=$'\e[31m'; fett=$'\e[1m'; aus=$'\e[0m'
FEHLER=0

printf '\n%sDiagramme der Dokumentation%s\n' "$fett" "$aus"

python3 - "$ABLAGE" <<'PY'
import re, pathlib, sys
ziel = pathlib.Path(sys.argv[1])
n = 0
for f in sorted(pathlib.Path("docs").rglob("*.md")):
    for m in re.finditer(r'```mermaid\n(.*?)```', f.read_text(), re.S):
        n += 1
        (ziel / f"{n:03d}__{f.stem[:24]}.mmd").write_text(m.group(1))
print(f"  {n} Diagramme gefunden")
PY

ANZAHL=$(ls "$ABLAGE"/*.mmd 2>/dev/null | wc -l | tr -d ' ')
if [ "${ANZAHL:-0}" = "0" ]; then
  printf '  %sFEHLGESCHLAGEN%s  keine Diagramme gefunden\n' "$rot" "$aus"
  exit 1
fi

# Ohne Docker keine Prüfung — dann ehrlich sagen, dass nichts geprüft wurde,
# statt "bestanden" zu melden.
if ! docker image inspect minlag/mermaid-cli:latest >/dev/null 2>&1; then
  echo "  Lade das Render-Werkzeug ..."
  docker pull -q minlag/mermaid-cli:latest >/dev/null 2>&1 || {
    printf '  %sÜBERSPRUNGEN%s  mermaid-cli nicht verfügbar — NICHT geprüft\n' "$rot" "$aus"
    exit 1; }
fi

for f in "$ABLAGE"/*.mmd; do
  name=$(basename "$f")
  if docker run --rm -v "$ABLAGE":/data minlag/mermaid-cli:latest \
       -i "/data/$name" -o "/data/${name%.mmd}.svg" >/dev/null 2>"$ABLAGE/fehler.txt"; then
    printf '.'
  else
    printf '\n  %sFEHLGESCHLAGEN%s  %s\n' "$rot" "$aus" "$name"
    grep -iE "error|expecting|parse" "$ABLAGE/fehler.txt" | head -2 | sed 's/^/      /'
    FEHLER=$((FEHLER + 1))
  fi
done
printf '\n'

if [ $FEHLER -eq 0 ]; then
  printf '  %sBESTANDEN%s  alle %s Diagramme rendern fehlerfrei\n' "$gruen" "$aus" "$ANZAHL"
else
  printf '  %s%d von %s Diagrammen fehlerhaft%s\n' "$rot" "$FEHLER" "$ANZAHL" "$aus"
fi

# Keine ASCII-Zeichnungen mehr — außer in ADRs. Die sind nach CLAUDE.md
# unveränderlich: Eine getroffene Entscheidung wird nicht nachträglich
# umgeschrieben, auch nicht kosmetisch.
ASCII=$(python3 - <<'PY'
import re, pathlib
n = 0
for f in pathlib.Path("docs").rglob("*.md"):
    if "/adr/" in str(f): continue
    for m in re.finditer(r'```\n(.*?)```', f.read_text(), re.S):
        if any(c in m.group(1) for c in '┌│└─►▶↓'): n += 1
print(n)
PY
)
if [ "${ASCII:-9}" = "0" ]; then
  printf '  %sBESTANDEN%s  keine ASCII-Zeichnungen außerhalb der ADRs\n' "$gruen" "$aus"
else
  printf '  %sFEHLGESCHLAGEN%s  %s ASCII-Zeichnungen außerhalb der ADRs\n' "$rot" "$aus" "$ASCII"
  FEHLER=$((FEHLER + 1))
fi

exit $FEHLER
