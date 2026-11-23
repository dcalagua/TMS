import { Alert, AlertTitle, Button, Typography } from "@mui/material";
import type { RouteStopOptimizationView } from "../../shared/api/routesApi";
import { t } from "../../lib/i18n";
import { fmtDecimal } from "../../lib/locale";

/**
 * El resultado de «Optimizar paradas» (ADR-030) dentro de la sección Paradas. Cuando la propuesta
 * acorta la ruta, el formulario ya la aplicó: aquí solo se dice cuánto se ahorra, y «Descartar»
 * devuelve las paradas al orden de antes. La ruta no cambia hasta que se guarda.
 *
 * Si la propuesta no acorta nada no se reordenó nada, y se dice así.
 */
export function RouteStopProposal({ proposal, onDiscard }: {
  proposal: RouteStopOptimizationView;
  onDiscard: () => void;
}) {
  const current = fmtDecimal(proposal.currentDistanceKm);
  const saving = proposal.currentDistanceKm > 0
    ? Math.round((1 - proposal.proposedDistanceKm / proposal.currentDistanceKm) * 100)
    : 0;

  return (
    <Alert
      severity={proposal.improved ? "success" : "info"}
      icon={false}
      action={<Button size="small" color="inherit" onClick={onDiscard}>{t("Descartar")}</Button>}
      sx={{ alignItems: "flex-start", "& .MuiAlert-action": { pt: 0.25 } }}
    >
      <AlertTitle sx={{ fontWeight: 700 }}>{t("Orden propuesto")}</AlertTitle>
      <Typography variant="body2" sx={{ fontWeight: 600, fontVariantNumeric: "tabular-nums" }}>
        {proposal.improved
          ? t("{{proposed}} km en lugar de {{current}} km ({{saving}} % menos)", {
            proposed: fmtDecimal(proposal.proposedDistanceKm), current, saving,
          })
          : t("El orden actual ya es el más corto que encontró el asistente ({{current}} km).", { current })}
      </Typography>
      {/* Sin proveedor de rutas (ADR-010) toda distancia es una estimación, y se dice. */}
      {proposal.estimated && (
        <Typography variant="caption" color="text.secondary" sx={{ display: "block", mt: 0.5 }}>
          {t("Distancias estimadas a partir de las coordenadas; el recorrido real por carretera puede variar.")}
        </Typography>
      )}
    </Alert>
  );
}
