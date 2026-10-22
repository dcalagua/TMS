import { useState, type ReactNode } from "react";
import { useNavigate } from "react-router-dom";
import {
  Box, Card, CardActionArea, CircularProgress, IconButton, Skeleton, ToggleButton, ToggleButtonGroup, Typography,
} from "@mui/material";
import {
  ArrowDownwardRounded, AssignmentTurnedInRounded, ChevronRightRounded, CloudUploadRounded, LocalShippingRounded,
  PersonRounded, RefreshRounded, ScheduleRounded, TripOriginRounded,
} from "@mui/icons-material";
import { fetchPortalTrips, type PortalTripSummary } from "../../shared/api/portalApi";
import { useCompany } from "../../shared/company/CompanyContext";
import { EmptyState, ErrorState, StatusChip } from "../../shared/ui/components";
import { t } from "../../lib/i18n";
import { fmtDateTime } from "../../lib/locale";
import { R } from "../../theme";
import { OfflineBanner } from "./OfflineBanner";
import { ProgressRing } from "./ProgressRing";
import { usePortalOffline } from "./offline/offlineContext";
import { queuedByTrip } from "./offline/overlay";
import { usePortalQuery } from "./offline/usePortalQuery";
import { describeLoadError, portalTripsKey } from "./portalReport";
import { PORTAL_PAGE_GRID } from "./portalUi";
import { usePullToRefresh } from "./usePullToRefresh";

const DAY_OPTIONS = [7, 14, 30] as const;
const DAYS_KEY = "tms.portal.days";
const PENDING_KEY = "tms.portal.pendingOnly";

function readNumber(key: string, fallback: number): number {
  try {
    const value = Number(window.localStorage.getItem(key));
    return (DAY_OPTIONS as readonly number[]).includes(value) ? value : fallback;
  } catch { return fallback; }
}

function readFlag(key: string): boolean {
  try { return window.localStorage.getItem(key) === "1"; } catch { return false; }
}

function store(key: string, value: string): void {
  try { window.localStorage.setItem(key, value); } catch { /* noop */ }
}

const SEGMENT_SX = {
  minHeight: 48, flex: 1, textTransform: "none", fontWeight: 800, fontSize: 16,
  "&.Mui-selected, &.Mui-selected:hover": { bgcolor: "primary.main", color: "primary.contrastText" },
} as const;

/**
 * Los viajes del transportista que el almacén ya despachó (ADR-018 §3), del más reciente al más
 * antiguo, cada uno con cuántos de sus documentos están reportados y qué falta.
 *
 * Un transportista no ve borradores ni viajes cancelados, ni nada que no le asignaron: lo filtra
 * el servidor. Aquí se elige si mirar solo lo pendiente —lo que se mira al empezar el reparto— y
 * la ventana de días. Sin señal se enseña la última lista leída en este teléfono, con su hora
 * (ADR-019 §1). Tirar hacia abajo recarga.
 */
export function PortalTripsPage() {
  const navigate = useNavigate();
  const { selected } = useCompany();
  const { entries } = usePortalOffline();
  const companyId = selected?.id ?? "";
  const [days, setDays] = useState(() => readNumber(DAYS_KEY, 14));
  const [pendingOnly, setPendingOnly] = useState(() => readFlag(PENDING_KEY));

  const tripsQuery = usePortalQuery({
    queryKey: [...portalTripsKey(companyId), days, pendingOnly],
    companyId,
    snapshotKey: `trips:${days}:${pendingOnly}`,
    fetcher: (signal) => fetchPortalTrips(companyId, { days, pendingOnly }, signal),
    enabled: companyId !== "",
    keepPrevious: true,
  });
  const pull = usePullToRefresh(() => tripsQuery.refetch());

  const trips = tripsQuery.data?.data ?? [];
  const queued = queuedByTrip(entries, companyId);

  return (
    <Box {...pull.handlers} sx={PORTAL_PAGE_GRID}>
      <Box aria-hidden={!pull.refreshing} sx={{
        height: pull.refreshing ? 48 : pull.pull, overflow: "hidden", display: "grid", placeItems: "center",
        transition: pull.pull === 0 ? "height .2s ease" : "none", mt: pull.pull || pull.refreshing ? 0 : -2,
      }}>
        {pull.refreshing
          ? <CircularProgress size={28} aria-label={t("Recargando")} />
          : <ArrowDownwardRounded color="primary" sx={{
              fontSize: 28, opacity: pull.progress, transform: `rotate(${pull.progress >= 1 ? 180 : 0}deg)`, transition: "transform .15s",
            }} />}
      </Box>

      <Box sx={{ display: "flex", alignItems: "center", gap: 1 }}>
        <Typography component="h1" sx={{ fontWeight: 800, fontSize: 24, flex: 1 }}>{t("Mis viajes")}</Typography>
        <IconButton
          onClick={() => void tripsQuery.refetch()} aria-label={t("Recargar")} disabled={tripsQuery.isFetching}
          sx={{ width: 48, height: 48 }}
        >
          {tripsQuery.isFetching && !tripsQuery.isPending ? <CircularProgress size={22} /> : <RefreshRounded />}
        </IconButton>
      </Box>

      <OfflineBanner savedAt={tripsQuery.data?.savedAt ?? null} />

      <ToggleButtonGroup
        exclusive fullWidth value={pendingOnly ? "pending" : "all"}
        onChange={(_e, next: "pending" | "all" | null) => {
          if (next === null) return;
          setPendingOnly(next === "pending");
          store(PENDING_KEY, next === "pending" ? "1" : "0");
        }}
        aria-label={t("Qué viajes ver")}
        sx={{ bgcolor: "background.paper" }}
      >
        <ToggleButton value="pending" sx={SEGMENT_SX}>{t("Pendientes")}</ToggleButton>
        <ToggleButton value="all" sx={SEGMENT_SX}>{t("Todos")}</ToggleButton>
      </ToggleButtonGroup>

      <ToggleButtonGroup
        exclusive fullWidth size="small" value={days}
        onChange={(_e, next: number | null) => { if (next !== null) { setDays(next); store(DAYS_KEY, String(next)); } }}
        aria-label={t("Días")}
        sx={{ bgcolor: "background.paper" }}
      >
        {DAY_OPTIONS.map((option) => (
          <ToggleButton key={option} value={option} sx={{ ...SEGMENT_SX, minHeight: 44, fontSize: 15, fontWeight: 700 }}>
            {t("{{n}} días", { n: option })}
          </ToggleButton>
        ))}
      </ToggleButtonGroup>

      {tripsQuery.isPending ? (
        <Box role="status" aria-label={t("Cargando viajes...")} sx={{ display: "grid", gap: 1.5 }}>
          {[0, 1, 2].map((i) => <TripCardSkeleton key={i} />)}
        </Box>
      ) : tripsQuery.isError ? (
        <ErrorState message={describeLoadError(tripsQuery.error)} onRetry={() => void tripsQuery.refetch()} />
      ) : trips.length === 0 ? (
        <EmptyState
          icon={<LocalShippingRounded />}
          title={pendingOnly ? t("Nada pendiente") : t("Aún no tienes viajes despachados")}
          message={pendingOnly
            ? t("Todos los documentos de tus viajes de estos días ya están reportados.")
            : t("Los viajes aparecen aquí cuando el almacén los despacha a tu transportista. Si esperas uno, tira hacia abajo para recargar o pregunta en el almacén.")}
        />
      ) : (
        <Box component="ul" aria-label={t("Mis viajes")} sx={{ listStyle: "none", m: 0, p: 0, display: "grid", gap: 1.5 }}>
          {trips.map((trip) => (
            <Box component="li" key={trip.tripId}>
              <PortalTripCard trip={trip} queued={queued.get(trip.tripId) ?? 0} onOpen={() => navigate(`/portal/trips/${trip.tripId}`)} />
            </Box>
          ))}
        </Box>
      )}
    </Box>
  );
}

function Line({ icon, children }: { icon: ReactNode; children: ReactNode }) {
  return (
    <Typography sx={{ display: "flex", alignItems: "center", gap: 1, fontSize: 15, minWidth: 0, "& svg": { fontSize: 19, color: "text.secondary", flexShrink: 0 } }}>
      {icon}
      <Box component="span" sx={{ overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>{children}</Box>
    </Typography>
  );
}

function PortalTripCard({ trip, queued, onOpen }: { trip: PortalTripSummary; queued: number; onOpen: () => void }) {
  const reported = trip.documentCount - trip.pendingCount;
  return (
    <Card variant="outlined" sx={{ borderRadius: `${R.lg}px` }}>
      <CardActionArea onClick={onOpen} aria-label={t("Abrir viaje {{n}}", { n: trip.shipmentNumber })}
        sx={{ p: 2, display: "grid", gap: 1.5, minHeight: 64 }}>
        <Box sx={{ display: "flex", alignItems: "center", gap: 1.5 }}>
          <Box sx={{ flex: 1, minWidth: 0 }}>
            <Typography sx={{ fontWeight: 800, fontSize: 20, lineHeight: 1.2, fontVariantNumeric: "tabular-nums" }}>{trip.shipmentNumber}</Typography>
            <Typography color="text.secondary" sx={{ fontSize: 14, display: "flex", alignItems: "center", gap: 0.5, mt: 0.25 }}>
              <ScheduleRounded sx={{ fontSize: 17 }} />
              {trip.dispatchedAt ? t("Despachado {{when}}", { when: fmtDateTime(trip.dispatchedAt) }) : t("Despacho sin hora")}
            </Typography>
          </Box>
          <ProgressRing done={reported} total={trip.documentCount} />
          <ChevronRightRounded color="action" />
        </Box>

        <Box sx={{ display: "grid", gap: 0.5 }}>
          <Line icon={<LocalShippingRounded />}>{trip.vehicleLicensePlate ?? t("Sin placa")}</Line>
          <Line icon={<PersonRounded />}>{trip.driverName ?? t("Sin conductor")}</Line>
          {trip.originName && <Line icon={<TripOriginRounded />}>{trip.originName}</Line>}
        </Box>

        <Box sx={{ display: "flex", gap: 0.75, flexWrap: "wrap", alignItems: "center" }}>
          {trip.pendingCount > 0 && <StatusChip size="medium" tone="neutral" label={t("{{n}} pendientes", { n: trip.pendingCount })} />}
          {trip.rejectedCount > 0 && <StatusChip size="medium" tone="overdue" label={t("{{n}} rechazados", { n: trip.rejectedCount })} />}
          {trip.notDeliveredCount > 0 && <StatusChip size="medium" tone="inProgress" label={t("{{n}} no entregados", { n: trip.notDeliveredCount })} />}
          {queued > 0 && <StatusChip size="medium" tone="open" icon={<CloudUploadRounded />} label={t("{{n}} por sincronizar", { n: queued })} />}
          {trip.returnedCount > 0 && (
            <StatusChip size="medium" tone="done" icon={<AssignmentTurnedInRounded />}
              label={t("Cargo recibido: {{n}}", { n: trip.returnedCount })} />
          )}
          <Typography color="text.secondary" sx={{ fontSize: 14, ml: "auto" }}>
            {t("{{n}} paradas", { n: trip.stopCount })}
          </Typography>
        </Box>
      </CardActionArea>
    </Card>
  );
}

function TripCardSkeleton() {
  return (
    <Card variant="outlined" sx={{ borderRadius: `${R.lg}px`, p: 2, display: "grid", gap: 1.5 }}>
      <Box sx={{ display: "flex", alignItems: "center", gap: 1.5 }}>
        <Box sx={{ flex: 1 }}>
          <Skeleton variant="text" width="45%" sx={{ fontSize: 22 }} />
          <Skeleton variant="text" width="65%" />
        </Box>
        <Skeleton variant="circular" width={60} height={60} />
      </Box>
      <Skeleton variant="text" width="50%" />
      <Skeleton variant="text" width="40%" />
      <Box sx={{ display: "flex", gap: 1 }}>
        <Skeleton variant="rounded" width={96} height={32} sx={{ borderRadius: 16 }} />
        <Skeleton variant="rounded" width={110} height={32} sx={{ borderRadius: 16 }} />
      </Box>
    </Card>
  );
}
