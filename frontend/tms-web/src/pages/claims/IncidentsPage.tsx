import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Link as RouterLink, useNavigate } from "react-router-dom";
import { Alert, Box, Button, Link, Typography } from "@mui/material";
import {
  CheckRounded, GavelRounded, HourglassTopRounded, ReportProblemRounded, WarningAmberRounded,
} from "@mui/icons-material";
import { CAPABILITY } from "../../shared/api/meApi";
import {
  CLAIM_TYPES, fetchIncidents, type IncidentKind, type IncidentQuery, type IncidentRow, type IncidentStatusGroup,
} from "../../shared/api/claimsApi";
import { resolveTripException } from "../../shared/api/planningApi";
import { fetchCarriers } from "../../shared/api/carriersApi";
import type { ApiError } from "../../shared/api/httpClient";
import { describeApiError, describePlanningError } from "../../shared/api/problemMessages";
import { DataTable, FilterBar, KpiCard, PageHeader, StatusChip, type DataTableColumn } from "../../shared/ui/components";
import { ICON_TINTS } from "../../shared/ui/navConfig";
import { useCompany } from "../../shared/company/CompanyContext";
import { notifyError, notifySuccess, promptDialog } from "../../lib/ui";
import { ENUM_LABELS, enumLabel } from "../../lib/enums";
import { t } from "../../lib/i18n";
import { fmtDateTime, fmtQuantity } from "../../lib/locale";
import { Money } from "../settlement/Money";
import { CLAIM_STATUS_TONE, ageLabel, ageTone } from "./claimUi";
import type { ClaimStatus } from "../../shared/api/claimsApi";

interface Filters {
  status: string;
  kind: string;
  carrierId: string;
  type: string;
  from: string;
  to: string;
}

const DEFAULTS: Filters = { status: "OPEN", kind: "", carrierId: "", type: "", from: "", to: "" };

const EXCEPTION_TYPES = Object.keys(ENUM_LABELS.tripExceptionType);

/** El tipo de una fila en su vocabulario: el de las incidencias o el de los reclamos. */
function typeLabel(row: IncidentRow): string {
  return row.kind === "CLAIM" ? enumLabel("claimType", row.type) : enumLabel("tripExceptionType", row.type);
}

/**
 * Centro de incidencias (V71): las incidencias de viaje abiertas y los reclamos de carga en una sola
 * lista, con los mismos filtros y su antigüedad. Lo más viejo y abierto, arriba.
 *
 * No es dueño de nada: resolver una incidencia sigue siendo de planificación (el mismo endpoint que el
 * espacio del viaje) y un reclamo se trabaja en «Reclamos de carga». Desde una incidencia se puede
 * abrir el reclamo ya prellenado.
 */
export function IncidentsPage() {
  const { selected, hasCapability, hasPermission } = useCompany();
  const companyId = selected?.id ?? "";
  const canResolve = hasPermission("planning.trip:execute");
  const canOpenClaim = hasCapability(CAPABILITY.CLAIMS_MANAGE);
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [filters, setFilters] = useState<Filters>(DEFAULTS);
  const [busyId, setBusyId] = useState<string | null>(null);

  const query: IncidentQuery = {
    status: filters.status as IncidentStatusGroup,
    kind: filters.kind as IncidentKind | "",
    carrierId: filters.carrierId,
    type: filters.type,
    from: filters.from,
    to: filters.to,
  };
  const incidentsQuery = useQuery({
    queryKey: ["incidents", companyId, query],
    queryFn: ({ signal }) => fetchIncidents(companyId, query, signal),
    enabled: companyId !== "",
  });
  const carriersQuery = useQuery({
    queryKey: ["carriers-for-incidents", companyId],
    queryFn: ({ signal }) => fetchCarriers({ companyId, size: 200, sort: "businessName,asc", signal }),
    enabled: companyId !== "",
  });

  function refresh() {
    void queryClient.invalidateQueries({ queryKey: ["incidents", companyId] });
  }

  async function resolve(row: IncidentRow) {
    const notes = await promptDialog({
      title: t("¿Resolver la incidencia?"),
      text: `${typeLabel(row)} · ${row.shipmentNumber ?? ""}`,
      inputLabel: t("Cómo se resolvió"),
      required: true,
      maxLength: 1000,
      confirmLabel: t("Resolver"),
    });
    if (notes === null) return;
    setBusyId(row.id);
    try {
      await resolveTripException(companyId, row.tripId, row.id, { notes });
      notifySuccess(t("Incidencia resuelta"), row.shipmentNumber ?? undefined);
      refresh();
    } catch (error) {
      notifyError(t("No se pudo completar la acción"), describePlanningError(error as ApiError));
    } finally {
      setBusyId(null);
    }
  }

  const columns: DataTableColumn<IncidentRow>[] = [
    {
      key: "kind",
      header: t("Qué"),
      render: (row) => (
        <Box>
          <Typography variant="body2" sx={{ fontWeight: 700 }}>
            {row.kind === "CLAIM" ? row.reference : enumLabel("incidentKind", row.kind)}
          </Typography>
          <Typography variant="caption" color="text.secondary">{typeLabel(row)}</Typography>
        </Box>
      ),
    },
    {
      key: "status",
      header: t("Estado"),
      render: (row) => row.kind === "CLAIM"
        ? <StatusChip label={enumLabel("claimStatus", row.status)} tone={CLAIM_STATUS_TONE[row.status as ClaimStatus] ?? "neutral"} />
        : <StatusChip label={enumLabel("tripExceptionStatus", row.status)} tone={row.open ? "overdue" : "done"} />,
    },
    {
      key: "shipment",
      header: t("Envío"),
      render: (row) => (
        <Link component={RouterLink} to={`/trips/${row.tripId}`} underline="hover" onClick={(e) => e.stopPropagation()}>
          {row.shipmentNumber ?? "—"}
        </Link>
      ),
    },
    { key: "carrier", header: t("Transportista"), render: (row) => row.carrierName ?? t("Flota propia") },
    { key: "occurredAt", header: t("Desde"), render: (row) => fmtDateTime(row.occurredAt) },
    {
      key: "age",
      header: t("Antigüedad"),
      numeric: true,
      render: (row) => (
        <Typography variant="body2" component="span" sx={{ color: ageTone(row.ageDays, row.open), fontVariantNumeric: "tabular-nums" }}>
          {ageLabel(row.ageDays)}
        </Typography>
      ),
    },
    {
      key: "amount",
      header: t("Importe"),
      numeric: true,
      render: (row) => row.amount === null || row.currency === null ? "—" : <Money value={row.amount} currency={row.currency} />,
    },
    {
      key: "summary",
      header: t("Detalle"),
      render: (row) => (
        <Box sx={{ maxWidth: 320 }}>
          <Typography variant="body2" noWrap title={row.summary ?? undefined}>{row.summary ?? "—"}</Typography>
          {row.claims.length > 0 && (
            <Typography variant="caption" color="text.secondary">{t("Reclamado en {{list}}", { list: row.claims.join(", ") })}</Typography>
          )}
        </Box>
      ),
    },
    {
      key: "actions",
      header: "",
      actions: true,
      render: (row) => (
        <Box sx={{ display: "flex", gap: 0.5, justifyContent: "flex-end" }}>
          {row.kind === "EXCEPTION" && row.open && canResolve && (
            <Button size="small" startIcon={<CheckRounded />} disabled={busyId === row.id}
              onClick={(e) => { e.stopPropagation(); void resolve(row); }}>
              {t("Resolver")}
            </Button>
          )}
          {row.kind === "EXCEPTION" && canOpenClaim && row.carrierId && (
            <Button size="small" startIcon={<GavelRounded />}
              onClick={(e) => { e.stopPropagation(); navigate(`/settlement/claims?fromException=${row.id}`); }}>
              {t("Abrir reclamo")}
            </Button>
          )}
        </Box>
      ),
    },
  ];

  const view = incidentsQuery.data;
  const ageing = view?.totals.ageing;
  const typeOptions = [
    ...EXCEPTION_TYPES.map((type) => ({ id: type, label: `${t("Incidencia")}: ${enumLabel("tripExceptionType", type)}` })),
    ...CLAIM_TYPES.filter((type) => !EXCEPTION_TYPES.includes(type))
      .map((type) => ({ id: type, label: `${t("Reclamo")}: ${enumLabel("claimType", type)}` })),
  ];
  return (
    <>
      <PageHeader
        icon={<ReportProblemRounded />}
        tint={ICON_TINTS["/incidents"]}
        title={t("Centro de incidencias")}
        subtitle={t("Incidencias de viaje y reclamos de carga en una sola lista, lo más antiguo primero.")}
        onRefresh={refresh}
        refreshing={incidentsQuery.isFetching}
      />

      <Box sx={{
        display: "grid", gap: 2, mb: 2,
        gridTemplateColumns: { xs: "1fr", sm: "repeat(2, minmax(0,1fr))", lg: "repeat(4, minmax(0,1fr))" },
      }}>
        <KpiCard icon={<ReportProblemRounded />} color="error.main" title={t("Incidencias abiertas")}
          loading={incidentsQuery.isPending} value={fmtQuantity(view?.totals.openExceptions ?? 0)} />
        <KpiCard icon={<GavelRounded />} color="warning.main" title={t("Reclamos activos")}
          loading={incidentsQuery.isPending} value={fmtQuantity(view?.totals.activeClaims ?? 0)} />
        <KpiCard icon={<HourglassTopRounded />} color="info.main" title={t("Abiertas hace 8 a 30 días")}
          loading={incidentsQuery.isPending} value={fmtQuantity(ageing?.from8To30 ?? 0)}
          sub={t("{{n}} de hasta 7 días", { n: fmtQuantity(ageing?.upTo7 ?? 0) })} />
        <KpiCard icon={<WarningAmberRounded />} color="error.main" title={t("Más de 30 días")}
          loading={incidentsQuery.isPending}
          value={fmtQuantity((ageing?.from31To60 ?? 0) + (ageing?.over60 ?? 0))}
          sub={t("{{n}} de más de 60", { n: fmtQuantity(ageing?.over60 ?? 0) })} />
      </Box>

      <FilterBar
        value={filters}
        defaults={DEFAULTS}
        onChange={setFilters}
        fields={[
          {
            type: "select", key: "status", label: t("Estado"),
            options: [
              { id: "OPEN", label: t("Abiertas") },
              { id: "CLOSED", label: t("Atendidas") },
              { id: "ALL", label: t("Todas") },
            ],
          },
          {
            type: "select", key: "kind", label: t("Qué"), allLabel: t("Todo"),
            options: [
              { id: "EXCEPTION", label: t("Incidencias") },
              { id: "CLAIM", label: t("Reclamos") },
            ],
          },
          {
            type: "select", key: "carrierId", label: t("Transportista"), allLabel: t("Todos"),
            options: (carriersQuery.data?.content ?? []).map((carrier) => ({ id: carrier.id, label: carrier.businessName })),
          },
          { type: "select", key: "type", label: t("Tipo"), allLabel: t("Todos"), options: typeOptions, more: true },
          { type: "dateRange", from: "from", to: "to", label: t("Periodo") },
        ]}
      />

      {view?.truncated && (
        <Alert severity="warning" sx={{ mb: 2 }}>
          {t("Hay más de {{n}} filas: se muestran las más recientes. Acota el periodo o el transportista.", { n: view.limit })}
        </Alert>
      )}

      <DataTable
        columns={columns}
        rows={view?.rows ?? []}
        rowKey={(row) => `${row.kind}-${row.id}`}
        total={view?.rows.length}
        isLoading={incidentsQuery.isPending && companyId !== ""}
        error={incidentsQuery.isError ? describeApiError(incidentsQuery.error as ApiError) : null}
        onRetry={() => void incidentsQuery.refetch()}
        onRowClick={(row) => navigate(row.kind === "CLAIM" ? `/settlement/claims?claim=${row.id}` : `/trips/${row.tripId}`)}
        rowAccent={(row) => (row.open && row.ageDays > 30 ? "error.main" : null)}
        emptyTitle={t("Nada pendiente con estos filtros")}
        emptyMessage={t("Las incidencias se reportan desde el viaje; los reclamos, desde una entrega o una incidencia.")}
      />
    </>
  );
}
