import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { Controller, useForm } from "react-hook-form";
import { Alert, Box, Button, MenuItem, TextField } from "@mui/material";
import { BadgeRounded } from "@mui/icons-material";
import { applyApiFieldErrors } from "../../shared/api/formErrors";
import type { ApiError } from "../../shared/api/httpClient";
import { fetchCarriers } from "../../shared/api/carriersApi";
import { createDriver, updateDriver, type DriverRequest, type DriverView } from "../../shared/api/driversApi";
import { FormDrawer, SectionHeader } from "../../shared/ui/components";
import { t } from "../../lib/i18n";

const FORM_ID = "driver-form";

const CODE_PATTERN = /^[A-Za-z0-9][A-Za-z0-9_-]{0,31}$/;

interface DriverFormValues {
  code: string;
  firstName: string;
  lastName: string;
  documentType: string;
  documentNumber: string;
  phone: string;
  licenseNumber: string;
  licenseCategory: string;
  licenseExpiresOn: string;
  carrierId: string;
}

interface DriverFormDrawerProps {
  companyId: string;
  driver: DriverView | null;
  onClose: () => void;
  onSaved: () => void;
}

const KNOWN_FIELDS = new Set<keyof DriverFormValues>([
  "code", "firstName", "lastName", "documentType", "documentNumber", "phone",
  "licenseNumber", "licenseCategory", "licenseExpiresOn", "carrierId",
]);

/**
 * Alta y edición de un conductor.
 *
 * La fecha de vencimiento de la licencia es opcional porque hay operaciones que no la registran;
 * cuando está, el backend deriva de ella el estado de la licencia y decide con él si el
 * conductor puede asignarse a un viaje. Aquí no se recalcula esa comparación: el navegador
 * podría restar dos fechas igual de fácil, y ese es justo el problema — una segunda copia de la
 * regla acabaría pintando en verde a alguien a quien el endpoint rechaza.
 */
export function DriverFormDrawer({ companyId, driver, onClose, onSaved }: DriverFormDrawerProps) {
  const isEdit = driver !== null;
  const [formError, setFormError] = useState<string | null>(null);

  const carriersQuery = useQuery({
    queryKey: ["carriers-for-driver-form", companyId],
    queryFn: ({ signal }) => fetchCarriers({ companyId, size: 200, active: true, sort: "code,asc", signal }),
  });

  const {
    register, control, handleSubmit, setError,
    formState: { errors, isDirty, isSubmitting },
  } = useForm<DriverFormValues>({
    defaultValues: {
      code: driver?.code ?? "",
      firstName: driver?.firstName ?? "",
      lastName: driver?.lastName ?? "",
      documentType: driver?.documentType ?? "DNI",
      documentNumber: driver?.documentNumber ?? "",
      phone: driver?.phone ?? "",
      licenseNumber: driver?.licenseNumber ?? "",
      licenseCategory: driver?.licenseCategory ?? "",
      licenseExpiresOn: driver?.licenseExpiresOn ?? "",
      carrierId: driver?.carrierId ?? "",
    },
  });

  async function onSubmit(values: DriverFormValues) {
    setFormError(null);
    const request: DriverRequest = {
      code: values.code.trim(),
      firstName: values.firstName.trim(),
      lastName: values.lastName.trim(),
      documentType: values.documentType.trim(),
      documentNumber: values.documentNumber.trim(),
      phone: values.phone.trim() || null,
      licenseNumber: values.licenseNumber.trim(),
      licenseCategory: values.licenseCategory.trim() || null,
      licenseExpiresOn: values.licenseExpiresOn.trim() || null,
      carrierId: values.carrierId || null,
    };

    try {
      if (isEdit) await updateDriver(companyId, driver.id, request);
      else await createDriver(companyId, request);
      onSaved();
    } catch (error) {
      setFormError(applyApiFieldErrors(error as ApiError, KNOWN_FIELDS, setError, t("Corrige los campos marcados.")));
    }
  }

  const grid2 = { display: "grid", gap: 2, gridTemplateColumns: { xs: "1fr", sm: "1fr 1fr" }, mb: 3 } as const;

  return (
    <FormDrawer
      open
      icon={<BadgeRounded />}
      title={isEdit ? t("Editar conductor") : t("Nuevo conductor")}
      subtitle={t("La persona que conduce, con su documento y su licencia.")}
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
        <Box sx={grid2}>
          <TextField
            label={t("Código")} required size="small" fullWidth
            error={Boolean(errors.code)} helperText={errors.code?.message}
            {...register("code", {
              required: t("Este campo es obligatorio"),
              maxLength: { value: 32, message: t("No puede superar los {{count}} caracteres", { count: 32 }) },
              pattern: { value: CODE_PATTERN, message: t("Solo letras, dígitos, guion bajo o guion") },
            })}
          />
          <Controller
            control={control}
            name="carrierId"
            render={({ field }) => (
              <TextField
                select label={t("Transportista")} size="small" fullWidth
                value={field.value} onChange={(e) => field.onChange(e.target.value)}
              >
                <MenuItem value="">{t("Flota propia")}</MenuItem>
                {(carriersQuery.data?.content ?? []).map((carrier) => (
                  <MenuItem key={carrier.id} value={carrier.id}>{carrier.code} · {carrier.businessName}</MenuItem>
                ))}
              </TextField>
            )}
          />
          <TextField
            label={t("Nombres")} required size="small" fullWidth
            error={Boolean(errors.firstName)} helperText={errors.firstName?.message}
            {...register("firstName", {
              required: t("Este campo es obligatorio"),
              maxLength: { value: 100, message: t("No puede superar los {{count}} caracteres", { count: 100 }) },
            })}
          />
          <TextField
            label={t("Apellidos")} required size="small" fullWidth
            error={Boolean(errors.lastName)} helperText={errors.lastName?.message}
            {...register("lastName", {
              required: t("Este campo es obligatorio"),
              maxLength: { value: 100, message: t("No puede superar los {{count}} caracteres", { count: 100 }) },
            })}
          />
        </Box>

        <SectionHeader title={t("Documento")} />
        <Box sx={grid2}>
          <TextField
            label={t("Tipo de documento")} required size="small" fullWidth placeholder={t("RUC, DNI, ...")}
            error={Boolean(errors.documentType)} helperText={errors.documentType?.message}
            {...register("documentType", { required: t("Este campo es obligatorio") })}
          />
          <TextField
            label={t("Número de documento")} required size="small" fullWidth
            error={Boolean(errors.documentNumber)} helperText={errors.documentNumber?.message}
            {...register("documentNumber", { required: t("Este campo es obligatorio") })}
          />
          <TextField
            label={t("Teléfono")} size="small" fullWidth
            error={Boolean(errors.phone)} helperText={errors.phone?.message}
            {...register("phone", {
              maxLength: { value: 40, message: t("No puede superar los {{count}} caracteres", { count: 40 }) },
            })}
          />
        </Box>

        <SectionHeader title={t("Licencia")} />
        <Box sx={grid2}>
          <TextField
            label={t("Número de licencia")} required size="small" fullWidth
            error={Boolean(errors.licenseNumber)} helperText={errors.licenseNumber?.message}
            {...register("licenseNumber", { required: t("Este campo es obligatorio") })}
          />
          <TextField
            label={t("Categoría")} size="small" fullWidth
            error={Boolean(errors.licenseCategory)} helperText={errors.licenseCategory?.message}
            {...register("licenseCategory", {
              maxLength: { value: 20, message: t("No puede superar los {{count}} caracteres", { count: 20 }) },
            })}
          />
          <TextField
            label={t("Vence el")} size="small" fullWidth type="date"
            slotProps={{ inputLabel: { shrink: true } }}
            helperText={t("El último día en que la licencia es válida. Déjalo vacío si no se registra.")}
            {...register("licenseExpiresOn")}
          />
        </Box>
      </Box>
    </FormDrawer>
  );
}
