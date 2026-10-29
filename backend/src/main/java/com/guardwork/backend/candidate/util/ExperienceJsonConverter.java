package com.guardwork.backend.candidate.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.guardwork.backend.candidate.dto.ExperienceEntry;
import jakarta.persistence.Converter;
import java.util.List;

@Converter(autoApply = false)
public class ExperienceJsonConverter extends JsonbConverter<ExperienceEntry> {
    public ExperienceJsonConverter() {
        super(new TypeReference<List<ExperienceEntry>>() {});
    }
}
