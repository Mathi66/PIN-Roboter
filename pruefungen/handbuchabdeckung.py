#!/usr/bin/env python3
"""
Deckt das Handbuch alles ab, was die Oberfläche zeigt?

Der PO hat den Info-Knopf zum Vollständigkeitsprüfer erklärt (15.08.2026):
Jede Tafel — jede Funktion, jede Konfiguration, jedes Statusfenster — soll
einen Knopf tragen, der in das Handbuch führt. Wo kein Abschnitt existiert,
fällt die Lücke damit auf, statt jahrelang niemandem zu begegnen.

Dieses Skript zählt, wie weit wir davon entfernt sind. Es liest die
Tafelüberschriften aus dem Quelltext und prüft je Tafel, ob dort ein
`Bedienelemente.info(...)` steht.

    pruefungen/handbuchabdeckung.py            Bericht
    pruefungen/handbuchabdeckung.py --offen    nur die Lücken, für die Arbeit

WARUM DIE ÜBERSCHRIFT UND NICHT DAS FELD: Ein Knopf je Eingabefeld wäre eine
Nadelwand. Die Tafel ist die Einheit, in der ein Mensch denkt — „was ist
Datensicherung", nicht „was bedeutet dieses eine Feld". Feldbezogene Angaben
bleiben am Feld.
"""
import re
import sys
import pathlib

QUELLEN = pathlib.Path("src/zentrale/src/main/java/de/pinroboter/zentrale")

# Nicht mitzählen: Das Handbuch erklärt sich nicht selbst, und die
# Anmeldeseite steht vor jeder Anmeldung — dort gibt es kein Handbuch.
AUSGENOMMEN = {"HandbuchInhalt", "Handbuchseiten", "Handbuchgrafik", "Anmeldeseiten"}


def tafeln():
    """Alle Tafelüberschriften mit Datei, Text und Info-Knopf-Kennung."""
    gefunden = []
    for datei in sorted(QUELLEN.glob("*.java")):
        name = datei.stem
        if name in AUSGENOMMEN:
            continue
        text = datei.read_text()
        # Seitenköpfe zählen mit: Nicht jede Hauptfunktion hat eine Tafel —
        # das Protokoll etwa besteht aus Filterleiste und Tabelle.
        # Der Textblock danach begrenzt den Aufruf: Ohne diese Bremse liefe
        # der Ausdruck über `) + """…` hinweg und fände irgendein späteres
        # Kurzwort — etwa eine CSS-Klasse — als vermeintliche Kennung.
        muster = r'seitenkopf\("([^"]+)"(?:(?!""")[^;])*?,\s*"([a-z0-9-]+)"\)'
        for kopf in re.finditer(muster, text, re.S):
            gefunden.append({
                "datei": name, "zeile": text[: kopf.start()].count("\n") + 1,
                "titel": kopf.group(1), "kennung": kopf.group(2),
            })

        for treffer in re.finditer(r"<h3>(.*?)</h3>", text, re.S):
            roh = treffer.group(1)
            knopf = re.search(r'Bedienelemente\.info\("([a-z0-9-]+)"', roh)
            # Der Titel: Markup, Java-Ausdrücke und Platzhalter raus.
            titel = re.sub(r"<[^>]*>", "", roh)
            titel = re.sub(r'"\s*[\+\)].*?(?:"|$)', " ", titel, flags=re.S)
            titel = re.sub(r"%[sd]|\.append\(.*?\)|[\"();]", " ", titel)
            titel = re.sub(r"\s+", " ", titel).strip(" …·-")
            if not titel:
                continue
            zeile = text[: treffer.start()].count("\n") + 1
            gefunden.append({
                "datei": name, "zeile": zeile, "titel": titel,
                "kennung": knopf.group(1) if knopf else None,
            })
    return gefunden


def abschnitte():
    """Alle im Handbuch markierten Abschnitte."""
    text = (QUELLEN / "HandbuchInhalt.java").read_text()
    return set(re.findall(r'<section data-hilfe="([a-z0-9-]+)">', text))


def main():
    nur_offen = "--offen" in sys.argv
    alle = tafeln()
    vorhanden = abschnitte()

    # Dubletten: Dieselbe Tafel erscheint oft in zwei Zuständen (mit und ohne
    # Daten). Für die Abdeckung zählt sie einmal — aber nur, wenn BEIDE
    # Fassungen einen Knopf haben, ist sie wirklich erledigt.
    nach_titel = {}
    for t in alle:
        nach_titel.setdefault(t["titel"], []).append(t)

    offen, fertig, tot = [], [], []
    for titel, gruppe in sorted(nach_titel.items()):
        mit = [t for t in gruppe if t["kennung"]]
        ohne = [t for t in gruppe if not t["kennung"]]
        for t in mit:
            if t["kennung"] not in vorhanden:
                tot.append(t)
        if ohne:
            offen.append((titel, ohne))
        else:
            fertig.append(titel)

    if not nur_offen:
        print(f"\n\033[1mHandbuch-Abdeckung der Oberfläche\033[0m")
        print(f"  {len(nach_titel)} Tafeln, {len(vorhanden)} Handbuchabschnitte\n")
        for titel in fertig:
            print(f"  \033[32m✓\033[0m {titel}")

    for titel, stellen in offen:
        orte = ", ".join(f"{t['datei']}:{t['zeile']}" for t in stellen)
        print(f"  \033[33m·\033[0m {titel}  ({orte})")

    for t in tot:
        print(f"  \033[31m✗\033[0m {t['titel']}: Kennung „{t['kennung']}“ "
              f"gibt es im Handbuch nicht")

    quote = 100 * len(fertig) // max(1, len(nach_titel))
    print(f"\n  \033[1m{len(fertig)} von {len(nach_titel)} Tafeln "
          f"({quote} %) führen ins Handbuch\033[0m")
    if tot:
        print(f"  \033[31m{len(tot)} Knopf/Knöpfe zeigen ins Leere\033[0m")
    return 1 if tot else 0


if __name__ == "__main__":
    sys.exit(main())
