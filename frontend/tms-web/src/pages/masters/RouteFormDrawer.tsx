import { useQuery } from "@tanstack/react-query";
import { useMemo, useState } from "react";
import { Controller, useFieldArray, useForm } from "react-hook-form";
import {
  Alert, Box, Button, IconButton, MenuItem, Paper, TextField, Tooltip, Typography,
} from "@mui/material";
import {
  AltRouteRounded, AddRounded, ArrowUpwardRounded, ArrowDownwardRounded, DeleteRounded,
} from "@mui/icons-material";
import { applyApiFieldErrors } from "../../shared/api/formErrors";
import type { ApiError } from "../../shared/api/httpClient";
import { fetchOrigins } from "../../shared/api/originsApi";
import { fetchZones } from "../../shared/api/zonesApi";
import { fetchFrequencies } from "../../shared/api/frequenciesApi";
import { fetchDestinations } from "../../shared/api/destinationsApi";
import {
  createRoute, fetchRoute, updateRoute,
  type RouteDetailView, type RouteRequest, type RouteStopRequest,
} from "../../shared/api/routesApi";
import { describeApiError } from "../../shared/api/problemMessages";
import { FormDrawer, LoadingState, SectionHeader } from "../../shared/ui/components";
import { t } from "../../lib/i18n";

const FORM_ID = "route-form";

/** Casa con la restricción de `code` del backend; vive junto al campo que valida. */
const CODE_PATTERN = /^[A-Za-z0-9][A-Za-z0-9_-]{0,31}$/;

interface SelectOption {
  id: string;
  code: string;
  name: string;
}

interface RouteFormValues {
  code: string;
  name: string;
  originId: string;
  zoneId: string;
  frequencyId: string;
  referenceDistanceKm: string;
  referenceDurationMinutes: string;
  /** `serviceTimeOverrideMinutes` vacío significa "hereda el de la ubicación" — ver `toStopRequest`. */
  stops: { destinationId: string; serviceTimeOverrideMinutes: string }[];
}

/** Vacío significa heredar; `'0'` es un override real (una parada de dejar y seguir), así que
 * tiene que sobrevivir como 0 y no colapsarse a null. */
function toStopRequest(stop: RouteFormValues["stops"][number]): RouteStopRequest {
  const override = stop.serviceTimeOverrideMinutes.trim();
  return {
    destinationId: stop.destinationId,
    serviceTimeOverrideMinutes: override === "" ? null : Number(override),
  };
}

interface RouteFormDrawerProps {
  companyId: string;
  /** `null` crea una ruta nueva; si no, el drawer carga y edita el detalle completo de esta
   * ruta (incluidas sus paradas ordenadas): la fila de la lista (`RouteView`) no las trae, por
   * diseño, para no provocar un N+1 en el listado. */
  routeId: string | null;
  onClose: () => void;
  onSaved: () => void;
}

const KNOWN_FIELDS = new Set<keyof RouteFormValues>([
  "code", "name", "originId", "zoneId", "frequencyId", "referenceDistanceKm", "referenceDurationMinutes",
]);

/** Antepone la opción actualmente asignada si se cayó de la lista de solo-activos que alimenta
 * el desplegable: desactivar un maestro no borra en silencio la historia de una ruta. */
function withCurrentValue(options: SelectOption[], id: string | null, code: string | null, name: string | null) {
  if (!id || options.some((option) => option.id === id)) return options;
  return [{ id, code: code ?? id, name: name ?? code ?? id }, ...options];
}

export function RouteFormDrawer({ companyId, routeId, onClose, onSaved }: RouteFormDrawerProps) {
  const routeQuery = useQuery({
    queryKey: ["route", companyId, routeId],
    queryFn: ({ signal }) => fetchRoute(companyId, routeId as string, signal),
    enabled: routeId !== null,
  });

  // El formulario de edición no puede pintarse antes de que lleguen las paradas, así que el
  // drawer abre con su propio estado de carga en vez de parpadear un formulario vacío.
  if (routeId !== null && !routeQuery.data) {
    return (
      <FormDrawer
        open
        icon={<AltRouteRounded />}
        title={t("Editar ruta")}
        subtitle={t("Origen, cadencia y la secuencia de paradas que recorre.")}
        size="xl"
        onClose={onClose}
      >
        {routeQuery.isError
          ? <Alert severity="error">{describeApiError(routeQuery.error as ApiError)}</Alert>
          : <LoadingState label={t("Cargando ruta...")} />}
      </FormDrawer>
    );
  }

  return <RouteForm companyId={companyId} route={routeQuery.data ?? null} onClose={onClose} onSaved={onSaved} />;
}

function RouteForm({
  companyId, route, onClose, onSaved,
}: {
  companyId: string;
  route: RouteDetailView | null;
  onClose: () => void;
  onSaved: () => void;
}) {
  const isEdit = route !== null;
  const [formError, setFormError] = useState<string | null>(null);
  const [stopToAdd, setStopToAdd] = useState("");

  const originsQuery = useQuery({
    queryKey: ["origins-for-route-form", companyId],
    queryFn: ({ signal }) => fetchOrigins({ companyId, size: 200, active: true, sort: "code,asc", signal }),
  });
  const zonesQuery = useQuery({
    queryKey: ["zones-for-route-form", companyId],
    queryFn: ({ signal }) => fetchZones({ companyId, size: 200, active: true, sort: "code,asc", signal }),
  });
  const frequenciesQuery = useQuery({
    queryKey: ["frequencies-for-route-form", companyId],
    queryFn: ({ signal }) => fetchFrequencies({ companyId, size: 200, active: true, sort: "code,asc", signal }),
  });
  const destinationsQuery = useQuery({
    queryKey: ["destinations-for-route-form", companyId],
    queryFn: ({ signal }) => fetchDestinations({ companyId, size: 200, active: true, sort: "code,asc", signal }),
  });

  const originOptions = withCurrentValue(
    originsQuery.data?.content ?? [], route?.originId ?? null, route?.originCode ?? null, route?.originName ?? null,
  );
  const zoneOptions = withCurrentValue(
    zonesQuery.data?.content ?? [], route?.zoneId ?? null, route?.zoneCode ?? null, route?.zoneName ?? null,
  );
  const frequencyOptions = withCurrentValue(
    frequenciesQuery.data?.content ?? [], route?.frequencyId ?? null, route?.frequencyCode ?? null,
    route?.frequencyName ?? null,
  );
  const availableDestinations = useMemo(() => destinationsQuery.data?.content ?? [], [destinationsQuery.data]);

  /** El código y el nombre de cada parada, más el tiempo de atención de su propia ubicación,
   * prefiriendo los datos que trae la ruta (posiblemente de un destino ya desactivado) sobre el
   * fetch de solo-activos: una parada existente siempre se pinta bien aunque su destino se haya
   * desactivado después. `serviceTimeMinutes` es lo que el campo de override enseña como
   * placeholder: el valor que aplica si el operador lo deja vacío. */
  const destinationLookup = useMemo(() => {
    const map = new Map<string, { code: string; name: string; serviceTimeMinutes: number | null }>();
    for (const destination of availableDestinations) {
      map.set(destination.id, {
        code: destination.code,
        name: destination.name,
        serviceTimeMinutes: destination.serviceTimeMinutes,
      });
    }
    for (const stop of route?.stops ?? []) {
      map.set(stop.destinationId, {
        code: stop.destinationCode ?? stop.destinationId,
        name: stop.destinationName ?? "",
        serviceTimeMinutes: stop.destinationServiceTimeMinutes,
      });
    }
    return map;
  }, [availableDestinations, route]);

  const {
    register, control, handleSubmit, setError,
    formState: { errors, isDirty, isSubmitting },
  } = useForm<RouteFormValues>({
    defaultValues: {
      code: route?.code ?? "",
      name: route?.name ?? "",
      originId: route?.originId ?? "",
      zoneId: route?.zoneId ?? "",
      frequencyId: route?.frequencyId ?? "",
      referenceDistanceKm: route?.referenceDistanceKm?.toString() ?? "",
      referenceDurationMinutes: route?.referenceDurationMinutes?.toString() ?? "",
      stops: (route?.stops ?? []).map((stop) => ({
        destinationId: stop.destinationId,
        serviceTimeOverrideMinutes: stop.serviceTimeOverrideMinutes?.toString() ?? "",
      })),
    },
  });
  const { fields, append, remove, move } = useFieldArray({ control, name: "stops" });

  const addableDestinations = availableDestinations.filter(
    (destination) => !fields.some((field) => field.destinationId === destination.id),
  );

  function addStop() {
    if (stopToAdd === "") return;
    // Se añade heredando: una parada nueva tarda lo que diga su ubicación hasta que alguien
    // diga otra cosa, que es lo correcto mucho más a menudo que no.
    append({ destinationId: stopToAdd, serviceTimeOverrideMinutes: "" });
    setStopToAdd("");
  }

  async function onSubmit(values: RouteFormValues) {
    setFormError(null);
    if (values.stops.length === 0) {
      setFormError(t("Una ruta necesita al menos una parada."));
      return;
    }

    const request: RouteRequest = {
      code: values.code.trim(),
      name: values.name.trim(),
      originId: values.originId,
      zoneId: values.zoneId || null,
      frequencyId: values.frequencyId || null,
      referenceDistanceKm: values.referenceDistanceKm.trim() === "" ? null : Number(values.referenceDistanceKm),
      referenceDurationMinutes:
        values.referenceDurationMinutes.trim() === "" ? null : Number(values.referenceDurationMinutes),
      stops: values.stops.map(toStopRequest),
    };

    try {
      if (isEdit) await updateRoute(companyId, route.id, request);
      else await createRoute(companyId, request);
      onSaved();
    } catch (error) {
      setFormError(applyApiFieldErrors(error as ApiError, KNOWN_FIELDS, setError, t("Corrige los campos marcados.")));
    }
  }

  const grid = { display: "grid", gap: 2, gridTemplateColumns: { xs: "1fr", sm: "1fr 1fr" }, mb: 3 } as const;

  return (
    <FormDrawer
      open
      icon={<AltRouteRounded />}
      title={isEdit ? t("Editar ruta") : t("Nueva ruta")}
      subtitle={t("Origen, cadencia y la secuencia de paradas que recorre.")}
      size="xl"
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
        </Box>

        <SectionHeader title={t("Operación")} />
        <Box sx={grid}>
          <Controller
            control={control}
            name="originId"
            rules={{ required: t("Este campo es obligatorio") }}
            render={({ field }) => (
              <TextField
                select label={t("Origen")} required size="small" fullWidth
                value={field.value} onChange={(e) => field.onChange(e.target.value)}
                error={Boolean(errors.originId)} helperText={errors.originId?.message}
              >
                <MenuItem value="">{t("Selecciona un origen")}</MenuItem>
                {originOptions.map((option) => (
                  <MenuItem key={option.id} value={option.id}>{option.code} · {option.name}</MenuItem>
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
                {zoneOptions.map((option) => (
                  <MenuItem key={option.id} value={option.id}>{option.name}</MenuItem>
                ))}
              </TextField>
            )}
          />
          <Controller
            control={control}
            name="frequencyId"
            render={({ field }) => (
              <TextField
                select label={t("Frecuencia")} size="small" fullWidth
                value={field.value} onChange={(e) => field.onChange(e.target.value)}
              >
                <MenuItem value="">{t("Sin frecuencia")}</MenuItem>
                {frequencyOptions.map((option) => (
                  <MenuItem key={option.id} value={option.id}>{option.code} · {option.name}</MenuItem>
                ))}
              </TextField>
            )}
          />
        </Box>

        <SectionHeader title={t("Referencia")} />
        <Box sx={grid}>
          <TextField
            label={t("Distancia de referencia (km)")} size="small" fullWidth type="number"
            error={Boolean(errors.referenceDistanceKm)} helperText={errors.referenceDistanceKm?.message}
            {...register("referenceDistanceKm", {
              validate: (value) => {
                if (value.trim() === "") return true;
                const parsed = Number(value);
                if (Number.isNaN(parsed)) return t("Debe ser un número");
                return parsed >= 0 || t("Debe ser cero o mayor");
              },
            })}
          />
          <TextField
            label={t("Duración de referencia (min)")} size="small" fullWidth type="number"
            error={Boolean(errors.referenceDurationMinutes)} helperText={errors.referenceDurationMinutes?.message}
            {...register("referenceDurationMinutes", {
              validate: (value) => {
                if (value.trim() === "") return true;
                const parsed = Number(value);
                if (Number.isNaN(parsed)) return t("Debe ser un número");
                return parsed >= 0 || t("Debe ser cero o mayor");
              },
            })}
          />
        </Box>

        <SectionHeader title={t("Paradas")} />
        <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>
          {t("El orden de la lista ES la secuencia de paradas. Deja el tiempo de atención vacío para heredar el de la ubicación.")}
        </Typography>

        <Box sx={{ display: "flex", gap: 1.5, mb: 2, alignItems: "flex-start" }}>
          <TextField
            select size="small" label={t("Destino a agregar")} value={stopToAdd}
            onChange={(e) => setStopToAdd(e.target.value)}
            sx={{ flex: 1, minWidth: 220 }}
          >
            <MenuItem value="">{t("Selecciona un destino")}</MenuItem>
            {addableDestinations.map((destination) => (
              <MenuItem key={destination.id} value={destination.id}>
                {destination.code} · {destination.name}
              </MenuItem>
            ))}
          </TextField>
          <Button variant="outlined" startIcon={<AddRounded />} onClick={addStop} disabled={stopToAdd === ""}>
            {t("Agregar")}
          </Button>
        </Box>

        {fields.length === 0 ? (
          <Alert severity="info">{t("Esta ruta todavía no tiene paradas.")}</Alert>
        ) : (
          <Box sx={{ display: "grid", gap: 1 }}>
            {fields.map((field, index) => {
              const info = destinationLookup.get(field.destinationId);
              const position = index + 1;
              return (
                <Paper
                  key={field.id}
                  variant="outlined"
                  sx={{ p: 1.5, display: "flex", alignItems: "center", gap: 1.5, flexWrap: "wrap" }}
                >
                  <Box sx={{
                    width: 28, height: 28, borderRadius: "50%", flexShrink: 0, display: "grid", placeItems: "center",
                    bgcolor: "primary.main", color: "primary.contrastText", fontWeight: 800, fontSize: 13,
                  }}>
                    {position}
                  </Box>
                  <Box sx={{ flex: 1, minWidth: 160 }}>
                    <Typography variant="body2" sx={{ fontWeight: 700, lineHeight: 1.3 }}>
                      {info?.name || field.destinationId}
                    </Typography>
                    <Typography variant="caption" color="text.secondary">{info?.code ?? ""}</Typography>
                  </Box>
                  <TextField
                    size="small" type="number" label={t("Servicio (min)")}
                    placeholder={info?.serviceTimeMinutes?.toString() ?? ""}
                    sx={{ width: 150 }}
                    error={Boolean(errors.stops?.[index]?.serviceTimeOverrideMinutes)}
                    helperText={errors.stops?.[index]?.serviceTimeOverrideMinutes?.message}
                    slotProps={{ inputLabel: { shrink: true } }}
                    {...register(`stops.${index}.serviceTimeOverrideMinutes` as const, {
                      validate: (value) => {
                        if (value.trim() === "") return true;
                        const parsed = Number(value);
                        if (Number.isNaN(parsed)) return t("Debe ser un número");
                        return parsed >= 0 || t("Debe ser cero o mayor");
                      },
                    })}
                  />
                  <Box sx={{ display: "flex", gap: 0.25 }}>
                    <Tooltip title={t("Subir")}>
                      <span>
                        <IconButton size="small" disabled={index === 0} onClick={() => move(index, index - 1)}
                          aria-label={t("Subir la parada {{position}}", { position })}>
                          <ArrowUpwardRounded fontSize="small" />
                        </IconButton>
                      </span>
                    </Tooltip>
                    <Tooltip title={t("Bajar")}>
                      <span>
                        <IconButton size="small" disabled={index === fields.length - 1} onClick={() => move(index, index + 1)}
                          aria-label={t("Bajar la parada {{position}}", { position })}>
                          <ArrowDownwardRounded fontSize="small" />
                        </IconButton>
                      </span>
                    </Tooltip>
                    <Tooltip title={t("Quitar")}>
                      <IconButton size="small" sx={{ color: "error.main" }} onClick={() => remove(index)}
                        aria-label={t("Quitar la parada {{position}}", { position })}>
                        <DeleteRounded fontSize="small" />
                      </IconButton>
                    </Tooltip>
                  </Box>
                </Paper>
              );
            })}
          </Box>
        )}
      </Box>
    </FormDrawer>
  );
}
