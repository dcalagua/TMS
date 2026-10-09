import { useQuery } from "@tanstack/react-query";
import { Typography } from "@mui/material";
import { DescriptionRounded } from "@mui/icons-material";
import type { ApiError } from "../../shared/api/httpClient";
import { fetchTripDocuments } from "../../shared/api/logisticsDocumentsApi";
import { describeApiError } from "../../shared/api/problemMessages";
import { LoadingState } from "../../shared/ui/components";
import { WorkspaceCard } from "./WorkspaceCard";
import { LogisticsDocumentsTable } from "../../shared/ui/LogisticsDocumentsTable";
import { t } from "../../lib/i18n";

/**
 * Los documentos que viajan en este envío (ADR-015), derivados de los pedidos que lleva: un
 * pedido repartido enseña sus documentos en cada envío que lleva parte de él. Solo lectura.
 */
export function TripDocumentsCard({ companyId, tripId, orderNumbers }: {
  companyId: string;
  tripId: string;
  orderNumbers: Map<string, string>;
}) {
  const documentsQuery = useQuery({
    queryKey: ["trip-documents", companyId, tripId],
    queryFn: ({ signal }) => fetchTripDocuments(companyId, tripId, signal),
    retry: false,
  });

  const rows = (documentsQuery.data ?? []).flatMap((entry) =>
    entry.documents.map((document) => ({ ...document, orderLabel: orderNumbers.get(entry.orderId) ?? entry.orderId })));

  return (
    <WorkspaceCard
      icon={<DescriptionRounded />}
      title={t("Documentos")}
    >
      {documentsQuery.isPending ? (
        <LoadingState minHeight={80} />
      ) : documentsQuery.isError ? (
        <Typography variant="body2" color="text.secondary">{describeApiError(documentsQuery.error as ApiError)}</Typography>
      ) : rows.length === 0 ? (
        <Typography variant="body2" color="text.secondary">
          {t("Ningún pedido de este envío tiene documentos registrados por el ERP.")}
        </Typography>
      ) : (
        <LogisticsDocumentsTable documents={rows} showOrder />
      )}
    </WorkspaceCard>
  );
}
