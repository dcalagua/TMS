import { keepPreviousData, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import {
  Button, Chip, Paper, Table, TableBody, TableCell, TableContainer,
  TableHead, TableRow, Typography,
} from "@mui/material";
import {
  AddRounded, UploadRounded, AccountTreeRounded, EditRounded, BlockRounded,
  CheckCircleRounded, AcUnitRounded, CategoryRounded, QrCodeRounded, ToggleOnRounded,
} from "@mui/icons-material";
import type { ApiError } from "../../shared/api/httpClient";
import {
  activateVehicleType, deactivateVehicleType, fetchVehicleTypes, VEHICLE_BODY_TYPES,
  VEHICLE_TYPE_IMPORT_BASE_PATH,
  type VehicleBodyType, type VehicleTypeImportPreview, type VehicleTypeView,
} from "../../shared/api/vehicleTypesApi";
import { describeApiError } from "../../shared/api/problemMessages";
import { useCompany } from "../../shared/company/CompanyContext";
import {
  ActionMenu, ActiveBadge, DataTable, FilterBar, ImportDrawer, ImportOutcomeChip, PageHeader,
  Pagination, dataTableSx, type DataTableColumn,
} from "../../shared/ui/components";
import {
  ACTIVE_FILTER_OPTIONS, activeParam, notifySaved, toggleActiveRecord, type ActiveFilter,
} from "../../shared/ui/masterActions";
import { ICON_TINTS } from "../../shared/ui/navConfig";
import { enumLabel } from "../../lib/enums";
import { t } from "../../lib/i18n";
import { fmtDecimal, fmtQuantity } from "../../lib/locale";
import { VehicleTypeFormDrawer } from "./VehicleTypeFormDrawer";

const PAGE_SIZE = 25;

interface AppliedFilters {
  code: string;
  name: string;
  bodyType: VehicleBodyType | "";
  active: ActiveFilter;
}

const DEFAULT_FILTERS: AppliedFilters = { code: "", name: "", bodyType: "", active: "active" };

type ModalState = { mode: "create" } | { mode: "edit"; vehicleType: VehicleTypeView } | null;

export function VehicleTypesPage() {
  const { selected, hasPermission } = useCompany();
  const companyId = selected?.id ?? "";
  const canManage = hasPermission("fleet.vehicle-type:manage");
  const queryClient = useQueryClient();

  const [page, setPage] = useState(0);
  // Los filtros se aplican al momento: no hay borrador ni botón de aplicar.
  const [filters, setFiltersState] = useState<AppliedFilters>(DEFAULT_FILTERS);
  const setFilters = (next: AppliedFilters) => { setFiltersState(next); setPage(0); };
  const [modal, setModal] = useState<ModalState>(null);
  const [showImport, setShowImport] = useState(false);

  const typesQuery = useQuery({
    queryKey: ["vehicle-types", companyId, page, filters],
    queryFn: ({ signal }) =>
      fetchVehicleTypes({
        companyId,
        page,
        size: PAGE_SIZE,
        sort: "code,asc",
        code: filters.code || undefined,
        name: filters.name || undefined,
        bodyType: filters.bodyType || undefined,
        active: activeParam(filters.active),
        signal,
      }),
    placeholderData: keepPreviousData,
  });

  function refresh() {
    void queryClient.invalidateQueries({ queryKey: ["vehicle-types", companyId] });
    // Un vehículo hereda su capacidad del tipo, así que su lista queda obsoleta con esto.
    void queryClient.invalidateQueries({ queryKey: ["vehicles", companyId] });
  }

  async function toggleActive(vehicleType: VehicleTypeView) {
    const changed = await toggleActiveRecord({
      name: vehicleType.name,
      active: vehicleType.active,
      activate: () => activateVehicleType(companyId, vehicleType.id),
      deactivate: () => deactivateVehicleType(companyId, vehicleType.id),
    });
    if (changed) refresh();
  }

  const columns: DataTableColumn<VehicleTypeView>[] = [
    { key: "code", header: t("Código"), render: (v) => <Typography variant="body2" sx={{ fontWeight: 700 }}>{v.code}</Typography> },
    {
      key: "name",
      header: t("Nombre"),
      render: (v) => (
        <Typography variant="body2" sx={{ display: "flex", alignItems: "center", gap: 0.75 }}>
          {v.name}
          {/* La refrigeración no es un adorno: decide qué carga puede subir a esta unidad. */}
          {v.temperatureControlled && (
            <Chip
              size="small" variant="outlined" color="info" icon={<AcUnitRounded />}
              label={t("Refrigerado")} sx={{ height: 20, fontSize: 10.5 }}
            />
          )}
        </Typography>
      ),
    },
    { key: "bodyType", header: t("Tipo de carrocería"), render: (v) => v.bodyType ? enumLabel("vehicleBodyType", v.bodyType) : "-" },
    { key: "maxWeight", header: t("Peso máx. (kg)"), numeric: true, render: (v) => fmtDecimal(v.maxWeightKg) },
    { key: "maxVolume", header: t("Volumen máx. (m³)"), numeric: true, render: (v) => fmtDecimal(v.maxVolumeM3) },
    { key: "maxPallets", header: t("Pallets máx."), numeric: true, render: (v) => fmtQuantity(v.maxPallets) },
    { key: "active", header: t("Estado"), render: (v) => <ActiveBadge active={v.active} /> },
  ];

  if (canManage) {
    columns.push({
      key: "actions",
      header: t("Acciones"),
      actions: true,
      render: (vehicleType) => (
        <ActionMenu
          items={[
            { key: "edit", label: t("Editar"), icon: <EditRounded />, onSelect: () => setModal({ mode: "edit", vehicleType }) },
            {
              key: "active",
              label: vehicleType.active ? t("Desactivar") : t("Activar"),
              icon: vehicleType.active ? <BlockRounded /> : <CheckCircleRounded />,
              dangerous: vehicleType.active,
              onSelect: () => void toggleActive(vehicleType),
            },
          ]}
        />
      ),
    });
  }

  const pageData = typesQuery.data;

  return (
    <>
      <PageHeader
        icon={<AccountTreeRounded />}
        tint={ICON_TINTS["/fleet/vehicle-types"]}
        title={t("Tipos de vehículo")}
        subtitle={t("Plantillas de capacidad: peso, volumen y pallets que hereda cada vehículo del tipo.")}
        onRefresh={refresh}
        refreshing={typesQuery.isFetching}
        actions={canManage && (
          <>
            <Button variant="outlined" startIcon={<UploadRounded />} onClick={() => setShowImport(true)}>
              {t("Importar")}
            </Button>
            <Button variant="contained" startIcon={<AddRounded />} onClick={() => setModal({ mode: "create" })}>
              {t("Nuevo tipo de vehículo")}
            </Button>
          </>
        )}
      />

      <FilterBar
        value={filters}
        defaults={DEFAULT_FILTERS}
        onChange={setFilters}
        fields={[
          { type: "search", key: "name", placeholder: t("Nombre") },
          { type: "select", key: "bodyType", label: t("Tipo de carrocería"), icon: <CategoryRounded />,
            allLabel: t("Todos los tipos"),
            options: VEHICLE_BODY_TYPES.map((type) => ({ id: type, label: enumLabel("vehicleBodyType", type) })) },
          { type: "select", key: "active", label: t("Estado"), icon: <ToggleOnRounded />,
            options: ACTIVE_FILTER_OPTIONS.map((o) => ({ id: o.value, label: t(o.label) })) },
          { type: "text", key: "code", label: t("Código"), icon: <QrCodeRounded /> },
        ]}
      />

      <DataTable
        columns={columns}
        rows={pageData?.content ?? []}
        total={pageData?.totalElements}
        rowKey={(vehicleType) => vehicleType.id}
        isLoading={typesQuery.isPending}
        error={typesQuery.isError ? describeApiError(typesQuery.error as ApiError) : null}
        onRetry={() => void typesQuery.refetch()}
        emptyTitle={t("Sin tipos de vehículo")}
        emptyMessage={t("Crea un tipo de vehículo o ajusta los filtros.")}
        footer={pageData ? <Pagination page={pageData} onPageChange={setPage} /> : undefined}
      />

      {modal && (
        <VehicleTypeFormDrawer
          companyId={companyId}
          vehicleType={modal.mode === "edit" ? modal.vehicleType : null}
          onClose={() => setModal(null)}
          onSaved={() => {
            const wasEdit = modal.mode === "edit";
            setModal(null);
            notifySaved(wasEdit);
            refresh();
          }}
        />
      )}

      {showImport && (
        <ImportDrawer<VehicleTypeImportPreview>
          open
          apiBasePath={VEHICLE_TYPE_IMPORT_BASE_PATH}
          companyId={companyId}
          title={t("Importar tipos de vehículo")}
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
                    <TableCell>{t("Nombre")}</TableCell>
                    <TableCell className="numeric-col">{t("Peso máx. (kg)")}</TableCell>
                    <TableCell className="numeric-col">{t("Volumen máx. (m³)")}</TableCell>
                    <TableCell className="numeric-col">{t("Pallets máx.")}</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {items.map((item, index) => (
                    <TableRow key={`${item.code}-${index}`}>
                      <TableCell><ImportOutcomeChip outcome={item.outcome} label={outcomeLabel(item.outcome)} /></TableCell>
                      <TableCell sx={{ fontWeight: 700 }}>{item.code}</TableCell>
                      <TableCell>{item.name}</TableCell>
                      <TableCell className="numeric-col">{fmtDecimal(item.maxWeightKg)}</TableCell>
                      <TableCell className="numeric-col">{fmtDecimal(item.maxVolumeM3)}</TableCell>
                      <TableCell className="numeric-col">{fmtQuantity(item.maxPallets)}</TableCell>
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
