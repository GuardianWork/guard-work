package com.guardwork.backend.common.utils;


import com.guardwork.backend.auth.security.UserPrincipal;
import com.guardwork.backend.common.exception.AppException;
import com.guardwork.backend.common.exception.ErrorCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;


public class SecurityUtils {

    private SecurityUtils() {
    }

    public static Long getCurrentUserId(){
        SecurityContext context = SecurityContextHolder.getContext();
        return extractPrincipal(context.getAuthentication());
    }

    // retrieve id
    private static Long extractPrincipal(Authentication authentication){
        if (authentication == null)
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        else if (authentication.getPrincipal() instanceof UserPrincipal userPrincipal){
            return userPrincipal.getId();
        } else if (authentication.getPrincipal() instanceof Long s){
            return s;
        }
        throw new AppException(ErrorCode.UNAUTHENTICATED);
    }


}

