package com.network.monitoring.exception;

import com.network.monitoring.dto.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler({BadRequestException.class, IllegalArgumentException.class})
    public ResponseEntity<ApiResponse<Object>> handleBadRequest(RuntimeException ex, HttpServletRequest request, HttpServletResponse response) {
        return error(HttpStatus.BAD_REQUEST, ex.getMessage(), request, response);
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiResponse<Object>> handleNotFound(ResourceNotFoundException ex, HttpServletRequest request, HttpServletResponse response) {
        return error(HttpStatus.NOT_FOUND, ex.getMessage(), request, response);
    }

    @ExceptionHandler(FileProcessingException.class)
    public ResponseEntity<ApiResponse<Object>> handleFileProcessing(FileProcessingException ex, HttpServletRequest request, HttpServletResponse response) {
        return error(HttpStatus.BAD_REQUEST, ex.getMessage(), request, response);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Object>> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request, HttpServletResponse response) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .orElse("Validation failed.");
        return error(HttpStatus.BAD_REQUEST, message, request, response);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Object>> handleUnexpected(Exception ex, HttpServletRequest request, HttpServletResponse response) {
        log.error("Unhandled backend exception", ex);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error occurred.", request, response);
    }

    private ResponseEntity<ApiResponse<Object>> error(HttpStatus status, String message, HttpServletRequest request, HttpServletResponse response) {
        if (!response.isCommitted()) {
            response.resetBuffer();
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("status", status.value());
        details.put("error", status.getReasonPhrase());
        details.put("path", request == null ? "" : request.getRequestURI());
        details.put("timestamp", Instant.now().toString());
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.error(message == null || message.isBlank() ? "Request failed." : message, details));
    }
}
