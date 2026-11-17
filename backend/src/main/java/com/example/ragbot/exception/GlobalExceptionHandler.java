package com.example.ragbot.exception;

import com.example.ragbot.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.io.IOException;
import java.time.Instant;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleBadRequest(IllegalArgumentException e,
                                                           HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, e.getMessage(), request);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleIllegalState(IllegalStateException e,
                                                             HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, e.getMessage(), request);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleTooLarge(MaxUploadSizeExceededException e,
                                                         HttpServletRequest request) {
        return build(HttpStatus.PAYLOAD_TOO_LARGE, "文件大小超出限制", request);
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<ErrorResponse> handleIo(IOException e, HttpServletRequest request) {
        log.error("IO 异常", e);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "文件读写失败: " + e.getMessage(), request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception e, HttpServletRequest request) {
        log.error("未处理异常", e);
        return build(HttpStatus.INTERNAL_SERVER_ERROR,
                e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), request);
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String message,
                                                 HttpServletRequest request) {
        return ResponseEntity.status(status).body(new ErrorResponse(
                message, request.getRequestURI(), Instant.now()));
    }
}
