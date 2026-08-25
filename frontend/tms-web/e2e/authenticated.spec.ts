import { expect, test } from "@playwright/test";
import { CORE_MODULES } from "./support/modules";
import { watchForProblems } from "./support/console";

/**
 * Smoke autenticado contra un entorno real (QAS, o cualquiera que se le indique).
 *
 * <h2>Por qué está separado del resto</h2>
 * Las otras specs prueban lo que se puede afirmar sin sesión: que el servidor sirve cada ruta,
 * que las protegidas redirigen al login, que no se acumulan errores de consola. Eso corre en
 * cualquier sitio y no necesita nada. Esto necesita un entorno con datos y una cuenta, así que
 * en vez de fallar cuando no los hay, se salta y lo dice.
 *
 * <h2>Credenciales</h2>
 * Nunca en el repositorio. Se leen del entorno:
 *
 *     E2E_USER_EMAIL      correo de una cuenta de prueba
 *     E2E_USER_PASSWORD   su contraseña
 *     E2E_BASE_URL        opcional; por defecto el `vite preview` local
 *
 * Sin las dos primeras, cada prueba de este fichero se marca como saltada. Saltada es visible en
 * el informe y cuenta como no ejecutada; que es la verdad, y es preferible a un verde que en
 * realidad no probó nada.
 */

const EMAIL = process.env.E2E_USER_EMAIL;
const PASSWORD = process.env.E2E_USER_PASSWORD;
const CREDENTIALS_AVAILABLE = Boolean(EMAIL && PASSWORD);

test.describe("Smoke autenticado", () => {
  test.skip(
    !CREDENTIALS_AVAILABLE,
    "E2E_USER_EMAIL y E2E_USER_PASSWORD no están definidas: se omite el smoke autenticado.",
  );

  test.beforeEach(async ({ page }) => {
    await page.goto("/login");
    await page.getByLabel(/correo electrónico|email/i).fill(EMAIL as string);
    await page.getByLabel(/^contraseña$|^password$/i).fill(PASSWORD as string);
    await page.getByRole("button", { name: /^ingresar$|^sign in$/i }).click();

    // La sesión ha empezado cuando dejamos de estar en /login. Se espera a eso y no a un
    // elemento concreto: el destino depende de si la persona tiene una sola empresa o varias.
    await expect(page).not.toHaveURL(/\/login/, { timeout: 20_000 });
  });

  for (const module of CORE_MODULES) {
    test(`${module.label} carga con sesión, sin errores de consola ni de red`, async ({ page }) => {
      const problems = watchForProblems(page);
      const failedApiCalls: string[] = [];

      page.on("response", (response) => {
        // Lo que importa es la llamada de negocio. Un 401 en la ruta de refresco de sesión es
        // parte del ciclo normal de Supabase y no dice nada sobre esta pantalla.
        if (response.url().includes("/api/v1/") && response.status() >= 400) {
          failedApiCalls.push(`${response.status()} ${response.url()}`);
        }
      });

      await page.goto(module.path);
      await page.waitForLoadState("networkidle");

      expect(failedApiCalls, `llamadas de API con error en ${module.label}`).toEqual([]);
      expect(problems.consoleErrors, `errores de consola en ${module.label}`).toEqual([]);
      expect(problems.failedRequests, `peticiones caídas en ${module.label}`).toEqual([]);
    });
  }
});
