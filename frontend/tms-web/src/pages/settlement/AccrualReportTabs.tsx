import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { Alert, Box, Button, TextField, Typography } from "@mui/material";
import { BoltRounded, DownloadRounded } from "@mui/icons-material";
import { CAPABILITY } from "../../shared/api/meApi";
import {
  downloadAccrualJournalCsv, fetchAccrualAging, fetchAccrualJournal, fetchUnaccruedTrips, sweepAccruals,
  type AccrualAgingRow, type AccrualJournalRow, type UnaccruedTripRow,
} from "../../shared/api/accrualApi";
import { saveDownloadedFile, type ApiError } from "../../shared/api/httpClient";
import { describeApiError } from "../../shared/api/problemMessages";
import { DataTable, StatusChip, type DataTableColumn } from "../../shared/ui/components";
import { useCompany } from "../../shared/company/CompanyContext";
import { confirmDialog, notifyError, notifySuccess } from "../../lib/ui";
import { enumLabel } from "../../lib/enums";
import { t } from "../../lib/i18n";
import { fmtDate, fmtDateTime } from "../../lib/locale";
import { Money } from "./Money";

/** Hoy como `YYYY-MM-DD` en el reloj del navegador: sólo un valor inicial del filtro. */
function today(): string {
  const now = new Date();
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, "0")}-${String(now.getDate()).padStart(2, "0")}`;
}

// --- antigüedad ---------------------------------------------------------------------------------

/** Saldo abierto a una fecha por transportista y moneda, en tramos desde la fecha de servicio. */
export function AccrualAgingTab({ companyId }: { companyId: string }) {
  const [asOf, setAsOf] = useState(today());
  const agingQuery = useQuery({
    queryKey: ["settlement-accrual-aging", companyId, asOf],
    queryFn: ({ signal }) => fetchAccrualAging(companyId, asOf || undefined, signal),
  });

  const columns: DataTableColumn<AccrualAgingRow>[] = [
    { key: "carrier", header: t("Transportista"), render: (row) => row.carrierName ?? "—" },
    { key: "accruals", header: t("Provisiones"), numeric: true, render: (row) => String(row.accruals) },
    { key: "d0", header: t("0-30 días"), numeric: true, render: (row) => <Money value={row.days0To30} currency={row.currency} /> },
    { key: "d31", header: t("31-60 días"), numeric: true, render: (row) => <Money value={row.days31To60} currency={row.currency} /> },
    { key: "d61", header: t("61-90 días"), numeric: true, render: (row) => <Money value={row.days61To90} currency={row.currency} /> },
    { key: "d90", header: t("Más de 90"), numeric: true, render: (row) => <Money value={row.over90} currency={row.currency} /> },
    { key: "total", header: t("Total"), numeric: true, render: (row) => <Money value={row.total} currency={row.currency} strong /> },
  ];

  return (
    <>
      <Box sx={{ display: "flex", gap: 1.5, mb: 2, alignItems: "center", flexWrap: "wrap" }}>
        <TextField
          size="small" type="date" label={t("Saldo al")} value={asOf}
          onChange={(e) => setAsOf(e.target.value)} slotProps={{ inputLabel: { shrink: true } }}
        />
        <Typography variant="caption" color="text.secondary">
          {t("Cada moneda va en su propia fila: TMS no convierte monedas.")}
        </Typography>
      </Box>
      <DataTable
        rows={agingQuery.data?.rows ?? []}
        columns={columns}
        isLoading={agingQuery.isLoading}
        error={agingQuery.isError ? describeApiError(agingQuery.error as ApiError) : null}
        onRetry={() => void agingQuery.refetch()}
        rowKey={(row) => `${row.carrierId ?? "-"}-${row.currency}`}
        emptyTitle={t("No hay saldo provisionado abierto a esta fecha")}
      />
    </>
  );
}

// --- viajes sin provisión -----------------------------------------------------------------------

/**
 * Viajes ejecutados sin provisión, con el motivo. La flota propia no aparece: no es un flete por
 * pagar. «Provisionar ahora» corre el mismo barrido que el automático sobre el rango elegido.
 */
export function UnaccruedTripsTab({ companyId }: { companyId: string }) {
  const { hasCapability } = useCompany();
  const canManage = hasCapability(CAPABILITY.ACCRUAL_MANAGE);
  const [from, setFrom] = useState(`${today().slice(0, 8)}01`);
  const [to, setTo] = useState(today());
  const [busy, setBusy] = useState(false);
  const valid = from !== "" && to !== "" && from <= to;
  const unaccruedQuery = useQuery({
    queryKey: ["settlement-unaccrued", companyId, from, to],
    queryFn: ({ signal }) => fetchUnaccruedTrips(companyId, from, to, signal),
    enabled: valid,
  });

  async function sweep() {
    // Books journal entries for every unaccrued executed trip in the range: asked once, with the range.
    const confirmed = await confirmDialog({
      title: t("¿Provisionar los viajes del rango?"),
      text: t("Se crearán provisiones (asientos contables) para los viajes ejecutados del {{from}} al {{to}} que aún no tienen. Las provisiones no se borran: se revierten.", {
        from: fmtDate(from), to: fmtDate(to),
      }),
      confirmLabel: t("Provisionar"),
    });
    if (!confirmed) {
      return;
    }
    setBusy(true);
    try {
      const result = await sweepAccruals(companyId, from, to);
      notifySuccess(
        t("Barrido terminado"),
        t("{{created}} provisiones creadas, {{adjusted}} ajustadas, {{unaccrued}} viajes sin costo calculable.", {
          created: result.created, adjusted: result.adjusted, unaccrued: result.unaccrued,
        }),
      );
      if ((result.failed ?? 0) > 0) {
        notifyError(t("Algunos viajes no se pudieron provisionar"),
          t("{{failed}} viajes fallaron y se reintentarán en el próximo barrido.", { failed: result.failed ?? 0 }));
      }
      await unaccruedQuery.refetch();
    } catch (error) {
      notifyError(t("No se pudo provisionar"), describeApiError(error as ApiError));
    } finally {
      setBusy(false);
    }
  }

  const columns: DataTableColumn<UnaccruedTripRow>[] = [
    { key: "shipment", header: t("Viaje"), render: (row) => row.shipmentNumber ?? "—" },
    { key: "carrier", header: t("Transportista"), render: (row) => row.carrierName ?? "—" },
    { key: "executedAt", header: t("Ejecutado"), render: (row) => fmtDateTime(row.executedAt) },
    {
      key: "reason",
      header: t("Motivo"),
      render: (row) => (
        <StatusChip
          label={enumLabel("unaccruedReason", row.reason)}
          tone={row.reason === "PENDING_SWEEP" ? "open" : "inProgress"}
        />
      ),
    },
  ];

  return (
    <>
      <Box sx={{ display: "flex", gap: 1.5, mb: 2, alignItems: "center", flexWrap: "wrap" }}>
        <TextField size="small" type="date" label={t("Desde")} value={from}
          onChange={(e) => setFrom(e.target.value)} slotProps={{ inputLabel: { shrink: true } }} />
        <TextField size="small" type="date" label={t("Hasta")} value={to}
          onChange={(e) => setTo(e.target.value)} slotProps={{ inputLabel: { shrink: true } }} />
        {canManage && (
          <Button variant="contained" startIcon={<BoltRounded />} disabled={!valid || busy} onClick={() => void sweep()}>
            {t("Provisionar ahora")}
          </Button>
        )}
      </Box>
      {unaccruedQuery.data?.truncated && (
        <Alert severity="warning" sx={{ mb: 2 }}>{t("La lista se cortó en 500 viajes. Acota el rango de fechas.")}</Alert>
      )}
      <DataTable
        rows={unaccruedQuery.data?.rows ?? []}
        columns={columns}
        isLoading={unaccruedQuery.isLoading && valid}
        error={unaccruedQuery.isError ? describeApiError(unaccruedQuery.error as ApiError) : null}
        onRetry={() => void unaccruedQuery.refetch()}
        rowKey={(row) => row.tripId}
        emptyTitle={t("Todos los viajes ejecutados del rango tienen provisión")}
        emptyMessage={t("Un viaje sin costo calculable aparece aquí con su motivo; nunca se le inventa una cifra.")}
      />
    </>
  );
}

// --- asientos -----------------------------------------------------------------------------------

/** El asiento del período con las cuentas mapeadas; sin mapeo, la cuenta queda vacía y se avisa. */
export function AccrualJournalTab({ companyId }: { companyId: string }) {
  const [period, setPeriod] = useState(today().slice(0, 7));
  const [requested, setRequested] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const journalQuery = useQuery({
    queryKey: ["settlement-accrual-journal", companyId, requested],
    queryFn: ({ signal }) => fetchAccrualJournal(companyId, requested ?? "", signal),
    enabled: requested !== null,
  });
  const journal = journalQuery.data;

  async function downloadCsv() {
    setBusy(true);
    try {
      saveDownloadedFile(await downloadAccrualJournalCsv(companyId, period), `accrual-journal-${period}.csv`);
    } catch (error) {
      notifyError(t("No se pudo descargar"), describeApiError(error as ApiError));
    } finally {
      setBusy(false);
    }
  }

  function downloadJson() {
    if (!journal) return;
    const blob = new Blob([JSON.stringify(journal, null, 2)], { type: "application/json" });
    saveDownloadedFile({ blob, fileName: null }, `accrual-journal-${journal.period}.json`);
  }

  const columns: DataTableColumn<AccrualJournalRow>[] = [
    { key: "date", header: t("Fecha"), render: (row) => fmtDate(row.entryDate) },
    { key: "type", header: t("Movimiento"), render: (row) => enumLabel("accrualEntryType", row.entryType) },
    { key: "shipment", header: t("Viaje"), render: (row) => row.shipmentNumber ?? "—" },
    { key: "carrier", header: t("Transportista"), render: (row) => row.carrierName ?? "—" },
    { key: "debit", header: t("Debe"), render: (row) => <Account concept={row.debitConcept} account={row.debitAccount} /> },
    { key: "credit", header: t("Haber"), render: (row) => <Account concept={row.creditConcept} account={row.creditAccount} /> },
    { key: "amount", header: t("Importe"), numeric: true, render: (row) => <Money value={row.amount} currency={row.currency} strong /> },
  ];

  return (
    <>
      <Box sx={{ display: "flex", gap: 1.5, mb: 2, alignItems: "center", flexWrap: "wrap" }}>
        <TextField size="small" type="month" label={t("Período")} value={period}
          onChange={(e) => setPeriod(e.target.value)} slotProps={{ inputLabel: { shrink: true } }} />
        <Button variant="contained" disabled={period === ""} onClick={() => setRequested(period)}>
          {t("Ver asiento")}
        </Button>
        <Button variant="outlined" startIcon={<DownloadRounded />} disabled={period === "" || busy} onClick={() => void downloadCsv()}>
          {t("Descargar CSV")}
        </Button>
        <Button variant="outlined" startIcon={<DownloadRounded />} disabled={!journal} onClick={downloadJson}>
          {t("Descargar JSON")}
        </Button>
      </Box>
      {journal && journal.warnings.length > 0 && (
        <Alert severity="warning" sx={{ mb: 2 }}>
          <Typography variant="body2" sx={{ fontWeight: 700 }}>{t("Hay cuentas sin mapeo: quedan vacías en el archivo.")}</Typography>
          {journal.warnings.map((warning) => <Typography key={warning} variant="body2">{warning}</Typography>)}
        </Alert>
      )}
      {requested !== null && (
        <DataTable
          rows={journal?.rows ?? []}
          columns={columns}
          isLoading={journalQuery.isLoading}
          error={journalQuery.isError ? describeApiError(journalQuery.error as ApiError) : null}
          onRetry={() => void journalQuery.refetch()}
          rowKey={(row) => row.entryId}
          emptyTitle={t("No hay movimientos en este período")}
        />
      )}
    </>
  );
}

function Account({ concept, account }: { concept: string; account: string | null }) {
  return (
    <Box>
      <Typography variant="body2" sx={{ fontWeight: 600, color: account ? undefined : "warning.main" }}>
        {account ?? t("Sin cuenta")}
      </Typography>
      <Typography variant="caption" color="text.secondary">{enumLabel("glConcept", concept)}</Typography>
    </Box>
  );
}
