import { useState } from "react";
import { Alert, Box, Button, MenuItem, Stack, TextField, Typography } from "@mui/material";
import { CallSplitRounded } from "@mui/icons-material";
import { FormDrawer } from "../../shared/ui/components";
import type { EligibleOrderView, TripView } from "../../shared/api/planningApi";
import { fmtDecimal, fmtVolumeM3, fmtWeightKg } from "../../lib/locale";
import { t } from "../../lib/i18n";

interface SplitAssignDrawerProps {
  open: boolean;
  order: EligibleOrderView | null;
  trips: TripView[];
  submitting: boolean;
  onClose: () => void;
  onSubmit: (tripId: string, amounts: { weightKg: number; volumeM3: number; pallets: number }) => void;
}

/**
 * Reparte un pedido entre varios viajes (migración V37).
 *
 * <h2>Por qué un panel y no un prompt</h2>
 * Un reparto son tres cifras y un viaje de destino, y las tres tienen que verse contra lo que
 * queda. Pedirlas de una en una convierte una decisión en cuatro, y pedir solo los pallets
 * obligaría al servidor a inventar el peso — que es exactamente lo que este producto no hace.
 *
 * <h2>Por qué arranca con lo pendiente y no con el total</h2>
 * Después de un primer reparto lo que se puede asignar es el resto, no el pedido. Prellenar el
 * total sería ofrecer por defecto la única cifra que el backend va a rechazar.
 *
 * La validación de aquí es cortesía: quien decide es
 * `ck_transport_order_not_over_allocated`, y el servicio rechaza antes con una frase legible.
 */
export function SplitAssignDrawer({ open, order, trips, submitting, onClose, onSubmit }: SplitAssignDrawerProps) {
  const [tripId, setTripId] = useState("");
  const [weightKg, setWeightKg] = useState("");
  const [volumeM3, setVolumeM3] = useState("");
  const [pallets, setPallets] = useState("");
  const [touched, setTouched] = useState(false);

  // Reinicia al abrir sobre otro pedido: los valores por defecto son los pendientes de *este*.
  const [lastOrderId, setLastOrderId] = useState<string | null>(null);
  if (order && order.id !== lastOrderId) {
    setLastOrderId(order.id);
    setTripId(trips[0]?.id ?? "");
    setWeightKg(String(order.pendingWeightKg ?? 0));
    setVolumeM3(String(order.pendingVolumeM3 ?? 0));
    setPallets(String(order.pendingPallets ?? 0));
    setTouched(false);
  }

  if (!order) return null;

  const parsed = {
    weightKg: Number(weightKg),
    volumeM3: Number(volumeM3),
    pallets: Number(pallets),
  };
  const anyNaN = Number.isNaN(parsed.weightKg) || Number.isNaN(parsed.volumeM3) || Number.isNaN(parsed.pallets);
  const anyNegative = parsed.weightKg < 0 || parsed.volumeM3 < 0 || parsed.pallets < 0;
  const allZero = parsed.weightKg === 0 && parsed.volumeM3 === 0 && parsed.pallets === 0;
  const overPending =
    parsed.weightKg > order.pendingWeightKg
    || parsed.volumeM3 > order.pendingVolumeM3
    || parsed.pallets > order.pendingPallets;
  const invalid = anyNaN || anyNegative || allZero || overPending || tripId === "";

  return (
    <FormDrawer
      open={open}
      title={t("Repartir pedido")}
      subtitle={order.orderNumber}
      icon={<CallSplitRounded />}
      onClose={onClose}
      dirty={touched}
      size="sm"
      footer={
        <>
          <Button onClick={onClose} disabled={submitting}>{t("Cancelar")}</Button>
          <Button
            variant="contained"
            disabled={invalid || submitting}
            onClick={() => onSubmit(tripId, parsed)}
          >
            {t("Asignar esta parte")}
          </Button>
        </>
      }
    >
      <Stack spacing={2}>
        <Alert severity="info" variant="outlined">
          {t("Sube a este viaje solo la parte indicada. El resto sigue en la bolsa para otro viaje; el pedido no se duplica.")}
        </Alert>

        <Box>
          <Typography variant="caption" color="text.secondary" sx={{ display: "block" }}>
            {t("Pendiente de planificar")}
          </Typography>
          <Typography variant="body2" sx={{ fontVariantNumeric: "tabular-nums", fontWeight: 700 }}>
            {fmtWeightKg(order.pendingWeightKg)} · {fmtVolumeM3(order.pendingVolumeM3)} ·{" "}
            {fmtDecimal(order.pendingPallets)} {t("pallets")}
          </Typography>
        </Box>

        <TextField
          select size="small" label={t("Viaje de destino")} value={tripId}
          onChange={(e) => { setTripId(e.target.value); setTouched(true); }}
        >
          {trips.map((trip) => (
            <MenuItem key={trip.id} value={trip.id}>
              {t("Viaje {{number}}", { number: trip.tripNumber })}
              {trip.vehicleCode ? ` · ${trip.vehicleCode}` : ""}
            </MenuItem>
          ))}
        </TextField>

        <TextField
          size="small" type="number" label={t("Peso (kg)")} value={weightKg}
          onChange={(e) => { setWeightKg(e.target.value); setTouched(true); }}
          slotProps={{ htmlInput: { min: 0, max: order.pendingWeightKg, step: "0.001" } }}
        />
        <TextField
          size="small" type="number" label={t("Volumen (m3)")} value={volumeM3}
          onChange={(e) => { setVolumeM3(e.target.value); setTouched(true); }}
          slotProps={{ htmlInput: { min: 0, max: order.pendingVolumeM3, step: "0.0001" } }}
        />
        <TextField
          size="small" type="number" label={t("Pallets")} value={pallets}
          onChange={(e) => { setPallets(e.target.value); setTouched(true); }}
          slotProps={{ htmlInput: { min: 0, max: order.pendingPallets, step: "0.01" } }}
        />

        {overPending && (
          <Alert severity="warning" variant="outlined">
            {t("No se puede asignar más de lo que queda pendiente.")}
          </Alert>
        )}
        {allZero && !anyNaN && (
          <Alert severity="warning" variant="outlined">
            {t("Un reparto tiene que llevar algo: peso, volumen o pallets.")}
          </Alert>
        )}
      </Stack>
    </FormDrawer>
  );
}
