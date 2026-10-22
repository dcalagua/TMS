import { useQueryClient } from "@tanstack/react-query";
import { useCallback } from "react";
import {
  DELIVERY_RESULTS_NEEDING_NOTES, DELIVERY_RESULTS_WITH_RECEIVER,
  type DeliveryResult, type StopExecutionStatus, type TripExceptionType,
} from "../../shared/api/planningApi";
import {
  MAX_REPORT_PHOTOS, sendPortalExecution,
  type PortalExecutionAction, type PortalStopTransition, type PortalTripDetail,
} from "../../shared/api/portalApi";
import { photoProblem } from "../../lib/images";
import { t } from "../../lib/i18n";
import { usePortalOffline } from "./offline/offlineContext";
import { classifySendError } from "./offline/network";
import type { Served } from "./offline/usePortalQuery";
import { describeReportError, portalTripKey, portalTripsKey } from "./portalReport";

/**
 * Paradas y entregas desde el portal (V70): las reglas del formulario, comprobadas antes de subir
 * nada —las mismas que aplica el servidor, que es el que manda— y el envío con su cola.
 */

/** Los permisos de V70. Uno para la parada y la incidencia, otro para el resultado de entrega. */
export const PORTAL_STOP_EXECUTE = "portal.stop:execute";
export const PORTAL_DELIVERY_REPORT = "portal.delivery:report";

/** Lo que el portal ofrece en una parada, en el orden en que se hace. Omitir es cosa de la oficina. */
export const PORTAL_STOP_ACTIONS: { transition: PortalStopTransition; target: StopExecutionStatus; label: string }[] = [
  { transition: "arrive", target: "ARRIVED", label: "Llegué" },
  { transition: "service", target: "IN_SERVICE", label: "Empecé la descarga" },
  { transition: "complete", target: "COMPLETED", label: "Terminé la parada" },
  { transition: "fail", target: "FAILED", label: "No se pudo atender" },
];

/** Los cinco resultados, en el orden en que se ofrecen en la puerta del cliente. */
export const PORTAL_DELIVERY_RESULTS: DeliveryResult[] = ["DELIVERED", "PARTIAL", "REJECTED", "FAILED", "NOT_ATTEMPTED"];

/** Los motivos de una parada que no se pudo atender, de los del catálogo de incidencias. */
export const STOP_FAILURE_REASONS: TripExceptionType[] = [
  "CUSTOMER_CLOSED", "ADDRESS_NOT_FOUND", "DELIVERY_REJECTED", "DELIVERY_FAILED", "OTHER",
];

export interface DeliveryFormValues {
  result: DeliveryResult | null;
  receiverName: string;
  receiverDocument: string;
  notes: string;
  refusedWeightKg: string;
  refusedVolumeM3: string;
  refusedPallets: string;
  photos: Blob[];
  signature: Blob | null;
}

export type DeliveryErrors = Partial<Record<"result" | "notes" | "refused" | "photos" | "receiverName", string>>;

/** Lo que se cargó para el pedido en este viaje: el tope de lo rechazado. */
export interface Loaded {
  weightKg: number | null;
  volumeM3: number | null;
  pallets: number | null;
}

/** Un número escrito con coma o punto; vacío es «no lo dijo». `NaN` si no es un número. */
export function parseAmount(value: string): number | null {
  const trimmed = value.trim().replace(",", ".");
  if (trimmed === "") return null;
  const parsed = Number(trimmed);
  return Number.isFinite(parsed) ? parsed : Number.NaN;
}

export const allowsReceiver = (result: DeliveryResult | null) => result !== null && DELIVERY_RESULTS_WITH_RECEIVER.includes(result);
export const needsNotes = (result: DeliveryResult | null) => result !== null && DELIVERY_RESULTS_NEEDING_NOTES.includes(result);
/** Fotos y firma solo donde hubo alguien en la puerta o se intentó: no en un pedido que no se bajó. */
export const takesEvidence = (result: DeliveryResult | null) => result !== null && result !== "NOT_ATTEMPTED";

/**
 * Las reglas del formulario de entrega, las del servidor (`TripDeliveryService`, V70), antes de
 * subir nada: en la puerta de un cliente, un 400 después de subir cinco fotos es el peor momento
 * para enterarse de que faltaba el motivo.
 */
export function validateDelivery(values: DeliveryFormValues, loaded: Loaded): DeliveryErrors {
  const errors: DeliveryErrors = {};
  if (values.result === null) {
    errors.result = t("Elige qué pasó con el pedido.");
    return errors;
  }
  if (needsNotes(values.result) && values.notes.trim() === "") {
    errors.notes = t("Cuenta qué pasó: es obligatorio cuando no se entregó todo.");
  }
  if (values.result === "PARTIAL") {
    const refused = [
      { value: parseAmount(values.refusedPallets), max: loaded.pallets },
      { value: parseAmount(values.refusedWeightKg), max: loaded.weightKg },
      { value: parseAmount(values.refusedVolumeM3), max: loaded.volumeM3 },
    ];
    if (refused.some(({ value }) => value !== null && (Number.isNaN(value) || value < 0))) {
      errors.refused = t("Escribe cantidades válidas, sin negativos.");
    } else if (refused.every(({ value }) => value === null || value === 0)) {
      errors.refused = t("Indica cuánto rechazó el cliente.");
    } else if (refused.some(({ value, max }) => value !== null && max !== null && value > max)) {
      errors.refused = t("No se puede rechazar más de lo que se cargó para este pedido.");
    }
  }
  if (values.photos.length > MAX_REPORT_PHOTOS) {
    errors.photos = t("Como mucho {{n}} fotos.", { n: MAX_REPORT_PHOTOS });
  } else {
    const problem = values.photos.map(photoProblem).find((p) => p !== null);
    if (problem === "type") errors.photos = t("Alguna foto no es JPEG, PNG ni WebP. Vuelve a tomarla.");
    if (problem === "size") errors.photos = t("Alguna foto pesa más de 6 MB. Vuelve a tomarla.");
  }
  return errors;
}

/** Cómo terminó un envío: el servidor lo tiene, o espera en la cola del teléfono. */
export type ExecutionOutcome = "sent" | "queued";

/** Un fallo que el servidor rechazó por sus reglas: se enseña su frase y no se encola. */
export class ExecutionRejectedError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "ExecutionRejectedError";
  }
}

/**
 * Enviar una acción de parada, o dejarla en la cola (V70, el patrón de ADR-019 §1).
 *
 * Sin red, o con otra acción del mismo viaje todavía en la cola, va a la cola: si saliera antes,
 * la entrega llegaría al servidor antes que la llegada a la parada. Con red se envía; si no hay
 * respuesta o el servidor está caído, a la cola con la MISMA clave, y un reenvío de algo que sí
 * llegó no registra nada dos veces. Un rechazo por reglas (400/404/409) se devuelve como
 * `ExecutionRejectedError` con la frase del servidor.
 */
export function usePortalExecution(companyId: string, tripId: string, shipmentNumber: string | null) {
  const queryClient = useQueryClient();
  const offline = usePortalOffline();

  return useCallback(async (action: PortalExecutionAction, title: string, idempotencyKey: string,
    reportedAt: string): Promise<ExecutionOutcome> => {
    const queue = async (): Promise<ExecutionOutcome> => {
      try {
        await offline.enqueue({
          kind: "EXECUTION", companyId, tripId, idempotencyKey, action, title, reportedAt, shipmentNumber,
        });
      } catch {
        throw new ExecutionRejectedError(t("No se pudo guardar en el teléfono. Libera espacio o vuelve a intentarlo con señal."));
      }
      void offline.sync();
      return "queued";
    };

    if (!offline.online || offline.hasQueuedExecution(tripId)) return queue();
    try {
      const detail = await sendPortalExecution(companyId, tripId, action, idempotencyKey);
      queryClient.setQueryData<Served<PortalTripDetail>>(portalTripKey(companyId, tripId),
        () => ({ data: detail, savedAt: null }));
      void queryClient.invalidateQueries({ queryKey: portalTripsKey(companyId) });
      void queryClient.invalidateQueries({ queryKey: portalTripKey(companyId, tripId) });
      return "sent";
    } catch (error) {
      const failure = classifySendError(error);
      if (failure === "network" || failure === "server") return queue();
      throw new ExecutionRejectedError(describeReportError(error));
    }
  }, [companyId, tripId, shipmentNumber, offline, queryClient]);
}
