import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import {
  Alert, Box, Button, Chip, Table, TableBody, TableCell, TableContainer,
  TableHead, TableRow, TextField, Typography,
} from "@mui/material";
import {
  BarChartRounded, DownloadRounded, LocalShippingRounded, ScheduleRounded,
  InventoryRounded, ReportProblemRounded, PaidRounded, AssignmentTurnedInRounded,
} from "@mui/icons-material";
import type { ApiError } from "../../shared/api/httpClient";
import { saveDownloadedFile } from "../../shared/api/httpClient";
import { downloadKpiCsv, fetchKpiReport } from "../../shared/api/reportingApi";
import { describeApiError } from "../../shared/api/problemMessages";
import { useCompany } from "../../shared/company/CompanyContext";
import {
  AppCard, ErrorState, KpiCard, LoadingState, PageHeader, SectionHeader, dataTableSx,
} from "../../shared/ui/components";
import { ICON_TINTS } from "../../shared/ui/navConfig";
import { notifyError } from "../../lib/ui";
import { t } from "../../lib/i18n";
import { fmtDate, fmtDateTime, fmtDecimal, fmtMoney, fmtPercent, fmtQuantity, fmtVolumeM3, fmtWeightKg } from "../../lib/locale";
import { DailyColumnChart } from "./DailyColumnChart";

/**
 * KPIs de una empresa sobre un rango de días operativos.
 *
 * **Nada se calcula en el navegador.** Cada porcentaje llega como un número que dividió el
 * backend, y cada `null` significa "no se midió nada" y se pinta como una raya — nunca como 0% ni
 * como 100%. Derivar cualquiera de ellos aquí le daría al producto una segunda opinión sobre qué
 * quiere decir "a tiempo".
 *
 * Los bloques de pedidos, ofertas y costo llegan `null` cuando la cuenta no tiene el permiso que
 * los gobierna. Null no es vacío: la pantalla dice "no disponible para tu cuenta" en lugar de
 * enseñar un cero que nadie le contó.
 */
export function ReportsPage() {
  const { selected } = useCompany();
  const companyId = selected?.id ?? "";

  const [range, setRange] = useState({ from: "", to: "" });
  const [applied, setApplied] = useState({ from: "", to: "" });
  const [exporting, setExporting] = useState(false);

  const reportQuery = useQuery({
    queryKey: ["kpi-report", companyId, applied],
    queryFn: ({ signal }) =>
      fetchKpiReport({ companyId, from: applied.from || undefined, to: applied.to || undefined, signal }),
    enabled: companyId !== "",
  });

  async function exportCsv() {
    setExporting(true);
    try {
      const file = await downloadKpiCsv({ companyId, from: applied.from || undefined, to: applied.to || undefined });
      saveDownloadedFile(file, "kpis.csv");
    } catch (error) {
      notifyError(t("No se pudo exportar"), describeApiError(error as ApiError));
    } finally {
      setExporting(false);
    }
  }

  if (reportQuery.isPending) return <LoadingState label={t("Cargando los indicadores...")} />;
  if (reportQuery.isError) {
    return (
      <ErrorState
        message={describeApiError(reportQuery.error as ApiError)}
        onRetry={() => void reportQuery.refetch()}
      />
    );
  }

  const report = reportQuery.data;
  const { shipments, service, exceptions, utilization, orders, tenders, cost, daily } = report;

  /** Un porcentaje del backend, o una raya. Nunca un cero inventado. */
  const pct = (value: number | null) => (value === null ? "-" : fmtPercent(value, 1));

  return (
    <>
      <PageHeader
        icon={<BarChartRounded />}
        tint={ICON_TINTS["/reporting"]}
        title={t("Reportes y KPIs")}
        subtitle={t("{{from}} → {{to}} · {{days}} días", {
          from: fmtDate(report.from), to: fmtDate(report.to), days: report.days,
        })}
        meta={<Chip size="small" variant="outlined" label={t("Al {{time}}", { time: fmtDateTime(report.generatedAt) })} />}
        onRefresh={() => void reportQuery.refetch()}
        refreshing={reportQuery.isFetching}
        actions={
          <Box sx={{ display: "flex", gap: 1, alignItems: "center", flexWrap: "wrap" }}>
            <TextField
              size="small" type="date" label={t("Desde")} value={range.from}
              onChange={(e) => setRange({ ...range, from: e.target.value })}
              slotProps={{ inputLabel: { shrink: true } }}
              sx={{ width: 165 }}
            />
            <TextField
              size="small" type="date" label={t("Hasta")} value={range.to}
              onChange={(e) => setRange({ ...range, to: e.target.value })}
              slotProps={{ inputLabel: { shrink: true } }}
              sx={{ width: 165 }}
            />
            <Button variant="outlined" onClick={() => setApplied(range)}>{t("Aplicar")}</Button>
            <Button variant="contained" startIcon={<DownloadRounded />} disabled={exporting} onClick={() => void exportCsv()}>
              {t("Exportar CSV")}
            </Button>
          </Box>
        }
      />

      <SectionHeader title={t("Envíos")} level={2} />
      <Box sx={{
        display: "grid", gap: 2, mb: 3,
        gridTemplateColumns: { xs: "1fr", sm: "repeat(2, minmax(0,1fr))", lg: "repeat(4, minmax(0,1fr))" },
      }}>
        <KpiCard icon={<LocalShippingRounded />} color="primary.main" title={t("Envíos planificados")} value={fmtQuantity(shipments.trips)} sub={t("{{n}} cancelados", { n: fmtQuantity(shipments.tripsCancelled) })} />
        <KpiCard icon={<AssignmentTurnedInRounded />} color="success.main" title={t("Completados")} value={fmtQuantity(shipments.tripsCompleted)} progress={shipments.completionPercent ?? undefined} sub={pct(shipments.completionPercent)} />
        <KpiCard
          icon={<ScheduleRounded />} color="info.main"
          title={t("Salidas a tiempo")} value={pct(shipments.onTimeDeparturePercent)}
          // El denominador va al lado del porcentaje: 92% sobre cinco salidas medidas es una
          // afirmación distinta de 92% sobre cuatrocientas.
          sub={t("sobre {{n}} salidas medidas", { n: fmtQuantity(shipments.departuresMeasured) })}
        />
        <KpiCard icon={<ScheduleRounded />} color="error.main" title={t("Salieron tarde")} value={fmtQuantity(shipments.departuresLate)} />
      </Box>

      <SectionHeader title={t("Servicio y entregas")} level={2} />
      <Box sx={{
        display: "grid", gap: 2, mb: 3,
        gridTemplateColumns: { xs: "1fr", sm: "repeat(2, minmax(0,1fr))", lg: "repeat(4, minmax(0,1fr))" },
      }}>
        <KpiCard icon={<ScheduleRounded />} color="info.main" title={t("Servicio en ventana")} value={pct(service.onTimeServicePercent)} sub={t("sobre {{n}} ventanas medidas", { n: fmtQuantity(service.serviceWindowsMeasured) })} />
        <KpiCard icon={<InventoryRounded />} color="success.main" title={t("Entregas exitosas")} value={pct(service.deliverySuccessPercent)} sub={t("{{n}} registradas", { n: fmtQuantity(service.deliveriesRecorded) })} />
        <KpiCard icon={<InventoryRounded />} color="warning.main" title={t("Entregas incompletas")} value={fmtQuantity(service.deliveriesShort)} sub={t("Parciales, rechazadas o fallidas")} />
        <KpiCard icon={<ReportProblemRounded />} color="error.main" title={t("Incidencias")} value={fmtQuantity(exceptions.exceptions)} sub={t("{{n}} abiertas", { n: fmtQuantity(exceptions.open) })} />
      </Box>

      <Box sx={{ display: "grid", gap: 3, mb: 3, gridTemplateColumns: { xs: "1fr", lg: "1fr 1fr" } }}>
        <AppCard title={t("Envíos por día")}>
          <DailyColumnChart
            rows={daily}
            series={[
              { key: "trips", label: "Envíos" },
              { key: "tripsCompleted", label: "Completados" },
              { key: "departuresLate", label: "Salieron tarde" },
            ]}
          />
        </AppCard>
        <AppCard title={t("Entregas por día")}>
          <DailyColumnChart
            rows={daily}
            series={[
              { key: "deliveriesRecorded", label: "Registradas" },
              { key: "deliveriesDelivered", label: "Entregadas" },
              { key: "exceptions", label: "Incidencias" },
            ]}
          />
        </AppCard>
      </Box>

      <SectionHeader title={t("Uso de capacidad")} level={2} />
      <AppCard title={t("Del rango completo")}>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
          {t("La carga total del rango sobre su capacidad total, no el promedio de los porcentajes por envío. Cubre {{n}} envíos.", { n: fmtQuantity(utilization.trips) })}
        </Typography>
        <Box sx={{ display: "grid", gap: 2, gridTemplateColumns: { xs: "1fr", sm: "repeat(3, minmax(0,1fr))" } }}>
          <KpiCard
            icon={<LocalShippingRounded />} color="primary.main" title={t("Peso")}
            value={pct(utilization.weightPercent)} progress={utilization.weightPercent ?? undefined}
            sub={`${fmtWeightKg(utilization.weightUsedKg)} / ${fmtWeightKg(utilization.weightCapacityKg)}`}
          />
          <KpiCard
            icon={<LocalShippingRounded />} color="info.main" title={t("Volumen")}
            value={pct(utilization.volumePercent)} progress={utilization.volumePercent ?? undefined}
            sub={`${fmtVolumeM3(utilization.volumeUsedM3)} / ${fmtVolumeM3(utilization.volumeCapacityM3)}`}
          />
          <KpiCard
            icon={<LocalShippingRounded />} color="warning.main" title={t("Pallets")}
            value={pct(utilization.palletsPercent)} progress={utilization.palletsPercent ?? undefined}
            sub={`${fmtDecimal(utilization.palletsUsed)} / ${fmtDecimal(utilization.palletCapacity)}`}
          />
        </Box>
      </AppCard>

      <Box sx={{ display: "grid", gap: 3, my: 3, gridTemplateColumns: { xs: "1fr", lg: "repeat(3, minmax(0,1fr))" } }}>
        <AppCard title={t("Pedidos")}>
          {orders === null ? (
            <Alert severity="info">{t("No disponible para tu cuenta.")}</Alert>
          ) : (
            <Box sx={{ display: "grid", gap: 1 }}>
              {[
                [t("Pedidos de entrada"), fmtQuantity(orders.inputOrders)],
                [t("Planificados"), `${fmtQuantity(orders.planned)} · ${pct(orders.plannedPercent)}`],
                [t("Sin planificar"), fmtQuantity(orders.unplanned)],
                [t("Listos para planificar"), fmtQuantity(orders.readyToPlan)],
                [t("Sin liberar"), fmtQuantity(orders.notReady)],
                [t("Cancelados"), fmtQuantity(orders.cancelled)],
              ].map(([label, value]) => (
                <Box key={label} sx={{ display: "flex", justifyContent: "space-between", gap: 1 }}>
                  <Typography variant="body2" color="text.secondary">{label}</Typography>
                  <Typography variant="body2" sx={{ fontWeight: 700, fontVariantNumeric: "tabular-nums" }}>{value}</Typography>
                </Box>
              ))}
            </Box>
          )}
        </AppCard>

        <AppCard title={t("Ofertas a transportista")}>
          {tenders === null ? (
            <Alert severity="info">{t("No disponible para tu cuenta.")}</Alert>
          ) : (
            <Box sx={{ display: "grid", gap: 1 }}>
              {[
                [t("Intentos"), fmtQuantity(tenders.attempts)],
                [t("Aceptadas"), `${fmtQuantity(tenders.accepted)} · ${pct(tenders.acceptancePercent)}`],
                [t("Rechazadas"), `${fmtQuantity(tenders.rejected)} · ${pct(tenders.rejectionPercent)}`],
                [t("Esperando respuesta"), fmtQuantity(tenders.awaitingResponse)],
                [t("Vencidas"), fmtQuantity(tenders.expired)],
              ].map(([label, value]) => (
                <Box key={label} sx={{ display: "flex", justifyContent: "space-between", gap: 1 }}>
                  <Typography variant="body2" color="text.secondary">{label}</Typography>
                  <Typography variant="body2" sx={{ fontWeight: 700, fontVariantNumeric: "tabular-nums" }}>{value}</Typography>
                </Box>
              ))}
            </Box>
          )}
        </AppCard>

        <AppCard title={t("Costo")}>
          {cost === null ? (
            <Alert severity="info">{t("No disponible para tu cuenta.")}</Alert>
          ) : cost.length === 0 ? (
            <Typography variant="body2" color="text.secondary">{t("Ningún envío del rango está costeado.")}</Typography>
          ) : (
            // Uno por moneda: no hay total general, y no lo habrá. Sumar dos monedas es inventar
            // un tipo de cambio que el producto no conoce.
            <Box sx={{ display: "grid", gap: 2 }}>
              {cost.map((entry) => (
                <Box key={entry.currency}>
                  <Box sx={{ display: "flex", alignItems: "center", gap: 1, mb: 0.5 }}>
                    <PaidRounded sx={{ fontSize: 17, color: "text.disabled" }} />
                    <Typography variant="subtitle2">{entry.currency}</Typography>
                  </Box>
                  {[
                    [t("Estimado"), fmtMoney(entry.estimatedAmount, entry.currency)],
                    [t("Real"), fmtMoney(entry.actualAmount, entry.currency)],
                    [t("Diferencia"), entry.variance === null ? "-" : `${fmtMoney(entry.variance, entry.currency)} · ${pct(entry.variancePercent)}`],
                  ].map(([label, value]) => (
                    <Box key={label} sx={{ display: "flex", justifyContent: "space-between", gap: 1 }}>
                      <Typography variant="body2" color="text.secondary">{label}</Typography>
                      <Typography variant="body2" sx={{ fontWeight: 700, fontVariantNumeric: "tabular-nums" }}>{value}</Typography>
                    </Box>
                  ))}
                </Box>
              ))}
            </Box>
          )}
        </AppCard>
      </Box>

      {/* La tabla diaria es la vista alternativa de las dos gráficas de arriba: los mismos
          números en texto, para quien necesite leerlos exactos o copiarlos. */}
      <AppCard title={t("Detalle por día")} flush>
        <TableContainer sx={{ maxHeight: 420 }}>
          <Table size="small" stickyHeader sx={dataTableSx}>
            <TableHead>
              <TableRow>
                <TableCell>{t("Fecha")}</TableCell>
                <TableCell className="numeric-col">{t("Envíos")}</TableCell>
                <TableCell className="numeric-col">{t("Completados")}</TableCell>
                <TableCell className="numeric-col">{t("Cancelados")}</TableCell>
                <TableCell className="numeric-col">{t("Salieron tarde")}</TableCell>
                <TableCell className="numeric-col">{t("Salidas a tiempo")}</TableCell>
                <TableCell className="numeric-col">{t("Entregas")}</TableCell>
                <TableCell className="numeric-col">{t("Entregadas")}</TableCell>
                <TableCell className="numeric-col">{t("Incidencias")}</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {daily.map((row) => (
                <TableRow key={row.date} hover>
                  <TableCell sx={{ fontWeight: 600 }}>{fmtDate(row.date)}</TableCell>
                  <TableCell className="numeric-col">{fmtQuantity(row.trips)}</TableCell>
                  <TableCell className="numeric-col">{fmtQuantity(row.tripsCompleted)}</TableCell>
                  <TableCell className="numeric-col">{fmtQuantity(row.tripsCancelled)}</TableCell>
                  <TableCell className="numeric-col">{fmtQuantity(row.departuresLate)}</TableCell>
                  <TableCell className="numeric-col">{pct(row.onTimeDeparturePercent)}</TableCell>
                  <TableCell className="numeric-col">{fmtQuantity(row.deliveriesRecorded)}</TableCell>
                  <TableCell className="numeric-col">{fmtQuantity(row.deliveriesDelivered)}</TableCell>
                  <TableCell className="numeric-col">{fmtQuantity(row.exceptions)}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      </AppCard>
    </>
  );
}
