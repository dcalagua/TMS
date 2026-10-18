import { t, tp } from "../../lib/i18n";

/** «Vencida hace 3 días», «Vence hoy», «Vence en 5 días». `days` negativo = vencida. */
export function dueLabel(days: number): string {
  if (days < 0) return tp(-days, "Vencida hace {{count}} día", "Vencida hace {{count}} días");
  if (days === 0) return t("Vence hoy");
  return tp(days, "Vence en {{count}} día", "Vence en {{count}} días");
}
