import { useState, type ReactNode } from "react";
import { Badge, Box, Button, Chip, Collapse, Paper, Typography, useMediaQuery, useTheme } from "@mui/material";
import { FilterAltRounded, SearchRounded, CloseRounded } from "@mui/icons-material";
import { t } from "../../../lib/i18n";

export interface ToolbarProps {
  /** Siempre visible: el buscador y la acción principal. */
  primary?: ReactNode;
  /** Los controles de filtro secundarios, plegables por debajo de `md`. */
  filters?: ReactNode;
  onApply?: () => void;
  onReset?: () => void;
  /** Se muestra junto al desplegador de filtros, p. ej. "3". */
  activeFilterCount?: number;
}

/**
 * La franja de controles encima de una lista. En pantalla ancha los filtros van en una línea
 * junto al buscador; por debajo de `md` se pliegan tras un botón, porque cinco inputs apilados
 * entre un operador y sus resultados no son una barra de filtros, son un muro.
 */
export function Toolbar({ primary, filters, onApply, onReset, activeFilterCount = 0 }: ToolbarProps) {
  const theme = useTheme();
  const isMobile = useMediaQuery(theme.breakpoints.down("md"));
  const [expanded, setExpanded] = useState(false);
  const showFilters = !isMobile || expanded;

  return (
    <Box sx={{ mb: 2 }}>
      <Box sx={{ display: "flex", flexWrap: "wrap", alignItems: "center", gap: 1, mb: filters ? 1.5 : 0 }}>
        {primary}
        {filters && isMobile && (
          <Button
            size="small" variant="outlined" sx={{ ml: "auto" }}
            onClick={() => setExpanded((v) => !v)}
            aria-expanded={expanded}
            startIcon={
              <Badge badgeContent={activeFilterCount || undefined} color="primary">
                <FilterAltRounded />
              </Badge>
            }
          >
            {t("Filtros")}
          </Button>
        )}
      </Box>

      {filters && (
        <Collapse in={showFilters} unmountOnExit={false}>
          <Paper
            component="form"
            variant="outlined"
            onSubmit={(e) => { e.preventDefault(); onApply?.(); }}
            sx={{
              borderRadius: "10px", p: 1.5,
              display: "flex", flexWrap: "wrap", alignItems: "flex-end", gap: 1.5,
            }}
          >
            {filters}
            {(onApply || onReset) && (
              <Box sx={{ display: "flex", gap: 1, ml: { md: "auto" } }}>
                {onReset && (
                  <Button size="small" variant="outlined" onClick={onReset} startIcon={<CloseRounded />}>
                    {t("Limpiar")}
                  </Button>
                )}
                {onApply && (
                  <Button size="small" type="submit" variant="contained" startIcon={<SearchRounded />}>
                    {t("Aplicar filtros")}
                  </Button>
                )}
              </Box>
            )}
          </Paper>
        </Collapse>
      )}
    </Box>
  );
}

export interface FilterChip {
  key: string;
  /** Qué es el filtro, p. ej. "Tipo". */
  label: string;
  /** A qué está puesto, ya traducido — nunca un enum crudo ni un código. */
  value: string;
  /** Limpia este filtro. Se omite para uno que no se pueda quitar por separado. */
  onClear?: () => void;
}

/**
 * Los filtros que están estrechando la lista, como chips que se pueden quitar.
 *
 * Una barra de filtros guarda sus valores en sus propios inputs, que están tres líneas más
 * arriba del conteo de resultados y son fáciles de pasar por alto. Cuando una lista se ve
 * vacía o corta, "¿por qué estoy filtrando?" es la primera pregunta, y esto la contesta sin que
 * el usuario relea cuatro controles — y le deja soltar uno sin buscar cuál lo tenía.
 */
export function FilterChips({ chips, onClearAll }: { chips: FilterChip[]; onClearAll?: () => void }) {
  if (chips.length === 0) return null;

  return (
    <Box sx={{ display: "flex", flexWrap: "wrap", alignItems: "center", gap: 0.75, mb: 2 }}>
      <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 700 }}>
        {t("Filtros:")}
      </Typography>
      {chips.map((chip) => (
        <Chip
          key={chip.key}
          size="small"
          variant="outlined"
          onDelete={chip.onClear}
          label={
            <>
              <Box component="span" sx={{ color: "text.secondary", fontWeight: 600 }}>{chip.label}: </Box>
              {chip.value}
            </>
          }
          sx={{ maxWidth: 280 }}
        />
      ))}
      {onClearAll && chips.length > 1 && (
        <Button size="small" onClick={onClearAll} sx={{ minHeight: 0, py: 0.25 }}>
          {t("Quitar todos")}
        </Button>
      )}
    </Box>
  );
}
