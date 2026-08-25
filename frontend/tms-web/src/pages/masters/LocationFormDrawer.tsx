import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { Controller, useForm, useWatch, type Validate } from "react-hook-form";
import {
  Alert, Autocomplete, Box, Button, Checkbox, FormControlLabel, FormGroup, MenuItem, TextField, Typography,
} from "@mui/material";
import { PlaceRounded } from "@mui/icons-material";
import { applyApiFieldErrors } from "../../shared/api/formErrors";
import type { ApiError } from "../../shared/api/httpClient";
import {
  createLocation, LOCATION_ROLES, LOCATION_TYPES, updateLocation,
  type LocationRequest, type LocationRole, type LocationType, type LocationView,
} from "../../shared/api/locationsApi";
import { fetchZones } from "../../shared/api/zonesApi";
import { LocationPickerMap } from "../../shared/maps/LocationPickerMap";
import { FormDrawer, SectionHeader } from "../../shared/ui/components";
import { enumLabel } from "../../lib/enums";
import { t } from "../../lib/i18n";
import { LocationFrequencyPanel } from "./LocationFrequencyPanel";

const FORM_ID = "location-form";

/** Casa con la restricción de `code` del backend; vive junto al campo que valida. */
const CODE_PATTERN = /^[A-Za-z0-9][A-Za-z0-9_-]{0,31}$/;

// `Intl.supportedValuesOf` está disponible en los navegadores modernos; una lista vacía solo
// significa que el campo de zona horaria cae a texto libre con la validación de abajo.
const TIME_ZONES: string[] = typeof Intl.supportedValuesOf === "function" ? Intl.supportedValuesOf("timeZone") : [];

function isValidTimeZone(value: string): boolean {
  try {
    Intl.DateTimeFormat(undefined, { timeZone: value });
    return true;
  } catch {
    return false;
  }
}

/** El mapa solo necesita un par válido; un valor a medio escribir o inválido significa
 * simplemente que todavía no hay marcador. */
function parseCoordinate(value: string | undefined): number | null {
  if (value === undefined || value.trim() === "") return null;
  const parsed = Number(value);
  return Number.isNaN(parsed) ? null : parsed;
}

interface LocationFormValues {
  code: string;
  name: string;
  type: LocationType;
  roles: LocationRole[];
  zoneId: string;
  address: string;
  addressReference: string;
  district: string;
  province: string;
  department: string;
  country: string;
  timeZone: string;
  latitude: string;
  longitude: string;
  serviceTimeMinutes: string;
  externalSystem: string;
  externalReference: string;
}

interface LocationFormDrawerProps {
  companyId: string;
  /** `null` crea una ubicación nueva; si no, el formulario edita esta. */
  location: LocationView | null;
  /**
   * Marcado por defecto en una ubicación nueva. Orígenes y Destinos son vistas filtradas de
   * este mismo maestro, así que "Nuevo origen" tiene que abrir este drawer ya diciendo lo que
   * el operador pidió — si no, crearía un lugar que no aparece en la lista desde la que lo creó.
   */
  presetRole?: LocationRole;
  onClose: () => void;
  onSaved: () => void;
}

const KNOWN_FIELDS = new Set<keyof LocationFormValues>([
  "code", "name", "type", "roles", "zoneId", "address", "addressReference", "district", "province",
  "department", "country", "timeZone", "latitude", "longitude", "serviceTimeMinutes",
  "externalSystem", "externalReference",
]);

/**
 * Crear y editar comparten un formulario: los campos y la validación son idénticos. Diecisiete
 * campos son demasiados para una lista plana, así que van agrupados como piensa un operador un
 * lugar: qué es, cómo puede usarse, dónde está, cómo se le da servicio y cómo lo llama otro
 * sistema.
 *
 * El uso operacional son dos casillas redactadas como frases —"puede utilizarse como origen"— y
 * no una lista de códigos de rol. Son los únicos campos de este formulario con consecuencia en
 * otro sitio: marcarlos es lo que hace que el lugar se pueda elegir en pedidos, rutas y
 * planificación.
 */
export function LocationFormDrawer({ companyId, location, presetRole, onClose, onSaved }: LocationFormDrawerProps) {
  const isEdit = location !== null;
  const [formError, setFormError] = useState<string | null>(null);

  const zonesQuery = useQuery({
    queryKey: ["zones-for-location-form", companyId],
    queryFn: ({ signal }) => fetchZones({ companyId, size: 200, active: true, sort: "code,asc", signal }),
  });
  const zones = zonesQuery.data?.content ?? [];

  const {
    register, control, handleSubmit, setError, setValue,
    formState: { errors, isDirty, isSubmitting },
  } = useForm<LocationFormValues>({
    defaultValues: {
      code: location?.code ?? "",
      name: location?.name ?? "",
      type: location?.type ?? "STORE",
      roles: location?.roles ?? (presetRole ? [presetRole] : ["DESTINATION"]),
      zoneId: location?.zoneId ?? "",
      address: location?.address ?? "",
      addressReference: location?.addressReference ?? "",
      district: location?.district ?? "",
      province: location?.province ?? "",
      department: location?.department ?? "",
      country: location?.country ?? "PE",
      timeZone: location?.timeZone ?? "America/Lima",
      latitude: location?.latitude?.toString() ?? "",
      longitude: location?.longitude?.toString() ?? "",
      serviceTimeMinutes: location?.serviceTimeMinutes?.toString() ?? "0",
      externalSystem: location?.externalSystem ?? "",
      externalReference: location?.externalReference ?? "",
    },
  });

  const watchedLatitude = useWatch({ control, name: "latitude" });
  const watchedLongitude = useWatch({ control, name: "longitude" });
  const mapLatitude = parseCoordinate(watchedLatitude);
  const mapLongitude = parseCoordinate(watchedLongitude);
  const initialMapSearchValue = location
    ? [location.address, location.district, location.province, location.country].filter(Boolean).join(", ")
    : "";

  const validateLatitude: Validate<string, LocationFormValues> = (value, formValues) => {
    if (value.trim() === "") {
      return formValues.longitude.trim() === "" || t("Indica latitud y longitud, o deja ambas en blanco");
    }
    const parsed = Number(value);
    if (Number.isNaN(parsed)) return t("Debe ser un número");
    if (parsed < -90 || parsed > 90) return t("Debe estar entre {{min}} y {{max}}", { min: -90, max: 90 });
    return true;
  };

  const validateLongitude: Validate<string, LocationFormValues> = (value, formValues) => {
    if (value.trim() === "") {
      return formValues.latitude.trim() === "" || t("Indica latitud y longitud, o deja ambas en blanco");
    }
    const parsed = Number(value);
    if (Number.isNaN(parsed)) return t("Debe ser un número");
    if (parsed < -180 || parsed > 180) return t("Debe estar entre {{min}} y {{max}}", { min: -180, max: 180 });
    return true;
  };

  /** Refleja `ck_location_external_pair_complete`: una referencia sin sistema no deduplica nada. */
  const validateExternalPair: Validate<string, LocationFormValues> = (_value, formValues) =>
    (formValues.externalSystem.trim() === "") === (formValues.externalReference.trim() === "")
    || t("Indica el sistema y la referencia externa, o deja ambos en blanco");

  async function onSubmit(values: LocationFormValues) {
    setFormError(null);
    const request: LocationRequest = {
      code: values.code.trim(),
      name: values.name.trim(),
      type: values.type,
      roles: values.roles,
      zoneId: values.zoneId || null,
      address: values.address.trim() || null,
      addressReference: values.addressReference.trim() || null,
      district: values.district.trim() || null,
      province: values.province.trim() || null,
      department: values.department.trim() || null,
      country: values.country.trim(),
      timeZone: values.timeZone.trim(),
      latitude: values.latitude.trim() === "" ? null : Number(values.latitude),
      longitude: values.longitude.trim() === "" ? null : Number(values.longitude),
      serviceTimeMinutes: values.serviceTimeMinutes.trim() === "" ? 0 : Number(values.serviceTimeMinutes),
      externalSystem: values.externalSystem.trim() || null,
      externalReference: values.externalReference.trim() || null,
    };

    try {
      if (isEdit) await updateLocation(companyId, location.id, request);
      else await createLocation(companyId, request);
      onSaved();
    } catch (error) {
      setFormError(applyApiFieldErrors(error as ApiError, KNOWN_FIELDS, setError, t("Corrige los campos marcados.")));
    }
  }

  const grid = { display: "grid", gap: 2, gridTemplateColumns: { xs: "1fr", sm: "1fr 1fr" }, mb: 3 } as const;

  return (
    <FormDrawer
      open
      icon={<PlaceRounded />}
      title={isEdit ? t("Editar ubicación") : t("Nueva ubicación")}
      subtitle={t("Un lugar físico y los roles que cumple en la operación.")}
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
            label={t("Nombre")} required size="small" fullWidth
            error={Boolean(errors.name)} helperText={errors.name?.message}
            {...register("name", {
              required: t("Este campo es obligatorio"),
              maxLength: { value: 200, message: t("No puede superar los {{count}} caracteres", { count: 200 }) },
            })}
          />
          <Controller
            control={control}
            name="type"
            rules={{ required: true }}
            render={({ field }) => (
              <TextField
                select label={t("Tipo")} required size="small" fullWidth
                value={field.value} onChange={(e) => field.onChange(e.target.value as LocationType)}
                error={Boolean(errors.type)}
              >
                {LOCATION_TYPES.map((type) => (
                  <MenuItem key={type} value={type}>{enumLabel("locationType", type)}</MenuItem>
                ))}
              </TextField>
            )}
          />
          <Controller
            control={control}
            name="zoneId"
            render={({ field }) => (
              <TextField
                select label={t("Zona")} size="small" fullWidth
                value={field.value} onChange={(e) => field.onChange(e.target.value)}
              >
                <MenuItem value="">{t("Sin zona")}</MenuItem>
                {zones.map((zone) => (
                  <MenuItem key={zone.id} value={zone.id}>{zone.name}</MenuItem>
                ))}
              </TextField>
            )}
          />
        </Box>

        <SectionHeader title={t("Uso operacional")} />
        <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>
          {t("Define cómo puede utilizarse este lugar en el transporte. Un mismo sitio puede ser origen y destino: la tienda recibe la entrega y despacha la devolución.")}
        </Typography>
        <Controller
          control={control}
          name="roles"
          render={({ field }) => (
            <FormGroup sx={{ mb: 3 }}>
              {LOCATION_ROLES.map((role) => (
                <FormControlLabel
                  key={role}
                  control={
                    <Checkbox
                      checked={field.value.includes(role)}
                      onChange={(e) => {
                        const next = e.target.checked
                          ? [...field.value, role]
                          : field.value.filter((r) => r !== role);
                        field.onChange(next);
                      }}
                    />
                  }
                  label={role === "ORIGIN" ? t("Puede utilizarse como origen") : t("Puede utilizarse como destino")}
                />
              ))}
            </FormGroup>
          )}
        />

        <SectionHeader title={t("Dirección")} />
        <Box sx={{ display: "grid", gap: 2, gridTemplateColumns: "1fr", mb: 2 }}>
          <TextField
            label={t("Dirección")} size="small" fullWidth
            error={Boolean(errors.address)} helperText={errors.address?.message}
            {...register("address", {
              maxLength: { value: 300, message: t("No puede superar los {{count}} caracteres", { count: 300 }) },
            })}
          />
          <TextField
            label={t("Referencia")} size="small" fullWidth placeholder={t("p. ej. portón azul")}
            {...register("addressReference", {
              maxLength: { value: 300, message: t("No puede superar los {{count}} caracteres", { count: 300 }) },
            })}
          />
        </Box>
        <Box sx={{ display: "grid", gap: 2, gridTemplateColumns: { xs: "1fr", sm: "repeat(3, 1fr)" }, mb: 2 }}>
          <TextField label={t("Distrito")} size="small" fullWidth {...register("district")} />
          <TextField label={t("Provincia")} size="small" fullWidth {...register("province")} />
          <TextField label={t("Departamento")} size="small" fullWidth {...register("department")} />
        </Box>
        <Box sx={grid}>
          <TextField
            label={t("País")} required size="small" fullWidth
            error={Boolean(errors.country)} helperText={errors.country?.message}
            {...register("country", {
              required: t("Este campo es obligatorio"),
              maxLength: { value: 2, message: t("No puede superar los {{count}} caracteres", { count: 2 }) },
            })}
          />
          <Controller
            control={control}
            name="timeZone"
            rules={{
              required: t("Este campo es obligatorio"),
              validate: (value) => isValidTimeZone(value) || t("Debe ser una zona horaria IANA válida, por ejemplo America/Lima"),
            }}
            render={({ field }) => (
              <Autocomplete
                freeSolo
                size="small"
                options={TIME_ZONES}
                value={field.value}
                onChange={(_e, next) => field.onChange(next ?? "")}
                onInputChange={(_e, next) => field.onChange(next)}
                renderInput={(params) => (
                  <TextField
                    {...params}
                    label={t("Zona horaria")}
                    required
                    placeholder="America/Lima"
                    error={Boolean(errors.timeZone)}
                    helperText={errors.timeZone?.message}
                  />
                )}
              />
            )}
          />
        </Box>

        <SectionHeader title={t("Ubicación geográfica")} />
        {/* El mapa escribe en los mismos dos campos que se pueden teclear a mano: sin clave de
            API configurada desaparece y la entrada manual sigue intacta. */}
        <LocationPickerMap
          latitude={mapLatitude}
          longitude={mapLongitude}
          initialSearchValue={initialMapSearchValue}
          onChange={(lat, lng) => {
            setValue("latitude", lat.toFixed(6), { shouldDirty: true, shouldValidate: true });
            setValue("longitude", lng.toFixed(6), { shouldDirty: true, shouldValidate: true });
          }}
        />
        <Box sx={grid}>
          <TextField
            label={t("Latitud")} size="small" fullWidth placeholder="-12.046374"
            error={Boolean(errors.latitude)} helperText={errors.latitude?.message}
            {...register("latitude", { validate: validateLatitude })}
          />
          <TextField
            label={t("Longitud")} size="small" fullWidth placeholder="-77.042793"
            error={Boolean(errors.longitude)} helperText={errors.longitude?.message}
            {...register("longitude", { validate: validateLongitude })}
          />
        </Box>

        <SectionHeader title={t("Operación")} />
        <Box sx={grid}>
          <TextField
            label={t("Tiempo de atención (min)")} size="small" fullWidth type="number"
            error={Boolean(errors.serviceTimeMinutes)} helperText={errors.serviceTimeMinutes?.message}
            {...register("serviceTimeMinutes", {
              validate: (value) => {
                if (value.trim() === "") return true;
                const parsed = Number(value);
                if (Number.isNaN(parsed)) return t("Debe ser un número");
                return parsed >= 0 || t("Debe ser cero o mayor");
              },
            })}
          />
        </Box>

        <SectionHeader title={t("Identificación externa")} />
        <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>
          {t("El sistema y la referencia externa identifican esta ubicación ante una integración. Van juntos o ninguno.")}
        </Typography>
        <Box sx={grid}>
          <TextField
            label={t("Sistema externo")} size="small" fullWidth placeholder={t("p. ej. EWM, ERP")}
            error={Boolean(errors.externalSystem)} helperText={errors.externalSystem?.message}
            {...register("externalSystem", { validate: validateExternalPair })}
          />
          <TextField
            label={t("Referencia externa")} size="small" fullWidth
            error={Boolean(errors.externalReference)} helperText={errors.externalReference?.message}
            {...register("externalReference", { validate: validateExternalPair })}
          />
        </Box>
      </Box>

      {/* El calendario de servicio vive fuera del <form>: sus asociaciones se guardan por su
          cuenta contra la ubicación ya existente, y anidar un formulario dentro de otro haría
          que Enter en un desplegable enviara el formulario principal. */}
      <SectionHeader title={t("Calendario de servicio")} />
      {isEdit ? (
        <LocationFrequencyPanel companyId={companyId} locationId={location.id} />
      ) : (
        <Typography variant="body2" color="text.secondary">
          {t("Guarda la ubicación primero para poder asociarle frecuencias.")}
        </Typography>
      )}
    </FormDrawer>
  );
}
