import { keepPreviousData, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import {
  Box, Button, Divider, IconButton, MenuItem, Paper, TextField, Tooltip, Typography,
} from "@mui/material";
import { FilterAltRounded, FilterAltOffOutlined, CallSplitRounded } from "@mui/icons-material";
import type { ApiError } from "../../shared/api/httpClient";
import type { OrderPriority } from "../../shared/api/ordersApi";
import { fetchDestinations } from "../../shared/api/destinationsApi";
import {
  assignOrderToTrip, fetchEligibleOrders,
  type EligibleOrderView, type PlanningRunView, type TripDetailView, type TripView,
} from "../../shared/api/planningApi";
import { describePlanningError } from "../../shared/api/problemMessages";
import { EmptyState, ErrorState, LoadingState, Pagination, StatusChip } from "../../shared/ui/components";
import { notifyError, notifySuccess } from "../../lib/ui";
import { enumLabel } from "../../lib/enums";
import { R, type StatusTone } from "../../theme";
import { t } from "../../lib/i18n";
import { SplitAssignDrawer } from "./SplitAssignDrawer";
import { fmtDecimal, fmtVolumeM3, fmtWeightKg } from "../../lib/locale";

const PAGE_SIZE = 10;

const PRIORITY_TONE: Record<OrderPriority, StatusTone> = {
  LOW: "neutral",
  NORMAL: "neutral",
  HIGH: "inProgress",
  URGENT: "overdue",
};

interface EligibleOrdersPanelProps {
  companyId: string;
  run: PlanningRunView;
  trips: TripView[];
  canManage: boolean;
  onAssigned: (detail: TripDetailView) => void;
  /** Rótulo en versalitas de la cabecera del panel; el tablero lo omite cuando ya hay pestañas. */
  title?: string;
}

/**
 * El panel izquierdo del tablero: los pedidos en `READY_FOR_PLANNING` para el origen y la fecha
 * de este plan, paginados — nunca cargados de golpe.
 *
 * El origen y la fecha de servicio no son filtros editables aquí: la elegibilidad exige que
 * coincidan exactamente los dos, así que ensancharlos solo listaría pedidos que la llamada de
 * asignación rechazaría después.
 *
 * Se pinta como una lista compacta y no como una tabla. Este panel es un tercio del tablero en
 * escritorio y una pestaña a ancho completo en un teléfono; una tabla de siete columnas en ese
 * ancho se va de lado y esconde justo las dos cosas que un planificador busca —el número de
 * pedido y su destino—. Cada fila sigue llevando todo: número, destino, prioridad, peso, volumen
 * y pallets, en dos líneas densas.
 */
export function EligibleOrdersPanel({ companyId, run, trips, canManage, onAssigned, title }: EligibleOrdersPanelProps) {
  const queryClient = useQueryClient();
  const [page, setPage] = useState(0);
  const [draft, setDraft] = useState({ destinationId: "", orderNumber: "" });
  const [filters, setFilters] = useState({ destinationId: "", orderNumber: "" });
  const [assignTargets, setAssignTargets] = useState<Record<string, string>>({});
  const [assigningOrderId, setAssigningOrderId] = useState<string | null>(null);
  const [splitting, setSplitting] = useState<EligibleOrderView | null>(null);

  const draftTrips = trips.filter((trip) => trip.status === "DRAFT");

  const eligibleQuery = useQuery({
    queryKey: ["eligible-orders", companyId, run.id, page, filters],
    queryFn: ({ signal }) =>
      fetchEligibleOrders({
        companyId,
        originId: run.originId,
        serviceDate: run.planningDate,
        destinationId: filters.destinationId || undefined,
        orderNumber: filters.orderNumber || undefined,
        page,
        size: PAGE_SIZE,
        sort: "orderNumber,asc",
        signal,
      }),
    placeholderData: keepPreviousData,
  });

  const destinationsQuery = useQuery({
    queryKey: ["destinations-for-eligible-orders", companyId],
    queryFn: ({ signal }) => fetchDestinations({ companyId, size: 200, active: true, sort: "code,asc", signal }),
  });

  function applyFilters() { setFilters(draft); setPage(0); }
  function resetFilters() {
    setDraft({ destinationId: "", orderNumber: "" });
    setFilters({ destinationId: "", orderNumber: "" });
    setPage(0);
  }

  const refreshEligible = () =>
    void queryClient.invalidateQueries({ queryKey: ["eligible-orders", companyId, run.id] });

  /**
   * Reparte un pedido: sube al viaje solo la parte indicada y deja el resto en la bolsa
   * (migración V37). El pedido no se duplica y su estado sigue siendo `READY_FOR_PLANNING`
   * mientras quede algo por colocar.
   */
  async function assignPart(
    order: EligibleOrderView, tripId: string,
    amounts: { weightKg: number; volumeM3: number; pallets: number },
  ) {
    setAssigningOrderId(order.id);
    try {
      const detail = await assignOrderToTrip(companyId, tripId, { orderId: order.id, ...amounts });
      notifySuccess(
        t("Parte del pedido asignada"),
        t("{{number}} se repartió hacia el viaje {{trip}}.", {
          number: order.orderNumber, trip: detail.trip.tripNumber,
        }),
      );
      setSplitting(null);
      refreshEligible();
      onAssigned(detail);
    } catch (error) {
      notifyError(t("No se pudo repartir el pedido"), describePlanningError(error as ApiError));
    } finally {
      setAssigningOrderId(null);
    }
  }

  async function assign(order: EligibleOrderView) {
    const targetTripId = assignTargets[order.id] ?? draftTrips[0]?.id;
    if (!targetTripId) return;

    setAssigningOrderId(order.id);
    try {
      const detail = await assignOrderToTrip(companyId, targetTripId, { orderId: order.id });
      notifySuccess(
        t("Pedido asignado"),
        t("{{number}} se asignó al viaje {{trip}}.", { number: order.orderNumber, trip: detail.trip.tripNumber }),
      );
      refreshEligible();
      onAssigned(detail);
    } catch (error) {
      // El rechazo de capacidad lo escribe el backend nombrando cada dimensión que no cupo, así
      // que aquí se muestra literal: `describePlanningError` es exactamente para esto.
      notifyError(t("No se pudo asignar el pedido"), describePlanningError(error as ApiError));
    } finally {
      setAssigningOrderId(null);
    }
  }

  const pageData = eligibleQuery.data;
  const rows = pageData?.content ?? [];

  return (
    <Paper variant="outlined" sx={{ borderRadius: `${R.lg}px`, overflow: "hidden" }}>
      {title && (
        <Typography
          component="h2" variant="overline" color="text.secondary"
          sx={{ display: "block", px: 1.75, pt: 1.5, lineHeight: 1.6, letterSpacing: ".1em" }}
        >
          {title}
        </Typography>
      )}
      <Box
        component="form"
        onSubmit={(e) => { e.preventDefault(); applyFilters(); }}
        sx={{
          px: 1.75, pt: title ? 1 : 1.75, pb: 1.5,
          display: "grid", gap: 1, alignItems: "center",
          gridTemplateColumns: "minmax(0, 1fr) minmax(0, 1fr) auto auto",
        }}
      >
        <TextField
          size="small" label={t("Pedido")} value={draft.orderNumber}
          onChange={(e) => setDraft({ ...draft, orderNumber: e.target.value })}
        />
        <TextField
          select size="small" label={t("Destino")} value={draft.destinationId}
          onChange={(e) => setDraft({ ...draft, destinationId: e.target.value })}
        >
          <MenuItem value="">{t("Todos los destinos")}</MenuItem>
          {(destinationsQuery.data?.content ?? []).map((destination) => (
            <MenuItem key={destination.id} value={destination.id}>{destination.name}</MenuItem>
          ))}
        </TextField>
        <Tooltip title={t("Aplicar filtros")}>
          <IconButton
            type="submit" size="small" aria-label={t("Aplicar filtros")}
            sx={{
              bgcolor: "primary.main", color: "primary.contrastText", borderRadius: `${R.sm}px`,
              width: 34, height: 34, "&:hover": { bgcolor: "primary.dark" },
            }}
          >
            <FilterAltRounded fontSize="small" />
          </IconButton>
        </Tooltip>
        <Tooltip title={t("Limpiar")}>
          <IconButton size="small" onClick={resetFilters} aria-label={t("Limpiar")} sx={{ borderRadius: `${R.sm}px` }}>
            <FilterAltOffOutlined fontSize="small" />
          </IconButton>
        </Tooltip>
      </Box>
      <Divider />

      {eligibleQuery.isPending ? (
        <LoadingState minHeight={200} />
      ) : eligibleQuery.isError ? (
        <ErrorState
          message={describePlanningError(eligibleQuery.error as ApiError)}
          onRetry={() => void eligibleQuery.refetch()}
        />
      ) : rows.length === 0 ? (
        <EmptyState
          title={t("Sin pedidos elegibles")}
          message={t("No hay pedidos liberados para este origen y esta fecha.")}
        />
      ) : (
        <Box sx={{ p: 1.25, display: "grid", gap: 1.25 }}>
          {rows.map((order) => {
            const target = assignTargets[order.id] ?? draftTrips[0]?.id ?? "";
            return (
              <Box
                key={order.id}
                sx={{
                  px: 1.5, py: 1.25, border: "1px solid", borderColor: "divider", borderRadius: `${R.md}px`,
                  minWidth: 0,
                }}
              >
                <Box sx={{ display: "flex", alignItems: "center", justifyContent: "space-between", gap: 1 }}>
                  <Typography variant="body2" noWrap sx={{ minWidth: 0, fontWeight: 800, fontVariantNumeric: "tabular-nums" }}>{order.orderNumber}</Typography>
                  <StatusChip
                    label={enumLabel("orderPriority", order.priority)}
                    tone={PRIORITY_TONE[order.priority as OrderPriority] ?? "neutral"}
                  />
                </Box>
                <Typography variant="caption" color="text.secondary" noWrap sx={{ display: "block", mt: 0.5 }}>
                  {order.destinationName ?? order.destinationCode ?? "-"}
                  {order.customerName && ` · ${order.customerName}`}
                </Typography>
                <Typography variant="caption" sx={{ display: "block", mt: 0.25, fontVariantNumeric: "tabular-nums", fontWeight: 600 }}>
                  {fmtWeightKg(order.pendingWeightKg)} · {fmtVolumeM3(order.pendingVolumeM3)} · {fmtDecimal(order.pendingPallets)} {t("pallets")}
                </Typography>
                {order.partiallyAllocated && (
                  // Parte ya viaja en otro camión. Se dice explícitamente y se muestra el total
                  // debajo: sin esto la fila de arriba parece el pedido entero y un planificador
                  // cargaría de nuevo lo que ya está cargado.
                  <Typography
                    variant="caption"
                    sx={{
                      display: "flex", alignItems: "center", gap: 0.5, mt: 0.25,
                      color: "warning.main", fontWeight: 700, fontVariantNumeric: "tabular-nums",
                    }}
                  >
                    <CallSplitRounded aria-hidden sx={{ fontSize: 14 }} />
                    {t("Repartido · pendiente de {{total}} pallets", {
                      total: fmtDecimal(order.totalPallets),
                    })}
                  </Typography>
                )}

                {canManage && draftTrips.length > 0 && (
                  <Box sx={{ display: "flex", alignItems: "center", gap: 0.75, mt: 1.25 }}>
                    <TextField
                      select size="small" value={target}
                      onChange={(e) => setAssignTargets({ ...assignTargets, [order.id]: e.target.value })}
                      aria-label={t("Viaje de destino de {{number}}", { number: order.orderNumber })}
                      sx={{
                        flex: 1, minWidth: 0,
                        "& .MuiInputBase-root": { minWidth: 0 },
                        "& .MuiSelect-select": { overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" },
                      }}
                    >
                      {draftTrips.map((trip) => (
                        <MenuItem key={trip.id} value={trip.id}>
                          {t("Viaje {{number}}", { number: trip.tripNumber })}
                          {trip.vehicleCode ? ` · ${trip.vehicleCode}` : ""}
                        </MenuItem>
                      ))}
                    </TextField>
                    <Button
                      size="small" variant="contained"
                      disabled={assigningOrderId === order.id || target === ""}
                      onClick={() => void assign(order)}
                      aria-label={t("Asignar {{number}}", { number: order.orderNumber })}
                      sx={{ flexShrink: 0 }}
                    >
                      {t("Asignar")}
                    </Button>
                    <Button
                      size="small" variant="text"
                      disabled={assigningOrderId === order.id}
                      onClick={() => setSplitting(order)}
                      aria-label={t("Repartir {{number}}", { number: order.orderNumber })}
                      sx={{ flexShrink: 0, minWidth: 0, px: 1 }}
                    >
                      {t("Repartir")}
                    </Button>
                  </Box>
                )}
              </Box>
            );
          })}
        </Box>
      )}

      {pageData && pageData.totalElements > 0 && (
        <>
          <Divider />
          <Box sx={{ px: 1.75, py: 1 }}>
            <Pagination page={pageData} onPageChange={setPage} />
          </Box>
        </>
      )}

      <SplitAssignDrawer
        open={splitting !== null}
        order={splitting}
        trips={draftTrips}
        submitting={assigningOrderId === splitting?.id}
        onClose={() => setSplitting(null)}
        onSubmit={(tripId, amounts) => { if (splitting) void assignPart(splitting, tripId, amounts); }}
      />
    </Paper>
  );
}
