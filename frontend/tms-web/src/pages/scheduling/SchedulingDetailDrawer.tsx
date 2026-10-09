import { useQuery } from "@tanstack/react-query";
import type { ReactNode } from "react";
import { Box, Button, Tooltip, Typography } from "@mui/material";
import {
  CheckCircleRounded, FactCheckRounded, OpenInNewRounded, PanToolRounded, LockOpenRounded,
} from "@mui/icons-material";
import { useNavigate } from "react-router-dom";
import type { ApiError } from "../../shared/api/httpClient";
import { describeApiError } from "../../shared/api/problemMessages";
import {
  fetchOrderHolds, fetchOrderScheduling, isReleasable, releaseOrderHold, type OrderHold, type SchedulingRow,
} from "../../shared/api/schedulingApi";
import { FormDrawer, StatusChip } from "../../shared/ui/components";
import { notifyError, notifySuccess, promptDialog } from "../../lib/ui";
import { enumLabel } from "../../lib/enums";
import { t } from "../../lib/i18n";
import { fmtDate, fmtDateTime } from "../../lib/locale";
import { EligibilityVerdict, ReasonList } from "./schedulingUi";
import { R, T } from "../../theme";

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
  const releasable = row ? isReleasable(row) : false;
  // Por qué no se puede liberar, dicho en el propio botón: oculto, el operador no sabía si la
  // acción existía; deshabilitado y explicado, sabe qué tiene que resolver.
  const releaseBlockedBy = !row ? "" : row.eligibility === "BLOCKED"
    ? t("Bloqueado: resuelve los motivos de arriba para poder liberarlo.")
    : row.status === "READY_FOR_PLANNING"
      ? t("Ya está liberado para planificar.")
      : t("Solo se libera un pedido «No listo».");

  const section = (title: string, children: ReactNode) => (
    <Box component="section" aria-label={title} sx={{ display: "grid", gap: 1.25 }}>
      <Typography sx={{ fontSize: T.micro, fontWeight: 700, letterSpacing: ".08em", textTransform: "uppercase", color: "text.secondary" }}>
        {title}
      </Typography>
      {children}
    </Box>
  );

  return (
    <FormDrawer
      open
      size="md"
      icon={<FactCheckRounded />}
      title={t("Razones de elegibilidad")}
      subtitle={row ? [row.orderNumber, row.customerName ?? row.customerReference].filter(Boolean).join(" · ") : undefined}
      onClose={onClose}
      loading={rowQuery.isPending}
      footer={row && (
        <>
          <Button variant="outlined" color="inherit" startIcon={<OpenInNewRounded />}
            onClick={() => navigate(`/orders?orderNumber=${encodeURIComponent(row.orderNumber)}`)}>
            {t("Ir al pedido")}
          </Button>
          <Box sx={{ flex: 1 }} />
          {canManageHolds && row.status !== "CANCELLED" && row.status !== "DELIVERED" && (
            <Button variant="outlined" color="inherit" startIcon={<PanToolRounded />} onClick={() => onPlaceHold(row)}>
              {t("Retener")}
            </Button>
          )}
          {canRelease && (
            <Tooltip title={releasable ? "" : releaseBlockedBy}>
              {/* El `span` porque un botón deshabilitado no emite los eventos que el tooltip escucha. */}
              <span>
                <Button variant="contained" startIcon={<CheckCircleRounded />} disabled={!releasable}
                  onClick={() => onRelease(row)}>
                  {t("Liberar")}
                </Button>
              </span>
            </Tooltip>
          )}
        </>
      )}
    >
      {rowQuery.isError && (
        <Typography color="error">{describeApiError(rowQuery.error as ApiError)}</Typography>
      )}
      {row && (
        <Box sx={{ display: "grid", gap: 3 }}>
          <EligibilityVerdict
            eligibility={row.eligibility}
            blockingCount={row.reasons.filter((r) => r.severity === "BLOCKED").length}
            warningCount={row.reasons.filter((r) => r.severity === "WARNING").length}
            status={<StatusChip label={enumLabel("orderStatus", row.status)} tone="neutral" />}
          />

          {section(t("Motivos"), <ReasonList reasons={row.reasons} />)}

          {section(t("Retenciones"), holds.length === 0 ? (
            <Typography variant="body2" color="text.secondary">{t("El pedido no tiene retenciones.")}</Typography>
          ) : (
            <Box sx={{ display: "grid", gap: 1 }}>
              {holds.map((hold) => (
                <Box key={hold.id} sx={{
                  p: 1.5, display: "grid", gap: 0.75, border: "1px solid", borderColor: "divider", borderRadius: `${R.md}px`,
                  opacity: hold.active ? 1 : 0.75,
                }}>
                  <Box sx={{ display: "flex", gap: 1, alignItems: "center", flexWrap: "wrap" }}>
                    <StatusChip label={enumLabel("holdType", hold.holdType)}
                      tone={hold.active ? (hold.blocking ? "overdue" : "inProgress") : "cancelled"} />
                    <Typography variant="caption" color="text.secondary">
                      {hold.active
                        ? (hold.blocking ? t("Activa · bloqueante") : t("Activa · no bloqueante"))
                        : t("Levantada el {{date}}", { date: fmtDateTime(hold.releasedAt) })}
                    </Typography>
                    {hold.reasonCode && (
                      <Typography variant="caption" sx={{ fontFamily: MONO, color: "text.secondary" }}>{hold.reasonCode}</Typography>
                    )}
                  </Box>
                  <Typography variant="body2" sx={{ fontWeight: 600 }}>{hold.reason}</Typography>
                  <Box sx={{ display: "flex", alignItems: "center", gap: 1, flexWrap: "wrap" }}>
                    <Typography variant="caption" color="text.secondary" sx={{ flex: 1, minWidth: 0 }}>
                      {t("Aplicada el {{date}}", { date: fmtDateTime(hold.createdAt) })}
                      {hold.releaseReason ? ` · ${t("Motivo al levantarla")}: ${hold.releaseReason}` : ""}
                    </Typography>
                    {hold.active && canManageHolds && (
                      <Button size="small" variant="outlined" startIcon={<LockOpenRounded />} onClick={() => void lift(hold)}>
                        {t("Levantar retención")}
                      </Button>
                    )}
                  </Box>
                </Box>
              ))}
            </Box>
          ))}

          {section(t("Datos de programación"), (
            <Box sx={{
              display: "grid", gridTemplateColumns: { xs: "1fr", sm: "1fr 1fr" },
              border: "1px solid", borderColor: "divider", borderRadius: `${R.md}px`, overflow: "hidden",
              "& > div": { px: 1.75, py: 1.25, borderColor: "divider", borderStyle: "solid", borderWidth: 0, borderTopWidth: "1px" },
              "& > div:nth-of-type(-n+2)": { borderTopWidth: { xs: 0, sm: 0 } },
              "& > div:nth-of-type(2)": { borderTopWidth: { xs: "1px", sm: 0 } },
              "& > div:nth-of-type(even)": { borderLeftWidth: { xs: 0, sm: "1px" } },
            }}>
              {[
                [t("Fecha de despacho"), fmtDate(row.scheduledDispatchDate)],
                [t("Corte de liberación"), row.releaseDeadline
                  ? `${fmtDateTime(row.releaseDeadline)}${row.releaseDeadlineEndOfDay ? ` · ${t("fin del día")}` : ""}`
                  : t("Sin calendario")],
                [t("Origen"), [row.originCode, row.originName].filter(Boolean).join(" · ") || "-"],
                [t("Destino"), row.destinationName ?? row.destinationCode ?? "-"],
                [t("Ruta"), row.routeCode
                  ? `${row.routeCode}${row.routeName ? ` · ${row.routeName}` : ""}`
                  : `${enumLabel("routeResolution", row.routeResolution)}${row.routeCandidates.length > 1
                    ? ` (${row.routeCandidates.join(", ")})` : ""}`],
                [t("Resolución de ruta"), enumLabel("routeResolution", row.routeResolution)],
                [t("Frecuencia del destino"), row.locationFrequencyCode ?? "-"],
                [t("Frecuencia de la ruta"), row.routeFrequencyCode ?? "-"],
              ].map(([label, value]) => (
                <Box key={label}>
                  <Typography sx={{ fontSize: T.micro + 0.5, color: "text.secondary" }}>{label}</Typography>
                  <Typography variant="body2" sx={{ fontWeight: 600 }}>{value}</Typography>
                </Box>
              ))}
            </Box>
          ))}
        </Box>
      )}
    </FormDrawer>
  );
}

const MONO = "ui-monospace, SFMono-Regular, Menlo, Consolas, monospace";
