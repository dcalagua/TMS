import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useMemo, useState } from "react";
import {
  Alert, Box, Button, Checkbox, Chip, FormControlLabel, IconButton, MenuItem,
  Paper, Tab, Tabs, TextField, Tooltip, Typography, useMediaQuery, useTheme,
} from "@mui/material";
import {
  LocalShippingRounded, ArrowUpwardRounded, ArrowDownwardRounded, DeleteRounded,
  SwapHorizRounded, SaveRounded, BadgeRounded, AltRouteRounded, CancelRounded,
} from "@mui/icons-material";
import type { ApiError } from "../../shared/api/httpClient";
import { fetchRoutes } from "../../shared/api/routesApi";
import {
  cancelTrip, fetchTrip, moveOrderToTrip, removeOrderFromTrip, reorderTripStops, updateTripRoute,
  type TripDetailView, type TripView,
} from "../../shared/api/planningApi";
import { describePlanningError } from "../../shared/api/problemMessages";
import { TripStopMap, type TripStopMapOrigin, type TripStopMapStop } from "../../shared/maps/TripStopMap";
import {
  CapacityBar, DetailGrid, DetailItem, FormDrawer, SectionHeader, StatusChip,
} from "../../shared/ui/components";
import { STOP_EXECUTION_TONE, TRIP_STATUS_TONE } from "../../shared/ui/statusTones";
import { confirmDialog, notifyError, notifySuccess } from "../../lib/ui";
import { enumLabel } from "../../lib/enums";
import { t } from "../../lib/i18n";
import { fmtDate, fmtDateTime, fmtDecimal, fmtVolumeM3, fmtWeightKg } from "../../lib/locale";
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
  const stopOrderDirty = stopOrder.join("|") !== serverStopsKey;

  return (
    <>
      <FormDrawer
        open
        loading={tripQuery.isPending}
        icon={<LocalShippingRounded />}
        title={trip ? t("Viaje {{number}}", { number: trip.tripNumber }) : t("Viaje")}
        subtitle={trip ? `${t("Envío")} ${trip.shipmentNumber}` : undefined}
        size="xl"
        onClose={onClose}
        footer={
          <>
            <Button onClick={onClose}>{t("Cerrar")}</Button>
            {editable && (
              <Button color="error" startIcon={<CancelRounded />} onClick={() => void cancelThisTrip()}>
                {t("Cancelar viaje")}
              </Button>
            )}
          </>
        }
      >
        {tripQuery.isError && (
          <Alert severity="error">{describePlanningError(tripQuery.error as ApiError)}</Alert>
        )}

        {detail && trip && (
          <>
            <Box sx={{ display: "flex", gap: 1, flexWrap: "wrap", mb: 2 }}>
              <StatusChip label={enumLabel("tripStatus", trip.status)} tone={TRIP_STATUS_TONE[trip.status]} variant="solid" />
              {!trip.capacity.withinCapacity && (
                <StatusChip label={t("Capacidad excedida")} tone="overdue" />
              )}
            </Box>

            <SectionHeader
              title={t("Envío")}
              actions={editable && (
                <Box sx={{ display: "flex", gap: 1 }}>
                  <Button size="small" startIcon={<LocalShippingRounded />} onClick={() => setShowVehicleDrawer(true)}>
                    {t("Vehículo")}
                  </Button>
                  <Button size="small" startIcon={<BadgeRounded />} onClick={() => setShowDriverDrawer(true)}>
                    {t("Conductor")}
                  </Button>
                </Box>
              )}
            />
            <DetailGrid columns={3}>
              <DetailItem label={t("Plan")} value={trip.planNumber} />
              <DetailItem label={t("Fecha de planificación")} value={fmtDate(trip.planningDate)} />
              <DetailItem label={t("Origen")} value={trip.originName ?? trip.originCode} />
              <DetailItem label={t("Vehículo")} value={trip.vehicleCode ? `${trip.vehicleCode} · ${trip.vehicleLicensePlate}` : null} />
              <DetailItem label={t("Transportista")} value={trip.carrierName} />
              <DetailItem
                label={t("Conductor")}
                value={trip.driverName ? (
                  <Box sx={{ display: "flex", alignItems: "center", gap: 0.75, flexWrap: "wrap" }}>
                    {trip.driverName}
                    {/* El estado de la licencia lo juzga el servidor contra la fecha del *viaje*,
                        no contra hoy: el tablero está indexado por el día en que el viaje sale. */}
                    {trip.driverLicenseStatus && trip.driverLicenseStatus !== "VALID" && (
                      <Chip
                        size="small"
                        color={trip.driverLicenseStatus === "EXPIRED" ? "error" : "warning"}
                        label={enumLabel("driverLicenseStatus", trip.driverLicenseStatus)}
                        sx={{ height: 20, fontSize: 10.5 }}
                      />
                    )}
                  </Box>
                ) : null}
              />
              <DetailItem label={t("Salida planificada")} value={trip.plannedDepartureAt ? fmtDateTime(trip.plannedDepartureAt) : null} />
              <DetailItem label={t("Salida real")} value={trip.actualDepartureAt ? fmtDateTime(trip.actualDepartureAt) : null} />
              <DetailItem label={t("Ruta")} value={trip.routeName ?? trip.routeCode} />
            </DetailGrid>

            <Box sx={{ mt: 3 }}>
              <SectionHeader title={t("Capacidad")} />
              <CapacityBar kind="weight" dimension={trip.capacity.weight} />
              <CapacityBar kind="volume" dimension={trip.capacity.volume} />
              <CapacityBar kind="pallets" dimension={trip.capacity.pallets} />
            </Box>

            <Box sx={{ mt: 3 }}>
              <SectionHeader title={t("Pedidos asignados")} />
              {detail.assignments.length === 0 ? (
                <Alert severity="info">{t("Este viaje todavía no lleva pedidos.")}</Alert>
              ) : (
                <Box sx={{ display: "grid", gap: 1 }}>
                  {detail.assignments.map((assignment) => (
                    <Paper key={assignment.assignmentId} variant="outlined" sx={{ p: 1.5 }}>
                      <Box sx={{ display: "flex", alignItems: "flex-start", gap: 1.5, flexWrap: "wrap" }}>
                        <Box sx={{ flex: 1, minWidth: 180 }}>
                          <Typography variant="body2" sx={{ fontWeight: 800 }}>{assignment.orderNumber}</Typography>
                          <Typography variant="caption" color="text.secondary" sx={{ display: "block" }}>
                            {assignment.destinationName ?? assignment.destinationCode ?? "-"}
                            {assignment.customerName && ` · ${assignment.customerName}`}
                          </Typography>
                          {/* Las cantidades vienen de la fila de asignación, no de la cabecera del
                              pedido: es lo que se comprometió en este viaje. */}
                          <Typography variant="caption" color="text.secondary" sx={{ display: "block", fontVariantNumeric: "tabular-nums" }}>
                            {fmtWeightKg(assignment.assignedWeightKg)} · {fmtVolumeM3(assignment.assignedVolumeM3)} · {fmtDecimal(assignment.assignedPallets)} {t("pallets")}
                          </Typography>
                        </Box>
                        {editable && (
                          <Box sx={{ display: "flex", gap: 0.75, alignItems: "center", flexWrap: "wrap" }}>
                            {targetTrips.length > 0 && (
                              <>
                                <TextField
                                  select size="small" sx={{ minWidth: 150 }}
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
                            <Tooltip title={t("Quitar")}>
                              <span>
                                <IconButton
                                  size="small" sx={{ color: "error.main" }}
                                  disabled={busyOrderId === assignment.orderId}
                                  onClick={() => void removeOrder(assignment.orderId, assignment.orderNumber)}
                                  aria-label={t("Quitar {{number}}", { number: assignment.orderNumber })}
                                >
                                  <DeleteRounded fontSize="small" />
                                </IconButton>
                              </span>
                            </Tooltip>
                          </Box>
                        )}
                      </Box>
                    </Paper>
                  ))}
                </Box>
              )}
            </Box>

            {editable && (
              <Box sx={{ mt: 3 }}>
                <SectionHeader title={t("Ruta sugerida")} />
                <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>
                  {t("La ruta es una sugerencia: no cambia qué destinos sirve el envío, solo puede reordenar los que ya tiene.")}
                </Typography>
                <Box sx={{ display: "flex", gap: 1.5, alignItems: "flex-start", flexWrap: "wrap" }}>
                  <TextField
                    select size="small" label={t("Ruta")} value={routeId}
                    onChange={(e) => setRouteId(e.target.value)}
                    sx={{ minWidth: 230, flex: 1 }}
                  >
                    <MenuItem value="">{t("Sin ruta")}</MenuItem>
                    {(routesQuery.data?.content ?? []).map((route) => (
                      <MenuItem key={route.id} value={route.id}>{route.code} · {route.name}</MenuItem>
                    ))}
                  </TextField>
                  <FormControlLabel
                    sx={{ mt: 0.5 }}
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

            <Box sx={{ mt: 3 }}>
              <SectionHeader
                title={t("Paradas")}
                actions={editable && stopOrderDirty && (
                  <Button
                    size="small" variant="contained" startIcon={<SaveRounded />}
                    disabled={savingStops}
                    onClick={() => void saveStopOrder()}
                  >
                    {t("Guardar el orden")}
                  </Button>
                )}
              />

              {isNarrow && (
                <Tabs
                  value={mobileStopTab}
                  onChange={(_e, value: "map" | "list") => setMobileStopTab(value)}
                  variant="fullWidth"
                  sx={{ mb: 2, borderBottom: "1px solid", borderColor: "divider" }}
                >
                  <Tab value="list" label={t("Lista")} />
                  <Tab value="map" label={t("Mapa")} />
                </Tabs>
              )}

              {(!isNarrow || mobileStopTab === "map") && (
                <TripStopMap
                  origin={mapOrigin}
                  stops={mapStops}
                  selectedStopId={selectedStopId}
                  onSelectStop={setSelectedStopId}
                  height={260}
                />
              )}

              {(!isNarrow || mobileStopTab === "list") && (
                stopsSorted.length === 0 ? (
                  <Alert severity="info">{t("Este viaje todavía no tiene paradas.")}</Alert>
                ) : (
                  <Box sx={{ display: "grid", gap: 1 }}>
                    {stopsSorted.map((stop, index) => {
                      if (!stop) return null;
                      const window = formatServiceWindow(stop.serviceWindowStart, stop.serviceWindowEnd);
                      const selected = stop.destinationId === selectedStopId;
                      return (
                        <Paper
                          key={stop.id}
                          variant="outlined"
                          onClick={() => setSelectedStopId(stop.destinationId)}
                          sx={{
                            p: 1.5, display: "flex", alignItems: "center", gap: 1.5, flexWrap: "wrap",
                            cursor: "pointer",
                            borderColor: selected ? "primary.main" : "divider",
                            bgcolor: selected ? "action.hover" : "transparent",
                          }}
                        >
                          <Box sx={{
                            width: 28, height: 28, borderRadius: "50%", flexShrink: 0, display: "grid", placeItems: "center",
                            bgcolor: "primary.main", color: "primary.contrastText", fontWeight: 800, fontSize: 13,
                          }}>
                            {index + 1}
                          </Box>
                          <Box sx={{ flex: 1, minWidth: 160 }}>
                            <Typography variant="body2" sx={{ fontWeight: 700, lineHeight: 1.3 }}>
                              {stop.destinationName ?? stop.destinationCode ?? stop.destinationId}
                            </Typography>
                            <Typography variant="caption" color="text.secondary">
                              {[stop.address, window].filter(Boolean).join(" · ") || stop.destinationCode}
                            </Typography>
                          </Box>
                          <StatusChip
                            label={enumLabel("stopExecutionStatus", stop.executionStatus)}
                            tone={STOP_EXECUTION_TONE[stop.executionStatus]}
                          />
                          {stop.openExceptionCount > 0 && (
                            <Chip size="small" color="error" label={t("{{count}} incidencias", { count: stop.openExceptionCount })} />
                          )}
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
                        </Paper>
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

            {detail.exceptions.length > 0 && (
              <Box sx={{ mt: 3 }}>
                <SectionHeader title={t("Incidencias")} />
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
          </>
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
