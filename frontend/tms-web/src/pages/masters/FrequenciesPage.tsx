import { keepPreviousData, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Box, Button, Chip, MenuItem, TextField, Typography } from "@mui/material";
import {
  AddRounded, CalendarViewWeekRounded, EditRounded, BlockRounded, CheckCircleRounded,
} from "@mui/icons-material";
import type { ApiError } from "../../shared/api/httpClient";
import {
  activateFrequency, deactivateFrequency, fetchFrequencies, type FrequencyView,
} from "../../shared/api/frequenciesApi";
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
import { FrequencyFormDrawer } from "./FrequencyFormDrawer";

const PAGE_SIZE = 25;

/** ISO-8601: 1=lunes … 7=domingo, el mismo orden que manda el backend. */
export const DAY_ABBREVIATIONS: Record<number, string> = {
  1: "Lun", 2: "Mar", 3: "Mié", 4: "Jue", 5: "Vie", 6: "Sáb", 7: "Dom",
};

export const DAY_NAMES: Record<number, string> = {
  1: "Lunes", 2: "Martes", 3: "Miércoles", 4: "Jueves", 5: "Viernes", 6: "Sábado", 7: "Domingo",
};

interface AppliedFilters {
  code: string;
  name: string;
  active: ActiveFilter;
}

const DEFAULT_FILTERS: AppliedFilters = { code: "", name: "", active: "active" };

type ModalState = { mode: "create" } | { mode: "edit"; frequency: FrequencyView } | null;

export function FrequenciesPage() {
  const { selected, hasPermission } = useCompany();
  const companyId = selected?.id ?? "";
  const canManage = hasPermission("masterdata.frequency:manage");
  const queryClient = useQueryClient();

  const [page, setPage] = useState(0);
  const [draft, setDraft] = useState<AppliedFilters>(DEFAULT_FILTERS);
  const [filters, setFilters] = useState<AppliedFilters>(DEFAULT_FILTERS);
  const [modal, setModal] = useState<ModalState>(null);

  const frequenciesQuery = useQuery({
    queryKey: ["frequencies", companyId, page, filters],
    queryFn: ({ signal }) =>
      fetchFrequencies({
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

  const refresh = () => void queryClient.invalidateQueries({ queryKey: ["frequencies", companyId] });

  function applyFilters() { setFilters(draft); setPage(0); }
  function resetFilters() { setDraft(DEFAULT_FILTERS); setFilters(DEFAULT_FILTERS); setPage(0); }

  async function toggleActive(frequency: FrequencyView) {
    const changed = await toggleActiveRecord({
      name: frequency.name,
      active: frequency.active,
      activate: () => activateFrequency(companyId, frequency.id),
      deactivate: () => deactivateFrequency(companyId, frequency.id),
    });
    if (changed) refresh();
  }

  const columns: DataTableColumn<FrequencyView>[] = [
    { key: "code", header: t("Código"), render: (f) => <Typography variant="body2" sx={{ fontWeight: 700 }}>{f.code}</Typography> },
    { key: "name", header: t("Nombre"), render: (f) => f.name },
    {
      key: "days",
      header: t("Días"),
      // Los siete días como pastillas, encendidas o apagadas: leer "L M X J V" de un vistazo es
      // más rápido que leer una lista separada por comas, y la forma es la misma en cada fila
      // aunque la cadencia cambie.
      render: (f) => (
        <Box sx={{ display: "inline-flex", gap: 0.4 }}>
          {[1, 2, 3, 4, 5, 6, 7].map((day) => {
            const enabled = f.weeklyRules.some((rule) => rule.dayOfWeek === day && rule.enabled);
            return (
              <Chip
                key={day}
                size="small"
                label={t(DAY_ABBREVIATIONS[day])}
                variant={enabled ? "filled" : "outlined"}
                color={enabled ? "primary" : "default"}
                sx={{
                  height: 20, fontSize: 10.5, "& .MuiChip-label": { px: 0.7 },
                  opacity: enabled ? 1 : 0.45,
                }}
              />
            );
          })}
        </Box>
      ),
    },
    { key: "description", header: t("Descripción"), render: (f) => f.description ?? "-" },
    { key: "active", header: t("Estado"), render: (f) => <ActiveBadge active={f.active} /> },
  ];

  if (canManage) {
    columns.push({
      key: "actions",
      header: t("Acciones"),
      actions: true,
      render: (frequency) => (
        <ActionMenu
          items={[
            { key: "edit", label: t("Editar"), icon: <EditRounded />, onSelect: () => setModal({ mode: "edit", frequency }) },
            {
              key: "active",
              label: frequency.active ? t("Desactivar") : t("Activar"),
              icon: frequency.active ? <BlockRounded /> : <CheckCircleRounded />,
              dangerous: frequency.active,
              onSelect: () => void toggleActive(frequency),
            },
          ]}
        />
      ),
    });
  }

  const pageData = frequenciesQuery.data;

  return (
    <>
      <PageHeader
        icon={<CalendarViewWeekRounded />}
        tint={ICON_TINTS["/masters/frequencies"]}
        title={t("Frecuencias")}
        subtitle={t("Cadencias semanales de servicio: qué días se despacha, con qué corte y con cuánta anticipación.")}
        onRefresh={refresh}
        refreshing={frequenciesQuery.isFetching}
        actions={canManage && (
          <Button variant="contained" startIcon={<AddRounded />} onClick={() => setModal({ mode: "create" })}>
            {t("Nueva frecuencia")}
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
        rowKey={(frequency) => frequency.id}
        isLoading={frequenciesQuery.isPending}
        error={frequenciesQuery.isError ? describeApiError(frequenciesQuery.error as ApiError) : null}
        onRetry={() => void frequenciesQuery.refetch()}
        emptyTitle={t("Sin frecuencias")}
        emptyMessage={t("Crea una frecuencia o ajusta los filtros.")}
        footer={pageData ? <Pagination page={pageData} onPageChange={setPage} /> : undefined}
      />

      {modal && (
        <FrequencyFormDrawer
          companyId={companyId}
          frequency={modal.mode === "edit" ? modal.frequency : null}
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
