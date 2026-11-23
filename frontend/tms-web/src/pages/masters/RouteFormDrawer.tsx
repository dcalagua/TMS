import { useQuery } from "@tanstack/react-query";
import { useMemo, useState } from "react";
import { Controller, useFieldArray, useForm, useWatch } from "react-hook-form";
import {
  Alert, Box, Button, CircularProgress, IconButton, MenuItem, Paper, TextField, ToggleButton, ToggleButtonGroup,
  Tooltip, Typography,
} from "@mui/material";
import {
  AltRouteRounded, AddRounded, ArrowUpwardRounded, ArrowDownwardRounded, AutoAwesomeRounded, CheckRounded,
  DeleteRounded, MapRounded, ViewListRounded,
} from "@mui/icons-material";
import { TripStopMap, type TripStopMapOrigin, type TripStopMapStop } from "../../shared/maps/TripStopMap";
import { applyApiFieldErrors } from "../../shared/api/formErrors";
import type { ApiError } from "../../shared/api/httpClient";
import { fetchOrigins } from "../../shared/api/originsApi";
import { fetchZones } from "../../shared/api/zonesApi";
import { fetchFrequencies } from "../../shared/api/frequenciesApi";
import { fetchDestinations } from "../../shared/api/destinationsApi";
import {
  createRoute, fetchRoute, optimizeRouteStops, updateRoute,
  type RouteDetailView, type RouteRequest, type RouteStopOptimizationView, type RouteStopRequest,
} from "../../shared/api/routesApi";
import { describeApiError } from "../../shared/api/problemMessages";
import {
  ActiveBadge, FormDrawer, FormMeta, FormRow, FormSection, LoadingState,
} from "../../shared/ui/components";
import { notifyError } from "../../lib/ui";
import { t } from "../../lib/i18n";
import { fmtDateTime } from "../../lib/locale";
import { RouteStopProposal } from "./RouteStopProposal";

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
    register, control, handleSubmit, setError, getValues,
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
  const { fields, append, remove, move, replace } = useFieldArray({ control, name: "stops" });

  // «Optimizar paradas» (ADR-030). Una propuesta más corta se aplica al llegar; el banner la
  // acompaña mientras la lista siga en ese orden, y «Descartar» vuelve al de antes. Si alguien
  // cambia las paradas o el origen después, el banner desaparece: ya no describe la lista.
  const originId = useWatch({ control, name: "originId" });
  const watchedStops = useWatch({ control, name: "stops" });
  const stopIds = (watchedStops ?? []).map((stop) => stop.destinationId);
  const [optimizing, setOptimizing] = useState(false);
  const [proposal, setProposal] = useState<{
    view: RouteStopOptimizationView; originId: string; previousIds: string[]; shownIds: string[];
  } | null>(null);
  const liveProposal = proposal && proposal.originId === originId && proposal.shownIds.join() === stopIds.join()
    ? proposal
    : null;
  // Basta con dos paradas: al crear una ruta el origen empieza vacío, y un botón apagado no dice
  // por qué. Sin origen, el clic lo pide (el recorrido se mide desde él).
  const canOptimize = stopIds.length >= 2 && !optimizing;

  // Lista o mapa de las paradas. El mapa sigue el orden de la lista, así que enseña al momento lo
  // que cambia «Optimizar paradas». Memorizado por origen y orden: TripStopMap redibuja y reencuadra
  // cuando cambian sus props, y no debe hacerlo en cada tecla que se escribe en el formulario.
  const [stopsView, setStopsView] = useState<"list" | "map">("list");
  const mapOrigin = useMemo<TripStopMapOrigin | null>(() => {
    const origin = (originsQuery.data?.content ?? []).find((option) => option.id === originId);
    return origin ? { latitude: origin.latitude, longitude: origin.longitude, label: `${origin.code} · ${origin.name}` } : null;
  }, [originsQuery.data, originId]);
  const stopsKey = stopIds.join(",");
  const mapStops = useMemo<TripStopMapStop[]>(() => (stopsKey === "" ? [] : stopsKey.split(",")).map((id, index) => {
    // Las coordenadas salen de los destinos activos; una parada cuyo destino se desactivó no las
    // trae, y el mapa la cuenta como «sin coordenadas» en vez de inventar dónde está.
    const destination = availableDestinations.find((option) => option.id === id);
    const info = destinationLookup.get(id);
    return {
      id, sequence: index + 1,
      latitude: destination?.latitude ?? null, longitude: destination?.longitude ?? null,
      label: info ? [info.code, info.name].filter(Boolean).join(" · ") : id,
    };
  }), [stopsKey, availableDestinations, destinationLookup]);

  /** Lo que el formulario ya sabe que no tiene coordenadas, para nombrarlo antes de preguntar. */
  function placesWithoutCoordinates(): string[] {
    const missing: string[] = [];
    const origin = (originsQuery.data?.content ?? []).find((option) => option.id === originId);
    if (origin && (origin.latitude === null || origin.longitude === null)) missing.push(origin.name);
    for (const id of stopIds) {
      const destination = availableDestinations.find((option) => option.id === id);
      if (destination && (destination.latitude === null || destination.longitude === null)) {
        missing.push(destination.name);
      }
    }
    return missing;
  }

  async function optimizeStops() {
    if (originId === "") {
      notifyError(t("Falta el origen"), t("La ruta parte del origen: elígelo para poder optimizar las paradas."));
      return;
    }
    const missing = placesWithoutCoordinates();
    if (missing.length > 0) {
      notifyError(t("Faltan coordenadas"),
        t("Sin coordenadas: {{names}}. Cárgalas en Maestros › Ubicaciones para poder optimizar.", { names: missing.join(", ") }));
      return;
    }
    const askedOrigin = originId;
    const askedIds = stopIds;
    setOptimizing(true);
    try {
      const view = await optimizeRouteStops(companyId, { originId: askedOrigin, destinationIds: askedIds });
      // Mientras el asistente pensaba alguien pudo tocar la lista: la respuesta es de la de antes.
      const nowIds = getValues("stops").map((stop) => stop.destinationId);
      if (getValues("originId") !== askedOrigin || nowIds.join() !== askedIds.join()) return;
      if (view.improved) reorderStops(view.destinationIds);
      setProposal({
        view, originId: askedOrigin, previousIds: askedIds, shownIds: view.improved ? view.destinationIds : askedIds,
      });
    } catch (error) {
      notifyError(t("No se pudieron optimizar las paradas"), describeApiError(error as ApiError));
    } finally {
      setOptimizing(false);
    }
  }

  /** Pone las paradas en este orden, conservando el tiempo de servicio de cada una. */
  function reorderStops(ids: string[]) {
    const byId = new Map(getValues("stops").map((stop) => [stop.destinationId, stop]));
    replace(ids.map((id) => byId.get(id) ?? { destinationId: id, serviceTimeOverrideMinutes: "" }));
  }

  /** Quita el banner y, si el orden propuesto se había aplicado, vuelve al de antes. */
  function discardProposal() {
    if (liveProposal?.view.improved) reorderStops(liveProposal.previousIds);
    setProposal(null);
  }

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

  return (
    <FormDrawer
      open
      icon={<AltRouteRounded />}
      title={isEdit ? t("Editar ruta") : t("Nueva ruta")}
      subtitle={isEdit
        ? [route.code, route.name, route.originName].filter(Boolean).join(" · ")
        : t("Origen, cadencia y la secuencia de paradas que recorre.")}
      titleAdornment={isEdit ? <ActiveBadge active={route.active} /> : undefined}
      footerStart={isEdit ? <FormMeta>{t("Actualizado el {{date}}", { date: fmtDateTime(route.updatedAt) })}</FormMeta> : undefined}
      size="xl"
      onClose={onClose}
      dirty={isDirty}
      closeOnBackdrop={!isSubmitting}
      footer={
        <>
          <Button onClick={onClose} disabled={isSubmitting} color="inherit" sx={{ color: "text.secondary" }}>{t("Cancelar")}</Button>
          <Button type="submit" form={FORM_ID} variant="contained" startIcon={<CheckRounded />} disabled={isSubmitting}>
            {isSubmitting ? t("Guardando...") : isEdit ? t("Guardar cambios") : t("Crear ruta")}
          </Button>
        </>
      }
    >
      <Box component="form" id={FORM_ID} onSubmit={(event) => void handleSubmit(onSubmit)(event)} noValidate>
        {formError && <Alert severity="error" sx={{ mb: 1 }}>{formError}</Alert>}

        <FormSection title={t("Identificación")} help={t("Con qué código y nombre se reconoce la ruta.")}>
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

        <FormSection title={t("Operación")} help={t("Desde dónde sale, en qué zona trabaja y con qué cadencia.")}>
        <FormRow cols={3}>
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
        </FormRow>
        </FormSection>

        <FormSection title={t("Referencia")} help={t("Distancia y duración habituales del recorrido completo. Opcionales.")}>
        <FormRow>
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
        </FormRow>
        </FormSection>

        <FormSection
          title={t("Paradas")}
          help={t("El orden de la lista ES la secuencia de paradas. Deja el tiempo de atención vacío para heredar el de la ubicación.")}
          actions={
            <Tooltip title={canOptimize || optimizing
              ? t("Ordena las paradas con IA para recorrer menos kilómetros. Se guarda solo cuando guardas la ruta.")
              : t("Agrega al menos dos paradas para optimizar.")}>
              <span>
                <Button
                  variant="outlined" size="small" onClick={() => void optimizeStops()} disabled={!canOptimize}
                  startIcon={optimizing ? <CircularProgress size={16} /> : <AutoAwesomeRounded />}
                >
                  {optimizing ? t("Optimizando...") : t("Optimizar paradas")}
                </Button>
              </span>
            </Tooltip>
          }
        >
        <Box sx={{ display: "flex", gap: 1.5, alignItems: "center", flexWrap: "wrap" }}>
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
          <ToggleButtonGroup
            size="small" exclusive value={stopsView} aria-label={t("Vista de paradas")}
            onChange={(_event, next: "list" | "map" | null) => { if (next) setStopsView(next); }}
            sx={{ "& .MuiToggleButton-root": { gap: 0.5, textTransform: "none" } }}
          >
            <ToggleButton value="list"><ViewListRounded fontSize="small" />{t("Lista")}</ToggleButton>
            <ToggleButton value="map"><MapRounded fontSize="small" />{t("Mapa")}</ToggleButton>
          </ToggleButtonGroup>
        </Box>

        {liveProposal && (
          <RouteStopProposal proposal={liveProposal.view} onDiscard={discardProposal} />
        )}

        {fields.length === 0 ? (
          <Alert severity="info">{t("Esta ruta todavía no tiene paradas.")}</Alert>
        ) : stopsView === "map" ? (
          <Box>
            <TripStopMap origin={mapOrigin} stops={mapStops} height={380} />
            <Typography variant="caption" color="text.secondary" sx={{ display: "block", mt: 0.75 }}>
              {t("El origen es el 0 y las paradas van numeradas en el orden de la lista. Las líneas unen los puntos en línea recta: no son el recorrido por carretera.")}
            </Typography>
          </Box>
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
        </FormSection>
      </Box>
    </FormDrawer>
  );
}
