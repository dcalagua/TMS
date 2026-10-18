package com.ebim.tms.settlement.domain.tax;

import java.math.RoundingMode;
import java.util.Set;

/**
 * Every withholding a regime of this product computes (V66, ADR-027): what the shipper keeps from the
 * carrier's document and deposits with the tax authority instead of paying it to the carrier. TMS
 * computes the figure and records the certificate; <b>it pays nothing</b> (D-01).
 *
 * <p>What is a <b>rule of the kind</b> lives here - its country, its base, its rounding, whether a
 * person may override it, which other kind excludes it. What is a <b>number</b> - the rate, the code,
 * the threshold, whether it is on - is configuration ({@code tms.finance.tax.<country>.withholdings.*},
 * overridable per company): D-06, defaults are configuration, never constants.
 */
public enum WithholdingKind {

    /**
     * Peru's SPOT detraction: a share of the total deposited in the carrier's Banco de la Nación
     * account, on documents strictly above the threshold. Deposits are in whole soles. Not
     * overridable: it is the law's, and an exempt operation is one below the threshold.
     */
    PE_DETRACTION("PE", "detraction", WithholdingBase.TOTAL, 0, false, "Detracción (SPOT)"),

    /**
     * Peru's IGV retention, withheld by a designated retention agent - never on a document already
     * subject to detraction. Overridable: a "buen contribuyente" carrier is not modelled and is set
     * to zero by hand.
     */
    PE_RETENTION("PE", "retention", WithholdingBase.TOTAL, 2, true, "Retención del IGV"),

    /**
     * Ecuador's retención en la fuente del impuesto a la renta, on the subtotal (SRI code 310 for
     * freight transport). Overridable: a carrier in a regime that is not withheld (RIMPE negocio
     * popular, for instance) is set to zero by hand with the reason.
     */
    EC_INCOME_TAX_WITHHOLDING("EC", "income-tax", WithholdingBase.SUBTOTAL, 2, true,
            "Retención en la fuente (IR)"),

    /**
     * Ecuador's retención de IVA, on the VAT amount, withheld only by a company designated as a VAT
     * withholding agent. Yields nothing on a document with no VAT - and domestic land freight is
     * VAT 0%.
     */
    EC_VAT_WITHHOLDING("EC", "vat", WithholdingBase.VAT, 2, true, "Retención de IVA");

    private final String country;
    private final String propertyKey;
    private final WithholdingBase base;
    private final int scale;
    private final boolean overridable;
    private final String label;

    WithholdingKind(String country, String propertyKey, WithholdingBase base, int scale, boolean overridable,
            String label) {
        this.country = country;
        this.propertyKey = propertyKey;
        this.base = base;
        this.scale = scale;
        this.overridable = overridable;
        this.label = label;
    }

    /** The ISO country whose regime has this kind. */
    public String country() {
        return country;
    }

    /** Its key under {@code tms.finance.tax.<country>.withholdings}. */
    public String propertyKey() {
        return propertyKey;
    }

    public WithholdingBase base() {
        return base;
    }

    /** Decimals the amount is rounded to before it is stored with two. */
    public int scale() {
        return scale;
    }

    public RoundingMode rounding() {
        return RoundingMode.HALF_UP;
    }

    /** Whether a person may replace the computed amount, with a reason. */
    public boolean overridable() {
        return overridable;
    }

    /**
     * The threshold, when one is configured, is compared with the document's total strictly: the
     * SPOT rule reads "mayor a S/ 400", so 400.00 is not subject and 400.01 is.
     */
    public boolean strictlyAboveThreshold() {
        return true;
    }

    /** Kinds whose presence on the document excludes this one. */
    public Set<WithholdingKind> excludedBy() {
        return this == PE_RETENTION ? Set.of(PE_DETRACTION) : Set.of();
    }

    /** The authority's own name for it, in the language of the country. */
    public String label() {
        return label;
    }
}
