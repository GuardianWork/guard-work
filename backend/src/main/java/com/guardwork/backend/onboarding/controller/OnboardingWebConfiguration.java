package com.guardwork.backend.onboarding.controller;

import java.io.IOException;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guardwork.backend.auth.security.UserPrincipal;
import com.guardwork.backend.common.ApiResponse;
import com.guardwork.backend.company.controller.CompanyController;
import com.guardwork.backend.onboarding.service.OnboardingAccessPolicy;

@Configuration
public class OnboardingWebConfiguration implements WebMvcConfigurer {
    private final OnboardingAccessPolicy policy;
    private final ObjectMapper json;

    public OnboardingWebConfiguration(OnboardingAccessPolicy policy, ObjectMapper json) {
        this.policy = policy;
        this.json = json;
    }

    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
                    throws IOException {
                var authentication = SecurityContextHolder.getContext().getAuthentication();
                if (authentication != null && authentication.getPrincipal() instanceof UserPrincipal user) {
                    try {
                        if (retiredCompanyHandler(handler)) {
                            // These resolved JSON-only handlers return 410 without invoking Company services.
                            policy.requireActiveAccount(user.getId());
                        } else {
                            policy.requireAccess(user.getId(), request.getRequestURI().substring(request.getContextPath().length()),
                                    request.getMethod());
                        }
                    } catch (ResponseStatusException denied) {
                        response.setStatus(denied.getStatusCode().value());
                        response.setContentType("application/json");
                        json.writeValue(response.getOutputStream(), new ApiResponse<>(denied.getStatusCode().value(),
                                denied.getReason(), null));
                        return false;
                    }
                }
                return true;
            }
        }).addPathPatterns("/api/**");
    }

    private static boolean retiredCompanyHandler(Object handler) {
        return handler instanceof HandlerMethod method
                && CompanyController.class.isAssignableFrom(method.getBeanType())
                && Set.of("retiredRegistration", "retiredCorrection").contains(method.getMethod().getName());
    }
}
