import { useEffect } from "react";
import { useParams } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { Alert, Box, Button, ButtonGroup, Paper, Skeleton, Typography } from "@mui/material";
import {
  CheckCircleRounded, DoNotDisturbOnRounded, HighlightOffRounded, Inventory2Outlined,
  LocalShippingRounded, PlaceRounded, RadioButtonUncheckedRounded, ScheduleRounded,
} from "@mui/icons-material";
import { ApiError } from "../../shared/api/httpClient";
import {
  fetchPublicTracking, type CustomerTrackingStatus, type PublicTrackingStep, type PublicTrackingView,
  type TrackingStepCode,
} from "../../shared/api/trackingLinksApi";
import { ProductLockup } from "../../shared/ui/EbimLogo";
import { STATUS, type StatusKey } from "../../theme";
import { getLang, setLang, t } from "../../lib/i18n";
import { localeFor } from "../../lib/locale";

/**
 * Lo que ve el cliente al abrir el enlace que le mandaron por WhatsApp (ADR-029). Sin sesión, fuera de
 * todo armazón de la aplicación, pensada para un teléfono de 390 px: una columna, letra grande, nada
 * que haga desplazar la página en horizontal.
 *
 * Publica exactamente lo que trae `PublicTrackingView` y no pide nada más. Las horas se pintan en el
 * huso de la empresa, que es como la operación las cuenta, y se dice cuál es.
 */

const STATUS_COPY: Record<CustomerTrackingStatus, { title: string; tone: StatusKey }> = {
  RECEIVED: { title: "Pedido registrado", tone: "open" },
  PLANNED: { title: "Programado", tone: "open" },
  IN_TRANSIT: { title: "En ruta", tone: "inProgress" },
  ARRIVED: { title: "Llegó a destino", tone: "inProgress" },
  DELIVERED: { title: "Entregado", tone: "done" },
  PARTIALLY_DELIVERED: { title: "Entregado parcialmente", tone: "inProgress" },
  REJECTED: { title: "Rechazado", tone: "overdue" },
  NOT_DELIVERED: { title: "No entregado", tone: "overdue" },
  CANCELLED: { title: "Pedido cancelado", tone: "cancelled" },
};

const STEP_LABEL: Record<Exclude<TrackingStepCode, "OUTCOME">, string> = {
  RECEIVED: "Pedido registrado",
  PLANNED: "Programado",
  IN_TRANSIT: "En ruta",
  ARRIVED: "Llegó a destino",
};

const REFRESH_MS = 60_000;

/** Pone (y quita al salir) las etiquetas que piden no indexar la página ni filtrar el enlace por Referer. */
function usePrivatePageMeta(title: string) {
  useEffect(() => {
    const previousTitle = document.title;
    document.title = title;
    const added: HTMLMetaElement[] = [];
    for (const [name, content] of [["robots", "noindex, nofollow"], ["referrer", "no-referrer"]] as const) {
      const meta = document.createElement("meta");
      meta.name = name;
      meta.content = content;
      document.head.appendChild(meta);
      added.push(meta);
    }
    return () => {
      document.title = previousTitle;
      added.forEach((meta) => meta.remove());
    };
  }, [title]);
}

function formatTime(value: string | null | undefined, timeZone: string): string {
  if (!value) return "";
  return new Intl.DateTimeFormat(localeFor(), { timeStyle: "short", timeZone }).format(new Date(value));
}

function formatDateTime(value: string | null | undefined, timeZone: string): string {
  if (!value) return "";
  return new Intl.DateTimeFormat(localeFor(), { dateStyle: "medium", timeStyle: "short", timeZone })
    .format(new Date(value));
}

function sameDay(a: string, b: string, timeZone: string): boolean {
  const day = (v: string) => new Intl.DateTimeFormat("en-CA", { timeZone, dateStyle: "short" }).format(new Date(v));
  return day(a) === day(b);
}

export function TrackingPublicPage() {
  const { token = "" } = useParams<{ token: string }>();
  usePrivatePageMeta(t("Seguimiento de pedido") + " · eTMS");

  const query = useQuery({
    queryKey: ["public-tracking", token],
    queryFn: ({ signal }) => fetchPublicTracking(token, signal),
    retry: false,
    refetchInterval: (q) => (q.state.status === "error" ? false : REFRESH_MS),
    refetchOnWindowFocus: true,
  });

  return (
    <Box
      component="main"
      sx={{
        minHeight: "100dvh", bgcolor: "background.default", color: "text.primary",
        display: "flex", justifyContent: "center", overflowX: "hidden",
      }}
    >
      <Box sx={{ width: "100%", maxWidth: 480, px: 2, py: 2.5, boxSizing: "border-box" }}>
        <Box component="header" sx={{ display: "flex", alignItems: "center", justifyContent: "space-between", mb: 2.5 }}>
          <ProductLockup name="eTMS" nameSize={20} markSize={14} />
          <LanguageToggle />
        </Box>

        {query.isPending ? (
          <Paper variant="outlined" sx={{ p: 2.5, borderRadius: 3 }} aria-busy="true" aria-label={t("Cargando el seguimiento...")}>
            <Skeleton width="40%" />
            <Skeleton width="70%" height={40} />
            <Skeleton width="100%" height={120} />
          </Paper>
        ) : query.isError ? (
          <TrackingError error={query.error} />
        ) : (
          <TrackingCard view={query.data} />
        )}

        <Typography variant="caption" color="text.secondary" component="p" sx={{ mt: 3, textAlign: "center" }}>
          {t("Seguimiento provisto por eTMS · by EBIM")}
        </Typography>
      </Box>
    </Box>
  );
}

function LanguageToggle() {
  const lang = getLang();
  return (
    <ButtonGroup size="small" variant="outlined" aria-label={t("Idioma")}>
      <Button onClick={() => lang !== "es" && setLang("es")} variant={lang === "es" ? "contained" : "outlined"}
        aria-pressed={lang === "es"} lang="es">
        ES
      </Button>
      <Button onClick={() => lang !== "en" && setLang("en")} variant={lang === "en" ? "contained" : "outlined"}
        aria-pressed={lang === "en"} lang="en">
        EN
      </Button>
    </ButtonGroup>
  );
}

function TrackingError({ error }: { error: unknown }) {
  const status = error instanceof ApiError ? error.status : 0;
  const message = status === 404
    ? t("Este enlace de seguimiento no existe o ya no está disponible. Pide uno nuevo a quien te lo envió.")
    : status === 429
      ? t("Demasiadas consultas seguidas. Espera unos minutos y vuelve a abrir el enlace.")
      : t("No pudimos cargar el seguimiento. Revisa tu conexión y vuelve a intentarlo.");
  return (
    <Alert severity={status === 404 ? "info" : "warning"} sx={{ borderRadius: 3 }} role="alert">
      {message}
    </Alert>
  );
}

function TrackingCard({ view }: { view: PublicTrackingView }) {
  const copy = STATUS_COPY[view.status];
  const zone = view.timeZone;

  return (
    <Paper variant="outlined" component="section" aria-labelledby="tracking-status" sx={{ p: 2.5, borderRadius: 3 }}>
      <Typography variant="overline" color="text.secondary" component="p" sx={{ lineHeight: 1.4 }}>
        {view.companyName}
      </Typography>
      <Typography variant="body2" color="text.secondary" sx={{ display: "flex", alignItems: "center", gap: 0.75, flexWrap: "wrap" }}>
        <Inventory2Outlined aria-hidden sx={{ fontSize: 18 }} />
        <span>{t("Pedido")} <strong>{view.orderNumber}</strong></span>
        {view.reference && <span>· {t("Ref.")} {view.reference}</span>}
      </Typography>

      <StatusHeadline id="tracking-status" title={t(copy.title)} tone={copy.tone} />
      <Headline view={view} />

      {view.destinationName && (
        <Typography variant="body2" sx={{ display: "flex", alignItems: "center", gap: 0.75, mt: 1.5 }}>
          <PlaceRounded aria-hidden sx={{ fontSize: 18, color: "text.secondary" }} />
          {t("Destino")}: {view.destinationName}
        </Typography>
      )}

      <Box component="ol" aria-label={t("Recorrido del pedido")} sx={{ listStyle: "none", p: 0, m: 0, mt: 2.5 }}>
        {view.timeline.map((step, index) => (
          <TimelineStep key={step.code} step={step} last={index === view.timeline.length - 1} zone={zone} />
        ))}
      </Box>

      {(view.carrierName || view.vehiclePlate) && (
        <Box sx={{ mt: 2, pt: 2, borderTop: 1, borderColor: "divider", display: "grid", gap: 0.75 }}>
          {view.carrierName && (
            <Typography variant="body2" sx={{ display: "flex", alignItems: "center", gap: 0.75 }}>
              <LocalShippingRounded aria-hidden sx={{ fontSize: 18, color: "text.secondary" }} />
              {t("Transportista")}: {view.carrierName}
            </Typography>
          )}
          {view.vehiclePlate && (
            <Typography variant="body2" sx={{ pl: 3.25, fontVariantNumeric: "tabular-nums" }}>
              {t("Placa")}: {view.vehiclePlate}
            </Typography>
          )}
        </Box>
      )}

      <Typography variant="caption" color="text.secondary" component="p" sx={{ mt: 2 }}>
        {t("Actualizado a las {{time}} · horas de {{zone}}", { time: formatTime(view.generatedAt, zone), zone })}
        <br />
        {t("Este enlace vence el {{date}}.", { date: formatDateTime(view.expiresAt, zone) })}
      </Typography>
    </Paper>
  );
}

function StatusHeadline({ id, title, tone }: { id: string; title: string; tone: StatusKey }) {
  return (
    <Typography
      id={id}
      component="h1"
      sx={(theme) => {
        const palette = STATUS[theme.palette.mode === "dark" ? "dark" : "light"][tone];
        return {
          mt: 1.5, display: "inline-block", px: 1.5, py: 0.5, borderRadius: 2, fontWeight: 800,
          fontSize: "1.5rem", lineHeight: 1.3, bgcolor: palette.soft, color: palette.softText,
        };
      }}
    >
      {title}
    </Typography>
  );
}

/** La frase que contesta «¿cuándo llega?» o «¿qué pasó?», si hay con qué contestarla. */
function Headline({ view }: { view: PublicTrackingView }) {
  const zone = view.timeZone;
  if (view.proofOfDelivery) {
    const time = formatTime(view.proofOfDelivery.deliveredAt, zone);
    return (
      <Typography variant="body1" sx={{ mt: 1, display: "flex", alignItems: "center", gap: 0.75 }}>
        <CheckCircleRounded aria-hidden color="success" sx={{ fontSize: 20 }} />
        {view.proofOfDelivery.receiverName
          ? t("Recibido a las {{time}} por {{name}}", { time, name: view.proofOfDelivery.receiverName })
          : t("Recibido a las {{time}}", { time })}
      </Typography>
    );
  }
  if (view.arrivalWindow) {
    const { from, to } = view.arrivalWindow;
    const range = sameDay(from, view.generatedAt, zone)
      ? t("Llega entre las {{from}} y las {{to}}", { from: formatTime(from, zone), to: formatTime(to, zone) })
      : t("Llega el {{date}}, entre las {{from}} y las {{to}}", {
        date: new Intl.DateTimeFormat(localeFor(), { dateStyle: "medium", timeZone: zone }).format(new Date(from)),
        from: formatTime(from, zone), to: formatTime(to, zone),
      });
    return (
      <Typography variant="body1" sx={{ mt: 1, display: "flex", alignItems: "center", gap: 0.75, fontWeight: 600 }}>
        <ScheduleRounded aria-hidden sx={{ fontSize: 20, color: "text.secondary" }} />
        {range}
      </Typography>
    );
  }
  return null;
}

function TimelineStep({ step, last, zone }: { step: PublicTrackingStep; last: boolean; zone: string }) {
  const done = step.state === "DONE";
  const label = step.code === "OUTCOME"
    ? t(step.outcome ? STATUS_COPY[step.outcome].title : "Entrega")
    : t(STEP_LABEL[step.code]);
  const failed = step.outcome === "REJECTED" || step.outcome === "NOT_DELIVERED";
  const icon = !done
    ? <RadioButtonUncheckedRounded aria-hidden sx={{ fontSize: 22, color: "text.disabled" }} />
    : failed
      ? <HighlightOffRounded aria-hidden color="error" sx={{ fontSize: 22 }} />
      : step.outcome === "CANCELLED"
        ? <DoNotDisturbOnRounded aria-hidden sx={{ fontSize: 22, color: "text.secondary" }} />
        : <CheckCircleRounded aria-hidden color="success" sx={{ fontSize: 22 }} />;

  return (
    <Box component="li" sx={{ display: "flex", gap: 1.25, position: "relative", pb: last ? 0 : 1.75 }}>
      <Box sx={{ display: "flex", flexDirection: "column", alignItems: "center" }}>
        {icon}
        {!last && <Box aria-hidden sx={{ flex: 1, width: 2, mt: 0.5, bgcolor: done ? "success.light" : "divider" }} />}
      </Box>
      <Box sx={{ minWidth: 0 }}>
        <Typography variant="body2" sx={{ fontWeight: done ? 700 : 400, color: done ? "text.primary" : "text.secondary" }}>
          {label}
          <Box component="span" sx={{ position: "absolute", width: 1, height: 1, overflow: "hidden", clip: "rect(0 0 0 0)" }}>
            {done ? ` (${t("completado")})` : ` (${t("pendiente")})`}
          </Box>
        </Typography>
        {done && step.at && (
          <Typography variant="caption" color="text.secondary" sx={{ fontVariantNumeric: "tabular-nums" }}>
            {formatDateTime(step.at, zone)}
          </Typography>
        )}
      </Box>
    </Box>
  );
}
