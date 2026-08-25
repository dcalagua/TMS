import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { Controller, useForm } from "react-hook-form";
import {
  Alert, Box, Button, Checkbox, FormControlLabel, MenuItem, Paper, TextField, Typography,
} from "@mui/material";
import { PowerRounded } from "@mui/icons-material";
import { applyApiFieldErrors } from "../../shared/api/formErrors";
import type { ApiError } from "../../shared/api/httpClient";
import { fetchCarriers } from "../../shared/api/carriersApi";
import {
  createIntegrationClient, updateIntegrationClient,
  type IntegrationClientRequest, type IntegrationClientSecretView, type IntegrationClientView,
} from "../../shared/api/integrationsApi";
import { FormDrawer, SectionHeader } from "../../shared/ui/components";
import { t } from "../../lib/i18n";

const FORM_ID = "integration-client-form";

/**
 * Los alcances que puede tener una credencial, con lo que concede cada uno dicho en una frase.
 *
 * La frase importa tanto como el código: quien concede `integration.order:write` está dejando que
 * un sistema cree pedidos sin que nadie mire, y el código por sí solo no dice eso.
 */
const SCOPES = [
  {
    code: "integration.location:write",
    label: "Escribir ubicaciones",
    help: "Crear y actualizar tiendas y ubicaciones. No puede leer nada.",
  },
  {
    code: "integration.order:write",
    label: "Escribir pedidos",
    help: "Crear y actualizar pedidos de transporte sin supervisión. El alcance más potente: concédelo solo a un sistema al que dejarías crear pedidos sin que nadie mire.",
  },
  {
    code: "integration.shipment:read",
    label: "Leer embarques",
    help: "Leer embarques confirmados y consultar el feed de cambios. Solo lectura: no concede escritura en ningún sitio.",
  },
  {
    code: "integration.tracking:write",
    label: "Reportar posiciones",
    help: "Reportar posiciones de vehículos. Solo escritura: un proveedor de telemetría no aprende nada de los embarques contra los que reporta.",
  },
  {
    code: "integration.tender:respond",
    label: "Responder ofertas",
    help: "Ver y responder las ofertas hechas a un transportista. Necesita un transportista y muestra solo sus ofertas.",
  },
] as const;

/** El único alcance que no significa nada sin transportista. */
const TENDER_SCOPE = "integration.tender:respond";

interface ClientFormValues {
  name: string;
  description: string;
  scopes: string[];
  carrierId: string;
}

interface IntegrationClientDrawerProps {
  companyId: string;
  client: IntegrationClientView | null;
  onClose: () => void;
  /** Al crear, la respuesta trae el secreto: la pantalla de arriba lo enseña una sola vez. */
  onCreated: (secret: IntegrationClientSecretView) => void;
  onUpdated: () => void;
}

const KNOWN_FIELDS = new Set<keyof ClientFormValues>(["name", "description", "scopes", "carrierId"]);

/**
 * Emitir o editar una credencial de máquina.
 *
 * Los alcances se eligen uno a uno y con su consecuencia escrita al lado. Una lista de códigos
 * sin explicación es cómo alguien concede escritura de pedidos pensando que concedía lectura.
 *
 * El transportista aparece solo si se marcó "responder ofertas": es el único alcance que lo
 * necesita, y ofrecerlo siempre sugeriría que una credencial de telemetría pertenece a alguien.
 */
export function IntegrationClientDrawer({
  companyId, client, onClose, onCreated, onUpdated,
}: IntegrationClientDrawerProps) {
  const isEdit = client !== null;
  const [formError, setFormError] = useState<string | null>(null);

  const carriersQuery = useQuery({
    queryKey: ["carriers-for-integration", companyId],
    queryFn: ({ signal }) => fetchCarriers({ companyId, size: 200, active: true, sort: "code,asc", signal }),
  });

  const {
    register, control, handleSubmit, setError, watch,
    formState: { errors, isDirty, isSubmitting },
  } = useForm<ClientFormValues>({
    defaultValues: {
      name: client?.name ?? "",
      description: client?.description ?? "",
      scopes: client?.scopes ?? [],
      carrierId: client?.carrierId ?? "",
    },
  });

  const scopes = watch("scopes");
  const answersTenders = scopes.includes(TENDER_SCOPE);

  async function onSubmit(values: ClientFormValues) {
    setFormError(null);
    if (values.scopes.length === 0) {
      setFormError(t("Selecciona al menos un alcance."));
      return;
    }
    if (values.scopes.includes(TENDER_SCOPE) && values.carrierId === "") {
      setFormError(t("Responder ofertas necesita un transportista."));
      return;
    }

    const request: IntegrationClientRequest = {
      name: values.name.trim(),
      description: values.description.trim() || null,
      scopes: values.scopes,
      carrierId: values.scopes.includes(TENDER_SCOPE) ? values.carrierId : null,
    };

    try {
      if (isEdit) {
        await updateIntegrationClient(companyId, client.id, request);
        onUpdated();
      } else {
        onCreated(await createIntegrationClient(companyId, request));
      }
    } catch (error) {
      setFormError(applyApiFieldErrors(error as ApiError, KNOWN_FIELDS, setError, t("Corrige los campos marcados.")));
    }
  }

  return (
    <FormDrawer
      open
      icon={<PowerRounded />}
      title={isEdit ? t("Editar la credencial") : t("Nueva credencial")}
      subtitle={t("Con qué se autentica un socio y qué le dejas hacer.")}
      size="md"
      onClose={onClose}
      dirty={isDirty}
      closeOnBackdrop={!isSubmitting}
      footer={
        <>
          <Button onClick={onClose} disabled={isSubmitting}>{t("Cancelar")}</Button>
          <Button type="submit" form={FORM_ID} variant="contained" disabled={isSubmitting}>
            {isSubmitting ? t("Guardando...") : isEdit ? t("Guardar") : t("Emitir credencial")}
          </Button>
        </>
      }
    >
      <Box component="form" id={FORM_ID} onSubmit={(event) => void handleSubmit(onSubmit)(event)} noValidate>
        {formError && <Alert severity="error" sx={{ mb: 2 }}>{formError}</Alert>}

        <SectionHeader title={t("Identificación")} />
        <Box sx={{ display: "grid", gap: 2, mb: 3 }}>
          <TextField
            label={t("Nombre")} required size="small" fullWidth
            placeholder={t("p. ej. ERP de compras, telemetría de la flota")}
            error={Boolean(errors.name)} helperText={errors.name?.message}
            {...register("name", { required: t("Este campo es obligatorio") })}
          />
          <TextField
            label={t("Descripción")} size="small" fullWidth multiline rows={2}
            {...register("description")}
          />
        </Box>

        <SectionHeader title={t("Alcances")} />
        <Controller
          control={control}
          name="scopes"
          render={({ field }) => (
            <Box sx={{ display: "grid", gap: 1, mb: 3 }}>
              {SCOPES.map((scope) => (
                <Paper key={scope.code} variant="outlined" sx={{ p: 1.25 }}>
                  <FormControlLabel
                    sx={{ alignItems: "flex-start", m: 0 }}
                    control={
                      <Checkbox
                        sx={{ mt: -0.5 }}
                        checked={field.value.includes(scope.code)}
                        onChange={(e) => {
                          const next = e.target.checked
                            ? [...field.value, scope.code]
                            : field.value.filter((code) => code !== scope.code);
                          field.onChange(next);
                        }}
                      />
                    }
                    label={
                      <Box>
                        <Typography variant="body2" sx={{ fontWeight: 700 }}>{t(scope.label)}</Typography>
                        <Typography variant="caption" color="text.secondary" sx={{ display: "block" }}>
                          {t(scope.help)}
                        </Typography>
                        <Typography variant="caption" color="text.disabled" sx={{ display: "block", fontFamily: "monospace" }}>
                          {scope.code}
                        </Typography>
                      </Box>
                    }
                  />
                </Paper>
              ))}
            </Box>
          )}
        />

        {/* Solo cuando hace falta: una credencial de telemetría no pertenece a nadie. */}
        {answersTenders && (
          <>
            <SectionHeader title={t("Transportista")} />
            <Controller
              control={control}
              name="carrierId"
              render={({ field }) => (
                <TextField
                  select label={t("Transportista")} required size="small" fullWidth
                  value={field.value} onChange={(e) => field.onChange(e.target.value)}
                  helperText={t("La credencial solo verá y responderá las ofertas hechas a este transportista.")}
                >
                  <MenuItem value="">{t("Selecciona un transportista")}</MenuItem>
                  {(carriersQuery.data?.content ?? []).map((carrier) => (
                    <MenuItem key={carrier.id} value={carrier.id}>{carrier.businessName}</MenuItem>
                  ))}
                </TextField>
              )}
            />
          </>
        )}
      </Box>
    </FormDrawer>
  );
}
