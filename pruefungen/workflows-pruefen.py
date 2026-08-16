#!/usr/bin/env python3
"""
Sind die Shell-Blöcke in den GitHub-Workflows syntaktisch heil?

WARUM ES DIESE PRÜFUNG GIBT: Am 14.08.2026 sind beide Veröffentlichungsläufe
für 0.4.0 nach zehn Sekunden gescheitert — an einem Anführungszeichen:

    echo "::error::Unbekannter Baustein „$BAUSTEIN". Erwartet: …"
                                                   ^ gerades Zeichen

Das öffnende Zeichen ist typografisch, das schließende gerade. Für die Shell
endet die Zeichenkette dort, und der Rest der Zeile gerät durcheinander:
„unexpected EOF while looking for matching". Der Fehler steckt in einem
Fehlerpfad, der nie durchlaufen wird — die Shell prüft die Syntax trotzdem
beim Einlesen und bricht ab, bevor irgendetwas gebaut wird.

Gemerkt hat es niemand, weil bei einem gewöhnlichen Commit kein Lauf startet:
Der Veröffentlichungslauf hängt am Tag. Zwei Tage lang stand auf dem Server
deshalb weiter 0.3.0, obwohl 0.4.0 getaggt war.

Diese Prüfung liest jeden `run:`-Block und legt ihn `bash -n` vor — dieselbe
Prüfung, die der Runner macht, nur sofort und ohne Wartezeit.

    pruefungen/workflows-pruefen.py
"""
import pathlib
import re
import subprocess
import sys

WORKFLOWS = pathlib.Path(".github/workflows")


def bloecke(text):
    """Jeder `run: |`-Block als (Zeilennummer, Quelltext ohne Einrückung)."""
    gefunden = []
    for treffer in re.finditer(r"\n([ \t]+)run: \|\n", text):
        einrueckung = treffer.group(1) + "  "
        zeile = text[: treffer.start()].count("\n") + 2
        rest = text[treffer.end():].split("\n")
        code = []
        for z in rest:
            # Der Block endet an der ersten Zeile, die flacher eingerückt
            # ist. Leerzeilen gehören noch dazu.
            if z.strip() and not z.startswith(einrueckung):
                break
            code.append(z[len(einrueckung):] if z.strip() else "")
        gefunden.append((zeile, "\n".join(code)))
    return gefunden


def main():
    if not WORKFLOWS.is_dir():
        print("  Kein Verzeichnis .github/workflows — nichts zu prüfen.")
        return 0

    geprueft, kaputt = 0, []
    for datei in sorted(WORKFLOWS.glob("*.yml")):
        for zeile, code in bloecke(datei.read_text()):
            # GitHub setzt seine Ausdrücke vor dem Lauf ein. Für die
            # Syntaxprüfung ersetzen wir sie durch einen harmlosen Platzhalter,
            # sonst stolpert bash über die geschweiften Klammern.
            sauber = re.sub(r"\$\{\{[^}]*\}\}", "PLATZHALTER", code)
            geprueft += 1
            lauf = subprocess.run(["bash", "-n"], input=sauber,
                                  capture_output=True, text=True)
            if lauf.returncode != 0:
                meldung = lauf.stderr.strip().replace("\n", " ")
                kaputt.append((datei.name, zeile, meldung))

    print("\n\033[1mShell-Blöcke in den Workflows\033[0m")
    for name, zeile, meldung in kaputt:
        print(f"  \033[31m✗\033[0m {name}:{zeile}  {meldung}")
    if kaputt:
        print(f"\n  \033[31m{len(kaputt)} von {geprueft} Blöcken sind "
              f"syntaktisch kaputt — der Lauf würde sofort abbrechen\033[0m")
        return 1
    print(f"  \033[32mBESTANDEN\033[0m  {geprueft} Blöcke, alle syntaktisch heil")
    return 0


if __name__ == "__main__":
    sys.exit(main())
