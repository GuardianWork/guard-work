package com.guardwork.backend.auth;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpServletRequest;

/** Server-verified portal context, independent of Company membership roles. */
public enum Portal {
    PUBLIC("public", "USER"),
    STAFF("staff", "STAFF");

    public static final String HEADER = "X-Guardian-Portal";
    private final String value;
    private final String role;

    Portal(String value, String role) {
        this.value = value;
        this.role = role;
    }

    public String value() { return value; }

    public void requireRole(String actualRole) {
        if (!role.equals(actualRole)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "40300 FORBIDDEN: Account cannot use this portal");
        }
    }

    public static Portal parse(String value) {
        for (Portal portal : values()) if (portal.value.equals(value)) return portal;
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "40300 FORBIDDEN: Invalid portal");
    }

    public static Portal fromRole(String role) {
        return "STAFF".equals(role) ? STAFF : PUBLIC;
    }

    /** Missing headers retain legacy clients; ambiguous proxy context fails closed. */
    public static Portal fromRequest(HttpServletRequest request) {
        var values = request.getHeaders(HEADER);
        if (values == null || !values.hasMoreElements()) return null;
        Portal portal = parse(values.nextElement());
        if (values.hasMoreElements()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "40300 FORBIDDEN: Multiple portal headers");
        }
        return portal;
    }
}
