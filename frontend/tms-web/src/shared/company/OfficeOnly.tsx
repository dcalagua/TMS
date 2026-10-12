import { Navigate, Outlet } from "react-router-dom";
import { LoadingState } from "../ui/components/states";
import { isCarrierScoped } from "../api/meApi";
import { t } from "../../lib/i18n";
import { useCompany } from "./CompanyContext";

/**
 * La puerta de la aplicación de oficina (ADR-018 §7).
 *
 * Quien opera la empresa elegida como transportista va a `/portal` y nunca ve el menú de oficina:
 * ni el inicio, ni la cuenta, ni un 404 con la barra lateral. Mientras `/me` no ha respondido no
 * se sabe de qué lado está, así que se espera en pantalla completa en lugar de pintar un armazón
 * que a lo mejor no le corresponde.
 *
 * Es orientación, no seguridad: los roles `CARRIER` no tienen ningún permiso de oficina, así que
 * cada endpoint de detrás respondería 403 de todos modos.
 */
export function OfficeOnly() {
  const { status, selected } = useCompany();

  if (status === "idle" || status === "loading") {
    return <LoadingState label={t("Cargando tus compañías...")} minHeight="100vh" />;
  }

  if (status === "ready" && isCarrierScoped(selected)) {
    return <Navigate to="/portal" replace />;
  }

  return <Outlet />;
}
