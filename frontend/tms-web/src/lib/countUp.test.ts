import { describe, expect, it } from "vitest";
import { easeOutCubic, formatFigure, parseFigure } from "./countUp";
import { fmtMoney, fmtPercent, fmtQuantity } from "./locale";

const ES = "es-ES";

describe("countUp", () => {
  it("separa la cifra de su unidad, con los separadores del idioma", () => {
    expect(parseFigure("12.345", ES)).toEqual({ prefix: "", value: 12345, decimals: 0, suffix: "" });
    expect(parseFigure("66,7 %", ES)).toMatchObject({ value: 66.7, decimals: 1, suffix: " %" });
    expect(parseFigure("1072,50 PEN", ES)).toMatchObject({ value: 1072.5, decimals: 2, suffix: " PEN" });
    expect(parseFigure("1,234.5", "en-GB")).toMatchObject({ value: 1234.5, decimals: 1 });
  });

  it("no anima lo que no es una sola cantidad", () => {
    expect(parseFigure("-", ES)).toBeNull();
    expect(parseFigure("Sin datos", ES)).toBeNull();
    expect(parseFigure("3 / 5", ES)).toBeNull();
    expect(parseFigure("2 h 15 min", ES)).toBeNull();
  });

  it("los pasos intermedios se leen igual que la cifra final", () => {
    for (const text of [fmtQuantity(12345), fmtPercent(66.7, 1), fmtMoney(6398.99, "PEN")]) {
      const figure = parseFigure(text, ES);
      expect(figure).not.toBeNull();
      expect(formatFigure(figure!, figure!.value, ES)).toBe(text);
      expect(formatFigure(figure!, 0, ES)).toMatch(/0/);
    }
  });

  it("frena al final", () => {
    expect(easeOutCubic(0)).toBe(0);
    expect(easeOutCubic(1)).toBe(1);
    expect(easeOutCubic(0.5)).toBeGreaterThan(0.5);
  });
});
