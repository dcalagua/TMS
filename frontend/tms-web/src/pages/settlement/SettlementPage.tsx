import { keepPreviousData, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Box, MenuItem, TextField, Typography } from "@mui/material";
import { ReceiptLongRounded } from "@mui/icons-material";
import {
  INVOICE_STATUSES, fetchInvoices,
  type CarrierInvoiceSummaryView, type InvoiceStatus,
} from "../../shared/api/settlementApi";
import {
  DataTable, PageHeader, Pagination, StatusChip, Toolbar, type DataTableColumn,
} from "../../shared/ui/components";
import { INVOICE_STATUS_TONE, MATCH_STATUS_TONE } from "../../shared/ui/statusTones";
import { ICON_TINTS } from "../../shared/ui/navConfig";
import { enumLabel } from "../../lib/enums";
import { t } from "../../lib/i18n";
import { fmtDate, fmtDecimal } from "../../lib/locale";
import { useCompany } from "../../shared/company/CompanyContext";
import { InvoiceWorkspaceDrawer } from "./InvoiceWorkspaceDrawer";

const PAGE_SIZE = 25;

/**
 * Auditoría de flete (migración V46).
 *
 * <h2>La regla de esta pantalla</h2>
 * **El razonamiento no se esconde detrás de un estado.** Una lista que dijera sólo "CON DIFERENCIAS"
 * obligaría a reconstruir la comparación a mano, que es justo el trabajo que este módulo existe
 * para quitar. Por eso cada fila lleva lo esperado, lo facturado y la diferencia — y no sólo el
 * veredicto.
 *
 * <h2>Null no es cero</h2>
 * `expectedAmount` en null significa que **no había nada con qué comparar**. Se pinta un guion.
 * Pintar 0,00 reportaría cada envío sin tarificar como un sobrecoste del importe entero, y mandaría
 * a alguien a discutir con un transportista que no hizo nada mal.
 */
export function SettlementPage() {
  const { selected } = useCompany();
  const companyId = selected?.id ?? "";
  const queryClient = useQueryClient();
  const [page, setPage] = useState(0);
  const [statusFilter, setStatusFilter] = useState<InvoiceStatus | "">("");
  const [openInvoiceId, setOpenInvoiceId] = useState<string | null>(null);

  const invoicesQuery = useQuery({
    queryKey: ["settlement-invoices", companyId, page, statusFilter],
    queryFn: ({ signal }) => fetchInvoices(
      companyId,
      { page, size: PAGE_SIZE, status: statusFilter ? [statusFilter] : undefined },
      signal,
    ),
    placeholderData: keepPreviousData,
  });

  function refresh() {
    void queryClient.invalidateQueries({ queryKey: ["settlement-invoices", companyId] });
  }

  /** Un importe que puede no existir. El guion es la respuesta honesta; 0,00 sería una afirmación. */
  function amount(value: number | null, currency: string) {
    return value === null ? "—" : `${fmtDecimal(value, 2)} ${currency}`;
  }

  const columns: DataTableColumn<CarrierInvoiceSummaryView>[] = [
    {
      key: "invoiceNumber",
      header: t("Factura"),
      render: (row) => (
        <Box>
          <Typography variant="body2" sx={{ fontWeight: 700 }}>{row.invoiceNumber}</Typography>
          <Typography variant="caption" color="text.secondary">{fmtDate(row.invoiceDate)}</Typography>
        </Box>
      ),
    },
    { key: "carrierName", header: t("Transportista"), render: (row) => row.carrierName ?? "—" },
    {
      key: "expectedAmount",
      header: t("Esperado"),
      render: (row) => amount(row.expectedAmount, row.currency),
    },
    {
      key: "totalAmount",
      header: t("Facturado"),
      render: (row) => `${fmtDecimal(row.totalAmount, 2)} ${row.currency}`,
    },
    {
      key: "differenceAmount",
      header: t("Diferencia"),
      render: (row) => {
        if (row.differenceAmount === null) return "—";
        const over = row.differenceAmount > 0;
        return (
          <Typography
            variant="body2"
            sx={{ fontWeight: 700, color: row.differenceAmount === 0 ? "text.secondary" : (over ? "error.main" : "warning.main") }}
          >
            {over ? "+" : ""}{fmtDecimal(row.differenceAmount, 2)}
          </Typography>
        );
      },
    },
    {
      key: "matchStatus",
      header: t("Comparación"),
      render: (row) => row.matchStatus === null
        ? <Typography variant="caption" color="text.secondary">{t("Sin comparar")}</Typography>
        : <StatusChip label={enumLabel("matchStatus", row.matchStatus)} tone={MATCH_STATUS_TONE[row.matchStatus]} />,
    },
    {
      key: "status",
      header: t("Estado"),
      render: (row) => (
        <StatusChip
          label={enumLabel("invoiceStatus", row.status)}
          tone={INVOICE_STATUS_TONE[row.status]}
          variant="solid"
        />
      ),
    },
  ];

  const pageData = invoicesQuery.data;

  return (
    <>
      <PageHeader
        icon={<ReceiptLongRounded />}
        tint={ICON_TINTS["/settlement"]}
        title={t("Auditoría de flete")}
        subtitle={t("Lo que el transportista factura, lo que TMS esperaba, y quién lo decide. TMS valida y exporta; el ERP paga.")}
        onRefresh={refresh}
        refreshing={invoicesQuery.isFetching}
      />

      <Toolbar
        onApply={() => setPage(0)}
        onReset={() => { setStatusFilter(""); setPage(0); }}
        filters={
          <TextField
            select size="small" label={t("Estado")} value={statusFilter}
            onChange={(e) => { setStatusFilter(e.target.value as InvoiceStatus | ""); setPage(0); }}
            sx={{ minWidth: 200 }}
          >
            <MenuItem value="">{t("Todos")}</MenuItem>
            {INVOICE_STATUSES.map((status) => (
              <MenuItem key={status} value={status}>{enumLabel("invoiceStatus", status)}</MenuItem>
            ))}
          </TextField>
        }
      />

      <DataTable
        rows={pageData?.content ?? []}
        columns={columns}
        isLoading={invoicesQuery.isLoading}
        rowKey={(row) => row.id}
        onRowClick={(row) => setOpenInvoiceId(row.id)}
        total={pageData?.totalElements}
        emptyTitle={t("No hay facturas de transportista todavía")}
        emptyMessage={t("Cuando llegue una, aparecerá aquí con lo que TMS esperaba al lado.")}
        footer={pageData ? <Pagination page={pageData} onPageChange={setPage} /> : undefined}
      />

      {openInvoiceId && (
        <InvoiceWorkspaceDrawer
          companyId={companyId}
          invoiceId={openInvoiceId}
          onClose={() => setOpenInvoiceId(null)}
          onChanged={refresh}
        />
      )}
    </>
  );
}
