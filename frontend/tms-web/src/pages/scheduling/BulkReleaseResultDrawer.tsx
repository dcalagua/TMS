import { Box, Button, Typography } from "@mui/material";
import { CheckCircleRounded, PlaylistAddCheckRounded, WarningAmberRounded } from "@mui/icons-material";
import type { BulkReleaseItem, BulkReleaseResult } from "../../shared/api/schedulingApi";
import { DataTable, FormDrawer, StatusChip, type DataTableColumn } from "../../shared/ui/components";
import { DetailSection, KeyFacts, VerdictBanner } from "../../shared/ui/components/DetailLayout";
import { enumLabel } from "../../lib/enums";
import { t } from "../../lib/i18n";

/**
 * El resultado de una liberación en bloque, fila por fila. El backend responde 207 cuando algo no
 * se liberó; lo que se liberó se queda liberado, y cada rechazo trae sus motivos. Un toast con
 * "3 de 5" no dice cuáles faltan ni por qué, que es lo único que el planificador necesita saber.
 */
export function BulkReleaseResultDrawer({ result, onClose }: { result: BulkReleaseResult; onClose: () => void }) {
  const columns: DataTableColumn<BulkReleaseItem>[] = [
    {
      key: "order",
      header: t("Pedido"),
      render: (item) => <Typography variant="body2" sx={{ fontWeight: 700 }}>{item.orderNumber ?? item.orderId}</Typography>,
    },
    {
      key: "outcome",
      header: t("Resultado"),
      render: (item) => (
        <StatusChip label={item.released ? t("Liberado") : t("No liberado")} tone={item.released ? "done" : "overdue"} />
      ),
    },
    {
      key: "why",
      header: t("Motivo"),
      render: (item) => {
        if (item.released) return "-";
        if (item.reasons.length > 0) {
          return (
            <Box sx={{ display: "flex", gap: 0.5, flexWrap: "wrap" }}>
              {item.reasons.map((reason) => (
                <StatusChip key={reason.code} label={enumLabel("schedulingReason", reason.code)}
                  tone={reason.severity === "BLOCKED" ? "overdue" : "inProgress"} />
              ))}
            </Box>
          );
        }
        return <Typography variant="caption">{item.message ?? "-"}</Typography>;
      },
    },
  ];

  const allReleased = result.released === result.submitted;

  return (
    <FormDrawer
      open
      size="md"
      icon={<PlaylistAddCheckRounded />}
      title={t("Resultado de la liberación")}
      subtitle={t("{{released}} de {{submitted}} liberados", { released: result.released, submitted: result.submitted })}
      onClose={onClose}
      footer={<Button variant="contained" onClick={onClose}>{t("Cerrar")}</Button>}
    >
      <Box sx={{ display: "grid", gap: 2.5 }}>
        <VerdictBanner
          tone={allReleased ? "success" : result.released === 0 ? "error" : "warning"}
          icon={allReleased ? <CheckCircleRounded /> : <WarningAmberRounded />}
          title={allReleased ? t("Todos liberados") : t("Liberación parcial")}
          message={allReleased
            ? t("Todos los pedidos pasaron a planificación.")
            : t("Lo liberado se queda liberado. Los rechazos traen sus motivos abajo.")}
        />

        <KeyFacts columns={3} items={[
          { label: t("Enviados"), value: result.submitted },
          { label: t("Liberados"), value: result.released },
          { label: t("No liberados"), value: result.refused },
        ]} />

        <DetailSection title={t("Pedido por pedido")}>
          <DataTable
            columns={columns}
            rows={result.results}
            rowKey={(item) => `${item.index}-${item.orderId}`}
            emptyTitle={t("Sin pedidos")}
          />
        </DetailSection>
      </Box>
    </FormDrawer>
  );
}
