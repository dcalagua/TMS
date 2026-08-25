import { useState } from "react";
import { useForm, type Validate } from "react-hook-form";
import { Alert, Box, Button, TextField, Typography } from "@mui/material";
import { LocalOfferRounded } from "@mui/icons-material";
import { applyApiFieldErrors } from "../../shared/api/formErrors";
import type { ApiError } from "../../shared/api/httpClient";
import type { TenderRequest, TripTenderView } from "../../shared/api/tendersApi";
import { FormDrawer } from "../../shared/ui/components";
import { t } from "../../lib/i18n";

const FORM_ID = "tender-form";

const CURRENCY_PATTERN = /^[A-Za-z]{3}$/;

interface TenderFormValues {
  offeredAmount: string;
  currency: string;
  notes: string;
  expiresAt: string;
}

export interface TenderDrawerProps {
  /** El transportista al que se va a ofrecer el envío — el suyo, y nunca una elección de aquí. */
  carrierName: string | null;
  /** El borrador que se está editando, o null cuando se prepara una oferta nueva. */
  tender: TripTenderView | null;
  onClose: () => void;
  onSubmit: (request: TenderRequest) => Promise<void>;
}

const KNOWN_FIELDS = new Set<keyof TenderFormValues>(["offeredAmount", "currency", "notes", "expiresAt"]);

/** Convierte un instante ISO al valor de un `<input type="datetime-local">`. */
function toLocalInput(iso: string | null | undefined): string {
  if (!iso) return "";
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return "";
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

/**
 * Los términos de una oferta: qué se paga, hasta cuándo tienen para contestar y lo que el
 * transportista necesite saber.
 *
 * **Sin selector de transportista**, y su ausencia es el diseño, no un olvido. El transportista de
 * un envío sale del vehículo que se le planificó, y para cuando un envío se puede ofertar ese
 * vehículo ya está fijado: hay exactamente un transportista al que puede ir la oferta, y un
 * desplegable de una sola entrada sugeriría una elección que el producto no tiene.
 *
 * El importe y la moneda viajan juntos o no viajan: el backend rechaza uno sin el otro, y una
 * empresa que oferta bajo un tarifario permanente no tiene precio que declarar por envío.
 */
export function TenderDrawer({ carrierName, tender, onClose, onSubmit }: TenderDrawerProps) {
  const [formError, setFormError] = useState<string | null>(null);

  const {
    register, handleSubmit, setError, watch,
    formState: { errors, isDirty, isSubmitting },
  } = useForm<TenderFormValues>({
    defaultValues: {
      offeredAmount: tender?.offeredAmount?.toString() ?? "",
      currency: tender?.currency ?? "",
      notes: tender?.notes ?? "",
      expiresAt: toLocalInput(tender?.expiresAt),
    },
  });

  const amount = watch("offeredAmount");
  const currency = watch("currency");

  /** Importe y moneda: los dos o ninguno. Es la regla del backend, dicha antes de enviar. */
  const validatePair: Validate<string, TenderFormValues> = () =>
    (amount.trim() === "") === (currency.trim() === "")
    || t("Indica el importe y la moneda, o deja ambos en blanco");

  async function submit(values: TenderFormValues) {
    setFormError(null);
    const request: TenderRequest = {
      offeredAmount: values.offeredAmount.trim() === "" ? null : Number(values.offeredAmount),
      currency: values.currency.trim() === "" ? null : values.currency.trim().toUpperCase(),
      notes: values.notes.trim() || null,
      expiresAt: values.expiresAt ? new Date(values.expiresAt).toISOString() : null,
    };

    try {
      await onSubmit(request);
    } catch (error) {
      setFormError(applyApiFieldErrors(error as ApiError, KNOWN_FIELDS, setError, t("Corrige los campos marcados.")));
    }
  }

  return (
    <FormDrawer
      open
      icon={<LocalOfferRounded />}
      title={tender ? t("Editar la oferta") : t("Nueva oferta")}
      subtitle={carrierName ?? t("Se crea como borrador: enviarla es una acción aparte.")}
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
          <Box sx={{ display: "grid", gap: 2, gridTemplateColumns: "2fr 1fr" }}>
            <TextField
              label={t("Importe ofrecido")} size="small" fullWidth type="number"
              error={Boolean(errors.offeredAmount)} helperText={errors.offeredAmount?.message}
              {...register("offeredAmount", { validate: validatePair })}
            />
            <TextField
              label={t("Moneda")} size="small" fullWidth placeholder="PEN"
              error={Boolean(errors.currency)} helperText={errors.currency?.message}
              {...register("currency", {
                validate: validatePair,
                pattern: { value: CURRENCY_PATTERN, message: t("Tres letras, p. ej. PEN") },
              })}
            />
          </Box>

          <TextField
            label={t("Vence el")} size="small" fullWidth type="datetime-local"
            slotProps={{ inputLabel: { shrink: true } }}
            helperText={t("Tiene que seguir en el futuro cuando se envíe la oferta, no cuando se redacta.")}
            {...register("expiresAt")}
          />

          <TextField
            label={t("Notas")} size="small" fullWidth multiline rows={3}
            {...register("notes", {
              maxLength: { value: 1000, message: t("No puede superar los {{count}} caracteres", { count: 1000 }) },
            })}
            error={Boolean(errors.notes)} helperText={errors.notes?.message}
          />
        </Box>

        <Typography variant="caption" color="text.secondary" sx={{ display: "block", mt: 2 }}>
          {t("Todos los términos son opcionales: una oferta puede ser solo una pregunta.")}
        </Typography>
      </Box>
    </FormDrawer>
  );
}
