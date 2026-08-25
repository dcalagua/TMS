import { keepPreviousData, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Box, Button, MenuItem, TextField, Typography } from "@mui/material";
import { AddRounded, BadgeRounded, EditRounded, BlockRounded, CheckCircleRounded } from "@mui/icons-material";
import type { ApiError } from "../../shared/api/httpClient";
import { fetchCarriers } from "../../shared/api/carriersApi";
import {
  activateDriver, deactivateDriver, DRIVER_LICENSE_STATUSES, fetchDrivers,
  type DriverLicenseStatus, type DriverView,
} from "../../shared/api/driversApi";
import { describeApiError } from "../../shared/api/problemMessages";
import { useCompany } from "../../shared/company/CompanyContext";
import {
  ActionMenu, ActiveBadge, DataTable, PageHeader, Pagination, StatusChip, Toolbar,
  type DataTableColumn,
} from "../../shared/ui/components";
import {
  ACTIVE_FILTER_OPTIONS, activeParam, notifySaved, toggleActiveRecord, type ActiveFilter,
} from "../../shared/ui/masterActions";
import { ICON_TINTS } from "../../shared/ui/navConfig";
import { enumLabel } from "../../lib/enums";
import type { StatusTone } from "../../theme";
import { t } from "../../lib/i18n";
import { fmtDate } from "../../lib/locale";
import { DriverFormDrawer } from "./DriverFormDrawer";

const PAGE_SIZE = 25;

/**
 * El estado de la licencia lo deriva el backend, y el color solo lo presenta.
 *
 * "Por vencer" es ámbar y no rojo a propósito: el conductor todavía puede salir, y teñirlo de
 * rojo junto a los que ya no pueden haría que la lista dejara de distinguir un aviso de un
 * bloqueo. "Sin registrar" es neutro: hay operaciones que no llevan esa fecha, y eso no es un
 * problema por sí mismo.
 */
const LICENSE_TONE: Record<DriverLicenseStatus, StatusTone> = {
  VALID: "done",
  EXPIRING_SOON: "inProgress",
  EXPIRED: "overdue",
  UNRECORDED: "neutral",
};

interface AppliedFilters {
  code: string;
  name: string;
  carrierId: string;
  licenseStatus: DriverLicenseStatus | "";
  active: ActiveFilter;
}

const DEFAULT_FILTERS: AppliedFilters = { code: "", name: "", carrierId: "", licenseStatus: "", active: "active" };

type ModalState = { mode: "create" } | { mode: "edit"; driver: DriverView } | null;

export function DriversPage() {
  const { selected, hasPermission } = useCompany();
  const companyId = selected?.id ?? "";
  const canManage = hasPermission("fleet.driver:manage");
  const queryClient = useQueryClient();

  const [page, setPage] = useState(0);
  const [draft, setDraft] = useState<AppliedFilters>(DEFAULT_FILTERS);
  const [filters, setFilters] = useState<AppliedFilters>(DEFAULT_FILTERS);
  const [modal, setModal] = useState<ModalState>(null);

  const driversQuery = useQuery({
    queryKey: ["drivers", companyId, page, filters],
    queryFn: ({ signal }) =>
      fetchDrivers({
        companyId,
        page,
        size: PAGE_SIZE,
        sort: "code,asc",
        code: filters.code || undefined,
        name: filters.name || undefined,
        carrierId: filters.carrierId || undefined,
        licenseStatus: filters.licenseStatus || undefined,
        active: activeParam(filters.active),
        signal,
      }),
    placeholderData: keepPreviousData,
  });

  const carriersQuery = useQuery({
    queryKey: ["carriers-for-filter", companyId],
    queryFn: ({ signal }) => fetchCarriers({ companyId, size: 200, active: true, sort: "code,asc", signal }),
    enabled: companyId !== "",
  });

  const refresh = () => void queryClient.invalidateQueries({ queryKey: ["drivers", companyId] });

  function applyFilters() { setFilters(draft); setPage(0); }
  function resetFilters() { setDraft(DEFAULT_FILTERS); setFilters(DEFAULT_FILTERS); setPage(0); }

  async function toggleActive(driver: DriverView) {
    const changed = await toggleActiveRecord({
      name: driver.fullName,
      active: driver.active,
      activate: () => activateDriver(companyId, driver.id),
      deactivate: () => deactivateDriver(companyId, driver.id),
    });
    if (changed) refresh();
  }

  const columns: DataTableColumn<DriverView>[] = [
    { key: "code", header: t("Código"), render: (d) => <Typography variant="body2" sx={{ fontWeight: 700 }}>{d.code}</Typography> },
    { key: "name", header: t("Nombre"), render: (d) => d.fullName },
    {
      key: "document",
      header: t("Documento"),
      render: (d) => (
        <Box>
          <Typography variant="body2" sx={{ fontVariantNumeric: "tabular-nums" }}>{d.documentNumber}</Typography>
          <Typography variant="caption" color="text.secondary">{d.documentType}</Typography>
        </Box>
      ),
    },
    { key: "carrier", header: t("Transportista"), render: (d) => d.carrierBusinessName ?? t("Flota propia") },
    {
      key: "license",
      header: t("Licencia"),
      render: (d) => (
        <Box sx={{ display: "flex", flexDirection: "column", gap: 0.4, alignItems: "flex-start" }}>
          <StatusChip tone={LICENSE_TONE[d.licenseStatus]} label={enumLabel("driverLicenseStatus", d.licenseStatus)} />
          {d.licenseExpiresOn && (
            <Typography variant="caption" color="text.secondary">{fmtDate(d.licenseExpiresOn)}</Typography>
          )}
        </Box>
      ),
    },
    { key: "active", header: t("Estado"), render: (d) => <ActiveBadge active={d.active} /> },
  ];

  if (canManage) {
    columns.push({
      key: "actions",
      header: t("Acciones"),
      actions: true,
      render: (driver) => (
        <ActionMenu
          items={[
            { key: "edit", label: t("Editar"), icon: <EditRounded />, onSelect: () => setModal({ mode: "edit", driver }) },
            {
              key: "active",
              label: driver.active ? t("Desactivar") : t("Activar"),
              icon: driver.active ? <BlockRounded /> : <CheckCircleRounded />,
              dangerous: driver.active,
              onSelect: () => void toggleActive(driver),
            },
          ]}
        />
      ),
    });
  }

  const pageData = driversQuery.data;

  return (
    <>
      <PageHeader
        icon={<BadgeRounded />}
        tint={ICON_TINTS["/fleet/drivers"]}
        title={t("Conductores")}
        subtitle={t("Las personas que conducen, con el estado de su licencia al día.")}
        onRefresh={refresh}
        refreshing={driversQuery.isFetching}
        actions={canManage && (
          <Button variant="contained" startIcon={<AddRounded />} onClick={() => setModal({ mode: "create" })}>
            {t("Nuevo conductor")}
          </Button>
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
              sx={{ minWidth: 140 }}
            />
            <TextField
              size="small" label={t("Nombre")} value={draft.name}
              onChange={(e) => setDraft({ ...draft, name: e.target.value })}
              sx={{ minWidth: 200 }}
            />
            <TextField
              select size="small" label={t("Transportista")} value={draft.carrierId}
              onChange={(e) => setDraft({ ...draft, carrierId: e.target.value })}
              sx={{ minWidth: 200 }}
            >
              <MenuItem value="">{t("Todos los transportistas")}</MenuItem>
              {(carriersQuery.data?.content ?? []).map((carrier) => (
                <MenuItem key={carrier.id} value={carrier.id}>{carrier.businessName}</MenuItem>
              ))}
            </TextField>
            <TextField
              select size="small" label={t("Licencia")} value={draft.licenseStatus}
              onChange={(e) => setDraft({ ...draft, licenseStatus: e.target.value as DriverLicenseStatus | "" })}
              sx={{ minWidth: 180 }}
            >
              <MenuItem value="">{t("Todos")}</MenuItem>
              {DRIVER_LICENSE_STATUSES.map((status) => (
                <MenuItem key={status} value={status}>{enumLabel("driverLicenseStatus", status)}</MenuItem>
              ))}
            </TextField>
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
        rowKey={(driver) => driver.id}
        isLoading={driversQuery.isPending}
        error={driversQuery.isError ? describeApiError(driversQuery.error as ApiError) : null}
        onRetry={() => void driversQuery.refetch()}
        emptyTitle={t("Sin conductores")}
        emptyMessage={t("Crea un conductor o ajusta los filtros.")}
        footer={pageData ? <Pagination page={pageData} onPageChange={setPage} /> : undefined}
      />

      {modal && (
        <DriverFormDrawer
          companyId={companyId}
          driver={modal.mode === "edit" ? modal.driver : null}
          onClose={() => setModal(null)}
          onSaved={() => {
            const wasEdit = modal.mode === "edit";
            setModal(null);
            notifySaved(wasEdit);
            refresh();
          }}
        />
      )}
    </>
  );
}
