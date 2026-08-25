import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { KPI_REPORT_FIXTURE } from "../../test/fixtures/kpiReport";

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
