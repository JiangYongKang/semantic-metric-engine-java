package com.github.highcumontoa.semanticmetricenginejava.web;

import com.github.highcumontoa.semanticmetricenginejava.model.ErrorCode;
import com.github.highcumontoa.semanticmetricenginejava.model.SemanticException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 所有语义错误以统一 JSON 返回：{"rejected":true,"errorCode":...,"message":...}。
 * “不存在”类为 404，其余明确拒绝为 400；绝不会把错误静默成空结果。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(SemanticException.class)
    public ResponseEntity<Map<String, Object>> handleSemantic(SemanticException ex) {
        return ResponseEntity.status(statusFor(ex.getCode())).body(body(ex.getCode().name(), ex.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest()
                .body(body(ErrorCode.INVALID_REQUEST.name(), "请求体不是合法 JSON: " + ex.getMostSpecificCause().getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleOther(Exception ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(body("INTERNAL_ERROR", ex.getClass().getSimpleName() + ": " + ex.getMessage()));
    }

    private static Map<String, Object> body(String code, String message) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("rejected", true);
        map.put("errorCode", code);
        map.put("message", message);
        return map;
    }

    static HttpStatus statusFor(ErrorCode code) {
        return switch (code) {
            case VERSION_NOT_FOUND, METRIC_NOT_FOUND, DIMENSION_NOT_FOUND,
                 TABLE_NOT_FOUND, VERSION_PARENT_NOT_FOUND -> HttpStatus.NOT_FOUND;
            default -> HttpStatus.BAD_REQUEST;
        };
    }
}
