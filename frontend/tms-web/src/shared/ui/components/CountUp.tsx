import { useEffect, useRef, useState, type ReactNode } from "react";
import { easeOutCubic, formatFigure, parseFigure } from "../../../lib/countUp";
import { localeFor } from "../../../lib/locale";

/** Lo que tarda en llegar a la cifra: algo más que la entrada de la pantalla (350 ms), para que el
 * número se asiente cuando la tarjeta ya está quieta. */
const DURATION_MS = 900;

/**
 * ¿Se puede animar? Solo en un navegador que lo permita y si la persona no pidió reducir el
 * movimiento. Sin `matchMedia` (jsdom) no se sabe qué prefiere, y se muestra la cifra quieta.
 */
function motionAllowed(): boolean {
  if (typeof window === "undefined" || typeof window.requestAnimationFrame !== "function") return false;
  if (typeof window.matchMedia !== "function") return false;
  return !window.matchMedia("(prefers-reduced-motion: reduce)").matches;
}

/**
 * La cifra de un indicador, contada desde 0 al aparecer (ver `lib/countUp.ts`).
 *
 * Recibe el texto ya formateado y termina siempre en ese texto exacto: los pasos intermedios son
 * solo presentación. Si la cifra cambia después —otro filtro, la torre que se refresca—, cuenta
 * desde la que se veía y no desde 0, para que un refresco no parezca un reinicio. Lo que no es una
 * sola cantidad (una raya, «Sin datos», un componente) se pinta tal cual.
 */
export function CountUp({ value }: { value: ReactNode }) {
  const text = typeof value === "string" ? value : null;
  const locale = localeFor();
  const figure = text === null ? null : parseFigure(text, locale);
  const animate = figure !== null && motionAllowed();

  const [display, setDisplay] = useState(() => (animate && figure ? formatFigure(figure, 0, locale) : text));
  // La cantidad que se ve ahora, de donde arranca la próxima animación.
  const shown = useRef(animate ? 0 : (figure?.value ?? 0));

  useEffect(() => {
    if (!animate || text === null) return;
    const target = parseFigure(text, locale);
    if (target === null) return;
    const from = shown.current;
    const to = target.value;
    let frame = 0;
    if (from === to) {
      frame = requestAnimationFrame(() => setDisplay(text));
      return () => cancelAnimationFrame(frame);
    }
    const start = performance.now();
    const step = (now: number) => {
      const progress = Math.min(1, (now - start) / DURATION_MS);
      const current = from + (to - from) * easeOutCubic(progress);
      shown.current = progress < 1 ? current : to;
      setDisplay(progress < 1 ? formatFigure(target, current, locale) : text);
      if (progress < 1) frame = requestAnimationFrame(step);
    };
    frame = requestAnimationFrame(step);
    return () => cancelAnimationFrame(frame);
  }, [animate, text, locale]);

  return <>{animate ? display : value}</>;
}
