import { useQuery } from "@tanstack/react-query";
import { Box, Button, Paper, Typography } from "@mui/material";
import { FactCheckRounded, OpenInNewRounded, PanToolRounded, LockOpenRounded } from "@mui/icons-material";
import { useNavigate } from "react-router-dom";
import type { ApiError } from "../../shared/api/httpClient";
import { describeApiError } from "../../shared/api/problemMessages";
import {
  fetchOrderHolds, fetchOrderScheduling, releaseOrderHold, type OrderHold, type SchedulingRow,
} from "../../shared/api/schedulingApi";
import { DetailGrid, DetailItem, FormDrawer, SectionHeader, StatusChip } from "../../shared/ui/components";
import { notifyError, notifySuccess, promptDialog } from "../../lib/ui";
import { enumLabel } from "../../lib/enums";
import { t } from "../../lib/i18n";
import { fmtDate, fmtDateTime } from "../../lib/locale";
import { EligibilityChip, ReasonList } from "./schedulingUi";

/**
 * "Ver razones": el panel lateral de una fila. La elegibilidad con cada motivo explicado, las
 * retenciones activas y levantadas, y los atajos al pedido y a la liberación.
 *
 * Pide la fila de nuevo al abrirse en lugar de reutilizar la de la tabla: la elegibilidad se
 * deriva al leer, y un panel que muestra la de hace cinco minutos puede contradecir al botón de
 * liberar que tiene justo debajo.
 */
export function SchedulingDetailDrawer({
  companyId, orderId, canManageHolds, canRelease, onClose, onChanged, onPlaceHold, onRelease,
}: {
  companyId: string;
  orderId: string;
  canManageHolds: boolean;
  canRelease: boolean;
  onClose: () => void;
  onChanged: () => void;
  onPlaceHold: (row: SchedulingRow) => void;
  onRelease: (row: SchedulingRow) => void;
}) {
  const navigate = useNavigate();
  const rowQuery = useQuery({
    queryKey: ["order-scheduling", companyId, orderId],
    queryFn: ({ signal }) => fetchOrderScheduling(companyId, orderId, signal),
  });
  const holdsQuery = useQuery({
    queryKey: ["order-holds", companyId, orderId],
    queryFn: ({ signal }) => fetchOrderHolds(companyId, orderId, signal),
  });

  const row = rowQuery.data;

  async function lift(hold: OrderHold) {
    const reason = await promptDialog({
      title: t("¿Levantar la retención?"),
      text: t("Queda registrado quién la levantó y por qué."),
      inputLabel: t("Motivo"),
      required: true,
      maxLength: 500,
      confirmLabel: t("Levantar retención"),
    });
    if (reason === null) return;
    try {
      await releaseOrderHold(companyId, orderId, hold.id, reason, hold.version);
      notifySuccess(t("Retención levantada"), row?.orderNumber);
      void rowQuery.refetch();
      void holdsQuery.refetch();
      onChanged();
    } catch (error) {
      notifyError(t("No se pudo levantar la retención"), describeApiError(error as ApiError));
    }
  }

  const holds = holdsQuery.data ?? [];

  return (
    <FormDrawer
      open
      size="md"
      icon={<FactCheckRounded />}
      title={t("Razones de elegibilidad")}
      subtitle={row?.orderNumber}
      onClose={onClose}
      loading={rowQuery.isPending}
      footer={row && (
        <>
          <Button startIcon={<OpenInNewRounded />}
            onClick={() => navigate(`/orders?orderNumber=${encodeURIComponent(row.orderNumber)}`)}>
            {t("Ir al pedido")}
          </Button>
          {canManageHolds && (
            <Button startIcon={<PanToolRounded />} onClick={() => onPlaceHold(row)}>{t("Retener")}</Button>
          )}
          {canRelease && row.status === "NOT_READY" && row.eligibility !== "BLOCKED" && (
            <Button variant="contained" onClick={() => onRelease(row)}>{t("Liberar")}</Button>
          )}
        </>
      )}
    >
      {rowQuery.isError && (
        <Typography color="error">{describeApiError(rowQuery.error as ApiError)}</Typography>
      )}
      {row && (
        <Box sx={{ display: "grid", gap: 2.5 }}>
          <Box sx={{ display: "flex", gap: 1, alignItems: "center", flexWrap: "wrap" }}>
            <EligibilityChip eligibility={row.eligibility} />
            <StatusChip label={enumLabel("orderStatus", row.status)} tone="neutral" />
          </Box>
          <DetailGrid>
            <DetailItem label={t("Cliente")} value={row.customerName ?? row.customerReference ?? "-"} />
            <DetailItem label={t("Fecha de despacho")} value={fmtDate(row.scheduledDispatchDate)} />
            <DetailItem label={t("Origen")} value={row.originName ?? row.originCode ?? "-"} />
            <DetailItem label={t("Destino")} value={row.destinationName ?? row.destinationCode ?? "-"} />
            <DetailItem label={t("Ruta")} value={row.routeCode
              ? `${row.routeCode}${row.routeName ? ` · ${row.routeName}` : ""}`
              : `${enumLabel("routeResolution", row.routeResolution)}${row.routeCandidates.length > 1
                ? ` (${row.routeCandidates.join(", ")})` : ""}`} />
            <DetailItem label={t("Corte de liberación")} value={row.releaseDeadline
              ? `${fmtDateTime(row.releaseDeadline)}${row.releaseDeadlineEndOfDay ? ` · ${t("fin del día")}` : ""}`
              : t("Sin calendario")} />
            <DetailItem label={t("Frecuencia del destino")} value={row.locationFrequencyCode ?? "-"} />
            <DetailItem label={t("Frecuencia de la ruta")} value={row.routeFrequencyCode ?? "-"} />
          </DetailGrid>

          <Box>
            <SectionHeader title={t("Motivos")} />
            <ReasonList reasons={row.reasons} />
          </Box>

          <Box>
            <SectionHeader title={t("Retenciones")} />
            {holds.length === 0 ? (
              <Typography variant="body2" color="text.secondary">{t("El pedido no tiene retenciones.")}</Typography>
            ) : (
              <Box sx={{ display: "grid", gap: 1 }}>
                {holds.map((hold) => (
                  <Paper key={hold.id} variant="outlined" sx={{ p: 1.25, display: "grid", gap: 0.5 }}>
                    <Box sx={{ display: "flex", gap: 1, alignItems: "center", flexWrap: "wrap" }}>
                      <StatusChip label={enumLabel("holdType", hold.holdType)}
                        tone={hold.active ? (hold.blocking ? "overdue" : "inProgress") : "cancelled"} />
                      <Typography variant="caption" color="text.secondary">
                        {hold.active
                          ? (hold.blocking ? t("Activa · bloqueante") : t("Activa · no bloqueante"))
                          : t("Levantada el {{date}}", { date: fmtDateTime(hold.releasedAt) })}
                      </Typography>
                      {hold.reasonCode && <Typography variant="caption">{hold.reasonCode}</Typography>}
                    </Box>
                    <Typography variant="body2">{hold.reason}</Typography>
                    <Typography variant="caption" color="text.secondary">
                      {t("Aplicada el {{date}}", { date: fmtDateTime(hold.createdAt) })}
                      {hold.releaseReason ? ` · ${t("Motivo al levantarla")}: ${hold.releaseReason}` : ""}
                    </Typography>
                    {hold.active && canManageHolds && (
                      <Box>
                        <Button size="small" startIcon={<LockOpenRounded />} onClick={() => void lift(hold)}>
                          {t("Levantar retención")}
                        </Button>
                      </Box>
                    )}
                  </Paper>
                ))}
              </Box>
            )}
          </Box>
        </Box>
      )}
    </FormDrawer>
  );
}
