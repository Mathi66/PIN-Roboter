/* ══════════════════════════════════════════════════════════════════════════
   PIN-Roboter — Bedienhilfen der Oberfläche

   Grundregel: Diese Datei enthält nur BEQUEMLICHKEIT. Fällt sie aus, bleibt
   jede Funktion über echte Links und Formulare erreichbar. Alles, was ohne
   JavaScript nicht mehr ginge, gehört nicht hierher.
   ══════════════════════════════════════════════════════════════════════════ */

(function () {
  'use strict';

  /* ── Zeilenklick ────────────────────────────────────────────────────────
     Eine Tabellenzeile öffnet ihren Eintrag, ohne dass ein „Öffnen"-Knopf
     nötig ist. Die Zeile ist bewusst KEIN Bedienelement im Sinne der
     Barrierefreiheit — das Ziel ist der echte Link in der ersten Zelle.
     Hier wird der Klick nur dorthin weitergereicht.

     Drei Fälle bleiben ausdrücklich unberührt:
       - Klicks auf Bedienelemente in der Zeile (Knopf, Auswahlfeld,
         Ankreuzfeld, ein anderer Link) — sonst löste ein „Löschen" zugleich
         das Öffnen aus.
       - Klicks mit gedrückter Sondertaste und die mittlere Maustaste — sie
         sollen wie gewohnt einen neuen Tab öffnen.
       - Klicks, die eine Textauswahl beenden: Wer eine Kennung markiert,
         will sie kopieren, nicht die Seite wechseln. */
  document.addEventListener('click', function (e) {
    var zeile = e.target.closest ? e.target.closest('tr.klickbar') : null;
    if (!zeile) return;
    if (e.target.closest('a, button, input, select, textarea, label, summary')) return;
    if (e.metaKey || e.ctrlKey || e.shiftKey || e.altKey || e.button !== 0) return;
    var auswahl = window.getSelection();
    if (auswahl && String(auswahl).length > 0) return;

    var ziel = zeile.querySelector('a.zeilenziel');
    if (ziel) ziel.click();
  });

  /* ── Bestätigung vor destruktiven Aktionen ──────────────────────────────
     HTMX bringt hx-confirm mit, aber nur für HTMX-Anfragen. Gewöhnliche
     Formulare brauchen denselben Schutz, und die Meldung soll überall
     gleich klingen. */
  document.addEventListener('submit', function (e) {
    var frage = e.target.getAttribute('data-nachfrage');
    if (frage && !window.confirm(frage)) e.preventDefault();
  });
})();
