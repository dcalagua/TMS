import { useState } from "react";
import { useForm } from "react-hook-form";
import { Alert, Box, Button, TextField, Typography } from "@mui/material";
import { EditNoteRounded } from "@mui/icons-material";
import type { ApiError } from "../../shared/api/httpClient";
import { recordActualTripCost, type TripCostView } from "../../shared/api/ratesApi";
import { describeApiError } from "../../shared/api/problemMessages";
import { FormDrawer } from "../../shared/ui/components";
import { notifySuccess } from "../../lib/ui";
import { t } from "../../lib/i18n";
import { fmtMoney } from "../../lib/locale";

const FORM_ID = "actual-cost-form";

interface ActualCostDrawerProps {
  companyId: string;
  tripId: string;
  cost: TripCostView;
  onClose: () => void;
  onSaved: (next: TripCostView) => void;
}

interface ActualCostFormValues {
  amount: string;
  currency: string;
  reference: string;
  notes: string;
}

/**
 * El costo real del viaje: lo que de verdad se pagó, contra lo que dijo el tarifario.
 *
 * La moneda solo se pide cuando el viaje no tiene estimado del que heredarla. Cuando lo tiene,
 * ofrecer el campo invitaría a registrar un real en una moneda distinta del estimado, y la
 * diferencia entre los dos dejaría de significar nada.
 *
 * La referencia es lo que conecta esta cifra con el papel: el número de la factura del
 * transportista, el del vale de combustible. Sin ella el número es una afirmación sin respaldo.
 */
export function ActualCostDrawer({ companyId, tripId, cost, onClose, onSaved }: ActualCostDrawerProps) {
  const [formError, setFormError] = useState<string | null>(null);
  const needsCurrency = cost.currency === null;

  const {
    register, handleSubmit,
    formState: { errors, isDirty, isSubmitting },
  } = useForm<ActualCostFormValues>({
    defaultValues: {
      amount: cost.actualAmount?.toString() ?? "",
      currency: cost.currency ?? "PEN",
      reference: cost.actualReference ?? "",
      notes: cost.actualNotes ?? "",
    },
  });

  async function onSubmit(values: ActualCostFormValues) {
    setFormError(null);
    try {
      const next = await recordActualTripCost(companyId, tripId, {
        amount: Number(values.amount),
        currency: needsCurrency ? values.currency.trim().toUpperCase() : null,
        reference: values.reference.trim() || null,
        notes: values.notes.trim() || null,
      });
      notifySuccess(t("Costo real registrado"));
      onSaved(next);
    } catch (error) {
      setFormError(describeApiError(error as ApiError));
    }
  }

  return (
    <FormDrawer
      open
      icon={<EditNoteRounded />}
      title={t("Costo real del viaje")}
      subtitle={cost.estimatedAmount !== null
        ? t("Estimado: {{amount}}", { amount: fmtMoney(cost.estimatedAmount, cost.currency ?? "PEN") })
        : t("Este viaje no tiene estimado.")}
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
      <Box component="form" id={FORM_ID} onSubmit={(event) => void handleSubmit(onSubmit)(event)} noValidate>
        {formError && <Alert severity="error" sx={{ mb: 2 }}>{formError}</Alert>}

        <Box sx={{ display: "grid", gap: 2 }}>
          <TextField
            label={t("Importe")} required size="small" fullWidth type="number"
            error={Boolean(errors.amount)} helperText={errors.amount?.message}
            {...register("amount", {
              required: t("Este campo es obligatorio"),
              validate: (value) => {
                const parsed = Number(value);
                if (Number.isNaN(parsed)) return t("Debe ser un número");
                return parsed >= 0 || t("Debe ser cero o mayor");
              },
            })}
          />

          {needsCurrency && (
            <TextField
              label={t("Moneda")} required size="small" fullWidth placeholder="PEN"
              error={Boolean(errors.currency)} helperText={errors.currency?.message}
              {...register("currency", {
                required: t("Este campo es obligatorio"),
                maxLength: { value: 3, message: t("No puede superar los {{count}} caracteres", { count: 3 }) },
              })}
            />
          )}

          <TextField
            label={t("Referencia")} size="small" fullWidth
            placeholder={t("Nº de factura, vale, guía...")}
            {...register("reference", {
              maxLength: { value: 100, message: t("No puede superar los {{count}} caracteres", { count: 100 }) },
            })}
            error={Boolean(errors.reference)} helperText={errors.reference?.message}
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
          {t("La diferencia contra el estimado la calcula el backend al guardar.")}
        </Typography>
      </Box>
    </FormDrawer>
  );
}
