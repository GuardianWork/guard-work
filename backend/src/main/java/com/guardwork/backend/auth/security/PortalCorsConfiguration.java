package com.guardwork.backend.auth.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guardwork.backend.common.ApiResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.DefaultCorsProcessor;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import jakarta.servlet.http.HttpServletRequest;

@Configuration
public class PortalCorsConfiguration {
    @Bean
    public CorsFilter portalCorsFilter(@Value("${guardwork.cors.allowed-origins:}") String origins,
                                       ObjectMapper json) {
        var configuration = new CorsConfiguration();
        List<String> allowedOrigins = Arrays.stream(origins.split(","))
                .map(String::trim).filter(value -> !value.isEmpty()).toList();
        if (allowedOrigins.contains("*")) {
            throw new IllegalArgumentException("Portal CORS requires exact frontend origins");
        }
        configuration.setAllowedOrigins(allowedOrigins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Guardian-Portal", "X-Request-ID"));
        configuration.setExposedHeaders(List.of("X-Request-ID"));
        configuration.setAllowCredentials(false);
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        var filter = new CorsFilter(source) {
            @Override
            protected boolean shouldNotFilter(HttpServletRequest request) {
                // Vite may retain Origin while changing Host. Leave proxy traffic alone
                // unless direct browser CORS was explicitly enabled for this deployment.
                return allowedOrigins.isEmpty()
                        || !request.getRequestURI().startsWith(request.getContextPath() + "/api/");
            }
        };
        filter.setCorsProcessor(new DefaultCorsProcessor() {
            @Override
            protected void rejectRequest(ServerHttpResponse response) throws IOException {
                response.setStatusCode(HttpStatus.FORBIDDEN);
                response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                json.writeValue(response.getBody(), new ApiResponse<Void>(403,
                        "40300 FORBIDDEN: Cross-origin request denied", null));
                response.flush();
            }
        });
        return filter;
    }
}
