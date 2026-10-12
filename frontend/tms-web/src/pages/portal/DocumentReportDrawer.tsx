import { useQueryClient } from "@tanstack/react-query";
import { useRef, useState, type ReactNode } from "react";
import {
  Alert, Box, Button, CircularProgress, Drawer, FormControlLabel, IconButton, Radio, RadioGroup, TextField,
  ToggleButton, ToggleButtonGroup, Typography, useMediaQuery, useTheme,
} from "@mui/material";
import {
  BlockRounded, CheckCircleRounded, CloseRounded, CloudOffRounded, CloudUploadRounded, DoNotDisturbOnRounded,
  DrawRounded, LockRounded, SaveRounded,
} from "@mui/icons-material";
import {
  DOCUMENT_DELIVERY_REASONS, type DocumentDeliveryReason,
} from "../../shared/api/documentDeliveriesApi";
import {
  MAX_REPORT_PHOTOS, downloadPortalEvidence, reportPortalDocument,
  type PortalDocument, type PortalTripDetail,
} from "../../shared/api/portalApi";
import { DocumentStatusChip } from "../../shared/ui/DocumentStatusChip";
import { EvidenceGallery } from "../../shared/ui/EvidenceGallery";
import { PhotoPicker, type PickedPhoto } from "../../shared/ui/PhotoPicker";
import { SignaturePad } from "../../shared/ui/SignaturePad";
import { StatusChip } from "../../shared/ui/components";
import { enumLabel } from "../../lib/enums";
import { t } from "../../lib/i18n";
import { fmtDateTime } from "../../lib/locale";
import { confirmDialog } from "../../lib/ui";
import { R } from "../../theme";
import { usePortalOffline } from "./offline/offlineContext";
import { classifySendError, haptic } from "./offline/network";
import type { Served } from "./offline/usePortalQuery";
import {
  REPORT_STATUSES, describeReportError, needsReason, portalTripKey, portalTripsKey, validateReport,
  type ReportErrors, type ReportStatus,
} from "./portalReport";
import { READABLE_INPUTS, SAFE_AREA, TOUCH_TARGET } from "./portalUi";

const FORM_ID = "portal-document-report";

const CHOICES: Record<ReportStatus, { label: string; save: string; icon: ReactNode; color: "success" | "error" | "warning" }> = {
  DELIVERED: { label: "Entregado", save: "Guardar entrega", icon: <CheckCircleRounded />, color: "success" },
  REJECTED: { label: "Rechazado", save: "Guardar rechazo", icon: <BlockRounded />, color: "error" },
  NOT_DELIVERED: { label: "No entregado", save: "Guardar no entrega", icon: <DoNotDisturbOnRounded />, color: "warning" },
};

/** Lo que el panel sabe del documento: el del servidor, más la marca de la cola si la hay. */
type DrawerDocument = PortalDocument & { pendingSync?: boolean };

/** Cómo terminó un guardado: enviado al servidor, o en la cola del teléfono. */
export type SaveOutcome = "sent" | "queued";

interface DocumentReportDrawerProps {
  companyId: string;
  tripId: string;
  shipmentNumber?: string | null;
  document: DrawerDocument;
  evidenceEnabled: boolean;
  onClose: () => void;
  /** Tras guardar (el panel ya no hace falta): quien lo abrió ofrece el siguiente documento. */
  onSaved?: (outcome: SaveOutcome, document: DrawerDocument) => void;
}

/**
 * Reportar qué pasó con un documento: entregado, rechazado o no entregado (ADR-018, ADR-019 §1).
 *
 * En el teléfono es una hoja a pantalla casi completa, con tres botones enormes, los motivos como
 * una lista de opciones grandes y una sola acción fija abajo. Pensado para la puerta del cliente:
 * una mano, a veces guantes, a veces sol.
 *
 * Una clave de idempotencia por apertura del panel, creada al montarlo y reutilizada en cada
 * reintento del mismo reporte; la hora del hecho (`reportedAt`) se fija al tocar «Guardar» la
 * primera vez. Las dos viajan con el reporte si va a la cola: se envíe cuando se envíe, cuenta
 * cuándo pasó y no se registra dos veces.
 *
 * Sin red —o si el envío se queda sin respuesta— el reporte se guarda en el teléfono y el panel
 * se cierra: la cola lo enviará sola. Si ya hay un reporte de este documento esperando en la
 * cola, el nuevo va detrás de él aunque haya red, para que la corrección no llegue antes que lo
 * que corrige.
 */
export function DocumentReportDrawer({
  companyId, tripId, shipmentNumber = null, document, evidenceEnabled, onClose, onSaved,
}: DocumentReportDrawerProps) {
  const queryClient = useQueryClient();
  const offline = usePortalOffline();
  const theme = useTheme();
  const wide = useMediaQuery(theme.breakpoints.up("sm"));
  const [idempotencyKey] = useState(() => crypto.randomUUID());
  const reportedAtRef = useRef<string | null>(null);

  const initialStatus = document.status === "PENDING" ? null : document.status;
  const [status, setStatus] = useState<ReportStatus | null>(initialStatus);
  const [reasonCode, setReasonCode] = useState<DocumentDeliveryReason | "">(document.reasonCode ?? "");
  const [remark, setRemark] = useState(document.remark ?? "");
  const [photos, setPhotos] = useState<PickedPhoto[]>([]);
  const [signature, setSignature] = useState<Blob | null>(null);
  const [signing, setSigning] = useState(false);
  const [errors, setErrors] = useState<ReportErrors>({});
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const editable = document.editable;
  const typeLabel = enumLabel("deliveryDocumentType", document.documentType);
  const title = `${typeLabel} ${document.documentNumber}`;
  const dirty = status !== initialStatus || reasonCode !== (document.reasonCode ?? "")
    || remark !== (document.remark ?? "") || photos.length > 0 || signature !== null;

  function requestClose() {
    if (submitting) return;
    if (!editable || !dirty) { onClose(); return; }
    void confirmDialog({
      title: t("¿Descartar cambios?"),
      text: t("Tienes cambios sin guardar."),
      confirmLabel: t("Descartar"),
      cancelLabel: t("Seguir editando"),
      dangerous: true,
    }).then((confirmed) => { if (confirmed) onClose(); });
  }

  function choose(next: ReportStatus | null) {
    if (next === null) return;
    haptic(15);
    setStatus(next);
    setErrors({});
    if (!needsReason(next)) setReasonCode("");
    if (next !== "DELIVERED") { setSigning(false); setSignature(null); }
  }

  function showFirstError(found: ReportErrors) {
    haptic([80, 50, 80]);
    const first = (["status", "reasonCode", "remark", "photos"] as const).find((field) => found[field]);
    if (first) globalThis.document.getElementById(`report-${first}`)?.scrollIntoView?.({ block: "center", behavior: "smooth" });
  }

  async function submit() {
    if (submitting) return;
    const values = { status, reasonCode, remark, photos: photos.map((p) => p.file), signature };
    const found = validateReport(values, { evidenceEnabled });
    setErrors(found);
    setSubmitError(null);
    if (Object.keys(found).length > 0 || status === null) { showFirstError(found); return; }

    // El momento en que tocó «Guardar» en la puerta del cliente, fijado una vez.
    reportedAtRef.current ??= new Date().toISOString();
    const report = {
      status,
      reasonCode: needsReason(status) ? reasonCode || null : null,
      remark: remark.trim() || null,
      reportedAt: reportedAtRef.current,
      photos: evidenceEnabled ? values.photos : [],
      signature: evidenceEnabled && status === "DELIVERED" ? signature : null,
    };

    const queue = async () => {
      try {
        await offline.enqueue({
          companyId, tripId, documentId: document.documentId, idempotencyKey,
          status: report.status, reasonCode: report.reasonCode, remark: report.remark, reportedAt: report.reportedAt,
          photos: report.photos, signature: report.signature,
          documentType: document.documentType, documentNumber: document.documentNumber,
          customerName: document.customerName, shipmentNumber,
        });
      } catch {
        setSubmitError(t("No se pudo guardar en el teléfono. Libera espacio o vuelve a intentarlo con señal."));
        return;
      }
      haptic([30, 40, 30]);
      void offline.sync();
      onSaved?.("queued", document);
      onClose();
    };

    setSubmitting(true);
    try {
      if (!offline.online || offline.hasQueued(tripId, document.documentId)) {
        await queue();
        return;
      }
      try {
        const reported = await reportPortalDocument(companyId, tripId, document.documentId, report, idempotencyKey);
        queryClient.setQueryData<Served<PortalTripDetail>>(portalTripKey(companyId, tripId), (previous) => previous && {
          ...previous,
          data: {
            ...previous.data,
            stops: previous.data.stops.map((stop) => ({
              ...stop,
              documents: stop.documents.map((doc) => (doc.documentId === reported.documentId ? reported : doc)),
            })),
          },
        });
        void queryClient.invalidateQueries({ queryKey: portalTripsKey(companyId) });
        void queryClient.invalidateQueries({ queryKey: portalTripKey(companyId, tripId) });
        haptic([30, 40, 30]);
        onSaved?.("sent", document);
        onClose();
      } catch (error) {
        const failure = classifySendError(error);
        // Sin respuesta o el servidor caído: a la cola, con la misma clave y la misma hora.
        if (failure === "network" || failure === "server") { await queue(); return; }
        haptic([80, 50, 80]);
        setSubmitError(describeReportError(error));
      }
    } finally {
      setSubmitting(false);
    }
  }

  const reasonVisible = needsReason(status);
  const saveLabel = submitting
    ? t("Guardando...")
    : !offline.online
      ? t("Guardar sin conexión")
      : status ? t(CHOICES[status].save) : t("Guardar");

  return (
    <Drawer
      anchor={wide ? "right" : "bottom"}
      open
      onClose={(_event, reason) => { if (reason !== "backdropClick" || !submitting) requestClose(); }}
      sx={{ zIndex: (th) => th.zIndex.drawer + 2 }}
      slotProps={{
        paper: {
          role: "dialog",
          "aria-labelledby": "report-title",
          sx: {
            display: "flex", flexDirection: "column", backgroundImage: "none", ...READABLE_INPUTS,
            ...(wide
              ? { width: 480, maxWidth: "100%", height: "100%" }
              : {
                height: `calc(100dvh - ${SAFE_AREA.top} - 12px)`,
                borderTopLeftRadius: `${R.xl}px`, borderTopRightRadius: `${R.xl}px`,
                pl: SAFE_AREA.left, pr: SAFE_AREA.right,
              }),
          },
        },
      }}
    >
      {!wide && <Box aria-hidden sx={{ width: 40, height: 5, borderRadius: 3, bgcolor: "divider", mx: "auto", mt: 1, flexShrink: 0 }} />}
      <Box sx={{ display: "flex", alignItems: "flex-start", gap: 1, px: 2, pt: 1.25, pb: 1.5, borderBottom: "1px solid", borderColor: "divider", flexShrink: 0 }}>
        <Box sx={{ flex: 1, minWidth: 0 }}>
          <Typography id="report-title" component="h2" sx={{ fontWeight: 800, fontSize: 19, lineHeight: 1.25 }}>{title}</Typography>
          {(document.customerName || document.orderNumbers.length > 0) && (
            <Typography color="text.secondary" sx={{ fontSize: 15, mt: 0.25 }}>
              {[document.customerName, document.orderNumbers.join(", ")].filter(Boolean).join(" · ")}
            </Typography>
          )}
          <Box sx={{ display: "flex", gap: 0.75, flexWrap: "wrap", mt: 0.75 }}>
            <DocumentStatusChip status={document.status} />
            {document.pendingSync && (
              <StatusChip tone="inProgress" icon={<CloudUploadRounded />} label={t("Pendiente de sincronizar")} />
            )}
          </Box>
        </Box>
        <IconButton onClick={requestClose} aria-label={t("Cerrar")} disabled={submitting} sx={{ width: 48, height: 48, mt: -0.5 }}>
          <CloseRounded />
        </IconButton>
      </Box>

      <Box
        component="form" id={FORM_ID} noValidate
        onSubmit={(event) => { event.preventDefault(); void submit(); }}
        sx={{ flex: 1, overflowY: "auto", px: 2, py: 2, display: "grid", alignContent: "start", gap: 3 }}
      >
        {document.status !== "PENDING" && (
          <Box sx={{ display: "grid", gap: 1 }}>
            <Typography color="text.secondary" sx={{ fontSize: 15 }}>
              {t("Último reporte")}: <strong>{enumLabel("documentDeliveryStatus", document.status)}</strong>
              {document.reasonCode && ` · ${enumLabel("documentDeliveryReason", document.reasonCode)}`}
              {document.reportedAt && ` · ${fmtDateTime(document.reportedAt)}`}
            </Typography>
            {document.remark && <Typography sx={{ fontSize: 15 }}>{document.remark}</Typography>}
            {document.evidence.length > 0 && offline.online && (
              <EvidenceGallery
                items={document.evidence}
                queryKey={["portal-evidence", companyId, tripId, document.documentId]}
                load={(evidenceId, signal) => downloadPortalEvidence(companyId, tripId, document.documentId, evidenceId, signal)}
              />
            )}
          </Box>
        )}

        {!editable ? (
          <Alert severity="info" icon={<LockRounded />} sx={{ fontSize: 15 }}>
            <strong>{t("Cerrado por oficina")}.</strong>{" "}
            {t("La oficina ya recibió el cargo de este documento: el reporte ya no se puede cambiar.")}
          </Alert>
        ) : (
          <>
            {!offline.online && (
              <Alert severity="warning" icon={<CloudOffRounded />} sx={{ fontSize: 15 }}>
                {t("Sin conexión. El reporte se guardará en el teléfono y se enviará al recuperar señal.")}
              </Alert>
            )}

            <Box id="report-status">
              <Typography component="h3" sx={{ fontWeight: 800, fontSize: 17, mb: 1.25 }} id="report-status-label">
                {document.status === "PENDING" ? t("¿Qué pasó con el documento?") : t("Corregir el reporte")}
              </Typography>
              <ToggleButtonGroup
                exclusive
                orientation="vertical"
                value={status}
                onChange={(_e, next: ReportStatus | null) => choose(next)}
                aria-labelledby="report-status-label"
                sx={{ display: "grid", gap: 1 }}
              >
                {REPORT_STATUSES.map((value) => {
                  const choice = CHOICES[value];
                  return (
                    <ToggleButton
                      key={value}
                      value={value}
                      disabled={value === "DELIVERED" && !evidenceEnabled}
                      sx={{
                        justifyContent: "flex-start", gap: 1.5, px: 2, minHeight: 64, textTransform: "none",
                        fontWeight: 800, fontSize: 18, lineHeight: 1.2, color: "text.primary",
                        border: "2px solid !important", borderColor: (th) => `${th.palette.divider} !important`,
                        borderRadius: `${R.md}px !important`, m: "0 !important",
                        "& svg": { fontSize: 32, color: `${choice.color}.main` },
                        "&.Mui-selected, &.Mui-selected:hover": {
                          bgcolor: `${choice.color}.main`, color: `${choice.color}.contrastText`,
                          borderColor: (th) => `${th.palette[choice.color].main} !important`,
                          "& svg": { color: "inherit" },
                        },
                      }}
                    >
                      {choice.icon}
                      {t(choice.label)}
                    </ToggleButton>
                  );
                })}
              </ToggleButtonGroup>
              {!evidenceEnabled && (
                <Alert severity="warning" sx={{ mt: 1, fontSize: 15 }}>
                  {t("No se puede marcar «Entregado»: este sistema no tiene dónde guardar la foto obligatoria. Sí puedes reportar un rechazo o una no entrega.")}
                </Alert>
              )}
              {errors.status && <Typography color="error" sx={{ display: "block", mt: 0.75, fontWeight: 700, fontSize: 15 }}>{errors.status}</Typography>}
            </Box>

            {reasonVisible && (
              <Box id="report-reasonCode" role="group" aria-labelledby="report-reason-label">
                <Typography component="h3" id="report-reason-label" sx={{ fontWeight: 800, fontSize: 17, mb: 1 }}>
                  {t("Motivo")}
                </Typography>
                <RadioGroup
                  name="reasonCode"
                  value={reasonCode}
                  onChange={(e) => { haptic(10); setReasonCode(e.target.value as DocumentDeliveryReason); setErrors((x) => ({ ...x, reasonCode: undefined })); }}
                  sx={{ display: "grid", gap: 0.75 }}
                >
                  {DOCUMENT_DELIVERY_REASONS.map((reason) => (
                    <FormControlLabel
                      key={reason}
                      value={reason}
                      control={<Radio />}
                      label={enumLabel("documentDeliveryReason", reason)}
                      sx={{
                        m: 0, minHeight: 52, pr: 1.5, borderRadius: `${R.md}px`, border: "1px solid",
                        borderColor: reasonCode === reason ? "primary.main" : "divider",
                        bgcolor: reasonCode === reason ? "action.selected" : "background.paper",
                        "& .MuiFormControlLabel-label": { fontSize: 16, fontWeight: 600 },
                      }}
                    />
                  ))}
                </RadioGroup>
                {errors.reasonCode && <Typography color="error" sx={{ mt: 0.75, fontWeight: 700, fontSize: 15 }}>{errors.reasonCode}</Typography>}
              </Box>
            )}

            {status !== null && evidenceEnabled && (
              <Box id="report-photos">
                <Typography component="h3" sx={{ fontWeight: 800, fontSize: 17, mb: 1 }}>
                  {status === "DELIVERED" ? t("Fotos (obligatorio)") : t("Fotos (opcional)")}
                </Typography>
                <PhotoPicker
                  value={photos}
                  onChange={(next) => { setPhotos(next); setErrors((x) => ({ ...x, photos: undefined })); }}
                  max={MAX_REPORT_PHOTOS}
                  disabled={submitting}
                  error={errors.photos}
                />
              </Box>
            )}

            {status === "DELIVERED" && evidenceEnabled && (
              <Box>
                {!signing ? (
                  <Button
                    variant="outlined" fullWidth startIcon={<DrawRounded />} onClick={() => setSigning(true)}
                    disabled={submitting} sx={{ minHeight: 52, fontSize: 16, fontWeight: 700 }}
                  >
                    {t("Añadir firma del cliente (opcional)")}
                  </Button>
                ) : (
                  <>
                    <Box sx={{ display: "flex", alignItems: "center", mb: 1 }}>
                      <Typography component="h3" sx={{ fontWeight: 800, fontSize: 17, flex: 1 }}>{t("Firma del cliente (opcional)")}</Typography>
                      <Button onClick={() => { setSigning(false); setSignature(null); }} disabled={submitting}>{t("Quitar firma")}</Button>
                    </Box>
                    <SignaturePad onChange={setSignature} disabled={submitting} height={200} />
                  </>
                )}
              </Box>
            )}

            {status !== null && (
              <Box id="report-remark">
                <TextField
                  fullWidth multiline minRows={2}
                  label={reasonCode === "OTHER" && reasonVisible ? t("Observación (obligatoria)") : t("Observación (opcional)")}
                  value={remark}
                  onChange={(e) => { setRemark(e.target.value); setErrors((x) => ({ ...x, remark: undefined })); }}
                  error={Boolean(errors.remark)}
                  helperText={errors.remark}
                  slotProps={{ htmlInput: { maxLength: 1000 } }}
                />
              </Box>
            )}
          </>
        )}
      </Box>

      <Box sx={{
        flexShrink: 0, borderTop: "1px solid", borderColor: "divider", bgcolor: "background.paper",
        px: 2, pt: 1.5, pb: `calc(${SAFE_AREA.bottom} + 12px)`, display: "grid", gap: 1,
      }}>
        {submitError && <Alert severity="error" sx={{ fontSize: 15 }}>{submitError}</Alert>}
        {editable ? (
          <Button
            type="submit" form={FORM_ID} variant="contained" size="large" fullWidth disabled={submitting}
            color={offline.online ? "primary" : "warning"}
            startIcon={submitting ? <CircularProgress size={22} color="inherit" /> : offline.online ? <SaveRounded /> : <CloudOffRounded />}
            sx={{ minHeight: TOUCH_TARGET, fontSize: 18, fontWeight: 800 }}
          >
            {saveLabel}
          </Button>
        ) : (
          <Button variant="contained" size="large" fullWidth onClick={onClose} sx={{ minHeight: TOUCH_TARGET, fontSize: 18 }}>
            {t("Cerrar")}
          </Button>
        )}
      </Box>
    </Drawer>
  );
}
