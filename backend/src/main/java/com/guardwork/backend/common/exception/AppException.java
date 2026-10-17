package com.guardwork.backend.common.exception;

import lombok.Getter;

@Getter
public class AppException extends RuntimeException{
    private final ErrorCode errorCode;
    private final String message;

    public AppException(ErrorCode errorCode) {
        this.errorCode = errorCode;
        this.message = errorCode.getDetailedMessage();
    }

    public AppException(ErrorCode errorCode, String message) {
        this.errorCode = errorCode;
        this.message = message;
    }

}
