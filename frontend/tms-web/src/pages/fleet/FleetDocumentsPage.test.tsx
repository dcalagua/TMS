import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { FleetComplianceSummary, FleetDocumentView } from "../../shared/api/fleetDocumentsApi";
import { expiryText } from "./fleetDocumentText";

/**
 * Documentos de flota (V72), con la API simulada en `fetch`.
 *
 * Lo que se protege: que el estado lo trae el backend y la pantalla no lo recalcula, que «Sin
 * registrar» no se pinta como vencido, que un KPI filtra la lista por su estado, y que quien sólo lee
 * no ve botones para escribir.
 */

let permissions: string[] = [];

vi.mock("../../shared/company/CompanyContext", () => ({
  useCompany: () => ({
    selected: { id: "company-1", code: "QAS-PLAPASA", name: "PLAPASA (demo)" },
    hasCapability: () => true,
    hasPermission: (permission: string) => permissions.includes(permission),
  }),
}));

import { FleetDocumentsPage } from "./FleetDocumentsPage";

const DOCUMENT = (overrides: Partial<FleetDocumentView>): FleetDocumentView => ({
  id: "doc-1", subjectType: "VEHICLE", subjectId: "veh-1", subjectCode: "GBC-5678", subjectName: "CAM-02",
  documentType: "TECHNICAL_INSPECTION", documentNumber: "RTV-GYE-0011876", issuer: "ATM Guayaquil (demo)",
  issuedOn: "2025-11-15", expiresOn: "2026-11-15", notes: null, active: true, status: "EXPIRING_SOON",
  daysToExpiry: 24, mandatory: true, enforcement: "BLOCK", createdAt: "2026-10-01T12:00:00Z",
  updatedAt: "2026-10-01T12:00:00Z", version: 0, ...overrides,
});

const SUMMARY: FleetComplianceSummary = {
  today: "2026-10-22", alertDays: 30, total: 21, valid: 18, expiringSoon: 2, expired: 1, unrecorded: 0,
  missingMandatory: 0,
  ageing: [
    { bucket: "EXPIRED_OVER_90", count: 0 }, { bucket: "EXPIRED_31_90", count: 0 }, { bucket: "EXPIRED_1_30", count: 1 },
    { bucket: "DUE_0_7", count: 0 }, { bucket: "DUE_8_30", count: 2 }, { bucket: "DUE_31_90", count: 3 },
    { bucket: "DUE_OVER_90", count: 15 }, { bucket: "NO_DATE", count: 0 },
  ],
};

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });
}

let fetchMock: ReturnType<typeof vi.fn>;

beforeEach(() => {
  permissions = ["fleet.document:read"];
  fetchMock = vi.fn(async (input: string) => {
    const url = new URL(input);
    if (url.pathname.endsWith("/fleet/documents/summary")) return json(SUMMARY);
    if (url.pathname.endsWith("/fleet/documents/missing")) return json([]);
    if (url.pathname.endsWith("/fleet/documents")) {
      const rows = [
        DOCUMENT({}),
        DOCUMENT({ id: "doc-2", subjectCode: "GSD-1357", subjectName: "CAM-04", documentType: "CARGO_INSURANCE",
          documentNumber: "POL-CARGA-76544", expiresOn: "2026-09-30", status: "EXPIRED", daysToExpiry: -22,
          mandatory: false, enforcement: "WARN" }),
      ];
      const status = url.searchParams.get("status");
      const content = status ? rows.filter((row) => row.status === status) : rows;
      return json({ content, page: 0, size: 25, totalElements: content.length });
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
      <FleetDocumentsPage />
    </QueryClientProvider>,
  );
}

describe("la pantalla de documentos de flota", () => {
  it("lista los papeles con el estado que trae el backend y su vencimiento en días", async () => {
    renderPage();

    const row = (await screen.findByText("GBC-5678")).closest("tr") as HTMLElement;
    expect(within(row).getByText("Por vencer")).toBeInTheDocument();
    expect(within(row).getByText("Vence en 24 días")).toBeInTheDocument();
    const expired = screen.getByText("GSD-1357").closest("tr") as HTMLElement;
    expect(within(expired).getByText("Vencido")).toBeInTheDocument();
    expect(within(expired).getByText("Venció hace 22 días")).toBeInTheDocument();
    // La política viaja con cada fila: una póliza de carga vencida sólo avisa.
    expect(within(expired).getByText("Avisa")).toBeInTheDocument();
  });

  it("un KPI filtra la lista por su estado, en el servidor", async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByText("GBC-5678");

    await user.click(screen.getByRole("button", { name: /Vencidos/ }));

    expect(await screen.findByText("GSD-1357")).toBeInTheDocument();
    expect(screen.queryByText("GBC-5678")).not.toBeInTheDocument();
    const last = new URL(fetchMock.mock.calls.map(([input]) => input as string)
      .filter((input) => new URL(input).pathname.endsWith("/fleet/documents")).at(-1) as string);
    expect(last.searchParams.get("status")).toBe("EXPIRED");
  });

  it("sin fleet.document:manage no ofrece registrar ni editar", async () => {
    renderPage();
    await screen.findByText("GBC-5678");

    expect(screen.queryByRole("button", { name: /Nuevo documento/ })).not.toBeInTheDocument();
    expect(screen.queryByRole("columnheader", { name: "Acciones" })).not.toBeInTheDocument();
  });

  it("con fleet.document:manage ofrece registrar un documento", async () => {
    permissions = ["fleet.document:read", "fleet.document:manage"];
    renderPage();

    expect(await screen.findByRole("button", { name: /Nuevo documento/ })).toBeInTheDocument();
  });
});

describe("el texto del vencimiento", () => {
  it("dice hoy, mañana, en N días o hace N días; sin fecha no es vencido", () => {
    expect(expiryText(0)).toBe("Vence hoy");
    expect(expiryText(1)).toBe("Vence mañana");
    expect(expiryText(10)).toBe("Vence en 10 días");
    expect(expiryText(-1)).toBe("Venció ayer");
    expect(expiryText(-5)).toBe("Venció hace 5 días");
    expect(expiryText(null)).toBe("Sin fecha de vencimiento");
  });
});
