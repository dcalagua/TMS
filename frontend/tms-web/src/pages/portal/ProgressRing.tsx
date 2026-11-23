import { Box, CircularProgress, Typography } from "@mui/material";

/** Anillo de progreso: `done` sobre `total` — en la tarjeta de un viaje, las entregas registradas. */
export function ProgressRing({ done, total, label, size = 60 }: {
  done: number;
  total: number;
  /** Lo que mide, para lectores de pantalla: «2 de 5 entregas registradas». */
  label: string;
  size?: number;
}) {
  const value = total === 0 ? 0 : Math.round((done / total) * 100);
  const complete = total > 0 && done >= total;
  return (
    <Box sx={{ position: "relative", width: size, height: size, flexShrink: 0 }} role="img" aria-label={label}>
      <CircularProgress variant="determinate" value={100} size={size} thickness={5} sx={{ color: "action.hover", position: "absolute" }} />
      <CircularProgress variant="determinate" value={value} size={size} thickness={5} color={complete ? "success" : "primary"}
        sx={{ position: "absolute", "& circle": { strokeLinecap: "round" } }} />
      <Box sx={{ position: "absolute", inset: 0, display: "grid", placeItems: "center" }}>
        <Typography sx={{ fontWeight: 800, fontSize: size < 52 ? 12.5 : 15, fontVariantNumeric: "tabular-nums" }}>
          {done}/{total}
        </Typography>
      </Box>
    </Box>
  );
}
