import { useTheme } from "@mui/material/styles";
import { Box, Paper, Typography } from "@mui/material";
import {
  Bar, BarChart, CartesianGrid, Legend, ResponsiveContainer, Tooltip, XAxis, YAxis,
} from "recharts";
import type { KpiDailyRow } from "../../shared/api/reportingApi";
import { datavizSeries } from "../../theme";
import { EmptyState } from "../../shared/ui/components";
import { t } from "../../lib/i18n";
import { fmtDate, fmtQuantity } from "../../lib/locale";

export interface DailySeries {
  /** La clave numérica de `KpiDailyRow` que pinta esta serie. */
  key: keyof KpiDailyRow;
  label: string;
}

interface DailyColumnChartProps {
  rows: KpiDailyRow[];
  series: DailySeries[];
  height?: number;
}

/**
 * Los días del rango como columnas.
 *
 * Columnas y no líneas porque lo que se lee es magnitud por día —cuántos envíos, cuántas
 * entregas— y una línea entre dos días insinúa una continuidad que un conteo diario no tiene.
 *
 * Un solo eje, siempre. Dos medidas de escalas distintas no comparten gráfica: irían en dos, o
 * indexadas a una base común. Un segundo eje Y hace que dos series se crucen donde el dato no se
 * cruza, y es la forma más rápida de contar una historia falsa.
 *
 * Los colores salen de `datavizSeries()`, que es el orden de asignación validado del tema: la
 * paleta de la suite pone juntos dos tonos que no se distinguen bien, y ese orden los separa.
 * La leyenda está siempre que haya dos o más series, así que la identidad nunca depende solo del
 * color.
 */
export function DailyColumnChart({ rows, series, height = 280 }: DailyColumnChartProps) {
  const theme = useTheme();
  const mode = theme.palette.mode === "dark" ? "dark" : "light";
  const colors = datavizSeries(mode);

  if (rows.length === 0) {
    return <EmptyState title={t("Sin datos")} message={t("No hay días con actividad en el rango elegido.")} />;
  }

  // El backend manda todos los días del rango, incluidos aquellos en los que no pasó nada: la
  // gráfica no rellena huecos por su cuenta, solo pinta lo que le dan.
  const data = rows.map((row) => ({
    date: row.date,
    label: fmtDate(row.date),
    ...Object.fromEntries(series.map((entry) => [entry.key, row[entry.key] ?? 0])),
  }));

  return (
    <Box sx={{ width: "100%", height }}>
      <ResponsiveContainer>
        <BarChart data={data} margin={{ top: 8, right: 8, left: 0, bottom: 0 }} barGap={2}>
          {/* Rejilla recesiva y solo horizontal: la vertical no ayuda a comparar alturas. */}
          <CartesianGrid strokeDasharray="3 3" vertical={false} stroke={theme.palette.divider} />
          <XAxis
            dataKey="label"
            tick={{ fontSize: 11, fill: theme.palette.text.secondary }}
            tickLine={false}
            axisLine={{ stroke: theme.palette.divider }}
            interval="preserveStartEnd"
            minTickGap={24}
          />
          <YAxis
            tick={{ fontSize: 11, fill: theme.palette.text.secondary }}
            tickLine={false}
            axisLine={false}
            allowDecimals={false}
            width={44}
          />
          <Tooltip
            cursor={{ fill: theme.palette.action.hover }}
            content={({ active, payload, label }) => {
              if (!active || !payload?.length) return null;
              return (
                <Paper variant="outlined" sx={{ px: 1.5, py: 1, boxShadow: 3 }}>
                  <Typography variant="caption" sx={{ fontWeight: 800, display: "block", mb: 0.5 }}>
                    {label}
                  </Typography>
                  {payload.map((entry) => (
                    <Box key={String(entry.dataKey)} sx={{ display: "flex", alignItems: "center", gap: 0.75 }}>
                      <Box sx={{ width: 9, height: 9, borderRadius: "2px", bgcolor: entry.color, flexShrink: 0 }} />
                      {/* El texto va en tinta del tema, no en el color de la serie: el color lo
                          lleva el cuadradito de al lado. */}
                      <Typography variant="caption" color="text.secondary">{entry.name}</Typography>
                      <Typography variant="caption" sx={{ fontWeight: 700, ml: "auto", fontVariantNumeric: "tabular-nums" }}>
                        {fmtQuantity(Number(entry.value))}
                      </Typography>
                    </Box>
                  ))}
                </Paper>
              );
            }}
          />
          {series.length > 1 && (
            <Legend
              verticalAlign="top"
              align="left"
              height={28}
              formatter={(value) => (
                <Typography component="span" variant="caption" sx={{ color: "text.secondary" }}>{value}</Typography>
              )}
            />
          )}
          {series.map((entry, index) => (
            <Bar
              key={String(entry.key)}
              dataKey={String(entry.key)}
              name={t(entry.label)}
              fill={colors[index % colors.length]}
              // Extremo redondeado de 4px anclado a la línea base, y 2px de hueco entre barras
              // contiguas para que dos columnas del mismo día no se lean como una.
              radius={[4, 4, 0, 0]}
              maxBarSize={26}
            />
          ))}
        </BarChart>
      </ResponsiveContainer>
    </Box>
  );
}
