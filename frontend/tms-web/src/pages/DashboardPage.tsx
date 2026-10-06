import type { ReactNode } from "react";
import { useQuery } from "@tanstack/react-query";
import { Link as RouterLink } from "react-router-dom";
import { Alert, Box, Link, Paper, Skeleton, Typography } from "@mui/material";
import { alpha, useTheme } from "@mui/material/styles";
import {
  ApartmentRounded, ArrowForwardRounded, BlockRounded, CheckCircleRounded, ChevronRightRounded,
  CorporateFareRounded, DirectionsRunRounded, DoneAllRounded, HourglassBottomRounded, InboxRounded,
  LocalShippingRounded, MailOutlineRounded, PendingActionsRounded, PriorityHighRounded,
  ReportProblemRounded, ScheduleRounded,
} from "@mui/icons-material";
import { fetchControlTower } from "../shared/api/controlTowerApi";
import { fetchOrders } from "../shared/api/ordersApi";
import { fetchSystemInfo } from "../shared/api/systemApi";
import { fetchVehicles } from "../shared/api/vehiclesApi";
import { useAuth } from "../shared/auth/AuthContext";
import { useCompany } from "../shared/company/CompanyContext";
import { StatusChip } from "../shared/ui/components";
import { getLang, t } from "../lib/i18n";
import { fmtDateTime, fmtPercent, fmtQuantity, localeFor } from "../lib/locale";
import { R, T } from "../theme";

/** Mismo intervalo que la Torre de control: los dos leen la misma consulta y la misma caché. */
const TOWER_POLL_MS = 60_000;

type Tone = "warning" | "info" | "success" | "error" | "neutral";

/**
 * Pantalla de aterrizaje: qué hay que hacer hoy, no un índice de módulos (eso ya es el menú).
 *
 * Diseño: `design/tms.pen` → "Pantalla · Inicio v2".
 *
 * - "Flujo de pedidos" pone en orden los tres conteos de pedidos que antes eran KPIs sueltos, con
 *   el atajo a la pantalla donde se mueve cada etapa.
 * - "Hoy en la operación" y "Requiere atención" leen el resumen de la Torre de control con su
 *   misma clave de caché, así que abrir la torre después no repite la consulta. Solo aparecen con
 *   la capability de monitorización, igual que la torre en el menú.
 *
 * Los conteos siguen siendo honestos: cada uno es el `totalElements` de una lista que el operador
 * puede abrir, o una cifra que el backend ya calculó. Nada se deriva de una página parcial.
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
  const ready = Boolean(companyId) && companyStatus === "ready";
  const canSeeOrders = hasCapability("ORDERS_VIEW");
  const canSeeFleet = hasCapability("FLEET_VIEW");
  const canMonitor = hasCapability("TRANSPORT_MONITOR_VIEW");

  /** Un conteo, pedido tan barato como la API lo permite. */
  function useCount(key: string, enabled: boolean, run: (signal: AbortSignal) => Promise<{ totalElements: number }>) {
    return useQuery({
      queryKey: ["kpi", key, companyId],
      queryFn: ({ signal }) => run(signal),
      enabled: ready && enabled,
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

  // Misma clave que ControlTowerPage con el día por defecto ("" = hoy para el backend).
  const tower = useQuery({
    queryKey: ["control-tower", companyId, ""],
    queryFn: ({ signal }) => fetchControlTower({ companyId, date: undefined, signal }),
    enabled: ready && canMonitor,
    refetchInterval: TOWER_POLL_MS,
  });

  const apiBadge = backend.isError
    ? <StatusChip label={t("API no disponible")} tone="overdue" />
    : backend.isSuccess
      ? <StatusChip label={t("API disponible")} tone="done" />
      : <StatusChip label={t("Comprobando API")} tone="neutral" />;

  const today = new Intl.DateTimeFormat(localeFor(getLang()), {
    weekday: "long", day: "numeric", month: "long", timeZone: selected?.timeZone,
  }).format(new Date());

  const session: Array<[ReactNode, string]> = [
    [<ApartmentRounded key="c" />, selected?.name ?? "-"],
    [<CorporateFareRounded key="o" />, selected?.organization.name ?? "-"],
    [<ScheduleRounded key="z" />, selected?.timeZone ?? "-"],
    [<MailOutlineRounded key="m" />, profile?.email ?? user?.email ?? "-"],
  ];

  return (
    <>
      {/* Cabecera: la fecha del día, el saludo y la sesión en una línea. Los datos de sesión son
          contexto de lectura, no merecen una tarjeta propia. */}
      <Box sx={{ display: "flex", alignItems: "flex-end", gap: 2, mb: 3, flexWrap: "wrap" }}>
        <Box sx={{ flex: 1, minWidth: 0 }}>
          <Typography sx={{
            fontSize: T.label, fontWeight: 800, letterSpacing: ".14em", textTransform: "uppercase",
            color: "accentDeep", mb: 0.75,
          }}>
            {today}
          </Typography>
          <Typography component="h1" sx={{ fontSize: 26, fontWeight: 800, letterSpacing: "-0.03em", lineHeight: 1.15 }}>
            {t("Hola, {{name}}", { name: profile?.fullName || user?.email || "" })}
          </Typography>
          {selected ? (
            <Box sx={{ display: "flex", flexWrap: "wrap", columnGap: 2, rowGap: 0.5, mt: 1 }}>
              {session.map(([icon, value]) => (
                <Box key={value} sx={{
                  display: "flex", alignItems: "center", gap: 0.6, color: "text.secondary",
                  fontSize: T.bodyStrong - 1, "& svg": { fontSize: 15 },
                }}>
                  {icon}{value}
                </Box>
              ))}
            </Box>
          ) : (
            <Typography sx={{ mt: 1, fontSize: T.body, color: "text.secondary" }}>
              {t("Todavía no hay una empresa seleccionada.")}
            </Typography>
          )}
        </Box>
        {apiBadge}
      </Box>

      {backend.isError && (
        <Alert severity="warning" sx={{ mb: 3 }}>
          {t("No se pudo contactar con el backend. Revisa que esté levantado y que VITE_API_BASE_URL apunte a él.")}
        </Alert>
      )}

      {(canSeeOrders || canSeeFleet) && (
        <Box sx={{
          display: "grid", gap: 2, mb: 2,
          gridTemplateColumns: { xs: "1fr", lg: canSeeOrders && canSeeFleet ? "minmax(0,1fr) 260px" : "1fr" },
        }}>
          {canSeeOrders && (
            <OrderFlowCard
              notReady={ordersNotReady.data}
              ready={ordersReady.data}
              planned={ordersPlanned.data}
              loading={ordersReady.isPending || ordersNotReady.isPending || ordersPlanned.isPending}
              canSchedule={hasCapability("ORDERS_VIEW")}
              canPlan={hasCapability("PLANNING_VIEW")}
              canSeeTrips={hasCapability("TRIPS_VIEW")}
            />
          )}
          {canSeeFleet && (
            <Card>
              <Box sx={{ display: "flex", alignItems: "center", gap: 1 }}>
                <IconBox tone="info"><LocalShippingRounded /></IconBox>
                <Typography sx={{ fontSize: T.body, fontWeight: 700, color: "text.secondary" }}>
                  {t("Vehículos activos")}
                </Typography>
              </Box>
              <Box sx={{ flex: 1, display: "flex", flexDirection: "column", justifyContent: "center", py: 1 }}>
                <BigValue size={44} loading={activeVehicles.isPending} value={activeVehicles.data} />
                <Typography sx={{ fontSize: T.body - 0.5, color: "text.secondary", mt: 0.5 }}>
                  {t("Flota disponible para planificar")}
                </Typography>
              </Box>
              <GoLink to="/fleet/vehicles">{t("Ver vehículos")}</GoLink>
            </Card>
          )}
        </Box>
      )}

      {canMonitor && (
        <Box sx={{ display: "grid", gap: 2, gridTemplateColumns: { xs: "1fr", lg: "minmax(0,1fr) 440px" } }}>
          <TodayCard
            loading={tower.isPending}
            error={tower.isError}
            generatedAt={tower.data?.generatedAt}
            inTransit={tower.data?.summary.tripsInTransit}
            scheduled={tower.data?.summary.tripsScheduled}
            completed={tower.data?.summary.tripsCompleted}
          />
          <AttentionCard loading={tower.isPending} error={tower.isError} summary={tower.data?.summary} />
        </Box>
      )}
    </>
  );
}

/* ─────────────────────────────── piezas ─────────────────────────────── */

function toneColor(tone: Tone): string {
  return tone === "neutral" ? "text.secondary" : `${tone}.main`;
}

function Card({ children, sx }: { children: ReactNode; sx?: object }) {
  return (
    <Paper variant="outlined" sx={{
      p: 2.5, borderRadius: `${R.xl - 2}px`, display: "flex", flexDirection: "column", gap: 2, ...sx,
    }}>
      {children}
    </Paper>
  );
}

function IconBox({ tone, size = 34, children }: { tone: Tone; size?: number; children: ReactNode }) {
  const theme = useTheme();
  const main = tone === "neutral" ? theme.palette.text.secondary : theme.palette[tone].main;
  return (
    <Box aria-hidden sx={{
      width: size, height: size, flexShrink: 0, borderRadius: "10px", display: "grid", placeItems: "center",
      bgcolor: alpha(main, theme.palette.mode === "dark" ? 0.2 : 0.12), color: main,
      "& svg": { fontSize: Math.round(size * 0.53) },
    }}>
      {children}
    </Box>
  );
}

function GoLink({ to, children }: { to: string; children: ReactNode }) {
  return (
    <Link
      component={RouterLink}
      to={to}
      underline="hover"
      sx={{
        display: "inline-flex", alignItems: "center", gap: 0.5, alignSelf: "flex-start",
        fontSize: T.bodyStrong - 1, fontWeight: 700, color: "accentDeep",
      }}
    >
      {children}
      <ArrowForwardRounded sx={{ fontSize: 15 }} aria-hidden />
    </Link>
  );
}

function BigValue({ value, loading, size = 32 }: { value?: number; loading: boolean; size?: number }) {
  if (loading) return <Skeleton variant="text" width={64} sx={{ fontSize: size }} />;
  return (
    <Typography sx={{
      fontSize: size, fontWeight: 800, letterSpacing: "-0.03em", lineHeight: 1,
      fontVariantNumeric: "tabular-nums",
    }}>
      {value === undefined ? "-" : fmtQuantity(value)}
    </Typography>
  );
}

function CardHeader({ title, subtitle, action }: { title: string; subtitle?: string; action?: ReactNode }) {
  return (
    <Box sx={{ display: "flex", alignItems: "flex-start", gap: 2 }}>
      <Box sx={{ flex: 1, minWidth: 0 }}>
        <Typography component="h2" sx={{ fontSize: 16, fontWeight: 800 }}>{title}</Typography>
        {subtitle && <Typography sx={{ fontSize: T.body - 0.5, color: "text.secondary" }}>{subtitle}</Typography>}
      </Box>
      {action}
    </Box>
  );
}

/** Barra segmentada: cada tramo mide lo que su cifra pesa en el total. */
function SegmentBar({ parts, height = 12 }: { parts: Array<{ value: number; color: string; label: string }>; height?: number }) {
  const total = parts.reduce((sum, part) => sum + part.value, 0);
  return (
    <Box
      role="img"
      aria-label={parts.map((part) => `${part.label}: ${fmtQuantity(part.value)}`).join(", ")}
      sx={{ display: "flex", gap: "3px", height, borderRadius: "4px", overflow: "hidden", bgcolor: "action.hover" }}
    >
      {total > 0 && parts.filter((part) => part.value > 0).map((part) => (
        <Box key={part.label} sx={{ flexGrow: part.value, flexBasis: 0, bgcolor: part.color, borderRadius: "4px" }} />
      ))}
    </Box>
  );
}

function OrderFlowCard(props: {
  notReady?: number; ready?: number; planned?: number; loading: boolean;
  canSchedule: boolean; canPlan: boolean; canSeeTrips: boolean;
}) {
  const theme = useTheme();
  const stages: Array<{
    icon: ReactNode; label: string; help: string; value?: number; tone: Tone; color: string;
    link?: { to: string; label: string }; highlight?: boolean;
  }> = [
    {
      icon: <HourglassBottomRounded />, label: t("Pedidos sin liberar"), help: t("Todavía no se pueden planificar"),
      value: props.notReady, tone: "neutral", color: theme.palette.text.secondary,
      link: props.canSchedule ? { to: "/scheduling", label: t("Revisar en Programación") } : undefined,
    },
    {
      icon: <InboxRounded />, label: t("Pedidos por planificar"), help: t("Listos para entrar en un viaje"),
      value: props.ready, tone: "warning", color: theme.palette.warning.main,
      link: props.canPlan ? { to: "/planning", label: t("Abrir Planificación") } : undefined,
      highlight: (props.ready ?? 0) > 0,
    },
    {
      icon: <CheckCircleRounded />, label: t("Pedidos planificados"), help: t("Ya asignados a un viaje"),
      value: props.planned, tone: "success", color: theme.palette.success.main,
      link: props.canSeeTrips ? { to: "/trips", label: t("Ver viajes") } : undefined,
    },
  ];

  return (
    <Card>
      <CardHeader
        title={t("Flujo de pedidos")}
        subtitle={t("Dónde está cada pedido antes de salir a ruta.")}
        action={<GoLink to="/orders">{t("Ver pedidos")}</GoLink>}
      />
      {props.loading
        ? <Skeleton variant="rounded" height={12} />
        : <SegmentBar parts={stages.map((stage) => ({ value: stage.value ?? 0, color: stage.color, label: stage.label }))} />}
      <Box sx={{ display: "grid", gap: 1.5, gridTemplateColumns: { xs: "1fr", sm: "repeat(3, minmax(0,1fr))" } }}>
        {stages.map((stage) => (
          <Box key={stage.label} sx={{
            p: 1.75, borderRadius: `${R.md}px`, display: "flex", flexDirection: "column", gap: 1,
            bgcolor: stage.highlight ? alpha(theme.palette.warning.main, theme.palette.mode === "dark" ? 0.16 : 0.1) : "background.default",
          }}>
            <Box sx={{ display: "flex", alignItems: "center", gap: 1, color: toneColor(stage.tone), "& svg": { fontSize: 17 } }}>
              {stage.icon}
              <Typography sx={{
                fontSize: T.body - 0.5, fontWeight: 700,
                color: stage.highlight ? "warning.dark" : "text.secondary",
              }}>
                {stage.label}
              </Typography>
            </Box>
            <BigValue value={stage.value} loading={props.loading} />
            <Typography sx={{ fontSize: T.label + 1, color: "text.secondary" }}>{stage.help}</Typography>
            {stage.link && <GoLink to={stage.link.to}>{stage.link.label}</GoLink>}
          </Box>
        ))}
      </Box>
    </Card>
  );
}

function TodayCard(props: {
  loading: boolean; error: boolean; generatedAt?: string;
  inTransit?: number; scheduled?: number; completed?: number;
}) {
  const theme = useTheme();
  const inTransit = props.inTransit ?? 0;
  const scheduled = props.scheduled ?? 0;
  const completed = props.completed ?? 0;
  const total = inTransit + scheduled + completed;

  const parts = [
    { label: t("Completados"), value: completed, color: theme.palette.success.main },
    { label: t("En tránsito"), value: inTransit, color: theme.palette.warning.main },
    { label: t("Programados"), value: scheduled, color: alpha(theme.palette.info.main, 0.35) },
  ];

  return (
    <Card>
      <CardHeader
        title={t("Hoy en la operación")}
        subtitle={props.generatedAt ? t("Al {{time}}", { time: fmtDateTime(props.generatedAt) }) : undefined}
        action={<GoLink to="/control-tower">{t("Abrir torre de control")}</GoLink>}
      />
      {props.error ? (
        <Alert severity="warning">{t("No se pudo cargar el resumen del día.")}</Alert>
      ) : (
        <>
          <Box>
            <Box sx={{ display: "flex", justifyContent: "space-between", mb: 1 }}>
              <Typography sx={{ fontSize: T.bodyStrong, fontWeight: 700 }}>
                {props.loading
                  ? <Skeleton width={180} />
                  : t("{{done}} de {{total}} envíos completados", { done: fmtQuantity(completed), total: fmtQuantity(total) })}
              </Typography>
              {!props.loading && total > 0 && (
                <Typography sx={{ fontSize: T.bodyStrong, fontWeight: 800, color: "success.dark" }}>
                  {fmtPercent((completed / total) * 100)}
                </Typography>
              )}
            </Box>
            {props.loading ? <Skeleton variant="rounded" height={10} /> : <SegmentBar parts={parts} height={10} />}
            <Box sx={{ display: "flex", gap: 2, mt: 1, flexWrap: "wrap" }}>
              {parts.map((part) => (
                <Box key={part.label} sx={{ display: "flex", alignItems: "center", gap: 0.75 }}>
                  <Box aria-hidden sx={{ width: 10, height: 10, borderRadius: "2px", bgcolor: part.color }} />
                  <Typography sx={{ fontSize: T.label + 1, color: "text.secondary" }}>{part.label}</Typography>
                </Box>
              ))}
            </Box>
          </Box>
          <Box sx={{ display: "grid", gridTemplateColumns: "repeat(3, minmax(0,1fr))", gap: 1.5, flex: 1 }}>
            {[
              { icon: <DirectionsRunRounded />, label: t("En tránsito"), value: props.inTransit, tone: "warning" as Tone },
              { icon: <ScheduleRounded />, label: t("Programados"), value: props.scheduled, tone: "info" as Tone },
              { icon: <DoneAllRounded />, label: t("Completados"), value: props.completed, tone: "success" as Tone },
            ].map((metric) => (
              <Box key={metric.label} sx={{
                p: 1.75, borderRadius: `${R.md}px`, bgcolor: "background.default",
                display: "flex", flexDirection: "column", justifyContent: "center",
              }}>
                <Box sx={{ display: "flex", alignItems: "center", gap: 0.75, color: toneColor(metric.tone), "& svg": { fontSize: 16 } }}>
                  {metric.icon}
                  <Typography sx={{ fontSize: T.body - 0.5, fontWeight: 700, color: "text.secondary" }}>{metric.label}</Typography>
                </Box>
                <Box sx={{ mt: 0.5 }}><BigValue size={26} value={metric.value} loading={props.loading} /></Box>
              </Box>
            ))}
          </Box>
        </>
      )}
    </Card>
  );
}

function AttentionCard(props: {
  loading: boolean; error: boolean;
  summary?: {
    tripsOverdue: number; openExceptions: number; blockedShipments: number;
    tripsDepartedLate: number; stopsPastWindow: number; outstandingStops: number;
  };
}) {
  const s = props.summary;
  // Cada fila es una cifra distinta del resumen y no se suman entre sí: un envío vencido y una
  // parada fuera de ventana no son la misma unidad.
  const rows: Array<{ icon: ReactNode; label: string; help: string; value?: number; tone: Tone }> = [
    { icon: <HourglassBottomRounded />, label: t("Vencidos sin salir"), help: t("Debían haber salido"), value: s?.tripsOverdue, tone: "error" },
    { icon: <ReportProblemRounded />, label: t("Incidencias abiertas"), help: t("En envíos de hoy"), value: s?.openExceptions, tone: "error" },
    { icon: <BlockRounded />, label: t("No pueden salir"), help: t("Bloqueados ahora mismo"), value: s?.blockedShipments, tone: "warning" },
    { icon: <PendingActionsRounded />, label: t("Salieron tarde"), help: t("Salida después de lo planificado"), value: s?.tripsDepartedLate, tone: "warning" },
    {
      icon: <ScheduleRounded />, label: t("Paradas fuera de ventana"),
      help: t("De {{n}} paradas pendientes", { n: fmtQuantity(s?.outstandingStops ?? 0) }),
      value: s?.stopsPastWindow, tone: "warning",
    },
  ];

  return (
    <Card sx={{ gap: 1 }}>
      <Box sx={{ display: "flex", alignItems: "center", gap: 1 }}>
        <PriorityHighRounded sx={{ fontSize: 19, color: "error.main" }} />
        <Typography component="h2" sx={{ fontSize: 16, fontWeight: 800 }}>{t("Requiere atención")}</Typography>
      </Box>
      {props.error ? (
        <Alert severity="warning">{t("No se pudo cargar el resumen del día.")}</Alert>
      ) : rows.map((row) => {
        const active = (row.value ?? 0) > 0;
        return (
          <Box
            key={row.label}
            component={RouterLink}
            to="/control-tower"
            sx={{
              display: "flex", alignItems: "center", gap: 1.5, px: 1.25, py: 0.75,
              borderRadius: `${R.md}px`, border: 1, borderColor: "divider",
              color: "text.primary", textDecoration: "none",
              transition: "border-color .15s, background-color .15s",
              "&:hover": { borderColor: "text.disabled", bgcolor: "action.hover" },
            }}
          >
            <IconBox size={30} tone={active ? row.tone : "neutral"}>{row.icon}</IconBox>
            <Box sx={{ flex: 1, minWidth: 0 }}>
              <Typography sx={{ fontSize: T.bodyStrong, fontWeight: 700 }}>{row.label}</Typography>
              <Typography sx={{ fontSize: T.label + 0.5, color: "text.secondary" }}>{row.help}</Typography>
            </Box>
            {props.loading ? <Skeleton width={24} /> : (
              <Typography sx={{
                fontSize: 20, fontWeight: 800, fontVariantNumeric: "tabular-nums",
                color: active ? `${row.tone}.dark` : "text.secondary",
              }}>
                {fmtQuantity(row.value ?? 0)}
              </Typography>
            )}
            <ChevronRightRounded sx={{ fontSize: 18, color: "text.secondary" }} aria-hidden />
          </Box>
        );
      })}
    </Card>
  );
}
