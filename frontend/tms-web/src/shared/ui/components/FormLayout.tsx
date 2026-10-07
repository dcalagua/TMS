import { useEffect, useState, type ReactNode } from "react";
import { Box, ButtonBase, Typography } from "@mui/material";
import { alpha, type Theme } from "@mui/material/styles";
import {
  CheckBoxOutlineBlankRounded, CheckBoxRounded, RadioButtonCheckedRounded, RadioButtonUncheckedRounded,
} from "@mui/icons-material";
import { R, T } from "../../../theme";

/**
 * Las piezas con las que se arma un panel de formulario (plantillas de «Formularios y detalles v2»).
 *
 * Una sección es una fila: a la izquierda su título y una línea de ayuda, a la derecha sus campos.
 * En pantallas estrechas el título pasa encima. Las secciones se separan con una línea fina, y la
 * última no la lleva.
 */
export function FormSection({ id, title, help, children, actions }: {
  /** Ancla para el índice de secciones de un formulario largo. */
  id?: string;
  title: string;
  help?: ReactNode;
  children: ReactNode;
  /** Algo a la derecha del título en pantallas estrechas, o bajo la ayuda en anchas (p. ej. «Añadir línea»). */
  actions?: ReactNode;
}) {
  return (
    <Box
      component="section"
      id={id}
      aria-label={title}
      sx={{
        // Se decide por el ancho del PANEL, no de la pantalla: un panel de 560 px en un monitor
        // ancho no tiene sitio para la columna lateral. El cuerpo del FormDrawer es el contenedor.
        display: "grid", gap: 2.25, py: 2.25, scrollMarginTop: 56, gridTemplateColumns: "1fr",
        "@container formbody (min-width: 620px)": { gridTemplateColumns: "190px minmax(0, 1fr)", gap: 3 },
        borderBottom: "1px solid", borderColor: "divider",
        "&:last-of-type": { borderBottom: 0 },
      }}
    >
      <Box sx={{ minWidth: 0 }}>
        <Typography component="h3" sx={{ fontSize: T.body + 1, fontWeight: 800, lineHeight: 1.35 }}>{title}</Typography>
        {help && (
          <Typography sx={{ fontSize: T.micro + 0.5, color: "text.secondary", mt: 0.5, lineHeight: 1.45 }}>{help}</Typography>
        )}
        {actions && <Box sx={{ mt: 1.25 }}>{actions}</Box>}
      </Box>
      <Box sx={{ display: "grid", gap: 1.75, minWidth: 0, alignContent: "start" }}>{children}</Box>
    </Box>
  );
}

/** Una fila de campos dentro de una sección: 2 o 3 columnas, una sola en el teléfono. */
export function FormRow({ cols = 2, children, template }: {
  cols?: 1 | 2 | 3 | 4; children: ReactNode;
  /** Plantilla de columnas a medida (p. ej. "180px 1fr") para cuando un campo es corto por naturaleza. */
  template?: string;
}) {
  return (
    <Box sx={{
      display: "grid", gap: 1.5, minWidth: 0, alignItems: "start", gridTemplateColumns: "1fr",
      "@container formbody (min-width: 400px)": { gridTemplateColumns: template ?? `repeat(${cols}, minmax(0, 1fr))` },
    }}>
      {children}
    </Box>
  );
}

/**
 * Índice de secciones de un formulario largo: una fila de pestañas fija arriba del cuerpo que lleva
 * a cada sección y marca la que se está viendo. Se coloca como primer hijo del `FormDrawer`.
 */
export function SectionIndex({ sections }: { sections: { id: string; label: string }[] }) {
  const [active, setActive] = useState(sections[0]?.id ?? "");

  useEffect(() => {
    const nodes = sections.map((s) => document.getElementById(s.id)).filter((n): n is HTMLElement => n !== null);
    if (nodes.length === 0 || typeof IntersectionObserver === "undefined") return;
    const observer = new IntersectionObserver(
      (entries) => {
        const visible = entries.filter((e) => e.isIntersecting).sort((a, b) => a.boundingClientRect.top - b.boundingClientRect.top);
        if (visible[0]) setActive(visible[0].target.id);
      },
      { rootMargin: "-56px 0px -60% 0px" },
    );
    nodes.forEach((n) => observer.observe(n));
    return () => observer.disconnect();
  }, [sections]);

  return (
    <Box
      component="nav"
      aria-label="Secciones"
      sx={{
        position: "sticky", top: -20, zIndex: 2, mx: -2.5, mt: -2.5, mb: 0.5, px: 2.5,
        bgcolor: "background.paper", borderBottom: "1px solid", borderColor: "divider",
        display: "flex", gap: 0.5, overflowX: "auto", scrollbarWidth: "none",
      }}
    >
      {sections.map((section) => {
        const isActive = section.id === active;
        return (
          <ButtonBase
            key={section.id}
            onClick={() => {
              setActive(section.id);
              document.getElementById(section.id)?.scrollIntoView({ behavior: "smooth", block: "start" });
            }}
            aria-current={isActive ? "true" : undefined}
            sx={{
              px: 1.25, py: 1.25, flexShrink: 0, fontSize: T.body - 0.5, whiteSpace: "nowrap",
              fontWeight: isActive ? 700 : 500, color: isActive ? "text.primary" : "text.secondary",
              boxShadow: (th: Theme) => (isActive ? `inset 0 -2px 0 ${th.palette.primary.main}` : "none"),
              "&:hover": { color: "text.primary" },
            }}
          >
            {section.label}
          </ButtonBase>
        );
      })}
    </Box>
  );
}

/** Aquello sobre lo que actúa una acción rápida: identidad, estado y una línea de contexto. */
export function ContextCard({ title, status, detail }: { title: ReactNode; status?: ReactNode; detail?: ReactNode }) {
  return (
    <Box sx={{ px: 1.75, py: 1.5, borderRadius: `${R.md}px`, bgcolor: "action.hover", display: "grid", gap: 0.5 }}>
      <Box sx={{ display: "flex", alignItems: "center", gap: 1, minWidth: 0 }}>
        <Typography sx={{ fontWeight: 700, fontVariantNumeric: "tabular-nums", flex: 1, minWidth: 0 }} noWrap>{title}</Typography>
        {status}
      </Box>
      {detail && <Typography sx={{ fontSize: T.micro + 0.5, color: "text.secondary" }}>{detail}</Typography>}
    </Box>
  );
}

type OptionTone = "primary" | "error" | "warning" | "neutral";

/**
 * Una opción excluyente con su efecto explicado: un radio grande. Para decisiones cuyo efecto no
 * cabe en una etiqueta (bloqueante o solo nota, origen o destino).
 */
export function OptionCard({ selected, onSelect, title, description, tone = "primary", disabled, role = "radio" }: {
  selected: boolean; onSelect: () => void; title: string; description?: ReactNode;
  tone?: OptionTone; disabled?: boolean;
  /** "checkbox" para opciones que se pueden marcar a la vez. */
  role?: "radio" | "checkbox";
}) {
  const color = (th: Theme) => (tone === "neutral" ? th.palette.text.secondary : th.palette[tone].main);
  return (
    <ButtonBase
      role={role}
      aria-checked={selected}
      disabled={disabled}
      onClick={onSelect}
      sx={(th) => ({
        width: "100%", justifyContent: "flex-start", alignItems: "flex-start", textAlign: "left", gap: 1.25,
        px: 1.5, py: 1.25, borderRadius: `${R.md}px`, border: "1px solid",
        borderColor: selected ? color(th) : th.palette.divider,
        bgcolor: selected ? alpha(color(th), th.palette.mode === "dark" ? 0.16 : 0.07) : "background.paper",
        opacity: disabled ? 0.6 : 1,
        "&:hover": { borderColor: color(th) },
        "&.Mui-focusVisible": { outline: "2px solid", outlineColor: th.palette.primary.main, outlineOffset: "1px" },
      })}
    >
      <Box sx={(th) => ({ color: selected ? color(th) : th.palette.text.disabled, display: "flex", mt: "1px", "& svg": { fontSize: 19 } })}>
        {role === "checkbox"
          ? (selected ? <CheckBoxRounded /> : <CheckBoxOutlineBlankRounded />)
          : (selected ? <RadioButtonCheckedRounded /> : <RadioButtonUncheckedRounded />)}
      </Box>
      <Box sx={{ minWidth: 0 }}>
        <Typography sx={(th) => ({ fontWeight: 700, fontSize: T.body, color: selected && tone !== "neutral" ? color(th) : "text.primary" })}>
          {title}
        </Typography>
        {description && <Typography sx={{ fontSize: T.micro + 0.5, color: "text.secondary", mt: 0.25 }}>{description}</Typography>}
      </Box>
    </ButtonBase>
  );
}

/** El texto al inicio del pie de un formulario: cuándo se actualizó, qué falta, una advertencia corta. */
export function FormMeta({ children }: { children: ReactNode }) {
  return <Typography sx={{ fontSize: T.micro + 0.5, color: "text.secondary" }}>{children}</Typography>;
}
