import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { Box, Chip, MenuItem, TextField, Typography } from "@mui/material";
import {
  BroadcastOnPersonalRounded, DirectionsRunRounded, ScheduleRounded, ReportProblemRounded, BlockRounded,
  DoneAllRounded, HourglassBottomRounded, PendingActionsRounded,
} from "@mui/icons-material";
import { fetchCarriers } from "../../shared/api/carriersApi";
import {
  DELAYED_TIMELINESS, fetchControlTower, fetchControlTowerTrips,
  type ControlTowerTripView, type DepartureTimeliness,
} from "../../shared/api/controlTowerApi";
import type { ApiError } from "../../shared/api/httpClient";
import { fetchOrigins } from "../../shared/api/originsApi";
import { TRIP_STATUSES, type TripStatus } from "../../shared/api/planningApi";
import { describeApiError } from "../../shared/api/problemMessages";
import { useCompany } from "../../shared/company/CompanyContext";
import {
  DataTable, ErrorState, KpiCard, LoadingState, PageHeader, Pagination, StatusChip, Toolbar,
  type DataTableColumn,
} from "../../shared/ui/components";
import { TRIP_STATUS_TONE } from "../../shared/ui/statusTones";
import { ICON_TINTS } from "../../shared/ui/navConfig";
import { enumLabel } from "../../lib/enums";
import type { StatusTone } from "../../theme";
import { t } from "../../lib/i18n";
import { fmtDateTime, fmtMinutes, fmtQuantity, fmtTime } from "../../lib/locale";
import { BlockersPanel,
  AdvisoriesPanel, ExceptionsPanel, OutstandingStopsPanel, WorkloadPanel } from "./ControlTowerPanels";

const PAGE_SIZE = 20;

/** Cada minuto: la torre es una pantalla que se deja abierta, y dos de sus contadores cambian
 * solos según avanza el reloj. */
const POLL_MS = 60_000;

const TIMELINESS_TONE: Record<DepartureTimeliness, StatusTone> = {
  NOT_APPLICABLE: "neutral",
  NOT_SCHEDULED: "neutral",
  SCHEDULED: "open",
  OVERDUE: "overdue",
  ON_TIME: "done",
  LATE: "overdue",
};

/**
 * La torre de control: un día de operación de transporte, de solo lectura.
 *
 * Dos consultas y no una. La de arriba trae el día entero —los KPIs y los tres paneles— y está
 * acotada solo por empresa y fecha: la franja es la foto completa del día, así que filtrar por un
 * transportista nunca puede hacer desaparecer del conteo la incidencia abierta de otro. La de
 * abajo es la tabla operativa, y esa sí obedece a los filtros.
 *
 * Ningún veredicto se calcula aquí. "Tarde", "vencido" y "cuánto va lleno" los decide el backend,
 * que además manda el `generatedAt` contra el que los juzgó: una pestaña que lleva media hora
 * abierta puede así decir que su veredicto es viejo, en lugar de parecer actual.
 */
export function ControlTowerPage() {
  const { selected } = useCompany();
  const companyId = selected?.id ?? "";
  const navigate = useNavigate();

  const [date, setDate] = useState("");
  const [draft, setDraft] = useState({ originId: "", carrierId: "", status: "" as TripStatus | "" });
  const [filters, setFilters] = useState({ originId: "", carrierId: "", status: "" as TripStatus | "" });
  const [page, setPage] = useState(0);

  const overviewQuery = useQuery({
    queryKey: ["control-tower", companyId, date],
    queryFn: ({ signal }) => fetchControlTower({ companyId, date: date || undefined, signal }),
    enabled: companyId !== "",
    refetchInterval: POLL_MS,
  });

  const tripsQuery = useQuery({
    queryKey: ["control-tower-trips", companyId, date, page, filters],
    queryFn: ({ signal }) =>
      fetchControlTowerTrips({
        companyId,
        date: date || undefined,
        originId: filters.originId || undefined,
        carrierId: filters.carrierId || undefined,
        status: filters.status || undefined,
        page,
        size: PAGE_SIZE,
        signal,
      }),
    enabled: companyId !== "",
    placeholderData: keepPreviousData,
    refetchInterval: POLL_MS,
  });

  const originsQuery = useQuery({
    queryKey: ["origins-for-tower", companyId],
    queryFn: ({ signal }) => fetchOrigins({ companyId, size: 200, active: true, sort: "code,asc", signal }),
    enabled: companyId !== "",
  });
  const carriersQuery = useQuery({
    queryKey: ["carriers-for-tower", companyId],
    queryFn: ({ signal }) => fetchCarriers({ companyId, size: 200, active: true, sort: "code,asc", signal }),
    enabled: companyId !== "",
  });

  function applyFilters() { setFilters(draft); setPage(0); }
  function resetFilters() {
    setDraft({ originId: "", carrierId: "", status: "" });
    setFilters({ originId: "", carrierId: "", status: "" });
    setPage(0);
  }

  const columns: DataTableColumn<ControlTowerTripView>[] = [
    {
      key: "shipment",
      header: t("Envío"),
      render: (row) => (
        <Box>
          <Typography variant="body2" sx={{ fontWeight: 800 }}>{row.trip.shipmentNumber}</Typography>
          <Typography variant="caption" color="text.secondary">
            {row.trip.vehicleLicensePlate ?? t("Sin vehículo asignado")}
          </Typography>
        </Box>
      ),
    },
    {
      key: "status",
      header: t("Estado"),
      render: (row) => <StatusChip label={enumLabel("tripStatus", row.trip.status)} tone={TRIP_STATUS_TONE[row.trip.status]} />,
    },
    {
      key: "timeliness",
      header: t("Salida"),
      // El veredicto y los minutos juntos: "tarde" a secas no distingue tres minutos de noventa
      // y cinco, y el backend manda el retraso justo para que no haya que pintar los dos igual.
      render: (row) => (
        <Box sx={{ display: "flex", alignItems: "center", gap: 0.75, flexWrap: "wrap" }}>
          <StatusChip
            label={enumLabel("departureTimeliness", row.departureTimeliness)}
            tone={TIMELINESS_TONE[row.departureTimeliness]}
          />
          {row.departureDelayMinutes !== null && row.departureDelayMinutes > 0 && (
            <Typography variant="caption" sx={{ fontWeight: 800, color: "error.main" }}>
              +{fmtMinutes(row.departureDelayMinutes)}
            </Typography>
          )}
        </Box>
      ),
    },
    { key: "carrier", header: t("Transportista"), render: (row) => row.trip.carrierName ?? t("Flota propia") },
    {
      key: "progress",
      header: t("Paradas"),
      numeric: true,
      render: (row) => (
        <Typography variant="body2" sx={{ fontVariantNumeric: "tabular-nums" }}>
          {fmtQuantity(row.stopsResolved)} / {fmtQuantity(row.stopsTotal)}
        </Typography>
      ),
    },
    {
      key: "pastWindow",
      header: t("Fuera de ventana"),
      numeric: true,
      render: (row) => row.stopsPastWindow === 0
        ? <Typography variant="body2" color="text.disabled">-</Typography>
        : <Typography variant="body2" sx={{ fontWeight: 800, color: "warning.main" }}>{fmtQuantity(row.stopsPastWindow)}</Typography>,
    },
    {
      key: "next",
      header: t("Próxima parada"),
      render: (row) => row.nextStopSequence === null ? "-" : (
        <Typography variant="body2">
          {row.nextStopSequence}
          {row.nextStopDueAt && ` · ${fmtTime(row.nextStopDueAt)}`}
        </Typography>
      ),
    },
    {
      key: "exceptions",
      header: t("Incidencias"),
      numeric: true,
      render: (row) => row.openExceptions === 0
        ? <Typography variant="body2" color="text.disabled">-</Typography>
        : <Chip size="small" color="error" label={fmtQuantity(row.openExceptions)} />,
    },
  ];

  if (overviewQuery.isPending) return <LoadingState label={t("Cargando la torre de control...")} />;
  if (overviewQuery.isError) {
    return (
      <ErrorState
        message={describeApiError(overviewQuery.error as ApiError)}
        onRetry={() => void overviewQuery.refetch()}
      />
    );
  }

  const overview = overviewQuery.data;
  const summary = overview.summary;
  const pageData = tripsQuery.data;
  const delayedCount = (pageData?.content ?? []).filter((row) => DELAYED_TIMELINESS.includes(row.departureTimeliness)).length;

  return (
    <>
      <PageHeader
        icon={<BroadcastOnPersonalRounded />}
        tint={ICON_TINTS["/control-tower"]}
        title={t("Torre de control")}
        subtitle={t("Un día de operación de transporte, de solo lectura.")}
        meta={
          <Chip
            size="small" variant="outlined"
            label={t("Al {{time}}", { time: fmtDateTime(overview.generatedAt) })}
          />
        }
        onRefresh={() => { void overviewQuery.refetch(); void tripsQuery.refetch(); }}
        refreshing={overviewQuery.isFetching || tripsQuery.isFetching}
        actions={
          <TextField
            size="small" type="date" label={t("Día")}
            value={date || overview.date}
            onChange={(e) => { setDate(e.target.value); setPage(0); }}
            slotProps={{ inputLabel: { shrink: true } }}
            sx={{ width: 175 }}
          />
        }
      />

      {/* La franja del día entero: no obedece a los filtros de abajo a propósito. */}
      <Box sx={{
        display: "grid", gap: 2, mb: 3,
        gridTemplateColumns: { xs: "1fr", sm: "repeat(2, minmax(0,1fr))", lg: "repeat(4, minmax(0,1fr))" },
      }}>
        <KpiCard icon={<DirectionsRunRounded />} color="warning.main" title={t("En tránsito")} value={fmtQuantity(summary.tripsInTransit)} />
        <KpiCard icon={<ScheduleRounded />} color="info.main" title={t("Programados")} value={fmtQuantity(summary.tripsScheduled)} />
        <KpiCard icon={<DoneAllRounded />} color="success.main" title={t("Completados")} value={fmtQuantity(summary.tripsCompleted)} />
        <KpiCard
          icon={<HourglassBottomRounded />} color="error.main"
          title={t("Vencidos sin salir")} sub={t("Debían haber salido")}
          value={fmtQuantity(summary.tripsOverdue)}
        />
        <KpiCard icon={<PendingActionsRounded />} color="error.main" title={t("Salieron tarde")} value={fmtQuantity(summary.tripsDepartedLate)} />
        <KpiCard icon={<ReportProblemRounded />} color="error.main" title={t("Incidencias abiertas")} value={fmtQuantity(summary.openExceptions)} />
        {/* JOB 12: lo único de esta fila que mira hacia adelante. */}
        <KpiCard
          icon={<BlockRounded />} color="warning.main"
          title={t("No pueden salir")} sub={t("Bloqueados ahora mismo")}
          value={fmtQuantity(summary.blockedShipments)}
        />
        <KpiCard
          icon={<ScheduleRounded />} color="warning.main"
          title={t("Paradas pendientes")} sub={t("{{n}} fuera de ventana", { n: fmtQuantity(summary.stopsPastWindow) })}
          value={fmtQuantity(summary.outstandingStops)}
        />
        {/* `null` y no `0` cuando la cuenta no puede ver pedidos: un cero sería una afirmación
            sobre una cola que la respuesta no tenía permiso para mirar. */}
        {summary.ordersUnplanned !== null && (
          <KpiCard icon={<PendingActionsRounded />} color="text.secondary" title={t("Pedidos sin planificar")} value={fmtQuantity(summary.ordersUnplanned)} />
        )}
      </Box>

      <Box sx={{
        display: "grid", gap: 3, mb: 3, alignItems: "start",
        gridTemplateColumns: { xs: "1fr", lg: "repeat(3, minmax(0, 1fr))" },
      }}>
        <WorkloadPanel items={overview.workload} total={summary.tripsInTransit + summary.tripsScheduled} />
        <ExceptionsPanel items={overview.openExceptions} total={summary.openExceptions} />
        <OutstandingStopsPanel items={overview.outstandingStops} total={summary.outstandingStops} />
        <BlockersPanel items={overview.blockers} total={summary.blockedShipments} />
        {/* Debajo de los bloqueadores y visiblemente distinto (JOB 23). Dos corrientes, dos
            contadores: "qué está atascado" y "qué conviene saber" son preguntas diferentes, y una
            lista única haría que un redondeo se leyera como un camión parado. */}
        <AdvisoriesPanel items={overview.advisories} total={summary.openAdvisories} />
      </Box>

      <Toolbar
        onApply={applyFilters}
        onReset={resetFilters}
        filters={
          <>
            <TextField
              select size="small" label={t("Origen")} value={draft.originId}
              onChange={(e) => setDraft({ ...draft, originId: e.target.value })}
              sx={{ minWidth: 190 }}
            >
              <MenuItem value="">{t("Todos los orígenes")}</MenuItem>
              {(originsQuery.data?.content ?? []).map((origin) => (
                <MenuItem key={origin.id} value={origin.id}>{origin.name}</MenuItem>
              ))}
            </TextField>
            <TextField
              select size="small" label={t("Transportista")} value={draft.carrierId}
              onChange={(e) => setDraft({ ...draft, carrierId: e.target.value })}
              sx={{ minWidth: 200 }}
            >
              <MenuItem value="">{t("Todos los transportistas")}</MenuItem>
              {(carriersQuery.data?.content ?? []).map((carrier) => (
                <MenuItem key={carrier.id} value={carrier.id}>{carrier.businessName}</MenuItem>
              ))}
            </TextField>
            <TextField
              select size="small" label={t("Estado")} value={draft.status}
              onChange={(e) => setDraft({ ...draft, status: e.target.value as TripStatus | "" })}
              sx={{ minWidth: 180 }}
            >
              <MenuItem value="">{t("Todos los estados")}</MenuItem>
              {TRIP_STATUSES.map((status) => (
                <MenuItem key={status} value={status}>{enumLabel("tripStatus", status)}</MenuItem>
              ))}
            </TextField>
          </>
        }
      />

      {delayedCount > 0 && (
        <Typography variant="caption" color="error.main" sx={{ display: "block", mb: 1, fontWeight: 700 }}>
          {t("{{count}} envíos de esta página salieron tarde o siguen sin salir.", { count: delayedCount })}
        </Typography>
      )}

      <DataTable
        columns={columns}
        rows={pageData?.content ?? []}
        total={pageData?.totalElements}
        rowKey={(row) => row.trip.id}
        isLoading={tripsQuery.isPending}
        error={tripsQuery.isError ? describeApiError(tripsQuery.error as ApiError) : null}
        onRetry={() => void tripsQuery.refetch()}
        emptyTitle={t("Sin envíos")}
        emptyMessage={t("Ningún envío coincide con los filtros seleccionados.")}
        onRowClick={(row) => navigate(`/trips/${row.trip.id}`)}
        rowAccent={(row) => DELAYED_TIMELINESS.includes(row.departureTimeliness) ? "#C0303A" : null}
        footer={pageData ? <Pagination page={pageData} onPageChange={setPage} /> : undefined}
      />
    </>
  );
}
