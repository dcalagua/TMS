import { useQuery } from "@tanstack/react-query";
import type { ReactNode } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { Alert, Box, Card, CircularProgress, IconButton, Skeleton, Typography } from "@mui/material";
import { ArrowBackRounded, RefreshRounded } from "@mui/icons-material";
import { fetchPortalInvoice } from "../../shared/api/portalApi";
import { useCompany } from "../../shared/company/CompanyContext";
import { StatusChip } from "../../shared/ui/components";
import { PORTAL_INVOICE_STATUS_TONE } from "../../shared/ui/statusTones";
import { enumLabel } from "../../lib/enums";
import { t } from "../../lib/i18n";
import { fmtDate } from "../../lib/locale";
import { R } from "../../theme";
import { PORTAL_BAR_HEIGHT, SAFE_AREA, portalInvoicesKey, portalMoney } from "./portalUi";
import { InvoiceLoadError } from "./PortalInvoicesPage";

function Row({ label, children, strong = false }: { label: string; children: ReactNode; strong?: boolean }) {
  return (
    <Box sx={{ display: "flex", justifyContent: "space-between", gap: 2, py: 0.75 }}>
      <Typography color="text.secondary" sx={{ fontSize: 15 }}>{label}</Typography>
      <Typography sx={{ fontSize: 15, fontWeight: strong ? 800 : 600, fontVariantNumeric: "tabular-nums", textAlign: "right" }}>
        {children}
      </Typography>
    </Box>
  );
}

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <Box component="section" aria-label={title} sx={{ display: "grid", gap: 1 }}>
      <Typography component="h2" sx={{ fontWeight: 800, fontSize: 17 }}>{title}</Typography>
      {children}
    </Box>
  );
}

/**
 * Una factura del transportista: sus cifras, sus líneas y los pagos que la oficina registró. Es
 * de solo lectura: lo pagado lo informa el ERP, y el transportista lo consulta, no lo cambia.
 */
export function PortalInvoicePage() {
  const navigate = useNavigate();
  const { invoiceId = "" } = useParams();
  const { selected } = useCompany();
  const companyId = selected?.id ?? "";

  const invoiceQuery = useQuery({
    queryKey: [...portalInvoicesKey(companyId), invoiceId],
    queryFn: ({ signal }) => fetchPortalInvoice(companyId, invoiceId, signal),
    enabled: companyId !== "" && invoiceId !== "",
    retry: false,
  });
  const invoice = invoiceQuery.data;

  return (
    <Box sx={{ display: "grid", gap: 2 }}>
      <Box sx={{
        position: "sticky", top: `calc(${SAFE_AREA.top} + ${PORTAL_BAR_HEIGHT}px)`, zIndex: 2,
        mx: -2, mt: -2, px: 2, pt: 1, pb: 1.25, bgcolor: "background.default",
        borderBottom: "1px solid", borderColor: "divider",
      }}>
        <Box sx={{ display: "flex", alignItems: "center", gap: 0.5 }}>
          <IconButton onClick={() => navigate("/portal/invoices")} aria-label={t("Volver")} edge="start" sx={{ width: 48, height: 48 }}>
            <ArrowBackRounded />
          </IconButton>
          <Box sx={{ flex: 1, minWidth: 0 }}>
            <Typography component="h1" noWrap sx={{ fontWeight: 800, fontSize: 20, lineHeight: 1.2 }}>
              {invoice?.invoiceNumber ?? t("Factura")}
            </Typography>
            {invoice && (
              <Typography color="text.secondary" sx={{ fontSize: 14, fontWeight: 600 }}>
                {enumLabel("documentType", invoice.documentType)} · {fmtDate(invoice.invoiceDate)}
              </Typography>
            )}
          </Box>
          {invoice && (
            <StatusChip size="medium" label={enumLabel("portalInvoiceStatus", invoice.status)}
              tone={PORTAL_INVOICE_STATUS_TONE[invoice.status] ?? "neutral"} />
          )}
          <IconButton onClick={() => void invoiceQuery.refetch()} aria-label={t("Recargar")} disabled={invoiceQuery.isFetching} sx={{ width: 48, height: 48 }}>
            {invoiceQuery.isFetching && !invoiceQuery.isPending ? <CircularProgress size={22} /> : <RefreshRounded />}
          </IconButton>
        </Box>
      </Box>

      {invoiceQuery.isPending ? (
        <Box role="status" aria-label={t("Cargando factura...")} sx={{ display: "grid", gap: 1.5 }}>
          <Skeleton variant="rounded" height={180} sx={{ borderRadius: `${R.lg}px` }} />
          <Skeleton variant="rounded" height={96} sx={{ borderRadius: `${R.md}px` }} />
        </Box>
      ) : invoiceQuery.isError || !invoice ? (
        <InvoiceLoadError error={invoiceQuery.error} onRetry={() => void invoiceQuery.refetch()} />
      ) : (
        <>
          {invoice.status === "REJECTED" && (
            <Alert severity="error" sx={{ fontSize: 15 }}>
              {t("Rechazada")}{invoice.rejectionReason ? `: ${invoice.rejectionReason}` : ""}
            </Alert>
          )}
          {invoice.status === "ON_HOLD" && (
            <Alert severity="warning" sx={{ fontSize: 15 }}>
              {t("El pago está en espera. Consulta a la oficina por el motivo.")}
            </Alert>
          )}

          <Card variant="outlined" sx={{ borderRadius: `${R.lg}px`, px: 2, py: 1 }}>
            <Row label={t("Vencimiento")}>{invoice.dueDate ? fmtDate(invoice.dueDate) : t("Sin vencimiento")}</Row>
            <Row label={t("Total")}>{portalMoney(invoice.totalAmount, invoice.currency)}</Row>
            {invoice.netAmount !== null && <Row label={t("Neto con notas")}>{portalMoney(invoice.netAmount, invoice.currency)}</Row>}
            {invoice.detractionAmount !== null && (
              <Row label={t("Detracción")}>
                {portalMoney(invoice.detractionAmount, invoice.currency)}
                <Typography component="span" color="text.secondary" sx={{ display: "block", fontSize: 13 }}>
                  {invoice.detractionConstancyNumber
                    ? t("Constancia {{number}}", { number: invoice.detractionConstancyNumber })
                    : t("Constancia pendiente")}
                </Typography>
              </Row>
            )}
            {invoice.retentionAmount !== null && <Row label={t("Retención")}>{portalMoney(invoice.retentionAmount, invoice.currency)}</Row>}
            {invoice.payableAmount !== null && <Row label={t("A pagar")} strong>{portalMoney(invoice.payableAmount, invoice.currency)}</Row>}
            <Row label={t("Pagado")}>{portalMoney(invoice.paidAmount, invoice.currency)}</Row>
            <Row label={t("Saldo")} strong>{portalMoney(invoice.openBalance, invoice.currency)}</Row>
          </Card>

          <Section title={t("Pagos")}>
            {invoice.payments.length === 0 ? (
              <Typography color="text.secondary" sx={{ fontSize: 15 }}>{t("Todavía no hay pagos registrados.")}</Typography>
            ) : (
              <Box component="ul" aria-label={t("Pagos")} sx={{ listStyle: "none", m: 0, p: 0, display: "grid", gap: 1 }}>
                {invoice.payments.map((payment, index) => (
                  <Card component="li" key={`${payment.paymentReference}-${index}`} variant="outlined"
                    sx={{ borderRadius: `${R.md}px`, px: 2, py: 1.25 }}>
                    <Box sx={{ display: "flex", justifyContent: "space-between", gap: 1 }}>
                      <Typography sx={{ fontWeight: 700, fontSize: 15 }}>{fmtDate(payment.paidOn)}</Typography>
                      <Typography sx={{ fontWeight: 800, fontSize: 15, fontVariantNumeric: "tabular-nums" }}>
                        {portalMoney(payment.amount, invoice.currency)}
                      </Typography>
                    </Box>
                    <Typography color="text.secondary" sx={{ fontSize: 14 }}>
                      {t("Referencia {{ref}}", { ref: payment.paymentReference })}
                    </Typography>
                    {payment.detractionConstancyNumber && (
                      <Typography color="text.secondary" sx={{ fontSize: 14 }}>
                        {t("Constancia {{number}}", { number: payment.detractionConstancyNumber })}
                        {payment.detractionConstancyDate ? ` · ${fmtDate(payment.detractionConstancyDate)}` : ""}
                        {payment.detractionAmount !== null ? ` · ${portalMoney(payment.detractionAmount, invoice.currency)}` : ""}
                      </Typography>
                    )}
                    {payment.retentionAmount !== null && (
                      <Typography color="text.secondary" sx={{ fontSize: 14 }}>
                        {t("Retención {{amount}}", { amount: portalMoney(payment.retentionAmount, invoice.currency) })}
                      </Typography>
                    )}
                  </Card>
                ))}
              </Box>
            )}
          </Section>

          <Section title={t("Líneas")}>
            <Card variant="outlined" sx={{ borderRadius: `${R.lg}px`, px: 2, py: 1 }}>
              {invoice.lines.map((line) => (
                <Row key={line.lineNumber} label={`${line.lineNumber}. ${line.description ?? enumLabel("chargeCode", line.chargeCode)}`}>
                  {portalMoney(line.lineAmount, invoice.currency)}
                </Row>
              ))}
            </Card>
          </Section>
        </>
      )}
    </Box>
  );
}
