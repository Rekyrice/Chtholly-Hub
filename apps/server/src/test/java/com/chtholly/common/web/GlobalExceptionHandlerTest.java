package com.chtholly.common.web;

import com.chtholly.common.exception.BusinessException;
import com.chtholly.common.exception.ErrorCode;
import com.chtholly.common.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
    }

    @Test
    void invalidBodyReturns400() {
        ResponseEntity<Map<String, Object>> response = handler.handleNotReadable(
                new HttpMessageNotReadableException("bad json", new RuntimeException("parse error")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("code", "INVALID_BODY");
        assertThat(response.getBody()).containsEntry("message", "请求体格式错误");
        assertThat(response.getBody()).containsKey("details");
    }

    @Test
    void accessDeniedReturns403() {
        ResponseEntity<Map<String, Object>> response = handler.handleAccessDenied(new AccessDeniedException("denied"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).containsEntry("code", "FORBIDDEN");
        assertThat(response.getBody()).containsEntry("message", "权限不足");
    }

    @Test
    void methodNotAllowedReturns405() {
        ResponseEntity<Map<String, Object>> response = handler.handleMethodNotSupported(
                new HttpRequestMethodNotSupportedException("PATCH"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getBody()).containsEntry("code", "METHOD_NOT_ALLOWED");
    }

    @Test
    void missingParamReturns400WithName() {
        ResponseEntity<Map<String, Object>> response = handler.handleMissingParam(
                new MissingServletRequestParameterException("page", "int"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("code", "MISSING_PARAM");
        assertThat(response.getBody().get("message")).asString().contains("page");
    }

    @Test
    void noHandlerReturns404() {
        ResponseEntity<Map<String, Object>> response = handler.handleNotFound(
                new NoHandlerFoundException("GET", "/api/v1/missing", null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsEntry("code", "NOT_FOUND");
    }

    @Test
    void resourceNotFoundReturns404() {
        ResponseEntity<Map<String, Object>> response = handler.handleBusiness(
                new ResourceNotFoundException("内容不存在"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsEntry("code", "RESOURCE_NOT_FOUND");
        assertThat(response.getBody()).containsEntry("message", "内容不存在");
    }

    @Test
    void businessExceptionDefaultsTo400() {
        ResponseEntity<Map<String, Object>> response = handler.handleBusiness(
                new BusinessException(ErrorCode.BAD_REQUEST, "参数非法"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("code", "BAD_REQUEST");
    }
}
