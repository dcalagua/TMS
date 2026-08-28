import AxeBuilder from "@axe-core/playwright";
import { expect, test } from "@playwright/test";

/**
 * axe sobre un navegador de verdad (JOB 26).
 *
 * <h2>Por qué esto existe además de las pruebas de componente</h2>
 * En jsdom, axe **no puede evaluar el contraste de color en absoluto** — no hay motor de render, no
 * hay `getContext`, y esas reglas se saltan en silencio. Una prueba de componente que pasa no dice
 * nada sobre el contraste. Aquí sí, porque es Chromium.
 *
 * <h2>Alcance: sólo login</h2>
 * Es la única pantalla alcanzable sin sesión, y el repositorio no contiene credenciales. **Todas las
 * pantallas de la aplicación quedan sin comprobar por este archivo**, y eso es exactamente lo que
 * mantiene la deuda D9 abierta en vez de resuelta.
 */
test.describe("Accesibilidad", () => {
  test("la pantalla de login no tiene violaciones que axe pueda detectar", async ({ page }) => {
    await page.goto("/login");
    await expect(page.getByRole("button", { name: /^ingresar$|^sign in$/i })).toBeVisible();

    const results = await new AxeBuilder({ page })
      // Las que se pueden comprobar automáticamente con sentido en una página completa.
      .withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"])
      .analyze();

    // Se imprime el detalle antes de fallar: "hay 3 violaciones" no le sirve a nadie, y el nodo
    // concreto y la regla sí.
    if (results.violations.length > 0) {
      console.log(JSON.stringify(results.violations.map((violation) => ({
        id: violation.id,
        impact: violation.impact,
        help: violation.help,
        nodes: violation.nodes.map((node) => node.html),
      })), null, 2));
    }

    expect(results.violations).toEqual([]);
  });

  test("el 404 tampoco", async ({ page }) => {
    await page.goto("/no-existe-esta-ruta");

    const results = await new AxeBuilder({ page })
      .withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"])
      .analyze();

    if (results.violations.length > 0) {
      console.log(JSON.stringify(results.violations.map((violation) => ({
        id: violation.id, impact: violation.impact, help: violation.help,
        nodes: violation.nodes.map((node) => node.html),
      })), null, 2));
    }

    expect(results.violations).toEqual([]);
  });
});
