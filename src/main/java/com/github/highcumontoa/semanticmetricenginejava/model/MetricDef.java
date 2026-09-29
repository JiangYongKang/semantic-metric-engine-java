package com.github.highcumontoa.semanticmetricenginejava.model;

/**
 * 指标定义。
 *
 * <p>基础指标：{@code aggregation} 非空，{@code expression} 为空；
 * {@code sourceColumn} 为被聚合列，COUNT 聚合允许列为空（表示 COUNT(*)）。</p>
 *
 * <p>派生指标：{@code expression} 非空，只能引用同表内的其它指标，
 * 禁止自引用与循环引用（发布期检测并拒绝）。</p>
 *
 * <p>{@code renameOf}：声明本指标由父版本中同名口径的旧指标重命名而来，
 * 用于版本血缘说明与校验，不参与计算。</p>
 */
public record MetricDef(
        String name,
        String table,
        AggregationType aggregation,
        String sourceColumn,
        MetricExpression expression,
        String renameOf) {

    public boolean isDerived() {
        return expression != null;
    }
}
