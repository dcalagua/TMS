import { useQuery } from "@tanstack/react-query";
import type { ReactNode } from "react";
import { useNavigate } from "react-router-dom";
import { Box, Button, Card, CardActionArea, CircularProgress, IconButton, Skeleton, Typography } from "@mui/material";
import {
  ChevronRightRounded, CloudOffRounded, EventRounded, ReceiptLongRounded, RefreshRounded,
} from "@mui/icons-material";
import { fetchPortalInvoices, type PortalInvoiceSummary } from "../../shared/api/portalApi";
import { ApiError } from "../../shared/api/httpClient";
import { describeApiError } from "../../shared/api/problemMessages";
import { useCompany } from "../../shared/company/CompanyContext";
import { EmptyState, ErrorState, StatusChip } from "../../shared/ui/components";
import { PORTAL_INVOICE_STATUS_TONE } from "../../shared/ui/statusTones";
import { enumLabel } from "../../lib/enums";
import { t } from "../../lib/i18n";
import { fmtDate } from "../../lib/locale";
import { R } from "../../theme";
import { portalInvoicesKey, portalMoney, PORTAL_PAGE_GRID } from "./portalUi";

/**
 * Lo que se enseña cuando la lectura falla. Sin señal no hay copia guardada de las facturas (no
 * se reportan desde la calle): se dice «Sin conexión» y se ofrece reintentar, sin más drama.
 */
export function InvoiceLoadError({ error, onRetry }: { error: unknown; onRetry: () => void }) {
  if (error instanceof ApiError) return <ErrorState message={describeApiError(error)} onRetry={onRetry} />;
  return (
    <EmptyState
      icon={<CloudOffRounded />}
      title={t("Sin conexión")}
      message={t("Tus facturas se ven cuando vuelva la señal.")}
      action={<Button variant="outlined" onClick={onRetry} sx={{ minHeight: 48 }}>{t("Reintentar")}</Button>}
    />
  );
}

/**
 * «Mis facturas» (fase 65): las facturas de este transportista, con el estado que le importa -
 * en revisión, aprobada, programada, pagada - su vencimiento, lo pagado y el saldo. El servidor
 * filtra por transportista; aquí no se elige nada.
 */
export function PortalInvoicesPage() {
  const navigate = useNavigate();
  const { selected } = useCompany();
  const companyId = selected?.id ?? "";

  const invoicesQuery = useQuery({
    queryKey: portalInvoicesKey(companyId),
    queryFn: ({ signal }) => fetchPortalInvoices(companyId, signal),
    enabled: companyId !== "",
    retry: false,
  });
  const invoices = invoicesQuery.data ?? [];

  return (
    <Box sx={PORTAL_PAGE_GRID}>
      <Box sx={{ display: "flex", alignItems: "center", gap: 1 }}>
        <Typography component="h1" sx={{ fontWeight: 800, fontSize: 24, flex: 1 }}>{t("Mis facturas")}</Typography>
        <IconButton
          onClick={() => void invoicesQuery.refetch()} aria-label={t("Recargar")} disabled={invoicesQuery.isFetching}
          sx={{ width: 48, height: 48 }}
        >
          {invoicesQuery.isFetching && !invoicesQuery.isPending ? <CircularProgress size={22} /> : <RefreshRounded />}
        </IconButton>
      </Box>

      {invoicesQuery.isPending ? (
        <Box role="status" aria-label={t("Cargando facturas...")} sx={{ display: "grid", gap: 1.5 }}>
          {[0, 1, 2].map((i) => <Skeleton key={i} variant="rounded" height={132} sx={{ borderRadius: `${R.lg}px` }} />)}
        </Box>
      ) : invoicesQuery.isError ? (
        <InvoiceLoadError error={invoicesQuery.error} onRetry={() => void invoicesQuery.refetch()} />
      ) : invoices.length === 0 ? (
        <EmptyState
          icon={<ReceiptLongRounded />}
          title={t("Aún no tienes facturas registradas")}
          message={t("Las facturas aparecen aquí cuando la oficina las registra.")}
        />
      ) : (
        <Box component="ul" aria-label={t("Mis facturas")} sx={{ listStyle: "none", m: 0, p: 0, display: "grid", gap: 1.5 }}>
          {invoices.map((invoice) => (
            <Box component="li" key={invoice.id}>
              <PortalInvoiceCard invoice={invoice} onOpen={() => navigate(`/portal/invoices/${invoice.id}`)} />
            </Box>
          ))}
        </Box>
      )}
    </Box>
  );
}

function Figure({ label, children }: { label: string; children: ReactNode }) {
  return (
    <Box sx={{ minWidth: 0 }}>
      <Typography color="text.secondary" sx={{ fontSize: 13, fontWeight: 600 }}>{label}</Typography>
      <Typography sx={{ fontSize: 16, fontWeight: 800, fontVariantNumeric: "tabular-nums" }}>{children}</Typography>
    </Box>
  );
}

function PortalInvoiceCard({ invoice, onOpen }: { invoice: PortalInvoiceSummary; onOpen: () => void }) {
  return (
    <Card variant="outlined" sx={{ borderRadius: `${R.lg}px` }}>
      <CardActionArea onClick={onOpen} aria-label={t("Abrir factura {{n}}", { n: invoice.invoiceNumber })}
        sx={{ p: 2, display: "grid", gap: 1.5, minHeight: 64 }}>
        <Box sx={{ display: "flex", alignItems: "center", gap: 1.5 }}>
          <Box sx={{ flex: 1, minWidth: 0 }}>
            <Typography sx={{ fontWeight: 800, fontSize: 20, lineHeight: 1.2, fontVariantNumeric: "tabular-nums" }}>
              {invoice.invoiceNumber}
            </Typography>
            <Typography color="text.secondary" sx={{ fontSize: 14, mt: 0.25 }}>
              {invoice.fiscalDocumentCode ? `${enumLabel("documentKind", invoice.documentKind)} (${invoice.fiscalDocumentCode})` : enumLabel("documentKind", invoice.documentKind)} · {fmtDate(invoice.invoiceDate)}
            </Typography>
          </Box>
          <StatusChip size="medium" label={enumLabel("portalInvoiceStatus", invoice.status)}
            tone={PORTAL_INVOICE_STATUS_TONE[invoice.status] ?? "neutral"} />
          <ChevronRightRounded color="action" />
        </Box>

        <Box sx={{ display: "grid", gap: 1, gridTemplateColumns: "repeat(3, minmax(0, 1fr))" }}>
          <Figure label={t("Total")}>{portalMoney(invoice.totalAmount, invoice.currency)}</Figure>
          <Figure label={t("Pagado")}>{portalMoney(invoice.paidAmount, invoice.currency)}</Figure>
          <Figure label={t("Saldo")}>{portalMoney(invoice.openBalance, invoice.currency)}</Figure>
        </Box>

        <Box sx={{ display: "flex", gap: 1, flexWrap: "wrap", alignItems: "center" }}>
          <Typography sx={{ display: "flex", alignItems: "center", gap: 0.5, fontSize: 15 }}>
            <EventRounded sx={{ fontSize: 18, color: "text.secondary" }} />
            {invoice.dueDate ? t("Vence {{date}}", { date: fmtDate(invoice.dueDate) }) : t("Sin vencimiento")}
          </Typography>
          {invoice.withholdingTotalAmount !== null && invoice.withholdingTotalAmount !== undefined && (
            <Typography color="text.secondary" sx={{ fontSize: 14, ml: "auto" }}>
              {t("Retenciones {{amount}}", { amount: portalMoney(invoice.withholdingTotalAmount, invoice.currency) })}
            </Typography>
          )}
        </Box>
      </CardActionArea>
    </Card>
  );
}
