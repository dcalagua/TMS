import type { ReactNode } from "react";
import { Box, Button, CircularProgress, Skeleton as MuiSkeleton, Stack, Typography } from "@mui/material";
import { InboxRounded, ErrorOutlineRounded, RefreshRounded } from "@mui/icons-material";
import { t } from "../../../lib/i18n";

/**
 * Los tres estados que toda pantalla de lista tiene que saber pintar — cargando, error y
 * vacío — en un solo sitio, para que veintitantas pantallas no inventen cada una el suyo.
 */

export function LoadingState({ label, minHeight = 220 }: { label?: string; minHeight?: number | string }) {
  return (
    <Box sx={{ display: "grid", placeItems: "center", minHeight, gap: 1.5, py: 4 }} role="status">
      <CircularProgress size={30} />
      <Typography variant="body2" color="text.secondary">{label ?? t("Cargando...")}</Typography>
    </Box>
  );
}

export function ErrorState({ message, onRetry }: { message: string; onRetry?: () => void }) {
  return (
    <Box sx={{ display: "grid", placeItems: "center", textAlign: "center", py: 5, px: 2, gap: 1 }}>
      <Box sx={{
        width: 52, height: 52, borderRadius: "50%", display: "grid", placeItems: "center",
        bgcolor: (th) => (th.palette.mode === "dark" ? "rgba(240,138,130,.14)" : "rgba(192,57,43,.09)"),
        color: "error.main", "& svg": { fontSize: 28 },
      }}>
        <ErrorOutlineRounded />
      </Box>
      <Typography variant="subtitle1">{t("Algo salió mal")}</Typography>
      <Typography variant="body2" color="text.secondary" sx={{ maxWidth: 460 }}>{message}</Typography>
      {onRetry && (
        <Button onClick={onRetry} startIcon={<RefreshRounded />} size="small" sx={{ mt: 1 }}>
          {t("Reintentar")}
        </Button>
      )}
    </Box>
  );
}

export function EmptyState({
  title, message, action, icon,
}: { title?: string; message?: string; action?: ReactNode; icon?: ReactNode }) {
  return (
    <Box sx={{ display: "grid", placeItems: "center", textAlign: "center", py: 5, px: 2, gap: 1 }}>
      <Box sx={{
        width: 52, height: 52, borderRadius: "50%", display: "grid", placeItems: "center",
        bgcolor: "action.hover", color: "text.disabled", "& svg": { fontSize: 28 },
      }}>
        {icon ?? <InboxRounded />}
      </Box>
      <Typography variant="subtitle1">{title ?? t("Sin resultados")}</Typography>
      {message && (
        <Typography variant="body2" color="text.secondary" sx={{ maxWidth: 460 }}>{message}</Typography>
      )}
      {action && <Box sx={{ mt: 1 }}>{action}</Box>}
    </Box>
  );
}

/** Esqueleto de tabla: mismas columnas y alto de fila que la tabla real, para que la pantalla
 * no dé un salto cuando llegan los datos. */
export function SkeletonTable({ columns = 5, rows = 6 }: { columns?: number; rows?: number }) {
  return (
    <Box sx={{ p: 2 }}>
      <Stack spacing={1.2}>
        {Array.from({ length: rows }).map((_, r) => (
          <Stack key={r} direction="row" spacing={1.5}>
            {Array.from({ length: columns }).map((__, c) => (
              <MuiSkeleton key={c} variant="rounded" height={18} sx={{ flex: c === 0 ? 2 : 1, borderRadius: 1 }} />
            ))}
          </Stack>
        ))}
      </Stack>
    </Box>
  );
}

export { MuiSkeleton as Skeleton };
