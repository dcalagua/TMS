import { StatusChip } from "../../shared/ui/components";
import type { FleetDocumentStatus } from "../../shared/api/fleetDocumentsApi";
import { enumLabel } from "../../lib/enums";
import { FLEET_DOCUMENT_TONE } from "./fleetDocumentText";

/** El chip de estado de un papel de flota (V72): vencido es el único rojo, «sin registrar» es gris. */
export function FleetDocumentStatusChip({ status }: { status: FleetDocumentStatus }) {
  return <StatusChip tone={FLEET_DOCUMENT_TONE[status]} label={enumLabel("fleetDocumentStatus", status)} />;
}
