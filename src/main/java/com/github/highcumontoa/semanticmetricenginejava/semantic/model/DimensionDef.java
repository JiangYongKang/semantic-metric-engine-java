package com.github.highcumontoa.semanticmetricenginejava.semantic.model;

/**
 * 维度定义。
 *
 * @param name        维度业务名（查询时引用）
 * @param table       来源物理表
 * @param expression  来源字段名（本表仅支持直接字段引用）
 * @param dataType    维度输出类型
 * @param granularity DATE 维度的分桶粒度；非 DATE 为 null
 */
public record DimensionDef(String name,
                           String table,
                           String expression,
                           DataType dataType,
                           DateGranularity granularity) {
}
