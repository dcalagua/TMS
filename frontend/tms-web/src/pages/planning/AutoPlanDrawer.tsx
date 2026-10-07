import { useMutation, useQuery } from "@tanstack/react-query";
import { useState } from "react";
import {
  Alert, Box, Button, Chip, Paper, Table, TableBody, TableCell, TableContainer,
  TableHead, TableRow, Typography,
} from "@mui/material";
import { AutoFixHighRounded } from "@mui/icons-material";
import {
  applyAutoPlan, previewAutoPlan, PLANNING_ENGINES,
  type AutoPlanView, type PlanningEngineName, type UnplannedOrderView,
} from "../../shared/api/planningApi";
import type { ApiError } from "../../shared/api/httpClient";
import { describeApiError } from "../../shared/api/problemMessages";
import { FormDrawer, OptionCard, dataTableSx } from "../../shared/ui/components";
import { DetailSection, KeyFacts } from "../../shared/ui/components/DetailLayout";
import { notifyError, notifySuccess } from "../../lib/ui";
import { t } from "../../lib/i18n";
import { fmtDecimal, fmtQuantity } from "../../lib/locale";

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
          <Button color="inherit" sx={{ color: "text.secondary" }} onClick={onClose}>{t("Cancelar")}</Button>
          <Button
            variant="contained" startIcon={<AutoFixHighRounded />}
            disabled={!canApply || !plan || plan.proposed.length === 0 || apply.isPending}
            onClick={() => apply.mutate()}
          >
            {apply.isPending ? t("Aplicando...") : t("Aplicar propuesta")}
          </Button>
        </>
      }
    >
      <Box sx={{ display: "grid", gap: 2.5 }}>
        {/* Elegir el motor recarga la previsualización. Comparar los dos sobre el mismo día es el
            punto: el que se aplica es el que está seleccionado, así que la propuesta que el
            planificador está mirando es la que se va a escribir. */}
        <DetailSection title={t("Motor de planificación")}>
          <Box role="radiogroup" aria-label={t("Motor de planificación")}
            sx={{ display: "grid", gap: 1, gridTemplateColumns: { xs: "1fr", sm: "repeat(2, minmax(0, 1fr))" } }}>
            {PLANNING_ENGINES.map((name) => (
              <OptionCard
                key={name}
                selected={engine === name}
                onSelect={() => setEngine(name)}
                title={name}
                description={t(ENGINE_COPY[name])}
              />
            ))}
          </Box>
        </DetailSection>

        {preview.isError && (
          <Alert severity="error">{describeApiError(preview.error as ApiError)}</Alert>
        )}
        {plan && <AutoPlanBody plan={plan} />}
      </Box>
    </FormDrawer>
  );
}

/** Lo que hace cada motor, dicho en la tarjeta con la que se elige. */
const ENGINE_COPY = {
  HEURISTIC_V1: "Agrupa por corredor y llena la unidad más grande disponible. No mira distancias ni jornada.",
  PLANNING_V2: "Además ordena las paradas por cercanía y descarta viajes que no caben en la jornada.",
} as const satisfies Record<PlanningEngineName, string>;

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

  return (
    <>
      <DetailSection title={t("Resumen")}>
        <KeyFacts columns={4} items={[
          { label: t("Pedidos evaluados"), value: fmtQuantity(plan.ordersConsidered) },
          { label: t("Unidades disponibles"), value: fmtQuantity(plan.vehiclesOffered) },
          { label: t("Viajes propuestos"), value: fmtQuantity(plan.proposed.length) },
          { label: t("Pedidos asignados"), value: fmtQuantity(plannedOrders) },
        ]} />
        <Typography variant="caption" color="text.secondary" sx={{ display: "block", mt: 0.75 }}>
          {t("Generado por {{engine}}. La misma entrada produce siempre la misma propuesta.", { engine: plan.engine })}
        </Typography>
      </DetailSection>

      {plan.kpis.trips > 0 && (
        <DetailSection title={t("Indicadores de la propuesta")}>
          <Box sx={{ display: "grid", gap: 1.5 }}>
            <KeyFacts columns={4} items={[
              { label: t("Unidades usadas"), value: fmtQuantity(plan.kpis.vehicles) },
              { label: t("Kilómetros"), value: fmtQuantity(Math.round(plan.kpis.totalDistanceKm)) },
              { label: t("Minutos"), value: fmtQuantity(plan.kpis.totalDurationMinutes) },
              { label: t("Pedidos con retraso"), value: fmtQuantity(plan.kpis.lateOrders) },
            ]} />
            <Box sx={{ display: "flex", flexWrap: "wrap", gap: 1 }}>
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
            {/* JOB 11: el coste ya se calcula, y cuando NO se puede se dice por qué en vez de
                mostrar un cero o una suma parcial que alguien compararía entre motores. */}
            {plan.kpis.pricing.totalCost !== null ? (
              <KeyFacts columns={2} items={[
                {
                  label: t("Coste estimado"),
                  value: (
                    <Typography component="span" sx={{ fontWeight: 800, fontSize: "1.25rem" }}>
                      {fmtDecimal(plan.kpis.pricing.totalCost, 2)} {plan.kpis.pricing.currency}
                    </Typography>
                  ),
                  sub: t("Sobre los acuerdos vigentes de cada transportista, con las mismas reglas que la factura."),
                  span: true,
                },
              ]} />
            ) : (
              <Alert severity="info" variant="outlined">
                {plan.kpis.pricing.reason === "MIXED_CURRENCIES"
                  ? t("Sin coste total: los acuerdos no están todos en la misma moneda, y este producto no inventa un tipo de cambio.")
                  : plan.kpis.pricing.reason === "NO_AGREEMENT_FOR_SOME_TRIP"
                    ? t("Sin coste total: {{priced}} de {{total}} viajes tienen tarifa aplicable. Un total parcial haría parecer más barato al peor plan.", {
                        priced: plan.kpis.pricing.pricedTrips, total: plan.kpis.pricing.totalTrips,
                      })
                    : t("Sin coste: la propuesta no colocó ningún viaje.")}
              </Alert>
            )}
          </Box>
        </DetailSection>
      )}

      <DetailSection title={t("Viajes propuestos")}>
        {plan.proposed.length === 0 ? (
          <Alert severity="info">
            {t("No hay nada que planificar con los pedidos y unidades de esta fecha.")}
          </Alert>
        ) : (
          <TableContainer component={Paper} variant="outlined">
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
      </DetailSection>

      <DetailSection title={t("Pedidos sin asignar")}>
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
      </DetailSection>
    </>
  );
}
