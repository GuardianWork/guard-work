package com.guardwork.backend.logging;

/** Reviewed constants only: never register request values or exception messages. */
public enum DiagnosticEvent {

    AUTH_LOGIN_REJECTED("auth_login_rejected", "authenticate", "AUTH:40101", "INVALID_CREDENTIALS"),
    AUTH_REGISTRATION_CONFLICT("auth_registration_conflict", "register_user", "AUTH:40901", "USERNAME_OR_EMAIL_EXISTS"),
    COMPANY_REGISTRATION_CONFLICT("company_registration_conflict", "register_company", "COMPANY:40901", "DUPLICATE_TAX_CODE"),
    UNEXPECTED_FAILURE("handled_failure", "handle_failure", "LOG:UNEXPECTED_FAILURE", "UNEXPECTED_FAILURE");

    private final String fields;
    private final String errorFields;

    DiagnosticEvent(String event, String operation, String code, String errorName) {
        errorFields = "error_code=" + code + " error_name=" + errorName;
        fields = "event=" + event + " operation=" + operation + " " + errorFields;
    }

    String fields() {
        return fields;
    }

    String errorFields() {
        return errorFields;
    }
}
