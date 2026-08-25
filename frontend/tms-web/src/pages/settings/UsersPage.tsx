import { keepPreviousData, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Box, Button, Chip, MenuItem, TextField, Typography } from "@mui/material";
import {
  PersonAddRounded, GroupsRounded, EditRounded, BlockRounded, CheckCircleRounded,
} from "@mui/icons-material";
import type { ApiError } from "../../shared/api/httpClient";
import {
  fetchAdministeredUsers, fetchAssignableRoles, restoreUserAccess, revokeUserAccess,
  type AdministeredUserView,
} from "../../shared/api/administrationApi";
import { describeApiError } from "../../shared/api/problemMessages";
import { useCompany } from "../../shared/company/CompanyContext";
import {
  ActionMenu, ActiveBadge, DataTable, PageHeader, Pagination, StatusChip, Toolbar,
  type DataTableColumn,
} from "../../shared/ui/components";
import { ACTIVE_FILTER_OPTIONS, activeParam, type ActiveFilter } from "../../shared/ui/masterActions";
import { ICON_TINTS } from "../../shared/ui/navConfig";
import { confirmDialog, notifyError, notifySuccess } from "../../lib/ui";
import { t } from "../../lib/i18n";
import { fmtDate } from "../../lib/locale";
import { UserFormDrawer } from "./UserFormDrawer";

const PAGE_SIZE = 25;

type ModalState = { mode: "invite" } | { mode: "edit"; user: AdministeredUserView } | null;

/**
 * Quién puede actuar en esta empresa y con qué roles.
 *
 * Lo que se lista son *membresías*, no cuentas: la misma persona puede aparecer en varias
 * empresas, y lo que se revoca aquí es su acceso a esta.
 *
 * La cuenta desactivada a nivel de instalación se enseña pero no se toca: explica por qué alguien
 * con membresía activa no puede entrar, y arreglarlo no es asunto de esta pantalla.
 */
export function UsersPage() {
  const { selected, hasPermission } = useCompany();
  const companyId = selected?.id ?? "";
  const canManage = hasPermission("iam.user:manage");
  const queryClient = useQueryClient();

  const [page, setPage] = useState(0);
  const [draft, setDraft] = useState({ search: "", active: "active" as ActiveFilter });
  const [filters, setFilters] = useState({ search: "", active: "active" as ActiveFilter });
  const [modal, setModal] = useState<ModalState>(null);

  const usersQuery = useQuery({
    queryKey: ["admin-users", companyId, page, filters],
    queryFn: ({ signal }) =>
      fetchAdministeredUsers({
        companyId,
        page,
        size: PAGE_SIZE,
        sort: "fullName,asc",
        search: filters.search || undefined,
        active: activeParam(filters.active),
        signal,
      }),
    enabled: companyId !== "",
    placeholderData: keepPreviousData,
  });

  const rolesQuery = useQuery({
    queryKey: ["assignable-roles", companyId],
    queryFn: ({ signal }) => fetchAssignableRoles(companyId, signal),
    enabled: companyId !== "",
  });

  /** El código de rol traducido a su nombre. Un código crudo en una tabla de personas no le dice
   * nada a quien administra. */
  const roleName = (code: string) => rolesQuery.data?.find((role) => role.code === code)?.name ?? code;

  const refresh = () => void queryClient.invalidateQueries({ queryKey: ["admin-users", companyId] });

  function applyFilters() { setFilters(draft); setPage(0); }
  function resetFilters() {
    setDraft({ search: "", active: "active" });
    setFilters({ search: "", active: "active" });
    setPage(0);
  }

  async function toggleAccess(user: AdministeredUserView) {
    const confirmed = await confirmDialog({
      title: user.membershipActive
        ? t("¿Revocar el acceso de {{name}}?", { name: user.fullName })
        : t("¿Restaurar el acceso de {{name}}?", { name: user.fullName }),
      text: user.membershipActive
        ? t("Dejará de poder entrar a esta empresa. Se puede restaurar después.")
        : t("Volverá a poder entrar a esta empresa con los roles que tenía."),
      confirmLabel: user.membershipActive ? t("Revocar") : t("Restaurar"),
      dangerous: user.membershipActive,
    });
    if (!confirmed) return;

    try {
      if (user.membershipActive) await revokeUserAccess(companyId, user.membershipId);
      else await restoreUserAccess(companyId, user.membershipId);
      notifySuccess(user.membershipActive ? t("Acceso revocado") : t("Acceso restaurado"), user.fullName);
      refresh();
    } catch (error) {
      notifyError(t("No se pudo completar la acción"), describeApiError(error as ApiError));
    }
  }

  const columns: DataTableColumn<AdministeredUserView>[] = [
    {
      key: "person",
      header: t("Nombre"),
      render: (user) => (
        <Box sx={{ minWidth: 0 }}>
          <Typography variant="body2" sx={{ fontWeight: 700, lineHeight: 1.3 }}>{user.fullName}</Typography>
          <Typography variant="caption" color="text.secondary">{user.email}</Typography>
        </Box>
      ),
    },
    {
      key: "roles",
      header: t("Roles"),
      render: (user) => (
        <Box sx={{ display: "flex", flexWrap: "wrap", gap: 0.5 }}>
          {user.roleCodes.map((code) => (
            <Chip key={code} size="small" variant="outlined" label={roleName(code)} />
          ))}
          {/* Un rol de organización alcanza a todas las empresas: decirlo evita que alguien
              intente quitarlo desde aquí y no entienda por qué no se puede. */}
          {user.organizationWide && (
            <Chip size="small" color="info" label={t("Organización")} />
          )}
        </Box>
      ),
    },
    { key: "since", header: t("Desde"), render: (user) => fmtDate(user.createdAt) },
    {
      key: "status",
      header: t("Estado"),
      render: (user) => (
        <Box sx={{ display: "flex", gap: 0.5, flexWrap: "wrap" }}>
          <ActiveBadge active={user.membershipActive} />
          {/* La cuenta desactivada a nivel de instalación explica por qué alguien con membresía
              activa no puede entrar. Se enseña; no se toca desde aquí. */}
          {!user.userActive && <StatusChip label={t("Cuenta deshabilitada")} tone="overdue" />}
        </Box>
      ),
    },
  ];

  if (canManage) {
    columns.push({
      key: "actions",
      header: t("Acciones"),
      actions: true,
      render: (user) => (
        <ActionMenu
          items={[
            { key: "edit", label: t("Editar"), icon: <EditRounded />, onSelect: () => setModal({ mode: "edit", user }) },
            {
              key: "access",
              label: user.membershipActive ? t("Revocar acceso") : t("Restaurar acceso"),
              icon: user.membershipActive ? <BlockRounded /> : <CheckCircleRounded />,
              dangerous: user.membershipActive,
              divider: true,
              onSelect: () => void toggleAccess(user),
            },
          ]}
        />
      ),
    });
  }

  const pageData = usersQuery.data;

  return (
    <>
      <PageHeader
        icon={<GroupsRounded />}
        tint={ICON_TINTS["/settings/users"]}
        title={t("Usuarios y accesos")}
        subtitle={t("Quién puede actuar en esta empresa y con qué roles.")}
        onRefresh={refresh}
        refreshing={usersQuery.isFetching}
        actions={canManage && (
          <Button variant="contained" startIcon={<PersonAddRounded />} onClick={() => setModal({ mode: "invite" })}>
            {t("Invitar")}
          </Button>
        )}
      />

      <Toolbar
        onApply={applyFilters}
        onReset={resetFilters}
        filters={
          <>
            {/* Un solo cuadro sobre nombre y correo: así es como se busca a una persona. */}
            <TextField
              size="small" type="search" label={t("Buscar")} value={draft.search}
              placeholder={t("Nombre o correo")}
              onChange={(e) => setDraft({ ...draft, search: e.target.value })}
              sx={{ minWidth: 260, flex: 1 }}
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
        rowKey={(user) => user.membershipId}
        isLoading={usersQuery.isPending}
        error={usersQuery.isError ? describeApiError(usersQuery.error as ApiError) : null}
        onRetry={() => void usersQuery.refetch()}
        emptyTitle={t("Sin usuarios")}
        emptyMessage={t("Invita a alguien o ajusta los filtros.")}
        footer={pageData ? <Pagination page={pageData} onPageChange={setPage} /> : undefined}
      />

      {modal && (
        <UserFormDrawer
          companyId={companyId}
          user={modal.mode === "edit" ? modal.user : null}
          onClose={() => setModal(null)}
          onSaved={() => {
            const wasEdit = modal.mode === "edit";
            setModal(null);
            notifySuccess(wasEdit ? t("Registro actualizado") : t("Invitación enviada"));
            refresh();
          }}
        />
      )}
    </>
  );
}
