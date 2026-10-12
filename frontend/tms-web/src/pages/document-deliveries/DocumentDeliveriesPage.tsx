import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { Box, Chip, Tooltip, Typography } from "@mui/material";
import {
  AssignmentReturnRounded, BlockRounded, BusinessRounded, CheckCircleRounded, DateRangeRounded,
  DoNotDisturbOnRounded, FactCheckRounded, FlagRounded, HourglassEmptyRounded, PhotoCameraRounded,
  ReportProblemRounded, AssignmentReturnedRounded,
} from "@mui/icons-material";
import { fetchCarriers } from "../../shared/api/carriersApi";
import {
  DOCUMENT_DELIVERY_STATUSES, fetchDocumentDeliveries, fetchDocumentDeliverySummary,
  type CarrierDocumentTotals, type DocumentDeliveryRow, type DocumentDeliveryStatus,
} from "../../shared/api/documentDeliveriesApi";
import type { ApiError } from "../../shared/api/httpClient";
import { CAPABILITY } from "../../shared/api/meApi";
import { describeApiError } from "../../shared/api/problemMessages";
import { useCompany } from "../../shared/company/CompanyContext";
import { DocumentStatusChip } from "../../shared/ui/DocumentStatusChip";
import {
  DataTable, FilterBar, KpiCard, PageHeader, Pagination, SectionHeader, StatusChip, type DataTableColumn,
} from "../../shared/ui/components";
import { enumLabel } from "../../lib/enums";
import { t } from "../../lib/i18n";
import { fmtDateTime, fmtQuantity } from "../../lib/locale";
import { DocumentDeliveryDrawer } from "./DocumentDeliveryDrawer";
import {
  defaultMonitorFilters, documentDeliveriesKey, toListParams, type MonitorFilters,
} from "./documentDeliveries";

const PAGE_SIZE = 25;

/**
 * Entrega de documentos (ADR-018): qué pasó con cada factura, guía y GRE que salió en un envío, y
 * qué cargos firmados faltan por volver a la oficina.
 *
 * El resumen y el desglose por transportista responden a las fechas y al transportista; la tabla,
 * además, al estado, al cargo y a la búsqueda, y pagina en el servidor. Las fechas son de despacho
 * del envío, no del reporte: así un documento no cambia de semana cuando el transportista lo
 * reporta tarde.
 */
export function DocumentDeliveriesPage() {
  const { selected, hasCapability } = useCompany();
  const companyId = selected?.id ?? "";
  const canManage = hasCapability(CAPABILITY.DOCUMENT_DELIVERY_MANAGE);

  const [defaults] = useState(() => defaultMonitorFilters());
  const [filters, setFiltersState] = useState<MonitorFilters>(defaults);
  const [page, setPage] = useState(0);
  const [openRow, setOpenRow] = useState<DocumentDeliveryRow | null>(null);
  const setFilters = (next: MonitorFilters) => { setFiltersState(next); setPage(0); };

  const listParams = toListParams(filters);

  const listQuery = useQuery({
    queryKey: [...documentDeliveriesKey(companyId), "list", listParams, page],
    queryFn: ({ signal }) => fetchDocumentDeliveries({ companyId, ...listParams, page, size: PAGE_SIZE, signal }),
    enabled: companyId !== "",
    placeholderData: keepPreviousData,
  });

  const summaryParams = { dateFrom: listParams.dateFrom, dateTo: listParams.dateTo, carrierId: listParams.carrierId };
  const summaryQuery = useQuery({
    queryKey: [...documentDeliveriesKey(companyId), "summary", summaryParams],
    queryFn: ({ signal }) => fetchDocumentDeliverySummary({ companyId, ...summaryParams, signal }),
    enabled: companyId !== "",
    placeholderData: keepPreviousData,
  });

  const carriersQuery = useQuery({
    queryKey: ["carriers-for-trip-filter", companyId],
    queryFn: ({ signal }) => fetchCarriers({ companyId, size: 200, active: true, sort: "code,asc", signal }),
    enabled: companyId !== "",
  });

  const summary = summaryQuery.data;
  const loadingSummary = summaryQuery.isPending;
  const filterStatus = (status: DocumentDeliveryStatus) => setFilters({ ...filters, status, returned: "" });

  const columns: DataTableColumn<DocumentDeliveryRow>[] = [
    {
      key: "shipment",
      header: t("Envío"),
      render: (row) => (
        <Box>
          <Typography variant="body2" sx={{ fontWeight: 800 }}>{row.shipmentNumber}</Typography>
          <Typography variant="caption" color="text.secondary">{row.dispatchedAt ? fmtDateTime(row.dispatchedAt) : "-"}</Typography>
        </Box>
      ),
    },
    {
      key: "carrier",
      header: t("Transportista"),
      render: (row) => (
        <Box>
          <Typography variant="body2">{row.carrierName ?? t("Sin transportista")}</Typography>
          <Typography variant="caption" color="text.secondary">
            {[row.vehicleLicensePlate, row.driverName].filter(Boolean).join(" · ")}
          </Typography>
        </Box>
      ),
    },
    {
      key: "document",
      header: t("Documento"),
      render: (row) => (
        <Box>
          <Typography variant="body2" sx={{ fontWeight: 700 }}>{row.documentNumber}</Typography>
          <Typography variant="caption" color="text.secondary">{enumLabel("deliveryDocumentType", row.documentType)}</Typography>
        </Box>
      ),
    },
    {
      key: "customer",
      header: t("Cliente / pedidos"),
      render: (row) => (
        <Box sx={{ maxWidth: 240 }}>
          <Typography variant="body2" noWrap>{row.customerName ?? "-"}</Typography>
          <Typography variant="caption" color="text.secondary" noWrap component="div">{row.orderNumbers.join(", ")}</Typography>
        </Box>
      ),
    },
    {
      key: "status",
      header: t("Estado"),
      render: (row) => (
        <Box sx={{ display: "grid", gap: 0.25, justifyItems: "start" }}>
          <DocumentStatusChip status={row.status} />
          {row.reasonCode && (
            <Typography variant="caption" color="text.secondary">{enumLabel("documentDeliveryReason", row.reasonCode)}</Typography>
          )}
        </Box>
      ),
    },
    {
      key: "reported",
      header: t("Reportado"),
      render: (row) => row.reportedAt ? (
        <Box>
          <Typography variant="body2">{fmtDateTime(row.reportedAt)}</Typography>
          {row.recordedByName && <Typography variant="caption" color="text.secondary">{row.recordedByName}</Typography>}
        </Box>
      ) : "-",
    },
    {
      key: "evidence",
      header: t("Fotos"),
      numeric: true,
      width: 70,
      render: (row) => row.evidenceCount > 0 ? (
        <Box component="span" sx={{ display: "inline-flex", alignItems: "center", gap: 0.5 }}>
          <PhotoCameraRounded sx={{ fontSize: 15, color: "text.secondary" }} />{row.evidenceCount}
        </Box>
      ) : "-",
    },
    {
      key: "return",
      header: t("Cargo"),
      render: (row) => row.returnedAt
        ? <StatusChip tone="done" label={t("Recibido")} />
        : row.status === "PENDING" ? <Typography variant="caption" color="text.disabled">-</Typography>
          : <StatusChip tone="inProgress" label={t("Por recibir")} />,
    },
    {
      key: "flags",
      header: t("Alertas"),
      render: (row) => row.inconsistency ? (
        <Tooltip title={enumLabel("documentInconsistency", row.inconsistency)}>
          <Chip size="small" color="warning" icon={<ReportProblemRounded />} label={t("Inconsistencia")} sx={{ height: 22, fontSize: 11 }} />
        </Tooltip>
      ) : <Typography variant="caption" color="text.disabled">-</Typography>,
    },
  ];

  const carrierColumns: DataTableColumn<CarrierDocumentTotals>[] = [
    { key: "carrier", header: t("Transportista"), render: (c) => <Typography variant="body2" sx={{ fontWeight: 700 }}>{c.carrierName ?? t("Sin transportista")}</Typography> },
    { key: "total", header: t("Documentos"), numeric: true, render: (c) => fmtQuantity(c.total) },
    { key: "pending", header: t("Pendientes"), numeric: true, render: (c) => fmtQuantity(c.pending) },
    { key: "delivered", header: t("Entregados"), numeric: true, render: (c) => fmtQuantity(c.delivered) },
    { key: "rejected", header: t("Rechazados"), numeric: true, render: (c) => fmtQuantity(c.rejected) },
    { key: "notDelivered", header: t("No entregados"), numeric: true, render: (c) => fmtQuantity(c.notDelivered) },
    { key: "awaitingReturn", header: t("Cargo por recibir"), numeric: true, render: (c) => fmtQuantity(c.awaitingReturn) },
  ];

  const pageData = listQuery.data;

  return (
    <>
      <PageHeader
        icon={<FactCheckRounded />}
        title={t("Entrega de documentos")}
        subtitle={t("Qué pasó con cada factura, guía y GRE de los envíos despachados, y qué cargos faltan por volver. Abre una fila para ver fotos, firma e historial.")}
        onRefresh={() => { void listQuery.refetch(); void summaryQuery.refetch(); }}
        refreshing={listQuery.isFetching || summaryQuery.isFetching}
      />

      <Box sx={{
        display: "grid", gap: 1.5, mb: 2.5,
        gridTemplateColumns: { xs: "repeat(2, minmax(0,1fr))", md: "repeat(3, minmax(0,1fr))", lg: "repeat(6, minmax(0,1fr))" },
      }}>
        <KpiCard loading={loadingSummary} icon={<HourglassEmptyRounded />} color="text.secondary" title={t("Pendientes")}
          value={fmtQuantity(summary?.pending)} onClick={() => filterStatus("PENDING")} />
        <KpiCard loading={loadingSummary} icon={<CheckCircleRounded />} color="success.main" title={t("Entregados")}
          value={fmtQuantity(summary?.delivered)} onClick={() => filterStatus("DELIVERED")} />
        <KpiCard loading={loadingSummary} icon={<BlockRounded />} color="error.main" title={t("Rechazados")}
          value={fmtQuantity(summary?.rejected)} onClick={() => filterStatus("REJECTED")} />
        <KpiCard loading={loadingSummary} icon={<DoNotDisturbOnRounded />} color="warning.main" title={t("No entregados")}
          value={fmtQuantity(summary?.notDelivered)} onClick={() => filterStatus("NOT_DELIVERED")} />
        <KpiCard loading={loadingSummary} icon={<AssignmentReturnRounded />} color="info.main" title={t("Cargo por recibir")}
          sub={summary ? t("{{n}} recibidos", { n: fmtQuantity(summary.returned) }) : undefined}
          value={fmtQuantity(summary?.awaitingReturn)} />
        <KpiCard loading={loadingSummary} icon={<ReportProblemRounded />} color="warning.main" title={t("Inconsistencias")}
          sub={t("Documento y mercadería no coinciden")} value={fmtQuantity(summary?.inconsistent)} />
      </Box>

      {summary && summary.byCarrier.length > 0 && (
        <Box sx={{ mb: 2.5 }}>
          <SectionHeader title={t("Por transportista")} />
          <DataTable
            columns={carrierColumns}
            rows={summary.byCarrier}
            rowKey={(c) => c.carrierId ?? "none"}
            onRowClick={(c) => { if (c.carrierId) setFilters({ ...filters, carrierId: c.carrierId }); }}
            maxHeight={260}
          />
        </Box>
      )}

      <FilterBar
        value={filters}
        defaults={defaults}
        onChange={setFilters}
        fields={[
          { type: "search", key: "q", placeholder: t("Documento, envío, pedido o cliente"), width: 300 },
          { type: "dateRange", from: "dateFrom", to: "dateTo", label: t("Despacho"), icon: <DateRangeRounded /> },
          { type: "select", key: "status", label: t("Estado"), icon: <FlagRounded />, allLabel: t("Todos los estados"),
            options: DOCUMENT_DELIVERY_STATUSES.map((s) => ({ id: s, label: enumLabel("documentDeliveryStatus", s) })) },
          { type: "select", key: "carrierId", label: t("Transportista"), icon: <BusinessRounded />, allLabel: t("Todos los transportistas"),
            options: (carriersQuery.data?.content ?? []).map((c) => ({ id: c.id, label: c.businessName })) },
          { type: "select", key: "returned", label: t("Cargo recibido"), icon: <AssignmentReturnedRounded />, allLabel: t("Todos"),
            options: [{ id: "true", label: t("Sí") }, { id: "false", label: t("No") }] },
        ]}
      />

      <DataTable
        columns={columns}
        rows={pageData?.content ?? []}
        total={pageData?.totalElements}
        rowKey={(row) => row.id}
        isLoading={listQuery.isPending}
        error={listQuery.isError ? describeApiError(listQuery.error as ApiError) : null}
        onRetry={() => void listQuery.refetch()}
        emptyTitle={t("Sin documentos")}
        emptyMessage={t("Ningún documento de envíos despachados coincide con las fechas y los filtros.")}
        onRowClick={setOpenRow}
        rowAccent={(row) => row.inconsistency ? "warning.main" : row.status === "REJECTED" ? "error.main" : null}
        footer={pageData ? <Pagination page={pageData} onPageChange={setPage} /> : undefined}
      />

      {openRow && (
        <DocumentDeliveryDrawer companyId={companyId} row={openRow} canManage={canManage} onClose={() => setOpenRow(null)} />
      )}
    </>
  );
}
