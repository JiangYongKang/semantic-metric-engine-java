package com.github.highcumontoa.semanticmetricenginejava.semantic.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.github.highcumontoa.semanticmetricenginejava.semantic.error.SemanticException;

/**
 * 语义层拒绝原因 -> HTTP 400（原因码 + 判定依据）。绝不吞错、不静默返回结果。
 */
@RestControllerAdvice
public class SemanticExceptionHandler {

    @ExceptionHandler(SemanticException.class)
    public ResponseEntity<ErrorResponse> handle(SemanticException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse(ex.getCode().name(), ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("BAD_REQUEST", ex.getMessage()));
    }
}
