import { useState } from "react";
import { useForm } from "react-hook-form";
import { Alert, Box, Button, TextField } from "@mui/material";
import { CheckRounded, CropFreeRounded } from "@mui/icons-material";
import type { ApiError } from "../../shared/api/httpClient";
import { applyApiFieldErrors } from "../../shared/api/formErrors";
import { createZone, updateZone, type ZoneRequest, type ZoneView } from "../../shared/api/zonesApi";
import { ActiveBadge, FormDrawer, FormMeta, FormRow, FormSection } from "../../shared/ui/components";
import { t } from "../../lib/i18n";
import { fmtDateTime } from "../../lib/locale";

const FORM_ID = "zone-form";

/** Casa con la restricción de `code` del backend; vive junto al campo que valida. */
export const CODE_PATTERN = /^[A-Za-z0-9][A-Za-z0-9_-]{0,31}$/;

interface ZoneFormValues {
  code: string;
  name: string;
  description: string;
}

interface ZoneFormDrawerProps {
  companyId: string;
  /** `null` crea una zona nueva; si no, el formulario edita esta. */
  zone: ZoneView | null;
  onClose: () => void;
  onSaved: () => void;
}

const KNOWN_FIELDS = new Set<keyof ZoneFormValues>(["code", "name", "description"]);

/** Crear y editar comparten un formulario: la diferencia es a qué endpoint va el submit. */
export function ZoneFormDrawer({ companyId, zone, onClose, onSaved }: ZoneFormDrawerProps) {
  const isEdit = zone !== null;
  const [formError, setFormError] = useState<string | null>(null);
  const {
    register, handleSubmit, setError,
    formState: { errors, isDirty, isSubmitting },
  } = useForm<ZoneFormValues>({
    defaultValues: {
      code: zone?.code ?? "",
      name: zone?.name ?? "",
      description: zone?.description ?? "",
    },
  });

  async function onSubmit(values: ZoneFormValues) {
    setFormError(null);
    const request: ZoneRequest = {
      code: values.code.trim(),
      name: values.name.trim(),
      description: values.description.trim() || null,
    };

    try {
      if (isEdit) await updateZone(companyId, zone.id, request);
      else await createZone(companyId, request);
      onSaved();
    } catch (error) {
      setFormError(applyApiFieldErrors(error as ApiError, KNOWN_FIELDS, setError, t("Corrige los campos marcados.")));
    }
  }

  return (
    <FormDrawer
      open
      icon={<CropFreeRounded />}
      title={isEdit ? t("Editar zona") : t("Nueva zona")}
      subtitle={isEdit
        ? [zone.code, zone.name].filter(Boolean).join(" · ")
        : t("Área operativa para agrupar orígenes, destinos y rutas.")}
      titleAdornment={isEdit ? <ActiveBadge active={zone.active} /> : undefined}
      footerStart={isEdit ? <FormMeta>{t("Actualizado el {{date}}", { date: fmtDateTime(zone.updatedAt) })}</FormMeta> : undefined}
      size="md"
      onClose={onClose}
      dirty={isDirty}
      closeOnBackdrop={!isSubmitting}
      footer={
        <>
          <Button onClick={onClose} disabled={isSubmitting} color="inherit" sx={{ color: "text.secondary" }}>{t("Cancelar")}</Button>
          <Button type="submit" form={FORM_ID} variant="contained" startIcon={<CheckRounded />} disabled={isSubmitting}>
            {isSubmitting ? t("Guardando...") : isEdit ? t("Guardar cambios") : t("Crear zona")}
          </Button>
        </>
      }
    >
      <Box component="form" id={FORM_ID} onSubmit={(event) => void handleSubmit(onSubmit)(event)} noValidate>
        {formError && <Alert severity="error" sx={{ mb: 1 }}>{formError}</Alert>}

        <FormSection title={t("Identificación")} help={t("Cómo se reconoce la zona al asignarla a ubicaciones y rutas.")}>
          <FormRow template="180px minmax(0, 1fr)">
            <TextField
              label={t("Código")} required fullWidth size="small"
              error={Boolean(errors.code)} helperText={errors.code?.message}
              {...register("code", {
                required: t("Este campo es obligatorio"),
                maxLength: { value: 32, message: t("No puede superar los {{count}} caracteres", { count: 32 }) },
                pattern: { value: CODE_PATTERN, message: t("Solo letras, dígitos, guion bajo o guion") },
              })}
            />
            <TextField
              label={t("Nombre")} required fullWidth size="small"
              error={Boolean(errors.name)} helperText={errors.name?.message}
              {...register("name", {
                required: t("Este campo es obligatorio"),
                maxLength: { value: 200, message: t("No puede superar los {{count}} caracteres", { count: 200 }) },
              })}
            />
          </FormRow>
          <TextField
            label={t("Descripción")} fullWidth size="small" multiline rows={3}
            error={Boolean(errors.description)} helperText={errors.description?.message}
            {...register("description", {
              maxLength: { value: 1000, message: t("No puede superar los {{count}} caracteres", { count: 1000 }) },
            })}
          />
        </FormSection>
      </Box>
    </FormDrawer>
  );
}
