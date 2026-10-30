// Checks Code.gs's reading of the Overview tab on made-up rows shaped like the real sheet
// (merged cells read as blanks). Run: node sheet-sync/test.js
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const gs = vm.createContext({});
vm.runInContext(fs.readFileSync(path.join(__dirname, 'Code.gs'), 'utf8'), gs);
const BASE = 'https://docs.google.com/spreadsheets/d/SHEET/edit';
// Objects made inside the script's context have its prototypes; compare them as plain JSON.
const parse = (...a) => JSON.parse(JSON.stringify(gs.parseTodos(...a)));

const pad = (row) => row.concat(Array(12 - row.length).fill(''));
const rows = [
  pad(['  💰  SUMMARY']),
  pad(['NET', '', 'SHARE']),
  pad(['  🔔  TO-DO & NOTIFICATIONS  ·  2 items need your attention  ·  oldest first']),
  pad(['Where  ·  click to open', '', '', 'Date', 'Amount', '', 'What needs doing  ·  who / what']),
  pad(['🪨 Materials · row 5', '', '', 'Oct 1, 2026', '₱1,000.00', '', '💵 Awaiting payment  ·  Sample Client  ·  Sand']),
  pad(['🤝 Funds · row 3', '', '', 'Oct 2, 2026', '₱250.50', '', '⏳ To return  ·  Sample Hauler']),
  pad(['  🏦  BANK  ·  business account']),
  pad(['Account', 'Balance']),
];
const links = rows.map((r, i) => r.map((_, c) => (i === 4 && c === 0 ? '#gid=42&range=A5' : i === 5 && c === 0 ? 'https://example.com/x' : null)));

let out = parse(rows, links, BASE);
assert.strictEqual(out.ok, true);
assert.strictEqual(out.count, 2);
assert.deepStrictEqual(out.items, [
  { where: '🪨 Materials · row 5', date: 'Oct 1, 2026', amount: '₱1,000.00', what: '💵 Awaiting payment · Sample Client · Sand', link: BASE + '#gid=42&range=A5' },
  { where: '🤝 Funds · row 3', date: 'Oct 2, 2026', amount: '₱250.50', what: '⏳ To return · Sample Hauler', link: 'https://example.com/x' },
]);

// One line, stated as "1 item"; no links at all.
out = parse([rows[2].map((v, c) => (c === 0 ? '🔔 TO-DO · 1 item needs your attention' : v)), rows[3], rows[4], pad([])], null, BASE);
assert.strictEqual(out.count, 1);
assert.strictEqual(out.items.length, 1);
assert.strictEqual(out.items[0].link, null);

// An empty list: the title says so and there is no header row.
out = parse([pad(['🔔 TO-DO & NOTIFICATIONS · nothing needs your attention']), rows[6], rows[7]], null, BASE);
assert.deepStrictEqual(out, { ok: true, count: 0, items: [] });

// A header with an empty first row: no lines, count as stated.
out = parse([rows[2], rows[3], pad(['✅ All clear']), rows[6]], null, BASE);
assert.strictEqual(out.items.length, 0);

// No list on the tab: an error, never an empty list (which would tick everything off).
assert.throws(() => gs.parseTodos([rows[0], rows[1], rows[6], rows[7]], null, BASE), /No to-do list/);

// =HYPERLINK() and rich-text links.
assert.strictEqual(gs.cellLink(null, '=HYPERLINK("#gid=1&range=A2", "row 2")'), '#gid=1&range=A2');
assert.strictEqual(gs.cellLink({ getLinkUrl: () => null, getRuns: () => [{ getLinkUrl: () => null }, { getLinkUrl: () => 'https://a' }] }, ''), 'https://a');
assert.strictEqual(gs.absolute('mailto:x@y', BASE), null);

console.log('sheet-sync: all checks passed');
