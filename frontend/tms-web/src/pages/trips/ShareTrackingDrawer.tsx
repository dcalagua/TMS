import { Button } from "@mui/material";
import { ShareRounded } from "@mui/icons-material";
import { FormDrawer } from "../../shared/ui/components";
import { t } from "../../lib/i18n";
import { TrackingLinksPanel } from "../orders/TrackingLinksPanel";

/**
 * «Compartir seguimiento» desde una parada del viaje (ADR-029). El enlace es siempre de un pedido, no
 * de la parada: una parada puede atender a varios clientes, y a cada uno se le manda el suyo.
 */
export function ShareTrackingDrawer({ companyId, orderId, orderNumber, stopLabel, onClose }: {
  companyId: string;
  orderId: string;
  orderNumber: string;
  stopLabel: string;
  onClose: () => void;
}) {
  return (
    <FormDrawer
      open
      title={t("Compartir seguimiento")}
      subtitle={`${t("Pedido")} ${orderNumber} · ${stopLabel}`}
      icon={<ShareRounded />}
      onClose={onClose}
      footer={<Button onClick={onClose}>{t("Cerrar")}</Button>}
    >
      <TrackingLinksPanel companyId={companyId} orderId={orderId} orderNumber={orderNumber} withHeader={false} />
    </FormDrawer>
  );
}
