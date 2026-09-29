package com.github.highcumontoa.semanticmetricenginejava.semantic.api;

/**
 * 统一错误响应体：原因码 + 判定依据描述。
 */
public record ErrorResponse(String code, String message) {
}
