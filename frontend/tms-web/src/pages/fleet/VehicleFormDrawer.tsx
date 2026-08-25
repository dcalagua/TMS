import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { Controller, useForm, useWatch, type Validate } from "react-hook-form";
import { Alert, Box, Button, MenuItem, TextField, Typography } from "@mui/material";
import { LocalShippingRounded } from "@mui/icons-material";
import { applyApiFieldErrors } from "../../shared/api/formErrors";
import type { ApiError } from "../../shared/api/httpClient";
import { fetchCarriers } from "../../shared/api/carriersApi";
import { fetchVehicleTypes } from "../../shared/api/vehicleTypesApi";
import {
  createVehicle, updateVehicle, VEHICLE_AVAILABILITY_STATUSES,
  type VehicleAvailabilityStatus, type VehicleRequest, type VehicleView,
} from "../../shared/api/vehiclesApi";
import { FormDrawer, SectionHeader } from "../../shared/ui/components";
import { enumLabel } from "../../lib/enums";
import { t } from "../../lib/i18n";
import { fmtDecimal } from "../../lib/locale";

const FORM_ID = "vehicle-form";

const CODE_PATTERN = /^[A-Za-z0-9][A-Za-z0-9_-]{0,31}$/;
/** Cuatro a doce caracteres: letras, dígitos o guion. La misma regla que el backend. */
const PLATE_PATTERN = /^[A-Za-z0-9-]{4,12}$/;

interface VehicleFormValues {
  code: string;
  licensePlate: string;
  carrierId: string;
  vehicleTypeId: string;
  maxWeightOverrideKg: string;
  maxVolumeOverrideM3: string;
  maxPalletsOverride: string;
  availabilityStatus: VehicleAvailabilityStatus;
  externalReference: string;
}

interface VehicleFormDrawerProps {
  companyId: string;
  vehicle: VehicleView | null;
  onClose: () => void;
  onSaved: () => void;
}

const KNOWN_FIELDS = new Set<keyof VehicleFormValues>([
  "code", "licensePlate", "carrierId", "vehicleTypeId", "maxWeightOverrideKg", "maxVolumeOverrideM3",
  "maxPalletsOverride", "availabilityStatus", "externalReference",
]);

/**
 * Alta y edición de un vehículo.
 *
 * Las tres capacidades son *overrides*, no valores: dejarlas vacías significa "usa la del tipo
 * de vehículo", que es lo correcto para casi toda la flota. Los placeholders muestran lo que se
 * heredaría, así que el operador ve el número que va a aplicar antes de decidir sobrescribirlo.
 * Quien resuelve la capacidad efectiva es el backend (`EffectiveCapacityResolver`); aquí no se
 * recalcula nada.
 */
export function VehicleFormDrawer({ companyId, vehicle, onClose, onSaved }: VehicleFormDrawerProps) {
  const isEdit = vehicle !== null;
  const [formError, setFormError] = useState<string | null>(null);

  const carriersQuery = useQuery({
    queryKey: ["carriers-for-vehicle-form", companyId],
    queryFn: ({ signal }) => fetchCarriers({ companyId, size: 200, active: true, sort: "code,asc", signal }),
  });
  const typesQuery = useQuery({
    queryKey: ["vehicle-types-for-vehicle-form", companyId],
    queryFn: ({ signal }) => fetchVehicleTypes({ companyId, size: 200, active: true, sort: "code,asc", signal }),
  });

  const {
    register, control, handleSubmit, setError,
    formState: { errors, isDirty, isSubmitting },
  } = useForm<VehicleFormValues>({
    defaultValues: {
      code: vehicle?.code ?? "",
      licensePlate: vehicle?.licensePlate ?? "",
      carrierId: vehicle?.carrierId ?? "",
      vehicleTypeId: vehicle?.vehicleTypeId ?? "",
      maxWeightOverrideKg: vehicle?.maxWeightOverrideKg?.toString() ?? "",
      maxVolumeOverrideM3: vehicle?.maxVolumeOverrideM3?.toString() ?? "",
      maxPalletsOverride: vehicle?.maxPalletsOverride?.toString() ?? "",
      availabilityStatus: vehicle?.availabilityStatus ?? "AVAILABLE",
      externalReference: vehicle?.externalReference ?? "",
    },
  });

  const selectedTypeId = useWatch({ control, name: "vehicleTypeId" });
  const selectedType = (typesQuery.data?.content ?? []).find((type) => type.id === selectedTypeId);

  const optionalPositive: Validate<string, VehicleFormValues> = (value) => {
    if (value.trim() === "") return true;
    const parsed = Number(value);
    if (Number.isNaN(parsed)) return t("Debe ser un número");
    return parsed > 0 || t("Debe ser un número mayor que cero");
  };

  async function onSubmit(values: VehicleFormValues) {
    setFormError(null);
    const request: VehicleRequest = {
      code: values.code.trim(),
      licensePlate: values.licensePlate.trim().toUpperCase(),
      carrierId: values.carrierId || null,
      vehicleTypeId: values.vehicleTypeId,
      maxWeightOverrideKg: values.maxWeightOverrideKg.trim() === "" ? null : Number(values.maxWeightOverrideKg),
      maxVolumeOverrideM3: values.maxVolumeOverrideM3.trim() === "" ? null : Number(values.maxVolumeOverrideM3),
      maxPalletsOverride: values.maxPalletsOverride.trim() === "" ? null : Number(values.maxPalletsOverride),
      availabilityStatus: values.availabilityStatus,
      externalReference: values.externalReference.trim() || null,
    };

    try {
      if (isEdit) await updateVehicle(companyId, vehicle.id, request);
      else await createVehicle(companyId, request);
      onSaved();
    } catch (error) {
      setFormError(applyApiFieldErrors(error as ApiError, KNOWN_FIELDS, setError, t("Corrige los campos marcados.")));
    }
  }

  const grid2 = { display: "grid", gap: 2, gridTemplateColumns: { xs: "1fr", sm: "1fr 1fr" }, mb: 3 } as const;
  const grid3 = { display: "grid", gap: 2, gridTemplateColumns: { xs: "1fr", sm: "repeat(3, 1fr)" }, mb: 2 } as const;

  return (
    <FormDrawer
      open
      icon={<LocalShippingRounded />}
      title={isEdit ? t("Editar vehículo") : t("Nuevo vehículo")}
      subtitle={t("Una unidad concreta de la flota, con su placa y su capacidad efectiva.")}
      size="lg"
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
          <TextField
            label={t("Placa")} required size="small" fullWidth
            error={Boolean(errors.licensePlate)} helperText={errors.licensePlate?.message}
            {...register("licensePlate", {
              required: t("Este campo es obligatorio"),
              pattern: { value: PLATE_PATTERN, message: t("De 4 a 12 caracteres: letras, dígitos o guion") },
            })}
          />
        </Box>

        <SectionHeader title={t("Asignación")} />
        <Box sx={grid2}>
          <Controller
            control={control}
            name="vehicleTypeId"
            rules={{ required: t("Este campo es obligatorio") }}
            render={({ field }) => (
              <TextField
                select label={t("Tipo de vehículo")} required size="small" fullWidth
                value={field.value} onChange={(e) => field.onChange(e.target.value)}
                error={Boolean(errors.vehicleTypeId)} helperText={errors.vehicleTypeId?.message}
              >
                <MenuItem value="">{t("Selecciona un tipo de vehículo")}</MenuItem>
                {(typesQuery.data?.content ?? []).map((type) => (
                  <MenuItem key={type.id} value={type.id}>{type.code} · {type.name}</MenuItem>
                ))}
              </TextField>
            )}
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
          <Controller
            control={control}
            name="availabilityStatus"
            render={({ field }) => (
              <TextField
                select label={t("Disponibilidad")} required size="small" fullWidth
                value={field.value} onChange={(e) => field.onChange(e.target.value as VehicleAvailabilityStatus)}
              >
                {VEHICLE_AVAILABILITY_STATUSES.map((status) => (
                  <MenuItem key={status} value={status}>{enumLabel("vehicleAvailability", status)}</MenuItem>
                ))}
              </TextField>
            )}
          />
          <TextField
            label={t("Referencia externa")} size="small" fullWidth
            error={Boolean(errors.externalReference)} helperText={errors.externalReference?.message}
            {...register("externalReference", {
              maxLength: { value: 100, message: t("No puede superar los {{count}} caracteres", { count: 100 }) },
            })}
          />
        </Box>

        <SectionHeader title={t("Capacidades")} />
        <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>
          {t("Déjalas vacías para heredar la capacidad del tipo de vehículo. Solo rellena las que esta unidad concreta contradiga.")}
        </Typography>
        <Box sx={grid3}>
          <TextField
            label={t("Peso propio (kg)")} size="small" fullWidth type="number"
            placeholder={selectedType ? fmtDecimal(selectedType.maxWeightKg) : ""}
            slotProps={{ inputLabel: { shrink: true } }}
            error={Boolean(errors.maxWeightOverrideKg)} helperText={errors.maxWeightOverrideKg?.message}
            {...register("maxWeightOverrideKg", { validate: optionalPositive })}
          />
          <TextField
            label={t("Volumen propio (m³)")} size="small" fullWidth type="number"
            placeholder={selectedType ? fmtDecimal(selectedType.maxVolumeM3) : ""}
            slotProps={{ inputLabel: { shrink: true } }}
            error={Boolean(errors.maxVolumeOverrideM3)} helperText={errors.maxVolumeOverrideM3?.message}
            {...register("maxVolumeOverrideM3", { validate: optionalPositive })}
          />
          <TextField
            label={t("Pallets propios")} size="small" fullWidth type="number"
            placeholder={selectedType ? String(selectedType.maxPallets) : ""}
            slotProps={{ inputLabel: { shrink: true } }}
            error={Boolean(errors.maxPalletsOverride)} helperText={errors.maxPalletsOverride?.message}
            {...register("maxPalletsOverride", { validate: optionalPositive })}
          />
        </Box>
      </Box>
    </FormDrawer>
  );
}
