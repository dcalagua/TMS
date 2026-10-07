import { keepPreviousData, useQuery, useQueryClient } from "@tanstack/react-query";
import { useCallback, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { Box, Button, Checkbox, Tooltip, Typography } from "@mui/material";
import { alpha, useTheme } from "@mui/material/styles";
import {
  EventRepeatRounded, CheckCircleRounded, WarningAmberRounded, BlockRounded, PanToolRounded,
  InventoryRounded, FactCheckRounded, OpenInNewRounded, PlaylistAddCheckRounded, ViewKanbanRounded,
  DoneAllRounded, ArrowForwardRounded, ChevronRightRounded,
} from "@mui/icons-material";
import type { ApiError } from "../../shared/api/httpClient";
import { describeApiError } from "../../shared/api/problemMessages";
import { fetchOrigins } from "../../shared/api/originsApi";
import { fetchRoutes } from "../../shared/api/routesApi";
import { createPlanningRun, fetchPlanningRuns } from "../../shared/api/planningApi";
import {
  NO_ROUTE, bulkReleaseOrders, fetchSchedulingBoard,
  fetchSchedulingSummary, isReleasable, needsOverride, planBulkRelease, primaryReason,
  type BulkReleaseResult, type SchedulingGroup,
  type SchedulingRow,
} from "../../shared/api/schedulingApi";
import { useCompany } from "../../shared/company/CompanyContext";
import {
  ActionMenu, AppCard, DataTable, PageHeader, Pagination, StatusChip,
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
import { ELIGIBILITY_COLOR, EligibilityChip, KpiStrip } from "./schedulingUi";
import { SchedulingFilterBar } from "./SchedulingFilterBar";
import { R, T } from "../../theme";

const PAGE_SIZE = 50;

/** Pila monoespaciada para números de pedido y fechas/horas de la tabla. */
const MONO = "ui-monospace, SFMono-Regular, Menlo, Consolas, monospace";

import { defaultFilters, toParams, type Filters } from "./schedulingFilterModel";

type Panel =
  | { kind: "detail"; orderId: string }
  | { kind: "hold"; orderId: string; orderNumber: string; row: SchedulingRow }
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
  // Los filtros se aplican al momento: no hay borrador ni botón de aplicar.
  const [filters, setFiltersState] = useState<Filters>(defaultFilters);
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

  const setFilters = useCallback((next: Filters) => { setFiltersState(next); setPage(0); setSelectedIds(new Set()); }, []);

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
      width: 190,
      render: (row) => (
        <Box sx={{ minWidth: 0 }}>
          <Typography variant="body2" noWrap sx={{ fontFamily: MONO, fontWeight: 700, letterSpacing: "-0.01em" }}>{row.orderNumber}</Typography>
          {row.activeHolds > 0 ? (
            <Typography noWrap sx={{ fontSize: T.micro + 0.5, fontWeight: 700, color: row.activeBlockingHolds > 0 ? "error.main" : "text.secondary" }}>
              {row.activeBlockingHolds > 0 ? t("Retenido") : t("Con nota de retención")}
            </Typography>
          ) : (
            <Typography noWrap sx={{ fontSize: T.micro + 0.5, color: "text.secondary", maxWidth: 200 }}>
              {row.customerName ?? row.customerReference ?? t("Sin cliente")}
            </Typography>
          )}
        </Box>
      ),
    },
    {
      key: "route",
      header: t("Ruta"),
      width: 250,
      render: (row) => (
        <Tooltip title={`${row.originName ?? row.originCode ?? "-"} → ${row.destinationName ?? row.destinationCode ?? "-"}`}>
          <Box sx={{ minWidth: 0, maxWidth: 270 }}>
            <Box sx={{ display: "flex", alignItems: "center", gap: 0.75, minWidth: 0 }}>
              <Typography noWrap sx={{ fontFamily: MONO, fontSize: T.micro + 0.5, fontWeight: 600, color: "text.secondary", flexShrink: 0 }}>
                {row.originCode ?? row.originName ?? "-"}
              </Typography>
              <ArrowForwardRounded aria-hidden sx={{ fontSize: 13, color: "text.secondary", flexShrink: 0 }} />
              <Typography variant="body2" noWrap sx={{ fontWeight: 600 }}>{row.destinationName ?? row.destinationCode ?? "-"}</Typography>
            </Box>
            {row.routeCode ? (
              <Typography noWrap sx={{ fontFamily: MONO, fontSize: T.micro, color: "text.secondary" }}>
                {t("Ruta")} {row.routeCode}
              </Typography>
            ) : (
              <Typography noWrap sx={{ fontSize: T.micro, color: "warning.dark", fontWeight: 700 }}>
                {enumLabel("routeResolution", row.routeResolution)}
              </Typography>
            )}
          </Box>
        </Tooltip>
      ),
    },
    {
      key: "date",
      header: t("Despacho"),
      width: 160,
      render: (row) => (
        <Box>
          <Typography variant="body2" noWrap sx={{ fontWeight: 600 }}>{fmtDate(row.scheduledDispatchDate)}</Typography>
          <Typography noWrap sx={{ fontSize: T.micro + 0.5, color: row.releaseDeadline ? "warning.dark" : "text.secondary" }}>
            {row.releaseDeadline ? `${t("Corte")} ${fmtDateTime(row.releaseDeadline)}` : t("Sin calendario")}
          </Typography>
        </Box>
      ),
    },
    {
      key: "status",
      header: t("Estado"),
      width: 180,
      render: (row) => (
        <StatusChip label={enumLabel("orderStatus", row.status)} tone={row.status === "READY_FOR_PLANNING" ? "open" : "neutral"} />
      ),
    },
    {
      key: "eligibility",
      header: t("Elegibilidad"),
      render: (row) => {
        const reason = primaryReason(row);
        const more = row.reasons.length - 1;
        return (
          <Box sx={{ minWidth: 0 }}>
            <EligibilityChip eligibility={row.eligibility} />
            <Typography noWrap sx={{
              fontSize: T.micro + 0.5, mt: "3px",
              color: reason ? `${ELIGIBILITY_COLOR[reason.severity]}.main` : "text.secondary",
              fontWeight: reason ? 600 : 400,
            }}>
              {reason ? enumLabel("schedulingReason", reason.code) : t("Sin observaciones")}
              {more > 0 && <Box component="span" sx={{ color: "text.secondary", fontWeight: 400 }}> +{more}</Box>}
            </Typography>
          </Box>
        );
      },
    },
    {
      key: "actions",
      header: "",
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
                  onSelect: () => setPanel({ kind: "hold", orderId: row.orderId, orderNumber: row.orderNumber, row }),
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

  const groups = summaryQuery.data?.groups ?? [];
  const groupColumns: DataTableColumn<SchedulingGroup>[] = [
    {
      key: "date",
      header: t("Despacho"),
      width: 140,
      render: (group) => {
        // La fecha se escribe fuerte solo en la primera fila de su día: las demás la repiten.
        const index = groups.indexOf(group);
        const first = index <= 0 || groups[index - 1].scheduledDispatchDate !== group.scheduledDispatchDate;
        return (
          <Typography variant="body2" noWrap sx={{ fontWeight: first ? 700 : 400, color: first ? "text.primary" : "text.secondary" }}>
            {fmtDate(group.scheduledDispatchDate)}
          </Typography>
        );
      },
    },
    {
      key: "group",
      header: t("Origen · Ruta"),
      width: 220,
      render: (group) => (
        <Box sx={{ display: "flex", alignItems: "center", gap: 0.5, minWidth: 0 }}>
          <Typography noWrap sx={{ fontFamily: MONO, fontSize: T.micro + 0.5, color: "text.secondary" }}>
            {group.originCode ?? group.originName ?? "-"}
          </Typography>
          <ChevronRightRounded aria-hidden sx={{ fontSize: 15, color: "text.secondary" }} />
          {group.routeCode
            ? <Typography noWrap sx={{ fontFamily: MONO, fontSize: T.body - 0.5, fontWeight: 700 }}>{group.routeCode}</Typography>
            : <Typography noWrap sx={{ fontSize: T.micro + 0.5, color: "warning.dark", fontWeight: 700 }}>{t("Sin ruta única")}</Typography>}
        </Box>
      ),
    },
    {
      // Una barra apilada en lugar de tres columnas de números: lo que importa es la proporción.
      key: "eligibility",
      header: t("Elegibilidad"),
      render: (group) => {
        const { total, eligible, warning, blocked } = group.counts;
        const pct = (n: number) => (total > 0 ? `${(n / total) * 100}%` : "0%");
        return (
          <Tooltip title={t("{{e}} elegibles · {{w}} con aviso · {{b}} bloqueados", { e: eligible, w: warning, b: blocked })}>
            <Box sx={{ display: "flex", alignItems: "center", gap: 1.25, minWidth: 0 }}>
              <Box aria-hidden sx={{ display: "flex", width: 180, flexShrink: 0, height: 8, borderRadius: 4, overflow: "hidden", bgcolor: "action.hover" }}>
                <Box sx={{ width: pct(eligible), bgcolor: "success.main" }} />
                <Box sx={{ width: pct(warning), bgcolor: "warning.main" }} />
                <Box sx={{ width: pct(blocked), bgcolor: "error.main" }} />
              </Box>
              <Typography noWrap sx={{ fontSize: T.micro + 0.5, fontWeight: 600, color: blocked > 0 ? "error.main" : "text.secondary", fontVariantNumeric: "tabular-nums" }}>
                {t("{{e}} de {{n}}", { e: fmtQuantity(eligible), n: fmtQuantity(total) })}
                {warning > 0 ? ` · ${fmtQuantity(warning)} ${t("aviso")}` : ""}
                {blocked > 0 ? ` · ${fmtQuantity(blocked)} ${t("bloq.")}` : ""}
              </Typography>
            </Box>
          </Tooltip>
        );
      },
    },
    {
      key: "holds", header: t("Retenidos"), numeric: true, width: 100,
      render: (group) => (
        <Box component="span" sx={{ fontWeight: group.counts.withHolds > 0 ? 700 : 400, color: group.counts.withHolds > 0 ? "secondary.main" : "text.secondary" }}>
          {fmtQuantity(group.counts.withHolds)}
        </Box>
      ),
    },
    {
      key: "released", header: t("Liberados"), numeric: true, width: 110,
      render: (group) => `${fmtQuantity(group.counts.released)} / ${fmtQuantity(group.counts.total)}`,
    },
    {
      key: "open",
      header: "",
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
  const setEligibility = (eligibility: Filters["eligibility"], hold: Filters["hold"] = filters.hold === "with" ? "" : filters.hold) =>
    setFilters({ ...filters, eligibility, hold });

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

      <KpiStrip
        loading={summaryQuery.isPending}
        items={[
          {
            key: "total", icon: <InventoryRounded />, color: "info", title: t("Total"),
            value: fmtQuantity(totals?.total ?? 0),
            active: filters.eligibility === "" && filters.hold !== "with",
            onClick: () => setEligibility(""),
          },
          {
            key: "eligible", icon: <CheckCircleRounded />, color: "success", title: t("Elegibles"),
            value: fmtQuantity(totals?.eligible ?? 0),
            active: filters.eligibility === "ELIGIBLE", onClick: () => setEligibility("ELIGIBLE"),
          },
          {
            key: "warning", icon: <WarningAmberRounded />, color: "warning", title: t("Con aviso"),
            value: fmtQuantity(totals?.warning ?? 0), hint: t("Requieren motivo"),
            active: filters.eligibility === "WARNING", onClick: () => setEligibility("WARNING"),
          },
          {
            key: "blocked", icon: <BlockRounded />, color: "error", title: t("Bloqueados"),
            value: fmtQuantity(totals?.blocked ?? 0), hint: t("No se pueden liberar"),
            active: filters.eligibility === "BLOCKED", onClick: () => setEligibility("BLOCKED"),
          },
          {
            key: "holds", icon: <PanToolRounded />, color: "secondary", title: t("Con retención"),
            value: fmtQuantity(totals?.withHolds ?? 0),
            active: filters.hold === "with", onClick: () => setEligibility("", "with"),
          },
        ]}
      />

      <SchedulingFilterBar
        value={filters}
        onChange={setFilters}
        origins={(originsQuery.data?.content ?? []).map((o) => ({ id: o.id, label: `${o.code} · ${o.name}` }))}
        routes={canReadRoutes
          ? (routesQuery.data?.content ?? []).map((r) => ({ id: r.code, label: `${r.code} · ${r.name}` }))
          : null}
      />

      {/* La tabla del resumen va a sangre dentro de la tarjeta: sin su propio borde ni radio. */}
      <Box sx={{ mb: 2, "& .MuiCard-root .MuiPaper-root": { border: 0, borderRadius: 0 } }}>
        <AppCard
          title={t("Resumen por origen, ruta y fecha")}
          flush
          actions={
            <Box aria-hidden sx={{ display: { xs: "none", sm: "flex" }, gap: 1.75, alignItems: "center" }}>
              {([["success.main", t("Elegibles")], ["warning.main", t("Con aviso")], ["error.main", t("Bloqueados")]] as const).map(([color, label]) => (
                <Box key={label} sx={{ display: "flex", alignItems: "center", gap: 0.75 }}>
                  <Box sx={{ width: 10, height: 10, borderRadius: "3px", bgcolor: color }} />
                  <Typography sx={{ fontSize: T.micro + 0.5, color: "text.secondary" }}>{label}</Typography>
                </Box>
              ))}
            </Box>
          }
        >
          <DataTable
            columns={groupColumns}
            rows={groups}
            rowKey={(group) => `${group.scheduledDispatchDate}-${group.originId}-${group.routeCode ?? NO_ROUTE}`}
            isLoading={summaryQuery.isPending}
            error={summaryQuery.isError ? describeApiError(summaryQuery.error as ApiError) : null}
            onRetry={() => void summaryQuery.refetch()}
            emptyTitle={t("Sin pedidos")}
            emptyMessage={t("Ningún pedido coincide con los filtros.")}
            maxHeight={280}
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
            bgcolor: alpha(th.palette.primary.main, th.palette.mode === "dark" ? 0.16 : 0.07),
            borderBottom: "1px solid", borderColor: "divider",
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
              </>
            )}
            <Box sx={{ flex: 1 }} />
            {selectedIds.size > 0 && (
              <Button size="small" variant="contained" startIcon={<PlaylistAddCheckRounded />}
                disabled={busy || releasableSelected === 0} onClick={() => void releaseSelected()}>
                {t("Liberar seleccionados")}
              </Button>
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
          onPlaceHold={(row) => setPanel({ kind: "hold", orderId: row.orderId, orderNumber: row.orderNumber, row })}
          onRelease={(row) => void releaseOne(row)}
        />
      )}
      {panel?.kind === "hold" && (
        <HoldDrawer
          companyId={companyId}
          orderId={panel.orderId}
          orderNumber={panel.orderNumber}
          row={panel.row}
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
