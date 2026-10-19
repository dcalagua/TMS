import { Alert, Box, Chip } from "@mui/material";
import { Inventory2Rounded } from "@mui/icons-material";
import type { ApiError } from "../../shared/api/httpClient";
import type { MaterialProfileView } from "../../shared/api/materialProfilesApi";
import { describeApiError } from "../../shared/api/problemMessages";
import { FormDrawer } from "../../shared/ui/components";
import { DetailSection, KeyFacts, type KeyFact } from "../../shared/ui/components/DetailLayout";
import { t } from "../../lib/i18n";
import { fmtDateTime, fmtQuantity, fmtVolumeM3, fmtWeightKg } from "../../lib/locale";
import { fmtMeters, stackModeLabel, useMaterialProfile } from "./materialProfiles";

interface MaterialProfileDrawerProps {
  companyId: string;
  row: MaterialProfileView;
  onClose: () => void;
}

/**
 * El detalle de un perfil de material, solo lectura: medidas, peso, apilado y de dónde vino.
 *
 * Abre con la fila de la lista y se refresca con el detalle del backend; si el detalle falla, lo
 * que ya se ve sigue siendo la fila, con el error encima. El incremento de anidado solo aparece
 * para un material anidable: en cualquier otro modo no significa nada.
 */
export function MaterialProfileDrawer({ companyId, row, onClose }: MaterialProfileDrawerProps) {
  const detailQuery = useMaterialProfile(companyId, row);
  const profile = detailQuery.data ?? row;

  const stacking: KeyFact[] = [
    { label: t("Modo de apilado"), value: stackModeLabel(profile.stackMode) },
    { label: t("Unidades por pila"), value: fmtQuantity(profile.stackMaxQuantity) },
    ...(profile.stackMode === "NESTABLE"
      ? [{ label: t("Incremento de anidado"), value: fmtMeters(profile.nestIncrementM) }]
      : []),
    { label: t("Carga máxima encima"), value: fmtWeightKg(profile.maxTopLoadKg) },
    { label: t("Orientación bloqueada"), value: profile.orientationLocked ? t("Sí") : t("No") },
  ];

  return (
    <FormDrawer
      open
      size="md"
      icon={<Inventory2Rounded />}
      title={profile.materialCode}
      subtitle={profile.description ?? undefined}
      titleAdornment={<Chip size="small" variant="outlined" label={t("Solo lectura")} sx={{ height: 22, fontSize: 11 }} />}
      onClose={onClose}
    >
      <Box sx={{ display: "grid", gap: 2.5 }}>
        {detailQuery.isError && (
          <Alert severity="error">{describeApiError(detailQuery.error as ApiError)}</Alert>
        )}

        <DetailSection title={t("Medidas y peso")}>
          <KeyFacts columns={3} items={[
            { label: t("Largo"), value: fmtMeters(profile.lengthM) },
            { label: t("Ancho"), value: fmtMeters(profile.widthM) },
            { label: t("Alto"), value: fmtMeters(profile.heightM) },
            { label: t("Peso unitario"), value: fmtWeightKg(profile.unitWeightKg) },
            { label: t("Volumen unitario"), value: fmtVolumeM3(profile.unitVolumeM3) },
          ]} />
        </DetailSection>

        <DetailSection title={t("Apilado")}>
          <KeyFacts columns={3} items={stacking} />
        </DetailSection>

        <DetailSection title={t("Origen y sincronización")}>
          <KeyFacts columns={2} items={[
            { label: t("Sistema origen"), value: profile.sourceSystem },
            { label: t("Versión"), value: fmtQuantity(profile.version) },
            { label: t("Actualizado en origen"), value: fmtDateTime(profile.sourceUpdatedAt) },
            { label: t("Sincronizado"), value: fmtDateTime(profile.updatedAt) },
            { label: t("Recibido por primera vez"), value: fmtDateTime(profile.createdAt) },
          ]} />
        </DetailSection>
      </Box>
    </FormDrawer>
  );
}
