package com.github.highcumontoa.semanticmetricenginejava.model;

import java.util.List;
import java.util.Map;

/**
 * 指标查询请求。
 *
 * <p>稳定性约定：{@code filters} 只限制“输出哪些时间期”，时间算法始终基于全量行计算，
 * 因此整段查询与分批查询（不同 filter）结果合并后完全一致。</p>
 *
 * @param version        语义定义版本；null 表示最新版本
 * @param metrics        要查询的指标（先基础后派生均可，输出列按给定顺序）
 * @param dimensions     分组维度，空列表表示全局单行聚合
 * @param timeDimension  时间算法使用的维度名；timeFunction 非 NONE 时必填
 * @param timeFunction   NONE / CUMULATIVE / SLIDING / RANK
 * @param windowSize     SLIDING 时的窗口大小（含当前期，正整数）
 * @param rankOrder      RANK 时的方向，默认 DESC
 * @param filters        等值过滤（维度名 -> 值），仅作用于输出行，不影响时间算法输入
 */
public record MetricQuery(
        String version,
        List<String> metrics,
        List<String> dimensions,
        String timeDimension,
        TimeFunction timeFunction,
        Integer windowSize,
        RankOrder rankOrder,
        Map<String, String> filters) {

    /** 便捷构造：无时间算法的分组聚合查询，使用最新版本。 */
    public static MetricQuery aggregate(String version, List<String> metrics, List<String> dimensions) {
        return new MetricQuery(version, List.copyOf(metrics), List.copyOf(dimensions),
                null, TimeFunction.NONE, null, null, Map.of());
    }
}
