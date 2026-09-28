package com.pickone.global.error;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 컨트롤러 이후 단계에서 발생한 예외를 { code, message } 형식으로 변환한다.
 * Security 필터에서 나는 401/403 은 여기까지 오지 않으므로 별도 핸들러(global.security.handler)가 같은 형식으로 응답한다.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

	@ExceptionHandler(BusinessException.class)
	public ResponseEntity<ErrorResponse> handleBusiness(BusinessException e) {
		ErrorCode code = e.getErrorCode();
		return ResponseEntity.status(code.getStatus()).body(new ErrorResponse(code.name(), e.getMessage(), null));
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
		List<ErrorResponse.FieldError> errors = e.getBindingResult().getFieldErrors().stream()
				.map(fe -> new ErrorResponse.FieldError(fe.getField(), fe.getDefaultMessage()))
				.toList();
		return ResponseEntity.status(ErrorCode.VALIDATION_ERROR.getStatus())
				.body(ErrorResponse.of(ErrorCode.VALIDATION_ERROR, errors));
	}

	/** JSON 파싱 실패, 본문 누락 등 */
	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ErrorResponse> handleNotReadable(HttpMessageNotReadableException e) {
		return ResponseEntity.status(ErrorCode.VALIDATION_ERROR.getStatus())
				.body(ErrorResponse.of(ErrorCode.VALIDATION_ERROR));
	}

	/**
	 * DB 유니크 제약 위반. 선검사(existsBy*)를 동시에 통과한 요청이 커밋 시점에 부딪히는 경합을 409 로 돌려준다.
	 * 위반된 제약 이름을 ErrorCode.constraintName 과 대조하고, 모르는 제약이면 500 으로 처리한다.
	 */
	@ExceptionHandler(DataIntegrityViolationException.class)
	public ResponseEntity<ErrorResponse> handleDataIntegrity(DataIntegrityViolationException e) {
		String constraintName = ConstraintViolations.constraintName(e);
		return ErrorCode.fromConstraintName(constraintName)
				.map(code -> ResponseEntity.status(code.getStatus()).body(ErrorResponse.of(code)))
				.orElseGet(() -> {
					log.error("매핑되지 않은 제약 위반: constraint={}", constraintName, e);
					return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.getStatus())
							.body(ErrorResponse.of(ErrorCode.INTERNAL_ERROR));
				});
	}

	@ExceptionHandler(NoResourceFoundException.class)
	public ResponseEntity<ErrorResponse> handleNoResource(NoResourceFoundException e) {
		return ResponseEntity.status(404)
				.body(new ErrorResponse("NOT_FOUND", "요청한 경로를 찾을 수 없습니다.", null));
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
		log.error("처리되지 않은 예외", e);
		return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.getStatus())
				.body(ErrorResponse.of(ErrorCode.INTERNAL_ERROR));
	}

}
