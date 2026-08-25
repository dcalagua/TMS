import { keepPreviousData, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import {
  Box, Button, Divider, IconButton, MenuItem, Paper, TextField, Tooltip, Typography,
} from "@mui/material";
import { SearchRounded, CloseRounded, AddTaskRounded } from "@mui/icons-material";
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
import type { StatusTone } from "../../theme";
import { t } from "../../lib/i18n";
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
export function EligibleOrdersPanel({ companyId, run, trips, canManage, onAssigned }: EligibleOrdersPanelProps) {
  const queryClient = useQueryClient();
  const [page, setPage] = useState(0);
  const [draft, setDraft] = useState({ destinationId: "", orderNumber: "" });
  const [filters, setFilters] = useState({ destinationId: "", orderNumber: "" });
  const [assignTargets, setAssignTargets] = useState<Record<string, string>>({});
  const [assigningOrderId, setAssigningOrderId] = useState<string | null>(null);

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
    <Paper variant="outlined" sx={{ borderRadius: "10px", overflow: "hidden" }}>
      <Box
        component="form"
        onSubmit={(e) => { e.preventDefault(); applyFilters(); }}
        sx={{ p: 1.5, display: "grid", gap: 1.25 }}
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
        <Box sx={{ display: "flex", gap: 1 }}>
          <Button size="small" type="submit" variant="contained" startIcon={<SearchRounded />} sx={{ flex: 1 }}>
            {t("Aplicar filtros")}
          </Button>
          <Tooltip title={t("Limpiar")}>
            <IconButton size="small" onClick={resetFilters}><CloseRounded fontSize="small" /></IconButton>
          </Tooltip>
        </Box>
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
        <Box>
          {rows.map((order) => {
            const target = assignTargets[order.id] ?? draftTrips[0]?.id ?? "";
            return (
              <Box
                key={order.id}
                sx={{
                  px: 1.5, py: 1.25, borderBottom: "1px solid", borderColor: "divider",
                  "&:last-of-type": { borderBottom: 0 },
                }}
              >
                <Box sx={{ display: "flex", alignItems: "center", justifyContent: "space-between", gap: 1 }}>
                  <Typography variant="body2" sx={{ fontWeight: 800 }}>{order.orderNumber}</Typography>
                  <StatusChip
                    label={enumLabel("orderPriority", order.priority)}
                    tone={PRIORITY_TONE[order.priority as OrderPriority] ?? "neutral"}
                  />
                </Box>
                <Typography variant="caption" color="text.secondary" noWrap sx={{ display: "block" }}>
                  {order.destinationName ?? order.destinationCode ?? "-"}
                  {order.customerName && ` · ${order.customerName}`}
                </Typography>
                <Typography variant="caption" color="text.secondary" sx={{ display: "block", fontVariantNumeric: "tabular-nums" }}>
                  {fmtWeightKg(order.totalWeightKg)} · {fmtVolumeM3(order.totalVolumeM3)} · {fmtDecimal(order.totalPallets)} {t("pallets")}
                </Typography>

                {canManage && draftTrips.length > 0 && (
                  <Box sx={{ display: "flex", gap: 0.75, mt: 1 }}>
                    <TextField
                      select size="small" value={target}
                      onChange={(e) => setAssignTargets({ ...assignTargets, [order.id]: e.target.value })}
                      aria-label={t("Viaje de destino de {{number}}", { number: order.orderNumber })}
                      sx={{ flex: 1 }}
                    >
                      {draftTrips.map((trip) => (
                        <MenuItem key={trip.id} value={trip.id}>
                          {t("Viaje {{number}}", { number: trip.tripNumber })}
                          {trip.vehicleCode ? ` · ${trip.vehicleCode}` : ""}
                        </MenuItem>
                      ))}
                    </TextField>
                    <Button
                      size="small" variant="outlined" startIcon={<AddTaskRounded />}
                      disabled={assigningOrderId === order.id || target === ""}
                      onClick={() => void assign(order)}
                      aria-label={t("Asignar {{number}}", { number: order.orderNumber })}
                    >
                      {t("Asignar")}
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
          <Box sx={{ px: 1.5, py: 1 }}>
            <Pagination page={pageData} onPageChange={setPage} />
          </Box>
        </>
      )}
    </Paper>
  );
}
