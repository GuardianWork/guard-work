package com.guardwork.backend.auth.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guardwork.backend.auth.AuthController;
import com.guardwork.backend.auth.AuthService;
import com.guardwork.backend.auth.LoginRequest;
import com.guardwork.backend.auth.RefreshTokenRequest;
import com.guardwork.backend.auth.RegisterUserRequest;
import com.guardwork.backend.auth.TokenPairResponse;
import com.guardwork.backend.auth.UserDto;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    @Mock
    private AuthService authService;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        AuthController controller = new AuthController(authService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void register_ValidPayload_Returns201() throws Exception {
        RegisterUserRequest request = new RegisterUserRequest(
                "Trần", "Bình", "binh_tran", "binh@example.com", "Password123"
        );
        UserDto userDto = new UserDto(10L, "Trần", "Bình", "binh_tran", "binh@example.com", "USER");
        when(authService.register(any(RegisterUserRequest.class))).thenReturn(userDto);

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.data.username").value("binh_tran"))
                .andExpect(jsonPath("$.data.role").value("USER"));
    }

    @Test
    void login_ValidPayload_Returns200WithTokenPair() throws Exception {
        LoginRequest request = new LoginRequest("binh_tran", "Password123");
        UserDto userDto = new UserDto(10L, "Trần", "Bình", "binh_tran", "binh@example.com", "USER");
        TokenPairResponse tokenPair = TokenPairResponse.of("access-token-jwt", "refresh-token-uuid", 900, userDto);

        when(authService.login(any(LoginRequest.class), anyString(), any())).thenReturn(tokenPair);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.data.accessToken").value("access-token-jwt"))
                .andExpect(jsonPath("$.data.refreshToken").value("refresh-token-uuid"));
    }

    @Test
    void refresh_ValidPayload_Returns200WithNewTokens() throws Exception {
        RefreshTokenRequest request = new RefreshTokenRequest("current-refresh-token");
        UserDto userDto = new UserDto(10L, "Trần", "Bình", "binh_tran", "binh@example.com", "USER");
        TokenPairResponse tokenPair = TokenPairResponse.of("new-access-token", "new-refresh-token", 900, userDto);

        when(authService.refreshToken(any(RefreshTokenRequest.class), anyString(), any())).thenReturn(tokenPair);

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.data.accessToken").value("new-access-token"));
    }

    @Test
    void logout_ValidPayload_Returns200() throws Exception {
        RefreshTokenRequest request = new RefreshTokenRequest("to-revoke-refresh-token");

        mockMvc.perform(post("/api/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.message").value("Logged out successfully"));

        verify(authService).logout("to-revoke-refresh-token");
    }
}
