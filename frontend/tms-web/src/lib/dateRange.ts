import { toIsoDate } from "./locale";

/**
 * Periodos de los filtros de fecha: `yyyy-mm-dd` en el reloj del navegador, los dos extremos
 * incluidos. «7 días» que terminan hoy empiezan hace seis.
 */

const DAY_MS = 24 * 60 * 60 * 1000;

/** El día de `iso` desplazado `days` días (negativo hacia atrás), sin pasar por husos horarios. */
export function addDays(iso: string, days: number): string {
  const [y, m, d] = iso.split("-").map(Number);
  return toIsoDate(new Date(y, m - 1, d + days));
}

/** Cuántos días cubre el periodo, contando los dos extremos: del 6 al 12 son 7. */
export function rangeDays(from: string, to: string): number {
  const [fy, fm, fd] = from.split("-").map(Number);
  const [ty, tm, td] = to.split("-").map(Number);
  return Math.round((Date.UTC(ty, tm - 1, td) - Date.UTC(fy, fm - 1, fd)) / DAY_MS) + 1;
}

/** Los últimos `days` días, hoy incluido. */
export function lastDays(days: number, today: string = toIsoDate(new Date())): { from: string; to: string } {
  return { from: addDays(today, -(days - 1)), to: today };
}

/**
 * Ajusta el otro extremo cuando uno cambia, en vez de rechazar la elección: quien mueve «Desde» a
 * un mes atrás quiere ver ese mes, no un error. Con `maxDays`, el periodo nunca pasa de ese largo;
 * un «Desde» posterior al «Hasta» arrastra el «Hasta» consigo, y al revés.
 */
export function clampRange(
  range: { from: string; to: string },
  changed: "from" | "to",
  maxDays?: number,
): { from: string; to: string } {
  let { from, to } = range;
  if (!from || !to) return { from, to };
  if (changed === "from") {
    if (from > to) to = from;
    if (maxDays && rangeDays(from, to) > maxDays) to = addDays(from, maxDays - 1);
  } else {
    if (to < from) from = to;
    if (maxDays && rangeDays(from, to) > maxDays) from = addDays(to, -(maxDays - 1));
  }
  return { from, to };
}
