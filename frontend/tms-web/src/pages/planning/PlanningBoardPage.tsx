import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Link, useParams } from "react-router-dom";
import {
  Box, Button, ButtonBase, Chip, Paper, Tab, Tabs, Typography, useMediaQuery, useTheme,
} from "@mui/material";
import {
  ArrowBackRounded, BoltRounded, AddRounded, LocalShippingRounded, CheckRounded,
} from "@mui/icons-material";
import type { ApiError } from "../../shared/api/httpClient";
import {
  cancelPlanningRun, confirmPlanningRun, fetchEligibleOrders, fetchPlanningRun,
} from "../../shared/api/planningApi";
import { describeApiError, describePlanningError } from "../../shared/api/problemMessages";
import { useCompany } from "../../shared/company/CompanyContext";
import {
  EmptyState, ErrorState, LoadingState, PageHeader, StatusChip,
} from "../../shared/ui/components";
import { confirmDialog, notifyError, notifySuccess } from "../../lib/ui";
import { enumLabel } from "../../lib/enums";
import { R, type StatusTone } from "../../theme";
import { t } from "../../lib/i18n";
import { fmtDate } from "../../lib/locale";
import { AutoPlanDrawer } from "./AutoPlanDrawer";
import { CreateTripDrawer } from "./CreateTripDrawer";
import { EligibleOrdersPanel } from "./EligibleOrdersPanel";
import { TripCard } from "./TripCard";
import { TripDetailDrawer } from "./TripDetailDrawer";
import { PlanSummaryStrip } from "./PlanSummaryStrip";

const STATUS_TONE: Record<"DRAFT" | "CONFIRMED" | "CANCELLED", StatusTone> = {
  DRAFT: "open",
  CONFIRMED: "done",
  CANCELLED: "cancelled",
};

/** Qué mitad del tablero enseña un teléfono. Dos columnas estrechas una al lado de la otra son
 * inservibles a 360px, así que por debajo de `lg` los paneles se vuelven pestañas en lugar de
 * encogerse. */
type MobilePanel = "orders" | "trips";

/**
 * El tablero de planificación: una sola llamada abre el plan con el resumen de capacidad de cada
 * viaje ya adjunto, así que esta pantalla nunca itera los viajes para construirse.
 *
 * Cada mutación de abajo vuelve a sincronizar el tablero invalidando esa única query, en lugar
 * de fusionar a mano respuestas parciales en estado local: el veredicto de capacidad es del
 * backend, y un merge en cliente es exactamente la forma de acabar enseñando uno que ya no es
 * verdad.
 */
export function PlanningBoardPage() {
  const theme = useTheme();
  const isNarrow = useMediaQuery(theme.breakpoints.down("lg"));
  const { runId } = useParams<{ runId: string }>();
  const { selected, hasPermission } = useCompany();
  const companyId = selected?.id ?? "";
  const canManageRun = hasPermission("planning.plan:manage") && hasPermission("planning.trip:manage");
  const canManageTrips = hasPermission("planning.trip:manage");
  const queryClient = useQueryClient();

  const queryKey = ["planning-run", companyId, runId];
  const runQuery = useQuery({
    queryKey,
    queryFn: ({ signal }) => fetchPlanningRun(companyId, runId as string, signal),
    enabled: runId !== undefined,
  });

  const [openTripId, setOpenTripId] = useState<string | null>(null);
  const [showCreateTrip, setShowCreateTrip] = useState(false);
  const [showAutoPlan, setShowAutoPlan] = useState(false);
  const [mobilePanel, setMobilePanel] = useState<MobilePanel>("orders");

  // Cuántos pedidos quedan por asignar, para el resumen del plan: una página de una fila de la
  // misma consulta del panel de la izquierda. Comparte su prefijo de clave, así que cada asignación
  // que invalida el panel invalida también este número.
  const run0 = runQuery.data?.run;
  const eligibleCountQuery = useQuery({
    queryKey: ["eligible-orders", companyId, runId, "count"],
    queryFn: ({ signal }) => fetchEligibleOrders({
      companyId, originId: run0!.originId, serviceDate: run0!.planningDate, page: 0, size: 1, signal,
    }),
    enabled: run0 !== undefined,
  });

  /**
   * Vuelve a sincronizar las dos mitades del tablero. La bolsa de elegibles también hay que
   * invalidarla: quitar un pedido de un viaje —o moverlo— lo devuelve a esa bolsa, y sin esto el
   * panel de la izquierda seguía enseñando una lista vieja hasta recargar la página.
   */
  function refreshBoard() {
    void queryClient.invalidateQueries({ queryKey });
    void queryClient.invalidateQueries({ queryKey: ["eligible-orders", companyId, runId] });
  }

  async function confirmPlan() {
    if (!runQuery.data) return;
    const { run } = runQuery.data;
    const confirmed = await confirmDialog({
      title: t("¿Confirmar el plan?"),
      text: t("{{number}} quedará confirmado y sus viajes pasarán a ejecución.", { number: run.planNumber }),
      confirmLabel: t("Confirmar plan"),
    });
    if (!confirmed) return;

    try {
      await confirmPlanningRun(companyId, run.id, { version: run.version });
      notifySuccess(t("Plan confirmado"), run.planNumber);
      refreshBoard();
    } catch (error) {
      notifyError(t("No se pudo confirmar el plan"), describePlanningError(error as ApiError));
    }
  }

  async function cancelPlan() {
    if (!runQuery.data) return;
    const { run } = runQuery.data;
    const confirmed = await confirmDialog({
      title: t("¿Cancelar este plan?"),
      text: t("{{number}} quedará cancelado y sus pedidos volverán a estar disponibles.", { number: run.planNumber }),
      confirmLabel: t("Cancelar plan"),
      dangerous: true,
    });
    if (!confirmed) return;

    try {
      await cancelPlanningRun(companyId, run.id, { version: run.version });
      notifySuccess(t("Plan cancelado"), run.planNumber);
      refreshBoard();
    } catch (error) {
      notifyError(t("No se pudo cancelar el plan"), describePlanningError(error as ApiError));
    }
  }

  if (runQuery.isPending) return <LoadingState label={t("Cargando el plan...")} />;

  if (runQuery.isError) {
    return (
      <ErrorState
        message={describeApiError(runQuery.error as ApiError)}
        onRetry={() => void runQuery.refetch()}
      />
    );
  }

  const { run, trips } = runQuery.data;
  const isDraft = run.status === "DRAFT";

  const ordersPanel = (
    <EligibleOrdersPanel
      companyId={companyId}
      run={run}
      trips={trips}
      canManage={isDraft && canManageTrips}
      onAssigned={refreshBoard}
      title={isNarrow ? undefined : t("Pedidos elegibles")}
    />
  );

  const tripsPanel = trips.length === 0 ? (
    <Paper variant="outlined" sx={{ borderRadius: `${R.lg}px` }}>
      <EmptyState
        icon={<LocalShippingRounded />}
        title={t("Este plan todavía no tiene viajes")}
        message={t("Crea un viaje o usa la planificación automática para proponerlos.")}
      />
    </Paper>
  ) : (
    <Box sx={{
      display: "grid", gap: 1.5,
      // Dos columnas en un escritorio normal y tres cuando de verdad caben, sin que una tarjeta
      // baje nunca de un ancho en el que las barras de capacidad se lean.
      gridTemplateColumns: "repeat(auto-fill, minmax(min(100%, 320px), 1fr))",
    }}>
      {trips.map((trip) => (
        <TripCard key={trip.id} trip={trip} onOpen={() => setOpenTripId(trip.id)} />
      ))}
      {isDraft && canManageTrips && (
        /* El mismo «Nuevo viaje» de la cabecera, donde se mira cuando faltan viajes. */
        <ButtonBase
          onClick={() => setShowCreateTrip(true)}
          sx={{
            minHeight: 200, borderRadius: `${R.lg}px`, border: "1.5px dashed", borderColor: "divider",
            display: "flex", flexDirection: "column", gap: 1, color: "text.secondary",
            "&:hover": { borderColor: "primary.main", color: "primary.main", bgcolor: "action.hover" },
          }}
        >
          <Box sx={{ width: 40, height: 40, borderRadius: "50%", display: "grid", placeItems: "center", bgcolor: "action.selected" }}>
            <AddRounded />
          </Box>
          <Typography sx={{ fontWeight: 800, color: "text.primary" }}>{t("Nuevo viaje")}</Typography>
          <Typography variant="caption">{t("Vehículo y hora de salida")}</Typography>
        </ButtonBase>
      )}
    </Box>
  );

  return (
    <>
      <Button
        component={Link} to="/planning" size="small" startIcon={<ArrowBackRounded sx={{ fontSize: "16px !important" }} />}
        sx={{ mb: 1.5, ml: -1, fontWeight: 700 }}
      >
        {t("Volver a planes")}
      </Button>

      <PageHeader
        title={run.planNumber}
        subtitle={`${run.originName ?? run.originCode ?? ""} · ${fmtDate(run.planningDate)}`}
        meta={
          <>
            <StatusChip label={enumLabel("planningRunStatus", run.status)} tone={STATUS_TONE[run.status]} />
            <Chip
              size="small" variant="outlined"
              label={t("{{count}} viajes", { count: trips.length })}
              sx={{ fontWeight: 700, color: "text.secondary", borderColor: "divider" }}
            />
          </>
        }
        onRefresh={refreshBoard}
        refreshing={runQuery.isFetching}
        actions={
          <Box sx={{ display: "flex", flexWrap: "wrap", gap: 1 }}>
            {isDraft && canManageTrips && (
              <>
                {/* Secundario y no principal: el plan que arma una persona sigue siendo el camino
                    normal, y este abre un paso de revisión en vez de hacer algo. */}
                <Button variant="outlined" color="inherit" startIcon={<BoltRounded />} onClick={() => setShowAutoPlan(true)} sx={{ borderColor: "divider" }}>
                  {t("Planificar automáticamente")}
                </Button>
                <Button variant="outlined" color="inherit" startIcon={<AddRounded />} onClick={() => setShowCreateTrip(true)} sx={{ borderColor: "divider" }}>
                  {t("Nuevo viaje")}
                </Button>
              </>
            )}
            {isDraft && canManageRun && (
              <>
                <Button variant="outlined" color="error" onClick={() => void cancelPlan()}>
                  {t("Cancelar plan")}
                </Button>
                <Button variant="contained" startIcon={<CheckRounded />} onClick={() => void confirmPlan()}>
                  {t("Confirmar plan")}
                </Button>
              </>
            )}
          </Box>
        }
      />

      <PlanSummaryStrip
        trips={trips}
        eligibleTotal={eligibleCountQuery.data?.totalElements}
        eligibleLoading={eligibleCountQuery.isPending}
      />

      {/* Por debajo de `lg` los dos paneles se vuelven pestañas; por encima, una vista partida. */}
      {isNarrow && (
        <Tabs
          value={mobilePanel}
          onChange={(_e, value: MobilePanel) => setMobilePanel(value)}
          variant="fullWidth"
          sx={{ mb: 2, borderBottom: "1px solid", borderColor: "divider" }}
        >
          <Tab value="orders" label={t("Pedidos")} />
          <Tab value="trips" label={t("Viajes")} />
        </Tabs>
      )}

      <Box sx={{
        display: "grid", gap: 2, alignItems: "start",
        gridTemplateColumns: { xs: "1fr", lg: "370px minmax(0, 1fr)" },
      }}>
        {(!isNarrow || mobilePanel === "orders") && (
          <Box sx={{ minWidth: 0 }}>
            {ordersPanel}
          </Box>
        )}
        {(!isNarrow || mobilePanel === "trips") && (
          <Box sx={{ minWidth: 0 }}>
            {!isNarrow && (
              <Typography
                component="h2" variant="overline" color="text.secondary"
                sx={{ display: "block", mb: 0.75, mt: -0.5, lineHeight: 1.6, letterSpacing: ".1em" }}
              >
                {t("Viajes")}
              </Typography>
            )}
            {tripsPanel}
          </Box>
        )}
      </Box>

      {openTripId && (
        <TripDetailDrawer
          companyId={companyId}
          tripId={openTripId}
          siblingTrips={trips}
          canManage={canManageTrips}
          onClose={() => setOpenTripId(null)}
          onChanged={refreshBoard}
        />
      )}

      {showAutoPlan && (
        <AutoPlanDrawer
          companyId={companyId}
          runId={run.id}
          runVersion={run.version}
          canApply={canManageRun && canManageTrips}
          onClose={() => setShowAutoPlan(false)}
          onApplied={refreshBoard}
        />
      )}

      {showCreateTrip && (
        <CreateTripDrawer
          companyId={companyId}
          runId={run.id}
          runVersion={run.version}
          run={run}
          onClose={() => setShowCreateTrip(false)}
          onCreated={() => {
            setShowCreateTrip(false);
            notifySuccess(t("Viaje creado"));
            refreshBoard();
          }}
        />
      )}
    </>
  );
}
