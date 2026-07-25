package com.igmo.admin.web;

import com.igmo.admin.exception.AdminImageGenerationBusyException;
import com.igmo.admin.exception.AdminImageGenerationConfigurationException;
import com.igmo.admin.exception.AdminImageGenerationFailedException;
import com.igmo.admin.exception.AdminImageStorageConfigurationException;
import com.igmo.admin.exception.AdminImageStorageException;
import com.igmo.admin.exception.InvalidAdminImageGenerationRequestException;
import com.igmo.admin.web.dto.AdminErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = AdminImageGenerationController.class)
public class AdminImageGenerationExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<AdminErrorResponse> handleInvalidRequest(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().get(0).getDefaultMessage();
        return ResponseEntity.badRequest().body(new AdminErrorResponse(message));
    }

    @ExceptionHandler(InvalidAdminImageGenerationRequestException.class)
    public ResponseEntity<AdminErrorResponse> handleNotAllowed(InvalidAdminImageGenerationRequestException exception) {
        return ResponseEntity.badRequest().body(new AdminErrorResponse(exception.getMessage()));
    }

    @ExceptionHandler(AdminImageGenerationBusyException.class)
    public ResponseEntity<AdminErrorResponse> handleBusy(AdminImageGenerationBusyException exception) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(new AdminErrorResponse(exception.getMessage()));
    }

    @ExceptionHandler(AdminImageGenerationConfigurationException.class)
    public ResponseEntity<AdminErrorResponse> handleConfiguration(AdminImageGenerationConfigurationException exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new AdminErrorResponse(exception.getMessage()));
    }

    @ExceptionHandler(AdminImageGenerationFailedException.class)
    public ResponseEntity<AdminErrorResponse> handleGenerationFailure(AdminImageGenerationFailedException exception) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new AdminErrorResponse(exception.getMessage()));
    }

    @ExceptionHandler(AdminImageStorageConfigurationException.class)
    public ResponseEntity<AdminErrorResponse> handleStorageConfiguration(AdminImageStorageConfigurationException exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new AdminErrorResponse(exception.getMessage()));
    }

    @ExceptionHandler(AdminImageStorageException.class)
    public ResponseEntity<AdminErrorResponse> handleStorageFailure(AdminImageStorageException exception) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new AdminErrorResponse(exception.getMessage()));
    }
}
