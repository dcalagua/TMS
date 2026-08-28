import { useState } from "react";
import { Alert, Button, Stack, TextField, Typography } from "@mui/material";
import { MyLocationRounded } from "@mui/icons-material";
import { FormDrawer } from "../../shared/ui/components";
import { setLocationGeofence, type LocationView } from "../../shared/api/locationsApi";
import type { ApiError } from "../../shared/api/httpClient";
import { describeApiError } from "../../shared/api/problemMessages";
import { notifyError, notifySuccess } from "../../lib/ui";
import { t } from "../../lib/i18n";

interface GeofenceDrawerProps {
  companyId: string;
  location: LocationView;
  onClose: () => void;
  onSaved: () => void;
}

/**
 * El círculo alrededor de un sitio (migración V43, ADR-011).
 *
 * <h2>Por qué es su propia pantalla y no un campo del formulario</h2>
 * Un geocerco se define una vez al dar de alta el sitio. Meterlo en la edición general haría que
 * cada corrección de dirección reenviara un radio que nadie miró, y el backend lo separó por la
 * misma razón.
 *
 * <h2>Qué NO hace</h2>
 * ADR-007 sigue en pie: una posición dentro de este círculo **informa a una persona y no mueve
 * ningún ciclo de vida**. No habilita ninguna transición, y la llegada que vale sigue siendo la que
 * registra quien llegó. El texto de la pantalla lo dice para que nadie configure esto esperando
 * detección automática de llegadas.
 */
export function GeofenceDrawer({ companyId, location, onClose, onSaved }: GeofenceDrawerProps) {
  const [radius, setRadius] = useState(location.geofenceRadiusM?.toString() ?? "");
  const [submitting, setSubmitting] = useState(false);
  const [touched, setTouched] = useState(false);

  const parsed = radius.trim() === "" ? null : Number(radius);
  const invalid = parsed !== null && (Number.isNaN(parsed) || parsed < 25 || parsed > 20000);
  const hasCoordinates = location.latitude !== null && location.longitude !== null;

  async function submit() {
    setSubmitting(true);
    try {
      await setLocationGeofence(companyId, location.id, parsed);
      notifySuccess(parsed === null ? t("Geocerco quitado") : t("Geocerco guardado"));
      onSaved();
    } catch (error) {
      notifyError(t("No se pudo guardar el geocerco"), describeApiError(error as ApiError));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <FormDrawer
      open
      title={t("Geocerco")}
      subtitle={`${location.code} · ${location.name}`}
      icon={<MyLocationRounded />}
      onClose={onClose}
      dirty={touched}
      size="sm"
      footer={
        <>
          <Button onClick={onClose} disabled={submitting}>{t("Cancelar")}</Button>
          <Button
            variant="contained"
            disabled={invalid || submitting || !hasCoordinates}
            onClick={() => void submit()}
          >
            {submitting ? t("Guardando...") : t("Guardar")}
          </Button>
        </>
      }
    >
      <Stack spacing={2}>
        {!hasCoordinates && (
          <Alert severity="warning" variant="outlined">
            {t("Este sitio no tiene coordenadas, así que un círculo a su alrededor sería un círculo alrededor de nada. Ponle latitud y longitud primero.")}
          </Alert>
        )}

        <TextField
          size="small" type="number" label={t("Radio (metros)")} value={radius}
          onChange={(e) => { setRadius(e.target.value); setTouched(true); }}
          disabled={!hasCoordinates}
          error={invalid}
          helperText={invalid
            ? t("Entre 25 y 20000 metros.")
            : t("Vacío quita el geocerco. Mínimo 25 m porque el GPS de consumo no es más preciso que eso.")}
        />

        <Alert severity="info" variant="outlined">
          <Typography variant="body2">
            {t("Un geocerco informa: sirve para ver que un vehículo reportó una posición dentro del círculo. No cambia el estado de ninguna parada ni registra llegadas por sí solo — la llegada la sigue registrando quien llegó.")}
          </Typography>
        </Alert>
      </Stack>
    </FormDrawer>
  );
}
