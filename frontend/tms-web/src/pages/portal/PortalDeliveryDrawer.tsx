import { useRef, useState, type ReactNode } from "react";
import {
  Alert, Box, Button, CircularProgress, Drawer, IconButton, TextField, ToggleButton, ToggleButtonGroup, Typography,
  useMediaQuery, useTheme,
} from "@mui/material";
import {
  BlockRounded, CheckCircleRounded, CloseRounded, CloudOffRounded, CloudUploadRounded, DoNotDisturbOnRounded,
  DrawRounded, HorizontalRuleRounded, PieChartRounded, SaveRounded,
} from "@mui/icons-material";
import type { DeliveryResult } from "../../shared/api/planningApi";
import { MAX_REPORT_PHOTOS, type PortalDeliveryInput } from "../../shared/api/portalApi";
import { PhotoPicker, type PickedPhoto } from "../../shared/ui/PhotoPicker";
import { SignaturePad } from "../../shared/ui/SignaturePad";
import { StatusChip } from "../../shared/ui/components";
import { DELIVERY_RESULT_TONE } from "../../shared/ui/statusTones";
import { enumLabel } from "../../lib/enums";
import { t } from "../../lib/i18n";
import { fmtQuantity } from "../../lib/locale";
import { confirmDialog } from "../../lib/ui";
import { R } from "../../theme";
import { usePortalOffline } from "./offline/offlineContext";
import { haptic } from "./offline/network";
import type { ShownOrder } from "./offline/overlay";
import {
  ExecutionRejectedError, PORTAL_DELIVERY_RESULTS, allowsReceiver, needsNotes, parseAmount, takesEvidence,
  usePortalExecution, validateDelivery, type DeliveryErrors, type ExecutionOutcome,
} from "./portalExecution";
import { READABLE_INPUTS, SAFE_AREA, TOUCH_TARGET } from "./portalUi";

const FORM_ID = "portal-delivery-record";

const CHOICES: Record<DeliveryResult, { label: string; icon: ReactNode; color: "success" | "error" | "warning" | "info" }> = {
  DELIVERED: { label: "Entregado completo", icon: <CheckCircleRounded />, color: "success" },
  PARTIAL: { label: "Entrega parcial", icon: <PieChartRounded />, color: "info" },
  REJECTED: { label: "Rechazado por el cliente", icon: <BlockRounded />, color: "error" },
  FAILED: { label: "No se pudo entregar", icon: <DoNotDisturbOnRounded />, color: "warning" },
  NOT_ATTEMPTED: { label: "No se intentó (sigue en el camión)", icon: <HorizontalRuleRounded />, color: "info" },
};

interface PortalDeliveryDrawerProps {
  companyId: string;
  tripId: string;
  shipmentNumber: string | null;
  stopId: string;
  stopLabel: string;
  order: ShownOrder;
  evidenceEnabled: boolean;
  onClose: () => void;
  onSaved?: (outcome: ExecutionOutcome) => void;
}

/**
 * Registrar qué pasó con un pedido en una parada (V70): el mismo resultado que registra la
 * oficina —entregado, parcial, rechazado, fallido o no intentado—, con quien recibió, lo que
 * rechazó y, si este sistema guarda evidencias, las fotos y la firma. Todo en un solo envío, para
 * que sin señal quede una sola entrada en la cola.
 *
 * Una clave de idempotencia por apertura del panel y la hora fijada al primer «Guardar»: se envíe
 * cuando se envíe, el historial dice cuándo pasó y no se registra dos veces.
 */
export function PortalDeliveryDrawer({
  companyId, tripId, shipmentNumber, stopId, stopLabel, order, evidenceEnabled, onClose, onSaved,
}: PortalDeliveryDrawerProps) {
  const offline = usePortalOffline();
  const run = usePortalExecution(companyId, tripId, shipmentNumber);
  const theme = useTheme();
  const wide = useMediaQuery(theme.breakpoints.up("sm"));
  const [idempotencyKey] = useState(() => crypto.randomUUID());
  const happenedAt = useRef<string | null>(null);
  const existing = order.delivery;

  const [result, setResult] = useState<DeliveryResult | null>(existing?.result ?? null);
  const [receiverName, setReceiverName] = useState(existing?.receiverName ?? "");
  const [receiverDocument, setReceiverDocument] = useState(existing?.receiverDocument ?? "");
  const [notes, setNotes] = useState(existing?.notes ?? "");
  const [refusedPallets, setRefusedPallets] = useState(existing?.refusedPallets?.toString() ?? "");
  const [refusedWeightKg, setRefusedWeightKg] = useState(existing?.refusedWeightKg?.toString() ?? "");
  const [refusedVolumeM3, setRefusedVolumeM3] = useState(existing?.refusedVolumeM3?.toString() ?? "");
  const [photos, setPhotos] = useState<PickedPhoto[]>([]);
  const [signature, setSignature] = useState<Blob | null>(null);
  const [signing, setSigning] = useState(false);
  const [errors, setErrors] = useState<DeliveryErrors>({});
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const loaded = { weightKg: order.assignedWeightKg, volumeM3: order.assignedVolumeM3, pallets: order.assignedPallets };
  const dirty = result !== (existing?.result ?? null) || photos.length > 0 || signature !== null
    || receiverName !== (existing?.receiverName ?? "") || notes !== (existing?.notes ?? "");

  function requestClose() {
    if (submitting) return;
    if (!dirty) { onClose(); return; }
    void confirmDialog({
      title: t("¿Descartar cambios?"),
      text: t("Tienes cambios sin guardar."),
      confirmLabel: t("Descartar"),
      cancelLabel: t("Seguir editando"),
      dangerous: true,
    }).then((confirmed) => { if (confirmed) onClose(); });
  }

  function choose(next: DeliveryResult | null) {
    if (next === null) return;
    haptic(15);
    setResult(next);
    setErrors({});
    if (!allowsReceiver(next)) { setReceiverName(""); setReceiverDocument(""); setSigning(false); setSignature(null); }
    if (!takesEvidence(next)) setPhotos([]);
  }

  async function submit() {
    if (submitting) return;
    const values = {
      result, receiverName, receiverDocument, notes, refusedPallets, refusedWeightKg, refusedVolumeM3,
      photos: photos.map((photo) => photo.file), signature,
    };
    const found = validateDelivery(values, loaded);
    setErrors(found);
    setSubmitError(null);
    if (Object.keys(found).length > 0 || result === null) { haptic([80, 50, 80]); return; }

    happenedAt.current ??= new Date().toISOString();
    const partial = result === "PARTIAL";
    const input: PortalDeliveryInput = {
      result,
      deliveredAt: result === "NOT_ATTEMPTED" ? null : happenedAt.current,
      receiverName: allowsReceiver(result) ? receiverName.trim() || null : null,
      receiverDocument: allowsReceiver(result) ? receiverDocument.trim() || null : null,
      notes: notes.trim() || null,
      refusedPallets: partial ? parseAmount(refusedPallets) : null,
      refusedWeightKg: partial ? parseAmount(refusedWeightKg) : null,
      refusedVolumeM3: partial ? parseAmount(refusedVolumeM3) : null,
      photos: evidenceEnabled && takesEvidence(result) ? values.photos : [],
      signature: evidenceEnabled && allowsReceiver(result) ? signature : null,
    };

    setSubmitting(true);
    try {
      const outcome = await run(
        { type: "DELIVERY", stopId, orderId: order.orderId, input },
        `${t("Entrega")} · ${order.orderNumber} · ${enumLabel("deliveryResult", result)}`,
        idempotencyKey, happenedAt.current,
      );
      haptic([30, 40, 30]);
      onSaved?.(outcome);
      onClose();
    } catch (error) {
      haptic([80, 50, 80]);
      setSubmitError(error instanceof ExecutionRejectedError ? error.message : t("No se pudo guardar."));
    } finally {
      setSubmitting(false);
    }
  }

  const loadedLine = [
    order.assignedPallets !== null ? `${fmtQuantity(order.assignedPallets)} ${t("pallets")}` : null,
    order.assignedWeightKg !== null ? `${fmtQuantity(order.assignedWeightKg)} kg` : null,
    order.assignedVolumeM3 !== null ? `${fmtQuantity(order.assignedVolumeM3)} m³` : null,
  ].filter(Boolean).join(" · ");

  return (
    <Drawer
      anchor={wide ? "right" : "bottom"}
      open
      onClose={() => requestClose()}
      sx={{ zIndex: (th) => th.zIndex.drawer + 2 }}
      slotProps={{
        paper: {
          role: "dialog",
          "aria-labelledby": "delivery-title",
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
          <Typography id="delivery-title" component="h2" sx={{ fontWeight: 800, fontSize: 19, lineHeight: 1.25 }}>
            {t("Entrega del pedido {{order}}", { order: order.orderNumber })}
          </Typography>
          <Typography color="text.secondary" sx={{ fontSize: 15, mt: 0.25 }}>
            {[stopLabel, order.customerName].filter(Boolean).join(" · ")}
          </Typography>
          {loadedLine && (
            <Typography color="text.secondary" sx={{ fontSize: 14, fontVariantNumeric: "tabular-nums" }}>
              {t("Cargado")}: {loadedLine}
            </Typography>
          )}
          {existing && (
            <Box sx={{ display: "flex", gap: 0.75, flexWrap: "wrap", mt: 0.75 }}>
              <StatusChip label={enumLabel("deliveryResult", existing.result)} tone={DELIVERY_RESULT_TONE[existing.result]} />
              {order.pendingSync && <StatusChip tone="inProgress" icon={<CloudUploadRounded />} label={t("Pendiente de sincronizar")} />}
            </Box>
          )}
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
        {!offline.online && (
          <Alert severity="warning" icon={<CloudOffRounded />} sx={{ fontSize: 15 }}>
            {t("Sin conexión. La entrega se guardará en el teléfono y se enviará al recuperar señal.")}
          </Alert>
        )}

        <Box>
          <Typography component="h3" id="delivery-result-label" sx={{ fontWeight: 800, fontSize: 17, mb: 1.25 }}>
            {existing ? t("Corregir el resultado") : t("¿Qué pasó con el pedido?")}
          </Typography>
          <ToggleButtonGroup
            exclusive orientation="vertical" value={result}
            onChange={(_e, next: DeliveryResult | null) => choose(next)}
            aria-labelledby="delivery-result-label"
            sx={{ display: "grid", gap: 1 }}
          >
            {PORTAL_DELIVERY_RESULTS.map((value) => {
              const choice = CHOICES[value];
              return (
                <ToggleButton
                  key={value} value={value}
                  sx={{
                    justifyContent: "flex-start", gap: 1.5, px: 2, minHeight: 60, textTransform: "none",
                    fontWeight: 800, fontSize: 17, lineHeight: 1.2, color: "text.primary", textAlign: "left",
                    border: "2px solid !important", borderColor: (th) => `${th.palette.divider} !important`,
                    borderRadius: `${R.md}px !important`, m: "0 !important",
                    "& svg": { fontSize: 30, color: `${choice.color}.main`, flexShrink: 0 },
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
          {errors.result && <Typography color="error" sx={{ mt: 0.75, fontWeight: 700, fontSize: 15 }}>{errors.result}</Typography>}
        </Box>

        {result === "PARTIAL" && (
          <Box role="group" aria-labelledby="delivery-refused-label">
            <Typography component="h3" id="delivery-refused-label" sx={{ fontWeight: 800, fontSize: 17, mb: 1 }}>
              {t("¿Cuánto rechazó el cliente?")}
            </Typography>
            <Box sx={{ display: "grid", gridTemplateColumns: "repeat(3, minmax(0, 1fr))", gap: 1 }}>
              <TextField label={t("Pallets")} value={refusedPallets} onChange={(e) => setRefusedPallets(e.target.value)}
                slotProps={{ htmlInput: { inputMode: "decimal" } }} />
              <TextField label="kg" value={refusedWeightKg} onChange={(e) => setRefusedWeightKg(e.target.value)}
                slotProps={{ htmlInput: { inputMode: "decimal" } }} />
              <TextField label="m³" value={refusedVolumeM3} onChange={(e) => setRefusedVolumeM3(e.target.value)}
                slotProps={{ htmlInput: { inputMode: "decimal" } }} />
            </Box>
            <Typography color={errors.refused ? "error" : "text.secondary"} sx={{ mt: 0.75, fontSize: 14, fontWeight: errors.refused ? 700 : 400 }}>
              {errors.refused ?? t("Lo que no rechazó se registra como entregado.")}
            </Typography>
          </Box>
        )}

        {allowsReceiver(result) && (
          <Box sx={{ display: "grid", gap: 1.5 }}>
            <TextField
              label={result === "REJECTED" ? t("Quién lo rechazó (opcional)") : t("Quién recibió (opcional)")}
              value={receiverName} onChange={(e) => setReceiverName(e.target.value)}
              slotProps={{ htmlInput: { maxLength: 120, autoComplete: "off" } }}
            />
            <TextField
              label={t("Documento de identidad (opcional)")}
              value={receiverDocument} onChange={(e) => setReceiverDocument(e.target.value)}
              slotProps={{ htmlInput: { maxLength: 60, autoComplete: "off" } }}
            />
          </Box>
        )}

        {result !== null && (
          <TextField
            fullWidth multiline minRows={2}
            label={needsNotes(result) ? t("Qué pasó (obligatorio)") : t("Observación (opcional)")}
            value={notes}
            onChange={(e) => { setNotes(e.target.value); setErrors((x) => ({ ...x, notes: undefined })); }}
            error={Boolean(errors.notes)}
            helperText={errors.notes}
            slotProps={{ htmlInput: { maxLength: 1000 } }}
          />
        )}

        {takesEvidence(result) && (evidenceEnabled ? (
          <>
            <Box>
              <Typography component="h3" sx={{ fontWeight: 800, fontSize: 17, mb: 1 }}>{t("Fotos (opcional)")}</Typography>
              <PhotoPicker
                value={photos}
                onChange={(next) => { setPhotos(next); setErrors((x) => ({ ...x, photos: undefined })); }}
                max={MAX_REPORT_PHOTOS}
                disabled={submitting}
                error={errors.photos}
              />
            </Box>
            {allowsReceiver(result) && (
              !signing ? (
                <Button
                  variant="outlined" fullWidth startIcon={<DrawRounded />} onClick={() => setSigning(true)}
                  disabled={submitting} sx={{ minHeight: 52, fontSize: 16, fontWeight: 700 }}
                >
                  {t("Añadir firma del cliente (opcional)")}
                </Button>
              ) : (
                <Box>
                  <Box sx={{ display: "flex", alignItems: "center", mb: 1 }}>
                    <Typography component="h3" sx={{ fontWeight: 800, fontSize: 17, flex: 1 }}>{t("Firma del cliente (opcional)")}</Typography>
                    <Button onClick={() => { setSigning(false); setSignature(null); }} disabled={submitting}>{t("Quitar firma")}</Button>
                  </Box>
                  <SignaturePad onChange={setSignature} disabled={submitting} height={200} />
                </Box>
              )
            )}
          </>
        ) : (
          <Alert severity="info" sx={{ fontSize: 15 }}>
            {t("Este sistema no guarda fotos ni firmas: el resultado se registra sin evidencia.")}
          </Alert>
        ))}
      </Box>

      <Box sx={{
        flexShrink: 0, borderTop: "1px solid", borderColor: "divider", bgcolor: "background.paper",
        px: 2, pt: 1.5, pb: `calc(${SAFE_AREA.bottom} + 12px)`, display: "grid", gap: 1,
      }}>
        {submitError && <Alert severity="error" sx={{ fontSize: 15 }}>{submitError}</Alert>}
        <Button
          type="submit" form={FORM_ID} variant="contained" size="large" fullWidth disabled={submitting}
          color={offline.online ? "primary" : "warning"}
          startIcon={submitting ? <CircularProgress size={22} color="inherit" /> : offline.online ? <SaveRounded /> : <CloudOffRounded />}
          sx={{ minHeight: TOUCH_TARGET, fontSize: 18, fontWeight: 800 }}
        >
          {submitting ? t("Guardando...") : offline.online ? t("Guardar entrega") : t("Guardar sin conexión")}
        </Button>
      </Box>
    </Drawer>
  );
}
