import { keepPreviousData, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Button, Typography } from "@mui/material";
import {
  AddRounded, AltRouteRounded, EditRounded, BlockRounded, CheckCircleRounded,
  CropFreeRounded, TagRounded, ToggleOnRounded, TripOriginRounded,
} from "@mui/icons-material";
import type { ApiError } from "../../shared/api/httpClient";
import { activateRoute, deactivateRoute, fetchRoutes, type RouteView } from "../../shared/api/routesApi";
import { fetchOrigins } from "../../shared/api/originsApi";
import { fetchZones } from "../../shared/api/zonesApi";
import { describeApiError } from "../../shared/api/problemMessages";
import { useCompany } from "../../shared/company/CompanyContext";
import {
  ActionMenu, ActiveBadge, DataTable, FilterBar, PageHeader, Pagination, type DataTableColumn,
} from "../../shared/ui/components";
import {
  ACTIVE_FILTER_OPTIONS, activeParam, notifySaved, toggleActiveRecord, type ActiveFilter,
} from "../../shared/ui/masterActions";
import { ICON_TINTS } from "../../shared/ui/navConfig";
import { t } from "../../lib/i18n";
import { fmtQuantity } from "../../lib/locale";
import { RouteFormDrawer } from "./RouteFormDrawer";

const PAGE_SIZE = 25;

interface AppliedFilters {
  code: string;
  name: string;
  originId: string;
  zoneId: string;
  active: ActiveFilter;
}

const DEFAULT_FILTERS: AppliedFilters = { code: "", name: "", originId: "", zoneId: "", active: "active" };

type ModalState = { mode: "create" } | { mode: "edit"; routeId: string } | null;

export function RoutesPage() {
  const { selected, hasPermission } = useCompany();
  const companyId = selected?.id ?? "";
  const canManage = hasPermission("masterdata.route:manage");
  const queryClient = useQueryClient();

  const [page, setPage] = useState(0);
  // Los filtros se aplican al momento: no hay borrador ni botón de aplicar.
  const [filters, setFiltersState] = useState<AppliedFilters>(DEFAULT_FILTERS);
  const setFilters = (next: AppliedFilters) => { setFiltersState(next); setPage(0); };
  const [modal, setModal] = useState<ModalState>(null);

  const routesQuery = useQuery({
    queryKey: ["routes", companyId, page, filters],
    queryFn: ({ signal }) =>
      fetchRoutes({
        companyId,
        page,
        size: PAGE_SIZE,
        sort: "code,asc",
        code: filters.code || undefined,
        name: filters.name || undefined,
        originId: filters.originId || undefined,
        zoneId: filters.zoneId || undefined,
        active: activeParam(filters.active),
        signal,
      }),
    placeholderData: keepPreviousData,
  });

  const originsQuery = useQuery({
    queryKey: ["origins-for-route-filter", companyId],
    queryFn: ({ signal }) => fetchOrigins({ companyId, size: 200, active: true, sort: "code,asc", signal }),
    enabled: companyId !== "",
  });
  const zonesQuery = useQuery({
    queryKey: ["zones-for-filter", companyId],
    queryFn: ({ signal }) => fetchZones({ companyId, size: 200, active: true, sort: "code,asc", signal }),
    enabled: companyId !== "",
  });

  function refresh() {
    void queryClient.invalidateQueries({ queryKey: ["routes", companyId] });
    // El detalle abierto en caché describe la ruta anterior; invalidarlo evita que reabrirla
    // enseñe las paradas de antes de guardar.
    void queryClient.invalidateQueries({ queryKey: ["route", companyId] });
  }

  async function toggleActive(route: RouteView) {
    const changed = await toggleActiveRecord({
      name: route.name,
      active: route.active,
      activate: () => activateRoute(companyId, route.id),
      deactivate: () => deactivateRoute(companyId, route.id),
    });
    if (changed) refresh();
  }

  const columns: DataTableColumn<RouteView>[] = [
    { key: "code", header: t("Código"), render: (r) => <Typography variant="body2" sx={{ fontWeight: 700 }}>{r.code}</Typography> },
    { key: "name", header: t("Nombre"), render: (r) => r.name },
    { key: "origin", header: t("Origen"), render: (r) => r.originName ?? r.originCode ?? "-" },
    { key: "zone", header: t("Zona"), render: (r) => r.zoneName ?? "-" },
    { key: "frequency", header: t("Frecuencia"), render: (r) => r.frequencyName ?? "-" },
    { key: "stops", header: t("Paradas"), numeric: true, render: (r) => fmtQuantity(r.stopCount) },
    { key: "active", header: t("Estado"), render: (r) => <ActiveBadge active={r.active} /> },
  ];

  if (canManage) {
    columns.push({
      key: "actions",
      header: t("Acciones"),
      actions: true,
      render: (route) => (
        <ActionMenu
          items={[
            { key: "edit", label: t("Editar"), icon: <EditRounded />, onSelect: () => setModal({ mode: "edit", routeId: route.id }) },
            {
              key: "active",
              label: route.active ? t("Desactivar") : t("Activar"),
              icon: route.active ? <BlockRounded /> : <CheckCircleRounded />,
              dangerous: route.active,
              onSelect: () => void toggleActive(route),
            },
          ]}
        />
      ),
    });
  }

  const pageData = routesQuery.data;

  return (
    <>
      <PageHeader
        icon={<AltRouteRounded />}
        tint={ICON_TINTS["/masters/routes"]}
        title={t("Rutas")}
        subtitle={t("Recorridos con nombre: un origen, una secuencia de destinos y su cadencia de servicio.")}
        onRefresh={refresh}
        refreshing={routesQuery.isFetching}
        actions={canManage && (
          <Button variant="contained" startIcon={<AddRounded />} onClick={() => setModal({ mode: "create" })}>
            {t("Nueva ruta")}
          </Button>
        )}
      />

      <FilterBar
        value={filters}
        defaults={DEFAULT_FILTERS}
        onChange={setFilters}
        fields={[
          { type: "search", key: "name", placeholder: t("Nombre") },
          { type: "select", key: "originId", label: t("Origen"), icon: <TripOriginRounded />,
            allLabel: t("Todos los orígenes"),
            options: (originsQuery.data?.content ?? []).map((o) => ({ id: o.id, label: `${o.code} · ${o.name}` })) },
          { type: "select", key: "zoneId", label: t("Zona"), icon: <CropFreeRounded />,
            allLabel: t("Todas las zonas"),
            options: (zonesQuery.data?.content ?? []).map((z) => ({ id: z.id, label: z.name })) },
          { type: "select", key: "active", label: t("Estado"), icon: <ToggleOnRounded />,
            options: ACTIVE_FILTER_OPTIONS.map((o) => ({ id: o.value, label: t(o.label) })) },
          { type: "text", key: "code", label: t("Código"), icon: <TagRounded /> },
        ]}
      />

      <DataTable
        columns={columns}
        rows={pageData?.content ?? []}
        total={pageData?.totalElements}
        rowKey={(route) => route.id}
        isLoading={routesQuery.isPending}
        error={routesQuery.isError ? describeApiError(routesQuery.error as ApiError) : null}
        onRetry={() => void routesQuery.refetch()}
        emptyTitle={t("Sin rutas")}
        emptyMessage={t("Crea una ruta o ajusta los filtros.")}
        footer={pageData ? <Pagination page={pageData} onPageChange={setPage} /> : undefined}
      />

      {modal && (
        <RouteFormDrawer
          companyId={companyId}
          routeId={modal.mode === "edit" ? modal.routeId : null}
          onClose={() => setModal(null)}
          onSaved={() => {
            const wasEdit = modal.mode === "edit";
            setModal(null);
            notifySaved(wasEdit);
            refresh();
          }}
        />
      )}
    </>
  );
}
