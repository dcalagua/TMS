import { keepPreviousData, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import {
  Box, Button, Divider, IconButton, InputAdornment, MenuItem, Paper, TextField, Tooltip, Typography,
} from "@mui/material";
import { alpha } from "@mui/material/styles";
import { AddRounded, CallSplitRounded, LocationOnRounded, SearchRounded } from "@mui/icons-material";
import type { ApiError } from "../../shared/api/httpClient";
import type { OrderPriority } from "../../shared/api/ordersApi";
import { fetchDestinations } from "../../shared/api/destinationsApi";
import {
  assignOrderToTrip, fetchEligibleOrders,
  type EligibleOrderView, type PlanningRunView, type TripDetailView, type TripView,
} from "../../shared/api/planningApi";
import { describePlanningError } from "../../shared/api/problemMessages";
import {
  EmptyState, ErrorState, FilterMenuChip, FilterOptionList, LoadingState, Pagination,
} from "../../shared/ui/components";
import { notifyError, notifySuccess } from "../../lib/ui";
import { enumLabel } from "../../lib/enums";
import { R, T } from "../../theme";
import { t } from "../../lib/i18n";
import { SplitAssignDrawer } from "./SplitAssignDrawer";
import { fmtDecimal, fmtVolumeM3, fmtWeightKg } from "../../lib/locale";

const PAGE_SIZE = 10;

/** Solo Alta y Urgente llevan color: si todo destaca, nada destaca. */
const PRIORITY_COLOR: Partial<Record<OrderPriority, string>> = { HIGH: "warning.main", URGENT: "error.main" };

const MONO = "ui-monospace, SFMono-Regular, Menlo, Consolas, monospace";

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
  const [filters, setFilters] = useState({ destinationId: "", orderNumber: "" });
  const [search, setSearch] = useState("");
  // Pausa breve antes de consultar por número, para no pedir en cada tecla.
  useEffect(() => {
    if (search.trim() === filters.orderNumber) return;
    const timer = setTimeout(() => { setFilters((f) => ({ ...f, orderNumber: search.trim() })); setPage(0); }, 350);
    return () => clearTimeout(timer);
  }, [search, filters.orderNumber]);
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
  const destinationOptions = (destinationsQuery.data?.content ?? []).map((d) => ({ id: d.id, label: `${d.code} · ${d.name}` }));

  return (
    <Paper variant="outlined" sx={{ borderRadius: `${R.lg}px`, overflow: "hidden" }}>
      <Box sx={{ px: 1.75, pt: 1.5, pb: 1.5, display: "grid", gap: 1.25, borderBottom: "1px solid", borderColor: "divider" }}>
        {title && (
          <Box sx={{ display: "flex", alignItems: "center", gap: 1 }}>
            <Typography component="h2" sx={{ fontSize: T.body + 1, fontWeight: 800 }}>{title}</Typography>
            {pageData && (
              <Box sx={(th) => ({
                px: 0.75, borderRadius: 10, fontSize: 11.5, fontWeight: 700, lineHeight: "18px",
                bgcolor: pageData.totalElements > 0 ? alpha(th.palette.warning.main, th.palette.mode === "dark" ? 0.2 : 0.14) : "action.hover",
                color: pageData.totalElements > 0 ? "warning.dark" : "text.secondary",
              })}>
                {pageData.totalElements}
              </Box>
            )}
          </Box>
        )}
        {/* Los filtros se aplican al momento: el número con una pausa breve, el destino al elegir. */}
        <Box sx={{ display: "flex", gap: 1, alignItems: "center" }}>
          <TextField
            size="small" value={search} onChange={(e) => setSearch(e.target.value)}
            placeholder={t("Número de pedido")}
            slotProps={{
              htmlInput: { "aria-label": t("Buscar por número de pedido") },
              input: { startAdornment: <InputAdornment position="start"><SearchRounded sx={{ fontSize: 18 }} /></InputAdornment> },
            }}
            sx={{ flex: 1, minWidth: 0 }}
          />
          <FilterMenuChip
            icon={<LocationOnRounded />}
            label={t("Destino")}
            valueLabel={filters.destinationId ? (destinationOptions.find((o) => o.id === filters.destinationId)?.label ?? t("Seleccionado")) : null}
            onClear={() => { setFilters({ ...filters, destinationId: "" }); setPage(0); }}
          >
            {(close) => (
              <FilterOptionList
                options={destinationOptions} value={filters.destinationId} allLabel={t("Todos los destinos")}
                onSelect={(id) => { setFilters({ ...filters, destinationId: id }); setPage(0); close(); }}
              />
            )}
          </FilterMenuChip>
        </Box>
      </Box>

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
            const priorityColor = PRIORITY_COLOR[order.priority as OrderPriority];
            const window = order.requestedWindowStart && order.requestedWindowEnd
              ? `${order.requestedWindowStart.slice(0, 5)} – ${order.requestedWindowEnd.slice(0, 5)}` : null;
            return (
              <Box
                key={order.id}
                sx={{ px: 1.75, py: 1.25, minWidth: 0, borderBottom: "1px solid", borderColor: "divider", "&:last-of-type": { borderBottom: 0 } }}
              >
                <Box sx={{ display: "flex", alignItems: "center", justifyContent: "space-between", gap: 1 }}>
                  <Typography variant="body2" noWrap sx={{ minWidth: 0, fontFamily: MONO, fontWeight: 700, fontVariantNumeric: "tabular-nums" }}>
                    {order.orderNumber}
                  </Typography>
                  <Box sx={{ display: "inline-flex", alignItems: "center", gap: 0.75, flexShrink: 0 }}>
                    <Box aria-hidden sx={{ width: 7, height: 7, borderRadius: "50%", bgcolor: priorityColor ?? "divider" }} />
                    <Typography sx={{ fontSize: T.micro + 0.5, fontWeight: priorityColor ? 700 : 500, color: priorityColor ?? "text.secondary" }}>
                      {enumLabel("orderPriority", order.priority)}
                    </Typography>
                  </Box>
                </Box>
                <Typography variant="body2" noWrap sx={{ fontWeight: 600, mt: 0.25 }}>
                  {order.destinationName ?? order.destinationCode ?? "-"}
                  {order.customerName && order.customerName !== order.destinationName && (
                    <Box component="span" sx={{ fontWeight: 400, color: "text.secondary" }}> · {order.customerName}</Box>
                  )}
                </Typography>
                <Typography noWrap sx={{ fontSize: T.micro + 0.5, color: "text.secondary", fontVariantNumeric: "tabular-nums" }}>
                  {fmtWeightKg(order.pendingWeightKg)} · {fmtVolumeM3(order.pendingVolumeM3)} · {fmtDecimal(order.pendingPallets)} {t("pal.")}
                  {window && ` · ${window}`}
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
                  <Box sx={{ display: "flex", alignItems: "center", gap: 0.75, mt: 1 }}>
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
                      size="small" variant="contained" startIcon={<AddRounded />}
                      disabled={assigningOrderId === order.id || target === ""}
                      onClick={() => void assign(order)}
                      aria-label={t("Asignar {{number}}", { number: order.orderNumber })}
                      sx={{ flexShrink: 0 }}
                    >
                      {t("Asignar")}
                    </Button>
                    <Tooltip title={t("Repartir: asignar solo una parte")}>
                      <span>
                        <IconButton
                          size="small"
                          disabled={assigningOrderId === order.id}
                          onClick={() => setSplitting(order)}
                          aria-label={t("Repartir {{number}}", { number: order.orderNumber })}
                          sx={{ width: 34, height: 34, border: "1px solid", borderColor: "divider", borderRadius: `${R.sm}px` }}
                        >
                          <CallSplitRounded fontSize="small" />
                        </IconButton>
                      </span>
                    </Tooltip>
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
