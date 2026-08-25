import { useState } from "react";
import { useForm } from "react-hook-form";
import { Alert, Box, Button, TextField } from "@mui/material";
import { BusinessRounded } from "@mui/icons-material";
import { applyApiFieldErrors } from "../../shared/api/formErrors";
import type { ApiError } from "../../shared/api/httpClient";
import { createCarrier, updateCarrier, type CarrierRequest, type CarrierView } from "../../shared/api/carriersApi";
import { FormDrawer, SectionHeader } from "../../shared/ui/components";
import { t } from "../../lib/i18n";

const FORM_ID = "carrier-form";

/** Casa con la restricción de `code` del backend; vive junto al campo que valida. */
const CODE_PATTERN = /^[A-Za-z0-9][A-Za-z0-9_-]{0,31}$/;
const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

interface CarrierFormValues {
  code: string;
  businessName: string;
  taxIdType: string;
  taxIdValue: string;
  contactName: string;
  phone: string;
  email: string;
  externalReference: string;
}

interface CarrierFormDrawerProps {
  companyId: string;
  /** `null` crea un transportista nuevo; si no, el formulario edita este. */
  carrier: CarrierView | null;
  onClose: () => void;
  onSaved: () => void;
}

const KNOWN_FIELDS = new Set<keyof CarrierFormValues>([
  "code", "businessName", "taxIdType", "taxIdValue", "contactName", "phone", "email", "externalReference",
]);

/**
 * Alta y edición de un transportista.
 *
 * No hay campo `active`: activar es su propio endpoint, de modo que retirar a un transportista
 * de la operación nunca sea un efecto colateral de corregirle el teléfono.
 */
export function CarrierFormDrawer({ companyId, carrier, onClose, onSaved }: CarrierFormDrawerProps) {
  const isEdit = carrier !== null;
  const [formError, setFormError] = useState<string | null>(null);
  const {
    register, handleSubmit, setError,
    formState: { errors, isDirty, isSubmitting },
  } = useForm<CarrierFormValues>({
    defaultValues: {
      code: carrier?.code ?? "",
      businessName: carrier?.businessName ?? "",
      taxIdType: carrier?.taxIdType ?? "RUC",
      taxIdValue: carrier?.taxIdValue ?? "",
      contactName: carrier?.contactName ?? "",
      phone: carrier?.phone ?? "",
      email: carrier?.email ?? "",
      externalReference: carrier?.externalReference ?? "",
    },
  });

  async function onSubmit(values: CarrierFormValues) {
    setFormError(null);
    const request: CarrierRequest = {
      code: values.code.trim(),
      businessName: values.businessName.trim(),
      taxIdType: values.taxIdType.trim(),
      taxIdValue: values.taxIdValue.trim(),
      contactName: values.contactName.trim() || null,
      phone: values.phone.trim() || null,
      email: values.email.trim() || null,
      externalReference: values.externalReference.trim() || null,
    };

    try {
      if (isEdit) await updateCarrier(companyId, carrier.id, request);
      else await createCarrier(companyId, request);
      onSaved();
    } catch (error) {
      setFormError(applyApiFieldErrors(error as ApiError, KNOWN_FIELDS, setError, t("Corrige los campos marcados.")));
    }
  }

  const grid = { display: "grid", gap: 2, gridTemplateColumns: { xs: "1fr", sm: "1fr 1fr" }, mb: 3 } as const;

  return (
    <FormDrawer
      open
      icon={<BusinessRounded />}
      title={isEdit ? t("Editar transportista") : t("Nuevo transportista")}
      subtitle={t("La empresa que pone los vehículos y los conductores.")}
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

        <SectionHeader title={t("Identificación")} />
        <Box sx={grid}>
          <TextField
            label={t("Código")} required size="small" fullWidth
            error={Boolean(errors.code)} helperText={errors.code?.message}
            {...register("code", {
              required: t("Este campo es obligatorio"),
              maxLength: { value: 32, message: t("No puede superar los {{count}} caracteres", { count: 32 }) },
              pattern: { value: CODE_PATTERN, message: t("Solo letras, dígitos, guion bajo o guion") },
            })}
          />
          <TextField
            label={t("Razón social")} required size="small" fullWidth
            error={Boolean(errors.businessName)} helperText={errors.businessName?.message}
            {...register("businessName", {
              required: t("Este campo es obligatorio"),
              maxLength: { value: 200, message: t("No puede superar los {{count}} caracteres", { count: 200 }) },
            })}
          />
        </Box>

        <SectionHeader title={t("Documento")} />
        <Box sx={grid}>
          <TextField
            label={t("Tipo de documento")} required size="small" fullWidth placeholder={t("RUC, DNI, ...")}
            error={Boolean(errors.taxIdType)} helperText={errors.taxIdType?.message}
            {...register("taxIdType", {
              required: t("Este campo es obligatorio"),
              maxLength: { value: 20, message: t("No puede superar los {{count}} caracteres", { count: 20 }) },
            })}
          />
          <TextField
            label={t("Número de documento")} required size="small" fullWidth
            error={Boolean(errors.taxIdValue)} helperText={errors.taxIdValue?.message}
            {...register("taxIdValue", {
              required: t("Este campo es obligatorio"),
              maxLength: { value: 32, message: t("No puede superar los {{count}} caracteres", { count: 32 }) },
            })}
          />
        </Box>

        <SectionHeader title={t("Contacto")} />
        <Box sx={grid}>
          <TextField
            label={t("Nombre de contacto")} size="small" fullWidth
            error={Boolean(errors.contactName)} helperText={errors.contactName?.message}
            {...register("contactName", {
              maxLength: { value: 200, message: t("No puede superar los {{count}} caracteres", { count: 200 }) },
            })}
          />
          <TextField
            label={t("Teléfono")} size="small" fullWidth
            error={Boolean(errors.phone)} helperText={errors.phone?.message}
            {...register("phone", {
              maxLength: { value: 40, message: t("No puede superar los {{count}} caracteres", { count: 40 }) },
            })}
          />
          <TextField
            label={t("Correo electrónico")} size="small" fullWidth type="email"
            error={Boolean(errors.email)} helperText={errors.email?.message}
            {...register("email", {
              pattern: { value: EMAIL_PATTERN, message: t("Ingresa un correo electrónico válido") },
              maxLength: { value: 200, message: t("No puede superar los {{count}} caracteres", { count: 200 }) },
            })}
          />
          <TextField
            label={t("Referencia externa")} size="small" fullWidth
            placeholder={t("Código opcional de EWM u otro sistema")}
            error={Boolean(errors.externalReference)} helperText={errors.externalReference?.message}
            {...register("externalReference", {
              maxLength: { value: 100, message: t("No puede superar los {{count}} caracteres", { count: 100 }) },
            })}
          />
        </Box>
      </Box>
    </FormDrawer>
  );
}
