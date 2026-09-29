package com.github.highcumontoa.semanticmetricenginejava.semantic.query;

import java.util.List;

/**
 * 指标查询请求。
 *
 * @param metrics     指标名列表（不能为空）
 * @param dimensions  分组维度名列表（可为空表示全局聚合）
 * @param version     指定模型版本；null 表示最新版本
 */
public record QueryRequest(List<String> metrics,
                           List<String> dimensions,
                           String version) {
}
