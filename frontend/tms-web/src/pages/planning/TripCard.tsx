import { Box, ButtonBase, Card, LinearProgress, Typography } from "@mui/material";
import {
  AltRouteRounded, BadgeOutlined, ChevronRightRounded, LocalShippingOutlined, PersonOffOutlined,
} from "@mui/icons-material";
import type { CapacityDimension, TripView } from "../../shared/api/planningApi";
import { StatusChip } from "../../shared/ui/components";
import { TRIP_STATUS_TONE } from "../../shared/ui/statusTones";
import { enumLabel } from "../../lib/enums";
import { t } from "../../lib/i18n";
import { fmtAreaM2, fmtDecimal, fmtMetres, fmtQuantity, fmtTime, fmtVolumeM3, fmtWeightKg } from "../../lib/locale";
import { capacityTone } from "../../shared/geometry/loadGeometry";
import { R, T } from "../../theme";

interface TripCardProps {
  trip: TripView;
  onOpen: () => void;
}

/**
 * Una dimensión de capacidad en miniatura: etiqueta y % arriba, barra fina, cantidad debajo. El tono
 * (ok / cerca / excedida) sale de `capacityTone`, el mismo que usa `CapacityBar`. `binding` marca la
 * dimensión que llena el camión (`bindingDimension` del backend).
 */
function MiniCapacity({ label, dimension, amount, binding = false }: {
  label: string; dimension: CapacityDimension; amount: string; binding?: boolean;
}) {
  const { percentUsed, exceeded, unlimited } = dimension;
  const pct = percentUsed === null ? 0 : Math.min(100, percentUsed);
  const tone = capacityTone(dimension);
  const color = tone === "over" ? "error" : tone === "near" ? "warning" : "primary";
  return (
    <Box sx={{ minWidth: 0 }}>
      <Box sx={{ display: "flex", alignItems: "baseline", justifyContent: "space-between", gap: 0.5 }}>
        <Typography sx={{
          fontSize: T.micro + 0.5, fontWeight: binding ? 800 : 700, color: binding ? "text.primary" : "text.secondary",
          textDecoration: binding ? "underline" : "none", textUnderlineOffset: 3,
        }}>
          {label}
        </Typography>
        <Typography sx={{ fontSize: T.micro + 0.5, fontWeight: 800, color: exceeded ? "error.main" : "text.primary", fontVariantNumeric: "tabular-nums" }}>
          {unlimited || percentUsed === null ? "—" : `${Math.round(percentUsed)} %`}
        </Typography>
      </Box>
      <LinearProgress
        variant="determinate" value={pct} color={color}
        aria-label={binding ? `${label} · ${t("lo que llena el camión")}` : label}
        sx={{ height: 6, borderRadius: 3, my: 0.5, bgcolor: "action.hover", "& .MuiLinearProgress-bar": { borderRadius: 3 } }}
      />
      <Typography noWrap sx={{ fontSize: T.micro, color: "text.secondary", fontVariantNumeric: "tabular-nums" }}>{amount}</Typography>
    </Box>
  );
}

/**
 * Piso y alto (V62) aparecen en la tarjeta solo cuando dicen algo: el vehículo declara la medida o
 * algún pedido del viaje se midió. Un camión de pallets sin geometría sigue viendo sus tres barras.
 */
function shows(dimension: CapacityDimension | null | undefined): dimension is CapacityDimension {
  return dimension != null && (!dimension.unlimited || Number(dimension.used) > 0);
}

/**
 * Un envío en el tablero: número de viaje y de envío, estado, vehículo y conductor, salida,
 * pedidos y paradas, y las tres dimensiones de capacidad tal como las calculó el backend.
 *
 * La tarjeta entera es el control que abre el detalle: en un tablero de una docena de viajes,
 * cazar un botoncito en cada pie es más lento que pulsar la tarjeta que el planificador ya está
 * leyendo. Es un botón de verdad, con nombre accesible propio, para quien navega con teclado.
 */
export function TripCard({ trip, onOpen }: TripCardProps) {
  const overCapacity = !trip.capacity.withinCapacity;
  const binding = trip.capacity.bindingDimension ?? null;
  // Solo la hora: la fecha es la del plan, que el tablero ya dice en su cabecera.
  const departure = trip.plannedDepartureAt ? fmtTime(trip.plannedDepartureAt) : t("Sin definir");

  return (
    <Card
      component="article"
      variant="outlined"
      sx={{
        height: "100%", borderRadius: `${R.lg}px`, overflow: "hidden",
        // El borde rojo es un aviso, no el veredicto: quien decide si cabe es el backend, y esto
        // solo repite lo que ya dijo en `withinCapacity`.
        ...(overCapacity ? { border: "1.5px solid", borderColor: "error.main" } : {}),
        transition: "box-shadow .15s, border-color .15s",
        "&:hover": { boxShadow: 3, borderColor: overCapacity ? "error.main" : "primary.main" },
      }}
    >
      <ButtonBase
        onClick={onOpen}
        aria-label={t("Abrir el viaje {{number}}", { number: trip.tripNumber })}
        sx={{ display: "flex", flexDirection: "column", alignItems: "stretch", textAlign: "left", width: "100%", height: "100%" }}
      >
        <Box sx={{ display: "flex", alignItems: "flex-start", justifyContent: "space-between", gap: 1, px: 2, pt: 1.75, pb: 1.25 }}>
          <Box sx={{ minWidth: 0 }}>
            <Typography sx={{ fontSize: 15, fontWeight: 800, lineHeight: 1.25 }}>
              {t("Viaje {{number}}", { number: trip.tripNumber })}
            </Typography>
            {/* El número de envío, no el de viaje, es lo que referencia un sistema externo, un
                manifiesto o una llamada: "viaje 3" no significa nada sin nombrar su plan. */}
            <Typography noWrap sx={{ fontSize: T.micro + 0.5, color: "text.secondary", fontVariantNumeric: "tabular-nums" }}>
              {t("Envío")} {trip.shipmentNumber}
            </Typography>
          </Box>
          <StatusChip label={enumLabel("tripStatus", trip.status)} tone={TRIP_STATUS_TONE[trip.status]} />
        </Box>

        <Box sx={{ px: 2, pb: 1.5, display: "grid", gap: 0.5 }}>
          <Box sx={{ display: "flex", alignItems: "center", gap: 1, minWidth: 0 }}>
            <LocalShippingOutlined sx={{ fontSize: 17, color: "text.secondary" }} />
            {trip.vehicleCode ? (
              <Typography variant="body2" noWrap sx={{ fontWeight: 700 }}>
                {trip.vehicleCode} · {trip.vehicleLicensePlate}
              </Typography>
            ) : (
              <Typography variant="body2" sx={{ color: "warning.main", fontWeight: 700 }}>{t("Sin vehículo asignado")}</Typography>
            )}
          </Box>
          <Typography noWrap sx={{ fontSize: T.micro + 0.5, color: "text.secondary", pl: "25px" }}>
            {trip.carrierName ?? t("Flota propia")}
            {trip.vehicleTypeCode && ` · ${trip.vehicleTypeCode}`}
          </Typography>
          <Box sx={{ display: "flex", alignItems: "center", gap: 1, minWidth: 0 }}>
            {trip.driverName
              ? <BadgeOutlined sx={{ fontSize: 17, color: "text.secondary" }} />
              : <PersonOffOutlined sx={{ fontSize: 17, color: "warning.main" }} />}
            <Typography variant="body2" noWrap sx={{
              fontWeight: trip.driverName ? 600 : 700, color: trip.driverName ? "text.primary" : "warning.main",
            }}>
              {trip.driverName ?? t("Sin conductor asignado")}
            </Typography>
          </Box>
        </Box>

        <Box component="dl" sx={{
          m: 0, px: 2, py: 1.25, display: "grid", gridTemplateColumns: "repeat(3, minmax(0, 1fr))", gap: 1,
          bgcolor: "action.hover", borderTop: "1px solid", borderBottom: "1px solid", borderColor: "divider",
        }}>
          {[
            { label: t("Salida"), value: departure },
            { label: t("Pedidos"), value: fmtQuantity(trip.orderCount) },
            { label: t("Paradas"), value: fmtQuantity(trip.stopCount) },
          ].map((row) => (
            <Box key={row.label} sx={{ minWidth: 0 }}>
              <Typography component="dt" sx={{ fontSize: T.micro - 0.5, fontWeight: 700, letterSpacing: ".05em", textTransform: "uppercase", color: "text.secondary" }}>
                {row.label}
              </Typography>
              <Typography component="dd" noWrap sx={{ m: 0, fontWeight: 800, fontVariantNumeric: "tabular-nums" }}>{row.value}</Typography>
            </Box>
          ))}
        </Box>

        <Box sx={{ px: 2, py: 1.5, display: "grid", gap: 1.5, gridTemplateColumns: "repeat(3, minmax(0, 1fr))", flexGrow: 1 }}>
          <MiniCapacity label={t("Peso")} dimension={trip.capacity.weight} amount={fmtWeightKg(trip.capacity.weight.used)}
            binding={binding === "WEIGHT"} />
          <MiniCapacity label={t("Vol.")} dimension={trip.capacity.volume} amount={fmtVolumeM3(trip.capacity.volume.used)}
            binding={binding === "VOLUME"} />
          <MiniCapacity label={t("Pal.")} dimension={trip.capacity.pallets} amount={`${fmtDecimal(trip.capacity.pallets.used)} ${t("pal.")}`}
            binding={binding === "PALLETS"} />
          {shows(trip.capacity.floor) && (
            <MiniCapacity label={t("Piso")} dimension={trip.capacity.floor} amount={fmtAreaM2(trip.capacity.floor.used)}
              binding={binding === "FLOOR"} />
          )}
          {shows(trip.capacity.height) && (
            <MiniCapacity label={t("Alto")} dimension={trip.capacity.height}
              amount={trip.capacity.height.limit == null
                ? fmtMetres(trip.capacity.height.used)
                : `${fmtMetres(trip.capacity.height.used)} / ${fmtMetres(trip.capacity.height.limit)}`}
              binding={binding === "HEIGHT"} />
          )}
        </Box>

        <Box sx={{ display: "flex", alignItems: "center", gap: 1, px: 2, py: 1.1, borderTop: "1px solid", borderColor: "divider" }}>
          <Box sx={{
            display: "inline-flex", alignItems: "center", gap: 0.5, px: 0.75, height: 22, borderRadius: "6px",
            bgcolor: "action.hover", color: trip.routeCode ? "text.primary" : "text.secondary",
          }}>
            <AltRouteRounded sx={{ fontSize: 14 }} />
            <Typography sx={{ fontSize: T.micro + 0.5, fontWeight: 700 }}>{trip.routeCode ?? t("Sin ruta")}</Typography>
          </Box>
          <Box sx={{ flex: 1 }} />
          <Typography sx={{ fontSize: T.body - 0.5, fontWeight: 700, color: "primary.main" }}>{t("Ver detalle")}</Typography>
          <ChevronRightRounded sx={{ fontSize: 18, color: "primary.main" }} />
        </Box>
      </ButtonBase>
    </Card>
  );
}
