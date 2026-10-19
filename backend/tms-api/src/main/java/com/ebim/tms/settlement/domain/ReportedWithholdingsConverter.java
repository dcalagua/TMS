package com.ebim.tms.settlement.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.List;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code reported_withholdings} (V67): a JSON array in a text column, written whole and read whole -
 * the shape V18, V35 and V53 chose for the same reason. Its own mapper, so the stored form does not
 * move with the web layer's serialisation settings.
 */
@Converter
public class ReportedWithholdingsConverter implements AttributeConverter<List<ReportedWithholding>, String> {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final TypeReference<List<ReportedWithholding>> TYPE = new TypeReference<>() {
    };

    @Override
    public String convertToDatabaseColumn(List<ReportedWithholding> attribute) {
        return MAPPER.writeValueAsString(attribute == null ? List.of() : attribute);
    }

    @Override
    public List<ReportedWithholding> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return List.of();
        }
        return List.copyOf(MAPPER.readValue(dbData, TYPE));
    }
}
