package com.ebim.tms.shared.tax;

/**
 * How a country's taxpayer identifier is checked (ADR-027). In {@code shared} rather than in
 * {@code settlement} because the carrier master ({@code fleet}) and the inbound API
 * ({@code integration}) may need the same answer, and business modules do not depend on one another.
 *
 * <p>A pure, local check of the <b>shape and check digit</b>: what it refuses is a typing mistake.
 * Whether the taxpayer exists or is active is the tax authority's question, which TMS does not ask.
 */
public enum TaxIdRule {

    /** Peru's RUC: 11 digits, prefix 10/15/17/20, modulo-11 check digit. */
    PE_RUC {
        @Override
        public boolean isValid(String taxId) {
            return PeruRuc.isValid(taxId);
        }
    },

    /** Ecuador's RUC: 13 digits, province, taxpayer type, check digit and establishment. */
    EC_RUC {
        @Override
        public boolean isValid(String taxId) {
            return EcuadorRuc.isValid(taxId);
        }
    },

    /** Any other country: a non-blank identifier of letters, digits and hyphens, up to 20 characters. */
    FREE_TEXT {
        @Override
        public boolean isValid(String taxId) {
            return taxId != null && taxId.matches("^[0-9A-Za-z-]{1,20}$");
        }
    };

    public abstract boolean isValid(String taxId);
}
