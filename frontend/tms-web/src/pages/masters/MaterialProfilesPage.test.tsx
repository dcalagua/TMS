import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { MaterialProfileView } from "../../shared/api/materialProfilesApi";

/**
 * Perfiles de material: un maestro de solo lectura cuyo dueño es el almacén (EWM).
 *
 * Se protege lo que se pierde fácil en un refactor: las filas con su modo de apilado en palabras,
 * que la búsqueda llega al backend como `search=`, que el detalle se abre en un drawer y enseña el
 * incremento de anidado solo para un material anidable, y que no aparece ningún botón de alta ni de
 * edición, porque TMS no es dueño de este dato.
 */

let permissions: string[] = ["masterdata.material_profile:read"];

vi.mock("../../shared/company/CompanyContext", () => ({
  useCompany: () => ({
    selected: { id: "company-1", code: "DEMO-LIMA", name: "Demo Lima" },
    hasCapability: () => true,
    hasPermission: (p: string) => permissions.includes(p),
  }),
}));

import { MaterialProfilesPage } from "./MaterialProfilesPage";

const BOX: MaterialProfileView = {
  id: "mp-1",
  materialCode: "MAT-CAJA-01",
  description: "Caja de cartón 40x30",
  sourceSystem: "EWM_EBIM",
  sourceUpdatedAt: "2026-10-10T08:00:00-05:00",
  lengthM: 0.4, widthM: 0.3, heightM: 0.25,
  unitWeightKg: 2.5, unitVolumeM3: 0.03,
  stackMode: "STACKABLE",
  stackMaxQuantity: 6,
  nestIncrementM: null,
  maxTopLoadKg: 40,
  orientationLocked: false,
  version: 3,
  createdAt: "2026-09-01T10:00:00Z",
  updatedAt: "2026-10-10T13:05:00Z",
};

const BUCKET: MaterialProfileView = {
  ...BOX,
  id: "mp-2",
  materialCode: "MAT-BALDE-20",
  description: "Balde plástico 20 L",
  stackMode: "NESTABLE",
  stackMaxQuantity: 25,
  nestIncrementM: 0.035,
  orientationLocked: true,
  version: 7,
};

const UNDECLARED: MaterialProfileView = {
  ...BOX,
  id: "mp-3",
  materialCode: "MAT-SUELTO",
  description: null,
  lengthM: null, widthM: null, heightM: null,
  stackMode: null,
  stackMaxQuantity: null,
};

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), { status: 200, headers: { "content-type": "application/json" } });
}

let fetchMock: ReturnType<typeof vi.fn>;

beforeEach(() => {
  permissions = ["masterdata.material_profile:read"];
  fetchMock = vi.fn(async (input: string) => {
    const url = new URL(input);
    if (url.pathname.endsWith("/masterdata/material-profiles/mp-2")) return json(BUCKET);
    if (url.pathname.endsWith("/masterdata/material-profiles/mp-1")) return json(BOX);
    if (url.pathname.endsWith("/masterdata/material-profiles")) {
      const search = url.searchParams.get("search");
      const rows = search ? [BUCKET] : [BOX, BUCKET, UNDECLARED];
      return json({ content: rows, page: 0, size: 25, totalElements: rows.length });
    }
    return new Response("{}", { status: 404 });
  });
  vi.stubGlobal("fetch", fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <MaterialProfilesPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

function listCalls(): URL[] {
  return fetchMock.mock.calls
    .map(([input]) => new URL(input as string))
    .filter((url) => url.pathname.endsWith("/masterdata/material-profiles"));
}

describe("la pantalla de perfiles de material", () => {
  it("pinta las filas con medidas y modo de apilado, ordenadas por código", async () => {
    renderPage();

    expect(await screen.findByText("MAT-CAJA-01")).toBeInTheDocument();
    expect(screen.getByText("MAT-BALDE-20")).toBeInTheDocument();
    expect(screen.getByText("Apilable")).toBeInTheDocument();
    expect(screen.getByText("Anidable")).toBeInTheDocument();
    expect(screen.getByText("No declarado")).toBeInTheDocument();
    expect(screen.getAllByText("0,4 × 0,3 × 0,25 m").length).toBe(2);
    expect(screen.getByText(/pertenecen al sistema de almacén \(EWM\)/)).toBeInTheDocument();

    const first = listCalls()[0];
    expect(first.searchParams.get("sort")).toBe("materialCode,asc");
    expect(first.searchParams.get("page")).toBe("0");
    expect(first.searchParams.get("size")).toBe("25");
    expect(first.searchParams.get("search")).toBeNull();
    const init = fetchMock.mock.calls[0][1] as RequestInit;
    expect((init.headers as Record<string, string>)["X-Company-Id"]).toBe("company-1");
  });

  it("la búsqueda pide al backend con search=", async () => {
    renderPage();
    await screen.findByText("MAT-CAJA-01");

    await userEvent.type(screen.getByPlaceholderText("Código o descripción"), "balde{Enter}");

    await waitFor(() => expect(listCalls().some((url) => url.searchParams.get("search") === "balde")).toBe(true));
    await waitFor(() => expect(screen.queryByText("MAT-CAJA-01")).not.toBeInTheDocument());
    expect(screen.getByText("MAT-BALDE-20")).toBeInTheDocument();
  });

  it("al pulsar una fila anidable abre el drawer con el incremento de anidado", async () => {
    renderPage();

    await userEvent.click(await screen.findByText("MAT-BALDE-20"));

    const drawer = await screen.findByRole("dialog");
    expect(within(drawer).getByText("Incremento de anidado")).toBeInTheDocument();
    expect(within(drawer).getByText("0,035 m")).toBeInTheDocument();
    expect(within(drawer).getByText("Orientación bloqueada")).toBeInTheDocument();
    expect(within(drawer).getByText("Sí")).toBeInTheDocument();
    expect(within(drawer).getByText("EWM_EBIM")).toBeInTheDocument();
    expect(within(drawer).getByText("7")).toBeInTheDocument();
    await waitFor(() => expect(fetchMock.mock.calls.some(([input]) =>
      new URL(input as string).pathname.endsWith("/masterdata/material-profiles/mp-2"))).toBe(true));
  });

  it("un material que no es anidable no enseña incremento de anidado", async () => {
    renderPage();

    await userEvent.click(await screen.findByText("MAT-CAJA-01"));

    const drawer = await screen.findByRole("dialog");
    expect(within(drawer).getByText("Modo de apilado")).toBeInTheDocument();
    expect(within(drawer).queryByText("Incremento de anidado")).not.toBeInTheDocument();
    expect(within(drawer).getByText("No")).toBeInTheDocument();
  });

  it("es de solo lectura: no ofrece crear, editar ni eliminar", async () => {
    renderPage();
    await screen.findByText("MAT-CAJA-01");

    expect(screen.queryByRole("button", { name: /nuevo|nueva|crear|editar|eliminar/i })).not.toBeInTheDocument();
    expect(screen.queryByText("Acciones")).not.toBeInTheDocument();
  });

  it("sin el permiso de lectura no pide nada y lo dice", async () => {
    permissions = [];
    renderPage();

    expect(await screen.findByText("Tu cuenta no tiene permiso para ver los perfiles de material.")).toBeInTheDocument();
    expect(listCalls()).toHaveLength(0);
  });
});
