import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Box, Button, Divider, Paper, Typography } from "@mui/material";
import {
  LocalOfferRounded, SendRounded, CheckCircleRounded, CancelRounded, EditRounded, UndoRounded,
} from "@mui/icons-material";
import type { ApiError } from "../../shared/api/httpClient";
import {
  acceptTender, createTender, fetchTripTenders, rejectTender, sendTender, updateTenderTerms,
  withdrawTender, type TenderRequest, type TripTenderView,
} from "../../shared/api/tendersApi";
import { describeApiError } from "../../shared/api/problemMessages";
import { AppCard, ErrorState, LoadingState, StatusChip } from "../../shared/ui/components";
import { TENDER_STATUS_TONE } from "../../shared/ui/statusTones";
import { confirmDialog, notifyError, notifySuccess, promptDialog } from "../../lib/ui";
import { enumLabel } from "../../lib/enums";
import { t } from "../../lib/i18n";
import { fmtDateTime, fmtMoney } from "../../lib/locale";
import { TenderDrawer } from "./TenderDrawer";

interface TripTenderCardProps {
  companyId: string;
  tripId: string;
  carrierName: string | null;
  /** `false` cuando el envío no puede ofertarse — sin transportista, o ya fuera de ruta. */
  offerable: boolean;
  canManage: boolean;
}

/**
 * A quién se le ofreció este envío y qué contestó.
 *
 * Cada mutación responde con el historial *entero* del envío, el intento más nuevo primero, y no
 * con el intento que tocó. Un viaje de ida y vuelta, y quien retira el intento 2 ve al momento el
 * rechazo del intento 1 encima, que suele ser justo por lo que está mirando.
 *
 * Los botones se pintan desde `allowedTransitions`, que es la respuesta del servidor a "qué
 * funciona" ya con el plazo aplicado. Derivarlos de `status` sería una segunda copia del ciclo de
 * vida en TypeScript, y la copia se equivoca en cuanto vence una oferta.
 */
export function TripTenderCard({ companyId, tripId, carrierName, offerable, canManage }: TripTenderCardProps) {
  const queryClient = useQueryClient();
  const queryKey = ["trip-tenders", companyId, tripId];
  const [editing, setEditing] = useState<TripTenderView | null>(null);
  const [creating, setCreating] = useState(false);
  const [busy, setBusy] = useState(false);

  const tendersQuery = useQuery({
    queryKey,
    queryFn: ({ signal }) => fetchTripTenders(companyId, tripId, signal),
  });

  const refresh = () => void queryClient.invalidateQueries({ queryKey });

  async function run(action: () => Promise<TripTenderView[]>, message: string) {
    if (busy) return;
    setBusy(true);
    try {
      queryClient.setQueryData(queryKey, await action());
      notifySuccess(message);
    } catch (error) {
      notifyError(t("No se pudo completar la acción"), describeApiError(error as ApiError));
    } finally {
      setBusy(false);
    }
  }

  async function send(tender: TripTenderView) {
    const confirmed = await confirmDialog({
      title: t("¿Enviar la oferta?"),
      text: t("Se le ofrece este envío a {{carrier}}.", { carrier: tender.carrierName ?? "" }),
      confirmLabel: t("Enviar"),
    });
    if (confirmed) await run(() => sendTender(companyId, tripId, tender.id), t("Oferta enviada"));
  }

  async function accept(tender: TripTenderView) {
    const confirmed = await confirmDialog({
      title: t("¿Aceptar la oferta?"),
      text: t("El envío queda colocado con {{carrier}}.", { carrier: tender.carrierName ?? "" }),
      confirmLabel: t("Aceptar"),
    });
    if (confirmed) await run(() => acceptTender(companyId, tripId, tender.id, {}), t("Oferta aceptada"));
  }

  /** El rechazo pide el motivo *dentro* de la confirmación: el backend lo exige, así que confirmar
   * primero y preguntar después sería un viaje de ida y vuelta a un 400. */
  async function reject(tender: TripTenderView) {
    const reason = await promptDialog({
      title: t("¿Registrar un rechazo?"),
      text: t("Anota lo que contestó el transportista."),
      inputLabel: t("Motivo"),
      required: true,
      maxLength: 500,
      confirmLabel: t("Registrar rechazo"),
      dangerous: true,
    });
    if (reason === null) return;
    await run(() => rejectTender(companyId, tripId, tender.id, { notes: reason }), t("Rechazo registrado"));
  }

  async function withdraw(tender: TripTenderView) {
    const reason = await promptDialog({
      title: t("¿Retirar la oferta?"),
      text: t("La oferta deja de estar en pie. Di por qué."),
      inputLabel: t("Motivo"),
      required: true,
      maxLength: 500,
      confirmLabel: t("Retirar"),
      dangerous: true,
    });
    if (reason === null) return;
    await run(() => withdrawTender(companyId, tripId, tender.id, { reason }), t("Oferta retirada"));
  }

  async function saveTerms(request: TenderRequest) {
    if (editing) await updateTenderTerms(companyId, tripId, editing.id, request);
    else await createTender(companyId, tripId, request);
    setEditing(null);
    setCreating(false);
    notifySuccess(t("Cambios guardados"));
    refresh();
  }

  if (tendersQuery.isPending) return <LoadingState minHeight={120} />;

  /* Se reporta como fallo y no como una línea gris: las otras frases cortas de esta tarjeta
     ("Sin ofertas", "No ofertable") son hechos sobre el envío, y una lectura rota no puede
     juntarse con ellas. Ofertar es un acto comercial, así que "no hay ofertas" y "no te lo
     pudimos decir" son especialmente distintas aquí. */
  if (tendersQuery.isError) {
    return (
      <ErrorState
        message={describeApiError(tendersQuery.error as ApiError)}
        onRetry={() => void tendersQuery.refetch()}
      />
    );
  }

  const tenders = tendersQuery.data;
  const live = tenders.find((tender) => tender.status === "DRAFT" || tender.status === "SENT") ?? null;
  const accepted = tenders.find((tender) => tender.status === "ACCEPTED") ?? null;
  // Un intento nuevo solo cabe cuando no hay nada vivo ni nada aceptado: las dos reglas que
  // aplica el servidor, replicadas aquí para que el botón esté ausente en vez de ser un 409 seguro.
  const canOffer = canManage && offerable && live === null && accepted === null;

  return (
    <AppCard
      title={
        <Box sx={{ display: "flex", alignItems: "center", gap: 1 }}>
          <LocalOfferRounded sx={{ fontSize: 19, color: "text.disabled" }} />
          {t("Ofertas a transportista")}
        </Box>
      }
      actions={canOffer && (
        <Button size="small" startIcon={<LocalOfferRounded />} onClick={() => setCreating(true)}>
          {t("Nueva oferta")}
        </Button>
      )}
    >
      {!offerable && tenders.length === 0 ? (
        <Typography variant="body2" color="text.secondary">
          {t("Este envío todavía no se puede ofertar: necesita un transportista.")}
        </Typography>
      ) : tenders.length === 0 ? (
        <Typography variant="body2" color="text.secondary">{t("Sin ofertas.")}</Typography>
      ) : (
        <Box sx={{ display: "grid", gap: 1 }}>
          {tenders.map((tender) => {
            const can = (status: string) => tender.allowedTransitions.includes(status as never);
            return (
              <Paper key={tender.id} variant="outlined" sx={{ p: 1.5 }}>
                <Box sx={{ display: "flex", alignItems: "center", gap: 1, mb: 0.5, flexWrap: "wrap" }}>
                  <Typography variant="body2" sx={{ fontWeight: 700 }}>
                    {t("Intento {{n}}", { n: tender.attempt })}
                  </Typography>
                  <StatusChip label={enumLabel("tenderStatus", tender.status)} tone={TENDER_STATUS_TONE[tender.status]} />
                  <Box sx={{ flex: 1 }} />
                  {tender.offeredAmount !== null && (
                    <Typography variant="body2" sx={{ fontWeight: 800, fontVariantNumeric: "tabular-nums" }}>
                      {fmtMoney(tender.offeredAmount, tender.currency ?? "PEN")}
                    </Typography>
                  )}
                </Box>

                <Typography variant="caption" color="text.secondary" sx={{ display: "block" }}>
                  {tender.carrierName ?? ""}
                  {tender.expiresAt && ` · ${t("Vence")} ${fmtDateTime(tender.expiresAt)}`}
                  {tender.sentAt && ` · ${t("Enviada")} ${fmtDateTime(tender.sentAt)}`}
                </Typography>

                {(tender.notes || tender.responseNotes || tender.cancelReason) && (
                  <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5 }}>
                    {[tender.notes, tender.responseNotes, tender.cancelReason].filter(Boolean).join(" · ")}
                  </Typography>
                )}

                {canManage && tender.allowedTransitions.length > 0 && (
                  <>
                    <Divider sx={{ my: 1 }} />
                    <Box sx={{ display: "flex", gap: 0.75, flexWrap: "wrap" }}>
                      {tender.status === "DRAFT" && (
                        <Button size="small" startIcon={<EditRounded />} onClick={() => setEditing(tender)}>
                          {t("Editar")}
                        </Button>
                      )}
                      {can("SENT") && (
                        <Button size="small" variant="contained" startIcon={<SendRounded />} disabled={busy} onClick={() => void send(tender)}>
                          {t("Enviar")}
                        </Button>
                      )}
                      {can("ACCEPTED") && (
                        <Button size="small" color="success" startIcon={<CheckCircleRounded />} disabled={busy} onClick={() => void accept(tender)}>
                          {t("Aceptar")}
                        </Button>
                      )}
                      {can("REJECTED") && (
                        <Button size="small" color="error" startIcon={<CancelRounded />} disabled={busy} onClick={() => void reject(tender)}>
                          {t("Rechazar")}
                        </Button>
                      )}
                      {can("CANCELLED") && (
                        <Button size="small" startIcon={<UndoRounded />} disabled={busy} onClick={() => void withdraw(tender)}>
                          {t("Retirar")}
                        </Button>
                      )}
                    </Box>
                  </>
                )}
              </Paper>
            );
          })}
        </Box>
      )}

      {(creating || editing) && (
        <TenderDrawer
          carrierName={carrierName}
          tender={editing}
          onClose={() => { setCreating(false); setEditing(null); }}
          onSubmit={saveTerms}
        />
      )}
    </AppCard>
  );
}
