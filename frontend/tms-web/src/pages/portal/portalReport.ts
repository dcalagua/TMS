import type { DocumentDeliveryReason, DocumentDeliveryStatus } from "../../shared/api/documentDeliveriesApi";
import { ApiError } from "../../shared/api/httpClient";
import { MAX_REPORT_PHOTOS } from "../../shared/api/portalApi";
import { describeApiError } from "../../shared/api/problemMessages";
import { photoProblem } from "../../lib/images";
import { t } from "../../lib/i18n";

/** Las claves de caché del portal: la lista y el detalle de un viaje, por empresa. */
export const portalTripsKey = (companyId: string) => ["portal-trips", companyId] as const;
export const portalTripKey = (companyId: string, tripId: string) => ["portal-trip", companyId, tripId] as const;

/** Lo que un transportista puede reportar: todo menos volver a `PENDING`. */
export type ReportStatus = Exclude<DocumentDeliveryStatus, "PENDING">;

export const REPORT_STATUSES: ReportStatus[] = ["DELIVERED", "REJECTED", "NOT_DELIVERED"];

export interface ReportFormValues {
  status: ReportStatus | null;
  reasonCode: DocumentDeliveryReason | "";
  remark: string;
  photos: Blob[];
  signature: Blob | null;
}

export type ReportErrors = Partial<Record<"status" | "reasonCode" | "remark" | "photos", string>>;

/** Un rechazo o una no entrega llevan motivo; una entrega, foto. */
export function needsReason(status: ReportStatus | null): boolean {
  return status === "REJECTED" || status === "NOT_DELIVERED";
}

/**
 * Las reglas del formulario, las mismas que aplica el servidor (ADR-018 §4 y §6), comprobadas
 * antes de subir nada: en la puerta de un cliente, con mala señal, un 400 después de subir cinco
 * fotos es el peor momento para enterarse de que faltaba el motivo.
 */
export function validateReport(values: ReportFormValues, options: { evidenceEnabled: boolean }): ReportErrors {
  const errors: ReportErrors = {};

  if (values.status === null) {
    errors.status = t("Elige qué pasó con el documento.");
    return errors;
  }

  if (values.status === "DELIVERED" && !options.evidenceEnabled) {
    errors.status = t("Este despliegue no guarda fotos, y una entrega necesita una. Avisa a la oficina.");
  }

  if (needsReason(values.status)) {
    if (!values.reasonCode) errors.reasonCode = t("Elige el motivo.");
    else if (values.reasonCode === "OTHER" && values.remark.trim() === "") {
      errors.remark = t("Con el motivo «Otro», cuenta qué pasó.");
    }
  }

  if (options.evidenceEnabled) {
    if (values.status === "DELIVERED" && values.photos.length === 0) {
      errors.photos = t("Para marcarlo entregado hace falta al menos una foto.");
    } else if (values.photos.length > MAX_REPORT_PHOTOS) {
      errors.photos = t("Como mucho {{n}} fotos.", { n: MAX_REPORT_PHOTOS });
    } else {
      const problem = values.photos.map(photoProblem).find((p) => p !== null);
      if (problem === "type") errors.photos = t("Alguna foto no es JPEG, PNG ni WebP. Vuelve a tomarla.");
      if (problem === "size") errors.photos = t("Alguna foto pesa más de 6 MB. Vuelve a tomarla.");
    }
  }

  return errors;
}

/**
 * La frase que se enseña cuando falla el envío.
 *
 * Un fallo de red no es un `ApiError`: no hubo respuesta. Es el caso para el que existe la clave
 * de idempotencia, así que se dice que reintentar es seguro. Para un rechazo de reglas del
 * servidor se enseña su propia frase, que nombra el campo.
 */
export function describeReportError(error: unknown): string {
  if (!(error instanceof ApiError)) {
    return t("No se pudo enviar: revisa la señal y vuelve a intentarlo. El reporte no se duplicará.");
  }
  if ((error.code === "validation-failed" || error.code === "conflict" || error.code === "malformed-request"
    || error.code === "feature-not-configured") && error.problem?.detail) {
    return error.problem.detail;
  }
  return describeApiError(error);
}

/**
 * La frase de una lectura que falló: la del servidor si respondió, la de «sin datos en este
 * teléfono» si no hubo red ni copia, y una de señal para lo demás.
 */
export function describeLoadError(error: unknown): string {
  if (error instanceof ApiError) return describeApiError(error);
  if (error instanceof Error && error.name === "OfflineNoDataError") return error.message;
  return t("No se pudo conectar con el servidor. Revisa la señal y vuelve a intentarlo.");
}
