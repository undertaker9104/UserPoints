package com.example.demo.exception;

import lombok.Getter;

@Getter
public enum ErrorCode {

    SUCCESS(200, "Success"),
    BAD_REQUEST(400, "Bad request"),
    INVALID_PARAMETER(400, "Invalid parameter"),
    USER_NOT_FOUND(404, "User not found"),
    RECORD_NOT_FOUND(404, "Record not found"),
    CONFLICT(409, "Operation conflict, please retry"),
    INTERNAL_ERROR(500, "Internal server error"),
    LOCK_ACQUIRE_FAILED(500, "Failed to acquire lock"),
    MQ_SEND_FAILED(500, "Failed to send message"),
    SERVICE_UNAVAILABLE(503, "Service temporarily unavailable");

    private final int httpStatus;
    private final String message;

    ErrorCode(int httpStatus, String message) {
        this.httpStatus = httpStatus;
        this.message = message;
    }
}
