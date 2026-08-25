import { Link } from "react-router-dom";
import { Box, Button, Typography } from "@mui/material";
import { ExploreOffRounded } from "@mui/icons-material";
import { t } from "../lib/i18n";

/** La pantalla que no existe. Ofrece la salida en vez de dejar al usuario mirando un vacío. */
export function NotFoundPage() {
  return (
    <Box sx={{ display: "grid", placeItems: "center", textAlign: "center", py: 10, gap: 1.5 }}>
      <Box sx={{
        width: 64, height: 64, borderRadius: "50%", display: "grid", placeItems: "center",
        bgcolor: "action.hover", color: "text.disabled", "& svg": { fontSize: 34 },
      }}>
        <ExploreOffRounded />
      </Box>
      <Typography variant="h4">404</Typography>
      <Typography variant="h6">{t("Página no encontrada")}</Typography>
      <Typography variant="body2" color="text.secondary" sx={{ maxWidth: 420 }}>
        {t("Esta pantalla no existe.")}
      </Typography>
      <Button component={Link} to="/" variant="contained" sx={{ mt: 1 }}>
        {t("Volver al inicio")}
      </Button>
    </Box>
  );
}
