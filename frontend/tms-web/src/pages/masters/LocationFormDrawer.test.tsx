import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

/**
 * D1 (QAS 2026-10-22): «Nueva ubicación» nacía con País = PE y huso America/Lima en una empresa
 * de Ecuador. Ahora parte del país y el huso de la empresa activa.
 */

const company = vi.hoisted(() => ({
  selected: { id: "company-ec", code: "PLAPASA", name: "PLAPASA", timeZone: "America/Guayaquil", defaultCountry: "EC" } as
    { id: string; code: string; name: string; timeZone: string; defaultCountry?: string },
}));

vi.mock("../../shared/company/CompanyContext", () => ({
  useCompany: () => ({ selected: company.selected, hasCapability: () => true, hasPermission: () => true }),
}));

vi.mock("../../shared/maps/googleMapsLoader", () => ({
  isGoogleMapsConfigured: () => false,
  loadCoreLibrary: () => Promise.reject(new Error("no key")),
  loadMapsLibrary: () => Promise.reject(new Error("no key")),
  loadMarkerLibrary: () => Promise.reject(new Error("no key")),
  loadGeocodingLibrary: () => Promise.reject(new Error("no key")),
  loadPlacesLibrary: () => Promise.reject(new Error("no key")),
}));

import { LocationFormDrawer } from "./LocationFormDrawer";

beforeEach(() => {
  vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify({ content: [], page: 0, size: 200, totalElements: 0 }), {
    status: 200, headers: { "content-type": "application/json" },
  })));
});

afterEach(() => {
  vi.unstubAllGlobals();
});

function renderNew(presetRole?: "ORIGIN" | "DESTINATION") {
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <LocationFormDrawer companyId={company.selected.id} location={null} presetRole={presetRole}
        onClose={vi.fn()} onSaved={vi.fn()} />
    </QueryClientProvider>,
  );
}

describe("una ubicación nueva", () => {
  it("nace en el país y el huso de la empresa activa (Ecuador)", async () => {
    renderNew();

    expect(await screen.findByRole("textbox", { name: /País/ })).toHaveValue("EC");
    expect(screen.getByRole("combobox", { name: /Zona horaria/ })).toHaveValue("America/Guayaquil");
  });

  it("sin país en el perfil (backend anterior) cae a PE, con el huso de la empresa", async () => {
    company.selected = { id: "company-pe", code: "DEMO", name: "Demo", timeZone: "America/Lima" };
    renderNew();

    expect(await screen.findByRole("textbox", { name: /País/ })).toHaveValue("PE");
    expect(screen.getByRole("combobox", { name: /Zona horaria/ })).toHaveValue("America/Lima");
  });
});

describe("el contacto de entrega (V75) es solo de un destino", () => {
  it("un origen no muestra quién recibe ni las indicaciones", async () => {
    renderNew("ORIGIN");

    await screen.findByRole("textbox", { name: /País/ });
    expect(screen.queryByText("Contacto de entrega")).not.toBeInTheDocument();
    expect(screen.queryByRole("textbox", { name: "Persona de contacto" })).not.toBeInTheDocument();
  });

  it("un destino sí los muestra", async () => {
    renderNew("DESTINATION");

    expect(await screen.findByRole("textbox", { name: "Persona de contacto" })).toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: "Teléfono de contacto" })).toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: "Indicaciones de entrega" })).toBeInTheDocument();
  });

  it("aparecen en cuanto el lugar se marca también como destino", async () => {
    renderNew("ORIGIN");
    await screen.findByRole("textbox", { name: /País/ });

    await userEvent.click(screen.getByRole("checkbox", { name: /Destino/ }));

    expect(screen.getByRole("textbox", { name: "Persona de contacto" })).toBeInTheDocument();
  });
});
