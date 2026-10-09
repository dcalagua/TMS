import { keepPreviousData, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import {
  Box, Button, Paper, Table, TableBody, TableCell, TableContainer,
  TableHead, TableRow, Typography,
} from "@mui/material";
import {
  AddRounded, UploadRounded, LocalShippingRounded, EditRounded, BlockRounded, CheckCircleRounded,
  BuildCircleRounded, BusinessRounded, CategoryRounded, ToggleOnRounded,
} from "@mui/icons-material";
import { AvailabilityDrawer } from "./AvailabilityDrawer";
import type { ApiError } from "../../shared/api/httpClient";
import { fetchCarriers } from "../../shared/api/carriersApi";
import { fetchVehicleTypes } from "../../shared/api/vehicleTypesApi";
import {
  activateVehicle, deactivateVehicle, fetchVehicles, VEHICLE_AVAILABILITY_STATUSES,
  VEHICLE_IMPORT_BASE_PATH,
  type VehicleAvailabilityStatus, type VehicleImportPreview, type VehicleView,
} from "../../shared/api/vehiclesApi";
import { describeApiError } from "../../shared/api/problemMessages";
import { useCompany } from "../../shared/company/CompanyContext";
import {
  ActionMenu, ActiveBadge, DataTable, FilterBar, ImportDrawer, ImportOutcomeChip, PageHeader,
  Pagination, StatusChip, dataTableSx, type DataTableColumn,
} from "../../shared/ui/components";
import {
  ACTIVE_FILTER_OPTIONS, activeParam, notifySaved, toggleActiveRecord, type ActiveFilter,
} from "../../shared/ui/masterActions";
import { ICON_TINTS } from "../../shared/ui/navConfig";
import { enumLabel } from "../../lib/enums";
import type { StatusTone } from "../../theme";
import { t } from "../../lib/i18n";
import { fmtDecimal, fmtQuantity } from "../../lib/locale";
import { VehicleFormDrawer } from "./VehicleFormDrawer";

const PAGE_SIZE = 25;

/** Disponible / en taller / fuera de servicio. Los tres son estados legítimos de una flota, y
 * solo el último es un problema — el del medio es mantenimiento planificado. */
const AVAILABILITY_TONE: Record<VehicleAvailabilityStatus, StatusTone> = {
  AVAILABLE: "done",
  IN_MAINTENANCE: "inProgress",
  OUT_OF_SERVICE: "overdue",
};

interface AppliedFilters {
  code: string;
  licensePlate: string;
  carrierId: string;
  vehicleTypeId: string;
  availabilityStatus: VehicleAvailabilityStatus | "";
  active: ActiveFilter;
}

const DEFAULT_FILTERS: AppliedFilters = {
  code: "", licensePlate: "", carrierId: "", vehicleTypeId: "", availabilityStatus: "", active: "active",
};

type ModalState = { mode: "create" } | { mode: "edit"; vehicle: VehicleView } | null;

export function VehiclesPage() {
  const { selected, hasPermission } = useCompany();
  const companyId = selected?.id ?? "";
  const canManage = hasPermission("fleet.vehicle:manage");
  const queryClient = useQueryClient();

  const [page, setPage] = useState(0);
  // Los filtros se aplican al momento: no hay borrador ni botón de aplicar.
  const [filters, setFiltersState] = useState<AppliedFilters>(DEFAULT_FILTERS);
  const setFilters = (next: AppliedFilters) => { setFiltersState(next); setPage(0); };
  const [modal, setModal] = useState<ModalState>(null);
  const [availability, setAvailability] = useState<VehicleView | null>(null);
  const [showImport, setShowImport] = useState(false);

  const vehiclesQuery = useQuery({
    queryKey: ["vehicles", companyId, page, filters],
    queryFn: ({ signal }) =>
      fetchVehicles({
        companyId,
        page,
        size: PAGE_SIZE,
        sort: "code,asc",
        code: filters.code || undefined,
        licensePlate: filters.licensePlate || undefined,
        carrierId: filters.carrierId || undefined,
        vehicleTypeId: filters.vehicleTypeId || undefined,
        availabilityStatus: filters.availabilityStatus || undefined,
        active: activeParam(filters.active),
        signal,
      }),
    placeholderData: keepPreviousData,
  });

  const carriersQuery = useQuery({
    queryKey: ["carriers-for-filter", companyId],
    queryFn: ({ signal }) => fetchCarriers({ companyId, size: 200, active: true, sort: "code,asc", signal }),
    enabled: companyId !== "",
  });
  const typesQuery = useQuery({
    queryKey: ["vehicle-types-for-filter", companyId],
    queryFn: ({ signal }) => fetchVehicleTypes({ companyId, size: 200, active: true, sort: "code,asc", signal }),
    enabled: companyId !== "",
  });

  const refresh = () => void queryClient.invalidateQueries({ queryKey: ["vehicles", companyId] });


  async function toggleActive(vehicle: VehicleView) {
    const changed = await toggleActiveRecord({
      name: vehicle.licensePlate,
      active: vehicle.active,
      activate: () => activateVehicle(companyId, vehicle.id),
      deactivate: () => deactivateVehicle(companyId, vehicle.id),
    });
    if (changed) refresh();
  }

  const columns: DataTableColumn<VehicleView>[] = [
    {
      key: "plate",
      header: t("Placa / código"),
      render: (v) => (
        <Box sx={{ minWidth: 0 }}>
          <Typography variant="body2" sx={{ fontWeight: 700, letterSpacing: ".04em" }}>{v.licensePlate}</Typography>
          <Typography variant="caption" color="text.secondary">{v.code}</Typography>
        </Box>
      ),
    },
    { key: "type", header: t("Tipo de vehículo"), render: (v) => v.vehicleTypeName ?? v.vehicleTypeCode ?? "-" },
    { key: "carrier", header: t("Transportista"), render: (v) => v.carrierBusinessName ?? t("Flota propia") },
    // Las tres capacidades efectivas las resolvió el backend: aquí solo se muestran.
    { key: "weight", header: t("Peso máx. (kg)"), numeric: true, render: (v) => fmtDecimal(v.effectiveMaxWeightKg) },
    { key: "volume", header: t("Volumen máx. (m³)"), numeric: true, render: (v) => fmtDecimal(v.effectiveMaxVolumeM3) },
    { key: "pallets", header: t("Pallets máx."), numeric: true, render: (v) => fmtQuantity(v.effectiveMaxPallets) },
    {
      key: "availability",
      header: t("Disponibilidad"),
      render: (v) => (
        <StatusChip
          tone={AVAILABILITY_TONE[v.availabilityStatus]}
          label={enumLabel("vehicleAvailability", v.availabilityStatus)}
        />
      ),
    },
    { key: "active", header: t("Estado"), render: (v) => <ActiveBadge active={v.active} /> },
  ];

  if (canManage) {
    columns.push({
      key: "actions",
      header: t("Acciones"),
      actions: true,
      render: (vehicle) => (
        <ActionMenu
          items={[
            { key: "edit", label: t("Editar"), icon: <EditRounded />, onSelect: () => setModal({ mode: "edit", vehicle }) },
            {
              key: "availability",
              label: t("Disponibilidad"),
              icon: <BuildCircleRounded />,
              onSelect: () => setAvailability(vehicle),
            },
            {
              key: "active",
              label: vehicle.active ? t("Desactivar") : t("Activar"),
              icon: vehicle.active ? <BlockRounded /> : <CheckCircleRounded />,
              dangerous: vehicle.active,
              onSelect: () => void toggleActive(vehicle),
            },
          ]}
        />
      ),
    });
  }

  const pageData = vehiclesQuery.data;

  return (
    <>
      <PageHeader
        icon={<LocalShippingRounded />}
        tint={ICON_TINTS["/fleet/vehicles"]}
        title={t("Vehículos")}
        subtitle={t("Las unidades concretas de la flota, con la capacidad que aplica a cada una.")}
        onRefresh={refresh}
        refreshing={vehiclesQuery.isFetching}
        actions={canManage && (
          <>
            <Button variant="outlined" startIcon={<UploadRounded />} onClick={() => setShowImport(true)}>
              {t("Importar")}
            </Button>
            <Button variant="contained" startIcon={<AddRounded />} onClick={() => setModal({ mode: "create" })}>
              {t("Nuevo vehículo")}
            </Button>
          </>
        )}
      />

      <FilterBar
        value={filters}
        defaults={DEFAULT_FILTERS}
        onChange={setFilters}
        fields={[
          { type: "search", key: "licensePlate", placeholder: t("Placa") },
          { type: "select", key: "carrierId", label: t("Transportista"), icon: <BusinessRounded />,
            allLabel: t("Todos los transportistas"),
            options: (carriersQuery.data?.content ?? []).map((c) => ({ id: c.id, label: c.businessName })) },
          { type: "select", key: "vehicleTypeId", label: t("Tipo de vehículo"), icon: <CategoryRounded />,
            allLabel: t("Todos los tipos"),
            options: (typesQuery.data?.content ?? []).map((v) => ({ id: v.id, label: v.name })) },
          { type: "select", key: "availabilityStatus", label: t("Disponibilidad"), icon: <BuildCircleRounded />,
            allLabel: t("Toda disponibilidad"),
            options: VEHICLE_AVAILABILITY_STATUSES.map((st) => ({ id: st, label: enumLabel("vehicleAvailability", st) })) },
          { type: "select", key: "active", label: t("Estado"), icon: <ToggleOnRounded />,
            options: ACTIVE_FILTER_OPTIONS.map((o) => ({ id: o.value, label: t(o.label) })) },
          { type: "text", key: "code", label: t("Código"), more: true },
        ]}
      />

      <DataTable
        columns={columns}
        rows={pageData?.content ?? []}
        total={pageData?.totalElements}
        rowKey={(vehicle) => vehicle.id}
        isLoading={vehiclesQuery.isPending}
        error={vehiclesQuery.isError ? describeApiError(vehiclesQuery.error as ApiError) : null}
        onRetry={() => void vehiclesQuery.refetch()}
        emptyTitle={t("Sin vehículos")}
        emptyMessage={t("Crea un vehículo o ajusta los filtros.")}
        footer={pageData ? <Pagination page={pageData} onPageChange={setPage} /> : undefined}
      />

      {modal && (
        <VehicleFormDrawer
          companyId={companyId}
          vehicle={modal.mode === "edit" ? modal.vehicle : null}
          onClose={() => setModal(null)}
          onSaved={() => {
            const wasEdit = modal.mode === "edit";
            setModal(null);
            notifySaved(wasEdit);
            refresh();
          }}
        />
      )}

      {availability && (
        <AvailabilityDrawer
          companyId={companyId}
          resource="vehicle"
          resourceId={availability.id}
          resourceLabel={`${availability.code} · ${availability.licensePlate}`}
          canManage={canManage}
          onClose={() => setAvailability(null)}
        />
      )}

      {showImport && (
        <ImportDrawer<VehicleImportPreview>
          open
          apiBasePath={VEHICLE_IMPORT_BASE_PATH}
          companyId={companyId}
          title={t("Importar vehículos")}
          subtitle={t("Alta masiva desde una plantilla .xlsx o .csv.")}
          onClose={() => setShowImport(false)}
          onImported={refresh}
          renderItems={(items, outcomeLabel) => (
            <TableContainer component={Paper} variant="outlined" sx={{ maxHeight: 340 }}>
              <Table size="small" stickyHeader sx={dataTableSx}>
                <TableHead>
                  <TableRow>
                    <TableCell>{t("Estado")}</TableCell>
                    <TableCell>{t("Código")}</TableCell>
                    <TableCell>{t("Placa")}</TableCell>
                    <TableCell>{t("Tipo de vehículo")}</TableCell>
                    <TableCell>{t("Transportista")}</TableCell>
                    <TableCell>{t("Disponibilidad")}</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {items.map((item, index) => (
                    <TableRow key={`${item.code}-${index}`}>
                      <TableCell><ImportOutcomeChip outcome={item.outcome} label={outcomeLabel(item.outcome)} /></TableCell>
                      <TableCell sx={{ fontWeight: 700 }}>{item.code}</TableCell>
                      <TableCell>{item.licensePlate}</TableCell>
                      <TableCell>{item.vehicleTypeCode ?? "-"}</TableCell>
                      <TableCell>{item.carrierCode ?? "-"}</TableCell>
                      <TableCell>{enumLabel("vehicleAvailability", item.availabilityStatus)}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </TableContainer>
          )}
        />
      )}
    </>
  );
}
