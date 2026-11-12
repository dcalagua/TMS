import { buildXlsx, excelFileName, parseCsv, typedCells, XLSX_MIME } from "../../lib/xlsx";
import { saveDownloadedFile, type DownloadedFile } from "./httpClient";

/**
 * Entrega como Excel un reporte que el servidor descargó en CSV: mismas columnas, mismas cifras y
 * el nombre que puso el servidor, con extensión `.xlsx` (ver `lib/xlsx.ts`).
 */
export async function saveCsvAsExcel(file: DownloadedFile, fallbackName: string, sheetName: string): Promise<void> {
  const rows = typedCells(parseCsv(await file.blob.text()));
  const bytes = buildXlsx(rows, sheetName);
  saveDownloadedFile(
    { blob: new Blob([bytes as Uint8Array<ArrayBuffer>], { type: XLSX_MIME }), fileName: file.fileName ? excelFileName(file.fileName) : null },
    excelFileName(fallbackName),
  );
}
