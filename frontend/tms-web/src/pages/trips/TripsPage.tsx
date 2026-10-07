import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { Box, Typography } from "@mui/material";
import {
  MapRounded, EventRounded, FlagRounded, WarehouseRounded, BusinessRounded, BadgeRounded,
} from "@mui/icons-material";
import { fetchCarriers } from "../../shared/api/carriersApi";
import { fetchDrivers } from "../../shared/api/driversApi";
import type { ApiError } from "../../shared/api/httpClient";
import { fetchOrigins } from "../../shared/api/originsApi";
import { fetchTrips, TRIP_STATUSES, type TripStatus, type TripView } from "../../shared/api/planningApi";
import { describeApiError } from "../../shared/api/problemMessages";
import { useCompany } from "../../shared/company/CompanyContext";
import {
  DataTable, FilterBar, PageHeader, Pagination, StatusChip, type DataTableColumn,
} from "../../shared/ui/components";
import { TRIP_STATUS_TONE } from "../../shared/ui/statusTones";
import { ICON_TINTS } from "../../shared/ui/navConfig";
import { enumLabel } from "../../lib/enums";
import { t } from "../../lib/i18n";
import { fmtDate, fmtQuantity, fmtTime } from "../../lib/locale";

const PAGE_SIZE = 20;

interface AppliedFilters {
  shipmentNumber: string;
  status: TripStatus | "";
  originId: string;
  carrierId: string;
  driverId: string;
  planningDateFrom: string;
  planningDateTo: string;
}

const DEFAULT_FILTERS: AppliedFilters = {
  shipmentNumber: "", status: "", originId: "", carrierId: "", driverId: "",
  planningDateFrom: "", planningDateTo: "",
};

/**
 * El tablero de ejecución: todos los viajes de la empresa, indexados por día en lugar de por
 * plan.
 *
 * La pregunta de un despachador es "qué sale hoy", y eso cruza todos los planes que produjeron un
 * viaje para esa fecha — mientras que el tablero de planificación solo puede enseñar uno. Por eso
 * esta pantalla existe al lado de aquella y no dentro, y por eso la fila es el número de envío y
 * no "viaje 2 del PL-17", que no significa nada fuera de su propio tablero.
 */
export function TripsPage() {
  const { selected } = useCompany();
  const companyId = selected?.id ?? "";
  const navigate = useNavigate();

  const [page, setPage] = useState(0);
  // Los filtros se aplican al momento: no hay borrador ni botón de aplicar.
  const [filters, setFiltersState] = useState<AppliedFilters>(DEFAULT_FILTERS);
  const setFilters = (next: AppliedFilters) => { setFiltersState(next); setPage(0); };

  const tripsQuery = useQuery({
    queryKey: ["trips", companyId, page, filters],
    queryFn: ({ signal }) =>
      fetchTrips({
        companyId,
        page,
        size: PAGE_SIZE,
        shipmentNumber: filters.shipmentNumber || undefined,
        status: filters.status || undefined,
        originId: filters.originId || undefined,
        carrierId: filters.carrierId || undefined,
        driverId: filters.driverId || undefined,
        planningDateFrom: filters.planningDateFrom || undefined,
        planningDateTo: filters.planningDateTo || undefined,
        signal,
      }),
    enabled: companyId !== "",
    placeholderData: keepPreviousData,
  });

  const originsQuery = useQuery({
    queryKey: ["origins-for-trip-filter", companyId],
    queryFn: ({ signal }) => fetchOrigins({ companyId, size: 200, active: true, sort: "code,asc", signal }),
    enabled: companyId !== "",
  });
  const carriersQuery = useQuery({
    queryKey: ["carriers-for-trip-filter", companyId],
    queryFn: ({ signal }) => fetchCarriers({ companyId, size: 200, active: true, sort: "code,asc", signal }),
    enabled: companyId !== "",
  });
  // Solo conductores activos: este es el filtro de "dónde está Ana hoy", y los viajes pasados de
  // un conductor retirado se alcanzan desde el maestro o desde el número de envío, no desde un
  // desplegable cuya lista crecería para siempre.
  const driversQuery = useQuery({
    queryKey: ["drivers-for-trip-filter", companyId],
    queryFn: ({ signal }) => fetchDrivers({ companyId, size: 200, active: true, signal }),
    enabled: companyId !== "",
  });

  const columns: DataTableColumn<TripView>[] = [
    {
      key: "shipment",
      header: t("Envío"),
      render: (trip) => (
        <Box>
          <Typography variant="body2" sx={{ fontWeight: 800, fontVariantNumeric: "tabular-nums", whiteSpace: "nowrap" }}>
            {trip.shipmentNumber}
          </Typography>
          <Typography variant="caption" color="text.secondary" sx={{ display: "block", whiteSpace: "nowrap" }}>
            {trip.planNumber} · {t("Viaje {{number}}", { number: trip.tripNumber })}
          </Typography>
        </Box>
      ),
    },
    {
      key: "date", header: t("Fecha"),
      render: (trip) => <Box component="span" sx={{ fontVariantNumeric: "tabular-nums", whiteSpace: "nowrap" }}>{fmtDate(trip.planningDate)}</Box>,
    },
    { key: "origin", header: t("Origen"), render: (trip) => trip.originName ?? trip.originCode ?? "-" },
    { key: "carrier", header: t("Transportista"), render: (trip) => trip.carrierName ?? t("Flota propia") },
    {
      key: "plate", header: t("Placa"),
      render: (trip) => <Box component="span" sx={{ fontVariantNumeric: "tabular-nums", whiteSpace: "nowrap" }}>{trip.vehicleLicensePlate ?? "-"}</Box>,
    },
    {
      key: "driver",
      header: t("Conductor"),
      render: (trip) => trip.driverName === null ? "-" : (
        <Box sx={{ display: "flex", flexDirection: "column", alignItems: "flex-start", gap: 0.4 }}>
          <Typography variant="body2">{trip.driverName}</Typography>
          {/* El estado de la licencia lo juzga el servidor contra la fecha del viaje; aquí solo
              se avisa cuando no es "vigente". */}
          {trip.driverLicenseStatus && trip.driverLicenseStatus !== "VALID" && (
            <StatusChip
              tone={trip.driverLicenseStatus === "EXPIRED" ? "overdue" : "inProgress"}
              label={enumLabel("driverLicenseStatus", trip.driverLicenseStatus)}
            />
          )}
        </Box>
      ),
    },
    {
      key: "departure",
      header: t("Salida"),
      // Planificada y real una encima de otra: la diferencia entre las dos ES el retraso de
      // salida, y ponerlas en columnas separadas obliga a restarlas de cabeza.
      render: (trip) => (
        <Box>
          <Typography variant="body2" sx={{ fontWeight: 600, fontVariantNumeric: "tabular-nums" }}>
            {trip.plannedDepartureAt ? fmtTime(trip.plannedDepartureAt) : "-"}
          </Typography>
          {trip.actualDepartureAt && (
            <Typography variant="caption" color="text.secondary" sx={{ display: "block", whiteSpace: "nowrap", fontVariantNumeric: "tabular-nums" }}>
              {t("Real")}: {fmtTime(trip.actualDepartureAt)}
            </Typography>
          )}
        </Box>
      ),
    },
    { key: "stops", header: t("Paradas"), numeric: true, render: (trip) => fmtQuantity(trip.stopCount) },
    { key: "orders", header: t("Pedidos"), numeric: true, render: (trip) => fmtQuantity(trip.orderCount) },
    {
      key: "status",
      header: t("Estado"),
      render: (trip) => <StatusChip label={enumLabel("tripStatus", trip.status)} tone={TRIP_STATUS_TONE[trip.status]} />,
    },
  ];

  const pageData = tripsQuery.data;

  return (
    <>
      <PageHeader
        icon={<MapRounded />}
        tint={ICON_TINTS["/trips"]}
        title={t("Viajes")}
        subtitle={t("Todos los envíos de la empresa por día, con el estado en que está cada uno.")}
        onRefresh={() => void tripsQuery.refetch()}
        refreshing={tripsQuery.isFetching}
      />

      <FilterBar
        value={filters}
        defaults={DEFAULT_FILTERS}
        onChange={setFilters}
        fields={[
          { type: "search", key: "shipmentNumber", placeholder: t("Envío") },
          { type: "dateRange", from: "planningDateFrom", to: "planningDateTo", label: t("Fecha"), icon: <EventRounded /> },
          { type: "select", key: "status", label: t("Estado"), icon: <FlagRounded />,
            allLabel: t("Todos los estados"),
            options: TRIP_STATUSES.map((status) => ({ id: status, label: enumLabel("tripStatus", status) })) },
          { type: "select", key: "originId", label: t("Origen"), icon: <WarehouseRounded />,
            allLabel: t("Todos los orígenes"),
            options: (originsQuery.data?.content ?? []).map((origin) => ({ id: origin.id, label: origin.name })) },
          { type: "select", key: "carrierId", label: t("Transportista"), icon: <BusinessRounded />,
            allLabel: t("Todos los transportistas"),
            options: (carriersQuery.data?.content ?? []).map((carrier) => ({ id: carrier.id, label: carrier.businessName })) },
          { type: "select", key: "driverId", label: t("Conductor"), icon: <BadgeRounded />,
            allLabel: t("Todos"),
            options: (driversQuery.data?.content ?? []).map((driver) => ({ id: driver.id, label: driver.fullName })) },
        ]}
      />

      <DataTable
        columns={columns}
        rows={pageData?.content ?? []}
        total={pageData?.totalElements}
        rowKey={(trip) => trip.id}
        isLoading={tripsQuery.isPending}
        error={tripsQuery.isError ? describeApiError(tripsQuery.error as ApiError) : null}
        onRetry={() => void tripsQuery.refetch()}
        emptyTitle={t("Sin viajes")}
        emptyMessage={t("Ningún envío coincide con los filtros seleccionados.")}
        onRowClick={(trip) => navigate(`/trips/${trip.id}`)}
        footer={pageData ? <Pagination page={pageData} onPageChange={setPage} /> : undefined}
      />
    </>
  );
}
