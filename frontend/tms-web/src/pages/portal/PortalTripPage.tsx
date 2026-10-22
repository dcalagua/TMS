import { useMemo, useState, type ReactNode } from "react";
import { useNavigate, useParams } from "react-router-dom";
import {
  Alert, Box, Button, ButtonBase, CircularProgress, Fab, IconButton, LinearProgress, Paper, Skeleton, Typography,
} from "@mui/material";
import {
  ArrowBackRounded, ChevronRightRounded, CloudUploadRounded, ContentCopyRounded, DirectionsRounded, ErrorOutlineRounded,
  LockRounded,
  PersonRounded, PhotoCameraRounded, PlaceRounded, RefreshRounded, ScheduleRounded, SkipNextRounded, TripOriginRounded,
  LocalShippingRounded,
} from "@mui/icons-material";
import { fetchPortalTrip } from "../../shared/api/portalApi";
import { useCompany } from "../../shared/company/CompanyContext";
import { DocumentStatusChip } from "../../shared/ui/DocumentStatusChip";
import { EmptyState, ErrorState, StatusChip } from "../../shared/ui/components";
import { enumLabel } from "../../lib/enums";
import { t } from "../../lib/i18n";
import { fmtDateTime } from "../../lib/locale";
import { notifyError, notifySuccess, notifyWithAction, toast } from "../../lib/ui";
import { authorizationLabel, logisticsDocumentLabel } from "../../shared/ui/logisticsDocumentLabel";
import { R } from "../../theme";
import { DocumentReportDrawer, type SaveOutcome } from "./DocumentReportDrawer";
import { OfflineBanner } from "./OfflineBanner";
import { usePortalOffline } from "./offline/offlineContext";
import {
  applyOutboxOverlay, nextPendingDocument, type ShownDocument, type ShownTripDetail,
} from "./offline/overlay";
import { usePortalQuery } from "./offline/usePortalQuery";
import { describeLoadError, portalTripKey } from "./portalReport";
import { PORTAL_BAR_HEIGHT, SAFE_AREA, directionsUrl, PORTAL_PAGE_GRID } from "./portalUi";

type ShownStop = ShownTripDetail["stops"][number];

/**
 * Un viaje del transportista: las paradas en orden, como una línea de tiempo, y en cada una los
 * documentos que hay que entregar. Tocar un documento abre la hoja para reportarlo; el botón
 * flotante abre directamente el siguiente pendiente.
 *
 * Lo que espera en la cola del teléfono se enseña ya aplicado y marcado «Pendiente de
 * sincronizar» (ADR-019 §1). Sin señal, se enseña la última copia leída, con su hora.
 *
 * El servidor agrupa los documentos por parada; los de un pedido que no casó con ninguna llegan
 * en un último grupo sin parada, y se enseñan aparte en lugar de esconderse.
 */
export function PortalTripPage() {
  const { tripId = "" } = useParams();
  const navigate = useNavigate();
  const { selected } = useCompany();
  const { entries } = usePortalOffline();
  const companyId = selected?.id ?? "";
  const [openDocumentId, setOpenDocumentId] = useState<string | null>(null);

  const tripQuery = usePortalQuery({
    queryKey: portalTripKey(companyId, tripId),
    companyId,
    snapshotKey: `trip:${tripId}`,
    fetcher: (signal) => fetchPortalTrip(companyId, tripId, signal),
    enabled: companyId !== "" && tripId !== "",
  });

  const served = tripQuery.data;
  const detail = useMemo(
    () => (served ? applyOutboxOverlay(served.data, entries.filter((entry) => entry.companyId === companyId)) : null),
    [served, entries, companyId],
  );
  const documents = useMemo(() => detail?.stops.flatMap((stop) => stop.documents) ?? [], [detail]);
  const openDocument = openDocumentId === null ? null : documents.find((doc) => doc.documentId === openDocumentId) ?? null;
  const nextPending = detail ? nextPendingDocument(detail) : null;

  function saved(outcome: SaveOutcome, document: { documentId: string }) {
    const next = detail ? nextPendingDocument(detail, document.documentId) : null;
    const message = outcome === "queued"
      ? t("Guardado sin conexión. Se enviará al recuperar señal.")
      : t("Reporte enviado");
    const severity = outcome === "queued" ? "info" : "success";
    if (next) {
      notifyWithAction(message, { label: t("Siguiente documento"), onClick: () => setOpenDocumentId(next.documentId) }, severity);
    } else {
      toast(message, severity, outcome === "sent" ? t("No quedan documentos pendientes en este viaje.") : undefined);
    }
  }

  const trip = detail?.trip;
  const reported = trip ? trip.documentCount - trip.pendingCount : 0;
  const progress = trip && trip.documentCount > 0 ? Math.round((reported / trip.documentCount) * 100) : 0;

  return (
    <Box sx={PORTAL_PAGE_GRID}>
      {/* Cabecera fija bajo la barra: de qué viaje se trata y cuánto falta, siempre a la vista. */}
      <Box sx={{
        position: "sticky", top: `calc(${SAFE_AREA.top} + ${PORTAL_BAR_HEIGHT}px)`, zIndex: 2,
        mx: -2, mt: -2, px: 2, pt: 1, pb: 1.25, bgcolor: "background.default",
        borderBottom: "1px solid", borderColor: "divider",
      }}>
        <Box sx={{ display: "flex", alignItems: "center", gap: 0.5 }}>
          <IconButton onClick={() => navigate("/portal")} aria-label={t("Volver")} edge="start" sx={{ width: 48, height: 48 }}>
            <ArrowBackRounded />
          </IconButton>
          <Box sx={{ flex: 1, minWidth: 0 }}>
            <Typography component="h1" noWrap sx={{ fontWeight: 800, fontSize: 20, lineHeight: 1.2 }}>
              {trip?.shipmentNumber ?? t("Viaje")}
            </Typography>
            {trip && (
              <Typography color="text.secondary" sx={{ fontSize: 14, fontWeight: 600, fontVariantNumeric: "tabular-nums" }}>
                {t("{{done}} de {{total}} reportados", { done: reported, total: trip.documentCount })}
                {trip.pendingCount > 0 && ` · ${t("{{n}} pendientes", { n: trip.pendingCount })}`}
              </Typography>
            )}
          </Box>
          <IconButton onClick={() => void tripQuery.refetch()} aria-label={t("Recargar")} disabled={tripQuery.isFetching} sx={{ width: 48, height: 48 }}>
            {tripQuery.isFetching && !tripQuery.isPending ? <CircularProgress size={22} /> : <RefreshRounded />}
          </IconButton>
        </Box>
        {trip && (
          <LinearProgress
            variant="determinate" value={progress} color={progress === 100 ? "success" : "primary"}
            aria-label={t("Documentos reportados")}
            sx={{ height: 8, borderRadius: 8, mt: 1 }}
          />
        )}
      </Box>

      {tripQuery.isPending ? (
        <Box role="status" aria-label={t("Cargando viaje...")} sx={{ display: "grid", gap: 1.5 }}>
          <Skeleton variant="rounded" height={88} sx={{ borderRadius: `${R.lg}px` }} />
          {[0, 1, 2].map((i) => <Skeleton key={i} variant="rounded" height={96} sx={{ borderRadius: `${R.md}px` }} />)}
        </Box>
      ) : tripQuery.isError || !detail || !trip ? (
        <ErrorState message={describeLoadError(tripQuery.error)} onRetry={() => void tripQuery.refetch()} />
      ) : (
        <>
          <OfflineBanner savedAt={served?.savedAt ?? null} />

          <Paper variant="outlined" sx={{ p: 1.5, borderRadius: `${R.lg}px`, display: "grid", gap: 0.5 }}>
            <InfoLine icon={<ScheduleRounded />}>
              {trip.dispatchedAt ? t("Despachado {{when}}", { when: fmtDateTime(trip.dispatchedAt) }) : t("Despacho sin hora")}
            </InfoLine>
            <InfoLine icon={<LocalShippingRounded />}>{trip.vehicleLicensePlate ?? t("Sin placa")}</InfoLine>
            <InfoLine icon={<PersonRounded />}>{trip.driverName ?? t("Sin conductor")}</InfoLine>
            {trip.originName && <InfoLine icon={<TripOriginRounded />}>{trip.originName}</InfoLine>}
          </Paper>

          {!detail.evidenceEnabled && (
            <Alert severity="warning" sx={{ fontSize: 15 }}>
              {t("Este sistema no tiene dónde guardar fotos: por ahora solo se pueden reportar rechazos y no entregas.")}
            </Alert>
          )}

          {detail.stops.length === 0 ? (
            <EmptyState title={t("Sin documentos")} message={t("Este viaje no lleva documentos que reportar.")} />
          ) : (
            <Box component="ol" aria-label={t("Paradas")} sx={{ listStyle: "none", m: 0, p: 0 }}>
              {detail.stops.map((stop, index) => (
                <StopStep
                  key={stop.stopId ?? `unassigned-${index}`}
                  stop={stop}
                  last={index === detail.stops.length - 1}
                  onOpen={(doc) => setOpenDocumentId(doc.documentId)}
                />
              ))}
            </Box>
          )}
        </>
      )}

      {nextPending && !openDocument && (
        <Fab
          variant="extended" color="primary" onClick={() => setOpenDocumentId(nextPending.documentId)}
          aria-label={t("Siguiente pendiente: {{doc}}", { doc: `${logisticsDocumentLabel(nextPending.documentType, nextPending.fiscal, true)} ${nextPending.documentNumber}` })}
          sx={{
            position: "fixed", zIndex: 3, right: `calc(${SAFE_AREA.right} + 16px)`, bottom: `calc(${SAFE_AREA.bottom} + 20px)`,
            height: 56, px: 2.5, fontSize: 17, fontWeight: 800, textTransform: "none", gap: 1,
          }}
        >
          <SkipNextRounded />
          {t("Siguiente pendiente")}
        </Fab>
      )}

      {openDocument && detail && (
        <DocumentReportDrawer
          key={openDocument.documentId}
          companyId={companyId}
          tripId={tripId}
          shipmentNumber={detail.trip.shipmentNumber}
          document={openDocument}
          evidenceEnabled={detail.evidenceEnabled}
          onClose={() => setOpenDocumentId(null)}
          onSaved={saved}
        />
      )}
    </Box>
  );
}

function InfoLine({ icon, children }: { icon: ReactNode; children: ReactNode }) {
  return (
    <Typography sx={{ display: "flex", alignItems: "center", gap: 1, fontSize: 15, "& svg": { fontSize: 19, color: "text.secondary" } }}>
      {icon}
      {children}
    </Typography>
  );
}

function StopStep({ stop, last, onOpen }: { stop: ShownStop; last: boolean; onOpen: (doc: ShownDocument) => void }) {
  const unassigned = stop.stopId === null;
  const title = unassigned
    ? t("Documentos sin parada")
    : stop.destinationName ?? (stop.sequence !== null ? t("Parada {{n}}", { n: stop.sequence }) : t("Parada"));
  const done = stop.documents.filter((doc) => doc.status !== "PENDING").length;
  const complete = stop.documents.length > 0 && done === stop.documents.length;
  const navigation = unassigned ? null : directionsUrl(stop);

  return (
    <Box component="li" aria-label={title} sx={{ display: "grid", gridTemplateColumns: "40px minmax(0, 1fr)", columnGap: 1.25 }}>
      {/* El riel de la línea de tiempo: el número de la parada y el trazo hasta la siguiente. */}
      <Box aria-hidden sx={{ display: "flex", flexDirection: "column", alignItems: "center" }}>
        <Box sx={{
          width: 36, height: 36, borderRadius: "50%", flexShrink: 0, display: "grid", placeItems: "center",
          bgcolor: complete ? "success.main" : unassigned ? "action.disabledBackground" : "primary.main",
          color: complete ? "success.contrastText" : unassigned ? "text.primary" : "primary.contrastText",
          fontWeight: 800, fontSize: 16,
        }}>
          {stop.sequence ?? <PlaceRounded sx={{ fontSize: 20 }} />}
        </Box>
        {!last && <Box sx={{ flex: 1, width: 3, borderRadius: 2, bgcolor: complete ? "success.main" : "divider", my: 0.5 }} />}
      </Box>

      <Box sx={{ pb: last ? 0 : 3, display: "grid", gap: 1, minWidth: 0 }}>
        <Box sx={{ minHeight: 36, display: "grid", alignContent: "center" }}>
          {!unassigned && stop.sequence !== null && stop.destinationName && (
            <Typography color="text.secondary" sx={{ fontSize: 13, fontWeight: 700, textTransform: "uppercase", letterSpacing: ".06em" }}>
              {t("Parada {{n}}", { n: stop.sequence })}
            </Typography>
          )}
          <Typography component="h2" sx={{ fontWeight: 800, fontSize: 18, lineHeight: 1.25 }}>{title}</Typography>
          {unassigned ? (
            <Typography color="text.secondary" sx={{ fontSize: 14 }}>
              {t("Su pedido no coincide con ninguna parada del viaje.")}
            </Typography>
          ) : stop.address && (
            <Typography color="text.secondary" sx={{ fontSize: 15 }}>{stop.address}</Typography>
          )}
        </Box>

        <Box sx={{ display: "flex", alignItems: "center", gap: 1, flexWrap: "wrap" }}>
          <Typography sx={{ fontSize: 14, fontWeight: 700, color: complete ? "success.main" : "text.secondary", fontVariantNumeric: "tabular-nums" }}>
            {t("{{done}} de {{total}} reportados", { done, total: stop.documents.length })}
          </Typography>
          {navigation && (
            <Button
              component="a" href={navigation} target="_blank" rel="noopener noreferrer"
              variant="outlined" startIcon={<DirectionsRounded />}
              aria-label={t("Cómo llegar a {{place}}", { place: title })}
              sx={{ ml: "auto", minHeight: 44, fontWeight: 800, fontSize: 15, borderRadius: `${R.pill}px` }}
            >
              {t("Cómo llegar")}
            </Button>
          )}
        </Box>

        <Box sx={{ display: "grid", gap: 1 }}>
          {stop.documents.map((doc) => <DocumentRow key={doc.documentId} doc={doc} onOpen={() => onOpen(doc)} />)}
        </Box>
      </Box>
    </Box>
  );
}

function DocumentRow({ doc, onOpen }: { doc: ShownDocument; onOpen: () => void }) {
  const typeLabel = logisticsDocumentLabel(doc.documentType, doc.fiscal, true);
  const closed = !doc.editable;
  // ADR-028: la clave de acceso de una guía ecuatoriana, a mano para un control en carretera. Fuera
  // del ButtonBase: un botón de copiar dentro de otro botón no se puede pulsar solo.
  const authorization = doc.fiscal?.authorizationNumber ?? null;
  const authorizationName = authorizationLabel(doc.fiscal?.fiscalCountry);

  async function copyAuthorization(value: string) {
    try {
      await navigator.clipboard.writeText(value);
      notifySuccess(t("Copiado al portapapeles"));
    } catch {
      notifyError(t("No se pudo copiar"));
    }
  }
  return (
    <Paper variant="outlined" sx={{
      borderRadius: `${R.md}px`, overflow: "hidden",
      borderLeft: "5px solid", borderLeftColor: doc.status === "PENDING" ? "divider"
        : doc.status === "DELIVERED" ? "success.main" : doc.status === "REJECTED" ? "error.main" : "warning.main",
    }}>
      <ButtonBase
        onClick={onOpen}
        aria-label={`${typeLabel} ${doc.documentNumber}`}
        sx={{ width: "100%", minHeight: 72, textAlign: "left", display: "flex", alignItems: "center", gap: 1, px: 1.5, py: 1.25 }}
      >
        <Box sx={{ flex: 1, minWidth: 0, display: "grid", gap: 0.4 }}>
          <Box sx={{ display: "flex", alignItems: "baseline", gap: 1, flexWrap: "wrap" }}>
            <Typography sx={{ fontWeight: 800, fontSize: 16 }}>{typeLabel}</Typography>
            <Typography sx={{ fontWeight: 700, fontSize: 16, fontVariantNumeric: "tabular-nums" }}>{doc.documentNumber}</Typography>
          </Box>
          {doc.customerName && <Typography sx={{ fontSize: 15 }}>{doc.customerName}</Typography>}
          {doc.orderNumbers.length > 0 && (
            <Typography color="text.secondary" sx={{ fontSize: 14 }}>
              {t("Pedidos")}: {doc.orderNumbers.join(", ")}
            </Typography>
          )}
          <Box sx={{ display: "flex", gap: 0.75, alignItems: "center", flexWrap: "wrap", mt: 0.5 }}>
            <DocumentStatusChip status={doc.status} />
            {doc.pendingSync && <StatusChip tone="inProgress" icon={<CloudUploadRounded />} label={t("Pendiente de sincronizar")} />}
            {doc.syncFailed && <StatusChip tone="overdue" icon={<ErrorOutlineRounded />} label={t("No se pudo sincronizar")} />}
            {doc.reasonCode && (
              <Typography color="text.secondary" sx={{ fontSize: 14 }}>{enumLabel("documentDeliveryReason", doc.reasonCode)}</Typography>
            )}
            {doc.evidence.length > 0 && (
              <Typography color="text.secondary" sx={{ fontSize: 14, display: "flex", alignItems: "center", gap: 0.25 }}
                aria-label={t("{{n}} fotos o firmas", { n: doc.evidence.length })}>
                <PhotoCameraRounded sx={{ fontSize: 16 }} /> {doc.evidence.length}
              </Typography>
            )}
          </Box>
          {closed && (
            <Typography sx={{ fontSize: 14, fontWeight: 700, color: "text.secondary", display: "flex", alignItems: "center", gap: 0.5, mt: 0.25 }}>
              <LockRounded sx={{ fontSize: 17 }} /> {t("Cerrado por oficina")}
            </Typography>
          )}
        </Box>
        {closed ? <LockRounded color="disabled" /> : <ChevronRightRounded color="action" />}
      </ButtonBase>
      {authorization && (
        <Box sx={{ display: "flex", alignItems: "center", gap: 0.5, px: 1.5, pb: 1, borderTop: "1px dashed", borderColor: "divider", pt: 0.75 }}>
          <Box sx={{ flex: 1, minWidth: 0 }}>
            <Typography color="text.secondary" sx={{ fontSize: 13, fontWeight: 700 }}>{authorizationName}</Typography>
            <Typography sx={{ fontFamily: "monospace", fontSize: 13, wordBreak: "break-all" }}>{authorization}</Typography>
          </Box>
          <IconButton
            aria-label={t("Copiar {{what}}", { what: authorizationName.toLowerCase() })}
            onClick={() => void copyAuthorization(authorization)}
            sx={{ width: 44, height: 44 }}
          >
            <ContentCopyRounded />
          </IconButton>
        </Box>
      )}
    </Paper>
  );
}
