import { Box, Chip, Typography } from "@mui/material";
import { SavingsOutlined, WarningAmberRounded } from "@mui/icons-material";
import type { OrderDetailView, OrderLineView } from "../../shared/api/ordersApi";
import { StackModeChip } from "../../shared/geometry/Occupancy";
import {
  NO_PHYSICAL_BLOCK_TEXT, geometryReasonText, occupancyText, orderGeometryState, stacksText,
  volumeComparisonText, volumeSavingText,
} from "../../shared/geometry/loadGeometry";
import { DetailSection, KeyFacts } from "../../shared/ui/components";
import { t, tp } from "../../lib/i18n";
import { fmtAreaM2, fmtMetres, fmtVolumeM3 } from "../../lib/locale";
import { T } from "../../theme";

/**
 * «Ocupa» (ADR-022 §10): lo que el pedido ocupa en un camión según su geometría, por pedido y por
 * línea. Todo lo que aquí aparece lo calculó el backend al grabar las líneas (V62) contra el alto de
 * referencia de la empresa; esta sección solo lo lee. Por eso refleja lo **guardado**: una línea
 * editada y sin guardar no cambia nada aquí hasta que se guarda.
 *
 * Una línea sin bloque físico no se "estima": cuenta con su volumen declarado y se dice así. Una
 * que tiene bloque y no se pudo medir nombra lo que falta, para que se le pida al almacén.
 */
export function OrderGeometrySection({ order }: { order: OrderDetailView }) {
  const geometry = order.geometry;
  const state = orderGeometryState(geometry);
  const hasBlock = order.lines.some((line) => line.physicalSource != null || line.geometryStatus != null);

  if (!geometry || (state === "none" && !hasBlock)) {
    return (
      <DetailSection title={t("Ocupa")}>
        <Typography variant="body2" color="text.secondary">
          {t("Ninguna línea trae medidas ni perfil de material: el pedido se planifica por su volumen declarado.")}
        </Typography>
      </DetailSection>
    );
  }

  const saving = volumeSavingText(geometry.planningVolumeM3, order.totalVolumeM3);
  const summary = occupancyText({
    stacks: geometry.stacks, floorAreaM2: geometry.floorAreaM2, stackHeightM: geometry.maxStackHeightM,
  });

  return (
    <DetailSection
      title={t("Ocupa")}
      extra={saving ? (
        <Chip size="small" color="success" variant="outlined" icon={<SavingsOutlined sx={{ fontSize: 15 }} />}
          label={saving} sx={{ height: 22, fontWeight: 700 }} />
      ) : undefined}
    >
      <Box sx={{ display: "grid", gap: 1.5 }}>
        {summary && (
          <Typography sx={{ fontWeight: 800, fontVariantNumeric: "tabular-nums" }}>{summary}</Typography>
        )}
        <KeyFacts columns={4} items={[
          {
            label: t("Volumen de planificación"),
            value: fmtVolumeM3(geometry.planningVolumeM3),
            sub: volumeComparisonText(geometry.planningVolumeM3, order.totalVolumeM3),
          },
          { label: t("Piso"), value: fmtAreaM2(geometry.floorAreaM2) },
          {
            label: t("Pila más alta"),
            value: geometry.maxStackHeightM == null ? t("Sin medir") : fmtMetres(geometry.maxStackHeightM),
            sub: geometry.maxUnitHeightM == null ? undefined
              : t("unidad más alta {{height}}", { height: fmtMetres(geometry.maxUnitHeightM) }),
          },
          {
            label: t("Alto de referencia"),
            value: fmtMetres(geometry.referenceHeightM),
            sub: t("con el que se apilaron las líneas"),
          },
        ]} />
        {geometry.unmeasuredLines > 0 && (
          <Typography variant="body2" sx={{ display: "flex", alignItems: "center", gap: 0.75, color: "warning.main", fontWeight: 700 }}>
            <WarningAmberRounded sx={{ fontSize: 17 }} aria-hidden />
            {tp(geometry.unmeasuredLines,
              "{{count}} línea no se pudo medir: cuenta con su volumen declarado.",
              "{{count}} líneas no se pudieron medir: cuentan con su volumen declarado.")}
          </Typography>
        )}
        <Box sx={{ border: "1px solid", borderColor: "divider", borderRadius: "10px", overflowX: "auto" }}>
          <Box component="table" sx={{ width: "100%", minWidth: 640, borderCollapse: "collapse", "& td, & th": { px: 1.25, py: 0.75, textAlign: "left", fontSize: T.body - 0.5 } }}>
            <Box component="thead" sx={{ bgcolor: "action.hover" }}>
              <tr>
                {[t("Línea"), t("Material"), t("Apilado"), t("Ocupa"), t("Volumen")].map((label) => (
                  <Box component="th" key={label} sx={{
                    fontSize: `${T.micro - 0.5}px !important`, fontWeight: 700, letterSpacing: ".05em",
                    textTransform: "uppercase", color: "text.secondary",
                  }}>{label}</Box>
                ))}
              </tr>
            </Box>
            <tbody>
              {order.lines.map((line) => <GeometryLineRow key={line.id} line={line} />)}
            </tbody>
          </Box>
        </Box>
      </Box>
    </DetailSection>
  );
}

function GeometryLineRow({ line }: { line: OrderLineView }) {
  const measured = line.geometryStatus === "MEASURED";
  const doesNotFit = line.geometryStatus === "DOES_NOT_FIT";
  const noBlock = line.geometryStatus == null;
  const reason = geometryReasonText(line.geometryReason);

  let occupancy: string;
  if (noBlock) occupancy = t(NO_PHYSICAL_BLOCK_TEXT);
  else if (measured || doesNotFit) {
    occupancy = [
      line.stacks != null ? stacksText(line.stacks) : null,
      line.floorAreaM2 != null ? t("{{area}} de piso", { area: fmtAreaM2(line.floorAreaM2) }) : null,
      line.stackHeightM != null ? t("alto {{height}}", { height: fmtMetres(line.stackHeightM) }) : null,
    ].filter(Boolean).join(" · ");
  } else occupancy = t("Sin medir");

  return (
    <Box component="tr" sx={{ borderTop: "1px solid", borderColor: "divider", verticalAlign: "top" }}>
      <td style={{ fontVariantNumeric: "tabular-nums" }}>{line.lineNumber}</td>
      <td>
        <Typography sx={{ fontSize: T.body - 0.5, fontWeight: 700 }}>{line.materialCode}</Typography>
        <Typography sx={{ fontSize: T.micro, color: "text.secondary" }}>
          {line.quantity} {line.uom}
          {line.physicalSource === "PROFILE" && ` · ${t("perfil del almacén")}`}
          {line.physicalSource === "ORDER_LINE" && ` · ${t("medidas del ERP")}`}
        </Typography>
      </td>
      <td>{noBlock ? <Typography sx={{ fontSize: T.micro + 0.5, color: "text.secondary" }}>-</Typography> : <StackModeChip mode={line.stackMode} />}</td>
      <td>
        <Typography sx={{ fontSize: T.body - 0.5, fontWeight: measured ? 700 : 500, color: noBlock ? "text.secondary" : undefined, fontVariantNumeric: "tabular-nums" }}>
          {occupancy}
        </Typography>
        {reason && (
          <Typography sx={{ fontSize: T.micro, color: doesNotFit ? "error.main" : "warning.main", fontWeight: 700 }}>
            {reason}
          </Typography>
        )}
      </td>
      <td style={{ fontVariantNumeric: "tabular-nums" }}>
        <Typography sx={{ fontSize: T.body - 0.5 }}>
          {measured
            ? volumeComparisonText(line.effectiveVolumeM3, line.lineVolumeM3)
            : t("{{declared}} declarados", { declared: fmtVolumeM3(line.lineVolumeM3) })}
        </Typography>
      </td>
    </Box>
  );
}
