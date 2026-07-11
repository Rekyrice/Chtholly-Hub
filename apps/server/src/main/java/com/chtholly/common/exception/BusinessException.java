package com.chtholly.common.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * 业务异常。
 *
 * <p>用于在业务校验失败时携带明确的 {@link ErrorCode} 抛出，由全局异常处理器统一转为 HTTP 响应。</p>
 */
@Getter
public class BusinessException extends RuntimeException {

    /** 业务错误码，用于前端/调用方做稳定的错误分支处理。 */
    private final ErrorCode errorCode;

    /** HTTP 状态码，默认 400。 */
    private final int httpStatus;

    /**
     * 使用错误码的默认文案构造异常。
     *
     * @param errorCode 错误码（必填）
     */
    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.getDefaultMessage(), defaultHttpStatus(errorCode));
    }

    /**
     * 使用自定义文案构造异常（错误码不变）。
     *
     * @param errorCode 错误码（必填）
     * @param message 自定义提示文案
     */
    public BusinessException(ErrorCode errorCode, String message) {
        this(errorCode, message, defaultHttpStatus(errorCode));
    }

    /**
     * 使用自定义文案与 HTTP 状态码构造异常。
     *
     * @param errorCode 错误码（必填）
     * @param message 自定义提示文案
     * @param httpStatus HTTP 状态码
     */
    public BusinessException(ErrorCode errorCode, String message, int httpStatus) {
        super(message);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
    }

    private static int defaultHttpStatus(ErrorCode errorCode) {
        return errorCode == ErrorCode.INTERNAL_ERROR
                ? HttpStatus.INTERNAL_SERVER_ERROR.value()
                : HttpStatus.BAD_REQUEST.value();
    }

}
