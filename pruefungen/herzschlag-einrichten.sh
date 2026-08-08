#!/usr/bin/env bash
# Richtet den Wächter als launchd-Job ein (macOS).
#
# Er läuft dann unabhängig von der Claude-Sitzung — genau darum geht es: Eine
# Überwachung, die im selben Prozess liegt wie das Überwachte, merkt einen
# Stillstand nicht.
#
#   Einrichten:   pruefungen/herzschlag-einrichten.sh
#   Entfernen:    pruefungen/herzschlag-einrichten.sh entfernen
#   Protokoll:    tail -f "${TMPDIR:-/tmp}/pinroboter-herzschlag.log"

set -uo pipefail
cd "$(dirname "$0")/.."

KENNUNG="de.pinroboter.herzschlag"
PLIST="$HOME/Library/LaunchAgents/$KENNUNG.plist"
SKRIPT="$PWD/pruefungen/herzschlag.sh"
TAKT=900          # alle 15 Minuten pruefen
GRENZE=45         # Alarm ab 45 Minuten ohne Commit

if [ "${1:-}" = "entfernen" ]; then
  launchctl bootout "gui/$(id -u)/$KENNUNG" 2>/dev/null
  rm -f "$PLIST"
  echo "Wächter entfernt."
  exit 0
fi

mkdir -p "$HOME/Library/LaunchAgents"
cat > "$PLIST" <<PLISTENDE
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN"
  "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>$KENNUNG</string>
  <key>ProgramArguments</key>
  <array>
    <string>/bin/bash</string>
    <string>$SKRIPT</string>
    <string>$GRENZE</string>
  </array>
  <key>StartInterval</key><integer>$TAKT</integer>
  <key>RunAtLoad</key><true/>
  <key>StandardOutPath</key><string>${TMPDIR:-/tmp}/pinroboter-herzschlag.out</string>
  <key>StandardErrorPath</key><string>${TMPDIR:-/tmp}/pinroboter-herzschlag.err</string>
</dict>
</plist>
PLISTENDE

launchctl bootout "gui/$(id -u)/$KENNUNG" 2>/dev/null
if launchctl bootstrap "gui/$(id -u)" "$PLIST" 2>/dev/null; then
  echo "Wächter eingerichtet: prüft alle $((TAKT/60)) Minuten,"
  echo "meldet sich ab $GRENZE Minuten ohne Commit."
  echo "Protokoll: ${TMPDIR:-/tmp}/pinroboter-herzschlag.log"
else
  echo "Der Wächter ließ sich nicht starten. Der Job liegt in $PLIST;"
  echo "von Hand:  launchctl bootstrap gui/$(id -u) \"$PLIST\""
  exit 1
fi
