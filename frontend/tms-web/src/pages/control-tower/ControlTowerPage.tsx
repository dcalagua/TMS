import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { Box, Chip, Typography } from "@mui/material";
import {
  BroadcastOnPersonalRounded, DirectionsRunRounded, ScheduleRounded, ReportProblemRounded, BlockRounded,
  DoneAllRounded, HourglassBottomRounded, PendingActionsRounded, WarehouseRounded, BusinessRounded, FlagRounded,
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
  DataTable, DateInput, ErrorState, FilterBar, LoadingState, PageHeader, Pagination, StatusChip,
  type DataTableColumn,
} from "../../shared/ui/components";
import { TRIP_STATUS_TONE } from "../../shared/ui/statusTones";
import { ICON_TINTS } from "../../shared/ui/navConfig";
import { enumLabel } from "../../lib/enums";
import type { StatusTone } from "../../theme";
import { t } from "../../lib/i18n";
import { fmtDateTime, fmtMinutes, fmtQuantity, fmtTime } from "../../lib/locale";
import { BlockersPanel, BandMetric, KpiBand,
  AdvisoriesPanel, ExceptionsPanel, OutstandingStopsPanel, WorkloadPanel } from "./ControlTowerPanels";

const PAGE_SIZE = 20;

/** Cada minuto: la torre es una pantalla que se deja abierta, y dos de sus contadores cambian
 * solos según avanza el reloj. */
const POLL_MS = 60_000;

interface TripFilters {
  originId: string;
  carrierId: string;
  status: TripStatus | "";
}

const DEFAULT_FILTERS: TripFilters = { originId: "", carrierId: "", status: "" };

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
  const [page, setPage] = useState(0);
  // Los filtros se aplican al momento: no hay borrador ni botón de aplicar.
  const [filters, setFiltersState] = useState<TripFilters>(DEFAULT_FILTERS);
  const setFilters = (next: TripFilters) => { setFiltersState(next); setPage(0); };

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
          <DateInput
            size="small" label={t("Día")}
            value={date || overview.date}
            onChange={(v) => { setDate(v); setPage(0); }}
            sx={{ width: 175 }}
          />
        }
      />

      {/* La franja del día entero: no obedece a los filtros de abajo a propósito. Dos bandas: lo
          que pasa con los envíos y lo que está en riesgo. */}
      <Box sx={{
        display: "grid", gap: 2, mb: 3,
        gridTemplateColumns: {
          xs: "1fr",
          lg: summary.ordersUnplanned !== null ? "minmax(0,5fr) minmax(0,4fr)" : "minmax(0,5fr) minmax(0,3fr)",
        },
      }}>
        <KpiBand title={t("Envíos")}>
          <BandMetric icon={<DirectionsRunRounded />} color="warning.main" title={t("En tránsito")} value={fmtQuantity(summary.tripsInTransit)} />
          <BandMetric icon={<ScheduleRounded />} color="info.main" title={t("Programados")} value={fmtQuantity(summary.tripsScheduled)} />
          <BandMetric icon={<DoneAllRounded />} color="success.main" title={t("Completados")} value={fmtQuantity(summary.tripsCompleted)} />
          <BandMetric
            icon={<HourglassBottomRounded />} color="error.main"
            title={t("Vencidos sin salir")} sub={t("Debían haber salido")}
            value={fmtQuantity(summary.tripsOverdue)}
          />
          <BandMetric icon={<PendingActionsRounded />} color="error.main" title={t("Salieron tarde")} value={fmtQuantity(summary.tripsDepartedLate)} />
        </KpiBand>
        <KpiBand title={t("Riesgos")}>
          <BandMetric icon={<ReportProblemRounded />} color="error.main" title={t("Incidencias abiertas")} value={fmtQuantity(summary.openExceptions)} />
          {/* JOB 12: lo único de esta fila que mira hacia adelante. */}
          <BandMetric
            icon={<BlockRounded />} color="warning.main"
            title={t("No pueden salir")} sub={t("Bloqueados ahora mismo")}
            value={fmtQuantity(summary.blockedShipments)}
          />
          <BandMetric
            icon={<ScheduleRounded />} color="warning.main"
            title={t("Paradas pendientes")} sub={t("{{n}} fuera de ventana", { n: fmtQuantity(summary.stopsPastWindow) })}
            value={fmtQuantity(summary.outstandingStops)}
          />
          {/* `null` y no `0` cuando la cuenta no puede ver pedidos: un cero sería una afirmación
              sobre una cola que la respuesta no tenía permiso para mirar. */}
          {summary.ordersUnplanned !== null && (
            <BandMetric icon={<PendingActionsRounded />} color="text.secondary" title={t("Pedidos sin planificar")} value={fmtQuantity(summary.ordersUnplanned)} />
          )}
        </KpiBand>
      </Box>

      {/* Paneles en dos filas de alturas iguales: tres arriba, dos abajo. */}
      <Box sx={{
        display: "grid", gap: 2, mb: 2,
        gridTemplateColumns: { xs: "1fr", lg: "repeat(3, minmax(0, 1fr))" },
      }}>
        <WorkloadPanel items={overview.workload} total={summary.tripsInTransit + summary.tripsScheduled} />
        <ExceptionsPanel items={overview.openExceptions} total={summary.openExceptions} />
        <OutstandingStopsPanel items={overview.outstandingStops} total={summary.outstandingStops} />
      </Box>
      <Box sx={{
        display: "grid", gap: 2, mb: 3,
        gridTemplateColumns: { xs: "1fr", lg: "repeat(2, minmax(0, 1fr))" },
      }}>
        <BlockersPanel items={overview.blockers} total={summary.blockedShipments} />
        {/* Debajo de los bloqueadores y visiblemente distinto (JOB 23). Dos corrientes, dos
            contadores: "qué está atascado" y "qué conviene saber" son preguntas diferentes, y una
            lista única haría que un redondeo se leyera como un camión parado. */}
        <AdvisoriesPanel items={overview.advisories} total={summary.openAdvisories} />
      </Box>

      <FilterBar
        value={filters}
        defaults={DEFAULT_FILTERS}
        onChange={setFilters}
        fields={[
          { type: "select", key: "originId", label: t("Origen"), icon: <WarehouseRounded />,
            allLabel: t("Todos los orígenes"),
            options: (originsQuery.data?.content ?? []).map((origin) => ({ id: origin.id, label: origin.name })) },
          { type: "select", key: "carrierId", label: t("Transportista"), icon: <BusinessRounded />,
            allLabel: t("Todos los transportistas"),
            options: (carriersQuery.data?.content ?? []).map((carrier) => ({ id: carrier.id, label: carrier.businessName })) },
          { type: "select", key: "status", label: t("Estado"), icon: <FlagRounded />,
            allLabel: t("Todos los estados"),
            options: TRIP_STATUSES.map((status) => ({ id: status, label: enumLabel("tripStatus", status) })) },
        ]}
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
