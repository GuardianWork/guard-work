package com.guardwork.backend.job.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Structural validation only: completeness and cross-field eligibility belong to submission. */
@Component
public class DraftContent {
  private static final Map<String, Integer> TEXT = Map.ofEntries(
      Map.entry("title", 200), Map.entry("description", 10000), Map.entry("responsibilities", 10000),
      Map.entry("requiredQualifications", 5000), Map.entry("preferredQualifications", 5000),
      Map.entry("experienceExpectations", 5000), Map.entry("benefits", 5000),
      Map.entry("location", 1000), Map.entry("applicationInstructions", 2000));
  private final ObjectMapper json;

  public DraftContent(ObjectMapper json) {
    this.json = json.copy().enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .setNodeFactory(com.fasterxml.jackson.databind.node.JsonNodeFactory.withExactBigDecimals(true));
  }

  public String normalize(Object value) {
    JsonNode input = json.valueToTree(value);
    if (input == null || !input.isObject()) throw invalid("content");
    ObjectNode content = ((ObjectNode) input).deepCopy();
    Set<String> allowed = new HashSet<>(TEXT.keySet());
    allowed.addAll(Set.of("employmentType", "workArrangement", "deadline", "headcount", "compensation", "screeningQuestions"));
    known(content, allowed, "content");
    TEXT.forEach((field, limit) -> text(content, field, limit, "content." + field));
    enumeration(content, "employmentType", Set.of("FULL_TIME", "PART_TIME", "CONTRACT", "INTERNSHIP", "TEMPORARY"));
    enumeration(content, "workArrangement", Set.of("ONSITE", "HYBRID", "REMOTE"));
    if (present(content, "headcount") && (!content.get("headcount").isIntegralNumber()
        || !content.get("headcount").canConvertToInt() || content.get("headcount").intValue() < 1
        || content.get("headcount").intValue() > 10000)) throw invalid("content.headcount");
    if (present(content, "deadline")) {
      if (!content.get("deadline").isTextual()) throw invalid("content.deadline");
      try { content.put("deadline", OffsetDateTime.parse(content.get("deadline").textValue()).toInstant().toString()); }
      catch (RuntimeException e) { throw invalid("content.deadline"); }
    }
    if (present(content, "compensation")) compensation(content.get("compensation"));
    if (present(content, "screeningQuestions")) questions(content.get("screeningQuestions"));
    try { return json.writeValueAsString(content); }
    catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw invalid("content"); }
  }

  public Map<String, Object> decode(String value) {
    try { return json.readValue(value, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}); }
    catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException("Invalid persisted job content", e); }
  }

  private void compensation(JsonNode value) {
    if (!value.isObject()) throw invalid("content.compensation");
    ObjectNode c = (ObjectNode) value;
    known(c, Set.of("disclosed", "minimum", "maximum", "currency", "payPeriod"), "content.compensation");
    if (present(c, "disclosed") && !c.get("disclosed").isBoolean()) throw invalid("content.compensation.disclosed");
    for (String f : List.of("minimum", "maximum")) if (present(c, f)) {
      if (!c.get(f).isNumber()) throw invalid("content.compensation." + f);
      BigDecimal n = c.get(f).decimalValue();
      if (n.signum() <= 0 || n.scale() > 2 || n.precision() + Math.max(0, -n.scale()) > 18)
        throw invalid("content.compensation." + f);
    }
    enumeration(c, "currency", Set.of("VND", "USD"));
    enumeration(c, "payPeriod", Set.of("HOUR", "DAY", "MONTH", "YEAR"));
  }

  private void questions(JsonNode value) {
    if (!value.isArray() || value.size() > 10) throw invalid("content.screeningQuestions");
    Set<UUID> keys = new HashSet<>();
    for (JsonNode q : value) {
      if (!q.isObject()) throw invalid("content.screeningQuestions[]");
      ObjectNode question = (ObjectNode) q;
      known(question, Set.of("key", "type", "prompt", "required", "options"), "content.screeningQuestions[]");
      if (present(q, "key")) {
        try {
          String key = q.get("key").textValue();
          UUID id = UUID.fromString(key);
          if (!id.toString().equalsIgnoreCase(key) || !keys.add(id)) throw invalid("content.screeningQuestions[].key");
        } catch (RuntimeException e) { throw invalid("content.screeningQuestions[].key"); }
      }
      enumeration(question, "type", Set.of("SHORT_TEXT", "SINGLE_CHOICE", "YES_NO", "NUMERIC"));
      text(question, "prompt", 500, "content.screeningQuestions[].prompt");
      if (present(q, "required") && !q.get("required").isBoolean()) throw invalid("content.screeningQuestions[].required");
      if (present(q, "options")) {
        JsonNode options = q.get("options");
        if (!options.isArray() || options.size() > 10) throw invalid("content.screeningQuestions[].options");
        Set<String> labels = new HashSet<>();
        for (int i = 0; i < options.size(); i++) {
          if (!options.get(i).isTextual()) throw invalid("content.screeningQuestions[].options");
          String label = options.get(i).textValue().strip();
          if (label.isBlank() || label.indexOf('\0') >= 0 || label.codePointCount(0, label.length()) > 200 || !labels.add(label))
            throw invalid("content.screeningQuestions[].options");
          ((com.fasterxml.jackson.databind.node.ArrayNode) options).set(i, json.getNodeFactory().textNode(label));
        }
      }
    }
  }

  private static boolean present(JsonNode node, String field) { return node.hasNonNull(field); }
  private static void known(JsonNode node, Set<String> fields, String path) {
    node.fieldNames().forEachRemaining(f -> { if (!fields.contains(f)) throw invalid(path + "." + f); });
  }
  private static void text(ObjectNode node, String field, int limit, String path) {
    if (!present(node, field)) return;
    if (!node.get(field).isTextual()) throw invalid(path);
    String value = node.get(field).textValue().strip();
    if (value.codePointCount(0, value.length()) > limit || value.indexOf('\0') >= 0) throw invalid(path);
    node.put(field, value);
  }
  private static void enumeration(ObjectNode node, String field, Set<String> values) {
    if (present(node, field) && (!node.get(field).isTextual() || !values.contains(node.get(field).textValue())))
      throw invalid("content." + field);
  }
  public static ResponseStatusException invalid(String field) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, "40000 BAD_REQUEST: Invalid " + field);
  }
}
