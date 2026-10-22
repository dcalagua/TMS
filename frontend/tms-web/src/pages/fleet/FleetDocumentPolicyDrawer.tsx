import { useMutation } from "@tanstack/react-query";
import { useState } from "react";
import { Alert, Box, Button, MenuItem, Switch, TextField, Typography } from "@mui/material";
import { CheckRounded, TuneRounded } from "@mui/icons-material";
import {
  updateFleetDocumentPolicy,
  type ComplianceEnforcement, type FleetDocumentPolicy, type TypePolicy,
} from "../../shared/api/fleetDocumentsApi";
import type { ApiError } from "../../shared/api/httpClient";
import { describeApiError } from "../../shared/api/problemMessages";
import { FormDrawer, FormRow, FormSection } from "../../shared/ui/components";
import { enumLabel } from "../../lib/enums";
import { t } from "../../lib/i18n";

interface Props {
  companyId: string;
  policy: FleetDocumentPolicy;
  canManage: boolean;
  onClose: () => void;
  onSaved: (policy: FleetDocumentPolicy) => void;
}

/**
 * La política de documentos de flota de la empresa (V72): cuántos días antes avisa un vencimiento,
 * qué papeles son obligatorios y si uno vencido BLOQUEA o sólo AVISA en la planificación y en los
 * días de trabajo. No saber un vencimiento nunca bloquea; eso no se configura.
 */
export function FleetDocumentPolicyDrawer({ companyId, policy, canManage, onClose, onSaved }: Props) {
  const [alertDays, setAlertDays] = useState(String(policy.alertDays));
  const [types, setTypes] = useState<TypePolicy[]>(policy.types);
  const [dirty, setDirty] = useState(false);

  const save = useMutation({
    mutationFn: () => updateFleetDocumentPolicy(companyId, {
      alertDays: Number(alertDays),
      types: types.map((type) => ({ documentType: type.documentType, mandatory: type.mandatory, enforcement: type.enforcement })),
    }),
    onSuccess: (saved) => onSaved(saved),
  });

  const days = Number(alertDays);
  const daysInvalid = !Number.isInteger(days) || days < 1 || days > 365;

  function change(index: number, patch: Partial<TypePolicy>) {
    setTypes((current) => current.map((type, i) => (i === index ? { ...type, ...patch } : type)));
    setDirty(true);
  }

  const section = (subject: "VEHICLE" | "DRIVER") => types
    .map((type, index) => ({ type, index }))
    .filter(({ type }) => type.subjectType === subject)
    .map(({ type, index }) => (
      <Box key={type.documentType} sx={{
        display: "grid", gap: 1.5, alignItems: "center", py: 0.75,
        gridTemplateColumns: "minmax(0, 1fr) auto 140px",
        "& + &": { borderTop: "1px solid", borderColor: "divider" },
      }}>
        <Box sx={{ minWidth: 0 }}>
          <Typography variant="body2" sx={{ fontWeight: 700 }}>{enumLabel("fleetDocumentType", type.documentType)}</Typography>
          {!type.configured && (
            <Typography variant="caption" color="text.secondary">{t("Valor por defecto del producto")}</Typography>
          )}
        </Box>
        <Box sx={{ display: "flex", alignItems: "center", gap: 0.5 }}>
          <Switch size="small" checked={type.mandatory} disabled={!canManage}
            onChange={(e) => change(index, { mandatory: e.target.checked })}
            slotProps={{ input: { "aria-label": t("Obligatorio: {{type}}", { type: enumLabel("fleetDocumentType", type.documentType) }) } }} />
          <Typography variant="caption">{t("Obligatorio")}</Typography>
        </Box>
        <TextField select size="small" value={type.enforcement} disabled={!canManage}
          label={t("Si vence")} onChange={(e) => change(index, { enforcement: e.target.value as ComplianceEnforcement })}>
          <MenuItem value="BLOCK">{enumLabel("complianceEnforcement", "BLOCK")}</MenuItem>
          <MenuItem value="WARN">{enumLabel("complianceEnforcement", "WARN")}</MenuItem>
        </TextField>
      </Box>
    ));

  return (
    <FormDrawer
      open
      icon={<TuneRounded />}
      title={t("Política de documentos")}
      subtitle={t("Qué papeles se exigen y qué pasa cuando uno vence.")}
      size="md"
      onClose={onClose}
      dirty={dirty}
      closeOnBackdrop={!save.isPending}
      footer={canManage ? (
        <>
          <Button onClick={onClose} disabled={save.isPending}>{t("Cancelar")}</Button>
          <Button variant="contained" startIcon={<CheckRounded />} disabled={save.isPending || daysInvalid}
            onClick={() => save.mutate()}>
            {save.isPending ? t("Guardando...") : t("Guardar política")}
          </Button>
        </>
      ) : <Button onClick={onClose}>{t("Cerrar")}</Button>}
    >
      {save.isError && <Alert severity="error" sx={{ mb: 1 }}>{describeApiError(save.error as ApiError)}</Alert>}
      <FormSection title={t("Aviso de vencimiento")}
        help={t("Un documento que vence dentro de esta ventana se muestra «Por vencer». Sigue siendo válido.")}>
        <FormRow template="180px">
          <TextField label={t("Días de anticipación")} size="small" type="number" value={alertDays} disabled={!canManage}
            onChange={(e) => { setAlertDays(e.target.value); setDirty(true); }}
            error={daysInvalid} helperText={daysInvalid ? t("Entre 1 y 365 días") : undefined} />
        </FormRow>
      </FormSection>
      <FormSection title={t("Vehículo")}
        help={t("Obligatorio: si falta o no tiene fecha, avisa. «Bloquea»: vencido, no se puede asignar a un viaje ni despachar.")}>
        <Box>{section("VEHICLE")}</Box>
      </FormSection>
      <FormSection title={t("Conductor")} help={t("Los papeles del conductor además de la licencia.")}>
        <Box>{section("DRIVER")}</Box>
      </FormSection>
    </FormDrawer>
  );
}
