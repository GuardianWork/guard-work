package com.guardwork.backend.job.dto;

import tools.jackson.core.JsonParser;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;

/** Keep compensation decimals exact without changing other features' JSON configuration. */
public class ExactContentDeserializer extends ValueDeserializer<Object> {
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .disable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  @Override
  public Object deserialize(JsonParser parser, DeserializationContext context) {
    return JSON.readValue(parser, Object.class);
  }
}
