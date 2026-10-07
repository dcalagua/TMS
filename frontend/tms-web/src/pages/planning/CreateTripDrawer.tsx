import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { Controller, useForm } from "react-hook-form";
import { Alert, Box, Button, MenuItem, TextField, Typography } from "@mui/material";
import { CheckRounded, LocalShippingRounded } from "@mui/icons-material";
import type { ApiError } from "../../shared/api/httpClient";
import { fetchVehicles } from "../../shared/api/vehiclesApi";
import {
  createTrip, type PlanningRunView, type TripCreateRequest, type TripDetailView,
} from "../../shared/api/planningApi";
import { describePlanningError } from "../../shared/api/problemMessages";
import { ContextCard, FormDateInput, FormDrawer, StatusChip } from "../../shared/ui/components";
import { enumLabel } from "../../lib/enums";
import { t } from "../../lib/i18n";
import { fmtDate } from "../../lib/locale";

const FORM_ID = "create-trip-form";

interface CreateTripDrawerProps {
  companyId: string;
  runId: string;
  runVersion: number;
  /** El plan entero, solo para enseñar en qué plan se crea el viaje. La versión que viaja sigue siendo `runVersion`. */
  run?: PlanningRunView;
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
export function CreateTripDrawer({ companyId, runId, runVersion, run, onClose, onCreated }: CreateTripDrawerProps) {
  const [formError, setFormError] = useState<string | null>(null);

  const vehiclesQuery = useQuery({
    queryKey: ["vehicles-for-trip-form", companyId],
    queryFn: ({ signal }) => fetchVehicles({ companyId, size: 200, active: true, sort: "code,asc", signal }),
  });

  const {
    control, handleSubmit,
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
      size="sm"
      onClose={onClose}
      dirty={isDirty}
      // Un Escape o un clic fuera no deben abandonar un envío que ya está en vuelo.
      closeOnBackdrop={!isSubmitting}
      footer={
        <>
          <Button color="inherit" sx={{ color: "text.secondary" }} onClick={onClose} disabled={isSubmitting}>{t("Cancelar")}</Button>
          <Button type="submit" form={FORM_ID} variant="contained" startIcon={<CheckRounded />} disabled={isSubmitting}>
            {isSubmitting ? t("Guardando...") : t("Crear viaje")}
          </Button>
        </>
      }
    >
      <Box
        component="form" id={FORM_ID} onSubmit={(event) => void handleSubmit(onSubmit)(event)} noValidate
        sx={{ display: "grid", gap: 2 }}
      >
        {run && (
          <ContextCard
            title={run.planNumber}
            status={<StatusChip label={enumLabel("planningRunStatus", run.status)} />}
            detail={[
              run.originName ?? run.originCode,
              fmtDate(run.planningDate),
              run.tripCount === 1 ? t("{{count}} viaje", { count: 1 }) : t("{{count}} viajes", { count: run.tripCount }),
            ].filter(Boolean).join(" · ")}
          />
        )}

        {formError && <Alert severity="error">{formError}</Alert>}

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
        <FormDateInput
          control={control} name="plannedDepartureAt" mode="datetime"
          label={t("Salida planificada")} size="small" fullWidth
        />
        <Typography variant="caption" color="text.secondary">
          {t("Los pedidos se asignan al viaje desde el tablero, una vez creado.")}
        </Typography>
      </Box>
    </FormDrawer>
  );
}
