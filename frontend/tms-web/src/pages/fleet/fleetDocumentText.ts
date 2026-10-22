import type { StatusTone } from "../../theme";
import type { ComplianceFinding, FleetDocumentStatus } from "../../shared/api/fleetDocumentsApi";
import { enumLabel } from "../../lib/enums";
import { t } from "../../lib/i18n";
import { fmtDate } from "../../lib/locale";

/**
 * Los textos y tonos que comparten la pantalla de documentos de flota, las secciones de los drawers
 * de vehículo y conductor y el espacio de trabajo del viaje (migración V72). En un solo sitio para
 * que «Por vencer» sea el mismo ámbar en todas.
 */

/** Vencido es el único rojo. «Sin registrar» es gris: no saber no es estar vencido. */
export const FLEET_DOCUMENT_TONE: Record<FleetDocumentStatus, StatusTone> = {
  VALID: "done",
  EXPIRING_SOON: "inProgress",
  EXPIRED: "overdue",
  UNRECORDED: "neutral",
};


/** «Vence en 12 días», «Venció hace 3 días», «Vence hoy»; vacío sin fecha. */
export function expiryText(daysToExpiry: number | null): string {
  if (daysToExpiry === null) return t("Sin fecha de vencimiento");
  if (daysToExpiry === 0) return t("Vence hoy");
  if (daysToExpiry > 0) return daysToExpiry === 1 ? t("Vence mañana") : t("Vence en {{n}} días", { n: daysToExpiry });
  const overdue = -daysToExpiry;
  return overdue === 1 ? t("Venció ayer") : t("Venció hace {{n}} días", { n: overdue });
}

/** La frase de un hallazgo, en el idioma de la pantalla (el backend la compone en inglés). */
export function findingText(finding: ComplianceFinding): string {
  const type = enumLabel("fleetDocumentType", finding.documentType);
  const subject = finding.subjectCode ?? "";
  if (finding.status === "EXPIRED") {
    return t("{{subject}}: {{type}} venció el {{date}}", { subject, type, date: fmtDate(finding.expiresOn) });
  }
  return finding.onFile
    ? t("{{subject}}: {{type}} (obligatorio) no tiene fecha de vencimiento", { subject, type })
    : t("{{subject}}: {{type}} (obligatorio) no está registrado", { subject, type });
}
