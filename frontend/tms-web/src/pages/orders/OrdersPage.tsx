import { keepPreviousData, useQueries, useQuery, useQueryClient } from "@tanstack/react-query";
import { useCallback, useState, type ReactNode } from "react";
import { useSearchParams } from "react-router-dom";
import { Box, Button, Tooltip, Typography } from "@mui/material";
import {
  AddRounded, UploadRounded, AssignmentTurnedInRounded, EditRounded, VisibilityRounded,
  CheckCircleRounded, CancelRounded, ReplayRounded, ArrowForwardRounded,
} from "@mui/icons-material";
import { fetchDestinations } from "../../shared/api/destinationsApi";
import type { ApiError } from "../../shared/api/httpClient";
import {
  ORDER_STATUSES, REOPENABLE_ORDER_STATUSES, cancelOrder, fetchOrders,
  reopenOrderForPlanning,
  type OrderFulfillmentStatus, type OrderPriority, type OrderStatus, type OrderView,
} from "../../shared/api/ordersApi";
import { fetchOrigins } from "../../shared/api/originsApi";
import { describeApiError } from "../../shared/api/problemMessages";
import { useCompany } from "../../shared/company/CompanyContext";
import {
  ActionMenu, DataTable, PageHeader, Pagination, StatusChip,
  type DataTableColumn,
} from "../../shared/ui/components";
import { ICON_TINTS } from "../../shared/ui/navConfig";
import { confirmDialog, notifyError, notifySuccess, promptDialog } from "../../lib/ui";
import { enumLabel } from "../../lib/enums";
import { T, type StatusTone } from "../../theme";
import { t } from "../../lib/i18n";
import { fmtDate, fmtDecimal, fmtQuantity, fmtVolumeM3, fmtWeightKg } from "../../lib/locale";
import { OrderFormDrawer } from "./OrderFormDrawer";
import { isPartlyPlanned } from "../../shared/api/ordersApi";
import { OrderImportDrawer } from "./OrderImportDrawer";
import { releaseWithOverride } from "../scheduling/releaseFlow";
import { OrderFilterBar, OrderStatusTabs } from "./OrderFilters";
import { DEFAULT_ORDER_FILTERS, type OrderFilters } from "./orderFilterModel";

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

/** Pila monoespaciada para los identificadores de pedido: se leen y se dictan carácter a carácter. */
const MONO = "ui-monospace, SFMono-Regular, Menlo, Consolas, monospace";

/** Los parámetros de consulta de unos filtros, sin el estado: lo pone quien llama. */
function filterQuery(filters: OrderFilters) {
  return {
    orderNumber: filters.orderNumber || undefined,
    originId: filters.originId || undefined,
    destinationId: filters.destinationId || undefined,
    serviceDateFrom: filters.serviceDateFrom || undefined,
    serviceDateTo: filters.serviceDateTo || undefined,
    priority: filters.priority || undefined,
  };
}

/** Texto principal y secundario de una celda de dos renglones. */
function TwoLine({ primary, secondary, mono, strike }: {
  primary: ReactNode; secondary?: ReactNode; mono?: boolean; strike?: boolean;
}) {
  return (
    <Box sx={{ minWidth: 0 }}>
      <Typography variant="body2" noWrap sx={{
        fontWeight: 600, fontFamily: mono ? MONO : undefined, letterSpacing: mono ? "-0.01em" : undefined,
        textDecoration: strike ? "line-through" : undefined, fontVariantNumeric: "tabular-nums",
      }}>
        {primary}
      </Typography>
      {secondary && (
        <Typography noWrap sx={{ fontSize: T.micro + 0.5, color: "text.secondary", mt: "1px" }}>
          {secondary}
        </Typography>
      )}
    </Box>
  );
}

const PRIORITY_COLOR: Partial<Record<OrderPriority, string>> = { HIGH: "warning.main", URGENT: "error.main" };
const FULFILLMENT_COLOR: Partial<Record<OrderFulfillmentStatus, string>> = {
  DELIVERED: "success.main", PARTIALLY_DELIVERED: "warning.main", REJECTED: "error.main", FAILED: "error.main",
};

export function OrdersPage() {
  const { selected, hasPermission } = useCompany();
  const companyId = selected?.id ?? "";
  const canManage = hasPermission("orders.order:manage");
  const queryClient = useQueryClient();

  const [searchParams] = useSearchParams();
  const [page, setPage] = useState(0);
  // `?orderNumber=` abre la lista ya filtrada: es el "Ir al pedido" de Programación y Liberación.
  // Los filtros se aplican al momento: no hay borrador ni botón de aplicar.
  const [filters, setFiltersState] = useState<OrderFilters>(
    () => ({ ...DEFAULT_ORDER_FILTERS, orderNumber: searchParams.get("orderNumber") ?? "" }));
  const [modal, setModal] = useState<ModalState>(null);
  const setFilters = useCallback((next: OrderFilters) => { setFiltersState(next); setPage(0); }, []);

  const ordersQuery = useQuery({
    queryKey: ["orders", companyId, page, filters],
    queryFn: ({ signal }) =>
      fetchOrders({
        companyId,
        page,
        size: PAGE_SIZE,
        sort: "serviceDate,desc",
        ...filterQuery(filters),
        status: filters.status || undefined,
        signal,
      }),
    placeholderData: keepPreviousData,
  });

  // Cuántos pedidos hay en cada estado con los demás filtros: una consulta de una fila por
  // estado, de la que solo se lee `totalElements`. Es lo mismo que hace Inicio con sus contadores.
  const countFilters = { ...filters, status: "" as const };
  const countQueries = useQueries({
    queries: ORDER_STATUSES.map((status) => ({
      queryKey: ["orders", companyId, "count", status, countFilters],
      queryFn: ({ signal }: { signal: AbortSignal }) =>
        fetchOrders({ companyId, page: 0, size: 1, ...filterQuery(countFilters), status, signal }),
      enabled: companyId !== "",
      placeholderData: keepPreviousData,
    })),
  });
  const counts = countQueries.every((q) => q.data)
    ? Object.fromEntries(ORDER_STATUSES.map((status, i) => [status, countQueries[i].data!.totalElements])) as Record<OrderStatus, number>
    : undefined;

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


  async function markReady(order: OrderView) {
    const confirmed = await confirmDialog({
      title: t("¿Marcar el pedido como listo para planificar?"),
      text: t("{{number}} será visible para planificación cuando tenga al menos una línea y un peso, volumen o cantidad de pallets conocidos.", { number: order.orderNumber }),
      confirmLabel: t("Marcar listo"),
    });
    if (!confirmed) return;

    // ADR-014: la liberación se juzga contra la elegibilidad. Si hace falta un motivo (corte
    // vencido, fuera de frecuencia) se pide y se reintenta; un bloqueo se informa con sus motivos.
    if (await releaseWithOverride(companyId, order.id, order.orderNumber)) {
      refresh();
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
      width: 190,
      render: (order) => (
        <TwoLine mono primary={order.orderNumber} secondary={order.customerName ?? t("Sin cliente")} />
      ),
    },
    {
      // Origen y destino en una sola columna: se leen como un trayecto, que es lo que son.
      key: "route",
      header: t("Ruta"),
      width: 250,
      render: (order) => (
        <Tooltip title={`${order.originName ?? order.originCode ?? "-"} → ${order.destinationName ?? order.destinationCode ?? "-"}`}>
          <Box sx={{ minWidth: 0, maxWidth: 260 }}>
            <Box sx={{ display: "flex", alignItems: "center", gap: 0.75, minWidth: 0 }}>
              <Typography noWrap sx={{ fontFamily: MONO, fontSize: T.micro + 0.5, fontWeight: 600, color: "text.secondary", flexShrink: 0 }}>
                {order.originCode ?? order.originName ?? "-"}
              </Typography>
              <ArrowForwardRounded aria-hidden sx={{ fontSize: 13, color: "text.secondary", flexShrink: 0 }} />
              <Typography variant="body2" noWrap sx={{ fontWeight: 600 }}>
                {order.destinationName ?? order.destinationCode ?? "-"}
              </Typography>
            </Box>
            {order.destinationCode && (
              <Typography noWrap sx={{ fontFamily: MONO, fontSize: T.micro, color: "text.secondary", mt: "1px" }}>
                {t("Destino")} {order.destinationCode}
              </Typography>
            )}
          </Box>
        </Tooltip>
      ),
    },
    {
      key: "serviceDate",
      header: t("Servicio"),
      width: 140,
      render: (order) => (
        <TwoLine
          primary={fmtDate(order.serviceDate)}
          strike={order.status === "CANCELLED"}
          secondary={order.requestedWindowStart && order.requestedWindowEnd
            ? `${order.requestedWindowStart.slice(0, 5)} – ${order.requestedWindowEnd.slice(0, 5)}`
            : t("Sin ventana")}
        />
      ),
    },
    {
      // Solo Alta y Urgente llevan color: si todo destaca, nada destaca.
      key: "priority",
      header: t("Prioridad"),
      width: 110,
      render: (order) => {
        const color = PRIORITY_COLOR[order.priority];
        return (
          <Box sx={{ display: "inline-flex", alignItems: "center", gap: 0.75 }}>
            <Box aria-hidden sx={{ width: 7, height: 7, borderRadius: "50%", bgcolor: color ?? "divider", flexShrink: 0 }} />
            <Typography variant="body2" sx={{ fontWeight: color ? 700 : 500, color: color ?? "text.secondary" }}>
              {enumLabel("orderPriority", order.priority)}
            </Typography>
          </Box>
        );
      },
    },
    {
      key: "load",
      header: t("Carga"),
      width: 190,
      render: (order) => (
        <TwoLine
          primary={fmtWeightKg(order.totalWeightKg)}
          secondary={`${fmtVolumeM3(order.totalVolumeM3)} · ${fmtDecimal(order.totalPallets)} ${t("pallets")} · ${fmtQuantity(order.lineCount)} ${order.lineCount === 1 ? t("línea") : t("líneas")}`}
        />
      ),
    },
    {
      // El estado de planificación y, debajo, el resultado de la entrega. Un pedido rechazado en
      // el muelle sigue siendo un pedido planificado: enseñar solo "Planificado" le diría al
      // despachador que el trabajo está hecho, por eso la entrega tiene color cuando algo falló.
      key: "status",
      header: t("Estado"),
      render: (order) => (
        <Box sx={{ minWidth: 0 }}>
          <Box sx={{ display: "flex", gap: 0.5, flexWrap: "wrap" }}>
            <StatusChip label={enumLabel("orderStatus", order.status)} tone={STATUS_TONE[order.status]} />
            {isPartlyPlanned(order) && (
              <Tooltip title={t("Parte del pedido ya está en un viaje; el resto sigue planificable")}>
                <span><StatusChip label={t("Parcialmente planificado")} tone="neutral" /></span>
              </Tooltip>
            )}
          </Box>
          <Typography noWrap sx={{
            fontSize: T.micro + 0.5, mt: "3px",
            color: FULFILLMENT_COLOR[order.fulfillmentStatus] ?? "text.secondary",
            fontWeight: FULFILLMENT_COLOR[order.fulfillmentStatus] ? 600 : 400,
          }}>
            {t("Entrega")}: {enumLabel("orderFulfillmentStatus", order.fulfillmentStatus)}
          </Typography>
        </Box>
      ),
    },
  ];

  if (canManage) {
    columns.push({
      key: "actions",
      header: "",
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
  const toOptions = (items: { id: string; code: string; name: string }[] | undefined) =>
    (items ?? []).map((item) => ({ id: item.id, label: `${item.code} · ${item.name}` }));

  return (
    <>
      <PageHeader
        icon={<AssignmentTurnedInRounded />}
        tint={ICON_TINTS["/orders"]}
        title={t("Pedidos")}
        subtitle={t("Pedidos de transporte por fecha de servicio. Los totales los calcula el backend.")}
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

      <OrderStatusTabs
        value={filters.status}
        counts={counts}
        onChange={(status) => setFilters({ ...filters, status })}
      />

      <OrderFilterBar
        value={filters}
        onChange={setFilters}
        origins={toOptions(originsQuery.data?.content)}
        destinations={toOptions(destinationsQuery.data?.content)}
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
        footer={pageData && pageData.totalElements > 0 ? (
          <Box sx={{ display: "flex", alignItems: "center", gap: 2, flexWrap: "wrap" }}>
            {/* Los totales de la página, no de la empresa: el backend pagina y esta suma solo
                puede hablar de lo que hay en pantalla. Se dice literalmente. */}
            <Typography sx={{ fontSize: T.micro, color: "text.secondary", fontVariantNumeric: "tabular-nums", order: { xs: 2, md: 0 } }}>
              {t("Esta página")}: {fmtWeightKg(totals.weight)} · {fmtVolumeM3(totals.volume)} · {fmtDecimal(totals.pallets)} {t("pallets")}
            </Typography>
            <Box sx={{ flex: 1, minWidth: 280 }}>
              <Pagination page={pageData} onPageChange={setPage} />
            </Box>
          </Box>
        ) : undefined}
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
