package com.guardwork.backend.membership.operations;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Opt-in local command. Bearer credentials come only from the process environment, never argv. */
@Component
@ConditionalOnProperty(name = "guardwork.ownership-command")
public class OwnershipCommandRunner implements ApplicationRunner {
  private final LegacyOwnershipCommand command;
  private final ObjectMapper mapper;
  private final Environment environment;

  public OwnershipCommandRunner(
      LegacyOwnershipCommand command, ObjectMapper mapper, Environment environment) {
    this.command = command;
    this.mapper = mapper;
    this.environment = environment;
  }

  @Override
  public void run(ApplicationArguments arguments) throws Exception {
    if (!arguments.containsOption("guardwork.ownership-command"))
      throw new IllegalArgumentException(
          "Ownership operations require an explicit local command argument");
    if (!"127.0.0.1".equals(environment.getProperty("server.address"))
        || !"0".equals(environment.getProperty("server.port")))
      throw new IllegalArgumentException(
          "Ownership commands require local ephemeral server configuration");
    String token = System.getenv("GUARDWORK_OWNERSHIP_TOKEN");
    if (token == null || token.isBlank())
      throw new IllegalArgumentException("GUARDWORK_OWNERSHIP_TOKEN required");
    String action = environment.getProperty("guardwork.ownership-command");
    String input = environment.getProperty("guardwork.ownership-input");
    if (input == null)
      throw new IllegalArgumentException("Permission-controlled input file required");
    Path path = Path.of(input);
    if (!Files.isRegularFile(path) || Files.size(path) > 8192)
      throw new IllegalArgumentException("A small case JSON file is required");
    try {
      var permissions = Files.getPosixFilePermissions(path);
      if (permissions.stream()
          .anyMatch(
              permission ->
                  permission.name().startsWith("GROUP_")
                      || permission.name().startsWith("OTHERS_")))
        throw new IllegalArgumentException(
            "Case input must be private to its operator (chmod 600)");
    } catch (UnsupportedOperationException ignored) {
      // Non-POSIX deployments must enforce their equivalent restricted file ACL.
    }
    try {
      // Parse only approved case metadata; documents and secrets never belong in this input.
      if ("review".equals(action)) {
        var review = mapper.readValue(Files.readString(path), LegacyOwnershipCommand.Review.class);
        String reference = command.review(token, review);
        System.out.println(
            mapper.writeValueAsString(
                java.util.Map.of("caseReference", reference, "decision", "APPROVED")));
      } else if ("assign".equals(action) || "inspect".equals(action)) {
        var node = mapper.readTree(Files.readString(path));
        if (!node.isObject() || node.size() != 1 || !node.path("caseReference").isTextual())
          throw new IllegalArgumentException("Assignment input must contain only caseReference");
        Object result =
            "assign".equals(action)
                ? command.assign(token, node.path("caseReference").asText())
                : command.inspect(token, node.path("caseReference").asText());
        System.out.println(mapper.writeValueAsString(result));
      } else
        throw new IllegalArgumentException(
            "Only review, assign or inspect ownership commands are supported");
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      // Never echo arbitrary input content or evidence into the application's exception logs.
      throw new IllegalArgumentException("Invalid case metadata JSON");
    }
  }
}
