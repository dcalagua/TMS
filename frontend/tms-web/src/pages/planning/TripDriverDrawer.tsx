import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { Controller, useForm } from "react-hook-form";
import { Alert, Box, Button, Chip, MenuItem, TextField, Typography } from "@mui/material";
import { BadgeRounded } from "@mui/icons-material";
import type { ApiError } from "../../shared/api/httpClient";
import { fetchDrivers } from "../../shared/api/driversApi";
import { updateTripDriver, type TripDetailView, type TripView } from "../../shared/api/planningApi";
import { describePlanningError } from "../../shared/api/problemMessages";
import { FormDrawer } from "../../shared/ui/components";
import { notifySuccess } from "../../lib/ui";
import { enumLabel } from "../../lib/enums";
import { t } from "../../lib/i18n";

const FORM_ID = "trip-driver-form";

interface TripDriverDrawerProps {
  companyId: string;
  trip: TripView;
  onClose: () => void;
  onSaved: (detail: TripDetailView) => void;
}

interface TripDriverFormValues {
  driverId: string;
}

/**
 * Asignar, cambiar o quitar el conductor de un viaje.
 *
 * A diferencia del vehículo, esto sigue disponible después de confirmar el plan y hasta la
 * salida: un conductor que llama enfermo a las cinco de la mañana no es una replanificación. El
 * servidor lo rechaza en cuanto el camión ya salió.
 *
 * Dejarlo vacío no es "no hacer nada": manda `null` y limpia la asignación, que es un estado real
 * que un despachador registra —"el conductor que teníamos no viene"— y libera a la persona para
 * otro viaje del mismo día.
 */
export function TripDriverDrawer({ companyId, trip, onClose, onSaved }: TripDriverDrawerProps) {
  const [formError, setFormError] = useState<string | null>(null);

  const driversQuery = useQuery({
    queryKey: ["drivers-for-trip", companyId, trip.carrierId],
    queryFn: ({ signal }) =>
      fetchDrivers({
        companyId, size: 200, active: true, sort: "code,asc",
        // Los conductores del transportista con el que se planificó el envío. Sin transportista
        // (flota propia) se ofrecen todos y el backend decide.
        carrierId: trip.carrierId ?? undefined,
        signal,
      }),
  });

  const {
    control, handleSubmit,
    formState: { isDirty, isSubmitting },
  } = useForm<TripDriverFormValues>({ defaultValues: { driverId: trip.driverId ?? "" } });

  async function onSubmit(values: TripDriverFormValues) {
    setFormError(null);
    try {
      const next = await updateTripDriver(companyId, trip.id, {
        driverId: values.driverId === "" ? null : values.driverId,
        version: trip.version,
      });
      notifySuccess(values.driverId === "" ? t("Conductor liberado") : t("Conductor asignado"));
      onSaved(next);
    } catch (error) {
      setFormError(describePlanningError(error as ApiError));
    }
  }

  return (
    <FormDrawer
      open
      icon={<BadgeRounded />}
      title={t("Conductor del viaje")}
      subtitle={t("Viaje {{number}}", { number: trip.tripNumber })}
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

        <Controller
          control={control}
          name="driverId"
          render={({ field }) => (
            <TextField
              select label={t("Conductor")} size="small" fullWidth sx={{ mb: 2 }}
              value={field.value} onChange={(e) => field.onChange(e.target.value)}
              helperText={t("Déjalo sin conductor para liberar a la persona y dejar el viaje sin asignar.")}
            >
              <MenuItem value="">{t("Sin conductor")}</MenuItem>
              {(driversQuery.data?.content ?? []).map((driver) => (
                <MenuItem key={driver.id} value={driver.id}>
                  <Box sx={{ display: "flex", alignItems: "center", gap: 1, minWidth: 0 }}>
                    <span>{driver.code} · {driver.fullName}</span>
                    {/* Una licencia vencida no se esconde de la lista: el backend es quien
                        rechaza la asignación, y esconder al conductor solo dejaría al despachador
                        preguntándose dónde se metió. */}
                    {driver.licenseStatus !== "VALID" && (
                      <Chip
                        size="small"
                        color={driver.licenseStatus === "EXPIRED" ? "error" : "warning"}
                        label={enumLabel("driverLicenseStatus", driver.licenseStatus)}
                        sx={{ height: 20, fontSize: 10.5 }}
                      />
                    )}
                  </Box>
                </MenuItem>
              ))}
            </TextField>
          )}
        />

        <Typography variant="caption" color="text.secondary">
          {t("El estado de la licencia se juzga contra la fecha de este viaje, no contra hoy.")}
        </Typography>
      </Box>
    </FormDrawer>
  );
}
