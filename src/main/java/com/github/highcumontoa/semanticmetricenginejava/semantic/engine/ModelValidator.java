package com.github.highcumontoa.semanticmetricenginejava.semantic.engine;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.github.highcumontoa.semanticmetricenginejava.semantic.model.AggType;
import com.github.highcumontoa.semanticmetricenginejava.semantic.model.ColumnDef;
import com.github.highcumontoa.semanticmetricenginejava.semantic.model.DataType;
import com.github.highcumontoa.semanticmetricenginejava.semantic.model.DimensionDef;
import com.github.highcumontoa.semanticmetricenginejava.semantic.model.MetricDef;
import com.github.highcumontoa.semanticmetricenginejava.semantic.model.ModelVersion;
import com.github.highcumontoa.semanticmetricenginejava.semantic.model.TableDef;
import com.github.highcumontoa.semanticmetricenginejava.semantic.error.ErrorCode;
import com.github.highcumontoa.semanticmetricenginejava.semantic.error.SemanticException;

/**
 * 模型定义校验。任何不合法都按 {@link ErrorCode} 明确拒绝，绝不静默降级：
 * <ol>
 *   <li>表名/列名/维度名/指标名非空且不重复；</li>
 *   <li>维度引用的表与字段必须存在，字段类型一致，DATE 维度必须带粒度；</li>
 *   <li>指标表达式引用的字段必须存在；SUM/AVG/MIN/MAX 只允许 LONG/DOUBLE；
 *       COUNT(*) 允许 expression 为 null；</li>
 *   <li>ROLLING 必须带正整数 windowSize，其他算法 windowSize 必须为 null；</li>
 *   <li>派生指标 sourceMetric 必须存在、不能携带物理定义、不能成环。</li>
 * </ol>
 */
public class ModelValidator {

    public void validate(ModelVersion model) {
        if (model == null || model.version() == null || model.version().isBlank()) {
            throw new SemanticException(ErrorCode.DEF_DUPLICATE_NAME,
                    "模型版本号缺失，依据: 每个 ModelVersion 必须有非空 version");
        }
        Map<String, TableDef> tables = validateTables(model);
        validateDimensions(model, tables);
        validateMetrics(model, tables);
    }

    private Map<String, TableDef> validateTables(ModelVersion model) {
        Map<String, TableDef> tables = new LinkedHashMap<>();
        for (TableDef table : model.tables()) {
            if (table.name() == null || table.name().isBlank()) {
                throw new SemanticException(ErrorCode.DEF_DUPLICATE_NAME,
                        "存在无名表，依据: 表名必须非空");
            }
            if (tables.put(table.name(), table) != null) {
                throw new SemanticException(ErrorCode.DEF_DUPLICATE_NAME,
                        "表名重复: '" + table.name() + "'");
            }
            Set<String> colNames = new HashSet<>();
            for (ColumnDef col : table.columns()) {
                if (col.name() == null || col.name().isBlank()) {
                    throw new SemanticException(ErrorCode.DEF_DUPLICATE_NAME,
                            "表 '" + table.name() + "' 存在无名字段");
                }
                if (!colNames.add(col.name())) {
                    throw new SemanticException(ErrorCode.DEF_DUPLICATE_NAME,
                            "表 '" + table.name() + "' 字段名重复: '" + col.name() + "'");
                }
            }
        }
        return tables;
    }

    private void validateDimensions(ModelVersion model, Map<String, TableDef> tables) {
        Set<String> seen = new HashSet<>();
        for (DimensionDef d : model.dimensions()) {
            if (d.name() == null || d.name().isBlank()) {
                throw new SemanticException(ErrorCode.DEF_DUPLICATE_NAME,
                        "存在无名维度，依据: 维度名必须非空");
            }
            if (!seen.add(d.name())) {
                throw new SemanticException(ErrorCode.DEF_DUPLICATE_NAME,
                        "维度名重复: '" + d.name() + "'");
            }
            TableDef table = tables.get(d.table());
            if (table == null) {
                throw new SemanticException(ErrorCode.DEF_TABLE_NOT_FOUND,
                        "维度 '" + d.name() + "' 引用了不存在的表: '" + d.table() + "'");
            }
            ColumnDef col = findColumn(table, d.expression());
            if (col == null) {
                throw new SemanticException(ErrorCode.DEF_COLUMN_NOT_FOUND,
                        "维度 '" + d.name() + "' 引用了表 '" + table.name()
                                + "' 中不存在的字段: '" + d.expression() + "'");
            }
            if (col.type() != d.dataType()) {
                throw new SemanticException(ErrorCode.DEF_COLUMN_TYPE_MISMATCH,
                        "维度 '" + d.name() + "' 声明类型 " + d.dataType()
                                + " 与物理字段 '" + col.name() + "' 类型 " + col.type() + " 不一致");
            }
            if (d.dataType() == DataType.DATE) {
                if (d.granularity() == null) {
                    throw new SemanticException(ErrorCode.DEF_GRANULARITY_INVALID,
                            "日期维度 '" + d.name() + "' 缺少分桶粒度(DAY/MONTH/YEAR)");
                }
            } else if (d.granularity() != null) {
                throw new SemanticException(ErrorCode.DEF_GRANULARITY_INVALID,
                        "非日期维度 '" + d.name() + "' 不允许设置粒度: " + d.granularity());
            }
        }
    }

    private void validateMetrics(ModelVersion model, Map<String, TableDef> tables) {
        Map<String, MetricDef> metrics = new HashMap<>();
        for (MetricDef m : model.metrics()) {
            if (m.name() == null || m.name().isBlank()) {
                throw new SemanticException(ErrorCode.DEF_DUPLICATE_NAME,
                        "存在无名指标，依据: 指标名必须非空");
            }
            if (metrics.put(m.name(), m) != null) {
                throw new SemanticException(ErrorCode.DEF_DUPLICATE_NAME,
                        "指标名重复: '" + m.name() + "'");
            }
        }

        for (MetricDef m : model.metrics()) {
            if (m.derived()) {
                validateDerived(m, metrics);
                continue;
            }
            if (m.aggType() == null) {
                throw new SemanticException(ErrorCode.DEF_AGG_TYPE_UNSUPPORTED,
                        "指标 '" + m.name() + "' 缺少聚合方式，"
                                + "依据: 合法值为 SUM/COUNT/COUNT_DISTINCT/AVG/MIN/MAX");
            }
            TableDef table = tables.get(m.table());
            if (table == null) {
                throw new SemanticException(ErrorCode.DEF_TABLE_NOT_FOUND,
                        "指标 '" + m.name() + "' 引用了不存在的表: '" + m.table() + "'");
            }
            validateWindow(m);
            if (m.aggType() == AggType.COUNT && m.expression() == null) {
                continue; // COUNT(*)
            }
            ColumnDef col = findColumn(table, m.expression());
            if (col == null) {
                throw new SemanticException(ErrorCode.DEF_COLUMN_NOT_FOUND,
                        "指标 '" + m.name() + "' 引用了表 '" + table.name()
                                + "' 中不存在的字段: '" + m.expression() + "'");
            }
            boolean numericAgg = m.aggType() == AggType.SUM || m.aggType() == AggType.AVG
                    || m.aggType() == AggType.MIN || m.aggType() == AggType.MAX;
            if (numericAgg && col.type() != DataType.LONG && col.type() != DataType.DOUBLE) {
                throw new SemanticException(ErrorCode.DEF_COLUMN_TYPE_MISMATCH,
                        "指标 '" + m.name() + "' 的 " + m.aggType()
                                + " 只能作用于 LONG/DOUBLE 字段，字段 '" + col.name()
                                + "' 实际为 " + col.type());
            }
        }
    }

    private void validateDerived(MetricDef m, Map<String, MetricDef> metrics) {
        if (!metrics.containsKey(m.sourceMetric())) {
            throw new SemanticException(ErrorCode.DEF_METRIC_SOURCE_NOT_FOUND,
                    "派生指标 '" + m.name() + "' 的来源指标不存在: '" + m.sourceMetric() + "'");
        }
        if (m.table() != null || m.expression() != null || m.aggType() != null
                || m.algorithm() != null || m.windowSize() != null) {
            throw new SemanticException(ErrorCode.DEF_AGG_TYPE_UNSUPPORTED,
                    "派生指标 '" + m.name() + "' 只能声明 name/sourceMetric，"
                            + "不能再携带表/字段/聚合方式等物理定义，依据: 防止口径二义");
        }
        // 环检测：沿 sourceMetric 链走，重复访问即成环
        Set<String> path = new HashSet<>();
        String cur = m.name();
        while (cur != null) {
            if (!path.add(cur)) {
                throw new SemanticException(ErrorCode.DEF_CIRCULAR_REFERENCE,
                        "指标派生链存在循环定义: " + path + " -> " + cur
                                + "，依据: 沿 sourceMetric 追踪时重复访问同名指标");
            }
            MetricDef next = metrics.get(cur);
            cur = next != null && next.derived() ? next.sourceMetric() : null;
        }
    }

    private void validateWindow(MetricDef m) {
        if (m.algorithm() == null) {
            throw new SemanticException(ErrorCode.DEF_AGG_TYPE_UNSUPPORTED,
                    "指标 '" + m.name() + "' 缺少时间算法，依据: 合法值为 NONE/CUMULATIVE/ROLLING/RANK");
        }
        if (m.algorithm().name().equals("ROLLING")) {
            if (m.windowSize() == null || m.windowSize() <= 0) {
                throw new SemanticException(ErrorCode.DEF_ROLLING_WINDOW_INVALID,
                        "指标 '" + m.name() + "' 的 ROLLING 窗口必须为正整数，实际: "
                                + m.windowSize());
            }
        } else if (m.windowSize() != null) {
            throw new SemanticException(ErrorCode.DEF_ROLLING_WINDOW_INVALID,
                    "指标 '" + m.name() + "' 使用 " + m.algorithm()
                            + "，windowSize 必须为 null，实际: " + m.windowSize());
        }
    }

    private ColumnDef findColumn(TableDef table, String column) {
        if (column == null) {
            return null;
        }
        return table.columns().stream()
                .filter(c -> c.name().equals(column))
                .findFirst()
                .orElse(null);
    }
}
