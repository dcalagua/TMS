import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { Controller, useForm } from "react-hook-form";
import { Alert, Box, Button, MenuItem, TextField, Typography } from "@mui/material";
import { CheckRounded, LocalShippingRounded } from "@mui/icons-material";
import type { ApiError } from "../../shared/api/httpClient";
import { fetchVehicles } from "../../shared/api/vehiclesApi";
import { updateTripVehicle, type TripDetailView, type TripView } from "../../shared/api/planningApi";
import { describePlanningError } from "../../shared/api/problemMessages";
import { ContextCard, FormDateInput, FormDrawer, StatusChip } from "../../shared/ui/components";
import { TRIP_STATUS_TONE } from "../../shared/ui/statusTones";
import { enumLabel } from "../../lib/enums";
import { notifySuccess } from "../../lib/ui";
import { t } from "../../lib/i18n";
import { fmtDate, fmtDecimal } from "../../lib/locale";

const FORM_ID = "trip-vehicle-form";

interface TripVehicleDrawerProps {
  companyId: string;
  trip: TripView;
  onClose: () => void;
  onSaved: (detail: TripDetailView) => void;
}

interface TripVehicleFormValues {
  vehicleId: string;
  plannedDepartureAt: string;
}

/** Convierte un instante ISO al valor que espera un `<input type="datetime-local">`, que quiere
 * hora local sin zona. */
function toLocalInput(iso: string | null): string {
  if (!iso) return "";
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return "";
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

/**
 * Asignar o cambiar el vehículo de un viaje.
 *
 * El transportista nunca se manda: lo resuelve el servidor a partir del vehículo. Si se mandara
 * desde aquí serían dos fuentes para el mismo hecho, y la segunda acabaría discrepando en cuanto
 * alguien cambiase el vehículo de flota.
 *
 * Cambiar el vehículo cambia la capacidad del viaje, así que el backend revalida lo que ya lleva
 * cargado: si la carga actual no cabe en la unidad nueva, el rechazo nombra la dimensión que no
 * entra y eso es lo que se muestra tal cual.
 */
export function TripVehicleDrawer({ companyId, trip, onClose, onSaved }: TripVehicleDrawerProps) {
  const [formError, setFormError] = useState<string | null>(null);

  const vehiclesQuery = useQuery({
    queryKey: ["vehicles-for-trip-vehicle", companyId],
    queryFn: ({ signal }) => fetchVehicles({ companyId, size: 200, active: true, sort: "code,asc", signal }),
  });

  const {
    control, handleSubmit,
    formState: { isDirty, isSubmitting },
  } = useForm<TripVehicleFormValues>({
    defaultValues: {
      vehicleId: trip.vehicleId ?? "",
      plannedDepartureAt: toLocalInput(trip.plannedDepartureAt),
    },
  });

  async function onSubmit(values: TripVehicleFormValues) {
    setFormError(null);
    if (values.vehicleId === "") {
      setFormError(t("Elige un vehículo."));
      return;
    }
    try {
      const next = await updateTripVehicle(companyId, trip.id, {
        vehicleId: values.vehicleId,
        plannedDepartureAt: values.plannedDepartureAt ? new Date(values.plannedDepartureAt).toISOString() : null,
        version: trip.version,
      });
      notifySuccess(t("Vehículo asignado"));
      onSaved(next);
    } catch (error) {
      setFormError(describePlanningError(error as ApiError));
    }
  }

  return (
    <FormDrawer
      open
      icon={<LocalShippingRounded />}
      title={t("Vehículo del viaje")}
      subtitle={t("La unidad que hace el viaje y su salida planificada.")}
      size="sm"
      onClose={onClose}
      dirty={isDirty}
      closeOnBackdrop={!isSubmitting}
      footer={
        <>
          <Button color="inherit" sx={{ color: "text.secondary" }} onClick={onClose} disabled={isSubmitting}>{t("Cancelar")}</Button>
          <Button type="submit" form={FORM_ID} variant="contained" startIcon={<CheckRounded />} disabled={isSubmitting}>
            {isSubmitting ? t("Guardando...") : t("Asignar vehículo")}
          </Button>
        </>
      }
    >
      <Box
        component="form" id={FORM_ID} onSubmit={(event) => void handleSubmit(onSubmit)(event)} noValidate
        sx={{ display: "grid", gap: 2 }}
      >
        <ContextCard
          title={t("Viaje {{number}}", { number: trip.tripNumber })}
          status={<StatusChip label={enumLabel("tripStatus", trip.status)} tone={TRIP_STATUS_TONE[trip.status]} />}
          detail={[
            trip.shipmentNumber,
            fmtDate(trip.planningDate),
            trip.vehicleId
              ? t("Ahora: {{vehicle}}", { vehicle: [trip.vehicleCode, trip.vehicleLicensePlate].filter(Boolean).join(" · ") })
              : t("Sin vehículo asignado"),
          ].join(" · ")}
        />

        {formError && <Alert severity="error">{formError}</Alert>}

        <Controller
          control={control}
          name="vehicleId"
          render={({ field }) => (
            <TextField
              select label={t("Vehículo")} required size="small" fullWidth
              value={field.value} onChange={(e) => field.onChange(e.target.value)}
            >
              <MenuItem value="">{t("Selecciona un vehículo")}</MenuItem>
              {(vehiclesQuery.data?.content ?? []).map((vehicle) => (
                <MenuItem key={vehicle.id} value={vehicle.id}>
                  {vehicle.code} · {vehicle.licensePlate} · {fmtDecimal(vehicle.effectiveMaxWeightKg)} kg
                </MenuItem>
              ))}
            </TextField>
          )}
        />
        <FormDateInput
          control={control} name="plannedDepartureAt" mode="datetime"
          label={t("Salida planificada")} size="small" fullWidth
        />
        <Typography variant="caption" color="text.secondary">
          {t("Si la carga actual no cabe en la unidad elegida, el backend rechaza el cambio y dice qué dimensión se excede.")}
        </Typography>
      </Box>
    </FormDrawer>
  );
}
