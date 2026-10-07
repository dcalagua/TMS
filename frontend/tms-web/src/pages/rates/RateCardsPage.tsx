import { keepPreviousData, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Box, Button, Typography } from "@mui/material";
import {
  AddRounded, PaidRounded, EditRounded, BlockRounded, CheckCircleRounded,
  BusinessRounded, CategoryRounded, EventAvailableRounded, TagRounded, ToggleOnRounded,
} from "@mui/icons-material";
import type { ApiError } from "../../shared/api/httpClient";
import { fetchCarriers } from "../../shared/api/carriersApi";
import {
  activateRateCard, deactivateRateCard, fetchRateCards, RATE_CARD_SCOPES,
  type RateCardScope, type RateCardView,
} from "../../shared/api/ratesApi";
import { describeApiError } from "../../shared/api/problemMessages";
import { useCompany } from "../../shared/company/CompanyContext";
import {
  ActionMenu, ActiveBadge, DataTable, FilterBar, PageHeader, Pagination, type DataTableColumn,
} from "../../shared/ui/components";
import {
  ACTIVE_FILTER_OPTIONS, activeParam, notifySaved, toggleActiveRecord, type ActiveFilter,
} from "../../shared/ui/masterActions";
import { ICON_TINTS } from "../../shared/ui/navConfig";
import { enumLabel } from "../../lib/enums";
import { t } from "../../lib/i18n";
import { fmtDate, fmtMoney } from "../../lib/locale";
import { RateCardFormDrawer } from "./RateCardFormDrawer";

const PAGE_SIZE = 25;

interface AppliedFilters {
  code: string;
  name: string;
  carrierId: string;
  scope: RateCardScope | "";
  onDate: string;
  active: ActiveFilter;
}

const DEFAULT_FILTERS: AppliedFilters = {
  code: "", name: "", carrierId: "", scope: "", onDate: "", active: "active",
};

type ModalState = { mode: "create" } | { mode: "edit"; rateCard: RateCardView } | null;

/**
 * Los tarifarios de la empresa: qué se paga a cada transportista y sobre qué base.
 *
 * Detrás de su propia capability (`RATES_VIEW`): las tarifas son información comercial, y un rol
 * que hace funcionar el día no gana automáticamente el derecho a ver cuánto vale ese día.
 */
export function RateCardsPage() {
  const { selected, hasPermission } = useCompany();
  const companyId = selected?.id ?? "";
  const canManage = hasPermission("rates.rate_card:manage");
  const queryClient = useQueryClient();

  const [page, setPage] = useState(0);
  // Los filtros se aplican al momento: no hay borrador ni botón de aplicar.
  const [filters, setFiltersState] = useState<AppliedFilters>(DEFAULT_FILTERS);
  const setFilters = (next: AppliedFilters) => { setFiltersState(next); setPage(0); };
  const [modal, setModal] = useState<ModalState>(null);

  const cardsQuery = useQuery({
    queryKey: ["rate-cards", companyId, page, filters],
    queryFn: ({ signal }) =>
      fetchRateCards({
        companyId,
        page,
        size: PAGE_SIZE,
        sort: "code,asc",
        code: filters.code || undefined,
        name: filters.name || undefined,
        carrierId: filters.carrierId || undefined,
        scope: filters.scope || undefined,
        onDate: filters.onDate || undefined,
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

  const refresh = () => void queryClient.invalidateQueries({ queryKey: ["rate-cards", companyId] });

  async function toggleActive(rateCard: RateCardView) {
    const changed = await toggleActiveRecord({
      name: rateCard.name,
      active: rateCard.active,
      activate: () => activateRateCard(companyId, rateCard.id),
      deactivate: () => deactivateRateCard(companyId, rateCard.id),
    });
    if (changed) refresh();
  }

  const columns: DataTableColumn<RateCardView>[] = [
    { key: "code", header: t("Código"), render: (r) => <Typography variant="body2" sx={{ fontWeight: 700 }}>{r.code}</Typography> },
    { key: "name", header: t("Nombre"), render: (r) => r.name },
    { key: "carrier", header: t("Transportista"), render: (r) => r.carrierName ?? r.carrierCode ?? "-" },
    {
      key: "scope",
      header: t("Ámbito"),
      // El ámbito y su objetivo son un solo hecho: "Ruta" a secas no dice cuál.
      render: (r) => (
        <Box>
          <Typography variant="body2">{enumLabel("rateCardScope", r.scope)}</Typography>
          {r.scopeTargetName && (
            <Typography variant="caption" color="text.secondary">{r.scopeTargetName}</Typography>
          )}
        </Box>
      ),
    },
    { key: "vehicleType", header: t("Tipo de vehículo"), render: (r) => r.vehicleTypeName ?? t("Cualquier tipo") },
    {
      key: "validity",
      header: t("Vigencia"),
      render: (r) => (
        <Typography variant="body2" sx={{ fontVariantNumeric: "tabular-nums" }}>
          {fmtDate(r.validFrom)} → {r.validTo ? fmtDate(r.validTo) : "∞"}
        </Typography>
      ),
    },
    {
      key: "base",
      header: t("Importe base"),
      numeric: true,
      render: (r) => r.baseAmount === null ? "-" : fmtMoney(r.baseAmount, r.currency),
    },
    { key: "active", header: t("Estado"), render: (r) => <ActiveBadge active={r.active} /> },
  ];

  if (canManage) {
    columns.push({
      key: "actions",
      header: t("Acciones"),
      actions: true,
      render: (rateCard) => (
        <ActionMenu
          items={[
            { key: "edit", label: t("Editar"), icon: <EditRounded />, onSelect: () => setModal({ mode: "edit", rateCard }) },
            {
              key: "active",
              label: rateCard.active ? t("Desactivar") : t("Activar"),
              icon: rateCard.active ? <BlockRounded /> : <CheckCircleRounded />,
              dangerous: rateCard.active,
              onSelect: () => void toggleActive(rateCard),
            },
          ]}
        />
      ),
    });
  }

  const pageData = cardsQuery.data;

  return (
    <>
      <PageHeader
        icon={<PaidRounded />}
        tint={ICON_TINTS["/rates/rate-cards"]}
        title={t("Tarifarios")}
        subtitle={t("Qué se le paga a cada transportista y sobre qué base se calcula.")}
        onRefresh={refresh}
        refreshing={cardsQuery.isFetching}
        actions={canManage && (
          <Button variant="contained" startIcon={<AddRounded />} onClick={() => setModal({ mode: "create" })}>
            {t("Nuevo tarifario")}
          </Button>
        )}
      />

      <FilterBar
        value={filters}
        defaults={DEFAULT_FILTERS}
        onChange={setFilters}
        fields={[
          { type: "search", key: "name", placeholder: t("Nombre") },
          { type: "select", key: "carrierId", label: t("Transportista"), icon: <BusinessRounded />,
            allLabel: t("Todos los transportistas"),
            options: (carriersQuery.data?.content ?? []).map((c) => ({ id: c.id, label: c.businessName })) },
          { type: "select", key: "scope", label: t("Ámbito"), icon: <CategoryRounded />,
            allLabel: t("Todos"),
            options: RATE_CARD_SCOPES.map((s) => ({ id: s, label: enumLabel("rateCardScope", s) })) },
          // "Vigente el" y no un rango: la pregunta real es "qué tarifa aplica ese día".
          { type: "date", key: "onDate", label: t("Vigente el"), icon: <EventAvailableRounded /> },
          { type: "select", key: "active", label: t("Estado"), icon: <ToggleOnRounded />,
            options: ACTIVE_FILTER_OPTIONS.map((o) => ({ id: o.value, label: t(o.label) })) },
          { type: "text", key: "code", label: t("Código"), icon: <TagRounded />, more: true },
        ]}
      />

      <DataTable
        columns={columns}
        rows={pageData?.content ?? []}
        total={pageData?.totalElements}
        rowKey={(rateCard) => rateCard.id}
        isLoading={cardsQuery.isPending}
        error={cardsQuery.isError ? describeApiError(cardsQuery.error as ApiError) : null}
        onRetry={() => void cardsQuery.refetch()}
        emptyTitle={t("Sin tarifarios")}
        emptyMessage={t("Crea un tarifario o ajusta los filtros.")}
        footer={pageData ? <Pagination page={pageData} onPageChange={setPage} /> : undefined}
      />

      {modal && (
        <RateCardFormDrawer
          companyId={companyId}
          rateCard={modal.mode === "edit" ? modal.rateCard : null}
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
