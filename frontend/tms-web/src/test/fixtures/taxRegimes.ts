import type { TaxRegimeView } from "../../shared/api/settlementApi";

/**
 * Los tres regímenes tal como los devuelve `GET /settlement/fiscal-settings` con los valores por
 * defecto del despliegue (ADR-027). Las pruebas del formulario, del panel y de configuración leen
 * de aquí: la pantalla no trae ninguna tasa propia, así que la prueba tampoco.
 */

const NOTHING_STORED = { fiscalCountry: null, vatRate: null, freightVatRate: null, withholdings: [] };

export const PE_REGIME: TaxRegimeView = {
  regime: "PE",
  country: "PE",
  fiscalCurrency: "PEN",
  vatLabel: "IGV",
  vatRate: 18,
  freightVatRate: 18,
  allowedVatRates: [18, 0],
  breakdownRequired: true,
  breakdownTolerance: 0.01,
  documentKinds: [
    { kind: "INVOICE", code: "01", label: "Factura" },
    { kind: "CREDIT_NOTE", code: "07", label: "Nota de crédito" },
    { kind: "DEBIT_NOTE", code: "08", label: "Nota de débito" },
  ],
  documentNumber: {
    pattern: "^(F[A-Z0-9]{3}|E001|[0-9]{4})-[0-9]{1,8}$",
    placeholder: "F001-123",
    hint: "Serie y correlativo (F001-123)",
  },
  taxId: { label: "RUC", rule: "PE_RUC", length: 11, pattern: "^[0-9]{11}$", hint: "11 dígitos" },
  electronicValidation: "EXTERNAL_DEPENDENCY",
  withholdings: [
    {
      kind: "PE_DETRACTION", label: "Detracción (SPOT)", enabled: true, code: "027", rate: 4, base: "TOTAL",
      thresholdAmount: 400, strictlyAboveThreshold: true, scale: 0, overridable: false, requiresNoOther: [],
    },
    {
      kind: "PE_RETENTION", label: "Retención del IGV", enabled: false, code: null, rate: 3, base: "TOTAL",
      thresholdAmount: null, strictlyAboveThreshold: true, scale: 2, overridable: true,
      requiresNoOther: ["PE_DETRACTION"],
    },
  ],
  stored: NOTHING_STORED,
  defaultCountry: "PE",
};

export const EC_REGIME: TaxRegimeView = {
  regime: "EC",
  country: "EC",
  fiscalCurrency: "USD",
  vatLabel: "IVA",
  vatRate: 15,
  freightVatRate: 0,
  allowedVatRates: [0, 5, 15],
  breakdownRequired: true,
  breakdownTolerance: 0.01,
  documentKinds: [
    { kind: "INVOICE", code: "01", label: "Factura" },
    { kind: "CREDIT_NOTE", code: "04", label: "Nota de crédito" },
    { kind: "DEBIT_NOTE", code: "05", label: "Nota de débito" },
  ],
  documentNumber: {
    pattern: "^[0-9]{3}-[0-9]{3}-[0-9]{1,9}$",
    placeholder: "001-001-000000123",
    hint: "Establecimiento - punto de emisión - secuencial (3-3-9 dígitos)",
  },
  taxId: {
    label: "RUC", rule: "EC_RUC", length: 13, pattern: "^[0-9]{13}$",
    hint: "13 dígitos; termina en el establecimiento (001)",
  },
  electronicValidation: "EXTERNAL_DEPENDENCY",
  withholdings: [
    {
      kind: "EC_INCOME_TAX_WITHHOLDING", label: "Retención en la fuente (IR)", enabled: true, code: "310", rate: 1,
      base: "SUBTOTAL", thresholdAmount: null, strictlyAboveThreshold: true, scale: 2, overridable: true,
      requiresNoOther: [],
    },
    {
      kind: "EC_VAT_WITHHOLDING", label: "Retención de IVA", enabled: false, code: null, rate: 70, base: "VAT",
      thresholdAmount: null, strictlyAboveThreshold: true, scale: 2, overridable: true, requiresNoOther: [],
    },
  ],
  stored: NOTHING_STORED,
  defaultCountry: "EC",
};

/** Un país sin régimen propio (Colombia): sin moneda impuesta, sin formato y sin retenciones. */
export const GENERIC_REGIME: TaxRegimeView = {
  regime: "GENERIC",
  country: "CO",
  fiscalCurrency: null,
  vatLabel: "IVA/VAT",
  vatRate: null,
  freightVatRate: null,
  allowedVatRates: [],
  breakdownRequired: false,
  breakdownTolerance: 0.01,
  documentKinds: [
    { kind: "INVOICE", code: null, label: "Factura" },
    { kind: "CREDIT_NOTE", code: null, label: "Nota de crédito" },
    { kind: "DEBIT_NOTE", code: null, label: "Nota de débito" },
  ],
  documentNumber: { pattern: null, placeholder: null, hint: null },
  taxId: { label: "Identificación fiscal", rule: "FREE_TEXT", length: null, pattern: null, hint: null },
  electronicValidation: "EXTERNAL_DEPENDENCY",
  withholdings: [],
  stored: NOTHING_STORED,
  defaultCountry: "CO",
};

/** Una RUC ecuatoriana de sociedad privada válida (módulo 11), la del ejemplo del contrato. */
export const EC_PRIVATE_RUC = "0990004196001";
/** Una RUC peruana válida (módulo 11). */
export const PE_RUC = "20100070970";
