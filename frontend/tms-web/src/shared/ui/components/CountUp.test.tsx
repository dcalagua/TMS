import { act, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { CountUp } from "./CountUp";

/** Un reloj de cuadros a mano: cada `frame(ms)` adelanta el tiempo y corre lo que esperaba. */
let now = 0;
let queue: FrameRequestCallback[] = [];
function frame(ms: number) {
  now += ms;
  const pending = queue;
  queue = [];
  act(() => pending.forEach((callback) => callback(now)));
}

function reducedMotion(reduce: boolean) {
  vi.stubGlobal("matchMedia", (query: string) => ({
    matches: reduce && query.includes("prefers-reduced-motion"), media: query, onchange: null,
    addListener: () => undefined, removeListener: () => undefined,
    addEventListener: () => undefined, removeEventListener: () => undefined, dispatchEvent: () => false,
  }));
}

beforeEach(() => {
  now = 0;
  queue = [];
  vi.stubGlobal("requestAnimationFrame", (callback: FrameRequestCallback) => queue.push(callback));
  vi.stubGlobal("cancelAnimationFrame", () => undefined);
  vi.spyOn(performance, "now").mockImplementation(() => now);
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("CountUp", () => {
  it("cuenta desde 0 y termina en el texto exacto", () => {
    reducedMotion(false);
    render(<CountUp value="1.278 kg" />);
    expect(screen.getByText("0 kg")).toBeInTheDocument();

    frame(0);
    frame(300);
    const middle = screen.getByText(/kg$/).textContent ?? "";
    expect(middle).not.toBe("0 kg");
    expect(middle).not.toBe("1.278 kg");

    frame(700);
    expect(screen.getByText("1.278 kg")).toBeInTheDocument();
  });

  it("con «reducir movimiento», la cifra aparece quieta", () => {
    reducedMotion(true);
    render(<CountUp value="66,7 %" />);
    expect(screen.getByText("66,7 %")).toBeInTheDocument();
    expect(queue).toHaveLength(0);
  });

  it("lo que no es una sola cantidad se pinta tal cual", () => {
    reducedMotion(false);
    render(<><span><CountUp value="Sin datos" /></span><span><CountUp value="-" /></span></>);
    expect(screen.getByText("Sin datos")).toBeInTheDocument();
    expect(screen.getByText("-")).toBeInTheDocument();
  });
});
