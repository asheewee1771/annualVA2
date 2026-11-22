// The two things the harness does with an Excel workbook, with what Node already has (an .xlsx file is a zip archive of
// XML parts): it writes the System Register workbook the baseline uploads (the header row of the fixed template and one
// row of text cells per System), and it reads a workbook the app exported, the way the person who receives it reads
// it (cell texts, page headers and footers, sheet by sheet). No zip64, no encryption, no styles.
import fs from "node:fs";
import zlib from "node:zlib";

const HEADERS = ["System ID", "System Name", "SMO/Tech Lead email", "Cyber Manager email", "Quarter"];
const SHEET_NAME = "System Register";

function escapeXml(text) {
  return String(text).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
}

function columnName(index) {
  return String.fromCharCode("A".charCodeAt(0) + index);
}

function sheetXml(rows) {
  const body = rows
    .map((cells, r) => {
      const xml = cells
        .map((value, c) => `<c r="${columnName(c)}${r + 1}" t="inlineStr"><is><t>${escapeXml(value)}</t></is></c>`)
        .join("");
      return `<row r="${r + 1}">${xml}</row>`;
    })
    .join("");
  return (
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>' +
    '<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">' +
    `<sheetData>${body}</sheetData></worksheet>`
  );
}

const PARTS = {
  "[Content_Types].xml":
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>' +
    '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">' +
    '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>' +
    '<Default Extension="xml" ContentType="application/xml"/>' +
    '<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>' +
    '<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>' +
    "</Types>",
  "_rels/.rels":
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>' +
    '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">' +
    '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>' +
    "</Relationships>",
  "xl/workbook.xml":
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>' +
    '<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" ' +
    'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">' +
    `<sheets><sheet name="${SHEET_NAME}" sheetId="1" r:id="rId1"/></sheets></workbook>`,
  "xl/_rels/workbook.xml.rels":
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>' +
    '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">' +
    '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>' +
    "</Relationships>",
};

/** A zip archive of deflated entries, sizes in the local headers (no data descriptors). */
function zip(entries) {
  const locals = [];
  const centrals = [];
  let offset = 0;
  for (const [name, text] of entries) {
    const nameBytes = Buffer.from(name, "utf8");
    const raw = Buffer.from(text, "utf8");
    const packed = zlib.deflateRawSync(raw);
    const crc = zlib.crc32(raw);
    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50, 0);
    local.writeUInt16LE(20, 4);
    local.writeUInt16LE(0x0800, 6);
    local.writeUInt16LE(8, 8);
    local.writeUInt16LE(0, 10);
    local.writeUInt16LE(0x21, 12);
    local.writeUInt32LE(crc, 14);
    local.writeUInt32LE(packed.length, 18);
    local.writeUInt32LE(raw.length, 22);
    local.writeUInt16LE(nameBytes.length, 26);
    local.writeUInt16LE(0, 28);
    const central = Buffer.alloc(46);
    central.writeUInt32LE(0x02014b50, 0);
    central.writeUInt16LE(20, 4);
    central.writeUInt16LE(20, 6);
    central.writeUInt16LE(0x0800, 8);
    central.writeUInt16LE(8, 10);
    central.writeUInt16LE(0, 12);
    central.writeUInt16LE(0x21, 14);
    central.writeUInt32LE(crc, 16);
    central.writeUInt32LE(packed.length, 20);
    central.writeUInt32LE(raw.length, 24);
    central.writeUInt16LE(nameBytes.length, 28);
    central.writeUInt32LE(offset, 42);
    locals.push(local, nameBytes, packed);
    centrals.push(central, nameBytes);
    offset += local.length + nameBytes.length + packed.length;
  }
  const directory = Buffer.concat(centrals);
  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0);
  end.writeUInt16LE(entries.length, 8);
  end.writeUInt16LE(entries.length, 10);
  end.writeUInt32LE(directory.length, 12);
  end.writeUInt32LE(offset, 16);
  return Buffer.concat([...locals, directory, end]);
}

/**
 * @param {{systemId: string, name: string, smoEmail: string, cyberManagerEmail: string, quarter: string}[]} systems
 * @returns {Buffer} the workbook
 */
export function registerWorkbook(systems) {
  const rows = [HEADERS, ...systems.map((s) => [s.systemId, s.name, s.smoEmail, s.cyberManagerEmail, s.quarter])];
  return zip([...Object.entries(PARTS), ["xl/worksheets/sheet1.xml", sheetXml(rows)]]);
}

/** The XML parts of a zip archive, by name. Sizes come from the central directory, so data descriptors do not matter. */
function unzip(bytes) {
  const end = bytes.lastIndexOf(Buffer.from([0x50, 0x4b, 0x05, 0x06]));
  if (end < 0) throw new Error("Not a zip archive, so not an Excel workbook");
  const count = bytes.readUInt16LE(end + 10);
  let at = bytes.readUInt32LE(end + 16);
  const parts = new Map();
  for (let entry = 0; entry < count; entry += 1) {
    const method = bytes.readUInt16LE(at + 10);
    const packedSize = bytes.readUInt32LE(at + 20);
    const nameLength = bytes.readUInt16LE(at + 28);
    const extraLength = bytes.readUInt16LE(at + 30);
    const commentLength = bytes.readUInt16LE(at + 32);
    const local = bytes.readUInt32LE(at + 42);
    const name = bytes.toString("utf8", at + 46, at + 46 + nameLength);
    const dataAt = local + 30 + bytes.readUInt16LE(local + 26) + bytes.readUInt16LE(local + 28);
    const packed = bytes.subarray(dataAt, dataAt + packedSize);
    if (/\.(xml|rels)$/.test(name)) {
      parts.set(name, (method === 0 ? packed : zlib.inflateRawSync(packed)).toString("utf8"));
    }
    at += 46 + nameLength + extraLength + commentLength;
  }
  return parts;
}

function unescapeXml(text) {
  return text
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&quot;/g, '"')
    .replace(/&apos;/g, "'")
    .replace(/&amp;/g, "&");
}

/** The text of every <t> element in a fragment, joined: one string of a cell or of a shared string. */
function textOf(fragment) {
  return [...fragment.matchAll(/<t(?:\s[^>]*)?>([^<]*)<\/t>/g)].map((match) => unescapeXml(match[1])).join("");
}

function attribute(tag, name) {
  return new RegExp(`(?:^|\\s)${name}="([^"]*)"`).exec(tag)?.[1];
}

function element(xml, tag) {
  return unescapeXml(new RegExp(`<${tag}(?:\\s[^>]*)?>([^<]*)</${tag}>`).exec(xml)?.[1] ?? "");
}

/**
 * One sheet of a workbook as its reader sees it.
 * @typedef {object} SheetWords
 * @property {string} name the sheet's tab name
 * @property {string[]} firstRow the texts of the cells of row 1, left to right
 * @property {string[]} cells the text of every cell that has one, row by row
 * @property {string} header the page header as Excel stores it (a leading &C means centred)
 * @property {string} footer the page footer, likewise
 */

/**
 * Reads a workbook file sheet by sheet.
 * @param {string} file
 * @returns {{ sheets: SheetWords[] }}
 */
export function readWorkbook(file) {
  const parts = unzip(fs.readFileSync(file));
  const shared = [...(parts.get("xl/sharedStrings.xml") ?? "").matchAll(/<si>([\s\S]*?)<\/si>/g)].map((match) =>
    textOf(match[1]),
  );
  const targets = new Map(
    [...(parts.get("xl/_rels/workbook.xml.rels") ?? "").matchAll(/<Relationship\s([^>]*)>/g)].map((match) => [
      attribute(match[1], "Id"),
      attribute(match[1], "Target"),
    ]),
  );
  const sheets = [];
  for (const match of (parts.get("xl/workbook.xml") ?? "").matchAll(/<sheet\s([^>]*)>/g)) {
    const target = targets.get(attribute(match[1], "r:id")) ?? "";
    const xml = parts.get(target.startsWith("/") ? target.slice(1) : `xl/${target}`);
    if (xml === undefined) throw new Error(`The workbook names a sheet whose part is missing: ${target}`);
    const cells = [];
    const firstRow = [];
    for (const row of xml.matchAll(/<row\s([^>]*)>([\s\S]*?)<\/row>/g)) {
      for (const cell of row[2].matchAll(/<c\s([^>]*?)(?:\/>|>([\s\S]*?)<\/c>)/g)) {
        const type = attribute(cell[1], "t");
        const body = cell[2] ?? "";
        const value = element(body, "v");
        const text = type === "s" ? (shared[Number(value)] ?? "") : type === "inlineStr" ? textOf(body) : value;
        if (text === "") continue;
        cells.push(text);
        if (attribute(row[1], "r") === "1") firstRow.push(text);
      }
    }
    sheets.push({
      name: unescapeXml(attribute(match[1], "name") ?? ""),
      firstRow,
      cells,
      header: element(xml, "oddHeader"),
      footer: element(xml, "oddFooter"),
    });
  }
  return { sheets };
}
