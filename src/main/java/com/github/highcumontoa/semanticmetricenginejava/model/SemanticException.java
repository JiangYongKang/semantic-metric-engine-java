package com.github.highcumontoa.semanticmetricenginejava.model;

/** 语义层明确拒绝异常：携带可区分的错误码与判定依据。 */
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
