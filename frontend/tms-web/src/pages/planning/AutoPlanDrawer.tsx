import { useMutation, useQuery } from "@tanstack/react-query";
import {
  Alert, Box, Button, Chip, Paper, Table, TableBody, TableCell, TableContainer,
  TableHead, TableRow, Typography,
} from "@mui/material";
import { AutoFixHighRounded } from "@mui/icons-material";
import {
  applyAutoPlan, previewAutoPlan, type AutoPlanView, type UnplannedOrderView,
} from "../../shared/api/planningApi";
import type { ApiError } from "../../shared/api/httpClient";
import { describeApiError } from "../../shared/api/problemMessages";
import { FormDrawer, SectionHeader, dataTableSx } from "../../shared/ui/components";
import { notifyError, notifySuccess } from "../../lib/ui";
import { t } from "../../lib/i18n";
import { fmtQuantity } from "../../lib/locale";

interface AutoPlanDrawerProps {
  companyId: string;
  runId: string;
  /** La versión del plan, enviada con la escritura para que un tablero viejo no pueda planificar
   * un plan que ya se confirmó. */
  runVersion: number;
  canApply: boolean;
  onClose: () => void;
  onApplied: () => void;
}

/**
 * El paso de revisión de la planificación automática.
 *
 * Previsualizar siempre, primero. El motor es determinista y la previsualización llama al mismo
 * código que la escritura, así que lo que enseña este drawer es lo que produce aplicar — y un
 * planificador al que están a punto de crearle nueve viajes debería ver los nueve antes. No hay
 * camino de "hazlo y ya", y ese es el punto: la planificación automática propone, decide una
 * persona.
 *
 * La lista de no asignados tiene el mismo peso que la propuesta. "7 viajes creados" al lado de
 * una cola descartada en silencio es como un planificador se entera a las seis de la tarde de que
 * cuarenta pedidos no salieron.
 */
export function AutoPlanDrawer({
  companyId, runId, runVersion, canApply, onClose, onApplied,
}: AutoPlanDrawerProps) {
  const preview = useQuery({
    queryKey: ["auto-plan-preview", companyId, runId],
    queryFn: ({ signal }) => previewAutoPlan(companyId, runId, signal),
    // Una propuesta es una foto de la cola: volver a pedirla mientras el planificador la lee
    // cambiaría justo aquello sobre lo que está decidiendo.
    staleTime: Infinity,
    refetchOnWindowFocus: false,
  });

  const apply = useMutation({
    mutationFn: () => applyAutoPlan(companyId, runId, { version: runVersion }),
    onSuccess: (result) => {
      notifySuccess(
        t("Propuesta aplicada"),
        result.created.length === 1
          ? t("Se creó {{count}} viaje en borrador.", { count: result.created.length })
          : t("Se crearon {{count}} viajes en borrador.", { count: result.created.length }),
      );
      onApplied();
      onClose();
    },
    onError: (error) => notifyError(t("No se pudo aplicar la propuesta"), describeApiError(error as ApiError)),
  });

  const plan = preview.data;

  return (
    <FormDrawer
      open
      loading={preview.isPending}
      icon={<AutoFixHighRounded />}
      title={t("Planificación automática")}
      subtitle={t("Propuesta de viajes en borrador. Revisa antes de aplicar; nada se confirma automáticamente.")}
      size="lg"
      onClose={onClose}
      footer={
        <>
          <Button onClick={onClose}>{t("Cancelar")}</Button>
          <Button
            variant="contained"
            disabled={!canApply || !plan || plan.proposed.length === 0 || apply.isPending}
            onClick={() => apply.mutate()}
          >
            {apply.isPending ? t("Aplicando...") : t("Aplicar propuesta")}
          </Button>
        </>
      }
    >
      {preview.isError && (
        <Alert severity="error">{describeApiError(preview.error as ApiError)}</Alert>
      )}
      {plan && <AutoPlanBody plan={plan} />}
    </FormDrawer>
  );
}

/**
 * Cada motivo redactado como lo que el planificador puede hacer al respecto, no como lo que
 * concluyó el motor. Un mapa literal y no un switch: así, añadir un motivo al backend sin
 * traducirlo es un error de compilación en lugar de un nombre de enum crudo en la pantalla.
 */
const REASON_COPY = {
  EXCEEDS_LARGEST_VEHICLE: "Excede la capacidad de cualquier unidad disponible. Divide el pedido o incorpora una unidad mayor.",
  NO_VEHICLE_AVAILABLE: "No quedó capacidad disponible en la flota de esta fecha.",
  NO_FLEET: "No hay unidades disponibles para esta fecha.",
  TAKEN_WHILE_PLANNING: "Otro planificador asignó este pedido mientras se escribía el plan. Recarga el tablero.",
  NOT_SERVICEABLE_ON_DATE: "El destino no se atiende en esta fecha según su calendario de servicio.",
} as const satisfies Record<UnplannedOrderView["reason"], string>;

function AutoPlanBody({ plan }: { plan: AutoPlanView }) {
  const plannedOrders = plan.proposed.reduce((total, trip) => total + trip.orderNumbers.length, 0);

  const stat = (label: string, value: number) => (
    <Box sx={{ textAlign: "center", minWidth: 100 }}>
      <Typography sx={{ fontWeight: 800, fontSize: "1.5rem", lineHeight: 1.1, fontVariantNumeric: "tabular-nums" }}>
        {fmtQuantity(value)}
      </Typography>
      <Typography variant="caption" color="text.secondary" sx={{ textTransform: "uppercase", fontWeight: 700, letterSpacing: ".05em" }}>
        {label}
      </Typography>
    </Box>
  );

  return (
    <>
      <SectionHeader title={t("Resumen")} />
      <Paper variant="outlined" sx={{ p: 2, mb: 1.5, display: "flex", flexWrap: "wrap", gap: 2, justifyContent: "space-around" }}>
        {stat(t("Pedidos evaluados"), plan.ordersConsidered)}
        {stat(t("Unidades disponibles"), plan.vehiclesOffered)}
        {stat(t("Viajes propuestos"), plan.proposed.length)}
        {stat(t("Pedidos asignados"), plannedOrders)}
      </Paper>
      <Typography variant="caption" color="text.secondary" sx={{ display: "block", mb: 3 }}>
        {t("Generado por {{engine}}. La misma entrada produce siempre la misma propuesta.", { engine: plan.engine })}
      </Typography>

      <SectionHeader title={t("Viajes propuestos")} />
      {plan.proposed.length === 0 ? (
        <Alert severity="info" sx={{ mb: 3 }}>
          {t("No hay nada que planificar con los pedidos y unidades de esta fecha.")}
        </Alert>
      ) : (
        <TableContainer component={Paper} variant="outlined" sx={{ mb: 3 }}>
          <Table size="small" sx={dataTableSx}>
            <TableHead>
              <TableRow>
                <TableCell>{t("Unidad")}</TableCell>
                <TableCell className="numeric-col">{t("Paradas")}</TableCell>
                <TableCell>{t("Pedidos")}</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {plan.proposed.map((trip, index) => (
                <TableRow key={`${trip.vehicleId}-${index}`}>
                  <TableCell sx={{ fontWeight: 700 }}>{trip.vehicleCode ?? trip.vehicleId}</TableCell>
                  <TableCell className="numeric-col">{fmtQuantity(trip.stopCount)}</TableCell>
                  <TableCell>
                    <Box sx={{ display: "flex", flexWrap: "wrap", gap: 0.5 }}>
                      {trip.orderNumbers.map((number) => (
                        <Chip key={number} size="small" variant="outlined" label={number} />
                      ))}
                    </Box>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      )}

      <SectionHeader title={t("Pedidos sin asignar")} />
      {plan.unplanned.length === 0 ? (
        <Alert severity="success">{t("Todos los pedidos evaluados quedaron asignados.")}</Alert>
      ) : (
        <>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>
            {t("Estos pedidos siguen disponibles en el pool. Decide qué hacer con cada uno.")}
          </Typography>
          <TableContainer component={Paper} variant="outlined">
            <Table size="small" sx={dataTableSx}>
              <TableHead>
                <TableRow>
                  <TableCell>{t("Pedido")}</TableCell>
                  <TableCell>{t("Motivo")}</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {plan.unplanned.map((order) => (
                  <TableRow key={order.orderId}>
                    <TableCell sx={{ fontWeight: 700 }}>{order.orderNumber ?? order.orderId}</TableCell>
                    <TableCell>{t(REASON_COPY[order.reason])}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableContainer>
        </>
      )}
    </>
  );
}
