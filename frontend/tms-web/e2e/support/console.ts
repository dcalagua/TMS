import type { ConsoleMessage, Page, Request } from "@playwright/test";

/**
 * Recoge de qué se queja el navegador mientras dura una prueba.
 *
 * Existe porque un `expect` que pasa no dice nada sobre lo que la pantalla escupió por consola de
 * camino: un `TypeError` dentro de un efecto, o un import dinámico que devuelve 404, dejan la
 * vista "renderizada" y rota. Los dos se recogen aquí y se afirman como parte del smoke.
 */
export interface BrowserProblems {
  readonly consoleErrors: string[];
  readonly failedRequests: string[];
}

/**
 * Ruido del entorno de prueba, no de la aplicación.
 *
 * Sin backend ni proveedor de identidad levantados, la llamada de sesión de Supabase y las del
 * API fallan por red. Eso es exactamente lo que esta suite provoca para poder comprobar que la
 * aplicación redirige al login en vez de romperse, así que contarlo como defecto sería contar el
 * escenario como fallo.
 */
const IGNORED = [/supabase/i, /Failed to load resource/i, /net::ERR_/i, /localhost:8080/];

function ignored(text: string): boolean {
  return IGNORED.some((pattern) => pattern.test(text));
}

export function watchForProblems(page: Page): BrowserProblems {
  const consoleErrors: string[] = [];
  const failedRequests: string[] = [];

  page.on("console", (message: ConsoleMessage) => {
    if (message.type() === "error" && !ignored(message.text())) {
      consoleErrors.push(message.text());
    }
  });

  page.on("requestfailed", (request: Request) => {
    const line = `${request.method()} ${request.url()} -> ${request.failure()?.errorText ?? "unknown"}`;
    if (!ignored(line)) {
      failedRequests.push(line);
    }
  });

  return { consoleErrors, failedRequests };
}
