import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Link } from "react-router-dom";
import { Alert, Box, Button, Divider, Typography } from "@mui/material";
import {
  AssignmentReturnedRounded, DescriptionRounded, OpenInNewRounded, ReportProblemRounded,
} from "@mui/icons-material";
import {
  canConfirmReturn, confirmDocumentReturn, downloadDocumentDeliveryEvidence, fetchDocumentDelivery,
  type DocumentDeliveryDetail, type DocumentDeliveryEvent, type DocumentDeliveryRow, type DocumentInconsistency,
} from "../../shared/api/documentDeliveriesApi";
import type { ApiError } from "../../shared/api/httpClient";
import { describeApiError } from "../../shared/api/problemMessages";
import { DocumentStatusChip } from "../../shared/ui/DocumentStatusChip";
import { EvidenceGallery } from "../../shared/ui/EvidenceGallery";
import { FormDrawer, LoadingState, StatusChip } from "../../shared/ui/components";
import { DetailSection, KeyFacts, VerdictBanner } from "../../shared/ui/components/DetailLayout";
import { enumLabel } from "../../lib/enums";
import { t } from "../../lib/i18n";
import { fmtDateTime } from "../../lib/locale";
import { notifyError, notifySuccess, promptDialog } from "../../lib/ui";
import { R } from "../../theme";
import { documentDeliveriesKey, documentDeliveryDetailKey } from "./documentDeliveries";

/** Qué significa cada desacuerdo, en palabras de quien tiene que resolverlo. */
const INCONSISTENCY_TEXT: Record<DocumentInconsistency, string> = {
  DOCUMENT_DELIVERED_GOODS_FAILED:
    "El transportista reportó el documento como entregado, pero la entrega de la mercadería de su pedido en este viaje se registró como fallida o rechazada. Uno de los dos registros no es correcto: confírmalo con el transportista.",
  GOODS_DELIVERED_DOCUMENT_NOT:
    "La mercadería del pedido figura entregada en este viaje, pero el documento no se reportó como entregado. Puede faltar el reporte, o el documento no llegó al cliente.",
};

interface DocumentDeliveryDrawerProps {
  companyId: string;
  row: DocumentDeliveryRow;
  canManage: boolean;
  onClose: () => void;
}

/**
 * El detalle de un documento en un viaje: qué reportó el transportista y cuándo, con qué fotos y
 * firma, el historial completo de reportes y correcciones, y si el cargo firmado ya volvió.
 *
 * El único acto de la oficina aquí es confirmar el cargo (ADR-018): nunca lo hace el
 * transportista, y una vez confirmado su reporte queda cerrado. El desacuerdo con la entrega de
 * la mercadería se explica, pero no se resuelve desde aquí: nada de esto mueve un pedido.
 */
export function DocumentDeliveryDrawer({ companyId, row, canManage, onClose }: DocumentDeliveryDrawerProps) {
  const queryClient = useQueryClient();
  const [busy, setBusy] = useState(false);

  const detailQuery = useQuery({
    queryKey: documentDeliveryDetailKey(companyId, row.id),
    queryFn: ({ signal }) => fetchDocumentDelivery(companyId, row.id, signal),
  });

  const delivery = detailQuery.data?.delivery ?? row;
  const typeLabel = enumLabel("deliveryDocumentType", delivery.documentType);
  const returnable = canManage && canConfirmReturn(delivery);

  async function confirmReturn() {
    if (busy) return;
    const remark = await promptDialog({
      title: t("¿Confirmar cargo recibido?"),
      text: t("Confirma que el cargo firmado de {{doc}} volvió a la oficina. Después, el transportista ya no podrá cambiar su reporte.",
        { doc: `${typeLabel} ${delivery.documentNumber}` }),
      inputLabel: t("Observación (opcional)"),
      maxLength: 500,
      confirmLabel: t("Confirmar cargo"),
    });
    if (remark === null) return;
    setBusy(true);
    try {
      const updated = await confirmDocumentReturn(companyId, row.id, remark || null);
      queryClient.setQueryData<DocumentDeliveryDetail>(documentDeliveryDetailKey(companyId, row.id), updated);
      void queryClient.invalidateQueries({ queryKey: documentDeliveriesKey(companyId) });
      notifySuccess(t("Cargo recibido"), `${typeLabel} ${delivery.documentNumber}`);
    } catch (error) {
      notifyError(t("No se pudo confirmar el cargo"), describeApiError(error as ApiError));
    } finally {
      setBusy(false);
    }
  }

  return (
    <FormDrawer
      open
      size="md"
      icon={<DescriptionRounded />}
      title={`${typeLabel} ${delivery.documentNumber}`}
      subtitle={[delivery.shipmentNumber, delivery.carrierName, delivery.customerName].filter(Boolean).join(" · ")}
      titleAdornment={<DocumentStatusChip status={delivery.status} />}
      onClose={onClose}
      footer={
        <>
          <Button variant="outlined" color="inherit" sx={{ borderColor: "divider" }} component={Link} to={`/trips/${delivery.tripId}`}
            startIcon={<OpenInNewRounded />}>
            {t("Abrir viaje")}
          </Button>
          {returnable && (
            <Button variant="contained" startIcon={<AssignmentReturnedRounded />} onClick={() => void confirmReturn()} disabled={busy}>
              {busy ? t("Guardando...") : t("Confirmar cargo recibido")}
            </Button>
          )}
        </>
      }
    >
      <Box sx={{ display: "grid", gap: 2.5 }}>
        {delivery.inconsistency && (
          <VerdictBanner
            tone="warning"
            icon={<ReportProblemRounded />}
            title={enumLabel("documentInconsistency", delivery.inconsistency)}
            message={t(INCONSISTENCY_TEXT[delivery.inconsistency])}
          />
        )}

        <KeyFacts columns={3} items={[
          { label: t("Envío"), value: delivery.shipmentNumber },
          { label: t("Despachado"), value: delivery.dispatchedAt ? fmtDateTime(delivery.dispatchedAt) : null },
          { label: t("Vehículo"), value: [delivery.vehicleLicensePlate, delivery.driverName].filter(Boolean).join(" · ") || null },
          { label: t("Pedidos"), value: delivery.orderNumbers.join(", ") || null },
          { label: t("Cliente"), value: delivery.customerName },
          { label: t("Motivo"), value: delivery.reasonCode ? enumLabel("documentDeliveryReason", delivery.reasonCode) : null },
          { label: t("Ocurrió"), value: delivery.reportedAt ? fmtDateTime(delivery.reportedAt) : null },
          { label: t("Reportado por"), value: delivery.recordedByName },
          {
            label: t("Cargo"),
            value: delivery.returnedAt
              ? `${fmtDateTime(delivery.returnedAt)}${delivery.returnedByName ? ` · ${delivery.returnedByName}` : ""}`
              : delivery.status === "PENDING" ? t("Sin reporte") : t("Por recibir"),
          },
        ]} />

        {delivery.remark && (
          <Alert severity="info" icon={false}>
            <strong>{t("Observación")}:</strong> {delivery.remark}
          </Alert>
        )}

        {detailQuery.isPending ? (
          <LoadingState minHeight={120} />
        ) : detailQuery.isError || !detailQuery.data ? (
          <Alert severity="error">{describeApiError(detailQuery.error as ApiError)}</Alert>
        ) : (
          <>
            <DetailSection title={t("Fotos y firma")}>
              <EvidenceGallery
                items={detailQuery.data.evidence.map((e) => ({ ...e, fileName: e.originalFilename }))}
                queryKey={["document-delivery-evidence", companyId, row.id]}
                load={(evidenceId, signal) => downloadDocumentDeliveryEvidence(companyId, row.id, evidenceId, signal)}
              />
            </DetailSection>

            <DetailSection title={t("Historial")}>
              {detailQuery.data.events.length === 0 ? (
                <Typography variant="body2" color="text.secondary">{t("El transportista todavía no reportó este documento.")}</Typography>
              ) : (
                <Box sx={{ border: "1px solid", borderColor: "divider", borderRadius: `${R.md}px`, overflow: "hidden" }}>
                  {detailQuery.data.events.map((event, index) => (
                    <Box key={event.id}>
                      {index > 0 && <Divider />}
                      <EventRow event={event} />
                    </Box>
                  ))}
                </Box>
              )}
            </DetailSection>
          </>
        )}
      </Box>
    </FormDrawer>
  );
}

function EventRow({ event }: { event: DocumentDeliveryEvent }) {
  return (
    <Box sx={{ px: 1.5, py: 1, display: "grid", gap: 0.5 }}>
      <Box sx={{ display: "flex", alignItems: "center", gap: 1, flexWrap: "wrap" }}>
        <Typography variant="body2" sx={{ fontWeight: 800 }}>{enumLabel("documentDeliveryEventType", event.eventType)}</Typography>
        {event.status && <DocumentStatusChip status={event.status} />}
        {event.eventType === "RETURNED" && <StatusChip tone="done" label={t("Cargo recibido")} />}
        <Typography variant="caption" color="text.secondary" sx={{ ml: "auto" }}>{fmtDateTime(event.recordedAt)}</Typography>
      </Box>
      <Typography variant="caption" color="text.secondary">
        {[
          event.actorName,
          event.reasonCode ? enumLabel("documentDeliveryReason", event.reasonCode) : null,
          event.reportedAt ? t("ocurrió {{when}}", { when: fmtDateTime(event.reportedAt) }) : null,
        ].filter(Boolean).join(" · ")}
      </Typography>
      {event.remark && <Typography variant="body2">{event.remark}</Typography>}
    </Box>
  );
}
