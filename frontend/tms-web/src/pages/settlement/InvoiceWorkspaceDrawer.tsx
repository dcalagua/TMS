import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import {
  Alert, Box, Button, Divider, Paper, Table, TableBody, TableCell, TableHead, TableRow,
  TextField, Typography,
} from "@mui/material";
import { ReceiptLongRounded } from "@mui/icons-material";
import {
  approveInvoice, beginInvoiceReview, exportInvoice, fetchInvoice, matchInvoice, rejectInvoice,
  resolveDiscrepancy,
  type FreightDiscrepancyView, type InvoiceLineView,
} from "../../shared/api/settlementApi";
import type { ApiError } from "../../shared/api/httpClient";
import { describeApiError } from "../../shared/api/problemMessages";
import { FormDrawer, SectionHeader, StatusChip } from "../../shared/ui/components";
import { INVOICE_STATUS_TONE, MATCH_STATUS_TONE } from "../../shared/ui/statusTones";
import { confirmDialog, notifyError, notifySuccess } from "../../lib/ui";
import { enumLabel } from "../../lib/enums";
import { t } from "../../lib/i18n";
import { fmtDate, fmtDateTime, fmtDecimal } from "../../lib/locale";

interface InvoiceWorkspaceDrawerProps {
  companyId: string;
  invoiceId: string;
  onClose: () => void;
  onChanged: () => void;
}

/**
 * Una factura de transportista, con todo lo necesario para responder **por qué** (migración V46).
 *
 * <h2>La regla de esta pantalla</h2>
 * **El razonamiento no se esconde detrás de un estado.** Una pantalla que mostrara "CON DIFERENCIAS"
 * y nada más obligaría a reconstruir la comparación a mano — que es exactamente el trabajo que este
 * módulo existe para quitar. Así que cada línea lleva lo que TMS esperaba al lado de lo facturado,
 * y cada diferencia lleva la frase que la explica.
 *
 * <h2>Null se pinta como hueco, nunca como cero</h2>
 * Un envío que nunca se tarificó no tiene importe esperado. Se muestra un guion. Pintar 0,00 diría
 * que el transportista cobró de más el importe entero, y sería una cifra inventada con aspecto de
 * medición.
 */
export function InvoiceWorkspaceDrawer({
  companyId, invoiceId, onClose, onChanged,
}: InvoiceWorkspaceDrawerProps) {
  const [busy, setBusy] = useState(false);
  const [rejectReason, setRejectReason] = useState("");

  const invoiceQuery = useQuery({
    queryKey: ["settlement-invoice", companyId, invoiceId],
    queryFn: ({ signal }) => fetchInvoice(companyId, invoiceId, signal),
  });

  const invoice = invoiceQuery.data;

  async function run(action: () => Promise<unknown>, success: string) {
    setBusy(true);
    try {
      await action();
      notifySuccess(success);
      await invoiceQuery.refetch();
      onChanged();
    } catch (error) {
      // El servidor nombra la razón - la diferencia sin resolver, el estado que no permite el
      // movimiento. Traducirlo a "no se pudo" tiraría justo la parte que dice qué hacer.
      notifyError(t("No se pudo completar"), describeApiError(error as ApiError));
    } finally {
      setBusy(false);
    }
  }

  /** Un importe que puede no existir. El guion es la respuesta honesta. */
  function amount(value: number | null | undefined, currency: string) {
    return value === null || value === undefined ? "—" : `${fmtDecimal(value, 2)} ${currency}`;
  }

  function difference(value: number | null | undefined) {
    if (value === null || value === undefined) return "—";
    return `${value > 0 ? "+" : ""}${fmtDecimal(value, 2)}`;
  }

  const openDifferences = invoice?.discrepancies.filter((d) => d.status === "OPEN") ?? [];
  const canApprove = Boolean(invoice?.allowedTransitions.includes("APPROVED")) && openDifferences.length === 0;

  return (
    <FormDrawer
      open
      title={invoice ? `${t("Factura")} ${invoice.invoiceNumber}` : t("Factura")}
      subtitle={invoice?.carrierName ?? undefined}
      icon={<ReceiptLongRounded />}
      onClose={onClose}
      size="lg"
      footer={<Button onClick={onClose} disabled={busy}>{t("Cerrar")}</Button>}
    >
      {invoiceQuery.isLoading || !invoice ? (
        <Typography variant="body2" color="text.secondary">{t("Cargando...")}</Typography>
      ) : (
        <Box sx={{ display: "grid", gap: 3 }}>
          {/* --- cabecera --- */}
          <Box sx={{ display: "flex", gap: 1, alignItems: "center", flexWrap: "wrap" }}>
            <StatusChip
              label={enumLabel("invoiceStatus", invoice.status)}
              tone={INVOICE_STATUS_TONE[invoice.status]}
              variant="solid"
            />
            {invoice.match && (
              <StatusChip
                label={enumLabel("matchStatus", invoice.match.status)}
                tone={MATCH_STATUS_TONE[invoice.match.status]}
              />
            )}
            <Box sx={{ flex: 1 }} />
            <Typography variant="caption" color="text.secondary">
              {t("Recibida")}: {fmtDate(invoice.invoiceDate)}
            </Typography>
          </Box>

          {/* --- la comparación, que es la razón de existir de la pantalla --- */}
          <Box>
            <SectionHeader title={t("La comparación")} />
            {invoice.match === null ? (
              <Alert severity="info" variant="outlined">
                {t("Todavía no se ha comparado. Pulsa Comparar para enfrentarla con lo que TMS esperaba.")}
              </Alert>
            ) : (
              <>
                {/* UNMATCHABLE no es un problema con la factura: TMS no tiene con qué compararla. */}
                {invoice.match.status === "UNMATCHABLE" && (
                  <Alert severity="info" variant="outlined" sx={{ mb: 1.5 }}>
                    {t("Ningún envío de esta factura tiene coste estimado, así que TMS no tiene con qué compararla. No es un sobrecoste: es que no hay opinión que dar.")}
                  </Alert>
                )}
                <Paper variant="outlined" sx={{ p: 2, display: "flex", gap: 4, flexWrap: "wrap" }}>
                  <Figure label={t("Esperado")} value={amount(invoice.match.expectedAmount, invoice.currency)} />
                  <Figure label={t("Coste real")} value={amount(invoice.match.actualAmount, invoice.currency)} />
                  <Figure label={t("Facturado")} value={amount(invoice.match.invoicedAmount, invoice.currency)} />
                  <Figure label={t("Diferencia")} value={difference(invoice.match.differenceAmount)} strong />
                </Paper>
                <Typography variant="caption" color="text.secondary" sx={{ display: "block", mt: 0.75 }}>
                  {/* La tolerancia congelada: ampliarla mañana no reescribe por qué esta cuadró. */}
                  {invoice.match.tolerancePercentage !== null || invoice.match.toleranceAbsolute !== null
                    ? t("Comparada con una tolerancia de {{tolerance}}, congelada el {{when}}.", {
                        tolerance: [
                          invoice.match.toleranceAbsolute !== null ? `${fmtDecimal(invoice.match.toleranceAbsolute, 2)} ${invoice.currency}` : null,
                          invoice.match.tolerancePercentage !== null ? `${invoice.match.tolerancePercentage}%` : null,
                        ].filter(Boolean).join(" o "),
                        when: fmtDateTime(invoice.match.computedAt),
                      })
                    : t("Sin tolerancia configurada: cualquier diferencia se reporta.")}
                </Typography>
              </>
            )}
          </Box>

          {/* --- las líneas, cada una con lo esperado al lado --- */}
          <Box>
            <SectionHeader title={t("Líneas")} />
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>{t("Concepto")}</TableCell>
                  <TableCell>{t("Envío")}</TableCell>
                  <TableCell align="right">{t("Esperado")}</TableCell>
                  <TableCell align="right">{t("Facturado")}</TableCell>
                  <TableCell align="right">{t("Diferencia")}</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {invoice.lines.map((line: InvoiceLineView) => (
                  <TableRow key={line.id} hover>
                    <TableCell>{line.description}</TableCell>
                    <TableCell>
                      {line.shipmentNumber ?? (
                        <Typography variant="caption" color="text.secondary">
                          {t("Sin envío")}
                        </Typography>
                      )}
                    </TableCell>
                    <TableCell align="right">{amount(line.expectedAmount, invoice.currency)}</TableCell>
                    <TableCell align="right">{fmtDecimal(line.lineAmount, 2)}</TableCell>
                    <TableCell align="right">{difference(line.differenceAmount)}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </Box>

          {/* --- las diferencias, cada una con su frase --- */}
          {invoice.discrepancies.length > 0 && (
            <Box>
              <SectionHeader title={t("Diferencias")} />
              <Box sx={{ display: "grid", gap: 1 }}>
                {invoice.discrepancies.map((discrepancy: FreightDiscrepancyView) => (
                  <Paper
                    key={discrepancy.id}
                    variant="outlined"
                    sx={{
                      p: 1.5, borderLeft: "3px solid",
                      borderLeftColor: discrepancy.status === "OPEN" ? "warning.main" : "divider",
                    }}
                  >
                    <Box sx={{ display: "flex", alignItems: "center", gap: 1, flexWrap: "wrap" }}>
                      <Typography variant="body2" sx={{ fontWeight: 700 }}>
                        {enumLabel("discrepancyType", discrepancy.type)}
                      </Typography>
                      {discrepancy.status !== "OPEN" && (
                        <Typography variant="caption" color="text.secondary">
                          {discrepancy.status === "ACCEPTED" ? t("Aceptada") : t("Rechazada")}
                        </Typography>
                      )}
                    </Box>
                    {/* La frase compuesta en el servidor: los números y la explicación no pueden
                        discrepar porque salen del mismo sitio. */}
                    <Typography variant="caption" color="text.secondary" sx={{ display: "block", mt: 0.25 }}>
                      {discrepancy.detail}
                    </Typography>
                    {discrepancy.status === "OPEN" && (
                      <Box sx={{ display: "flex", gap: 1, mt: 1 }}>
                        <Button
                          size="small" variant="outlined" disabled={busy}
                          onClick={() => void run(
                            () => resolveDiscrepancy(companyId, invoice.id, discrepancy.id, "ACCEPTED"),
                            t("Diferencia aceptada"),
                          )}
                        >
                          {t("Aceptar")}
                        </Button>
                        <Button
                          size="small" variant="outlined" color="error" disabled={busy}
                          onClick={() => void run(
                            () => resolveDiscrepancy(companyId, invoice.id, discrepancy.id, "REJECTED"),
                            t("Diferencia rechazada"),
                          )}
                        >
                          {t("Rechazar")}
                        </Button>
                      </Box>
                    )}
                  </Paper>
                ))}
              </Box>
            </Box>
          )}

          {/* --- las decisiones --- */}
          {invoice.approvals.length > 0 && (
            <Box>
              <SectionHeader title={t("Decisiones")} />
              {invoice.approvals.map((approval) => (
                <Typography key={approval.id} variant="body2" sx={{ mb: 0.5 }}>
                  {approval.decision === "APPROVED" ? t("Aprobada") : t("Rechazada")} ·{" "}
                  {fmtDateTime(approval.decidedAt)}
                  {approval.comment ? ` · ${approval.comment}` : ""}
                </Typography>
              ))}
            </Box>
          )}

          {invoice.export && (
            <Alert severity="success" variant="outlined">
              {t("Exportada a contabilidad el {{when}} con la referencia {{reference}}.", {
                when: fmtDateTime(invoice.export.exportedAt),
                reference: invoice.export.exportReference,
              })}
            </Alert>
          )}

          <Divider />

          {/* --- acciones, sólo las que el servidor permite --- */}
          <Box sx={{ display: "flex", gap: 1, flexWrap: "wrap" }}>
            {invoice.allowedTransitions.includes("MATCHING") && (
              <Button
                variant="contained" disabled={busy}
                onClick={() => void run(() => matchInvoice(companyId, invoice.id), t("Comparada"))}
              >
                {t("Comparar")}
              </Button>
            )}
            {invoice.allowedTransitions.includes("UNDER_REVIEW") && (
              <Button
                variant="outlined" disabled={busy}
                onClick={() => void run(() => beginInvoiceReview(companyId, invoice.id), t("En revisión"))}
              >
                {t("Revisar")}
              </Button>
            )}
            {invoice.allowedTransitions.includes("APPROVED") && (
              <Button
                variant="contained" color="success" disabled={busy || !canApprove}
                onClick={() => void run(() => approveInvoice(companyId, invoice.id), t("Aprobada"))}
              >
                {t("Aprobar")}
              </Button>
            )}
            {invoice.status === "APPROVED" && (
              <Button
                variant="contained" disabled={busy}
                onClick={() => void run(() => exportInvoice(companyId, invoice.id), t("Exportada"))}
              >
                {t("Exportar a contabilidad")}
              </Button>
            )}
          </Box>

          {/* El aviso que explica un botón deshabilitado, en vez de dejar a alguien adivinando. */}
          {invoice.allowedTransitions.includes("APPROVED") && !canApprove && (
            <Alert severity="warning" variant="outlined">
              {t("Quedan {{count}} diferencias sin resolver. Acepta o rechaza cada una antes de aprobar.", {
                count: openDifferences.length,
              })}
            </Alert>
          )}

          {invoice.allowedTransitions.includes("REJECTED") && (
            <Box sx={{ display: "grid", gap: 1 }}>
              <TextField
                size="small" label={t("Motivo del rechazo")} value={rejectReason}
                onChange={(e) => setRejectReason(e.target.value)}
                helperText={t("Obligatorio: el transportista tiene que poder responderlo.")}
              />
              <Box>
                <Button
                  variant="outlined" color="error" disabled={busy || rejectReason.trim() === ""}
                  onClick={async () => {
                    const confirmed = await confirmDialog({
                      title: t("¿Rechazar la factura?"),
                      text: t("Una factura rechazada es definitiva: el transportista emite una nota de crédito y un número nuevo."),
                      confirmLabel: t("Sí, rechazar"),
                      dangerous: true,
                    });
                    if (confirmed) {
                      void run(() => rejectInvoice(companyId, invoice.id, rejectReason.trim()), t("Rechazada"));
                    }
                  }}
                >
                  {t("Rechazar factura")}
                </Button>
              </Box>
            </Box>
          )}
        </Box>
      )}
    </FormDrawer>
  );
}

function Figure({ label, value, strong }: { label: string; value: string; strong?: boolean }) {
  return (
    <Box>
      <Typography variant="overline" color="text.secondary">{label}</Typography>
      <Typography variant={strong ? "h6" : "body1"} sx={{ fontWeight: strong ? 800 : 600 }}>
        {value}
      </Typography>
    </Box>
  );
}
