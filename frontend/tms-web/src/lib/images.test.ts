import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { MAX_UPLOAD_FILE_BYTES, PHOTO_JPEG_QUALITY, downscalePhoto, photoProblem } from "./images";

/**
 * La reducción de fotos del portal. jsdom no tiene canvas ni `createImageBitmap`, así que se
 * simulan: lo que se comprueba es la decisión (a qué tamaño, en qué formato, cuándo quedarse con
 * el original), no el píxel.
 */

interface FakeCanvas {
  width: number;
  height: number;
  getContext: ReturnType<typeof vi.fn>;
  toBlob: ReturnType<typeof vi.fn>;
}

let canvas: FakeCanvas;
let drawImage: ReturnType<typeof vi.fn>;
const realCreateElement = document.createElement.bind(document);

function stubBitmap(width: number, height: number) {
  vi.stubGlobal("createImageBitmap", vi.fn(async () => ({ width, height, close: vi.fn() })));
}

function stubCanvas(outputBytes: number) {
  drawImage = vi.fn();
  canvas = {
    width: 0,
    height: 0,
    getContext: vi.fn(() => ({ drawImage })),
    toBlob: vi.fn((cb: (blob: Blob | null) => void, type: string) => cb(new Blob([new Uint8Array(outputBytes)], { type }))),
  };
  vi.spyOn(document, "createElement").mockImplementation(((tag: string) =>
    tag === "canvas" ? (canvas as unknown as HTMLCanvasElement) : realCreateElement(tag)) as typeof document.createElement);
}

function photo(bytes: number, type = "image/jpeg", name = "IMG_0001.jpg"): File {
  return new File([new Uint8Array(bytes)], name, { type });
}

beforeEach(() => {
  vi.restoreAllMocks();
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("downscalePhoto", () => {
  it("lleva el lado mayor a 1600 px y reencoda en JPEG al 80 %", async () => {
    stubBitmap(4000, 3000);
    stubCanvas(300_000);

    const out = await downscalePhoto(photo(5_000_000));

    expect(canvas.width).toBe(1600);
    expect(canvas.height).toBe(1200);
    expect(drawImage).toHaveBeenCalledWith(expect.anything(), 0, 0, 1600, 1200);
    expect(canvas.toBlob).toHaveBeenCalledWith(expect.any(Function), "image/jpeg", PHOTO_JPEG_QUALITY);
    expect(out.type).toBe("image/jpeg");
    expect(out.name).toBe("IMG_0001.jpg");
    expect(out.size).toBe(300_000);
  });

  it("respeta la orientación vertical", async () => {
    stubBitmap(3000, 4000);
    stubCanvas(200_000);

    await downscalePhoto(photo(4_000_000));

    expect(canvas.width).toBe(1200);
    expect(canvas.height).toBe(1600);
  });

  it("una foto pequeña que crecería al reencodarse se queda como estaba", async () => {
    stubBitmap(800, 600);
    stubCanvas(90_000);
    const original = photo(50_000);

    expect(await downscalePhoto(original)).toBe(original);
  });

  it("convierte a JPEG una imagen de un tipo que el servidor no acepta, aunque no haya que reducirla", async () => {
    stubBitmap(800, 600);
    stubCanvas(90_000);

    const out = await downscalePhoto(photo(50_000, "image/heic", "IMG_0002.HEIC"));

    expect(out.type).toBe("image/jpeg");
    expect(out.name).toBe("IMG_0002.jpg");
  });

  it("sin createImageBitmap (o si falla) devuelve el original en lugar de impedir el reporte", async () => {
    const original = photo(1_000);
    expect(await downscalePhoto(original)).toBe(original);

    vi.stubGlobal("createImageBitmap", vi.fn(async () => { throw new Error("decode"); }));
    expect(await downscalePhoto(original)).toBe(original);
  });

  it("no toca lo que no es una imagen", async () => {
    stubBitmap(4000, 3000);
    const pdf = new File([new Uint8Array(10)], "guia.pdf", { type: "application/pdf" });
    expect(await downscalePhoto(pdf)).toBe(pdf);
  });
});

describe("photoProblem", () => {
  it("acepta JPEG, PNG y WebP dentro del límite", () => {
    expect(photoProblem(photo(1_000, "image/jpeg"))).toBeNull();
    expect(photoProblem(photo(1_000, "image/png"))).toBeNull();
    expect(photoProblem(photo(1_000, "image/webp"))).toBeNull();
  });

  it("rechaza otros tipos y lo que supera 6 MB", () => {
    expect(photoProblem(photo(1_000, "image/heic"))).toBe("type");
    expect(photoProblem(photo(MAX_UPLOAD_FILE_BYTES + 1))).toBe("size");
  });
});
