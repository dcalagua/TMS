import type { FieldValues, Path, UseFormSetError } from "react-hook-form";
import type { ApiError } from "./httpClient";
import { describeApiError } from "./problemMessages";

/**
 * Convierte un rechazo del backend en errores de campo en línea más, cuando hace falta, un
 * mensaje a nivel de formulario.
 *
 * Todos los diálogos de formulario necesitan el mismo reparto en tres, así que vive aquí en
 * lugar de escribirse una vez por pantalla:
 *
 * - un documento `validation-failed` nombra los campos ofensores, y cada uno que el formulario
 *   realmente pinta se convierte en un error en línea bajo ese campo, donde el usuario mira;
 * - un campo que el formulario no pinta (el backend valida más de lo que enseña cualquier
 *   pantalla) se desvanecería, así que su mensaje se saca a nivel de formulario;
 * - cualquier otra cosa — un conflicto, un permiso denegado, un error de servidor — se
 *   describe desde su `code` estable, nunca desde `detail`.
 *
 * @param knownFields los nombres de campo del propio formulario; lo de fuera no puede pintarse en línea
 * @param fallback copy para "hay campos que corregir", cuando todos los errores de campo se
 *   colocaron en línea y no queda nada que decir a nivel de formulario
 * @returns el mensaje de formulario, o `null` cuando los errores en línea ya lo dicen todo
 */
export function applyApiFieldErrors<TValues extends FieldValues>(
  error: ApiError,
  knownFields: ReadonlySet<string>,
  setError: UseFormSetError<TValues>,
  fallback: string,
): string | null {
  if (error.fieldErrors.length === 0) {
    return describeApiError(error);
  }

  const unmatched: string[] = [];
  for (const fieldError of error.fieldErrors) {
    if (knownFields.has(fieldError.field)) {
      setError(fieldError.field as Path<TValues>, { message: fieldError.message });
    } else {
      unmatched.push(fieldError.message);
    }
  }

  return unmatched.length > 0 ? unmatched.join(" ") : fallback;
}
