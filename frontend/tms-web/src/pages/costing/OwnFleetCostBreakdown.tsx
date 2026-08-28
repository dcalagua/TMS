import { Alert, Box, Chip, Divider, Stack, Table, TableBody, TableCell, TableHead, TableRow, Typography } from "@mui/material";
import type { OwnFleetQuoteView } from "../../shared/api/ownFleetCostingApi";
import {
  amountText, componentLabel, quantityText, sourceLabel, totalCaveat, totalText,
} from "./ownFleetCostText";
import { t } from "../../lib/i18n";

interface Props {
  quote: OwnFleetQuoteView;
  /** Un precio de transportista para el mismo envío, si lo hay, sólo para etiquetarlo al lado. */
  carrierPrice?: { amount: number; currency: string; label: string } | null;
}

/**
 * Qué estimamos que nos cuesta hacer este viaje con nuestro propio camión (migración V48).
 *
 * <h2>Costo interno, no precio</h2>
 * El encabezado lo dice y el chip lo repite. Un transportista presenta un **precio**: pactado, con
 * su margen dentro, y que obliga. Esto es un **costo interno modelado**: sin margen, no obliga a
 * nadie, y vale exactamente lo que valen las tarifas que alguien escribió en el perfil. Que la
 * flota propia salga más barata es la forma esperada de comparar un número con margen contra uno
 * sin él — no es, por sí solo, prueba de que convenga.
 *
 * <h2>Sin total no se imprime un total</h2>
 * Cuando falta el insumo de algún componente que el perfil sí cobra, la pantalla dice que no hay
 * total y explica qué falta. El subtotal parcial aparece **debajo y dicho como lo que es**, porque
 * un plan no debe parecer barato por no poder costear sus propios costos.
 */
export function OwnFleetCostBreakdown({ quote, carrierPrice = null }: Props) {
  const caveat = totalCaveat(quote);
  const hasLines = quote.lines.length > 0;

  return (
    <Stack spacing={1.5}>
      <Box sx={{ display: "flex", alignItems: "baseline", gap: 1, flexWrap: "wrap" }}>
        <Typography variant="subtitle2">{t("Costo estimado de flota propia")}</Typography>
        <Chip size="small" variant="outlined" color="info" label={t("costo interno, sin margen")} />
      </Box>

      {hasLines && (
        <Box sx={{ overflowX: "auto" }}>
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>{t("Componente")}</TableCell>
                <TableCell>{t("Cantidad × tarifa")}</TableCell>
                <TableCell>{t("Origen del dato")}</TableCell>
                <TableCell align="right">{t("Importe")}</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {quote.lines.map((line) => (
                <TableRow key={line.component}>
                  <TableCell>{t(componentLabel(line.component))}</TableCell>
                  <TableCell>
                    {quantityText(line) ?? (
                      <Typography variant="caption" color="text.secondary">
                        {line.status === "NOT_CALCULABLE"
                          ? t("sin cantidad medible")
                          : t("cargo fijo")}
                      </Typography>
                    )}
                  </TableCell>
                  <TableCell>
                    <Typography variant="caption" color="text.secondary">
                      {sourceLabel(line.quantitySource) ?? "—"}
                    </Typography>
                  </TableCell>
                  <TableCell align="right">{amountText(line, quote.currency)}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </Box>
      )}

      <Divider />

      <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "baseline", gap: 2 }}>
        <Typography variant="subtitle2">{t("TOTAL")}</Typography>
        <Typography variant="h6" color={quote.comparableTotal === null ? "text.secondary" : "text.primary"}>
          {t(totalText(quote))}
        </Typography>
      </Box>

      {caveat && <Alert severity="warning" variant="outlined">{t(caveat)}</Alert>}

      {carrierPrice && (
        <Alert severity="info" variant="outlined">
          <Typography variant="body2">
            {/* Los dos números, etiquetados. Nunca "la flota propia es más barata" a secas. */}
            {t("Precio del transportista")} {carrierPrice.label}: {carrierPrice.currency}{" "}
            {carrierPrice.amount.toFixed(2)} — {t("un precio comercial, con su margen dentro")}.
            <br />
            {t("Costo interno estimado")}: {t(totalText(quote))} — {t("sin margen, y sólo tan bueno como las tarifas configuradas")}.
          </Typography>
        </Alert>
      )}
    </Stack>
  );
}
