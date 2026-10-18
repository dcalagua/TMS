import { useQuery } from "@tanstack/react-query";
import { useState, type ReactNode } from "react";
import {
  Alert, Box, Button, Chip, LinearProgress, Table, TableBody, TableCell, TableContainer,
  TableHead, TableRow, Typography,
} from "@mui/material";
import {
  BarChartRounded, DownloadRounded, LocalShippingRounded, ScheduleRounded,
  InventoryRounded, ReportProblemRounded, PaidRounded, AssignmentTurnedInRounded,
  ReceiptLongRounded, FactCheckRounded, TimerRounded,
} from "@mui/icons-material";
import type { ApiError } from "../../shared/api/httpClient";
import { saveDownloadedFile } from "../../shared/api/httpClient";
import { downloadKpiCsv, fetchKpiReport, type KpiSettlementView } from "../../shared/api/reportingApi";
import { describeApiError } from "../../shared/api/problemMessages";
import { useCompany } from "../../shared/company/CompanyContext";
import {
  AppCard, ErrorState, FilterBar, KpiCard, LoadingState, PageHeader, SectionHeader, dataTableSx,
} from "../../shared/ui/components";
import { ICON_TINTS } from "../../shared/ui/navConfig";
import { alpha } from "@mui/material/styles";
import { T } from "../../theme";
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

  // Vacío = los últimos 30 días que decide el backend. Se aplica al elegirlo: no hay borrador.
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
  const { shipments, service, exceptions, utilization, orders, tenders, cost, settlement, daily } = report;

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
            <Button variant="contained" startIcon={<DownloadRounded />} disabled={exporting} onClick={() => void exportCsv()}>
              {t("Exportar CSV")}
            </Button>
          </Box>
        }
      />

      <FilterBar
        value={applied}
        defaults={{ from: "", to: "" }}
        onChange={setApplied}
        fields={[{ type: "dateRange", from: "from", to: "to", label: t("Periodo") }]}
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

      <Box sx={{ display: "grid", gap: 2, mb: 3, gridTemplateColumns: { xs: "1fr", lg: "repeat(2, minmax(0,1fr))" } }}>
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
        <Box sx={{ display: "grid", columnGap: 3, rowGap: 2, gridTemplateColumns: { xs: "1fr", sm: "repeat(3, minmax(0,1fr))" } }}>
          <CapacityMetric
            icon={<LocalShippingRounded />} color="primary.main" title={t("Peso")}
            value={pct(utilization.weightPercent)} progress={utilization.weightPercent}
            sub={`${fmtWeightKg(utilization.weightUsedKg)} / ${fmtWeightKg(utilization.weightCapacityKg)}`}
          />
          <CapacityMetric
            icon={<LocalShippingRounded />} color="info.main" title={t("Volumen")}
            value={pct(utilization.volumePercent)} progress={utilization.volumePercent}
            sub={`${fmtVolumeM3(utilization.volumeUsedM3)} / ${fmtVolumeM3(utilization.volumeCapacityM3)}`}
          />
          <CapacityMetric
            icon={<LocalShippingRounded />} color="warning.main" title={t("Pallets")}
            value={pct(utilization.palletsPercent)} progress={utilization.palletsPercent}
            sub={`${fmtDecimal(utilization.palletsUsed)} / ${fmtDecimal(utilization.palletCapacity)}`}
          />
        </Box>
      </AppCard>

      <Box sx={{ display: "grid", gap: 2, my: 3, alignItems: "start", gridTemplateColumns: { xs: "1fr", md: "repeat(3, minmax(0,1fr))" } }}>
        <AppCard title={t("Pedidos")}>
          {orders === null ? (
            <Alert severity="info">{t("No disponible para tu cuenta.")}</Alert>
          ) : (
            <ValueList rows={[
                [t("Pedidos de entrada"), fmtQuantity(orders.inputOrders)],
                [t("Planificados"), `${fmtQuantity(orders.planned)} · ${pct(orders.plannedPercent)}`],
                [t("Sin planificar"), fmtQuantity(orders.unplanned)],
                [t("Listos para planificar"), fmtQuantity(orders.readyToPlan)],
                [t("Sin liberar"), fmtQuantity(orders.notReady)],
                [t("Cancelados"), fmtQuantity(orders.cancelled)],
              ]} />
          )}
        </AppCard>

        <AppCard title={t("Ofertas a transportista")}>
          {tenders === null ? (
            <Alert severity="info">{t("No disponible para tu cuenta.")}</Alert>
          ) : (
            <ValueList rows={[
                [t("Intentos"), fmtQuantity(tenders.attempts)],
                [t("Aceptadas"), `${fmtQuantity(tenders.accepted)} · ${pct(tenders.acceptancePercent)}`],
                [t("Rechazadas"), `${fmtQuantity(tenders.rejected)} · ${pct(tenders.rejectionPercent)}`],
                [t("Esperando respuesta"), fmtQuantity(tenders.awaitingResponse)],
                [t("Vencidas"), fmtQuantity(tenders.expired)],
              ]} />
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
                  <Box sx={{ display: "flex", alignItems: "center", gap: 1, mb: 0.75 }}>
                    <PaidRounded sx={{ fontSize: 17, color: "text.disabled" }} />
                    <Typography variant="subtitle2">{entry.currency}</Typography>
                  </Box>
                  <ValueList rows={[
                    [t("Estimado"), fmtMoney(entry.estimatedAmount, entry.currency)],
                    [t("Real"), fmtMoney(entry.actualAmount, entry.currency)],
                    [t("Diferencia"), entry.variance === null ? "-" : `${fmtMoney(entry.variance, entry.currency)} · ${pct(entry.variancePercent)}`],
                  ]} />
                </Box>
              ))}
            </Box>
          )}
        </AppCard>
      </Box>

      <SectionHeader title={t("Liquidación de fletes")} level={2} />
      {settlement === null ? (
        <AppCard title={t("Auditoría de facturas de transportista")}>
          <Alert severity="info">{t("No disponible para tu cuenta.")}</Alert>
        </AppCard>
      ) : (
        <SettlementSection settlement={settlement} />
      )}

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

/** Una cifra que puede no existir. La raya es la respuesta honesta; 0 sería una afirmación. */
const orDash = (value: number | null, format: (v: number) => string) => (value === null ? "—" : format(value));

/**
 * Un ciclo en horas, o en días cuando pasa de dos: "36 h" se lee de un vistazo, "312 h" no.
 * El número es el del backend; aquí sólo se elige la unidad.
 */
function cycle(hours: number | null): string {
  if (hours === null) return "—";
  return hours < 48 ? `${fmtDecimal(hours, 1)} h` : `${fmtDecimal(hours / 24, 1)} d`;
}

/**
 * La auditoría de flete del rango: cuántas facturas, cuántas con diferencia, cuánto tardan en
 * aprobarse y, por transportista, cuánto se observó facturado de más y cuánto de eso se rechazó.
 *
 * Una fila por transportista **y** moneda: no hay tipo de cambio en TMS, así que dos monedas del
 * mismo transportista son dos filas y nunca una suma.
 */
function SettlementSection({ settlement }: { settlement: KpiSettlementView }) {
  const pct = (value: number | null) => orDash(value, (v) => fmtPercent(v, 1));
  return (
    <Box sx={{ mb: 3 }}>
      <Box sx={{
        display: "grid", gap: 2, mb: 2,
        gridTemplateColumns: { xs: "1fr", sm: "repeat(2, minmax(0,1fr))", lg: "repeat(4, minmax(0,1fr))" },
      }}>
        <KpiCard
          icon={<ReceiptLongRounded />} color="primary.main" title={t("Facturas")}
          value={fmtQuantity(settlement.invoicesReceived)}
          sub={t("{{n}} aprobadas", { n: fmtQuantity(settlement.invoicesApproved) })}
        />
        <KpiCard
          icon={<FactCheckRounded />} color="warning.main" title={t("Con diferencia")}
          value={pct(settlement.discrepancyRatePercent)}
          sub={t("{{n}} facturas", { n: fmtQuantity(settlement.invoicesWithDiscrepancy) })}
        />
        <KpiCard
          icon={<TimerRounded />} color="info.main" title={t("Ciclo hasta aprobar (mediana)")}
          value={cycle(settlement.cycleMedianHours)} sub={t("De recibida a aprobada")}
        />
        <KpiCard
          icon={<TimerRounded />} color="error.main" title={t("Ciclo hasta aprobar (p90)")}
          value={cycle(settlement.cycleP90Hours)} sub={t("Nueve de cada diez, en este plazo o menos")}
        />
      </Box>

      <AppCard title={t("Por transportista")} flush>
        {settlement.byCarrier.length === 0 ? (
          <Typography variant="body2" color="text.secondary" sx={{ p: 2 }}>
            {t("Ninguna factura con fecha en el rango.")}
          </Typography>
        ) : (
          <TableContainer>
            <Table size="small" sx={dataTableSx}>
              <TableHead>
                <TableRow>
                  <TableCell>{t("Transportista")}</TableCell>
                  <TableCell>{t("Moneda")}</TableCell>
                  <TableCell className="numeric-col">{t("Facturas")}</TableCell>
                  <TableCell className="numeric-col">{t("Aprobadas")}</TableCell>
                  <TableCell className="numeric-col">{t("Con diferencia")}</TableCell>
                  <TableCell className="numeric-col">{t("Observado")}</TableCell>
                  <TableCell className="numeric-col">{t("Ahorrado")}</TableCell>
                  <TableCell className="numeric-col">{t("Ciclo mediana")}</TableCell>
                  <TableCell className="numeric-col">{t("Ciclo p90")}</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {settlement.byCarrier.map((row) => (
                  <TableRow key={`${row.carrierId}|${row.currency}`} hover>
                    <TableCell sx={{ fontWeight: 600 }}>{row.carrierName ?? "—"}</TableCell>
                    <TableCell>{row.currency}</TableCell>
                    <TableCell className="numeric-col">{fmtQuantity(row.invoices)}</TableCell>
                    <TableCell className="numeric-col">{fmtQuantity(row.approved)}</TableCell>
                    <TableCell className="numeric-col">
                      {`${fmtQuantity(row.withDiscrepancy)} · ${pct(row.discrepancyRatePercent)}`}
                    </TableCell>
                    <TableCell className="numeric-col">{fmtMoney(row.amountObserved, row.currency)}</TableCell>
                    <TableCell className="numeric-col">{fmtMoney(row.amountSaved, row.currency)}</TableCell>
                    <TableCell className="numeric-col">{cycle(row.cycleMedianHours)}</TableCell>
                    <TableCell className="numeric-col">{cycle(row.cycleP90Hours)}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableContainer>
        )}
      </AppCard>
      <Typography variant="caption" color="text.secondary" sx={{ display: "block", mt: 1 }}>
        {t("Observado: lo facturado por encima de lo esperado en las facturas con diferencia. Ahorrado: la parte de eso que se rechazó.")}
      </Typography>
    </Box>
  );
}

/** Una lista etiqueta a la izquierda, valor a la derecha, con divisores finos entre filas. */
function ValueList({ rows }: { rows: [string, string][] }) {
  return (
    <Box>
      {rows.map(([label, value]) => (
        <Box key={label} sx={{
          display: "flex", justifyContent: "space-between", alignItems: "baseline", gap: 1, py: 1.1,
          "&:not(:last-of-type)": { borderBottom: "1px solid", borderBottomColor: "divider" },
          "&:first-of-type": { pt: 0.25 },
          "&:last-of-type": { pb: 0 },
        }}>
          <Typography variant="body2" color="text.secondary">{label}</Typography>
          <Typography variant="body2" sx={{ fontWeight: 700, fontVariantNumeric: "tabular-nums", textAlign: "right" }}>{value}</Typography>
        </Box>
      ))}
    </Box>
  );
}

/**
 * Una dimensión del uso de capacidad en línea: icono y etiqueta a la izquierda, el porcentaje a
 * la derecha, la barra debajo y los números absolutos al pie. El porcentaje es el del backend;
 * sin porcentaje no hay barra, igual que antes.
 */
function CapacityMetric({ icon, title, value, progress, sub, color }: {
  icon: ReactNode; title: string; value: string; progress: number | null; sub: string; color: string;
}) {
  return (
    <Box sx={{ minWidth: 0 }}>
      <Box sx={{ display: "flex", alignItems: "center", gap: 0.75, mb: 0.75 }}>
        <Box aria-hidden sx={{ color, display: "flex", "& svg": { fontSize: 17 } }}>{icon}</Box>
        <Typography sx={{ flex: 1, minWidth: 0, fontSize: T.body - 0.5, fontWeight: 700, color: "text.secondary" }} noWrap>
          {title}
        </Typography>
        <Typography component="div" sx={{
          fontSize: T.figure - 4, fontWeight: 800, lineHeight: 1, letterSpacing: "-0.02em",
          fontVariantNumeric: "tabular-nums", whiteSpace: "nowrap",
        }}>
          {value}
        </Typography>
      </Box>
      {progress !== null && (
        <LinearProgress
          variant="determinate" value={Math.max(0, Math.min(100, progress))}
          sx={(th) => ({
            height: 6, borderRadius: "3px", bgcolor: alpha(th.palette.text.primary, 0.08),
            "& .MuiLinearProgress-bar": { borderRadius: "3px", bgcolor: color },
          })}
        />
      )}
      <Typography sx={{ mt: 1, fontSize: T.label + 0.5, color: "text.secondary", fontVariantNumeric: "tabular-nums" }} noWrap>
        {sub}
      </Typography>
    </Box>
  );
}
