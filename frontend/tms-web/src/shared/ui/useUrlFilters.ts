import { useCallback, useMemo } from "react";
import { useSearchParams } from "react-router-dom";

/** Una comprobación por clave: un valor del enlace que no la pasa se ignora y rige el defecto. */
export type UrlFilterValidators<T extends Record<string, string>> = { [K in keyof T]?: (value: string) => boolean };

/** `YYYY-MM-DD`: la forma en que viaja un día en todos los filtros del producto. */
export const isIsoDay = (value: string): boolean => /^\d{4}-\d{2}-\d{2}$/.test(value);

/** Un valor de una lista cerrada (un estado, una vista). */
export const oneOf = (allowed: readonly string[]) => (value: string): boolean => allowed.includes(value);

/**
 * Los filtros de una pantalla, guardados en la URL (ítem 11): un enlace pegado en un chat o una
 * recarga abren la pantalla con la misma fecha y el mismo estado, en vez de con los de por defecto.
 *
 * Reglas, y por qué:
 * - **Sólo se escribe lo que difiere del defecto.** `/delivery-tracking` sigue siendo una URL
 *   limpia; `?status=` (vacío) sí se escribe cuando el defecto no es vacío, porque «todos» es una
 *   elección distinta de «lo de siempre».
 * - **`replace`, no `push`.** Cambiar un filtro no es navegar: el botón «atrás» tiene que sacar a
 *   la persona de la pantalla, no deshacerle los filtros uno por uno.
 * - **Las demás claves de la URL no se tocan**, así que un parámetro que lea otra parte de la
 *   pantalla convive con éstos.
 * - **Un valor que no pasa su validación se ignora**: un enlace viejo o mal copiado abre la
 *   pantalla con el defecto en vez de mandarle al backend algo que va a rechazar.
 *
 * La página y lo que está abierto (un drawer, una fila) no van aquí: son estado de la visita,
 * no de lo que se quiere compartir.
 */
export function useUrlFilters<T extends Record<string, string>>(
  defaults: T,
  validators: UrlFilterValidators<T> = {},
): [T, (next: T) => void] {
  const [searchParams, setSearchParams] = useSearchParams();
  // Los defectos suelen construirse en cada render (`today()`): se comparan por contenido.
  const defaultsKey = JSON.stringify(defaults);

  const read: Record<string, string> = { ...defaults };
  for (const key of Object.keys(defaults)) {
    const raw = searchParams.get(key);
    if (raw === null) continue;
    const valid = validators[key as keyof T];
    if (valid && !valid(raw)) continue;
    read[key] = raw;
  }
  // Mismo contenido, mismo objeto: quien lo use como dependencia no se recalcula en cada render.
  const readKey = JSON.stringify(read);
  const values = useMemo(() => JSON.parse(readKey) as T, [readKey]);

  const setValues = useCallback((next: T) => {
    const base = JSON.parse(defaultsKey) as T;
    setSearchParams((previous) => {
      const params = new URLSearchParams(previous);
      for (const key of Object.keys(base)) {
        const value = next[key] ?? base[key];
        if (value === base[key]) params.delete(key);
        else params.set(key, value);
      }
      return params;
    }, { replace: true });
  }, [defaultsKey, setSearchParams]);

  return [values, setValues];
}
