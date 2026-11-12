import { describe, expect, it } from "vitest";
import { buildXlsx, columnName, crc32, excelFileName, parseCsv, typedCells } from "./xlsx";

/** Los archivos de un ZIP sin compresión, leídos por su directorio central. */
function unzip(bytes: Uint8Array): Record<string, string> {
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const end = bytes.length - 22;
  expect(view.getUint32(end, true)).toBe(0x06054b50);
  const count = view.getUint16(end + 10, true);
  let at = view.getUint32(end + 16, true);
  const decoder = new TextDecoder();
  const files: Record<string, string> = {};
  for (let i = 0; i < count; i++) {
    expect(view.getUint32(at, true)).toBe(0x02014b50);
    const size = view.getUint32(at + 20, true);
    const nameLength = view.getUint16(at + 28, true);
    const local = view.getUint32(at + 42, true);
    const name = decoder.decode(bytes.subarray(at + 46, at + 46 + nameLength));
    const localNameLength = view.getUint16(local + 26, true);
    const data = bytes.subarray(local + 30 + localNameLength, local + 30 + localNameLength + size);
    expect(crc32(data)).toBe(view.getUint32(at + 16, true));
    files[name] = decoder.decode(data);
    at += 46 + nameLength;
  }
  return files;
}

describe("xlsx", () => {
  it("computes the standard CRC-32", () => {
    expect(crc32(new TextEncoder().encode("123456789"))).toBe(0xcbf43926);
  });

  it("reads RFC 4180 CSV with a BOM, quotes and CRLF", () => {
    const csv = "﻿fecha,cliente,peso\r\n2026-11-12,\"La Huerta, Surco\",12.5\r\n2026-11-13,\"Dijo \"\"no\"\"\",\r\n";
    expect(parseCsv(csv)).toEqual([
      ["fecha", "cliente", "peso"],
      ["2026-11-12", "La Huerta, Surco", "12.5"],
      ["2026-11-13", "Dijo \"no\"", ""],
    ]);
  });

  it("turns figures into numbers and keeps codes with leading zeros as text", () => {
    expect(typedCells([["a", "b", "c", "d"], ["12", "-3.25", "0990000034001", "0.5"]]))
      .toEqual([["a", "b", "c", "d"], [12, -3.25, "0990000034001", 0.5]]);
  });

  it("names columns past Z", () => {
    expect([columnName(0), columnName(25), columnName(26), columnName(701), columnName(702)])
      .toEqual(["A", "Z", "AA", "ZZ", "AAA"]);
  });

  it("writes a workbook whose sheet holds the cells", () => {
    const files = unzip(buildXlsx([["Cliente", "Costo"], ["Bodega <Rosa> & Cía", 120.5]], "Costo: por cliente"));
    expect(Object.keys(files)).toEqual([
      "[Content_Types].xml", "_rels/.rels", "xl/workbook.xml", "xl/_rels/workbook.xml.rels",
      "xl/styles.xml", "xl/worksheets/sheet1.xml",
    ]);
    expect(files["xl/workbook.xml"]).toContain("name=\"Costo  por cliente\"");
    const sheet = files["xl/worksheets/sheet1.xml"];
    expect(sheet).toContain("<c r=\"A1\" s=\"1\" t=\"inlineStr\"><is><t>Cliente</t></is></c>");
    expect(sheet).toContain("<t>Bodega &lt;Rosa&gt; &amp; Cía</t>");
    expect(sheet).toContain("<c r=\"B2\"><v>120.5</v></c>");
    expect(sheet).toContain("state=\"frozen\"");
  });

  it("renames the server's CSV", () => {
    expect(excelFileName("tms-kpis-2026-10-14-to-2026-11-12.csv")).toBe("tms-kpis-2026-10-14-to-2026-11-12.xlsx");
    expect(excelFileName("kpis")).toBe("kpis.xlsx");
  });
});
