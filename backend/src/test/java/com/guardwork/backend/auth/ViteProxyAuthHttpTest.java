package com.guardwork.backend.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "guardwork.cors.allowed-origins=")
class ViteProxyAuthHttpTest {
    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate database;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;

    @ParameterizedTest
    @CsvSource({"USER,public,5137", "STAFF,staff,5138"})
    void proxyLoginPreservesBrowserOriginWithoutRequiringDirectCors(String role, String portal, int port) throws Exception {
        String username = "vite_" + UUID.randomUUID().toString().replace("-", "");
        String password = "ProxyTest123!";
        database.update("INSERT INTO users(first_name,last_name,username,email,password,role) VALUES ('Proxy','Tester',?,?,?,?)",
                username, username + "@example.vn", passwords.encode(password), role);
        var http = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        // Vite can rewrite Host while preserving the browser's original Origin header.
        http.perform(post("/api/auth/login").header("Host", "localhost:8080")
                .header("Origin", "http://localhost:" + port).header(Portal.HEADER, portal)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("usernameOrEmail", username, "password", password))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.user.role").value(role))
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }
}
