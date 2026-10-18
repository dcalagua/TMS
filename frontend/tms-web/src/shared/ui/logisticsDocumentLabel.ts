import type { LogisticsDocumentView } from "../api/logisticsDocumentsApi";
import { enumLabel } from "../../lib/enums";
import { t } from "../../lib/i18n";

/**
 * Cómo se nombra un documento logístico según el país que lo emitió (ADR-028).
 *
 * El TMS recibe estos documentos del ERP del cliente y los enseña: nunca los emite ni los valida
 * ante el SRI o la SUNAT. Por eso la etiqueta sale de lo que el ERP declaró (`fiscalCountry`,
 * `fiscalDocumentCode`) y del catálogo mínimo de alias que ADR-028 documenta - las GRE son
 * guías de remisión peruanas (SUNAT 09 y 31), la guía ecuatoriana es SRI 06 - y de nada más.
 */

interface FiscalHint {
  fiscalCountry?: string | null;
  fiscalDocumentCode?: string | null;
}

/** Los alias peruanos de V55: son guías de remisión de la SUNAT aunque no traigan país. */
const PERU_ALIASES: Record<string, string> = { GRE_REMITENTE: "09", GRE_TRANSPORTISTA: "31" };

/** La autoridad tributaria de un país, para la etiqueta; el código ISO cuando no la conocemos. */
export function fiscalAuthority(country: string | null | undefined): string | null {
  if (!country) return null;
  if (country === "EC") return "SRI";
  if (country === "PE") return "SUNAT";
  return country;
}

/** El país del documento: el declarado, o Perú para una GRE (que solo existe allí). */
export function documentCountry(documentType: string, fiscal?: FiscalHint | null): string | null {
  return fiscal?.fiscalCountry ?? (documentType in PERU_ALIASES ? "PE" : null);
}

/** El código de la autoridad: el declarado, o el que el catálogo fija para la clase (SRI 06, SUNAT 09/31). */
export function documentCode(documentType: string, fiscal?: FiscalHint | null): string | null {
  if (fiscal?.fiscalDocumentCode) return fiscal.fiscalDocumentCode;
  if (documentType in PERU_ALIASES) return PERU_ALIASES[documentType];
  if (documentType === "REMISSION_GUIDE" && fiscal?.fiscalCountry === "EC") return "06";
  return null;
}

/**
 * La etiqueta de un documento: «Guía de remisión (SRI 06)» en Ecuador, «GRE remitente (SUNAT 09)»
 * en Perú, el tipo a secas cuando el ERP no dijo de qué país es.
 *
 * @param short la variante corta del portal y del monitor de entregas (tarjeta de móvil)
 */
export function logisticsDocumentLabel(documentType: string, fiscal?: FiscalHint | null, short = false): string {
  const country = documentCountry(documentType, fiscal);
  const code = documentCode(documentType, fiscal);
  const authority = fiscalAuthority(country);
  const isGuide = documentType === "REMISSION_GUIDE" || documentType in PERU_ALIASES;
  if (isGuide && country === "PE" && (code === "09" || code === "31")) {
    return t(code === "09" ? "GRE remitente ({{authority}} {{code}})" : "GRE transportista ({{authority}} {{code}})",
      { authority: authority ?? "SUNAT", code });
  }
  if (isGuide && authority) {
    return code
      ? t("Guía de remisión ({{authority}} {{code}})", { authority, code })
      : t("Guía de remisión ({{authority}})", { authority });
  }
  const base = enumLabel(short ? "deliveryDocumentType" : "logisticsDocumentType", documentType);
  return authority && code ? `${base} (${authority} ${code})` : base;
}

/** El nombre del identificador de autorización en cada país. */
export function authorizationLabel(country: string | null | undefined): string {
  if (country === "EC") return t("Clave de acceso");
  return t("Autorización");
}

/** La nota que acompaña a todo documento fiscal: lo emitió el ERP, el TMS solo lo enseña. */
export function receivedFromErpNote(country: string | null | undefined): string {
  if (country === "EC") return t("Recibido del ERP · el TMS no emite ni valida ante el SRI");
  if (country === "PE") return t("Recibido del ERP · el TMS no emite ni valida ante la SUNAT");
  return t("Recibido del ERP · el TMS no emite ni valida documentos fiscales");
}

/** Si el documento trae algo que el detalle fiscal pueda enseñar (V69): un campo fiscal o un destinatario. */
export function hasFiscalDetail(document: LogisticsDocumentView): boolean {
  const fiscal = document.fiscal;
  const anyFiscal = fiscal != null && Object.values(fiscal).some((value) => value != null && value !== "");
  return anyFiscal || (document.recipients?.length ?? 0) > 0;
}
