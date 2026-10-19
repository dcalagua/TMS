import { Typography } from "@mui/material";
import { fmtNumber } from "../../lib/locale";

/** Una cifra con signo y moneda, en números tabulares; el negativo es una variación, no un error. */
export function Money({ value, currency, strong = false }: { value: number; currency: string; strong?: boolean }) {
  return (
    <Typography
      variant="body2" component="span"
      sx={{ fontWeight: strong ? 700 : 500, fontVariantNumeric: "tabular-nums", color: value < 0 ? "warning.main" : undefined }}
    >
      {`${fmtNumber(value, { minimumFractionDigits: 2, maximumFractionDigits: 2 })} ${currency}`}
    </Typography>
  );
}
