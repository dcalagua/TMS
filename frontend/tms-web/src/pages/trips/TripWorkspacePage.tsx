import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useMemo, useState } from "react";
import { Link, useParams } from "react-router-dom";
import {
  Alert, Box, Button, Chip, Divider, IconButton, Paper, Tab, Tabs, TextField, Tooltip, Typography,
  useMediaQuery, useTheme,
} from "@mui/material";
import {
  ArrowBackRounded, MapRounded, FlagRounded, PlayArrowRounded, DoneAllRounded, CancelRounded,
  PlaceRounded, BuildRounded, CheckRounded, SkipNextRounded, ReportProblemRounded,
  InventoryRounded, AttachFileRounded, DownloadRounded, BadgeRounded, EditRounded,
} from "@mui/icons-material";
import type { ApiError } from "../../shared/api/httpClient";
import {
  arriveAtStop, cancelTrip, completeStop, completeTrip, dispatchTrip, failStop, fetchTrip,
  fetchTripEvents, markTripReady, recordDelivery, reportTripException, resolveTripException,
  skipStop, startStopService, uploadDeliveryEvidence, downloadDeliveryEvidence,
  type DeliveryEvidenceView, type OrderDeliveryView, type TripDetailView, type TripExceptionView,
  type TripStopView,
} from "../../shared/api/planningApi";
import { fetchTripTracking } from "../../shared/api/trackingApi";
import { describeApiError, describePlanningError } from "../../shared/api/problemMessages";
import { useCompany } from "../../shared/company/CompanyContext";
import {
  TripStopMap, type TripStopMapOrigin, type TripStopMapStop, type TripStopMapVehicle,
} from "../../shared/maps/TripStopMap";
import {
  AppCard, DetailGrid, DetailItem, ErrorState, LoadingState, PageHeader, StatusChip,
} from "../../shared/ui/components";
import { DELIVERY_RESULT_TONE, STOP_EXECUTION_TONE, TRIP_STATUS_TONE } from "../../shared/ui/statusTones";
import { ICON_TINTS } from "../../shared/ui/navConfig";
import { TripDriverDrawer } from "../planning/TripDriverDrawer";
import { confirmDialog, notifyError, notifySuccess, promptDialog } from "../../lib/ui";
import { enumLabel } from "../../lib/enums";
import { t } from "../../lib/i18n";
import { fmtDate, fmtDateTime, fmtMinutes, fmtTime } from "../../lib/locale";
import { DeliveryDrawer, type DeliveryValues } from "./DeliveryDrawer";
import { DeliveryEvidenceDrawer, type DeliveryEvidenceValues } from "./DeliveryEvidenceDrawer";
import { TripCostCard } from "./TripCostCard";
import { TripProblemDrawer, type TripProblemMode, type TripProblemValues } from "./TripProblemDrawer";
import { TripTenderCard } from "./TripTenderCard";
import { TripTimeline } from "./TripTimeline";
import { TripTrackingCard } from "./TripTrackingCard";

function formatServiceWindow(start: string | null, end: string | null): string | null {
  if (!start && !end) return null;
  return `${start?.slice(0, 5) ?? "--:--"} - ${end?.slice(0, 5) ?? "--:--"}`;
}

/** El valor de un `<input type="datetime-local">` como instante ISO, o `null` si está vacío. */
function toInstant(localValue: string): string | null {
  if (localValue.trim() === "") return null;
  const date = new Date(localValue);
  return Number.isNaN(date.getTime()) ? null : date.toISOString();
}

/** Las tres acciones de parada que no piden motivo. */
const STOP_ACTIONS = {
  ARRIVED: { send: arriveAtStop, toast: "Llegada registrada", label: "Registrar llegada", icon: <PlaceRounded /> },
  IN_SERVICE: { send: startStopService, toast: "Servicio iniciado", label: "Iniciar servicio", icon: <BuildRounded /> },
  COMPLETED: { send: completeStop, toast: "Parada completada", label: "Completar parada", icon: <CheckRounded /> },
} as const;

/**
 * El espacio de trabajo de un envío: dónde está, qué le va pasando y las acciones que lo mueven.
 *
 * Una ruta propia y no un drawer sobre la lista: un despachador se queda dentro de un viaje
 * durante minutos, y `/trips/{id}` es lo que se pega en un chat cuando alguien pregunta dónde
 * está un camión.
 *
 * Tres consultas y no una. El viaje es lo que se acciona; la línea de tiempo crece todo el día y
 * se lee más de lo que se toca; y el rastreo está detrás de otro permiso, puede consultar a un
 * proveedor externo y es la única lectura de esta pantalla que puede fallar legítimamente
 * mientras el resto funciona.
 */
export function TripWorkspacePage() {
  const theme = useTheme();
  const isNarrow = useMediaQuery(theme.breakpoints.down("lg"));
  const { tripId } = useParams<{ tripId: string }>();
  const { selected, hasPermission } = useCompany();
  const companyId = selected?.id ?? "";
  const canExecute = hasPermission("planning.trip:execute");
  const canCancel = canExecute || hasPermission("planning.trip:manage");
  /** El rastreo es otra autoridad, así que la tarjeta simplemente no está para un rol sin él: ni
   * gris, ni pedida y escondida. El endpoint aplica la misma regla; esto solo evita un 403 en
   * cada visita para roles que nunca van a tenerlo. */
  const canMonitor = hasPermission("monitoring.transport:read");
  /** Lo que cuesta un envío también es su propia autoridad, y por una razón más afilada que la del
   * rastreo: es información comercial, y una instalación puede querer que un despachador saque el
   * día sin ver cuánto vale. */
  const canReadCost = hasPermission("rates.trip_cost:read");
  const canManageCost = hasPermission("rates.trip_cost:manage");
  /** Ofertar es una tercera autoridad, y comercialmente sensible por lo mismo que el costeo: una
   * oferta lleva precio. Separada de `planning.trip:manage`, porque colocar una carga con un
   * transportista y armar el plan son trabajos distintos. */
  const canReadTenders = hasPermission("planning.tender:read");
  const canManageTenders = hasPermission("planning.tender:manage");
  const queryClient = useQueryClient();

  const queryKey = ["trip", companyId, tripId];
  const tripQuery = useQuery({
    queryKey,
    queryFn: ({ signal }) => fetchTrip(companyId, tripId as string, signal),
    enabled: companyId !== "" && tripId !== undefined,
  });

  const eventsQueryKey = ["trip-events", companyId, tripId];
  const eventsQuery = useQuery({
    queryKey: eventsQueryKey,
    queryFn: ({ signal }) => fetchTripEvents(companyId, tripId as string, signal),
    enabled: companyId !== "" && tripId !== undefined,
  });

  const trackingQuery = useQuery({
    queryKey: ["trip-tracking", companyId, tripId],
    queryFn: ({ signal }) => fetchTripTracking(companyId, tripId as string, signal),
    enabled: companyId !== "" && tripId !== undefined && canMonitor,
    // Un despliegue sin feed no debería gastar tres viajes por visita en volver a descubrirlo.
    retry: false,
  });

  /** La hora real que aporta el operador, vacía por defecto: vacío significa "ahora". */
  const [occurredAt, setOccurredAt] = useState("");
  const [selectedStopId, setSelectedStopId] = useState<string | null>(null);
  const [showDriverDrawer, setShowDriverDrawer] = useState(false);
  const [mobileTab, setMobileTab] = useState<"map" | "stops">("stops");
  /** Cuál de los drawers de "algo fue mal" está abierto. Un estado y no tres booleanos: los tres
   * flujos comparten formulario, y una forma que no puede representar dos a la vez es una forma
   * que no puede abrir dos a la vez. */
  const [problem, setProblem] = useState<{ mode: TripProblemMode; stopId?: string; stopLabel?: string } | null>(null);
  /** La entrega que se está registrando o corrigiendo. Lleva las etiquetas que el drawer imprime
   * para que no necesite una segunda consulta, y el registro existente cuando es una corrección. */
  const [delivery, setDelivery] = useState<{
    stopId: string; stopLabel: string; orderId: string; orderNumber: string; existing?: OrderDeliveryView;
  } | null>(null);
  const [evidenceFor, setEvidenceFor] = useState<{ deliveryId: string; orderNumber: string } | null>(null);
  const [busy, setBusy] = useState(false);

  const detail: TripDetailView | undefined = tripQuery.data;

  const mapOrigin = useMemo<TripStopMapOrigin | null>(() => {
    if (!detail || !detail.trip.originId) return null;
    return {
      latitude: detail.trip.originLatitude,
      longitude: detail.trip.originLongitude,
      label: detail.trip.originName ?? detail.trip.originCode ?? t("Origen"),
    };
  }, [detail]);

  const mapStops = useMemo<TripStopMapStop[]>(
    () => (detail?.stops ?? []).map((stop) => ({
      id: stop.destinationId,
      sequence: stop.sequence,
      latitude: stop.latitude,
      longitude: stop.longitude,
      label: stop.destinationName ?? stop.destinationCode ?? "",
    })),
    [detail],
  );

  /**
   * El marcador del vehículo, dibujado solo mientras el envío está de verdad fuera. Una posición
   * guardada esta mañana sobre un viaje que ya volvió es un hecho cierto y un pin engañoso, así
   * que el mapa la enseña mientras significa "aquí está" y la tarjeta la sigue reportando con su
   * antigüedad en cualquier caso.
   */
  const mapVehicle = useMemo<TripStopMapVehicle | null>(() => {
    const tracking = trackingQuery.data;
    if (!tracking?.trackable || !tracking.lastPosition) return null;
    return {
      latitude: tracking.lastPosition.latitude,
      longitude: tracking.lastPosition.longitude,
      label: tracking.vehicleLicensePlate ?? tracking.vehicleCode ?? tracking.shipmentNumber,
    };
  }, [trackingQuery.data]);

  function refresh() {
    void queryClient.invalidateQueries({ queryKey });
    // Toda escritura de este módulo añade a la línea de tiempo, así que nunca se refresca sola.
    void queryClient.invalidateQueries({ queryKey: eventsQueryKey });
    // El rastreo se refresca también, no porque una escritura produzca posiciones —nada en eTMS
    // lo hace— sino porque despachar y completar cambian si el envío está en la carretera, y la
    // tarjeta dice algo distinto en cada caso.
    void queryClient.invalidateQueries({ queryKey: ["trip-tracking", companyId, tripId] });
    // La lista de detrás enseña el mismo estado: dejarla obsoleta significa que quien vuelva
    // atrás vea como "listo" un viaje que acaba de despachar.
    void queryClient.invalidateQueries({ queryKey: ["trips", companyId] });
  }

  /**
   * Cada transición sigue los mismos cinco pasos, así que comparten función: confirmar, mandar la
   * versión desde la que se pintó la pantalla, avisar, refrescar y traducir un rechazo al
   * vocabulario de planificación. `busy` es lo que impide que un doble clic produzca una segunda
   * petición: el backend contestaría el reintento de forma idempotente, pero un spinner es mejor
   * respuesta que una carrera.
   */
  async function run(action: (version: number, at: string | null) => Promise<unknown>, successMessage: string) {
    if (!detail || busy) return;
    setBusy(true);
    try {
      await action(detail.trip.version, toInstant(occurredAt));
      setOccurredAt("");
      notifySuccess(successMessage, detail.trip.shipmentNumber);
      refresh();
    } catch (error) {
      notifyError(t("No se pudo completar la acción"), describePlanningError(error as ApiError));
    } finally {
      setBusy(false);
    }
  }

  async function markReady() {
    if (!detail) return;
    const confirmed = await confirmDialog({
      title: t("¿Marcar el envío como listo?"),
      text: t("{{number}} queda listo para despacho.", { number: detail.trip.shipmentNumber }),
      confirmLabel: t("Marcar listo"),
    });
    if (!confirmed) return;
    await run((version, at) => markTripReady(companyId, detail.trip.id, { version, occurredAt: at }), t("Envío listo"));
  }

  async function dispatch() {
    if (!detail) return;
    const confirmed = await confirmDialog({
      title: t("¿Despachar el envío?"),
      text: t("{{number}} sale a ruta.", { number: detail.trip.shipmentNumber }),
      confirmLabel: t("Despachar"),
    });
    if (!confirmed) return;
    await run((version, at) => dispatchTrip(companyId, detail.trip.id, { version, occurredAt: at }), t("Envío despachado"));
  }

  async function complete() {
    if (!detail) return;
    const confirmed = await confirmDialog({
      title: t("¿Completar el envío?"),
      text: t("{{number}} se cierra. No se puede deshacer.", { number: detail.trip.shipmentNumber }),
      confirmLabel: t("Completar"),
      // Terminal e irreversible: el mismo trato que cualquier acción destructiva de la app.
      dangerous: true,
    });
    if (!confirmed) return;
    await run((version, at) => completeTrip(companyId, detail.trip.id, { version, occurredAt: at }), t("Envío completado"));
  }

  /**
   * La cancelación pide el motivo *dentro* de la confirmación y no después. El backend lo exige
   * para un viaje que alguna vez estuvo confirmado —que es todo viaje alcanzable desde esta
   * pantalla— así que un diálogo que confirmara primero y preguntara después sería un viaje de
   * ida y vuelta a un 400.
   */
  async function cancel() {
    if (!detail || busy) return;
    const reason = await promptDialog({
      title: t("¿Cancelar el envío?"),
      text: t("{{number}} quedará cancelado. Di por qué.", { number: detail.trip.shipmentNumber }),
      inputLabel: t("Motivo"),
      required: true,
      maxLength: 500,
      confirmLabel: t("Cancelar envío"),
      dangerous: true,
    });
    if (reason === null) return;

    setBusy(true);
    try {
      await cancelTrip(companyId, detail.trip.id, { version: detail.trip.version, reason });
      notifySuccess(t("Envío cancelado"), detail.trip.shipmentNumber);
      refresh();
    } catch (error) {
      notifyError(t("No se pudo completar la acción"), describePlanningError(error as ApiError));
    } finally {
      setBusy(false);
    }
  }

  /**
   * Las tres acciones de parada que no piden motivo. Comparten la forma de `run` pero no su
   * firma: una acción de parada no manda `version` —el backend las serializa con el bloqueo de
   * fila del viaje— y lleva la misma hora aportada por el operador que las acciones de viaje, de
   * modo que registrar una llegada de las 11:04 desde un escritorio a las 11:40 funciona igual
   * que para una salida.
   */
  async function runStopAction(stop: TripStopView, outcome: keyof typeof STOP_ACTIONS) {
    if (!detail || busy) return;
    const action = STOP_ACTIONS[outcome];
    setBusy(true);
    try {
      await action.send(companyId, detail.trip.id, stop.id, { occurredAt: toInstant(occurredAt), notes: null });
      setOccurredAt("");
      notifySuccess(t(action.toast), stop.destinationName ?? stop.destinationCode ?? "");
      refresh();
    } catch (error) {
      notifyError(t("No se pudo completar la acción"), describePlanningError(error as ApiError));
    } finally {
      setBusy(false);
    }
  }

  /**
   * Saltar una parada, marcarla fallida y reportar una incidencia del viaje acaban aquí: el
   * drawer recoge un motivo tipado y una frase, y a cuál de los tres endpoints van es lo que
   * recuerda `problem.mode`. El drawer se queda abierto ante un rechazo, con el mensaje dentro,
   * para que un despachador que eligió un motivo que el servidor rechazó no pierda lo que escribió.
   */
  async function submitProblem(values: TripProblemValues) {
    if (!detail || problem === null) return;
    const { mode, stopId } = problem;
    try {
      if (mode === "report") {
        await reportTripException(companyId, detail.trip.id, {
          tripStopId: values.tripStopId,
          exceptionType: values.exceptionType,
          occurredAt: toInstant(occurredAt),
          notes: values.notes,
        });
      } else {
        const call = mode === "skip" ? skipStop : failStop;
        await call(companyId, detail.trip.id, stopId as string, {
          occurredAt: toInstant(occurredAt),
          exceptionType: values.exceptionType,
          notes: values.notes,
        });
      }
    } catch (error) {
      // Se relanza como Error plano para que el drawer pinte la frase del servidor en su propia
      // alerta, y no como un toast detrás de un modal que nadie puede leer.
      throw new Error(describePlanningError(error as ApiError));
    }
    setProblem(null);
    setOccurredAt("");
    notifySuccess(t("Registrado"), detail.trip.shipmentNumber);
    refresh();
  }

  async function submitDelivery(values: DeliveryValues) {
    if (!detail || delivery === null) return;
    try {
      await recordDelivery(companyId, detail.trip.id, delivery.stopId, delivery.orderId, {
        result: values.result,
        deliveredAt: values.deliveredAt === null ? null : toInstant(values.deliveredAt),
        receiverName: values.receiverName,
        receiverDocument: values.receiverDocument,
        notes: values.notes,
      });
    } catch (error) {
      throw new Error(describePlanningError(error as ApiError));
    }
    setDelivery(null);
    notifySuccess(t("Entrega registrada"), delivery.orderNumber);
    refresh();
  }

  async function submitEvidence(values: DeliveryEvidenceValues) {
    if (!detail || evidenceFor === null) return;
    try {
      await uploadDeliveryEvidence(companyId, detail.trip.id, evidenceFor.deliveryId, {
        evidenceType: values.evidenceType,
        capturedAt: values.capturedAt === null ? null : toInstant(values.capturedAt),
        file: values.file,
      });
    } catch (error) {
      throw new Error(describePlanningError(error as ApiError));
    }
    setEvidenceFor(null);
    notifySuccess(t("Prueba adjuntada"), evidenceFor.orderNumber);
    refresh();
  }

  /**
   * Trae un artefacto y se lo pasa al navegador para guardarlo.
   *
   * Por la API y no por un enlace, porque estos bytes no tienen dirección a la que llegar: la
   * petición lleva el bearer token y la cabecera de empresa como todas las demás, y la URL del
   * blob se revoca en cuanto el clic sintético tomó su propia referencia.
   */
  async function downloadEvidence(deliveryId: string, evidence: DeliveryEvidenceView) {
    if (!detail) return;
    try {
      const downloaded = await downloadDeliveryEvidence(companyId, detail.trip.id, deliveryId, evidence.id);
      const url = URL.createObjectURL(downloaded.blob);
      const link = document.createElement("a");
      link.href = url;
      link.download = downloaded.fileName ?? evidence.originalFilename ?? `evidencia-${evidence.id}`;
      link.click();
      URL.revokeObjectURL(url);
    } catch (error) {
      notifyError(t("No se pudo completar la acción"), describePlanningError(error as ApiError));
    }
  }

  /** Cerrar una incidencia. La nota la exige la API y se pide dentro de la confirmación por la
   * misma razón que el motivo de cancelación. */
  async function resolveProblem(exception: TripExceptionView) {
    if (!detail || busy) return;
    const notes = await promptDialog({
      title: t("¿Resolver la incidencia?"),
      text: enumLabel("tripExceptionType", exception.exceptionType),
      inputLabel: t("Cómo se resolvió"),
      required: true,
      maxLength: 1000,
      confirmLabel: t("Resolver"),
    });
    if (notes === null) return;

    setBusy(true);
    try {
      await resolveTripException(companyId, detail.trip.id, exception.id, { notes });
      notifySuccess(t("Incidencia resuelta"));
      refresh();
    } catch (error) {
      notifyError(t("No se pudo completar la acción"), describePlanningError(error as ApiError));
    } finally {
      setBusy(false);
    }
  }

  if (tripQuery.isPending) return <LoadingState label={t("Cargando el envío...")} />;
  if (tripQuery.isError || !detail) {
    return (
      <ErrorState
        message={describeApiError(tripQuery.error as ApiError)}
        onRetry={() => void tripQuery.refetch()}
      />
    );
  }

  const { trip, stops, assignments, deliveries, exceptions } = detail;
  const can = (status: string) => trip.allowedTransitions.includes(status as never);
  const openExceptions = exceptions.filter((exception) => exception.status === "OPEN");

  /** Las entregas de una parada, indexadas por pedido: la respuesta viene plana a propósito
   * porque una pantalla la agrupa por parada y otra por pedido, y las dos claves están ahí. */
  const deliveriesByStop = (stopId: string) => deliveries.filter((entry) => entry.tripStopId === stopId);

  return (
    <>
      <Button component={Link} to="/trips" size="small" startIcon={<ArrowBackRounded />} sx={{ mb: 1, ml: -1 }}>
        {t("Volver a viajes")}
      </Button>

      <PageHeader
        icon={<MapRounded />}
        tint={ICON_TINTS["/trips"]}
        title={trip.shipmentNumber}
        subtitle={`${trip.originName ?? trip.originCode ?? ""} · ${fmtDate(trip.planningDate)}`}
        meta={
          <>
            <StatusChip label={enumLabel("tripStatus", trip.status)} tone={TRIP_STATUS_TONE[trip.status]} variant="solid" />
            {openExceptions.length > 0 && (
              <Chip size="small" color="error" label={t("{{count}} incidencias", { count: openExceptions.length })} />
            )}
          </>
        }
        onRefresh={refresh}
        refreshing={tripQuery.isFetching}
        actions={
          <Box sx={{ display: "flex", flexWrap: "wrap", gap: 1, alignItems: "center" }}>
            {canExecute && (
              <>
                {/* La hora la aporta el operador y vale para la siguiente acción que pulse:
                    vacía significa "ahora", que es lo que hace un despachador en vivo. */}
                <TextField
                  size="small" type="datetime-local" label={t("Hora real")}
                  value={occurredAt} onChange={(e) => setOccurredAt(e.target.value)}
                  slotProps={{ inputLabel: { shrink: true } }}
                  sx={{ width: 215 }}
                />
                {/* Los botones se pintan desde `allowedTransitions`, que decide el servidor. Un
                    switch sobre `status` sería una segunda copia del ciclo de vida. */}
                {can("READY_FOR_DISPATCH") && (
                  <Button variant="outlined" startIcon={<FlagRounded />} disabled={busy} onClick={() => void markReady()}>
                    {t("Marcar listo")}
                  </Button>
                )}
                {can("IN_TRANSIT") && (
                  <Button variant="contained" startIcon={<PlayArrowRounded />} disabled={busy} onClick={() => void dispatch()}>
                    {t("Despachar")}
                  </Button>
                )}
                {can("COMPLETED") && (
                  <Button variant="contained" color="success" startIcon={<DoneAllRounded />} disabled={busy} onClick={() => void complete()}>
                    {t("Completar")}
                  </Button>
                )}
                <Button
                  variant="outlined" color="warning" startIcon={<ReportProblemRounded />}
                  onClick={() => setProblem({ mode: "report" })}
                >
                  {t("Reportar")}
                </Button>
              </>
            )}
            {canCancel && can("CANCELLED") && (
              <Button variant="outlined" color="error" startIcon={<CancelRounded />} disabled={busy} onClick={() => void cancel()}>
                {t("Cancelar")}
              </Button>
            )}
          </Box>
        }
      />

      <Box sx={{
        display: "grid", gap: 3, alignItems: "start",
        gridTemplateColumns: { xs: "1fr", lg: "minmax(0, 7fr) minmax(0, 5fr)" },
      }}>
        {/* Columna izquierda: el envío y sus paradas — lo que se acciona. */}
        <Box sx={{ display: "grid", gap: 3, minWidth: 0 }}>
          <AppCard
            title={t("Envío")}
            actions={canExecute && trip.status !== "COMPLETED" && trip.status !== "CANCELLED" && (
              <Button size="small" startIcon={<BadgeRounded />} onClick={() => setShowDriverDrawer(true)}>
                {t("Conductor")}
              </Button>
            )}
          >
            <DetailGrid columns={3}>
              <DetailItem label={t("Plan")} value={trip.planNumber} />
              <DetailItem label={t("Vehículo")} value={trip.vehicleCode ? `${trip.vehicleCode} · ${trip.vehicleLicensePlate}` : null} />
              <DetailItem label={t("Transportista")} value={trip.carrierName ?? t("Flota propia")} />
              <DetailItem
                label={t("Conductor")}
                value={trip.driverName ? (
                  <Box sx={{ display: "flex", alignItems: "center", gap: 0.75, flexWrap: "wrap" }}>
                    {trip.driverName}
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
              <DetailItem label={t("Teléfono")} value={trip.driverPhone} />
              <DetailItem label={t("Ruta")} value={trip.routeName ?? trip.routeCode} />
              <DetailItem label={t("Salida planificada")} value={trip.plannedDepartureAt ? fmtDateTime(trip.plannedDepartureAt) : null} />
              <DetailItem label={t("Salida real")} value={trip.actualDepartureAt ? fmtDateTime(trip.actualDepartureAt) : null} />
              <DetailItem label={t("Cierre")} value={trip.actualCompletionAt ? fmtDateTime(trip.actualCompletionAt) : null} />
            </DetailGrid>
            {trip.cancelReason && (
              <Alert severity="error" sx={{ mt: 2 }}>{trip.cancelReason}</Alert>
            )}
          </AppCard>

          {isNarrow && (
            <Tabs
              value={mobileTab}
              onChange={(_e, value: "map" | "stops") => setMobileTab(value)}
              variant="fullWidth"
              sx={{ borderBottom: "1px solid", borderColor: "divider" }}
            >
              <Tab value="stops" label={t("Paradas")} />
              <Tab value="map" label={t("Mapa")} />
            </Tabs>
          )}

          {(!isNarrow || mobileTab === "map") && (
            <AppCard title={t("Recorrido")} flush>
              <Box sx={{ p: 2, pb: 0 }}>
                <TripStopMap
                  origin={mapOrigin}
                  stops={mapStops}
                  vehicle={mapVehicle}
                  selectedStopId={selectedStopId}
                  onSelectStop={setSelectedStopId}
                  height={300}
                />
              </Box>
            </AppCard>
          )}

          {(!isNarrow || mobileTab === "stops") && (
            <AppCard title={t("Paradas")}>
              {stops.length === 0 ? (
                <Alert severity="info">{t("Este viaje todavía no tiene paradas.")}</Alert>
              ) : (
                <Box sx={{ display: "grid", gap: 1.5 }}>
                  {stops.map((stop) => {
                    const window = formatServiceWindow(stop.serviceWindowStart, stop.serviceWindowEnd);
                    const stopLabel = stop.destinationName ?? stop.destinationCode ?? stop.destinationId;
                    const stopDeliveries = deliveriesByStop(stop.id);
                    const selected = stop.destinationId === selectedStopId;
                    const allows = (outcome: string) => stop.allowedExecutionTransitions.includes(outcome as never);
                    const stopAssignments = assignments.filter((a) => a.destinationId === stop.destinationId);

                    return (
                      <Paper
                        key={stop.id}
                        variant="outlined"
                        onClick={() => setSelectedStopId(stop.destinationId)}
                        sx={{
                          p: 1.75, cursor: "pointer",
                          borderColor: selected ? "primary.main" : "divider",
                          bgcolor: selected ? "action.hover" : "transparent",
                        }}
                      >
                        <Box sx={{ display: "flex", alignItems: "flex-start", gap: 1.5, flexWrap: "wrap" }}>
                          <Box sx={{
                            width: 28, height: 28, borderRadius: "50%", flexShrink: 0, display: "grid", placeItems: "center",
                            bgcolor: "primary.main", color: "primary.contrastText", fontWeight: 800, fontSize: 13,
                          }}>
                            {stop.sequence}
                          </Box>
                          <Box sx={{ flex: 1, minWidth: 160 }}>
                            <Typography variant="body2" sx={{ fontWeight: 700, lineHeight: 1.3 }}>{stopLabel}</Typography>
                            <Typography variant="caption" color="text.secondary">
                              {[stop.address, window].filter(Boolean).join(" · ") || stop.destinationCode}
                            </Typography>
                          </Box>
                          <StatusChip
                            label={enumLabel("stopExecutionStatus", stop.executionStatus)}
                            tone={STOP_EXECUTION_TONE[stop.executionStatus]}
                          />
                        </Box>

                        {(stop.actualArrivalAt || stop.actualDepartureAt || stop.dwellMinutes !== null) && (
                          <Typography variant="caption" color="text.secondary" sx={{ display: "block", mt: 0.75, ml: 5.5 }}>
                            {stop.actualArrivalAt && `${t("Llegada")} ${fmtTime(stop.actualArrivalAt)}`}
                            {stop.actualDepartureAt && ` · ${t("Salida")} ${fmtTime(stop.actualDepartureAt)}`}
                            {stop.dwellMinutes !== null && ` · ${t("Permanencia")} ${fmtMinutes(stop.dwellMinutes)}`}
                          </Typography>
                        )}

                        {stop.executionNotes && (
                          <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5, ml: 5.5 }}>
                            {stop.executionNotes}
                          </Typography>
                        )}

                        {/* Las acciones de parada se pintan desde `allowedExecutionTransitions`,
                            que está vacía mientras el vehículo no ha salido: una parada no se
                            puede trabajar antes de que su camión se vaya. */}
                        {canExecute && stop.allowedExecutionTransitions.length > 0 && (
                          <Box sx={{ display: "flex", gap: 0.75, flexWrap: "wrap", mt: 1.25, ml: 5.5 }}>
                            {(["ARRIVED", "IN_SERVICE", "COMPLETED"] as const).map((outcome) =>
                              allows(outcome) ? (
                                <Button
                                  key={outcome} size="small" variant="outlined"
                                  startIcon={STOP_ACTIONS[outcome].icon}
                                  disabled={busy}
                                  onClick={(e) => { e.stopPropagation(); void runStopAction(stop, outcome); }}
                                >
                                  {t(STOP_ACTIONS[outcome].label)}
                                </Button>
                              ) : null,
                            )}
                            {allows("SKIPPED") && (
                              <Button
                                size="small" color="warning" startIcon={<SkipNextRounded />}
                                onClick={(e) => { e.stopPropagation(); setProblem({ mode: "skip", stopId: stop.id, stopLabel }); }}
                              >
                                {t("Saltar")}
                              </Button>
                            )}
                            {allows("FAILED") && (
                              <Button
                                size="small" color="error" startIcon={<ReportProblemRounded />}
                                onClick={(e) => { e.stopPropagation(); setProblem({ mode: "fail", stopId: stop.id, stopLabel }); }}
                              >
                                {t("Fallida")}
                              </Button>
                            )}
                          </Box>
                        )}

                        {/* Los pedidos de esta parada y qué se entregó de cada uno. Un pedido sin
                            entrega registrada simplemente no tiene entrada: aquí no se inventa
                            un estado "pendiente" que el backend no tiene. */}
                        {stopAssignments.length > 0 && (
                          <Box sx={{ mt: 1.5, ml: 5.5 }}>
                            <Divider sx={{ mb: 1 }} />
                            {stopAssignments.map((assignment) => {
                              const recorded = stopDeliveries.find((entry) => entry.orderId === assignment.orderId);
                              return (
                                <Box key={assignment.assignmentId} sx={{ display: "flex", alignItems: "center", gap: 1, flexWrap: "wrap", py: 0.5 }}>
                                  <Typography variant="body2" sx={{ fontWeight: 700, minWidth: 100 }}>
                                    {assignment.orderNumber}
                                  </Typography>
                                  {recorded ? (
                                    <>
                                      <StatusChip
                                        label={enumLabel("deliveryResult", recorded.result)}
                                        tone={DELIVERY_RESULT_TONE[recorded.result]}
                                      />
                                      {recorded.receiverName && (
                                        <Typography variant="caption" color="text.secondary">{recorded.receiverName}</Typography>
                                      )}
                                      {recorded.evidence.map((evidence) => (
                                        <Tooltip key={evidence.id} title={evidence.originalFilename ?? enumLabel("evidenceType", evidence.evidenceType)}>
                                          <IconButton
                                            size="small"
                                            onClick={(e) => { e.stopPropagation(); void downloadEvidence(recorded.id, evidence); }}
                                            aria-label={t("Descargar la prueba")}
                                          >
                                            <DownloadRounded fontSize="small" />
                                          </IconButton>
                                        </Tooltip>
                                      ))}
                                    </>
                                  ) : (
                                    <Typography variant="caption" color="text.disabled">{t("Sin registrar")}</Typography>
                                  )}
                                  <Box sx={{ flex: 1 }} />
                                  {canExecute && (
                                    <>
                                      <Tooltip title={recorded ? t("Corregir la entrega") : t("Registrar la entrega")}>
                                        <IconButton
                                          size="small"
                                          onClick={(e) => {
                                            e.stopPropagation();
                                            setDelivery({
                                              stopId: stop.id, stopLabel,
                                              orderId: assignment.orderId, orderNumber: assignment.orderNumber,
                                              existing: recorded,
                                            });
                                          }}
                                        >
                                          {recorded ? <EditRounded fontSize="small" /> : <InventoryRounded fontSize="small" />}
                                        </IconButton>
                                      </Tooltip>
                                      {recorded && (
                                        <Tooltip title={t("Adjuntar prueba de entrega")}>
                                          <IconButton
                                            size="small"
                                            onClick={(e) => {
                                              e.stopPropagation();
                                              setEvidenceFor({ deliveryId: recorded.id, orderNumber: assignment.orderNumber });
                                            }}
                                          >
                                            <AttachFileRounded fontSize="small" />
                                          </IconButton>
                                        </Tooltip>
                                      )}
                                    </>
                                  )}
                                </Box>
                              );
                            })}
                          </Box>
                        )}
                      </Paper>
                    );
                  })}
                </Box>
              )}
            </AppCard>
          )}
        </Box>

        {/* Columna derecha: lo que se lee — rastreo, dinero, ofertas, incidencias y la historia. */}
        <Box sx={{ display: "grid", gap: 3, minWidth: 0 }}>
          {canMonitor && (
            <TripTrackingCard
              tracking={trackingQuery.data}
              loading={trackingQuery.isPending}
              failed={trackingQuery.isError}
            />
          )}

          {canReadCost && <TripCostCard companyId={companyId} tripId={trip.id} canManage={canManageCost} />}

          {canReadTenders && (
            <TripTenderCard
              companyId={companyId}
              tripId={trip.id}
              carrierName={trip.carrierName}
              offerable={trip.carrierId !== null && trip.status !== "COMPLETED" && trip.status !== "CANCELLED"}
              canManage={canManageTenders}
            />
          )}

          {exceptions.length > 0 && (
            <AppCard title={t("Incidencias")}>
              <Box sx={{ display: "grid", gap: 1 }}>
                {exceptions.map((exception) => (
                  <Paper key={exception.id} variant="outlined" sx={{ p: 1.5 }}>
                    <Box sx={{ display: "flex", alignItems: "center", gap: 1, mb: 0.5, flexWrap: "wrap" }}>
                      <Typography variant="body2" sx={{ fontWeight: 700 }}>
                        {enumLabel("tripExceptionType", exception.exceptionType)}
                      </Typography>
                      <StatusChip
                        label={enumLabel("tripExceptionStatus", exception.status)}
                        tone={exception.status === "OPEN" ? "overdue" : "done"}
                      />
                    </Box>
                    <Typography variant="caption" color="text.secondary" sx={{ display: "block" }}>
                      {exception.stopSequence !== null && `${exception.stopSequence}. ${exception.stopDestinationName ?? ""} · `}
                      {fmtDateTime(exception.reportedAt)}
                    </Typography>
                    {exception.notes && (
                      <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5 }}>{exception.notes}</Typography>
                    )}
                    {exception.resolutionNotes && (
                      <Typography variant="body2" color="success.main" sx={{ mt: 0.5 }}>
                        {exception.resolutionNotes}
                      </Typography>
                    )}
                    {canExecute && exception.status === "OPEN" && (
                      <Button size="small" sx={{ mt: 1 }} disabled={busy} onClick={() => void resolveProblem(exception)}>
                        {t("Resolver")}
                      </Button>
                    )}
                  </Paper>
                ))}
              </Box>
            </AppCard>
          )}

          <AppCard title={t("Historia del viaje")}>
            <TripTimeline events={eventsQuery.data ?? []} loading={eventsQuery.isPending} />
          </AppCard>
        </Box>
      </Box>

      {problem && (
        <TripProblemDrawer
          mode={problem.mode}
          stops={stops}
          stopLabel={problem.stopLabel}
          onClose={() => setProblem(null)}
          onSubmit={submitProblem}
        />
      )}

      {delivery && (
        <DeliveryDrawer
          stopLabel={delivery.stopLabel}
          orderNumber={delivery.orderNumber}
          existing={delivery.existing}
          onClose={() => setDelivery(null)}
          onSubmit={submitDelivery}
        />
      )}

      {evidenceFor && (
        <DeliveryEvidenceDrawer
          orderNumber={evidenceFor.orderNumber}
          onClose={() => setEvidenceFor(null)}
          onSubmit={submitEvidence}
        />
      )}

      {showDriverDrawer && (
        <TripDriverDrawer
          companyId={companyId}
          trip={trip}
          onClose={() => setShowDriverDrawer(false)}
          onSaved={() => { setShowDriverDrawer(false); refresh(); }}
        />
      )}

    </>
  );
}
