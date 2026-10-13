import { Alert, AlertTitle } from "@mui/material";
import { t } from "../../lib/i18n";
import { isOperationSuspended, type CompanyAccessView } from "../api/meApi";

/**
 * El texto del aviso según la causa que MasterAdmin envió (ADR-021). Con `NON_PAYMENT` el contrato
 * `lifecycle.v1` pide decir expresamente que se regularice el pago con el proveedor.
 */
function detailFor(reason: string | null | undefined): string {
  switch (reason) {
    case "NON_PAYMENT":
      return "La operación está suspendida por falta de pago: regularice el pago con su proveedor para reactivarla. Puede consultar y exportar sus datos; los cambios están bloqueados.";
    case "TERMINATED":
      return "El contrato de esta cuenta terminó. Puede consultar y exportar sus datos; los cambios están bloqueados.";
    default:
      return "Su proveedor suspendió la operación de esta cuenta. Puede consultar y exportar sus datos; los cambios están bloqueados.";
  }
}

/**
 * Aviso fijo «Cuenta suspendida» sobre el contenido de cada pantalla (ADR-021).
 *
 * Solo UX: lo que impide escribir es el servidor, que responde `423 tenant-suspended` a toda
 * escritura de una organización suspendida aunque este aviso no se vea. Los datos no se tocan y
 * todo vuelve a funcionar en cuanto MasterAdmin reactiva la cuenta.
 */
export function TenantSuspendedBanner({ company }: { company: Pick<CompanyAccessView, "operationalStatus"> | null }) {
  if (!isOperationSuspended(company)) return null;
  return (
    <Alert severity="warning" variant="outlined" role="alert" sx={{ mb: 2 }}>
      <AlertTitle>{t("Cuenta suspendida")}</AlertTitle>
      {t(detailFor(company?.operationalStatus?.reason))}
    </Alert>
  );
}
