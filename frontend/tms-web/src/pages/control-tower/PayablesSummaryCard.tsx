import { useQuery } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { Box, Button, Typography } from "@mui/material";
import { PaymentsRounded } from "@mui/icons-material";
import { CAPABILITY } from "../../shared/api/meApi";
import { fetchPayableSummary } from "../../shared/api/payablesApi";
import { useCompany } from "../../shared/company/CompanyContext";
import { AppCard } from "../../shared/ui/components";
import { t, tp } from "../../lib/i18n";
import { fmtNumber } from "../../lib/locale";
import { T } from "../../theme";

function money(value: number, currency: string): string {
  return `${fmtNumber(value, { minimumFractionDigits: 2, maximumFractionDigits: 2 })} ${currency}`;
}

/**
 * Facturas vencidas y por vencer (fase 65), en la torre de control de quien puede leer facturas.
 *
 * Es un añadido de la torre y no parte de ella: sin `SETTLEMENT_VIEW` no se pide ni se pinta, y si
 * la consulta falla la tarjeta desaparece en silencio - perder este resumen no puede tumbar la
 * vista del día. Cada moneda va aparte: TMS no convierte.
 */
export function PayablesSummaryCard() {
  const { selected, hasCapability } = useCompany();
  const companyId = selected?.id ?? "";
  const allowed = hasCapability(CAPABILITY.SETTLEMENT_VIEW);

  const summaryQuery = useQuery({
    queryKey: ["settlement-payables", "summary", companyId],
    queryFn: ({ signal }) => fetchPayableSummary(companyId, signal),
    enabled: allowed && companyId !== "",
    retry: false,
    staleTime: 60_000,
  });

  if (!allowed || !summaryQuery.data) return null;
  const summary = summaryQuery.data;
  const currencies = summary.currencies.filter((row) => row.overdueCount > 0 || row.dueSoonCount > 0);

  return (
    <AppCard
      title={(
        <Box sx={{ display: "flex", alignItems: "center", gap: 1, fontSize: T.cardTitle, fontWeight: 700 }}>
          <PaymentsRounded sx={{ fontSize: 19, color: "text.disabled" }} />
          {t("Facturas vencidas / por vencer")}
        </Box>
      )}
      actions={(
        <Button component={Link} to="/settlement/payables" size="small">{t("Ver cuentas por pagar")}</Button>
      )}
    >
      <Box sx={{ display: "grid", gap: 1 }}>
        {currencies.length === 0 ? (
          <Typography variant="body2" color="text.secondary">
            {t("Nada vencido ni por vencer en {{n}} días.", { n: summary.dueSoonDays })}
          </Typography>
        ) : currencies.map((row) => (
          <Box key={row.currency} sx={{ display: "flex", gap: 2, flexWrap: "wrap", alignItems: "baseline" }}>
            <Typography variant="body2" sx={{ fontWeight: 800, minWidth: 40 }}>{row.currency}</Typography>
            <Typography variant="body2" sx={{ color: row.overdueCount > 0 ? "error.main" : "text.secondary", fontWeight: 700 }}>
              {tp(row.overdueCount, "{{count}} vencida", "{{count}} vencidas")} · {money(row.overdueAmount, row.currency)}
            </Typography>
            <Typography variant="body2" color="text.secondary">
              {t("Vencen en {{n}} días", { n: summary.dueSoonDays })}: {row.dueSoonCount} · {money(row.dueSoonAmount, row.currency)}
            </Typography>
          </Box>
        ))}
        {summary.pendingPaymentRequests > 0 && (
          <Typography variant="body2" sx={{ fontWeight: 700, color: "warning.main" }}>
            {tp(summary.pendingPaymentRequests, "{{count}} pago por confirmar", "{{count}} pagos por confirmar")}
          </Typography>
        )}
      </Box>
    </AppCard>
  );
}
