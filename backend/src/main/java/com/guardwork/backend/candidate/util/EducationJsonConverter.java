package com.guardwork.backend.candidate.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.guardwork.backend.candidate.dto.EducationEntry;
import jakarta.persistence.Converter;
import java.util.List;

@Converter(autoApply = false)
public class EducationJsonConverter extends JsonbConverter<EducationEntry> {
    public EducationJsonConverter() {
        super(new TypeReference<List<EducationEntry>>() {});
    }
}
