import { keepPreviousData, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Box, Button, LinearProgress, Tab, Tabs, Typography } from "@mui/material";
import {
  AddRounded, VerifiedUserRounded, BlockRounded, CategoryRounded, CheckCircleRounded, EditRounded,
  EventBusyRounded, HelpOutlineRounded, PersonRounded, ReportProblemRounded, ScheduleRounded, ToggleOnRounded,
  TuneRounded, VerifiedRounded,
} from "@mui/icons-material";
import {
  activateFleetDocument, deactivateFleetDocument, fetchFleetComplianceSummary, fetchFleetDocumentPolicy,
  fetchFleetDocuments, fetchMissingFleetDocuments, FLEET_DOCUMENT_STATUSES, FLEET_DOCUMENT_TYPES,
  type FleetDocumentStatus, type FleetDocumentSubject, type FleetDocumentType, type FleetDocumentView,
  type MissingDocument,
} from "../../shared/api/fleetDocumentsApi";
import type { ApiError } from "../../shared/api/httpClient";
import { describeApiError } from "../../shared/api/problemMessages";
import { useCompany } from "../../shared/company/CompanyContext";
import {
  ActionMenu, ActiveBadge, DataTable, FilterBar, KpiCard, PageHeader, Pagination, StatusChip, type DataTableColumn,
} from "../../shared/ui/components";
import {
  ACTIVE_FILTER_OPTIONS, activeParam, notifySaved, toggleActiveRecord, type ActiveFilter,
} from "../../shared/ui/masterActions";
import { ICON_TINTS } from "../../shared/ui/navConfig";
import { enumLabel } from "../../lib/enums";
import { t } from "../../lib/i18n";
import { fmtDate, fmtQuantity } from "../../lib/locale";
import { FleetDocumentFormDrawer } from "./FleetDocumentFormDrawer";
import { FleetDocumentPolicyDrawer } from "./FleetDocumentPolicyDrawer";
import { expiryText } from "./fleetDocumentText";
import { FleetDocumentStatusChip } from "./fleetDocumentUi";

const PAGE_SIZE = 25;

interface Filters {
  subjectType: FleetDocumentSubject | "";
  documentType: FleetDocumentType | "";
  status: FleetDocumentStatus | "";
  active: ActiveFilter;
}

const DEFAULT_FILTERS: Filters = { subjectType: "", documentType: "", status: "", active: "active" };

/** Rojo para lo vencido, ámbar para lo que vence pronto, verde lo lejano, gris sin fecha. */
const BUCKET_COLOR: Record<string, string> = {
  EXPIRED_OVER_90: "error.main", EXPIRED_31_90: "error.main", EXPIRED_1_30: "error.main",
  DUE_0_7: "warning.main", DUE_8_30: "warning.main", DUE_31_90: "success.main", DUE_OVER_90: "success.main",
  NO_DATE: "text.disabled",
};

type Tab = "documents" | "missing";

/**
 * Documentos de flota (V72): todos los papeles de vehículos y conductores con su estado de hoy,
 * cuánto les falta para vencer, lo que falta registrar y la política de la empresa.
 *
 * La pantalla no calcula ningún estado: los trae el backend, contra el «hoy» de la empresa y su
 * ventana de alerta, con la misma regla que aplica la planificación al asignar un vehículo.
 */
export function FleetDocumentsPage() {
  const { selected, hasPermission } = useCompany();
  const companyId = selected?.id ?? "";
  const canManage = hasPermission("fleet.document:manage");
  const canManagePolicy = hasPermission("fleet.document_policy:manage");
  const queryClient = useQueryClient();

  const [tab, setTab] = useState<Tab>("documents");
  const [page, setPage] = useState(0);
  const [filters, setFiltersState] = useState<Filters>(DEFAULT_FILTERS);
  const setFilters = (next: Filters) => { setFiltersState(next); setPage(0); };
  const [editing, setEditing] = useState<FleetDocumentView | "new" | null>(null);
  const [policyOpen, setPolicyOpen] = useState(false);

  const summaryQuery = useQuery({
    queryKey: ["fleet-documents-summary", companyId],
    queryFn: ({ signal }) => fetchFleetComplianceSummary(companyId, signal),
    enabled: companyId !== "",
  });
  const documentsQuery = useQuery({
    queryKey: ["fleet-documents", companyId, page, filters],
    queryFn: ({ signal }) => fetchFleetDocuments({
      companyId, page, size: PAGE_SIZE,
      subjectType: filters.subjectType || undefined,
      documentType: filters.documentType || undefined,
      status: filters.status || undefined,
      active: activeParam(filters.active),
      signal,
    }),
    enabled: companyId !== "",
    placeholderData: keepPreviousData,
  });
  const missingQuery = useQuery({
    queryKey: ["fleet-documents-missing", companyId],
    queryFn: ({ signal }) => fetchMissingFleetDocuments(companyId, signal),
    enabled: companyId !== "" && tab === "missing",
  });
  const policyQuery = useQuery({
    queryKey: ["fleet-document-policy", companyId],
    queryFn: ({ signal }) => fetchFleetDocumentPolicy(companyId, signal),
    enabled: companyId !== "" && policyOpen,
  });

  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: ["fleet-documents", companyId] });
    void queryClient.invalidateQueries({ queryKey: ["fleet-documents-summary", companyId] });
    void queryClient.invalidateQueries({ queryKey: ["fleet-documents-missing", companyId] });
  };

  async function toggle(document: FleetDocumentView) {
    const changed = await toggleActiveRecord({
      name: enumLabel("fleetDocumentType", document.documentType),
      active: document.active,
      activate: () => activateFleetDocument(companyId, document.id),
      deactivate: () => deactivateFleetDocument(companyId, document.id),
    });
    if (changed) refresh();
  }

  const summary = summaryQuery.data;
  const showStatus = (status: FleetDocumentStatus) => { setTab("documents"); setFilters({ ...filters, status }); };

  const columns: DataTableColumn<FleetDocumentView>[] = [
    {
      key: "subject", header: t("Vehículo / conductor"),
      render: (d) => (
        <Box sx={{ minWidth: 0 }}>
          <Typography variant="body2" sx={{ fontWeight: 700 }}>{d.subjectCode ?? "-"}</Typography>
          <Typography variant="caption" color="text.secondary">
            {enumLabel("fleetDocumentSubject", d.subjectType)}{d.subjectName ? ` · ${d.subjectName}` : ""}
          </Typography>
        </Box>
      ),
    },
    {
      key: "type", header: t("Documento"),
      render: (d) => (
        <Box sx={{ minWidth: 0 }}>
          <Typography variant="body2">{enumLabel("fleetDocumentType", d.documentType)}</Typography>
          <Typography variant="caption" color="text.secondary">
            {[d.documentNumber, d.issuer].filter(Boolean).join(" · ") || "-"}
          </Typography>
        </Box>
      ),
    },
    { key: "expires", header: t("Vence el"), render: (d) => (d.expiresOn ? fmtDate(d.expiresOn) : "-") },
    { key: "ageing", header: t("Vencimiento"), render: (d) => expiryText(d.daysToExpiry) },
    { key: "status", header: t("Estado"), render: (d) => <FleetDocumentStatusChip status={d.status} /> },
    {
      key: "policy", header: t("Si vence"),
      render: (d) => (
        <StatusChip tone={d.enforcement === "BLOCK" ? "overdue" : "neutral"}
          label={`${enumLabel("complianceEnforcement", d.enforcement)}${d.mandatory ? ` · ${t("Obligatorio")}` : ""}`} />
      ),
    },
    { key: "active", header: t("Activo"), render: (d) => <ActiveBadge active={d.active} /> },
  ];
  if (canManage) {
    columns.push({
      key: "actions", header: t("Acciones"), actions: true,
      render: (document) => (
        <ActionMenu items={[
          { key: "edit", label: t("Editar"), icon: <EditRounded />, onSelect: () => setEditing(document) },
          {
            key: "active", label: document.active ? t("Desactivar") : t("Activar"),
            icon: document.active ? <BlockRounded /> : <CheckCircleRounded />, dangerous: document.active,
            onSelect: () => void toggle(document),
          },
        ]} />
      ),
    });
  }

  const missingColumns: DataTableColumn<MissingDocument>[] = [
    {
      key: "subject", header: t("Vehículo / conductor"),
      render: (m) => (
        <Box sx={{ minWidth: 0 }}>
          <Typography variant="body2" sx={{ fontWeight: 700 }}>{m.subjectCode ?? "-"}</Typography>
          <Typography variant="caption" color="text.secondary">
            {enumLabel("fleetDocumentSubject", m.subjectType)}{m.subjectName ? ` · ${m.subjectName}` : ""}
          </Typography>
        </Box>
      ),
    },
    { key: "type", header: t("Documento obligatorio"), render: (m) => enumLabel("fleetDocumentType", m.documentType) },
    { key: "status", header: t("Estado"), render: () => <FleetDocumentStatusChip status="UNRECORDED" /> },
  ];

  const ageingTotal = summary ? Math.max(1, summary.ageing.reduce((sum, bucket) => sum + bucket.count, 0)) : 1;
  const documentTypes = filters.subjectType ? FLEET_DOCUMENT_TYPES[filters.subjectType]
    : [...FLEET_DOCUMENT_TYPES.VEHICLE, ...FLEET_DOCUMENT_TYPES.DRIVER];

  return (
    <>
      <PageHeader
        icon={<VerifiedUserRounded />}
        tint={ICON_TINTS["/fleet/documents"]}
        title={t("Documentos de flota")}
        subtitle={t("Matrícula, revisión técnica, seguros y permisos de vehículos y conductores, con su vencimiento.")}
        onRefresh={refresh}
        refreshing={documentsQuery.isFetching || summaryQuery.isFetching}
        actions={(
          <>
            <Button variant="outlined" startIcon={<TuneRounded />} onClick={() => setPolicyOpen(true)}>
              {t("Política")}
            </Button>
            {canManage && (
              <Button variant="contained" startIcon={<AddRounded />} onClick={() => setEditing("new")}>
                {t("Nuevo documento")}
              </Button>
            )}
          </>
        )}
      />

      <Box sx={{ display: "grid", gap: 1.5, mb: 2, gridTemplateColumns: { xs: "repeat(2, minmax(0, 1fr))", md: "repeat(5, minmax(0, 1fr))" } }}>
        <KpiCard title={t("Vencidos")} value={fmtQuantity(summary?.expired ?? 0)} icon={<EventBusyRounded />}
          color="error.main" loading={summaryQuery.isPending} onClick={() => showStatus("EXPIRED")} />
        <KpiCard title={t("Por vencer")} value={fmtQuantity(summary?.expiringSoon ?? 0)} icon={<ScheduleRounded />}
          color="warning.main" loading={summaryQuery.isPending}
          sub={summary ? t("En los próximos {{n}} días", { n: summary.alertDays }) : undefined}
          onClick={() => showStatus("EXPIRING_SOON")} />
        <KpiCard title={t("Vigentes")} value={fmtQuantity(summary?.valid ?? 0)} icon={<VerifiedRounded />}
          color="success.main" loading={summaryQuery.isPending} onClick={() => showStatus("VALID")} />
        <KpiCard title={t("Sin fecha")} value={fmtQuantity(summary?.unrecorded ?? 0)} icon={<HelpOutlineRounded />}
          color="info.main" loading={summaryQuery.isPending} onClick={() => showStatus("UNRECORDED")} />
        <KpiCard title={t("Obligatorios faltantes")} value={fmtQuantity(summary?.missingMandatory ?? 0)}
          icon={<ReportProblemRounded />} color="warning.main" loading={summaryQuery.isPending}
          onClick={() => setTab("missing")} />
      </Box>

      {summary && summary.total > 0 && (
        <Box component="section" aria-label={t("Antigüedad de los vencimientos")}
          sx={{ mb: 2, display: "grid", gap: 0.75, gridTemplateColumns: { xs: "1fr", md: "repeat(2, minmax(0, 1fr))" }, columnGap: 3 }}>
          {summary.ageing.map((bucket) => (
            <Box key={bucket.bucket} sx={{ display: "grid", gridTemplateColumns: "minmax(0, 220px) minmax(0, 1fr) 40px", alignItems: "center", gap: 1 }}>
              <Typography variant="caption" color="text.secondary" noWrap>{enumLabel("fleetDocumentAgeing", bucket.bucket)}</Typography>
              <LinearProgress variant="determinate" value={(bucket.count / ageingTotal) * 100}
                sx={{ height: 8, borderRadius: 4, bgcolor: "action.hover", "& .MuiLinearProgress-bar": { bgcolor: BUCKET_COLOR[bucket.bucket] } }}
                aria-label={enumLabel("fleetDocumentAgeing", bucket.bucket)} />
              <Typography variant="caption" sx={{ textAlign: "right", fontVariantNumeric: "tabular-nums" }}>{bucket.count}</Typography>
            </Box>
          ))}
        </Box>
      )}

      <Tabs value={tab} onChange={(_, value: Tab) => setTab(value)} sx={{ mb: 1.5 }}>
        <Tab value="documents" label={t("Documentos")} />
        <Tab value="missing" label={t("Faltantes obligatorios")} />
      </Tabs>

      {tab === "documents" ? (
        <>
          <FilterBar
            value={filters}
            defaults={DEFAULT_FILTERS}
            onChange={setFilters}
            fields={[
              { type: "select", key: "subjectType", label: t("Sujeto"), icon: <PersonRounded />, allLabel: t("Vehículos y conductores"),
                options: (["VEHICLE", "DRIVER"] as const).map((s) => ({ id: s, label: enumLabel("fleetDocumentSubject", s) })) },
              { type: "select", key: "documentType", label: t("Documento"), icon: <CategoryRounded />, allLabel: t("Todos los documentos"),
                options: documentTypes.map((type) => ({ id: type, label: enumLabel("fleetDocumentType", type) })) },
              { type: "select", key: "status", label: t("Estado"), icon: <ScheduleRounded />, allLabel: t("Todos los estados"),
                options: FLEET_DOCUMENT_STATUSES.map((s) => ({ id: s, label: enumLabel("fleetDocumentStatus", s) })) },
              { type: "select", key: "active", label: t("Activo"), icon: <ToggleOnRounded />,
                options: ACTIVE_FILTER_OPTIONS.map((o) => ({ id: o.value, label: t(o.label) })) },
            ]}
          />
          <DataTable
            columns={columns}
            rows={documentsQuery.data?.content ?? []}
            total={documentsQuery.data?.totalElements}
            rowKey={(d) => d.id}
            isLoading={documentsQuery.isPending}
            error={documentsQuery.isError ? describeApiError(documentsQuery.error as ApiError) : null}
            onRetry={() => void documentsQuery.refetch()}
            emptyTitle={t("Sin documentos")}
            emptyMessage={t("Registra los papeles de la flota o ajusta los filtros.")}
            footer={documentsQuery.data ? <Pagination page={documentsQuery.data} onPageChange={setPage} /> : undefined}
          />
        </>
      ) : (
        <DataTable
          columns={missingColumns}
          rows={missingQuery.data ?? []}
          rowKey={(m) => `${m.subjectId}-${m.documentType}`}
          isLoading={missingQuery.isPending}
          error={missingQuery.isError ? describeApiError(missingQuery.error as ApiError) : null}
          onRetry={() => void missingQuery.refetch()}
          emptyTitle={t("Nada que falte")}
          emptyMessage={t("Ningún vehículo ni conductor activo carece de un documento obligatorio. Los obligatorios se eligen en la política.")}
        />
      )}

      {editing && (
        <FleetDocumentFormDrawer
          companyId={companyId}
          document={editing === "new" ? null : editing}
          onClose={() => setEditing(null)}
          onSaved={() => { notifySaved(editing !== "new"); setEditing(null); refresh(); }}
        />
      )}
      {policyOpen && policyQuery.data && (
        <FleetDocumentPolicyDrawer
          companyId={companyId}
          policy={policyQuery.data}
          canManage={canManagePolicy}
          onClose={() => setPolicyOpen(false)}
          onSaved={(saved) => {
            queryClient.setQueryData(["fleet-document-policy", companyId], saved);
            setPolicyOpen(false);
            notifySaved(true);
            refresh();
          }}
        />
      )}
    </>
  );
}
