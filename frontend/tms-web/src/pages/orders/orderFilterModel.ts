import type { OrderPriority, OrderStatus } from "../../shared/api/ordersApi";

/** Los filtros de la lista de pedidos, tal y como se aplican: no hay borrador. */
export interface OrderFilters {
  orderNumber: string;
  originId: string;
  destinationId: string;
  serviceDateFrom: string;
  serviceDateTo: string;
  status: OrderStatus | "";
  priority: OrderPriority | "";
}

export const DEFAULT_ORDER_FILTERS: OrderFilters = {
  orderNumber: "", originId: "", destinationId: "", serviceDateFrom: "", serviceDateTo: "", status: "", priority: "",
};
