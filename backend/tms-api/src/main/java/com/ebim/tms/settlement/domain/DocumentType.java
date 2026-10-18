package com.ebim.tms.settlement.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Which SUNAT document a carrier sent (migration V66, ADR-024 part 2, item 3).
 *
 * <p>Stored as the SUNAT code ({@code 01}, {@code 07}, {@code 08}) because that is what the
 * accountant, the CPE and the ERP all call it. <b>Amounts are positive on every type</b>, as SUNAT
 * issues them; {@link #effectiveSign()} is where a credit note becomes a subtraction.
 */
public enum DocumentType {

    /** Factura. */
    INVOICE("01"),

    /** Nota de crédito: reduces the invoice it references. */
    CREDIT_NOTE("07"),

    /** Nota de débito: adds to the invoice it references. */
    DEBIT_NOTE("08");

    private final String code;

    DocumentType(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    /** +1 for an invoice and a debit note, -1 for a credit note. */
    public int effectiveSign() {
        return this == CREDIT_NOTE ? -1 : 1;
    }

    /** A note references the invoice it corrects; an invoice references nothing. */
    public boolean isNote() {
        return this != INVOICE;
    }

    public static DocumentType ofCode(String code) {
        if (code == null) {
            return INVOICE;
        }
        for (DocumentType type : values()) {
            if (type.code.equals(code.trim())) {
                return type;
            }
        }
        throw new IllegalArgumentException("unknown SUNAT document type " + code);
    }

    /** {@code document_type} holds the SUNAT code, not the enum name. */
    @Converter
    public static class CodeConverter implements AttributeConverter<DocumentType, String> {

        @Override
        public String convertToDatabaseColumn(DocumentType attribute) {
            return (attribute == null ? INVOICE : attribute).code();
        }

        @Override
        public DocumentType convertToEntityAttribute(String dbData) {
            return ofCode(dbData);
        }
    }
}
