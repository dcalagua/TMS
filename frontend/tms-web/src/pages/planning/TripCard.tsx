import { Box, Button, Card, CardContent, Typography } from "@mui/material";
import { ArrowForwardRounded, LocalShippingOutlined } from "@mui/icons-material";
import type { TripView } from "../../shared/api/planningApi";
import { CapacityBar, StatusChip } from "../../shared/ui/components";
import { TRIP_STATUS_TONE } from "../../shared/ui/statusTones";
import { enumLabel } from "../../lib/enums";
import { t } from "../../lib/i18n";
import { fmtDateTime, fmtQuantity } from "../../lib/locale";
import { R } from "../../theme";

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

  const facts = [
    { label: t("Salida"), value: trip.plannedDepartureAt ? fmtDateTime(trip.plannedDepartureAt) : t("Sin definir") },
    { label: t("Pedidos"), value: fmtQuantity(trip.orderCount) },
    { label: t("Paradas"), value: fmtQuantity(trip.stopCount) },
    ...(trip.routeCode ? [{ label: t("Ruta"), value: trip.routeCode }] : []),
  ];

  return (
    <Card
      component="article"
      variant="outlined"
      onClick={onOpen}
      sx={{
        height: "100%", display: "flex", flexDirection: "column", cursor: "pointer",
        borderRadius: `${R.lg}px`,
        // El borde rojo es un aviso, no el veredicto: quien decide si cabe es el backend, y esto
        // solo repite lo que ya dijo en `withinCapacity`.
        ...(overCapacity ? { border: "1.5px solid", borderColor: "error.main" } : {}),
        transition: "transform .15s, box-shadow .15s",
        "&:hover": { transform: "translateY(-2px)", boxShadow: 3 },
      }}
    >
      <CardContent sx={{ flexGrow: 1, p: 2, "&:last-child": { pb: 2 } }}>
        <Box sx={{ display: "flex", alignItems: "flex-start", justifyContent: "space-between", gap: 1.5 }}>
          <Box sx={{ minWidth: 0 }}>
            <Typography variant="subtitle1" sx={{ lineHeight: 1.25, fontSize: 15, fontWeight: 700 }}>
              {t("Viaje {{number}}", { number: trip.tripNumber })}
            </Typography>
            {/* El número de envío, no el de viaje, es lo que referencia un sistema externo, un
                manifiesto o una llamada: "viaje 3" no significa nada sin nombrar su plan. */}
            <Typography
              variant="caption" color="text.secondary" noWrap
              sx={{ display: "block", fontVariantNumeric: "tabular-nums", letterSpacing: ".02em" }}
            >
              {t("Envío")} {trip.shipmentNumber}
            </Typography>
          </Box>
          <StatusChip label={enumLabel("tripStatus", trip.status)} tone={TRIP_STATUS_TONE[trip.status]} />
        </Box>

        <Box sx={{ display: "flex", alignItems: "center", gap: 1, mt: 1.5, mb: 0.25 }}>
          <LocalShippingOutlined sx={{ fontSize: 17, color: "text.secondary" }} />
          {trip.vehicleCode ? (
            <Typography variant="body2" sx={{ fontWeight: 700 }}>
              {trip.vehicleCode}
              <Box component="span"> · {trip.vehicleLicensePlate}</Box>
            </Typography>
          ) : (
            <Typography variant="body2" color="text.secondary" sx={{ fontStyle: "italic" }}>
              {t("Sin vehículo asignado")}
            </Typography>
          )}
        </Box>
        <Typography variant="caption" color="text.secondary" noWrap sx={{ display: "block" }}>
          {trip.carrierName ?? t("Flota propia")}
          {trip.vehicleTypeCode && ` · ${trip.vehicleTypeCode}`}
        </Typography>

        <Box
          component="dl"
          sx={{
            m: 0, my: 1.5, py: 1.25, borderTop: "1px solid", borderBottom: "1px solid", borderColor: "divider",
            display: "grid", gap: 1, gridTemplateColumns: `repeat(${facts.length}, minmax(0, auto))`,
            justifyContent: "space-between",
          }}
        >
          {facts.map((row) => (
            <Box key={row.label} sx={{ minWidth: 0 }}>
              <Typography component="dt" variant="caption" color="text.secondary" sx={{ display: "block", lineHeight: 1.4 }}>
                {row.label}
              </Typography>
              <Typography
                component="dd" variant="body2"
                sx={{ m: 0, fontWeight: 700, fontVariantNumeric: "tabular-nums", whiteSpace: "nowrap" }}
              >
                {row.value}
              </Typography>
            </Box>
          ))}
        </Box>

        <CapacityBar kind="weight" dimension={trip.capacity.weight} />
        <CapacityBar kind="volume" dimension={trip.capacity.volume} />
        <CapacityBar kind="pallets" dimension={trip.capacity.pallets} />

        <Box sx={{ display: "flex", justifyContent: "flex-end", mt: 1 }}>
          {/* La etiqueta visible se queda corta; el nombre accesible dice qué viaje abre, para que
              un tablero de doce tarjetas no presente doce botones llamados todos "Abrir". */}
          <Button
            size="small" variant="outlined" color="inherit"
            endIcon={<ArrowForwardRounded sx={{ fontSize: "15px !important" }} />}
            aria-label={t("Abrir el viaje {{number}}", { number: trip.tripNumber })}
            onClick={(e) => { e.stopPropagation(); onOpen(); }}
            sx={{ borderColor: "divider" }}
          >
            {t("Abrir")}
          </Button>
        </Box>
      </CardContent>
    </Card>
  );
}
