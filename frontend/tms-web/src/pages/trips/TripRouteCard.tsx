import { Alert, Box, Chip, Divider, Tooltip, Typography } from "@mui/material";
import { RouteRounded, InfoOutlined } from "@mui/icons-material";
import { AppCard } from "../../shared/ui/components";
import type { TripRouteMetrics } from "../../shared/api/planningApi";
import { fmtDecimal } from "../../lib/locale";
import { t } from "../../lib/i18n";

/**
 * Cuánto conduce el viaje y cuánto tarda (migración V38).
 *
 * <h2>Por qué dice de dónde salen las cifras</h2>
 * Sin un proveedor de rutas configurado, la distancia es una estimación: línea recta entre
 * coordenadas por un factor de carretera. Es útil — antes de V38 el producto no sabía responder
 * "cuánto conduce este viaje" en absoluto — pero no es una medición, y un número sin esa etiqueta
 * acaba usado para prometerle una hora a un cliente.
 *
 * <h2>Por qué los tramos no medidos se cuentan en voz alta</h2>
 * Un destino sin coordenadas no rompe la pantalla: se pierde ese tramo y el total sale corto. Un
 * total corto y silencioso es peor que uno que dice cuánto le falta.
 */
export function TripRouteCard({ routing }: { routing: TripRouteMetrics }) {
  const hasLegs = routing.legs.length > 0;

  return (
    <AppCard
      title={
        <Box sx={{ display: "flex", alignItems: "center", gap: 1 }}>
          <RouteRounded fontSize="small" />
          <span>{t("Recorrido")}</span>
        </Box>
      }
      actions={
        routing.estimated ? (
          <Tooltip title={t("Sin proveedor de rutas configurado: las distancias se estiman a partir de las coordenadas.")}>
            <Chip size="small" color="warning" variant="outlined" label={t("Estimado")} icon={<InfoOutlined />} />
          </Tooltip>
        ) : routing.provider ? (
          <Chip size="small" variant="outlined" label={routing.provider} />
        ) : null
      }
    >
      {!hasLegs ? (
        <Typography variant="body2" color="text.secondary">
          {t("Este viaje todavía no tiene paradas con coordenadas para medir.")}
        </Typography>
      ) : (
        <>
          <Box sx={{ display: "flex", gap: 4, flexWrap: "wrap" }}>
            <Box>
              <Typography variant="caption" color="text.secondary" sx={{ display: "block" }}>
                {t("Distancia")}
              </Typography>
              <Typography variant="h6" sx={{ fontVariantNumeric: "tabular-nums", fontWeight: 800 }}>
                {fmtDecimal(routing.totalDistanceKm)} {t("km")}
              </Typography>
            </Box>
            <Box>
              <Typography variant="caption" color="text.secondary" sx={{ display: "block" }}>
                {t("Tiempo de conducción")}
              </Typography>
              <Typography variant="h6" sx={{ fontVariantNumeric: "tabular-nums", fontWeight: 800 }}>
                {formatMinutes(routing.totalMinutes)}
              </Typography>
            </Box>
          </Box>

          {!routing.complete && (
            <Alert severity="warning" variant="outlined" sx={{ mt: 2 }}>
              {t("{{count}} tramo(s) sin medir porque falta la coordenada de una ubicación. La distancia mostrada es menor que la real.", {
                count: routing.unmeasurableLegs,
              })}
            </Alert>
          )}

          <Divider sx={{ my: 2 }} />
          <Box sx={{ display: "grid", gap: 0.75 }}>
            {routing.legs.map((leg) => (
              <Box
                key={`${leg.fromStopSequence ?? "origin"}-${leg.toStopSequence}`}
                sx={{ display: "flex", alignItems: "baseline", justifyContent: "space-between", gap: 1 }}
              >
                <Typography variant="body2" noWrap sx={{ minWidth: 0 }}>
                  {leg.fromLabel} → {leg.toLabel}
                </Typography>
                <Typography
                  variant="caption"
                  color="text.secondary"
                  sx={{ fontVariantNumeric: "tabular-nums", whiteSpace: "nowrap" }}
                >
                  {fmtDecimal(leg.distanceKm)} {t("km")} · {formatMinutes(leg.travelMinutes)}
                </Typography>
              </Box>
            ))}
          </Box>

          <Typography variant="caption" color="text.secondary" sx={{ display: "block", mt: 2 }}>
            {t("Solo conducción. El tiempo de servicio en cada parada no está incluido.")}
          </Typography>
        </>
      )}
    </AppCard>
  );
}

/** `95` → `1 h 35 min`. Horas y minutos porque nadie lee un viaje en minutos sueltos. */
function formatMinutes(minutes: number): string {
  if (!Number.isFinite(minutes) || minutes <= 0) return t("0 min");
  const hours = Math.floor(minutes / 60);
  const rest = minutes % 60;
  if (hours === 0) return t("{{m}} min", { m: rest });
  if (rest === 0) return t("{{h}} h", { h: hours });
  return t("{{h}} h {{m}} min", { h: hours, m: rest });
}
