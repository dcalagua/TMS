import { useMutation, useQuery } from "@tanstack/react-query";
import { useState } from "react";
import {
  Alert, Box, Button, Chip, Paper, Table, TableBody, TableCell, TableContainer,
  TableHead, TableRow, ToggleButton, ToggleButtonGroup, Typography,
} from "@mui/material";
import { AutoFixHighRounded } from "@mui/icons-material";
import {
  applyAutoPlan, previewAutoPlan, PLANNING_ENGINES,
  type AutoPlanView, type PlanningEngineName, type UnplannedOrderView,
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
  // El motor elegido forma parte de la clave: previsualizar con el otro es otra propuesta, no un
  // refresco de la misma, y es exactamente así como se comparan los dos sobre el mismo día.
  const [engine, setEngine] = useState<PlanningEngineName>("HEURISTIC_V1");

  const preview = useQuery({
    queryKey: ["auto-plan-preview", companyId, runId, engine],
    queryFn: ({ signal }) => previewAutoPlan(companyId, runId, engine, signal),
    // Una propuesta es una foto de la cola: volver a pedirla mientras el planificador la lee
    // cambiaría justo aquello sobre lo que está decidiendo.
    staleTime: Infinity,
    refetchOnWindowFocus: false,
  });

  const apply = useMutation({
    mutationFn: () => applyAutoPlan(companyId, runId, { version: runVersion, engine }),
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
      {/* Elegir el motor recarga la previsualización. Comparar los dos sobre el mismo día es el
          punto: el que se aplica es el que está seleccionado, así que la propuesta que el
          planificador está mirando es la que se va a escribir. */}
      <SectionHeader title={t("Motor de planificación")} />
      <ToggleButtonGroup
        exclusive size="small" value={engine} sx={{ mb: 1 }}
        onChange={(_, next: PlanningEngineName | null) => { if (next) setEngine(next); }}
        aria-label={t("Motor de planificación")}
      >
        {PLANNING_ENGINES.map((name) => (
          <ToggleButton key={name} value={name}>{name}</ToggleButton>
        ))}
      </ToggleButtonGroup>
      <Typography variant="caption" color="text.secondary" sx={{ display: "block", mb: 3 }}>
        {engine === "HEURISTIC_V1"
          ? t("Agrupa por corredor y llena la unidad más grande disponible. No mira distancias ni jornada.")
          : t("Además ordena las paradas por cercanía y descarta viajes que no caben en la jornada.")}
      </Typography>

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
  // PLANNING_V2 los produce. El primero se resuelve con una salida más temprana, una jornada más
  // larga o paradas más cercanas — nunca con otro camión, que es justo lo que "sin capacidad"
  // haría buscar.
  EXCEEDS_SHIFT: "El viaje no cabe en la jornada. Adelanta la salida, amplía la jornada o reparte las paradas.",
  FULLY_ALLOCATED: "Ya está entero en viajes: no queda nada por planificar.",
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

      {plan.kpis.trips > 0 && (
        <>
          <SectionHeader title={t("Indicadores de la propuesta")} />
          <Paper variant="outlined" sx={{ p: 2, mb: 1.5, display: "flex", flexWrap: "wrap", gap: 2, justifyContent: "space-around" }}>
            {stat(t("Unidades usadas"), plan.kpis.vehicles)}
            {stat(t("Kilómetros"), Math.round(plan.kpis.totalDistanceKm))}
            {stat(t("Minutos"), plan.kpis.totalDurationMinutes)}
            {stat(t("Pedidos con retraso"), plan.kpis.lateOrders)}
          </Paper>
          <Box sx={{ display: "flex", flexWrap: "wrap", gap: 1, mb: 1.5 }}>
            {plan.kpis.weightUtilizationPercent !== null && (
              <Chip size="small" variant="outlined"
                label={t("Peso {{p}}%", { p: plan.kpis.weightUtilizationPercent })} />
            )}
            {plan.kpis.volumeUtilizationPercent !== null && (
              <Chip size="small" variant="outlined"
                label={t("Volumen {{p}}%", { p: plan.kpis.volumeUtilizationPercent })} />
            )}
            {plan.kpis.palletUtilizationPercent !== null && (
              <Chip size="small" variant="outlined"
                label={t("Pallets {{p}}%", { p: plan.kpis.palletUtilizationPercent })} />
            )}
            {plan.kpis.distanceEstimated && (
              <Chip size="small" color="warning" variant="outlined" label={t("Distancias estimadas")} />
            )}
          </Box>
          {/* El coste es la única cifra que falta, y se dice en voz alta en vez de mostrar un cero
              que alguien compararía entre motores. */}
          <Alert severity="info" variant="outlined" sx={{ mb: 3 }}>
            {t("El coste todavía no se calcula sobre una propuesta: requiere tarificar un viaje que aún no existe.")}
          </Alert>
        </>
      )}

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
