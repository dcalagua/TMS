import { useState, type ChangeEvent } from "react";
import {
  Alert, Box, Button, Paper, Stack, Table, TableBody, TableCell, TableContainer,
  TableHead, TableRow, TextField, Typography,
} from "@mui/material";
import {
  DownloadRounded, UploadFileRounded, FactCheckRounded, PlaylistAddCheckRounded,
} from "@mui/icons-material";
import type { ApiError } from "../../shared/api/httpClient";
import { saveDownloadedFile } from "../../shared/api/httpClient";
import {
  applyOrderImport, downloadOrderImportTemplate, previewOrderImport,
  type OrderImportOutcome, type OrderImportReport,
} from "../../shared/api/ordersApi";
import { describeApiError, describeImportError } from "../../shared/api/problemMessages";
import { FormDrawer, ImportOutcomeChip, dataTableSx } from "../../shared/ui/components";
import { DetailSection, KeyFacts } from "../../shared/ui/components/DetailLayout";
import { confirmDialog, notifyError, notifySuccess } from "../../lib/ui";
import { enumLabel } from "../../lib/enums";
import { t } from "../../lib/i18n";
import { fmtDate, fmtDecimal, fmtQuantity, fmtVolumeM3, fmtWeightKg } from "../../lib/locale";

const OUTCOME_LABEL: Record<OrderImportOutcome, string> = {
  CREATE: "Se creará",
  SKIPPED_DUPLICATE: "Ya existe",
  REJECTED: "Rechazada",
};

interface OrderImportDrawerProps {
  companyId: string;
  onClose: () => void;
  onImported: () => void;
}

/**
 * La importación masiva de pedidos: descarga la plantilla, di de qué sistema viene el fichero,
 * súbelo, lee qué haría — y solo entonces aplícalo.
 *
 * Tiene su propio drawer y no el genérico de maestros por un motivo de contrato: este endpoint
 * exige además un `externalSource`, porque un pedido importado se deduplica por el par
 * (sistema, referencia externa) y sin el sistema esa clave no existe. Su informe también es
 * distinto: agrupa por pedido, no por fila, ya que un pedido son varias filas del fichero.
 *
 * La previsualización no es una comodidad: el backend rechaza de plano un fichero con cualquier
 * fila inválida, así que sin una pasada en seco la única forma de enterarse de qué está mal
 * sería intentar la importación y leer el rechazo.
 */
export function OrderImportDrawer({ companyId, onClose, onImported }: OrderImportDrawerProps) {
  const [externalSource, setExternalSource] = useState("");
  const [file, setFile] = useState<File | null>(null);
  const [report, setReport] = useState<OrderImportReport | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState<"preview" | "apply" | null>(null);

  const isApplied = report?.applied === true;
  const canValidate = file !== null && externalSource.trim() !== "" && busy === null;
  const canApply =
    report !== null && !report.applied && report.issueCount === 0 && report.createdCount > 0 && busy === null;

  /** Cualquier edición invalida el informe: describe el fichero anterior, y enseñarlo junto a
   * otro fichero es la forma de que alguien apruebe algo distinto de lo que leyó. */
  function pickFile(event: ChangeEvent<HTMLInputElement>) {
    setFile(event.target.files?.[0] ?? null);
    setReport(null);
    setError(null);
  }

  async function template(format: "XLSX" | "CSV") {
    try {
      const downloaded = await downloadOrderImportTemplate(companyId, format);
      saveDownloadedFile(downloaded, `pedidos.${format.toLowerCase()}`);
    } catch (cause) {
      notifyError(t("No se pudo descargar la plantilla."), describeApiError(cause as ApiError));
    }
  }

  async function validate() {
    if (!file) return;
    setBusy("preview");
    setError(null);
    try {
      setReport(await previewOrderImport(companyId, externalSource.trim(), file));
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
      const applied = await applyOrderImport(companyId, externalSource.trim(), file);
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

  const counter = (value: number, color?: "success" | "warning" | "error") => (
    <Typography component="span" sx={{
      fontWeight: 800, fontSize: "1.25rem", lineHeight: 1.2, fontVariantNumeric: "tabular-nums",
      color: color && value > 0 ? `${color}.main` : "text.primary",
    }}>
      {fmtQuantity(value)}
    </Typography>
  );

  return (
    <FormDrawer
      open
      icon={<UploadFileRounded />}
      title={t("Importar pedidos")}
      subtitle={t("Alta masiva desde una plantilla .xlsx o .csv.")}
      size="xl"
      onClose={onClose}
      footer={
        <>
          <Button color="inherit" sx={{ color: "text.secondary" }} onClick={onClose}>{isApplied ? t("Cerrar") : t("Cancelar")}</Button>
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
      <Box sx={{ display: "grid", gap: 2.5 }}>
        <DetailSection title={t("1. Plantilla")}>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>
            {t("Descarga la plantilla, rellénala y vuelve aquí para subirla.")}
          </Typography>
          <Stack direction="row" spacing={1}>
            <Button size="small" variant="outlined" startIcon={<DownloadRounded />} onClick={() => template("XLSX")}>
              {t("Descargar .xlsx")}
            </Button>
            <Button size="small" variant="outlined" startIcon={<DownloadRounded />} onClick={() => template("CSV")}>
              {t("Descargar .csv")}
            </Button>
          </Stack>
        </DetailSection>

        <DetailSection title={t("2. Origen y fichero")}>
          <Box sx={{ display: "grid", gap: 1.5, gridTemplateColumns: { xs: "1fr", sm: "minmax(0, 360px) minmax(0, 1fr)" }, alignItems: "start" }}>
            {/* El sistema de origen es obligatorio: la deduplicación de un pedido importado es el par
                (sistema, referencia externa), y sin la primera mitad esa clave no existe. */}
            <TextField
              size="small" fullWidth label={t("Sistema de origen")} required
              placeholder={t("p. ej. EWM, ERP")}
              value={externalSource}
              onChange={(e) => { setExternalSource(e.target.value); setReport(null); }}
              disabled={isApplied}
            />
            <Box>
              <Button component="label" variant="outlined" startIcon={<UploadFileRounded />} disabled={isApplied}
                fullWidth sx={{ justifyContent: "flex-start", borderStyle: "dashed", py: 0.75 }}>
                {file ? file.name : t("Elegir fichero")}
                <input type="file" hidden accept=".xlsx,.csv" onChange={pickFile} />
              </Button>
              <Typography variant="caption" color="text.secondary" sx={{ display: "block", mt: 0.5 }}>
                {t("Formatos .xlsx y .csv, hasta {{mb}} MB y {{rows}} filas.", { mb: 2, rows: fmtQuantity(5000) })}
              </Typography>
            </Box>
          </Box>
        </DetailSection>

        {error && <Alert severity="error">{error}</Alert>}

        {report && (
          <DetailSection title={isApplied ? t("Resultado") : t("3. Previsualización")}>
            <Box sx={{ display: "grid", gap: 1.5 }}>
              {isApplied ? (
                <Alert severity="success">
                  {t("{{created}} creados, {{skipped}} omitidos.", { created: report.createdCount, skipped: report.skippedCount })}
                </Alert>
              ) : report.issueCount > 0 ? (
                <Alert severity="error">
                  {t("El fichero tiene problemas. Corrígelos y vuelve a validarlo: no se importará nada mientras quede uno.")}
                </Alert>
              ) : report.createdCount === 0 ? (
                <Alert severity="info">{t("No hay nada nuevo que crear en este fichero.")}</Alert>
              ) : (
                <Alert severity="success">{t("El fichero es válido. Ya puedes importarlo.")}</Alert>
              )}

              <KeyFacts columns={3} items={[
                { label: t("Filas"), value: counter(report.rowCount) },
                { label: t("Pedidos"), value: counter(report.orderCount) },
                { label: t("A crear"), value: counter(report.createdCount, "success") },
                { label: t("Duplicados"), value: counter(report.skippedCount, "warning") },
                { label: t("Rechazados"), value: counter(report.rejectedCount, "error") },
                { label: t("Problemas"), value: counter(report.issueCount, "error") },
              ]} />
            </Box>
          </DetailSection>
        )}

        {report && report.issues.length > 0 && (
          <DetailSection title={t("Problemas")}>
            <TableContainer component={Paper} variant="outlined" sx={{ maxHeight: 300 }}>
              <Table size="small" stickyHeader sx={dataTableSx}>
                <TableHead>
                  <TableRow>
                    <TableCell>{t("Fila")}</TableCell>
                    <TableCell>{t("Columna")}</TableCell>
                    <TableCell>{t("Referencia externa")}</TableCell>
                    <TableCell>{t("Mensaje")}</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {report.issues.map((issue, index) => (
                    <TableRow key={`${issue.rowNumber}-${index}`}>
                      <TableCell>{issue.rowNumber}</TableCell>
                      <TableCell>{issue.column ?? "-"}</TableCell>
                      <TableCell>{issue.externalReference ?? "-"}</TableCell>
                      <TableCell>{issue.message}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </TableContainer>
            {report.issuesTruncated && (
              <Typography variant="caption" color="text.secondary" sx={{ display: "block", mt: 0.75 }}>
                {t("Se muestran {{shown}} de {{total}} problemas.", { shown: report.issues.length, total: report.issueCount })}
              </Typography>
            )}
          </DetailSection>
        )}

        {report && report.orders.length > 0 && (
          <DetailSection title={t("Pedidos")}>
            <TableContainer component={Paper} variant="outlined" sx={{ maxHeight: 360 }}>
              <Table size="small" stickyHeader sx={dataTableSx}>
                <TableHead>
                  <TableRow>
                    <TableCell>{t("Estado")}</TableCell>
                    <TableCell>{t("Referencia externa")}</TableCell>
                    <TableCell>{t("Origen")}</TableCell>
                    <TableCell>{t("Destino")}</TableCell>
                    <TableCell>{t("Fecha de servicio")}</TableCell>
                    <TableCell>{t("Prioridad")}</TableCell>
                    <TableCell className="numeric-col">{t("Líneas")}</TableCell>
                    <TableCell className="numeric-col">{t("Peso")}</TableCell>
                    <TableCell className="numeric-col">{t("Volumen")}</TableCell>
                    <TableCell className="numeric-col">{t("Pallets")}</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {report.orders.map((item, index) => (
                    <TableRow key={`${item.externalReference}-${index}`}>
                      <TableCell>
                        <ImportOutcomeChip outcome={item.outcome} label={t(OUTCOME_LABEL[item.outcome])} />
                      </TableCell>
                      <TableCell sx={{ fontWeight: 700 }}>
                        {item.orderNumber ?? item.externalReference}
                      </TableCell>
                      <TableCell>{item.originName ?? item.originCode ?? "-"}</TableCell>
                      <TableCell>{item.destinationName ?? item.destinationCode ?? "-"}</TableCell>
                      <TableCell>{item.serviceDate ? fmtDate(item.serviceDate) : "-"}</TableCell>
                      <TableCell>{enumLabel("orderPriority", item.priority)}</TableCell>
                      <TableCell className="numeric-col">{fmtQuantity(item.lineCount)}</TableCell>
                      <TableCell className="numeric-col">{item.totalWeightKg === null ? "-" : fmtWeightKg(item.totalWeightKg)}</TableCell>
                      <TableCell className="numeric-col">{item.totalVolumeM3 === null ? "-" : fmtVolumeM3(item.totalVolumeM3)}</TableCell>
                      <TableCell className="numeric-col">{item.totalPallets === null ? "-" : fmtDecimal(item.totalPallets)}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </TableContainer>
          </DetailSection>
        )}
      </Box>
    </FormDrawer>
  );
}
