import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { Controller, useForm } from "react-hook-form";
import { Alert, Box, Button, MenuItem, TextField, Typography } from "@mui/material";
import { LocalShippingRounded } from "@mui/icons-material";
import type { ApiError } from "../../shared/api/httpClient";
import { fetchVehicles } from "../../shared/api/vehiclesApi";
import { createTrip, type TripCreateRequest, type TripDetailView } from "../../shared/api/planningApi";
import { describePlanningError } from "../../shared/api/problemMessages";
import { FormDrawer } from "../../shared/ui/components";
import { t } from "../../lib/i18n";

const FORM_ID = "create-trip-form";

interface CreateTripDrawerProps {
  companyId: string;
  runId: string;
  runVersion: number;
  onClose: () => void;
  onCreated: (detail: TripDetailView) => void;
}

interface CreateTripFormValues {
  vehicleId: string;
  plannedDepartureAt: string;
}

/**
 * Crea un viaje dentro de un plan en borrador.
 *
 * Tanto el vehículo como la salida son opcionales: un planificador esboza rutinariamente el
 * "viaje 3" antes de decidir qué camión lo hace, y obligarle a elegir uno para poder empezar
 * invertiría el orden real del trabajo.
 *
 * Manda la versión *del plan*, no la del viaje: crear un viaje es una escritura de nivel plan, y
 * la versión es lo que hace que falle ruidosamente si alguien confirmó o canceló el plan desde
 * que esta pantalla lo cargó.
 */
export function CreateTripDrawer({ companyId, runId, runVersion, onClose, onCreated }: CreateTripDrawerProps) {
  const [formError, setFormError] = useState<string | null>(null);

  const vehiclesQuery = useQuery({
    queryKey: ["vehicles-for-trip-form", companyId],
    queryFn: ({ signal }) => fetchVehicles({ companyId, size: 200, active: true, sort: "code,asc", signal }),
  });

  const {
    register, control, handleSubmit,
    formState: { isDirty, isSubmitting },
  } = useForm<CreateTripFormValues>({ defaultValues: { vehicleId: "", plannedDepartureAt: "" } });

  async function onSubmit(values: CreateTripFormValues) {
    setFormError(null);
    const request: TripCreateRequest = {
      vehicleId: values.vehicleId || null,
      plannedDepartureAt: values.plannedDepartureAt ? new Date(values.plannedDepartureAt).toISOString() : null,
      version: runVersion,
    };

    try {
      onCreated(await createTrip(companyId, runId, request));
    } catch (error) {
      setFormError(describePlanningError(error as ApiError));
    }
  }

  return (
    <FormDrawer
      open
      icon={<LocalShippingRounded />}
      title={t("Nuevo viaje")}
      subtitle={t("Un viaje dentro de este plan. El vehículo y la salida se pueden decidir después.")}
      size="md"
      onClose={onClose}
      dirty={isDirty}
      // Un Escape o un clic fuera no deben abandonar un envío que ya está en vuelo.
      closeOnBackdrop={!isSubmitting}
      footer={
        <>
          <Button onClick={onClose} disabled={isSubmitting}>{t("Cancelar")}</Button>
          <Button type="submit" form={FORM_ID} variant="contained" disabled={isSubmitting}>
            {isSubmitting ? t("Guardando...") : t("Crear viaje")}
          </Button>
        </>
      }
    >
      <Box component="form" id={FORM_ID} onSubmit={(event) => void handleSubmit(onSubmit)(event)} noValidate>
        {formError && <Alert severity="error" sx={{ mb: 2 }}>{formError}</Alert>}

        <Box sx={{ display: "grid", gap: 2, mb: 2 }}>
          <Controller
            control={control}
            name="vehicleId"
            render={({ field }) => (
              <TextField
                select label={t("Vehículo")} size="small" fullWidth
                value={field.value} onChange={(e) => field.onChange(e.target.value)}
                helperText={t("Opcional: sin vehículo, el viaje no tiene límite de capacidad todavía.")}
              >
                <MenuItem value="">{t("Decidir después")}</MenuItem>
                {(vehiclesQuery.data?.content ?? []).map((vehicle) => (
                  <MenuItem key={vehicle.id} value={vehicle.id}>
                    {vehicle.code} · {vehicle.licensePlate}
                  </MenuItem>
                ))}
              </TextField>
            )}
          />
          <TextField
            label={t("Salida planificada")} size="small" fullWidth type="datetime-local"
            slotProps={{ inputLabel: { shrink: true } }}
            {...register("plannedDepartureAt")}
          />
        </Box>

        <Typography variant="caption" color="text.secondary">
          {t("Los pedidos se asignan al viaje desde el tablero, una vez creado.")}
        </Typography>
      </Box>
    </FormDrawer>
  );
}
