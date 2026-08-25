import { expect, test } from "@playwright/test";
import { ALL_MODULES, CORE_MODULES } from "./support/modules";
import { watchForProblems } from "./support/console";

/**
 * Que cada módulo del menú sea alcanzable y esté protegido.
 *
 * Son dos garantías distintas y las dos se rompen en silencio. Si el servidor deja de servir el
 * `index.html` para una ruta profunda, recargar `/masters/locations` da un 404 del servidor y no
 * la pantalla — un fallo que sólo aparece al recargar o al pegar un enlace en un chat. Y si una
 * ruta pierde su guardia, se dibuja sin sesión: sin datos, con errores de red, y enseñando un
 * armazón a quien no ha entrado.
 */
test.describe("Rutas del menú", () => {
  for (const module of ALL_MODULES) {
    test(`${module.label} (${module.path}) la sirve el servidor, no un 404`, async ({ page }) => {
      const response = await page.goto(module.path);

      // El 404 de negocio lo pinta NotFoundPage dentro de la aplicación; el servidor siempre
      // devuelve el documento, que es lo que hace que una recarga profunda funcione.
      expect(response?.status(), `${module.path} debería servir el index`).toBe(200);
    });
  }

  for (const module of CORE_MODULES) {
    test(`${module.label} redirige al login cuando no hay sesión`, async ({ page }) => {
      await page.goto(module.path);

      await expect(page).toHaveURL(/\/login/);
    });
  }

  test("una ruta que no existe no rompe la aplicación", async ({ page }) => {
    const problems = watchForProblems(page);

    const response = await page.goto("/esta-ruta-no-existe");

    expect(response?.status()).toBe(200);
    await expect(page).toHaveURL(/\/login|\/esta-ruta-no-existe/);
    expect(problems.consoleErrors).toEqual([]);
  });
});
