import { Outlet } from "react-router-dom";
import { EmptyState, ErrorState, LoadingState } from "../ui/components/states";
import { t } from "../../lib/i18n";
import { useCompany } from "./CompanyContext";

/**
 * Guarda de ruta para las pantallas con ambito de empresa. Pinta el `Outlet` solo una vez hay
 * empresa elegida — una comodidad de UX, no una frontera de seguridad. La cabecera de empresa
 * que acabe mandando una pantalla la sigue validando por su cuenta el `CompanyScopeFilter` del
 * backend en cada peticion.
 */
export function RequireCompany() {
  const { status, companies, selected, errorMessage, refetch } = useCompany();

  if (status === "idle" || status === "loading") {
    return <LoadingState label={t("Cargando tus compañías...")} />;
  }

  if (status === "error") {
    return <ErrorState message={errorMessage ?? t("No se pudieron cargar tus compañías.")} onRetry={refetch} />;
  }

  if (companies.length === 0 || !selected) {
    return (
      <EmptyState
        title={t("Sin acceso a compañías")}
        message={t("Tu cuenta no tiene una membresía activa en ninguna compañía. Contacta a un administrador.")}
      />
    );
  }

  return <Outlet />;
}
