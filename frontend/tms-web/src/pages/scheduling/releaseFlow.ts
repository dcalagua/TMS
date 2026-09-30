import type { ApiError } from "../../shared/api/httpClient";
import { describeApiError } from "../../shared/api/problemMessages";
import { releaseOrder, releaseRefusalOf, type SchedulingReason } from "../../shared/api/schedulingApi";
import { notifyError, notifySuccess, promptDialog } from "../../lib/ui";
import { enumLabel } from "../../lib/enums";
import { t } from "../../lib/i18n";

/** Los motivos en una línea, en español, para el texto de un diálogo o un toast. */
export function reasonLabels(reasons: SchedulingReason[]): string {
  return reasons.map((reason) => enumLabel("schedulingReason", reason.code)).join(", ");
}

/** Pide el motivo de una liberación sobre un aviso. `null` si se descartó. */
export function askOverrideReason(reasons: SchedulingReason[], count = 1): Promise<string | null> {
  const overriding = reasons.filter((reason) => reason.requiresOverride);
  const calendar = overriding.some((reason) => reason.code === "FREQUENCY_OVERRIDE");
  return promptDialog({
    title: count === 1 ? t("Liberar con motivo") : t("Liberar {{count}} pedidos con motivo", { count }),
    text: `${t("Avisos")}: ${reasonLabels(overriding)}. `
      + (calendar
        ? t("Fuera de su calendario, el pedido se podrá asignar a mano pero la planificación automática no lo tomará para esa fecha.")
        : t("La liberación queda auditada con este motivo.")),
    inputLabel: t("Motivo de la liberación"),
    required: true,
    maxLength: 500,
    confirmLabel: t("Liberar"),
  });
}

/**
 * Liberar un pedido (ADR-014) desde cualquier pantalla: intenta con el motivo que ya se tenga y,
 * si el backend responde que hace falta uno, lo pide y reintenta una vez. Un bloqueo se informa con
 * sus motivos; no hay motivo que lo salve.
 *
 * El backend es quien juzga: esta función no decide nada por su cuenta sobre la elegibilidad, así
 * que funciona igual desde la lista de pedidos, que no la conoce, que desde Programación.
 *
 * @returns true si el pedido quedó liberado
 */
export async function releaseWithOverride(
  companyId: string, orderId: string, orderNumber: string, presetReason?: string,
): Promise<boolean> {
  let reason = presetReason;
  for (let attempt = 0; attempt < 2; attempt++) {
    try {
      await releaseOrder(companyId, orderId, reason);
      notifySuccess(t("Pedido liberado para planificar"), orderNumber);
      return true;
    } catch (error) {
      const refusal = releaseRefusalOf(error);
      if (refusal?.overrideRequired && !reason) {
        const asked = await askOverrideReason(refusal.reasons);
        if (asked === null) return false;
        reason = asked;
        continue;
      }
      if (refusal) {
        notifyError(t("No se puede liberar el pedido"), `${orderNumber}: ${reasonLabels(
          refusal.reasons.filter((candidate) => candidate.severity === "BLOCKED" || candidate.requiresOverride))}`);
        return false;
      }
      notifyError(t("No se pudo liberar el pedido"), describeApiError(error as ApiError));
      return false;
    }
  }
  return false;
}
