/**
 * To-Do sync for the FINANCE sheet: a web app that sends the Overview tab's
 * "TO-DO & NOTIFICATIONS" lines to the To-Do app on the phone. It only reads:
 * nothing in the sheet changes, and nothing but those lines is sent.
 *
 * Install once (README "Google Sheet sync"): in the sheet, Extensions > Apps Script,
 * paste this file, Deploy > New deployment > Web app, Execute as: Me,
 * Who has access: Anyone. Paste the /exec link into To-Do > Settings > Google Sheet.
 */

function doGet() {
  var out;
  try {
    out = readTodos(SpreadsheetApp.getActiveSpreadsheet());
  } catch (e) {
    out = { ok: false, error: String(e && e.message || e) };
  }
  return ContentService.createTextOutput(JSON.stringify(out)).setMimeType(ContentService.MimeType.JSON);
}

/** Run this once from the editor to see what the app will get (View > Logs). */
function preview() {
  Logger.log(JSON.stringify(readTodos(SpreadsheetApp.getActiveSpreadsheet()), null, 2));
}

function readTodos(ss) {
  var tab = ss.getSheets().filter(function (s) { return /overview/i.test(s.getName()); })[0];
  if (!tab) throw new Error('No tab named "Overview" in ' + ss.getName());
  var range = tab.getDataRange();
  var values = range.getDisplayValues();
  var rich = range.getRichTextValues();
  var formulas = range.getFormulas();
  var links = values.map(function (row, r) {
    return row.map(function (_, c) { return cellLink(rich[r][c], formulas[r][c]); });
  });
  var list = parseTodos(values, links, ss.getUrl());
  list.sheet = ss.getName();
  list.url = ss.getUrl();
  return list;
}

/** The cell's link: a link in its text, or the first argument of =HYPERLINK(). */
function cellLink(rich, formula) {
  var url = rich && rich.getLinkUrl();
  if (!url && rich && rich.getRuns) {
    rich.getRuns().some(function (run) { url = run.getLinkUrl(); return !!url; });
  }
  if (!url && formula) {
    var m = /^=\s*HYPERLINK\(\s*"([^"]*)"/i.exec(formula);
    if (m) url = m[1];
  }
  return url || null;
}

/**
 * Finds the to-do list in the tab's cells: the "TO-DO" title row, then the header row that
 * starts with "Where", then one line per row until a row has no "Where" or no "What".
 * Merged cells read as blanks except their first cell, so columns are found by their headers.
 * [count] is the number the title states ("3 items need your attention"), null when it states
 * none; the app only ticks off lines that left the sheet when it got that many lines.
 */
function parseTodos(values, links, baseUrl) {
  var header = -1;
  for (var r = 0; r < values.length && header < 0; r++) {
    if (/^where\b/i.test(firstCell(values[r]))) header = r;
  }
  // The list's title is the to-do row nearest above its header: the page's subtitle at the top
  // ("Your to-do list, Maribank, ...") says to-do too. With no header (an empty list), it is the
  // to-do row that states how many lines there are.
  var title = -1;
  for (r = (header < 0 ? values.length : header) - 1; r >= 0 && title < 0; r--) {
    var text = firstCell(values[r]);
    if (/TO-?DO/i.test(text) && (header >= 0 || countOf(text) !== null)) title = r;
  }
  var count = title < 0 ? null : countOf(firstCell(values[title]));
  // An empty list may have no header row at all.
  if (header < 0 && count === 0) return { ok: true, count: 0, items: [] };
  if (header < 0) throw new Error('No to-do list on the Overview tab (no header row starting with "Where")');

  var cols = {};
  values[header].forEach(function (cell, c) {
    var h = clean(cell).toLowerCase();
    if (cols.where === undefined && /^where\b/.test(h)) cols.where = c;
    else if (cols.date === undefined && /^date\b/.test(h)) cols.date = c;
    else if (cols.amount === undefined && /^amount\b/.test(h)) cols.amount = c;
    else if (cols.what === undefined && /^what\b/.test(h)) cols.what = c;
  });
  if (cols.what === undefined) throw new Error('The to-do list has no "What needs doing" column');

  var items = [];
  for (r = header + 1; r < values.length; r++) {
    var row = values[r];
    var where = cell(row, cols.where);
    var what = cell(row, cols.what);
    if (!where || !what) break;
    var link = null;
    [cols.where, cols.what, cols.date, cols.amount].some(function (c) {
      link = c === undefined ? null : links && links[r] && links[r][c];
      return !!link;
    });
    items.push({
      where: where,
      date: cell(row, cols.date),
      amount: cell(row, cols.amount),
      what: what,
      link: absolute(link, baseUrl),
    });
  }
  return { ok: true, count: count, items: items };
}

/** "4 items need your attention" -> 4, "nothing needs your attention" -> 0, else null. */
function countOf(text) {
  var m = /(\d+)\s+items?\b/i.exec(text);
  if (m) return Number(m[1]);
  if (/\b(no|nothing|zero|none)\b|\ball (clear|done)\b/i.test(text)) return 0;
  return null;
}

function firstCell(row) {
  for (var c = 0; c < row.length; c++) if (clean(row[c])) return clean(row[c]);
  return '';
}

function cell(row, c) {
  return c === undefined ? '' : clean(row[c]);
}

/** Trims and folds the sheet's double spaces around " · " into one. */
function clean(s) {
  return String(s == null ? '' : s).replace(/\s+/g, ' ').trim();
}

/** A link inside the sheet ("#gid=…&range=A12") made into a full link to that row. */
function absolute(link, baseUrl) {
  if (!link) return null;
  if (/^https?:\/\//i.test(link)) return link;
  if (link.charAt(0) === '#' && baseUrl) return baseUrl.replace(/#.*$/, '') + link;
  return null;
}
