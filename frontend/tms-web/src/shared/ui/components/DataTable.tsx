import type { MouseEvent, ReactNode } from "react";
import {
  Box, Paper, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, Typography,
} from "@mui/material";
import { ChevronRightRounded } from "@mui/icons-material";
import { dataTableSx, TABLE_MAX_H } from "./tableStyles";
import { EmptyState, ErrorState, SkeletonTable } from "./states";
import { t } from "../../../lib/i18n";
import { fmtQuantity } from "../../../lib/locale";
import { R, T } from "../../../theme";

/**
 * ¿El gesto nació en un control de la fila, y no en la fila?
 *
 * Una fila que abre el detalle lleva su `onClick` en el `<tr>`, y las columnas de acciones
 * viven dentro de ese `<tr>`. Sin este guardia, pulsar «Editar» hacía las dos cosas: abría el
 * formulario y, por debajo, navegaba al detalle.
 */
function fromRowControl(event: MouseEvent<HTMLElement>): boolean {
  const control = (event.target as HTMLElement | null)
    ?.closest("button, a, input, select, textarea, [role='button'], [role='menuitem']");
  return control !== null && control !== event.currentTarget;
}

export interface DataTableColumn<T> {
  key: string;
  header: string;
  render: (row: T) => ReactNode;
  /** Alinea a la derecha con cifras tabulares: pesos, volúmenes, conteos. */
  numeric?: boolean;
  /** Ancla la columna a la derecha y la encoge: los controles de acción de la fila. */
  actions?: boolean;
  /** Ancho fijo, cuando la columna tiene que dejar de pelearse por el espacio. */
  width?: number | string;
}

interface DataTableProps<T> {
  columns: DataTableColumn<T>[];
  rows: T[];
  rowKey: (row: T) => string;
  isLoading?: boolean;
  error?: string | null;
  onRetry?: () => void;
  emptyTitle?: string;
  emptyMessage?: string;
  emptyAction?: ReactNode;
  /** Nombre accesible de la tabla; sin esto queda sin nombrar. */
  caption?: string;
  /**
   * Total de servidor para los filtros actuales. Se muestra como conteo permanente encima de
   * la tabla — a diferencia del paginador, que se esconde cuando todo cabe en una página.
   */
  total?: number;
  /**
   * Se pinta en la franja de pie del propio panel, bajo la última fila. El paginador va aquí
   * y no en una tarjeta envolviendo a esta: un panel dentro de un panel duplica el borde y el
   * radio, que es lo que hacía que las listas se leyeran como cajas apiladas sobre un campo gris.
   */
  footer?: ReactNode;
  /** Se llama al pulsar una fila. Con esto, la fila entera se vuelve el enlace al detalle. */
  onRowClick?: (row: T) => void;
  /** Franja de color a la izquierda de la fila — severidad, prioridad, alerta. */
  rowAccent?: (row: T) => string | null;
  maxHeight?: number | string;
}

function cellClass<T>(column: DataTableColumn<T>): string | undefined {
  const classes = [column.numeric ? "numeric-col" : null, column.actions ? "actions-col" : null]
    .filter(Boolean).join(" ");
  return classes || undefined;
}

/**
 * El envoltorio de tabla sobre el que se construye toda pantalla de lista: un solo sitio que
 * es dueño de la presentación de cargando/error/vacío, de la densidad operativa, del contenedor
 * de scroll horizontal y de las columnas como datos en lugar de JSX repetido por pantalla.
 *
 * El scroll vive en el envoltorio de la tabla. Una tabla ancha nunca debe ensanchar la página:
 * eso es lo que pone una barra horizontal en toda la aplicación a anchos de tablet.
 */
export function DataTable<T>({
  columns, rows, rowKey, isLoading = false, error = null, onRetry,
  emptyTitle, emptyMessage, emptyAction, caption, total, footer, onRowClick, rowAccent,
  maxHeight = TABLE_MAX_H,
}: DataTableProps<T>) {
  if (error) {
    return (
      <Paper variant="outlined" sx={{ borderRadius: `${R.lg}px` }}>
        <ErrorState message={error} onRetry={onRetry} />
      </Paper>
    );
  }

  if (isLoading) {
    return (
      <Paper variant="outlined" sx={{ borderRadius: `${R.lg}px` }}>
        <Box role="status" sx={{ position: "absolute", width: 1, height: 1, overflow: "hidden", clip: "rect(0 0 0 0)" }}>
          {t("Cargando registros...")}
        </Box>
        <SkeletonTable columns={Math.min(columns.length, 6)} />
      </Paper>
    );
  }

  const isEmpty = rows.length === 0;

  return (
    <Paper variant="outlined" sx={{ borderRadius: `${R.lg}px`, overflow: "hidden" }}>
      {total !== undefined && (
        /* `role="status"`: al cambiar un filtro, el conteo nuevo se anuncia sin que haya que ir
           a buscarlo. Es la única forma de que quien no ve la tabla se entere de que el filtro
           hizo algo. Va sobre el papel y no sobre una franja gris: la única superficie tintada
           del panel es la cabecera, y con dos la tabla parece tener tres fondos. */
        <Box role="status" sx={{
          px: 2, py: "10px", display: "flex", alignItems: "baseline", gap: 1, flexWrap: "wrap",
          borderBottom: "1px solid", borderColor: "divider",
        }}>
          <Typography component="span" sx={{
            fontSize: T.body, fontWeight: 700, fontVariantNumeric: "tabular-nums",
          }}>
            {fmtQuantity(total)} {total === 1 ? t("resultado") : t("resultados")}
          </Typography>
          {/* «18 resultados» no se lee como «hay 18» cuando en pantalla hay 20 de 340. Se dice
              al lado del número y no en la barra de filtros, porque es el número el que se cree. */}
          {rows.length > 0 && rows.length < total && (
            <Typography component="span" sx={{ fontSize: T.micro, color: "text.secondary" }}>
              {t("Mostrando {{count}} en esta página", { count: fmtQuantity(rows.length) })}
            </Typography>
          )}
        </Box>
      )}

      {/* `tabIndex` porque un contenedor con scroll que solo se mueve con el dedo deja fuera a
          quien navegue con teclado. */}
      <TableContainer tabIndex={0} sx={{ maxHeight }}>
        <Table size="small" stickyHeader aria-label={caption} sx={dataTableSx}>
          <TableHead>
            <TableRow>
              {columns.map((column) => (
                <TableCell key={column.key} className={cellClass(column)} sx={{ width: column.width }}>
                  {column.header}
                </TableCell>
              ))}
              {/* La columna del chevron. Sin título y angosta: es una señal, no un dato. Va solo
                  cuando la fila abre algo — una tabla que no navega no puede insinuar que sí. */}
              {onRowClick && <TableCell aria-hidden className="open-col" />}
            </TableRow>
          </TableHead>
          <TableBody>
            {isEmpty ? (
              /* El mensaje de vacío va dentro de la tabla en vez de reemplazarla. Cambiar el
                 panel entero por un mensaje quita las cabeceras de columna — lo que le dice al
                 usuario qué es esta lista — y hace que la pantalla parezca cambiar de identidad
                 entre una búsqueda y la siguiente. */
              <TableRow>
                <TableCell colSpan={columns.length + (onRowClick ? 1 : 0)} sx={{ borderBottom: 0 }}>
                  <EmptyState title={emptyTitle} message={emptyMessage} action={emptyAction} />
                </TableCell>
              </TableRow>
            ) : (
              rows.map((row) => {
                const accent = rowAccent?.(row) ?? null;
                return (
                  /* Una fila que abre el detalle tiene que abrirlo también con el teclado. Con
                     `onClick` a secas, las pantallas cuyo único camino al detalle es pulsar la
                     fila quedaban inalcanzables sin ratón: no es que costara más, es que no
                     había forma. */
                  <TableRow
                    key={rowKey(row)}
                    hover
                    onClick={onRowClick
                      ? (event) => { if (!fromRowControl(event)) onRowClick(row); }
                      : undefined}
                    tabIndex={onRowClick ? 0 : undefined}
                    role={onRowClick ? "button" : undefined}
                    onKeyDown={onRowClick
                      ? (event) => {
                        if (event.key !== "Enter" && event.key !== " ") return;
                        // Con el foco en el botón «Editar» de la fila, Enter lo activa y además
                        // burbujea hasta aquí. Se atiende solo lo que pasó sobre la fila misma.
                        if (event.target !== event.currentTarget) return;
                        // Espacio desplaza la página por defecto: sin esto, abrir una fila con
                        // la barra espaciadora además salta al pie.
                        event.preventDefault();
                        onRowClick(row);
                      }
                      : undefined}
                    sx={{
                      cursor: onRowClick ? "pointer" : "default",
                      ...(onRowClick ? { "&:focus-visible": { outline: "2px solid", outlineColor: "primary.main", outlineOffset: "-2px" } } : {}),
                      ...(accent ? { "& td:first-of-type": { boxShadow: `inset 3px 0 0 ${accent}` } } : {}),
                    }}
                  >
                    {columns.map((column) => (
                      <TableCell key={column.key} className={cellClass(column)}>
                        {column.render(row)}
                      </TableCell>
                    ))}
                    {/* `aria-hidden` porque la fila YA se anuncia como botón: nombrarlo otra vez
                        le haría leer al lector de pantalla un control que no existe. Es una
                        pista para el ojo; el teclado ya tiene la suya en `focus-visible`. */}
                    {onRowClick && (
                      <TableCell aria-hidden className="open-col">
                        <ChevronRightRounded sx={{ fontSize: 16, verticalAlign: "middle" }} />
                      </TableCell>
                    )}
                  </TableRow>
                );
              })
            )}
          </TableBody>
        </Table>
      </TableContainer>

      {footer && (
        <Box sx={{ borderTop: "1px solid", borderColor: "divider", px: 1.5, py: 1 }}>{footer}</Box>
      )}
    </Paper>
  );
}
