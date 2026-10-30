package com.guardwork.backend.common.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

@Getter
public enum ErrorCode {
    // --- BAD REQUEST (400) ---

    // General
    INVALID_INPUT(40000, "Invalid input. Please check your data", HttpStatus.BAD_REQUEST),

    // Authentication & User
    INVALID_CREDENTIALS(40001, "Invalid credentials. Please check your username and password.", HttpStatus.BAD_REQUEST),
    USER_ALREADY_EXISTS(40010, "A user with this username or email already exists.", HttpStatus.BAD_REQUEST),
    EMAIL_ALREADY_EXISTS(40011, "A user with this email address already exists.", HttpStatus.BAD_REQUEST),
    PHONE_NUMBER_ALREADY_EXISTS(40012, "A user with this phone number already exists.", HttpStatus.BAD_REQUEST),
    SAME_PASSWORD(40013, "Your new password cannot be the same as your old password.", HttpStatus.BAD_REQUEST),
    ACCOUNT_BLOCKED(40014, "Your account has been blocked. Please contact support for assistance.", HttpStatus.BAD_REQUEST),
    INVALID_EMAIL_FORMAT(40015, "The email address you entered is not valid.", HttpStatus.BAD_REQUEST),
    INVALID_OTP(40018, "The OTP code is invalid or has expired.", HttpStatus.BAD_REQUEST),

    // Candidate CV
    CV_CONSENT_REQUIRED(40020, "You must confirm the CV privacy notice before uploading.", HttpStatus.BAD_REQUEST),
    INVALID_CV_FILE_FORMAT(40021, "Only PDF, DOC, and DOCX files up to 10 MB are accepted.", HttpStatus.BAD_REQUEST),
    CV_QUOTA_EXCEEDED(40022, "You have reached the maximum number of CVs.", HttpStatus.BAD_REQUEST),
    CV_ASSET_NOT_VERIFIED(40023, "The uploaded file could not be verified. Please upload it again.", HttpStatus.BAD_REQUEST),

    // --- UNAUTHENTICATED (401) ---
    UNAUTHENTICATED(40100, "You must be logged in to perform this action.", HttpStatus.UNAUTHORIZED),
    INVALID_TOKEN(40101, "Invalid or expired token. Please log in again.", HttpStatus.UNAUTHORIZED),
    EXPIRED_TOKEN(40102, "Your session has expired. Please log in again.", HttpStatus.UNAUTHORIZED),

    // --- FORBIDDEN (403) ---
    FORBIDDEN(40300, "You do not have permission to access this resource.", HttpStatus.FORBIDDEN),
    USER_NOT_VERIFIED(40301, "Your account is not verified.", HttpStatus.FORBIDDEN),
    ACCOUNT_SUSPENDED(40304, "Your account is suspended. Please contact support for assistance.", HttpStatus.FORBIDDEN),

    // --- NOT FOUND (404) ---
    RESOURCE_NOT_FOUND(40400, "No endpoint found for this request", HttpStatus.NOT_FOUND),
    USER_NOT_FOUND(40401, "The user you are looking for could not be found.", HttpStatus.NOT_FOUND),
    VERIFICATION_TOKEN_NOT_FOUND(40404, "The verification token is invalid or has expired.", HttpStatus.NOT_FOUND),
    CV_NOT_FOUND(40405, "The CV could not be found.", HttpStatus.NOT_FOUND),
    JOB_NOT_FOUND(40478, "The job posting could not be found.", HttpStatus.NOT_FOUND),

    // --- CONFLICT (409) ---
    DUPLICATE_CV(40902, "This CV has already been registered.", HttpStatus.CONFLICT),
    APPLICATION_ALREADY_SUBMITTED(40903, "You have already applied to this job.", HttpStatus.CONFLICT),
    JOB_NOT_ACCEPTING_APPLICATIONS(40904, "This job posting is no longer accepting applications.", HttpStatus.CONFLICT),

    // --- SERVICE UNAVAILABLE (503) ---
    CV_STORAGE_UNAVAILABLE(50300, "CV storage is temporarily unavailable. Please try again later.", HttpStatus.SERVICE_UNAVAILABLE),
    APPLICATION_CV_COPY_FAILED(50301, "The CV could not be attached to your application. Please try again later.", HttpStatus.SERVICE_UNAVAILABLE),

    // --- TOO MANY REQUESTS (429) ---
    TOO_MANY_REQUESTS(42900, "Too many requests. Please wait %s seconds before trying again.", HttpStatus.TOO_MANY_REQUESTS);

    private final int code;
    private final String detailedMessage;
    private final HttpStatusCode statusCode;

    ErrorCode(int code, String message, HttpStatusCode statusCode) {
        this.code = code;
        this.detailedMessage = message;
        this.statusCode = statusCode;
    }
}
