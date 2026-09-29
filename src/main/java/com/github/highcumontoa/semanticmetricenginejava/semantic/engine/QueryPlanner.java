package com.github.highcumontoa.semanticmetricenginejava.semantic.engine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import com.github.highcumontoa.semanticmetricenginejava.semantic.model.Algorithm;
import com.github.highcumontoa.semanticmetricenginejava.semantic.model.DataType;
import com.github.highcumontoa.semanticmetricenginejava.semantic.model.DimensionDef;
import com.github.highcumontoa.semanticmetricenginejava.semantic.model.MetricDef;
import com.github.highcumontoa.semanticmetricenginejava.semantic.model.ModelVersion;
import com.github.highcumontoa.semanticmetricenginejava.semantic.query.QueryRequest;
import com.github.highcumontoa.semanticmetricenginejava.semantic.error.ErrorCode;
import com.github.highcumontoa.semanticmetricenginejava.semantic.error.SemanticException;

/**
 * 查询计划：校验请求并解析出可执行计划。非法请求按原因码拒绝：
 * <ul>
 *   <li>Q_EMPTY_METRICS：指标列表为空；</li>
 *   <li>Q_METRIC_NOT_FOUND / Q_DIMENSION_NOT_FOUND：引用了该版本不存在的名字（重命名/删除不静默兼容）；</li>
 *   <li>Q_MULTIPLE_TABLES：指标/维度分属不同物理表；</li>
 *   <li>Q_ALGORITHM_MIX_CONFLICT：RANK 与 CUMULATIVE/ROLLING 混用；</li>
 *   <li>Q_ALGORITHM_REQUIRES_DATE_DIM：时间算法要求恰好一个 DATE 维度（CUMULATIVE/ROLLING/RANK 的分区排名不需要）。</li>
 * </ul>
 */
public class QueryPlanner {

    public Plan plan(QueryRequest request, ModelVersion model) {
        List<String> metricNames = request.metrics() == null ? List.of() : request.metrics();
        List<String> dimNames = request.dimensions() == null ? List.of() : request.dimensions();
        if (metricNames.isEmpty()) {
            throw new SemanticException(ErrorCode.Q_EMPTY_METRICS,
                    "查询必须至少指定一个指标，依据: 空指标列表无法构成聚合查询");
        }

        Map<String, MetricDef> metricByName = new HashMap<>();
        model.metrics().forEach(m -> metricByName.put(m.name(), m));
        Map<String, DimensionDef> dimByName = new HashMap<>();
        model.dimensions().forEach(d -> dimByName.put(d.name(), d));

        // 保持请求顺序、去重；解析派生链到根物理指标
        List<ResolvedMetric> metrics = new ArrayList<>();
        LinkedHashSet<String> seenMetrics = new LinkedHashSet<>();
        for (String name : metricNames) {
            if (!seenMetrics.add(name)) {
                continue;
            }
            MetricDef m = metricByName.get(name);
            if (m == null) {
                throw new SemanticException(ErrorCode.Q_METRIC_NOT_FOUND,
                        "指标 '" + name + "' 在版本 " + model.version()
                                + " 中不存在（重命名/删除不会静默兼容，请改用该版本存在的指标名）");
            }
            metrics.add(new ResolvedMetric(name, resolveRoot(m, metricByName)));
        }

        List<DimensionDef> dimensions = new ArrayList<>();
        LinkedHashSet<String> seenDims = new LinkedHashSet<>();
        for (String name : dimNames) {
            if (!seenDims.add(name)) {
                continue;
            }
            DimensionDef d = dimByName.get(name);
            if (d == null) {
                throw new SemanticException(ErrorCode.Q_DIMENSION_NOT_FOUND,
                        "维度 '" + name + "' 在版本 " + model.version() + " 中不存在");
            }
            dimensions.add(d);
        }

        String tableName = requireSingleTable(metrics, dimensions, model.version());
        DimensionDef timeDim = validateAlgorithms(metrics, dimensions, model.version());

        return new Plan(model, tableName, metrics, dimensions, timeDim);
    }

    /** 沿 sourceMetric 解析到根物理指标；模型注册时已保证无环、来源存在。 */
    private MetricDef resolveRoot(MetricDef m, Map<String, MetricDef> metricByName) {
        MetricDef cur = m;
        while (cur.derived()) {
            cur = metricByName.get(cur.sourceMetric());
        }
        return cur;
    }

    private String requireSingleTable(List<ResolvedMetric> metrics, List<DimensionDef> dimensions,
                                      String version) {
        String table = null;
        for (ResolvedMetric m : metrics) {
            MetricDef root = m.root();
            table = checkTable(table, root.table(), "指标 " + m.outputName(), version);
        }
        for (DimensionDef d : dimensions) {
            table = checkTable(table, d.table(), "维度 " + d.name(), version);
        }
        return table;
    }

    private String checkTable(String current, String candidate, String who, String version) {
        if (current == null) {
            return candidate;
        }
        if (!current.equals(candidate)) {
            throw new SemanticException(ErrorCode.Q_MULTIPLE_TABLES,
                    who + " 属于表 '" + candidate + "'，与查询中已引用的表 '" + current
                            + "' 不同（版本 " + version + " 仅支持单表指标查询）");
        }
        return current;
    }

    private DimensionDef validateAlgorithms(List<ResolvedMetric> metrics,
                                            List<DimensionDef> dimensions,
                                            String version) {
        boolean hasRank = false;
        boolean hasWindow = false; // CUMULATIVE / ROLLING
        for (ResolvedMetric m : metrics) {
            hasRank |= m.root().algorithm() == Algorithm.RANK;
            hasWindow |= m.root().algorithm() == Algorithm.CUMULATIVE
                    || m.root().algorithm() == Algorithm.ROLLING;
        }
        if (hasRank && hasWindow) {
            throw new SemanticException(ErrorCode.Q_ALGORITHM_MIX_CONFLICT,
                    "同一条查询不能混用 RANK 与 CUMULATIVE/ROLLING，"
                            + "依据: 排名与时间窗口的排序口径互斥（版本 " + version + "）");
        }

        List<DimensionDef> dateDims = dimensions.stream()
                .filter(d -> d.dataType() == DataType.DATE)
                .toList();
        if (hasWindow) {
            if (dateDims.size() != 1) {
                throw new SemanticException(ErrorCode.Q_ALGORITHM_REQUIRES_DATE_DIM,
                        "CUMULATIVE/ROLLING 要求恰好指定一个日期维度，实际: "
                                + dateDims.size() + " 个（"
                                + dateDims.stream().map(DimensionDef::name).toList()
                                + "，版本 " + version + "）");
            }
            return dateDims.get(0);
        }
        if (hasRank && dateDims.size() > 1) {
            throw new SemanticException(ErrorCode.Q_ALGORITHM_REQUIRES_DATE_DIM,
                    "RANK 查询最多指定一个日期维度（作为分区内的排名轴），实际: "
                            + dateDims.size() + " 个（"
                            + dateDims.stream().map(DimensionDef::name).toList()
                            + "，版本 " + version + "）");
        }
        // RANK 有 0 或 1 个日期维度：有则按“非日期维度分区、跨日期桶”排名；无则全表一个分区
        return hasRank && dateDims.size() == 1 ? dateDims.get(0) : null;
    }

    /**
     * 请求指标解析结果。
     *
     * @param outputName 输出列名（即请求中的名字；派生指标使用其别名而非根指标名）
     * @param root       解析派生链后的根物理指标
     */
    public record ResolvedMetric(String outputName, MetricDef root) {
    }

    /**
     * 可执行查询计划。
     */
    public record Plan(ModelVersion model,
                       String tableName,
                       List<ResolvedMetric> metrics,
                       List<DimensionDef> dimensions,
                       DimensionDef timeDimension) {
    }
}
