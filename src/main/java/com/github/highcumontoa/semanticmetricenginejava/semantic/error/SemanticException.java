package com.github.highcumontoa.semanticmetricenginejava.semantic.error;

/**
 * 语义层统一受检异常：携带可区分的原因码与判定依据描述。
 */
public class SemanticException extends RuntimeException {

    private final ErrorCode code;

    public SemanticException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode getCode() {
        return code;
    }
}
