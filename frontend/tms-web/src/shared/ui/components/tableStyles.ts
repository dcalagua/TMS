import type { SxProps, Theme } from "@mui/material";
import { T, neutralSoft } from "../../../theme";

/**
 * El aspecto de la tabla de datos de la suite. **Una sola definición para todos los listados.**
 *
 * <h2>De qué se sostiene una tabla</h2>
 * No de líneas. Un borde por celda, una cabecera con el mismo peso que el contenido y filas
 * altas se leen como una hoja de cálculo, y en una tabla de doce columnas las líneas verticales
 * compiten con los datos por la atención de quien busca un número.
 *
 * Ésta se sostiene sobre jerarquía tipográfica y espacio: la cabecera en versalitas diminutas
 * sobre una franja neutra —es un rótulo, no un dato—, un separador horizontal tenue entre filas,
 * ninguna línea vertical, y el hover como única marca de la fila bajo el cursor. Las cifras van
 * a la derecha y en tabulares, que es lo que permite comparar una columna de un vistazo.
 *
 * <p>El alto de fila lo sigue poniendo la densidad elegida en el menú de cuenta (`theme.ts`), no
 * este fichero: es el control que existe justamente para eso.
 *
 * Uso:
 *   <TableContainer sx={{ maxHeight: TABLE_MAX_H }}>
 *     <Table size="small" stickyHeader sx={dataTableSx}>
 *       …
 *       <TableCell align="right" className="actions-col">…</TableCell>
 */
export const dataTableSx: SxProps<Theme> = {
  "& thead th": {
    whiteSpace: "nowrap",
    textTransform: "uppercase",
    fontSize: T.micro,
    letterSpacing: ".05em",
    fontWeight: 700,
    color: "text.secondary",
    // La franja neutra: separa la cabecera del cuerpo sin necesitar una línea gruesa.
    bgcolor: (theme: Theme) => neutralSoft(theme.palette.mode === "dark"),
    borderBottom: "1px solid",
    borderColor: "divider",
    paddingTop: "9px",
    paddingBottom: "9px",
  },
  "& tbody td": { borderColor: "divider" },
  // La última fila no lleva separador: el panel ya termina ahí, y la línea duplicada con el
  // borde del pie se ve como un renglón doble.
  "& tbody tr:last-of-type td": { borderBottom: 0 },
  // El hover comparte tono con la cabecera a propósito: son las dos únicas superficies que se
  // separan del papel, y con dos grises distintos la tabla parece tener tres fondos.
  "& tbody tr:hover": {
    backgroundColor: (theme: Theme) => neutralSoft(theme.palette.mode === "dark"),
  },
  // Cifras: alineadas a la derecha y con cifras tabulares, para que las columnas de peso,
  // volumen y conteo se puedan comparar de un vistazo en vertical.
  "& .numeric-col": { textAlign: "right", fontVariantNumeric: "tabular-nums", whiteSpace: "nowrap" },
  "& .actions-col": {
    position: "sticky",
    right: 0,
    /**
     * «Lo más estrecha que quepa», que en una tabla se pide con `1%`.
     *
     * NO con `width: 1`: en el `sx` de MUI un ancho menor o igual que uno se interpreta como
     * fracción, así que eso compilaba a `width: 100%` y la columna de acciones se quedaba con
     * todo el espacio sobrante — el resto de columnas partían su texto en tres renglones al
     * lado de un hueco enorme.
     */
    width: "1%",
    bgcolor: "background.paper",
    whiteSpace: "nowrap",
    textAlign: "right",
    /**
     * Sin regla vertical.
     *
     * La columna sigue anclada a la derecha, pero el borde que la separaba se dibujaba SIEMPRE,
     * y la tabla solo se desplaza en algunas pantallas: en las demás era la única línea vertical
     * de un diseño que se sostiene a propósito sin ellas. Cuando de verdad hay desplazamiento, el
     * fondo opaco de la celda ya basta para que las columnas pasen por debajo sin mezclarse.
     */
  },
  "& thead .actions-col": {
    zIndex: 3,
    bgcolor: (theme: Theme) => neutralSoft(theme.palette.mode === "dark"),
  },
  "& tbody .actions-col": { zIndex: 1 },
  // La columna del chevron: una señal, no un dato. Angosta y sin título.
  "& .open-col": { width: "36px", padding: 0, textAlign: "center", color: "text.secondary" },
};

/** Alto máximo del contenedor para que la cabecera fija tenga sentido. */
export const TABLE_MAX_H = "68vh";

/** Franja de severidad por prioridad (color semántico, no el acento de marca). */
export const SEVERITY_COLOR: Record<string, string> = {
  LOW: "success.light",
  NORMAL: "info.main",
  HIGH: "warning.main",
  URGENT: "error.main",
};
