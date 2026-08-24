package com.network.monitoring.exception;

import com.network.monitoring.dto.response.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler({BadRequestException.class, IllegalArgumentException.class})
    public ResponseEntity<ApiResponse<Object>> handleBadRequest(RuntimeException ex, HttpServletResponse response) {
        return error(HttpStatus.BAD_REQUEST, ex.getMessage(), response);
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiResponse<Object>> handleNotFound(ResourceNotFoundException ex, HttpServletResponse response) {
        return error(HttpStatus.NOT_FOUND, ex.getMessage(), response);
    }

    @ExceptionHandler(FileProcessingException.class)
    public ResponseEntity<ApiResponse<Object>> handleFileProcessing(FileProcessingException ex, HttpServletResponse response) {
        return error(HttpStatus.BAD_REQUEST, ex.getMessage(), response);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Object>> handleValidation(MethodArgumentNotValidException ex, HttpServletResponse response) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .orElse("Validation failed.");
        return error(HttpStatus.BAD_REQUEST, message, response);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Object>> handleUnexpected(Exception ex, HttpServletResponse response) {
        log.error("Unhandled backend exception", ex);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error occurred.", response);
    }

    private ResponseEntity<ApiResponse<Object>> error(HttpStatus status, String message, HttpServletResponse response) {
        if (!response.isCommitted()) {
            response.resetBuffer();
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        }
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.error(message == null || message.isBlank() ? "Request failed." : message));
    }
}
