import { Alert, Box, Typography } from "@mui/material";
import { MyLocationRounded, SpeedRounded } from "@mui/icons-material";
import type { TripTrackingView } from "../../shared/api/trackingApi";
import { AppCard, DetailGrid, DetailItem, LoadingState } from "../../shared/ui/components";
import { t } from "../../lib/i18n";
import { fmtDateTime, fmtDecimal, fmtMinutes } from "../../lib/locale";

interface TripTrackingCardProps {
  tracking: TripTrackingView | undefined;
  loading: boolean;
  /** La lectura de tracking es la única de esta pantalla que puede fallar legítimamente mientras
   * todo lo demás funciona: un despliegue sin feed no es un error del operador. */
  failed: boolean;
}

/** Cuánto hace de la última posición, en minutos. La antigüedad es la mitad del dato: una
 * posición de hace tres horas y una de hace tres minutos dicen cosas muy distintas. */
function ageMinutes(occurredAt: string): number | null {
  const then = new Date(occurredAt).getTime();
  if (Number.isNaN(then)) return null;
  return Math.max(0, Math.round((Date.now() - then) / 60000));
}

/**
 * Dónde está el vehículo.
 *
 * Los tres casos de "no hay posición" se distinguen a propósito, porque solo uno merece una
 * llamada de teléfono:
 *
 *  - `trackable` falso: el envío no ha salido, o se canceló;
 *  - `providerConfigured` falso: este despliegue no tiene feed en absoluto;
 *  - los dos ciertos y `lastPosition` nulo: hay feed y no ha dicho nada de este envío.
 *
 * Un único campo vacío le diría al despachador cuál de los tres es: nada.
 */
export function TripTrackingCard({ tracking, loading, failed }: TripTrackingCardProps) {
  return (
    <AppCard
      title={
        <Box sx={{ display: "flex", alignItems: "center", gap: 1 }}>
          <MyLocationRounded sx={{ fontSize: 19, color: "text.disabled" }} />
          {t("Ubicación del vehículo")}
        </Box>
      }
    >
      {loading ? (
        <LoadingState minHeight={100} />
      ) : failed || !tracking ? (
        <Typography variant="body2" color="text.secondary">
          {t("No se pudo consultar la ubicación en este momento.")}
        </Typography>
      ) : !tracking.providerConfigured ? (
        <Alert severity="info">
          {t("Esta instalación no tiene un proveedor de rastreo configurado.")}
        </Alert>
      ) : !tracking.trackable ? (
        <Typography variant="body2" color="text.secondary">
          {t("El envío todavía no está en ruta.")}
        </Typography>
      ) : tracking.lastPosition === null ? (
        // El único de los tres que merece una llamada: hay feed, y no dice nada de este camión.
        <Alert severity="warning">
          {t("Hay rastreo configurado, pero no ha reportado ninguna posición de este envío.")}
        </Alert>
      ) : (
        <>
          <DetailGrid columns={2}>
            <DetailItem
              label={t("Última posición")}
              value={`${fmtDecimal(tracking.lastPosition.latitude, 5)}, ${fmtDecimal(tracking.lastPosition.longitude, 5)}`}
            />
            <DetailItem label={t("Reportada")} value={fmtDateTime(tracking.lastPosition.occurredAt)} />
            <DetailItem
              label={t("Antigüedad")}
              value={(() => {
                const age = ageMinutes(tracking.lastPosition.occurredAt);
                return age === null ? "-" : fmtMinutes(age);
              })()}
            />
            <DetailItem
              label={t("Velocidad")}
              value={tracking.lastPosition.speedKph === null ? null : (
                <Box sx={{ display: "flex", alignItems: "center", gap: 0.5 }}>
                  <SpeedRounded sx={{ fontSize: 16, color: "text.disabled" }} />
                  {fmtDecimal(tracking.lastPosition.speedKph, 0)} km/h
                </Box>
              )}
            />
          </DetailGrid>

          <Typography variant="caption" color="text.disabled" sx={{ display: "block", mt: 1.5 }}>
            {t("Proveedor")}: {tracking.lastPosition.provider}
            {tracking.vehicleLicensePlate && ` · ${tracking.vehicleLicensePlate}`}
          </Typography>
        </>
      )}
    </AppCard>
  );
}
