import { useQuery } from "@tanstack/react-query";
import { useMemo, useState } from "react";
import {
  Alert, Box, Button, Chip, IconButton, MenuItem, Table, TableBody, TableCell, TableContainer, TableHead,
  TableRow, TextField, Tooltip, Typography,
} from "@mui/material";
import { alpha } from "@mui/material/styles";
import {
  WarehouseRounded, DescriptionRounded, ContentCopyRounded, InventoryRounded, TaskAltRounded,
  CancelRounded, CircleRounded,
} from "@mui/icons-material";
import type { ApiError } from "../../shared/api/httpClient";
import type { TripView } from "../../shared/api/planningApi";
import { describeApiError } from "../../shared/api/problemMessages";
import {
  fetchTripWarehouseRawDocument, type DispatchDocumentView, type TripWarehouseView,
} from "../../shared/api/warehouseApi";
import {
  DetailGrid, DetailItem, FormDrawer, LoadingState, StatusChip,
} from "../../shared/ui/components";
import { DetailSection, KeyFacts } from "../../shared/ui/components/DetailLayout";
import { R } from "../../theme";
import { WorkspaceCard } from "./WorkspaceCard";
import { enumLabel } from "../../lib/enums";
import { t } from "../../lib/i18n";
import { fmtDateTime, fmtDecimal, fmtQuantity, fmtVolumeM3, fmtWeightKg } from "../../lib/locale";
import { notifyError, notifySuccess } from "../../lib/ui";
import {
  countBySeverity, orderMatchTone, outcomeTone, planVsReal, severityTone, sortDocuments, sortMilestones,
  verificationTone,
} from "./warehouseDispatch";

interface TripWarehouseCardProps {
  companyId: string;
  trip: TripView;
  warehouse: TripWarehouseView | undefined;
  loading: boolean;
  failed: boolean;
  /** orderId → número de pedido, sacado de las asignaciones que la pantalla ya tiene. */
  orderNumbers: Map<string, string>;
}

const MILESTONE_ICON: Record<string, typeof CircleRounded> = {
  LOADING_STARTED: InventoryRounded,
  LOAD_READY: TaskAltRounded,
  LOAD_CANCELLED: CancelRounded,
};

const MILESTONE_COLOR: Record<string, string> = {
  LOADING_STARTED: "info.main",
  LOAD_READY: "success.main",
  LOAD_CANCELLED: "error.main",
};

/**
 * "Despacho de almacén" (ADR-013): cómo salió el envío, qué informó el almacén y en qué se
 * diferencia del plan.
 *
 * Plan y realidad lado a lado y nunca fundidos: el plan no se reescribe con lo que salió, así
 * que esta tarjeta no ofrece "aceptar" una diferencia. Solo informa. La conciliación la hizo el
 * servidor, y cada diferencia llega con su código, su severidad y su frase.
 */
export function TripWarehouseCard({ companyId, trip, warehouse, loading, failed, orderNumbers }: TripWarehouseCardProps) {
  const documents = useMemo(() => sortDocuments(warehouse?.documents ?? []), [warehouse]);
  const milestones = useMemo(() => sortMilestones(warehouse?.milestones ?? []), [warehouse]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [rawFor, setRawFor] = useState<DispatchDocumentView | null>(null);
  const document = documents.find((entry) => entry.id === selectedId) ?? documents[0] ?? null;

  const title = t("Despacho de almacén");
  const icon = <WarehouseRounded />;

  if (loading) {
    return <WorkspaceCard icon={icon} title={title}><LoadingState minHeight={100} /></WorkspaceCard>;
  }
  if (failed || !warehouse) {
    return (
      <WorkspaceCard icon={icon} title={title}>
        <Typography variant="body2" color="text.secondary">
          {t("No se pudo consultar el despacho del almacén en este momento.")}
        </Typography>
      </WorkspaceCard>
    );
  }

  return (
    <WorkspaceCard
      icon={icon}
      title={title}
      actions={
        <StatusChip
          label={enumLabel("dispatchVerificationStatus", warehouse.verificationStatus)}
          tone={verificationTone(warehouse.verificationStatus)}
          variant="solid"
        />
      }
    >
      <DetailGrid columns={2}>
        <DetailItem label={t("Modo")} value={enumLabel("dispatchConfirmationMode", warehouse.dispatchConfirmationMode)} />
        <DetailItem
          label={t("Salió por")}
          value={warehouse.dispatchSource ? enumLabel("dispatchSource", warehouse.dispatchSource) : t("Todavía no ha salido")}
        />
        <DetailItem label={t("Salida registrada")} value={warehouse.actualDepartureAt ? fmtDateTime(warehouse.actualDepartureAt) : null} />
      </DetailGrid>

      {document === null ? (
        <Box sx={{ mt: 2 }}>
          <Alert severity="info" icon={<WarehouseRounded fontSize="inherit" />}>
            <Typography variant="body2" sx={{ fontWeight: 700 }}>{t("Sin confirmación de almacén")}</Typography>
            {warehouse.dispatchConfirmationMode === "EXTERNAL_REQUIRED"
              ? t("Este envío sale cuando el almacén (WMS) confirme el despacho.")
              : t("El almacén todavía no ha informado del despacho de este envío.")}
          </Alert>
        </Box>
      ) : (
        <DocumentSection
          trip={trip}
          document={document}
          documents={documents}
          onSelect={setSelectedId}
          onShowRaw={() => setRawFor(document)}
          orderNumbers={orderNumbers}
        />
      )}

      {milestones.length > 0 && (
        <Box sx={{ mt: 2 }}>
          <Typography variant="caption" sx={{ textTransform: "uppercase", letterSpacing: ".06em", fontWeight: 700, color: "text.secondary" }}>
            {t("Hitos del almacén")}
          </Typography>
          <Box sx={{ display: "grid", gap: 0.75, mt: 0.75 }}>
            {milestones.map((milestone) => {
              const Icon = MILESTONE_ICON[milestone.type] ?? CircleRounded;
              return (
                <Box key={milestone.eventId} sx={{ display: "flex", alignItems: "center", gap: 1, flexWrap: "wrap" }}>
                  <Icon sx={{ fontSize: 18, color: MILESTONE_COLOR[milestone.type] ?? "text.disabled" }} />
                  <Typography variant="body2" sx={{ fontWeight: 700 }}>
                    {enumLabel("warehouseMilestoneType", milestone.type)}
                  </Typography>
                  <Typography variant="caption" color="text.secondary">
                    {fmtDateTime(milestone.occurredAt)}
                    {milestone.loadReference && ` · ${milestone.loadReference}`}
                    {milestone.warehouseCode && ` · ${milestone.warehouseCode}`}
                  </Typography>
                </Box>
              );
            })}
          </Box>
        </Box>
      )}

      {rawFor && (
        <RawDocumentDrawer
          companyId={companyId}
          tripId={trip.id}
          document={rawFor}
          onClose={() => setRawFor(null)}
        />
      )}
    </WorkspaceCard>
  );
}

function DocumentSection({
  trip, document, documents, onSelect, onShowRaw, orderNumbers,
}: {
  trip: TripView;
  document: DispatchDocumentView;
  documents: DispatchDocumentView[];
  onSelect: (id: string) => void;
  onShowRaw: () => void;
  orderNumbers: Map<string, string>;
}) {
  const severities = countBySeverity(document);
  const rows = planVsReal(trip, document, fmtDateTime);
  const cellSx = { py: 0.5, px: 1, fontSize: 12.5 } as const;

  return (
    <Box sx={{ mt: 2, display: "grid", gap: 2 }}>
      <Box sx={{ display: "flex", alignItems: "center", gap: 1, flexWrap: "wrap" }}>
        {documents.length > 1 ? (
          <TextField
            select size="small" label={t("Documento")} value={document.id}
            onChange={(event) => onSelect(event.target.value)}
            sx={{ minWidth: 220 }}
          >
            {documents.map((entry) => (
              <MenuItem key={entry.id} value={entry.id}>
                {entry.dispatchReference} · r{entry.revision}{entry.current ? ` · ${t("vigente")}` : ""}
              </MenuItem>
            ))}
          </TextField>
        ) : (
          <Typography variant="body2" sx={{ fontWeight: 800 }}>
            {document.dispatchReference} · {t("revisión {{revision}}", { revision: document.revision })}
          </Typography>
        )}
        {!document.current && <Chip size="small" variant="outlined" label={t("Revisión anterior")} />}
        <StatusChip label={enumLabel("dispatchDocumentOutcome", document.outcome)} tone={outcomeTone(document.outcome)} />
        <StatusChip label={enumLabel("dispatchVerificationStatus", document.verificationStatus)} tone={verificationTone(document.verificationStatus)} />
        <Box sx={{ flex: 1 }} />
        <Button size="small" variant="outlined" color="inherit" sx={{ borderColor: "divider" }} startIcon={<DescriptionRounded />} onClick={onShowRaw}>
          {t("Ver documento original")}
        </Button>
      </Box>

      <DetailGrid columns={2}>
        <DetailItem label={t("Sistema")} value={document.sourceSystem} />
        <DetailItem label={t("Carga")} value={document.loadReference} />
        <DetailItem label={t("Despacho físico")} value={document.actualDispatchAt ? fmtDateTime(document.actualDispatchAt) : null} />
        <DetailItem label={t("Precinto")} value={document.sealNumber} />
        <DetailItem label={t("Guía de transporte")} value={document.transportDocumentNumber} />
        <DetailItem label={t("Recibido")} value={fmtDateTime(document.receivedAt)} />
        <DetailItem
          label={t("Bultos · peso · volumen")}
          value={[
            document.totalHandlingUnits === null ? null : fmtQuantity(document.totalHandlingUnits),
            document.totalWeightKg === null ? null : fmtWeightKg(document.totalWeightKg),
            document.totalVolumeM3 === null ? null : fmtVolumeM3(document.totalVolumeM3),
          ].filter(Boolean).join(" · ") || null}
          span
        />
      </DetailGrid>

      {/* Plan contra realidad. La marca de diferencia la pone el código de discrepancia del
          servidor, nunca una comparación hecha aquí. */}
      <TableContainer sx={{ border: "1px solid", borderColor: "divider", borderRadius: `${R.md}px` }}>
        <Table size="small" aria-label={t("Plan contra despachado")}>
          <TableHead>
            <TableRow>
              <TableCell sx={cellSx}>{t("Dato")}</TableCell>
              <TableCell sx={cellSx}>{t("Plan (TMS)")}</TableCell>
              <TableCell sx={cellSx}>{t("Despachado (almacén)")}</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {rows.map((row) => (
              <TableRow key={row.key} sx={row.differs ? { bgcolor: (th) => alpha(th.palette.error.main, 0.08) } : undefined}>
                <TableCell sx={{ ...cellSx, fontWeight: 700 }}>{t(row.label)}</TableCell>
                <TableCell sx={cellSx}>{row.planned ?? "-"}</TableCell>
                <TableCell sx={{ ...cellSx, color: row.differs ? "error.main" : undefined, fontWeight: row.differs ? 700 : undefined }}>
                  {row.dispatched ?? "-"}
                  {document.driverDocumentNumber && row.key === "driver" && (
                    <Typography component="span" variant="caption" color="text.secondary"> · {document.driverDocumentNumber}</Typography>
                  )}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </TableContainer>

      <Box>
        <Box sx={{ display: "flex", alignItems: "center", gap: 1, mb: 0.75, flexWrap: "wrap" }}>
          <Typography variant="caption" sx={{ textTransform: "uppercase", letterSpacing: ".06em", fontWeight: 700, color: "text.secondary" }}>
            {t("Diferencias")}
          </Typography>
          {severities.ERROR > 0 && <StatusChip label={`${severities.ERROR} ${t("errores")}`} tone="overdue" />}
          {severities.WARNING > 0 && <StatusChip label={`${severities.WARNING} ${t("advertencias")}`} tone="inProgress" />}
          {severities.INFO > 0 && <StatusChip label={`${severities.INFO} ${t("informativas")}`} tone="open" />}
        </Box>
        {document.discrepancies.length === 0 ? (
          <Typography variant="body2" color="text.secondary">{t("Sin diferencias con el plan.")}</Typography>
        ) : (
          <TableContainer sx={{ border: "1px solid", borderColor: "divider", borderRadius: `${R.md}px`, maxHeight: 280 }}>
            <Table size="small" stickyHeader aria-label={t("Diferencias con el plan")}>
              <TableHead>
                <TableRow>
                  <TableCell sx={cellSx}>{t("Diferencia")}</TableCell>
                  <TableCell sx={cellSx}>{t("Severidad")}</TableCell>
                  <TableCell sx={cellSx}>{t("Pedido")}</TableCell>
                  <TableCell sx={cellSx} align="right">{t("Línea")}</TableCell>
                  <TableCell sx={cellSx} align="right">{t("Plan")}</TableCell>
                  <TableCell sx={cellSx} align="right">{t("Despachado")}</TableCell>
                  <TableCell sx={cellSx}>{t("UdM")}</TableCell>
                  <TableCell sx={cellSx}>{t("Detalle")}</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {document.discrepancies.map((discrepancy, index) => (
                  <TableRow key={`${discrepancy.code}-${discrepancy.orderReference ?? ""}-${discrepancy.lineNumber ?? ""}-${index}`}>
                    <TableCell sx={cellSx}>
                      <Tooltip title={discrepancy.code}>
                        <span>{enumLabel("dispatchDiscrepancyCode", discrepancy.code)}</span>
                      </Tooltip>
                    </TableCell>
                    <TableCell sx={cellSx}>
                      <StatusChip label={enumLabel("discrepancySeverity", discrepancy.severity)} tone={severityTone(discrepancy.severity)} />
                    </TableCell>
                    <TableCell sx={cellSx}>{discrepancy.orderReference ?? "-"}</TableCell>
                    <TableCell sx={cellSx} align="right">{discrepancy.lineNumber ?? "-"}</TableCell>
                    <TableCell sx={cellSx} align="right">{discrepancy.planned === null ? "-" : fmtDecimal(discrepancy.planned, 3)}</TableCell>
                    <TableCell sx={cellSx} align="right">{discrepancy.dispatched === null ? "-" : fmtDecimal(discrepancy.dispatched, 3)}</TableCell>
                    <TableCell sx={cellSx}>{discrepancy.uom ?? "-"}</TableCell>
                    <TableCell sx={{ ...cellSx, minWidth: 160 }}>{discrepancy.detail ?? "-"}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableContainer>
        )}
      </Box>

      {document.orders.length > 0 && (
        <Box>
          <Typography variant="caption" sx={{ textTransform: "uppercase", letterSpacing: ".06em", fontWeight: 700, color: "text.secondary" }}>
            {t("Pedidos despachados")}
          </Typography>
          <Box sx={{ display: "grid", gap: 0.5, mt: 0.75 }}>
            {document.orders.map((order, index) => {
              const tmsNumber = order.orderId ? orderNumbers.get(order.orderId) : undefined;
              return (
                <Box
                  key={`${order.externalSource ?? ""}-${order.externalReference ?? ""}-${index}`}
                  sx={{ display: "flex", alignItems: "center", gap: 1, flexWrap: "wrap", py: 0.25 }}
                >
                  <Typography variant="body2" sx={{ fontWeight: 700, minWidth: 110 }}>
                    {tmsNumber ?? order.externalReference ?? "-"}
                  </Typography>
                  <Typography variant="caption" color="text.secondary">
                    {[
                      order.externalSource && order.externalReference ? `${order.externalSource}/${order.externalReference}` : null,
                      order.warehouseOrderNumber,
                      order.status,
                      order.handlingUnits === null ? null : `${fmtQuantity(order.handlingUnits)} ${t("bultos")}`,
                      order.weightKg === null ? null : fmtWeightKg(order.weightKg),
                    ].filter(Boolean).join(" · ")}
                  </Typography>
                  <Box sx={{ flex: 1 }} />
                  <StatusChip label={enumLabel("dispatchOrderMatch", order.matchResult)} tone={orderMatchTone(order.matchResult)} />
                </Box>
              );
            })}
          </Box>
        </Box>
      )}
    </Box>
  );
}

/**
 * El documento tal y como lo mandó el almacén, en un panel lateral y en monoespaciado. Se pide
 * al abrir el panel y no con la tarjeta: es texto largo que casi nadie lee.
 */
function RawDocumentDrawer({
  companyId, tripId, document, onClose,
}: { companyId: string; tripId: string; document: DispatchDocumentView; onClose: () => void }) {
  const rawQuery = useQuery({
    queryKey: ["trip-warehouse-raw", companyId, tripId, document.id],
    queryFn: ({ signal }) => fetchTripWarehouseRawDocument(companyId, tripId, document.id, signal),
    staleTime: Infinity,
    retry: false,
  });

  // Tal cual llegó. Si un servidor lo etiquetara como JSON, el cliente ya lo habría parseado y se
  // vuelve a serializar para no pintar "[object Object]".
  const raw: unknown = rawQuery.data;
  const text = typeof raw === "string" ? raw : raw == null ? "" : JSON.stringify(raw, null, 2);

  async function copy() {
    try {
      await navigator.clipboard.writeText(text);
      notifySuccess(t("Copiado al portapapeles"));
    } catch {
      notifyError(t("No se pudo copiar"));
    }
  }

  return (
    <FormDrawer
      open
      size="lg"
      title={t("Documento original")}
      subtitle={`${document.sourceSystem} · ${document.dispatchReference} · r${document.revision}`}
      icon={<DescriptionRounded />}
      onClose={onClose}
      footer={
        <>
          <Button variant="outlined" color="inherit" onClick={onClose} sx={{ borderColor: "divider" }}>{t("Cerrar")}</Button>
          <Button variant="contained" startIcon={<ContentCopyRounded />} disabled={text === ""} onClick={() => void copy()}>
            {t("Copiar")}
          </Button>
        </>
      }
    >
      <Box sx={{ display: "grid", gap: 2.5 }}>
        <Box sx={{ display: "flex", gap: 1, flexWrap: "wrap", alignItems: "center" }}>
          <StatusChip label={enumLabel("dispatchDocumentOutcome", document.outcome)} tone={outcomeTone(document.outcome)} variant="solid" />
          <StatusChip label={enumLabel("dispatchVerificationStatus", document.verificationStatus)} tone={verificationTone(document.verificationStatus)} />
          {!document.current && <Chip size="small" variant="outlined" label={t("Revisión anterior")} />}
        </Box>

        <KeyFacts columns={4} items={[
          { label: t("Sistema"), value: document.sourceSystem },
          { label: t("Despacho"), value: document.dispatchReference },
          { label: t("Revisión"), value: `r${document.revision}` },
          { label: t("Recibido"), value: fmtDateTime(document.receivedAt) },
        ]} />

        <DetailSection title={t("Contenido tal cual llegó")}>
          {rawQuery.isPending ? (
            <LoadingState minHeight={160} />
          ) : rawQuery.isError ? (
            <Alert severity="error">{describeApiError(rawQuery.error as ApiError)}</Alert>
          ) : (
            <Box sx={{ position: "relative" }}>
              <Tooltip title={t("Copiar")}>
                <IconButton size="small" onClick={() => void copy()} sx={{ position: "absolute", top: 4, right: 4 }} aria-label={t("Copiar")}>
                  <ContentCopyRounded fontSize="small" />
                </IconButton>
              </Tooltip>
              <Box
                component="pre"
                sx={{
                  m: 0, p: 1.5, pr: 5, borderRadius: `${R.md}px`, bgcolor: "action.hover", overflow: "auto", maxHeight: "60vh",
                  border: "1px solid", borderColor: "divider",
                  fontFamily: "ui-monospace, SFMono-Regular, Menlo, Consolas, monospace", fontSize: 12, lineHeight: 1.5,
                  whiteSpace: "pre-wrap", wordBreak: "break-all",
                }}
              >
                {text || t("El documento está vacío.")}
              </Box>
            </Box>
          )}
        </DetailSection>
      </Box>
    </FormDrawer>
  );
}
