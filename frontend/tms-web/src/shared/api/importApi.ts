import { apiDownload, apiUpload, type DownloadedFile } from "./httpClient";

/**
 * Cliente genérico de todos los endpoints de importación masiva de maestros (Ubicaciones,
 * Transportistas, Tipos de vehículo, Vehículos — la importación de pedidos, en `ordersApi.ts`,
 * es anterior a esto y conserva su propia forma `OrderImportReport`, que estas replican).
 *
 * Todo endpoint de importación comparte la misma forma de tres llamadas: `GET {base}/template`,
 * `POST {base}/preview` (no escribe nada) y `POST {base}` (aplica). `basePath` es lo único que
 * cambia entre entidades, p. ej. `/masterdata/locations/import` o `/fleet/carriers/import`.
 */

/** Refleja el enum `ImportFormat` del backend. */
export type ImportFormat = "XLSX" | "CSV";

/** Refleja `ImportOutcome` — qué decidió la importación sobre una fila. */
export type ImportOutcome = "CREATE" | "SKIPPED_DUPLICATE" | "REJECTED";

/** Refleja `ImportIssue` — un motivo por el que una fila no se puede aceptar. */
export interface ImportIssue {
  rowNumber: number;
  column: string | null;
  identifier: string | null;
  message: string;
}

/**
 * Refleja el `ImportReport<T>` del backend. Una sola forma para la previsualización y para el
 * resultado aplicado, de modo que la tabla que aprueba un operador y la confirmación que
 * recibe sean el mismo render — `applied` es lo único que cambia. `items` es genérico por
 * entidad (p. ej. `LocationImportPreview[]`).
 *
 * Un fichero con problemas vuelve como HTTP 200 con `applied: false` y sin haber escrito nada,
 * así que hay que ramificar por `applied` y no por que la petición haya ido bien.
 */
export interface ImportReport<T> {
  dryRun: boolean;
  applied: boolean;
  batchId: string | null;
  fileName: string | null;
  format: ImportFormat;
  rowCount: number;
  itemCount: number;
  createdCount: number;
  skippedCount: number;
  rejectedCount: number;
  issueCount: number;
  issuesTruncated: boolean;
  items: T[];
  issues: ImportIssue[];
}

export function downloadImportTemplate(
  basePath: string,
  companyId: string,
  format: ImportFormat,
  signal?: AbortSignal,
): Promise<DownloadedFile> {
  return apiDownload(`${basePath}/template`, { companyId, query: { format }, signal });
}

function importForm(file: File): FormData {
  const form = new FormData();
  form.append("file", file);
  return form;
}

/** Valida el fichero e informa de qué haría aplicarlo. No escribe nada. */
export function previewImport<T>(
  basePath: string,
  companyId: string,
  file: File,
  signal?: AbortSignal,
): Promise<ImportReport<T>> {
  return apiUpload<ImportReport<T>>(`${basePath}/preview`, { companyId, formData: importForm(file), signal });
}

/** Aplica el fichero en una transacción, o no escribe absolutamente nada si alguna fila tiene problemas. */
export function applyImport<T>(basePath: string, companyId: string, file: File): Promise<ImportReport<T>> {
  return apiUpload<ImportReport<T>>(basePath, { companyId, formData: importForm(file) });
}
