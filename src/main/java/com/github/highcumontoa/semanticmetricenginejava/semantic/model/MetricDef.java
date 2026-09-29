package com.github.highcumontoa.semanticmetricenginejava.semantic.model;

/**
 * 指标定义。
 *
 * @param name        指标业务名
 * @param table       来源物理表
 * @param expression  聚合输入字段名；COUNT 可为 null（COUNT(*)）
 * @param aggType     聚合方式
 * @param algorithm   时间算法（NONE/CUMULATIVE/ROLLING/RANK）
 * @param windowSize  ROLLING 窗口大小（含当前时间桶的桶数）；其他算法为 null
 * @param sourceMetric 派生自另一指标的指标名（用于口径调整/重命名）；否则 null
 */
public record MetricDef(String name,
                        String table,
                        String expression,
                        AggType aggType,
                        Algorithm algorithm,
                        Integer windowSize,
                        String sourceMetric) {

    /** 是否为派生指标（引用另一个指标定义）。 */
    public boolean derived() {
        return sourceMetric != null;
    }
}
