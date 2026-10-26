import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
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

function renderNew() {
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <LocationFormDrawer companyId={company.selected.id} location={null} onClose={vi.fn()} onSaved={vi.fn()} />
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
