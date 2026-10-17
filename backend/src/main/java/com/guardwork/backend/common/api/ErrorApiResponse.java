package com.guardwork.backend.common.api;


import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

import java.util.Map;

@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorApiResponse(
    String message,
    int status,
    @JsonProperty("detailed_message")
    String detailMessage,
    Map<String, String> errors
) { }
