import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

/**
 * D5 (QAS 2026-10-22): «Mi cuenta» decía «Nombre y contraseña» y no dejaba cambiar la contraseña.
 * Ahora sí: se comprueba la actual volviendo a entrar con ella y recién entonces se cambia, con
 * el mismo `updatePassword` que usa la recuperación.
 */

const auth = vi.hoisted(() => ({
  signIn: vi.fn(),
  updatePassword: vi.fn(),
}));
const notifySuccess = vi.hoisted(() => vi.fn());

vi.mock("../../shared/auth/AuthContext", () => ({ useAuth: () => auth }));
vi.mock("../../shared/auth/supabaseClient", () => ({ getRememberSession: () => false }));
vi.mock("../../lib/ui", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../../lib/ui")>()),
  notifySuccess,
}));

import { PasswordCard } from "./PasswordCard";

beforeEach(() => {
  auth.signIn.mockReset();
  auth.updatePassword.mockReset();
  notifySuccess.mockReset();
});

async function fill(current: string, next: string, confirm = next) {
  await userEvent.type(screen.getByLabelText(/Contraseña actual/), current);
  await userEvent.type(screen.getByLabelText(/Nueva contraseña/), next);
  await userEvent.type(screen.getByLabelText(/Confirma la contraseña/), confirm);
  await userEvent.click(screen.getByRole("button", { name: "Cambiar contraseña" }));
}

describe("cambiar la contraseña desde «Mi cuenta»", () => {
  it("comprueba la actual, guarda la nueva y conserva el «recordarme» de la sesión", async () => {
    auth.signIn.mockResolvedValue({ ok: true });
    auth.updatePassword.mockResolvedValue({ ok: true });
    render(<PasswordCard email="ana@example.invalid" />);

    await fill("vieja-123", "nueva-4567");

    await waitFor(() => expect(auth.updatePassword).toHaveBeenCalledWith("nueva-4567"));
    expect(auth.signIn).toHaveBeenCalledWith("ana@example.invalid", "vieja-123", false);
    expect(notifySuccess).toHaveBeenCalledWith("Contraseña actualizada", expect.any(String));
    expect(screen.getByLabelText(/Contraseña actual/)).toHaveValue("");
  });

  it("con la actual equivocada no cambia nada y lo dice en el campo", async () => {
    auth.signIn.mockResolvedValue({ ok: false, message: "Invalid login credentials" });
    render(<PasswordCard email="ana@example.invalid" />);

    await fill("equivocada", "nueva-4567");

    expect(await screen.findByText("La contraseña actual no es correcta.")).toBeInTheDocument();
    expect(auth.updatePassword).not.toHaveBeenCalled();
  });

  it("valida en el navegador: longitud, confirmación y que la nueva sea distinta", async () => {
    render(<PasswordCard email="ana@example.invalid" />);

    await fill("vieja-123", "corta", "otra");
    // Una vez como indicación de la tarjeta y otra como error del campo.
    expect(await screen.findAllByText("Usa al menos 8 caracteres.")).toHaveLength(2);
    expect(screen.getByText("Las contraseñas no coinciden.")).toBeInTheDocument();
    expect(auth.signIn).not.toHaveBeenCalled();
  });

  it("muestra el motivo si Supabase rechaza la nueva", async () => {
    auth.signIn.mockResolvedValue({ ok: true });
    auth.updatePassword.mockResolvedValue({ ok: false, message: "Password should be at least 10 characters." });
    render(<PasswordCard email="ana@example.invalid" />);

    await fill("vieja-123", "nueva-4567");

    expect(await screen.findByText("Password should be at least 10 characters.")).toBeInTheDocument();
    expect(notifySuccess).not.toHaveBeenCalled();
  });
});
