#!/bin/sh
# Healthcheck des Containers (DevOps-Vorgabe 12.08.2026).
#
# /bin/sh, nicht bash: Seit dem Wechsel auf ein Alpine-Laufzeitbild (13.08.2026)
# gibt es keine bash mehr im Image. Die vorige Fassung nutzte bash-eigenes
# /dev/tcp — auf ash schlägt das fehl, und der Container wäre dauerhaft
# ungesund gewesen.
#
# Geprüft wird NICHT nur, ob der Port offen ist. Ein Java-Prozess nimmt
# Verbindungen an, lange bevor er arbeitsfähig ist; entscheidend ist die
# Antwort "status":"ok" — sie schließt die Datenbank ein.
#
# Welcher Port geprüft wird, steht in /app/gesundheitsport. Die Datei entsteht
# beim Bauen und nur für Bausteine mit HTTP-Schnittstelle: Der Standort-Agent
# baut Verbindungen nur nach außen auf und hat keinen Port.
set -u

PORTDATEI=/app/gesundheitsport
[ -r "$PORTDATEI" ] || { echo "kein HTTP-Dienst in diesem Baustein"; exit 0; }
PORT=$(cat "$PORTDATEI")

# wget gehört zu BusyBox und ist damit in jedem Alpine-Image vorhanden — ohne
# ein zusätzliches Paket. curl fehlt dort und wird nicht nachinstalliert.
ANTWORT=$(wget -q -T 3 -O - "http://127.0.0.1:${PORT}/health" 2>/dev/null) || {
  echo "keine Antwort von Port ${PORT}"; exit 1; }

case "$ANTWORT" in
  *'"status":"ok"'*) exit 0 ;;
  *) echo "$ANTWORT"; exit 1 ;;
esac
