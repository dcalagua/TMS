import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Alert, Box, Button, FormControlLabel, MenuItem, Switch, TextField, Typography } from "@mui/material";
import { AddRounded, BlockRounded, EditRounded } from "@mui/icons-material";
import { CAPABILITY } from "../../shared/api/meApi";
import {
  GL_CONCEPTS, createGlMapping, deactivateGlMapping, fetchGlMappings, updateGlMapping,
  type GlAccountMappingView, type GlConcept, type GlScopeType,
} from "../../shared/api/accrualApi";
import { fetchOrigins } from "../../shared/api/originsApi";
import { WITHHOLDING_KINDS, fetchFiscalSettings, type WithholdingKind } from "../../shared/api/settlementApi";
import { fetchRoutes } from "../../shared/api/routesApi";
import type { ApiError } from "../../shared/api/httpClient";
import { describeApiError } from "../../shared/api/problemMessages";
import { ActiveBadge, DataTable, FormDrawer, type DataTableColumn } from "../../shared/ui/components";
import { useCompany } from "../../shared/company/CompanyContext";
import { confirmDialog, notifyError, notifySuccess } from "../../lib/ui";
import { enumLabel } from "../../lib/enums";
import { t } from "../../lib/i18n";
import { fmtDateTime } from "../../lib/locale";

type Editing = { mapping: GlAccountMappingView | null } | null;

/**
 * Cuentas contables del asiento de provisiones (V65, ADR-023). La más específica gana: ruta, luego
 * origen, luego toda la empresa. Sin ninguna, el asiento deja la cuenta vacía y lo avisa.
 */
export function GlMappingsTab({ companyId }: { companyId: string }) {
  const { hasCapability } = useCompany();
  // Esconder es sólo UX: el servidor exige `settlement.accrual:manage` en cada escritura.
  const canManage = hasCapability(CAPABILITY.ACCRUAL_MANAGE);
  const queryClient = useQueryClient();
  const [includeInactive, setIncludeInactive] = useState(false);
  const [editing, setEditing] = useState<Editing>(null);
  const [busyId, setBusyId] = useState<string | null>(null);

  const mappingsQuery = useQuery({
    queryKey: ["settlement-gl-mappings", companyId, includeInactive],
    queryFn: ({ signal }) => fetchGlMappings(companyId, includeInactive, signal),
  });

  function refresh() {
    void queryClient.invalidateQueries({ queryKey: ["settlement-gl-mappings", companyId] });
  }

  async function deactivate(mapping: GlAccountMappingView) {
    const confirmed = await confirmDialog({
      title: t("¿Retirar la cuenta?"),
      text: t("Los asientos usarán la siguiente cuenta más general que exista para {{concept}}. Los archivos ya generados no cambian.", {
        concept: enumLabel("glConcept", mapping.concept),
      }),
      confirmLabel: t("Sí, retirar"),
      dangerous: true,
    });
    if (!confirmed) return;
    setBusyId(mapping.id);
    try {
      await deactivateGlMapping(companyId, mapping.id);
      notifySuccess(t("Cuenta retirada"));
      refresh();
    } catch (error) {
      notifyError(t("No se pudo retirar"), describeApiError(error as ApiError));
    } finally {
      setBusyId(null);
    }
  }

  const columns: DataTableColumn<GlAccountMappingView>[] = [
    {
      key: "concept",
      header: t("Concepto"),
      render: (row) => (
        <Box>
          <Typography variant="body2">{enumLabel("glConcept", row.concept)}</Typography>
          {row.concept === "WITHHOLDING_PAYABLE" && (
            <Typography variant="caption" color="text.secondary">
              {row.withholdingKind ? enumLabel("withholdingKind", row.withholdingKind) : t("Toda retención sin cuenta propia")}
            </Typography>
          )}
        </Box>
      ),
    },
    {
      key: "scope",
      header: t("Ámbito"),
      render: (row) => (
        <Box>
          <Typography variant="body2" sx={{ fontWeight: 600 }}>{enumLabel("glScopeType", row.scopeType)}</Typography>
          {row.scopeName && <Typography variant="caption" color="text.secondary">{row.scopeName}</Typography>}
        </Box>
      ),
    },
    { key: "account", header: t("Cuenta"), render: (row) => <Typography variant="body2" sx={{ fontWeight: 700 }}>{row.accountCode}</Typography> },
    { key: "costCenter", header: t("Centro de costo"), render: (row) => row.costCenterCode ?? "—" },
    { key: "active", header: t("Estado"), render: (row) => <ActiveBadge active={row.active} /> },
    { key: "updatedAt", header: t("Actualizada"), render: (row) => fmtDateTime(row.updatedAt) },
    ...(canManage ? [{
      key: "actions",
      header: "",
      actions: true,
      render: (row: GlAccountMappingView) => row.active ? (
        <Box sx={{ display: "flex", gap: 0.5, justifyContent: "flex-end" }}>
          <Button size="small" startIcon={<EditRounded />} disabled={busyId === row.id}
            onClick={(event) => { event.stopPropagation(); setEditing({ mapping: row }); }}>
            {t("Editar")}
          </Button>
          <Button size="small" color="error" startIcon={<BlockRounded />} disabled={busyId === row.id}
            onClick={(event) => { event.stopPropagation(); void deactivate(row); }}>
            {t("Retirar")}
          </Button>
        </Box>
      ) : null,
    } satisfies DataTableColumn<GlAccountMappingView>] : []),
  ];

  return (
    <>
      <Box sx={{ display: "flex", gap: 1, alignItems: "center", flexWrap: "wrap", mb: 2 }}>
        <FormControlLabel
          control={<Switch size="small" checked={includeInactive} onChange={(e) => setIncludeInactive(e.target.checked)} />}
          label={t("Mostrar retiradas")}
        />
        <Box sx={{ flex: 1 }} />
        {canManage && (
          <Button variant="contained" startIcon={<AddRounded />} onClick={() => setEditing({ mapping: null })}>
            {t("Nueva cuenta")}
          </Button>
        )}
      </Box>
      <Alert severity="info" variant="outlined" sx={{ mb: 2 }}>
        {t("La provisión se registra contra Gasto de flete y Provisión de flete; el consumo, contra la Cuenta por pagar al transportista. La cuenta más específica gana: ruta, luego origen, luego toda la empresa.")}
      </Alert>
      <DataTable
        rows={mappingsQuery.data ?? []}
        columns={columns}
        isLoading={mappingsQuery.isLoading}
        error={mappingsQuery.isError ? describeApiError(mappingsQuery.error as ApiError) : null}
        onRetry={() => void mappingsQuery.refetch()}
        rowKey={(row) => row.id}
        emptyTitle={t("No hay cuentas contables configuradas")}
        emptyMessage={t("Sin cuentas, el asiento sale con las cuentas vacías y un aviso por concepto.")}
      />
      {editing && (
        <GlMappingFormDrawer
          companyId={companyId}
          mapping={editing.mapping}
          onClose={() => setEditing(null)}
          onSaved={() => {
            notifySuccess(editing.mapping ? t("Cuenta actualizada") : t("Cuenta creada"));
            setEditing(null);
            refresh();
          }}
        />
      )}
    </>
  );
}

interface GlMappingFormDrawerProps {
  companyId: string;
  mapping: GlAccountMappingView | null;
  onClose: () => void;
  onSaved: () => void;
}

/** Concepto y ámbito definen la cuenta: al editar sólo cambian el código y el centro de costo. */
export function GlMappingFormDrawer({ companyId, mapping, onClose, onSaved }: GlMappingFormDrawerProps) {
  const editing = mapping !== null;
  const [concept, setConcept] = useState<GlConcept>(mapping?.concept ?? "FREIGHT_EXPENSE");
  const [withholdingKind, setWithholdingKind] = useState<WithholdingKind | "">(mapping?.withholdingKind ?? "");
  const [scopeType, setScopeType] = useState<GlScopeType>(mapping?.scopeType ?? "COMPANY");
  const [scopeId, setScopeId] = useState(mapping?.scopeId ?? "");
  const [accountCode, setAccountCode] = useState(mapping?.accountCode ?? "");
  const [costCenterCode, setCostCenterCode] = useState(mapping?.costCenterCode ?? "");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Las retenciones del régimen de la empresa; sin él (o sin permiso), todas las que conoce el producto.
  const regimeQuery = useQuery({
    queryKey: ["fiscal-settings", companyId],
    queryFn: ({ signal }) => fetchFiscalSettings(companyId, signal),
    enabled: !editing && concept === "WITHHOLDING_PAYABLE",
    retry: false,
  });
  const regimeKinds = regimeQuery.data?.withholdings.map((rule) => rule.kind);
  const withholdingKinds: readonly WithholdingKind[] = regimeKinds && regimeKinds.length > 0 ? regimeKinds : WITHHOLDING_KINDS;

  const originsQuery = useQuery({
    queryKey: ["origins-lookup", companyId],
    queryFn: ({ signal }) => fetchOrigins({ companyId, size: 200, active: true, signal }),
    enabled: !editing && scopeType === "ORIGIN",
    retry: false,
  });
  const routesQuery = useQuery({
    queryKey: ["routes-lookup", companyId],
    queryFn: ({ signal }) => fetchRoutes({ companyId, size: 200, active: true, signal }),
    enabled: !editing && scopeType === "ROUTE",
    retry: false,
  });
  const scopeOptions = scopeType === "ORIGIN"
    ? (originsQuery.data?.content ?? []).map((origin) => ({ id: origin.id, label: `${origin.code} - ${origin.name}` }))
    : (routesQuery.data?.content ?? []).map((route) => ({ id: route.id, label: `${route.code} - ${route.name}` }));

  const missingScope = scopeType !== "COMPANY" && scopeId === "";
  const valid = accountCode.trim() !== "" && accountCode.trim().length <= 40 && costCenterCode.trim().length <= 40
    && !missingScope;

  async function save() {
    setSaving(true);
    setError(null);
    const request = {
      concept,
      withholdingKind: concept === "WITHHOLDING_PAYABLE" && withholdingKind !== "" ? withholdingKind : null,
      scopeType,
      scopeId: scopeType === "COMPANY" ? null : scopeId,
      accountCode: accountCode.trim(),
      costCenterCode: costCenterCode.trim() || null,
    };
    try {
      if (mapping) {
        await updateGlMapping(companyId, mapping.id, request);
      } else {
        await createGlMapping(companyId, request);
      }
      onSaved();
    } catch (failure) {
      setError(describeApiError(failure as ApiError));
    } finally {
      setSaving(false);
    }
  }

  return (
    <FormDrawer
      open
      title={editing ? t("Editar cuenta contable") : t("Nueva cuenta contable")}
      subtitle={t("A qué cuenta del ERP va cada concepto del asiento de provisiones.")}
      onClose={onClose}
      dirty={!editing && accountCode !== ""}
      footer={
        <Box sx={{ display: "flex", gap: 1, justifyContent: "flex-end" }}>
          <Button onClick={onClose}>{t("Cancelar")}</Button>
          <Button variant="contained" disabled={!valid || saving} onClick={() => void save()}>
            {editing ? t("Guardar") : t("Crear")}
          </Button>
        </Box>
      }
    >
      <Box sx={{ display: "grid", gap: 2 }}>
        {error && <Alert severity="error">{error}</Alert>}
        <TextField select label={t("Concepto")} value={concept} disabled={editing}
          onChange={(e) => setConcept(e.target.value as GlConcept)}>
          {GL_CONCEPTS.map((value) => <MenuItem key={value} value={value}>{enumLabel("glConcept", value)}</MenuItem>)}
        </TextField>
        {concept === "WITHHOLDING_PAYABLE" && (
          <TextField select label={t("Clase de retención")} value={withholdingKind} disabled={editing}
            onChange={(e) => setWithholdingKind(e.target.value as WithholdingKind | "")}
            helperText={t("Una cuenta por clase de retención, o una para todas las que no tengan la suya.")}>
            <MenuItem value="">{t("Todas las retenciones")}</MenuItem>
            {withholdingKinds.map((kind) => (
              <MenuItem key={kind} value={kind}>{enumLabel("withholdingKind", kind)}</MenuItem>
            ))}
          </TextField>
        )}
        <TextField select label={t("Ámbito")} value={scopeType} disabled={editing}
          onChange={(e) => { setScopeType(e.target.value as GlScopeType); setScopeId(""); }}>
          {(["COMPANY", "ORIGIN", "ROUTE"] as const).map((value) => (
            <MenuItem key={value} value={value}>{enumLabel("glScopeType", value)}</MenuItem>
          ))}
        </TextField>
        {scopeType !== "COMPANY" && !editing && (
          <TextField select label={scopeType === "ORIGIN" ? t("Origen") : t("Ruta")} value={scopeId}
            onChange={(e) => setScopeId(e.target.value)} error={missingScope}
            helperText={scopeOptions.length === 0 ? t("No hay opciones disponibles con tus permisos.") : undefined}>
            {scopeOptions.map((option) => <MenuItem key={option.id} value={option.id}>{option.label}</MenuItem>)}
          </TextField>
        )}
        {scopeType !== "COMPANY" && editing && (
          <TextField label={scopeType === "ORIGIN" ? t("Origen") : t("Ruta")} value={mapping?.scopeName ?? ""} disabled />
        )}
        <TextField label={t("Cuenta")} value={accountCode} required onChange={(e) => setAccountCode(e.target.value)}
          slotProps={{ htmlInput: { maxLength: 40 } }} />
        <TextField label={t("Centro de costo")} value={costCenterCode} onChange={(e) => setCostCenterCode(e.target.value)}
          slotProps={{ htmlInput: { maxLength: 40 } }} />
      </Box>
    </FormDrawer>
  );
}
