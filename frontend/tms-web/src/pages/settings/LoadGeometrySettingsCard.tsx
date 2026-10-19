import { useQuery } from "@tanstack/react-query";
import { Alert, Typography } from "@mui/material";
import type { ApiError } from "../../shared/api/httpClient";
import { fetchCompanyLoadGeometry, type CompanyLoadGeometryView } from "../../shared/api/administrationApi";
import { describeApiError } from "../../shared/api/problemMessages";
import { AppCard, KeyFacts, LoadingState } from "../../shared/ui/components";
import { t } from "../../lib/i18n";
import { fmtDecimal, fmtMetres } from "../../lib/locale";

const SOURCE_COPY: Record<CompanyLoadGeometryView["referenceHeightSource"], string> = {
  COMPANY: "Valor propio de la empresa",
  DEPLOYMENT: "Valor por defecto de la instalación",
};

/**
 * Geometría de carga (V62, ADR-022 §4-5): el alto de referencia contra el que se apilan las líneas
 * de un pedido al grabarlo, y el factor de piso que rige para un tipo de vehículo que no declara el
 * suyo. **De solo lectura**: el backend todavía no tiene una escritura para estas dos columnas, así
 * que se dicen con su origen en vez de ofrecer un campo que el guardado ignoraría.
 */
export function LoadGeometrySettingsCard({ companyId }: { companyId: string }) {
  const query = useQuery({
    queryKey: ["company-load-geometry", companyId],
    queryFn: ({ signal }) => fetchCompanyLoadGeometry(companyId, signal),
    enabled: companyId !== "",
    retry: false,
  });

  return (
    <AppCard title={t("Geometría de carga")}>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
        {t("Con estas dos cifras se calculan las pilas de los productos apilables y anidables: el alto de referencia es contra el que se apilan las líneas al grabar un pedido, y el factor de piso es la parte del largo × ancho de la caja que una carga puede cubrir cuando el tipo de vehículo no declara el suyo.")}
      </Typography>
      {query.isPending ? (
        <LoadingState minHeight={80} />
      ) : query.isError ? (
        <Alert severity="warning">{describeApiError(query.error as ApiError)}</Alert>
      ) : (
        <>
          <KeyFacts columns={2} items={[
            {
              label: t("Alto de referencia"),
              value: fmtMetres(query.data.referenceHeightM),
              sub: t(SOURCE_COPY[query.data.referenceHeightSource]),
            },
            {
              label: t("Factor de llenado de piso"),
              value: fmtDecimal(query.data.floorFillFactor),
              sub: t(SOURCE_COPY[query.data.floorFillFactorSource]),
            },
          ]} />
          <Typography variant="caption" color="text.secondary" sx={{ display: "block", mt: 1 }}>
            {t("Solo lectura: estos valores todavía no se editan desde esta pantalla. El factor de piso de cada tipo de vehículo sí se edita en Flota › Tipos de vehículo.")}
          </Typography>
        </>
      )}
    </AppCard>
  );
}
