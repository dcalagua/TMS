import { expect, test } from "@playwright/test";
import { ALL_MODULES } from "./support/modules";
import { watchForProblems } from "./support/console";

/**
 * Smoke del menú completo: abrir cada módulo, uno detrás de otro, en la misma sesión de
 * navegador, y comprobar que ninguno deja un error de consola ni una petición de aplicación
 * caída por el camino.
 *
 * Se hace en una sola prueba y no en veintiuna porque lo que se persigue aquí es lo que se
 * acumula: un efecto que no se limpia al desmontar, o un import perezoso que sólo falla cuando
 * ya se cargó otro, no aparecen visitando una pantalla aislada.
 */
test("todo el menú se abre sin errores de consola ni peticiones caídas", async ({ page }) => {
  const problems = watchForProblems(page);
  const notServed: string[] = [];

  for (const module of ALL_MODULES) {
    const response = await page.goto(module.path);
    if (response?.status() !== 200) {
      notServed.push(`${module.label} (${module.path}) -> ${response?.status() ?? "sin respuesta"}`);
    }
    await page.waitForLoadState("domcontentloaded");
  }

  expect(notServed, "módulos que el servidor no sirvió").toEqual([]);
  expect(problems.consoleErrors, "errores de consola durante el recorrido").toEqual([]);
  expect(problems.failedRequests, "peticiones caídas durante el recorrido").toEqual([]);
});
