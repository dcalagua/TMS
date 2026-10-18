import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import { Controller, useForm } from "react-hook-form";
import { Alert, Autocomplete, Box, Button, MenuItem, TextField, Typography } from "@mui/material";
import { ApartmentRounded, AddBusinessRounded, SaveRounded } from "@mui/icons-material";
import { applyApiFieldErrors } from "../../shared/api/formErrors";
import type { ApiError } from "../../shared/api/httpClient";
import {
  DISPATCH_CONFIRMATION_MODES, fetchCompanyProfile, updateCompanyProfile, type CompanyProfileRequest,
  type DispatchConfirmationMode, type AccrualTrigger, ACCRUAL_TRIGGERS,
} from "../../shared/api/administrationApi";
import { COST_ALLOCATION_BASES, type CostAllocationBasis } from "../../shared/api/costToServeApi";
import { describeApiError } from "../../shared/api/problemMessages";
import { useCompany } from "../../shared/company/CompanyContext";
import {
  AppCard, DetailGrid, DetailItem, ErrorState, LoadingState, PageHeader, SectionHeader, StatusChip,
} from "../../shared/ui/components";
import { ICON_TINTS } from "../../shared/ui/navConfig";
import { confirmDialog, notifySuccess } from "../../lib/ui";
import { enumLabel } from "../../lib/enums";
import { t } from "../../lib/i18n";
import { CompanyCreateDrawer } from "./CompanyCreateDrawer";
import { FiscalRegimeCard } from "./FiscalRegimeCard";
import { CAPABILITY } from "../../shared/api/meApi";

const FORM_ID = "company-profile-form";

const TIME_ZONES: string[] = typeof Intl.supportedValuesOf === "function" ? Intl.supportedValuesOf("timeZone") : [];

function isValidTimeZone(value: string): boolean {
  try {
    Intl.DateTimeFormat(undefined, { timeZone: value });
    return true;
  } catch {
    return false;
  }
}

interface CompanyFormValues {
  name: string;
  taxIdentifier: string;
  timeZone: string;
  defaultCountry: string;
  orderNumberPrefix: string;
  shipmentNumberPrefix: string;
  dispatchConfirmationMode: DispatchConfirmationMode;
  accrualTrigger: AccrualTrigger;
  /** "" = la base por defecto de la instalación (peso); no se envía. */
  costAllocationBasis: CostAllocationBasis | "";
}

const KNOWN_FIELDS = new Set<keyof CompanyFormValues>([
  "name", "taxIdentifier", "timeZone", "defaultCountry", "orderNumberPrefix", "shipmentNumberPrefix",
  "dispatchConfirmationMode", "accrualTrigger", "costAllocationBasis",
]);

/** Qué significa cada modo para quien opera, en una frase (ADR-013 §1). */
const DISPATCH_MODE_HELP: Record<DispatchConfirmationMode, string> = {
  MANUAL: "Un despachador marca la salida en TMS. Si el almacén envía su confirmación, solo se compara con el plan.",
  EXTERNAL_REQUIRED: "La salida la confirma el almacén (WMS). Despachar a mano exige el permiso de override y un motivo.",
  HYBRID: "Despacha quien confirme primero, TMS o el almacén; lo que llegue después solo se concilia.",
};

/**
 * Qué *es* esta empresa: su nombre, su documento, su zona horaria y los prefijos con los que
 * numera pedidos y envíos.
 *
 * El código no se edita. Es la clave con la que la nombra todo lo demás —integraciones incluidas—
 * y cambiarlo no sería corregir un dato, sería otra empresa.
 *
 * La zona horaria no es cosmética: es contra ella contra la que el backend decide a qué día
 * operativo pertenece una fecha de servicio. Por eso se valida contra el catálogo IANA del
 * navegador antes de mandarla.
 */
export function CompanySettingsPage() {
  const { selected, refetch: refetchCompanies, hasCapability } = useCompany();
  // ADR-027: el régimen fiscal lo ve quien lee facturas y lo edita quien gestiona el régimen.
  const canManageTax = hasCapability(CAPABILITY.TAX_REGIME_MANAGE);
  const canSeeTax = canManageTax || hasCapability(CAPABILITY.SETTLEMENT_VIEW);
  const companyId = selected?.id ?? "";
  const queryClient = useQueryClient();
  const [formError, setFormError] = useState<string | null>(null);
  const [showCreate, setShowCreate] = useState(false);

  const profileQuery = useQuery({
    queryKey: ["company-profile", companyId],
    queryFn: ({ signal }) => fetchCompanyProfile(companyId, signal),
    enabled: companyId !== "",
  });

  const {
    register, handleSubmit, setError, reset, setValue, watch, control,
    formState: { errors, isDirty, isSubmitting },
  } = useForm<CompanyFormValues>({
    defaultValues: {
      name: "", taxIdentifier: "", timeZone: "", defaultCountry: "", orderNumberPrefix: "", shipmentNumberPrefix: "",
      dispatchConfirmationMode: "MANUAL",
      accrualTrigger: "DISPATCHED",
      costAllocationBasis: "",
    },
  });

  // El formulario se siembra cuando llega el perfil, no en el render: `reset` es lo que hace que
  // `isDirty` signifique "el usuario cambió algo" y no "todavía no habían llegado los datos".
  const profile = profileQuery.data;
  useEffect(() => {
    if (!profile) return;
    reset({
      name: profile.name,
      taxIdentifier: profile.taxIdentifier ?? "",
      timeZone: profile.timeZone,
      defaultCountry: profile.settings.defaultCountry,
      orderNumberPrefix: profile.settings.orderNumberPrefix,
      shipmentNumberPrefix: profile.settings.shipmentNumberPrefix,
      // Un backend anterior a V52 no lo manda: es MANUAL, el valor por defecto del servidor.
      dispatchConfirmationMode: profile.settings.dispatchConfirmationMode ?? "MANUAL",
      accrualTrigger: profile.settings.accrualTrigger ?? "DISPATCHED",
      costAllocationBasis: profile.settings.costAllocationBasis ?? "",
    });
  }, [profile, reset]);

  async function onSubmit(values: CompanyFormValues) {
    setFormError(null);
    const currentMode = profile?.settings.dispatchConfirmationMode ?? "MANUAL";
    // Pasar a EXTERNAL_REQUIRED cambia quién puede despachar desde el día siguiente: se confirma.
    if (values.dispatchConfirmationMode === "EXTERNAL_REQUIRED" && currentMode !== "EXTERNAL_REQUIRED") {
      const confirmed = await confirmDialog({
        title: t("¿Exigir la confirmación del almacén?"),
        text: t("Los envíos saldrán cuando el almacén (WMS) confirme el despacho. Despachar a mano desde TMS exigirá el permiso de override y un motivo, que queda registrado."),
        confirmLabel: t("Exigir confirmación"),
      });
      if (!confirmed) return;
    }
    const request: CompanyProfileRequest = {
      name: values.name.trim(),
      taxIdentifier: values.taxIdentifier.trim() || null,
      timeZone: values.timeZone.trim(),
      defaultCountry: values.defaultCountry.trim().toUpperCase(),
      orderNumberPrefix: values.orderNumberPrefix.trim(),
      shipmentNumberPrefix: values.shipmentNumberPrefix.trim(),
      dispatchConfirmationMode: values.dispatchConfirmationMode,
      accrualTrigger: values.accrualTrigger,
      costAllocationBasis: values.costAllocationBasis === "" ? undefined : values.costAllocationBasis,
    };

    try {
      const next = await updateCompanyProfile(companyId, request);
      queryClient.setQueryData(["company-profile", companyId], next);
      // El nombre de la empresa sale en el shell: sin esto, la barra superior seguiría diciendo
      // el anterior hasta la siguiente recarga.
      refetchCompanies();
      notifySuccess(t("Cambios guardados"));
    } catch (error) {
      setFormError(applyApiFieldErrors(error as ApiError, KNOWN_FIELDS, setError, t("Corrige los campos marcados.")));
    }
  }

  if (profileQuery.isPending) return <LoadingState label={t("Cargando la empresa...")} />;
  if (profileQuery.isError || !profile) {
    return (
      <ErrorState
        message={describeApiError(profileQuery.error as ApiError)}
        onRetry={() => void profileQuery.refetch()}
      />
    );
  }

  const grid2 = { display: "grid", gap: 2, gridTemplateColumns: { xs: "1fr", sm: "1fr 1fr" } } as const;

  return (
    <>
      <PageHeader
        icon={<ApartmentRounded />}
        tint={ICON_TINTS["/settings/company"]}
        title={t("Empresa")}
        subtitle={t("Qué es esta empresa y cómo numera lo que produce.")}
        meta={!profile.organizationActive && <StatusChip label={t("Organización inactiva")} tone="overdue" />}
        actions={
          <Box sx={{ display: "flex", gap: 1 }}>
            {/* Solo para quien tiene un rol de organización: crear una empresa alcanza a toda la
                organización, y `iam.company:manage` lo tiene también un administrador de una sola. */}
            {profile.canCreateCompany && (
              <Button variant="outlined" startIcon={<AddBusinessRounded />} onClick={() => setShowCreate(true)}>
                {t("Nueva empresa")}
              </Button>
            )}
            <Button
              type="submit" form={FORM_ID} variant="contained" startIcon={<SaveRounded />}
              disabled={isSubmitting || !isDirty}
            >
              {isSubmitting ? t("Guardando...") : t("Guardar")}
            </Button>
          </Box>
        }
      />

      {!profile.organizationActive && (
        <Alert severity="warning" sx={{ mb: 3 }}>
          {t("La organización está desactivada: eso revoca cada membresía por debajo de ella.")}
        </Alert>
      )}

      <Box component="form" id={FORM_ID} onSubmit={(event) => void handleSubmit(onSubmit)(event)} noValidate>
        {formError && <Alert severity="error" sx={{ mb: 2 }}>{formError}</Alert>}

        <Box sx={{ display: "grid", gap: 3, gridTemplateColumns: { xs: "1fr", lg: "1fr 1fr" } }}>
          <AppCard title={t("Identidad")}>
            <SectionHeader title={t("Datos fijos")} />
            <DetailGrid>
              {/* El código no se edita: es la clave con la que la nombra todo lo demás. */}
              <DetailItem label={t("Código")} value={profile.code} />
              <DetailItem label={t("Organización")} value={profile.organization.name} />
            </DetailGrid>

            <SectionHeader title={t("Datos editables")} />
            <Box sx={{ display: "grid", gap: 2 }}>
              <TextField
                label={t("Nombre")} required size="small" fullWidth
                error={Boolean(errors.name)} helperText={errors.name?.message}
                {...register("name", { required: t("Este campo es obligatorio") })}
              />
              <Box sx={grid2}>
                <TextField
                  label={t("Identificación fiscal")} size="small" fullWidth
                  error={Boolean(errors.taxIdentifier)} helperText={errors.taxIdentifier?.message}
                  {...register("taxIdentifier")}
                />
                <Autocomplete
                  freeSolo
                  size="small"
                  options={TIME_ZONES}
                  value={watch("timeZone")}
                  onChange={(_e, next) => setValue("timeZone", next ?? "", { shouldDirty: true, shouldValidate: true })}
                  onInputChange={(_e, next) => setValue("timeZone", next, { shouldDirty: true })}
                  renderInput={(params) => (
                    <TextField
                      {...params}
                      label={t("Zona horaria")} required placeholder="America/Lima"
                      error={Boolean(errors.timeZone)} helperText={errors.timeZone?.message}
                    />
                  )}
                />
              </Box>
              {/* Registrado aparte del Autocomplete para que la validación siga viviendo en el
                  formulario y no en el componente. */}
              <input
                type="hidden"
                {...register("timeZone", {
                  required: t("Este campo es obligatorio"),
                  validate: (value) => isValidTimeZone(value) || t("Debe ser una zona horaria IANA válida, por ejemplo America/Lima"),
                })}
              />
            </Box>
          </AppCard>

          <AppCard title={t("Numeración y defectos")}>
            <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
              {t("Los prefijos van delante del correlativo: «TO-» produce TO-00000001. El país por defecto se aplica a una fila de importación que deje el país en blanco.")}
            </Typography>
            <Box sx={{ display: "grid", gap: 2 }}>
              <Box sx={grid2}>
                <TextField
                  label={t("Prefijo de pedidos")} required size="small" fullWidth
                  error={Boolean(errors.orderNumberPrefix)} helperText={errors.orderNumberPrefix?.message}
                  {...register("orderNumberPrefix", { required: t("Este campo es obligatorio") })}
                />
                <TextField
                  label={t("Prefijo de envíos")} required size="small" fullWidth
                  error={Boolean(errors.shipmentNumberPrefix)} helperText={errors.shipmentNumberPrefix?.message}
                  {...register("shipmentNumberPrefix", { required: t("Este campo es obligatorio") })}
                />
              </Box>
              <TextField
                label={t("País por defecto")} required size="small" sx={{ maxWidth: 200 }}
                placeholder="PE"
                error={Boolean(errors.defaultCountry)} helperText={errors.defaultCountry?.message}
                {...register("defaultCountry", {
                  required: t("Este campo es obligatorio"),
                  maxLength: { value: 2, message: t("No puede superar los {{count}} caracteres", { count: 2 }) },
                })}
              />
            </Box>
          </AppCard>

          <AppCard title={t("Despacho")}>
            <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
              {t("Quién da por salido un envío: el despachador en TMS, el almacén (WMS) con su confirmación de despacho, o el primero de los dos. La confirmación del almacén se guarda y se compara con el plan en todos los modos.")}
            </Typography>
            <Controller
              name="dispatchConfirmationMode"
              control={control}
              render={({ field }) => (
                <TextField
                  select size="small" fullWidth label={t("Modo de confirmación de despacho")}
                  value={field.value} onChange={field.onChange} onBlur={field.onBlur}
                  error={Boolean(errors.dispatchConfirmationMode)}
                  helperText={errors.dispatchConfirmationMode?.message ?? t(DISPATCH_MODE_HELP[field.value])}
                >
                  {DISPATCH_CONFIRMATION_MODES.map((mode) => (
                    <MenuItem key={mode} value={mode}>{enumLabel("dispatchConfirmationMode", mode)}</MenuItem>
                  ))}
                </TextField>
              )}
            />
          </AppCard>

          <AppCard title={t("Provisión de fletes")}>
            <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
              {t("Cuándo un viaje con transportista pasa a ser una obligación provisionada. Cambiarlo no mueve las provisiones ya creadas; vale para los viajes que se ejecuten desde ahora.")}
            </Typography>
            <Controller
              name="accrualTrigger"
              control={control}
              render={({ field }) => (
                <TextField
                  select size="small" fullWidth label={t("La provisión nace")}
                  value={field.value} onChange={field.onChange} onBlur={field.onBlur}
                >
                  {ACCRUAL_TRIGGERS.map((trigger) => (
                    <MenuItem key={trigger} value={trigger}>{enumLabel("accrualTrigger", trigger)}</MenuItem>
                  ))}
                </TextField>
              )}
            />
          </AppCard>

          <AppCard title={t("Costo para servir")}>
            <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
              {t("Cómo se reparte el costo de cada viaje entre sus pedidos. Un pedido sin la medida de la base no recibe costo: se cuenta. Cambiarla no reescribe repartos anteriores; vale para los próximos cálculos.")}
            </Typography>
            <Controller
              name="costAllocationBasis"
              control={control}
              render={({ field }) => (
                <TextField
                  select size="small" fullWidth label={t("Base de reparto")}
                  value={field.value} onChange={field.onChange} onBlur={field.onBlur}
                  slotProps={{ select: { displayEmpty: true }, inputLabel: { shrink: true } }}
                >
                  {field.value === "" && (
                    <MenuItem value="">{t("Por defecto de la instalación (peso)")}</MenuItem>
                  )}
                  {COST_ALLOCATION_BASES.map((basis) => (
                    <MenuItem key={basis} value={basis}>{enumLabel("costAllocationBasis", basis)}</MenuItem>
                  ))}
                </TextField>
              )}
            />
          </AppCard>
        </Box>
      </Box>

      {/* Fuera del formulario de la empresa: se guarda aparte, con su propio permiso. */}
      {canSeeTax && (
        <Box sx={{ mt: 3 }}>
          <FiscalRegimeCard companyId={companyId} canManage={canManageTax} />
        </Box>
      )}

      {showCreate && (
        <CompanyCreateDrawer
          companyId={companyId}
          onClose={() => setShowCreate(false)}
          onCreated={() => {
            setShowCreate(false);
            notifySuccess(t("Empresa creada"));
            // La nueva empresa tiene que aparecer en el selector de la barra superior.
            refetchCompanies();
          }}
        />
      )}
    </>
  );
}
