package com.github.highcumontoa.semanticmetricenginejava.semantic.query;

import java.util.List;
import java.util.Map;

/**
 * 查询结果。
 *
 * @param version    实际使用的模型版本
 * @param columns    输出列名（维度列在前、指标列在后，顺序稳定）
 * @param rows       结果行（列名 -> 值；值为 Long/Double/String/LocalDate/null）
 */
public record QueryResult(String version,
                          List<String> columns,
                          List<Map<String, Object>> rows) {
}
