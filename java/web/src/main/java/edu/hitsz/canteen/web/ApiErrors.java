package edu.hitsz.canteen.web;

import edu.hitsz.canteen.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(IdempotencyConflictException.class)
    ResponseEntity<?> conflict(IdempotencyConflictException error) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", error.getMessage()));
    }
    @ExceptionHandler(ForbiddenException.class)
    ResponseEntity<?> forbidden(ForbiddenException error) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", error.getMessage()));
    }
    @ExceptionHandler(BusinessException.class)
    ResponseEntity<?> badRequest(BusinessException error) {
        return ResponseEntity.badRequest().body(Map.of("error", error.getMessage()));
    }
    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class})
    ResponseEntity<?> invalid(Exception error) {
        return ResponseEntity.badRequest().body(Map.of("error", "请求字段格式错误"));
    }
}
