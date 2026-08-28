import { useCallback, useEffect, useState } from "react";
import {
  Alert, Button, Chip, IconButton, MenuItem, Stack, Table, TableBody, TableCell, TableHead,
  TableRow, TextField, Tooltip, Typography,
} from "@mui/material";
import { BuildCircleRounded, DeleteOutlineRounded } from "@mui/icons-material";
import { FormDrawer } from "../../shared/ui/components";
import {
  DRIVER_UNAVAILABILITY_REASONS, VEHICLE_UNAVAILABILITY_REASONS, blockDriver, blockVehicle,
  listDriverUnavailability, listVehicleUnavailability, releaseDriver, releaseVehicle,
  type UnavailabilityReason, type UnavailabilityView,
} from "../../shared/api/fleetAvailabilityApi";
import type { ApiError } from "../../shared/api/httpClient";
import { describeApiError } from "../../shared/api/problemMessages";
import { confirmDialog, notifyError, notifySuccess } from "../../lib/ui";
import { enumLabel } from "../../lib/enums";
import { t } from "../../lib/i18n";

interface AvailabilityDrawerProps {
  companyId: string;
  /** Qué recurso se está gestionando. Decide los motivos ofrecidos y el endpoint que se llama. */
  resource: "vehicle" | "driver";
  resourceId: string;
  /** El código que el usuario reconoce: la placa del camión, el código del conductor. */
  resourceLabel: string;
  canManage: boolean;
  onClose: () => void;
}

/**
 * Cuándo un vehículo o un conductor no puede trabajar (migración V42).
 *
 * <h2>Por qué un solo cajón para los dos</h2>
 * La pregunta es la misma — "de cuándo a cuándo no está disponible, y por qué" — y el backend la
 * responde con una sola tabla. Lo único que cambia es la lista de motivos: un camión de vacaciones
 * y un conductor en reparación son ambos absurdos, y el servidor los rechaza, así que el
 * formulario no los ofrece.
 *
 * <h2>Por qué liberar borra la fila</h2>
 * Un mantenimiento cargado por error no es un hecho sobre el camión, y dejarlo con duración cero
 * metería un fantasma en cualquier cálculo de disponibilidad. La decisión sobrevive en la pista de
 * auditoría, que es donde va una reversión.
 */
export function AvailabilityDrawer({
  companyId, resource, resourceId, resourceLabel, canManage, onClose,
}: AvailabilityDrawerProps) {
  const isVehicle = resource === "vehicle";
  const reasons: readonly UnavailabilityReason[] =
    isVehicle ? VEHICLE_UNAVAILABILITY_REASONS : DRIVER_UNAVAILABILITY_REASONS;

  const [blocks, setBlocks] = useState<UnavailabilityView[] | null>(null);
  const [reason, setReason] = useState<UnavailabilityReason>(reasons[0]);
  const [startsAt, setStartsAt] = useState("");
  const [endsAt, setEndsAt] = useState("");
  const [notes, setNotes] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [touched, setTouched] = useState(false);

  const load = useCallback(async () => {
    try {
      setBlocks(isVehicle
        ? await listVehicleUnavailability(companyId, resourceId)
        : await listDriverUnavailability(companyId, resourceId));
    } catch (error) {
      notifyError(t("No se pudo cargar la disponibilidad"), describeApiError(error as ApiError));
      setBlocks([]);
    }
  }, [companyId, isVehicle, resourceId]);

  useEffect(() => { void load(); }, [load]);

  const invalid = startsAt === "" || endsAt === "" || endsAt <= startsAt;

  async function submit() {
    setSubmitting(true);
    try {
      const request = {
        reason,
        startsAt: new Date(startsAt).toISOString(),
        endsAt: new Date(endsAt).toISOString(),
        notes: notes.trim() || null,
      };
      if (isVehicle) {
        await blockVehicle(companyId, resourceId, request);
      } else {
        await blockDriver(companyId, resourceId, request);
      }
      notifySuccess(isVehicle ? t("Vehículo fuera de servicio") : t("Ausencia registrada"));
      setStartsAt(""); setEndsAt(""); setNotes(""); setTouched(false);
      await load();
    } catch (error) {
      // El backend nombra la ventana que estorba y hasta cuándo dura. Traducir eso a "no se pudo"
      // tiraría justo la parte que dice qué hacer a continuación.
      notifyError(t("No se pudo registrar"), describeApiError(error as ApiError));
    } finally {
      setSubmitting(false);
    }
  }

  async function release(block: UnavailabilityView) {
    const confirmed = await confirmDialog({
      title: isVehicle ? t("¿Devolver el vehículo al servicio?") : t("¿Quitar la ausencia?"),
      text: t("La ventana se elimina. La decisión queda en la pista de auditoría."),
      confirmLabel: t("Sí, liberar"),
      dangerous: true,
    });
    if (!confirmed) return;
    try {
      if (isVehicle) {
        await releaseVehicle(companyId, resourceId, block.id);
      } else {
        await releaseDriver(companyId, resourceId, block.id);
      }
      notifySuccess(t("Liberado"));
      await load();
    } catch (error) {
      notifyError(t("No se pudo liberar"), describeApiError(error as ApiError));
    }
  }

  return (
    <FormDrawer
      open
      title={isVehicle ? t("Disponibilidad del vehículo") : t("Disponibilidad del conductor")}
      subtitle={resourceLabel}
      icon={<BuildCircleRounded />}
      onClose={onClose}
      dirty={touched}
      size="md"
      footer={<Button onClick={onClose} disabled={submitting}>{t("Cerrar")}</Button>}
    >
      <Stack spacing={3}>
        {canManage && (
          <Stack spacing={2}>
            <Typography variant="subtitle2">{t("Registrar una ventana")}</Typography>
            <Stack direction={{ xs: "column", sm: "row" }} spacing={2}>
              <TextField
                select size="small" label={t("Motivo")} value={reason} sx={{ minWidth: 180 }}
                onChange={(e) => { setReason(e.target.value as UnavailabilityReason); setTouched(true); }}
              >
                {reasons.map((value) => (
                  <MenuItem key={value} value={value}>{enumLabel("unavailabilityReason", value)}</MenuItem>
                ))}
              </TextField>
              <TextField
                size="small" type="datetime-local" label={t("Desde")} value={startsAt} required
                onChange={(e) => { setStartsAt(e.target.value); setTouched(true); }}
                slotProps={{ inputLabel: { shrink: true } }}
              />
              <TextField
                size="small" type="datetime-local" label={t("Hasta")} value={endsAt} required
                onChange={(e) => { setEndsAt(e.target.value); setTouched(true); }}
                slotProps={{ inputLabel: { shrink: true } }}
                helperText={endsAt !== "" && endsAt <= startsAt ? t("Debe terminar después de empezar.") : " "}
                error={endsAt !== "" && endsAt <= startsAt}
              />
            </Stack>
            <TextField
              size="small" label={t("Notas")} value={notes} multiline minRows={1}
              onChange={(e) => { setNotes(e.target.value); setTouched(true); }}
            />
            <Stack direction="row" sx={{ justifyContent: "flex-end" }}>
              <Button variant="contained" disabled={invalid || submitting} onClick={() => void submit()}>
                {submitting ? t("Registrando...") : t("Registrar")}
              </Button>
            </Stack>
          </Stack>
        )}

        <Stack spacing={1}>
          <Typography variant="subtitle2">{t("Ventanas registradas")}</Typography>
          {blocks === null ? (
            <Typography variant="body2" color="text.secondary">{t("Cargando...")}</Typography>
          ) : blocks.length === 0 ? (
            <Alert severity="info" variant="outlined">
              {isVehicle
                ? t("Sin ventanas: el vehículo está disponible siempre.")
                : t("Sin ventanas: el conductor está disponible siempre.")}
            </Alert>
          ) : (
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>{t("Motivo")}</TableCell>
                  <TableCell>{t("Desde")}</TableCell>
                  <TableCell>{t("Hasta")}</TableCell>
                  <TableCell>{t("Notas")}</TableCell>
                  {canManage && <TableCell align="right">{t("Acciones")}</TableCell>}
                </TableRow>
              </TableHead>
              <TableBody>
                {blocks.map((block) => (
                  <TableRow key={block.id} hover>
                    <TableCell>
                      <Chip size="small" label={enumLabel("unavailabilityReason", block.reason)} />
                    </TableCell>
                    <TableCell>{new Date(block.startsAt).toLocaleString()}</TableCell>
                    <TableCell>{new Date(block.endsAt).toLocaleString()}</TableCell>
                    <TableCell>{block.notes ?? "—"}</TableCell>
                    {canManage && (
                      <TableCell align="right">
                        <Tooltip title={t("Liberar")}>
                          <IconButton size="small" onClick={() => void release(block)}>
                            <DeleteOutlineRounded fontSize="small" />
                          </IconButton>
                        </Tooltip>
                      </TableCell>
                    )}
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </Stack>
      </Stack>
    </FormDrawer>
  );
}
