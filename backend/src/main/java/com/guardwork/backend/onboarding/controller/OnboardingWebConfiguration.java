package com.guardwork.backend.onboarding.controller;

import com.guardwork.backend.auth.security.UserPrincipal;
import com.guardwork.backend.onboarding.service.OnboardingAccessPolicy;
import jakarta.servlet.http.*;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.*;

@Configuration
public class OnboardingWebConfiguration implements WebMvcConfigurer {
    private final OnboardingAccessPolicy policy;
    private final com.fasterxml.jackson.databind.ObjectMapper json;
    public OnboardingWebConfiguration(OnboardingAccessPolicy policy, com.fasterxml.jackson.databind.ObjectMapper json) {
        this.policy = policy; this.json = json;
    }

    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws java.io.IOException {
                var authentication = SecurityContextHolder.getContext().getAuthentication();
                if (authentication != null && authentication.getPrincipal() instanceof UserPrincipal user
                        && !"ADMIN".equals(user.getRole()) && !policy.permits(user.getId(),
                                request.getRequestURI().substring(request.getContextPath().length()), request.getMethod())) {
                    response.setStatus(403); response.setContentType("application/json");
                    json.writeValue(response.getOutputStream(), new com.guardwork.backend.common.ApiResponse<>(403,
                            "40330 ONBOARDING_RESTRICTED: Complete the required verification first", null));
                    return false;
                }
                return true;
            }
        }).addPathPatterns("/api/**");
    }
}
