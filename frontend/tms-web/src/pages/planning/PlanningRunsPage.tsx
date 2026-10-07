import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { Box, Button, Typography } from "@mui/material";
import {
  AddRounded, ViewKanbanRounded, ArrowForwardRounded, BoltRounded, PanToolOutlined,
  WarehouseRounded, EventRounded, FlagRounded,
} from "@mui/icons-material";
import type { ApiError } from "../../shared/api/httpClient";
import { fetchOrigins } from "../../shared/api/originsApi";
import {
  fetchPlanningRuns, PLANNING_RUN_STATUSES,
  type PlanningRunStatus, type PlanningRunView,
} from "../../shared/api/planningApi";
import { describeApiError } from "../../shared/api/problemMessages";
import { useCompany } from "../../shared/company/CompanyContext";
import {
  DataTable, FilterBar, PageHeader, Pagination, StatusChip, type DataTableColumn,
} from "../../shared/ui/components";
import { ICON_TINTS } from "../../shared/ui/navConfig";
import { enumLabel } from "../../lib/enums";
import type { StatusTone } from "../../theme";
import { t } from "../../lib/i18n";
import { fmtDate, fmtQuantity } from "../../lib/locale";
import { PlanningRunFormDrawer } from "./PlanningRunFormDrawer";

const PAGE_SIZE = 20;

const STATUS_TONE: Record<PlanningRunStatus, StatusTone> = {
  DRAFT: "open",
  CONFIRMED: "done",
  CANCELLED: "cancelled",
};

interface AppliedFilters {
  planNumber: string;
  originId: string;
  planningDateFrom: string;
  planningDateTo: string;
  status: PlanningRunStatus | "";
}

const DEFAULT_FILTERS: AppliedFilters = {
  planNumber: "", originId: "", planningDateFrom: "", planningDateTo: "", status: "",
};

/**
 * La puerta de entrada de la planificación manual: encuentra o abre un plan, y a partir de ahí
 * todo lo que pasa dentro es del tablero (`PlanningBoardPage`).
 *
 * Los planes nunca se editan desde aquí: solo se listan, se filtran y se crean. Un plan es el
 * contenedor de un día y un origen, y lo que se cambia dentro de él son viajes, no el plan.
 */
export function PlanningRunsPage() {
  const { selected, hasPermission } = useCompany();
  const companyId = selected?.id ?? "";
  const canManage = hasPermission("planning.plan:manage");
  const navigate = useNavigate();

  const [page, setPage] = useState(0);
  // Los filtros se aplican al momento: no hay borrador ni botón de aplicar.
  const [filters, setFiltersState] = useState<AppliedFilters>(DEFAULT_FILTERS);
  const setFilters = (next: AppliedFilters) => { setFiltersState(next); setPage(0); };
  const [showCreate, setShowCreate] = useState(false);

  const runsQuery = useQuery({
    queryKey: ["planning-runs", companyId, page, filters],
    queryFn: ({ signal }) =>
      fetchPlanningRuns({
        companyId,
        page,
        size: PAGE_SIZE,
        sort: "planningDate,desc",
        planNumber: filters.planNumber || undefined,
        originId: filters.originId || undefined,
        planningDateFrom: filters.planningDateFrom || undefined,
        planningDateTo: filters.planningDateTo || undefined,
        status: filters.status || undefined,
        signal,
      }),
    placeholderData: keepPreviousData,
  });

  const originsQuery = useQuery({
    queryKey: ["origins-for-planning-run-filter", companyId],
    queryFn: ({ signal }) => fetchOrigins({ companyId, size: 200, active: true, sort: "code,asc", signal }),
    enabled: companyId !== "",
  });

  const columns: DataTableColumn<PlanningRunView>[] = [
    { key: "planNumber", header: t("Plan"), render: (run) => (
        <Typography variant="body2" sx={{ fontWeight: 800, fontVariantNumeric: "tabular-nums", letterSpacing: ".01em", whiteSpace: "nowrap" }}>
          {run.planNumber}
        </Typography>
      ) },
    { key: "origin", header: t("Origen"), render: (run) => run.originName ?? run.originCode ?? "-" },
    { key: "planningDate", header: t("Fecha de planificación"), render: (run) => fmtDate(run.planningDate) },
    {
      key: "mode",
      header: t("Modo"),
      render: (run) => (
        <Box sx={{ display: "inline-flex", alignItems: "center", gap: 0.75, whiteSpace: "nowrap" }}>
          {run.mode === "AUTOMATIC"
            ? <BoltRounded aria-hidden sx={{ fontSize: 15, color: "text.secondary" }} />
            : <PanToolOutlined aria-hidden sx={{ fontSize: 14, color: "text.secondary" }} />}
          {run.mode === "AUTOMATIC" ? t("Automático") : t("Manual")}
        </Box>
      ),
    },
    {
      key: "status",
      header: t("Estado"),
      render: (run) => <StatusChip label={enumLabel("planningRunStatus", run.status)} tone={STATUS_TONE[run.status]} />,
    },
    { key: "trips", header: t("Viajes"), numeric: true, render: (run) => fmtQuantity(run.tripCount) },
    { key: "orders", header: t("Pedidos asignados"), numeric: true, render: (run) => fmtQuantity(run.assignedOrderCount) },
    {
      key: "actions",
      header: t("Acciones"),
      actions: true,
      render: (run) => (
        <Button
          size="small" variant="outlined" color="inherit" endIcon={<ArrowForwardRounded sx={{ fontSize: "15px !important" }} />}
          onClick={(e) => { e.stopPropagation(); navigate(`/planning/${run.id}`); }}
          sx={{ borderColor: "divider", whiteSpace: "nowrap" }}
        >
          {t("Abrir")}
        </Button>
      ),
    },
  ];

  const pageData = runsQuery.data;

  return (
    <>
      <PageHeader
        icon={<ViewKanbanRounded />}
        tint={ICON_TINTS["/planning"]}
        title={t("Planificación")}
        subtitle={t("Un plan por origen y día. Dentro de él se arman los viajes y se les asignan pedidos.")}
        onRefresh={() => void runsQuery.refetch()}
        refreshing={runsQuery.isFetching}
        actions={canManage && (
          <Button variant="contained" startIcon={<AddRounded />} onClick={() => setShowCreate(true)}>
            {t("Nuevo plan")}
          </Button>
        )}
      />

      <FilterBar
        value={filters}
        defaults={DEFAULT_FILTERS}
        onChange={setFilters}
        fields={[
          { type: "search", key: "planNumber", placeholder: t("Plan") },
          { type: "select", key: "originId", label: t("Origen"), icon: <WarehouseRounded />,
            allLabel: t("Todos los orígenes"),
            options: (originsQuery.data?.content ?? []).map((origin) => ({ id: origin.id, label: origin.name })) },
          { type: "dateRange", from: "planningDateFrom", to: "planningDateTo", label: t("Fecha de planificación"),
            icon: <EventRounded /> },
          { type: "select", key: "status", label: t("Estado"), icon: <FlagRounded />,
            allLabel: t("Todos los estados"),
            options: PLANNING_RUN_STATUSES.map((status) => ({ id: status, label: enumLabel("planningRunStatus", status) })) },
        ]}
      />

      <DataTable
        columns={columns}
        rows={pageData?.content ?? []}
        total={pageData?.totalElements}
        rowKey={(run) => run.id}
        isLoading={runsQuery.isPending}
        error={runsQuery.isError ? describeApiError(runsQuery.error as ApiError) : null}
        onRetry={() => void runsQuery.refetch()}
        emptyTitle={t("Sin planes")}
        emptyMessage={t("Abre un plan para un origen y una fecha para empezar a asignar pedidos a viajes.")}
        onRowClick={(run) => navigate(`/planning/${run.id}`)}
        footer={pageData ? <Pagination page={pageData} onPageChange={setPage} /> : undefined}
      />

      {showCreate && (
        <PlanningRunFormDrawer
          companyId={companyId}
          onClose={() => setShowCreate(false)}
          onCreated={(detail) => navigate(`/planning/${detail.run.id}`)}
        />
      )}
    </>
  );
}
