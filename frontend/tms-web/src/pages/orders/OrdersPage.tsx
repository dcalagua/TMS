import { keepPreviousData, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Box, Button, MenuItem, TextField, Tooltip, Typography } from "@mui/material";
import {
  AddRounded, UploadRounded, AssignmentTurnedInRounded, EditRounded, VisibilityRounded,
  CheckCircleRounded, CancelRounded, ReplayRounded, ScaleRounded, ViewInArRounded, LayersRounded,
} from "@mui/icons-material";
import { fetchDestinations } from "../../shared/api/destinationsApi";
import type { ApiError } from "../../shared/api/httpClient";
import {
  ORDER_PRIORITIES, ORDER_STATUSES, REOPENABLE_ORDER_STATUSES, cancelOrder, fetchOrders,
  markOrderReadyForPlanning, reopenOrderForPlanning,
  type OrderFulfillmentStatus, type OrderPriority, type OrderStatus, type OrderView,
} from "../../shared/api/ordersApi";
import { fetchOrigins } from "../../shared/api/originsApi";
import { describeApiError } from "../../shared/api/problemMessages";
import { useCompany } from "../../shared/company/CompanyContext";
import {
  ActionMenu, DataTable, KpiCard, PageHeader, Pagination, StatusChip, Toolbar,
  type DataTableColumn,
} from "../../shared/ui/components";
import { ICON_TINTS } from "../../shared/ui/navConfig";
import { confirmDialog, notifyError, notifySuccess, promptDialog } from "../../lib/ui";
import { enumLabel } from "../../lib/enums";
import type { StatusTone } from "../../theme";
import { t } from "../../lib/i18n";
import { fmtDate, fmtDecimal, fmtQuantity, fmtVolumeM3, fmtWeightKg } from "../../lib/locale";
import { OrderFormDrawer } from "./OrderFormDrawer";
import { OrderImportDrawer } from "./OrderImportDrawer";

const PAGE_SIZE = 25;

/**
 * Los colores del ciclo de vida del pedido (migración V36).
 *
 * `PLANNED` es `done` porque para el planificador el trabajo terminó: el pedido está en un
 * camión. `IN_EXECUTION` es `inProgress` — está pasando ahora mismo. Las dos formas de volver
 * corto, `PARTIALLY_DELIVERED` y `DELIVERY_FAILED`, son `overdue` y no `cancelled`: son trabajo
 * que alguien todavía le debe a un cliente, que es exactamente lo que hay que ver en la lista.
 */
const STATUS_TONE: Record<OrderStatus, StatusTone> = {
  NOT_READY: "neutral",
  READY_FOR_PLANNING: "open",
  PLANNED: "done",
  IN_EXECUTION: "inProgress",
  DELIVERED: "done",
  PARTIALLY_DELIVERED: "overdue",
  DELIVERY_FAILED: "overdue",
  CANCELLED: "cancelled",
};

/**
 * Los colores del resultado de entrega, aparte de `STATUS_TONE` porque las dos columnas
 * responden preguntas distintas: una dice si el pedido puede subir a un camión, la otra dice qué
 * pasó cuando subió. `PENDING` es neutro y no ámbar — un pedido que nadie ha entregado todavía
 * es el estado normal de casi toda la lista, no un problema.
 */
const FULFILLMENT_TONE: Record<OrderFulfillmentStatus, StatusTone> = {
  PENDING: "neutral",
  DELIVERED: "done",
  PARTIALLY_DELIVERED: "inProgress",
  REJECTED: "overdue",
  FAILED: "overdue",
  NOT_ATTEMPTED: "neutral",
};

const PRIORITY_TONE: Record<OrderPriority, StatusTone> = {
  LOW: "neutral",
  NORMAL: "neutral",
  HIGH: "inProgress",
  URGENT: "overdue",
};

interface AppliedFilters {
  orderNumber: string;
  originId: string;
  destinationId: string;
  serviceDateFrom: string;
  serviceDateTo: string;
  status: OrderStatus | "";
  priority: OrderPriority | "";
}

const DEFAULT_FILTERS: AppliedFilters = {
  orderNumber: "", originId: "", destinationId: "", serviceDateFrom: "", serviceDateTo: "", status: "", priority: "",
};

type ModalState = { mode: "create" } | { mode: "edit"; orderId: string } | { mode: "import" } | null;

/** Totales de las filas que están en pantalla. Deliberadamente no se presentan como una cifra de
 * toda la empresa: el backend pagina, así que lo que hay más allá de esta página simplemente no
 * se conoce aquí. */
function pageTotals(rows: OrderView[]) {
  return rows.reduce(
    (running, order) => ({
      weight: running.weight + order.totalWeightKg,
      volume: running.volume + order.totalVolumeM3,
      pallets: running.pallets + order.totalPallets,
    }),
    { weight: 0, volume: 0, pallets: 0 },
  );
}

export function OrdersPage() {
  const { selected, hasPermission } = useCompany();
  const companyId = selected?.id ?? "";
  const canManage = hasPermission("orders.order:manage");
  const queryClient = useQueryClient();

  const [page, setPage] = useState(0);
  const [draft, setDraft] = useState<AppliedFilters>(DEFAULT_FILTERS);
  const [filters, setFilters] = useState<AppliedFilters>(DEFAULT_FILTERS);
  const [modal, setModal] = useState<ModalState>(null);

  const ordersQuery = useQuery({
    queryKey: ["orders", companyId, page, filters],
    queryFn: ({ signal }) =>
      fetchOrders({
        companyId,
        page,
        size: PAGE_SIZE,
        sort: "serviceDate,desc",
        orderNumber: filters.orderNumber || undefined,
        originId: filters.originId || undefined,
        destinationId: filters.destinationId || undefined,
        serviceDateFrom: filters.serviceDateFrom || undefined,
        serviceDateTo: filters.serviceDateTo || undefined,
        status: filters.status || undefined,
        priority: filters.priority || undefined,
        signal,
      }),
    placeholderData: keepPreviousData,
  });

  const originsQuery = useQuery({
    queryKey: ["origins-for-order-filter", companyId],
    queryFn: ({ signal }) => fetchOrigins({ companyId, size: 200, active: true, sort: "code,asc", signal }),
    enabled: companyId !== "",
  });
  const destinationsQuery = useQuery({
    queryKey: ["destinations-for-order-filter", companyId],
    queryFn: ({ signal }) => fetchDestinations({ companyId, size: 200, active: true, sort: "code,asc", signal }),
    enabled: companyId !== "",
  });

  function refresh() {
    void queryClient.invalidateQueries({ queryKey: ["orders", companyId] });
    // Un pedido liberado cambia lo que la planificación puede recoger.
    void queryClient.invalidateQueries({ queryKey: ["eligible-orders", companyId] });
  }

  function applyFilters() { setFilters(draft); setPage(0); }
  function resetFilters() { setDraft(DEFAULT_FILTERS); setFilters(DEFAULT_FILTERS); setPage(0); }

  async function markReady(order: OrderView) {
    const confirmed = await confirmDialog({
      title: t("¿Marcar el pedido como listo para planificar?"),
      text: t("{{number}} será visible para planificación cuando tenga al menos una línea y un peso, volumen o cantidad de pallets conocidos.", { number: order.orderNumber }),
      confirmLabel: t("Marcar listo"),
    });
    if (!confirmed) return;

    try {
      await markOrderReadyForPlanning(companyId, order.id);
      notifySuccess(t("Pedido marcado como listo"), order.orderNumber);
      refresh();
    } catch (error) {
      notifyError(t("No se pudo marcar el pedido como listo"), describeApiError(error as ApiError));
    }
  }

  async function cancel(order: OrderView) {
    const confirmed = await confirmDialog({
      title: t("¿Cancelar el pedido?"),
      text: t("{{number}} quedará cancelado y ya no podrá editarse ni planificarse.", { number: order.orderNumber }),
      confirmLabel: t("Cancelar pedido"),
      dangerous: true,
    });
    if (!confirmed) return;

    try {
      await cancelOrder(companyId, order.id);
      notifySuccess(t("Pedido cancelado"), order.orderNumber);
      refresh();
    } catch (error) {
      notifyError(t("No se pudo cancelar el pedido"), describeApiError(error as ApiError));
    }
  }

  /**
   * Devuelve a la bolsa planificable un pedido que volvió corto (migración V36).
   *
   * Pide el motivo en lugar de solo confirmar: una reentrega cuesta un camión, y "por qué fuimos
   * dos veces" es la pregunta que hace el cliente. El motivo viaja al registro de auditoría.
   */
  async function reopen(order: OrderView) {
    const reason = await promptDialog({
      title: t("¿Reabrir el pedido?"),
      text: t("El pedido vuelve a la bolsa planificable para un segundo intento de entrega. Conserva el registro del primer intento."),
      inputLabel: t("Motivo de la reapertura"),
      maxLength: 500,
      confirmLabel: t("Reabrir pedido"),
    });
    if (reason === null) return;

    try {
      await reopenOrderForPlanning(companyId, order.id, reason);
      notifySuccess(t("Pedido reabierto para planificar"), order.orderNumber);
      refresh();
    } catch (error) {
      notifyError(t("No se pudo reabrir el pedido"), describeApiError(error as ApiError));
    }
  }

  const columns: DataTableColumn<OrderView>[] = [
    {
      key: "orderNumber",
      header: t("Pedido"),
      render: (order) => <Typography variant="body2" sx={{ fontWeight: 800 }}>{order.orderNumber}</Typography>,
    },
    {
      key: "origin",
      header: t("Origen"),
      render: (order) => (
        <Tooltip title={order.originName ?? ""}>
          <Typography variant="body2" noWrap sx={{ maxWidth: "14rem" }}>
            {order.originName ?? order.originCode ?? "-"}
          </Typography>
        </Tooltip>
      ),
    },
    {
      key: "destination",
      header: t("Destino"),
      render: (order) => (
        <Tooltip title={order.destinationName ?? ""}>
          <Typography variant="body2" noWrap sx={{ maxWidth: "14rem" }}>
            {order.destinationName ?? order.destinationCode ?? "-"}
          </Typography>
        </Tooltip>
      ),
    },
    { key: "serviceDate", header: t("Fecha requerida"), render: (order) => fmtDate(order.serviceDate) },
    {
      key: "priority",
      header: t("Prioridad"),
      render: (order) => (
        <StatusChip label={enumLabel("orderPriority", order.priority)} tone={PRIORITY_TONE[order.priority]} />
      ),
    },
    { key: "weight", header: t("Peso"), numeric: true, render: (order) => fmtWeightKg(order.totalWeightKg) },
    { key: "volume", header: t("Volumen"), numeric: true, render: (order) => fmtVolumeM3(order.totalVolumeM3) },
    { key: "pallets", header: t("Pallets"), numeric: true, render: (order) => fmtDecimal(order.totalPallets) },
    { key: "lines", header: t("Líneas"), numeric: true, render: (order) => fmtQuantity(order.lineCount) },
    {
      key: "status",
      header: t("Estado"),
      render: (order) => <StatusChip label={enumLabel("orderStatus", order.status)} tone={STATUS_TONE[order.status]} />,
    },
    {
      // Columna propia, al lado del estado de planificación y no en su lugar. Un pedido que
      // rechazaron en el muelle sigue siendo un pedido planificado, y una lista que solo
      // enseñara "Planificado" le estaría diciendo al despachador que el trabajo está hecho.
      key: "fulfillment",
      header: t("Entrega"),
      render: (order) => (
        <StatusChip
          label={enumLabel("orderFulfillmentStatus", order.fulfillmentStatus)}
          tone={FULFILLMENT_TONE[order.fulfillmentStatus]}
        />
      ),
    },
  ];

  if (canManage) {
    columns.push({
      key: "actions",
      header: t("Acciones"),
      actions: true,
      render: (order) => {
        const editable = order.status === "NOT_READY" || order.status === "READY_FOR_PLANNING";
        // Espejo de OrderStatus: un pedido en ruta no se cancela (la mercancía se está moviendo)
        // y uno entregado tampoco (ya pasó). La regla la impone el backend con un 409; esto solo
        // decide si vale la pena dibujar el botón.
        const cancellable = order.status !== "CANCELLED" && order.status !== "PLANNED"
          && order.status !== "IN_EXECUTION" && order.status !== "DELIVERED";
        const reopenable = REOPENABLE_ORDER_STATUSES.includes(order.status);
        return (
          <ActionMenu
            items={[
              {
                key: "open",
                label: editable ? t("Editar") : t("Ver"),
                icon: editable ? <EditRounded /> : <VisibilityRounded />,
                onSelect: () => setModal({ mode: "edit", orderId: order.id }),
              },
              ...(order.status === "NOT_READY"
                ? [{
                    key: "ready",
                    label: t("Marcar listo"),
                    icon: <CheckCircleRounded />,
                    onSelect: () => void markReady(order),
                  }]
                : []),
              ...(reopenable
                ? [{
                    key: "reopen",
                    label: t("Reabrir para planificar"),
                    icon: <ReplayRounded />,
                    onSelect: () => void reopen(order),
                  }]
                : []),
              ...(cancellable
                ? [{
                    key: "cancel",
                    label: t("Cancelar pedido"),
                    icon: <CancelRounded />,
                    dangerous: true,
                    divider: true,
                    onSelect: () => void cancel(order),
                  }]
                : []),
            ]}
          />
        );
      },
    });
  }

  const pageData = ordersQuery.data;
  const rows = pageData?.content ?? [];
  const totals = pageTotals(rows);

  return (
    <>
      <PageHeader
        icon={<AssignmentTurnedInRounded />}
        tint={ICON_TINTS["/orders"]}
        title={t("Pedidos")}
        subtitle={t("Pedidos de transporte: cabecera más líneas. Los totales siempre los calcula y controla el backend.")}
        onRefresh={refresh}
        refreshing={ordersQuery.isFetching}
        actions={canManage && (
          <>
            <Button variant="outlined" startIcon={<UploadRounded />} onClick={() => setModal({ mode: "import" })}>
              {t("Importar")}
            </Button>
            <Button variant="contained" startIcon={<AddRounded />} onClick={() => setModal({ mode: "create" })}>
              {t("Nuevo pedido")}
            </Button>
          </>
        )}
      />

      {/* Los totales de la página, no de la empresa: el backend pagina y esta suma solo puede
          hablar de lo que hay en pantalla. Se dice literalmente, debajo. */}
      <Box sx={{
        display: "grid", gap: 2, mb: 2,
        gridTemplateColumns: { xs: "1fr", sm: "repeat(3, minmax(0, 1fr))" },
      }}>
        <KpiCard icon={<ScaleRounded />} color="info.main" title={t("Peso en esta página")} value={fmtWeightKg(totals.weight)} loading={ordersQuery.isPending} />
        <KpiCard icon={<ViewInArRounded />} color="secondary.main" title={t("Volumen en esta página")} value={fmtVolumeM3(totals.volume)} loading={ordersQuery.isPending} />
        <KpiCard icon={<LayersRounded />} color="warning.main" title={t("Pallets en esta página")} value={fmtDecimal(totals.pallets)} loading={ordersQuery.isPending} />
      </Box>
      <Typography variant="caption" color="text.secondary" sx={{ display: "block", mb: 2 }}>
        {t("Los totales corresponden solo a los pedidos de esta página.")}
      </Typography>

      <Toolbar
        onApply={applyFilters}
        onReset={resetFilters}
        filters={
          <>
            <TextField
              size="small" label={t("Pedido")} value={draft.orderNumber}
              onChange={(e) => setDraft({ ...draft, orderNumber: e.target.value })}
              sx={{ minWidth: 150 }}
            />
            <TextField
              select size="small" label={t("Origen")} value={draft.originId}
              onChange={(e) => setDraft({ ...draft, originId: e.target.value })}
              sx={{ minWidth: 190 }}
            >
              <MenuItem value="">{t("Todos los orígenes")}</MenuItem>
              {(originsQuery.data?.content ?? []).map((origin) => (
                <MenuItem key={origin.id} value={origin.id}>{origin.name}</MenuItem>
              ))}
            </TextField>
            <TextField
              select size="small" label={t("Destino")} value={draft.destinationId}
              onChange={(e) => setDraft({ ...draft, destinationId: e.target.value })}
              sx={{ minWidth: 190 }}
            >
              <MenuItem value="">{t("Todos los destinos")}</MenuItem>
              {(destinationsQuery.data?.content ?? []).map((destination) => (
                <MenuItem key={destination.id} value={destination.id}>{destination.name}</MenuItem>
              ))}
            </TextField>
            <TextField
              size="small" type="date" label={t("Desde")} value={draft.serviceDateFrom}
              onChange={(e) => setDraft({ ...draft, serviceDateFrom: e.target.value })}
              slotProps={{ inputLabel: { shrink: true } }}
              sx={{ minWidth: 160 }}
            />
            <TextField
              size="small" type="date" label={t("Hasta")} value={draft.serviceDateTo}
              onChange={(e) => setDraft({ ...draft, serviceDateTo: e.target.value })}
              slotProps={{ inputLabel: { shrink: true } }}
              sx={{ minWidth: 160 }}
            />
            <TextField
              select size="small" label={t("Estado")} value={draft.status}
              onChange={(e) => setDraft({ ...draft, status: e.target.value as OrderStatus | "" })}
              sx={{ minWidth: 180 }}
            >
              <MenuItem value="">{t("Todos los estados")}</MenuItem>
              {ORDER_STATUSES.map((status) => (
                <MenuItem key={status} value={status}>{enumLabel("orderStatus", status)}</MenuItem>
              ))}
            </TextField>
            <TextField
              select size="small" label={t("Prioridad")} value={draft.priority}
              onChange={(e) => setDraft({ ...draft, priority: e.target.value as OrderPriority | "" })}
              sx={{ minWidth: 180 }}
            >
              <MenuItem value="">{t("Todas las prioridades")}</MenuItem>
              {ORDER_PRIORITIES.map((priority) => (
                <MenuItem key={priority} value={priority}>{enumLabel("orderPriority", priority)}</MenuItem>
              ))}
            </TextField>
          </>
        }
      />

      <DataTable
        columns={columns}
        rows={rows}
        total={pageData?.totalElements}
        rowKey={(order) => order.id}
        isLoading={ordersQuery.isPending}
        error={ordersQuery.isError ? describeApiError(ordersQuery.error as ApiError) : null}
        onRetry={() => void ordersQuery.refetch()}
        emptyTitle={t("Sin pedidos")}
        emptyMessage={t("Crea un pedido o ajusta los filtros.")}
        onRowClick={(order) => setModal({ mode: "edit", orderId: order.id })}
        footer={pageData ? <Pagination page={pageData} onPageChange={setPage} /> : undefined}
      />

      {(modal?.mode === "create" || modal?.mode === "edit") && (
        <OrderFormDrawer
          companyId={companyId}
          orderId={modal.mode === "edit" ? modal.orderId : null}
          canManage={canManage}
          onClose={() => setModal(null)}
          onSaved={() => {
            const wasEdit = modal.mode === "edit";
            setModal(null);
            notifySuccess(wasEdit ? t("Registro actualizado") : t("Registro creado"));
            refresh();
          }}
        />
      )}

      {modal?.mode === "import" && (
        <OrderImportDrawer
          companyId={companyId}
          onClose={() => setModal(null)}
          onImported={refresh}
        />
      )}
    </>
  );
}
