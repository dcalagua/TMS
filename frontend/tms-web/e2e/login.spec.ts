import { expect, test } from "@playwright/test";
import { watchForProblems } from "./support/console";

/**
 * La pantalla de login: la única alcanzable sin sesión, y por tanto la única que puede probarse
 * de extremo a extremo sin credenciales dentro del repositorio.
 */
test.describe("Login", () => {
  test("se sirve y pide correo y contraseña", async ({ page }) => {
    const problems = watchForProblems(page);

    const response = await page.goto("/login");

    expect(response?.status()).toBe(200);
    await expect(page.getByLabel(/correo electrónico|email/i)).toBeVisible();
    await expect(page.getByLabel(/^contraseña$|^password$/i)).toBeVisible();
    await expect(page.getByRole("button", { name: /^ingresar$|^sign in$/i })).toBeVisible();
    expect(problems.consoleErrors).toEqual([]);
  });

  test("valida antes de llamar a nadie: sin correo no se envía nada", async ({ page }) => {
    await page.goto("/login");
    const apiCalls: string[] = [];
    page.on("request", (request) => {
      if (request.url().includes("/api/v1/")) apiCalls.push(request.url());
    });

    await page.getByRole("button", { name: /^ingresar$|^sign in$/i }).click();

    // Un formulario que sale a la red con el correo vacío gasta una petición para nada y, peor,
    // enseña un error de servidor donde correspondía un error de campo.
    expect(apiCalls).toEqual([]);
  });

  test("la marca es visible, para que nadie dude de en qué producto está entrando", async ({ page }) => {
    await page.goto("/login");

    await expect(page.getByText(/TMS/i).first()).toBeVisible();
  });
});
