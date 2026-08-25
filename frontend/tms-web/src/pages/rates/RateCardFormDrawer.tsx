import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { Controller, useForm, useWatch } from "react-hook-form";
import { Alert, Box, Button, MenuItem, TextField, Typography } from "@mui/material";
import { PaidRounded } from "@mui/icons-material";
import { applyApiFieldErrors } from "../../shared/api/formErrors";
import type { ApiError } from "../../shared/api/httpClient";
import { fetchCarriers } from "../../shared/api/carriersApi";
import { fetchOrigins } from "../../shared/api/originsApi";
import { fetchRoutes } from "../../shared/api/routesApi";
import { fetchVehicleTypes } from "../../shared/api/vehicleTypesApi";
import {
  createRateCard, RATE_CARD_SCOPES, updateRateCard,
  type RateCardRequest, type RateCardScope, type RateCardView,
} from "../../shared/api/ratesApi";
import { FormDrawer, SectionHeader } from "../../shared/ui/components";
import { enumLabel } from "../../lib/enums";
import { t } from "../../lib/i18n";
import { today } from "../../lib/locale";

const FORM_ID = "rate-card-form";

const CODE_PATTERN = /^[A-Za-z0-9][A-Za-z0-9_-]{0,31}$/;
const CURRENCY_PATTERN = /^[A-Za-z]{3}$/;

interface RateCardFormValues {
  code: string;
  name: string;
  carrierId: string;
  scope: RateCardScope;
  originId: string;
  routeId: string;
  vehicleTypeId: string;
  currency: string;
  validFrom: string;
  validTo: string;
  baseAmount: string;
  amountPerKm: string;
  amountPerKg: string;
  amountPerM3: string;
  amountPerPallet: string;
  minimumAmount: string;
}

interface RateCardFormDrawerProps {
  companyId: string;
  rateCard: RateCardView | null;
  onClose: () => void;
  onSaved: () => void;
}

const KNOWN_FIELDS = new Set<keyof RateCardFormValues>([
  "code", "name", "carrierId", "scope", "originId", "routeId", "vehicleTypeId", "currency",
  "validFrom", "validTo", "baseAmount", "amountPerKm", "amountPerKg", "amountPerM3",
  "amountPerPallet", "minimumAmount",
]);

const toAmount = (value: string): number | null => (value.trim() === "" ? null : Number(value));

/**
 * Un tarifario: a qué transportista aplica, sobre qué alcance y con qué componentes se calcula.
 *
 * El alcance decide qué segundo campo hace falta. `CARRIER` no pide ninguno —vale para todo lo
 * que haga ese transportista—, `ORIGIN` pide un origen y `ROUTE` una ruta. Se enseña solo el que
 * corresponde en lugar de los tres en gris: los tres a la vez invitan a rellenar dos, y el
 * backend rechaza esa combinación.
 *
 * Los componentes son todos opcionales porque un tarifario real rara vez los usa todos: uno de
 * distancia pura deja peso y volumen vacíos, y un mínimo sin nada más es un precio plano.
 */
export function RateCardFormDrawer({ companyId, rateCard, onClose, onSaved }: RateCardFormDrawerProps) {
  const isEdit = rateCard !== null;
  const [formError, setFormError] = useState<string | null>(null);

  const carriersQuery = useQuery({
    queryKey: ["carriers-for-rate-form", companyId],
    queryFn: ({ signal }) => fetchCarriers({ companyId, size: 200, active: true, sort: "code,asc", signal }),
  });
  const originsQuery = useQuery({
    queryKey: ["origins-for-rate-form", companyId],
    queryFn: ({ signal }) => fetchOrigins({ companyId, size: 200, active: true, sort: "code,asc", signal }),
  });
  const routesQuery = useQuery({
    queryKey: ["routes-for-rate-form", companyId],
    queryFn: ({ signal }) => fetchRoutes({ companyId, size: 200, active: true, sort: "code,asc", signal }),
  });
  const typesQuery = useQuery({
    queryKey: ["vehicle-types-for-rate-form", companyId],
    queryFn: ({ signal }) => fetchVehicleTypes({ companyId, size: 200, active: true, sort: "code,asc", signal }),
  });

  const {
    register, control, handleSubmit, setError,
    formState: { errors, isDirty, isSubmitting },
  } = useForm<RateCardFormValues>({
    defaultValues: {
      code: rateCard?.code ?? "",
      name: rateCard?.name ?? "",
      carrierId: rateCard?.carrierId ?? "",
      scope: rateCard?.scope ?? "CARRIER",
      originId: rateCard?.scope === "ORIGIN" ? (rateCard.scopeTargetId ?? "") : "",
      routeId: rateCard?.scope === "ROUTE" ? (rateCard.scopeTargetId ?? "") : "",
      vehicleTypeId: rateCard?.vehicleTypeId ?? "",
      currency: rateCard?.currency ?? "PEN",
      validFrom: rateCard?.validFrom ?? today(),
      validTo: rateCard?.validTo ?? "",
      baseAmount: rateCard?.baseAmount?.toString() ?? "",
      amountPerKm: rateCard?.amountPerKm?.toString() ?? "",
      amountPerKg: rateCard?.amountPerKg?.toString() ?? "",
      amountPerM3: rateCard?.amountPerM3?.toString() ?? "",
      amountPerPallet: rateCard?.amountPerPallet?.toString() ?? "",
      minimumAmount: rateCard?.minimumAmount?.toString() ?? "",
    },
  });

  const scope = useWatch({ control, name: "scope" });

  async function onSubmit(values: RateCardFormValues) {
    setFormError(null);
    const request: RateCardRequest = {
      code: values.code.trim(),
      name: values.name.trim(),
      carrierId: values.carrierId,
      scope: values.scope,
      // Solo el objetivo del alcance elegido viaja: mandar los dos sería contradecirse.
      originId: values.scope === "ORIGIN" ? (values.originId || null) : null,
      routeId: values.scope === "ROUTE" ? (values.routeId || null) : null,
      vehicleTypeId: values.vehicleTypeId || null,
      currency: values.currency.trim().toUpperCase(),
      validFrom: values.validFrom,
      validTo: values.validTo || null,
      baseAmount: toAmount(values.baseAmount),
      amountPerKm: toAmount(values.amountPerKm),
      amountPerKg: toAmount(values.amountPerKg),
      amountPerM3: toAmount(values.amountPerM3),
      amountPerPallet: toAmount(values.amountPerPallet),
      minimumAmount: toAmount(values.minimumAmount),
    };

    try {
      if (isEdit) await updateRateCard(companyId, rateCard.id, request);
      else await createRateCard(companyId, request);
      onSaved();
    } catch (error) {
      setFormError(applyApiFieldErrors(error as ApiError, KNOWN_FIELDS, setError, t("Corrige los campos marcados.")));
    }
  }

  const grid2 = { display: "grid", gap: 2, gridTemplateColumns: { xs: "1fr", sm: "1fr 1fr" }, mb: 3 } as const;

  return (
    <FormDrawer
      open
      icon={<PaidRounded />}
      title={isEdit ? t("Editar tarifario") : t("Nuevo tarifario")}
      subtitle={t("Cómo se calcula lo que cuesta un envío con este transportista.")}
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
            label={t("Nombre")} required size="small" fullWidth
            error={Boolean(errors.name)} helperText={errors.name?.message}
            {...register("name", { required: t("Este campo es obligatorio") })}
          />
        </Box>

        <SectionHeader title={t("Alcance")} />
        <Box sx={grid2}>
          <Controller
            control={control}
            name="carrierId"
            rules={{ required: t("Este campo es obligatorio") }}
            render={({ field }) => (
              <TextField
                select label={t("Transportista")} required size="small" fullWidth
                value={field.value} onChange={(e) => field.onChange(e.target.value)}
                error={Boolean(errors.carrierId)} helperText={errors.carrierId?.message}
              >
                <MenuItem value="">{t("Selecciona un transportista")}</MenuItem>
                {(carriersQuery.data?.content ?? []).map((carrier) => (
                  <MenuItem key={carrier.id} value={carrier.id}>{carrier.businessName}</MenuItem>
                ))}
              </TextField>
            )}
          />
          <Controller
            control={control}
            name="scope"
            render={({ field }) => (
              <TextField
                select label={t("Ámbito")} required size="small" fullWidth
                value={field.value} onChange={(e) => field.onChange(e.target.value as RateCardScope)}
              >
                {RATE_CARD_SCOPES.map((option) => (
                  <MenuItem key={option} value={option}>{enumLabel("rateCardScope", option)}</MenuItem>
                ))}
              </TextField>
            )}
          />

          {/* Solo el objetivo que el ámbito elegido necesita. Los tres a la vez invitan a
              rellenar dos, y el backend rechaza esa combinación. */}
          {scope === "ORIGIN" && (
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
                  {(originsQuery.data?.content ?? []).map((origin) => (
                    <MenuItem key={origin.id} value={origin.id}>{origin.code} · {origin.name}</MenuItem>
                  ))}
                </TextField>
              )}
            />
          )}
          {scope === "ROUTE" && (
            <Controller
              control={control}
              name="routeId"
              rules={{ required: t("Este campo es obligatorio") }}
              render={({ field }) => (
                <TextField
                  select label={t("Ruta")} required size="small" fullWidth
                  value={field.value} onChange={(e) => field.onChange(e.target.value)}
                  error={Boolean(errors.routeId)} helperText={errors.routeId?.message}
                >
                  <MenuItem value="">{t("Selecciona una ruta")}</MenuItem>
                  {(routesQuery.data?.content ?? []).map((route) => (
                    <MenuItem key={route.id} value={route.id}>{route.code} · {route.name}</MenuItem>
                  ))}
                </TextField>
              )}
            />
          )}

          <Controller
            control={control}
            name="vehicleTypeId"
            render={({ field }) => (
              <TextField
                select label={t("Tipo de vehículo")} size="small" fullWidth
                value={field.value} onChange={(e) => field.onChange(e.target.value)}
                helperText={t("Opcional: acota el tarifario a un tipo de unidad.")}
              >
                <MenuItem value="">{t("Cualquier tipo")}</MenuItem>
                {(typesQuery.data?.content ?? []).map((type) => (
                  <MenuItem key={type.id} value={type.id}>{type.code} · {type.name}</MenuItem>
                ))}
              </TextField>
            )}
          />
        </Box>

        <SectionHeader title={t("Vigencia")} />
        <Box sx={{ display: "grid", gap: 2, gridTemplateColumns: { xs: "1fr", sm: "repeat(3, 1fr)" }, mb: 3 }}>
          <TextField
            label={t("Moneda")} required size="small" fullWidth placeholder="PEN"
            error={Boolean(errors.currency)} helperText={errors.currency?.message}
            {...register("currency", {
              required: t("Este campo es obligatorio"),
              pattern: { value: CURRENCY_PATTERN, message: t("Tres letras, p. ej. PEN") },
            })}
          />
          <TextField
            label={t("Vigente desde")} required size="small" fullWidth type="date"
            slotProps={{ inputLabel: { shrink: true } }}
            error={Boolean(errors.validFrom)} helperText={errors.validFrom?.message}
            {...register("validFrom", { required: t("Este campo es obligatorio") })}
          />
          <TextField
            label={t("Vigente hasta")} size="small" fullWidth type="date"
            slotProps={{ inputLabel: { shrink: true } }}
            helperText={t("Vacío = sin fecha de fin.")}
            {...register("validTo")}
          />
        </Box>

        <SectionHeader title={t("Componentes")} />
        <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>
          {t("Todos son opcionales. Un tarifario de distancia pura deja peso y volumen vacíos; un mínimo solo es un precio plano.")}
        </Typography>
        <Box sx={{ display: "grid", gap: 2, gridTemplateColumns: { xs: "1fr", sm: "repeat(3, 1fr)" } }}>
          {([
            ["baseAmount", "Importe base"],
            ["amountPerKm", "Por kilómetro"],
            ["amountPerKg", "Por kilogramo"],
            ["amountPerM3", "Por m³"],
            ["amountPerPallet", "Por pallet"],
            ["minimumAmount", "Mínimo"],
          ] as const).map(([name, label]) => (
            <TextField
              key={name}
              label={t(label)} size="small" fullWidth type="number"
              error={Boolean(errors[name])} helperText={errors[name]?.message}
              {...register(name, {
                validate: (value) => {
                  if (value.trim() === "") return true;
                  const parsed = Number(value);
                  if (Number.isNaN(parsed)) return t("Debe ser un número");
                  return parsed >= 0 || t("Debe ser cero o mayor");
                },
              })}
            />
          ))}
        </Box>
      </Box>
    </FormDrawer>
  );
}
