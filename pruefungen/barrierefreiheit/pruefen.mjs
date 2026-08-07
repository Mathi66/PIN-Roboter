/**
 * Barrierefreiheitsprüfung der Oberfläche (NFA-USA-030, WCAG 2.2 Level AA).
 *
 * Bis zum 07.08.2026 war die Anforderung BEHAUPTET, nicht belegt: Die Vorgaben
 * wurden beim Bauen beachtet, aber nie mit einem Werkzeug gegengeprüft. Das
 * holt dieses Skript nach.
 *
 * WAS ES PRÜFT: axe-core gegen die ausgelieferten Seiten, in jsdom.
 * Das deckt Struktur ab — ARIA-Rollen, Beschriftungen von Bedienelementen,
 * Überschriftenhierarchie, Tabellenköpfe, Formularzuordnungen, doppelte
 * Kennungen, Sprachauszeichnung.
 *
 * WAS ES NICHT PRÜFT, und das ist wesentlich:
 *  - Kontraste. Sie brauchen echtes Rendering; jsdom rechnet keine Farben aus.
 *    Dafür gibt es kontraste.mjs, das die CSS-Farbpaare rechnerisch prüft.
 *  - Tastaturbedienung im Ablauf (Reihenfolge, Fallen). Das braucht einen
 *    echten Browser und bleibt eine offene Lücke.
 *  - Alles, was erst nach einer Nutzerhandlung entsteht.
 *
 * Ein Werkzeug findet ohnehin nur einen Teil der Barrieren. Es ersetzt keine
 * Prüfung mit einem Screenreader — es fängt nur das ab, was maschinell
 * erkennbar ist, und das zuverlässig.
 */
import { JSDOM } from 'jsdom';
import axe from 'axe-core';
import { readFileSync, readdirSync } from 'node:fs';

const ordner = process.argv[2] || '/seiten';
const dateien = readdirSync(ordner).filter(n => n.endsWith('.html'));

let befunde = 0;
let geprueft = 0;

for (const datei of dateien) {
  const html = readFileSync(`${ordner}/${datei}`, 'utf8');
  const dom = new JSDOM(html, { runScripts: 'outside-only', pretendToBeVisual: true });
  const { window } = dom;

  // axe-core in das Fenster einspielen und dort ausführen.
  window.eval(axe.source);
  const ergebnis = await window.axe.run(window.document, {
    // Regeln, die ohne Rendering nicht entscheidbar sind, ausschalten -
    // sie würden sonst falsche Sicherheit oder falschen Alarm erzeugen.
    rules: {
      'color-contrast': { enabled: false },
      'color-contrast-enhanced': { enabled: false }
    },
    runOnly: { type: 'tag', values: ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'] }
  });

  geprueft++;
  const verstoesse = ergebnis.violations;
  if (verstoesse.length === 0) {
    console.log(`  OK        ${datei}`);
    continue;
  }
  console.log(`  BEFUNDE   ${datei}`);
  for (const v of verstoesse) {
    befunde += v.nodes.length;
    console.log(`    [${v.impact}] ${v.id}: ${v.help}`);
    for (const n of v.nodes.slice(0, 3)) {
      console.log(`      ${n.html.slice(0, 110).replace(/\s+/g, ' ')}`);
    }
    if (v.nodes.length > 3) console.log(`      … und ${v.nodes.length - 3} weitere`);
  }
}

console.log(`\n  ${geprueft} Seite(n) geprüft, ${befunde} Befund(e)`);
process.exit(befunde === 0 ? 0 : 1);
