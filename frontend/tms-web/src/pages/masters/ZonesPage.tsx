import { keepPreviousData, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Button, MenuItem, TextField, Typography } from "@mui/material";
import { AddRounded, CropFreeRounded, EditRounded, BlockRounded, CheckCircleRounded } from "@mui/icons-material";
import type { ApiError } from "../../shared/api/httpClient";
import { describeApiError } from "../../shared/api/problemMessages";
import { useCompany } from "../../shared/company/CompanyContext";
import { activateZone, deactivateZone, fetchZones, type ZoneView } from "../../shared/api/zonesApi";
import {
  ActionMenu, ActiveBadge, DataTable, PageHeader, Pagination, Toolbar, type DataTableColumn,
} from "../../shared/ui/components";
import {
  ACTIVE_FILTER_OPTIONS, activeParam, notifySaved, toggleActiveRecord, type ActiveFilter,
} from "../../shared/ui/masterActions";
import { ICON_TINTS } from "../../shared/ui/navConfig";
import { t } from "../../lib/i18n";
// El drawer viaja en el mismo trozo que la pantalla: quien abre Zonas casi siempre acaba
// creando o editando una, y un segundo viaje de red para eso solo añadiría un parpadeo.
import { ZoneFormDrawer } from "./ZoneFormDrawer";

const PAGE_SIZE = 25;

interface AppliedFilters {
  code: string;
  name: string;
  active: ActiveFilter;
}

const DEFAULT_FILTERS: AppliedFilters = { code: "", name: "", active: "active" };

type ModalState = { mode: "create" } | { mode: "edit"; zone: ZoneView } | null;

export function ZonesPage() {
  const { selected, hasPermission } = useCompany();
  const companyId = selected?.id ?? "";
  const canManage = hasPermission("masterdata.zone:manage");
  const queryClient = useQueryClient();

  const [page, setPage] = useState(0);
  const [draft, setDraft] = useState<AppliedFilters>(DEFAULT_FILTERS);
  const [filters, setFilters] = useState<AppliedFilters>(DEFAULT_FILTERS);
  const [modal, setModal] = useState<ModalState>(null);

  const zonesQuery = useQuery({
    queryKey: ["zones", companyId, page, filters],
    queryFn: ({ signal }) =>
      fetchZones({
        companyId,
        page,
        size: PAGE_SIZE,
        sort: "code,asc",
        code: filters.code || undefined,
        name: filters.name || undefined,
        active: activeParam(filters.active),
        signal,
      }),
    placeholderData: keepPreviousData,
  });

  const refresh = () => void queryClient.invalidateQueries({ queryKey: ["zones", companyId] });

  function applyFilters() { setFilters(draft); setPage(0); }
  function resetFilters() { setDraft(DEFAULT_FILTERS); setFilters(DEFAULT_FILTERS); setPage(0); }

  async function toggleActive(zone: ZoneView) {
    const changed = await toggleActiveRecord({
      name: zone.name,
      active: zone.active,
      activate: () => activateZone(companyId, zone.id),
      deactivate: () => deactivateZone(companyId, zone.id),
    });
    if (changed) refresh();
  }

  const columns: DataTableColumn<ZoneView>[] = [
    { key: "code", header: t("Código"), render: (zone) => <Typography variant="body2" sx={{ fontWeight: 700 }}>{zone.code}</Typography> },
    { key: "name", header: t("Nombre"), render: (zone) => zone.name },
    { key: "description", header: t("Descripción"), render: (zone) => zone.description ?? "-" },
    { key: "active", header: t("Estado"), render: (zone) => <ActiveBadge active={zone.active} /> },
  ];

  if (canManage) {
    columns.push({
      key: "actions",
      header: t("Acciones"),
      actions: true,
      render: (zone) => (
        <ActionMenu
          items={[
            { key: "edit", label: t("Editar"), icon: <EditRounded />, onSelect: () => setModal({ mode: "edit", zone }) },
            {
              key: "active",
              label: zone.active ? t("Desactivar") : t("Activar"),
              icon: zone.active ? <BlockRounded /> : <CheckCircleRounded />,
              dangerous: zone.active,
              onSelect: () => void toggleActive(zone),
            },
          ]}
        />
      ),
    });
  }

  const pageData = zonesQuery.data;

  return (
    <>
      <PageHeader
        icon={<CropFreeRounded />}
        tint={ICON_TINTS["/masters/zones"]}
        title={t("Zonas")}
        subtitle={t("Áreas operativas con nombre usadas para agrupar orígenes, destinos y rutas.")}
        onRefresh={refresh}
        refreshing={zonesQuery.isFetching}
        actions={canManage && (
          <Button variant="contained" startIcon={<AddRounded />} onClick={() => setModal({ mode: "create" })}>
            {t("Nueva zona")}
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
              sx={{ minWidth: 160 }}
            />
            <TextField
              size="small" label={t("Nombre")} value={draft.name}
              onChange={(e) => setDraft({ ...draft, name: e.target.value })}
              sx={{ minWidth: 200 }}
            />
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
        rowKey={(zone) => zone.id}
        isLoading={zonesQuery.isPending}
        error={zonesQuery.isError ? describeApiError(zonesQuery.error as ApiError) : null}
        onRetry={() => void zonesQuery.refetch()}
        emptyTitle={t("Sin zonas")}
        emptyMessage={t("Crea una zona o ajusta los filtros.")}
        footer={pageData ? <Pagination page={pageData} onPageChange={setPage} /> : undefined}
      />

      {modal && (
        <ZoneFormDrawer
          companyId={companyId}
          zone={modal.mode === "edit" ? modal.zone : null}
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
