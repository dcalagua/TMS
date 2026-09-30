import { useQuery } from "@tanstack/react-query";
import { Alert, Typography } from "@mui/material";
import type { ApiError } from "../../shared/api/httpClient";
import { fetchOrderDocuments } from "../../shared/api/logisticsDocumentsApi";
import { describeApiError } from "../../shared/api/problemMessages";
import { LoadingState, SectionHeader } from "../../shared/ui/components";
import { LogisticsDocumentsTable } from "../../shared/ui/LogisticsDocumentsTable";
import { t } from "../../lib/i18n";

/**
 * Los documentos del pedido (ADR-015): facturas, notas de entrega y guías de remisión que el ERP
 * registró. Solo lectura: TMS los enseña y no cambia nada por ellos.
 */
export function OrderDocumentsSection({ companyId, orderId }: { companyId: string; orderId: string }) {
  const documentsQuery = useQuery({
    queryKey: ["order-documents", companyId, orderId],
    queryFn: ({ signal }) => fetchOrderDocuments(companyId, orderId, signal),
    retry: false,
  });

  return (
    <>
      <SectionHeader title={t("Documentos")} />
      {documentsQuery.isPending ? (
        <LoadingState minHeight={80} />
      ) : documentsQuery.isError ? (
        <Alert severity="warning">{describeApiError(documentsQuery.error as ApiError)}</Alert>
      ) : documentsQuery.data.length === 0 ? (
        <Typography variant="body2" color="text.secondary">
          {t("El ERP no ha registrado documentos para este pedido.")}
        </Typography>
      ) : (
        <LogisticsDocumentsTable documents={documentsQuery.data} />
      )}
    </>
  );
}
