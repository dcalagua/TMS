import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import {
  Alert, Box, Button, Chip, MenuItem, Paper, Stack, TextField, Typography,
} from "@mui/material";
import { PaymentsRounded } from "@mui/icons-material";
import {
  createOwnFleetProfile, listOwnFleetProfiles, setOwnFleetProfileActive, updateOwnFleetProfile,
  type OwnFleetCostProfileRequest, type OwnFleetCostProfileView,
} from "../../shared/api/ownFleetCostingApi";
import { fetchVehicles } from "../../shared/api/vehiclesApi";
import { fetchVehicleTypes } from "../../shared/api/vehicleTypesApi";
import type { ApiError } from "../../shared/api/httpClient";
import { describeApiError } from "../../shared/api/problemMessages";
import { DataTable, FormDrawer, PageHeader, type DataTableColumn } from "../../shared/ui/components";
import { useCompany } from "../../shared/company/CompanyContext";
import { confirmDialog, notifyError, notifySuccess } from "../../lib/ui";
import { t } from "../../lib/i18n";
import { requirementText, scopeText, stateColor, stateLabel } from "./ownFleetCostText";

/**
 * Qué cuesta operar nuestros propios camiones (migración V48, deuda D6).
 *
 * <h2>Costo interno, no tarifa</h2>
 * Esta pantalla no es la de tarifas. Una tarifa es un acuerdo comercial con un transportista; esto
 * es un modelo financiero de nuestra propia operación — cuánto le pagamos al conductor por hora,
 * qué creemos que cuesta el combustible, cuánto se deprecia el camión. Por eso tiene su propio
 * permiso: una instalación va a querer las dos cosas en manos distintas.
 *
 * <h2>Vacío no es cero</h2>
 * La regla que sostiene toda la pantalla. Un campo **vacío** significa "no modelamos este
 * componente": no se cobra y no falta nada. Un **0** significa "lo cobramos a cero": sí se cobra, y
 * sigue exigiendo su cantidad antes de que haya un total comparable. El formulario nunca convierte
 * uno en el otro, y el texto de ayuda lo dice porque nadie lo adivinaría.
 *
 * <h2>Sin perfil no hay costo, y eso no es costo cero</h2>
 * Un camión sin perfil vigente simplemente no tiene costo estimado. Si esta pantalla lo dejara en
 * cero, cada camión sin configurar sería la opción más barata en toda comparación de planificación.
 */
export function OwnFleetCostProfilesPage() {
  const { selected, hasPermission } = useCompany();
  const companyId = selected?.id ?? "";
  const canWrite = hasPermission("costing.own_fleet:write");
  const queryClient = useQueryClient();

  const [editing, setEditing] = useState<OwnFleetCostProfileView | null>(null);
  const [creating, setCreating] = useState(false);

  const profiles = useQuery({
    queryKey: ["own-fleet-profiles", companyId],
    queryFn: () => listOwnFleetProfiles(companyId),
    enabled: Boolean(companyId),
  });

  function refresh() {
    void queryClient.invalidateQueries({ queryKey: ["own-fleet-profiles", companyId] });
  }

  async function toggleActive(profile: OwnFleetCostProfileView) {
    const turningOff = profile.active;
    if (turningOff) {
      const confirmed = await confirmDialog({
        title: t("Desactivar el perfil de costo"),
        text: t("Los viajes de este vehículo dejarán de tener costo estimado. No pasarán a costar cero: pasarán a no tener costo, y la planificación lo dirá así."),
        confirmLabel: t("Desactivar"),
        dangerous: true,
      });
      if (!confirmed) return;
    }
    try {
      await setOwnFleetProfileActive(companyId, profile.id, !profile.active);
      notifySuccess(turningOff ? t("Perfil desactivado") : t("Perfil activado"));
      refresh();
    } catch (error) {
      notifyError(t("No se pudo cambiar el perfil"), describeApiError(error as ApiError));
    }
  }

  const columns: DataTableColumn<OwnFleetCostProfileView>[] = [
    { key: "scope", header: t("Se aplica a"), render: (row) => scopeText(row) },
    {
      key: "state", header: t("Estado"),
      render: (row) => (
        <Chip size="small" color={stateColor(row.state)} label={t(stateLabel(row.state))} />
      ),
    },
    { key: "currency", header: t("Moneda"), render: (row) => row.currency },
    {
      key: "validity", header: t("Vigencia"),
      render: (row) => `${row.effectiveFrom} → ${row.effectiveTo ?? t("sin fin")}`,
    },
    {
      key: "needs", header: t("Qué exige de cada viaje"),
      render: (row) => (
        <Typography variant="caption" color="text.secondary">{t(requirementText(row))}</Typography>
      ),
    },
    {
      key: "actions", header: "", actions: true,
      render: (row) => canWrite ? (
        <Stack direction="row" spacing={1}>
          <Button size="small" onClick={() => setEditing(row)}>{t("Editar")}</Button>
          <Button size="small" color="inherit" onClick={() => void toggleActive(row)}>
            {row.active ? t("Desactivar") : t("Activar")}
          </Button>
        </Stack>
      ) : null,
    },
  ];

  return (
    <Box>
      <PageHeader
        title={t("Costo de flota propia")}
        subtitle={t("Qué estimamos que nos cuesta operar nuestros propios camiones")}
        icon={<PaymentsRounded />}
        actions={canWrite ? (
          <Button variant="contained" onClick={() => setCreating(true)}>{t("Nuevo perfil")}</Button>
        ) : null}
      />

      <Alert severity="info" variant="outlined" sx={{ mb: 2 }}>
        {t("Esto produce un COSTO INTERNO ESTIMADO, no un precio. No lleva margen, no obliga a nadie y vale lo que valgan las tarifas que se escriban aquí. Un vehículo sin perfil vigente no cuesta cero: no tiene costo, y la planificación lo dice así.")}
      </Alert>

      <Paper variant="outlined">
        <DataTable
          columns={columns}
          rows={profiles.data ?? []}
          isLoading={profiles.isLoading}
          emptyMessage={t("Todavía no hay perfiles de costo. Sin ellos, ningún viaje de flota propia tiene costo estimado.")}
          rowKey={(row) => row.id}
          caption={t("Perfiles de costo de flota propia")}
        />
      </Paper>

      {(creating || editing) && (
        <ProfileDrawer
          companyId={companyId}
          profile={editing}
          onClose={() => { setCreating(false); setEditing(null); }}
          onSaved={() => { setCreating(false); setEditing(null); refresh(); }}
        />
      )}
    </Box>
  );
}

interface DrawerProps {
  companyId: string;
  profile: OwnFleetCostProfileView | null;
  onClose: () => void;
  onSaved: () => void;
}

/** Un campo vacío se envía como `null` y nunca como `0`. Es la regla entera del JOB 22. */
function numberOrNull(raw: string): number | null {
  const trimmed = raw.trim();
  return trimmed === "" ? null : Number(trimmed);
}

function ProfileDrawer({ companyId, profile, onClose, onSaved }: DrawerProps) {
  const editingExisting = profile !== null;
  const [target, setTarget] = useState<"VEHICLE" | "VEHICLE_TYPE">(
    profile?.vehicleTypeId ? "VEHICLE_TYPE" : "VEHICLE");
  const [vehicleId, setVehicleId] = useState(profile?.vehicleId ?? "");
  const [vehicleTypeId, setVehicleTypeId] = useState(profile?.vehicleTypeId ?? "");
  const [currency, setCurrency] = useState(profile?.currency ?? "PEN");
  const [from, setFrom] = useState(profile?.effectiveFrom ?? new Date().toISOString().slice(0, 10));
  const [to, setTo] = useState(profile?.effectiveTo ?? "");
  const [rates, setRates] = useState({
    fixedTripAmount: profile?.fixedTripAmount?.toString() ?? "",
    fuelPerKm: profile?.fuelPerKm?.toString() ?? "",
    driverPerHour: profile?.driverPerHour?.toString() ?? "",
    vehiclePerHour: profile?.vehiclePerHour?.toString() ?? "",
    maintenancePerKm: profile?.maintenancePerKm?.toString() ?? "",
    depreciationPerKm: profile?.depreciationPerKm?.toString() ?? "",
    tollAmount: profile?.tollAmount?.toString() ?? "",
  });
  const [submitting, setSubmitting] = useState(false);
  const [touched, setTouched] = useState(false);

  const vehicles = useQuery({
    queryKey: ["vehicles", companyId, "for-costing"],
    queryFn: () => fetchVehicles({ companyId, size: 200, active: true }),
    enabled: Boolean(companyId),
  });
  const vehicleTypes = useQuery({
    queryKey: ["vehicle-types", companyId, "for-costing"],
    queryFn: () => fetchVehicleTypes({ companyId, size: 200, active: true }),
    enabled: Boolean(companyId),
  });

  // Sólo los camiones que son nuestros. Un vehículo de transportista tiene precio, no costo interno.
  const ownVehicles = (vehicles.data?.content ?? []).filter((vehicle) => vehicle.carrierId === null);

  const anyRate = Object.values(rates).some((value) => value.trim() !== "");
  const targetChosen = target === "VEHICLE" ? vehicleId !== "" : vehicleTypeId !== "";

  function set(field: keyof typeof rates, value: string) {
    setRates((current) => ({ ...current, [field]: value }));
    setTouched(true);
  }

  async function submit() {
    setSubmitting(true);
    const request: OwnFleetCostProfileRequest = {
      vehicleId: target === "VEHICLE" ? vehicleId : null,
      vehicleTypeId: target === "VEHICLE_TYPE" ? vehicleTypeId : null,
      currency,
      effectiveFrom: from,
      effectiveTo: to.trim() === "" ? null : to,
      fixedTripAmount: numberOrNull(rates.fixedTripAmount),
      fuelPerKm: numberOrNull(rates.fuelPerKm),
      driverPerHour: numberOrNull(rates.driverPerHour),
      vehiclePerHour: numberOrNull(rates.vehiclePerHour),
      maintenancePerKm: numberOrNull(rates.maintenancePerKm),
      depreciationPerKm: numberOrNull(rates.depreciationPerKm),
      tollAmount: numberOrNull(rates.tollAmount),
    };
    try {
      if (editingExisting) {
        await updateOwnFleetProfile(companyId, profile.id, request);
      } else {
        await createOwnFleetProfile(companyId, request);
      }
      notifySuccess(t("Perfil de costo guardado"));
      onSaved();
    } catch (error) {
      notifyError(t("No se pudo guardar el perfil"), describeApiError(error as ApiError));
    } finally {
      setSubmitting(false);
    }
  }

  const rateFields: { field: keyof typeof rates; label: string; help: string }[] = [
    { field: "fixedTripAmount", label: t("Cargo fijo por viaje"), help: t("Se cobra una vez por viaje, pase lo que pase.") },
    { field: "fuelPerKm", label: t("Combustible por km"), help: t("Necesita que el viaje tenga distancia medible.") },
    { field: "driverPerHour", label: t("Conductor por hora"), help: t("Se cobra sobre la jornada, reposicionamiento incluido.") },
    { field: "vehiclePerHour", label: t("Vehículo por hora"), help: t("El camión ocupado, sin poder hacer otra cosa.") },
    { field: "maintenancePerKm", label: t("Mantenimiento por km"), help: t("Servicios y llantas, acumulados por kilómetro.") },
    { field: "depreciationPerKm", label: t("Depreciación por km"), help: t("El capital del camión consumido por kilómetro.") },
    { field: "tollAmount", label: t("Peajes por viaje"), help: t("Un monto fijo esperado. A propósito NO se calcula por km: los peajes dependen de qué carreteras usa la ruta, no de cuán larga es.") },
  ];

  return (
    <FormDrawer
      open
      title={editingExisting ? t("Editar perfil de costo") : t("Nuevo perfil de costo")}
      subtitle={t("Costo interno estimado — sin margen")}
      icon={<PaymentsRounded />}
      onClose={onClose}
      dirty={touched}
      size="md"
      footer={
        <>
          <Button onClick={onClose} disabled={submitting}>{t("Cancelar")}</Button>
          <Button
            variant="contained"
            disabled={submitting || !targetChosen || !anyRate}
            onClick={() => void submit()}
          >
            {submitting ? t("Guardando...") : t("Guardar")}
          </Button>
        </>
      }
    >
      <Stack spacing={2}>
        <Alert severity="warning" variant="outlined">
          {t("Deja un campo VACÍO para lo que no modelas: no se cobra y no falta nada. Escribe 0 sólo si de verdad lo cobras a cero — eso sí se cobra, y sigue exigiendo su cantidad. No son lo mismo.")}
        </Alert>

        <TextField
          select size="small" label={t("Se aplica a")} value={target}
          disabled={editingExisting}
          helperText={editingExisting
            ? t("Un perfil no se puede mover a otro vehículo: reformularía lo que ya se costeó con él.")
            : t("El perfil de un vehículo concreto le gana al de su tipo.")}
          onChange={(e) => { setTarget(e.target.value as "VEHICLE" | "VEHICLE_TYPE"); setTouched(true); }}
        >
          <MenuItem value="VEHICLE">{t("Un vehículo")}</MenuItem>
          <MenuItem value="VEHICLE_TYPE">{t("Un tipo de vehículo")}</MenuItem>
        </TextField>

        {target === "VEHICLE" ? (
          <TextField
            select size="small" label={t("Vehículo")} value={vehicleId}
            disabled={editingExisting}
            helperText={t("Sólo vehículos propios. Uno de transportista tiene precio, no costo interno.")}
            onChange={(e) => { setVehicleId(e.target.value); setTouched(true); }}
          >
            {ownVehicles.map((vehicle) => (
              <MenuItem key={vehicle.id} value={vehicle.id}>
                {vehicle.code} · {vehicle.licensePlate}
              </MenuItem>
            ))}
          </TextField>
        ) : (
          <TextField
            select size="small" label={t("Tipo de vehículo")} value={vehicleTypeId}
            disabled={editingExisting}
            onChange={(e) => { setVehicleTypeId(e.target.value); setTouched(true); }}
          >
            {(vehicleTypes.data?.content ?? []).map((type) => (
              <MenuItem key={type.id} value={type.id}>{type.code} · {type.name}</MenuItem>
            ))}
          </TextField>
        )}

        <Stack direction="row" spacing={2}>
          <TextField
            size="small" label={t("Moneda")} value={currency} sx={{ width: 120 }}
            helperText={t("Sin conversión")}
            onChange={(e) => { setCurrency(e.target.value.toUpperCase()); setTouched(true); }}
          />
          <TextField
            size="small" type="date" label={t("Vigente desde")} value={from}
            slotProps={{ inputLabel: { shrink: true } }}
            onChange={(e) => { setFrom(e.target.value); setTouched(true); }}
          />
          <TextField
            size="small" type="date" label={t("Hasta")} value={to}
            slotProps={{ inputLabel: { shrink: true } }}
            helperText={t("Vacío = sigue vigente")}
            onChange={(e) => { setTo(e.target.value); setTouched(true); }}
          />
        </Stack>

        {rateFields.map(({ field, label, help }) => (
          <TextField
            key={field} size="small" type="number" label={label} value={rates[field]}
            helperText={help}
            onChange={(e) => set(field, e.target.value)}
          />
        ))}

        {!anyRate && (
          <Alert severity="error" variant="outlined">
            {t("Un perfil que no cobra nada no es un perfil. Pon al menos un componente.")}
          </Alert>
        )}
      </Stack>
    </FormDrawer>
  );
}
