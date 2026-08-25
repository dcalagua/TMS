import { useState } from "react";
import { Controller, useForm, useWatch } from "react-hook-form";
import { Alert, Box, Button, MenuItem, TextField, Typography } from "@mui/material";
import { InventoryRounded } from "@mui/icons-material";
import {
  DELIVERY_RESULTS, DELIVERY_RESULTS_NEEDING_NOTES, DELIVERY_RESULTS_NEEDING_TIME,
  DELIVERY_RESULTS_WITH_RECEIVER,
  type DeliveryResult, type OrderDeliveryView,
} from "../../shared/api/planningApi";
import { FormDrawer } from "../../shared/ui/components";
import { enumLabel } from "../../lib/enums";
import { t } from "../../lib/i18n";

const FORM_ID = "delivery-form";

export interface DeliveryValues {
  result: DeliveryResult;
  deliveredAt: string | null;
  receiverName: string | null;
  receiverDocument: string | null;
  notes: string | null;
}

interface DeliveryDrawerProps {
  stopLabel: string;
  orderNumber: string;
  /** El registro existente cuando esto es una corrección; `undefined` cuando es un alta. */
  existing?: OrderDeliveryView;
  onClose: () => void;
  /** Lanza un `Error` con la frase del servidor si el backend rechaza. */
  onSubmit: (values: DeliveryValues) => Promise<void>;
}

/** Convierte un instante ISO al valor de un `<input type="datetime-local">`. */
function toLocalInput(iso: string | null | undefined): string {
  if (!iso) return "";
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return "";
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

/**
 * Qué se entregó de un pedido en una parada, y en qué condiciones.
 *
 * El mismo drawer sirve para registrar y para corregir: es un `PUT` con el estado *completo* de
 * una entrega, no un parche, así que un nombre de receptor borrado del formulario es un nombre
 * que se quita — que es la única forma de deshacer uno tecleado por error.
 *
 * Qué combinaciones son legales lo decide el servidor. Las listas de `planningApi` que este
 * formulario consulta (`DELIVERY_RESULTS_NEEDING_TIME` y hermanas) solo dan forma al formulario:
 * enseñan el campo que hace falta y esconden el que sobra, pero no sustituyen a la validación.
 */
export function DeliveryDrawer({ stopLabel, orderNumber, existing, onClose, onSubmit }: DeliveryDrawerProps) {
  const [formError, setFormError] = useState<string | null>(null);

  const {
    register, control, handleSubmit,
    formState: { errors, isDirty, isSubmitting },
  } = useForm<{
    result: DeliveryResult;
    deliveredAt: string;
    receiverName: string;
    receiverDocument: string;
    notes: string;
  }>({
    defaultValues: {
      result: existing?.result ?? "DELIVERED",
      deliveredAt: toLocalInput(existing?.deliveredAt),
      receiverName: existing?.receiverName ?? "",
      receiverDocument: existing?.receiverDocument ?? "",
      notes: existing?.notes ?? "",
    },
  });

  const result = useWatch({ control, name: "result" });
  const needsTime = DELIVERY_RESULTS_NEEDING_TIME.includes(result);
  const hasReceiver = DELIVERY_RESULTS_WITH_RECEIVER.includes(result);
  const needsNotes = DELIVERY_RESULTS_NEEDING_NOTES.includes(result);

  async function submit(values: {
    result: DeliveryResult; deliveredAt: string; receiverName: string; receiverDocument: string; notes: string;
  }) {
    setFormError(null);
    try {
      await onSubmit({
        result: values.result,
        // Los campos que no aplican al resultado elegido se mandan vacíos y no con lo que quedara
        // escrito: el backend rechaza un receptor en un intento fallido, y esto evita mandarle uno
        // que el operador ya no ve.
        deliveredAt: needsTime && values.deliveredAt ? values.deliveredAt : null,
        receiverName: hasReceiver ? (values.receiverName.trim() || null) : null,
        receiverDocument: hasReceiver ? (values.receiverDocument.trim() || null) : null,
        notes: values.notes.trim() || null,
      });
    } catch (error) {
      setFormError((error as Error).message);
    }
  }

  return (
    <FormDrawer
      open
      icon={<InventoryRounded />}
      title={existing ? t("Corregir la entrega") : t("Registrar la entrega")}
      subtitle={`${orderNumber} · ${stopLabel}`}
      size="md"
      onClose={onClose}
      dirty={isDirty}
      closeOnBackdrop={!isSubmitting}
      footer={
        <>
          <Button onClick={onClose} disabled={isSubmitting}>{t("Cancelar")}</Button>
          <Button type="submit" form={FORM_ID} variant="contained" disabled={isSubmitting}>
            {isSubmitting ? t("Guardando...") : t("Guardar")}
          </Button>
        </>
      }
    >
      <Box component="form" id={FORM_ID} onSubmit={(event) => void handleSubmit(submit)(event)} noValidate>
        {formError && <Alert severity="error" sx={{ mb: 2 }}>{formError}</Alert>}

        <Box sx={{ display: "grid", gap: 2 }}>
          <Controller
            control={control}
            name="result"
            render={({ field }) => (
              <TextField
                select label={t("Resultado")} required size="small" fullWidth
                value={field.value} onChange={(e) => field.onChange(e.target.value as DeliveryResult)}
              >
                {DELIVERY_RESULTS.map((option) => (
                  <MenuItem key={option} value={option}>{enumLabel("deliveryResult", option)}</MenuItem>
                ))}
              </TextField>
            )}
          />

          {needsTime && (
            <TextField
              label={t("Entregado el")} size="small" fullWidth type="datetime-local"
              slotProps={{ inputLabel: { shrink: true } }}
              helperText={t("Cuándo ocurrió de verdad, no cuándo se está tecleando.")}
              {...register("deliveredAt")}
            />
          )}

          {hasReceiver && (
            <>
              <TextField
                label={t("Nombre de quien recibe")} size="small" fullWidth
                {...register("receiverName", {
                  maxLength: { value: 200, message: t("No puede superar los {{count}} caracteres", { count: 200 }) },
                })}
                error={Boolean(errors.receiverName)} helperText={errors.receiverName?.message}
              />
              <TextField
                label={t("Documento de quien recibe")} size="small" fullWidth
                {...register("receiverDocument", {
                  maxLength: { value: 50, message: t("No puede superar los {{count}} caracteres", { count: 50 }) },
                })}
                error={Boolean(errors.receiverDocument)} helperText={errors.receiverDocument?.message}
              />
            </>
          )}

          <TextField
            label={t("Notas")} size="small" fullWidth multiline rows={3}
            required={needsNotes}
            {...register("notes", {
              maxLength: { value: 1000, message: t("No puede superar los {{count}} caracteres", { count: 1000 }) },
            })}
            error={Boolean(errors.notes)} helperText={errors.notes?.message}
          />

          {needsNotes && (
            <Typography variant="caption" color="text.secondary">
              {t("Una entrega parcial, rechazada o fallida necesita una explicación: es lo que alguien va a leer mañana.")}
            </Typography>
          )}
        </Box>
      </Box>
    </FormDrawer>
  );
}
