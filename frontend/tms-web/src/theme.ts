import { createTheme, darken, lighten, getContrastRatio, type Theme } from "@mui/material/styles";

/** La familia de la suite. Se declara una vez y se reparte a todas las variantes: MUI no
 *  hereda `fontFamily` en las que traen la suya de fábrica. */
const FONT = "'DM Sans', system-ui, -apple-system, 'Segoe UI', sans-serif";

declare module "@mui/material/styles" {
  interface Palette { accentDeep: string; sidebarText: string }
  interface PaletteOptions { accentDeep?: string; sidebarText?: string }
}

// ─────────────────────────────────────────────────────────────────────────────
// Paleta oficial EBIM — accesible (WCAG AA). Ratios verificados.
// "Brand" = identidad (fondos/decoración). "Acción" = botones/texto/iconos (AA).
// ─────────────────────────────────────────────────────────────────────────────
export const BRAND = {
  greenBrand:  "#5AA97F",   // identidad EBIM · 2.45:1 vs blanco → SOLO fondos/decoración
  greenLight:  "#AEEA94",   // verde claro decorativo (gradientes, hero)
  green:       "#2F8159",   // ★ acción · 4.74:1 vs blanco (AA) · blanco encima 4.74:1
  greenHover:  "#266A49",   // hover/pressed · 6.31:1 vs blanco
  teal:        "#056769",   // teal de marca · 6.05:1 vs blanco (AA) — accesible tal cual
  tealDark:    "#055658",   // sombra/pressed teal · 7.45:1
  ink:         "#2E2E2E",   // texto principal · 13.6:1
  greenDark:   "#5FBF8C",   // acción en DARK · 6.0:1 vs paper #172320
  tealOnDark:  "#3FBFC1",   // teal en DARK · 6.7:1 vs #172320
  gradient:     "linear-gradient(135deg, #AEEA94 0%, #5AA97F 100%)",
  gradientDeep: "linear-gradient(135deg, #5AA97F 0%, #056769 100%)",
};

export type ColorMode = "light" | "dark";

// ─────────────────────────────────────────────────────────────────────────────
// ESCALAS DE SUITE — valores exactos del handoff (los mismos que usan WMS y
// eExpense). No redefinir medidas en línea por pantalla: importar de acá.
// ─────────────────────────────────────────────────────────────────────────────

/** Espaciado, base 4. */
export const S = { 1: 4, 2: 8, 3: 12, 4: 16, 5: 20, 6: 24, 8: 32, 10: 40 } as const;

/** Radios. Card = lg(14) · botones = md(12) · hero = xl(18) · chips = pill.
 *  El input usa 9 y no 12: junto a un botón del mismo radio se lee como otra
 *  píldora, y un campo de texto no debería competir con una acción. */
export const R = { sm: 8, md: 12, lg: 14, xl: 18, pill: 999 } as const;

/**
 * Escala tipográfica en px, por FUNCIÓN y no por variante.
 *
 * Pesos: hero/pageTitle/KPI = 800 (tracking −0.4 a −0.6) · cardTitle/botones = 700 ·
 * body = 500 · etiquetas en versalitas = 700/800 con tracking .08–.14em.
 * Los números van SIEMPRE en cifras tabulares.
 */
export const T = {
  hero: 30, kpiBig: 28, figure: 26, kpiCard: 24, pageTitle: 21,
  cardTitle: 14.5, bodyStrong: 13.5, body: 13, label: 11, micro: 10,
} as const;

/** Sombras como pares light/dark ya resueltos: MUI no lee variables CSS aquí. */
export const SH = {
  sm:   { light: "0 2px 8px rgba(0,0,0,.04)",             dark: "0 1px 0 rgba(255,255,255,.03), 0 2px 10px rgba(0,0,0,.45)" },
  md:   { light: "0 16px 40px -12px rgba(0,0,0,.28)",     dark: "0 1px 0 rgba(255,255,255,.04), 0 18px 44px -12px rgba(0,0,0,.6)" },
  lg:   { light: "0 10px 26px -16px rgba(10,90,82,.4)",   dark: "0 1px 0 rgba(255,255,255,.04), 0 12px 30px -14px rgba(0,0,0,.65)" },
  hero: { light: "0 20px 50px -22px rgba(10,90,82,.55)",  dark: "0 20px 50px -22px rgba(0,0,0,.7)" },
} as const;

export const shadow = (isDark: boolean, key: keyof typeof SH): string => SH[key][isDark ? "dark" : "light"];

/**
 * El neutro suave de la suite: la franja que separa sin dibujar una caja.
 *
 * Es el fondo de la cabecera de tabla, del hover de fila y del botón secundario. Va como
 * token y no como hex suelto porque los tres tienen que moverse juntos: el día que la
 * cabecera y el hover no coincidan, la fila bajo el cursor se lee como una cabecera más.
 */
export const NEUTRAL_SOFT = { light: "#EEF1F1", dark: "#222D28" } as const;
export const neutralSoft = (isDark: boolean): string => NEUTRAL_SOFT[isDark ? "dark" : "light"];

/**
 * REGLA AA DEL ACENTO.
 *
 * El acento se usa para RELLENOS y barras. Para TEXTO y ENLACES sobre fondo claro
 * hay que usar su versión profunda: los acentos claros no llegan a 4.5:1 como
 * texto sobre blanco — forest #5AA97F se queda en 2.45:1 y cobalt #2563EB no
 * sobra. Un botón de texto pintado con el acento es ilegible justo para quien
 * más necesita que no lo sea.
 *
 * Los cinco primeros son los hex del handoff de suite. `asfalto` es propio de
 * eTMS, así que su par se deriva del tema (su `pd` en claro, su `p` en oscuro,
 * ambos ya pensados como tono profundo).
 */
export const ACCENT_DEEP: Record<Exclude<ThemeKey, "brand">, string> = {
  forest: "#3F8A66", indigo: "#4F46E5", cobalt: "#1D4ED8",
  teal: "#0F766E", graphite: "#334155", asfalto: "#28414F",
};

export const ACCENT_DEEP_DARK: Record<Exclude<ThemeKey, "brand">, string> = {
  forest: "#6FD29A", indigo: "#A5B4FC", cobalt: "#60A5FA",
  teal: "#5EEAD4", graphite: "#CBD5E1", asfalto: "#8CA6BD",
};

// Densidad de la UI (homologada 1:1 con el resto de la suite — contrato Apariencia).
// controlH = alto de botón/input · rowH = alto de fila de tabla · padY/padX = padding de celda.
export type Density = "comoda" | "equilibrada" | "compacta";
export const DENSITY: Record<Density, { controlH: number; rowH: number; padY: number; padX: number }> = {
  comoda:      { controlH: 40, rowH: 52, padY: 12, padX: 14 },
  equilibrada: { controlH: 36, rowH: 44, padY: 9,  padX: 12 }, // default
  compacta:    { controlH: 32, rowH: 38, padY: 6,  padX: 10 },
};
export const DENSITY_LIST: { key: Density; label: string; sub: string }[] = [
  { key: "comoda",      label: "Cómoda",      sub: "Más aire" },
  { key: "equilibrada", label: "Equilibrada", sub: "Recomendada" },
  { key: "compacta",    label: "Compacta",    sub: "Más datos" },
];

// Tokens semánticos de ESTADO (pedidos/viajes/paradas). Chip relleno: {bgcolor:bg, color:text}.
// Chip sutil/outlined: {bgcolor:soft, color:softText}. Todos AA.
export const STATUS = {
  light: {
    open:       { bg: "#1F6FB2", text: "#FFFFFF", soft: "#E3F0FA", softText: "#0F4C81" },
    inProgress: { bg: "#B26A00", text: "#FFFFFF", soft: "#FBEEDD", softText: "#8A5200" },
    done:       { bg: "#2F8159", text: "#FFFFFF", soft: "#E5F2EB", softText: "#236245" },
    overdue:    { bg: "#C0303A", text: "#FFFFFF", soft: "#FBE7E8", softText: "#9A2630" },
    cancelled:  { bg: "#5B6B63", text: "#FFFFFF", soft: "#EEF1F0", softText: "#46534C" },
  },
  dark: {
    open:       { bg: "#5AB0EE", text: "#08263D", soft: "#15314A", softText: "#9FD0F2" },
    inProgress: { bg: "#E8A317", text: "#2B1E00", soft: "#3A2E10", softText: "#F0C25E" },
    done:       { bg: "#5FBF8C", text: "#0B2418", soft: "#163528", softText: "#86D4AB" },
    overdue:    { bg: "#EA6B73", text: "#2E0B0D", soft: "#3A1719", softText: "#F0989D" },
    cancelled:  { bg: "#9AADA6", text: "#16201C", soft: "#27332E", softText: "#B7C6C0" },
  },
} as const;

/** Clave de estado semántico. "neutral" es el gris de reposo (sin token propio). */
export type StatusKey = keyof (typeof STATUS)["light"];
export type StatusTone = StatusKey | "neutral";

// Paleta de data-viz / KPIs (distinguible y accesible en ambos modos).
export const DATAVIZ = {
  light: ["#2F8159", "#056769", "#1F6FB2", "#B26A00", "#7E4FB0", "#C0303A"],
  dark:  ["#5FBF8C", "#3FBFC1", "#5AB0EE", "#E8A317", "#B98BE0", "#EA6B73"],
} as const;

export const dataviz = (mode: ColorMode) => DATAVIZ[mode];

/**
 * El orden en que se reparten los colores a las series de una gráfica.
 *
 * NO es el orden de `DATAVIZ`. La paleta de la suite pone el verde de marca y el teal juntos, y
 * ese par concreto falla la separación entre sí: validado con el comprobador de daltonismo, el
 * par verde↔teal queda en ΔE 9.9 en claro y 7.5 en oscuro para visión normal, muy por debajo del
 * umbral de 15 — o sea, cuesta distinguirlos incluso viendo todos los colores. Mandar el teal al
 * final sube el peor par adyacente a ΔE 16.5 (claro) y 16.2 (oscuro), que sí pasa.
 *
 * Los tokens de `DATAVIZ` no se tocan: son identidad de la suite y se siguen usando para pintar
 * un dato suelto. Lo que cambia es a qué serie le toca cada uno.
 */
const SERIES_ORDER = [0, 2, 3, 4, 5, 1] as const;

/** Los colores de gráfica en orden de asignación a series, para el modo activo. */
export const datavizSeries = (mode: ColorMode): string[] =>
  SERIES_ORDER.map((index) => DATAVIZ[mode][index]);
export const statusToken = (mode: ColorMode, key: StatusKey) => STATUS[mode][key];

// ─────────────────────────────────────────────────────────────────────────────
// TEMAS de color seleccionables. Solo rotan primary/secondary (marca); STATUS y
// semánticos (success/info/warning/error) se mantienen estables entre temas.
// p=primary.main · pd=primary.dark(hover) · pc=contrastText · s=secondary.main · sd · sc
// Set ALINEADO 1:1 con el resto de la suite: forest(default)/indigo/cobalt/teal/graphite.
// "brand" = tema dinámico con el color del cliente (white-label).
// ─────────────────────────────────────────────────────────────────────────────
export type ThemeKey = "forest" | "indigo" | "cobalt" | "teal" | "graphite" | "asfalto" | "brand";

interface Pal { p: string; pd: string; pc: string; s: string; sd: string; sc: string }
export const THEMES: Record<Exclude<ThemeKey, "brand">, { light: Pal; dark: Pal }> = {
  forest:   { light: { p: "#5AA97F", pd: "#3F8A66", pc: "#FFFFFF", s: "#3F8A66", sd: "#2F7A63", sc: "#FFFFFF" },
              dark:  { p: "#5AA97F", pd: "#3F8A66", pc: "#0F1715", s: "#7CC09A", sd: "#3F8A66", sc: "#0F1715" } },
  indigo:   { light: { p: "#6366F1", pd: "#4F46E5", pc: "#FFFFFF", s: "#4F46E5", sd: "#4338CA", sc: "#FFFFFF" },
              dark:  { p: "#818CF8", pd: "#6366F1", pc: "#13102A", s: "#818CF8", sd: "#6366F1", sc: "#13102A" } },
  cobalt:   { light: { p: "#2563EB", pd: "#1D4ED8", pc: "#FFFFFF", s: "#1E40AF", sd: "#1D4ED8", sc: "#FFFFFF" },
              dark:  { p: "#3B82F6", pd: "#2563EB", pc: "#08182E", s: "#60A5FA", sd: "#2563EB", sc: "#08182E" } },
  teal:     { light: { p: "#0D9488", pd: "#0F766E", pc: "#FFFFFF", s: "#0F766E", sd: "#115E59", sc: "#FFFFFF" },
              dark:  { p: "#2DD4BF", pd: "#0D9488", pc: "#06262A", s: "#2DD4BF", sd: "#0D9488", sc: "#06262A" } },
  graphite: { light: { p: "#475569", pd: "#334155", pc: "#FFFFFF", s: "#334155", sd: "#1E293B", sc: "#FFFFFF" },
              dark:  { p: "#94A3B8", pd: "#475569", pc: "#11161A", s: "#94A3B8", sd: "#475569", sc: "#11161A" } },
  // Tema LOGÍSTICO exclusivo de eTMS: azul asfalto/carretera + acento ámbar de señalización.
  // Donde eGMAO tiene "Acero" (maquinaria), el transporte tiene el color de la vía.
  asfalto:  { light: { p: "#37536B", pd: "#28414F", pc: "#FFFFFF", s: "#C2660A", sd: "#9A5108", sc: "#FFFFFF" },
              dark:  { p: "#8CA6BD", pd: "#37536B", pc: "#0D141B", s: "#F0A23C", sd: "#C2660A", sc: "#0D141B" } },
};

// Metadatos para el selector (nombres EXACTOS de la suite + swatches deep→accent).
export const THEME_LIST: { key: ThemeKey; label: string; sub: string; swatch: [string, string, string] }[] = [
  { key: "forest",   label: "Bosque EBIM", sub: "Marca",      swatch: ["#0A5A52", "#2F7A63", "#5AA97F"] },
  { key: "indigo",   label: "Índigo",      sub: "IA",         swatch: ["#312E81", "#4F46E5", "#6366F1"] },
  { key: "cobalt",   label: "Cobalto",     sub: "Enterprise", swatch: ["#172554", "#1D4ED8", "#2563EB"] },
  { key: "teal",     label: "Teal",        sub: "Tech",       swatch: ["#134E4A", "#0F766E", "#0D9488"] },
  { key: "graphite", label: "Grafito",     sub: "Pro",        swatch: ["#0F172A", "#334155", "#475569"] },
  { key: "asfalto",  label: "Asfalto",     sub: "Logística",  swatch: ["#18242E", "#28414F", "#37536B"] },
];

// Sidebar premium: GRADIENTE de marca por tema (no negro plano), 180deg.
export const SIDEBAR: Record<Exclude<ThemeKey, "brand">, string> = {
  forest:   "linear-gradient(180deg, #0A5A52 0%, #2F7A63 55%, #3F8A66 100%)",
  indigo:   "linear-gradient(180deg, #312E81 0%, #3F37A8 55%, #4F46E5 100%)",
  cobalt:   "linear-gradient(180deg, #172554 0%, #1E40AF 55%, #1D4ED8 100%)",
  teal:     "linear-gradient(180deg, #134E4A 0%, #115E59 55%, #0F766E 100%)",
  graphite: "linear-gradient(180deg, #0F172A 0%, #1E293B 55%, #334155 100%)",
  asfalto:  "linear-gradient(180deg, #18242E 0%, #28414F 55%, #37536B 100%)",
};

/** Gradiente de sidebar derivado del color de marca del cliente (tema "brand"). */
export function brandSidebar(accent: string): string {
  try {
    return `linear-gradient(180deg, ${darken(accent, 0.58)} 0%, ${darken(accent, 0.32)} 55%, ${darken(accent, 0.12)} 100%)`;
  } catch { return SIDEBAR.forest; } // color inválido → fallback seguro
}

export function getTheme(mode: ColorMode, themeKey: ThemeKey = "forest", brandAccent?: string, density: Density = "equilibrada"): Theme {
  const dark = mode === "dark";
  const D = DENSITY[density] ?? DENSITY.equilibrada;
  let c: Pal;
  if (themeKey === "brand" && brandAccent) {
    try {
      const m = brandAccent;
      const pcLight = getContrastRatio(m, "#FFFFFF") >= 3 ? "#FFFFFF" : "#0F1715";
      // Combinación balanceada: color de marca (primary) + neutro profesional (secondary).
      c = dark
        ? { p: lighten(m, 0.25), pd: m, pc: "#0F1715", s: "#94A3B8", sd: "#475569", sc: "#0F1715" }
        : { p: m, pd: darken(m, 0.16), pc: pcLight, s: "#475569", sd: "#334155", sc: "#FFFFFF" };
    } catch {
      c = THEMES.forest[dark ? "dark" : "light"]; // color de cliente inválido → no tumba la app
    }
  } else {
    c = (THEMES[(themeKey as Exclude<ThemeKey, "brand">)] ?? THEMES.forest)[dark ? "dark" : "light"];
  }

  // Superficies y texto de la suite, resueltos una vez: los overrides de abajo los
  // necesitan como valores, no como referencias al tema que aún se está creando.
  const paper = dark ? "#172320" : "#FFFFFF";
  const divider = dark ? "#27332E" : "#E2E8E7";
  const textPrimary = dark ? "#EAF2EF" : "#0F1B1C";
  const textSecondary = dark ? "#9AADA6" : "#5C6B6C";

  /** El tono profundo del acento, para texto y enlaces (regla AA). Un tema de marca
   *  no tiene par publicado, así que se deriva oscureciendo el color del cliente. */
  const deepMap = dark ? ACCENT_DEEP_DARK : ACCENT_DEEP;
  const deep =
    themeKey === "brand"
      ? (dark ? lighten(c.p, 0.2) : darken(c.p, 0.22))
      : (deepMap[themeKey as Exclude<ThemeKey, "brand">] ?? deepMap.forest);

  return createTheme({
    palette: {
      mode,
      // primary/secondary rotan según el TEMA elegido (todos AA, ver THEMES).
      primary: { main: c.p, dark: c.pd, contrastText: c.pc },
      secondary: { main: c.s, dark: c.sd, contrastText: c.sc },
      // Superficies/semánticos EXACTOS de la suite.
      background: dark ? { default: "#0F1715", paper: "#172320" } : { default: "#F3F5F5", paper: "#FFFFFF" },
      text: dark ? { primary: "#EAF2EF", secondary: "#9AADA6" } : { primary: "#0F1B1C", secondary: "#5C6B6C" },
      divider: dark ? "#27332E" : "#E2E8E7",
      success: dark ? { main: "#6FD29A", contrastText: "#0B2418" } : { main: "#2E7D5B", contrastText: "#FFFFFF" },
      info:    dark ? { main: "#7FB2EE", contrastText: "#0A1C30" } : { main: "#1E5FB0", contrastText: "#FFFFFF" },
      warning: dark ? { main: "#F0C75A", contrastText: "#2B1E00" } : { main: "#946200", contrastText: "#FFFFFF" },
      error:   dark ? { main: "#F08A82", contrastText: "#2E0B0D" } : { main: "#C0392B", contrastText: "#FFFFFF" },
      // Token AA publicado en la paleta, como hace el EWM: para texto y enlaces se usa
      // SIEMPRE éste, nunca `primary.main`.
      accentDeep: deep,
      sidebarText: "rgba(237,247,241,.9)",
    },
    /**
     * Tipografía EXACTA del handoff de suite, la misma tabla que compila el EWM.
     *
     * Cada variante lleva su `fontFamily`: MUI no hereda la del tema en las que trae
     * definidas de fábrica, y sin repetirla los títulos salían en Helvetica.
     *
     * Los tamaños van en px y no en rem porque el handoff da píxeles exactos, y un rem
     * depende del tamaño raíz del navegador.
     */
    typography: {
      fontFamily: FONT,
      h1: { fontFamily: FONT, fontWeight: 800, fontSize: T.hero,      lineHeight: 1.1,  letterSpacing: "-0.6px" },
      h2: { fontFamily: FONT, fontWeight: 800, fontSize: T.kpiBig,    lineHeight: 1.15, letterSpacing: "-0.5px" },
      h3: { fontFamily: FONT, fontWeight: 800, fontSize: T.figure,    lineHeight: 1.15, letterSpacing: "-0.5px" },
      h4: { fontFamily: FONT, fontWeight: 800, fontSize: T.kpiCard,   lineHeight: 1.15, letterSpacing: "-0.5px" },
      h5: { fontFamily: FONT, fontWeight: 800, fontSize: T.pageTitle, lineHeight: 1.15, letterSpacing: "-0.4px" },
      h6: { fontFamily: FONT, fontWeight: 700, fontSize: T.cardTitle, lineHeight: 1.4,  letterSpacing: "-0.2px" },
      body1:     { fontFamily: FONT, fontWeight: 500, fontSize: T.body,       lineHeight: 1.5 },
      body2:     { fontFamily: FONT, fontWeight: 500, fontSize: T.label + 1,  lineHeight: 1.5 },
      subtitle1: { fontFamily: FONT, fontWeight: 700, fontSize: T.bodyStrong, lineHeight: 1.4 },
      subtitle2: { fontFamily: FONT, fontWeight: 600, fontSize: T.body,       lineHeight: 1.4 },
      caption:   { fontFamily: FONT, fontWeight: 500, fontSize: T.micro,      lineHeight: 1.4 },
      overline: {
        fontFamily: FONT, fontWeight: 800, fontSize: T.label,
        letterSpacing: ".1em", textTransform: "uppercase", lineHeight: 1.4,
      },
      button: { fontFamily: FONT, fontWeight: 700, fontSize: T.bodyStrong, textTransform: "none" },
    },
    shape: { borderRadius: R.md },
    components: {
      MuiButton: {
        defaultProps: { disableElevation: true },
        styleOverrides: {
          root: {
            textTransform: "none", fontWeight: 700, borderRadius: R.md, boxShadow: "none",
            minHeight: D.controlH, padding: `${D.padY}px ${D.padX + 4}px`,
          },
          sizeSmall: { minHeight: Math.max(26, D.controlH - 6) },
          /** Los botones de texto son enlaces: van en el tono profundo por la regla AA. */
          text: { color: deep },
          outlined: { borderColor: divider, color: textPrimary },
        },
        // MUI 9 retiró las claves compuestas (`containedPrimary`, `outlinedPrimary`):
        // lo que antes era un slot ahora se expresa emparejando props.
        variants: [
          {
            /**
             * Deshabilitado SIGUE pareciendo un botón.
             *
             * El gris de MUI es un bloque muerto: sobre una tarjeta se lee como un
             * separador, no como la acción principal esperando datos. El mismo
             * acento al 30 % dice «esto se va a poder tocar» sin dejar duda de que
             * ahora no.
             */
            props: { variant: "contained" as const, color: "primary" as const },
            style: {
              "&.Mui-disabled": {
                backgroundColor: `color-mix(in srgb, ${c.p} 30%, transparent)`,
                color: "color-mix(in srgb, #fff 72%, transparent)",
              },
            },
          },
          {
            /**
             * El botón SECUNDARIO: relleno neutro con borde marcado, no blanco lavado.
             *
             * «Importar» o «Exportar» en blanco con borde gris quedaban tan apagados
             * que había que buscarlos.
             *
             * Aquí me aparto a propósito del WMS, que tiñe el secundario con el verde
             * semántico. Su acento de casa es cobalto, así que azul sólido junto a
             * verde pastel separa bien. El de eTMS es **verde**: copiar esa regla
             * daría verde pastel al lado de verde sólido, que es exactamente el
             * defecto del que su comentario avisa. Un neutro con peso funciona con
             * los seis acentos y con el tema de marca.
             */
            props: { variant: "outlined" as const, color: "primary" as const },
            style: {
              backgroundColor: neutralSoft(dark),
              borderColor: divider,
              color: textPrimary,
              "&:hover": {
                backgroundColor: dark ? "#27332E" : "#E4E9E8",
                borderColor: dark ? "#3A4741" : "#CBD5D2",
              },
              "&.Mui-disabled": { backgroundColor: "transparent", borderColor: divider },
            },
          },
        ],
      },
      MuiOutlinedInput: {
        styleOverrides: {
          root: {
            // El fondo del campo es el de la tarjeta, declarado y no heredado: sobre un
            // lienzo gris un input sin fondo propio se lee como deshabilitado.
            borderRadius: 9, minHeight: D.controlH, backgroundColor: paper,
            /**
             * El autocompletado del navegador NO decide el color.
             *
             * Chrome pinta el campo autocompletado con un amarillo propio que
             * ignora el tema: en modo oscuro queda una barra clara con texto
             * negro dentro de un formulario oscuro. El `background` lo protege el
             * navegador, pero se puede tapar con una sombra interna enorme.
             */
            "& input:-webkit-autofill": {
              WebkitBoxShadow: `0 0 0 1000px ${paper} inset`,
              WebkitTextFillColor: textPrimary,
              caretColor: textPrimary,
              borderRadius: "inherit",
            },
          },
          input: { padding: `${D.padY}px ${D.padX}px` },
        },
      },
      MuiTableCell: {
        styleOverrides: {
          root: {
            paddingTop: D.padY, paddingBottom: D.padY, paddingLeft: D.padX, paddingRight: D.padX,
            fontSize: T.body, borderColor: divider,
          },
          /** Versalitas diminutas: la cabecera nombra la columna, no compite con el dato. */
          head: {
            fontSize: T.micro, fontWeight: 700, textTransform: "uppercase",
            letterSpacing: ".08em", color: textSecondary,
          },
          sizeSmall: { paddingTop: Math.max(4, D.padY - 3), paddingBottom: Math.max(4, D.padY - 3) },
        },
      },
      MuiTableRow: { styleOverrides: { root: { height: D.rowH } } },
      MuiCard: {
        styleOverrides: {
          root: {
            borderRadius: R.lg,
            boxShadow: shadow(dark, "sm"),
            border: `1px solid ${divider}`,
            backgroundImage: "none",
            // La tarjeta acusa el puntero: sin esto, una rejilla de tarjetas que
            // llevan a algún sitio no da ninguna señal de ser pulsable.
            transition: "border-color .15s ease",
            "&:hover": { borderColor: c.p },
          },
        },
      },
      MuiPaper: {
        styleOverrides: {
          // MUI tiñe el papel oscuro con un degradado de "elevación" que sube el fondo
          // medio tono por nivel. La suite trabaja con superficies planas.
          root: { backgroundImage: "none" },
          rounded: { borderRadius: R.lg },
        },
      },
      MuiChip: {
        styleOverrides: { root: { borderRadius: R.pill, fontWeight: 700, fontSize: T.label, height: 24 } },
      },
      MuiTooltip: {
        styleOverrides: { tooltip: { fontSize: T.label, fontWeight: 600, borderRadius: R.sm } },
      },
      MuiAppBar: { styleOverrides: { root: { boxShadow: "none" } } },
    },
  });
}

/** Alto de fila de tabla para la densidad activa. */
export const rowHeight = (density: Density) => (DENSITY[density] ?? DENSITY.equilibrada).rowH;

// Compatibilidad: theme por defecto (claro)
export const theme = getTheme("light");
