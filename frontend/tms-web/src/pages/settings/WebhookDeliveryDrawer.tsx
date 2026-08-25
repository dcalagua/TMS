import { useQuery } from "@tanstack/react-query";
import {
  Alert, Box, Chip, Paper, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, Typography,
} from "@mui/material";
import { SendRounded } from "@mui/icons-material";
import type { ApiError } from "../../shared/api/httpClient";
import { fetchWebhookDelivery } from "../../shared/api/integrationsApi";
import { describeApiError } from "../../shared/api/problemMessages";
import {
  DetailGrid, DetailItem, FormDrawer, SectionHeader, StatusChip, dataTableSx,
} from "../../shared/ui/components";
import type { StatusTone } from "../../theme";
import { t } from "../../lib/i18n";
import { fmtDateTime, fmtQuantity } from "../../lib/locale";

interface WebhookDeliveryDrawerProps {
  companyId: string;
  deliveryId: string;
  onClose: () => void;
}

const OUTCOME_TONE: Record<string, StatusTone> = {
  DELIVERED: "done",
  RETRYABLE_FAILURE: "inProgress",
  PERMANENT_FAILURE: "overdue",
};

/**
 * Una entrega, intento por intento, con el cuerpo exacto que se mandó.
 *
 * Es la pantalla desde la que se zanja una discusión con un socio: "no nos llegó" contra "sí os
 * lo mandamos". Por eso el payload se enseña byte a byte, tal y como lo envió cada intento, y no
 * reformateado — un JSON embellecido ya no es lo que viajó.
 *
 * Un intento sin código de estado no es un cero: es una llamada que nunca produjo respuesta —un
 * timeout, una conexión rechazada, un DNS malo— y se dice así.
 */
export function WebhookDeliveryDrawer({ companyId, deliveryId, onClose }: WebhookDeliveryDrawerProps) {
  const detailQuery = useQuery({
    queryKey: ["webhook-delivery", companyId, deliveryId],
    queryFn: ({ signal }) => fetchWebhookDelivery(companyId, deliveryId, signal),
  });

  const detail = detailQuery.data;

  return (
    <FormDrawer
      open
      loading={detailQuery.isPending}
      icon={<SendRounded />}
      title={t("Entrega")}
      subtitle={detail?.delivery.eventType}
      size="lg"
      onClose={onClose}
    >
      {detailQuery.isError && (
        <Alert severity="error">{describeApiError(detailQuery.error as ApiError)}</Alert>
      )}

      {detail && (
        <>
          <SectionHeader title={t("Resumen")} />
          <DetailGrid columns={2}>
            <DetailItem label={t("Suscripción")} value={detail.delivery.subscriptionName} />
            <DetailItem label={t("Estado")} value={detail.delivery.status} />
            {/* El id de evento es el valor con el que el receptor deduplica: es estable entre
                intentos y reenvíos, y es lo primero que se le pide en una discusión. */}
            <DetailItem label={t("ID de evento")} value={
              <Typography component="code" variant="body2" sx={{ fontFamily: "monospace", wordBreak: "break-all" }}>
                {detail.delivery.eventId}
              </Typography>
            } />
            <DetailItem label={t("Ocurrió")} value={fmtDateTime(detail.delivery.occurredAt)} />
            <DetailItem label={t("Intentos")} value={fmtQuantity(detail.delivery.attemptCount)} />
            <DetailItem
              label={t("Cerrada")}
              value={detail.delivery.completedAt ? fmtDateTime(detail.delivery.completedAt) : null}
            />
          </DetailGrid>

          {detail.delivery.lastError && (
            <Alert severity="error" sx={{ mt: 2 }}>{detail.delivery.lastError}</Alert>
          )}

          <Box sx={{ mt: 3 }}>
            <SectionHeader title={t("Intentos")} />
            <TableContainer component={Paper} variant="outlined">
              <Table size="small" sx={dataTableSx}>
                <TableHead>
                  <TableRow>
                    <TableCell className="numeric-col">{t("Nº")}</TableCell>
                    <TableCell>{t("Cuándo")}</TableCell>
                    <TableCell className="numeric-col">{t("Duración")}</TableCell>
                    <TableCell className="numeric-col">{t("Código")}</TableCell>
                    <TableCell>{t("Resultado")}</TableCell>
                    <TableCell>{t("Error")}</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {detail.attempts.map((attempt) => (
                    <TableRow key={attempt.id}>
                      <TableCell className="numeric-col">{attempt.attemptNumber}</TableCell>
                      <TableCell>{fmtDateTime(attempt.attemptedAt)}</TableCell>
                      <TableCell className="numeric-col">{fmtQuantity(attempt.durationMs)} ms</TableCell>
                      <TableCell className="numeric-col">
                        {/* Sin código: la llamada nunca produjo respuesta. No es un cero. */}
                        {attempt.statusCode ?? <Chip size="small" variant="outlined" label={t("Sin respuesta")} />}
                      </TableCell>
                      <TableCell>
                        <StatusChip label={attempt.outcome} tone={OUTCOME_TONE[attempt.outcome] ?? "neutral"} />
                      </TableCell>
                      <TableCell>{attempt.error ?? "-"}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </TableContainer>
          </Box>

          <Box sx={{ mt: 3 }}>
            <SectionHeader title={t("Cuerpo enviado")} />
            <Paper
              variant="outlined"
              sx={{ p: 1.5, maxHeight: 320, overflow: "auto", bgcolor: "action.hover" }}
            >
              <Typography
                component="pre"
                sx={{ m: 0, fontFamily: "monospace", fontSize: 12, whiteSpace: "pre-wrap", wordBreak: "break-word" }}
              >
                {detail.payload}
              </Typography>
            </Paper>
            <Typography variant="caption" color="text.disabled" sx={{ display: "block", mt: 0.75 }}>
              {t("Exactamente como lo mandó cada intento, sin reformatear.")}
            </Typography>
          </Box>
        </>
      )}
    </FormDrawer>
  );
}
