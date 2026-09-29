package com.github.highcumontoa.semanticmetricenginejava.registry;

import com.github.highcumontoa.semanticmetricenginejava.model.AggregationType;
import com.github.highcumontoa.semanticmetricenginejava.model.ColumnDef;
import com.github.highcumontoa.semanticmetricenginejava.model.DataType;
import com.github.highcumontoa.semanticmetricenginejava.model.DimensionDef;
import com.github.highcumontoa.semanticmetricenginejava.model.ErrorCode;
import com.github.highcumontoa.semanticmetricenginejava.model.MetricDef;
import com.github.highcumontoa.semanticmetricenginejava.model.MetricExpression;
import com.github.highcumontoa.semanticmetricenginejava.model.SemanticException;
import com.github.highcumontoa.semanticmetricenginejava.model.TableDef;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 定义期校验：列存在性、聚合合法性、派生表达式引用合法性、循环定义检测。
 *
 * <p>任何一项不通过都以 {@link SemanticException} 明确拒绝，错误码区分原因，
 * 不允许带着非法定义进入版本快照。</p>
 */
public class DefinitionValidator {

    private final LocalTableRegistry tables;

    public DefinitionValidator(LocalTableRegistry tables) {
        this.tables = tables;
    }

    public void validate(List<DimensionDef> dimensions, List<MetricDef> metrics) {
        if (dimensions == null || metrics == null) {
            throw new SemanticException(ErrorCode.INVALID_REQUEST, "dimensions 与 metrics 列表不能为 null");
        }

        // 1. 维度校验 + 重名检测
        Set<String> usedNames = new HashSet<>();
        for (DimensionDef dim : dimensions) {
            if (dim.name() == null || dim.name().isBlank()) {
                throw new SemanticException(ErrorCode.INVALID_REQUEST, "维度名称不能为空");
            }
            if (!usedNames.add(dim.name())) {
                throw new SemanticException(ErrorCode.DUPLICATE_DEFINITION,
                        "维度名称重复: " + dim.name());
            }
            TableDef table = requireTable(dim.table());
            requireColumn(table, dim.column());
        }

        // 2. 指标自身校验 + 重名检测（指标之间、指标与维度之间均不允许同名）
        for (MetricDef metric : metrics) {
            if (metric.name() == null || metric.name().isBlank()) {
                throw new SemanticException(ErrorCode.INVALID_REQUEST, "指标名称不能为空");
            }
            if (!usedNames.add(metric.name())) {
                throw new SemanticException(ErrorCode.DUPLICATE_DEFINITION,
                        "指标/维度名称重复: " + metric.name());
            }
            if (metric.isDerived()) {
                if (metric.aggregation() != null) {
                    throw new SemanticException(ErrorCode.INVALID_REQUEST,
                            "派生指标不能同时声明聚合方式: " + metric.name());
                }
                validateDerivedShape(metric);
            } else {
                if (metric.aggregation() == null) {
                    throw new SemanticException(ErrorCode.INVALID_REQUEST,
                            "基础指标必须声明聚合方式: " + metric.name());
                }
                validateBaseMetric(metric);
            }
        }

        // 3. 派生表达式引用合法性（必须引用本次定义集合内、同表的指标）
        Map<String, MetricDef> byName = new java.util.HashMap<>();
        metrics.forEach(m -> byName.put(m.name(), m));
        for (MetricDef metric : metrics) {
            if (!metric.isDerived()) {
                continue;
            }
            for (String ref : referencedMetrics(metric.expression())) {
                MetricDef target = byName.get(ref);
                if (target == null) {
                    throw new SemanticException(ErrorCode.INVALID_EXPRESSION_REFERENCE,
                            "派生指标 " + metric.name() + " 引用了未定义的指标: " + ref);
                }
                if (!target.table().equals(metric.table())) {
                    throw new SemanticException(ErrorCode.INVALID_EXPRESSION_REFERENCE,
                            "派生指标 " + metric.name() + " 跨表引用指标 " + ref
                                    + "（指标计算不支持跨表）");
                }
                // 表达式为数值算术：只允许引用 SUM/AVG/COUNT 等数值结果指标
                if (!isNumericResult(target)) {
                    throw new SemanticException(ErrorCode.INVALID_AGGREGATION,
                            "派生指标 " + metric.name() + " 引用了非数值结果指标 " + ref
                                    + "（" + describeResult(target) + "），表达式仅支持数值运算");
                }
            }
        }

        // 4. 循环定义检测（含自引用）
        detectCycles(byName);
    }

    private TableDef requireTable(String tableName) {
        if (tableName == null || tableName.isBlank()) {
            throw new SemanticException(ErrorCode.INVALID_REQUEST, "表名不能为空");
        }
        return tables.get(tableName)
                .orElseThrow(() -> new SemanticException(ErrorCode.TABLE_NOT_FOUND,
                        "引用的本地表不存在: " + tableName))
                .def();
    }

    private ColumnDef requireColumn(TableDef table, String columnName) {
        if (columnName == null || columnName.isBlank()) {
            throw new SemanticException(ErrorCode.INVALID_REQUEST,
                    "表 " + table.name() + " 的列名不能为空");
        }
        return table.column(columnName)
                .orElseThrow(() -> new SemanticException(ErrorCode.COLUMN_NOT_FOUND,
                        "表 " + table.name() + " 中不存在列: " + columnName));
    }

    private void validateBaseMetric(MetricDef metric) {
        TableDef table = requireTable(metric.table());
        AggregationType agg = metric.aggregation();

        if (agg == AggregationType.COUNT) {
            // COUNT 允许 sourceColumn 为空表示 COUNT(*)；给了列则列必须存在
            if (metric.sourceColumn() != null && !metric.sourceColumn().isBlank()) {
                requireColumn(table, metric.sourceColumn());
            }
            return;
        }

        if (metric.sourceColumn() == null || metric.sourceColumn().isBlank()) {
            throw new SemanticException(ErrorCode.INVALID_AGGREGATION,
                    agg + " 聚合必须指定 sourceColumn，指标: " + metric.name());
        }
        ColumnDef column = requireColumn(table, metric.sourceColumn());
        DataType type = column.type();

        boolean legal = switch (agg) {
            case SUM, AVG -> type.isNumeric();
            case COUNT_DISTINCT -> true;
            case MIN, MAX -> true;
            case COUNT -> true;
        };
        if (!legal) {
            throw new SemanticException(ErrorCode.INVALID_AGGREGATION,
                    "聚合方式 " + agg + " 不能用于 " + type + " 类型列 "
                            + table.name() + "." + column.name()
                            + "（指标 " + metric.name() + "）");
        }
    }

    private void validateDerivedShape(MetricDef metric) {
        if (metric.table() == null || metric.table().isBlank()) {
            throw new SemanticException(ErrorCode.INVALID_REQUEST,
                    "派生指标必须声明所属表: " + metric.name());
        }
        requireTable(metric.table());
        if (referencedMetrics(metric.expression()).isEmpty()) {
            throw new SemanticException(ErrorCode.INVALID_EXPRESSION_REFERENCE,
                    "派生指标表达式必须至少引用一个指标: " + metric.name());
        }
    }

    /** 指标结果是否为数值（派生表达式只接受数值输入）。 */
    private boolean isNumericResult(MetricDef target) {
        if (target.isDerived()) {
            return true;
        }
        AggregationType agg = target.aggregation();
        if (agg == AggregationType.COUNT || agg == AggregationType.COUNT_DISTINCT
                || agg == AggregationType.SUM || agg == AggregationType.AVG) {
            return true;
        }
        // MIN/MAX 结果类型跟随列类型
        if (target.sourceColumn() == null) {
            return false;
        }
        return tables.get(target.table())
                .flatMap(t -> t.def().column(target.sourceColumn()))
                .map(c -> c.type().isNumeric())
                .orElse(false);
    }

    private String describeResult(MetricDef target) {
        return target.isDerived() ? "派生指标" : ("基础指标 " + target.aggregation());
    }

    /** 收集表达式中引用的全部指标名（含嵌套）。 */
    public static List<String> referencedMetrics(MetricExpression expression) {
        List<String> refs = new ArrayList<>();
        collect(expression, refs);
        return refs;
    }

    private static void collect(MetricExpression expression, List<String> out) {
        switch (expression) {
            case null -> throw new SemanticException(ErrorCode.INVALID_REQUEST, "表达式不能为 null");
            case MetricExpression.ExprRef ref -> out.add(ref.metricName());
            case MetricExpression.ExprLiteral ignored -> {
            }
            case MetricExpression.ExprBinary binary -> {
                collect(binary.left(), out);
                collect(binary.right(), out);
            }
        }
    }

    /**
     * 派生指标引用图循环检测（DFS，三色思想，白/灰/黑用两个集合表达）。
     * 检测到环（含自引用）时抛出 CIRCULAR_DEFINITION，消息给出成环节点链。
     */
    public static void detectCycles(Map<String, MetricDef> metricsByName) {
        Set<String> visited = new HashSet<>();
        Set<String> onStack = new HashSet<>();
        Deque<String> stack = new ArrayDeque<>();
        for (String name : metricsByName.keySet().stream().sorted().toList()) {
            if (!visited.contains(name)) {
                dfs(name, metricsByName, visited, onStack, stack);
            }
        }
    }

    private static void dfs(String name, Map<String, MetricDef> byName,
                            Set<String> visited, Set<String> onStack, Deque<String> stack) {
        visited.add(name);
        onStack.add(name);
        stack.push(name);

        MetricDef metric = byName.get(name);
        if (metric != null && metric.isDerived()) {
            // 固定遍历顺序，保证错误消息稳定
            for (String ref : referencedMetrics(metric.expression()).stream().distinct().sorted().toList()) {
                if (!byName.containsKey(ref)) {
                    // 引用缺失已在前面以 INVALID_EXPRESSION_REFERENCE 拒绝
                    continue;
                }
                if (onStack.contains(ref)) {
                    List<String> chain = new ArrayList<>();
                    // 从栈顶向下找到 ref，构成环路径
                    for (String node : stack) {
                        chain.add(node);
                        if (node.equals(ref)) {
                            break;
                        }
                    }
                    java.util.Collections.reverse(chain);
                    throw new SemanticException(ErrorCode.CIRCULAR_DEFINITION,
                            "检测到指标循环定义: " + String.join(" -> ", chain) + " -> " + ref
                                    + "（" + ref + " 经依赖链回到自身）");
                }
                if (!visited.contains(ref)) {
                    dfs(ref, byName, visited, onStack, stack);
                }
            }
        }

        onStack.remove(name);
        stack.pop();
    }
}
