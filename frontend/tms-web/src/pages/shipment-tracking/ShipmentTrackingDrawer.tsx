import { useQuery } from "@tanstack/react-query";
import { useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { Alert, Box, Button, ButtonBase, Divider, Typography } from "@mui/material";
import { GpsOffRounded, LocalShippingRounded, OpenInNewRounded, PhotoCameraRounded } from "@mui/icons-material";
import { fetchDocumentDeliveries, type DocumentDeliveryRow } from "../../shared/api/documentDeliveriesApi";
import type { ApiError } from "../../shared/api/httpClient";
import { CAPABILITY } from "../../shared/api/meApi";
import type { TripStatus } from "../../shared/api/planningApi";
import { describeApiError } from "../../shared/api/problemMessages";
import type { ShipmentTrackingRow, ShipmentTrackingStop } from "../../shared/api/shipmentTrackingApi";
import { useCompany } from "../../shared/company/CompanyContext";
import { ShipmentRouteMap, type RouteMapPosition } from "../../shared/maps/ShipmentRouteMap";
import { STOP_STATE_COLOR, STOP_STATE_LABEL, type StopDocumentState } from "../../shared/maps/shipmentMarkers";
import { DocumentStatusChip } from "../../shared/ui/DocumentStatusChip";
import { FormDrawer, LoadingState, StatusChip } from "../../shared/ui/components";
import { DetailSection, KeyFacts } from "../../shared/ui/components/DetailLayout";
import { STOP_EXECUTION_TONE, TRIP_STATUS_TONE } from "../../shared/ui/statusTones";
import { enumLabel } from "../../lib/enums";
import { t } from "../../lib/i18n";
import { fmtDateTime } from "../../lib/locale";
import { R } from "../../theme";
import { DocumentDeliveryDrawer } from "../document-deliveries/DocumentDeliveryDrawer";
import { verificationTone } from "../trips/warehouseDispatch";
import { useTripDetail, useTripTracking } from "../trips/tripQueries";
import { documentDeliveriesKey } from "../document-deliveries/documentDeliveries";
import { groupDocumentsByStop, stopStateFromDocuments } from "./shipmentTracking";

/** El monitor de documentos devuelve como mucho 200 filas por página (`PageQuery.MAX_SIZE`). */
const DOCUMENTS_PAGE_SIZE = 200;

interface ShipmentTrackingDrawerProps {
  row: ShipmentTrackingRow;
  /** Las fechas de la pantalla: el envío salió dentro de ellas, así que sus documentos también. */
  dateFrom?: string;
  dateTo?: string;
  onClose: () => void;
}

/**
 * Un despacho abierto: qué salió y cómo se verificó, su ruta con las últimas posiciones
 * reportadas, y parada a parada qué pasó con cada documento.
 *
 * Cuatro lecturas, y solo al abrirlo: los documentos del envío (el monitor de ADR-018, filtrado
 * por el número de envío y después por el viaje), el detalle del viaje (para situar cada documento
 * en su parada por sus pedidos), y el rastreo (ADR-007). Las posiciones informan: nada aquí se
 * acciona a partir de ellas.
 */
export function ShipmentTrackingDrawer({ row, dateFrom, dateTo, onClose }: ShipmentTrackingDrawerProps) {
  const { selected, hasPermission, hasCapability } = useCompany();
  const companyId = selected?.id ?? "";
  const canReadDocuments = hasCapability(CAPABILITY.DOCUMENT_DELIVERY_VIEW);
  const canManageDocuments = hasCapability(CAPABILITY.DOCUMENT_DELIVERY_MANAGE);
  const canReadTrip = hasPermission("planning.trip:read");
  const canMonitor = hasPermission("monitoring.transport:read");
  const [openDocument, setOpenDocument] = useState<DocumentDeliveryRow | null>(null);

  const documentsQuery = useQuery({
    // Bajo la clave del monitor de documentos: confirmar un cargo desde el detalle (que la invalida)
    // refresca también esta lista.
    queryKey: [...documentDeliveriesKey(companyId), "shipment", row.tripId, row.shipmentNumber, dateFrom, dateTo],
    queryFn: ({ signal }) => fetchDocumentDeliveries({
      companyId, q: row.shipmentNumber, dateFrom, dateTo, page: 0, size: DOCUMENTS_PAGE_SIZE, signal,
    }),
    enabled: companyId !== "" && canReadDocuments,
  });
  const detailQuery = useTripDetail(companyId, row.tripId, canReadTrip);
  const trackingQuery = useTripTracking(companyId, row.tripId, canMonitor);

  // La búsqueda por texto puede traer envíos cuyo número contiene este: se queda solo este viaje.
  const documents = useMemo(
    () => (documentsQuery.data?.content ?? []).filter((d) => d.tripId === row.tripId),
    [documentsQuery.data, row.tripId],
  );
  const grouped = useMemo(
    () => groupDocumentsByStop(row.stops, documents, detailQuery.data ?? null),
    [row.stops, documents, detailQuery.data],
  );
  const tripHasProblems = row.rejectedCount + row.notDeliveredCount > 0;
  const documentsLoaded = canReadDocuments && documentsQuery.isSuccess;

  const routeStops = useMemo(() => grouped.stops.map(({ stop, documents: stopDocuments }) => ({
    id: stop.stopId,
    sequence: stop.sequence,
    latitude: stop.latitude,
    longitude: stop.longitude,
    label: stop.destinationName ?? stop.address ?? "",
    state: stopStateFromDocuments(stop, documentsLoaded ? stopDocuments : null, tripHasProblems),
  })), [grouped, documentsLoaded, tripHasProblems]);
  const origin = useMemo(
    () => ({ latitude: row.originLatitude, longitude: row.originLongitude, label: row.originName ?? t("Origen") }),
    [row.originLatitude, row.originLongitude, row.originName],
  );

  const tracking = trackingQuery.data;
  const positions: RouteMapPosition[] = useMemo(() => {
    if (!tracking) return [];
    const trail = tracking.track.length > 0 ? tracking.track : tracking.lastPosition ? [tracking.lastPosition] : [];
    return trail.map((p) => ({ latitude: p.latitude, longitude: p.longitude, occurredAt: p.occurredAt }));
  }, [tracking]);
  const lastPosition = tracking?.lastPosition ?? (positions.length > 0 ? positions[positions.length - 1] : null);

  return (
    <>
      <FormDrawer
        open
        size="lg"
        icon={<LocalShippingRounded />}
        title={row.shipmentNumber}
        subtitle={[row.carrierName ?? t("Sin transportista"), row.vehicleLicensePlate, row.driverName].filter(Boolean).join(" · ")}
        onClose={onClose}
        footer={
          <>
            <Button variant="outlined" color="inherit" onClick={onClose} sx={{ borderColor: "divider" }}>{t("Cerrar")}</Button>
            <Button variant="contained" component={Link} to={`/trips/${row.tripId}`} startIcon={<OpenInNewRounded />}>
              {t("Abrir espacio de trabajo")}
            </Button>
          </>
        }
      >
        <Box sx={{ display: "grid", gap: 2.5 }}>
          <Box sx={{ display: "flex", gap: 1, flexWrap: "wrap" }}>
            <StatusChip label={enumLabel("tripStatus", row.tripStatus)} tone={TRIP_STATUS_TONE[row.tripStatus as TripStatus] ?? "neutral"} variant="solid" />
            <StatusChip label={enumLabel("dispatchVerificationStatus", row.dispatchVerification)} tone={verificationTone(row.dispatchVerification)} />
          </Box>

          <KeyFacts columns={3} items={[
            { label: t("Despacho del almacén"), value: row.dispatchReference, sub: fmtDateTime(row.dispatchedAt) },
            { label: t("Vehículo despachado"), value: row.vehicleLicensePlate, sub: row.driverName ?? undefined },
            { label: t("Origen"), value: row.originName },
            { label: t("Paradas"), value: `${row.stopsResolved}/${row.stopsTotal}`, sub: t("Resueltas / totales") },
            {
              label: t("Documentos"),
              value: t("{{done}} de {{total}} reportados", { done: row.documentCount - row.pendingCount, total: row.documentCount }),
              sub: t("{{n}} cargos recibidos", { n: row.returnedCount }),
            },
            { label: t("Último reporte"), value: row.lastReportAt ? fmtDateTime(row.lastReportAt) : null },
          ]} />

          <DetailSection title={t("Ruta y posiciones")}>
            <Box sx={{ display: "grid", gap: 1 }}>
              <ShipmentRouteMap origin={origin} stops={routeStops} positions={positions} />
              {canMonitor && (
                trackingQuery.isPending ? (
                  <Typography variant="caption" color="text.secondary">{t("Consultando posiciones...")}</Typography>
                ) : lastPosition ? (
                  <Typography variant="caption" color="text.secondary">
                    {t("Última posición GPS: {{when}}. Las posiciones informan; no cambian el estado de nada.", { when: fmtDateTime(lastPosition.occurredAt) })}
                  </Typography>
                ) : (
                  <Box sx={{ display: "flex", alignItems: "center", gap: 0.75, color: "text.secondary" }}>
                    <GpsOffRounded sx={{ fontSize: 16 }} />
                    <Typography variant="caption">{t("Sin posiciones GPS")}</Typography>
                  </Box>
                )
              )}
            </Box>
          </DetailSection>

          <DetailSection title={t("Paradas y documentos")}>
            {!canReadDocuments ? (
              <Alert severity="info" sx={{ mb: 1 }}>
                {t("Tu rol puede seguir los despachos pero no leer el detalle de sus documentos.")}
              </Alert>
            ) : documentsQuery.isPending ? (
              <LoadingState minHeight={100} />
            ) : documentsQuery.isError ? (
              <Alert severity="error" sx={{ mb: 1 }}>{describeApiError(documentsQuery.error as ApiError)}</Alert>
            ) : null}

            {row.stops.length === 0 ? (
              <Typography variant="body2" color="text.secondary">{t("Este viaje todavía no tiene paradas.")}</Typography>
            ) : (
              <Box sx={{ border: "1px solid", borderColor: "divider", borderRadius: `${R.md}px`, overflow: "hidden" }}>
                {grouped.stops.map(({ stop, documents: stopDocuments }, index) => (
                  <Box key={stop.stopId}>
                    {index > 0 && <Divider />}
                    <StopBlock
                      stop={stop}
                      documents={documentsLoaded ? stopDocuments : null}
                      state={routeStops[index].state}
                      onOpenDocument={setOpenDocument}
                    />
                  </Box>
                ))}
              </Box>
            )}

            {documentsLoaded && grouped.unassigned.length > 0 && (
              <Box sx={{ mt: 1.5 }}>
                <Typography variant="caption" color="text.secondary" sx={{ display: "block", mb: 0.5 }}>
                  {canReadTrip && detailQuery.isSuccess
                    ? t("Documentos del envío sin parada identificada")
                    : t("Documentos del envío")}
                </Typography>
                <Box sx={{ border: "1px solid", borderColor: "divider", borderRadius: `${R.md}px`, overflow: "hidden" }}>
                  {grouped.unassigned.map((document, index) => (
                    <Box key={document.id}>
                      {index > 0 && <Divider />}
                      <DocumentLine document={document} onOpen={setOpenDocument} />
                    </Box>
                  ))}
                </Box>
              </Box>
            )}
          </DetailSection>
        </Box>
      </FormDrawer>

      {openDocument && (
        <DocumentDeliveryDrawer
          companyId={companyId}
          row={openDocument}
          canManage={canManageDocuments}
          onClose={() => setOpenDocument(null)}
        />
      )}
    </>
  );
}

function StopBlock({ stop, documents, state, onOpenDocument }: {
  stop: ShipmentTrackingStop;
  documents: DocumentDeliveryRow[] | null;
  state: StopDocumentState;
  onOpenDocument: (document: DocumentDeliveryRow) => void;
}) {
  return (
    <Box role="group" aria-label={t("Parada {{n}}", { n: stop.sequence })} sx={{ px: 1.25, py: 1 }}>
      <Box sx={{ display: "flex", alignItems: "center", gap: 1, flexWrap: "wrap" }}>
        <Box aria-hidden sx={{
          width: 24, height: 24, borderRadius: "50%", display: "grid", placeItems: "center", flexShrink: 0,
          bgcolor: STOP_STATE_COLOR[state], color: "#fff", fontSize: 12, fontWeight: 800,
        }}>
          {stop.sequence}
        </Box>
        <Box sx={{ flex: 1, minWidth: 160 }}>
          <Typography variant="body2" sx={{ fontWeight: 700, lineHeight: 1.3 }}>
            {stop.sequence}. {stop.destinationName ?? t("Destino sin nombre")}
          </Typography>
          <Typography variant="caption" color="text.secondary">
            {[stop.address, t(STOP_STATE_LABEL[state])].filter(Boolean).join(" · ")}
          </Typography>
        </Box>
        <Typography variant="caption" color="text.secondary" sx={{ fontVariantNumeric: "tabular-nums" }}>
          {t("{{pending}} pendientes de {{total}}", { pending: stop.pendingCount, total: stop.documentCount })}
        </Typography>
        <StatusChip
          label={enumLabel("stopExecutionStatus", stop.executionStatus)}
          tone={STOP_EXECUTION_TONE[stop.executionStatus as keyof typeof STOP_EXECUTION_TONE] ?? "neutral"}
        />
      </Box>
      {documents !== null && documents.length > 0 && (
        <Box sx={{ mt: 0.75, ml: { xs: 0, sm: 4 }, border: "1px solid", borderColor: "divider", borderRadius: `${R.sm}px`, overflow: "hidden" }}>
          {documents.map((document, index) => (
            <Box key={document.id}>
              {index > 0 && <Divider />}
              <DocumentLine document={document} onOpen={onOpenDocument} />
            </Box>
          ))}
        </Box>
      )}
    </Box>
  );
}

/** Un documento en su parada; pulsarlo abre su detalle (fotos, firma, historial, cargo). */
function DocumentLine({ document, onOpen }: { document: DocumentDeliveryRow; onOpen: (document: DocumentDeliveryRow) => void }) {
  return (
    <ButtonBase
      onClick={() => onOpen(document)}
      aria-label={t("Abrir el documento {{doc}}", { doc: document.documentNumber })}
      sx={{ width: "100%", display: "flex", alignItems: "center", gap: 1, px: 1, py: 0.75, textAlign: "left", flexWrap: "wrap", "&:hover": { bgcolor: "action.hover" } }}
    >
      <Box sx={{ flex: 1, minWidth: 150 }}>
        <Typography variant="body2" sx={{ fontWeight: 700 }}>{document.documentNumber}</Typography>
        <Typography variant="caption" color="text.secondary">
          {[enumLabel("deliveryDocumentType", document.documentType), document.customerName].filter(Boolean).join(" · ")}
        </Typography>
      </Box>
      <Box sx={{ display: "grid", gap: 0.25, justifyItems: "start", minWidth: 120 }}>
        <DocumentStatusChip status={document.status} />
        {document.reasonCode && (
          <Typography variant="caption" color="text.secondary">{enumLabel("documentDeliveryReason", document.reasonCode)}</Typography>
        )}
      </Box>
      <Box sx={{ minWidth: 120 }}>
        {document.reportedAt ? (
          <>
            <Typography variant="caption" sx={{ display: "block" }}>{fmtDateTime(document.reportedAt)}</Typography>
            <Typography variant="caption" color="text.secondary">{document.recordedByName ?? ""}</Typography>
          </>
        ) : <Typography variant="caption" color="text.disabled">{t("Sin reporte")}</Typography>}
      </Box>
      {document.evidenceCount > 0 && (
        <Box component="span" sx={{ display: "inline-flex", alignItems: "center", gap: 0.5, color: "text.secondary" }}>
          <PhotoCameraRounded sx={{ fontSize: 15 }} />
          <Typography variant="caption">{document.evidenceCount}</Typography>
        </Box>
      )}
      {document.returnedAt
        ? <StatusChip tone="done" label={t("Cargo recibido")} />
        : document.status === "PENDING" ? null
          : <StatusChip tone="inProgress" label={t("Por recibir")} />}
    </ButtonBase>
  );
}
