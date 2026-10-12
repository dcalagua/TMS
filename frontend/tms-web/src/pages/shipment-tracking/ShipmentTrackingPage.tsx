import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { useCallback, useMemo, useState } from "react";
import { Link } from "react-router-dom";
import {
  Box, Button, Chip, LinearProgress, ToggleButton, ToggleButtonGroup, Typography, useMediaQuery, useTheme,
} from "@mui/material";
import {
  AssignmentReturnRounded, BlockRounded, BusinessRounded, CheckCircleRounded, CompareArrowsRounded, DateRangeRounded,
  DescriptionRounded, DoNotDisturbOnRounded, FlagRounded, HourglassEmptyRounded, ListRounded, LocalShippingRounded,
  MapRounded, PendingActionsRounded, RouteRounded, TaskAltRounded, TravelExploreRounded,
} from "@mui/icons-material";
import { fetchCarriers } from "../../shared/api/carriersApi";
import type { ApiError } from "../../shared/api/httpClient";
import type { TripStatus } from "../../shared/api/planningApi";
import { describeApiError } from "../../shared/api/problemMessages";
import {
  fetchShipmentTracking, fetchShipmentTrackingSummary, type ShipmentTrackingRow,
} from "../../shared/api/shipmentTrackingApi";
import { useCompany } from "../../shared/company/CompanyContext";
import { ShipmentOverviewMap } from "../../shared/maps/ShipmentOverviewMap";
import { STOP_STATE_COLOR } from "../../shared/maps/shipmentMarkers";
import {
  AppCard, DataTable, FilterBar, KpiCard, PageHeader, Pagination, StatusChip, type DataTableColumn,
} from "../../shared/ui/components";
import { TRIP_STATUS_TONE } from "../../shared/ui/statusTones";
import { enumLabel } from "../../lib/enums";
import { t } from "../../lib/i18n";
import { fmtDateTime, fmtPercent, fmtQuantity } from "../../lib/locale";
import { verificationTone } from "../trips/warehouseDispatch";
import { ShipmentTrackingDrawer } from "./ShipmentTrackingDrawer";
import {
  defaultShipmentFilters, overviewPoints, percent, shipmentTrackingKey, toQueryParams, type ShipmentFilters,
} from "./shipmentTracking";

const PAGE_SIZE = 50;
const POLL_MS = 60_000;

/** Los estados en que puede estar un envío que el almacén ya despachó. */
const STATUS_OPTIONS: TripStatus[] = ["IN_TRANSIT", "COMPLETED", "READY_FOR_DISPATCH", "CANCELLED"];

type View = "list" | "map";

/**
 * Seguimiento de despachos (ADR-019 §2): los envíos que salieron de verdad del almacén - su
 * despacho (ADR-013), con la placa y el conductor que reportó el almacén y su verificación contra
 * el plan - y, por cada uno, cuánto llevan de paradas y qué pasó con sus documentos (ADR-018).
 *
 * Al lado de "Seguimiento de reparto" y sin tocarlo: aquel sale del tablero de la torre de control
 * y mira el plan; este sale del despacho y mira lo que se fue. Un informe que se refresca solo cada
 * minuto, no un mapa de flota en vivo: las posiciones solo aparecen al abrir un envío, e informan.
 */
export function ShipmentTrackingPage() {
  const { selected } = useCompany();
  const companyId = selected?.id ?? "";
  const theme = useTheme();
  const wide = useMediaQuery(theme.breakpoints.up("lg"), { noSsr: true });

  const [defaults] = useState(defaultShipmentFilters);
  const [filters, setFiltersState] = useState<ShipmentFilters>(defaults);
  const [page, setPage] = useState(0);
  const [view, setView] = useState<View>("list");
  const [selectedTripId, setSelectedTripId] = useState<string | null>(null);
  const [openRow, setOpenRow] = useState<ShipmentTrackingRow | null>(null);
  const setFilters = (next: ShipmentFilters) => { setFiltersState(next); setPage(0); };

  const params = toQueryParams(filters);

  const listQuery = useQuery({
    queryKey: [...shipmentTrackingKey(companyId), "list", params, page],
    queryFn: ({ signal }) => fetchShipmentTracking({ companyId, ...params, page, size: PAGE_SIZE, signal }),
    enabled: companyId !== "",
    placeholderData: keepPreviousData,
    refetchInterval: POLL_MS,
  });

  const summaryParams = { dateFrom: params.dateFrom, dateTo: params.dateTo, carrierId: params.carrierId };
  const summaryQuery = useQuery({
    queryKey: [...shipmentTrackingKey(companyId), "summary", summaryParams],
    queryFn: ({ signal }) => fetchShipmentTrackingSummary({ companyId, ...summaryParams, signal }),
    enabled: companyId !== "",
    placeholderData: keepPreviousData,
    refetchInterval: POLL_MS,
  });

  const carriersQuery = useQuery({
    queryKey: ["carriers-for-trip-filter", companyId],
    queryFn: ({ signal }) => fetchCarriers({ companyId, size: 200, active: true, sort: "code,asc", signal }),
    enabled: companyId !== "",
  });

  const pageData = listQuery.data;
  const rows = useMemo(() => pageData?.content ?? [], [pageData]);
  const points = useMemo(() => overviewPoints(rows), [rows]);
  const summary = summaryQuery.data;
  const loadingSummary = summaryQuery.isPending;

  // El panel abierto lee la fila del último refresco, para que el minuto también le llegue.
  const currentOpen = openRow ? rows.find((r) => r.tripId === openRow.tripId) ?? openRow : null;

  const openShipment = useCallback((row: ShipmentTrackingRow) => {
    setSelectedTripId(row.tripId);
    setOpenRow(row);
  }, []);
  const selectFromMap = useCallback((tripId: string) => {
    const row = rows.find((r) => r.tripId === tripId);
    if (row) openShipment(row);
  }, [rows, openShipment]);

  const refresh = () => { void listQuery.refetch(); void summaryQuery.refetch(); };
  const filterBy = (patch: Partial<ShipmentFilters>) => setFilters({ ...filters, ...patch });

  const columns: DataTableColumn<ShipmentTrackingRow>[] = [
    {
      key: "shipment",
      header: t("Envío"),
      render: (row) => (
        <Box>
          <Typography variant="body2" sx={{ fontWeight: 800 }}>{row.shipmentNumber}</Typography>
          <Typography variant="caption" color="text.secondary">{row.dispatchReference}</Typography>
        </Box>
      ),
    },
    {
      key: "carrier",
      header: t("Transportista"),
      render: (row) => (
        <Box>
          <Typography variant="body2">{row.carrierName ?? t("Sin transportista")}</Typography>
          <Typography variant="caption" color="text.secondary">
            {[row.vehicleLicensePlate, row.driverName].filter(Boolean).join(" · ") || "-"}
          </Typography>
        </Box>
      ),
    },
    {
      key: "dispatch",
      header: t("Despacho"),
      render: (row) => (
        <Box sx={{ display: "grid", gap: 0.25, justifyItems: "start" }}>
          <Typography variant="body2" sx={{ whiteSpace: "nowrap" }}>{fmtDateTime(row.dispatchedAt)}</Typography>
          <StatusChip label={enumLabel("dispatchVerificationStatus", row.dispatchVerification)} tone={verificationTone(row.dispatchVerification)} />
        </Box>
      ),
    },
    {
      key: "stops",
      header: t("Paradas"),
      width: 130,
      render: (row) => {
        const progress = percent(row.stopsResolved, row.stopsTotal);
        return (
          <Box sx={{ minWidth: 100 }}>
            <Typography variant="body2" sx={{ fontVariantNumeric: "tabular-nums", fontWeight: 700 }}>
              {row.stopsResolved}/{row.stopsTotal}
              {progress !== null && (
                <Typography component="span" variant="caption" color="text.secondary"> · {fmtPercent(progress)}</Typography>
              )}
            </Typography>
            <LinearProgress variant="determinate" value={progress ?? 0} sx={{ height: 5, borderRadius: "12px", mt: 0.5 }}
              aria-label={t("Progreso de paradas")} />
          </Box>
        );
      },
    },
    {
      key: "documents",
      header: t("Documentos"),
      width: 210,
      render: (row) => <DocumentsCell row={row} />,
    },
    {
      key: "lastReport",
      header: t("Último reporte"),
      render: (row) => row.lastReportAt
        ? <Typography variant="body2" sx={{ whiteSpace: "nowrap" }}>{fmtDateTime(row.lastReportAt)}</Typography>
        : <Typography variant="caption" color="text.disabled">-</Typography>,
    },
    {
      key: "status",
      header: t("Estado"),
      render: (row) => <StatusChip label={enumLabel("tripStatus", row.tripStatus)} tone={TRIP_STATUS_TONE[row.tripStatus as TripStatus] ?? "neutral"} />,
    },
  ];

  const table = (
    <DataTable
      columns={columns}
      rows={rows}
      total={pageData?.totalElements}
      rowKey={(row) => row.tripId}
      isLoading={listQuery.isPending}
      error={listQuery.isError ? describeApiError(listQuery.error as ApiError) : null}
      onRetry={() => void listQuery.refetch()}
      emptyTitle={t("Sin despachos")}
      emptyMessage={t("Ningún envío despachado por el almacén coincide con las fechas y los filtros.")}
      onRowClick={openShipment}
      rowAccent={(row) => row.tripId === selectedTripId ? "primary.main"
        : row.dispatchVerification === "MISMATCH" || row.rejectedCount + row.notDeliveredCount > 0 ? "error.main"
          : null}
      footer={pageData && pageData.totalElements > PAGE_SIZE ? <Pagination page={pageData} onPageChange={setPage} /> : undefined}
    />
  );

  const map = (
    <Box sx={{ position: { lg: "sticky" }, top: { lg: 16 }, minWidth: 0 }}>
      <AppCard title={t("Mapa de despachos")}>
        <ShipmentOverviewMap points={points} selectedGroupId={selectedTripId} onSelectGroup={selectFromMap} height={wide ? 520 : 420} />
      </AppCard>
    </Box>
  );

  return (
    <>
      <PageHeader
        icon={<RouteRounded />}
        title={t("Seguimiento de despachos")}
        subtitle={t("Los envíos que el almacén despachó de verdad: qué salió, cómo avanzan sus paradas y qué pasó con sus documentos. Abre una fila para ver la ruta, las posiciones y cada documento.")}
        onRefresh={refresh}
        refreshing={listQuery.isFetching || summaryQuery.isFetching}
        actions={
          <Button component={Link} to="/delivery-tracking" variant="outlined" color="inherit" size="small"
            startIcon={<TravelExploreRounded />} sx={{ borderColor: "divider" }}>
            {t("Seguimiento de reparto")}
          </Button>
        }
      />

      <Box sx={(th) => ({
        display: "grid", gap: { xs: 1.5, xl: 1.25 }, mb: 2.5,
        gridTemplateColumns: { xs: "repeat(2, minmax(0,1fr))", md: "repeat(3, minmax(0,1fr))", lg: "repeat(5, minmax(0,1fr))" },
        [th.breakpoints.up("lg")]: { "& .MuiCardContent-root": { p: 1.5, gap: 1, "&:last-child": { pb: 1.5 } } },
      })}>
        <KpiCard loading={loadingSummary} icon={<LocalShippingRounded />} color="primary.main" title={t("Despachos")}
          value={fmtQuantity(summary?.shipments)} onClick={() => filterBy({ status: "", documents: "" })} />
        <KpiCard loading={loadingSummary} icon={<PendingActionsRounded />} color="warning.main" title={t("En ruta")}
          value={fmtQuantity(summary?.inTransit)} onClick={() => filterBy({ status: "IN_TRANSIT" })} />
        <KpiCard loading={loadingSummary} icon={<TaskAltRounded />} color="success.main" title={t("Completados")}
          value={fmtQuantity(summary?.completed)} onClick={() => filterBy({ status: "COMPLETED" })} />
        <KpiCard loading={loadingSummary} icon={<DescriptionRounded />} color="info.main" title={t("Con documentos pendientes")}
          value={fmtQuantity(summary?.withPendingDocuments)} onClick={() => filterBy({ documents: "PENDING" })} />
        <KpiCard loading={loadingSummary} icon={<CompareArrowsRounded />} color="error.main" title={t("Diferencias de despacho")}
          sub={t("Lo que salió no coincide con el plan")} value={fmtQuantity(summary?.dispatchMismatches)} />
        <KpiCard loading={loadingSummary} icon={<HourglassEmptyRounded />} color="text.secondary" title={t("Documentos pendientes")}
          sub={summary ? t("de {{n}} documentos", { n: fmtQuantity(summary.documentsTotal) }) : undefined}
          value={fmtQuantity(summary?.documentsPending)} />
        <KpiCard loading={loadingSummary} icon={<CheckCircleRounded />} color="success.main" title={t("Documentos entregados")}
          value={fmtQuantity(summary?.documentsDelivered)} />
        <KpiCard loading={loadingSummary} icon={<BlockRounded />} color="error.main" title={t("Documentos rechazados")}
          value={fmtQuantity(summary?.documentsRejected)} />
        <KpiCard loading={loadingSummary} icon={<DoNotDisturbOnRounded />} color="warning.main" title={t("Documentos no entregados")}
          value={fmtQuantity(summary?.documentsNotDelivered)} />
        <KpiCard loading={loadingSummary} icon={<AssignmentReturnRounded />} color="info.main" title={t("Cargo por recibir")}
          value={fmtQuantity(summary?.awaitingReturn)} />
      </Box>

      <FilterBar
        value={filters}
        defaults={defaults}
        onChange={setFilters}
        fields={[
          { type: "search", key: "q", placeholder: t("Envío, despacho, placa o conductor"), width: 280 },
          { type: "dateRange", from: "dateFrom", to: "dateTo", label: t("Despacho"), icon: <DateRangeRounded /> },
          { type: "select", key: "carrierId", label: t("Transportista"), icon: <BusinessRounded />, allLabel: t("Todos los transportistas"),
            options: (carriersQuery.data?.content ?? []).map((c) => ({ id: c.id, label: c.businessName })) },
          { type: "select", key: "status", label: t("Estado"), icon: <FlagRounded />, allLabel: t("Todos los estados"),
            options: STATUS_OPTIONS.map((s) => ({ id: s, label: enumLabel("tripStatus", s) })) },
          { type: "select", key: "documents", label: t("Documentos"), icon: <DescriptionRounded />, allLabel: t("Todos"),
            options: [
              { id: "PENDING", label: t("Con documentos pendientes") },
              { id: "COMPLETE", label: t("Sin documentos pendientes") },
            ] },
        ]}
        trailing={wide ? undefined : (
          <ToggleButtonGroup
            size="small" exclusive value={view} aria-label={t("Vista")}
            onChange={(_, next: View | null) => { if (next) setView(next); }}
          >
            <ToggleButton value="list" aria-label={t("Lista")}><ListRounded sx={{ fontSize: 18, mr: 0.5 }} />{t("Lista")}</ToggleButton>
            <ToggleButton value="map" aria-label={t("Mapa")}><MapRounded sx={{ fontSize: 18, mr: 0.5 }} />{t("Mapa")}</ToggleButton>
          </ToggleButtonGroup>
        )}
      />

      {wide ? (
        <Box sx={{ display: "grid", gap: 2, gridTemplateColumns: "minmax(0, 1.6fr) minmax(360px, 1fr)", alignItems: "start" }}>
          <Box sx={{ minWidth: 0 }}>{table}</Box>
          {map}
        </Box>
      ) : view === "list" ? table : map}

      {currentOpen && (
        <ShipmentTrackingDrawer
          row={currentOpen}
          dateFrom={params.dateFrom}
          dateTo={params.dateTo}
          onClose={() => setOpenRow(null)}
        />
      )}
    </>
  );
}

/**
 * Los documentos de un envío en una barra apilada (entregados, rechazados o no entregados, y lo
 * pendiente como fondo) y en cifras, porque el color solo no basta.
 */
function DocumentsCell({ row }: { row: ShipmentTrackingRow }) {
  if (row.documentCount === 0) return <Typography variant="caption" color="text.disabled">{t("Sin documentos")}</Typography>;
  const problems = row.rejectedCount + row.notDeliveredCount;
  const reported = row.documentCount - row.pendingCount;
  const share = (n: number) => `${(Math.max(0, n) / row.documentCount) * 100}%`;
  return (
    <Box sx={{ minWidth: 150 }}>
      <Typography variant="body2" sx={{ fontVariantNumeric: "tabular-nums", fontWeight: 700 }}>
        {reported}/{row.documentCount}
        <Typography component="span" variant="caption" color="text.secondary"> {t("reportados")}</Typography>
      </Typography>
      <Box
        role="img"
        aria-label={t("{{delivered}} entregados, {{problems}} rechazados o no entregados, {{pending}} pendientes", {
          delivered: row.deliveredCount, problems, pending: row.pendingCount,
        })}
        sx={{ display: "flex", height: 5, borderRadius: "12px", overflow: "hidden", bgcolor: "action.hover", mt: 0.5 }}
      >
        <Box sx={{ width: share(row.deliveredCount), bgcolor: STOP_STATE_COLOR.DELIVERED }} />
        <Box sx={{ width: share(problems), bgcolor: STOP_STATE_COLOR.PROBLEM }} />
      </Box>
      <Box sx={{ display: "flex", gap: 0.5, flexWrap: "wrap", mt: 0.5 }}>
        {row.pendingCount > 0 && <Chip size="small" color="warning" variant="outlined" label={t("{{n}} pendientes", { n: row.pendingCount })} sx={{ height: 20, fontSize: 10.5 }} />}
        {row.rejectedCount > 0 && <Chip size="small" color="error" label={t("{{n}} rechazados", { n: row.rejectedCount })} sx={{ height: 20, fontSize: 10.5 }} />}
        {row.notDeliveredCount > 0 && <Chip size="small" color="error" variant="outlined" label={t("{{n}} no entregados", { n: row.notDeliveredCount })} sx={{ height: 20, fontSize: 10.5 }} />}
        {row.inconsistentCount > 0 && <Chip size="small" color="warning" label={t("{{n}} inconsistentes", { n: row.inconsistentCount })} sx={{ height: 20, fontSize: 10.5 }} />}
      </Box>
    </Box>
  );
}
