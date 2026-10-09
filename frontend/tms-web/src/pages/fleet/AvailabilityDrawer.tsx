import { useCallback, useEffect, useState, type ReactNode } from "react";
import { Alert, Box, Button, IconButton, MenuItem, TextField, Tooltip, Typography } from "@mui/material";
import { BuildCircleRounded, CheckRounded, DeleteOutlineRounded } from "@mui/icons-material";
import { ContextCard, DateTimeInput, FormDrawer, StatusChip } from "../../shared/ui/components";
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
import { T } from "../../theme";

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
      subtitle={isVehicle
        ? t("Cuándo el vehículo no puede salir, y por qué.")
        : t("Cuándo el conductor no puede trabajar, y por qué.")}
      icon={<BuildCircleRounded />}
      onClose={onClose}
      dirty={touched}
      size="sm"
      footer={
        <>
          <Button color="inherit" sx={{ color: "text.secondary" }} onClick={onClose} disabled={submitting}>{t("Cerrar")}</Button>
          {canManage && (
            <Button variant="contained" startIcon={<CheckRounded />} disabled={invalid || submitting} onClick={() => void submit()}>
              {submitting ? t("Registrando...") : t("Registrar ventana")}
            </Button>
          )}
        </>
      }
    >
      <Box sx={{ display: "grid", gap: 2.5 }}>
        <ContextCard
          title={resourceLabel}
          status={blocks !== null && (
            <StatusChip
              label={blocks.length === 0
                ? t("Disponible")
                : blocks.length === 1 ? t("{{count}} ventana", { count: 1 }) : t("{{count}} ventanas", { count: blocks.length })}
              tone={blocks.length === 0 ? "done" : "inProgress"}
            />
          )}
          detail={isVehicle ? t("Vehículo") : t("Conductor")}
        />

        {canManage && section(t("Registrar una ventana"), (
          <>
            <TextField
              select size="small" label={t("Motivo")} value={reason}
              onChange={(e) => { setReason(e.target.value as UnavailabilityReason); setTouched(true); }}
            >
              {reasons.map((value) => (
                <MenuItem key={value} value={value}>{enumLabel("unavailabilityReason", value)}</MenuItem>
              ))}
            </TextField>
            <DateTimeInput
              size="small" label={t("Desde")} value={startsAt} required
              onChange={(v) => { setStartsAt(v); setTouched(true); }}
            />
            <DateTimeInput
              size="small" label={t("Hasta")} value={endsAt} required
              onChange={(v) => { setEndsAt(v); setTouched(true); }}
              helperText={endsAt !== "" && endsAt <= startsAt ? t("Debe terminar después de empezar.") : " "}
              error={endsAt !== "" && endsAt <= startsAt}
            />
            <TextField
              size="small" label={t("Notas")} value={notes} multiline minRows={1}
              onChange={(e) => { setNotes(e.target.value); setTouched(true); }}
            />
          </>
        ))}

        {section(t("Ventanas registradas"), blocks === null ? (
          <Typography variant="body2" color="text.secondary">{t("Cargando...")}</Typography>
        ) : blocks.length === 0 ? (
          <Alert severity="info" variant="outlined">
            {isVehicle
              ? t("Sin ventanas: el vehículo está disponible siempre.")
              : t("Sin ventanas: el conductor está disponible siempre.")}
          </Alert>
        ) : (
          <Box sx={{ border: "1px solid", borderColor: "divider", borderRadius: 1.5 }}>
            {blocks.map((block) => (
              <Box
                key={block.id}
                sx={{
                  display: "flex", alignItems: "flex-start", gap: 1, px: 1.5, py: 1.25,
                  "& + &": { borderTop: "1px solid", borderColor: "divider" },
                }}
              >
                <Box sx={{ flex: 1, minWidth: 0, display: "grid", gap: 0.5 }}>
                  <Box><StatusChip label={enumLabel("unavailabilityReason", block.reason)} /></Box>
                  <Typography sx={{ fontSize: T.body - 0.5, fontVariantNumeric: "tabular-nums" }}>
                    {new Date(block.startsAt).toLocaleString()} → {new Date(block.endsAt).toLocaleString()}
                  </Typography>
                  {block.notes && (
                    <Typography sx={{ fontSize: T.micro + 0.5, color: "text.secondary" }}>{block.notes}</Typography>
                  )}
                </Box>
                {canManage && (
                  <Tooltip title={t("Liberar")}>
                    <IconButton size="small" onClick={() => void release(block)}>
                      <DeleteOutlineRounded fontSize="small" />
                    </IconButton>
                  </Tooltip>
                )}
              </Box>
            ))}
          </Box>
        ))}
      </Box>
    </FormDrawer>
  );
}

/** Un bloque del cajón con su título pequeño en mayúsculas, como en los paneles de detalle. */
function section(title: string, children: ReactNode) {
  return (
    <Box component="section" aria-label={title} sx={{ display: "grid", gap: 1.5 }}>
      <Typography sx={{ fontSize: T.micro, fontWeight: 700, letterSpacing: ".08em", textTransform: "uppercase", color: "text.secondary" }}>
        {title}
      </Typography>
      {children}
    </Box>
  );
}
