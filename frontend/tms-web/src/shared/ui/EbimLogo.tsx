import { Box, Typography } from "@mui/material";
import { useTheme } from "@mui/material/styles";
import { keyframes } from "@mui/system";

// Animación "gira y para" (estándar de marca de la suite).
const spin = keyframes`
  0% { transform: rotate(0); }
  16% { transform: rotate(360deg); }
  100% { transform: rotate(360deg); }
`;

/** Isotipo EBIM — swirl de 6 figuras (estándar ÚNICO de la suite). */
export function EbimMark({ size = 28, color = "#0A5A52", animated = false }: { size?: number; color?: string; animated?: boolean }) {
  return (
    <Box
      component="span"
      sx={{
        display: "inline-flex", transformOrigin: "center", lineHeight: 0,
        ...(animated ? {
          animation: `${spin} 3.6s cubic-bezier(.66,0,.2,1) infinite`,
          "@media (prefers-reduced-motion: reduce)": { animation: "none" },
        } : {}),
      }}
    >
      <svg width={size} height={size} viewBox="0 0 200 200" fill="none" aria-label="EBIM">
        <circle cx="100" cy="38" r="26" fill={color} />
        <rect x="127.7" y="43" width="52" height="52" rx="4" transform="rotate(15 153.7 69)" fill={color} />
        <rect x="127.7" y="105" width="52" height="52" rx="14" transform="rotate(-10 153.7 131)" fill={color} />
        <rect x="74" y="136" width="52" height="52" rx="13" transform="rotate(45 100 162)" fill={color} />
        <rect x="20.3" y="105" width="52" height="52" rx="16" transform="rotate(8 46.3 131)" fill={color} />
        <rect x="20.3" y="43" width="52" height="52" rx="23" transform="rotate(-6 46.3 69)" fill={color} />
      </svg>
    </Box>
  );
}

/** Logotipo EBIM: isotipo + la palabra "EBIM". */
export function EbimLogo({ color = "#0A5A52", markSize = 30, fontSize = 26, animated = false }: { color?: string; markSize?: number; fontSize?: number; animated?: boolean }) {
  return (
    <Box sx={{ display: "flex", alignItems: "center", gap: 1 }}>
      <EbimMark size={markSize} color={color} animated={animated} />
      <Typography sx={{ fontWeight: 800, fontSize, letterSpacing: 0.5, color, lineHeight: 1 }}>EBIM</Typography>
    </Box>
  );
}

/**
 * Lockup de producto EBIM: isotipo + "<App>" héroe + "BY EBIM" debajo (estándar de suite).
 * El primer carácter (la "e" de eTMS) se pinta con el color de acento del tema, igual que hacen
 * eGMAO, eSupplier y eExpense.
 */
export function ProductLockup({
  name = "eTMS", nameSize = 22, markSize = 15, color, accentColor, animated = false,
}: { name?: string; nameSize?: number; markSize?: number; color?: string; accentColor?: string; animated?: boolean }) {
  const theme = useTheme();
  const accent = accentColor ?? theme.palette.primary.main;
  const first = name.slice(0, 1);
  const rest = name.slice(1);
  return (
    <Box sx={{ display: "flex", alignItems: "center", gap: 1 }}>
      <EbimMark size={markSize + 6} color={color} animated={animated} />
      <Box sx={{ display: "flex", flexDirection: "column", lineHeight: 1 }}>
        <Typography sx={{ fontWeight: 800, fontSize: nameSize, letterSpacing: -0.4, lineHeight: 1, color: color ?? "text.primary" }}>
          <Box component="span" sx={{ color: accent }}>{first}</Box>{rest}
        </Typography>
        {/*
          Sin `opacity` (JOB 26). `text.secondary` ya está calculado para cumplir AA sobre el fondo;
          bajarlo al 85% lo dejaba en **1.72:1** contra 4.5:1 exigidos, medido por axe en Chromium.
          Es el fallo más fácil de introducir sin querer: la opacidad no cambia el color declarado,
          así que ninguna revisión de la paleta lo encuentra — sólo medirlo ya renderizado.
        */}
        <Typography sx={{
          fontSize: Math.max(8.5, nameSize * 0.42), fontWeight: 700, letterSpacing: "0.22em",
          color: color ?? "text.secondary", lineHeight: 1.5,
        }}>
          BY EBIM
        </Typography>
      </Box>
    </Box>
  );
}

/** El nombre del producto, en un solo sitio. La "e" va más ligera que el resto, como en toda la suite. */
export const BRAND_NAME = { prefix: "e", name: "TMS", full: "eTMS", owner: "by EBIM", mark: "eT" } as const;
