import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { KPI_REPORT_FIXTURE } from "../../test/fixtures/kpiReport";
import type { KpiReportView } from "../../shared/api/reportingApi";

/**
 * Reportes y KPIs, una de las tres pantallas que el gate de runtime vigila, en sus tres estados.
 *
 * Lo que se protege es la rama, no el maquetado: mientras cargue tiene que decirlo, si el backend
 * rechaza tiene que mostrar la copy traducida del `code` — nunca el `detail` crudo ni una pantalla
 * en blanco — y con datos tiene que pintar la cabecera. Las tres son regresiones que un
 * typechecker no ve y que en producción se leen como "la pantalla no funciona".
 */

// La empresa seleccionada viene de un contexto que arrastra auth y red. La pantalla solo usa su
// id, así que se sustituye por el valor y no se monta media aplicación para obtenerlo.
vi.mock("../../shared/company/CompanyContext", () => ({
  useCompany: () => ({ selected: { id: "company-1", code: "DEMO-LIMA", name: "Demo Lima" } }),
}));

// El gráfico es SVG sobre recharts y mide su contenedor, que en jsdom siempre es 0x0. No es lo
// que estas pruebas afirman, y dejarlo dentro solo añade ruido de layout.
vi.mock("./DailyColumnChart", () => ({
  DailyColumnChart: () => <div data-testid="daily-chart" />,
}));

import { ReportsPage } from "./ReportsPage";

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0 } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <ReportsPage />
    </QueryClientProvider>,
  );
}

let fetchMock: ReturnType<typeof vi.fn>;

beforeEach(() => {
  fetchMock = vi.fn();
  vi.stubGlobal("fetch", fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("ReportsPage", () => {
  it("anuncia que está cargando mientras la petición está en vuelo", () => {
    fetchMock.mockImplementation(() => new Promise(() => {}));

    renderPage();

    expect(screen.getByText(/cargando/i)).toBeInTheDocument();
  });

  it("pinta la cabecera del informe cuando el backend responde", async () => {
    fetchMock.mockImplementation(
      async () =>
        new Response(JSON.stringify(KPI_REPORT_FIXTURE), {
          status: 200,
          headers: { "content-type": "application/json" },
        }),
    );

    renderPage();

    expect(await screen.findByText("Reportes y KPIs")).toBeInTheDocument();
    // La pantalla monta el gráfico diario más de una vez (una por serie), así que se cuentan.
    expect(screen.getAllByTestId("daily-chart").length).toBeGreaterThan(0);
  });

  /** Fase 50: la liquidación de fletes, por transportista y moneda, sin sumar monedas. */
  it("pinta la liquidación de fletes con su fila por transportista y moneda", async () => {
    const report: KpiReportView = {
      ...KPI_REPORT_FIXTURE,
      settlement: {
        invoicesReceived: 12, invoicesApproved: 9, invoicesWithDiscrepancy: 3,
        discrepancyRatePercent: 25, cycleMedianHours: 30, cycleP90Hours: 120,
        byCarrier: [
          {
            carrierId: "car-1", carrierName: "Transportes Andinos SAC", currency: "PEN",
            invoices: 10, approved: 8, withDiscrepancy: 2, discrepancyRatePercent: 20,
            amountObserved: 450, amountSaved: 300, cycleMedianHours: 24, cycleP90Hours: 72,
          },
          {
            carrierId: "car-1", carrierName: "Transportes Andinos SAC", currency: "USD",
            invoices: 2, approved: 1, withDiscrepancy: 1, discrepancyRatePercent: null,
            amountObserved: 0, amountSaved: 0, cycleMedianHours: null, cycleP90Hours: null,
          },
        ],
      },
    };
    fetchMock.mockImplementation(async () => new Response(JSON.stringify(report), {
      status: 200, headers: { "content-type": "application/json" },
    }));

    renderPage();

    expect(await screen.findByText("Liquidación de fletes")).toBeInTheDocument();
    expect(screen.getByText("12")).toBeInTheDocument();
    expect(screen.getByText("30 h")).toBeInTheDocument();
    expect(screen.getByText("5 d")).toBeInTheDocument();
    // Dos monedas del mismo transportista son dos filas, nunca una suma.
    const rows = screen.getAllByText("Transportes Andinos SAC").map((cell) => cell.closest("tr") as HTMLElement);
    expect(rows).toHaveLength(2);
    expect(within(rows[0]).getByText("PEN")).toBeInTheDocument();
    expect(within(rows[1]).getByText("USD")).toBeInTheDocument();
    // Lo que no se midió es una raya, no un cero.
    expect(within(rows[1]).getAllByText("—").length).toBeGreaterThanOrEqual(2);
  });

  /** Fase 70: finanzas de fletes por moneda, con días a pago y costo por kg. */
  it("pinta las finanzas: provisión abierta, varianza, días a pago y costo por kg", async () => {
    const report: KpiReportView = {
      ...KPI_REPORT_FIXTURE,
      finance: {
        averageDaysToPayment: 12.5, paidInvoices: 4,
        byCurrency: [{
          currency: "PEN", openAccrualBalance: 3200, openAccruals: 5, accrualsBilled: 3, accruedAmount: 3000,
          invoicedAmount: 3150, varianceAmount: 150, variancePercent: 5, allocatedCost: 6000,
          allocatedWeightKg: 12000, costPerKg: 0.5,
        }],
      },
    };
    fetchMock.mockImplementation(async () => new Response(JSON.stringify(report), {
      status: 200, headers: { "content-type": "application/json" },
    }));

    renderPage();

    expect(await screen.findByText("Finanzas de fletes")).toBeInTheDocument();
    expect(screen.getByText("12,5 d")).toBeInTheDocument();
    const row = screen.getByText("PEN", { selector: "td" }).closest("tr") as HTMLElement;
    expect(within(row).getByText("0,5 PEN")).toBeInTheDocument();
  });

  it("las finanzas dicen 'no disponible' sin permiso de provisiones (o con un backend anterior)", async () => {
    fetchMock.mockImplementation(async () => new Response(JSON.stringify({ ...KPI_REPORT_FIXTURE, finance: null }), {
      status: 200, headers: { "content-type": "application/json" },
    }));

    renderPage();

    expect(await screen.findByText("Provisión, pago y costo por kg")).toBeInTheDocument();
  });

  it("la liquidación dice 'no disponible' cuando la cuenta no puede leer facturas", async () => {
    fetchMock.mockImplementation(async () => new Response(JSON.stringify(KPI_REPORT_FIXTURE), {
      status: 200, headers: { "content-type": "application/json" },
    }));

    renderPage();

    expect(await screen.findByText("Liquidación de fletes")).toBeInTheDocument();
    const card = screen.getByText("Auditoría de facturas de transportista").closest(".MuiCard-root") as HTMLElement;
    expect(within(card).getByText(/no disponible/i)).toBeInTheDocument();
  });

  it("sin el campo settlement en la respuesta no se cae: la liquidación dice 'no disponible'", async () => {
    const withoutSettlement: Record<string, unknown> = { ...KPI_REPORT_FIXTURE };
    delete withoutSettlement.settlement;
    fetchMock.mockImplementation(async () => new Response(JSON.stringify(withoutSettlement), {
      status: 200, headers: { "content-type": "application/json" },
    }));

    renderPage();

    expect(await screen.findByText("Liquidación de fletes")).toBeInTheDocument();
    const card = screen.getByText("Auditoría de facturas de transportista").closest(".MuiCard-root") as HTMLElement;
    expect(within(card).getByText(/no disponible/i)).toBeInTheDocument();
  });

  it("muestra la copy del código de problema, no el detail del backend, cuando falla", async () => {
    fetchMock.mockImplementation(
      async () =>
        new Response(
          JSON.stringify({
            status: 403,
            code: "access-denied",
            detail: "principal lacks monitoring.transport:read on company f4e09afc",
          }),
          { status: 403, headers: { "content-type": "application/problem+json" } },
        ),
    );

    renderPage();

    expect(await screen.findByText(/no tienes permiso/i)).toBeInTheDocument();
    // El detail nombra permisos internos y un id de empresa: no es texto para un planificador.
    expect(screen.queryByText(/monitoring.transport:read/)).not.toBeInTheDocument();
  });

  it("un fallo sin documento Problem Details sigue mostrando algo, no una pantalla en blanco", async () => {
    fetchMock.mockImplementation(async () => new Response("<html>502</html>", { status: 502 }));

    renderPage();

    expect(await screen.findByRole("button", { name: /reintentar/i })).toBeInTheDocument();
  });
});
