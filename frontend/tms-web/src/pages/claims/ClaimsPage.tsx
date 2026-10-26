import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import { useSearchParams } from "react-router-dom";
import { Box, Button, Typography } from "@mui/material";
import {
  AddRounded, GavelRounded, HourglassTopRounded, PendingActionsRounded, ReceiptLongRounded,
} from "@mui/icons-material";
import { CAPABILITY } from "../../shared/api/meApi";
import {
  ACTIVE_CLAIM_STATUSES, CLAIM_STATUSES, CLAIM_TYPES, fetchClaims, fetchClaimsOverview,
  type ClaimStatus, type ClaimSummary, type ClaimType,
} from "../../shared/api/claimsApi";
import { fetchCarriers } from "../../shared/api/carriersApi";
import type { ApiError } from "../../shared/api/httpClient";
import { describeApiError } from "../../shared/api/problemMessages";
import { DataTable, FilterBar, KpiCard, PageHeader, Pagination, StatusChip, type DataTableColumn } from "../../shared/ui/components";
import { ICON_TINTS } from "../../shared/ui/navConfig";
import { useCompany } from "../../shared/company/CompanyContext";
import { notifySuccess } from "../../lib/ui";
import { enumLabel } from "../../lib/enums";
import { t } from "../../lib/i18n";
import { fmtDate, fmtDecimal, fmtQuantity } from "../../lib/locale";
import { Money } from "../settlement/Money";
import { ClaimDetailDrawer } from "./ClaimDetailDrawer";
import { ClaimFormDrawer, type ClaimFormMode } from "./ClaimFormDrawer";
import { CLAIM_STATUS_TONE, ageLabel, ageTone } from "./claimUi";

interface Filters {
  status: string;
  carrierId: string;
  claimType: string;
  from: string;
  to: string;
  search: string;
}

/** «Activos» es lo que se mira cada mañana: lo que todavía pide que alguien haga algo. */
const DEFAULTS: Filters = { status: "ACTIVE", carrierId: "", claimType: "", from: "", to: "", search: "" };
const PAGE_SIZE = 25;

function statusesOf(value: string): ClaimStatus[] | undefined {
  if (value === "ACTIVE") return ACTIVE_CLAIM_STATUSES;
  if (value === "") return undefined;
  return [value as ClaimStatus];
}

const LINK_PARAMS = ["fromDelivery", "fromException", "fromTrip", "claim"];

/** El formulario que abre un enlace de otra pantalla, ya con su origen. */
function linkedForm(params: URLSearchParams, canManage: boolean): ClaimFormMode | null {
  if (!canManage || params.has("claim")) return null;
  const fromDelivery = params.get("fromDelivery");
  const fromException = params.get("fromException");
  const fromTrip = params.get("fromTrip");
  if (fromDelivery) return { kind: "open", source: { orderDeliveryId: fromDelivery } };
  if (fromException) return { kind: "open", source: { tripExceptionId: fromException } };
  if (fromTrip) return { kind: "open", source: { tripId: fromTrip } };
  return null;
}

/**
 * Reclamos de carga (V71): lo que se le reclama a un transportista por daño, faltante, pérdida o
 * demora, con su antigüedad y lo que falta recuperar.
 *
 * Se llega aquí también desde «Registrar la entrega» o desde una incidencia, con
 * `?fromDelivery=`, `?fromException=` o `?fromTrip=`: el formulario se abre ya prellenado. Y con
 * `?claim=` se abre un reclamo concreto.
 */
export function ClaimsPage() {
  const { selected, hasCapability } = useCompany();
  const companyId = selected?.id ?? "";
  const canManage = hasCapability(CAPABILITY.CLAIMS_MANAGE);
  const queryClient = useQueryClient();
  const [searchParams, setSearchParams] = useSearchParams();
  const [filters, setFilters] = useState<Filters>(DEFAULTS);
  const [page, setPage] = useState(0);
  // Los enlaces de otras pantallas («Abrir reclamo» desde una entrega o una incidencia, un reclamo
  // concreto): se leen al montar y se quitan de la URL, para que recargar no vuelva a abrir el drawer.
  const [form, setForm] = useState<ClaimFormMode | null>(() => linkedForm(searchParams, canManage));
  const [openId, setOpenId] = useState<string | null>(() => searchParams.get("claim"));
  useEffect(() => {
    if (LINK_PARAMS.some((name) => searchParams.has(name))) setSearchParams({}, { replace: true });
  }, [searchParams, setSearchParams]);

  const query = {
    status: statusesOf(filters.status),
    carrierId: filters.carrierId,
    claimType: (filters.claimType || undefined) as ClaimType | undefined,
    from: filters.from,
    to: filters.to,
    search: filters.search,
    page,
    size: PAGE_SIZE,
  };
  const claimsQuery = useQuery({
    queryKey: ["freight-claims", companyId, query],
    queryFn: ({ signal }) => fetchClaims(companyId, query, signal),
    enabled: companyId !== "",
  });
  const overviewQuery = useQuery({
    queryKey: ["freight-claims-overview", companyId],
    queryFn: ({ signal }) => fetchClaimsOverview(companyId, signal),
    enabled: companyId !== "",
  });
  const carriersQuery = useQuery({
    queryKey: ["carriers-for-claims", companyId],
    queryFn: ({ signal }) => fetchCarriers({ companyId, size: 200, sort: "businessName,asc", signal }),
    enabled: companyId !== "",
  });

  function refresh() {
    void queryClient.invalidateQueries({ queryKey: ["freight-claims", companyId] });
    void queryClient.invalidateQueries({ queryKey: ["freight-claims-overview", companyId] });
    void queryClient.invalidateQueries({ queryKey: ["incidents", companyId] });
  }

  const columns: DataTableColumn<ClaimSummary>[] = [
    {
      key: "claimNumber",
      header: t("Reclamo"),
      render: (row) => (
        <Box>
          <Typography variant="body2" sx={{ fontWeight: 700, fontVariantNumeric: "tabular-nums" }}>{row.claimNumber}</Typography>
          <Typography variant="caption" color="text.secondary">{enumLabel("claimType", row.claimType)}</Typography>
        </Box>
      ),
    },
    {
      key: "status",
      header: t("Estado"),
      render: (row) => (
        <Box sx={{ display: "flex", gap: 0.5, flexWrap: "wrap" }}>
          <StatusChip label={enumLabel("claimStatus", row.status)} tone={CLAIM_STATUS_TONE[row.status]} />
          {row.status === "CLOSED" && row.resolution && (
            <Typography variant="caption" color="text.secondary">{enumLabel("claimResolution", row.resolution)}</Typography>
          )}
        </Box>
      ),
    },
    { key: "carrier", header: t("Transportista"), render: (row) => row.carrierName ?? "—" },
    {
      key: "shipment",
      header: t("Envío"),
      render: (row) => (
        <Box>
          <Typography variant="body2">{row.shipmentNumber ?? "—"}</Typography>
          {row.orderNumber && <Typography variant="caption" color="text.secondary">{row.orderNumber}</Typography>}
        </Box>
      ),
    },
    { key: "incidentDate", header: t("Incidente"), render: (row) => fmtDate(row.incidentDate) },
    {
      key: "age",
      header: t("Antigüedad"),
      numeric: true,
      render: (row) => (
        <Typography variant="body2" component="span" sx={{ color: ageTone(row.ageDays, ACTIVE_CLAIM_STATUSES.includes(row.status)), fontVariantNumeric: "tabular-nums" }}>
          {ageLabel(row.ageDays)}
        </Typography>
      ),
    },
    { key: "claimed", header: t("Reclamado"), numeric: true, render: (row) => <Money value={row.claimedAmount} currency={row.currency} /> },
    {
      key: "accepted",
      header: t("Aceptado"),
      numeric: true,
      render: (row) => row.acceptedAmount === null ? "—" : <Money value={row.acceptedAmount} currency={row.currency} strong />,
    },
  ];

  const overview = overviewQuery.data;
  const pageData = claimsQuery.data;
  return (
    <>
      <PageHeader
        icon={<GavelRounded />}
        tint={ICON_TINTS["/settlement/claims"]}
        title={t("Reclamos de carga")}
        subtitle={t("Daños, faltantes, pérdidas y demoras reclamados al transportista, hasta que se recuperan.")}
        onRefresh={refresh}
        refreshing={claimsQuery.isFetching}
        actions={canManage ? (
          <Button variant="contained" startIcon={<AddRounded />} onClick={() => setForm({ kind: "open", source: null })}>
            {t("Nuevo reclamo")}
          </Button>
        ) : undefined}
      />

      <Box sx={{
        display: "grid", gap: 2, mb: 2,
        gridTemplateColumns: { xs: "1fr", sm: "repeat(2, minmax(0,1fr))", lg: "repeat(4, minmax(0,1fr))" },
      }}>
        <KpiCard
          icon={<PendingActionsRounded />} color="info.main" title={t("Activos")} loading={overviewQuery.isPending}
          value={fmtQuantity(overview?.activeCount ?? 0)}
          sub={t("{{open}} abiertos · {{review}} en revisión", {
            open: fmtQuantity(overview?.openCount ?? 0), review: fmtQuantity(overview?.underReviewCount ?? 0),
          })}
        />
        <KpiCard
          icon={<ReceiptLongRounded />} color="success.main" title={t("Por recuperar")} loading={overviewQuery.isPending}
          value={fmtQuantity(overview?.awaitingRecovery ?? 0)}
          sub={(overview?.currencies ?? []).filter((c) => c.acceptedPending > 0)
            .map((c) => `${fmtDecimal(c.acceptedPending, 2)} ${c.currency}`).join(" · ") || t("Nada aceptado pendiente")}
        />
        <KpiCard
          icon={<GavelRounded />} color="warning.main" title={t("Importe reclamado activo")} loading={overviewQuery.isPending}
          value={(overview?.currencies ?? []).map((c) => `${fmtDecimal(c.claimedAmount, 2)} ${c.currency}`).join(" · ") || "—"}
          sub={t("Cada moneda va aparte")}
        />
        <KpiCard
          icon={<HourglassTopRounded />} color="error.main" title={t("Antigüedad")} loading={overviewQuery.isPending}
          value={fmtQuantity((overview?.ageing.from31To60 ?? 0) + (overview?.ageing.over60 ?? 0))}
          sub={t("Más de 30 días · {{a}} hasta 7 · {{b}} de 8 a 30", {
            a: fmtQuantity(overview?.ageing.upTo7 ?? 0), b: fmtQuantity(overview?.ageing.from8To30 ?? 0),
          })}
        />
      </Box>

      <FilterBar
        value={filters}
        defaults={DEFAULTS}
        onChange={(next) => { setFilters(next); setPage(0); }}
        fields={[
          { type: "search", key: "search", placeholder: t("Número de reclamo") },
          {
            type: "select", key: "status", label: t("Estado"), allLabel: t("Todos"),
            options: [
              { id: "ACTIVE", label: t("Activos") },
              ...CLAIM_STATUSES.map((status) => ({ id: status, label: enumLabel("claimStatus", status) })),
            ],
          },
          {
            type: "select", key: "carrierId", label: t("Transportista"), allLabel: t("Todos"),
            options: (carriersQuery.data?.content ?? []).map((carrier) => ({ id: carrier.id, label: carrier.businessName })),
          },
          {
            type: "select", key: "claimType", label: t("Tipo"), allLabel: t("Todos"),
            options: CLAIM_TYPES.map((type) => ({ id: type, label: enumLabel("claimType", type) })),
          },
          { type: "dateRange", from: "from", to: "to", label: t("Abierto entre") },
        ]}
      />

      <DataTable
        columns={columns}
        rows={pageData?.content ?? []}
        rowKey={(row) => row.id}
        total={pageData?.totalElements}
        isLoading={claimsQuery.isPending && companyId !== ""}
        error={claimsQuery.isError ? describeApiError(claimsQuery.error as ApiError) : null}
        onRetry={() => void claimsQuery.refetch()}
        onRowClick={(row) => setOpenId(row.id)}
        emptyTitle={t("No hay reclamos con estos filtros")}
        emptyMessage={t("Se abre un reclamo desde una entrega con faltante, desde una incidencia o con «Nuevo reclamo».")}
        footer={pageData ? <Pagination page={pageData} onPageChange={setPage} /> : undefined}
      />

      {form && (
        <ClaimFormDrawer
          companyId={companyId}
          mode={form}
          onClose={() => setForm(null)}
          onSaved={(claim) => {
            notifySuccess(form.kind === "edit" ? t("Reclamo corregido") : t("Reclamo abierto"), claim.summary.claimNumber);
            setForm(null);
            setOpenId(claim.summary.id);
            void queryClient.invalidateQueries({ queryKey: ["freight-claim", companyId, claim.summary.id] });
            refresh();
          }}
        />
      )}

      {openId && !form && (
        <ClaimDetailDrawer
          companyId={companyId}
          claimId={openId}
          onClose={() => setOpenId(null)}
          onChanged={refresh}
          onEdit={(claim) => setForm({ kind: "edit", claim })}
        />
      )}
    </>
  );
}
