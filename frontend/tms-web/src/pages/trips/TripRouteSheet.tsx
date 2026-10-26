import type { ReactNode } from "react";
import { Box, GlobalStyles, Typography } from "@mui/material";
import type { LogisticsDocumentView } from "../../shared/api/logisticsDocumentsApi";
import type { OrderDetailView } from "../../shared/api/ordersApi";
import type { TripDetailView } from "../../shared/api/planningApi";
import { authorizationLabel, logisticsDocumentLabel } from "../../shared/ui/logisticsDocumentLabel";
import { enumLabel } from "../../lib/enums";
import { t } from "../../lib/i18n";
import { fmtDate, fmtDateTime, fmtQuantity, fmtTime, fmtVolumeM3, fmtWeightKg } from "../../lib/locale";
import { stopsWithOrders } from "./routeSheet";

/**
 * Lo que la hoja de ruta necesita, ya leído. Separado de la página para que se pueda probar con un
 * fixture y para que la página sólo decida qué pedir.
 */
export interface RouteSheetData {
  companyName: string;
  detail: TripDetailView;
  /** Pedido por id, con sus líneas. Vacío cuando quien imprime no puede leer pedidos. */
  orders: Map<string, OrderDetailView>;
  /** Documentos del ERP por id de pedido (ADR-015/028). Vacío sin `orders.order:read`. */
  documents: Map<string, LogisticsDocumentView[]>;
  /** Si se pudieron leer los pedidos: sin eso la hoja dice que faltan las líneas en vez de omitirlas. */
  linesAvailable: boolean;
  printedAt: Date;
}

/** Una ventana horaria: hora local suelta (`HH:mm:ss`) o instante ISO, según de dónde venga. */
function fmtWindow(start: string | null | undefined, end: string | null | undefined): string {
  if (!start && !end) return "-";
  const part = (value: string | null | undefined) =>
    !value ? "--:--" : value.includes("T") ? fmtTime(value) : value.slice(0, 5);
  return `${part(start)} – ${part(end)}`;
}

const INK = "#111";
const MUTED = "#555";
const RULE = "#9a9a9a";

const cellSx = {
  border: `1px solid ${RULE}`, px: 0.75, py: 0.4, textAlign: "left", verticalAlign: "top",
  fontSize: 11, lineHeight: 1.35,
} as const;

function Fact({ label, children }: { label: string; children: ReactNode }) {
  return (
    <Box sx={{ minWidth: 0 }}>
      <Typography component="div" sx={{ fontSize: 9.5, textTransform: "uppercase", letterSpacing: 0.4, color: MUTED }}>
        {label}
      </Typography>
      <Typography component="div" sx={{ fontSize: 12, fontWeight: 600, color: INK, overflowWrap: "anywhere" }}>
        {children}
      </Typography>
    </Box>
  );
}

/** Una casilla para escribir a mano: rótulo arriba, espacio en blanco debajo. */
function WriteBox({ label, height = 34, wide = false }: { label: string; height?: number; wide?: boolean }) {
  return (
    <Box sx={{ border: `1px solid ${RULE}`, p: 0.5, minHeight: height, gridColumn: wide ? "1 / -1" : "auto" }}>
      <Typography component="div" sx={{ fontSize: 9.5, color: MUTED }}>{label}</Typography>
    </Box>
  );
}

/**
 * La hoja de ruta de un envío, para imprimir en A4 y entregar en garita.
 *
 * <h2>Por qué colores fijos y no los del tema</h2>
 * Es un papel. En modo oscuro el tema pinta texto claro sobre fondo oscuro, y eso impreso es una
 * hoja negra o texto invisible. Tinta negra sobre blanco, siempre, en pantalla y en papel.
 *
 * <h2>Por qué no lleva importes</h2>
 * La hoja viaja con el conductor y se firma en la puerta del cliente. Lo que vale un envío o una
 * factura es información comercial que tiene su propio permiso en la oficina; en una hoja que
 * pasa por tres manos no tiene lugar.
 *
 * <h2>Por qué las líneas son las del pedido</h2>
 * Un pedido repartido entre envíos viaja en parte. El envío sabe cuánto peso, volumen y pallets
 * lleva de él, pero no qué líneas: la hoja enseña las líneas del pedido y lo dice, en vez de
 * inventar un reparto por línea que nadie decidió.
 */
export function TripRouteSheet({ data }: { data: RouteSheetData }) {
  const { detail, orders, documents, linesAvailable } = data;
  const trip = detail.trip;
  const groups = stopsWithOrders(detail);
  const totals = detail.assignments.reduce(
    (sum, a) => ({ kg: sum.kg + a.assignedWeightKg, m3: sum.m3 + a.assignedVolumeM3, pallets: sum.pallets + a.assignedPallets }),
    { kg: 0, m3: 0, pallets: 0 },
  );
  const carrier = trip.acceptedCarrierName ?? trip.carrierName;
  const vehicle = [trip.vehicleLicensePlate, trip.vehicleCode, trip.vehicleTypeCode].filter(Boolean).join(" · ");

  return (
    <>
      <GlobalStyles styles={{
        "@page": { size: "A4 portrait", margin: "12mm" },
        "@media print": {
          "html, body": { background: "#fff !important" },
          ".route-sheet-screen-only": { display: "none !important" },
          ".route-sheet": { boxShadow: "none !important", margin: "0 !important", padding: "0 !important", maxWidth: "none !important" },
          ".route-sheet-keep": { breakInside: "avoid", pageBreakInside: "avoid" },
        },
      }} />
      <Box
        component="article"
        className="route-sheet"
        aria-label={t("Hoja de ruta {{shipment}}", { shipment: trip.shipmentNumber })}
        sx={{
          bgcolor: "#fff", color: INK, maxWidth: "210mm", mx: "auto", p: { xs: 2, sm: "12mm" },
          boxShadow: "0 1px 6px rgba(0,0,0,.18)", fontFamily: "inherit",
          "& h1, & h2, & h3": { m: 0, color: INK },
        }}
      >
        {/* Cabecera */}
        <Box component="header" sx={{ display: "flex", justifyContent: "space-between", gap: 2, borderBottom: `2px solid ${INK}`, pb: 1, mb: 1.5 }}>
          <Box sx={{ minWidth: 0 }}>
            <Typography component="div" sx={{ fontSize: 12, fontWeight: 700, color: MUTED }}>{data.companyName}</Typography>
            <Typography component="h1" sx={{ fontSize: 20, fontWeight: 800 }}>{t("Hoja de ruta")}</Typography>
          </Box>
          <Box sx={{ textAlign: "right" }}>
            <Typography component="div" sx={{ fontSize: 9.5, color: MUTED, textTransform: "uppercase" }}>{t("Envío")}</Typography>
            <Typography component="div" sx={{ fontSize: 18, fontWeight: 800, fontVariantNumeric: "tabular-nums" }}>
              {trip.shipmentNumber}
            </Typography>
            <Typography component="div" sx={{ fontSize: 10, color: MUTED }}>
              {t("Plan {{plan}} · {{date}}", { plan: trip.planNumber ?? "-", date: fmtDate(trip.planningDate) })}
            </Typography>
          </Box>
        </Box>

        <Box sx={{ display: "grid", gap: 1.25, gridTemplateColumns: { xs: "1fr 1fr", sm: "repeat(3, 1fr)" }, mb: 1.5 }}>
          <Fact label={t("Origen")}>{[trip.originCode, trip.originName].filter(Boolean).join(" · ") || "-"}</Fact>
          <Fact label={t("Salida planificada")}>{fmtDateTime(trip.plannedDepartureAt)}</Fact>
          <Fact label={t("Ruta")}>{[trip.routeCode, trip.routeName].filter(Boolean).join(" · ") || "-"}</Fact>
          <Fact label={t("Vehículo (placa)")}>{vehicle || "-"}</Fact>
          <Fact label={t("Transportista")}>{carrier ?? "-"}</Fact>
          <Fact label={t("Conductor")}>
            {trip.driverName ?? "-"}
            {(trip.driverLicenseNumber || trip.driverPhone) && (
              <Typography component="div" sx={{ fontSize: 10, color: MUTED, fontWeight: 400 }}>
                {[trip.driverLicenseNumber && t("Licencia {{n}}", { n: trip.driverLicenseNumber }), trip.driverPhone]
                  .filter(Boolean).join(" · ")}
              </Typography>
            )}
          </Fact>
        </Box>

        {/* Totales */}
        <Box component="table" sx={{ width: "100%", borderCollapse: "collapse", mb: 2 }} aria-label={t("Totales del envío")}>
          <thead>
            <tr>
              {[t("Paradas"), t("Pedidos"), t("Peso"), t("Volumen"), t("Pallets")].map((label) => (
                <Box component="th" key={label} sx={{ ...cellSx, bgcolor: "#f1f1f1", fontWeight: 700 }}>{label}</Box>
              ))}
            </tr>
          </thead>
          <tbody>
            <tr>
              {[fmtQuantity(detail.stops.length), fmtQuantity(detail.assignments.length), fmtWeightKg(totals.kg),
                fmtVolumeM3(totals.m3), fmtQuantity(totals.pallets)].map((value, i) => (
                <Box component="td" key={i} sx={{ ...cellSx, fontVariantNumeric: "tabular-nums", fontSize: 12, fontWeight: 700 }}>{value}</Box>
              ))}
            </tr>
          </tbody>
        </Box>

        {!linesAvailable && (
          <Typography sx={{ fontSize: 10.5, color: MUTED, mb: 1.5 }}>
            {t("Tu perfil no puede leer pedidos: la hoja muestra lo asignado a cada parada, sin las líneas ni los documentos del ERP.")}
          </Typography>
        )}

        {/* Paradas */}
        {groups.map(({ stop, orders: stopOrders }) => (
          <Box component="section" key={stop?.id ?? "unplaced"} className="route-sheet-keep"
            aria-label={stop ? t("Parada {{n}}", { n: stop.sequence }) : t("Pedidos sin parada")}
            sx={{ border: `1px solid ${INK}`, mb: 1.5, p: 1 }}>
            <Box sx={{ display: "flex", gap: 1, alignItems: "baseline", justifyContent: "space-between", flexWrap: "wrap" }}>
              <Typography component="h2" sx={{ fontSize: 14, fontWeight: 800 }}>
                {stop
                  ? `${t("Parada {{n}}", { n: stop.sequence })} · ${[stop.destinationCode, stop.destinationName].filter(Boolean).join(" · ")}`
                  : t("Pedidos sin parada")}
              </Typography>
              {stop && (
                <Typography component="div" sx={{ fontSize: 11, fontVariantNumeric: "tabular-nums" }}>
                  {t("Ventana {{window}}", { window: fmtWindow(stop.serviceWindowStart, stop.serviceWindowEnd) })}
                  {stop.etaArrivalAt && ` · ${t("Llegada estimada {{eta}}", { eta: fmtTime(stop.etaArrivalAt) })}`}
                </Typography>
              )}
            </Box>
            {stop && (
              <Typography sx={{ fontSize: 11, color: MUTED, mb: 0.75 }}>{stop.address ?? t("Sin dirección registrada")}</Typography>
            )}

            {stopOrders.map((assignment) => {
              const order = orders.get(assignment.orderId);
              const orderDocuments = documents.get(assignment.orderId) ?? [];
              return (
                <Box key={assignment.assignmentId} sx={{ mb: 1 }}>
                  <Box sx={{ display: "flex", gap: 1, flexWrap: "wrap", justifyContent: "space-between", alignItems: "baseline" }}>
                    <Typography component="h3" sx={{ fontSize: 12, fontWeight: 700 }}>
                      {t("Pedido {{number}}", { number: assignment.orderNumber })}
                      {assignment.customerName && ` · ${assignment.customerName}`}
                    </Typography>
                    <Typography component="div" sx={{ fontSize: 11, fontVariantNumeric: "tabular-nums" }}>
                      {[fmtWeightKg(assignment.assignedWeightKg), fmtVolumeM3(assignment.assignedVolumeM3),
                        t("{{n}} pallets", { n: fmtQuantity(assignment.assignedPallets) })].join(" · ")}
                      {(assignment.requestedWindowStart || assignment.requestedWindowEnd)
                        && ` · ${t("Pide {{window}}", { window: fmtWindow(assignment.requestedWindowStart, assignment.requestedWindowEnd) })}`}
                    </Typography>
                  </Box>
                  {!assignment.wholeOrder && (
                    <Typography sx={{ fontSize: 10.5, color: MUTED }}>
                      {t("Viaja una parte del pedido (las cifras de arriba). Las líneas son las del pedido completo.")}
                    </Typography>
                  )}
                  {order && order.lines.length > 0 && (
                    <Box component="table" sx={{ width: "100%", borderCollapse: "collapse", mt: 0.5 }}
                      aria-label={t("Líneas del pedido {{number}}", { number: assignment.orderNumber })}>
                      <thead>
                        <tr>
                          {[t("Línea"), t("Material"), t("Descripción"), t("Cantidad"), t("Peso"), t("Volumen"), t("Recibido")].map((label) => (
                            <Box component="th" key={label} sx={{ ...cellSx, bgcolor: "#f1f1f1", fontWeight: 700 }}>{label}</Box>
                          ))}
                        </tr>
                      </thead>
                      <tbody>
                        {order.lines.map((line) => (
                          <tr key={line.id}>
                            <Box component="td" sx={{ ...cellSx, fontVariantNumeric: "tabular-nums" }}>{line.lineNumber}</Box>
                            <Box component="td" sx={cellSx}>{line.materialCode}</Box>
                            <Box component="td" sx={cellSx}>{line.materialDescription}</Box>
                            <Box component="td" sx={{ ...cellSx, fontVariantNumeric: "tabular-nums", whiteSpace: "nowrap" }}>
                              {`${fmtQuantity(line.quantity)} ${line.uom}`}
                            </Box>
                            <Box component="td" sx={{ ...cellSx, fontVariantNumeric: "tabular-nums", whiteSpace: "nowrap" }}>{fmtWeightKg(line.lineWeightKg)}</Box>
                            <Box component="td" sx={{ ...cellSx, fontVariantNumeric: "tabular-nums", whiteSpace: "nowrap" }}>{fmtVolumeM3(line.lineVolumeM3)}</Box>
                            {/* Vacía a propósito: la llena a mano quien recibe. */}
                            <Box component="td" sx={{ ...cellSx, minWidth: 60 }} />
                          </tr>
                        ))}
                      </tbody>
                    </Box>
                  )}
                  {orderDocuments.length > 0 && (
                    <Box component="ul" sx={{ m: 0, mt: 0.5, pl: 2, fontSize: 11 }} aria-label={t("Documentos del pedido {{number}}", { number: assignment.orderNumber })}>
                      {orderDocuments.map((document) => (
                        <li key={document.id}>
                          <strong>{logisticsDocumentLabel(document.documentType, document.fiscal)}</strong>{" "}
                          <span style={{ fontVariantNumeric: "tabular-nums" }}>{document.documentNumber}</span>
                          {document.fiscal?.authorizationNumber && (
                            <span style={{ color: MUTED }}>
                              {` · ${authorizationLabel(document.fiscal.fiscalCountry)}: ${document.fiscal.authorizationNumber}`}
                            </span>
                          )}
                        </li>
                      ))}
                    </Box>
                  )}
                </Box>
              );
            })}

            {stop && (
              <Box sx={{ display: "grid", gap: 0.5, gridTemplateColumns: "repeat(4, 1fr)", mt: 1 }}
                aria-label={t("Recibí conforme")}>
                <Typography component="div" sx={{ gridColumn: "1 / -1", fontSize: 11, fontWeight: 700 }}>
                  {t("Recibí conforme")}
                </Typography>
                <WriteBox label={t("Nombre de quien recibe")} />
                <WriteBox label={t("Documento de identidad")} />
                <WriteBox label={t("Hora de llegada / salida")} />
                <WriteBox label={t("Firma y sello")} />
                <WriteBox label={t("Observaciones (faltantes, daños, rechazos)")} height={30} wide />
              </Box>
            )}
          </Box>
        ))}

        {/* Cierre */}
        <Box className="route-sheet-keep" sx={{ display: "grid", gap: 0.5, gridTemplateColumns: "repeat(3, 1fr)", mt: 2 }}>
          <WriteBox label={t("Firma del conductor")} height={48} />
          <WriteBox label={t("Despacho (origen)")} height={48} />
          <WriteBox label={t("Garita / control de salida")} height={48} />
        </Box>
        <Typography sx={{ fontSize: 9.5, color: MUTED, mt: 1 }}>
          {t("Estado del envío al imprimir: {{status}} · Impreso el {{when}}", {
            status: enumLabel("tripStatus", trip.status), when: fmtDateTime(data.printedAt),
          })}
        </Typography>
      </Box>
    </>
  );
}
