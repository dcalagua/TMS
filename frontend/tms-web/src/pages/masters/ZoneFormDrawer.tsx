import { useState } from "react";
import { useForm } from "react-hook-form";
import { Alert, Box, Button, TextField } from "@mui/material";
import { CropFreeRounded } from "@mui/icons-material";
import type { ApiError } from "../../shared/api/httpClient";
import { applyApiFieldErrors } from "../../shared/api/formErrors";
import { createZone, updateZone, type ZoneRequest, type ZoneView } from "../../shared/api/zonesApi";
import { FormDrawer } from "../../shared/ui/components";
import { t } from "../../lib/i18n";

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
      subtitle={t("Área operativa para agrupar orígenes, destinos y rutas.")}
      size="md"
      onClose={onClose}
      dirty={isDirty}
      closeOnBackdrop={!isSubmitting}
      footer={
        <>
          <Button onClick={onClose} disabled={isSubmitting}>{t("Cancelar")}</Button>
          <Button type="submit" form={FORM_ID} variant="contained" disabled={isSubmitting}>
            {isSubmitting ? t("Guardando...") : t("Guardar")}
          </Button>
        </>
      }
    >
      <Box component="form" id={FORM_ID} onSubmit={(event) => void handleSubmit(onSubmit)(event)} noValidate>
        {formError && <Alert severity="error" sx={{ mb: 2 }}>{formError}</Alert>}

        <Box sx={{ display: "grid", gap: 2, gridTemplateColumns: { xs: "1fr", sm: "1fr 2fr" }, mb: 2 }}>
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
        </Box>

        <TextField
          label={t("Descripción")} fullWidth size="small" multiline rows={3}
          error={Boolean(errors.description)} helperText={errors.description?.message}
          {...register("description", {
            maxLength: { value: 1000, message: t("No puede superar los {{count}} caracteres", { count: 1000 }) },
          })}
        />
      </Box>
    </FormDrawer>
  );
}
