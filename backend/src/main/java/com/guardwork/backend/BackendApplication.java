package com.guardwork.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class BackendApplication {

	public static void main(String[] args) {
        SpringApplication application = new SpringApplication(BackendApplication.class);
        boolean ownershipCommand = java.util.Arrays.stream(args)
                .anyMatch(arg -> arg.startsWith("--guardwork.ownership-command="));
        if (ownershipCommand) {
            application.setDefaultProperties(java.util.Map.of("server.address", "127.0.0.1", "server.port", "0",
                    "logging.level.org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration", "ERROR"));
            try (var context = application.run(args)) {
                // The restricted runner completed; close persistence and the local ephemeral servlet context.
            }
        } else {
            application.run(args);
        }
	}

	@org.springframework.context.annotation.Bean
	public com.fasterxml.jackson.databind.ObjectMapper objectMapper() {
		return new com.fasterxml.jackson.databind.ObjectMapper();
	}
}
