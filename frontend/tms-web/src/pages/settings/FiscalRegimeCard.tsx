import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Alert, Box, Button, MenuItem, TextField, Typography } from "@mui/material";
import { SaveRounded } from "@mui/icons-material";
import type { ApiError } from "../../shared/api/httpClient";
import { describeApiError } from "../../shared/api/problemMessages";
import {
  fetchFiscalSettings, updateFiscalSettings,
  type FiscalSettingsUpdateRequest, type TaxRegimeView, type WithholdingKind,
} from "../../shared/api/settlementApi";
import { AppCard, DetailGrid, DetailItem, ErrorState, LoadingState } from "../../shared/ui/components";
import { confirmDialog, notifyError, notifySuccess } from "../../lib/ui";
import { enumLabel } from "../../lib/enums";
import { t } from "../../lib/i18n";
import { fmtDecimal } from "../../lib/locale";

type Tristate = "" | "true" | "false";

interface WithholdingDraft {
  enabled: Tristate;
  code: string;
  rate: string;
  thresholdAmount: string;
}

interface Draft {
  fiscalCountry: string;
  vatRate: string;
  freightVatRate: string;
  withholdings: Partial<Record<WithholdingKind, WithholdingDraft>>;
}

const asText = (value: number | null | undefined): string => (value === null || value === undefined ? "" : String(value));
const asNumber = (value: string): number | null => (value.trim() === "" ? null : Number(value));

function draftOf(regime: TaxRegimeView): Draft {
  const withholdings: Partial<Record<WithholdingKind, WithholdingDraft>> = {};
  for (const rule of regime.withholdings) {
    const stored = regime.stored.withholdings.find((entry) => entry.kind === rule.kind);
    withholdings[rule.kind] = {
      enabled: stored?.enabled === true ? "true" : stored?.enabled === false ? "false" : "",
      code: stored?.code ?? "",
      rate: asText(stored?.rate),
      thresholdAmount: asText(stored?.thresholdAmount),
    };
  }
  return {
    fiscalCountry: regime.stored.fiscalCountry ?? "",
    vatRate: asText(regime.stored.vatRate),
    freightVatRate: asText(regime.stored.freightVatRate),
    withholdings,
  };
}

/** Una tasa vacía (hereda) o un porcentaje entre 0 y 100. */
function invalidRate(value: string): boolean {
  if (value.trim() === "") return false;
  const parsed = Number(value);
  return !Number.isFinite(parsed) || parsed < 0 || parsed >= 100;
}

function invalidAmount(value: string): boolean {
  if (value.trim() === "") return false;
  const parsed = Number(value);
  return !Number.isFinite(parsed) || parsed < 0;
}

/**
 * El régimen fiscal de la empresa (ADR-027): con qué país calcula sus impuestos, la tasa general
 * y la del flete, y cada retención. Todo vacío hereda el valor por defecto del despliegue
 * (`tms.finance.tax.<país>.*`), que el servidor devuelve ya resuelto: la pantalla no trae ninguno.
 *
 * Lo ve quien puede leer facturas (`SETTLEMENT_VIEW`); lo edita sólo quien tiene `TAX_REGIME_MANAGE`
 * (`settlement.tax:manage`). TMS sólo RECIBE los comprobantes del transportista: no emite ni firma
 * nada ante la autoridad tributaria. Esconder es
 * UX: el servidor vuelve a comprobarlo.
 */
export function FiscalRegimeCard({ companyId, canManage }: { companyId: string; canManage: boolean }) {
  const regimeQuery = useQuery({
    queryKey: ["fiscal-settings", companyId],
    queryFn: ({ signal }) => fetchFiscalSettings(companyId, signal),
    enabled: companyId !== "",
  });
  const regime = regimeQuery.data;

  if (regimeQuery.isPending) {
    return <AppCard title={t("Régimen fiscal")}><LoadingState label={t("Cargando el régimen fiscal...")} /></AppCard>;
  }
  if (regimeQuery.isError || !regime) {
    return (
      <AppCard title={t("Régimen fiscal")}>
        <ErrorState message={describeApiError(regimeQuery.error as ApiError)} onRetry={() => void regimeQuery.refetch()} />
      </AppCard>
    );
  }
  // La clave es lo guardado: al guardar, el borrador vuelve a sembrarse desde lo que dijo el servidor.
  return (
    <FiscalRegimeEditor
      key={`${regime.country}|${JSON.stringify(regime.stored)}`}
      companyId={companyId} canManage={canManage} regime={regime}
    />
  );
}

function FiscalRegimeEditor({ companyId, canManage, regime }: {
  companyId: string; canManage: boolean; regime: TaxRegimeView;
}) {
  const queryClient = useQueryClient();
  const [draft, setDraft] = useState<Draft>(() => draftOf(regime));
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const country = draft.fiscalCountry.trim().toUpperCase();
  const countryInvalid = country !== "" && !/^[A-Z]{2}$/.test(country);
  // Las retenciones que se ven son las del país actual: si cambia el país, se configuran después.
  const countryChanges = (country === "" ? regime.defaultCountry ?? regime.country : country) !== regime.country;
  const withholdingInvalid = Object.values(draft.withholdings)
    .some((entry) => entry && (invalidRate(entry.rate) || invalidAmount(entry.thresholdAmount) || entry.code.length > 10));
  const invalid = countryInvalid || invalidRate(draft.vatRate) || invalidRate(draft.freightVatRate)
    || (!countryChanges && withholdingInvalid);
  const dirty = JSON.stringify(draft) !== JSON.stringify(draftOf(regime));

  const change = (next: Partial<Draft>) => setDraft((current) => ({ ...current, ...next }));
  const changeWithholding = (kind: WithholdingKind, next: Partial<WithholdingDraft>) =>
    setDraft((current) => {
      const previous = current.withholdings[kind] ?? { enabled: "", code: "", rate: "", thresholdAmount: "" };
      return { ...current, withholdings: { ...current.withholdings, [kind]: { ...previous, ...next } } };
    });

  async function save() {
    if (invalid) return;
    if (countryChanges) {
      const confirmed = await confirmDialog({
        title: t("¿Cambiar el país del régimen fiscal?"),
        text: t("Las facturas que se registren desde ahora se validarán y calcularán con las reglas de {{country}}. Las ya registradas conservan el régimen con que se recibieron. Las retenciones del nuevo país se configuran después de guardar.", {
          country: country === "" ? regime.defaultCountry ?? "" : country,
        }),
        confirmLabel: t("Cambiar régimen"),
      });
      if (!confirmed) return;
    }
    const request: FiscalSettingsUpdateRequest = {
      fiscalCountry: country === "" ? null : country,
      vatRate: asNumber(draft.vatRate),
      freightVatRate: asNumber(draft.freightVatRate),
      withholdings: countryChanges ? [] : regime.withholdings
        .map((rule) => ({ kind: rule.kind, entry: draft.withholdings[rule.kind] }))
        .filter(({ entry }) => entry && (entry.enabled !== "" || entry.code.trim() !== "" || entry.rate.trim() !== ""
          || entry.thresholdAmount.trim() !== ""))
        .map(({ kind, entry }) => ({
          kind,
          enabled: entry?.enabled === "" ? null : entry?.enabled === "true",
          code: entry?.code.trim() || null,
          rate: asNumber(entry?.rate ?? ""),
          thresholdAmount: asNumber(entry?.thresholdAmount ?? ""),
        })),
    };
    setSaving(true);
    setError(null);
    try {
      const next = await updateFiscalSettings(companyId, request);
      queryClient.setQueryData(["fiscal-settings", companyId], next);
      notifySuccess(t("Régimen fiscal guardado"));
    } catch (failure) {
      const message = describeApiError(failure as ApiError);
      setError(message);
      notifyError(t("No se pudo guardar"), message);
    } finally {
      setSaving(false);
    }
  }

  const grid = { display: "grid", gap: 2, gridTemplateColumns: { xs: "1fr", sm: "repeat(3, minmax(0, 1fr))" } } as const;
  /** Qué pasa con el campo vacío: si nada está guardado, el resuelto ES el valor por defecto. */
  const inherits = (stored: number | null, resolved: number | null) => {
    if (stored !== null) return t("Vacío: hereda el valor por defecto.");
    return resolved === null
      ? t("Vacío: sin valor por defecto.")
      : t("Vacío: {{value}} % por defecto.", { value: fmtDecimal(resolved, 2) });
  };

  return (
    <AppCard title={t("Régimen fiscal")}>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
        {t("Con qué reglas se validan y calculan los impuestos de las facturas de transportista. Un campo vacío hereda el valor por defecto de la instalación; verifica cada porcentaje con el contador de la empresa.")}
      </Typography>
      {error && <Alert severity="error" sx={{ mb: 2 }}>{error}</Alert>}

      <DetailGrid>
        <DetailItem label={t("Régimen")} value={`${enumLabel("taxRegime", regime.regime)} · ${regime.country}`} />
        <DetailItem label={t("Moneda fiscal")} value={regime.fiscalCurrency ?? t("Ninguna impuesta")} />
        <DetailItem label={t("Impuesto")} value={regime.vatLabel} />
        <DetailItem
          label={t("Tasas admitidas")}
          value={regime.allowedVatRates.length > 0
            ? `${regime.allowedVatRates.map((rate) => fmtDecimal(rate, 2)).join(", ")} %`
            : t("Cualquiera entre 0 y 100")}
        />
        <DetailItem label={t("Identificación del emisor")} value={regime.taxId.label} />
        <DetailItem label={t("Validación electrónica")} value={t("No disponible (dependencia externa)")} />
      </DetailGrid>

      {!canManage && (
        <Alert severity="info" variant="outlined" sx={{ mt: 2 }}>
          {t("Sólo quien tiene permiso para gestionar el régimen fiscal puede cambiarlo.")}
        </Alert>
      )}

      <Box component="form" aria-label={t("Régimen fiscal")} onSubmit={(event) => { event.preventDefault(); void save(); }}
        noValidate sx={{ display: "grid", gap: 2, mt: 2 }}>
        <Box sx={grid}>
          <TextField
            label={t("País fiscal")} size="small" fullWidth disabled={!canManage} value={draft.fiscalCountry}
            placeholder={regime.defaultCountry ?? undefined}
            error={countryInvalid}
            helperText={countryInvalid
              ? t("Dos letras, ISO 3166 (EC, PE, CO).")
              : t("Vacío: el país por defecto de la empresa ({{country}}).", { country: regime.defaultCountry ?? "—" })}
            onChange={(event) => change({ fiscalCountry: event.target.value })}
            slotProps={{ htmlInput: { maxLength: 2 } }}
          />
          <TextField
            label={t("Tasa general de {{vat}} (%)", { vat: regime.vatLabel })} size="small" fullWidth type="number"
            disabled={!canManage} value={draft.vatRate} error={invalidRate(draft.vatRate)}
            placeholder={asText(regime.vatRate)} helperText={inherits(regime.stored.vatRate, regime.vatRate)}
            onChange={(event) => change({ vatRate: event.target.value })}
          />
          <TextField
            label={t("Tasa de {{vat}} del flete (%)", { vat: regime.vatLabel })} size="small" fullWidth type="number"
            disabled={!canManage} value={draft.freightVatRate} error={invalidRate(draft.freightVatRate)}
            placeholder={asText(regime.freightVatRate)}
            helperText={inherits(regime.stored.freightVatRate, regime.freightVatRate)}
            onChange={(event) => change({ freightVatRate: event.target.value })}
          />
        </Box>

        {countryChanges ? (
          <Alert severity="info" variant="outlined">
            {t("Las retenciones del nuevo país se configuran después de guardar.")}
          </Alert>
        ) : regime.withholdings.length === 0 ? (
          <Typography variant="body2" color="text.secondary">{t("Este régimen no calcula retenciones.")}</Typography>
        ) : (
          <Box sx={{ display: "grid", gap: 1.5 }}>
            <Typography variant="subtitle2">{t("Retenciones fiscales")}</Typography>
            {regime.withholdings.map((rule) => {
              const entry = draft.withholdings[rule.kind] ?? { enabled: "", code: "", rate: "", thresholdAmount: "" };
              return (
                <Box key={rule.kind} role="group" aria-label={rule.label}
                  sx={{ p: 1.5, border: "1px solid", borderColor: "divider", borderRadius: 1, display: "grid", gap: 1 }}>
                  <Typography variant="body2" sx={{ fontWeight: 700 }}>
                    {rule.label}
                    <Typography component="span" variant="caption" color="text.secondary">
                      {` · ${rule.enabled ? t("Activa") : t("Inactiva")} · ${t("sobre {{base}}", { base: enumLabel("withholdingBase", rule.base) })}`}
                    </Typography>
                  </Typography>
                  <Box sx={{ display: "grid", gap: 1.5, gridTemplateColumns: { xs: "1fr", sm: "repeat(4, minmax(0, 1fr))" } }}>
                    <TextField
                      select size="small" fullWidth label={t("Se calcula")} disabled={!canManage} value={entry.enabled}
                      onChange={(event) => changeWithholding(rule.kind, { enabled: event.target.value as Tristate })}
                      slotProps={{ select: { displayEmpty: true }, inputLabel: { shrink: true } }}
                    >
                      <MenuItem value="">{t("Por defecto")}</MenuItem>
                      <MenuItem value="true">{t("Sí")}</MenuItem>
                      <MenuItem value="false">{t("No")}</MenuItem>
                    </TextField>
                    <TextField
                      size="small" fullWidth label={t("Código")} disabled={!canManage} value={entry.code}
                      placeholder={rule.code ?? undefined} error={entry.code.length > 10}
                      onChange={(event) => changeWithholding(rule.kind, { code: event.target.value })}
                      slotProps={{ htmlInput: { maxLength: 10 } }}
                    />
                    <TextField
                      size="small" fullWidth type="number" label={t("Tasa (%)")} disabled={!canManage} value={entry.rate}
                      placeholder={asText(rule.rate)} error={invalidRate(entry.rate)}
                      onChange={(event) => changeWithholding(rule.kind, { rate: event.target.value })}
                    />
                    <TextField
                      size="small" fullWidth type="number" label={t("Umbral (total)")} disabled={!canManage}
                      value={entry.thresholdAmount} placeholder={asText(rule.thresholdAmount)}
                      error={invalidAmount(entry.thresholdAmount)}
                      onChange={(event) => changeWithholding(rule.kind, { thresholdAmount: event.target.value })}
                    />
                  </Box>
                </Box>
              );
            })}
          </Box>
        )}

        {canManage && (
          <Box sx={{ display: "flex", justifyContent: "flex-end" }}>
            <Button type="submit" variant="contained" startIcon={<SaveRounded />} disabled={!dirty || invalid || saving}>
              {saving ? t("Guardando...") : t("Guardar régimen fiscal")}
            </Button>
          </Box>
        )}
      </Box>
    </AppCard>
  );
}
