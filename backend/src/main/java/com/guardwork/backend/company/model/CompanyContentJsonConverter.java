package com.guardwork.backend.company.model;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Persists the single {@link CompanyContent} document as JSONB. Unlike the candidate
 * list converters this handles one object column and tolerates the legacy empty object.
 */
@Converter(autoApply = false)
public class CompanyContentJsonConverter implements AttributeConverter<CompanyContent, String> {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(CompanyContent attribute) {
        if (attribute == null || attribute.payloadEmpty()) {
            return "{}";
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(attribute.stamped());
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize company content JSON", e);
        }
    }

    @Override
    public CompanyContent convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank() || "{}".equals(dbData.trim())) {
            return CompanyContent.empty();
        }
        try {
            return OBJECT_MAPPER.readValue(dbData, CompanyContent.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to deserialize company content JSON", e);
        }
    }
}
