import { Box, Button, Card, CardContent, Divider, Typography } from "@mui/material";
import { LocalShippingRounded } from "@mui/icons-material";
import type { TripView } from "../../shared/api/planningApi";
import { CapacityBar, StatusChip } from "../../shared/ui/components";
import { TRIP_STATUS_TONE } from "../../shared/ui/statusTones";
import { enumLabel } from "../../lib/enums";
import { t } from "../../lib/i18n";
import { fmtDateTime, fmtQuantity } from "../../lib/locale";

interface TripCardProps {
  trip: TripView;
  onOpen: () => void;
}

/**
 * Un envío en el tablero: las dos identidades (el número de viaje dentro del plan, que es lo que
 * lee un planificador, y el `shipmentNumber` que usa todo lo de fuera del tablero), el estado, el
 * vehículo con su tipo y el transportista con el que se *planificó* el envío, la salida, cuánto
 * lleva encima, la ruta sugerida si alguien eligió una, y las tres dimensiones de capacidad
 * pintadas exactamente como las calculó el backend.
 *
 * La tarjeta entera es el control que abre el detalle: en un tablero de una docena de viajes,
 * cazar un botoncito "Abrir" en cada pie es más lento que pulsar la tarjeta que el planificador
 * ya está leyendo. El botón se queda igualmente, con nombre accesible propio, para quien navega
 * con teclado.
 */
export function TripCard({ trip, onOpen }: TripCardProps) {
  const overCapacity = !trip.capacity.withinCapacity;

  return (
    <Card
      component="article"
      variant="outlined"
      onClick={onOpen}
      sx={{
        height: "100%", display: "flex", flexDirection: "column", cursor: "pointer",
        // El borde rojo es un aviso, no el veredicto: quien decide si cabe es el backend, y esto
        // solo repite lo que ya dijo en `withinCapacity`.
        ...(overCapacity ? { borderColor: "error.main" } : {}),
        transition: "transform .15s, box-shadow .15s",
        "&:hover": { transform: "translateY(-2px)", boxShadow: 3 },
      }}
    >
      <Box sx={{
        display: "flex", alignItems: "flex-start", justifyContent: "space-between",
        gap: 1.5, px: 2, py: 1.35,
      }}>
        <Box sx={{ minWidth: 0 }}>
          <Typography variant="subtitle1" sx={{ lineHeight: 1.25 }}>
            {t("Viaje {{number}}", { number: trip.tripNumber })}
          </Typography>
          {/* El número de envío, no el de viaje, es lo que referencia un sistema externo, un
              manifiesto o una llamada: "viaje 3" no significa nada sin nombrar su plan. */}
          <Typography variant="caption" color="text.secondary" noWrap>
            {t("Envío")} {trip.shipmentNumber}
          </Typography>
        </Box>
        <StatusChip label={enumLabel("tripStatus", trip.status)} tone={TRIP_STATUS_TONE[trip.status]} />
      </Box>
      <Divider />

      <CardContent sx={{ flexGrow: 1, p: 2, "&:last-child": { pb: 2 } }}>
        <Box sx={{ display: "flex", alignItems: "center", gap: 1, mb: 0.5 }}>
          <LocalShippingRounded sx={{ fontSize: 18, color: "text.disabled" }} />
          {trip.vehicleCode ? (
            <Typography variant="body2">
              <Box component="span" sx={{ fontWeight: 700 }}>{trip.vehicleCode}</Box>
              <Box component="span" sx={{ color: "text.secondary" }}> · {trip.vehicleLicensePlate}</Box>
            </Typography>
          ) : (
            <Typography variant="body2" color="text.disabled" sx={{ fontStyle: "italic" }}>
              {t("Sin vehículo asignado")}
            </Typography>
          )}
        </Box>
        <Typography variant="caption" color="text.secondary" noWrap sx={{ display: "block", mb: 2 }}>
          {trip.carrierName ?? t("Flota propia")}
          {trip.vehicleTypeCode && ` · ${trip.vehicleTypeCode}`}
        </Typography>

        <Box component="dl" sx={{ m: 0, mb: 2, display: "grid", gap: 0.4 }}>
          {[
            { label: t("Salida"), value: trip.plannedDepartureAt ? fmtDateTime(trip.plannedDepartureAt) : t("Sin definir") },
            { label: t("Pedidos"), value: fmtQuantity(trip.orderCount) },
            { label: t("Paradas"), value: fmtQuantity(trip.stopCount) },
            ...(trip.routeCode ? [{ label: t("Ruta"), value: trip.routeCode }] : []),
          ].map((row) => (
            <Box key={row.label} sx={{ display: "flex", justifyContent: "space-between", gap: 1 }}>
              <Typography component="dt" variant="caption" color="text.secondary">{row.label}</Typography>
              <Typography component="dd" variant="caption" sx={{ m: 0, textAlign: "right", fontWeight: 600 }}>
                {row.value}
              </Typography>
            </Box>
          ))}
        </Box>

        <CapacityBar kind="weight" dimension={trip.capacity.weight} />
        <CapacityBar kind="volume" dimension={trip.capacity.volume} />
        <CapacityBar kind="pallets" dimension={trip.capacity.pallets} />
      </CardContent>

      <Divider />
      <Box sx={{ display: "flex", justifyContent: "flex-end", px: 2, py: 1 }}>
        {/* La etiqueta visible se queda corta; el nombre accesible dice qué viaje abre, para que
            un tablero de doce tarjetas no presente doce botones llamados todos "Abrir". */}
        <Button
          size="small" variant="outlined"
          aria-label={t("Abrir el viaje {{number}}", { number: trip.tripNumber })}
          onClick={(e) => { e.stopPropagation(); onOpen(); }}
        >
          {t("Abrir")}
        </Button>
      </Box>
    </Card>
  );
}
