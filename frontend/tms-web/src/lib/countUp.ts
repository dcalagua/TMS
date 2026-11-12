/**
 * La cifra de un indicador ya formateado, para contarla desde cero al aparecer.
 *
 * Las tarjetas reciben el texto que pintan («1.278», «66,7 %», «572,49 PEN», «36,0 h»), no el
 * número: lo formatea cada pantalla con `lib/locale.ts`. Aquí se separa ese texto en lo que va
 * antes de la cifra, la cifra y lo que va después, para animar solo la cifra y dejar el resto —la
 * unidad, el símbolo, el signo de porcentaje— tal cual. Los separadores son los del idioma activo,
 * los mismos con que se formateó.
 *
 * Un texto sin cifra («-», «Sin datos») o con más de una («3 / 5», «2 h 15 min») no se anima: no
 * hay una sola cantidad que contar, y animar la primera inventaría un estado intermedio raro.
 */

export interface Figure {
  prefix: string;
  value: number;
  /** Decimales de la cifra original: los pasos intermedios llevan los mismos. */
  decimals: number;
  suffix: string;
}

function separators(locale: string): { group: string; decimal: string } {
  const parts = new Intl.NumberFormat(locale).formatToParts(12345.6);
  return {
    group: parts.find((part) => part.type === "group")?.value ?? ",",
    decimal: parts.find((part) => part.type === "decimal")?.value ?? ".",
  };
}

const escape = (value: string) => value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");

/** La única cifra del texto, o `null` si no hay exactamente una. */
export function parseFigure(text: string, locale: string): Figure | null {
  const { group, decimal } = separators(locale);
  const g = escape(group);
  const d = escape(decimal);
  const number = new RegExp(`-?\\d{1,3}(?:${g}\\d{3})+(?:${d}\\d+)?|-?\\d+(?:${d}\\d+)?`, "g");
  const matches = [...text.matchAll(number)];
  if (matches.length !== 1) return null;
  const [token] = matches[0];
  const start = matches[0].index ?? 0;
  const [integer, fraction = ""] = token.split(decimal);
  const value = Number(`${integer.split(group).join("")}${fraction ? `.${fraction}` : ""}`);
  if (!Number.isFinite(value)) return null;
  return { prefix: text.slice(0, start), value, decimals: fraction.length, suffix: text.slice(start + token.length) };
}

/** El texto con la cifra cambiada por `value`, con los decimales y separadores del original. */
export function formatFigure(figure: Figure, value: number, locale: string): string {
  const number = new Intl.NumberFormat(locale, {
    minimumFractionDigits: figure.decimals, maximumFractionDigits: figure.decimals,
  }).format(value);
  return `${figure.prefix}${number}${figure.suffix}`;
}

/** Arranca rápido y se asienta al final: los últimos pasos son los que se leen. */
export const easeOutCubic = (t: number): number => 1 - (1 - t) ** 3;
