import { Link } from "react-router-dom";
import { Alert, Box, Button, Chip, Divider, Typography } from "@mui/material";
import { LocalShippingRounded, OpenInNewRounded } from "@mui/icons-material";
import type { ControlTowerTripView } from "../../shared/api/controlTowerApi";
import type { ApiError } from "../../shared/api/httpClient";
import { describeApiError } from "../../shared/api/problemMessages";
import { useCompany } from "../../shared/company/CompanyContext";
import { FormDrawer, LoadingState, StatusChip } from "../../shared/ui/components";
import { DetailSection, KeyFacts } from "../../shared/ui/components/DetailLayout";
import { DELIVERY_RESULT_TONE, STOP_EXECUTION_TONE, TRIP_STATUS_TONE } from "../../shared/ui/statusTones";
import { enumLabel } from "../../lib/enums";
import { t } from "../../lib/i18n";
import { fmtDateTime, fmtTime } from "../../lib/locale";
import { TripTrackingCard } from "../trips/TripTrackingCard";
import { TripWarehouseCard } from "../trips/TripWarehouseCard";
import { useTripDetail, useTripTracking, useTripWarehouse } from "../trips/tripQueries";
import { R } from "../../theme";
import { deliverySummary } from "./deliveryTracking";

/**
 * El panel lateral de una fila del seguimiento: el detalle que la tabla no pide para no disparar
 * una consulta por fila. Tres lecturas, y solo al abrirlo: el viaje (paradas y entregas), la
 * ubicación y el despacho del almacén. Reutiliza las tarjetas del espacio de trabajo y sus mismas
 * claves de caché, así que pasar de aquí al espacio de trabajo no vuelve a pedir nada.
 *
 * No acciona nada. Registrar una llegada o una entrega se hace en el espacio de trabajo, que es
 * donde están los controles y la hora real aportada por el operador.
 */
export function DeliveryTripDrawer({ row, onClose }: { row: ControlTowerTripView; onClose: () => void }) {
  const { selected, hasPermission } = useCompany();
  const companyId = selected?.id ?? "";
  const tripId = row.trip.id;
  const canReadTrip = hasPermission("planning.trip:read");
  const canMonitor = hasPermission("monitoring.transport:read");

  const detailQuery = useTripDetail(companyId, tripId, canReadTrip);
  const trackingQuery = useTripTracking(companyId, tripId, canMonitor);
  const warehouseQuery = useTripWarehouse(companyId, tripId, canReadTrip);

  const detail = detailQuery.data;
  const summary = detail ? deliverySummary(detail) : null;
  const orderNumbers = new Map((detail?.assignments ?? []).map((a) => [a.orderId, a.orderNumber]));

  return (
    <FormDrawer
      open
      size="lg"
      icon={<LocalShippingRounded />}
      title={row.trip.shipmentNumber}
      subtitle={[row.trip.carrierName ?? t("Flota propia"), row.trip.vehicleLicensePlate, row.trip.driverName].filter(Boolean).join(" · ")}
      onClose={onClose}
      footer={
        <>
          <Button variant="outlined" color="inherit" onClick={onClose} sx={{ borderColor: "divider" }}>{t("Cerrar")}</Button>
          <Button variant="contained" component={Link} to={`/trips/${tripId}`} startIcon={<OpenInNewRounded />}>
            {t("Abrir espacio de trabajo")}
          </Button>
        </>
      }
    >
      <Box sx={{ display: "grid", gap: 2.5 }}>
        <Box sx={{ display: "flex", gap: 1, flexWrap: "wrap" }}>
          <StatusChip label={enumLabel("tripStatus", row.trip.status)} tone={TRIP_STATUS_TONE[row.trip.status]} variant="solid" />
          <StatusChip label={enumLabel("departureTimeliness", row.departureTimeliness)} tone="neutral" />
          {row.openExceptions > 0 && (
            <Chip size="small" color="error" label={t("{{count}} incidencias", { count: row.openExceptions })} />
          )}
        </Box>

        <KeyFacts columns={3} items={[
          { label: t("Ruta"), value: row.trip.routeName ?? row.trip.routeCode },
          { label: t("Salida planificada"), value: row.trip.plannedDepartureAt ? fmtDateTime(row.trip.plannedDepartureAt) : null },
          { label: t("Salida real"), value: row.trip.actualDepartureAt ? fmtDateTime(row.trip.actualDepartureAt) : null },
          { label: t("Teléfono"), value: row.trip.driverPhone },
          { label: t("Paradas"), value: `${row.stopsResolved}/${row.stopsTotal}` },
          {
            label: t("Próxima parada"),
            value: row.nextStopSequence === null ? null
              : `${row.nextStopSequence}${row.nextStopDueAt ? ` · ${fmtTime(row.nextStopDueAt)}` : ""}`,
          },
        ]} />

        {!canReadTrip ? (
          <Alert severity="info">
            {t("Tu rol puede seguir el día pero no leer el detalle de los envíos (planning.trip:read).")}
          </Alert>
        ) : detailQuery.isPending ? (
          <LoadingState minHeight={120} />
        ) : detailQuery.isError || !detail ? (
          <Alert severity="error">{describeApiError(detailQuery.error as ApiError)}</Alert>
        ) : (
          <>
            <DetailSection title={t("Entregas")}>
              {summary && (
                <Box sx={{ display: "flex", gap: 1, flexWrap: "wrap" }}>
                  <Chip size="small" variant="outlined" label={t("{{n}} pedidos", { n: summary.orders })} />
                  <StatusChip label={`${t("Entregados")}: ${summary.delivered}`} tone="done" />
                  <StatusChip label={`${t("Parciales")}: ${summary.partial}`} tone="inProgress" />
                  <StatusChip label={`${t("Rechazados o fallidos")}: ${summary.notDelivered}`} tone="overdue" />
                  {summary.notAttempted > 0 && <StatusChip label={`${t("No intentados")}: ${summary.notAttempted}`} tone="neutral" />}
                  <StatusChip label={`${t("Sin registrar")}: ${summary.unrecorded}`} tone="neutral" />
                </Box>
              )}
            </DetailSection>

            <DetailSection title={t("Paradas")}>
              {detail.stops.length === 0 ? (
                <Typography variant="body2" color="text.secondary">{t("Este viaje todavía no tiene paradas.")}</Typography>
              ) : (
                <Box sx={{ border: "1px solid", borderColor: "divider", borderRadius: `${R.md}px`, overflow: "hidden" }}>
                  {detail.stops.map((stop, index) => {
                    const stopOrders = detail.assignments.filter((a) => a.destinationId === stop.destinationId);
                    return (
                      <Box key={stop.id}>
                        {index > 0 && <Divider />}
                        <Box sx={{ display: "flex", alignItems: "center", gap: 1, px: 1.25, py: 0.75, flexWrap: "wrap" }}>
                          <Typography variant="body2" sx={{ fontWeight: 800, width: 22 }}>{stop.sequence}</Typography>
                          <Box sx={{ flex: 1, minWidth: 160 }}>
                            <Typography variant="body2" sx={{ fontWeight: 700, lineHeight: 1.3 }}>
                              {stop.destinationName ?? stop.destinationCode ?? stop.destinationId}
                            </Typography>
                            <Typography variant="caption" color="text.secondary">
                              {stop.actualArrivalAt ? `${t("Llegada")} ${fmtTime(stop.actualArrivalAt)}`
                                : stop.etaArrivalAt ? `${t("ETA")} ${fmtTime(stop.etaArrivalAt)}${stop.etaSource === "FALLBACK" ? ` (${t("estimada")})` : ""}`
                                  : t("Sin estimación")}
                              {stop.etaMissesWindow && !stop.actualArrivalAt && ` · ${t("fuera de ventana")}`}
                            </Typography>
                          </Box>
                          <Box sx={{ display: "flex", gap: 0.5, flexWrap: "wrap" }}>
                            {stopOrders.map((assignment) => {
                              const recorded = detail.deliveries.find((d) => d.orderId === assignment.orderId && d.tripStopId === stop.id);
                              return (
                                <StatusChip
                                  key={assignment.assignmentId}
                                  label={`${assignment.orderNumber}: ${recorded ? enumLabel("deliveryResult", recorded.result) : t("Sin registrar")}`}
                                  tone={recorded ? DELIVERY_RESULT_TONE[recorded.result] : "neutral"}
                                />
                              );
                            })}
                          </Box>
                          <StatusChip label={enumLabel("stopExecutionStatus", stop.executionStatus)} tone={STOP_EXECUTION_TONE[stop.executionStatus]} />
                        </Box>
                      </Box>
                    );
                  })}
                </Box>
              )}
            </DetailSection>
          </>
        )}

        <Box sx={{ display: "grid", gap: 2 }}>
          {canMonitor && (
            <TripTrackingCard tracking={trackingQuery.data} loading={trackingQuery.isPending} failed={trackingQuery.isError} />
          )}
          {canReadTrip && detail && (
            <TripWarehouseCard
              companyId={companyId}
              trip={detail.trip}
              warehouse={warehouseQuery.data}
              loading={warehouseQuery.isPending}
              failed={warehouseQuery.isError}
              orderNumbers={orderNumbers}
            />
          )}
        </Box>
      </Box>
    </FormDrawer>
  );
}
