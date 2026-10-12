import { afterEach, describe, expect, it } from "vitest";
import type { CompanyAccessView, MeView } from "../api/meApi";
import { clearMeCopy, readMeCopy, writeMeCopy } from "./meOfflineCopy";

/**
 * La copia de `/me` con la que el portal arranca sin señal (ADR-019 §1): solo de transportistas,
 * solo para quien la leyó, y fuera al cerrar sesión.
 */

const company = (carrierId: string | null): CompanyAccessView => ({
  id: "c-1", code: "DEMO", name: "Demo", timeZone: "America/Lima",
  organization: { id: "o-1", code: "ORG", name: "Org" }, permissions: [], capabilities: [],
  carrierId, carrierName: carrierId ? "Transportes Andinos" : null,
});

const me = (carrierId: string | null): MeView => ({
  user: { id: "u-1", email: "chofer@andinos.pe", fullName: "Juan" } as MeView["user"],
  companies: [company(carrierId)],
});

afterEach(() => window.localStorage.clear());

describe("la copia de /me para arrancar sin señal", () => {
  it("se guarda para un usuario de transportista y solo la lee ese usuario", () => {
    writeMeCopy("u-1", me("car-1"));

    expect(readMeCopy("u-1")?.companies[0].carrierId).toBe("car-1");
    expect(readMeCopy("u-2")).toBeUndefined();
    expect(readMeCopy(null)).toBeUndefined();
  });

  it("no se guarda para un usuario de oficina, y borra la que hubiera", () => {
    writeMeCopy("u-1", me("car-1"));
    writeMeCopy("u-1", me(null));

    expect(readMeCopy("u-1")).toBeUndefined();
  });

  it("al cerrar sesión se borra; al entrar otro usuario, se borra la del anterior", () => {
    writeMeCopy("u-1", me("car-1"));
    clearMeCopy("u-1");
    expect(readMeCopy("u-1")).toBeDefined();

    clearMeCopy("u-2");
    expect(readMeCopy("u-1")).toBeUndefined();

    writeMeCopy("u-1", me("car-1"));
    clearMeCopy();
    expect(readMeCopy("u-1")).toBeUndefined();
  });
});
