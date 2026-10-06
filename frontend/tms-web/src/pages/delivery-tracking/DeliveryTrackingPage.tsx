import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { useMemo, useState } from "react";
import { Alert, Box, Chip, LinearProgress, MenuItem, TextField, Typography } from "@mui/material";
import {
  LocalShippingRounded, PlaceRounded, TaskAltRounded, PendingActionsRounded, ScheduleRounded,
  ReportProblemRounded, DonutLargeRounded, TravelExploreRounded,
} from "@mui/icons-material";
import { fetchCarriers } from "../../shared/api/carriersApi";
import {
  DELAYED_TIMELINESS, type ControlTowerTripView, type DepartureTimeliness,
} from "../../shared/api/controlTowerApi";
import type { ApiError } from "../../shared/api/httpClient";
import type { TripStatus } from "../../shared/api/planningApi";
import { describeApiError } from "../../shared/api/problemMessages";
import { useCompany } from "../../shared/company/CompanyContext";
import {
  DataTable, KpiCard, PageHeader, Pagination, StatusChip, Toolbar, type DataTableColumn,
} from "../../shared/ui/components";
import { TRIP_STATUS_TONE } from "../../shared/ui/statusTones";
import { ICON_TINTS } from "../../shared/ui/navConfig";
import type { StatusTone } from "../../theme";
import { enumLabel } from "../../lib/enums";
import { t } from "../../lib/i18n";
import { fmtPercent, fmtQuantity, fmtTime, today } from "../../lib/locale";
import { DeliveryTripDrawer } from "./DeliveryTripDrawer";
import { aggregateKpis, fetchBoardSnapshot, matchesSearch, stopProgress } from "./deliveryTracking";

const PAGE_SIZE = 25;
const POLL_MS = 60_000;

/** Los estados que tienen sentido en un seguimiento de reparto. `""` = todos los del día. */
const STATUS_OPTIONS: (TripStatus | "")[] = ["IN_TRANSIT", "READY_FOR_DISPATCH", "COMPLETED", ""];

const TIMELINESS_TONE: Record<DepartureTimeliness, StatusTone> = {
  NOT_APPLICABLE: "neutral",
  NOT_SCHEDULED: "neutral",
  SCHEDULED: "open",
  OVERDUE: "overdue",
  ON_TIME: "done",
  LATE: "overdue",
};

interface Filters {
  date: string;
  carrierId: string;
  status: TripStatus | "";
}

/**
 * Seguimiento de reparto: los envíos que están en la calle hoy, cuánto llevan hecho y dónde hay
 * un problema.
 *
 * Una primera versión construida con lo que ya existe, sin motor de reparto ni estados nuevos
 * (`IN_TRANSIT` sigue siendo el estado del viaje). La tabla sale del tablero de la torre de
 * control, que ya trae por fila el progreso de paradas y las incidencias; se trae el día entero
 * en páginas de 200 para que los KPI sean del día y no de la página visible, y la tabla pagina en
 * el navegador. Lo que cuesta una consulta por viaje —entregas por pedido, ubicación, despacho
 * del almacén— se pide solo al abrir una fila.
 */
export function DeliveryTrackingPage() {
  const { selected } = useCompany();
  const companyId = selected?.id ?? "";

  const initial: Filters = { date: today(), carrierId: "", status: "IN_TRANSIT" };
  const [draft, setDraft] = useState<Filters>(initial);
  const [filters, setFilters] = useState<Filters>(initial);
  const [search, setSearch] = useState("");
  const [page, setPage] = useState(0);
  const [openRow, setOpenRow] = useState<ControlTowerTripView | null>(null);

  const boardQuery = useQuery({
    queryKey: ["delivery-tracking", companyId, filters],
    queryFn: ({ signal }) => fetchBoardSnapshot({
      companyId,
      date: filters.date || undefined,
      carrierId: filters.carrierId || undefined,
      status: filters.status || undefined,
      signal,
    }),
    enabled: companyId !== "",
    placeholderData: keepPreviousData,
    // Un tablero en vivo: se refresca solo, como la torre de control.
    refetchInterval: POLL_MS,
  });

  const carriersQuery = useQuery({
    queryKey: ["carriers-for-trip-filter", companyId],
    queryFn: ({ signal }) => fetchCarriers({ companyId, size: 200, active: true, sort: "code,asc", signal }),
    enabled: companyId !== "",
  });

  const snapshot = boardQuery.data;
  const rows = useMemo(
    () => (snapshot?.rows ?? []).filter((row) => matchesSearch(row, search)),
    [snapshot, search],
  );
  const kpis = useMemo(() => aggregateKpis(rows), [rows]);
  const lastPage = Math.max(0, Math.ceil(rows.length / PAGE_SIZE) - 1);
  const currentPage = Math.min(page, lastPage);
  const visible = rows.slice(currentPage * PAGE_SIZE, (currentPage + 1) * PAGE_SIZE);

  function applyFilters() { setFilters(draft); setPage(0); }
  function resetFilters() { setDraft(initial); setFilters(initial); setSearch(""); setPage(0); }

  const columns: DataTableColumn<ControlTowerTripView>[] = [
    {
      key: "shipment",
      header: t("Envío"),
      render: ({ trip }) => (
        <Box>
          <Typography variant="body2" sx={{ fontWeight: 800 }}>{trip.shipmentNumber}</Typography>
          <Typography variant="caption" color="text.secondary">{trip.routeName ?? trip.originName ?? trip.originCode ?? ""}</Typography>
        </Box>
      ),
    },
    { key: "carrier", header: t("Transportista"), render: ({ trip }) => trip.carrierName ?? t("Flota propia") },
    { key: "vehicle", header: t("Vehículo"), render: ({ trip }) => trip.vehicleLicensePlate ?? trip.vehicleCode ?? "-" },
    { key: "driver", header: t("Conductor"), render: ({ trip }) => trip.driverName ?? "-" },
    {
      key: "departure",
      header: t("Salida real"),
      render: (row) => (
        <Box>
          <Typography variant="body2">{row.trip.actualDepartureAt ? fmtTime(row.trip.actualDepartureAt) : "-"}</Typography>
          {DELAYED_TIMELINESS.includes(row.departureTimeliness) && (
            <StatusChip
              label={row.departureDelayMinutes !== null
                ? t("{{n}} min tarde", { n: row.departureDelayMinutes })
                : enumLabel("departureTimeliness", row.departureTimeliness)}
              tone={TIMELINESS_TONE[row.departureTimeliness]}
            />
          )}
        </Box>
      ),
    },
    {
      key: "stops",
      header: t("Paradas"),
      width: 150,
      render: (row) => {
        const progress = stopProgress(row);
        return (
          <Box sx={{ minWidth: 110 }}>
            <Typography variant="body2" sx={{ fontVariantNumeric: "tabular-nums", fontWeight: 700 }}>
              {row.stopsResolved}/{row.stopsTotal}
              {progress !== null && (
                <Typography component="span" variant="caption" color="text.secondary"> · {fmtPercent(progress)}</Typography>
              )}
            </Typography>
            <LinearProgress
              variant="determinate" value={progress ?? 0}
              color={row.stopsPastWindow > 0 ? "warning" : "primary"}
              sx={{ height: 5, borderRadius: "12px", mt: 0.5 }}
              aria-label={t("Progreso de paradas")}
            />
          </Box>
        );
      },
    },
    {
      key: "next",
      header: t("Próxima parada"),
      render: (row) => row.nextStopSequence === null ? "-" : (
        <Box>
          <Typography variant="body2">#{row.nextStopSequence}</Typography>
          {row.nextStopDueAt && <Typography variant="caption" color="text.secondary">{fmtTime(row.nextStopDueAt)}</Typography>}
        </Box>
      ),
    },
    {
      key: "issues",
      header: t("Alertas"),
      render: (row) => (
        <Box sx={{ display: "flex", gap: 0.5, flexWrap: "wrap" }}>
          {row.openExceptions > 0 && <Chip size="small" color="error" label={t("{{count}} incidencias", { count: row.openExceptions })} sx={{ height: 20, fontSize: 10.5 }} />}
          {row.stopsPastWindow > 0 && <Chip size="small" color="warning" label={t("{{n}} fuera de ventana", { n: row.stopsPastWindow })} sx={{ height: 20, fontSize: 10.5 }} />}
          {row.openExceptions === 0 && row.stopsPastWindow === 0 && <Typography variant="caption" color="text.disabled">-</Typography>}
        </Box>
      ),
    },
    {
      key: "status",
      header: t("Estado"),
      render: ({ trip }) => <StatusChip label={enumLabel("tripStatus", trip.status)} tone={TRIP_STATUS_TONE[trip.status]} />,
    },
  ];

  return (
    <>
      <PageHeader
        icon={<TravelExploreRounded />}
        tint={ICON_TINTS["/delivery-tracking"]}
        title={t("Seguimiento de reparto")}
        subtitle={t("Los envíos en la calle, cuánto llevan hecho y dónde hay un problema. Abre una fila para ver entregas, ubicación y despacho del almacén.")}
        onRefresh={() => void boardQuery.refetch()}
        refreshing={boardQuery.isFetching}
      />

      <Box sx={{
        display: "grid", gap: 2, mb: 3,
        gridTemplateColumns: { xs: "1fr", sm: "repeat(2, minmax(0,1fr))", md: "repeat(4, minmax(0,1fr))", xl: "repeat(7, minmax(0,1fr))" },
      }}>
        <KpiCard loading={boardQuery.isPending} icon={<LocalShippingRounded />} color="warning.main" title={t("Viajes en reparto")} value={fmtQuantity(kpis.tripsInTransit)} />
        <KpiCard loading={boardQuery.isPending} icon={<PlaceRounded />} color="info.main" title={t("Paradas totales")} value={fmtQuantity(kpis.stopsTotal)} />
        <KpiCard loading={boardQuery.isPending} icon={<TaskAltRounded />} color="success.main" title={t("Paradas resueltas")} sub={t("Atendidas, omitidas o fallidas")} value={fmtQuantity(kpis.stopsResolved)} />
        <KpiCard loading={boardQuery.isPending} icon={<PendingActionsRounded />} color="text.secondary" title={t("Paradas pendientes")} value={fmtQuantity(kpis.stopsPending)} />
        <KpiCard loading={boardQuery.isPending} icon={<ScheduleRounded />} color="warning.main" title={t("Fuera de ventana")} value={fmtQuantity(kpis.stopsPastWindow)} />
        <KpiCard loading={boardQuery.isPending} icon={<ReportProblemRounded />} color="error.main" title={t("Incidencias abiertas")} value={fmtQuantity(kpis.openExceptions)} />
        <KpiCard
          loading={boardQuery.isPending} icon={<DonutLargeRounded />} color="primary.main" title={t("Progreso")}
          value={kpis.progressPercent === null ? "-" : fmtPercent(kpis.progressPercent)}
          progress={kpis.progressPercent ?? undefined}
        />
      </Box>

      {snapshot?.truncated && (
        <Alert severity="warning" sx={{ mb: 2 }}>
          {t("Se muestran {{shown}} de {{total}} envíos. Filtra por transportista o estado para ver el resto.", {
            shown: fmtQuantity(snapshot.rows.length), total: fmtQuantity(snapshot.totalElements),
          })}
        </Alert>
      )}

      <Toolbar
        onApply={applyFilters}
        onReset={resetFilters}
        filters={
          <>
            <TextField
              size="small" type="date" label={t("Fecha")} value={draft.date}
              onChange={(e) => setDraft({ ...draft, date: e.target.value })}
              slotProps={{ inputLabel: { shrink: true } }}
              sx={{ minWidth: 160 }}
            />
            <TextField
              select size="small" label={t("Estado")} value={draft.status}
              onChange={(e) => setDraft({ ...draft, status: e.target.value as TripStatus | "" })}
              sx={{ minWidth: 170 }}
            >
              {STATUS_OPTIONS.map((status) => (
                <MenuItem key={status || "ALL"} value={status}>
                  {status === "" ? t("Todos los estados") : enumLabel("tripStatus", status)}
                </MenuItem>
              ))}
            </TextField>
            <TextField
              select size="small" label={t("Transportista")} value={draft.carrierId}
              onChange={(e) => setDraft({ ...draft, carrierId: e.target.value })}
              sx={{ minWidth: 190 }}
            >
              <MenuItem value="">{t("Todos los transportistas")}</MenuItem>
              {(carriersQuery.data?.content ?? []).map((carrier) => (
                <MenuItem key={carrier.id} value={carrier.id}>{carrier.businessName}</MenuItem>
              ))}
            </TextField>
            {/* Local sobre lo ya traído: no es un filtro del servidor, así que no espera a "Aplicar". */}
            <TextField
              size="small" label={t("Buscar envío, placa o conductor")} value={search}
              onChange={(e) => { setSearch(e.target.value); setPage(0); }}
              sx={{ minWidth: 230 }}
            />
          </>
        }
      />

      <DataTable
        columns={columns}
        rows={visible}
        total={rows.length}
        rowKey={(row) => row.trip.id}
        isLoading={boardQuery.isPending}
        error={boardQuery.isError ? describeApiError(boardQuery.error as ApiError) : null}
        onRetry={() => void boardQuery.refetch()}
        emptyTitle={t("Sin envíos en reparto")}
        emptyMessage={t("Ningún envío coincide con la fecha y los filtros seleccionados.")}
        onRowClick={setOpenRow}
        rowAccent={(row) => row.openExceptions > 0 ? "#C0303A" : row.stopsPastWindow > 0 ? "#B26A00" : null}
        footer={rows.length > PAGE_SIZE
          ? <Pagination page={{ page: currentPage, size: PAGE_SIZE, totalElements: rows.length }} onPageChange={setPage} />
          : undefined}
      />

      {openRow && <DeliveryTripDrawer row={openRow} onClose={() => setOpenRow(null)} />}
    </>
  );
}
