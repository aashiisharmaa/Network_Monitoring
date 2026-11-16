package com.network.monitoring.alarm.api;

import com.network.monitoring.alarm.exception.AlarmConfigurationException;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {AlarmConfigurationController.class, AlarmWorkflowController.class})
public class AlarmConfigurationAdvice {
    @ExceptionHandler(AlarmConfigurationException.class)
    public ResponseEntity<?> domain(AlarmConfigurationException error) { return error(error.status(), error.code(), error.getMessage()); }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<?> validation(MethodArgumentNotValidException error) {
        return error(400, "INVALID_ALARM_CONFIGURATION", "Required configuration fields are missing or invalid.");
    }
    @ExceptionHandler({HttpMessageNotReadableException.class, MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<?> request(Exception error) {
        return error(400, "INVALID_ALARM_CONFIGURATION", "Request JSON, path or query parameters are invalid.");
    }
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> invalid(IllegalArgumentException error) {
        return error(400,"INVALID_ALARM_CONFIGURATION","Configuration values are invalid.");
    }
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<?> conflict(DataIntegrityViolationException error) {
        return error(409, "ALARM_CONFIGURATION_CONFLICT", "Code/alias/source key already exists or a referenced mapping changed.");
    }
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<?> database(DataAccessException error) {
        return error(503, "ALARM_CONFIGURATION_UNAVAILABLE", "Configuration storage is unavailable. Verify migration 008 and database health.");
    }
    private ResponseEntity<?> error(int status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of("success", false, "code", code, "message", message));
    }
}
