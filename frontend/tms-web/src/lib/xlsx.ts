/**
 * Un libro de Excel (`.xlsx`) de una hoja, escrito en el navegador y sin dependencias.
 *
 * <h2>Por qué aquí y no en el servidor</h2>
 * Los reportes se descargan del backend en CSV (RFC 4180, coma, BOM, decimales con punto), que es la
 * copia legible por máquina y no cambia. El Excel es la misma tabla para una persona: se arma con
 * ese CSV, así que tiene exactamente las columnas y las cifras del servidor, sin una segunda versión
 * de los cálculos.
 *
 * <h2>Lo que hace, y lo que no</h2>
 * Un `.xlsx` es un ZIP de XML. Este escribe lo mínimo que Excel, LibreOffice y Google Sheets abren
 * sin quejarse: la cabecera en negrita y congelada, el ancho de cada columna según su contenido, y
 * las cifras como números (para poder sumarlas). Los textos van en línea (`inlineStr`), sin tabla
 * de cadenas compartidas, y el ZIP va sin comprimir: un reporte de unas cientos de filas pesa poco y
 * así no hace falta un compresor.
 *
 * Un valor que parece número pero empieza con cero («0990000034001», un RUC) se queda como texto:
 * convertirlo le borraría el cero.
 */

export type CellValue = string | number | null;

/** Un número de verdad: sin ceros a la izquierda, con punto decimal y que Excel no redondea. */
const NUMBER = /^-?(0|[1-9]\d{0,14})(\.\d+)?$/;

/** Lee un CSV RFC 4180: comillas, comillas dobladas, saltos de línea dentro de un campo, CRLF o LF. */
export function parseCsv(text: string): string[][] {
  const source = text.charCodeAt(0) === 0xfeff ? text.slice(1) : text;
  const rows: string[][] = [];
  let row: string[] = [];
  let field = "";
  let quoted = false;
  for (let i = 0; i < source.length; i++) {
    const ch = source[i];
    if (quoted) {
      if (ch === "\"") {
        if (source[i + 1] === "\"") { field += "\""; i++; } else quoted = false;
      } else field += ch;
    } else if (ch === "\"") {
      quoted = true;
    } else if (ch === ",") {
      row.push(field); field = "";
    } else if (ch === "\n" || ch === "\r") {
      if (ch === "\r" && source[i + 1] === "\n") i++;
      row.push(field); rows.push(row); row = []; field = "";
    } else field += ch;
  }
  if (field !== "" || row.length > 0) { row.push(field); rows.push(row); }
  // El salto de línea final no es una fila vacía.
  while (rows.length > 0 && rows[rows.length - 1].every((cell) => cell === "")) rows.pop();
  return rows;
}

/** Las celdas de texto que son cifras, como números; el resto, igual. */
export function typedCells(rows: string[][]): CellValue[][] {
  return rows.map((row, index) => row.map((cell) => {
    if (index === 0 || cell === "") return cell;
    return NUMBER.test(cell) ? Number(cell) : cell;
  }));
}

// --- XML -----------------------------------------------------------------------------------

const XML_HEAD = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n";

function escapeXml(value: string): string {
  return value
    // Caracteres de control que XML 1.0 no admite: Excel declara el libro dañado si aparecen.
    // eslint-disable-next-line no-control-regex
    .replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F]/g, "")
    .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
}

/** A, B, …, Z, AA, AB… */
export function columnName(index: number): string {
  let n = index + 1;
  let name = "";
  while (n > 0) {
    const rest = (n - 1) % 26;
    name = String.fromCharCode(65 + rest) + name;
    n = Math.floor((n - 1) / 26);
  }
  return name;
}

/** Excel no admite en el nombre de una hoja `[]:*?/\` ni más de 31 caracteres. */
function sheetTitle(name: string): string {
  const clean = name.replace(/[[\]:*?/\\]/g, " ").trim().slice(0, 31);
  return clean || "Hoja1";
}

function sheetXml(rows: CellValue[][]): string {
  const widths: number[] = [];
  rows.forEach((row) => row.forEach((cell, col) => {
    const length = cell === null ? 0 : String(cell).length;
    widths[col] = Math.max(widths[col] ?? 0, length);
  }));
  const cols = widths.map((width, col) => {
    const chars = Math.min(Math.max(width + 2, 8), 60);
    return `<col min="${col + 1}" max="${col + 1}" width="${chars}" customWidth="1"/>`;
  }).join("");

  const body = rows.map((row, r) => {
    const cells = row.map((cell, c) => {
      const ref = `${columnName(c)}${r + 1}`;
      const style = r === 0 ? " s=\"1\"" : "";
      if (cell === null || cell === "") return `<c r="${ref}"${style}/>`;
      if (typeof cell === "number" && Number.isFinite(cell)) return `<c r="${ref}"${style}><v>${cell}</v></c>`;
      const text = String(cell);
      const space = /^\s|\s$/.test(text) ? " xml:space=\"preserve\"" : "";
      return `<c r="${ref}"${style} t="inlineStr"><is><t${space}>${escapeXml(text)}</t></is></c>`;
    }).join("");
    return `<row r="${r + 1}">${cells}</row>`;
  }).join("");

  const frozen = rows.length > 1
    ? "<sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews>"
    : "";
  return `${XML_HEAD}<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">${frozen}`
    + `${cols ? `<cols>${cols}</cols>` : ""}<sheetData>${body}</sheetData></worksheet>`;
}

const CONTENT_TYPES = `${XML_HEAD}<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">`
  + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
  + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
  + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
  + "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
  + "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>"
  + "</Types>";

const ROOT_RELS = `${XML_HEAD}<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">`
  + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
  + "</Relationships>";

const WORKBOOK_RELS = `${XML_HEAD}<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">`
  + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>"
  + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>"
  + "</Relationships>";

/** Dos estilos de celda: el normal y la cabecera en negrita. */
const STYLES = `${XML_HEAD}<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">`
  + "<fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font><font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts>"
  + "<fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill></fills>"
  + "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>"
  + "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"
  + "<cellXfs count=\"2\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>"
  + "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/></cellXfs>"
  + "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>"
  + "</styleSheet>";

function workbookXml(sheetName: string): string {
  return `${XML_HEAD}<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" `
    + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
    + `<sheets><sheet name="${escapeXml(sheetTitle(sheetName))}" sheetId="1" r:id="rId1"/></sheets></workbook>`;
}

// --- ZIP -----------------------------------------------------------------------------------

const CRC_TABLE = (() => {
  const table = new Uint32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    table[n] = c >>> 0;
  }
  return table;
})();

export function crc32(bytes: Uint8Array): number {
  let crc = 0xffffffff;
  for (let i = 0; i < bytes.length; i++) crc = CRC_TABLE[(crc ^ bytes[i]) & 0xff] ^ (crc >>> 8);
  return (crc ^ 0xffffffff) >>> 0;
}

/** Un ZIP sin compresión (método 0): cabecera local + datos por archivo, el directorio y su fin. */
function zip(files: Array<{ name: string; data: Uint8Array }>): Uint8Array {
  const encoder = new TextEncoder();
  const locals: Uint8Array[] = [];
  const centrals: Uint8Array[] = [];
  let offset = 0;

  for (const file of files) {
    const name = encoder.encode(file.name);
    const crc = crc32(file.data);
    const size = file.data.length;

    const local = new Uint8Array(30 + name.length);
    const lv = new DataView(local.buffer);
    lv.setUint32(0, 0x04034b50, true);
    lv.setUint16(4, 20, true);       // versión necesaria
    lv.setUint16(8, 0, true);        // sin compresión
    lv.setUint16(12, 0x21, true);    // fecha DOS: 1/1/1980
    lv.setUint32(14, crc, true);
    lv.setUint32(18, size, true);
    lv.setUint32(22, size, true);
    lv.setUint16(26, name.length, true);
    local.set(name, 30);

    const central = new Uint8Array(46 + name.length);
    const cv = new DataView(central.buffer);
    cv.setUint32(0, 0x02014b50, true);
    cv.setUint16(4, 20, true);
    cv.setUint16(6, 20, true);
    cv.setUint16(14, 0x21, true);
    cv.setUint32(16, crc, true);
    cv.setUint32(20, size, true);
    cv.setUint32(24, size, true);
    cv.setUint16(28, name.length, true);
    cv.setUint32(42, offset, true);
    central.set(name, 46);

    locals.push(local, file.data);
    centrals.push(central);
    offset += local.length + size;
  }

  const centralSize = centrals.reduce((sum, part) => sum + part.length, 0);
  const end = new Uint8Array(22);
  const ev = new DataView(end.buffer);
  ev.setUint32(0, 0x06054b50, true);
  ev.setUint16(8, files.length, true);
  ev.setUint16(10, files.length, true);
  ev.setUint32(12, centralSize, true);
  ev.setUint32(16, offset, true);

  const parts = [...locals, ...centrals, end];
  const out = new Uint8Array(parts.reduce((sum, part) => sum + part.length, 0));
  let at = 0;
  for (const part of parts) { out.set(part, at); at += part.length; }
  return out;
}

/** Los bytes de un `.xlsx` con una hoja: la primera fila es la cabecera. */
export function buildXlsx(rows: CellValue[][], sheetName: string): Uint8Array {
  const encoder = new TextEncoder();
  return zip([
    { name: "[Content_Types].xml", data: encoder.encode(CONTENT_TYPES) },
    { name: "_rels/.rels", data: encoder.encode(ROOT_RELS) },
    { name: "xl/workbook.xml", data: encoder.encode(workbookXml(sheetName)) },
    { name: "xl/_rels/workbook.xml.rels", data: encoder.encode(WORKBOOK_RELS) },
    { name: "xl/styles.xml", data: encoder.encode(STYLES) },
    { name: "xl/worksheets/sheet1.xml", data: encoder.encode(sheetXml(rows)) },
  ]);
}

export const XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

/** El nombre del CSV con extensión de Excel: `tms-kpis-….csv` → `tms-kpis-….xlsx`. */
export function excelFileName(name: string): string {
  return /\.csv$/i.test(name) ? name.replace(/\.csv$/i, ".xlsx") : `${name}.xlsx`;
}
