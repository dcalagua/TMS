import { keepPreviousData, useQuery, useQueryClient } from "@tanstack/react-query";
import { useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { Box, Button, Checkbox, MenuItem, TextField, Tooltip, Typography } from "@mui/material";
import { alpha, useTheme } from "@mui/material/styles";
import {
  EventRepeatRounded, CheckCircleRounded, WarningAmberRounded, BlockRounded, PanToolRounded,
  InventoryRounded, FactCheckRounded, OpenInNewRounded, PlaylistAddCheckRounded, ViewKanbanRounded,
  DoneAllRounded,
} from "@mui/icons-material";
import type { ApiError } from "../../shared/api/httpClient";
import { describeApiError } from "../../shared/api/problemMessages";
import { ORDER_PRIORITIES, type OrderPriority, type OrderStatus } from "../../shared/api/ordersApi";
import { fetchOrigins } from "../../shared/api/originsApi";
import { fetchRoutes } from "../../shared/api/routesApi";
import { createPlanningRun, fetchPlanningRuns } from "../../shared/api/planningApi";
import {
  ELIGIBILITIES, NO_ROUTE, activeFilterCount, bulkReleaseOrders, fetchSchedulingBoard,
  fetchSchedulingSummary, isReleasable, needsOverride, planBulkRelease, primaryReason,
  type BulkReleaseResult, type Eligibility, type SchedulingFilterParams, type SchedulingGroup,
  type SchedulingRow,
} from "../../shared/api/schedulingApi";
import { useCompany } from "../../shared/company/CompanyContext";
import {
  ActionMenu, AppCard, DataTable, PageHeader, Pagination, StatusChip, Toolbar,
  type DataTableColumn,
} from "../../shared/ui/components";
import { ICON_TINTS } from "../../shared/ui/navConfig";
import { confirmDialog, notifyError, notifySuccess } from "../../lib/ui";
import { enumLabel } from "../../lib/enums";
import { t } from "../../lib/i18n";
import { fmtDate, fmtDateTime, fmtQuantity } from "../../lib/locale";
import { BulkReleaseResultDrawer } from "./BulkReleaseResultDrawer";
import { HoldDrawer } from "./HoldDrawer";
import { SchedulingDetailDrawer } from "./SchedulingDetailDrawer";
import { askOverrideReason, reasonLabels, releaseWithOverride } from "./releaseFlow";
import { EligibilityChip, FilterKpiCard } from "./schedulingUi";
import { R } from "../../theme";
import { reasonTone } from "./schedulingLabels";

const PAGE_SIZE = 50;

/** Pila monoespaciada para números de pedido y fechas/horas de la tabla. */
const MONO = "ui-monospace, SFMono-Regular, Menlo, Consolas, monospace";

/** Filtros tal como los edita la pantalla: cadenas vacías en lugar de `undefined`. */
interface Filters {
  originId: string;
  routeCode: string;
  serviceDateFrom: string;
  serviceDateTo: string;
  customer: string;
  priority: OrderPriority | "";
  eligibility: Eligibility | "";
  hold: "" | "with" | "without";
  frequency: string;
  status: "" | Extract<OrderStatus, "NOT_READY" | "READY_FOR_PLANNING">;
}

function todayIso(): string {
  const now = new Date();
  const local = new Date(now.getTime() - now.getTimezoneOffset() * 60000);
  return local.toISOString().slice(0, 10);
}

function plusDays(iso: string, days: number): string {
  const date = new Date(`${iso}T00:00:00`);
  date.setDate(date.getDate() + days);
  const local = new Date(date.getTime() - date.getTimezoneOffset() * 60000);
  return local.toISOString().slice(0, 10);
}

function defaultFilters(): Filters {
  const today = todayIso();
  return {
    originId: "", routeCode: "", serviceDateFrom: today, serviceDateTo: plusDays(today, 7), customer: "",
    priority: "", eligibility: "", hold: "", frequency: "", status: "",
  };
}

/** Los filtros de pantalla en el vocabulario de la API. */
function toParams(filters: Filters): SchedulingFilterParams {
  return {
    originId: filters.originId || undefined,
    routeCode: filters.routeCode || undefined,
    serviceDateFrom: filters.serviceDateFrom || undefined,
    serviceDateTo: filters.serviceDateTo || undefined,
    customer: filters.customer.trim() || undefined,
    priority: filters.priority || undefined,
    eligibility: filters.eligibility || undefined,
    hasHold: filters.hold === "" ? undefined : filters.hold === "with",
    frequency: filters.frequency.trim() || undefined,
    status: filters.status || undefined,
  };
}

type Panel =
  | { kind: "detail"; orderId: string }
  | { kind: "hold"; orderId: string; orderNumber: string }
  | { kind: "bulk"; result: BulkReleaseResult }
  | null;

/**
 * Programación y Liberación (ADR-014).
 *
 * Una pantalla sobre endpoints que ya existen, no un motor: qué pedidos pueden pasar a
 * planificación, cuáles no y por qué, agrupados por origen, ruta derivada y fecha de despacho.
 * Liberar es la misma transición de siempre, `NOT_READY -> READY_FOR_PLANNING`, y la elegibilidad
 * que se enseña aquí no se guarda en ningún sitio: se calcula al leer, así que un cambio en una
 * ruta o una frecuencia se ve en cuanto se recarga.
 *
 * Lo que la pantalla decide es poco y está a la vista: qué filas se pueden seleccionar para una
 * liberación en bloque y cuándo pedir un motivo. El juicio de verdad lo hace el backend en cada
 * liberación, y esta pantalla le cree.
 */
export function SchedulingReleasePage() {
  const { selected, hasPermission } = useCompany();
  const companyId = selected?.id ?? "";
  const canRelease = hasPermission("orders.order:manage");
  const canManageHolds = hasPermission("orders.hold:manage");
  const canManagePlanning = hasPermission("planning.plan:manage");
  const canReadRoutes = hasPermission("masterdata.route:read");
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const theme = useTheme();

  const [page, setPage] = useState(0);
  const [draft, setDraft] = useState<Filters>(defaultFilters);
  const [filters, setFilters] = useState<Filters>(draft);
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());
  const [panel, setPanel] = useState<Panel>(null);
  const [busy, setBusy] = useState(false);

  const params = useMemo(() => toParams(filters), [filters]);

  const boardQuery = useQuery({
    queryKey: ["scheduling-board", companyId, page, params],
    queryFn: ({ signal }) => fetchSchedulingBoard({ companyId, page, size: PAGE_SIZE, ...params, signal }),
    placeholderData: keepPreviousData,
    enabled: companyId !== "",
  });
  const summaryQuery = useQuery({
    queryKey: ["scheduling-summary", companyId, params],
    queryFn: ({ signal }) => fetchSchedulingSummary(companyId, params, signal),
    placeholderData: keepPreviousData,
    enabled: companyId !== "",
  });
  const originsQuery = useQuery({
    queryKey: ["origins-for-scheduling-filter", companyId],
    queryFn: ({ signal }) => fetchOrigins({ companyId, size: 200, active: true, sort: "code,asc", signal }),
    enabled: companyId !== "",
  });
  const routesQuery = useQuery({
    queryKey: ["routes-for-scheduling-filter", companyId],
    queryFn: ({ signal }) => fetchRoutes({ companyId, size: 200, active: true, sort: "code,asc", signal }),
    enabled: companyId !== "" && canReadRoutes,
  });

  const rows = boardQuery.data?.content ?? [];
  const selectedRows = rows.filter((row) => selectedIds.has(row.orderId));
  const totals = summaryQuery.data?.totals;

  function refresh() {
    void queryClient.invalidateQueries({ queryKey: ["scheduling-board", companyId] });
    void queryClient.invalidateQueries({ queryKey: ["scheduling-summary", companyId] });
    void queryClient.invalidateQueries({ queryKey: ["orders", companyId] });
    void queryClient.invalidateQueries({ queryKey: ["eligible-orders", companyId] });
  }

  function applyFilters() { setFilters(draft); setPage(0); setSelectedIds(new Set()); }
  function resetFilters() { const reset = defaultFilters(); setDraft(reset); setFilters(reset); setPage(0); setSelectedIds(new Set()); }

  function toggle(row: SchedulingRow) {
    setSelectedIds((current) => {
      const next = new Set(current);
      if (next.has(row.orderId)) next.delete(row.orderId); else next.add(row.orderId);
      return next;
    });
  }

  function selectReleasablePage() {
    setSelectedIds(new Set(rows.filter(isReleasable).map((row) => row.orderId)));
  }

  async function releaseOne(row: SchedulingRow) {
    let reason: string | undefined;
    if (needsOverride(row)) {
      const asked = await askOverrideReason(row.reasons);
      if (asked === null) return;
      reason = asked;
    } else {
      const confirmed = await confirmDialog({
        title: t("¿Liberar el pedido para planificar?"),
        text: row.reasons.length > 0
          ? t("{{number}} pasará a Listo para planificar. Avisos: {{reasons}}.", { number: row.orderNumber, reasons: reasonLabels(row.reasons) })
          : t("{{number}} pasará a Listo para planificar.", { number: row.orderNumber }),
        confirmLabel: t("Liberar"),
      });
      if (!confirmed) return;
    }
    if (await releaseWithOverride(companyId, row.orderId, row.orderNumber, reason)) {
      setPanel(null);
      refresh();
    }
  }

  async function releaseSelected() {
    const plan = planBulkRelease(selectedRows);
    if (plan.toSend.length === 0) {
      notifyError(t("Nada que liberar"), t("La selección no tiene pedidos pendientes que se puedan liberar."));
      return;
    }
    let reason: string | undefined;
    if (plan.reasonNeeded) {
      const asked = await askOverrideReason(plan.toSend.flatMap((row) => row.reasons), plan.toSend.length);
      if (asked === null) return;
      reason = asked;
    } else {
      const confirmed = await confirmDialog({
        title: t("¿Liberar {{count}} pedidos?", { count: plan.toSend.length }),
        text: plan.skipped.length > 0
          ? t("{{skipped}} de la selección están bloqueados o ya liberados y no se enviarán.", { skipped: plan.skipped.length })
          : undefined,
        confirmLabel: t("Liberar"),
      });
      if (!confirmed) return;
    }
    setBusy(true);
    try {
      const result = await bulkReleaseOrders(companyId, plan.toSend.map((row) => row.orderId), reason);
      if (result.refused === 0) {
        notifySuccess(t("{{count}} pedidos liberados", { count: result.released }));
      } else {
        setPanel({ kind: "bulk", result });
      }
      setSelectedIds(new Set());
      refresh();
    } catch (error) {
      notifyError(t("No se pudo liberar la selección"), describeApiError(error as ApiError));
    } finally {
      setBusy(false);
    }
  }

  /** Abre la corrida borrador de ese origen y fecha, o la crea si no hay ninguna. */
  async function openPlanningRun(originId: string, date: string) {
    try {
      const existing = await fetchPlanningRuns({
        companyId, originId, planningDateFrom: date, planningDateTo: date, status: "DRAFT", size: 1,
      });
      const run = existing.content[0];
      if (run) { navigate(`/planning/${run.id}`); return; }
      if (!canManagePlanning) {
        notifyError(t("No hay corrida de planificación"), t("No existe una corrida borrador para ese origen y fecha, y no tienes permiso para crearla."));
        return;
      }
      const confirmed = await confirmDialog({
        title: t("¿Crear corrida de planificación?"),
        text: t("No hay una corrida borrador para este origen el {{date}}. Se creará una y se abrirá el tablero.", { date: fmtDate(date) }),
        confirmLabel: t("Crear y abrir"),
      });
      if (!confirmed) return;
      const created = await createPlanningRun(companyId, { originId, planningDate: date });
      navigate(`/planning/${created.run.id}`);
    } catch (error) {
      notifyError(t("No se pudo abrir la planificación"), describeApiError(error as ApiError));
    }
  }

  const columns: DataTableColumn<SchedulingRow>[] = [];
  if (canRelease) {
    columns.push({
      key: "select",
      header: "",
      width: 44,
      render: (row) => (
        <Checkbox
          size="small"
          checked={selectedIds.has(row.orderId)}
          disabled={!isReleasable(row)}
          onChange={() => toggle(row)}
          slotProps={{ input: { "aria-label": t("Seleccionar {{number}}", { number: row.orderNumber }) } }}
        />
      ),
    });
  }
  columns.push(
    {
      key: "order",
      header: t("Pedido"),
      render: (row) => (
        <Box>
          <Typography variant="body2" noWrap sx={{ fontFamily: MONO, fontWeight: 700, letterSpacing: "-0.01em" }}>{row.orderNumber}</Typography>
          {row.activeHolds > 0 && (
            <Typography variant="caption" color={row.activeBlockingHolds > 0 ? "error" : "text.secondary"}
              sx={{ display: "block", fontWeight: 700, lineHeight: 1.3 }}>
              {row.activeBlockingHolds > 0 ? t("Retenido") : t("Con nota de retención")}
            </Typography>
          )}
        </Box>
      ),
    },
    {
      key: "customer",
      header: t("Cliente"),
      render: (row) => (
        <Typography variant="body2" noWrap sx={{ maxWidth: "12rem" }}>{row.customerName ?? row.customerReference ?? "-"}</Typography>
      ),
    },
    {
      key: "origin",
      header: t("Origen"),
      render: (row) => <Typography variant="body2" noWrap sx={{ maxWidth: "10rem" }}>{row.originCode ?? row.originName ?? "-"}</Typography>,
    },
    {
      key: "destination",
      header: t("Destino"),
      render: (row) => (
        <Tooltip title={row.destinationName ?? ""}>
          <Typography variant="body2" noWrap sx={{ maxWidth: "12rem" }}>{row.destinationName ?? row.destinationCode ?? "-"}</Typography>
        </Tooltip>
      ),
    },
    {
      key: "route",
      header: t("Ruta"),
      render: (row) => row.routeCode
        ? <Typography variant="body2" noWrap>{row.routeCode}</Typography>
        : <Typography variant="caption" color="warning.dark" sx={{ fontWeight: 700 }}>{enumLabel("routeResolution", row.routeResolution)}</Typography>,
    },
    {
      key: "date",
      header: t("Fecha despacho"),
      render: (row) => <Typography variant="body2" noWrap sx={{ fontFamily: MONO }}>{fmtDate(row.scheduledDispatchDate)}</Typography>,
    },
    {
      key: "cutoff",
      header: t("Corte"),
      render: (row) => row.releaseDeadline
        ? <Typography variant="body2" noWrap sx={{ fontFamily: MONO }}>{fmtDateTime(row.releaseDeadline)}</Typography>
        : <Typography variant="caption" color="text.secondary">{t("Sin calendario")}</Typography>,
    },
    {
      key: "status",
      header: t("Estado"),
      render: (row) => (
        <StatusChip label={enumLabel("orderStatus", row.status)} tone={row.status === "READY_FOR_PLANNING" ? "open" : "neutral"} />
      ),
    },
    { key: "eligibility", header: t("Elegibilidad"), render: (row) => <EligibilityChip eligibility={row.eligibility} /> },
    {
      key: "reason",
      header: t("Motivo"),
      render: (row) => {
        const reason = primaryReason(row);
        if (!reason) return "-";
        const more = row.reasons.length - 1;
        return (
          <Box sx={{ display: "flex", gap: 0.5, alignItems: "center" }}>
            <StatusChip label={enumLabel("schedulingReason", reason.code)} tone={reasonTone(reason)} />
            {more > 0 && <Typography variant="caption" color="text.secondary">+{more}</Typography>}
          </Box>
        );
      },
    },
    {
      key: "actions",
      header: t("Acciones"),
      actions: true,
      render: (row) => (
        <ActionMenu
          items={[
            {
              key: "reasons", label: t("Ver razones"), icon: <FactCheckRounded />,
              onSelect: () => setPanel({ kind: "detail", orderId: row.orderId }),
            },
            ...(canRelease && isReleasable(row)
              ? [{ key: "release", label: t("Liberar"), icon: <CheckCircleRounded />, onSelect: () => void releaseOne(row) }]
              : []),
            ...(canManageHolds && row.status !== "CANCELLED" && row.status !== "DELIVERED"
              ? [{
                  key: "hold", label: t("Retener"), icon: <PanToolRounded />,
                  onSelect: () => setPanel({ kind: "hold", orderId: row.orderId, orderNumber: row.orderNumber }),
                }]
              : []),
            {
              key: "order", label: t("Ir al pedido"), icon: <OpenInNewRounded />,
              onSelect: () => navigate(`/orders?orderNumber=${encodeURIComponent(row.orderNumber)}`),
            },
            {
              key: "planning", label: t("Abrir planificación"), icon: <ViewKanbanRounded />, divider: true,
              onSelect: () => void openPlanningRun(row.originId, row.scheduledDispatchDate),
            },
          ]}
        />
      ),
    },
  );

  const groupColumns: DataTableColumn<SchedulingGroup>[] = [
    {
      key: "date",
      header: t("Fecha despacho"),
      render: (group) => <Typography variant="body2" noWrap sx={{ fontFamily: MONO }}>{fmtDate(group.scheduledDispatchDate)}</Typography>,
    },
    { key: "origin", header: t("Origen"), render: (group) => group.originCode ?? group.originName ?? "-" },
    {
      key: "route",
      header: t("Ruta"),
      render: (group) => group.routeCode ?? <Typography variant="caption" color="text.secondary">{t("Sin ruta única")}</Typography>,
    },
    { key: "total", header: t("Total"), numeric: true, render: (group) => fmtQuantity(group.counts.total) },
    {
      key: "eligible", header: t("Elegibles"), numeric: true,
      render: (group) => <Box component="span" sx={{ color: "primary.main", fontWeight: 700 }}>{fmtQuantity(group.counts.eligible)}</Box>,
    },
    { key: "warning", header: t("Con aviso"), numeric: true, render: (group) => fmtQuantity(group.counts.warning) },
    {
      key: "blocked", header: t("Bloqueados"), numeric: true,
      render: (group) => group.counts.blocked > 0
        ? <Box component="span" sx={{ color: "error.main", fontWeight: 700 }}>{fmtQuantity(group.counts.blocked)}</Box>
        : fmtQuantity(group.counts.blocked),
    },
    { key: "holds", header: t("Retenidos"), numeric: true, render: (group) => fmtQuantity(group.counts.withHolds) },
    { key: "released", header: t("Liberados"), numeric: true, render: (group) => fmtQuantity(group.counts.released) },
    {
      key: "open",
      header: t("Acciones"),
      actions: true,
      render: (group) => (
        <Button size="small" variant="outlined" color="inherit" startIcon={<ViewKanbanRounded />}
          sx={{ borderColor: "divider", whiteSpace: "nowrap" }}
          onClick={() => void openPlanningRun(group.originId, group.scheduledDispatchDate)}>
          {t("Planificación")}
        </Button>
      ),
    },
  ];

  const pageData = boardQuery.data;
  const releasableSelected = selectedRows.filter(isReleasable).length;

  return (
    <>
      <PageHeader
        icon={<EventRepeatRounded />}
        tint={ICON_TINTS["/scheduling"]}
        title={t("Programación y Liberación")}
        subtitle={t("Qué pedidos pueden pasar a planificación, cuáles no y por qué. La elegibilidad se calcula al leer: no se guarda en el pedido.")}
        onRefresh={refresh}
        refreshing={boardQuery.isFetching || summaryQuery.isFetching}
      />

      <Box sx={{
        display: "grid", gap: 2, mb: 2,
        gridTemplateColumns: { xs: "repeat(2, minmax(0, 1fr))", sm: "repeat(3, minmax(0, 1fr))", md: "repeat(5, minmax(0, 1fr))" },
      }}>
        <FilterKpiCard icon={<InventoryRounded />} color="info" title={t("Total")} value={fmtQuantity(totals?.total ?? 0)}
          loading={summaryQuery.isPending} active={filters.eligibility === "" && filters.hold !== "with"}
          onClick={() => { setDraft({ ...draft, eligibility: "" }); setFilters({ ...filters, eligibility: "" }); setPage(0); }} />
        <FilterKpiCard icon={<CheckCircleRounded />} color="success" title={t("Elegibles")} value={fmtQuantity(totals?.eligible ?? 0)}
          loading={summaryQuery.isPending} active={filters.eligibility === "ELIGIBLE"}
          onClick={() => { setDraft({ ...draft, eligibility: "ELIGIBLE" }); setFilters({ ...filters, eligibility: "ELIGIBLE" }); setPage(0); }} />
        <FilterKpiCard icon={<WarningAmberRounded />} color="warning" title={t("Con aviso")} value={fmtQuantity(totals?.warning ?? 0)}
          loading={summaryQuery.isPending} active={filters.eligibility === "WARNING"}
          onClick={() => { setDraft({ ...draft, eligibility: "WARNING" }); setFilters({ ...filters, eligibility: "WARNING" }); setPage(0); }} />
        <FilterKpiCard icon={<BlockRounded />} color="error" title={t("Bloqueados")} value={fmtQuantity(totals?.blocked ?? 0)}
          loading={summaryQuery.isPending} active={filters.eligibility === "BLOCKED"}
          onClick={() => { setDraft({ ...draft, eligibility: "BLOCKED" }); setFilters({ ...filters, eligibility: "BLOCKED" }); setPage(0); }} />
        <FilterKpiCard icon={<PanToolRounded />} color="secondary" title={t("Con retención")} value={fmtQuantity(totals?.withHolds ?? 0)}
          loading={summaryQuery.isPending} active={filters.hold === "with"}
          onClick={() => { setDraft({ ...draft, hold: "with" }); setFilters({ ...filters, hold: "with" }); setPage(0); }} />
      </Box>

      <Toolbar
        onApply={applyFilters}
        onReset={resetFilters}
        activeFilterCount={activeFilterCount(toParams(filters))}
        filters={
          <>
            <TextField select size="small" label={t("Origen")} value={draft.originId}
              onChange={(e) => setDraft({ ...draft, originId: e.target.value })} sx={{ minWidth: 170 }}>
              <MenuItem value="">{t("Todos los orígenes")}</MenuItem>
              {(originsQuery.data?.content ?? []).map((origin) => (
                <MenuItem key={origin.id} value={origin.id}>{origin.code} · {origin.name}</MenuItem>
              ))}
            </TextField>
            <TextField select size="small" label={t("Ruta")} value={draft.routeCode}
              onChange={(e) => setDraft({ ...draft, routeCode: e.target.value })} sx={{ minWidth: 150 }}>
              <MenuItem value="">{t("Todas las rutas")}</MenuItem>
              <MenuItem value={NO_ROUTE}>{t("Sin ruta única")}</MenuItem>
              {(routesQuery.data?.content ?? []).map((route) => (
                <MenuItem key={route.id} value={route.code}>{route.code}</MenuItem>
              ))}
            </TextField>
            <TextField size="small" type="date" label={t("Despacho desde")} value={draft.serviceDateFrom}
              onChange={(e) => setDraft({ ...draft, serviceDateFrom: e.target.value })}
              slotProps={{ inputLabel: { shrink: true } }} sx={{ minWidth: 150 }} />
            <TextField size="small" type="date" label={t("Despacho hasta")} value={draft.serviceDateTo}
              onChange={(e) => setDraft({ ...draft, serviceDateTo: e.target.value })}
              slotProps={{ inputLabel: { shrink: true } }} sx={{ minWidth: 150 }} />
            <TextField size="small" label={t("Cliente")} value={draft.customer}
              onChange={(e) => setDraft({ ...draft, customer: e.target.value })} sx={{ minWidth: 150 }} />
            <TextField select size="small" label={t("Prioridad")} value={draft.priority}
              onChange={(e) => setDraft({ ...draft, priority: e.target.value as OrderPriority | "" })} sx={{ minWidth: 140 }}>
              <MenuItem value="">{t("Todas las prioridades")}</MenuItem>
              {ORDER_PRIORITIES.map((priority) => (
                <MenuItem key={priority} value={priority}>{enumLabel("orderPriority", priority)}</MenuItem>
              ))}
            </TextField>
            <TextField select size="small" label={t("Elegibilidad")} value={draft.eligibility}
              onChange={(e) => setDraft({ ...draft, eligibility: e.target.value as Eligibility | "" })} sx={{ minWidth: 140 }}>
              <MenuItem value="">{t("Todas")}</MenuItem>
              {ELIGIBILITIES.map((eligibility) => (
                <MenuItem key={eligibility} value={eligibility}>{enumLabel("eligibility", eligibility)}</MenuItem>
              ))}
            </TextField>
            <TextField select size="small" label={t("Retención")} value={draft.hold}
              onChange={(e) => setDraft({ ...draft, hold: e.target.value as Filters["hold"] })} sx={{ minWidth: 140 }}>
              <MenuItem value="">{t("Todas")}</MenuItem>
              <MenuItem value="with">{t("Con retención")}</MenuItem>
              <MenuItem value="without">{t("Sin retención")}</MenuItem>
            </TextField>
            <TextField size="small" label={t("Frecuencia")} value={draft.frequency}
              onChange={(e) => setDraft({ ...draft, frequency: e.target.value })} sx={{ minWidth: 130 }} />
            <TextField select size="small" label={t("Estado")} value={draft.status}
              onChange={(e) => setDraft({ ...draft, status: e.target.value as Filters["status"] })} sx={{ minWidth: 170 }}>
              <MenuItem value="">{t("Pendientes y liberados")}</MenuItem>
              <MenuItem value="NOT_READY">{enumLabel("orderStatus", "NOT_READY")}</MenuItem>
              <MenuItem value="READY_FOR_PLANNING">{enumLabel("orderStatus", "READY_FOR_PLANNING")}</MenuItem>
            </TextField>
          </>
        }
      />

      {/* La tabla del resumen va a sangre dentro de la tarjeta: sin su propio borde ni radio. */}
      <Box sx={{ mb: 2, "& .MuiCard-root .MuiPaper-root": { border: 0, borderRadius: 0 } }}>
        <AppCard title={t("Resumen por origen, ruta y fecha")} flush>
          <DataTable
            columns={groupColumns}
            rows={summaryQuery.data?.groups ?? []}
            rowKey={(group) => `${group.scheduledDispatchDate}-${group.originId}-${group.routeCode ?? NO_ROUTE}`}
            isLoading={summaryQuery.isPending}
            error={summaryQuery.isError ? describeApiError(summaryQuery.error as ApiError) : null}
            onRetry={() => void summaryQuery.refetch()}
            emptyTitle={t("Sin pedidos")}
            emptyMessage={t("Ningún pedido coincide con los filtros.")}
            maxHeight={260}
          />
        </AppCard>
      </Box>

      {/* La barra de selección y la tabla forman un solo panel: la franja verde va encima de las
          filas que selecciona. Se le quita a la tabla su propio borde para no apilar dos. */}
      <Box sx={canRelease ? {
        border: "1px solid", borderColor: "divider", borderRadius: `${R.lg}px`, overflow: "hidden", bgcolor: "background.paper",
        "& > .MuiPaper-root": { border: 0, borderRadius: 0 },
      } : undefined}>
        {canRelease && (
          <Box sx={(th) => ({
            display: "flex", gap: 1.5, alignItems: "center", flexWrap: "wrap", px: 2, py: 1.25,
            bgcolor: alpha(th.palette.primary.main, th.palette.mode === "dark" ? 0.16 : 0.1),
            borderBottom: "1px solid", borderColor: alpha(th.palette.primary.main, 0.18),
          })}>
            <Button size="small" variant="outlined" color="inherit" startIcon={<DoneAllRounded />}
              onClick={selectReleasablePage} disabled={rows.length === 0}
              sx={{ bgcolor: "background.paper", borderColor: "divider", whiteSpace: "nowrap" }}>
              {t("Seleccionar liberables de la página")}
            </Button>
            {selectedIds.size > 0 && (
              <>
                <Typography variant="body2" sx={{ color: "primary.main", fontWeight: 700 }}>
                  {t("{{count}} seleccionados", { count: selectedIds.size })}
                </Typography>
                <Button size="small" color="inherit" sx={{ color: "text.secondary" }} onClick={() => setSelectedIds(new Set())}>
                  {t("Limpiar selección")}
                </Button>
                <Button size="small" variant="contained" startIcon={<PlaylistAddCheckRounded />}
                  sx={{ ml: "auto" }}
                  disabled={busy || releasableSelected === 0} onClick={() => void releaseSelected()}>
                  {t("Liberar seleccionados")}
                </Button>
              </>
            )}
          </Box>
        )}

        <DataTable
          columns={columns}
          rows={rows}
          total={pageData?.totalElements}
          rowKey={(row) => row.orderId}
          isLoading={boardQuery.isPending}
          error={boardQuery.isError ? describeApiError(boardQuery.error as ApiError) : null}
          onRetry={() => void boardQuery.refetch()}
          emptyTitle={t("Sin pedidos")}
          emptyMessage={t("Ningún pedido coincide con los filtros.")}
          rowAccent={(row) => row.eligibility === "BLOCKED" ? theme.palette.error.main : row.eligibility === "WARNING" ? theme.palette.warning.main : null}
          onRowClick={(row) => setPanel({ kind: "detail", orderId: row.orderId })}
          footer={pageData ? <Pagination page={pageData} onPageChange={(next) => { setPage(next); setSelectedIds(new Set()); }} /> : undefined}
        />
      </Box>

      {panel?.kind === "detail" && (
        <SchedulingDetailDrawer
          companyId={companyId}
          orderId={panel.orderId}
          canManageHolds={canManageHolds}
          canRelease={canRelease}
          onClose={() => setPanel(null)}
          onChanged={refresh}
          onPlaceHold={(row) => setPanel({ kind: "hold", orderId: row.orderId, orderNumber: row.orderNumber })}
          onRelease={(row) => void releaseOne(row)}
        />
      )}
      {panel?.kind === "hold" && (
        <HoldDrawer
          companyId={companyId}
          orderId={panel.orderId}
          orderNumber={panel.orderNumber}
          onClose={() => setPanel(null)}
          onPlaced={() => {
            setPanel(null);
            void queryClient.invalidateQueries({ queryKey: ["order-holds", companyId] });
            refresh();
          }}
        />
      )}
      {panel?.kind === "bulk" && <BulkReleaseResultDrawer result={panel.result} onClose={() => setPanel(null)} />}
    </>
  );
}
