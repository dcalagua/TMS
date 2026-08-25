import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import { useForm } from "react-hook-form";
import { Alert, Autocomplete, Box, Button, TextField, Typography } from "@mui/material";
import { ApartmentRounded, AddBusinessRounded, SaveRounded } from "@mui/icons-material";
import { applyApiFieldErrors } from "../../shared/api/formErrors";
import type { ApiError } from "../../shared/api/httpClient";
import {
  fetchCompanyProfile, updateCompanyProfile, type CompanyProfileRequest,
} from "../../shared/api/administrationApi";
import { describeApiError } from "../../shared/api/problemMessages";
import { useCompany } from "../../shared/company/CompanyContext";
import {
  AppCard, DetailGrid, DetailItem, ErrorState, LoadingState, PageHeader, SectionHeader, StatusChip,
} from "../../shared/ui/components";
import { ICON_TINTS } from "../../shared/ui/navConfig";
import { notifySuccess } from "../../lib/ui";
import { t } from "../../lib/i18n";
import { CompanyCreateDrawer } from "./CompanyCreateDrawer";

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
}

const KNOWN_FIELDS = new Set<keyof CompanyFormValues>([
  "name", "taxIdentifier", "timeZone", "defaultCountry", "orderNumberPrefix", "shipmentNumberPrefix",
]);

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
  const { selected, refetch: refetchCompanies } = useCompany();
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
    register, handleSubmit, setError, reset, setValue, watch,
    formState: { errors, isDirty, isSubmitting },
  } = useForm<CompanyFormValues>({
    defaultValues: {
      name: "", taxIdentifier: "", timeZone: "", defaultCountry: "", orderNumberPrefix: "", shipmentNumberPrefix: "",
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
    });
  }, [profile, reset]);

  async function onSubmit(values: CompanyFormValues) {
    setFormError(null);
    const request: CompanyProfileRequest = {
      name: values.name.trim(),
      taxIdentifier: values.taxIdentifier.trim() || null,
      timeZone: values.timeZone.trim(),
      defaultCountry: values.defaultCountry.trim().toUpperCase(),
      orderNumberPrefix: values.orderNumberPrefix.trim(),
      shipmentNumberPrefix: values.shipmentNumberPrefix.trim(),
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
                  label={t("RUC")} size="small" fullWidth
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
        </Box>
      </Box>

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
