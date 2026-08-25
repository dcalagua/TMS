import { keepPreviousData, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Button, MenuItem, TextField, Typography } from "@mui/material";
import { AddRounded, AltRouteRounded, EditRounded, BlockRounded, CheckCircleRounded } from "@mui/icons-material";
import type { ApiError } from "../../shared/api/httpClient";
import { activateRoute, deactivateRoute, fetchRoutes, type RouteView } from "../../shared/api/routesApi";
import { fetchOrigins } from "../../shared/api/originsApi";
import { fetchZones } from "../../shared/api/zonesApi";
import { describeApiError } from "../../shared/api/problemMessages";
import { useCompany } from "../../shared/company/CompanyContext";
import {
  ActionMenu, ActiveBadge, DataTable, PageHeader, Pagination, Toolbar, type DataTableColumn,
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
  const [draft, setDraft] = useState<AppliedFilters>(DEFAULT_FILTERS);
  const [filters, setFilters] = useState<AppliedFilters>(DEFAULT_FILTERS);
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

  function applyFilters() { setFilters(draft); setPage(0); }
  function resetFilters() { setDraft(DEFAULT_FILTERS); setFilters(DEFAULT_FILTERS); setPage(0); }

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

      <Toolbar
        onApply={applyFilters}
        onReset={resetFilters}
        filters={
          <>
            <TextField
              size="small" label={t("Código")} value={draft.code}
              onChange={(e) => setDraft({ ...draft, code: e.target.value })}
              sx={{ minWidth: 150 }}
            />
            <TextField
              size="small" label={t("Nombre")} value={draft.name}
              onChange={(e) => setDraft({ ...draft, name: e.target.value })}
              sx={{ minWidth: 180 }}
            />
            <TextField
              select size="small" label={t("Origen")} value={draft.originId}
              onChange={(e) => setDraft({ ...draft, originId: e.target.value })}
              sx={{ minWidth: 190 }}
            >
              <MenuItem value="">{t("Todos los orígenes")}</MenuItem>
              {(originsQuery.data?.content ?? []).map((origin) => (
                <MenuItem key={origin.id} value={origin.id}>{origin.code} · {origin.name}</MenuItem>
              ))}
            </TextField>
            <TextField
              select size="small" label={t("Zona")} value={draft.zoneId}
              onChange={(e) => setDraft({ ...draft, zoneId: e.target.value })}
              sx={{ minWidth: 170 }}
            >
              <MenuItem value="">{t("Todas las zonas")}</MenuItem>
              {(zonesQuery.data?.content ?? []).map((zone) => (
                <MenuItem key={zone.id} value={zone.id}>{zone.name}</MenuItem>
              ))}
            </TextField>
            <TextField
              select size="small" label={t("Estado")} value={draft.active}
              onChange={(e) => setDraft({ ...draft, active: e.target.value as ActiveFilter })}
              sx={{ minWidth: 150 }}
            >
              {ACTIVE_FILTER_OPTIONS.map((option) => (
                <MenuItem key={option.value} value={option.value}>{t(option.label)}</MenuItem>
              ))}
            </TextField>
          </>
        }
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
