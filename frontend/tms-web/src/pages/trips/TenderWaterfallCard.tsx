import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Alert, Box, Button, Chip, Divider, Table, TableBody, TableCell, TableContainer,
  TableHead, TableRow, Typography,
} from "@mui/material";
import { AltRouteRounded, PlayArrowRounded, SkipNextRounded, StopRounded } from "@mui/icons-material";
import { AppCard, LoadingState, StatusChip, dataTableSx } from "../../shared/ui/components";
import {
  advanceTenderWaterfall, fetchTenderWaterfall, startTenderWaterfall, stopTenderWaterfall,
  type TenderWaterfallView, type WaterfallCandidateStatus,
} from "../../shared/api/tendersApi";
import type { ApiError } from "../../shared/api/httpClient";
import { describeApiError } from "../../shared/api/problemMessages";
import { confirmDialog, notifyError, notifySuccess, promptDialog } from "../../lib/ui";
import { enumLabel } from "../../lib/enums";
import type { StatusTone } from "../../theme";
import { fmtDateTime, fmtMoney } from "../../lib/locale";
import { t } from "../../lib/i18n";

interface TenderWaterfallCardProps {
  companyId: string;
  tripId: string;
  canManage: boolean;
}

/**
 * La cascada de tendering (migración V40).
 *
 * <h2>Por qué la lista completa y no un contador</h2>
 * "Ofrecido a tres transportistas" es un número. La lista en orden, con lo que se cotizó a cada uno
 * y lo que contestó, es la respuesta a lo que un despachador realmente pregunta a las siete de la
 * tarde: quién ya dijo que no, y quién queda.
 *
 * <h2>Por qué hay un botón de avanzar</h2>
 * Crear una oferta pasa por `requireAppUserId`, que rechaza a una máquina por diseño: una oferta a
 * un transportista es un compromiso comercial y el rastro tiene que nombrar a quien lo hizo. No hay
 * barrido automático, así que la pantalla dice cuándo venció la oferta y una persona avanza.
 */
export function TenderWaterfallCard({ companyId, tripId, canManage }: TenderWaterfallCardProps) {
  const queryClient = useQueryClient();
  const [busy, setBusy] = useState(false);

  const waterfall = useQuery({
    queryKey: ["tender-waterfall", companyId, tripId],
    // Un 404 significa "este viaje nunca fue a cascada", que es un estado y no un error.
    queryFn: () => fetchTenderWaterfall(companyId, tripId).catch(() => null),
  });

  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: ["tender-waterfall", companyId, tripId] });
    void queryClient.invalidateQueries({ queryKey: ["tenders", companyId, tripId] });
    void queryClient.invalidateQueries({ queryKey: ["trip", companyId, tripId] });
  };

  const run = async (action: () => Promise<TenderWaterfallView>, success: string, failure: string) => {
    setBusy(true);
    try {
      await action();
      notifySuccess(t(success));
      refresh();
    } catch (error) {
      notifyError(t(failure), describeApiError(error as ApiError));
    } finally {
      setBusy(false);
    }
  };

  async function start() {
    const confirmed = await confirmDialog({
      title: t("¿Iniciar la cascada de tendering?"),
      text: t("Se ordenarán los transportistas activos por lo que cobrarían y se enviará la oferta al primero. Nada se acepta ni se despacha automáticamente."),
      confirmLabel: t("Iniciar cascada"),
    });
    if (!confirmed) return;
    await run(() => startTenderWaterfall(companyId, tripId), "Cascada iniciada", "No se pudo iniciar la cascada");
  }

  async function advance() {
    await run(() => advanceTenderWaterfall(companyId, tripId),
      "Oferta enviada al siguiente transportista", "No se pudo avanzar la cascada");
  }

  async function stop() {
    const reason = await promptDialog({
      title: t("¿Detener la cascada?"),
      text: t("Se retirará la oferta que esté fuera, para que ningún transportista acepte un viaje cuya cascada acabas de detener."),
      inputLabel: t("Motivo"),
      maxLength: 500,
      confirmLabel: t("Detener cascada"),
      dangerous: true,
    });
    if (reason === null) return;
    await run(() => stopTenderWaterfall(companyId, tripId, reason),
      "Cascada detenida", "No se pudo detener la cascada");
  }

  if (waterfall.isPending) {
    return <AppCard title={t("Cascada de tendering")}><LoadingState /></AppCard>;
  }

  const plan = waterfall.data;

  return (
    <AppCard
      title={
        <Box sx={{ display: "flex", alignItems: "center", gap: 1 }}>
          <AltRouteRounded fontSize="small" />
          <span>{t("Cascada de tendering")}</span>
        </Box>
      }
      actions={plan && <StatusChip label={enumLabel("waterfallStatus", plan.status)} tone={STATUS_TONE[plan.status]} />}
    >
      {!plan ? (
        <>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
            {t("Este viaje no se ha ofrecido por cascada. Al iniciarla se ordenan los transportistas activos por lo que cobrarían y se ofrece al primero.")}
          </Typography>
          {canManage && (
            <Button size="small" variant="outlined" startIcon={<PlayArrowRounded />} disabled={busy} onClick={() => void start()}>
              {t("Iniciar cascada")}
            </Button>
          )}
        </>
      ) : (
        <>
          <Box sx={{ display: "flex", gap: 3, flexWrap: "wrap", mb: 2 }}>
            <Stat label={t("Ofertas hechas")} value={`${plan.attemptsUsed} / ${plan.maxAttempts}`} />
            <Stat label={t("Plazo por oferta")} value={t("{{m}} min", { m: plan.responseMinutes })} />
            <Stat label={t("Iniciada")} value={fmtDateTime(plan.startedAt)} />
          </Box>

          {plan.currentOfferLapsed && (
            <Alert
              severity="warning" variant="outlined" sx={{ mb: 2 }}
              action={canManage && (
                <Button size="small" startIcon={<SkipNextRounded />} disabled={busy} onClick={() => void advance()}>
                  {t("Avanzar")}
                </Button>
              )}
            >
              {t("La oferta actual venció sin respuesta. Avanza para ofrecer al siguiente transportista.")}
            </Alert>
          )}

          {plan.outcomeNote && (
            <Alert severity="info" variant="outlined" sx={{ mb: 2 }}>{plan.outcomeNote}</Alert>
          )}

          <TableContainer sx={{ mb: 2 }}>
            <Table size="small" sx={dataTableSx}>
              <TableHead>
                <TableRow>
                  <TableCell className="numeric-col">{t("Orden")}</TableCell>
                  <TableCell>{t("Transportista")}</TableCell>
                  <TableCell className="numeric-col">{t("Cotizado")}</TableCell>
                  <TableCell>{t("Estado")}</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {plan.candidates.map((candidate) => (
                  <TableRow key={candidate.rank}>
                    <TableCell className="numeric-col">{candidate.rank}</TableCell>
                    <TableCell>
                      <Typography variant="body2" sx={{ fontWeight: 600 }}>
                        {candidate.carrierName ?? candidate.carrierCode ?? candidate.carrierId}
                      </Typography>
                    </TableCell>
                    <TableCell className="numeric-col">
                      {/* Sin tarifa aplicable no es cero: se dice, y por eso este transportista
                          quedó al final de la lista y no al principio. */}
                      {candidate.quotedAmount === null
                        ? <Chip size="small" variant="outlined" label={t("Sin tarifa")} />
                        : fmtMoney(candidate.quotedAmount, candidate.quotedCurrency ?? "PEN")}
                    </TableCell>
                    <TableCell>
                      <StatusChip
                        label={enumLabel("waterfallCandidateStatus", candidate.status)}
                        tone={CANDIDATE_TONE[candidate.status]}
                      />
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableContainer>

          {canManage && plan.status === "ACTIVE" && (
            <>
              <Divider sx={{ mb: 1.5 }} />
              <Button size="small" color="error" startIcon={<StopRounded />} disabled={busy} onClick={() => void stop()}>
                {t("Detener cascada")}
              </Button>
            </>
          )}
        </>
      )}
    </AppCard>
  );
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <Box>
      <Typography variant="caption" color="text.secondary" sx={{ display: "block" }}>{label}</Typography>
      <Typography variant="body2" sx={{ fontWeight: 700, fontVariantNumeric: "tabular-nums" }}>{value}</Typography>
    </Box>
  );
}

/** `EXHAUSTED` es `overdue` y no `cancelled`: nadie decidió parar, se acabó la lista - hay que actuar. */
const STATUS_TONE: Record<TenderWaterfallView["status"], StatusTone> = {
  ACTIVE: "inProgress",
  ACCEPTED: "done",
  EXHAUSTED: "overdue",
  CANCELLED: "cancelled",
};

const CANDIDATE_TONE: Record<WaterfallCandidateStatus, StatusTone> = {
  PENDING: "neutral",
  OFFERED: "inProgress",
  ACCEPTED: "done",
  REJECTED: "overdue",
  EXPIRED: "overdue",
  SKIPPED: "neutral",
};
