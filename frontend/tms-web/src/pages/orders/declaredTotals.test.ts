import { describe, expect, it } from "vitest";
import { allowedRange, marginHint } from "./declaredTotals";

/**
 * El margen que el formulario anuncia bajo cada cifra declarada.
 *
 * Tiene que ser el MISMO que aplica el backend, porque si no es peor que no anunciarlo: un
 * intervalo mas estrecho rechazaria cifras validas, y uno mas ancho dejaria teclear cifras que
 * el guardado va a rechazar despues.
 *
 * La regla, en `OrderTotals.mismatch`:
 *
 *     |declarado - calculado| > calculado.abs() * 0.01   ->  rechazo
 *
 * Es un `>`, asi que los dos extremos del intervalo son validos.
 */
describe("el margen permitido para una cifra declarada", () => {
  it("es el uno por ciento del total calculado, a cada lado", () => {
    expect(allowedRange(9000)).toEqual({ min: 8910, max: 9090 });
  });

  it("no impone nada cuando las lineas no dicen esa medida", () => {
    // El backend solo contrasta lo declarado contra las lineas cuando las lineas lo dicen. Sin
    // ellas cualquier cifra vale, y anunciar un intervalo seria inventarse una regla.
    expect(allowedRange(null)).toBeNull();
    expect(marginHint(null, 2)).toBeUndefined();
  });

  it("sobre cero no admite desvio alguno", () => {
    expect(allowedRange(0)).toEqual({ min: 0, max: 0 });
  });

  it("escala con el total, no es una cantidad fija", () => {
    expect(allowedRange(3)).toEqual({ min: 2.97, max: 3.03 });
    expect(allowedRange(100)).toEqual({ min: 99, max: 101 });
  });

  it("toma el valor absoluto, igual que el backend", () => {
    // `calculated.abs()` en OrderTotals: un total negativo no deberia existir, pero si llegara,
    // el margen no puede salir invertido.
    const range = allowedRange(-50);
    expect(range).not.toBeNull();
    expect(range!.min).toBeLessThan(range!.max);
  });
});

describe("como se escribe el margen", () => {
  it("nombra el desvio y los dos extremos, no solo el porcentaje", () => {
    // Se comparan los digitos sin separadores: el agrupamiento de miles depende del idioma
    // activo, y afirmarlo aqui convertiria un cambio de locale en un fallo de esta prueba.
    const digits = marginHint(9000, 2)!.replace(/[.,\s]/g, "");
    expect(digits).toContain("90");    // el margen
    expect(digits).toContain("8910");  // el extremo inferior
    expect(digits).toContain("9090");  // el superior
    expect(marginHint(9000, 2)).toContain("±");
  });

  it("sobre cero lo dice con otra frase", () => {
    // "entre 0 y 0" se lee como un fallo de la pantalla, no como una exigencia.
    const hint = marginHint(0, 2);
    expect(hint).not.toContain("±");
    expect(hint).toMatch(/exactamente/);
  });
});
