import { keepPreviousData, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Box, Button, Chip, MenuItem, TextField, Typography } from "@mui/material";
import {
  AddRounded, EditRounded, AutorenewRounded, BlockRounded,
} from "@mui/icons-material";
import type { ApiError } from "../../shared/api/httpClient";
import {
  fetchIntegrationClients, fetchIntegrationRequests, revokeIntegrationClient, rotateIntegrationClient,
  type IntegrationClientSecretView, type IntegrationClientView, type IntegrationRequestView,
} from "../../shared/api/integrationsApi";
import { describeApiError } from "../../shared/api/problemMessages";
import {
  ActionMenu, DataTable, Pagination, SectionHeader, StatusChip, type DataTableColumn,
} from "../../shared/ui/components";
import { confirmDialog, notifyError, notifySuccess } from "../../lib/ui";
import type { StatusTone } from "../../theme";
import { t } from "../../lib/i18n";
import { fmtDateTime, fmtQuantity } from "../../lib/locale";
import { IntegrationClientDrawer } from "./IntegrationClientDrawer";
import { SecretRevealDrawer } from "./SecretRevealDrawer";

const PAGE_SIZE = 20;

const REQUEST_TONE: Record<IntegrationRequestView["status"], StatusTone> = {
  SUCCEEDED: "done",
  PARTIAL: "inProgress",
  REJECTED: "overdue",
  FAILED: "overdue",
};

interface InboundPanelProps {
  companyId: string;
  canManage: boolean;
}

/**
 * La mitad de entrada del hub: las credenciales con las que se autentican los socios, y la
 * bandeja de lo que mandaron.
 *
 * Las dos van juntas porque la pregunta es una: "¿esto está conectado y funciona?". Una lista de
 * credenciales sin el registro de peticiones dice quién puede entrar, pero no si alguien entró.
 */
export function InboundPanel({ companyId, canManage }: InboundPanelProps) {
  const queryClient = useQueryClient();
  const [clientsPage, setClientsPage] = useState(0);
  const [requestsPage, setRequestsPage] = useState(0);
  const [clientFilter, setClientFilter] = useState("");
  const [editing, setEditing] = useState<IntegrationClientView | null>(null);
  const [creating, setCreating] = useState(false);
  const [secret, setSecret] = useState<IntegrationClientSecretView | null>(null);

  const clientsQuery = useQuery({
    queryKey: ["integration-clients", companyId, clientsPage],
    queryFn: ({ signal }) => fetchIntegrationClients(companyId, { page: clientsPage, size: PAGE_SIZE }, signal),
    placeholderData: keepPreviousData,
  });

  const requestsQuery = useQuery({
    queryKey: ["integration-requests", companyId, requestsPage, clientFilter],
    queryFn: ({ signal }) =>
      fetchIntegrationRequests(
        companyId,
        { page: requestsPage, size: PAGE_SIZE, clientId: clientFilter || undefined },
        signal,
      ),
    placeholderData: keepPreviousData,
  });

  function refresh() {
    void queryClient.invalidateQueries({ queryKey: ["integration-clients", companyId] });
    void queryClient.invalidateQueries({ queryKey: ["integration-requests", companyId] });
  }

  /**
   * Rotar pregunta primero si el secreto pudo filtrarse, porque de eso depende si el anterior
   * sigue valiendo un rato o muere ahora mismo: si se filtró, una ventana de gracia es una puerta
   * abierta.
   */
  async function rotate(client: IntegrationClientView) {
    const leaked = await confirmDialog({
      title: t("¿Rotar el secreto de {{name}}?", { name: client.name }),
      text: t("¿El secreto actual pudo filtrarse? Si es así se revoca al instante y el socio dejará de poder entrar hasta que configure el nuevo."),
      confirmLabel: t("Sí, revocar el anterior ya"),
      cancelLabel: t("No, dar margen"),
      dangerous: true,
    });

    try {
      // `graceHours: 0` mata el anterior al momento; omitirlo usa la ventana configurada.
      setSecret(await rotateIntegrationClient(companyId, client.id, leaked ? 0 : undefined));
      refresh();
    } catch (error) {
      notifyError(t("No se pudo completar la acción"), describeApiError(error as ApiError));
    }
  }

  async function revoke(client: IntegrationClientView) {
    const confirmed = await confirmDialog({
      title: t("¿Revocar {{name}}?", { name: client.name }),
      text: t("La credencial deja de funcionar al momento y no se puede restaurar."),
      confirmLabel: t("Revocar"),
      dangerous: true,
    });
    if (!confirmed) return;

    try {
      await revokeIntegrationClient(companyId, client.id);
      notifySuccess(t("Credencial revocada"), client.name);
      refresh();
    } catch (error) {
      notifyError(t("No se pudo completar la acción"), describeApiError(error as ApiError));
    }
  }

  const clientColumns: DataTableColumn<IntegrationClientView>[] = [
    {
      key: "name",
      header: t("Credencial"),
      render: (client) => (
        <Box sx={{ minWidth: 0 }}>
          <Typography variant="body2" sx={{ fontWeight: 700, lineHeight: 1.3 }}>{client.name}</Typography>
          {/* El clientId es la mitad pública: se puede enseñar y copiar sin riesgo. */}
          <Typography variant="caption" color="text.secondary" sx={{ fontFamily: "monospace" }}>
            {client.clientId}
          </Typography>
        </Box>
      ),
    },
    {
      key: "scopes",
      header: t("Alcances"),
      render: (client) => (
        <Box sx={{ display: "flex", flexWrap: "wrap", gap: 0.5 }}>
          {client.scopes.map((scope) => (
            <Chip key={scope} size="small" variant="outlined" label={scope.replace("integration.", "")} />
          ))}
        </Box>
      ),
    },
    {
      key: "lastUsed",
      header: t("Último uso"),
      // La pregunta real de esta columna es "¿esto sigue en uso?": un "nunca" en una credencial
      // de hace seis meses es una credencial que sobra.
      render: (client) => client.lastUsedAt ? fmtDateTime(client.lastUsedAt) : t("Nunca"),
    },
    {
      key: "status",
      header: t("Estado"),
      render: (client) => (
        <Box sx={{ display: "flex", gap: 0.5, flexWrap: "wrap" }}>
          <StatusChip
            label={client.active ? t("Activa") : t("Revocada")}
            tone={client.active ? "done" : "cancelled"}
          />
          {client.rotationGraceEndsAt && (
            <StatusChip label={t("Rotando")} tone="inProgress" />
          )}
        </Box>
      ),
    },
  ];

  if (canManage) {
    clientColumns.push({
      key: "actions",
      header: t("Acciones"),
      actions: true,
      render: (client) => (
        <ActionMenu
          items={[
            { key: "edit", label: t("Editar"), icon: <EditRounded />, disabled: !client.active, onSelect: () => setEditing(client) },
            { key: "rotate", label: t("Rotar secreto"), icon: <AutorenewRounded />, disabled: !client.active, onSelect: () => void rotate(client) },
            {
              key: "revoke", label: t("Revocar"), icon: <BlockRounded />,
              dangerous: true, divider: true, disabled: !client.active,
              onSelect: () => void revoke(client),
            },
          ]}
        />
      ),
    });
  }

  const requestColumns: DataTableColumn<IntegrationRequestView>[] = [
    { key: "operation", header: t("Operación"), render: (row) => <Typography variant="body2" sx={{ fontWeight: 600 }}>{row.operation}</Typography> },
    {
      key: "status",
      header: t("Resultado"),
      render: (row) => (
        <Box sx={{ display: "flex", alignItems: "center", gap: 0.75 }}>
          <StatusChip label={row.status} tone={REQUEST_TONE[row.status]} />
          <Typography variant="caption" color="text.secondary">{row.httpStatus}</Typography>
        </Box>
      ),
    },
    {
      key: "items",
      header: t("Elementos"),
      numeric: true,
      // Éxitos y fallos juntos: un 200 con la mitad de las filas rechazadas no es un éxito.
      render: (row) => (
        <Typography variant="body2" sx={{ fontVariantNumeric: "tabular-nums" }}>
          {fmtQuantity(row.succeededCount)} / {fmtQuantity(row.itemCount)}
        </Typography>
      ),
    },
    { key: "reference", header: t("Referencia externa"), render: (row) => row.externalReference ?? "-" },
    { key: "received", header: t("Recibida"), render: (row) => fmtDateTime(row.receivedAt) },
    {
      key: "duration",
      header: t("Duración"),
      numeric: true,
      render: (row) => `${fmtQuantity(row.durationMs)} ms`,
    },
    { key: "error", header: t("Error"), render: (row) => row.errorSummary ?? "-" },
  ];

  return (
    <>
      <SectionHeader
        title={t("Credenciales")}
        level={2}
        actions={canManage && (
          <Button size="small" variant="contained" startIcon={<AddRounded />} onClick={() => setCreating(true)}>
            {t("Nueva credencial")}
          </Button>
        )}
      />
      <Box sx={{ mb: 4 }}>
        <DataTable
          columns={clientColumns}
          rows={clientsQuery.data?.content ?? []}
          total={clientsQuery.data?.totalElements}
          rowKey={(client) => client.id}
          isLoading={clientsQuery.isPending}
          error={clientsQuery.isError ? describeApiError(clientsQuery.error as ApiError) : null}
          onRetry={() => void clientsQuery.refetch()}
          emptyTitle={t("Sin credenciales")}
          emptyMessage={t("Emite una credencial para que un socio pueda conectarse.")}
          footer={clientsQuery.data ? <Pagination page={clientsQuery.data} onPageChange={setClientsPage} /> : undefined}
        />
      </Box>

      <SectionHeader
        title={t("Bandeja de entrada")}
        level={2}
        actions={
          <TextField
            select size="small" label={t("Credencial")} value={clientFilter}
            onChange={(e) => { setClientFilter(e.target.value); setRequestsPage(0); }}
            sx={{ minWidth: 220 }}
          >
            <MenuItem value="">{t("Todas")}</MenuItem>
            {(clientsQuery.data?.content ?? []).map((client) => (
              <MenuItem key={client.id} value={client.id}>{client.name}</MenuItem>
            ))}
          </TextField>
        }
      />
      <DataTable
        columns={requestColumns}
        rows={requestsQuery.data?.content ?? []}
        total={requestsQuery.data?.totalElements}
        rowKey={(row) => row.id}
        isLoading={requestsQuery.isPending}
        error={requestsQuery.isError ? describeApiError(requestsQuery.error as ApiError) : null}
        onRetry={() => void requestsQuery.refetch()}
        emptyTitle={t("Sin peticiones")}
        emptyMessage={t("Todavía no ha entrado ninguna petición de integración.")}
        footer={requestsQuery.data ? <Pagination page={requestsQuery.data} onPageChange={setRequestsPage} /> : undefined}
      />

      {(creating || editing) && (
        <IntegrationClientDrawer
          companyId={companyId}
          client={editing}
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
          title={t("Credencial de {{name}}", { name: secret.client.name })}
          notice={secret.notice}
          previousValidUntil={secret.previousSecretValidUntil}
          fields={[
            { label: t("Client ID"), value: secret.clientId },
            { label: t("Secreto"), value: secret.secret },
            { label: t("Bearer token"), value: secret.bearerToken, primary: true },
          ]}
          onClose={() => setSecret(null)}
        />
      )}
    </>
  );
}
