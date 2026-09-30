import { Box, Typography } from "@mui/material";
import { StatusChip } from "../../shared/ui/components";
import type { Eligibility, SchedulingReason } from "../../shared/api/schedulingApi";
import { enumLabel } from "../../lib/enums";
import { t } from "../../lib/i18n";
import { ELIGIBILITY_TONE, REASON_HELP, reasonTone } from "./schedulingLabels";

/** Piezas de presentación compartidas por la pantalla de Programación y Liberación y sus paneles. */
export function EligibilityChip({ eligibility }: { eligibility: Eligibility }) {
  return <StatusChip label={enumLabel("eligibility", eligibility)} tone={ELIGIBILITY_TONE[eligibility]} />;
}

/** La lista de motivos de una fila, con qué significa cada uno y el texto del backend debajo. */
export function ReasonList({ reasons }: { reasons: SchedulingReason[] }) {
  if (reasons.length === 0) {
    return <Typography variant="body2" color="text.secondary">{t("Sin observaciones: el pedido es elegible.")}</Typography>;
  }
  return (
    <Box component="ul" sx={{ listStyle: "none", p: 0, m: 0, display: "grid", gap: 1.25 }}>
      {reasons.map((reason) => (
        <Box component="li" key={reason.code} sx={{ display: "grid", gap: 0.5 }}>
          <Box sx={{ display: "flex", gap: 1, alignItems: "center", flexWrap: "wrap" }}>
            <StatusChip label={enumLabel("schedulingReason", reason.code)} tone={reasonTone(reason)} />
            {reason.requiresOverride && (
              <Typography variant="caption" color="text.secondary">{t("Requiere motivo para liberar")}</Typography>
            )}
          </Box>
          <Typography variant="body2">{t(REASON_HELP[reason.code])}</Typography>
          <Typography variant="caption" color="text.secondary">{reason.detail}</Typography>
        </Box>
      ))}
    </Box>
  );
}
