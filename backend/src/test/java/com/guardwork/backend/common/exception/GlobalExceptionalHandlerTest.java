package com.guardwork.backend.common.exception;

import com.guardwork.backend.common.api.ErrorApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GlobalExceptionalHandlerTest {

    private final GlobalExceptionalHandler handler = new GlobalExceptionalHandler();

    @Test
    void appExceptionUsesErrorCodeHttpStatus() {
        ResponseEntity<ErrorApiResponse> response =
                handler.handleAppException(new AppException(ErrorCode.USER_NOT_FOUND));

        assertEquals(404, response.getStatusCode().value());
        assertEquals(ErrorCode.USER_NOT_FOUND.getCode(), response.getBody().status());
    }

    @Test
    void appExceptionPreservesCustomDetailMessage() {
        ResponseEntity<ErrorApiResponse> response =
                handler.handleAppException(new AppException(ErrorCode.INVALID_INPUT, "Skill is too long"));

        assertEquals(400, response.getStatusCode().value());
        assertEquals("Skill is too long", response.getBody().detailMessage());
    }
}
