import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { LocationPickerMap } from "./LocationPickerMap";

/**
 * El selector de ubicación, ya sobre Leaflet.
 *
 * Lo que estas pruebas protegen no es el dibujo del mapa - eso no se puede afirmar en jsdom, que
 * no pinta teselas - sino las tres promesas que el formulario de ubicaciones le hace a quien lo
 * usa y que un refactor rompe sin que nadie lo note:
 *
 * 1. **El mapa aparece sin clave de API.** Es el cambio entero: antes, sin
 *    `VITE_GOOGLE_MAPS_API_KEY`, este componente se sustituía por un aviso y había que teclear
 *    coordenadas. Una regresión aquí devolvería el producto a ese estado.
 * 2. **Elegir un resultado escribe las coordenadas.** El mapa no guarda estado propio: su único
 *    efecto observable es `onChange`, y si se pierde, la persona mueve el pin y no se guarda nada.
 * 3. **«No hay resultados» y «no pude preguntar» se cuentan distinto.** Para quien busca no son
 *    lo mismo: lo primero se arregla escribiendo otra cosa y lo segundo no, y un único mensaje
 *    genérico manda a la gente a corregir una dirección que estaba bien.
 */

const ORIGINAL_FETCH = globalThis.fetch;

afterEach(() => {
  globalThis.fetch = ORIGINAL_FETCH;
  vi.restoreAllMocks();
});

function mockGeocoder(rows: unknown, init: { ok?: boolean; status?: number } = {}) {
  const fetchMock = vi.fn().mockResolvedValue({
    ok: init.ok ?? true,
    status: init.status ?? 200,
    json: async () => rows,
  } as Response);
  globalThis.fetch = fetchMock as unknown as typeof fetch;
  return fetchMock;
}

describe("LocationPickerMap", () => {
  it("muestra el mapa sin ninguna clave de API configurada", () => {
    render(<LocationPickerMap latitude={null} longitude={null} onChange={() => {}} />);

    expect(screen.getByRole("application", { name: "Mapa para seleccionar la ubicación" }))
      .toBeInTheDocument();
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
  });

  it("aplica directamente el único resultado de una búsqueda", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    mockGeocoder([{ display_name: "Av. Jose Larco 500, Miraflores", lat: "-12.1219", lon: "-77.0297" }]);

    render(<LocationPickerMap latitude={null} longitude={null} onChange={onChange} />);
    await user.type(screen.getByRole("textbox", { name: "Buscar una dirección" }), "Larco 500");
    await user.click(screen.getByRole("button", { name: "Buscar" }));

    await waitFor(() => expect(onChange).toHaveBeenCalledWith(-12.1219, -77.0297));
    // Un solo candidato no abre lista: confirmar lo obvio es un clic de más.
    expect(screen.queryByRole("list", { name: "Resultados de la búsqueda" })).not.toBeInTheDocument();
  });

  it("deja elegir cuando hay varios candidatos y sólo entonces escribe las coordenadas", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    mockGeocoder([
      { display_name: "Av. Primavera 800, Surco", lat: "-12.1109", lon: "-76.9764" },
      { display_name: "Av. Primavera 800, San Borja", lat: "-12.0906", lon: "-77.0003" },
    ]);

    render(<LocationPickerMap latitude={null} longitude={null} onChange={onChange} />);
    await user.type(screen.getByRole("textbox", { name: "Buscar una dirección" }), "Primavera 800");
    await user.click(screen.getByRole("button", { name: "Buscar" }));

    const options = await screen.findAllByRole("button", { name: /Av\. Primavera 800/ });
    expect(options).toHaveLength(2);
    expect(onChange).not.toHaveBeenCalled();

    await user.click(options[1]);
    expect(onChange).toHaveBeenCalledWith(-12.0906, -77.0003);
  });

  it("descarta un resultado sin coordenadas utilizables en vez de colocarlo en el golfo de Guinea", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    mockGeocoder([{ display_name: "Sitio sin geocodificar", lat: "no-es-un-numero", lon: null }]);

    render(<LocationPickerMap latitude={null} longitude={null} onChange={onChange} />);
    await user.type(screen.getByRole("textbox", { name: "Buscar una dirección" }), "algo");
    await user.click(screen.getByRole("button", { name: "Buscar" }));

    expect(await screen.findByText("No se encontraron resultados para esa dirección.")).toBeInTheDocument();
    expect(onChange).not.toHaveBeenCalled();
  });

  it("distingue un servicio caído de una dirección inexistente", async () => {
    const user = userEvent.setup();
    mockGeocoder([], { ok: false, status: 503 });

    render(<LocationPickerMap latitude={null} longitude={null} onChange={() => {}} />);
    await user.type(screen.getByRole("textbox", { name: "Buscar una dirección" }), "Larco 500");
    await user.click(screen.getByRole("button", { name: "Buscar" }));

    expect(await screen.findByText(
      "El servicio de búsqueda de direcciones no respondió. Puedes ubicar el punto en el mapa.",
    )).toBeInTheDocument();
    expect(screen.queryByText("No se encontraron resultados para esa dirección.")).not.toBeInTheDocument();
  });

  it("sesga la búsqueda hacia lo que se está viendo, sin excluir el resto del mundo", async () => {
    const user = userEvent.setup();
    const fetchMock = mockGeocoder([]);

    render(<LocationPickerMap latitude={-12.1219} longitude={-77.0297} onChange={() => {}} />);
    await user.type(screen.getByRole("textbox", { name: "Buscar una dirección" }), "Larco");
    await user.click(screen.getByRole("button", { name: "Buscar" }));

    await waitFor(() => expect(fetchMock).toHaveBeenCalled());
    const url = new URL(String(fetchMock.mock.calls[0][0]));
    expect(url.searchParams.get("viewbox")).not.toBeNull();
    expect(url.searchParams.get("bounded")).toBe("0");
  });
});
