package com.igmo.admin.web;

import com.igmo.admin.exception.AdminImageGenerationBusyException;
import com.igmo.admin.exception.AdminImageGenerationConfigurationException;
import com.igmo.admin.exception.AdminImageGenerationFailedException;
import com.igmo.admin.exception.AdminImageStorageConfigurationException;
import com.igmo.admin.exception.AdminImageStorageException;
import com.igmo.admin.exception.InvalidAdminImageGenerationRequestException;
import com.igmo.admin.web.dto.AdminErrorResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = AdminImageGenerationController.class)
@Slf4j
public class AdminImageGenerationExceptionHandler {

    private static final String INVALID_REQUEST_MESSAGE = "요청 값을 확인해주세요.";
    private static final String BUSY_MESSAGE = "이미지 생성 요청이 처리 중입니다. 잠시 후 다시 시도해주세요.";
    private static final String GENERATION_FAILURE_MESSAGE = "이미지 생성에 실패했습니다. 잠시 후 다시 시도해주세요.";

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<AdminErrorResponse> handleInvalidRequest(MethodArgumentNotValidException exception) {
        var fieldError = exception.getBindingResult().getFieldErrors().get(0);
        log.warn("관리자 이미지 생성 요청이 유효하지 않습니다. field={}, reason={}",
                fieldError.getField(), fieldError.getDefaultMessage());
        return ResponseEntity.badRequest().body(new AdminErrorResponse(INVALID_REQUEST_MESSAGE));
    }

    @ExceptionHandler(InvalidAdminImageGenerationRequestException.class)
    public ResponseEntity<AdminErrorResponse> handleNotAllowed(InvalidAdminImageGenerationRequestException exception) {
        log.warn("관리자 이미지 생성 요청이 허용되지 않았습니다. reason={}", exception.getMessage());
        return ResponseEntity.badRequest().body(new AdminErrorResponse(INVALID_REQUEST_MESSAGE));
    }

    @ExceptionHandler(AdminImageGenerationBusyException.class)
    public ResponseEntity<AdminErrorResponse> handleBusy(AdminImageGenerationBusyException exception) {
        log.warn("관리자 이미지 생성 요청이 동시 실행 제한에 도달했습니다.");
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(new AdminErrorResponse(BUSY_MESSAGE));
    }

    @ExceptionHandler(AdminImageGenerationConfigurationException.class)
    public ResponseEntity<AdminErrorResponse> handleConfiguration(AdminImageGenerationConfigurationException exception) {
        log.error("관리자 이미지 생성 설정 오류.", exception);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new AdminErrorResponse(GENERATION_FAILURE_MESSAGE));
    }

    @ExceptionHandler(AdminImageGenerationFailedException.class)
    public ResponseEntity<AdminErrorResponse> handleGenerationFailure(AdminImageGenerationFailedException exception) {
        log.error("관리자 이미지 생성 요청 실패.", exception);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new AdminErrorResponse(GENERATION_FAILURE_MESSAGE));
    }

    @ExceptionHandler(AdminImageStorageConfigurationException.class)
    public ResponseEntity<AdminErrorResponse> handleStorageConfiguration(AdminImageStorageConfigurationException exception) {
        log.error("관리자 이미지 저장 설정 오류.", exception);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new AdminErrorResponse(GENERATION_FAILURE_MESSAGE));
    }

    @ExceptionHandler(AdminImageStorageException.class)
    public ResponseEntity<AdminErrorResponse> handleStorageFailure(AdminImageStorageException exception) {
        log.error("관리자 이미지 저장 요청 실패.", exception);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new AdminErrorResponse(GENERATION_FAILURE_MESSAGE));
    }
}
