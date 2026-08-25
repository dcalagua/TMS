import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import {
  Alert, Box, Button, Divider, Paper, Table, TableBody, TableCell, TableContainer,
  TableHead, TableRow, Typography,
} from "@mui/material";
import {
  PaidRounded, CalculateRounded, LockRounded, LockOpenRounded, EditNoteRounded,
} from "@mui/icons-material";
import type { ApiError } from "../../shared/api/httpClient";
import {
  closeTripCost, estimateTripCost, fetchTripCost, reopenTripCost, type TripCostView,
} from "../../shared/api/ratesApi";
import { describeApiError } from "../../shared/api/problemMessages";
import { AppCard, LoadingState, StatusChip, dataTableSx } from "../../shared/ui/components";
import { confirmDialog, notifyError, notifySuccess } from "../../lib/ui";
import { enumLabel } from "../../lib/enums";
import { t } from "../../lib/i18n";
import { fmtDateTime, fmtDecimal, fmtMoney } from "../../lib/locale";
import { ActualCostDrawer } from "./ActualCostDrawer";

interface TripCostCardProps {
  companyId: string;
  tripId: string;
  canManage: boolean;
}

/**
 * Lo que cuesta el envío: el estimado que salió del tarifario, el real que alguien registró y la
 * diferencia entre los dos.
 *
 * El estimado nunca se calcula aquí. Se pide al backend, que aplica el tarifario vigente y
 * devuelve el desglose por componente con el motivo de cada uno; recalcularlo en el navegador
 * sería una segunda tarifa que acabaría discrepando de la que se factura.
 *
 * Cerrar el costo es explícito y reversible por su propio endpoint. Es la frontera entre "esto
 * todavía se está cocinando" y "esto es lo que vale ese viaje", y esa frontera merece una acción
 * deliberada en lugar de deducirse de que el viaje terminó.
 */
export function TripCostCard({ companyId, tripId, canManage }: TripCostCardProps) {
  const queryClient = useQueryClient();
  const queryKey = ["trip-cost", companyId, tripId];
  const [showActual, setShowActual] = useState(false);
  const [busy, setBusy] = useState(false);

  const costQuery = useQuery({
    queryKey,
    queryFn: ({ signal }) => fetchTripCost(companyId, tripId, signal),
    retry: false,
  });

  const applyCost = (next: TripCostView) => queryClient.setQueryData(queryKey, next);

  async function run(action: () => Promise<TripCostView>, message: string) {
    setBusy(true);
    try {
      applyCost(await action());
      notifySuccess(message);
    } catch (error) {
      notifyError(t("No se pudo completar la acción"), describeApiError(error as ApiError));
    } finally {
      setBusy(false);
    }
  }

  async function close() {
    const confirmed = await confirmDialog({
      title: t("¿Cerrar el costo?"),
      text: t("El costo queda fijado. Se puede reabrir, pero deja de cambiar solo."),
      confirmLabel: t("Cerrar costo"),
    });
    if (confirmed) await run(() => closeTripCost(companyId, tripId), t("Costo cerrado"));
  }

  const cost = costQuery.data;

  return (
    <AppCard
      title={
        <Box sx={{ display: "flex", alignItems: "center", gap: 1 }}>
          <PaidRounded sx={{ fontSize: 19, color: "text.disabled" }} />
          {t("Costo del viaje")}
          {cost?.closed && <StatusChip label={t("Cerrado")} tone="done" />}
        </Box>
      }
      actions={canManage && cost && (
        <Box sx={{ display: "flex", gap: 0.75, flexWrap: "wrap" }}>
          {!cost.closed && (
            <>
              <Button
                size="small" startIcon={<CalculateRounded />} disabled={busy}
                onClick={() => void run(() => estimateTripCost(companyId, tripId), t("Costo estimado"))}
              >
                {t("Estimar")}
              </Button>
              <Button size="small" startIcon={<EditNoteRounded />} onClick={() => setShowActual(true)}>
                {t("Costo real")}
              </Button>
              <Button size="small" startIcon={<LockRounded />} disabled={busy} onClick={() => void close()}>
                {t("Cerrar")}
              </Button>
            </>
          )}
          {cost.closed && (
            <Button
              size="small" startIcon={<LockOpenRounded />} disabled={busy}
              onClick={() => void run(() => reopenTripCost(companyId, tripId), t("Costo reabierto"))}
            >
              {t("Reabrir")}
            </Button>
          )}
        </Box>
      )}
    >
      {costQuery.isPending ? (
        <LoadingState minHeight={120} />
      ) : costQuery.isError ? (
        <Alert severity="error">{describeApiError(costQuery.error as ApiError)}</Alert>
      ) : !cost?.priced ? (
        // 200 y no 404: un viaje que nadie ha costeado no es un error, es un viaje sin costear.
        <Alert severity="info">
          {t("Este viaje todavía no está costeado. Pulsa «Estimar» para aplicarle el tarifario vigente.")}
        </Alert>
      ) : (
        <>
          <Box sx={{
            display: "grid", gap: 2, mb: 2,
            gridTemplateColumns: { xs: "1fr", sm: "repeat(3, minmax(0, 1fr))" },
          }}>
            {[
              { label: t("Estimado"), value: fmtMoney(cost.estimatedAmount, cost.currency ?? "PEN"), color: "text.primary" },
              { label: t("Real"), value: cost.actualAmount === null ? "-" : fmtMoney(cost.actualAmount, cost.currency ?? "PEN"), color: "text.primary" },
              {
                label: t("Diferencia"),
                value: cost.variance === null ? "-" : fmtMoney(cost.variance, cost.currency ?? "PEN"),
                // El signo importa más que el número: por encima del estimado es rojo.
                color: cost.variance === null ? "text.primary" : cost.variance > 0 ? "error.main" : "success.main",
              },
            ].map((item) => (
              <Box key={item.label}>
                <Typography variant="caption" color="text.secondary" sx={{ textTransform: "uppercase", fontWeight: 700, letterSpacing: ".06em" }}>
                  {item.label}
                </Typography>
                <Typography sx={{ fontWeight: 800, fontSize: "1.25rem", fontVariantNumeric: "tabular-nums", color: item.color }}>
                  {item.value}
                </Typography>
              </Box>
            ))}
          </Box>

          {cost.rateCardCode && (
            <Typography variant="caption" color="text.secondary" sx={{ display: "block", mb: 1.5 }}>
              {t("Tarifario")}: {cost.rateCardCode} · {cost.rateCardName}
              {cost.estimatedAt && ` · ${fmtDateTime(cost.estimatedAt)}`}
            </Typography>
          )}

          {!cost.estimateComplete && (
            <Alert severity="warning" sx={{ mb: 2 }}>
              {t("El estimado está incompleto: algún componente del tarifario no se pudo calcular.")}
            </Alert>
          )}

          {cost.components.length > 0 && (
            <TableContainer component={Paper} variant="outlined">
              <Table size="small" sx={dataTableSx}>
                <TableHead>
                  <TableRow>
                    <TableCell>{t("Componente")}</TableCell>
                    <TableCell className="numeric-col">{t("Tarifa")}</TableCell>
                    <TableCell className="numeric-col">{t("Cantidad")}</TableCell>
                    <TableCell>{t("Origen")}</TableCell>
                    <TableCell className="numeric-col">{t("Importe")}</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {cost.components.map((component, index) => (
                    <TableRow key={`${component.component}-${index}`}>
                      <TableCell>
                        <Typography variant="body2" sx={{ fontWeight: 600 }}>
                          {enumLabel("rateComponent", component.component)}
                        </Typography>
                        {/* El motivo explica por qué un componente vale cero o no se calculó: sin
                            él, una fila en blanco parece un fallo del tarifario. */}
                        {component.reason && (
                          <Typography variant="caption" color="text.secondary">
                            {enumLabel("costComponentReason", component.reason)}
                          </Typography>
                        )}
                      </TableCell>
                      <TableCell className="numeric-col">{component.rate === null ? "-" : fmtDecimal(component.rate)}</TableCell>
                      <TableCell className="numeric-col">{component.quantity === null ? "-" : fmtDecimal(component.quantity)}</TableCell>
                      <TableCell>
                        {component.quantitySource ? enumLabel("costQuantitySource", component.quantitySource) : "-"}
                      </TableCell>
                      <TableCell className="numeric-col">{fmtMoney(component.amount, cost.currency ?? "PEN")}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </TableContainer>
          )}

          {(cost.actualReference || cost.actualNotes) && (
            <>
              <Divider sx={{ my: 2 }} />
              <Typography variant="caption" color="text.secondary" sx={{ display: "block" }}>
                {[cost.actualReference, cost.actualNotes].filter(Boolean).join(" · ")}
              </Typography>
            </>
          )}
        </>
      )}

      {showActual && cost && (
        <ActualCostDrawer
          companyId={companyId}
          tripId={tripId}
          cost={cost}
          onClose={() => setShowActual(false)}
          onSaved={(next) => { applyCost(next); setShowActual(false); }}
        />
      )}
    </AppCard>
  );
}
