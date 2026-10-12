import { Box, CircularProgress, Typography } from "@mui/material";
import { t } from "../../lib/i18n";

/** Anillo de progreso: documentos reportados (con cualquier resultado) sobre el total. */
export function ProgressRing({ done, total, size = 60 }: { done: number; total: number; size?: number }) {
  const value = total === 0 ? 0 : Math.round((done / total) * 100);
  const complete = total > 0 && done >= total;
  return (
    <Box sx={{ position: "relative", width: size, height: size, flexShrink: 0 }}
      role="img" aria-label={t("{{done}} de {{total}} documentos reportados", { done, total })}>
      <CircularProgress variant="determinate" value={100} size={size} thickness={5} sx={{ color: "action.hover", position: "absolute" }} />
      <CircularProgress variant="determinate" value={value} size={size} thickness={5} color={complete ? "success" : "primary"}
        sx={{ position: "absolute", "& circle": { strokeLinecap: "round" } }} />
      <Box sx={{ position: "absolute", inset: 0, display: "grid", placeItems: "center" }}>
        <Typography sx={{ fontWeight: 800, fontSize: 15, fontVariantNumeric: "tabular-nums" }}>{done}/{total}</Typography>
      </Box>
    </Box>
  );
}
