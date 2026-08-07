/**
 * Kontrastprüfung der Farbpaare (WCAG 2.2 AA, Kriterium 1.4.3).
 *
 * jsdom rendert nicht und kann Kontraste deshalb nicht messen. Statt die Lücke
 * offen zu lassen, wird hier gerechnet: Die Farbpaare stehen als CSS-Variablen
 * fest, und das Kontrastverhältnis ist eine Formel — das Ergebnis ist damit
 * belastbarer als ein Screenshot-Vergleich.
 *
 * Grenzwerte nach WCAG 2.2:
 *   4,5:1 für normalen Text
 *   3,0:1 für großen Text (ab 18,66 px fett oder 24 px) und Bedienelemente
 */
import { readFileSync } from 'node:fs';

const css = readFileSync(process.argv[2] || '/stil.css', 'utf8');

/** Liest die :root-Variablen eines Blocks. */
function variablen(block) {
  const v = {};
  for (const m of block.matchAll(/--([a-z-]+)\s*:\s*(#[0-9a-fA-F]{3,8})/g)) v[m[1]] = m[2];
  return v;
}

const hell = variablen(css.slice(css.indexOf(':root'), css.indexOf('@media')));
const dunkelBlock = css.slice(css.indexOf('@media (prefers-color-scheme: dark)'));
const dunkel = { ...hell, ...variablen(dunkelBlock.slice(0, dunkelBlock.indexOf('}\n}'))) };

function kanal(c) {
  const s = c / 255;
  return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
}

function helligkeit(hex) {
  let h = hex.replace('#', '');
  if (h.length === 3) h = h.split('').map(x => x + x).join('');
  const r = parseInt(h.slice(0, 2), 16), g = parseInt(h.slice(2, 4), 16), b = parseInt(h.slice(4, 6), 16);
  return 0.2126 * kanal(r) + 0.7152 * kanal(g) + 0.0722 * kanal(b);
}

function verhaeltnis(a, b) {
  const l1 = helligkeit(a), l2 = helligkeit(b);
  return (Math.max(l1, l2) + 0.05) / (Math.min(l1, l2) + 0.05);
}

// Die Paare, die in der Oberfläche tatsächlich vorkommen.
const paare = [
  ['Fließtext auf Grund',        'text', 'grund',        4.5],
  ['Fließtext auf Karte',        'text', 'karte',        4.5],
  ['Gedämpfter Text auf Karte',  'gedaempft', 'karte',   4.5],
  ['Gedämpfter Text auf Grund',  'gedaempft', 'grund',   4.5],
  ['Verknüpfung auf Karte',      'akzent', 'karte',      4.5],
  ['Verknüpfung auf Grund',      'akzent', 'grund',      4.5],
  ['Marke „in Ordnung"',         'ok', 'ok-hg',          4.5],
  ['Marke „Warnung"',            'warnung', 'warnung-hg', 4.5],
  ['Marke „Fehler"',             'fehler', 'fehler-hg',  4.5],
  ['Marke „unbekannt"',          'unbekannt', 'unbekannt-hg', 4.5],
  ['Hinweisfeld',                'text', 'akzent-hg',    4.5],
  // WCAG 1.4.11: Begrenzungen von Bedienelementen brauchen 3:1. Der
  // dekorative --rand (Trennlinien, Kartenumrandung) faellt nicht darunter -
  // er traegt keine Information, die zur Bedienung noetig waere.
  ['Rand von Bedienelementen',   'rand-bedien', 'karte', 3.0],
  ['Rand von Bedienelementen',   'rand-bedien', 'grund', 3.0],
];

let fehler = 0;
for (const [name, farbe] of [['hell', hell], ['dunkel', dunkel]]) {
  console.log(`\n  Farbschema: ${name}`);
  for (const [bez, vg, hg, ziel] of paare) {
    if (!farbe[vg] || !farbe[hg]) { console.log(`    ?  ${bez} — Variable fehlt`); continue; }
    const r = verhaeltnis(farbe[vg], farbe[hg]);
    const ok = r >= ziel;
    if (!ok) fehler++;
    console.log(`    ${ok ? 'OK' : '!!'} ${bez.padEnd(28)} ${r.toFixed(2)}:1  (nötig ${ziel}:1)`);
  }
}

console.log(`\n  ${fehler} Kontrast(e) unter dem Grenzwert`);
process.exit(fehler === 0 ? 0 : 1);
