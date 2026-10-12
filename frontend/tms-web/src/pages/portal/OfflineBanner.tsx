import { Box, Typography } from "@mui/material";
import { CloudOffRounded } from "@mui/icons-material";
import { R } from "../../theme";
import { staleLabel } from "./portalUi";

/**
 * Lo que se ve cuando la pantalla enseña la copia guardada en el teléfono: que no hay conexión y
 * de cuándo son los datos. Sin hora, un transportista no sabe si lo que lee sigue valiendo.
 */
export function OfflineBanner({ savedAt }: { savedAt: string | null }) {
  if (!savedAt) return null;
  return (
    <Box role="status" sx={{
      display: "flex", alignItems: "center", gap: 1, px: 1.5, py: 1.25, borderRadius: `${R.md}px`,
      bgcolor: (theme) => (theme.palette.mode === "dark" ? "rgba(232,163,23,.16)" : "#FBEEDD"),
      color: (theme) => (theme.palette.mode === "dark" ? "#F0C25E" : "#8A5200"),
    }}>
      <CloudOffRounded sx={{ fontSize: 22, flexShrink: 0 }} />
      <Typography sx={{ fontWeight: 700, fontSize: 15, lineHeight: 1.3 }}>{staleLabel(savedAt)}</Typography>
    </Box>
  );
}
