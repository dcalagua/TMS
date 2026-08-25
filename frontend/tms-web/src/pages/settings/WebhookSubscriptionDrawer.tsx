import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { Controller, useForm } from "react-hook-form";
import {
  Alert, Box, Button, Checkbox, FormControlLabel, Paper, TextField, Typography,
} from "@mui/material";
import { WebhookRounded } from "@mui/icons-material";
import { applyApiFieldErrors } from "../../shared/api/formErrors";
import type { ApiError } from "../../shared/api/httpClient";
import {
  createWebhookSubscription, fetchWebhookEventTypes, updateWebhookSubscription,
  type WebhookSubscriptionRequest, type WebhookSubscriptionSecretView, type WebhookSubscriptionView,
} from "../../shared/api/integrationsApi";
import { FormDrawer, LoadingState, SectionHeader } from "../../shared/ui/components";
import { t } from "../../lib/i18n";

const FORM_ID = "webhook-subscription-form";

interface SubscriptionFormValues {
  name: string;
  description: string;
  targetUrl: string;
  eventTypes: string[];
}

interface WebhookSubscriptionDrawerProps {
  companyId: string;
  subscription: WebhookSubscriptionView | null;
  onClose: () => void;
  /** Al crear, la respuesta trae el secreto de firma: se enseña una sola vez. */
  onCreated: (secret: WebhookSubscriptionSecretView) => void;
  onUpdated: () => void;
}

const KNOWN_FIELDS = new Set<keyof SubscriptionFormValues>(["name", "description", "targetUrl", "eventTypes"]);

/**
 * A dónde empuja esta empresa sus eventos y cuáles.
 *
 * Los tipos de evento los da el backend, no una lista escrita aquí: es el mismo vocabulario que
 * el feed de cambios por sondeo, y una copia en el frontend se quedaría corta el día que se añada
 * uno.
 *
 * La URL tiene que ser https en cualquier despliegue real. No se valida aquí más allá de que sea
 * una URL: quien decide qué destinos acepta es el backend, y duplicar esa regla es cómo el
 * formulario acaba rechazando algo que el servidor sí aceptaba.
 */
export function WebhookSubscriptionDrawer({
  companyId, subscription, onClose, onCreated, onUpdated,
}: WebhookSubscriptionDrawerProps) {
  const isEdit = subscription !== null;
  const [formError, setFormError] = useState<string | null>(null);

  const eventTypesQuery = useQuery({
    queryKey: ["webhook-event-types", companyId],
    queryFn: ({ signal }) => fetchWebhookEventTypes(companyId, signal),
  });

  const {
    register, control, handleSubmit, setError,
    formState: { errors, isDirty, isSubmitting },
  } = useForm<SubscriptionFormValues>({
    defaultValues: {
      name: subscription?.name ?? "",
      description: subscription?.description ?? "",
      targetUrl: subscription?.targetUrl ?? "",
      eventTypes: subscription?.eventTypes ?? [],
    },
  });

  async function onSubmit(values: SubscriptionFormValues) {
    setFormError(null);
    if (values.eventTypes.length === 0) {
      setFormError(t("Selecciona al menos un tipo de evento."));
      return;
    }

    const request: WebhookSubscriptionRequest = {
      name: values.name.trim(),
      description: values.description.trim() || null,
      targetUrl: values.targetUrl.trim(),
      eventTypes: values.eventTypes,
    };

    try {
      if (isEdit) {
        await updateWebhookSubscription(companyId, subscription.id, request);
        onUpdated();
      } else {
        onCreated(await createWebhookSubscription(companyId, request));
      }
    } catch (error) {
      setFormError(applyApiFieldErrors(error as ApiError, KNOWN_FIELDS, setError, t("Corrige los campos marcados.")));
    }
  }

  return (
    <FormDrawer
      open
      icon={<WebhookRounded />}
      title={isEdit ? t("Editar la suscripción") : t("Nueva suscripción")}
      subtitle={t("A dónde se empujan los eventos de esta empresa, y cuáles.")}
      size="md"
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

        <SectionHeader title={t("Destino")} />
        <Box sx={{ display: "grid", gap: 2, mb: 3 }}>
          <TextField
            label={t("Nombre")} required size="small" fullWidth
            error={Boolean(errors.name)} helperText={errors.name?.message}
            {...register("name", { required: t("Este campo es obligatorio") })}
          />
          <TextField
            label={t("URL de destino")} required size="small" fullWidth
            placeholder="https://..."
            error={Boolean(errors.targetUrl)}
            helperText={errors.targetUrl?.message ?? t("Cada entrega va firmada: el receptor verifica la firma con el secreto.")}
            {...register("targetUrl", {
              required: t("Este campo es obligatorio"),
              validate: (value) => {
                try {
                  new URL(value);
                  return true;
                } catch {
                  return t("Debe ser una URL válida");
                }
              },
            })}
          />
          <TextField
            label={t("Descripción")} size="small" fullWidth multiline rows={2}
            {...register("description")}
          />
        </Box>

        <SectionHeader title={t("Eventos")} />
        {eventTypesQuery.isPending ? (
          <LoadingState minHeight={120} />
        ) : (
          <Controller
            control={control}
            name="eventTypes"
            render={({ field }) => (
              <Paper variant="outlined" sx={{ p: 1.25 }}>
                <Box sx={{ display: "grid", gap: 0.25 }}>
                  {(eventTypesQuery.data ?? []).map((eventType) => (
                    <FormControlLabel
                      key={eventType}
                      sx={{ m: 0 }}
                      control={
                        <Checkbox
                          size="small"
                          checked={field.value.includes(eventType)}
                          onChange={(e) => {
                            const next = e.target.checked
                              ? [...field.value, eventType]
                              : field.value.filter((code) => code !== eventType);
                            field.onChange(next);
                          }}
                        />
                      }
                      label={
                        <Typography variant="body2" sx={{ fontFamily: "monospace", fontSize: 12.5 }}>
                          {eventType}
                        </Typography>
                      }
                    />
                  ))}
                </Box>
              </Paper>
            )}
          />
        )}
      </Box>
    </FormDrawer>
  );
}
