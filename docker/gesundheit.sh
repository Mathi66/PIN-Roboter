#!/usr/bin/env bash
# Healthcheck des Containers (DevOps-Vorgabe 12.08.2026).
#
# Ohne curl und ohne wget: Beide fehlen in schlanken Basisimages, und ein
# Healthcheck, der an einem Basisimage-Wechsel scheitert, ist eine Falle.
# bash kann TCP von sich aus (/dev/tcp) — mehr braucht ein HTTP/1.0-Aufruf nicht.
#
# Geprüft wird NICHT nur, ob der Port offen ist. Ein Java-Prozess nimmt
# Verbindungen an, lange bevor er arbeitsfähig ist; entscheidend ist die
# Antwort "status":"ok" — sie schließt die Datenbank ein.
#
# Welcher Port geprüft wird, steht in /app/gesundheitsport. Die Datei entsteht
# beim Bauen und nur für Bausteine, die eine HTTP-Schnittstelle haben: Der
# Standort-Agent baut Verbindungen nur nach außen auf und hat keinen Port.
# Für ihn gibt es nichts zu prüfen — Docker sieht ohnehin, ob der Prozess läuft.
set -uo pipefail

PORTDATEI=/app/gesundheitsport
[ -r "$PORTDATEI" ] || { echo "kein HTTP-Dienst in diesem Baustein"; exit 0; }
PORT=$(cat "$PORTDATEI")

exec 3<>"/dev/tcp/127.0.0.1/${PORT}" || { echo "Port ${PORT} antwortet nicht"; exit 1; }
printf 'GET /health HTTP/1.0\r\nHost: localhost\r\nConnection: close\r\n\r\n' >&3
ANTWORT=$(timeout 3 cat <&3)
exec 3<&-

case "$ANTWORT" in
  *'"status":"ok"'*) exit 0 ;;
  *) echo "$ANTWORT" | tail -3; exit 1 ;;
esac
