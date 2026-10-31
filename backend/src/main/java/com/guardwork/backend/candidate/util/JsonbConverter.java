package com.guardwork.backend.candidate.util;

import java.io.IOException;
import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Generic JPA {@link AttributeConverter} that persists a {@link List} of objects as a JSONB string.
 * It can be used for any List<T> where T is a POJO that Jackson can serialize/deserialize.
 */
@Converter(autoApply = false)
public class JsonbConverter<T> implements AttributeConverter<List<T>, String> {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private final TypeReference<List<T>> typeReference;

    public JsonbConverter(TypeReference<List<T>> typeReference) {
        this.typeReference = typeReference;
    }

    @Override
    public String convertToDatabaseColumn(List<T> attribute) {
        if (attribute == null || attribute.isEmpty()) {
            return "[]";
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(attribute);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize list to JSON", e);
        }
    }

    @Override
    public List<T> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank() || "[]".equals(dbData.trim())) {
            return List.of();
        }
        try {
            return OBJECT_MAPPER.readValue(dbData, typeReference);
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to deserialize JSON to list", e);
        }
    }
}
