import { keepPreviousData, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Box, Button, Chip, MenuItem, TextField, Typography } from "@mui/material";
import {
  AddRounded, EditRounded, AutorenewRounded, BlockRounded, CheckCircleRounded, ReplayRounded,
} from "@mui/icons-material";
import type { ApiError } from "../../shared/api/httpClient";
import {
  fetchWebhookDeliveries, fetchWebhookSubscriptions, retryWebhookDelivery, rotateWebhookSecret,
  setWebhookSubscriptionActive,
  type WebhookDeliveryStatus, type WebhookDeliveryView,
  type WebhookSubscriptionSecretView, type WebhookSubscriptionView,
} from "../../shared/api/integrationsApi";
import { describeApiError } from "../../shared/api/problemMessages";
import {
  ActionMenu, DataTable, Pagination, SectionHeader, StatusChip, type DataTableColumn,
} from "../../shared/ui/components";
import { confirmDialog, notifyError, notifySuccess } from "../../lib/ui";
import type { StatusTone } from "../../theme";
import { t } from "../../lib/i18n";
import { fmtDateTime, fmtQuantity } from "../../lib/locale";
import { SecretRevealDrawer } from "./SecretRevealDrawer";
import { WebhookDeliveryDrawer } from "./WebhookDeliveryDrawer";
import { WebhookSubscriptionDrawer } from "./WebhookSubscriptionDrawer";

const PAGE_SIZE = 20;

const DELIVERY_TONE: Record<WebhookDeliveryStatus, StatusTone> = {
  PENDING: "inProgress",
  PROCESSED: "done",
  FAILED: "overdue",
};

interface OutboundPanelProps {
  companyId: string;
  canManage: boolean;
}

/**
 * La mitad de salida del hub: a dónde se empujan los eventos de esta empresa, y el registro de
 * lo que se entregó.
 *
 * La racha de fallos consecutivos es lo primero que se mira: no es un contador de por vida, se
 * pone a cero en cuanto algo se entrega, así que un número alto significa "esto está roto ahora",
 * no "esto falló alguna vez".
 */
export function OutboundPanel({ companyId, canManage }: OutboundPanelProps) {
  const queryClient = useQueryClient();
  const [subsPage, setSubsPage] = useState(0);
  const [deliveriesPage, setDeliveriesPage] = useState(0);
  const [subFilter, setSubFilter] = useState("");
  const [statusFilter, setStatusFilter] = useState<WebhookDeliveryStatus | "">("");
  const [editing, setEditing] = useState<WebhookSubscriptionView | null>(null);
  const [creating, setCreating] = useState(false);
  const [secret, setSecret] = useState<WebhookSubscriptionSecretView | null>(null);
  const [openDeliveryId, setOpenDeliveryId] = useState<string | null>(null);

  const subsQuery = useQuery({
    queryKey: ["webhook-subscriptions", companyId, subsPage],
    queryFn: ({ signal }) => fetchWebhookSubscriptions(companyId, { page: subsPage, size: PAGE_SIZE }, signal),
    placeholderData: keepPreviousData,
  });

  const deliveriesQuery = useQuery({
    queryKey: ["webhook-deliveries", companyId, deliveriesPage, subFilter, statusFilter],
    queryFn: ({ signal }) =>
      fetchWebhookDeliveries(
        companyId,
        {
          page: deliveriesPage,
          size: PAGE_SIZE,
          subscriptionId: subFilter || undefined,
          status: statusFilter || undefined,
        },
        signal,
      ),
    placeholderData: keepPreviousData,
  });

  function refresh() {
    void queryClient.invalidateQueries({ queryKey: ["webhook-subscriptions", companyId] });
    void queryClient.invalidateQueries({ queryKey: ["webhook-deliveries", companyId] });
  }

  async function toggleActive(subscription: WebhookSubscriptionView) {
    const confirmed = await confirmDialog({
      title: subscription.active
        ? t("¿Desactivar {{name}}?", { name: subscription.name })
        : t("¿Activar {{name}}?", { name: subscription.name }),
      text: subscription.active
        ? t("Se dejan de empujar eventos a este destino. Los pendientes no se reintentan.")
        : t("Vuelve a recibir eventos desde ahora. No se reenvían los de mientras estuvo apagada."),
      confirmLabel: subscription.active ? t("Desactivar") : t("Activar"),
      dangerous: subscription.active,
    });
    if (!confirmed) return;

    try {
      await setWebhookSubscriptionActive(companyId, subscription.id, !subscription.active);
      notifySuccess(subscription.active ? t("Suscripción desactivada") : t("Suscripción activada"), subscription.name);
      refresh();
    } catch (error) {
      notifyError(t("No se pudo completar la acción"), describeApiError(error as ApiError));
    }
  }

  async function rotate(subscription: WebhookSubscriptionView) {
    const confirmed = await confirmDialog({
      title: t("¿Rotar el secreto de firma?"),
      text: t("Las entregas se firmarán con el nuevo desde ya. El receptor deja de validar hasta que lo cambie."),
      confirmLabel: t("Rotar"),
      dangerous: true,
    });
    if (!confirmed) return;

    try {
      setSecret(await rotateWebhookSecret(companyId, subscription.id));
      refresh();
    } catch (error) {
      notifyError(t("No se pudo completar la acción"), describeApiError(error as ApiError));
    }
  }

  async function retry(delivery: WebhookDeliveryView) {
    try {
      await retryWebhookDelivery(companyId, delivery.id);
      notifySuccess(t("Reintento encolado"));
      refresh();
    } catch (error) {
      notifyError(t("No se pudo completar la acción"), describeApiError(error as ApiError));
    }
  }

  const subColumns: DataTableColumn<WebhookSubscriptionView>[] = [
    {
      key: "name",
      header: t("Suscripción"),
      render: (sub) => (
        <Box sx={{ minWidth: 0 }}>
          <Typography variant="body2" sx={{ fontWeight: 700, lineHeight: 1.3 }}>{sub.name}</Typography>
          <Typography variant="caption" color="text.secondary" sx={{ wordBreak: "break-all" }}>
            {sub.targetUrl}
          </Typography>
        </Box>
      ),
    },
    {
      key: "events",
      header: t("Eventos"),
      render: (sub) => (
        <Box sx={{ display: "flex", flexWrap: "wrap", gap: 0.5, maxWidth: 320 }}>
          {sub.eventTypes.map((eventType) => (
            <Chip key={eventType} size="small" variant="outlined" label={eventType} sx={{ fontSize: 10.5, height: 20 }} />
          ))}
        </Box>
      ),
    },
    {
      key: "health",
      header: t("Salud"),
      // La racha, no el histórico: se pone a cero en cuanto algo se entrega.
      render: (sub) => (
        <Box>
          {sub.consecutiveFailures > 0 ? (
            <Typography variant="body2" sx={{ fontWeight: 800, color: "error.main" }}>
              {t("{{count}} fallos seguidos", { count: sub.consecutiveFailures })}
            </Typography>
          ) : (
            <Typography variant="body2" color="success.main" sx={{ fontWeight: 700 }}>{t("Sana")}</Typography>
          )}
          <Typography variant="caption" color="text.secondary">
            {sub.lastSuccessAt ? `${t("Último éxito")}: ${fmtDateTime(sub.lastSuccessAt)}` : t("Nunca entregó")}
          </Typography>
        </Box>
      ),
    },
    {
      key: "secret",
      header: t("Secreto"),
      // Los últimos cuatro caracteres: bastante para reconocerlo, no para firmar con él.
      render: (sub) => (
        <Typography variant="body2" sx={{ fontFamily: "monospace" }}>···{sub.secretHint}</Typography>
      ),
    },
    {
      key: "status",
      header: t("Estado"),
      render: (sub) => (
        <Box sx={{ display: "flex", gap: 0.5, flexWrap: "wrap" }}>
          <StatusChip label={sub.active ? t("Activa") : t("Inactiva")} tone={sub.active ? "done" : "cancelled"} />
          {/* Solo cuando fue eTMS quien la apagó tras fallar repetidamente. */}
          {sub.suspendedReason && <StatusChip label={t("Suspendida")} tone="overdue" />}
        </Box>
      ),
    },
  ];

  if (canManage) {
    subColumns.push({
      key: "actions",
      header: t("Acciones"),
      actions: true,
      render: (sub) => (
        <ActionMenu
          items={[
            { key: "edit", label: t("Editar"), icon: <EditRounded />, onSelect: () => setEditing(sub) },
            { key: "rotate", label: t("Rotar secreto"), icon: <AutorenewRounded />, onSelect: () => void rotate(sub) },
            {
              key: "active",
              label: sub.active ? t("Desactivar") : t("Activar"),
              icon: sub.active ? <BlockRounded /> : <CheckCircleRounded />,
              dangerous: sub.active,
              divider: true,
              onSelect: () => void toggleActive(sub),
            },
          ]}
        />
      ),
    });
  }

  const deliveryColumns: DataTableColumn<WebhookDeliveryView>[] = [
    { key: "event", header: t("Evento"), render: (row) => <Typography variant="body2" sx={{ fontWeight: 600 }}>{row.eventType}</Typography> },
    { key: "subscription", header: t("Suscripción"), render: (row) => row.subscriptionName },
    {
      key: "status",
      header: t("Estado"),
      render: (row) => (
        <Box sx={{ display: "flex", alignItems: "center", gap: 0.75 }}>
          <StatusChip label={row.status} tone={DELIVERY_TONE[row.status]} />
          {row.lastStatusCode !== null && (
            <Typography variant="caption" color="text.secondary">{row.lastStatusCode}</Typography>
          )}
        </Box>
      ),
    },
    { key: "attempts", header: t("Intentos"), numeric: true, render: (row) => fmtQuantity(row.attemptCount) },
    { key: "occurred", header: t("Ocurrió"), render: (row) => fmtDateTime(row.occurredAt) },
    {
      key: "next",
      header: t("Próximo intento"),
      // Solo significa algo mientras está pendiente: en las demás es ruido.
      render: (row) => row.status === "PENDING" ? fmtDateTime(row.nextAttemptAt) : "-",
    },
    { key: "error", header: t("Error"), render: (row) => row.lastError ?? "-" },
  ];

  if (canManage) {
    deliveryColumns.push({
      key: "actions",
      header: t("Acciones"),
      actions: true,
      render: (row) => (
        <ActionMenu
          items={[
            { key: "open", label: t("Ver detalle"), icon: <EditRounded />, onSelect: () => setOpenDeliveryId(row.id) },
            {
              key: "retry", label: t("Reintentar"), icon: <ReplayRounded />,
              disabled: row.status === "PROCESSED",
              onSelect: () => void retry(row),
            },
          ]}
        />
      ),
    });
  }

  return (
    <>
      <SectionHeader
        title={t("Suscripciones")}
        level={2}
        actions={canManage && (
          <Button size="small" variant="contained" startIcon={<AddRounded />} onClick={() => setCreating(true)}>
            {t("Nueva suscripción")}
          </Button>
        )}
      />
      <Box sx={{ mb: 4 }}>
        <DataTable
          columns={subColumns}
          rows={subsQuery.data?.content ?? []}
          total={subsQuery.data?.totalElements}
          rowKey={(sub) => sub.id}
          isLoading={subsQuery.isPending}
          error={subsQuery.isError ? describeApiError(subsQuery.error as ApiError) : null}
          onRetry={() => void subsQuery.refetch()}
          emptyTitle={t("Sin suscripciones")}
          emptyMessage={t("Crea una suscripción para que un sistema reciba los eventos de esta empresa.")}
          footer={subsQuery.data ? <Pagination page={subsQuery.data} onPageChange={setSubsPage} /> : undefined}
        />
      </Box>

      <SectionHeader
        title={t("Entregas")}
        level={2}
        actions={
          <Box sx={{ display: "flex", gap: 1 }}>
            <TextField
              select size="small" label={t("Suscripción")} value={subFilter}
              onChange={(e) => { setSubFilter(e.target.value); setDeliveriesPage(0); }}
              sx={{ minWidth: 200 }}
            >
              <MenuItem value="">{t("Todas")}</MenuItem>
              {(subsQuery.data?.content ?? []).map((sub) => (
                <MenuItem key={sub.id} value={sub.id}>{sub.name}</MenuItem>
              ))}
            </TextField>
            <TextField
              select size="small" label={t("Estado")} value={statusFilter}
              onChange={(e) => { setStatusFilter(e.target.value as WebhookDeliveryStatus | ""); setDeliveriesPage(0); }}
              sx={{ minWidth: 160 }}
            >
              <MenuItem value="">{t("Todos")}</MenuItem>
              <MenuItem value="PENDING">PENDING</MenuItem>
              <MenuItem value="PROCESSED">PROCESSED</MenuItem>
              <MenuItem value="FAILED">FAILED</MenuItem>
            </TextField>
          </Box>
        }
      />
      <DataTable
        columns={deliveryColumns}
        rows={deliveriesQuery.data?.content ?? []}
        total={deliveriesQuery.data?.totalElements}
        rowKey={(row) => row.id}
        isLoading={deliveriesQuery.isPending}
        error={deliveriesQuery.isError ? describeApiError(deliveriesQuery.error as ApiError) : null}
        onRetry={() => void deliveriesQuery.refetch()}
        emptyTitle={t("Sin entregas")}
        emptyMessage={t("Todavía no se ha empujado ningún evento.")}
        onRowClick={(row) => setOpenDeliveryId(row.id)}
        footer={deliveriesQuery.data ? <Pagination page={deliveriesQuery.data} onPageChange={setDeliveriesPage} /> : undefined}
      />

      {(creating || editing) && (
        <WebhookSubscriptionDrawer
          companyId={companyId}
          subscription={editing}
          onClose={() => { setCreating(false); setEditing(null); }}
          onCreated={(created) => {
            setCreating(false);
            setSecret(created);
            refresh();
          }}
          onUpdated={() => {
            setEditing(null);
            notifySuccess(t("Cambios guardados"));
            refresh();
          }}
        />
      )}

      {secret && (
        <SecretRevealDrawer
          title={t("Secreto de {{name}}", { name: secret.subscription.name })}
          notice={secret.notice}
          fields={[
            { label: t("Secreto de firma"), value: secret.secret, primary: true },
            { label: t("Cabecera de firma"), value: secret.signatureHeader },
            { label: t("Formato firmado"), value: secret.signedPayloadFormat },
          ]}
          onClose={() => setSecret(null)}
        />
      )}

      {openDeliveryId && (
        <WebhookDeliveryDrawer
          companyId={companyId}
          deliveryId={openDeliveryId}
          onClose={() => setOpenDeliveryId(null)}
        />
      )}
    </>
  );
}
