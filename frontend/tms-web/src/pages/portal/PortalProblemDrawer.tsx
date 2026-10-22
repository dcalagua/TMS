import { useRef, useState } from "react";
import {
  Alert, Box, Button, CircularProgress, Drawer, FormControlLabel, IconButton, Radio, RadioGroup, TextField,
  Typography, useMediaQuery, useTheme,
} from "@mui/material";
import { CloseRounded, CloudOffRounded, ReportProblemRounded } from "@mui/icons-material";
import { TRIP_EXCEPTION_TYPES, type TripExceptionType } from "../../shared/api/planningApi";
import type { PortalExecutionAction } from "../../shared/api/portalApi";
import { enumLabel } from "../../lib/enums";
import { t } from "../../lib/i18n";
import { R } from "../../theme";
import { usePortalOffline } from "./offline/offlineContext";
import { haptic } from "./offline/network";
import {
  ExecutionRejectedError, STOP_FAILURE_REASONS, usePortalExecution, type ExecutionOutcome,
} from "./portalExecution";
import { READABLE_INPUTS, SAFE_AREA, TOUCH_TARGET } from "./portalUi";

/** Los tipos que el servidor solo acepta sobre una parada (`TripExceptionType.requiresStop`). */
const STOP_ONLY: TripExceptionType[] = ["CUSTOMER_CLOSED", "DELIVERY_REJECTED", "ADDRESS_NOT_FOUND", "DELIVERY_FAILED"];

interface PortalProblemDrawerProps {
  companyId: string;
  tripId: string;
  shipmentNumber: string | null;
  /**
   * `incident`: una incidencia sobre el viaje o una parada, que no cambia la parada. `fail`: la
   * parada no se pudo atender, con su motivo (la oficina abre la incidencia igual que cuando lo
   * registra ella).
   */
  mode: "incident" | "fail";
  /** La parada de la que se trata, o null para una incidencia del viaje entero. */
  stop: { id: string; label: string } | null;
  onClose: () => void;
  onSaved?: (outcome: ExecutionOutcome) => void;
}

/**
 * Reportar un problema desde el portal (V70): una incidencia —demora, avería, cliente cerrado…—
 * o que una parada no se pudo atender. Mismos tipos que la oficina, y «Otra» exige contarlo.
 */
export function PortalProblemDrawer({
  companyId, tripId, shipmentNumber, mode, stop, onClose, onSaved,
}: PortalProblemDrawerProps) {
  const offline = usePortalOffline();
  const run = usePortalExecution(companyId, tripId, shipmentNumber);
  const theme = useTheme();
  const wide = useMediaQuery(theme.breakpoints.up("sm"));
  const [idempotencyKey] = useState(() => crypto.randomUUID());
  const happenedAt = useRef<string | null>(null);
  const [type, setType] = useState<TripExceptionType | "">("");
  const [notes, setNotes] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const types = mode === "fail" ? STOP_FAILURE_REASONS
    : TRIP_EXCEPTION_TYPES.filter((candidate) => stop !== null || !STOP_ONLY.includes(candidate));
  const title = mode === "fail" ? t("No se pudo atender la parada") : t("Reportar incidencia");

  async function submit() {
    if (submitting) return;
    if (type === "") { setError(t("Elige qué pasó.")); haptic([80, 50, 80]); return; }
    if (type === "OTHER" && notes.trim() === "") { setError(t("Con «Otra», cuenta qué pasó.")); haptic([80, 50, 80]); return; }
    setError(null);
    happenedAt.current ??= new Date().toISOString();
    const action: PortalExecutionAction = mode === "fail" && stop
      ? { type: "STOP", stopId: stop.id, transition: "fail", occurredAt: happenedAt.current, exceptionType: type,
        notes: notes.trim() || null }
      : { type: "INCIDENT", tripStopId: stop?.id ?? null, exceptionType: type, occurredAt: happenedAt.current,
        notes: notes.trim() || null };
    const label = mode === "fail"
      ? `${t("No atendida")} · ${stop?.label ?? ""}`
      : `${t("Incidencia")} · ${enumLabel("tripExceptionType", type)}`;
    setSubmitting(true);
    try {
      const outcome = await run(action, label, idempotencyKey, happenedAt.current);
      haptic([30, 40, 30]);
      onSaved?.(outcome);
      onClose();
    } catch (failure) {
      haptic([80, 50, 80]);
      setError(failure instanceof ExecutionRejectedError ? failure.message : t("No se pudo guardar."));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Drawer
      anchor={wide ? "right" : "bottom"}
      open
      onClose={() => { if (!submitting) onClose(); }}
      sx={{ zIndex: (th) => th.zIndex.drawer + 2 }}
      slotProps={{
        paper: {
          role: "dialog",
          "aria-labelledby": "problem-title",
          sx: {
            display: "flex", flexDirection: "column", backgroundImage: "none", ...READABLE_INPUTS,
            ...(wide
              ? { width: 480, maxWidth: "100%", height: "100%" }
              : {
                maxHeight: `calc(100dvh - ${SAFE_AREA.top} - 12px)`,
                borderTopLeftRadius: `${R.xl}px`, borderTopRightRadius: `${R.xl}px`,
                pl: SAFE_AREA.left, pr: SAFE_AREA.right,
              }),
          },
        },
      }}
    >
      <Box sx={{ display: "flex", alignItems: "flex-start", gap: 1, px: 2, pt: 1.5, pb: 1.5, borderBottom: "1px solid", borderColor: "divider", flexShrink: 0 }}>
        <Box sx={{ flex: 1, minWidth: 0 }}>
          <Typography id="problem-title" component="h2" sx={{ fontWeight: 800, fontSize: 19, lineHeight: 1.25 }}>{title}</Typography>
          <Typography color="text.secondary" sx={{ fontSize: 15, mt: 0.25 }}>
            {stop ? stop.label : t("Todo el viaje")}
          </Typography>
        </Box>
        <IconButton onClick={onClose} aria-label={t("Cerrar")} disabled={submitting} sx={{ width: 48, height: 48, mt: -0.5 }}>
          <CloseRounded />
        </IconButton>
      </Box>

      <Box
        component="form" id="portal-problem" noValidate
        onSubmit={(event) => { event.preventDefault(); void submit(); }}
        sx={{ flex: 1, overflowY: "auto", px: 2, py: 2, display: "grid", alignContent: "start", gap: 2 }}
      >
        {!offline.online && (
          <Alert severity="warning" icon={<CloudOffRounded />} sx={{ fontSize: 15 }}>
            {t("Sin conexión. Se guardará en el teléfono y se enviará al recuperar señal.")}
          </Alert>
        )}
        <Box role="group" aria-labelledby="problem-type-label">
          <Typography component="h3" id="problem-type-label" sx={{ fontWeight: 800, fontSize: 17, mb: 1 }}>
            {t("¿Qué pasó?")}
          </Typography>
          <RadioGroup
            name="exceptionType" value={type}
            onChange={(e) => { haptic(10); setType(e.target.value as TripExceptionType); setError(null); }}
            sx={{ display: "grid", gap: 0.75 }}
          >
            {types.map((candidate) => (
              <FormControlLabel
                key={candidate} value={candidate} control={<Radio />}
                label={enumLabel("tripExceptionType", candidate)}
                sx={{
                  m: 0, minHeight: 52, pr: 1.5, borderRadius: `${R.md}px`, border: "1px solid",
                  borderColor: type === candidate ? "primary.main" : "divider",
                  bgcolor: type === candidate ? "action.selected" : "background.paper",
                  "& .MuiFormControlLabel-label": { fontSize: 16, fontWeight: 600 },
                }}
              />
            ))}
          </RadioGroup>
        </Box>
        <TextField
          fullWidth multiline minRows={2}
          label={type === "OTHER" ? t("Qué pasó (obligatorio)") : t("Observación (opcional)")}
          value={notes} onChange={(e) => setNotes(e.target.value)}
          slotProps={{ htmlInput: { maxLength: 500 } }}
        />
      </Box>

      <Box sx={{
        flexShrink: 0, borderTop: "1px solid", borderColor: "divider", bgcolor: "background.paper",
        px: 2, pt: 1.5, pb: `calc(${SAFE_AREA.bottom} + 12px)`, display: "grid", gap: 1,
      }}>
        {error && <Alert severity="error" sx={{ fontSize: 15 }}>{error}</Alert>}
        <Button
          type="submit" form="portal-problem" variant="contained" size="large" fullWidth disabled={submitting}
          color={mode === "fail" ? "warning" : "primary"}
          startIcon={submitting ? <CircularProgress size={22} color="inherit" /> : <ReportProblemRounded />}
          sx={{ minHeight: TOUCH_TARGET, fontSize: 18, fontWeight: 800 }}
        >
          {submitting ? t("Guardando...") : mode === "fail" ? t("Guardar como no atendida") : t("Reportar")}
        </Button>
      </Box>
    </Drawer>
  );
}
