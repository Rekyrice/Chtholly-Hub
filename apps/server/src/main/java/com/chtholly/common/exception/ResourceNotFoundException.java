package com.chtholly.common.exception;

import org.springframework.http.HttpStatus;

/** 资源不存在，默认 HTTP 404。 */
public class ResourceNotFoundException extends BusinessException {

    public ResourceNotFoundException(String message) {
        super(ErrorCode.RESOURCE_NOT_FOUND, message, HttpStatus.NOT_FOUND.value());
    }
}
