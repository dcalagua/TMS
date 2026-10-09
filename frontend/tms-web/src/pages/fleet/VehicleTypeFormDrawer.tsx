import { useState } from "react";
import { Controller, useForm, useWatch, type Validate } from "react-hook-form";
import {
  Alert, Box, Button, Checkbox, FormControlLabel, MenuItem, TextField, Typography,
} from "@mui/material";
import { AccountTreeRounded, CheckRounded } from "@mui/icons-material";
import { applyApiFieldErrors } from "../../shared/api/formErrors";
import type { ApiError } from "../../shared/api/httpClient";
import {
  createVehicleType, updateVehicleType, VEHICLE_BODY_TYPES,
  type VehicleBodyType, type VehicleTypeRequest, type VehicleTypeView,
} from "../../shared/api/vehicleTypesApi";
import { ActiveBadge, FormDrawer, FormMeta, FormRow, FormSection } from "../../shared/ui/components";
import { enumLabel } from "../../lib/enums";
import { t } from "../../lib/i18n";
import { fmtDateTime } from "../../lib/locale";

const FORM_ID = "vehicle-type-form";

const CODE_PATTERN = /^[A-Za-z0-9][A-Za-z0-9_-]{0,31}$/;

interface VehicleTypeFormValues {
  code: string;
  name: string;
  maxWeightKg: string;
  maxVolumeM3: string;
  maxPallets: string;
  lengthM: string;
  widthM: string;
  heightM: string;
  bodyType: VehicleBodyType | "";
  temperatureControlled: boolean;
  minTemperatureCelsius: string;
  maxTemperatureCelsius: string;
  axles: string;
}

interface VehicleTypeFormDrawerProps {
  companyId: string;
  vehicleType: VehicleTypeView | null;
  onClose: () => void;
  onSaved: () => void;
}

const KNOWN_FIELDS = new Set<keyof VehicleTypeFormValues>([
  "code", "name", "maxWeightKg", "maxVolumeM3", "maxPallets", "lengthM", "widthM", "heightM", "bodyType",
  "temperatureControlled", "minTemperatureCelsius", "maxTemperatureCelsius", "axles",
]);

/**
 * Alta y edición de un tipo de vehículo: la plantilla de capacidad que heredan los vehículos.
 *
 * Las tres capacidades son obligatorias y las tres viajan con su unidad en el nombre del campo:
 * un tipo de vehículo sin peso máximo no puede decirle a la planificación si una carga cabe, que
 * es lo único para lo que existe este maestro.
 *
 * Las temperaturas solo se admiten si la unidad es de temperatura controlada. El backend rechaza
 * la combinación contraria, y aquí se dice antes de enviar en lugar de dejar que el operador
 * descubra la regla por un 400.
 */
export function VehicleTypeFormDrawer({ companyId, vehicleType, onClose, onSaved }: VehicleTypeFormDrawerProps) {
  const isEdit = vehicleType !== null;
  const [formError, setFormError] = useState<string | null>(null);
  const {
    register, control, handleSubmit, setError,
    formState: { errors, isDirty, isSubmitting },
  } = useForm<VehicleTypeFormValues>({
    defaultValues: {
      code: vehicleType?.code ?? "",
      name: vehicleType?.name ?? "",
      maxWeightKg: vehicleType?.maxWeightKg?.toString() ?? "",
      maxVolumeM3: vehicleType?.maxVolumeM3?.toString() ?? "",
      maxPallets: vehicleType?.maxPallets?.toString() ?? "",
      lengthM: vehicleType?.lengthM?.toString() ?? "",
      widthM: vehicleType?.widthM?.toString() ?? "",
      heightM: vehicleType?.heightM?.toString() ?? "",
      bodyType: vehicleType?.bodyType ?? "",
      temperatureControlled: vehicleType?.temperatureControlled ?? false,
      minTemperatureCelsius: vehicleType?.minTemperatureCelsius?.toString() ?? "",
      maxTemperatureCelsius: vehicleType?.maxTemperatureCelsius?.toString() ?? "",
      axles: vehicleType?.axles?.toString() ?? "",
    },
  });

  const temperatureControlled = useWatch({ control, name: "temperatureControlled" });

  const positive: Validate<string, VehicleTypeFormValues> = (value) => {
    const parsed = Number(value);
    if (value.trim() === "") return t("Este campo es obligatorio");
    if (Number.isNaN(parsed)) return t("Debe ser un número");
    return parsed > 0 || t("Debe ser un número mayor que cero");
  };

  const optionalPositive: Validate<string, VehicleTypeFormValues> = (value) => {
    if (value.trim() === "") return true;
    const parsed = Number(value);
    if (Number.isNaN(parsed)) return t("Debe ser un número");
    return parsed > 0 || t("Debe ser un número mayor que cero");
  };

  const validateTemperature: Validate<string, VehicleTypeFormValues> = (value, formValues) => {
    if (value.trim() === "") return true;
    if (!formValues.temperatureControlled) return t("Solo se permite si la unidad es de temperatura controlada");
    return Number.isNaN(Number(value)) ? t("Debe ser un número") : true;
  };

  async function onSubmit(values: VehicleTypeFormValues) {
    setFormError(null);

    if (values.minTemperatureCelsius.trim() !== "" && values.maxTemperatureCelsius.trim() !== ""
        && Number(values.minTemperatureCelsius) > Number(values.maxTemperatureCelsius)) {
      setFormError(t("La temperatura mínima no puede ser mayor que la máxima."));
      return;
    }

    const request: VehicleTypeRequest = {
      code: values.code.trim(),
      name: values.name.trim(),
      maxWeightKg: Number(values.maxWeightKg),
      maxVolumeM3: Number(values.maxVolumeM3),
      maxPallets: Number(values.maxPallets),
      lengthM: values.lengthM.trim() === "" ? null : Number(values.lengthM),
      widthM: values.widthM.trim() === "" ? null : Number(values.widthM),
      heightM: values.heightM.trim() === "" ? null : Number(values.heightM),
      bodyType: values.bodyType || null,
      temperatureControlled: values.temperatureControlled,
      minTemperatureCelsius: values.minTemperatureCelsius.trim() === "" ? null : Number(values.minTemperatureCelsius),
      maxTemperatureCelsius: values.maxTemperatureCelsius.trim() === "" ? null : Number(values.maxTemperatureCelsius),
      axles: values.axles.trim() === "" ? null : Number(values.axles),
    };

    try {
      if (isEdit) await updateVehicleType(companyId, vehicleType.id, request);
      else await createVehicleType(companyId, request);
      onSaved();
    } catch (error) {
      setFormError(applyApiFieldErrors(error as ApiError, KNOWN_FIELDS, setError, t("Corrige los campos marcados.")));
    }
  }

  return (
    <FormDrawer
      open
      icon={<AccountTreeRounded />}
      title={isEdit ? t("Editar tipo de vehículo") : t("Nuevo tipo de vehículo")}
      subtitle={isEdit
        ? [vehicleType.code, vehicleType.name].filter(Boolean).join(" · ")
        : t("La plantilla de capacidad que heredan los vehículos.")}
      titleAdornment={isEdit ? <ActiveBadge active={vehicleType.active} /> : undefined}
      footerStart={isEdit ? <FormMeta>{t("Actualizado el {{date}}", { date: fmtDateTime(vehicleType.updatedAt) })}</FormMeta> : undefined}
      size="lg"
      onClose={onClose}
      dirty={isDirty}
      closeOnBackdrop={!isSubmitting}
      footer={
        <>
          <Button onClick={onClose} disabled={isSubmitting} color="inherit" sx={{ color: "text.secondary" }}>{t("Cancelar")}</Button>
          <Button type="submit" form={FORM_ID} variant="contained" startIcon={<CheckRounded />} disabled={isSubmitting}>
            {isSubmitting ? t("Guardando...") : isEdit ? t("Guardar cambios") : t("Crear tipo de vehículo")}
          </Button>
        </>
      }
    >
      <Box component="form" id={FORM_ID} onSubmit={(event) => void handleSubmit(onSubmit)(event)} noValidate>
        {formError && <Alert severity="error" sx={{ mb: 1 }}>{formError}</Alert>}

        <FormSection title={t("Identificación")} help={t("Cómo se reconoce este tipo al dar de alta un vehículo.")}>
          <FormRow template="180px minmax(0, 1fr)">
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
              label={t("Nombre")} required size="small" fullWidth
              error={Boolean(errors.name)} helperText={errors.name?.message}
              {...register("name", {
                required: t("Este campo es obligatorio"),
                maxLength: { value: 200, message: t("No puede superar los {{count}} caracteres", { count: 200 }) },
              })}
            />
          </FormRow>
        </FormSection>

        <FormSection
          title={t("Capacidades")}
          help={t("Lo que la planificación usa para decidir si una carga cabe. Los vehículos las heredan.")}
        >
          <FormRow cols={3}>
            <TextField
              label={t("Peso máx. (kg)")} required size="small" fullWidth type="number"
              error={Boolean(errors.maxWeightKg)} helperText={errors.maxWeightKg?.message}
              {...register("maxWeightKg", { validate: positive })}
            />
            <TextField
              label={t("Volumen máx. (m³)")} required size="small" fullWidth type="number"
              error={Boolean(errors.maxVolumeM3)} helperText={errors.maxVolumeM3?.message}
              {...register("maxVolumeM3", { validate: positive })}
            />
            <TextField
              label={t("Pallets máx.")} required size="small" fullWidth type="number"
              error={Boolean(errors.maxPallets)} helperText={errors.maxPallets?.message}
              {...register("maxPallets", { validate: positive })}
            />
          </FormRow>
        </FormSection>

        <FormSection title={t("Dimensiones")} help={t("Medidas útiles de la caja. Opcionales.")}>
          <FormRow cols={3}>
            <TextField
              label={t("Largo (m)")} size="small" fullWidth type="number"
              error={Boolean(errors.lengthM)} helperText={errors.lengthM?.message}
              {...register("lengthM", { validate: optionalPositive })}
            />
            <TextField
              label={t("Ancho (m)")} size="small" fullWidth type="number"
              error={Boolean(errors.widthM)} helperText={errors.widthM?.message}
              {...register("widthM", { validate: optionalPositive })}
            />
            <TextField
              label={t("Alto (m)")} size="small" fullWidth type="number"
              error={Boolean(errors.heightM)} helperText={errors.heightM?.message}
              {...register("heightM", { validate: optionalPositive })}
            />
          </FormRow>
        </FormSection>

        <FormSection
          title={t("Restricciones")}
          help={t("Carrocería, ejes y, si la unidad es refrigerada, su rango de temperatura.")}
        >
          <FormRow>
            <Controller
              control={control}
              name="bodyType"
              render={({ field }) => (
                <TextField
                  select label={t("Tipo de carrocería")} size="small" fullWidth
                  value={field.value} onChange={(e) => field.onChange(e.target.value)}
                >
                  <MenuItem value="">{t("Sin especificar")}</MenuItem>
                  {VEHICLE_BODY_TYPES.map((type) => (
                    <MenuItem key={type} value={type}>{enumLabel("vehicleBodyType", type)}</MenuItem>
                  ))}
                </TextField>
              )}
            />
            <TextField
              label={t("Ejes")} size="small" fullWidth type="number"
              error={Boolean(errors.axles)} helperText={errors.axles?.message}
              {...register("axles", { validate: optionalPositive })}
            />
          </FormRow>

          <Controller
            control={control}
            name="temperatureControlled"
            render={({ field }) => (
              <FormControlLabel
                control={<Checkbox checked={field.value} onChange={(e) => field.onChange(e.target.checked)} />}
                label={t("Unidad de temperatura controlada")}
              />
            )}
          />
          {/* Las temperaturas solo tienen sentido —y solo las acepta el backend— cuando la unidad
              es refrigerada, así que se deshabilitan en vez de esconderse: quien las está mirando
              entiende por qué no puede escribirlas. */}
          <FormRow>
            <TextField
              label={t("Temperatura mín. (°C)")} size="small" fullWidth type="number"
              disabled={!temperatureControlled}
              error={Boolean(errors.minTemperatureCelsius)} helperText={errors.minTemperatureCelsius?.message}
              {...register("minTemperatureCelsius", { validate: validateTemperature })}
            />
            <TextField
              label={t("Temperatura máx. (°C)")} size="small" fullWidth type="number"
              disabled={!temperatureControlled}
              error={Boolean(errors.maxTemperatureCelsius)} helperText={errors.maxTemperatureCelsius?.message}
              {...register("maxTemperatureCelsius", { validate: validateTemperature })}
            />
          </FormRow>

          {!temperatureControlled && (
            <Typography variant="caption" color="text.secondary">
              {t("Solo se permite si la unidad es de temperatura controlada")}
            </Typography>
          )}
        </FormSection>
      </Box>
    </FormDrawer>
  );
}
