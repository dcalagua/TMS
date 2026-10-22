import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { Controller, useForm } from "react-hook-form";
import {
  Alert, Box, Button, Checkbox, FormControlLabel, MenuItem, Paper, Stack, TextField, Typography,
} from "@mui/material";
import { CheckRounded, PowerRounded, WarehouseRounded } from "@mui/icons-material";
import { applyApiFieldErrors } from "../../shared/api/formErrors";
import type { ApiError } from "../../shared/api/httpClient";
import { fetchCarriers } from "../../shared/api/carriersApi";
import {
  createIntegrationClient, fetchIntegrationScopes, updateIntegrationClient,
  type IntegrationClientRequest, type IntegrationClientSecretView, type IntegrationClientView,
  type IntegrationScopeView,
} from "../../shared/api/integrationsApi";
import { ActiveBadge, FormDrawer, FormMeta, FormRow, FormSection } from "../../shared/ui/components";
import { t } from "../../lib/i18n";
import { enumLabel } from "../../lib/enums";
import { fmtDateTime } from "../../lib/locale";

const FORM_ID = "integration-client-form";

/**
 * Lo que concede cada alcance, dicho en una frase. El catálogo de alcances viene del backend
 * (`GET /integration-clients/scopes`): esta tabla sólo pone las palabras.
 *
 * La frase importa tanto como el código: quien concede `integration.order:write` está dejando que
 * un sistema cree pedidos sin que nadie mire, y el código por sí solo no dice eso. Un alcance que
 * esta versión no conoce se ofrece igual, con su código como nombre: es feo, pero no desaparece.
 */
const SCOPE_TEXT: Record<string, { label: string; help: string }> = {
  "integration.location:write": {
    label: "Escribir ubicaciones",
    help: "Crear y actualizar tiendas y ubicaciones. No puede leer nada.",
  },
  "integration.order:write": {
    label: "Escribir pedidos",
    help: "Crear y actualizar pedidos de transporte sin supervisión. El alcance más potente: concédelo solo a un sistema al que dejarías crear pedidos sin que nadie mire.",
  },
  "integration.document:write": {
    label: "Registrar documentos",
    help: "Registrar los documentos que el ERP emitió para los pedidos (factura, guía de remisión). TMS los recibe; no emite ninguno.",
  },
  "integration.shipment:read": {
    label: "Leer embarques",
    help: "Leer embarques confirmados y consultar el feed de cambios. Solo lectura: no concede escritura en ningún sitio.",
  },
  "integration.dispatch:write": {
    label: "Reportar despachos",
    help: "Informar lo que el almacén despachó físicamente. Solo escritura: para leer el plan contra el que despacha, la credencial necesita también «Leer embarques».",
  },
  "integration.warehouse-milestone:write": {
    label: "Reportar hitos del almacén",
    help: "Informar que la carga empezó, quedó lista o se anuló. Solo escritura.",
  },
  "integration.material:write": {
    label: "Escribir perfiles de material",
    help: "Escribir el perfil físico que midió el almacén: dimensiones, peso y apilado. Solo escritura.",
  },
  "integration.order:read": {
    label: "Leer pedidos",
    help: "Leer los pedidos de transporte, uno por número o el feed por estado y fecha. Solo lectura.",
  },
  "integration.warehouse-order-event:write": {
    label: "Reportar eventos de pedido del almacén",
    help: "Informar que el almacén anuló su pedido o lo cerró con saldo. Como mucho genera una retención: TMS nunca anula por sí solo.",
  },
  "integration.tracking:write": {
    label: "Reportar posiciones",
    help: "Reportar posiciones de vehículos. Solo escritura: un proveedor de telemetría no aprende nada de los embarques contra los que reporta.",
  },
  "integration.tender:respond": {
    label: "Responder ofertas",
    help: "Ver y responder las ofertas hechas a un transportista. Necesita un transportista y muestra solo sus ofertas.",
  },
  "integration.payable-payment:write": {
    label: "Registrar pagos de facturas",
    help: "El ERP informa lo que pagó contra una factura de transportista exportada. TMS lo registra y no mueve dinero.",
  },
};

/** El orden de los grupos en el formulario; uno que esta versión no conoce va al final. */
const GROUP_ORDER = ["ERP", "WAREHOUSE", "CARRIER", "FINANCE"];

/** El grupo cuyos alcances marca de una vez la plantilla «Conector EWM». */
const WAREHOUSE_GROUP = "WAREHOUSE";

/** Los alcances de una credencial ya emitida que el catálogo actual no trae. */
const UNLISTED_GROUP = "__UNLISTED__";

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

  const scopesQuery = useQuery({
    queryKey: ["integration-scopes", companyId],
    queryFn: ({ signal }) => fetchIntegrationScopes(companyId, signal),
    enabled: companyId !== "",
    staleTime: Infinity,
  });
  const catalogue = scopesQuery.data ?? [];
  const carrierScopes = new Set(catalogue.filter((scope) => scope.requiresCarrier).map((scope) => scope.code));

  const scopes = watch("scopes");
  const needsCarrier = (codes: string[]) => codes.some((code) => carrierScopes.has(code));
  const answersTenders = needsCarrier(scopes);

  async function onSubmit(values: ClientFormValues) {
    setFormError(null);
    if (values.scopes.length === 0) {
      setFormError(t("Selecciona al menos un alcance."));
      return;
    }
    if (needsCarrier(values.scopes) && values.carrierId === "") {
      setFormError(t("Responder ofertas necesita un transportista."));
      return;
    }

    const request: IntegrationClientRequest = {
      name: values.name.trim(),
      description: values.description.trim() || null,
      scopes: values.scopes,
      carrierId: needsCarrier(values.scopes) ? values.carrierId : null,
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
      subtitle={isEdit
        ? [client.name, client.clientId].filter(Boolean).join(" · ")
        : t("Con qué se autentica un socio y qué le dejas hacer.")}
      titleAdornment={isEdit ? <ActiveBadge active={client.active} /> : undefined}
      footerStart={isEdit ? <FormMeta>{t("Actualizado el {{date}}", { date: fmtDateTime(client.updatedAt) })}</FormMeta> : undefined}
      size="md"
      onClose={onClose}
      dirty={isDirty}
      closeOnBackdrop={!isSubmitting}
      footer={
        <>
          <Button onClick={onClose} disabled={isSubmitting} color="inherit" sx={{ color: "text.secondary" }}>{t("Cancelar")}</Button>
          <Button type="submit" form={FORM_ID} variant="contained" startIcon={<CheckRounded />} disabled={isSubmitting}>
            {isSubmitting ? t("Guardando...") : isEdit ? t("Guardar cambios") : t("Emitir credencial")}
          </Button>
        </>
      }
    >
      <Box component="form" id={FORM_ID} onSubmit={(event) => void handleSubmit(onSubmit)(event)} noValidate>
        {formError && <Alert severity="error" sx={{ mb: 1 }}>{formError}</Alert>}

        <FormSection title={t("Identificación")} help={t("Qué sistema usa esta credencial, para reconocerla en la lista.")}>
        <FormRow cols={1}>
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
        </FormRow>
        </FormSection>

        <FormSection
          title={t("Alcances")}
          help={t("Qué le dejas hacer, uno a uno y con su consecuencia escrita. Al menos uno.")}
        >
        <Controller
          control={control}
          name="scopes"
          render={({ field }) => (
            <ScopePicker
              catalogue={catalogue}
              loading={scopesQuery.isPending}
              failed={scopesQuery.isError}
              value={field.value}
              onChange={field.onChange}
            />
          )}
        />
        </FormSection>

        {/* Solo cuando hace falta: una credencial de telemetría no pertenece a nadie. */}
        {answersTenders && (
          <FormSection title={t("Transportista")} help={t("A quién pertenece la credencial que responde ofertas.")}>
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
          </FormSection>
        )}
      </Box>
    </FormDrawer>
  );
}

interface ScopePickerProps {
  catalogue: IntegrationScopeView[];
  loading: boolean;
  failed: boolean;
  value: string[];
  onChange: (next: string[]) => void;
}

/**
 * Los alcances agrupados por para quién son, cada uno con su consecuencia escrita. La plantilla
 * «Conector EWM» marca de una vez los del almacén: son seis y conectar el EWM exige todos.
 */
function ScopePicker({ catalogue, loading, failed, value, onChange }: ScopePickerProps) {
  if (loading) {
    return <Typography variant="body2" color="text.secondary">{t("Cargando los alcances...")}</Typography>;
  }
  if (failed) {
    return <Alert severity="error">{t("No se pudieron cargar los alcances. Cierra y vuelve a abrir el formulario.")}</Alert>;
  }

  const listed = new Set(catalogue.map((scope) => scope.code));
  const entries = [
    ...catalogue,
    // Una credencial emitida con un alcance que el catálogo ya no trae sigue mostrándolo: si no,
    // guardarla se lo quitaría sin que nadie lo hubiera decidido.
    ...value.filter((code) => !listed.has(code)).map((code) => ({ code, group: UNLISTED_GROUP, requiresCarrier: false })),
  ];
  const groups = [...new Set(entries.map((scope) => scope.group))].sort((a, b) => rank(a) - rank(b));
  const warehouseCodes = catalogue.filter((scope) => scope.group === WAREHOUSE_GROUP).map((scope) => scope.code);
  const hasAllWarehouse = warehouseCodes.length > 0 && warehouseCodes.every((code) => value.includes(code));

  const toggle = (code: string, checked: boolean) =>
    onChange(checked ? [...value, code] : value.filter((existing) => existing !== code));

  return (
    <Stack spacing={1.5}>
      {warehouseCodes.length > 0 && (
        <Box sx={{ display: "flex", alignItems: "center", gap: 1, flexWrap: "wrap" }}>
          <Button
            size="small" variant="outlined" startIcon={<WarehouseRounded />} disabled={hasAllWarehouse}
            onClick={() => onChange([...new Set([...value, ...warehouseCodes])])}
          >
            {t("Plantilla: conector EWM")}
          </Button>
          <Typography variant="caption" color="text.secondary">
            {t("Marca los {{count}} alcances que necesita el almacén.", { count: warehouseCodes.length })}
          </Typography>
        </Box>
      )}
      {groups.map((group) => (
        <Box key={group} role="group" aria-label={groupLabel(group)}>
          <Typography variant="overline" color="text.secondary" sx={{ display: "block", lineHeight: 2 }}>
            {groupLabel(group)}
          </Typography>
          <Box sx={{ display: "grid", gap: 1 }}>
            {entries.filter((scope) => scope.group === group).map((scope) => {
              const text = SCOPE_TEXT[scope.code];
              return (
                <Paper key={scope.code} variant="outlined" sx={{ p: 1.25 }}>
                  <FormControlLabel
                    sx={{ alignItems: "flex-start", m: 0 }}
                    control={
                      <Checkbox
                        sx={{ mt: -0.5 }}
                        checked={value.includes(scope.code)}
                        onChange={(e) => toggle(scope.code, e.target.checked)}
                      />
                    }
                    label={
                      <Box>
                        <Typography variant="body2" sx={{ fontWeight: 700 }}>{text ? t(text.label) : scope.code}</Typography>
                        {text && (
                          <Typography variant="caption" color="text.secondary" sx={{ display: "block" }}>
                            {t(text.help)}
                          </Typography>
                        )}
                        <Typography variant="caption" color="text.disabled" sx={{ display: "block", fontFamily: "monospace" }}>
                          {scope.code}
                        </Typography>
                      </Box>
                    }
                  />
                </Paper>
              );
            })}
          </Box>
        </Box>
      ))}
    </Stack>
  );
}

function rank(group: string): number {
  const index = GROUP_ORDER.indexOf(group);
  return index === -1 ? GROUP_ORDER.length + (group === UNLISTED_GROUP ? 1 : 0) : index;
}

function groupLabel(group: string): string {
  return group === UNLISTED_GROUP ? t("Otros (fuera del catálogo actual)") : enumLabel("integrationScopeGroup", group);
}
