import { useQuery } from "@tanstack/react-query";
import { useState, type ReactNode } from "react";
import { Link as RouterLink, useSearchParams } from "react-router-dom";
import {
  Alert, Box, Button, Chip, Link, Table, TableBody, TableCell, TableContainer, TableHead, TableRow,
  Tooltip, Typography,
} from "@mui/material";
import {
  DownloadRounded, InfoOutlined, LeaderboardRounded, LocalShippingRounded, HelpOutlineRounded,
  ListAltRounded,
} from "@mui/icons-material";
import type { ApiError } from "../../shared/api/httpClient";
import { saveDownloadedFile } from "../../shared/api/httpClient";
import {
  downloadCarrierScorecardCsv, fetchCarrierScorecard, fetchCarrierScorecardTrips,
  type CarrierScorecardRow, type CarrierScorecardView,
} from "../../shared/api/carrierScorecardApi";
import { describeApiError } from "../../shared/api/problemMessages";
import { useCompany } from "../../shared/company/CompanyContext";
import {
  AppCard, ErrorState, FilterBar, FormDrawer, KpiCard, LoadingState, PageHeader, StatusChip, dataTableSx,
} from "../../shared/ui/components";
import { ICON_TINTS } from "../../shared/ui/navConfig";
import type { StatusTone } from "../../theme";
import { notifyError } from "../../lib/ui";
import { t } from "../../lib/i18n";
import { enumLabel } from "../../lib/enums";
import { fmtDate, fmtDateTime, fmtDecimal, fmtMinutes, fmtMoney, fmtPercent, fmtQuantity } from "../../lib/locale";

/**
 * Scorecard de transportistas (ITEM 3, `docs/domain/CARRIER_SCORECARD_V1.md`): por transportista —y la
 * flota propia— cómo corrieron sus viajes en el periodo.
 *
 * **Nada se calcula aquí.** Cada porcentaje es el que dividió el backend; un `null` es "sin datos"
 * —no hubo denominador— y se pinta así, nunca como 0%. Al lado de cada porcentaje va su
 * denominador: 50% de 2 no es lo mismo que 50% de 400.
 *
 * El semáforo es presentación y nada más: verde, ámbar o rojo según umbrales fijos de esta
 * pantalla (`TONE_RULES`). No es una regla de negocio y no viaja a ningún otro sitio.
 */
export function CarrierScorecardPage() {
  const { selected } = useCompany();
  const companyId = selected?.id ?? "";
  const [searchParams, setSearchParams] = useSearchParams();

  const [applied, setApplied] = useState({ from: "", to: "" });
  const [exporting, setExporting] = useState(false);
  // `?carrier=` abre el detalle: es lo que usa "Ver desempeño" desde el maestro de transportistas.
  const drillCarrier = searchParams.get("carrier");

  const range = { from: applied.from || undefined, to: applied.to || undefined };
  const scorecardQuery = useQuery({
    queryKey: ["carrier-scorecard", companyId, applied],
    queryFn: ({ signal }) => fetchCarrierScorecard({ companyId, ...range, signal }),
    enabled: companyId !== "",
  });

  function openDrill(carrierKey: string | null) {
    const next = new URLSearchParams(searchParams);
    if (carrierKey) next.set("carrier", carrierKey);
    else next.delete("carrier");
    setSearchParams(next, { replace: true });
  }

  async function exportCsv() {
    setExporting(true);
    try {
      const file = await downloadCarrierScorecardCsv({ companyId, ...range });
      saveDownloadedFile(file, "carrier-scorecard.csv");
    } catch (error) {
      notifyError(t("No se pudo exportar"), describeApiError(error as ApiError));
    } finally {
      setExporting(false);
    }
  }

  if (scorecardQuery.isPending) return <LoadingState label={t("Cargando el scorecard...")} />;
  if (scorecardQuery.isError) {
    return (
      <ErrorState
        message={describeApiError(scorecardQuery.error as ApiError)}
        onRetry={() => void scorecardQuery.refetch()}
      />
    );
  }

  const report = scorecardQuery.data;
  const totalTrips = report.carriers.reduce((sum, row) => sum + row.trips, 0);

  return (
    <>
      <PageHeader
        icon={<LeaderboardRounded />}
        tint={ICON_TINTS["/reporting/carriers"]}
        title={t("Scorecard de transportistas")}
        subtitle={t("{{from}} → {{to}} · {{days}} días", {
          from: fmtDate(report.from), to: fmtDate(report.to), days: report.days,
        })}
        meta={<Chip size="small" variant="outlined" label={t("Al {{time}}", { time: fmtDateTime(report.generatedAt) })} />}
        onRefresh={() => void scorecardQuery.refetch()}
        refreshing={scorecardQuery.isFetching}
        actions={
          <Button variant="contained" startIcon={<DownloadRounded />} disabled={exporting} onClick={() => void exportCsv()}>
            {t("Exportar CSV")}
          </Button>
        }
      />

      <FilterBar
        value={applied}
        defaults={{ from: "", to: "" }}
        onChange={setApplied}
        fields={[{ type: "dateRange", from: "from", to: "to", label: t("Periodo") }]}
      />

      <Box sx={{
        display: "grid", gap: 2, mb: 3,
        gridTemplateColumns: { xs: "1fr", sm: "repeat(3, minmax(0,1fr))" },
      }}>
        <KpiCard icon={<LeaderboardRounded />} color="primary.main" title={t("Transportistas evaluados")}
          value={fmtQuantity(report.carriers.length)} sub={t("Con viajes, ofertas o facturas en el periodo")} />
        <KpiCard icon={<LocalShippingRounded />} color="info.main" title={t("Viajes atribuidos")}
          value={fmtQuantity(totalTrips)} sub={t("Sin contar los cancelados")} />
        <KpiCard icon={<HelpOutlineRounded />} color="warning.main" title={t("Viajes sin transportista")}
          value={fmtQuantity(report.unattributedTrips)} sub={t("Sin transportista ni vehículo: no cuentan para nadie")} />
      </Box>

      <AppCard title={t("Desempeño por transportista")} flush>
        {report.carriers.length === 0 ? (
          <Typography variant="body2" color="text.secondary" sx={{ p: 2 }}>
            {t("Ningún transportista tuvo viajes, ofertas ni facturas en el periodo.")}
          </Typography>
        ) : (
          <ScorecardTable report={report} onDrill={(row) => openDrill(row.carrierKey)} />
        )}
      </AppCard>

      <Definitions report={report} />

      <TripsDrawer
        companyId={companyId}
        carrierKey={drillCarrier}
        range={range}
        fallbackName={nameOf(report.carriers.find((row) => row.carrierKey === drillCarrier))}
        onClose={() => openDrill(null)}
      />
    </>
  );
}

// --- the table ---------------------------------------------------------------------------

/** Umbrales del semáforo, sólo de esta pantalla. `higherIsBetter` false: incidencias. */
const TONE_RULES = {
  punctuality: { good: 95, fair: 85 },
  acceptance: { good: 90, fair: 70 },
  cargo: { good: 95, fair: 80 },
  discrepancy: { good: 5, fair: 15 },
} as const;

function tone(value: number | null, rule: { good: number; fair: number }, higherIsBetter = true): StatusTone {
  if (value === null) return "neutral";
  if (higherIsBetter) return value >= rule.good ? "done" : value >= rule.fair ? "inProgress" : "overdue";
  return value <= rule.good ? "done" : value <= rule.fair ? "inProgress" : "overdue";
}

function nameOf(row: CarrierScorecardRow | undefined): string | null {
  if (!row) return null;
  return row.ownFleet ? t("Flota propia") : (row.carrierName ?? row.carrierCode ?? "—");
}

/** Un porcentaje con su semáforo y su denominador, o "sin datos". */
function Rate({ value, of, rule, higherIsBetter }: {
  value: number | null; of: number; rule?: { good: number; fair: number }; higherIsBetter?: boolean;
}) {
  if (value === null) {
    return <Typography variant="body2" color="text.disabled" component="span">{t("sin datos")}</Typography>;
  }
  return (
    <Box sx={{ display: "inline-flex", flexDirection: "column", alignItems: "flex-end", gap: 0.25 }}>
      {rule
        ? <StatusChip label={fmtPercent(value, 1)} tone={tone(value, rule, higherIsBetter)} />
        : <Typography variant="body2" sx={{ fontWeight: 700 }}>{fmtPercent(value, 1)}</Typography>}
      <Typography variant="caption" color="text.secondary">{t("de {{n}}", { n: fmtQuantity(of) })}</Typography>
    </Box>
  );
}

/** Una cabecera con su definición a un toque. */
function Header({ label, definition, numeric = true }: { label: string; definition: string; numeric?: boolean }) {
  return (
    <TableCell className={numeric ? "numeric-col" : undefined}>
      <Box sx={{ display: "inline-flex", alignItems: "center", gap: 0.5 }}>
        {label}
        <Tooltip title={definition}>
          <InfoOutlined aria-label={definition} sx={{ fontSize: 14, color: "text.disabled" }} />
        </Tooltip>
      </Box>
    </TableCell>
  );
}

function PerCurrency({ entries }: { entries: ReactNode[] }) {
  if (entries.length === 0) {
    return <Typography variant="body2" color="text.disabled" component="span">{t("sin datos")}</Typography>;
  }
  return <Box sx={{ display: "grid", gap: 0.5, justifyItems: "end" }}>{entries}</Box>;
}

function ScorecardTable({ report, onDrill }: { report: CarrierScorecardView; onDrill: (row: CarrierScorecardRow) => void }) {
  return (
    <TableContainer>
      <Table size="small" sx={dataTableSx}>
        <TableHead>
          <TableRow>
            <TableCell>{t("Transportista")}</TableCell>
            <Header label={t("Viajes")} definition={DEFINITIONS.trips} />
            <Header label={t("Salida a tiempo")} definition={DEFINITIONS.onTime} />
            <Header label={t("En ventana")} definition={DEFINITIONS.inWindow} />
            <Header label={t("Entregas exitosas")} definition={DEFINITIONS.delivery} />
            <Header label={t("Incidencias / 100 viajes")} definition={DEFINITIONS.exceptions} />
            {report.tendersVisible && <Header label={t("Ofertas aceptadas")} definition={DEFINITIONS.acceptance} />}
            {report.tendersVisible && <Header label={t("Respuesta media")} definition={DEFINITIONS.response} />}
            {report.documentsVisible && <Header label={t("Cargos devueltos")} definition={DEFINITIONS.cargo} />}
            {report.invoicesVisible && <Header label={t("Facturas con diferencia")} definition={DEFINITIONS.invoices} />}
            {report.costVisible && <Header label={t("Costo por viaje")} definition={DEFINITIONS.costPerTrip} />}
            {report.costVisible && <Header label={t("Costo por kg")} definition={DEFINITIONS.costPerKg} />}
            <TableCell aria-label={t("Acciones")} />
          </TableRow>
        </TableHead>
        <TableBody>
          {report.carriers.map((row) => (
            <TableRow key={row.carrierKey} hover>
              <TableCell sx={{ fontWeight: 600 }}>
                {nameOf(row)}
                {row.carrierCode && !row.ownFleet && (
                  <Typography variant="caption" color="text.secondary" sx={{ display: "block" }}>{row.carrierCode}</Typography>
                )}
              </TableCell>
              <TableCell className="numeric-col">
                {fmtQuantity(row.trips)}
                <Typography variant="caption" color="text.secondary" sx={{ display: "block" }}>
                  {t("{{n}} salieron", { n: fmtQuantity(row.tripsExecuted) })}
                </Typography>
              </TableCell>
              <TableCell className="numeric-col">
                <Rate value={row.onTimeDeparturePercent} of={row.departuresMeasured} rule={TONE_RULES.punctuality} />
              </TableCell>
              <TableCell className="numeric-col">
                <Rate value={row.inWindowPercent} of={row.serviceWindowsMeasured} rule={TONE_RULES.punctuality} />
              </TableCell>
              <TableCell className="numeric-col">
                <Rate value={row.deliverySuccessPercent} of={row.deliveriesRecorded} rule={TONE_RULES.punctuality} />
              </TableCell>
              <TableCell className="numeric-col">
                {row.exceptionsPer100Trips === null
                  ? <Typography variant="body2" color="text.disabled" component="span">{t("sin datos")}</Typography>
                  : (
                    <>
                      {fmtDecimal(row.exceptionsPer100Trips, 1)}
                      <Typography variant="caption" color="text.secondary" sx={{ display: "block" }}>
                        {t("{{n}} incidencias", { n: fmtQuantity(row.exceptions) })}
                      </Typography>
                    </>
                  )}
              </TableCell>
              {report.tendersVisible && (
                <TableCell className="numeric-col">
                  <Rate
                    value={row.tenders?.acceptancePercent ?? null}
                    of={(row.tenders?.accepted ?? 0) + (row.tenders?.rejected ?? 0) + (row.tenders?.expired ?? 0)}
                    rule={TONE_RULES.acceptance}
                  />
                </TableCell>
              )}
              {report.tendersVisible && (
                <TableCell className="numeric-col">
                  {row.tenders?.averageResponseMinutes == null
                    ? <Typography variant="body2" color="text.disabled" component="span">{t("sin datos")}</Typography>
                    : fmtMinutes(row.tenders.averageResponseMinutes)}
                </TableCell>
              )}
              {report.documentsVisible && (
                <TableCell className="numeric-col">
                  <Rate value={row.documents?.cargoReturnPercent ?? null} of={row.documents?.delivered ?? 0} rule={TONE_RULES.cargo} />
                </TableCell>
              )}
              {report.invoicesVisible && (
                <TableCell className="numeric-col">
                  <PerCurrency entries={(row.invoices ?? []).map((invoice) => (
                    <Box key={invoice.currency} sx={{ textAlign: "right" }}>
                      <Rate value={invoice.discrepancyRatePercent} of={invoice.invoices}
                        rule={TONE_RULES.discrepancy} higherIsBetter={false} />
                      {invoice.withDiscrepancy > 0 && (
                        <Typography variant="caption" color="text.secondary" sx={{ display: "block" }}>
                          {fmtMoney(invoice.amountObserved, invoice.currency)}
                        </Typography>
                      )}
                    </Box>
                  ))} />
                </TableCell>
              )}
              {report.costVisible && (
                <TableCell className="numeric-col">
                  <PerCurrency entries={(row.cost ?? []).map((cost) => (
                    <Typography key={cost.currency} variant="body2">
                      {cost.costPerTrip === null ? t("sin datos") : fmtMoney(cost.costPerTrip, cost.currency)}
                    </Typography>
                  ))} />
                </TableCell>
              )}
              {report.costVisible && (
                <TableCell className="numeric-col">
                  <PerCurrency entries={(row.cost ?? []).map((cost) => (
                    <Typography key={cost.currency} variant="body2">
                      {cost.costPerKg === null ? t("sin datos") : `${fmtDecimal(cost.costPerKg, 4)} ${cost.currency}`}
                    </Typography>
                  ))} />
                </TableCell>
              )}
              <TableCell>
                <Button size="small" startIcon={<ListAltRounded />} onClick={() => onDrill(row)}>
                  {t("Ver viajes")}
                </Button>
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </TableContainer>
  );
}

// --- definitions -------------------------------------------------------------------------

/** Qué mide cada columna. Las mismas frases que `docs/domain/CARRIER_SCORECARD_V1.md`. */
const DEFINITIONS = {
  get trips() { return t("Viajes del periodo (por fecha de planificación) que no se cancelaron. Debajo, cuántos salieron (en tránsito o completados)."); },
  get onTime() { return t("Salidas reales no posteriores a la planificada, sobre los viajes con las dos horas registradas."); },
  get inWindow() { return t("Paradas con llegada dentro de su ventana de servicio (hora de la empresa), sobre las paradas con llegada y ventana."); },
  get delivery() { return t("Entregas completas sobre todas las entregas registradas: parciales, rechazadas, fallidas y no intentadas cuentan en contra."); },
  get exceptions() { return t("Incidencias reportadas en sus viajes por cada 100 viajes del periodo. Puede pasar de 100."); },
  get acceptance() { return t("Ofertas aceptadas sobre las resueltas por el transportista: aceptadas, rechazadas y vencidas sin respuesta."); },
  get response() { return t("Tiempo medio entre el envío de la oferta y la respuesta, sobre las aceptadas y rechazadas."); },
  get cargo() { return t("Documentos con el cargo firmado devuelto a la oficina, sobre los documentos entregados."); },
  get invoices() { return t("Facturas con al menos una diferencia en su último cotejo, sobre las facturas con fecha en el periodo. Una fila por moneda; debajo, lo facturado de más."); },
  get costPerTrip() { return t("Costo conocido (el real si se registró, si no el estimado) dividido entre los viajes con costo. Por moneda: no se convierte."); },
  get costPerKg() { return t("El costo de los viajes con peso asignado dividido entre esos kilos. Los viajes sin peso no entran."); },
};

function Definitions({ report }: { report: CarrierScorecardView }) {
  const rows: [string, string][] = [
    [t("Viajes"), DEFINITIONS.trips],
    [t("Salida a tiempo"), DEFINITIONS.onTime],
    [t("En ventana"), DEFINITIONS.inWindow],
    [t("Entregas exitosas"), DEFINITIONS.delivery],
    [t("Incidencias / 100 viajes"), DEFINITIONS.exceptions],
    ...(report.tendersVisible ? [[t("Ofertas aceptadas"), DEFINITIONS.acceptance], [t("Respuesta media"), DEFINITIONS.response]] as [string, string][] : []),
    ...(report.documentsVisible ? [[t("Cargos devueltos"), DEFINITIONS.cargo]] as [string, string][] : []),
    ...(report.invoicesVisible ? [[t("Facturas con diferencia"), DEFINITIONS.invoices]] as [string, string][] : []),
    ...(report.costVisible ? [[t("Costo por viaje"), DEFINITIONS.costPerTrip], [t("Costo por kg"), DEFINITIONS.costPerKg]] as [string, string][] : []),
  ];
  return (
    <Box sx={{ mt: 3 }}>
      <AppCard title={t("Cómo se calcula")}>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>
          {t("«sin datos» quiere decir que no hubo nada que medir — nunca 0%. El color es una ayuda de lectura de esta pantalla.")}
        </Typography>
        <Box component="dl" sx={{ m: 0, display: "grid", gap: 1, gridTemplateColumns: { xs: "1fr", md: "220px 1fr" } }}>
          {rows.map(([label, definition]) => (
            <Box key={label} sx={{ display: "contents" }}>
              <Typography component="dt" variant="body2" sx={{ fontWeight: 700 }}>{label}</Typography>
              <Typography component="dd" variant="body2" color="text.secondary" sx={{ m: 0 }}>{definition}</Typography>
            </Box>
          ))}
        </Box>
      </AppCard>
    </Box>
  );
}

// --- drill-down --------------------------------------------------------------------------

function TripsDrawer({ companyId, carrierKey, range, fallbackName, onClose }: {
  companyId: string;
  carrierKey: string | null;
  range: { from?: string; to?: string };
  fallbackName: string | null;
  onClose: () => void;
}) {
  const tripsQuery = useQuery({
    queryKey: ["carrier-scorecard-trips", companyId, carrierKey, range.from ?? "", range.to ?? ""],
    queryFn: ({ signal }) => fetchCarrierScorecardTrips({ companyId, carrier: carrierKey ?? "", ...range, signal }),
    enabled: companyId !== "" && carrierKey !== null,
  });
  const data = tripsQuery.data;
  const title = data?.ownFleet ? t("Flota propia") : (data?.carrierName ?? fallbackName ?? t("Transportista"));

  return (
    <FormDrawer
      open={carrierKey !== null}
      onClose={onClose}
      title={title}
      subtitle={data ? t("{{n}} viajes · {{from}} → {{to}}", {
        n: fmtQuantity(data.total), from: fmtDate(data.from), to: fmtDate(data.to),
      }) : undefined}
      icon={<ListAltRounded />}
      size="lg"
      loading={tripsQuery.isPending && carrierKey !== null}
    >
      {tripsQuery.isError ? (
        <Alert severity="error">{describeApiError(tripsQuery.error as ApiError)}</Alert>
      ) : data && (
        <>
          {data.truncated && (
            <Alert severity="info" sx={{ mb: 2 }}>
              {t("Se muestran los {{shown}} viajes más recientes de {{total}}. Acota el periodo para verlos todos.", {
                shown: fmtQuantity(data.trips.length), total: fmtQuantity(data.total),
              })}
            </Alert>
          )}
          {data.trips.length === 0 ? (
            <Typography variant="body2" color="text.secondary">{t("Sin viajes en el periodo.")}</Typography>
          ) : (
            <TableContainer>
              <Table size="small" sx={dataTableSx}>
                <TableHead>
                  <TableRow>
                    <TableCell>{t("Envío")}</TableCell>
                    <TableCell>{t("Fecha")}</TableCell>
                    <TableCell>{t("Estado")}</TableCell>
                    <TableCell>{t("Salida")}</TableCell>
                    <TableCell className="numeric-col">{t("En ventana")}</TableCell>
                    <TableCell className="numeric-col">{t("Entregas")}</TableCell>
                    <TableCell className="numeric-col">{t("Incidencias")}</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {data.trips.map((trip) => (
                    <TableRow key={trip.tripId} hover>
                      <TableCell sx={{ fontWeight: 600 }}>
                        <Link component={RouterLink} to={`/trips/${trip.tripId}`}>{trip.shipmentNumber}</Link>
                      </TableCell>
                      <TableCell>{fmtDate(trip.planningDate)}</TableCell>
                      <TableCell>{enumLabel("tripStatus", trip.status)}</TableCell>
                      <TableCell>
                        {trip.departureLate === null
                          ? <Typography variant="body2" color="text.disabled" component="span">{t("sin medir")}</Typography>
                          : <StatusChip label={trip.departureLate ? t("Tarde") : t("A tiempo")} tone={trip.departureLate ? "overdue" : "done"} />}
                      </TableCell>
                      <TableCell className="numeric-col">
                        {trip.windowsMeasured === 0 ? "—" : `${fmtQuantity(trip.windowsMeasured - trip.windowsMissed)} / ${fmtQuantity(trip.windowsMeasured)}`}
                      </TableCell>
                      <TableCell className="numeric-col">
                        {trip.deliveriesRecorded === 0 ? "—" : `${fmtQuantity(trip.deliveriesDelivered)} / ${fmtQuantity(trip.deliveriesRecorded)}`}
                      </TableCell>
                      <TableCell className="numeric-col">{fmtQuantity(trip.exceptions)}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </TableContainer>
          )}
        </>
      )}
    </FormDrawer>
  );
}
