import type { PortalStop } from "../../shared/api/portalApi";
import { t } from "../../lib/i18n";
import { fmtDateTime, fmtTime } from "../../lib/locale";

/**
 * Medidas y ayudas del portal en el teléfono: fuera, con una mano, a veces con guantes.
 */

/** Alto de la barra superior del portal, sin la muesca. La cabecera fija del viaje se pega debajo. */
export const PORTAL_BAR_HEIGHT = 60;

/** El mínimo de un objetivo táctil en el portal (WCAG 2.5.5 pide 44; con guantes, más). */
export const TOUCH_TARGET = 56;

/** Las zonas seguras del teléfono (muesca, barra de gestos). Requieren `viewport-fit=cover`. */
export const SAFE_AREA = {
  top: "env(safe-area-inset-top, 0px)",
  right: "env(safe-area-inset-right, 0px)",
  bottom: "env(safe-area-inset-bottom, 0px)",
  left: "env(safe-area-inset-left, 0px)",
} as const;

/** 16 px en los campos: con menos, iOS hace zoom al enfocarlos y la pantalla se descoloca. */
export const READABLE_INPUTS = {
  "& input, & textarea, & .MuiSelect-select": { fontSize: 16 },
} as const;

/**
 * El enlace de navegación de una parada: por coordenadas cuando las hay (llevan al portón, no al
 * centro de la calle) y si no por dirección. `null` si no hay ni lo uno ni lo otro.
 */
export function directionsUrl(stop: Pick<PortalStop, "latitude" | "longitude" | "address" | "destinationName">): string | null {
  const base = "https://www.google.com/maps/dir/?api=1&destination=";
  if (typeof stop.latitude === "number" && typeof stop.longitude === "number"
    && Number.isFinite(stop.latitude) && Number.isFinite(stop.longitude)) {
    return `${base}${stop.latitude},${stop.longitude}`;
  }
  const address = [stop.address, stop.destinationName].find((value) => value && value.trim() !== "");
  return address ? `${base}${encodeURIComponent(address.trim())}` : null;
}

/** «Sin conexión · datos de las 14:05» — o del día, si la copia no es de hoy. */
export function staleLabel(savedAt: string, now: Date = new Date()): string {
  const saved = new Date(savedAt);
  const sameDay = saved.toDateString() === now.toDateString();
  return sameDay
    ? t("Sin conexión · datos de las {{time}}", { time: fmtTime(saved) })
    : t("Sin conexión · datos del {{when}}", { when: fmtDateTime(saved) });
}
