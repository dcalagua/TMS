import type { ChipProps } from "@mui/material";
import type { DocumentDeliveryStatus } from "../api/documentDeliveriesApi";
import { enumLabel } from "../../lib/enums";
import { StatusChip } from "./components";
import { DOCUMENT_DELIVERY_STATUS_TONE } from "./statusTones";

/**
 * El estado de un documento en un viaje (ADR-018), igual en el portal del transportista y en el
 * monitor de la oficina: los dos hablan del mismo hecho y tienen que verse igual.
 */
export function DocumentStatusChip({ status, variant, size }: {
  status: DocumentDeliveryStatus;
  variant?: "soft" | "solid";
  size?: ChipProps["size"];
}) {
  return (
    <StatusChip
      label={enumLabel("documentDeliveryStatus", status)}
      tone={DOCUMENT_DELIVERY_STATUS_TONE[status] ?? "neutral"}
      variant={variant}
      size={size}
    />
  );
}
