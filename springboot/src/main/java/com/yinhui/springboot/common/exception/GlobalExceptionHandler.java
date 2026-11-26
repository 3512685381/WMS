package com.yinhui.springboot.common.exception;

import com.yinhui.springboot.common.result.Result;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Result<Void>> handleApiException(ApiException error) {
        return failure(error.getStatus(), error.getMessage());
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Result<Void>> handleConstraintViolation() {
        return failure(HttpStatus.BAD_REQUEST, "请求参数有误");
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Result<Void>> handleAccessDenied() {
        return failure(HttpStatus.FORBIDDEN, "权限不足");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleUnexpected(Exception error) {
        log.error("请求处理失败", error);
        return failure(HttpStatus.INTERNAL_SERVER_ERROR, "服务器处理请求失败");
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException error,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        var firstError = error.getBindingResult().getFieldError();
        String message = firstError == null ? "请求参数有误" : firstError.getDefaultMessage();
        return handleExceptionInternal(error, Result.failure(status.value(), message), headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception error, Object body,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (status.is5xxServerError()) {
            log.error("请求处理失败", error);
        }
        Object response = body instanceof Result<?> ? body : Result.failure(status.value(), messageFor(status));
        return super.handleExceptionInternal(error, response, headers, status, request);
    }

    private static ResponseEntity<Result<Void>> failure(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Result.failure(status.value(), message));
    }

    private static String messageFor(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> "请求参数或请求体有误";
            case 404 -> "接口不存在";
            case 405 -> "请求方法不受支持";
            case 406 -> "请求的响应格式不受支持";
            case 415 -> "请求内容类型不受支持";
            default -> status.is5xxServerError() ? "服务器处理请求失败" : "请求无法处理";
        };
    }
}
