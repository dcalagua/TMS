import { useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Alert, Typography } from "@mui/material";
import { Inventory2Rounded, LockRounded } from "@mui/icons-material";
import type { ApiError } from "../../shared/api/httpClient";
import type { MaterialProfileView } from "../../shared/api/materialProfilesApi";
import { describeApiError } from "../../shared/api/problemMessages";
import { useCompany } from "../../shared/company/CompanyContext";
import {
  DataTable, EmptyState, FilterBar, PageHeader, Pagination, type DataTableColumn,
} from "../../shared/ui/components";
import { ICON_TINTS } from "../../shared/ui/navConfig";
import { t } from "../../lib/i18n";
import { fmtDateTime, fmtQuantity } from "../../lib/locale";
import { MaterialProfileDrawer } from "./MaterialProfileDrawer";
import {
  MATERIAL_PROFILE_READ, dimensionsLabel, materialProfilesKey, stackModeLabel, useMaterialProfiles,
} from "./materialProfiles";

const PAGE_SIZE = 25;

interface Filters {
  search: string;
}

const DEFAULT_FILTERS: Filters = { search: "" };

/**
 * Perfiles de material: dimensiones, peso y reglas de apilado de cada material, tal como los
 * declara el almacén (EWM) y los sincroniza la API de integración.
 *
 * Solo lectura a propósito. El dueño del dato es el sistema de almacén; si TMS los editara, la
 * próxima sincronización pisaría el cambio sin avisar, o peor, los dos sistemas dejarían de
 * coincidir. Por eso no hay alta, edición ni baja, y la pantalla lo dice.
 */
export function MaterialProfilesPage() {
  const { selected, hasPermission } = useCompany();
  const companyId = selected?.id ?? "";
  const canRead = hasPermission(MATERIAL_PROFILE_READ);
  const queryClient = useQueryClient();

  const [page, setPage] = useState(0);
  const [filters, setFiltersState] = useState<Filters>(DEFAULT_FILTERS);
  const setFilters = (next: Filters) => { setFiltersState(next); setPage(0); };
  const [openRow, setOpenRow] = useState<MaterialProfileView | null>(null);

  const listQuery = useMaterialProfiles({
    companyId, search: filters.search, page, size: PAGE_SIZE, enabled: canRead,
  });

  const refresh = () => void queryClient.invalidateQueries({ queryKey: materialProfilesKey(companyId) });

  const columns: DataTableColumn<MaterialProfileView>[] = [
    {
      key: "materialCode",
      header: t("Código"),
      render: (p) => <Typography variant="body2" sx={{ fontWeight: 700 }}>{p.materialCode}</Typography>,
    },
    { key: "description", header: t("Descripción"), render: (p) => p.description ?? "-" },
    { key: "dimensions", header: t("L × A × H (m)"), render: (p) => dimensionsLabel(p) },
    { key: "stackMode", header: t("Apilado"), render: (p) => stackModeLabel(p.stackMode) },
    { key: "stackMaxQuantity", header: t("Unidades por pila"), numeric: true, render: (p) => fmtQuantity(p.stackMaxQuantity) },
    { key: "sourceSystem", header: t("Sistema origen"), render: (p) => p.sourceSystem },
    { key: "sourceUpdatedAt", header: t("Actualizado en origen"), render: (p) => fmtDateTime(p.sourceUpdatedAt) },
    { key: "updatedAt", header: t("Sincronizado"), render: (p) => fmtDateTime(p.updatedAt) },
  ];

  const pageData = listQuery.data;

  return (
    <>
      <PageHeader
        icon={<Inventory2Rounded />}
        tint={ICON_TINTS["/masters/material-profiles"]}
        title={t("Perfiles de material")}
        subtitle={t("Dimensiones, peso y reglas de apilado de cada material, como los declara el almacén.")}
        onRefresh={canRead ? refresh : undefined}
        refreshing={listQuery.isFetching}
      />

      {!canRead ? (
        <EmptyState
          icon={<LockRounded />}
          title={t("Sin acceso")}
          message={t("Tu cuenta no tiene permiso para ver los perfiles de material.")}
        />
      ) : (
        <>
          <Alert severity="info" sx={{ mb: 2 }}>
            {t("Los perfiles de material pertenecen al sistema de almacén (EWM) y se sincronizan por la API de integración. TMS no los edita.")}
          </Alert>

          <FilterBar
            value={filters}
            defaults={DEFAULT_FILTERS}
            onChange={setFilters}
            fields={[
              { type: "search", key: "search", placeholder: t("Código o descripción"), width: 300 },
            ]}
          />

          <DataTable
            columns={columns}
            rows={pageData?.content ?? []}
            total={pageData?.totalElements}
            rowKey={(p) => p.id}
            isLoading={listQuery.isPending}
            error={listQuery.isError ? describeApiError(listQuery.error as ApiError) : null}
            onRetry={() => void listQuery.refetch()}
            emptyTitle={t("Sin perfiles de material")}
            emptyMessage={filters.search.trim()
              ? t("Ningún perfil coincide con la búsqueda.")
              : t("El almacén todavía no sincronizó ningún perfil de material.")}
            onRowClick={setOpenRow}
            footer={pageData ? <Pagination page={pageData} onPageChange={setPage} /> : undefined}
          />
        </>
      )}

      {openRow && (
        <MaterialProfileDrawer companyId={companyId} row={openRow} onClose={() => setOpenRow(null)} />
      )}
    </>
  );
}
