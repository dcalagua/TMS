import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { Alert, Box, Button, ToggleButton, ToggleButtonGroup, Typography } from "@mui/material";
import { DownloadRounded, InsightsRounded, LocalShippingRounded, ScaleRounded, WarningAmberRounded } from "@mui/icons-material";
import {
  COST_TO_SERVE_GROUPS, downloadCostToServe, fetchCostToServe,
  type CostToServeGroup, type CostToServeGroupBy, type CostToServeQuery,
} from "../../shared/api/costToServeApi";
import { saveDownloadedFile, type ApiError } from "../../shared/api/httpClient";
import { describeApiError } from "../../shared/api/problemMessages";
import { DataTable, FilterBar, KpiCard, PageHeader, type DataTableColumn } from "../../shared/ui/components";
import { ICON_TINTS } from "../../shared/ui/navConfig";
import { useCompany } from "../../shared/company/CompanyContext";
import { notifyError } from "../../lib/ui";
import { enumLabel } from "../../lib/enums";
import { t } from "../../lib/i18n";
import { fmtDecimal, fmtQuantity, fmtVolumeM3, fmtWeightKg } from "../../lib/locale";
import { Money } from "./Money";

/** Del primer día del mes a hoy, en el reloj del navegador: sólo el valor inicial del filtro. */
function monthToDate(): { from: string; to: string } {
  const now = new Date();
  const pad = (n: number) => String(n).padStart(2, "0");
  const today = `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`;
  return { from: `${now.getFullYear()}-${pad(now.getMonth() + 1)}-01`, to: today };
}

/** Un costo unitario: cuatro decimales, porque el costo por kg de un flete suele estar bajo el céntimo. */
function unit(value: number | null, currency: string): string {
  return value === null ? "—" : `${fmtDecimal(value, 4)} ${currency}`;
}

/**
 * Costo para servir (fase 70, ADR-026): el costo de los viajes repartido entre sus pedidos y
 * agrupado por cliente, destino o ruta, con costo por kg, por m³ y por pedido.
 *
 * Cada moneda va aparte (TMS no convierte). Lo que no tiene base no recibe un reparto inventado: se
 * cuenta. Y no hay margen, porque TMS no tiene tarifa de venta; la pantalla lo dice.
 */
export function CostToServePage() {
  const { selected } = useCompany();
  const companyId = selected?.id ?? "";
  const [range, setRange] = useState(monthToDate);
  const [groupBy, setGroupBy] = useState<CostToServeGroupBy>("CUSTOMER");
  const [exporting, setExporting] = useState(false);
  const query: CostToServeQuery = { from: range.from, to: range.to, groupBy };

  const reportQuery = useQuery({
    queryKey: ["cost-to-serve", companyId, query],
    queryFn: ({ signal }) => fetchCostToServe(companyId, query, signal),
    enabled: companyId !== "" && range.from !== "" && range.to !== "",
  });

  async function exportCsv() {
    setExporting(true);
    try {
      const file = await downloadCostToServe(companyId, query, "CSV");
      saveDownloadedFile(file, `cost-to-serve-${groupBy.toLowerCase()}-${range.from}-${range.to}.csv`);
    } catch (error) {
      notifyError(t("No se pudo exportar"), describeApiError(error as ApiError));
    } finally {
      setExporting(false);
    }
  }

  const groupLabel = enumLabel("costToServeGroupBy", groupBy);
  const columns: DataTableColumn<CostToServeGroup>[] = [
    {
      key: "label",
      header: groupLabel,
      render: (row) => (
        <Typography variant="body2" sx={{ fontWeight: 600 }}>
          {row.label ?? t("(sin {{group}})", { group: groupLabel.toLowerCase() })}
        </Typography>
      ),
    },
    { key: "currency", header: t("Moneda"), render: (row) => row.currency },
    { key: "trips", header: t("Viajes"), numeric: true, render: (row) => fmtQuantity(row.trips) },
    { key: "orders", header: t("Pedidos"), numeric: true, render: (row) => fmtQuantity(row.orders) },
    {
      key: "unallocated",
      header: t("Sin base"),
      numeric: true,
      render: (row) => (
        <Typography variant="body2" component="span" sx={{ color: row.unallocatedOrders > 0 ? "warning.main" : undefined }}>
          {fmtQuantity(row.unallocatedOrders)}
        </Typography>
      ),
    },
    { key: "cost", header: t("Costo"), numeric: true, render: (row) => <Money value={row.cost} currency={row.currency} strong /> },
    { key: "weight", header: t("Peso"), numeric: true, render: (row) => fmtWeightKg(row.weightKg) },
    { key: "volume", header: t("Volumen"), numeric: true, render: (row) => fmtVolumeM3(row.volumeM3) },
    { key: "perKg", header: t("Costo / kg"), numeric: true, render: (row) => unit(row.costPerKg, row.currency) },
    { key: "perM3", header: t("Costo / m³"), numeric: true, render: (row) => unit(row.costPerM3, row.currency) },
    { key: "perOrder", header: t("Costo / pedido"), numeric: true, render: (row) => unit(row.costPerOrder, row.currency) },
  ];

  const report = reportQuery.data;
  return (
    <>
      <PageHeader
        icon={<InsightsRounded />}
        tint={ICON_TINTS["/settlement/cost-to-serve"]}
        title={t("Costo por cliente / destino / ruta")}
        subtitle={t("El costo de cada viaje repartido entre sus pedidos. Cada moneda va aparte.")}
        onRefresh={() => void reportQuery.refetch()}
        refreshing={reportQuery.isFetching}
        actions={
          <Button
            variant="contained" startIcon={<DownloadRounded />} disabled={exporting || companyId === ""}
            onClick={() => void exportCsv()}
          >
            {t("Exportar CSV")}
          </Button>
        }
      />

      <Alert severity="info" sx={{ mb: 2 }}>
        {t("Sin margen: TMS no tiene tarifa de venta, así que muestra lo que costó servir y no lo que se ganó.")}
      </Alert>

      <FilterBar
        value={range}
        defaults={monthToDate()}
        onChange={setRange}
        fields={[{ type: "dateRange", from: "from", to: "to", label: t("Fecha de servicio") }]}
      />

      <Box sx={{ display: "flex", gap: 1.5, flexWrap: "wrap", mb: 2, alignItems: "center" }}>
        <ToggleButtonGroup
          size="small" exclusive value={groupBy} aria-label={t("Agrupar por")}
          onChange={(_, value: CostToServeGroupBy | null) => value && setGroupBy(value)}
        >
          {COST_TO_SERVE_GROUPS.map((group) => (
            <ToggleButton key={group} value={group}>{enumLabel("costToServeGroupBy", group)}</ToggleButton>
          ))}
        </ToggleButtonGroup>
        {report && (
          <Typography variant="caption" color="text.secondary">
            {t("Base de reparto: {{basis}}", { basis: enumLabel("costAllocationBasis", report.basis) })}
          </Typography>
        )}
      </Box>

      {report && report.totals.length > 0 && (
        <Box sx={{
          display: "grid", gap: 2, mb: 2,
          gridTemplateColumns: { xs: "1fr", sm: "repeat(2, minmax(0,1fr))", lg: "repeat(4, minmax(0,1fr))" },
        }}>
          {report.totals.map((total) => (
            <Box key={total.currency} sx={{ display: "contents" }}>
              <KpiCard
                icon={<LocalShippingRounded />} color="primary.main" title={t("Costo repartido ({{c}})", { c: total.currency })}
                value={<Money value={total.allocated} currency={total.currency} strong />}
                sub={t("{{n}} viajes · {{inv}} facturados, {{act}} reales, {{est}} estimados", {
                  n: fmtQuantity(total.trips), inv: fmtQuantity(total.invoicedTrips),
                  act: fmtQuantity(total.actualTrips), est: fmtQuantity(total.estimatedTrips),
                })}
              />
              <KpiCard
                icon={<ScaleRounded />} color="info.main" title={t("Costo por kg ({{c}})", { c: total.currency })}
                value={unit(total.costPerKg, total.currency)} sub={t("Sobre los kg que recibieron costo")}
              />
              <KpiCard
                icon={<WarningAmberRounded />} color="warning.main" title={t("Pedidos sin base")}
                value={fmtQuantity(total.unallocatedOrders)}
                sub={t("{{n}} viajes sin ningún pedido medible", { n: fmtQuantity(total.tripsNotAllocated) })}
              />
            </Box>
          ))}
        </Box>
      )}

      <DataTable
        columns={columns}
        rows={report?.groups ?? []}
        rowKey={(row) => `${row.key ?? "none"}-${row.currency}`}
        isLoading={reportQuery.isPending && companyId !== ""}
        error={reportQuery.isError ? describeApiError(reportQuery.error as ApiError) : null}
        onRetry={() => void reportQuery.refetch()}
        emptyTitle={t("Sin costos repartidos en el rango")}
        emptyMessage={t("Un viaje se reparte al salir con su estimado, de nuevo al cerrar el costo real y al aprobar la factura.")}
      />
    </>
  );
}
