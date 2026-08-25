import { Box, IconButton, MenuItem, TextField, Tooltip, Typography } from "@mui/material";
import {
  ChevronLeftRounded, ChevronRightRounded, FirstPageRounded, LastPageRounded,
} from "@mui/icons-material";
import type { ReactNode } from "react";
import { hasNextPage, hasPreviousPage, totalPages, type PageResponse } from "../../api/pageResponse";
import { fmtQuantity } from "../../../lib/locale";
import { t } from "../../../lib/i18n";
import { R, T } from "../../../theme";

/** Tamaños ofrecidos. Pocos y redondos: el tamaño de página es una comodidad, no una consulta. */
export const PAGE_SIZE_OPTIONS = [10, 20, 25, 50, 100];

/** El separador de la lista de páginas. No es una página: es un hueco. */
const GAP = -1;

/**
 * Qué números de página se dibujan.
 *
 * Siempre la primera, la última y la vecindad de la actual; lo que queda en medio se resume en
 * un hueco. Con cincuenta páginas, dibujarlas todas parte la franja en tres renglones, y una
 * lista de números que no cabe deja de ser un atajo.
 */
function visiblePages(current: number, last: number): number[] {
  if (last <= 6) return Array.from({ length: last + 1 }, (_, i) => i);

  const around = [current - 1, current, current + 1].filter((n) => n > 0 && n < last);
  const pages = [0, ...around, last];

  const out: number[] = [];
  let previous = -2;
  for (const page of pages) {
    if (page - previous > 1) out.push(GAP);
    out.push(page);
    previous = page;
  }
  return out;
}

interface PaginationProps {
  page: Pick<PageResponse<unknown>, "page" | "size" | "totalElements">;
  onPageChange: (page: number) => void;
  /** Habilita el control de filas por página. Si se omite, no se pinta — que es lo que quiere
   * una pantalla con tamaño de página fijo. */
  onPageSizeChange?: (size: number) => void;
  sizeOptions?: number[];
}

/**
 * La franja de pie de la tabla: cuántas filas están en pantalla, los números de página y los
 * cuatro controles para moverse entre ellas.
 *
 * <h2>Por qué números y no solo «anterior / siguiente»</h2>
 * «1–20 de 340» responde a «¿cuánto hay y cuánto llevo?», y por eso va primero y a la izquierda.
 * Pero con diecisiete páginas, llegar a la última con el botón de siguiente son dieciséis
 * pulsaciones. Los números resuelven el salto, y los botones de primera y última resuelven el
 * caso que de verdad se pide a diario: volver al principio tras filtrar.
 *
 * <p>La página activa se marca con FONDO además de color: una señal que solo depende del color
 * no la ve todo el mundo.
 */
export function Pagination({ page, onPageChange, onPageSizeChange, sizeOptions = PAGE_SIZE_OPTIONS }: PaginationProps) {
  const pages = totalPages(page);

  if (page.totalElements === 0) return null;

  const current = page.page;
  const last = Math.max(0, pages - 1);
  const from = current * page.size + 1;
  const to = Math.min((current + 1) * page.size, page.totalElements);

  const goTo = (n: number) => onPageChange(Math.min(Math.max(0, n), last));

  const arrow = (icon: ReactNode, label: string, target: number, disabled: boolean) => (
    <Tooltip title={label} key={label}>
      {/* El `span` porque un botón deshabilitado no emite los eventos que el tooltip escucha. */}
      <span>
        <IconButton
          size="small" aria-label={label} disabled={disabled} onClick={() => goTo(target)}
          sx={{ width: 30, height: 30, borderRadius: `${R.sm}px`, color: "text.secondary" }}
        >
          {icon}
        </IconButton>
      </span>
    </Tooltip>
  );

  return (
    <Box sx={{
      display: "flex", alignItems: "center", justifyContent: "space-between",
      gap: 1.5, flexWrap: "wrap",
    }}>
      <Box sx={{ display: "flex", alignItems: "center", gap: 1.5, flexWrap: "wrap" }}>
        <Typography sx={{ fontSize: T.micro, color: "text.secondary", fontVariantNumeric: "tabular-nums" }}>
          {t("{{from}}-{{to}} de {{total}}", {
            from: fmtQuantity(from), to: fmtQuantity(to), total: fmtQuantity(page.totalElements),
          })}
        </Typography>

        {onPageSizeChange && (
          <Box sx={{ display: "flex", alignItems: "center", gap: 1 }}>
            <Typography sx={{ fontSize: T.micro, color: "text.secondary" }}>
              {t("Filas por página")}
            </Typography>
            <TextField
              select size="small" value={String(page.size)}
              onChange={(e) => onPageSizeChange(Number(e.target.value))}
              sx={{ width: 78, "& .MuiOutlinedInput-root": { minHeight: 30 } }}
              aria-label={t("Filas por página")}
            >
              {sizeOptions.map((size) => (
                <MenuItem key={size} value={String(size)}>{fmtQuantity(size)}</MenuItem>
              ))}
            </TextField>
          </Box>
        )}
      </Box>

      <Box component="nav" aria-label={t("Paginación")} sx={{ display: "flex", alignItems: "center", gap: "2px" }}>
        {arrow(<FirstPageRounded sx={{ fontSize: 18 }} />, t("Primera página"), 0, !hasPreviousPage(page))}
        {arrow(<ChevronLeftRounded sx={{ fontSize: 19 }} />, t("Página anterior"), current - 1, !hasPreviousPage(page))}

        {/* Los números solo desde `sm`. En un teléfono, siete botones de página no entran y se
            parten en dos renglones: ahí se muestra «Página 6 de 54», que es la misma información
            sin pedir puntería. */}
        <Box sx={{ display: { xs: "none", sm: "flex" }, alignItems: "center", gap: "2px" }}>
          {visiblePages(current, last).map((n, i) => (
            n === GAP ? (
              <Box key={`gap-${i}`} aria-hidden sx={{ px: "4px", color: "text.secondary", fontSize: T.micro }}>
                …
              </Box>
            ) : (
              <Box
                key={n} component="button" type="button"
                onClick={() => goTo(n)}
                aria-label={t("Página {{page}}", { page: fmtQuantity(n + 1) })}
                aria-current={n === current ? "page" : undefined}
                sx={{
                  minWidth: 30, height: 30, px: "8px", borderRadius: `${R.sm}px`,
                  border: 0, cursor: "pointer", fontFamily: "inherit",
                  fontSize: T.micro, fontWeight: n === current ? 800 : 600,
                  fontVariantNumeric: "tabular-nums",
                  bgcolor: n === current ? "action.selected" : "transparent",
                  color: n === current ? "primary.dark" : "text.secondary",
                  "&:hover": { bgcolor: n === current ? "action.selected" : "action.hover" },
                  "&:focus-visible": { outline: "2px solid", outlineColor: "primary.main", outlineOffset: "1px" },
                }}
              >
                {n + 1}
              </Box>
            )
          ))}
        </Box>

        <Typography sx={{
          display: { xs: "block", sm: "none" }, px: 1,
          fontSize: T.micro, color: "text.secondary", fontVariantNumeric: "tabular-nums",
        }}>
          {t("Página {{page}} de {{pages}}", { page: fmtQuantity(current + 1), pages: fmtQuantity(pages) })}
        </Typography>

        {arrow(<ChevronRightRounded sx={{ fontSize: 19 }} />, t("Página siguiente"), current + 1, !hasNextPage(page))}
        {arrow(<LastPageRounded sx={{ fontSize: 18 }} />, t("Última página"), last, !hasNextPage(page))}
      </Box>
    </Box>
  );
}
