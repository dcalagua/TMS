import { useState } from "react";
import { Route, Routes } from "react-router-dom";
import { QueryClientProvider } from "@tanstack/react-query";
import { createQueryClient } from "./app/queryClient";
import { UiHost } from "./lib/ui";
import { AuthProvider } from "./shared/auth/AuthContext";
import { ProtectedRoute } from "./shared/auth/ProtectedRoute";
import { CompanyProvider } from "./shared/company/CompanyContext";
import { RequireCompany } from "./shared/company/RequireCompany";
import { AppLayout } from "./shared/ui/AppLayout";
import { AccountPage } from "./pages/AccountPage";
import { DashboardPage } from "./pages/DashboardPage";
import { LoginPage } from "./pages/LoginPage";
import { NotFoundPage } from "./pages/NotFoundPage";
import {
  AuditPage, CarriersPage, CompanySettingsPage, ControlTowerPage, DestinationsPage, DriversPage,
  FrequenciesPage, IntegrationsPage, LocationsPage, OrdersPage, OriginsPage, PlanningBoardPage,
  AppointmentsPage,
  SettlementPage,
  PlanningRunsPage, RateCardsPage, ReportsPage, RoutesPage, TripWorkspacePage, TripsPage,
  UsersPage, VehicleTypesPage, VehiclesPage, ZonesPage,
} from "./app/lazyRoutes";

/**
 * La aplicación y su tabla de rutas.
 *
 * Los proveedores se anidan de dentro afuera por dependencia: el contexto de empresa lee el
 * estado de autenticación y lanza queries, así que va dentro de los dos. El tema queda por
 * fuera de todo, en `main.tsx`, porque tiene que aplicarse igual a la pantalla de login y a un
 * error boundary que a una sesión abierta.
 *
 * `UiHost` se monta una sola vez y por encima de las rutas: los toasts y las confirmaciones son
 * del producto, no de la pantalla que los pidió, y tienen que sobrevivir a una navegación.
 */
export default function App() {
  const [queryClient] = useState(createQueryClient);

  return (
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <CompanyProvider>
          <UiHost />
          <Routes>
            <Route path="/login" element={<LoginPage />} />

            <Route element={<ProtectedRoute />}>
              <Route element={<AppLayout />}>
                <Route index element={<DashboardPage />} />

                {/* Fuera de RequireCompany a propósito: un perfil es de la persona, no de la
                    empresa a la que la sesión resulte estar apuntando. */}
                <Route path="account" element={<AccountPage />} />

                <Route element={<RequireCompany />}>
                  {/* Dentro de RequireCompany, a diferencia del dashboard: cada número de estas
                      dos pantallas es "de esta empresa", y sin una no hay nada que enseñar. */}
                  <Route path="control-tower" element={<ControlTowerPage />} />
                  <Route path="reporting" element={<ReportsPage />} />

                  <Route path="masters/locations" element={<LocationsPage />} />
                  <Route path="masters/origins" element={<OriginsPage />} />
                  <Route path="masters/destinations" element={<DestinationsPage />} />
                  <Route path="masters/zones" element={<ZonesPage />} />
                  <Route path="masters/frequencies" element={<FrequenciesPage />} />
                  <Route path="masters/routes" element={<RoutesPage />} />

                  <Route path="fleet/carriers" element={<CarriersPage />} />
                  <Route path="fleet/vehicle-types" element={<VehicleTypesPage />} />
                  <Route path="fleet/vehicles" element={<VehiclesPage />} />
                  <Route path="fleet/drivers" element={<DriversPage />} />

                  <Route path="orders" element={<OrdersPage />} />

                  <Route path="planning" element={<PlanningRunsPage />} />
                  <Route path="planning/:runId" element={<PlanningBoardPage />} />

                  <Route path="rates/rate-cards" element={<RateCardsPage />} />

                  <Route path="trips" element={<TripsPage />} />
                  <Route path="appointments" element={<AppointmentsPage />} />
                  <Route path="settlement" element={<SettlementPage />} />
                  {/* Una ruta completa y no un drawer sobre la lista: un despachador se queda
                      dentro de un viaje durante minutos, y `/trips/{id}` es lo que se pega en un
                      chat cuando alguien pregunta dónde está un camión. */}
                  <Route path="trips/:tripId" element={<TripWorkspacePage />} />

                  {/* Configuración. Dentro de RequireCompany como cualquier otra pantalla de
                      administración: las dos van de *esta* empresa, y los endpoints que hay
                      detrás rechazan una petición sin X-Company-Id. */}
                  <Route path="settings/company" element={<CompanySettingsPage />} />
                  <Route path="settings/users" element={<UsersPage />} />
                  <Route path="settings/integrations" element={<IntegrationsPage />} />

                  {/* El histórico de auditoría, al lado de la administración de usuarios que en
                      buena parte explica. Solo lectura y tras su propio permiso: quién hizo qué
                      no es algo que todo planificador necesite. */}
                  <Route path="security/audit" element={<AuditPage />} />
                </Route>

                <Route path="*" element={<NotFoundPage />} />
              </Route>
            </Route>
          </Routes>
        </CompanyProvider>
      </AuthProvider>
    </QueryClientProvider>
  );
}
