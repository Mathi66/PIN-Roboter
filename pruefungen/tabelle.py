#!/usr/bin/env python3
"""
Liest eine Tabellenspalte aus einer HTML-Seite — gefunden über ihre Überschrift.

    python3 pruefungen/tabelle.py <datei> <kopfname> [--zeilen]

Ohne Angabe werden die Werte der Spalte zeilenweise ausgegeben; mit
``--zeilen`` nur ihre Anzahl.

Warum ein eigenes Werkzeug
--------------------------
Am 11.08.2026 wurde dieselbe Aufgabe dreimal in awk gelöst und zweimal falsch:
einmal las die Prüfung die ganze Seite statt einer Tabelle (und mischte zwei
Listen), einmal nach einer Spaltenumstellung stillschweigend die falsche
Spalte. Beide Male meldete der Nachweis Ordnung, wo keine war — der
schlechteste Fehler, den ein Nachweis machen kann.

HTML zeilenweise mit awk zu zerlegen setzt voraus, dass die Ausgabe umbricht.
Sie tut es nicht durchgehend: Die Zentrale baut Tabellen teils in einem Stück
zusammen. Der Parser hier ist von Zeilenumbrüchen unabhängig.

Nur Standardbibliothek — der Prüfstand darf nicht an einer fehlenden
Abhängigkeit scheitern.
"""
import sys
from html.parser import HTMLParser


class Tabellenleser(HTMLParser):
    """Liest die erste Tabelle der Seite und merkt sich Kopf und Zellen."""

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.kopf = []
        self.zeilen = []
        self._fertig = False
        self._in_tabelle = False
        self._in_zelle = False
        self._puffer = []
        self._zeile = []
        self._ist_kopfzelle = False

    def handle_starttag(self, tag, attrs):
        if self._fertig:
            return
        if tag == "table":
            self._in_tabelle = True
        elif self._in_tabelle and tag in ("th", "td"):
            self._in_zelle = True
            self._ist_kopfzelle = tag == "th"
            self._puffer = []
        elif self._in_zelle and tag in ("br", "span", "div", "p", "a", "code"):
            # Trennzeichen einfügen: Ohne das klebt eine Zweitzeile am Namen
            # („Hausarztpraxis NordstadtKonnektor A") und jeder Vergleich auf
            # den reinen Wert schlägt fehl.
            self._puffer.append(" ")

    def handle_endtag(self, tag):
        if self._fertig:
            return
        if tag in ("th", "td") and self._in_zelle:
            text = " ".join("".join(self._puffer).split())
            if self._ist_kopfzelle:
                # Sortierpfeile abschneiden: Der aktive Spaltenkopf heißt
                # „Praxis ▴", nicht „Praxis". Ohne diese Normalisierung findet
                # eine Suche nach dem Spaltennamen ausgerechnet die Spalte
                # nicht, nach der gerade sortiert wird — und der Nachweis
                # meldet „zu wenige Zeilen" statt eines Ergebnisses
                # (11.08.2026).
                self.kopf.append(text.rstrip(" ▴▾↑↓"))
            else:
                self._zeile.append(text)
            self._in_zelle = False
        elif tag == "tr":
            if self._zeile:
                self.zeilen.append(self._zeile)
            self._zeile = []
        elif tag == "table" and self._in_tabelle:
            # Nur die ERSTE Tabelle. Wer eine bestimmte meint, schneidet sie
            # vorher heraus — sonst mischt die Prüfung zwei Listen, wie es
            # beim ersten Anlauf des Sortierungsnachweises geschah.
            self._fertig = True
            self._in_tabelle = False

    def handle_data(self, daten):
        if self._in_zelle:
            self._puffer.append(daten)


def main() -> int:
    if len(sys.argv) < 3:
        print("Aufruf: tabelle.py <datei> <kopfname> [--zeilen]", file=sys.stderr)
        return 2
    datei, gesucht = sys.argv[1], sys.argv[2]
    nur_zaehlen = "--zeilen" in sys.argv[3:]

    with open(datei, encoding="utf-8", errors="replace") as f:
        leser = Tabellenleser()
        leser.feed(f.read())

    if gesucht not in leser.kopf:
        print("Spalte „%s“ nicht gefunden. Vorhanden: %s"
              % (gesucht, ", ".join(leser.kopf) or "keine Tabelle"), file=sys.stderr)
        return 1

    index = leser.kopf.index(gesucht)
    werte = [z[index] for z in leser.zeilen if len(z) > index]
    if nur_zaehlen:
        print(len(werte))
    else:
        for w in werte:
            print(w)
    return 0


if __name__ == "__main__":
    sys.exit(main())
