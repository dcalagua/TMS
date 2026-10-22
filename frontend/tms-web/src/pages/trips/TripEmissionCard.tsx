import { useQuery } from "@tanstack/react-query";
import { Alert, Box, Chip, Divider, Tooltip, Typography } from "@mui/material";
import { Co2Rounded, InfoOutlined } from "@mui/icons-material";
import { WorkspaceCard } from "./WorkspaceCard";
import { fetchTripEmissions, type TripEmissionView } from "../../shared/api/emissionsApi";
import type { ApiError } from "../../shared/api/httpClient";
import { describeApiError } from "../../shared/api/problemMessages";
import { enumLabel } from "../../lib/enums";
import { t } from "../../lib/i18n";
import { fmtCo2e, fmtDecimal, fmtPercent } from "../../lib/locale";


/**
 * «Huella de carbono» del viaje (V73): su distancia —la misma del «Recorrido»— por el factor de su
 * tipo de vehículo, repartida entre sus pedidos con la base del costo para servir.
 *
 * Sin factor, sin vehículo o sin recorrido medible no hay cifra: la tarjeta dice «sin datos» y por
 * qué. Un cero sería el número más halagador de un informe de sostenibilidad y el menos cierto.
 */
export function TripEmissionCard({ companyId, tripId, version }: { companyId: string; tripId: string; version: number }) {
  const query = useQuery({
    queryKey: ["trip-emissions", companyId, tripId, version],
    queryFn: ({ signal }) => fetchTripEmissions(companyId, tripId, signal),
    enabled: companyId !== "",
  });
  const emission = query.data;

  return (
    <WorkspaceCard
      icon={<Co2Rounded />}
      title={t("Huella de carbono")}
      actions={emission?.status === "CALCULATED" && emission.estimated ? (
        <Tooltip title={t("La distancia es una estimación a partir de las coordenadas: la huella también lo es.")}>
          <Chip size="small" color="warning" variant="outlined" label={t("Estimado")} icon={<InfoOutlined />} sx={{ fontWeight: 700 }} />
        </Tooltip>
      ) : null}
    >
      {query.isError && <Alert severity="error">{describeApiError(query.error as ApiError)}</Alert>}
      {query.isPending && <Typography variant="body2" color="text.secondary">{t("Calculando...")}</Typography>}
      {emission && <EmissionBody emission={emission} />}
    </WorkspaceCard>
  );
}

function EmissionBody({ emission }: { emission: TripEmissionView }) {
  if (emission.status !== "CALCULATED") {
    return (
      <Box>
        <Typography sx={{ fontSize: 16, fontWeight: 800 }}>{t("Sin datos")}</Typography>
        <Typography variant="body2" color="text.secondary">
          {emission.status === "NO_FACTOR"
            ? t("El tipo de vehículo {{type}} no tiene factor de emisión. Cárgalo en Flota › Tipos de vehículo.",
              { type: emission.vehicleTypeCode ?? "" })
            : enumLabel("emissionStatus", emission.status)}
        </Typography>
      </Box>
    );
  }
  return (
    <>
      <Box sx={{ display: "grid", gap: 2, gridTemplateColumns: "repeat(2, minmax(0, 1fr))" }}>
        <Box>
          <Typography variant="caption" color="text.secondary" sx={{ display: "block" }}>{t("Emisiones")}</Typography>
          <Typography sx={{ fontSize: 16, fontVariantNumeric: "tabular-nums", fontWeight: 800 }}>{fmtCo2e(emission.kgCo2e)}</Typography>
        </Box>
        <Box>
          <Typography variant="caption" color="text.secondary" sx={{ display: "block" }}>{t("Intensidad")}</Typography>
          <Typography sx={{ fontSize: 16, fontVariantNumeric: "tabular-nums", fontWeight: 800 }}>
            {emission.gramsPerTonneKm === null ? "—" : `${fmtDecimal(emission.gramsPerTonneKm, 1)} g/t·km`}
          </Typography>
        </Box>
      </Box>
      <Typography variant="caption" color="text.secondary" sx={{ display: "block", mt: 1 }}>
        {t("{{km}} km × {{factor}} kg CO₂e/km ({{type}}, {{fuel}})", {
          km: fmtDecimal(emission.distanceKm ?? 0, 2),
          factor: fmtDecimal(emission.factorKgCo2ePerKm ?? 0, 4),
          type: emission.vehicleTypeCode ?? "",
          fuel: emission.fuelType ? enumLabel("fuelType", emission.fuelType) : t("combustible sin especificar"),
        })}
      </Typography>
      {emission.unmeasurableLegs > 0 && (
        <Alert severity="warning" variant="outlined" sx={{ mt: 1.5 }}>
          {t("{{count}} tramo(s) sin medir: la huella mostrada es menor que la real.", { count: emission.unmeasurableLegs })}
        </Alert>
      )}
      {emission.orders.length > 0 && (
        <>
          <Divider sx={{ mt: 1.5, mb: 0.5 }} />
          <Typography variant="caption" color="text.secondary" sx={{ display: "block", mb: 0.5 }}>
            {t("Reparto por pedido · base {{basis}}", { basis: enumLabel("costAllocationBasis", emission.basis) })}
          </Typography>
          {emission.orders.map((order) => (
            <Box key={order.assignmentId} sx={{
              display: "flex", alignItems: "baseline", justifyContent: "space-between", gap: 1.5, py: 0.5,
              "& + &": { borderTop: "1px solid", borderColor: "divider" },
            }}>
              <Typography variant="body2" sx={{ minWidth: 0, fontSize: 12.5 }}>
                {order.orderNumber}{order.customerLabel ? ` · ${order.customerLabel}` : ""}
              </Typography>
              <Typography variant="caption" color="text.secondary" sx={{ fontVariantNumeric: "tabular-nums", whiteSpace: "nowrap" }}>
                {order.kgCo2e === null ? t("sin base") : `${fmtCo2e(order.kgCo2e)} · ${fmtPercent((order.share ?? 0) * 100, 1)}`}
              </Typography>
            </Box>
          ))}
        </>
      )}
    </>
  );
}
