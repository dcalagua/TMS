import { useState } from "react";
import { Controller, useForm, useWatch } from "react-hook-form";
import { Alert, Box, Button, MenuItem, TextField, Typography } from "@mui/material";
import { InventoryRounded } from "@mui/icons-material";
import {
  DELIVERY_RESULTS, DELIVERY_RESULTS_NEEDING_NOTES, DELIVERY_RESULTS_NEEDING_TIME,
  DELIVERY_RESULTS_WITH_RECEIVER,
  type DeliveryQuantitiesRequest, type DeliveryResult, type OrderDeliveryView,
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
  /**
   * Las cantidades (V45, deuda D3). **`null` = no registrado, que no es cero.**
   *
   * Registrar sólo el resultado sigue siendo legítimo — es lo que hacía toda entrega antes de V45.
   * Lo que la pantalla no debe hacer nunca es mandar ceros cuando el operador no escribió nada:
   * eso afirmaría que el cliente no se llevó nada.
   */
  quantities: DeliveryQuantitiesRequest | null;
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
  } = useForm<DeliveryFormValues>({
    defaultValues: {
      result: existing?.result ?? "DELIVERED",
      deliveredAt: toLocalInput(existing?.deliveredAt),
      receiverName: existing?.receiverName ?? "",
      receiverDocument: existing?.receiverDocument ?? "",
      notes: existing?.notes ?? "",
      // Vacío, no cero. Un campo en blanco significa "no lo estoy diciendo"; un 0 escrito a mano
      // significa "no se entregó nada", y son afirmaciones distintas.
      attemptedWeightKg: numberInput(existing?.quantities?.attemptedWeightKg),
      deliveredWeightKg: numberInput(existing?.quantities?.deliveredWeightKg),
      refusedWeightKg: numberInput(existing?.quantities?.refusedWeightKg),
      attemptedPallets: numberInput(existing?.quantities?.attemptedPallets),
      deliveredPallets: numberInput(existing?.quantities?.deliveredPallets),
      refusedPallets: numberInput(existing?.quantities?.refusedPallets),
    },
  });

  const result = useWatch({ control, name: "result" });
  const needsTime = DELIVERY_RESULTS_NEEDING_TIME.includes(result);
  const hasReceiver = DELIVERY_RESULTS_WITH_RECEIVER.includes(result);
  const needsNotes = DELIVERY_RESULTS_NEEDING_NOTES.includes(result);

  // Un aviso, no una defensa. El backend revalida cada medida por separado - confiar en que el
  // formulario impida el exceso sería confiar en el cliente para un invariante de dominio.
  const watched = useWatch({ control });
  const overDelivered = exceedsAttempted(watched as Partial<DeliveryFormValues>);

  async function submit(values: DeliveryFormValues) {
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
        quantities: quantitiesOf(values),
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

          {/* V45, deuda D3. Opcional a propósito: dejarlo en blanco es no afirmar nada sobre
              cantidades, que es lo que hacía toda entrega antes de V45 y sigue valiendo. Lo que la
              pantalla nunca hace es rellenar ceros por comodidad — eso convertiría "no lo dije" en
              "no llegó nada". */}
          <Typography variant="overline" color="text.secondary" sx={{ mt: 1 }}>
            {t("Cantidades (opcional)")}
          </Typography>
          <Typography variant="caption" color="text.secondary" sx={{ mt: -1 }}>
            {t("Déjalo en blanco si no vas a registrar cantidades. En blanco no es cero: un cero dice que el cliente no se llevó nada.")}
          </Typography>

          <Box sx={{ display: "grid", gap: 1.5, gridTemplateColumns: { xs: "1fr", sm: "repeat(3, 1fr)" } }}>
            <TextField
              label={t("Llevado (kg)")} size="small" type="number" fullWidth
              {...register("attemptedWeightKg")}
            />
            <TextField
              label={t("Entregado (kg)")} size="small" type="number" fullWidth
              {...register("deliveredWeightKg")}
            />
            <TextField
              label={t("Rechazado (kg)")} size="small" type="number" fullWidth
              {...register("refusedWeightKg")}
            />
            <TextField
              label={t("Llevado (pallets)")} size="small" type="number" fullWidth
              {...register("attemptedPallets")}
            />
            <TextField
              label={t("Entregado (pallets)")} size="small" type="number" fullWidth
              {...register("deliveredPallets")}
            />
            <TextField
              label={t("Rechazado (pallets)")} size="small" type="number" fullWidth
              {...register("refusedPallets")}
            />
          </Box>

          {/* El servidor vuelve a validar esto y rechaza la entrega; el aviso está aquí para que el
              operador lo vea antes de mandarla, no para sustituir la validación. */}
          {overDelivered && (
            <Alert severity="warning" variant="outlined">
              {t("Entregado más rechazado no puede superar lo llevado. El servidor lo va a rechazar.")}
            </Alert>
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

/** Los campos del formulario, incluidos los seis números que el operador puede dejar en blanco. */
interface DeliveryFormValues {
  result: DeliveryResult;
  deliveredAt: string;
  receiverName: string;
  receiverDocument: string;
  notes: string;
  attemptedWeightKg: string;
  deliveredWeightKg: string;
  refusedWeightKg: string;
  attemptedPallets: string;
  deliveredPallets: string;
  refusedPallets: string;
}

/** Un número que puede no existir se edita como texto vacío, nunca como 0. */
function numberInput(value: number | null | undefined): string {
  return value === null || value === undefined ? "" : String(value);
}

/**
 * El bloque de cantidades, o `null` si el operador no registró ninguna.
 *
 * **La regla que importa**: si el campo de "llevado" está vacío, no se manda nada. Rellenar ceros
 * por comodidad convertiría "no lo dije" en "no llegó nada", que es exactamente el faltante
 * inventado que la deuda D3 prohíbe.
 *
 * El volumen no se pide en el formulario — el operador de muelle cuenta bultos y pesa, no calcula
 * metros cúbicos — así que viaja en 0 junto a un peso y unos pallets reales. El servidor valida
 * cada medida por separado, de modo que un 0 en volumen no puede tapar un faltante en las otras.
 */
function quantitiesOf(values: DeliveryFormValues): DeliveryQuantitiesRequest | null {
  if (values.attemptedWeightKg.trim() === "") {
    return null;
  }
  const number = (raw: string) => (raw.trim() === "" ? 0 : Number(raw));
  return {
    attemptedWeightKg: number(values.attemptedWeightKg),
    attemptedVolumeM3: 0,
    attemptedPallets: number(values.attemptedPallets),
    deliveredWeightKg: number(values.deliveredWeightKg),
    deliveredVolumeM3: 0,
    deliveredPallets: number(values.deliveredPallets),
    refusedWeightKg: number(values.refusedWeightKg),
    refusedVolumeM3: 0,
    refusedPallets: number(values.refusedPallets),
  };
}

/** Sólo para avisar en pantalla. La regla de verdad vive en el servidor y en la base de datos. */
function exceedsAttempted(values: Partial<DeliveryFormValues>): boolean {
  const num = (raw: string | undefined) => (raw === undefined || raw.trim() === "" ? 0 : Number(raw));
  if ((values.attemptedWeightKg ?? "").trim() === "") return false;
  const weightOver = num(values.deliveredWeightKg) + num(values.refusedWeightKg) > num(values.attemptedWeightKg);
  const palletsOver = num(values.deliveredPallets) + num(values.refusedPallets) > num(values.attemptedPallets);
  return weightOver || palletsOver;
}
