#!/usr/bin/env bash
# Wächter über die autonome Arbeit am PIN-Roboter.
#
# WARUM ES DIESES SKRIPT GIBT
#
# Am 08.08.2026 hat die Schleife nach 01:18 Uhr aufgehört zu arbeiten. Erst
# gegen 14:30 fiel es auf — durch eine Nachfrage, nicht durch das System.
# Dreizehn Stunden Stillstand, unbemerkt.
#
# Der Grund, warum das nicht von selbst auffällt: Wenn die Sitzung nicht
# aufwacht, läuft nichts, was das feststellen könnte. Ein Prozess kann sein
# eigenes Nicht-Laufen nicht bemerken. Die Überwachung muss deshalb AUSSERHALB
# liegen — hier, in einem Skript, das der Rechner startet, nicht die Sitzung.
#
# WAS ES TUT
#
# Vergleicht den Zeitpunkt des letzten Commits mit jetzt. Liegt er länger
# zurück als das Grenzalter, meldet es sich per Mitteilung auf dem Bildschirm
# und schreibt eine Zeile in das Wächterprotokoll.
#
# Aufruf von Hand:   pruefungen/herzschlag.sh [grenze-in-minuten]
# Als Wächter:       siehe pruefungen/herzschlag-einrichten.sh

set -uo pipefail
cd "$(dirname "$0")/.."

GRENZE_MINUTEN=${1:-45}
PROTOKOLL="${TMPDIR:-/tmp}/pinroboter-herzschlag.log"

LETZTER=$(git log -1 --format=%ct 2>/dev/null)
if [ -z "$LETZTER" ]; then
  echo "Kein Git-Verlauf gefunden — hier ist nichts zu überwachen."
  exit 2
fi

JETZT=$(date +%s)
STILL_MINUTEN=$(( (JETZT - LETZTER) / 60 ))
BETREFF=$(git log -1 --format=%s)

printf '%s  Stillstand: %d min (Grenze %d)  Letzter: %s\n' \
  "$(date '+%d.%m. %H:%M')" "$STILL_MINUTEN" "$GRENZE_MINUTEN" "$BETREFF" >> "$PROTOKOLL"

if [ "$STILL_MINUTEN" -le "$GRENZE_MINUTEN" ]; then
  echo "In Ordnung: letzter Commit vor $STILL_MINUTEN Minuten — „$BETREFF“"
  exit 0
fi

STUNDEN=$(( STILL_MINUTEN / 60 ))
TEXT="Seit $STILL_MINUTEN Minuten kein Commit. Der letzte war „$BETREFF“."
[ "$STUNDEN" -ge 1 ] && TEXT="Seit rund $STUNDEN Stunden kein Commit. Der letzte war „$BETREFF“."

echo "STILLSTAND: $TEXT"

# Mitteilung auf dem Bildschirm. Nur auf macOS; anderswo bleibt die Ausgabe
# oben und der Eintrag im Protokoll.
if command -v osascript >/dev/null 2>&1; then
  osascript -e "display notification \"$TEXT\" with title \"PIN-Roboter steht still\" sound name \"Basso\"" \
    >/dev/null 2>&1
fi
exit 1
