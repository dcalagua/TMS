import { useState } from "react";
import { Box, Button, MenuItem, TextField } from "@mui/material";
import { PanToolRounded } from "@mui/icons-material";
import type { ApiError } from "../../shared/api/httpClient";
import { describeApiError } from "../../shared/api/problemMessages";
import { HOLD_TYPES, placeOrderHold, type HoldType, type SchedulingRow } from "../../shared/api/schedulingApi";
import { ContextCard, FormDrawer, OptionCard, StatusChip } from "../../shared/ui/components";
import { notifyError, notifySuccess } from "../../lib/ui";
import { enumLabel } from "../../lib/enums";
import { t } from "../../lib/i18n";
import { fmtDate } from "../../lib/locale";

const REASON_CODE_PATTERN = /^[A-Z0-9][A-Z0-9_.-]{0,63}$/;

/**
 * Aplicar una retención a un pedido (V54). Un panel lateral y no un diálogo: la lista de la que se
 * vino sigue a la vista, y el motivo es texto que alguien va a leer después.
 *
 * Bloqueante por defecto, como en el backend: una retención detiene la liberación, la planificación
 * y el despacho mientras esté activa. Desmarcarla la convierte en una nota que viaja con el pedido.
 */
export function HoldDrawer({ companyId, orderId, orderNumber, row, onClose, onPlaced }: {
  companyId: string;
  orderId: string;
  orderNumber: string;
  /** La fila que ya tiene quien abre el panel: solo para la tarjeta de contexto. */
  row?: SchedulingRow;
  onClose: () => void;
  onPlaced: () => void;
}) {
  const [holdType, setHoldType] = useState<HoldType>("COMMERCIAL");
  const [reasonCode, setReasonCode] = useState("");
  const [reason, setReason] = useState("");
  const [blocking, setBlocking] = useState(true);
  const [saving, setSaving] = useState(false);
  const [touched, setTouched] = useState(false);

  const code = reasonCode.trim().toUpperCase();
  const codeInvalid = code !== "" && !REASON_CODE_PATTERN.test(code);
  const reasonMissing = reason.trim() === "";

  const contextLine = row
    ? [
        row.customerName,
        row.destinationName ?? row.destinationCode,
        t("Despacho {{date}}", { date: fmtDate(row.scheduledDispatchDate) }),
      ].filter(Boolean).join(" · ")
    : undefined;

  async function submit() {
    setTouched(true);
    if (reasonMissing || codeInvalid) return;
    setSaving(true);
    try {
      await placeOrderHold(companyId, orderId, {
        holdType, reasonCode: code || null, reason: reason.trim(), blocking,
      });
      notifySuccess(t("Retención aplicada"), orderNumber);
      onPlaced();
    } catch (error) {
      notifyError(t("No se pudo aplicar la retención"), describeApiError(error as ApiError));
    } finally {
      setSaving(false);
    }
  }

  return (
    <FormDrawer
      open
      size="sm"
      icon={<PanToolRounded />}
      title={t("Retener pedido")}
      subtitle={t("Detiene la liberación, la planificación y el despacho mientras esté activa.")}
      onClose={onClose}
      dirty={reason.trim() !== "" || code !== ""}
      footer={
        <>
          <Button onClick={onClose} color="inherit" sx={{ color: "text.secondary" }}>{t("Cancelar")}</Button>
          <Button variant="contained" startIcon={<PanToolRounded />} onClick={() => void submit()} disabled={saving}>
            {t("Retener pedido")}
          </Button>
        </>
      }
    >
      <Box component="form" onSubmit={(e) => { e.preventDefault(); void submit(); }} sx={{ display: "grid", gap: 2 }}>
        <ContextCard
          title={orderNumber}
          status={row ? <StatusChip label={enumLabel("orderStatus", row.status)} tone="neutral" /> : undefined}
          detail={contextLine}
        />
        <TextField
          select size="small" label={t("Tipo")} value={holdType}
          onChange={(e) => setHoldType(e.target.value as HoldType)}
        >
          {HOLD_TYPES.map((type) => (
            <MenuItem key={type} value={type}>{enumLabel("holdType", type)}</MenuItem>
          ))}
        </TextField>
        <TextField
          size="small" label={t("Código de motivo (opcional)")} value={reasonCode}
          onChange={(e) => setReasonCode(e.target.value)}
          error={codeInvalid}
          helperText={codeInvalid ? t("Solo mayúsculas, dígitos, '_', '.' o '-'.") : t("Por ejemplo LIMITE_CREDITO.")}
          slotProps={{ htmlInput: { maxLength: 64 } }}
        />
        <TextField
          size="small" label={t("Motivo")} value={reason} required multiline minRows={3}
          onChange={(e) => setReason(e.target.value)}
          error={touched && reasonMissing}
          helperText={touched && reasonMissing ? t("Este campo es obligatorio") : `${reason.length}/500`}
          slotProps={{ htmlInput: { maxLength: 500 } }}
        />
        <Box role="radiogroup" aria-label={t("Efecto")} sx={{ display: "grid", gap: 1 }}>
          <OptionCard
            selected={blocking}
            onSelect={() => setBlocking(true)}
            tone="error"
            title={t("Bloqueante")}
            description={t("Mientras esté activa, el pedido no se puede liberar ni planificar. Si ya está en un viaje, no se desplanifica: el viaje no podrá despacharse hasta levantarla.")}
          />
          <OptionCard
            selected={!blocking}
            onSelect={() => setBlocking(false)}
            tone="neutral"
            title={t("Solo nota")}
            description={t("Una retención no bloqueante es una nota: queda registrada y no detiene nada.")}
          />
        </Box>
      </Box>
    </FormDrawer>
  );
}
