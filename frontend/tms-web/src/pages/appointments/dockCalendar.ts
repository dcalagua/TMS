import type { DayHoursRequest, DayHoursView } from "../../shared/api/appointmentsApi";
import type { LocationView } from "../../shared/api/locationsApi";
import { enumLabel } from "../../lib/enums";
import { t } from "../../lib/i18n";

/** La semana en orden ISO: lunes primero, como la escribe una planta ("de lunes a sábado"). */
export const WEEK: DayHoursView["day"][] = [
  "MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY",
];

/** Una fila del editor de horario: un día, abierto o no, y sus dos horas en `HH:mm`. */
export interface DayRow {
  day: DayHoursView["day"];
  open: boolean;
  opensAt: string;
  closesAt: string;
}

const hhmm = (value: string) => value.slice(0, 5);

const DAY_NAMES: Record<DayHoursView["day"], string> = {
  MONDAY: "Lunes", TUESDAY: "Martes", WEDNESDAY: "Miércoles", THURSDAY: "Jueves",
  FRIDAY: "Viernes", SATURDAY: "Sábado", SUNDAY: "Domingo",
};

/** El nombre completo del día, para el editor; el resumen usa la abreviatura de `enumLabel`. */
export const dayName = (day: DayHoursView["day"]): string => t(DAY_NAMES[day]);

/**
 * Las siete filas del editor a partir del horario guardado. Un día sin entrada queda cerrado con
 * las horas sugeridas, para que abrirlo sea un clic y no tres.
 */
export function rowsFromHours(hours: DayHoursView[], suggested = { opensAt: "07:00", closesAt: "19:00" }): DayRow[] {
  return WEEK.map((day) => {
    const entry = hours.find((h) => h.day === day);
    return entry
      ? { day, open: true, opensAt: hhmm(entry.opensAt), closesAt: hhmm(entry.closesAt) }
      : { day, open: false, opensAt: suggested.opensAt, closesAt: suggested.closesAt };
  });
}

/** Lo que se envía: sólo los días abiertos. */
export function hoursFromRows(rows: DayRow[]): DayHoursRequest[] {
  return rows.filter((row) => row.open).map((row) => ({ day: row.day, opensAt: row.opensAt, closesAt: row.closesAt }));
}

/**
 * El error de una fila, o null. El backend dice lo mismo; decirlo antes evita mandar una semana
 * que sólo puede volver rechazada. Sin turnos nocturnos: una puerta abierta 22:00-06:00 son dos
 * días, como dice el contrato.
 */
export function rowError(row: DayRow): string | null {
  if (!row.open) return null;
  if (!/^\d{2}:\d{2}$/.test(row.opensAt) || !/^\d{2}:\d{2}$/.test(row.closesAt)) {
    return t("Indica la hora de apertura y la de cierre.");
  }
  if (row.closesAt <= row.opensAt) {
    return t("El cierre tiene que ser después de la apertura. Un turno nocturno son dos días.");
  }
  return null;
}

/**
 * El horario en una línea: "Lun–Sáb 07:00–19:00". Agrupa los días seguidos con la misma franja,
 * que es como la gente lee un horario; sin entradas dice lo que el backend entiende, que es
 * "abierta siempre" y no "cerrada".
 */
export function summarizeHours(hours: DayHoursView[]): string {
  if (hours.length === 0) return t("Sin horario: abierta siempre");
  const byDay = new Map(hours.map((h) => [h.day, `${hhmm(h.opensAt)}–${hhmm(h.closesAt)}`]));
  const groups: { from: DayHoursView["day"]; to: DayHoursView["day"]; span: string }[] = [];
  for (const day of WEEK) {
    const span = byDay.get(day);
    if (span === undefined) continue;
    const last = groups[groups.length - 1];
    const previous = WEEK[WEEK.indexOf(day) - 1];
    if (last && last.span === span && last.to === previous) last.to = day;
    else groups.push({ from: day, to: day, span });
  }
  return groups
    .map((g) => `${enumLabel("dayOfWeek", g.from)}${g.from === g.to ? "" : `–${enumLabel("dayOfWeek", g.to)}`} ${g.span}`)
    .join(" · ");
}

/**
 * Los sitios en los que se puede citar un camión: orígenes **y** destinos activos. Los orígenes
 * primero — una planta o un CD es donde más camiones se citan — y cada uno con el uso que tiene,
 * para que "GYE-CD" no se lea como un cliente.
 */
export function appointmentSites(locations: LocationView[]): { id: string; label: string }[] {
  const rank = (l: LocationView) => (l.roles.includes("ORIGIN") ? 0 : 1);
  return locations
    .filter((l) => l.roles.length > 0)
    .slice()
    .sort((a, b) => rank(a) - rank(b) || a.code.localeCompare(b.code))
    .map((l) => ({
      id: l.id,
      label: `${l.code} · ${l.name} (${l.roles.map((role) => enumLabel("locationRole", role)).join(", ")})`,
    }));
}

/** La misma clave en el tablero de citas y en el panel de puertas: lo que cambia uno lo ve el otro. */
export const dockResourcesKey = (companyId: string, locationId: string) =>
  ["dock-resources", companyId, locationId] as const;
