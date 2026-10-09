import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useMemo, useState, type ReactNode } from "react";
import {
  Alert, Box, Button, Checkbox, Chip, FormControlLabel, IconButton, MenuItem,
  Paper, TextField, ToggleButton, ToggleButtonGroup, Tooltip, Typography, useMediaQuery, useTheme,
} from "@mui/material";
import { alpha } from "@mui/material/styles";
import {
  LocalShippingRounded, ArrowUpwardRounded, ArrowDownwardRounded, DeleteOutlineRounded,
  SwapHorizRounded, SaveRounded, BadgeRounded, AltRouteRounded, CancelRounded, ScheduleRounded,
  BadgeOutlined, CheckRounded, CloseRounded, LocalShippingOutlined, RedoRounded, ReportRounded,
  WarehouseOutlined,
} from "@mui/icons-material";
import type { ApiError } from "../../shared/api/httpClient";
import { fetchRoutes } from "../../shared/api/routesApi";
import {
  cancelTrip, fetchTrip, moveOrderToTrip, recomputeTripEta, removeOrderFromTrip, reorderTripStops,
  updateTripRoute,
  type DeliveryResult, type StopExecutionStatus, type TripDetailView, type TripView,
} from "../../shared/api/planningApi";
import { describeApiError, describePlanningError } from "../../shared/api/problemMessages";
import { TripStopMap, type TripStopMapOrigin, type TripStopMapStop } from "../../shared/maps/TripStopMap";
import { CapacityBar, FormDrawer, StatusChip } from "../../shared/ui/components";
import { STOP_EXECUTION_TONE, TRIP_STATUS_TONE } from "../../shared/ui/statusTones";
import { confirmDialog, notifyError, notifySuccess } from "../../lib/ui";
import { enumLabel } from "../../lib/enums";
import { t, tp } from "../../lib/i18n";
import { fmtDate, fmtDateTime, fmtDecimal, fmtTime, fmtVolumeM3, fmtWeightKg } from "../../lib/locale";
import { R, T, type StatusTone } from "../../theme";
import { TripDriverDrawer } from "./TripDriverDrawer";
import { TripVehicleDrawer } from "./TripVehicleDrawer";

interface TripDetailDrawerProps {
  companyId: string;
  tripId: string;
  /** Los demás viajes del plan, para poder mover un pedido de uno a otro sin salir de aquí. */
  siblingTrips: TripView[];
  canManage: boolean;
  onClose: () => void;
  onChanged: () => void;
}

function formatServiceWindow(start: string | null, end: string | null): string | null {
  if (!start && !end) return null;
  const from = start?.slice(0, 5) ?? "--:--";
  const to = end?.slice(0, 5) ?? "--:--";
  return `${from} - ${to}`;
}

function moveItem<T>(items: T[], from: number, to: number): T[] {
  if (to < 0 || to >= items.length) return items;
  const next = items.slice();
  const [item] = next.splice(from, 1);
  next.splice(to, 0, item);
  return next;
}

/**
 * El envío por dentro: cabecera, capacidad, los pedidos que lleva, la secuencia de paradas y la
 * ruta sugerida.
 *
 * Cada mutación devuelve el `TripDetailView` actualizado y ese es el que se escribe en la caché:
 * el veredicto de capacidad, la secuencia y las transiciones permitidas son del backend, y
 * fusionar a mano una respuesta parcial es la forma de acabar enseñando una capacidad que ya no
 * es cierta.
 *
 * La cabecera es de solo lectura y la resuelve entera el servidor: el navegador nunca une un
 * origen, un transportista o una ruta a un viaje por su cuenta, así que lo que lee aquí un
 * planificador es lo mismo que publicaría una integración saliente.
 */
export function TripDetailDrawer({
  companyId, tripId, siblingTrips, canManage, onClose, onChanged,
}: TripDetailDrawerProps) {
  const theme = useTheme();
  const isNarrow = useMediaQuery(theme.breakpoints.down("md"));
  const queryClient = useQueryClient();
  const queryKey = ["trip", companyId, tripId];

  const tripQuery = useQuery({
    queryKey,
    queryFn: ({ signal }) => fetchTrip(companyId, tripId, signal),
  });
  const detail = tripQuery.data ?? null;
  const editable = detail !== null && canManage && detail.trip.status === "DRAFT";

  const [moveTargets, setMoveTargets] = useState<Record<string, string>>({});
  const [busyOrderId, setBusyOrderId] = useState<string | null>(null);
  const [showVehicleDrawer, setShowVehicleDrawer] = useState(false);
  const [showDriverDrawer, setShowDriverDrawer] = useState(false);
  const [stopOrder, setStopOrder] = useState<string[]>([]);
  const [savingStops, setSavingStops] = useState(false);
  const [routeId, setRouteId] = useState<string>("");
  const [applyRouteSequence, setApplyRouteSequence] = useState(true);
  const [savingRoute, setSavingRoute] = useState(false);
  const [selectedStopId, setSelectedStopId] = useState<string | null>(null);
  const [mobileStopTab, setMobileStopTab] = useState<"map" | "list">("list");

  const serverStopIds = detail
    ? detail.stops.slice().sort((a, b) => a.sequence - b.sequence).map((s) => s.destinationId)
    : [];
  const serverStopsKey = serverStopIds.join("|");

  // Vuelve a sembrar el orden local solo cuando cambia el *conjunto* de paradas (una asignación o
  // un movimiento añadió o quitó un destino), no en cada refetch: así un reordenamiento manual en
  // curso no lo pisa un refresco de capacidad que no tiene nada que ver. Deliberadamente clavado
  // en `serverStopsKey` y no en `detail`, que cambia de referencia en cada refetch.
  useEffect(() => {
    setStopOrder(serverStopIds);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [serverStopsKey]);

  // Una selección que se cayó del conjunto (el destino se quitó o se movió a otro viaje) seguiría
  // resaltando un marcador y una fila que ya no existen.
  useEffect(() => {
    if (selectedStopId && !stopOrder.includes(selectedStopId)) setSelectedStopId(null);
  }, [stopOrder, selectedStopId]);

  const mapOrigin = useMemo<TripStopMapOrigin | null>(() => {
    if (!detail || !detail.trip.originId) return null;
    return {
      latitude: detail.trip.originLatitude,
      longitude: detail.trip.originLongitude,
      label: detail.trip.originName ?? detail.trip.originCode ?? t("Origen"),
    };
  }, [detail]);

  // Numeradas desde el orden actual del planificador (posiblemente sin guardar) y no desde la
  // última secuencia guardada, para que el mapa refleje un movimiento arriba/abajo al momento en
  // lugar de solo después de "Guardar el orden".
  const mapStops = useMemo<TripStopMapStop[]>(() => {
    const stops = detail?.stops;
    if (!stops) return [];
    return stopOrder.map((destinationId, index) => {
      const stop = stops.find((s) => s.destinationId === destinationId);
      return {
        id: destinationId,
        sequence: index + 1,
        latitude: stop?.latitude ?? null,
        longitude: stop?.longitude ?? null,
        label: stop?.destinationName ?? stop?.destinationCode ?? destinationId,
      };
    });
  }, [stopOrder, detail]);

  const serverRouteId = detail?.trip.routeId ?? "";
  useEffect(() => { setRouteId(serverRouteId); }, [serverRouteId]);

  // Solo los corredores que de verdad salen del origen de este envío: el backend rechaza
  // cualquier otro con un 400, así que ofrecerlos sería ofrecer un error garantizado. Ni se piden
  // cuando el viaje no se puede editar: la ruta de un envío confirmado es un hecho, no una opción.
  const originId = detail?.trip.originId ?? null;
  const routesQuery = useQuery({
    queryKey: ["routes", companyId, "for-origin", originId],
    enabled: editable && originId !== null,
    queryFn: ({ signal }) =>
      fetchRoutes({ companyId, originId: originId ?? undefined, active: true, size: 200, sort: "code,asc", signal }),
  });

  function applyDetail(next: TripDetailView) {
    queryClient.setQueryData(queryKey, next);
    onChanged();
  }

  const targetTrips = siblingTrips.filter((trip) => trip.id !== tripId && trip.status === "DRAFT");

  async function removeOrder(orderId: string, orderNumber: string) {
    setBusyOrderId(orderId);
    try {
      applyDetail(await removeOrderFromTrip(companyId, tripId, orderId));
      notifySuccess(t("Pedido quitado del viaje"), orderNumber);
    } catch (error) {
      notifyError(t("No se pudo quitar el pedido"), describePlanningError(error as ApiError));
    } finally {
      setBusyOrderId(null);
    }
  }

  async function moveOrder(orderId: string, orderNumber: string) {
    const targetTripId = moveTargets[orderId] ?? targetTrips[0]?.id;
    if (!targetTripId) return;

    setBusyOrderId(orderId);
    try {
      applyDetail(await moveOrderToTrip(companyId, tripId, orderId, { targetTripId }));
      notifySuccess(t("Pedido movido"), orderNumber);
    } catch (error) {
      notifyError(t("No se pudo mover el pedido"), describePlanningError(error as ApiError));
    } finally {
      setBusyOrderId(null);
    }
  }

  async function saveStopOrder() {
    setSavingStops(true);
    try {
      applyDetail(await reorderTripStops(companyId, tripId, { destinationIds: stopOrder }));
      notifySuccess(t("Orden de paradas guardado"));
    } catch (error) {
      notifyError(t("No se pudo guardar el orden de paradas"), describePlanningError(error as ApiError));
    } finally {
      setSavingStops(false);
    }
  }

  /**
   * Guarda la referencia de ruta. `applySequence` es lo que convierte una sugerencia en una
   * reordenación puntual; sin él, el corredor solo queda registrado. En cualquier caso, qué
   * destinos sirve el envío lo decide el backend y no cambia aquí.
   */
  async function saveRoute(nextRouteId: string | null) {
    if (!detail) return;
    setSavingRoute(true);
    try {
      applyDetail(await updateTripRoute(companyId, tripId, {
        routeId: nextRouteId,
        applySequence: nextRouteId !== null && applyRouteSequence,
        version: detail.trip.version,
      }));
      notifySuccess(nextRouteId === null ? t("Ruta quitada") : t("Ruta guardada"));
    } catch (error) {
      notifyError(t("No se pudo guardar la ruta"), describePlanningError(error as ApiError));
    } finally {
      setSavingRoute(false);
    }
  }

  async function cancelThisTrip() {
    if (!detail) return;
    const confirmed = await confirmDialog({
      title: t("¿Cancelar el viaje?"),
      text: t("El viaje {{number}} quedará cancelado y sus pedidos volverán al pool.", { number: detail.trip.tripNumber }),
      confirmLabel: t("Cancelar viaje"),
      dangerous: true,
    });
    if (!confirmed) return;

    try {
      applyDetail(await cancelTrip(companyId, tripId, { version: detail.trip.version }));
      notifySuccess(t("Viaje cancelado"));
    } catch (error) {
      notifyError(t("No se pudo cancelar el viaje"), describePlanningError(error as ApiError));
    }
  }

  const trip = detail?.trip;
  const stopsSorted = detail ? stopOrder.map((id) => detail.stops.find((s) => s.destinationId === id)) : [];
  // Si ninguna parada tiene estimación, el envío simplemente no la ha pedido nunca - decir "no se
  // pudo medir un tramo" en ese caso sería inventar una causa. El aviso sólo aparece cuando el
  // resto del viaje sí está estimado y esta parada se quedó fuera.
  const anyStopScheduled = (detail?.stops ?? []).some((stop) => stop.etaArrivalAt !== null);
  const [recomputingEta, setRecomputingEta] = useState(false);
  // Sin salida planificada no hay desde cuándo contar, y el backend lo rechaza diciéndolo. Ocultar
  // el botón evita ofrecer una acción que sólo puede fallar.
  const canRecomputeEta = Boolean(detail?.trip.plannedDepartureAt) && (detail?.stops.length ?? 0) > 0;

  async function recomputeEta() {
    if (!detail) return;
    setRecomputingEta(true);
    try {
      await recomputeTripEta(companyId, detail.trip.id);
      notifySuccess(t("ETA recalculada"));
      onChanged();
    } catch (error) {
      notifyError(t("No se pudo recalcular la ETA"), describeApiError(error as ApiError));
    } finally {
      setRecomputingEta(false);
    }
  }
  const stopOrderDirty = stopOrder.join("|") !== serverStopsKey;

  const openExceptions = detail?.exceptions.filter((exception) => exception.status === "OPEN") ?? [];
  const deliveryOf = (orderId: string) => detail?.deliveries.find((delivery) => delivery.orderId === orderId) ?? null;
  const departureDelay = trip?.actualDepartureAt && trip.plannedDepartureAt
    ? Math.round((new Date(trip.actualDepartureAt).getTime() - new Date(trip.plannedDepartureAt).getTime()) / 60000)
    : null;
  const completedStops = (detail?.stops ?? []).filter((stop) => STOP_DONE.has(stop.executionStatus)).length;

  const sectionTitle = (title: string, extra?: ReactNode, actions?: ReactNode) => (
    <Box sx={{ display: "flex", alignItems: "center", gap: 1, minHeight: 32, mb: 1.25, flexWrap: "wrap" }}>
      <Typography component="h3" sx={{ fontSize: T.micro, fontWeight: 800, letterSpacing: ".08em", textTransform: "uppercase", color: "text.secondary" }}>
        {title}
      </Typography>
      {extra}
      <Box sx={{ flex: 1 }} />
      {actions}
    </Box>
  );
  const countBadge = (value: ReactNode, tone: "neutral" | "primary" = "neutral") => (
    <Box sx={{
      px: 0.75, borderRadius: 10, fontSize: 11.5, fontWeight: 700, lineHeight: "18px",
      bgcolor: "action.hover", color: tone === "primary" ? "primary.main" : "text.secondary",
    }}>{value}</Box>
  );

  return (
    <>
      <FormDrawer
        open
        loading={tripQuery.isPending}
        icon={<LocalShippingRounded />}
        title={trip ? t("Viaje {{number}}", { number: trip.tripNumber }) : t("Viaje")}
        subtitle={trip
          ? [`${t("Envío")} ${trip.shipmentNumber}`, `${t("Plan")} ${trip.planNumber}`, fmtDate(trip.planningDate)].join(" · ")
          : undefined}
        size="xl"
        onClose={onClose}
        footer={
          <>
            {editable && (
              <Button color="error" startIcon={<CancelRounded />} onClick={() => void cancelThisTrip()}
                sx={{ color: "error.main", "&:hover": { bgcolor: "action.hover" } }}>
                {t("Cancelar viaje")}
              </Button>
            )}
            <Box sx={{ flex: 1, display: { xs: "none", sm: "block" } }}>
              {detail && !editable && (
                <Typography variant="caption" color="text.secondary">
                  {t("Solo un viaje en borrador se edita desde planificación.")}
                </Typography>
              )}
            </Box>
            <Button variant="outlined" color="inherit" onClick={onClose} sx={{ borderColor: "divider" }}>{t("Cerrar")}</Button>
          </>
        }
      >
        {tripQuery.isError && (
          <Alert severity="error">{describePlanningError(tripQuery.error as ApiError)}</Alert>
        )}

        {detail && trip && (
          <Box sx={{ display: "grid", gap: 2.5 }}>
            <Box sx={{ display: "flex", gap: 1, flexWrap: "wrap", alignItems: "center" }}>
              <StatusChip label={enumLabel("tripStatus", trip.status)} tone={TRIP_STATUS_TONE[trip.status]} variant="solid" />
              {!trip.capacity.withinCapacity && (
                <StatusChip label={t("Capacidad excedida")} tone="overdue" />
              )}
              <Box sx={{ flex: 1 }} />
              {editable && (
                <>
                  <Button size="small" variant="outlined" color="inherit" startIcon={<LocalShippingRounded />}
                    onClick={() => setShowVehicleDrawer(true)} sx={{ borderColor: "divider" }}>
                    {t("Cambiar vehículo")}
                  </Button>
                  <Button size="small" variant="outlined" color="inherit" startIcon={<BadgeRounded />}
                    onClick={() => setShowDriverDrawer(true)} sx={{ borderColor: "divider" }}>
                    {t("Cambiar conductor")}
                  </Button>
                </>
              )}
            </Box>

            {openExceptions.length > 0 && (
              <Box role="alert" sx={(th) => ({
                display: "flex", gap: 1.25, alignItems: "flex-start", p: 1.5, borderRadius: `${R.md}px`,
                bgcolor: alpha(th.palette.error.main, th.palette.mode === "dark" ? 0.16 : 0.08),
              })}>
                <ReportRounded sx={{ color: "error.main", fontSize: 20, mt: "1px" }} />
                <Box sx={{ minWidth: 0 }}>
                  <Typography variant="body2" sx={{ fontWeight: 700, color: "error.main" }}>
                    {tp(openExceptions.length, "{{count}} incidencia abierta", "{{count}} incidencias abiertas")}
                    {" · "}{enumLabel("tripExceptionType", openExceptions[0].exceptionType)}
                  </Typography>
                  <Typography variant="caption" sx={{ color: "error.main" }}>
                    {[openExceptions[0].stopDestinationName, openExceptions[0].notes].filter(Boolean).join(" · ")}
                  </Typography>
                </Box>
              </Box>
            )}

            {/* Los cuatro datos que se preguntan primero: con qué, quién, cuándo y por dónde. */}
            <Box sx={{
              display: "grid", border: "1px solid", borderColor: "divider", borderRadius: `${R.md}px`, overflow: "hidden",
              gridTemplateColumns: { xs: "1fr", sm: "repeat(2, minmax(0, 1fr))", lg: "repeat(4, minmax(0, 1fr))" },
            }}>
              <Fact icon={<LocalShippingOutlined />} label={t("Vehículo")}
                value={trip.vehicleCode ? `${trip.vehicleCode} · ${trip.vehicleLicensePlate}` : t("Sin vehículo")}
                sub={[trip.carrierName ?? t("Flota propia"), trip.vehicleTypeCode].filter(Boolean).join(" · ")}
                tone={trip.vehicleCode ? undefined : "warning"}
                extra={trip.awaitsCarrierVehicle ? (
                  // V42: sólo cuando los dos discrepan. Mostrarlo siempre repetiría el transportista
                  // en la mitad de las pantallas y escondería el caso que importa entre el ruido.
                  <Chip size="small" color="warning" variant="outlined" sx={{ mt: 0.5, height: 20, fontSize: 10.5 }}
                    label={`${t("Aceptado por")} ${trip.acceptedCarrierName ?? "—"} · ${t("Falta vehículo del transportista")}`} />
                ) : undefined}
              />
              <Fact icon={<BadgeOutlined />} label={t("Conductor")}
                value={trip.driverName ?? t("Sin conductor asignado")}
                tone={trip.driverName ? undefined : "warning"}
                // El estado de la licencia lo juzga el servidor contra la fecha del *viaje*, no
                // contra hoy: el tablero está indexado por el día en que el viaje sale.
                sub={trip.driverLicenseStatus ? `${t("Licencia")} ${enumLabel("driverLicenseStatus", trip.driverLicenseStatus).toLowerCase()}` : undefined}
                subTone={trip.driverLicenseStatus === "EXPIRED" ? "error" : trip.driverLicenseStatus === "EXPIRING_SOON" ? "warning" : undefined}
              />
              <Fact icon={<ScheduleRounded />} label={t("Salida")}
                value={trip.actualDepartureAt
                  ? `${fmtTime(trip.actualDepartureAt)} ${t("real")}`
                  : trip.plannedDepartureAt ? fmtTime(trip.plannedDepartureAt) : t("Sin definir")}
                sub={trip.actualDepartureAt && trip.plannedDepartureAt
                  ? `${t("Planificada")} ${fmtTime(trip.plannedDepartureAt)}${departureDelay ? ` · ${departureDelay > 0 ? "+" : ""}${departureDelay} min` : ""}`
                  : trip.plannedDepartureAt ? t("Planificada") : undefined}
                subTone={departureDelay !== null && departureDelay > 5 ? "warning" : undefined}
              />
              <Fact icon={<AltRouteRounded />} label={t("Ruta")}
                value={trip.routeCode ?? t("Sin ruta")}
                sub={trip.routeName ?? (trip.originName ?? trip.originCode ?? undefined)}
              />
            </Box>

            <Box>
              {sectionTitle(t("Capacidad"))}
              <Box sx={{ display: "grid", columnGap: 3, gridTemplateColumns: { xs: "1fr", md: "repeat(3, minmax(0, 1fr))" } }}>
                <CapacityBar kind="weight" dimension={trip.capacity.weight} />
                <CapacityBar kind="volume" dimension={trip.capacity.volume} />
                <CapacityBar kind="pallets" dimension={trip.capacity.pallets} />
              </Box>
            </Box>

            <Box sx={{
              display: "grid", gap: 3, alignItems: "start",
              gridTemplateColumns: { xs: "1fr", md: "minmax(0, 1fr) 320px" },
            }}>
              {/* Paradas: la secuencia como línea de tiempo desde el origen. */}
              <Box sx={{ minWidth: 0 }}>
                {editable && (
                  <Box sx={{ mb: 2.5, p: 1.5, border: "1px solid", borderColor: "divider", borderRadius: `${R.md}px` }}>
                    {sectionTitle(t("Ruta sugerida"))}
                    <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5, mt: -0.5 }}>
                      {t("La ruta es una sugerencia: no cambia qué destinos sirve el envío, solo puede reordenar los que ya tiene.")}
                    </Typography>
                    <Box sx={{ display: "flex", gap: 1.5, alignItems: "center", flexWrap: "wrap" }}>
                      <TextField
                        select size="small" label={t("Ruta")} value={routeId}
                        onChange={(e) => setRouteId(e.target.value)}
                        sx={{ minWidth: 200, flex: 1 }}
                      >
                        <MenuItem value="">{t("Sin ruta")}</MenuItem>
                        {(routesQuery.data?.content ?? []).map((route) => (
                          <MenuItem key={route.id} value={route.id}>{route.code} · {route.name}</MenuItem>
                        ))}
                      </TextField>
                      <FormControlLabel
                        control={
                          <Checkbox
                            size="small" checked={applyRouteSequence}
                            onChange={(e) => setApplyRouteSequence(e.target.checked)}
                            disabled={routeId === ""}
                          />
                        }
                        label={<Typography variant="body2">{t("Aplicar su secuencia")}</Typography>}
                      />
                      <Button
                        size="small" variant="outlined" startIcon={<AltRouteRounded />}
                        disabled={savingRoute}
                        onClick={() => void saveRoute(routeId === "" ? null : routeId)}
                      >
                        {t("Guardar ruta")}
                      </Button>
                    </Box>
                  </Box>
                )}

                {sectionTitle(
                  t("Paradas"),
                  detail.stops.length > 0 ? countBadge(`${completedStops} ${t("de")} ${detail.stops.length}`, "primary") : undefined,
                  <Box sx={{ display: "flex", gap: 1, alignItems: "center", flexWrap: "wrap" }}>
                    <ToggleButtonGroup
                      exclusive size="small" value={mobileStopTab}
                      onChange={(_e, value: "map" | "list" | null) => { if (value) setMobileStopTab(value); }}
                      aria-label={t("Vista de paradas")}
                      sx={{ "& .MuiToggleButton-root": { py: 0.25, px: 1.25, textTransform: "none", fontWeight: 600 } }}
                    >
                      <ToggleButton value="list">{t("Lista")}</ToggleButton>
                      <ToggleButton value="map">{t("Mapa")}</ToggleButton>
                    </ToggleButtonGroup>
                    {/* V43. Explícito y no automático: recalcular necesita que alguien lo pida,
                        porque un proceso de fondo necesitaría un actor a quien atribuirlo (D4). */}
                    {canRecomputeEta && (
                      <Button
                        size="small" variant="outlined" color="inherit" startIcon={<ScheduleRounded />}
                        disabled={recomputingEta} onClick={() => void recomputeEta()} sx={{ borderColor: "divider" }}
                      >
                        {recomputingEta ? t("Calculando...") : t("Recalcular ETA")}
                      </Button>
                    )}
                    {editable && stopOrderDirty && (
                      <Button
                        size="small" variant="contained" startIcon={<SaveRounded />}
                        disabled={savingStops} onClick={() => void saveStopOrder()}
                      >
                        {t("Guardar el orden")}
                      </Button>
                    )}
                  </Box>,
                )}

                {mobileStopTab === "map" && (
                  <TripStopMap
                    origin={mapOrigin}
                    stops={mapStops}
                    selectedStopId={selectedStopId}
                    onSelectStop={setSelectedStopId}
                    height={isNarrow ? 260 : 340}
                  />
                )}

                {mobileStopTab === "list" && (
                  stopsSorted.length === 0 ? (
                    <Alert severity="info">{t("Este viaje todavía no tiene paradas.")}</Alert>
                  ) : (
                    <Box component="ol" sx={{ listStyle: "none", m: 0, p: 0 }}>
                      <TimelineRow
                        marker={<WarehouseOutlined sx={{ fontSize: 15 }} />}
                        markerTone="origin"
                        lineDone={STOP_DONE.has(stopsSorted[0]?.executionStatus ?? "PENDING") || Boolean(trip.actualDepartureAt)}
                        title={trip.originName ?? trip.originCode ?? t("Origen")}
                        sub={trip.actualDepartureAt
                          ? `${t("Salida")} ${fmtTime(trip.actualDepartureAt)}${trip.plannedDepartureAt ? ` · ${t("planificada")} ${fmtTime(trip.plannedDepartureAt)}` : ""}`
                          : trip.plannedDepartureAt ? `${t("Salida planificada")} ${fmtTime(trip.plannedDepartureAt)}` : t("Salida sin definir")}
                      />
                      {stopsSorted.map((stop, index) => {
                        if (!stop) return null;
                        const window = formatServiceWindow(stop.serviceWindowStart, stop.serviceWindowEnd);
                        const selected = stop.destinationId === selectedStopId;
                        const next = stopsSorted[index + 1];
                        const done = STOP_DONE.has(stop.executionStatus);
                        return (
                          <TimelineRow
                            key={stop.id}
                            selected={selected}
                            onClick={() => setSelectedStopId(stop.destinationId)}
                            marker={stop.executionStatus === "COMPLETED" ? <CheckRounded sx={{ fontSize: 16 }} />
                              : stop.executionStatus === "FAILED" ? <CloseRounded sx={{ fontSize: 16 }} />
                                : stop.executionStatus === "SKIPPED" ? <RedoRounded sx={{ fontSize: 15 }} />
                                  : index + 1}
                            markerTone={stop.executionStatus === "COMPLETED" ? "done"
                              : stop.executionStatus === "FAILED" ? "failed"
                                : stop.executionStatus === "SKIPPED" ? "skipped"
                                  : stop.executionStatus === "PENDING" ? "pending" : "active"}
                            last={index === stopsSorted.length - 1}
                            lineDone={done && Boolean(next) && STOP_DONE.has(next?.executionStatus ?? "PENDING")}
                            title={stop.destinationName ?? stop.destinationCode ?? stop.destinationId}
                            sub={[stop.address, window ? `${t("ventana")} ${window}` : null].filter(Boolean).join(" · ") || stop.destinationCode || undefined}
                            meta={stop.actualArrivalAt ? (
                              <Typography variant="caption" sx={{ display: "block", fontWeight: 600 }}>
                                {t("Llegada")} {fmtTime(stop.actualArrivalAt)}
                                {stop.dwellMinutes !== null && ` · ${stop.dwellMinutes} min ${t("en el punto")}`}
                              </Typography>
                            ) : stop.etaArrivalAt ? (
                              /* V43: el hueco se muestra, no se rellena. Una hora plausible pero
                                 equivocada se ve igual que una correcta, y nada diría cuál es cuál. */
                              <Typography variant="caption" sx={{ display: "block", fontWeight: 600, color: "info.main" }}>
                                {t("Llegada estimada: {{time}}", { time: fmtDateTime(stop.etaArrivalAt) })}
                                {stop.etaSource === "FALLBACK" && ` · ${t("línea recta")}`}
                              </Typography>
                            ) : anyStopScheduled ? (
                              <Typography variant="caption" color="text.secondary" sx={{ display: "block" }}>
                                {t("Sin estimación: un tramo del camino no se pudo medir.")}
                              </Typography>
                            ) : null}
                            chips={
                              <>
                                {stop.openExceptionCount > 0 && (
                                  <Chip size="small" color="error" label={tp(stop.openExceptionCount, "{{count}} incidencia", "{{count}} incidencias")} sx={{ height: 22 }} />
                                )}
                                {stop.etaMissesWindow && (
                                  <Chip size="small" color="warning" variant="outlined" label={t("Fuera de ventana")} sx={{ height: 22 }} />
                                )}
                                <StatusChip
                                  label={enumLabel("stopExecutionStatus", stop.executionStatus)}
                                  tone={STOP_EXECUTION_TONE[stop.executionStatus]}
                                />
                                {editable && (
                                  <Box sx={{ display: "flex", gap: 0.25 }}>
                                    <Tooltip title={t("Subir")}>
                                      <span>
                                        <IconButton
                                          size="small" disabled={index === 0}
                                          onClick={(e) => { e.stopPropagation(); setStopOrder(moveItem(stopOrder, index, index - 1)); }}
                                          aria-label={t("Subir la parada {{position}}", { position: index + 1 })}
                                        >
                                          <ArrowUpwardRounded fontSize="small" />
                                        </IconButton>
                                      </span>
                                    </Tooltip>
                                    <Tooltip title={t("Bajar")}>
                                      <span>
                                        <IconButton
                                          size="small" disabled={index === stopsSorted.length - 1}
                                          onClick={(e) => { e.stopPropagation(); setStopOrder(moveItem(stopOrder, index, index + 1)); }}
                                          aria-label={t("Bajar la parada {{position}}", { position: index + 1 })}
                                        >
                                          <ArrowDownwardRounded fontSize="small" />
                                        </IconButton>
                                      </span>
                                    </Tooltip>
                                  </Box>
                                )}
                              </>
                            }
                          />
                        );
                      })}
                    </Box>
                  )
                )}

                {stopOrderDirty && (
                  <Alert severity="warning" sx={{ mt: 1.5 }}>
                    {t("El orden de paradas tiene cambios sin guardar.")}
                  </Alert>
                )}
              </Box>

              {/* Pedidos del viaje: lo comprometido en este envío y, si ya salió, cómo se entregó. */}
              <Box sx={{ minWidth: 0 }}>
                {sectionTitle(t("Pedidos del viaje"), countBadge(detail.assignments.length))}
                {detail.assignments.length === 0 ? (
                  <Alert severity="info">{t("Este viaje todavía no lleva pedidos.")}</Alert>
                ) : (
                  <Box sx={{ border: "1px solid", borderColor: "divider", borderRadius: `${R.md}px`, overflow: "hidden" }}>
                    {detail.assignments.map((assignment, i) => {
                      const delivery = deliveryOf(assignment.orderId);
                      return (
                        <Box key={assignment.assignmentId} sx={{
                          px: 1.5, py: 1.25, display: "grid", gap: 0.25,
                          borderTop: i === 0 ? 0 : "1px solid", borderColor: "divider",
                        }}>
                          <Box sx={{ display: "flex", alignItems: "center", gap: 1 }}>
                            <Typography variant="body2" noWrap sx={{ fontFamily: MONO, fontWeight: 700, flex: 1, minWidth: 0 }}>
                              {assignment.orderNumber}
                            </Typography>
                            {delivery ? (
                              <StatusChip label={enumLabel("deliveryResult", delivery.result)} tone={DELIVERY_TONE[delivery.result]} />
                            ) : trip.status === "IN_TRANSIT" || trip.status === "COMPLETED" ? (
                              <StatusChip label={t("Pendiente")} tone="neutral" />
                            ) : null}
                          </Box>
                          <Typography variant="caption" color="text.secondary" noWrap>
                            {assignment.destinationName ?? assignment.destinationCode ?? "-"}
                            {assignment.customerName && ` · ${assignment.customerName}`}
                          </Typography>
                          {/* Las cantidades vienen de la fila de asignación, no de la cabecera del
                              pedido: es lo que se comprometió en este viaje. */}
                          <Typography variant="caption" sx={{ fontWeight: 600, fontVariantNumeric: "tabular-nums" }}>
                            {fmtWeightKg(assignment.assignedWeightKg)} · {fmtVolumeM3(assignment.assignedVolumeM3)} · {fmtDecimal(assignment.assignedPallets)} {t("pallets")}
                          </Typography>
                          {editable && (
                            <Box sx={{ display: "flex", gap: 0.75, alignItems: "center", mt: 0.75 }}>
                              {targetTrips.length > 0 && (
                                <>
                                  <TextField
                                    select size="small" sx={{ flex: 1, minWidth: 0 }}
                                    value={moveTargets[assignment.orderId] ?? targetTrips[0].id}
                                    onChange={(e) => setMoveTargets({ ...moveTargets, [assignment.orderId]: e.target.value })}
                                    aria-label={t("Viaje de destino de {{number}}", { number: assignment.orderNumber })}
                                  >
                                    {targetTrips.map((target) => (
                                      <MenuItem key={target.id} value={target.id}>
                                        {t("Viaje {{number}}", { number: target.tripNumber })}
                                      </MenuItem>
                                    ))}
                                  </TextField>
                                  <Tooltip title={t("Mover")}>
                                    <span>
                                      <IconButton
                                        size="small"
                                        disabled={busyOrderId === assignment.orderId}
                                        onClick={() => void moveOrder(assignment.orderId, assignment.orderNumber)}
                                        aria-label={t("Mover {{number}}", { number: assignment.orderNumber })}
                                      >
                                        <SwapHorizRounded fontSize="small" />
                                      </IconButton>
                                    </span>
                                  </Tooltip>
                                </>
                              )}
                              <Box sx={{ flex: targetTrips.length > 0 ? 0 : 1 }} />
                              <Tooltip title={t("Quitar")}>
                                <span>
                                  <IconButton
                                    size="small" sx={{ color: "error.main" }}
                                    disabled={busyOrderId === assignment.orderId}
                                    onClick={() => void removeOrder(assignment.orderId, assignment.orderNumber)}
                                    aria-label={t("Quitar {{number}}", { number: assignment.orderNumber })}
                                  >
                                    <DeleteOutlineRounded fontSize="small" />
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
              </Box>
            </Box>

            {detail.exceptions.length > 0 && (
              <Box>
                {sectionTitle(t("Incidencias"), countBadge(detail.exceptions.length))}
                <Box sx={{ display: "grid", gap: 1 }}>
                  {detail.exceptions.map((exception) => (
                    <Paper key={exception.id} variant="outlined" sx={{ p: 1.5 }}>
                      <Box sx={{ display: "flex", alignItems: "center", gap: 1, mb: 0.5 }}>
                        <Typography variant="body2" sx={{ fontWeight: 700 }}>
                          {enumLabel("tripExceptionType", exception.exceptionType)}
                        </Typography>
                        <StatusChip
                          label={enumLabel("tripExceptionStatus", exception.status)}
                          tone={exception.status === "OPEN" ? "overdue" : "done"}
                        />
                      </Box>
                      <Typography variant="caption" color="text.secondary">
                        {[exception.stopDestinationName, exception.notes].filter(Boolean).join(" · ")}
                      </Typography>
                    </Paper>
                  ))}
                </Box>
              </Box>
            )}
          </Box>
        )}
      </FormDrawer>

      {showVehicleDrawer && detail && (
        <TripVehicleDrawer
          companyId={companyId}
          trip={detail.trip}
          onClose={() => setShowVehicleDrawer(false)}
          onSaved={(next) => { applyDetail(next); setShowVehicleDrawer(false); }}
        />
      )}

      {showDriverDrawer && detail && (
        <TripDriverDrawer
          companyId={companyId}
          trip={detail.trip}
          onClose={() => setShowDriverDrawer(false)}
          onSaved={(next) => { applyDetail(next); setShowDriverDrawer(false); }}
        />
      )}
    </>
  );
}

const MONO = "ui-monospace, SFMono-Regular, Menlo, Consolas, monospace";

/** Las paradas que ya no esperan al vehículo: atendidas, omitidas o no atendidas. */
const STOP_DONE = new Set<StopExecutionStatus>(["COMPLETED", "SKIPPED", "FAILED"]);

const DELIVERY_TONE: Record<DeliveryResult, StatusTone> = {
  DELIVERED: "done", PARTIAL: "inProgress", REJECTED: "overdue", FAILED: "overdue", NOT_ATTEMPTED: "neutral",
};

/** Un dato clave del viaje: icono, etiqueta, valor y una línea de apoyo. */
function Fact({ icon, label, value, sub, tone, subTone, extra }: {
  icon: ReactNode; label: string; value: ReactNode; sub?: string;
  tone?: "warning"; subTone?: "warning" | "error"; extra?: ReactNode;
}) {
  return (
    <Box sx={{
      display: "flex", gap: 1.25, p: 1.5, minWidth: 0,
      borderRight: "1px solid", borderBottom: "1px solid", borderColor: "divider", mr: "-1px", mb: "-1px",
    }}>
      <Box aria-hidden sx={{
        width: 30, height: 30, flexShrink: 0, borderRadius: `${R.sm}px`, display: "grid", placeItems: "center",
        bgcolor: "action.hover", color: tone ? `${tone}.main` : "text.secondary", "& svg": { fontSize: 17 },
      }}>{icon}</Box>
      <Box sx={{ minWidth: 0 }}>
        <Typography sx={{ fontSize: T.micro - 0.5, fontWeight: 700, letterSpacing: ".06em", textTransform: "uppercase", color: "text.secondary" }}>
          {label}
        </Typography>
        <Typography variant="body2" noWrap sx={{ fontWeight: 700, color: tone ? `${tone}.main` : "text.primary" }}>{value}</Typography>
        {sub && (
          <Typography noWrap sx={{ fontSize: T.micro, color: subTone ? `${subTone}.main` : "text.secondary", fontWeight: subTone ? 700 : 400 }}>
            {sub}
          </Typography>
        )}
        {extra}
      </Box>
    </Box>
  );
}

type MarkerTone = "origin" | "done" | "failed" | "skipped" | "active" | "pending";

/** Una fila de la línea de tiempo de paradas: el marcador sobre el raíl y la tarjeta al lado. */
function TimelineRow({ marker, markerTone, title, sub, meta, chips, last, lineDone, selected, onClick }: {
  marker: ReactNode; markerTone: MarkerTone; title: string; sub?: string; meta?: ReactNode; chips?: ReactNode;
  last?: boolean; lineDone?: boolean; selected?: boolean; onClick?: () => void;
}) {
  const markerSx = {
    origin: { bgcolor: "action.hover", color: "text.secondary" },
    done: { bgcolor: "primary.main", color: "primary.contrastText" },
    failed: { bgcolor: "error.main", color: "error.contrastText" },
    skipped: { bgcolor: "action.disabledBackground", color: "text.secondary" },
    active: { bgcolor: "warning.main", color: "warning.contrastText" },
    pending: { bgcolor: "background.paper", color: "primary.main", border: "2px solid", borderColor: "primary.main" },
  }[markerTone];
  return (
    <Box component="li" sx={{ display: "flex", gap: 1.5 }}>
      <Box sx={{ display: "flex", flexDirection: "column", alignItems: "center", width: 28, flexShrink: 0 }}>
        <Box sx={{
          width: 28, height: 28, borderRadius: "50%", display: "grid", placeItems: "center",
          fontWeight: 800, fontSize: 12.5, flexShrink: 0, ...markerSx,
        }}>{marker}</Box>
        {!last && <Box sx={{ flex: 1, width: 2, minHeight: 12, bgcolor: lineDone ? "primary.main" : "divider" }} />}
      </Box>
      <Box
        onClick={onClick}
        sx={{
          flex: 1, minWidth: 0, mb: last ? 0 : 1.25, px: 1.25, py: 0.75, borderRadius: `${R.sm}px`,
          cursor: onClick ? "pointer" : "default",
          bgcolor: selected ? "action.selected" : "transparent",
          "&:hover": onClick ? { bgcolor: selected ? "action.selected" : "action.hover" } : undefined,
        }}
      >
        <Box sx={{ display: "flex", alignItems: "center", gap: 1, flexWrap: "wrap" }}>
          <Typography variant="body2" sx={{ fontWeight: 700, flex: 1, minWidth: 140 }}>{title}</Typography>
          {chips}
        </Box>
        {sub && <Typography variant="caption" color="text.secondary" sx={{ display: "block" }}>{sub}</Typography>}
        {meta}
      </Box>
    </Box>
  );
}
