import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import {
  Alert, Box, Button, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, ToggleButton,
  ToggleButtonGroup, Typography,
} from "@mui/material";
import { Co2Rounded, DownloadRounded, LocalShippingRounded, RouteRounded, WarningAmberRounded } from "@mui/icons-material";
import {
  downloadEmissionReport, fetchEmissionReport, type EmissionGroupBy,
} from "../../shared/api/emissionsApi";
import { saveDownloadedFile, type ApiError } from "../../shared/api/httpClient";
import { describeApiError } from "../../shared/api/problemMessages";
import { AppCard, KpiCard, SectionHeader, dataTableSx } from "../../shared/ui/components";
import { enumLabel } from "../../lib/enums";
import { notifyError } from "../../lib/ui";
import { t } from "../../lib/i18n";
import { fmtCo2e, fmtDecimal, fmtQuantity, fmtWeightKg } from "../../lib/locale";

const GROUPS: EmissionGroupBy[] = ["CUSTOMER", "DESTINATION", "ROUTE"];

/**
 * «Huella de carbono» en Reportes y KPIs (V73): los kg CO₂e del período por cliente, destino o ruta,
 * repartidos con la base del costo para servir. Lo que no tiene factor no suma un cero: se cuenta
 * aparte, para que un total corto diga por qué lo es.
 */
export function CarbonFootprintSection({ companyId, from, to }: { companyId: string; from: string; to: string }) {
  const [groupBy, setGroupBy] = useState<EmissionGroupBy>("CUSTOMER");
  const [exporting, setExporting] = useState(false);
  const query = useQuery({
    queryKey: ["emission-report", companyId, from, to, groupBy],
    queryFn: ({ signal }) => fetchEmissionReport(companyId, { from, to, groupBy }, signal),
    enabled: companyId !== "" && from !== "" && to !== "",
  });

  async function exportCsv() {
    setExporting(true);
    try {
      const file = await downloadEmissionReport(companyId, { from, to, groupBy }, "CSV");
      saveDownloadedFile(file, "huella-carbono.csv");
    } catch (error) {
      notifyError(t("No se pudo exportar"), describeApiError(error as ApiError));
    } finally {
      setExporting(false);
    }
  }

  const report = query.data;
  const missing = report
    ? report.totals.tripsWithoutFactor + report.totals.tripsWithoutDistance + report.totals.tripsWithoutVehicle
    : 0;
  const perKm = report && report.totals.distanceKm > 0 ? report.totals.kgCo2e / report.totals.distanceKm : null;

  return (
    <Box component="section" aria-label={t("Huella de carbono")} sx={{ mb: 3 }}>
      <SectionHeader
        title={t("Huella de carbono")} level={2}
        actions={(
          <Box sx={{ display: "flex", gap: 1, alignItems: "center", flexWrap: "wrap" }}>
            <ToggleButtonGroup size="small" exclusive value={groupBy}
              onChange={(_, value: EmissionGroupBy | null) => value && setGroupBy(value)} aria-label={t("Agrupar por")}>
              {GROUPS.map((group) => (
                <ToggleButton key={group} value={group}>
                  {group === "CUSTOMER" ? t("Cliente") : group === "DESTINATION" ? t("Destino") : t("Ruta")}
                </ToggleButton>
              ))}
            </ToggleButtonGroup>
            <Button size="small" variant="outlined" startIcon={<DownloadRounded />} disabled={exporting || !report}
              onClick={() => void exportCsv()}>
              {t("CSV")}
            </Button>
          </Box>
        )}
      />
      {query.isError && <Alert severity="warning">{describeApiError(query.error as ApiError)}</Alert>}
      {report && (
        <>
          {report.truncated && (
            <Alert severity="info" sx={{ mb: 1.5 }}>{t("El período tiene más viajes de los que se leen de una vez: acorta el rango.")}</Alert>
          )}
          <Box sx={{
            display: "grid", gap: 2, mb: 2,
            gridTemplateColumns: { xs: "1fr", sm: "repeat(2, minmax(0,1fr))", lg: "repeat(4, minmax(0,1fr))" },
          }}>
            <KpiCard icon={<Co2Rounded />} color="success.main" title={t("Emisiones del período")}
              value={report.totals.tripsCalculated === 0 ? t("Sin datos") : fmtCo2e(report.totals.kgCo2e)}
              sub={t("{{n}} de {{total}} viajes con huella", { n: fmtQuantity(report.totals.tripsCalculated), total: fmtQuantity(report.totals.trips) })} />
            <KpiCard icon={<RouteRounded />} color="info.main" title={t("Distancia con huella")}
              value={`${fmtDecimal(report.totals.distanceKm, 0)} km`}
              sub={perKm === null ? "—" : t("{{v}} kg CO₂e por km", { v: fmtDecimal(perKm, 3) })} />
            <KpiCard icon={<LocalShippingRounded />} color="warning.main" title={t("Viajes estimados")}
              value={fmtQuantity(report.totals.tripsEstimated)} sub={t("Distancia estimada por coordenadas")} />
            <KpiCard icon={<WarningAmberRounded />} color={missing > 0 ? "warning.main" : "success.main"} title={t("Viajes sin datos")}
              value={fmtQuantity(missing)}
              sub={t("{{f}} sin factor · {{d}} sin recorrido · {{v}} sin vehículo", {
                f: report.totals.tripsWithoutFactor, d: report.totals.tripsWithoutDistance, v: report.totals.tripsWithoutVehicle,
              })} />
          </Box>
          <AppCard title={t("Por {{group}} · base {{basis}}", {
            group: groupBy === "CUSTOMER" ? t("cliente") : groupBy === "DESTINATION" ? t("destino") : t("ruta"),
            basis: enumLabel("costAllocationBasis", report.basis),
          })} flush>
            {report.groups.length === 0 ? (
              <Typography variant="body2" color="text.secondary" sx={{ p: 2 }}>
                {t("Ningún viaje del período tiene huella: carga el factor de emisión de los tipos de vehículo.")}
              </Typography>
            ) : (
              <TableContainer>
                <Table size="small" sx={dataTableSx}>
                  <TableHead>
                    <TableRow>
                      <TableCell>{groupBy === "CUSTOMER" ? t("Cliente") : groupBy === "DESTINATION" ? t("Destino") : t("Ruta")}</TableCell>
                      <TableCell className="numeric-col">{t("Viajes")}</TableCell>
                      <TableCell className="numeric-col">{t("Pedidos")}</TableCell>
                      <TableCell className="numeric-col">{t("kg CO₂e")}</TableCell>
                      <TableCell className="numeric-col">{t("Peso")}</TableCell>
                      <TableCell className="numeric-col">{t("kg CO₂e por t")}</TableCell>
                    </TableRow>
                  </TableHead>
                  <TableBody>
                    {report.groups.map((group) => (
                      <TableRow key={group.key ?? "none"} hover>
                        <TableCell sx={{ fontWeight: 600 }}>{group.label ?? t("Sin especificar")}</TableCell>
                        <TableCell className="numeric-col">{fmtQuantity(group.trips)}</TableCell>
                        <TableCell className="numeric-col">{fmtQuantity(group.orders)}</TableCell>
                        <TableCell className="numeric-col">{fmtDecimal(group.kgCo2e, 2)}</TableCell>
                        <TableCell className="numeric-col">{fmtWeightKg(group.weightKg)}</TableCell>
                        <TableCell className="numeric-col">{group.kgCo2ePerTonne === null ? "—" : fmtDecimal(group.kgCo2ePerTonne, 2)}</TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </TableContainer>
            )}
          </AppCard>
          {report.totals.unallocatedKgCo2e > 0 && (
            <Typography variant="caption" color="text.secondary" sx={{ display: "block", mt: 1 }}>
              {t("{{kg}} kg CO₂e no se pudieron repartir: ningún pedido de esos viajes tiene medida en la base.", { kg: fmtDecimal(report.totals.unallocatedKgCo2e, 2) })}
            </Typography>
          )}
        </>
      )}
    </Box>
  );
}
