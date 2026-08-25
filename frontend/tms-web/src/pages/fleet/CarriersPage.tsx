import { keepPreviousData, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import {
  Box, Button, MenuItem, Paper, Table, TableBody, TableCell, TableContainer,
  TableHead, TableRow, TextField, Typography,
} from "@mui/material";
import {
  AddRounded, UploadRounded, BusinessRounded, EditRounded, BlockRounded, CheckCircleRounded,
} from "@mui/icons-material";
import type { ApiError } from "../../shared/api/httpClient";
import {
  activateCarrier, CARRIER_IMPORT_BASE_PATH, deactivateCarrier, fetchCarriers,
  type CarrierImportPreview, type CarrierView,
} from "../../shared/api/carriersApi";
import { describeApiError } from "../../shared/api/problemMessages";
import { useCompany } from "../../shared/company/CompanyContext";
import {
  ActionMenu, ActiveBadge, DataTable, ImportDrawer, ImportOutcomeChip, PageHeader,
  Pagination, Toolbar, dataTableSx, type DataTableColumn,
} from "../../shared/ui/components";
import {
  ACTIVE_FILTER_OPTIONS, activeParam, notifySaved, toggleActiveRecord, type ActiveFilter,
} from "../../shared/ui/masterActions";
import { ICON_TINTS } from "../../shared/ui/navConfig";
import { t } from "../../lib/i18n";
import { CarrierFormDrawer } from "./CarrierFormDrawer";

const PAGE_SIZE = 25;

interface AppliedFilters {
  code: string;
  businessName: string;
  taxIdValue: string;
  active: ActiveFilter;
}

const DEFAULT_FILTERS: AppliedFilters = { code: "", businessName: "", taxIdValue: "", active: "active" };

type ModalState = { mode: "create" } | { mode: "edit"; carrier: CarrierView } | null;

export function CarriersPage() {
  const { selected, hasPermission } = useCompany();
  const companyId = selected?.id ?? "";
  const canManage = hasPermission("fleet.carrier:manage");
  const queryClient = useQueryClient();

  const [page, setPage] = useState(0);
  const [draft, setDraft] = useState<AppliedFilters>(DEFAULT_FILTERS);
  const [filters, setFilters] = useState<AppliedFilters>(DEFAULT_FILTERS);
  const [modal, setModal] = useState<ModalState>(null);
  const [showImport, setShowImport] = useState(false);

  const carriersQuery = useQuery({
    queryKey: ["carriers", companyId, page, filters],
    queryFn: ({ signal }) =>
      fetchCarriers({
        companyId,
        page,
        size: PAGE_SIZE,
        sort: "code,asc",
        code: filters.code || undefined,
        businessName: filters.businessName || undefined,
        taxIdValue: filters.taxIdValue || undefined,
        active: activeParam(filters.active),
        signal,
      }),
    placeholderData: keepPreviousData,
  });

  const refresh = () => void queryClient.invalidateQueries({ queryKey: ["carriers", companyId] });

  function applyFilters() { setFilters(draft); setPage(0); }
  function resetFilters() { setDraft(DEFAULT_FILTERS); setFilters(DEFAULT_FILTERS); setPage(0); }

  async function toggleActive(carrier: CarrierView) {
    const changed = await toggleActiveRecord({
      name: carrier.businessName,
      active: carrier.active,
      activate: () => activateCarrier(companyId, carrier.id),
      deactivate: () => deactivateCarrier(companyId, carrier.id),
    });
    if (changed) refresh();
  }

  const columns: DataTableColumn<CarrierView>[] = [
    { key: "code", header: t("Código"), render: (c) => <Typography variant="body2" sx={{ fontWeight: 700 }}>{c.code}</Typography> },
    { key: "businessName", header: t("Razón social"), render: (c) => c.businessName },
    {
      key: "taxId",
      header: t("RUC"),
      render: (c) => (
        <Box>
          <Typography variant="body2" sx={{ fontVariantNumeric: "tabular-nums" }}>{c.taxIdValue}</Typography>
          <Typography variant="caption" color="text.secondary">{c.taxIdType}</Typography>
        </Box>
      ),
    },
    {
      key: "contact",
      header: t("Contacto"),
      // Nombre, teléfono y correo en una celda: son un solo hecho —a quién se llama— y tres
      // columnas medio vacías por él ensancharían la tabla sin decir más.
      render: (c) => (
        <Box sx={{ minWidth: 0 }}>
          {c.contactName && <Typography variant="body2" sx={{ lineHeight: 1.3 }}>{c.contactName}</Typography>}
          <Typography variant="caption" color="text.secondary">
            {[c.phone, c.email].filter(Boolean).join(" · ") || "-"}
          </Typography>
        </Box>
      ),
    },
    { key: "active", header: t("Estado"), render: (c) => <ActiveBadge active={c.active} /> },
  ];

  if (canManage) {
    columns.push({
      key: "actions",
      header: t("Acciones"),
      actions: true,
      render: (carrier) => (
        <ActionMenu
          items={[
            { key: "edit", label: t("Editar"), icon: <EditRounded />, onSelect: () => setModal({ mode: "edit", carrier }) },
            {
              key: "active",
              label: carrier.active ? t("Desactivar") : t("Activar"),
              icon: carrier.active ? <BlockRounded /> : <CheckCircleRounded />,
              dangerous: carrier.active,
              onSelect: () => void toggleActive(carrier),
            },
          ]}
        />
      ),
    });
  }

  const pageData = carriersQuery.data;

  return (
    <>
      <PageHeader
        icon={<BusinessRounded />}
        tint={ICON_TINTS["/fleet/carriers"]}
        title={t("Transportistas")}
        subtitle={t("Las empresas que ponen los vehículos y los conductores de la operación.")}
        onRefresh={refresh}
        refreshing={carriersQuery.isFetching}
        actions={canManage && (
          <>
            <Button variant="outlined" startIcon={<UploadRounded />} onClick={() => setShowImport(true)}>
              {t("Importar")}
            </Button>
            <Button variant="contained" startIcon={<AddRounded />} onClick={() => setModal({ mode: "create" })}>
              {t("Nuevo transportista")}
            </Button>
          </>
        )}
      />

      <Toolbar
        onApply={applyFilters}
        onReset={resetFilters}
        filters={
          <>
            <TextField
              size="small" label={t("Código")} value={draft.code}
              onChange={(e) => setDraft({ ...draft, code: e.target.value })}
              sx={{ minWidth: 150 }}
            />
            <TextField
              size="small" label={t("Razón social")} value={draft.businessName}
              onChange={(e) => setDraft({ ...draft, businessName: e.target.value })}
              sx={{ minWidth: 220 }}
            />
            <TextField
              size="small" label={t("RUC")} value={draft.taxIdValue}
              onChange={(e) => setDraft({ ...draft, taxIdValue: e.target.value })}
              sx={{ minWidth: 160 }}
            />
            <TextField
              select size="small" label={t("Estado")} value={draft.active}
              onChange={(e) => setDraft({ ...draft, active: e.target.value as ActiveFilter })}
              sx={{ minWidth: 150 }}
            >
              {ACTIVE_FILTER_OPTIONS.map((option) => (
                <MenuItem key={option.value} value={option.value}>{t(option.label)}</MenuItem>
              ))}
            </TextField>
          </>
        }
      />

      <DataTable
        columns={columns}
        rows={pageData?.content ?? []}
        total={pageData?.totalElements}
        rowKey={(carrier) => carrier.id}
        isLoading={carriersQuery.isPending}
        error={carriersQuery.isError ? describeApiError(carriersQuery.error as ApiError) : null}
        onRetry={() => void carriersQuery.refetch()}
        emptyTitle={t("Sin transportistas")}
        emptyMessage={t("Crea un transportista o ajusta los filtros.")}
        footer={pageData ? <Pagination page={pageData} onPageChange={setPage} /> : undefined}
      />

      {modal && (
        <CarrierFormDrawer
          companyId={companyId}
          carrier={modal.mode === "edit" ? modal.carrier : null}
          onClose={() => setModal(null)}
          onSaved={() => {
            const wasEdit = modal.mode === "edit";
            setModal(null);
            notifySaved(wasEdit);
            refresh();
          }}
        />
      )}

      {showImport && (
        <ImportDrawer<CarrierImportPreview>
          open
          apiBasePath={CARRIER_IMPORT_BASE_PATH}
          companyId={companyId}
          title={t("Importar transportistas")}
          subtitle={t("Alta masiva desde una plantilla .xlsx o .csv.")}
          onClose={() => setShowImport(false)}
          onImported={refresh}
          renderItems={(items, outcomeLabel) => (
            <TableContainer component={Paper} variant="outlined" sx={{ maxHeight: 340 }}>
              <Table size="small" stickyHeader sx={dataTableSx}>
                <TableHead>
                  <TableRow>
                    <TableCell>{t("Estado")}</TableCell>
                    <TableCell>{t("Código")}</TableCell>
                    <TableCell>{t("Razón social")}</TableCell>
                    <TableCell>{t("Documento")}</TableCell>
                    <TableCell>{t("Contacto")}</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {items.map((item, index) => (
                    <TableRow key={`${item.code}-${index}`}>
                      <TableCell><ImportOutcomeChip outcome={item.outcome} label={outcomeLabel(item.outcome)} /></TableCell>
                      <TableCell sx={{ fontWeight: 700 }}>{item.code}</TableCell>
                      <TableCell>{item.businessName}</TableCell>
                      <TableCell>{[item.taxIdType, item.taxIdValue].filter(Boolean).join(" ") || "-"}</TableCell>
                      <TableCell>{[item.contactName, item.phone, item.email].filter(Boolean).join(" · ") || "-"}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </TableContainer>
          )}
        />
      )}
    </>
  );
}
