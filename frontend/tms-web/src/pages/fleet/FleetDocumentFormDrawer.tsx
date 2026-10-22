import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { Controller, useForm, useWatch } from "react-hook-form";
import { Alert, Box, Button, MenuItem, TextField } from "@mui/material";
import { CheckRounded, VerifiedUserRounded } from "@mui/icons-material";
import { applyApiFieldErrors } from "../../shared/api/formErrors";
import type { ApiError } from "../../shared/api/httpClient";
import { fetchDrivers } from "../../shared/api/driversApi";
import { fetchVehicles } from "../../shared/api/vehiclesApi";
import {
  createFleetDocument, FLEET_DOCUMENT_TYPES, updateFleetDocument,
  type FleetDocumentRequest, type FleetDocumentSubject, type FleetDocumentType, type FleetDocumentView,
} from "../../shared/api/fleetDocumentsApi";
import { FormDateInput, FormDrawer, FormRow, FormSection } from "../../shared/ui/components";
import { enumLabel } from "../../lib/enums";
import { t } from "../../lib/i18n";

const FORM_ID = "fleet-document-form";

interface Values {
  subjectType: FleetDocumentSubject;
  subjectId: string;
  documentType: FleetDocumentType | "";
  documentNumber: string;
  issuer: string;
  issuedOn: string;
  expiresOn: string;
  notes: string;
}

const KNOWN_FIELDS = new Set<keyof Values>([
  "subjectType", "subjectId", "documentType", "documentNumber", "issuer", "issuedOn", "expiresOn", "notes",
]);

interface Props {
  companyId: string;
  /** Edición: el documento. Alta: null. */
  document: FleetDocumentView | null;
  /** Desde el drawer de un vehículo o conductor: el sujeto viene fijado y no se elige. */
  fixedSubject?: { subjectType: FleetDocumentSubject; subjectId: string; label: string };
  onClose: () => void;
  onSaved: () => void;
}

/**
 * Alta y edición de un papel de flota (V72).
 *
 * El sujeto se fija al crear y no cambia después: una matrícula no pasa de un camión a otro. El
 * vencimiento es opcional a propósito, y el texto de ayuda dice lo que significa dejarlo vacío:
 * «sin registrar» avisa si el tipo es obligatorio, y nunca bloquea un viaje.
 */
export function FleetDocumentFormDrawer({ companyId, document, fixedSubject, onClose, onSaved }: Props) {
  const isEdit = document !== null;
  const [formError, setFormError] = useState<string | null>(null);
  const subjectLocked = isEdit || fixedSubject !== undefined;

  const { register, control, handleSubmit, setError, setValue, formState: { errors, isDirty, isSubmitting } } =
    useForm<Values>({
      defaultValues: {
        subjectType: document?.subjectType ?? fixedSubject?.subjectType ?? "VEHICLE",
        subjectId: document?.subjectId ?? fixedSubject?.subjectId ?? "",
        documentType: document?.documentType ?? "",
        documentNumber: document?.documentNumber ?? "",
        issuer: document?.issuer ?? "",
        issuedOn: document?.issuedOn ?? "",
        expiresOn: document?.expiresOn ?? "",
        notes: document?.notes ?? "",
      },
    });
  const subjectType = useWatch({ control, name: "subjectType" });

  const vehiclesQuery = useQuery({
    queryKey: ["vehicles-for-fleet-document", companyId],
    queryFn: ({ signal }) => fetchVehicles({ companyId, size: 200, active: true, sort: "code,asc", signal }),
    enabled: companyId !== "" && !subjectLocked && subjectType === "VEHICLE",
  });
  const driversQuery = useQuery({
    queryKey: ["drivers-for-fleet-document", companyId],
    queryFn: ({ signal }) => fetchDrivers({ companyId, size: 200, active: true, sort: "code,asc", signal }),
    enabled: companyId !== "" && !subjectLocked && subjectType === "DRIVER",
  });

  async function onSubmit(values: Values) {
    setFormError(null);
    const request: FleetDocumentRequest = {
      subjectType: values.subjectType,
      subjectId: values.subjectId,
      documentType: values.documentType as FleetDocumentType,
      documentNumber: values.documentNumber.trim() || null,
      issuer: values.issuer.trim() || null,
      issuedOn: values.issuedOn || null,
      expiresOn: values.expiresOn || null,
      notes: values.notes.trim() || null,
    };
    try {
      if (isEdit) await updateFleetDocument(companyId, document.id, request);
      else await createFleetDocument(companyId, request);
      onSaved();
    } catch (error) {
      setFormError(applyApiFieldErrors(error as ApiError, KNOWN_FIELDS, setError, t("Corrige los campos marcados.")));
    }
  }

  const subjectLabel = fixedSubject?.label
    ?? (document ? [document.subjectCode, document.subjectName].filter(Boolean).join(" · ") : undefined);

  return (
    <FormDrawer
      open
      icon={<VerifiedUserRounded />}
      title={isEdit ? t("Editar documento") : t("Nuevo documento de flota")}
      subtitle={subjectLabel ?? t("Un papel de un vehículo o de un conductor, con su vencimiento.")}
      size="md"
      onClose={onClose}
      dirty={isDirty}
      closeOnBackdrop={!isSubmitting}
      footer={
        <>
          <Button onClick={onClose} disabled={isSubmitting}>{t("Cancelar")}</Button>
          <Button type="submit" form={FORM_ID} variant="contained" startIcon={<CheckRounded />} disabled={isSubmitting}>
            {isSubmitting ? t("Guardando...") : isEdit ? t("Guardar cambios") : t("Registrar documento")}
          </Button>
        </>
      }
    >
      <Box component="form" id={FORM_ID} onSubmit={(event) => void handleSubmit(onSubmit)(event)} noValidate>
        {formError && <Alert severity="error" sx={{ mb: 1 }}>{formError}</Alert>}

        {!subjectLocked && (
          <FormSection title={t("De quién es")} help={t("Un papel es de un vehículo o de un conductor, y no cambia de dueño.")}>
            <FormRow template="180px minmax(0, 1fr)">
              <Controller
                control={control} name="subjectType"
                render={({ field }) => (
                  <TextField
                    select label={t("Sujeto")} size="small" fullWidth value={field.value}
                    onChange={(e) => {
                      field.onChange(e.target.value);
                      setValue("subjectId", "");
                      setValue("documentType", "");
                    }}
                  >
                    <MenuItem value="VEHICLE">{enumLabel("fleetDocumentSubject", "VEHICLE")}</MenuItem>
                    <MenuItem value="DRIVER">{enumLabel("fleetDocumentSubject", "DRIVER")}</MenuItem>
                  </TextField>
                )}
              />
              <Controller
                control={control} name="subjectId" rules={{ required: t("Este campo es obligatorio") }}
                render={({ field }) => (
                  <TextField
                    select required size="small" fullWidth value={field.value}
                    label={subjectType === "VEHICLE" ? t("Vehículo") : t("Conductor")}
                    onChange={(e) => field.onChange(e.target.value)}
                    error={Boolean(errors.subjectId)} helperText={errors.subjectId?.message}
                  >
                    <MenuItem value="">{t("Selecciona...")}</MenuItem>
                    {subjectType === "VEHICLE"
                      ? (vehiclesQuery.data?.content ?? []).map((v) => (
                        <MenuItem key={v.id} value={v.id}>{v.licensePlate} · {v.code}</MenuItem>))
                      : (driversQuery.data?.content ?? []).map((d) => (
                        <MenuItem key={d.id} value={d.id}>{d.code} · {d.fullName}</MenuItem>))}
                  </TextField>
                )}
              />
            </FormRow>
          </FormSection>
        )}

        <FormSection title={t("Documento")} help={t("Qué papel es, su número y quién lo emitió.")}>
          <FormRow>
            <Controller
              control={control} name="documentType" rules={{ required: t("Este campo es obligatorio") }}
              render={({ field }) => (
                <TextField
                  select label={t("Tipo de documento")} required size="small" fullWidth value={field.value}
                  onChange={(e) => field.onChange(e.target.value)}
                  error={Boolean(errors.documentType)} helperText={errors.documentType?.message}
                >
                  <MenuItem value="">{t("Selecciona un tipo")}</MenuItem>
                  {FLEET_DOCUMENT_TYPES[subjectType].map((type) => (
                    <MenuItem key={type} value={type}>{enumLabel("fleetDocumentType", type)}</MenuItem>
                  ))}
                </TextField>
              )}
            />
          </FormRow>
          <FormRow>
            <TextField
              label={t("Número")} size="small" fullWidth
              error={Boolean(errors.documentNumber)} helperText={errors.documentNumber?.message}
              {...register("documentNumber", {
                maxLength: { value: 60, message: t("No puede superar los {{count}} caracteres", { count: 60 }) },
              })}
            />
            <TextField
              label={t("Emisor")} size="small" fullWidth placeholder={t("ANT, ATM, aseguradora...")}
              error={Boolean(errors.issuer)} helperText={errors.issuer?.message}
              {...register("issuer", {
                maxLength: { value: 200, message: t("No puede superar los {{count}} caracteres", { count: 200 }) },
              })}
            />
          </FormRow>
        </FormSection>

        <FormSection
          title={t("Vigencia")}
          help={t("Sin vencimiento, el papel queda «sin registrar»: avisa si el tipo es obligatorio y nunca bloquea un viaje.")}
        >
          <FormRow>
            <FormDateInput control={control} name="issuedOn" mode="date" label={t("Emitido el")} size="small" fullWidth />
            <FormDateInput
              control={control} name="expiresOn" mode="date" label={t("Vence el")} size="small" fullWidth
              helperText={t("El último día en que el documento es válido.")}
            />
          </FormRow>
          <FormRow cols={1}>
            <TextField
              label={t("Notas")} size="small" fullWidth multiline minRows={2}
              error={Boolean(errors.notes)} helperText={errors.notes?.message}
              {...register("notes", {
                maxLength: { value: 500, message: t("No puede superar los {{count}} caracteres", { count: 500 }) },
              })}
            />
          </FormRow>
        </FormSection>
      </Box>
    </FormDrawer>
  );
}
