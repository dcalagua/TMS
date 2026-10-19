import { t } from "../../lib/i18n";

const CURRENCY_PATTERN = /^[A-Za-z]{3}$/;

export interface TolerancePolicyFormValues {
  scope: "COMPANY" | "CARRIER";
  carrierId: string;
  absoluteAmount: string;
  percentage: string;
  currency: string;
}

export type ToleranceFieldName = Exclude<keyof TolerancePolicyFormValues, "scope">;

/**
 * Las reglas de una tolerancia, las mismas que aplica el servidor, para decirlas antes de enviar.
 *
 * Devuelve los errores por campo y, aparte, el que no es de ningún campo: "al menos un límite".
 * Una política sin límites diría que no se tolera nada, y eso ya es lo que pasa sin política.
 */
export function validateTolerance(values: TolerancePolicyFormValues): {
  fields: Partial<Record<ToleranceFieldName, string>>;
  form: string | null;
} {
  const fields: Partial<Record<ToleranceFieldName, string>> = {};
  const absolute = values.absoluteAmount.trim();
  const percentage = values.percentage.trim();
  const currency = values.currency.trim();

  if (values.scope === "CARRIER" && values.carrierId === "") {
    fields.carrierId = t("Este campo es obligatorio");
  }
  if (absolute !== "") {
    const parsed = Number(absolute);
    if (!Number.isFinite(parsed)) fields.absoluteAmount = t("Introduce un número válido");
    else if (parsed < 0) fields.absoluteAmount = t("No puede ser negativo");
    // 5 PEN y 5 USD no son la misma tolerancia: un importe sin moneda no dice nada.
    if (currency === "") fields.currency = t("Un importe absoluto necesita su moneda.");
  }
  if (currency !== "" && !CURRENCY_PATTERN.test(currency)) {
    fields.currency = t("Tres letras, p. ej. PEN");
  }
  if (percentage !== "") {
    const parsed = Number(percentage);
    if (!Number.isFinite(parsed)) fields.percentage = t("Introduce un número válido");
    else if (parsed < 0 || parsed > 100) fields.percentage = t("Entre 0 y 100.");
  }
  const form = absolute === "" && percentage === ""
    ? t("Indica al menos un límite: un importe absoluto, un porcentaje o los dos.")
    : null;
  return { fields, form };
}
