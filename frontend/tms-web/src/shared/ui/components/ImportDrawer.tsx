import { useState, type ChangeEvent, type ReactNode } from "react";
import {
  Alert, Box, Button, Chip, Paper, Stack, Table, TableBody, TableCell, TableContainer,
  TableHead, TableRow, Typography,
} from "@mui/material";
import { DownloadRounded, UploadFileRounded, FactCheckRounded, PlaylistAddCheckRounded } from "@mui/icons-material";
import type { ApiError } from "../../api/httpClient";
import { saveDownloadedFile } from "../../api/httpClient";
import {
  applyImport, downloadImportTemplate, previewImport,
  type ImportOutcome, type ImportReport,
} from "../../api/importApi";
import { describeApiError, describeImportError } from "../../api/problemMessages";
import { confirmDialog, notifyError, notifySuccess } from "../../../lib/ui";
import { t } from "../../../lib/i18n";
import { fmtQuantity } from "../../../lib/locale";
import { FormDrawer } from "./FormDrawer";
import { SectionHeader } from "./layout";
import { dataTableSx } from "./tableStyles";

const OUTCOME_LABEL: Record<ImportOutcome, string> = {
  CREATE: "Se creará",
  SKIPPED_DUPLICATE: "Ya existe",
  REJECTED: "Rechazada",
};

const OUTCOME_COLOR: Record<ImportOutcome, "success" | "default" | "error"> = {
  CREATE: "success",
  SKIPPED_DUPLICATE: "default",
  REJECTED: "error",
};

interface ImportDrawerProps<T> {
  open: boolean;
  /** p. ej. `/masterdata/locations/import` — el `@RequestMapping` del controlador. */
  apiBasePath: string;
  companyId: string;
  title: string;
  subtitle: string;
  onClose: () => void;
  /** Se llama una vez tras una importación aplicada, para que la lista de detrás recargue. */
  onImported: () => void;
  /** Pinta la tabla de previsualización específica de la entidad. */
  renderItems: (items: T[], outcomeLabel: (outcome: ImportOutcome) => string) => ReactNode;
  /** Se muestra en la ayuda del campo de fichero. Refleja el `ImportLimits` del backend; aquí
   * no se aplica — el servidor rechaza un fichero grande de todos modos. */
  maxFileMb?: number;
  maxRows?: number;
}

/**
 * La importación masiva de maestros, como tres pasos en un solo drawer: descarga la plantilla,
 * sube una copia rellena, lee qué haría — y solo entonces aplícala.
 *
 * La previsualización no es una comodidad: el backend rechaza de plano un fichero con cualquier
 * fila inválida, así que sin una pasada en seco la única forma que tendría un operador de
 * enterarse de qué está mal sería intentar la importación y leer el rechazo. Aplicar va detrás
 * de una segunda acción explícita y de una confirmación, nunca fundido con la subida: subir es
 * inspeccionar, importar es decidir.
 */
export function ImportDrawer<T>({
  open, apiBasePath, companyId, title, subtitle, onClose, onImported, renderItems,
  maxFileMb = 2, maxRows = 5000,
}: ImportDrawerProps<T>) {
  const [file, setFile] = useState<File | null>(null);
  const [report, setReport] = useState<ImportReport<T> | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState<"preview" | "apply" | null>(null);

  // Un informe aplicado es terminal: el fichero ya está en el sistema y volver a aplicarlo solo
  // produciría un informe de duplicados omitidos. El drawer pasa a vista de resultado.
  const isApplied = report?.applied === true;
  const canValidate = file !== null && busy === null;
  const canApply =
    report !== null && !report.applied && report.issueCount === 0 && report.createdCount > 0 && busy === null;

  /** Cualquier edición invalida el informe: describe el fichero anterior, y mostrarlo junto a
   * otro fichero es la forma de que alguien apruebe una cosa distinta de la que leyó. */
  function pickFile(event: ChangeEvent<HTMLInputElement>) {
    setFile(event.target.files?.[0] ?? null);
    setReport(null);
    setError(null);
  }

  function reset() {
    setFile(null);
    setReport(null);
    setError(null);
  }

  async function template(format: "XLSX" | "CSV") {
    try {
      const downloaded = await downloadImportTemplate(apiBasePath, companyId, format);
      saveDownloadedFile(downloaded, `plantilla.${format.toLowerCase()}`);
    } catch (cause) {
      notifyError(t("No se pudo descargar la plantilla."), describeApiError(cause as ApiError));
    }
  }

  async function validate() {
    if (!file) return;
    setBusy("preview");
    setError(null);
    try {
      setReport(await previewImport<T>(apiBasePath, companyId, file));
    } catch (cause) {
      setReport(null);
      setError(describeImportError(cause as ApiError));
    } finally {
      setBusy(null);
    }
  }

  async function apply() {
    if (!file || !report) return;
    const confirmed = await confirmDialog({
      title: t("¿Aplicar la importación?"),
      text: t("Se crearán {{count}} registros. Esta acción no se puede deshacer.", { count: report.createdCount }),
      confirmLabel: t("Importar"),
    });
    if (!confirmed) return;

    setBusy("apply");
    setError(null);
    try {
      const applied = await applyImport<T>(apiBasePath, companyId, file);
      setReport(applied);
      notifySuccess(
        t("Importación aplicada"),
        t("{{created}} creados, {{skipped}} omitidos.", { created: applied.createdCount, skipped: applied.skippedCount }),
      );
      onImported();
    } catch (cause) {
      setError(describeImportError(cause as ApiError));
    } finally {
      setBusy(null);
    }
  }

  const counter = (label: string, value: number, color?: "success" | "warning" | "error") => (
    <Box sx={{ textAlign: "center", minWidth: 84 }}>
      <Typography sx={{
        fontWeight: 800, fontSize: "1.4rem", lineHeight: 1.1, fontVariantNumeric: "tabular-nums",
        color: color ? `${color}.main` : "text.primary",
      }}>
        {fmtQuantity(value)}
      </Typography>
      <Typography variant="caption" color="text.secondary" sx={{ textTransform: "uppercase", fontWeight: 700, letterSpacing: ".05em" }}>
        {label}
      </Typography>
    </Box>
  );

  return (
    <FormDrawer
      open={open}
      title={title}
      subtitle={subtitle}
      onClose={onClose}
      size="xl"
      icon={<UploadFileRounded />}
      footer={
        <>
          <Button onClick={onClose}>{isApplied ? t("Cerrar") : t("Cancelar")}</Button>
          {!isApplied && (
            <>
              <Button onClick={validate} disabled={!canValidate} variant="outlined" startIcon={<FactCheckRounded />}>
                {busy === "preview" ? t("Validando...") : t("Validar")}
              </Button>
              <Button onClick={apply} disabled={!canApply} variant="contained" startIcon={<PlaylistAddCheckRounded />}>
                {busy === "apply" ? t("Importando...") : t("Importar")}
              </Button>
            </>
          )}
        </>
      }
    >
      <SectionHeader title={t("1. Plantilla")} />
      <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>
        {t("Descarga la plantilla, rellénala y vuelve aquí para subirla.")}
      </Typography>
      <Stack direction="row" spacing={1} sx={{ mb: 3 }}>
        <Button size="small" variant="outlined" startIcon={<DownloadRounded />} onClick={() => template("XLSX")}>
          {t("Descargar .xlsx")}
        </Button>
        <Button size="small" variant="outlined" startIcon={<DownloadRounded />} onClick={() => template("CSV")}>
          {t("Descargar .csv")}
        </Button>
      </Stack>

      <SectionHeader title={t("2. Fichero")} />
      <Button component="label" variant="outlined" startIcon={<UploadFileRounded />} disabled={isApplied} sx={{ mb: 1 }}>
        {file ? file.name : t("Elegir fichero")}
        <input type="file" hidden accept=".xlsx,.csv" onChange={pickFile} />
      </Button>
      <Typography variant="caption" color="text.secondary" sx={{ display: "block", mb: 3 }}>
        {t("Formatos .xlsx y .csv, hasta {{mb}} MB y {{rows}} filas.", { mb: maxFileMb, rows: fmtQuantity(maxRows) })}
      </Typography>

      {error && <Alert severity="error" sx={{ mb: 2 }}>{error}</Alert>}

      {report && (
        <>
          <SectionHeader title={isApplied ? t("Resultado") : t("3. Previsualización")} />

          <Paper variant="outlined" sx={{ p: 2, mb: 2, display: "flex", flexWrap: "wrap", gap: 2, justifyContent: "space-around" }}>
            {counter(t("Filas"), report.rowCount)}
            {counter(t("Registros"), report.itemCount)}
            {counter(t("A crear"), report.createdCount, "success")}
            {counter(t("Duplicados"), report.skippedCount, "warning")}
            {counter(t("Rechazados"), report.rejectedCount, "error")}
            {counter(t("Problemas"), report.issueCount, "error")}
          </Paper>

          {isApplied ? (
            <Alert severity="success" sx={{ mb: 2 }}>
              {t("{{created}} creados, {{skipped}} omitidos.", { created: report.createdCount, skipped: report.skippedCount })}
            </Alert>
          ) : report.issueCount > 0 ? (
            <Alert severity="error" sx={{ mb: 2 }}>
              {t("El fichero tiene problemas. Corrígelos y vuelve a validarlo: no se importará nada mientras quede uno.")}
            </Alert>
          ) : report.createdCount === 0 ? (
            <Alert severity="info" sx={{ mb: 2 }}>{t("No hay nada nuevo que crear en este fichero.")}</Alert>
          ) : (
            <Alert severity="success" sx={{ mb: 2 }}>{t("El fichero es válido. Ya puedes importarlo.")}</Alert>
          )}

          {report.issues.length > 0 && (
            <>
              <SectionHeader title={t("Problemas")} />
              <TableContainer component={Paper} variant="outlined" sx={{ mb: 2, maxHeight: 300 }}>
                <Table size="small" stickyHeader sx={dataTableSx}>
                  <TableHead>
                    <TableRow>
                      <TableCell>{t("Fila")}</TableCell>
                      <TableCell>{t("Columna")}</TableCell>
                      <TableCell>{t("Identificador")}</TableCell>
                      <TableCell>{t("Mensaje")}</TableCell>
                    </TableRow>
                  </TableHead>
                  <TableBody>
                    {report.issues.map((issue, index) => (
                      <TableRow key={`${issue.rowNumber}-${index}`}>
                        <TableCell>{issue.rowNumber}</TableCell>
                        <TableCell>{issue.column ?? "-"}</TableCell>
                        <TableCell>{issue.identifier ?? "-"}</TableCell>
                        <TableCell>{issue.message}</TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </TableContainer>
              {report.issuesTruncated && (
                <Typography variant="caption" color="text.secondary">
                  {t("Se muestran {{shown}} de {{total}} problemas.", { shown: report.issues.length, total: report.issueCount })}
                </Typography>
              )}
            </>
          )}

          {report.items.length > 0 && (
            <>
              <SectionHeader title={t("Registros")} />
              {renderItems(report.items, (outcome) => t(OUTCOME_LABEL[outcome]))}
            </>
          )}

          {!isApplied && (
            <Button size="small" onClick={reset} sx={{ mt: 2 }}>{t("Empezar de nuevo")}</Button>
          )}
        </>
      )}
    </FormDrawer>
  );
}

/** El chip de resultado por fila que comparten todas las tablas de previsualización. */
export function ImportOutcomeChip({ outcome, label }: { outcome: ImportOutcome; label: string }) {
  return <Chip size="small" label={label} color={OUTCOME_COLOR[outcome]} variant={outcome === "CREATE" ? "filled" : "outlined"} />;
}
