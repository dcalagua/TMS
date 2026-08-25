import { useQuery } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { Alert, Box, Typography } from "@mui/material";
import { alpha } from "@mui/material/styles";
import {
  SpeedRounded, InboxRounded, HourglassBottomRounded, CheckCircleRounded, LocalShippingRounded,
} from "@mui/icons-material";
import { fetchOrders } from "../shared/api/ordersApi";
import { fetchSystemInfo } from "../shared/api/systemApi";
import { fetchVehicles } from "../shared/api/vehiclesApi";
import { useAuth } from "../shared/auth/AuthContext";
import { useCompany } from "../shared/company/CompanyContext";
import { DEFAULT_TINT, ICON_TINTS, NAV_SECTIONS } from "../shared/ui/navConfig";
import { AppCard, DetailGrid, DetailItem, KpiCard, PageHeader, StatusChip } from "../shared/ui/components";
import { t } from "../lib/i18n";
import { fmtQuantity } from "../lib/locale";

/**
 * Pantalla de aterrizaje: quién eres, en qué empresa estás operando y qué módulos te concede
 * realmente tu membresía en ella.
 *
 * Los contadores son reales. Todavía no hay endpoint de KPIs, así que cada uno es el
 * `totalElements` de una consulta de lista que el propio operador podría lanzar — pedida con
 * `size: 1`, porque la página de filas se tira y solo se guarda el conteo del servidor. Eso
 * mantiene honesto al dashboard: cada cifra de aquí se alcanza pulsándola, y ninguna se calcula
 * en el navegador a partir de una página parcial.
 *
 * Van ordenados por lo que un operador puede accionar. "Por planificar" va primero porque es la
 * cola que atasca el día si nadie la trabaja; los conteos de maestros irían al final, porque
 * cambian cada mes y no cada hora.
 */
export function DashboardPage() {
  const { user } = useAuth();
  const { profile, selected, hasCapability, status: companyStatus } = useCompany();

  const backend = useQuery({
    queryKey: ["system", "info"],
    queryFn: ({ signal }) => fetchSystemInfo(signal),
    retry: false,
  });

  const companyId = selected?.id ?? "";
  const canSeeOrders = hasCapability("ORDERS_VIEW");
  const canSeeFleet = hasCapability("FLEET_VIEW");

  /** Un conteo, pedido tan barato como la API lo permite. */
  function useCount(key: string, enabled: boolean, run: (signal: AbortSignal) => Promise<{ totalElements: number }>) {
    return useQuery({
      queryKey: ["kpi", key, companyId],
      queryFn: ({ signal }) => run(signal),
      enabled: Boolean(companyId) && enabled && companyStatus === "ready",
      select: (page) => page.totalElements,
      staleTime: 30_000,
    });
  }

  const ordersReady = useCount("orders-ready", canSeeOrders, (signal) =>
    fetchOrders({ companyId, size: 1, status: "READY_FOR_PLANNING", signal }));
  const ordersNotReady = useCount("orders-not-ready", canSeeOrders, (signal) =>
    fetchOrders({ companyId, size: 1, status: "NOT_READY", signal }));
  const ordersPlanned = useCount("orders-planned", canSeeOrders, (signal) =>
    fetchOrders({ companyId, size: 1, status: "PLANNED", signal }));
  const activeVehicles = useCount("vehicles-active", canSeeFleet, (signal) =>
    fetchVehicles({ companyId, size: 1, active: true, signal }));

  const count = (query: { data?: number }) => (query.data === undefined ? "-" : fmtQuantity(query.data));

  // El acceso rápido replica el filtrado por capability de la barra lateral: solo puede ofrecer
  // lo que concedió `/me`.
  const availableSections = NAV_SECTIONS
    .filter((section) => !section.capability || companyStatus !== "ready" || hasCapability(section.capability))
    .map((section) => ({
      ...section,
      items: section.items.filter((item) => !item.capability || companyStatus !== "ready" || hasCapability(item.capability)),
    }))
    .filter((section) => section.items.length > 0);

  const apiBadge = backend.isError
    ? <StatusChip label={t("API no disponible")} tone="overdue" />
    : backend.isSuccess
      ? <StatusChip label={t("API disponible")} tone="done" />
      : <StatusChip label={t("Comprobando API")} tone="neutral" />;

  return (
    <>
      <PageHeader
        icon={<SpeedRounded />}
        title={t("Hola, {{name}}", { name: profile?.fullName || user?.email || "" })}
        subtitle={selected
          ? t("Estás operando en {{company}}.", { company: selected.name })
          : t("Todavía no hay una empresa seleccionada.")}
        actions={apiBadge}
      />

      {backend.isError && (
        <Alert severity="warning" sx={{ mb: 3 }}>
          {t("No se pudo contactar con el backend. Revisa que esté levantado y que VITE_API_BASE_URL apunte a él.")}
        </Alert>
      )}

      {/* Ordenados por urgencia, no por módulo. La cola que atasca el día va primero, y es la
          única que toma un color: colorear los cuatro no diría nada sobre cuál necesita atención. */}
      {/* El número de columnas se queda fijo —así ninguna fila deja una tarjeta huérfana— y lo
          que se acota es el ancho de la REJILLA: cuatro pistas de 340 px con sus separaciones.
          Repartiendo el ancho entero, con la lateral plegada cada tarjeta pasaba de 440 px, y
          una tarjeta que solo lleva un rótulo y una cifra no sabe qué hacer con ese espacio. */}
      <Box sx={{
        display: "grid", gap: 2, mb: 3, maxWidth: 4 * 340 + 3 * 16,
        gridTemplateColumns: { xs: "1fr", sm: "repeat(2, minmax(0, 1fr))", lg: "repeat(4, minmax(0, 1fr))" },
      }}>
        {canSeeOrders && (
          <>
            <KpiCard
              icon={<InboxRounded />}
              color={(ordersReady.data ?? 0) > 0 ? "warning.main" : "text.secondary"}
              title={t("Pedidos por planificar")}
              sub={t("Listos para entrar en un viaje")}
              value={count(ordersReady)}
              loading={ordersReady.isPending}
            />
            <KpiCard
              icon={<HourglassBottomRounded />}
              color="text.secondary"
              title={t("Pedidos sin liberar")}
              sub={t("Todavía no se pueden planificar")}
              value={count(ordersNotReady)}
              loading={ordersNotReady.isPending}
            />
            <KpiCard
              icon={<CheckCircleRounded />}
              color="success.main"
              title={t("Pedidos planificados")}
              sub={t("Ya asignados a un viaje")}
              value={count(ordersPlanned)}
              loading={ordersPlanned.isPending}
            />
          </>
        )}
        {canSeeFleet && (
          <KpiCard
            icon={<LocalShippingRounded />}
            color="info.main"
            title={t("Vehículos activos")}
            sub={t("Flota disponible para planificar")}
            value={count(activeVehicles)}
            loading={activeVehicles.isPending}
          />
        )}
      </Box>

      <Box sx={{ mb: 3 }}>
        <AppCard title={t("Acceso rápido")}>
          {availableSections.length === 0 ? (
            <Typography variant="body2" color="text.secondary">
              {t("Tu membresía en esta compañía no habilita ningún módulo todavía.")}
            </Typography>
          ) : (
            <Box sx={{ display: "grid", gap: 3 }}>
              {availableSections.map((section) => (
                <Box key={section.title}>
                  <Typography variant="overline" color="text.secondary" sx={{ display: "block", mb: 1 }}>
                    {t(section.title)}
                  </Typography>
                  {/* Mismo criterio que las tarjetas de arriba: seis columnas, pero la rejilla
                      no pasa de seis pistas de 190 px. Repartiendo el ancho entero, una baldosa
                      con un icono de 38 px y una palabra acababa midiendo casi 300. */}
                  <Box sx={{
                    display: "grid", gap: 1.25, maxWidth: 6 * 190 + 5 * 10,
                    gridTemplateColumns: { xs: "repeat(2, minmax(0,1fr))", sm: "repeat(3, minmax(0,1fr))", md: "repeat(6, minmax(0,1fr))" },
                  }}>
                    {section.items.map((item) => {
                      const tint = ICON_TINTS[item.to] ?? DEFAULT_TINT;
                      return (
                        <Box
                          key={item.to}
                          component={Link}
                          to={item.to}
                          sx={{
                            textDecoration: "none", color: "text.primary",
                            display: "flex", flexDirection: "column", alignItems: "center", gap: 1,
                            p: 1.5, borderRadius: 2.5, border: "1px solid", borderColor: "divider",
                            transition: "transform .15s, border-color .15s, box-shadow .15s",
                            "&:hover": {
                              transform: "translateY(-2px)",
                              borderColor: alpha(tint, 0.55),
                              boxShadow: `0 10px 22px ${alpha(tint, 0.16)}`,
                            },
                          }}
                        >
                          {/* La baldosa lleva el acento del propio módulo, así que el ojo
                              encuentra "Vehículos" por su color antes de haber leído la palabra. */}
                          <Box aria-hidden sx={{
                            width: 38, height: 38, borderRadius: 2, display: "grid", placeItems: "center",
                            bgcolor: alpha(tint, 0.18), color: tint, "& svg": { fontSize: 21 },
                          }}>
                            {item.icon}
                          </Box>
                          <Typography variant="caption" sx={{ fontWeight: 700, textAlign: "center", lineHeight: 1.25 }}>
                            {t(item.label)}
                          </Typography>
                        </Box>
                      );
                    })}
                  </Box>
                </Box>
              ))}
            </Box>
          )}
        </AppCard>
      </Box>

      {/* Los datos de sesión son contexto, no contenido: una franja discreta bajo el trabajo en
          lugar de un panel compitiendo con él. */}
      <AppCard title={t("Tu sesión")}>
        <DetailGrid columns={4}>
          <DetailItem label={t("Correo electrónico")} value={profile?.email ?? user?.email ?? "-"} />
          <DetailItem label={t("Compañía")} value={selected?.name ?? "-"} />
          <DetailItem label={t("Organización")} value={selected?.organization.name ?? "-"} />
          <DetailItem label={t("Zona horaria")} value={selected?.timeZone ?? "-"} />
        </DetailGrid>
      </AppCard>
    </>
  );
}
