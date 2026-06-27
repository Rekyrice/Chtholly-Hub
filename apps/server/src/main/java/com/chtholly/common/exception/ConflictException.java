package com.chtholly.common.exception;

import org.springframework.http.HttpStatus;

/** 资源冲突，默认 HTTP 409。 */
public class ConflictException extends BusinessException {

    public ConflictException(String message) {
        super(ErrorCode.CONFLICT, message, HttpStatus.CONFLICT.value());
    }

    public ConflictException(ErrorCode errorCode, String message) {
        super(errorCode, message, HttpStatus.CONFLICT.value());
    }
}
