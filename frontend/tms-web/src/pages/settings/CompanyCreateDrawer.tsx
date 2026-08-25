import { useState } from "react";
import { useForm } from "react-hook-form";
import { Alert, Autocomplete, Box, Button, TextField, Typography } from "@mui/material";
import { AddBusinessRounded } from "@mui/icons-material";
import { applyApiFieldErrors } from "../../shared/api/formErrors";
import type { ApiError } from "../../shared/api/httpClient";
import { createCompany, type CompanyCreateRequest } from "../../shared/api/administrationApi";
import { FormDrawer } from "../../shared/ui/components";
import { t } from "../../lib/i18n";

const FORM_ID = "company-create-form";

const CODE_PATTERN = /^[A-Za-z0-9][A-Za-z0-9_-]{0,31}$/;

const TIME_ZONES: string[] = typeof Intl.supportedValuesOf === "function" ? Intl.supportedValuesOf("timeZone") : [];

interface CompanyCreateFormValues {
  code: string;
  name: string;
  taxIdentifier: string;
  timeZone: string;
}

interface CompanyCreateDrawerProps {
  companyId: string;
  onClose: () => void;
  onCreated: () => void;
}

const KNOWN_FIELDS = new Set<keyof CompanyCreateFormValues>(["code", "name", "taxIdentifier", "timeZone"]);

/**
 * Dar de alta otra empresa dentro de la misma organización.
 *
 * No se pide la organización: se usa la de quien llama. Ofrecer un selector daría a entender que
 * se puede crear una empresa en la organización de otro, que es exactamente lo que el backend
 * impide.
 *
 * El código sí se pide aquí y no se vuelve a poder cambiar: es la clave con la que la nombrarán
 * las integraciones, y una clave que cambia no es una clave.
 */
export function CompanyCreateDrawer({ companyId, onClose, onCreated }: CompanyCreateDrawerProps) {
  const [formError, setFormError] = useState<string | null>(null);

  const {
    register, handleSubmit, setError, setValue, watch,
    formState: { errors, isDirty, isSubmitting },
  } = useForm<CompanyCreateFormValues>({
    defaultValues: { code: "", name: "", taxIdentifier: "", timeZone: "America/Lima" },
  });

  async function onSubmit(values: CompanyCreateFormValues) {
    setFormError(null);
    const request: CompanyCreateRequest = {
      code: values.code.trim(),
      name: values.name.trim(),
      taxIdentifier: values.taxIdentifier.trim() || null,
      timeZone: values.timeZone.trim(),
    };

    try {
      await createCompany(companyId, request);
      onCreated();
    } catch (error) {
      setFormError(applyApiFieldErrors(error as ApiError, KNOWN_FIELDS, setError, t("Corrige los campos marcados.")));
    }
  }

  return (
    <FormDrawer
      open
      icon={<AddBusinessRounded />}
      title={t("Nueva empresa")}
      subtitle={t("Se crea dentro de tu misma organización.")}
      size="md"
      onClose={onClose}
      dirty={isDirty}
      closeOnBackdrop={!isSubmitting}
      footer={
        <>
          <Button onClick={onClose} disabled={isSubmitting}>{t("Cancelar")}</Button>
          <Button type="submit" form={FORM_ID} variant="contained" disabled={isSubmitting}>
            {isSubmitting ? t("Guardando...") : t("Crear empresa")}
          </Button>
        </>
      }
    >
      <Box component="form" id={FORM_ID} onSubmit={(event) => void handleSubmit(onSubmit)(event)} noValidate>
        {formError && <Alert severity="error" sx={{ mb: 2 }}>{formError}</Alert>}

        <Box sx={{ display: "grid", gap: 2 }}>
          <TextField
            label={t("Código")} required size="small" fullWidth
            helperText={errors.code?.message ?? t("No se puede cambiar después: es la clave con la que la nombran las integraciones.")}
            error={Boolean(errors.code)}
            {...register("code", {
              required: t("Este campo es obligatorio"),
              maxLength: { value: 32, message: t("No puede superar los {{count}} caracteres", { count: 32 }) },
              pattern: { value: CODE_PATTERN, message: t("Solo letras, dígitos, guion bajo o guion") },
            })}
          />
          <TextField
            label={t("Nombre")} required size="small" fullWidth
            error={Boolean(errors.name)} helperText={errors.name?.message}
            {...register("name", { required: t("Este campo es obligatorio") })}
          />
          <TextField
            label={t("RUC")} size="small" fullWidth
            error={Boolean(errors.taxIdentifier)} helperText={errors.taxIdentifier?.message}
            {...register("taxIdentifier")}
          />
          <Autocomplete
            freeSolo
            size="small"
            options={TIME_ZONES}
            value={watch("timeZone")}
            onChange={(_e, next) => setValue("timeZone", next ?? "", { shouldDirty: true })}
            onInputChange={(_e, next) => setValue("timeZone", next, { shouldDirty: true })}
            renderInput={(params) => (
              <TextField
                {...params}
                label={t("Zona horaria")} required placeholder="America/Lima"
                error={Boolean(errors.timeZone)} helperText={errors.timeZone?.message}
              />
            )}
          />
          <input type="hidden" {...register("timeZone", { required: t("Este campo es obligatorio") })} />
        </Box>

        <Typography variant="caption" color="text.secondary" sx={{ display: "block", mt: 2 }}>
          {t("La zona horaria decide a qué día operativo pertenece una fecha de servicio.")}
        </Typography>
      </Box>
    </FormDrawer>
  );
}
