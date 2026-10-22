import { useMemo, useState } from "react";
import { useQueries, useQuery } from "@tanstack/react-query";
import { Link, useParams } from "react-router-dom";
import { Box, Button, Typography } from "@mui/material";
import { ArrowBackRounded, PrintRounded } from "@mui/icons-material";
import type { ApiError } from "../../shared/api/httpClient";
import { fetchTripDocuments, type LogisticsDocumentView } from "../../shared/api/logisticsDocumentsApi";
import { fetchOrder, type OrderDetailView } from "../../shared/api/ordersApi";
import { describeApiError } from "../../shared/api/problemMessages";
import { useCompany } from "../../shared/company/CompanyContext";
import { ErrorState, LoadingState } from "../../shared/ui/components";
import { t } from "../../lib/i18n";
import { useTripDetail } from "./tripQueries";
import { TripRouteSheet } from "./TripRouteSheet";

/**
 * `/trips/:tripId/print`: la hoja de ruta del envío, lista para imprimir.
 *
 * Fuera del armazón de la oficina a propósito: sin menú lateral ni barra superior, lo que se ve
 * es lo que sale en el papel. La impresión es la del navegador (`window.print`) con CSS de
 * impresión; no hay librería de PDF — "Guardar como PDF" del diálogo de impresión ya lo hace.
 *
 * Lee lo mismo que el espacio de trabajo (mismas claves de caché) y, con `orders.order:read`, las
 * líneas de cada pedido y los documentos que mandó el ERP. Sin ese permiso imprime igual, con lo
 * que el envío sabe de cada parada, y lo dice.
 */
export function TripRouteSheetPage() {
  const { tripId } = useParams<{ tripId: string }>();
  const { selected, hasPermission } = useCompany();
  const companyId = selected?.id ?? "";
  const canReadOrders = hasPermission("orders.order:read");
  const [printedAt] = useState(() => new Date());

  const tripQuery = useTripDetail(companyId, tripId);
  const orderIds = useMemo(
    () => [...new Set((tripQuery.data?.assignments ?? []).map((a) => a.orderId))],
    [tripQuery.data],
  );

  const orderQueries = useQueries({
    queries: orderIds.map((orderId) => ({
      queryKey: ["order", companyId, orderId],
      queryFn: ({ signal }: { signal: AbortSignal }) => fetchOrder(companyId, orderId, signal),
      enabled: canReadOrders && companyId !== "",
      retry: false,
    })),
  });

  const documentsQuery = useQuery({
    queryKey: ["trip-documents", companyId, tripId],
    queryFn: ({ signal }) => fetchTripDocuments(companyId, tripId as string, signal),
    enabled: canReadOrders && companyId !== "" && tripId !== undefined,
    retry: false,
  });

  const orders = useMemo(() => {
    const map = new Map<string, OrderDetailView>();
    for (const query of orderQueries) if (query.data) map.set(query.data.id, query.data);
    return map;
  }, [orderQueries]);

  const documents = useMemo(() => {
    const map = new Map<string, LogisticsDocumentView[]>();
    for (const entry of documentsQuery.data ?? []) map.set(entry.orderId, entry.documents);
    return map;
  }, [documentsQuery.data]);

  const waitingForOrders = canReadOrders
    && (orderQueries.some((query) => query.isPending) || documentsQuery.isPending);

  if (tripQuery.isPending || (tripQuery.isSuccess && waitingForOrders)) {
    return <LoadingState label={t("Preparando la hoja de ruta...")} />;
  }
  if (tripQuery.isError) {
    return <ErrorState message={describeApiError(tripQuery.error as ApiError)} />;
  }

  const detail = tripQuery.data;
  const linesFailed = orderQueries.some((query) => query.isError) || documentsQuery.isError;

  return (
    <Box sx={{ bgcolor: "#e9e9e9", minHeight: "100vh", py: { xs: 0, sm: 3 }, "@media print": { bgcolor: "#fff", py: 0 } }}>
      <Box className="route-sheet-screen-only" sx={{
        maxWidth: "210mm", mx: "auto", mb: 2, px: { xs: 2, sm: 0 }, pt: { xs: 2, sm: 0 },
        display: "flex", gap: 1, alignItems: "center", flexWrap: "wrap",
      }}>
        <Button component={Link} to={`/trips/${detail.trip.id}`} startIcon={<ArrowBackRounded />} color="inherit">
          {t("Volver al viaje")}
        </Button>
        <Box sx={{ flex: 1 }} />
        {linesFailed && (
          <Typography variant="body2" color="warning.dark">
            {t("No se pudieron leer todas las líneas o documentos; la hoja sale con lo que llegó.")}
          </Typography>
        )}
        <Button variant="contained" startIcon={<PrintRounded />} onClick={() => window.print()}>
          {t("Imprimir")}
        </Button>
      </Box>
      <TripRouteSheet data={{
        companyName: selected?.name ?? "",
        detail,
        orders,
        documents,
        linesAvailable: canReadOrders,
        printedAt,
      }} />
    </Box>
  );
}
