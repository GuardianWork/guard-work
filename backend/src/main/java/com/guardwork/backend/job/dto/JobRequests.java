package com.guardwork.backend.job.dto;

import tools.jackson.databind.annotation.JsonDeserialize;

public final class JobRequests {
  private JobRequests() {}

  public record Create(@JsonDeserialize(using = ExactContentDeserializer.class) Object content) {}

  public record Save(
      Long expectedJobVersion,
      Long expectedRevisionVersion,
      @JsonDeserialize(using = ExactContentDeserializer.class) Object content) {}
}
