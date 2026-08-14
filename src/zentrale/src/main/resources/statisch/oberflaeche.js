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

  /* ── Hilfefenster (FA-DOK-020) ──────────────────────────────────────────
     Der Info-Knopf ist ein echter Link ins Handbuch. Ist JavaScript da,
     wird daraus ein Fenster: HTMX holt den Abschnitt, dieses Stück öffnet
     den Dialog. Fällt JavaScript aus, öffnet der Link das Kapitel — die
     Erklärung bleibt erreichbar, nur unbequemer.

     Das <dialog>-Element bringt mit, was man sonst nachbauen müsste: Esc
     schließt, der Fokus bleibt darin, dahinter ist nichts bedienbar, und
     ein Screenreader kündigt es als Dialog an. */

  var zuletztFokussiert = null;

  window.pinHilfe = function (ausloeser) {
    var f = document.getElementById('hilfefenster');
    if (!f || !f.showModal) return true;   /* alter Browser: dem Link folgen */
    /* Wohin der Fokus zurück soll, wenn das Fenster zugeht. Ohne das landet
       er am Seitenanfang, und man sucht seine Stelle wieder. */
    zuletztFokussiert = ausloeser;
    if (!f.open) f.showModal();
    return false;
  };

  /* Der Fokus muss dorthin zurück, wo er herkam. Ohne das landet er am
     Seitenanfang, und wer mit der Tastatur arbeitet, sucht seine Stelle
     wieder — bei einer Hilfe besonders ärgerlich, denn man wollte ja
     weitermachen, wo man war. */
  function fokusZurueck() {
    if (zuletztFokussiert && document.contains(zuletztFokussiert)) {
      zuletztFokussiert.focus();
    }
    zuletztFokussiert = null;
  }

  window.pinHilfeZu = function () {
    var f = document.getElementById('hilfefenster');
    if (f && f.open) f.close();
    fokusZurueck();
  };

  document.addEventListener('DOMContentLoaded', function () {
    var f = document.getElementById('hilfefenster');
    if (!f) return;

    /* Klick auf die Fläche daneben schließt — erwartetes Verhalten, und
       das dialog-Element liefert es nicht von selbst. Geprüft wird gegen
       das Fenster selbst: Nur dort trifft ein Klick den Hintergrund. */
    f.addEventListener('click', function (e) {
      if (e.target === f) window.pinHilfeZu();
    });

    /* „cancel" ist der Esc-Weg. Bewusst NICHT nur am „close"-Ereignis
       gehangen: Das feuert nicht in jeder Engine zuverlässig — gemessen am
       14.08.2026. Alle drei Wege, die ein Mensch geht — Knopf, Esc, Klick
       daneben — setzen den Fokus deshalb selbst zurück. */
    f.addEventListener('cancel', fokusZurueck);
    f.addEventListener('close', fokusZurueck);
  });

  /* Nach dem Nachladen den Fokus in das Fenster setzen: Wer mit der Tastatur
     arbeitet, steht sonst weiter auf dem Knopf hinter dem Dialog. */
  document.addEventListener('htmx:afterSwap', function (e) {
    if (e.target && e.target.id === 'hilfeinhalt') {
      var zu = e.target.querySelector('.hilfe-zu');
      if (zu) zu.focus();
    }
  });
})();
