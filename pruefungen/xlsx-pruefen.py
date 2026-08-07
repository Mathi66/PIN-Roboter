#!/usr/bin/env python3
"""Prueft eine exportierte Arbeitsmappe mit openpyxl.

openpyxl ist eine unabhaengige Umsetzung des Formats. Wenn sie die Datei
oeffnen und lesen kann, ist das ein Nachweis - unsere eigene Umsetzung gegen
sich selbst zu pruefen waere zirkulaer.
"""
import sys
from openpyxl import load_workbook

pfad = sys.argv[1]
wb = load_workbook(pfad)
fehler = []

if wb.sheetnames != ["Deckblatt", "Protokoll"]:
    fehler.append(f"Blaetter unerwartet: {wb.sheetnames}")

d = wb["Deckblatt"]
text = " ".join(str(c.value) for r in d.iter_rows() for c in r if c.value)
for pflicht in ("Erstellt am", "Erstellt von", "Angewendete Filter", "Zeilen in diesem Auszug"):
    if pflicht not in text:
        fehler.append(f"Deckblatt ohne '{pflicht}'")

p = wb["Protokoll"]
kopf = [c.value for c in p[1]]
if kopf[:4] != ["Zeitpunkt", "Geraet" if "Geraet" in str(kopf) else "Gerät", "Ereignis", "Ergebnis"]:
    fehler.append(f"Kopfzeile unerwartet: {kopf}")
if p.freeze_panes != "A2":
    fehler.append(f"Kopfzeile nicht fixiert: {p.freeze_panes}")
if not p.auto_filter.ref:
    fehler.append("kein Autofilter gesetzt")

# Datumswerte muessen echte Zeitstempel sein, kein Text
if p.max_row > 1:
    import datetime
    wert = p.cell(row=2, column=1).value
    if not isinstance(wert, datetime.datetime):
        fehler.append(f"Zeitpunkt ist kein Datumswert, sondern {type(wert).__name__}")

# Keine Geheimnisse
alles = " ".join(str(c.value) for b in wb for r in b.iter_rows() for c in r if c.value)
for geheim in ("123456", "999999", "654321"):
    if geheim in alles:
        fehler.append(f"Geheimnis im Export gefunden: {geheim}")

if fehler:
    for f in fehler:
        print(f"  FEHLER: {f}")
    sys.exit(1)
print(f"  openpyxl liest die Datei: {p.max_row - 1} Protokollzeilen, "
      f"{len(kopf)} Spalten, Kopfzeile fixiert, Autofilter gesetzt")
