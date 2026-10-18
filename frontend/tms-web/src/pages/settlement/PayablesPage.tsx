import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import {
  Box, Button, MenuItem, Tab, Tabs, TextField, ToggleButton, ToggleButtonGroup, Typography,
} from "@mui/material";
import { CheckRounded, CloseRounded, PaymentsRounded } from "@mui/icons-material";
import { CAPABILITY } from "../../shared/api/meApi";
import { fetchCarriers } from "../../shared/api/carriersApi";
import {
  AGING_BUCKETS, confirmPaymentRequest, fetchDuePayables, fetchPayableAging, fetchPendingPaymentRequests,
  rejectPaymentRequest,
  type AgingBucket, type DuePayableRow, type PayableAgingCarrierRow, type PayableAgingTotal,
  type PendingPaymentRequestRow,
} from "../../shared/api/payablesApi";
import type { ApiError } from "../../shared/api/httpClient";
import { describeApiError, describePaymentError } from "../../shared/api/problemMessages";
import { DataTable, PageHeader, StatusChip, type DataTableColumn } from "../../shared/ui/components";
import { INVOICE_STATUS_TONE } from "../../shared/ui/statusTones";
import { ICON_TINTS } from "../../shared/ui/navConfig";
import { useCompany } from "../../shared/company/CompanyContext";
import { confirmDialog, notifyError, notifySuccess, promptDialog } from "../../lib/ui";
import { enumLabel } from "../../lib/enums";
import { t } from "../../lib/i18n";
import { fmtDate, fmtDateTime, fmtDecimal } from "../../lib/locale";
import { Money } from "./Money";
import { PayableDrawer } from "./PayableDrawer";
import { dueLabel } from "./payableUi";

type TabKey = "due" | "aging" | "pending";

const WITHIN_OPTIONS = [7, 15, 30, 60] as const;

/** Todas las consultas de esta pantalla empiezan así: un cambio en una factura las refresca todas. */
const PAYABLES_KEY = "settlement-payables";

/** Hoy como `YYYY-MM-DD` en el reloj del navegador: sólo un valor inicial del filtro. */
function today(): string {
  const now = new Date();
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, "0")}-${String(now.getDate()).padStart(2, "0")}`;
}

/**
 * Cuentas por pagar (fase 65): qué vence, cuánto se debe por antigüedad y qué pagos manuales
 * esperan a la segunda persona.
 *
 * TMS no mueve dinero. Lo pagado lo informa el ERP por integración; cuando una persona lo
 * transcribe, queda como solicitud hasta que otra la confirme. Cada moneda va aparte: TMS no
 * convierte.
 */
export function PayablesPage() {
  const { selected, hasCapability } = useCompany();
  const companyId = selected?.id ?? "";
  const queryClient = useQueryClient();
  const [tab, setTab] = useState<TabKey>("due");
  const [openId, setOpenId] = useState<string | null>(null);

  const changed = () => void queryClient.invalidateQueries({
    predicate: (query) => query.queryKey[0] === PAYABLES_KEY,
  });

  return (
    <>
      <PageHeader
        icon={<PaymentsRounded />}
        tint={ICON_TINTS["/settlement/payables"]}
        title={t("Cuentas por pagar")}
        subtitle={t("Vencimientos y pagos de las facturas de transportistas. TMS registra lo que el ERP pagó; no mueve dinero.")}
      />

      <Tabs value={tab} onChange={(_, value: TabKey) => setTab(value)} sx={{ mb: 2 }} variant="scrollable">
        <Tab value="due" label={t("Vencimientos")} />
        <Tab value="aging" label={t("Antigüedad")} />
        <Tab value="pending" label={t("Pagos por confirmar")} />
      </Tabs>

      {companyId !== "" && tab === "due" && <DueTab companyId={companyId} onOpen={setOpenId} />}
      {companyId !== "" && tab === "aging" && <AgingTab companyId={companyId} />}
      {companyId !== "" && tab === "pending" && (
        <PendingTab
          companyId={companyId} onOpen={setOpenId} onChanged={changed}
          canConfirm={hasCapability(CAPABILITY.PAYMENT_CONFIRM)}
        />
      )}

      {openId && companyId !== "" && (
        <PayableDrawer companyId={companyId} invoiceId={openId} onClose={() => setOpenId(null)} onChanged={changed} />
      )}
    </>
  );
}

// --- vencimientos -------------------------------------------------------------------------------

function DueTab({ companyId, onOpen }: { companyId: string; onOpen: (invoiceId: string) => void }) {
  const [within, setWithin] = useState<number>(15);
  const [carrierId, setCarrierId] = useState("");
  const dueQuery = useQuery({
    queryKey: [PAYABLES_KEY, "due", companyId, within, carrierId],
    queryFn: ({ signal }) => fetchDuePayables(companyId, { within, carrierId: carrierId || undefined }, signal),
  });
  // Sin permiso de flota la lista llega vacía: el filtro simplemente no ofrece opciones.
  const carriersQuery = useQuery({
    queryKey: ["carriers-lookup", companyId],
    queryFn: ({ signal }) => fetchCarriers({ companyId, size: 200, active: true, sort: "businessName,asc", signal }),
    retry: false,
  });

  const columns: DataTableColumn<DuePayableRow>[] = [
    {
      key: "invoice",
      header: t("Factura"),
      render: (row) => (
        <Box>
          <Typography variant="body2" sx={{ fontWeight: 700 }}>{row.invoiceNumber}</Typography>
          <Typography variant="caption" color="text.secondary">{row.carrierName ?? "—"}</Typography>
        </Box>
      ),
    },
    { key: "invoiceDate", header: t("Fecha de factura"), render: (row) => fmtDate(row.invoiceDate) },
    {
      key: "dueDate",
      header: t("Vencimiento"),
      render: (row) => (
        <Box>
          <Typography variant="body2">{fmtDate(row.dueDate)}</Typography>
          <Typography variant="caption" sx={{
            color: row.daysToDue < 0 ? "error.main" : "text.secondary", fontWeight: row.daysToDue < 0 ? 700 : 400,
          }}>
            {dueLabel(row.daysToDue)}
          </Typography>
        </Box>
      ),
    },
    {
      key: "status",
      header: t("Estado"),
      render: (row) => <StatusChip label={enumLabel("invoiceStatus", row.status)} tone={INVOICE_STATUS_TONE[row.status]} />,
    },
    { key: "payable", header: t("A pagar"), numeric: true, render: (row) => <Money value={row.payableAmount} currency={row.currency} /> },
    { key: "paid", header: t("Pagado"), numeric: true, render: (row) => <Money value={row.paidAmount} currency={row.currency} /> },
    { key: "balance", header: t("Saldo"), numeric: true, render: (row) => <Money value={row.openBalance} currency={row.currency} strong /> },
  ];

  const rows = dueQuery.data ?? [];
  const overdue = rows.filter((row) => row.daysToDue < 0).length;

  return (
    <>
      <Box sx={{ display: "flex", gap: 1.5, flexWrap: "wrap", mb: 2, alignItems: "center" }}>
        <ToggleButtonGroup
          size="small" exclusive value={within} aria-label={t("Vencen en")}
          onChange={(_e, next: number | null) => { if (next !== null) setWithin(next); }}
        >
          {WITHIN_OPTIONS.map((option) => (
            <ToggleButton key={option} value={option}>{t("{{n}} días", { n: option })}</ToggleButton>
          ))}
        </ToggleButtonGroup>
        <TextField
          size="small" select label={t("Transportista")} value={carrierId} sx={{ minWidth: 220 }}
          onChange={(e) => setCarrierId(e.target.value)}
        >
          <MenuItem value="">{t("Todos")}</MenuItem>
          {(carriersQuery.data?.content ?? []).map((carrier) => (
            <MenuItem key={carrier.id} value={carrier.id}>{carrier.businessName}</MenuItem>
          ))}
        </TextField>
        {overdue > 0 && (
          <Typography variant="caption" color="error.main" sx={{ fontWeight: 700 }}>
            {t("{{count}} facturas vencidas", { count: overdue })}
          </Typography>
        )}
      </Box>

      <DataTable
        rows={rows}
        columns={columns}
        caption={t("Vencimientos")}
        isLoading={dueQuery.isLoading}
        error={dueQuery.isError ? describeApiError(dueQuery.error as ApiError) : null}
        onRetry={() => void dueQuery.refetch()}
        rowKey={(row) => row.invoiceId}
        onRowClick={(row) => onOpen(row.invoiceId)}
        rowAccent={(row) => (row.daysToDue < 0 ? "error.main" : null)}
        total={rows.length}
        emptyTitle={t("Nada vence en este plazo")}
        emptyMessage={t("Aquí aparecen las facturas aprobadas con saldo por pagar, las vencidas primero.")}
      />
    </>
  );
}

// --- antigüedad ---------------------------------------------------------------------------------

function bucketColumns<T extends PayableAgingTotal>(buckets: readonly AgingBucket[]): DataTableColumn<T>[] {
  return buckets.map((bucket) => ({
    key: bucket,
    header: enumLabel("agingBucket", bucket),
    numeric: true,
    render: (row: T) => <Money value={row.amounts[bucket] ?? 0} currency={row.currency} />,
  }));
}

/** Saldo abierto por tramo de vencimiento: primero el total de cada moneda, después por transportista. */
function AgingTab({ companyId }: { companyId: string }) {
  const [asOf, setAsOf] = useState(today());
  const agingQuery = useQuery({
    queryKey: [PAYABLES_KEY, "aging", companyId, asOf],
    queryFn: ({ signal }) => fetchPayableAging(companyId, asOf || undefined, signal),
  });
  const buckets = agingQuery.data?.buckets.length ? agingQuery.data.buckets : AGING_BUCKETS;
  const error = agingQuery.isError ? describeApiError(agingQuery.error as ApiError) : null;

  const totalColumns: DataTableColumn<PayableAgingTotal>[] = [
    { key: "currency", header: t("Moneda"), render: (row) => <Typography variant="body2" sx={{ fontWeight: 700 }}>{row.currency}</Typography> },
    ...bucketColumns<PayableAgingTotal>(buckets),
    { key: "total", header: t("Total"), numeric: true, render: (row) => <Money value={row.total} currency={row.currency} strong /> },
    { key: "count", header: t("Facturas"), numeric: true, render: (row) => String(row.count) },
  ];
  const carrierColumns: DataTableColumn<PayableAgingCarrierRow>[] = [
    { key: "carrier", header: t("Transportista"), render: (row) => row.carrierName ?? "—" },
    ...bucketColumns<PayableAgingCarrierRow>(buckets),
    { key: "total", header: t("Total"), numeric: true, render: (row) => <Money value={row.total} currency={row.currency} strong /> },
    { key: "count", header: t("Facturas"), numeric: true, render: (row) => String(row.count) },
  ];

  return (
    <>
      <Box sx={{ display: "flex", gap: 1.5, mb: 2, alignItems: "center", flexWrap: "wrap" }}>
        <TextField
          size="small" type="date" label={t("Saldo al")} value={asOf}
          onChange={(e) => setAsOf(e.target.value)} slotProps={{ inputLabel: { shrink: true } }}
        />
        <Typography variant="caption" color="text.secondary">
          {t("Tramos desde el vencimiento. Cada moneda va en su propia fila: TMS no convierte monedas.")}
        </Typography>
      </Box>
      <Box sx={{ display: "grid", gap: 2 }}>
        <DataTable
          rows={agingQuery.data?.totals ?? []}
          columns={totalColumns}
          caption={t("Totales por moneda")}
          isLoading={agingQuery.isLoading}
          error={error}
          onRetry={() => void agingQuery.refetch()}
          rowKey={(row) => row.currency}
          emptyTitle={t("No hay saldo por pagar a esta fecha")}
        />
        <DataTable
          rows={agingQuery.data?.carriers ?? []}
          columns={carrierColumns}
          caption={t("Por transportista")}
          isLoading={agingQuery.isLoading}
          error={error}
          onRetry={() => void agingQuery.refetch()}
          rowKey={(row) => `${row.carrierId}-${row.currency}`}
          emptyTitle={t("Ningún transportista con saldo a esta fecha")}
        />
      </Box>
    </>
  );
}

// --- pagos por confirmar ------------------------------------------------------------------------

/**
 * Los pagos manuales que esperan a la segunda persona. Confirmar los aplica; el servidor rechaza
 * que los confirme quien los registró, y ese mensaje se muestra tal cual.
 */
function PendingTab({ companyId, canConfirm, onOpen, onChanged }: {
  companyId: string; canConfirm: boolean; onOpen: (invoiceId: string) => void; onChanged: () => void;
}) {
  const [busy, setBusy] = useState(false);
  const pendingQuery = useQuery({
    queryKey: [PAYABLES_KEY, "pending", companyId],
    queryFn: ({ signal }) => fetchPendingPaymentRequests(companyId, signal),
  });

  async function run(action: () => Promise<unknown>, success: string) {
    setBusy(true);
    try {
      await action();
      notifySuccess(success);
      onChanged();
    } catch (error) {
      notifyError(t("No se pudo completar"), describePaymentError(error as ApiError));
    } finally {
      setBusy(false);
    }
  }

  async function confirm(row: PendingPaymentRequestRow) {
    const ok = await confirmDialog({
      title: t("¿Confirmar el pago?"),
      text: t("Se aplica el pago {{reference}} de {{amount}} {{currency}} a la factura {{invoice}}. Confirmas que el ERP lo pagó así; quien lo registró no puede confirmarlo.", {
        reference: row.paymentReference, amount: fmtDecimal(row.amount, 2), currency: row.currency, invoice: row.invoiceNumber,
      }),
      confirmLabel: t("Confirmar pago"),
    });
    if (!ok) return;
    await run(() => confirmPaymentRequest(companyId, row.invoiceId, row.id), t("Pago confirmado"));
  }

  async function reject(row: PendingPaymentRequestRow) {
    const comment = await promptDialog({
      title: t("¿Rechazar la solicitud de pago?"),
      text: t("El pago {{reference}} no se aplica. Quien lo registró verá tu comentario.", { reference: row.paymentReference }),
      inputLabel: t("Comentario"), required: true, maxLength: 500, confirmLabel: t("Rechazar"), dangerous: true,
    });
    if (comment === null) return;
    await run(() => rejectPaymentRequest(companyId, row.invoiceId, row.id, comment), t("Solicitud rechazada"));
  }

  const columns: DataTableColumn<PendingPaymentRequestRow>[] = [
    {
      key: "invoice",
      header: t("Factura"),
      render: (row) => (
        <Box>
          <Typography variant="body2" sx={{ fontWeight: 700 }}>{row.invoiceNumber}</Typography>
          <Typography variant="caption" color="text.secondary">{row.carrierName ?? "—"}</Typography>
        </Box>
      ),
    },
    { key: "reference", header: t("Referencia"), render: (row) => row.paymentReference },
    { key: "paidOn", header: t("Fecha de pago"), render: (row) => fmtDate(row.paidOn) },
    { key: "amount", header: t("Monto"), numeric: true, render: (row) => <Money value={row.amount} currency={row.currency} strong /> },
    { key: "balance", header: t("Saldo"), numeric: true, render: (row) => <Money value={row.openBalance} currency={row.currency} /> },
    {
      key: "requested",
      header: t("Solicitó"),
      render: (row) => (
        <Box>
          <Typography variant="body2">{row.requestedBy}</Typography>
          <Typography variant="caption" color="text.secondary">{fmtDateTime(row.requestedAt)}</Typography>
        </Box>
      ),
    },
    ...(canConfirm ? [{
      key: "actions",
      header: "",
      actions: true,
      render: (row: PendingPaymentRequestRow) => (
        <Box sx={{ display: "flex", gap: 0.5, justifyContent: "flex-end" }}>
          <Button size="small" color="success" startIcon={<CheckRounded />} disabled={busy}
            onClick={() => void confirm(row)}>
            {t("Confirmar")}
          </Button>
          <Button size="small" color="error" startIcon={<CloseRounded />} disabled={busy}
            onClick={() => void reject(row)}>
            {t("Rechazar")}
          </Button>
        </Box>
      ),
    } satisfies DataTableColumn<PendingPaymentRequestRow>] : []),
  ];

  return (
    <DataTable
      rows={pendingQuery.data ?? []}
      columns={columns}
      caption={t("Pagos por confirmar")}
      isLoading={pendingQuery.isLoading}
      error={pendingQuery.isError ? describeApiError(pendingQuery.error as ApiError) : null}
      onRetry={() => void pendingQuery.refetch()}
      rowKey={(row) => row.id}
      onRowClick={(row) => onOpen(row.invoiceId)}
      total={pendingQuery.data?.length}
      emptyTitle={t("Ningún pago espera confirmación")}
      emptyMessage={canConfirm
        ? t("Cuando alguien registre un pago manual, aparecerá aquí para que otra persona lo confirme.")
        : t("Confirmar un pago pide un permiso que tu usuario no tiene: lo hace otra persona.")}
    />
  );
}
