import { Navigate, Outlet, useLocation } from "react-router-dom";
import { LoadingState } from "../ui/components/states";
import { t } from "../../lib/i18n";
import { useAuth } from "./AuthContext";

/** Guarda de autenticación a nivel de ruta. A quien no ha entrado se le manda a `/login`; el
 * backend sigue siendo la autoridad real en cada peticion pase lo que pase — esto solo evita
 * el parpadeo de pantallas que van a fallar con 401. */
export function ProtectedRoute() {
  const { status } = useAuth();
  const location = useLocation();

  if (status === "loading") {
    return <LoadingState label={t("Verificando tu sesión...")} minHeight="100vh" />;
  }

  if (status === "signedOut") {
    return <Navigate to="/login" replace state={{ from: location }} />;
  }

  return <Outlet />;
}
