import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Alert, Box, Button, FormControlLabel, Switch, Typography } from "@mui/material";
import { AddRounded, BlockRounded, EditRounded, TuneRounded } from "@mui/icons-material";
import { CAPABILITY } from "../../shared/api/meApi";
import type { ApiError } from "../../shared/api/httpClient";
import { describeApiError } from "../../shared/api/problemMessages";
import {
  deactivateTolerancePolicy, fetchTolerancePolicies, type TolerancePolicyView,
} from "../../shared/api/settlementApi";
import { ActiveBadge, DataTable, PageHeader, type DataTableColumn } from "../../shared/ui/components";
import { ICON_TINTS } from "../../shared/ui/navConfig";
import { useCompany } from "../../shared/company/CompanyContext";
import { confirmDialog, notifyError, notifySuccess } from "../../lib/ui";
import { t } from "../../lib/i18n";
import { fmtDateTime, fmtDecimal } from "../../lib/locale";
import { TolerancePolicyFormDrawer } from "./TolerancePolicyFormDrawer";

/** Abierto para crear (`null`) o para editar una política concreta. */
type Editing = { policy: TolerancePolicyView | null } | null;

/**
 * Tolerancias de conciliación (fase 50).
 *
 * Una diferencia entre lo facturado y lo esperado se acepta sin abrir discrepancia si cabe en el
 * importe absoluto **o** en el porcentaje de la política que aplica. La de un transportista
 * desplaza a la de toda la empresa; sin ninguna, cualquier diferencia es una discrepancia.
 *
 * La comparación congela la tolerancia con la que se hizo: cambiar una política aquí no reescribe
 * por qué una factura ya comparada cuadró o no.
 */
export function TolerancePoliciesPage() {
  const { selected, hasCapability } = useCompany();
  const companyId = selected?.id ?? "";
  const queryClient = useQueryClient();
  // Esconder es sólo UX: el servidor exige `settlement.tolerance:manage` en cada escritura.
  const canManage = hasCapability(CAPABILITY.SETTLEMENT_MANAGE);
  const [includeInactive, setIncludeInactive] = useState(false);
  const [editing, setEditing] = useState<Editing>(null);
  const [busyId, setBusyId] = useState<string | null>(null);

  const policiesQuery = useQuery({
    queryKey: ["settlement-tolerance-policies", companyId, includeInactive],
    queryFn: ({ signal }) => fetchTolerancePolicies(companyId, includeInactive, signal),
    enabled: companyId !== "",
  });

  function refresh() {
    void queryClient.invalidateQueries({ queryKey: ["settlement-tolerance-policies", companyId] });
  }

  async function deactivate(policy: TolerancePolicyView) {
    const confirmed = await confirmDialog({
      title: t("¿Desactivar la tolerancia?"),
      text: policy.carrierId
        ? t("Las facturas de {{carrier}} pasarán a compararse con la tolerancia de toda la empresa, si existe. Las ya comparadas no cambian.", {
            carrier: policy.carrierName ?? t("este transportista"),
          })
        : t("Sin tolerancia de empresa, cualquier diferencia de un transportista sin política propia será una discrepancia. Las facturas ya comparadas no cambian."),
      confirmLabel: t("Sí, desactivar"),
      dangerous: true,
    });
    if (!confirmed) return;
    setBusyId(policy.id);
    try {
      await deactivateTolerancePolicy(companyId, policy.id);
      notifySuccess(t("Tolerancia desactivada"));
      refresh();
    } catch (error) {
      notifyError(t("No se pudo desactivar"), describeApiError(error as ApiError));
    } finally {
      setBusyId(null);
    }
  }

  const columns: DataTableColumn<TolerancePolicyView>[] = [
    {
      key: "scope",
      header: t("Ámbito"),
      render: (row) => row.carrierId === null ? (
        <Box>
          <Typography variant="body2" sx={{ fontWeight: 700 }}>{t("Toda la empresa")}</Typography>
          <Typography variant="caption" color="text.secondary">{t("Por defecto")}</Typography>
        </Box>
      ) : (
        <Typography variant="body2" sx={{ fontWeight: 700 }}>{row.carrierName ?? "—"}</Typography>
      ),
    },
    {
      key: "absoluteAmount",
      header: t("Importe absoluto"),
      numeric: true,
      render: (row) => row.absoluteAmount === null ? "—" : `${fmtDecimal(row.absoluteAmount, 2)} ${row.currency ?? ""}`.trim(),
    },
    {
      key: "percentage",
      header: t("Porcentaje"),
      numeric: true,
      render: (row) => row.percentage === null ? "—" : `${fmtDecimal(row.percentage, 2)} %`,
    },
    { key: "active", header: t("Estado"), render: (row) => <ActiveBadge active={row.active} /> },
    { key: "updatedAt", header: t("Actualizada"), render: (row) => fmtDateTime(row.updatedAt) },
    ...(canManage ? [{
      key: "actions",
      header: "",
      actions: true,
      render: (row: TolerancePolicyView) => row.active ? (
        <Box sx={{ display: "flex", gap: 0.5, justifyContent: "flex-end" }}>
          <Button
            size="small" startIcon={<EditRounded />} disabled={busyId === row.id}
            onClick={(event) => { event.stopPropagation(); setEditing({ policy: row }); }}
          >
            {t("Editar")}
          </Button>
          <Button
            size="small" color="error" startIcon={<BlockRounded />} disabled={busyId === row.id}
            onClick={(event) => { event.stopPropagation(); void deactivate(row); }}
          >
            {t("Desactivar")}
          </Button>
        </Box>
      ) : null,
    } satisfies DataTableColumn<TolerancePolicyView>] : []),
  ];

  return (
    <>
      <PageHeader
        icon={<TuneRounded />}
        tint={ICON_TINTS["/settlement/tolerances"]}
        title={t("Tolerancias de conciliación")}
        subtitle={t("Cuánta diferencia entre lo facturado y lo esperado se acepta sin abrir una discrepancia.")}
        onRefresh={refresh}
        refreshing={policiesQuery.isFetching}
        actions={
          <Box sx={{ display: "flex", gap: 1, alignItems: "center", flexWrap: "wrap" }}>
            <FormControlLabel
              control={<Switch size="small" checked={includeInactive} onChange={(e) => setIncludeInactive(e.target.checked)} />}
              label={t("Mostrar inactivas")}
            />
            {canManage && (
              <Button variant="contained" startIcon={<AddRounded />} onClick={() => setEditing({ policy: null })}>
                {t("Nueva tolerancia")}
              </Button>
            )}
          </Box>
        }
      />

      <Alert severity="info" variant="outlined" sx={{ mb: 2 }}>
        {t("Una diferencia se acepta si cabe en el importe absoluto o en el porcentaje. La política de un transportista desplaza a la de toda la empresa. Sin ninguna política, cualquier diferencia es una discrepancia.")}
      </Alert>

      <DataTable
        rows={policiesQuery.data ?? []}
        columns={columns}
        isLoading={policiesQuery.isLoading}
        error={policiesQuery.isError ? describeApiError(policiesQuery.error as ApiError) : null}
        onRetry={() => void policiesQuery.refetch()}
        rowKey={(row) => row.id}
        emptyTitle={t("No hay tolerancias configuradas")}
        emptyMessage={t("Sin tolerancia, cualquier diferencia con lo esperado se reporta como discrepancia.")}
      />

      {editing && (
        <TolerancePolicyFormDrawer
          companyId={companyId}
          policy={editing.policy}
          onClose={() => setEditing(null)}
          onSaved={() => {
            notifySuccess(editing.policy ? t("Tolerancia actualizada") : t("Tolerancia creada"));
            setEditing(null);
            refresh();
          }}
        />
      )}
    </>
  );
}
