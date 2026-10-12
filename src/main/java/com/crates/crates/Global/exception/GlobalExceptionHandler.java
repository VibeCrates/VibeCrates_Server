package com.crates.crates.Global.exception;

import com.crates.crates.DTO.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Arrays;
import java.util.Objects;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException e)
    {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiResponse<>(false, null, e.getMessage()));
    }

    /** AI 서버로 가는 요청이 밀렸다(EmbeddingBatcher). 붐비는 것이지 고장이 아니므로 스택은 남기지 않는다. */
    @ExceptionHandler(AiServerBusyException.class)
    public ResponseEntity<ApiResponse<Void>> handleAiServerBusyException(AiServerBusyException e)
    {
        log.warn("[AI SERVER BUSY] {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ApiResponse<>(false, null, e.getMessage()));
    }

    @ExceptionHandler(AiServerException.class)
    public ResponseEntity<ApiResponse<Void>> handleAiServerException(AiServerException e)
    {
        log.error("[AI SERVER ERROR] {}", e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new ApiResponse<>(false, null, e.getMessage()));
    }

    /**
     * {@code @Valid}가 붙은 요청 본문의 검증 실패. 잡지 않으면 아래 Exception 핸들러로 떨어져
     * 사용자 입력 문제가 500으로 나간다.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleBodyValidation(MethodArgumentNotValidException e)
    {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse("요청 값이 올바르지 않습니다.");

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiResponse<>(false, null, message));
    }

    /** {@code @Validated} 컨트롤러의 쿼리 파라미터 검증 실패. 위와 같은 이유로 400으로 내린다. */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleParameterValidation(ConstraintViolationException e)
    {
        String message = e.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .findFirst()
                .orElse("요청 값이 올바르지 않습니다.");

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiResponse<>(false, null, message));
    }

    /**
     * 쿼리 파라미터를 선언한 타입으로 바꾸지 못했을 때. {@code ?filter=FOO}, {@code ?page=abc} 같은 경우다.
     *
     * <p>잡지 않으면 아래 Exception 핸들러로 떨어져 500이 나간다. 위의 두 핸들러는 값을 <b>변환한 뒤</b>
     * 검증하는 단계라 여기까지 오지 못한다 — 변환 자체가 실패하면 검증이 시작되지도 않는다.</p>
     *
     * <p>enum이면 가능한 값을 함께 알려준다. 프론트가 오타를 냈을 때 "형식이 올바르지 않습니다"만
     * 받으면 어떤 값을 보내야 하는지 알 수 없다.</p>
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException e)
    {
        Class<?> requiredType = e.getRequiredType();
        String message = requiredType != null && requiredType.isEnum()
                ? "%s 값이 올바르지 않습니다. 가능한 값: %s"
                        .formatted(e.getName(), Arrays.toString(requiredType.getEnumConstants()))
                : "%s 값의 형식이 올바르지 않습니다.".formatted(e.getName());

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiResponse<>(false, null, message));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception e, HttpServletRequest request)
    {
        log.error("[SYSTEM ERROR] URI: {} | {}",
                request.getRequestURI(),
                e.getMessage(),
                e);  // 세 번째 인자 Throwable → SLF4J가 전체 스택 자동 출력
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiResponse<>(false, null, "서버 내부 오류가 발생했습니다."));
    }

}
