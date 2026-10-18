import { keepPreviousData, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import {
  Alert, Box, Button, MenuItem, Tab, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, Tabs,
  TextField, Typography,
} from "@mui/material";
import { AccountBalanceWalletRounded, UndoRounded } from "@mui/icons-material";
import { CAPABILITY } from "../../shared/api/meApi";
import { fetchCarriers } from "../../shared/api/carriersApi";
import {
  ACCRUAL_STATUSES, fetchAccrual, fetchAccruals, reverseAccrual,
  type AccrualStatus, type AccrualSummaryView,
} from "../../shared/api/accrualApi";
import type { ApiError } from "../../shared/api/httpClient";
import { describeApiError } from "../../shared/api/problemMessages";
import {
  DataTable, DetailSection, FormDrawer, KeyFacts, PageHeader, Pagination, StatusChip, type DataTableColumn,
} from "../../shared/ui/components";
import { ACCRUAL_STATUS_TONE } from "../../shared/ui/statusTones";
import { ICON_TINTS } from "../../shared/ui/navConfig";
import { useCompany } from "../../shared/company/CompanyContext";
import { notifyError, notifySuccess, promptDialog } from "../../lib/ui";
import { enumLabel } from "../../lib/enums";
import { t } from "../../lib/i18n";
import { fmtDate, fmtDateTime, fmtDecimal } from "../../lib/locale";
import { AccrualAgingTab, AccrualJournalTab, UnaccruedTripsTab } from "./AccrualReportTabs";
import { GlMappingsTab } from "./GlMappingsTab";
import { Money } from "./Money";

type TabKey = "accruals" | "aging" | "unaccrued" | "journal" | "accounts";

/**
 * Provisiones de flete (migración V65, ADR-023 §1).
 *
 * La obligación con el transportista entre el servicio y la factura. Nace sola cuando el viaje se
 * despacha (o se entrega, según la empresa), se ajusta al costo real cerrado y la consume la factura
 * aprobada; una persona la revierte o cierra su remanente, siempre con motivo. Los movimientos no se
 * editan: se corrigen con otro movimiento.
 *
 * TMS no mueve dinero ni lleva libro mayor: el asiento del período es un archivo para el ERP, y una
 * cuenta sin mapeo queda vacía y avisada, nunca inventada.
 */
export function AccrualsPage() {
  const { selected, hasCapability } = useCompany();
  const companyId = selected?.id ?? "";
  const canExport = hasCapability(CAPABILITY.ACCRUAL_EXPORT);
  const [tab, setTab] = useState<TabKey>("accruals");

  return (
    <>
      <PageHeader
        icon={<AccountBalanceWalletRounded />}
        tint={ICON_TINTS["/settlement/accruals"]}
        title={t("Provisiones de flete")}
        subtitle={t("Lo que se debe a los transportistas por servicios prestados y aún no facturados. TMS provisiona y exporta; el ERP contabiliza.")}
      />

      <Tabs value={tab} onChange={(_, value: TabKey) => setTab(value)} sx={{ mb: 2 }} variant="scrollable">
        <Tab value="accruals" label={t("Provisiones")} />
        <Tab value="aging" label={t("Antigüedad")} />
        <Tab value="unaccrued" label={t("Viajes sin provisión")} />
        {canExport && <Tab value="journal" label={t("Asientos")} />}
        <Tab value="accounts" label={t("Cuentas contables")} />
      </Tabs>

      {companyId !== "" && tab === "accruals" && <AccrualListTab companyId={companyId} />}
      {companyId !== "" && tab === "aging" && <AccrualAgingTab companyId={companyId} />}
      {companyId !== "" && tab === "unaccrued" && <UnaccruedTripsTab companyId={companyId} />}
      {companyId !== "" && tab === "journal" && canExport && <AccrualJournalTab companyId={companyId} />}
      {companyId !== "" && tab === "accounts" && <GlMappingsTab companyId={companyId} />}
    </>
  );
}

interface ListFilters {
  period: string;
  carrierId: string;
  currency: string;
  status: AccrualStatus | "";
}

function AccrualListTab({ companyId }: { companyId: string }) {
  const queryClient = useQueryClient();
  const [filters, setFilters] = useState<ListFilters>({ period: "", carrierId: "", currency: "", status: "" });
  const [page, setPage] = useState(0);
  const [openId, setOpenId] = useState<string | null>(null);

  const accrualsQuery = useQuery({
    queryKey: ["settlement-accruals", companyId, filters, page],
    queryFn: ({ signal }) => fetchAccruals(companyId, {
      page, size: 25,
      period: filters.period || undefined,
      carrierId: filters.carrierId || undefined,
      currency: filters.currency.trim() || undefined,
      status: filters.status || undefined,
    }, signal),
    placeholderData: keepPreviousData,
  });
  // Sin permiso de flota la lista llega vacía: el filtro por transportista simplemente no ofrece opciones.
  const carriersQuery = useQuery({
    queryKey: ["carriers-lookup", companyId],
    queryFn: ({ signal }) => fetchCarriers({ companyId, size: 200, active: true, sort: "businessName,asc", signal }),
    retry: false,
  });

  function change(next: Partial<ListFilters>) {
    setFilters((current) => ({ ...current, ...next }));
    setPage(0);
  }

  const columns: DataTableColumn<AccrualSummaryView>[] = [
    {
      key: "shipment",
      header: t("Viaje"),
      render: (row) => (
        <Box>
          <Typography variant="body2" sx={{ fontWeight: 700 }}>{row.shipmentNumber ?? "—"}</Typography>
          <Typography variant="caption" color="text.secondary">{row.carrierName ?? "—"}</Typography>
        </Box>
      ),
    },
    { key: "serviceDate", header: t("Fecha de servicio"), render: (row) => fmtDate(row.serviceDate) },
    { key: "basis", header: t("Base"), render: (row) => enumLabel("accrualBasis", row.basis) },
    { key: "accrued", header: t("Provisionado"), numeric: true, render: (row) => <Money value={row.accruedAmount} currency={row.currency} /> },
    { key: "consumed", header: t("Consumido"), numeric: true, render: (row) => <Money value={row.consumedAmount} currency={row.currency} /> },
    { key: "balance", header: t("Saldo"), numeric: true, render: (row) => <Money value={row.balance} currency={row.currency} strong /> },
    {
      key: "status",
      header: t("Estado"),
      render: (row) => <StatusChip label={enumLabel("accrualStatus", row.status)} tone={ACCRUAL_STATUS_TONE[row.status]} />,
    },
  ];

  const pageData = accrualsQuery.data;

  return (
    <>
      <Box sx={{ display: "flex", gap: 1.5, flexWrap: "wrap", mb: 2 }}>
        <TextField
          size="small" type="month" label={t("Período de servicio")} value={filters.period}
          onChange={(e) => change({ period: e.target.value })} slotProps={{ inputLabel: { shrink: true } }}
        />
        <TextField
          size="small" select label={t("Transportista")} value={filters.carrierId} sx={{ minWidth: 220 }}
          onChange={(e) => change({ carrierId: e.target.value })}
        >
          <MenuItem value="">{t("Todos")}</MenuItem>
          {(carriersQuery.data?.content ?? []).map((carrier) => (
            <MenuItem key={carrier.id} value={carrier.id}>{carrier.businessName}</MenuItem>
          ))}
        </TextField>
        <TextField
          size="small" label={t("Moneda")} value={filters.currency} sx={{ width: 110 }}
          onChange={(e) => change({ currency: e.target.value.toUpperCase().slice(0, 3) })}
        />
        <TextField
          size="small" select label={t("Estado")} value={filters.status} sx={{ minWidth: 190 }}
          onChange={(e) => change({ status: e.target.value as AccrualStatus | "" })}
        >
          <MenuItem value="">{t("Todos")}</MenuItem>
          {ACCRUAL_STATUSES.map((status) => (
            <MenuItem key={status} value={status}>{enumLabel("accrualStatus", status)}</MenuItem>
          ))}
        </TextField>
      </Box>

      <DataTable
        rows={pageData?.content ?? []}
        columns={columns}
        isLoading={accrualsQuery.isLoading}
        error={accrualsQuery.isError ? describeApiError(accrualsQuery.error as ApiError) : null}
        onRetry={() => void accrualsQuery.refetch()}
        rowKey={(row) => row.id}
        onRowClick={(row) => setOpenId(row.id)}
        total={pageData?.totalElements}
        emptyTitle={t("No hay provisiones con estos filtros")}
        emptyMessage={t("Una provisión nace sola cuando un viaje con transportista se despacha y tiene costo. Los que no pudieron provisionarse están en «Viajes sin provisión».")}
        footer={pageData ? <Pagination page={pageData} onPageChange={setPage} /> : undefined}
      />

      {openId && (
        <AccrualDetailDrawer
          companyId={companyId}
          accrualId={openId}
          onClose={() => setOpenId(null)}
          onChanged={() => void queryClient.invalidateQueries({ queryKey: ["settlement-accruals", companyId] })}
        />
      )}
    </>
  );
}

interface AccrualDetailDrawerProps {
  companyId: string;
  accrualId: string;
  onClose: () => void;
  onChanged: () => void;
}

/** Una provisión con sus movimientos. Los movimientos suman el saldo; ninguno se edita. */
export function AccrualDetailDrawer({ companyId, accrualId, onClose, onChanged }: AccrualDetailDrawerProps) {
  const { hasCapability } = useCompany();
  // Esconder es sólo UX: el servidor exige `settlement.accrual:manage` al revertir.
  const canManage = hasCapability(CAPABILITY.ACCRUAL_MANAGE);
  const [busy, setBusy] = useState(false);
  const accrualQuery = useQuery({
    queryKey: ["settlement-accrual", companyId, accrualId],
    queryFn: ({ signal }) => fetchAccrual(companyId, accrualId, signal),
  });
  const accrual = accrualQuery.data;
  const summary = accrual?.summary;
  const releasable = summary !== undefined && summary.balance > 0
    && (summary.status === "OPEN" || summary.status === "PARTIALLY_SETTLED");
  const closing = summary?.status === "PARTIALLY_SETTLED";

  async function release() {
    if (!summary) return;
    const reason = await promptDialog({
      title: closing ? t("¿Cerrar el remanente de la provisión?") : t("¿Revertir la provisión?"),
      text: closing
        ? t("Se libera el saldo de {{amount}} {{currency}}: lo facturado se da por final. Queda un movimiento de reversión con tu motivo.", {
            amount: fmtDecimal(summary.balance, 2), currency: summary.currency,
          })
        : t("Se libera la provisión entera ({{amount}} {{currency}}): el servicio no se facturará. Queda un movimiento de reversión con tu motivo.", {
            amount: fmtDecimal(summary.balance, 2), currency: summary.currency,
          }),
      inputLabel: t("Motivo"),
      required: true,
      maxLength: 500,
      confirmLabel: closing ? t("Cerrar remanente") : t("Revertir"),
      dangerous: true,
    });
    if (reason === null) return;
    setBusy(true);
    try {
      await reverseAccrual(companyId, summary.id, reason);
      notifySuccess(closing ? t("Remanente cerrado") : t("Provisión revertida"));
      await accrualQuery.refetch();
      onChanged();
    } catch (error) {
      notifyError(t("No se pudo revertir"), describeApiError(error as ApiError));
    } finally {
      setBusy(false);
    }
  }

  return (
    <FormDrawer
      open
      size="lg"
      title={summary?.shipmentNumber ? t("Provisión del viaje {{shipment}}", { shipment: summary.shipmentNumber }) : t("Provisión")}
      subtitle={summary?.carrierName ?? undefined}
      icon={<AccountBalanceWalletRounded />}
      titleAdornment={summary
        ? <StatusChip label={enumLabel("accrualStatus", summary.status)} tone={ACCRUAL_STATUS_TONE[summary.status]} variant="solid" />
        : undefined}
      loading={accrualQuery.isLoading}
      onClose={onClose}
      footer={
        <Box sx={{ display: "flex", gap: 1, justifyContent: "flex-end" }}>
          <Button onClick={onClose}>{t("Cerrar")}</Button>
          {canManage && releasable && (
            <Button variant="contained" color="error" startIcon={<UndoRounded />} disabled={busy} onClick={() => void release()}>
              {closing ? t("Cerrar remanente") : t("Revertir")}
            </Button>
          )}
        </Box>
      }
    >
      {accrualQuery.isError && <Alert severity="error">{describeApiError(accrualQuery.error as ApiError)}</Alert>}
      {summary && accrual && (
        <Box sx={{ display: "grid", gap: 2.5 }}>
          <KeyFacts columns={4} items={[
            { label: t("Fecha de servicio"), value: fmtDate(summary.serviceDate) },
            { label: t("Base"), value: enumLabel("accrualBasis", summary.basis) },
            { label: t("Provisionado"), value: <Money value={summary.accruedAmount} currency={summary.currency} /> },
            { label: t("Consumido"), value: <Money value={summary.consumedAmount} currency={summary.currency} /> },
            { label: t("Revertido"), value: <Money value={summary.reversedAmount} currency={summary.currency} /> },
            {
              label: t("Saldo"),
              value: <Money value={summary.balance} currency={summary.currency} strong />,
              sub: summary.balance < 0 ? t("Facturado por encima de lo provisionado") : undefined,
            },
          ]} />

          <DetailSection title={t("Movimientos")}>
            <TableContainer>
              <Table size="small" aria-label={t("Movimientos")}>
                <TableHead>
                  <TableRow>
                    <TableCell>{t("Fecha")}</TableCell>
                    <TableCell>{t("Movimiento")}</TableCell>
                    <TableCell>{t("Origen")}</TableCell>
                    <TableCell>{t("Período")}</TableCell>
                    <TableCell align="right">{t("Importe")}</TableCell>
                    <TableCell>{t("Motivo")}</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {accrual.entries.map((entry) => (
                    <TableRow key={entry.id}>
                      <TableCell>{fmtDateTime(entry.createdAt)}</TableCell>
                      <TableCell>{enumLabel("accrualEntryType", entry.entryType)}</TableCell>
                      <TableCell>{enumLabel("accrualSourceType", entry.sourceType)}</TableCell>
                      <TableCell>{entry.period.slice(0, 7)}</TableCell>
                      <TableCell align="right"><Money value={entry.amount} currency={summary.currency} /></TableCell>
                      <TableCell>{entry.reason ?? "—"}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </TableContainer>
          </DetailSection>
        </Box>
      )}
    </FormDrawer>
  );
}
