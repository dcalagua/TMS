import type { ApiError } from "../api/httpClient";
import { describeApiError } from "../api/problemMessages";
import { confirmDialog, notifyError, notifySuccess } from "../../lib/ui";
import { t } from "../../lib/i18n";

/**
 * Activar/desactivar un maestro: la confirmación, la llamada y el aviso, en un solo sitio.
 *
 * Los diez maestros del producto repiten exactamente este diálogo, y "exactamente" ya se estaba
 * torciendo en el original: un título sin el nombre del registro aquí, un botón que no era
 * destructivo allá. Vale la pena que la decisión de "¿seguro que quieres retirar esto de la
 * operación?" se lea igual en los diez.
 *
 * Devuelve `true` si el cambio se aplicó, para que la pantalla decida si recargar.
 */
export async function toggleActiveRecord(options: {
  name: string;
  active: boolean;
  activate: () => Promise<unknown>;
  deactivate: () => Promise<unknown>;
}): Promise<boolean> {
  const { name, active, activate, deactivate } = options;

  const confirmed = await confirmDialog({
    title: active ? t("¿Desactivar {{name}}?", { name }) : t("¿Activar {{name}}?", { name }),
    text: active
      ? t("Dejará de estar disponible para nuevas operaciones.")
      : t("Volverá a estar disponible para la operación."),
    confirmLabel: active ? t("Desactivar") : t("Activar"),
    dangerous: active,
  });
  if (!confirmed) return false;

  try {
    if (active) {
      await deactivate();
      notifySuccess(t("Registro desactivado"), name);
    } else {
      await activate();
      notifySuccess(t("Registro activado"), name);
    }
    return true;
  } catch (error) {
    notifyError(t("No se pudo completar la acción"), describeApiError(error as ApiError));
    return false;
  }
}

/** El aviso de éxito que sigue a guardar un maestro. Un solo texto para los diez formularios. */
export function notifySaved(isEdit: boolean): void {
  notifySuccess(isEdit ? t("Registro actualizado") : t("Registro creado"));
}

/** El aviso de fallo de una acción suelta (reactivar, reabrir, reintentar…). */
export function notifyActionError(error: ApiError): void {
  notifyError(t("No se pudo completar la acción"), describeApiError(error));
}

/** Los tres estados del filtro de actividad, presentes en todas las listas de maestros. */
export type ActiveFilter = "active" | "inactive" | "all";

export const ACTIVE_FILTER_OPTIONS: { value: ActiveFilter; label: string }[] = [
  { value: "active", label: "Activos" },
  { value: "inactive", label: "Inactivos" },
  { value: "all", label: "Todos" },
];

/** El `active` que espera la API a partir del filtro de la pantalla. */
export const activeParam = (filter: ActiveFilter): boolean | undefined =>
  filter === "all" ? undefined : filter === "active";
