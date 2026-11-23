import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "./httpClient";
import { requestPasswordReset } from "./passwordResetApi";

/** «¿Olvidaste tu contraseña?» (ADR-031): una llamada anónima al backend, que envía por MailHub. */
describe("requestPasswordReset", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("pide el correo al backend, sin sesión y con la dirección recortada", async () => {
    const fetchMock = vi.fn(async () => new Response(null, { status: 202 }));
    vi.stubGlobal("fetch", fetchMock);

    await requestPasswordReset("  rosa.quispe@empresa.com ");

    const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toMatch(/\/auth\/password-reset$/);
    expect(init.method).toBe("POST");
    expect(init.credentials).toBe("omit");
    expect(new Headers(init.headers).has("Authorization")).toBe(false);
    expect(JSON.parse(String(init.body))).toEqual({ email: "rosa.quispe@empresa.com" });
  });

  it("un límite de intentos llega como ApiError 429", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify({ code: "rate-limited" }), {
      status: 429, headers: { "content-type": "application/problem+json" },
    })));

    await expect(requestPasswordReset("rosa.quispe@empresa.com")).rejects.toSatisfy(
      (error: unknown) => error instanceof ApiError && error.status === 429);
  });
});
