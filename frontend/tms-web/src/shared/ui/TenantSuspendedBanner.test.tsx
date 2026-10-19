import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import type { OperationalStatusView } from "../api/meApi";
import { TenantSuspendedBanner } from "./TenantSuspendedBanner";

/**
 * ADR-021: el aviso fijo de cuenta suspendida. Es UX; el servidor rechaza las escrituras igual.
 */

function company(operationalStatus: OperationalStatusView | null | undefined) {
  return { operationalStatus };
}

describe("el aviso de cuenta suspendida", () => {
  it("no aparece si la organización opera", () => {
    const { container } = render(
      <TenantSuspendedBanner company={company({ state: "ACTIVE", reason: null, appliedAt: null })} />,
    );

    expect(container).toBeEmptyDOMElement();
  });

  it("no aparece con un backend anterior que no envía el estado", () => {
    const { container } = render(<TenantSuspendedBanner company={company(undefined)} />);

    expect(container).toBeEmptyDOMElement();
  });

  it("por falta de pago pide regularizar el pago con el proveedor", () => {
    render(
      <TenantSuspendedBanner
        company={company({ state: "SUSPENDED", reason: "NON_PAYMENT", appliedAt: "2026-10-13T11:00:00Z" })}
      />,
    );

    expect(screen.getByRole("alert")).toBeInTheDocument();
    expect(screen.getByText("Cuenta suspendida")).toBeInTheDocument();
    expect(screen.getByText(/regularice el pago con su proveedor/)).toBeInTheDocument();
  });

  it("con una suspensión manual explica que la lectura sigue sin hablar de pagos", () => {
    render(<TenantSuspendedBanner company={company({ state: "SUSPENDED", reason: "MANUAL", appliedAt: null })} />);

    expect(screen.getByText("Cuenta suspendida")).toBeInTheDocument();
    expect(screen.getByText(/consultar y exportar/)).toBeInTheDocument();
    expect(screen.queryByText(/pago/)).not.toBeInTheDocument();
  });
});
