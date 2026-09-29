package com.github.highcumontoa.semanticmetricenginejava.engine;

import com.github.highcumontoa.semanticmetricenginejava.model.AggregationType;
import com.github.highcumontoa.semanticmetricenginejava.model.ColumnDef;
import com.github.highcumontoa.semanticmetricenginejava.model.DataType;
import com.github.highcumontoa.semanticmetricenginejava.model.DimensionDef;
import com.github.highcumontoa.semanticmetricenginejava.model.ErrorCode;
import com.github.highcumontoa.semanticmetricenginejava.model.LocalTable;
import com.github.highcumontoa.semanticmetricenginejava.model.MetricDef;
import com.github.highcumontoa.semanticmetricenginejava.model.MetricExpression;
import com.github.highcumontoa.semanticmetricenginejava.model.MetricQuery;
import com.github.highcumontoa.semanticmetricenginejava.model.QueryResult;
import com.github.highcumontoa.semanticmetricenginejava.model.RankOrder;
import com.github.highcumontoa.semanticmetricenginejava.model.SemanticException;
import com.github.highcumontoa.semanticmetricenginejava.model.SemanticVersion;
import com.github.highcumontoa.semanticmetricenginejava.model.TableDef;
import com.github.highcumontoa.semanticmetricenginejava.model.TimeFunction;
import com.github.highcumontoa.semanticmetricenginejava.registry.DefinitionValidator;
import com.github.highcumontoa.semanticmetricenginejava.registry.LocalTableRegistry;
import com.github.highcumontoa.semanticmetricenginejava.registry.SemanticRegistry;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * 查询引擎。
 *
 * <p>处理流程与稳定性约定：</p>
 * <ol>
 *   <li>按版本号取不可变定义快照（null=最新），任一引用非法都以区分化错误码拒绝；</li>
 *   <li>单次顺序扫描本地表完成分组，组内行保持表内行序，保证求和等运算逐字节可重复；</li>
 *   <li>累计/滑动窗口始终基于<b>全量行</b>在“分区（非时间维度）+ 时间期”上重算，
 *       filters 只筛选输出行，因此整段查询与分批查询合并后结果一致；</li>
 *   <li>排名采用标准竞赛排名（1,2,2,4），null 值一律排末尾并列，行序另有确定性兜底排序。</li>
 * </ol>
 */
@Component
public class QueryEngine {

    private final SemanticRegistry semanticRegistry;
    private final LocalTableRegistry tableRegistry;
    private final ExpressionEvaluator expressionEvaluator = new ExpressionEvaluator();

    public QueryEngine(SemanticRegistry semanticRegistry, LocalTableRegistry tableRegistry) {
        this.semanticRegistry = semanticRegistry;
        this.tableRegistry = tableRegistry;
    }

    public QueryResult execute(MetricQuery query) {
        if (query == null) {
            throw new SemanticException(ErrorCode.INVALID_REQUEST, "查询请求体不能为空");
        }
        SemanticVersion version = semanticRegistry.resolve(query.version());

        List<String> metricNames = query.metrics() == null ? List.of() : query.metrics();
        List<String> dimNames = query.dimensions() == null ? List.of() : query.dimensions();
        TimeFunction function = query.timeFunction() == null ? TimeFunction.NONE : query.timeFunction();
        Map<String, String> filters = query.filters() == null ? Map.of() : query.filters();

        // ---- 请求形态校验 ----
        if (metricNames.isEmpty()) {
            throw new SemanticException(ErrorCode.INVALID_REQUEST, "metrics 至少指定一个指标");
        }
        if (new HashSet<>(metricNames).size() != metricNames.size()) {
            throw new SemanticException(ErrorCode.INVALID_REQUEST, "metrics 中存在重复指标: " + metricNames);
        }
        if (new HashSet<>(dimNames).size() != dimNames.size()) {
            throw new SemanticException(ErrorCode.INVALID_REQUEST, "dimensions 中存在重复维度: " + dimNames);
        }

        // ---- 引用存在性校验：指标/维度不存在，明确拒绝 ----
        List<MetricDef> requestedMetrics = new ArrayList<>();
        for (String name : metricNames) {
            MetricDef metric = version.metrics().get(name);
            if (metric == null) {
                throw new SemanticException(ErrorCode.METRIC_NOT_FOUND,
                        "版本 " + version.version() + " 中不存在指标: " + name);
            }
            requestedMetrics.add(metric);
        }
        List<DimensionDef> requestedDims = new ArrayList<>();
        for (String name : dimNames) {
            DimensionDef dim = version.dimensions().get(name);
            if (dim == null) {
                throw new SemanticException(ErrorCode.DIMENSION_NOT_FOUND,
                        "版本 " + version.version() + " 中不存在维度: " + name);
            }
            requestedDims.add(dim);
        }

        // ---- 单表约束：维度/指标必须全部落在同一张本地表 ----
        String tableName = requestedMetrics.get(0).table();
        for (MetricDef m : requestedMetrics) {
            if (!m.table().equals(tableName)) {
                throw new SemanticException(ErrorCode.MULTIPLE_TABLES,
                        "一次查询只能引用同一张表的指标，发现 " + tableName + " 与 " + m.table());
            }
        }
        for (DimensionDef d : requestedDims) {
            if (!d.table().equals(tableName)) {
                throw new SemanticException(ErrorCode.MULTIPLE_TABLES,
                        "维度 " + d.name() + " 来自表 " + d.table() + "，与指标所在表 " + tableName + " 不一致");
            }
        }
        LocalTable table = tableRegistry.get(tableName)
                .orElseThrow(() -> new SemanticException(ErrorCode.TABLE_NOT_FOUND,
                        "引用的本地表未注册: " + tableName));
        TableDef tableDef = table.def();

        // ---- 维度列解析 ----
        List<ColumnDef> dimColumns = new ArrayList<>();
        for (DimensionDef dim : requestedDims) {
            dimColumns.add(tableDef.column(dim.column())
                    .orElseThrow(() -> new SemanticException(ErrorCode.COLUMN_NOT_FOUND,
                            "表 " + tableName + " 中不存在维度列: " + dim.column())));
        }

        // ---- 时间算法参数校验 ----
        int timeIndex = -1;
        ColumnDef timeColumn = null;
        if (function == TimeFunction.NONE) {
            if (query.timeDimension() != null) {
                throw new SemanticException(ErrorCode.INVALID_TIME_FUNCTION,
                        "timeFunction=NONE 时不允许设置 timeDimension");
            }
            if (query.windowSize() != null) {
                throw new SemanticException(ErrorCode.INVALID_WINDOW_SIZE,
                        "windowSize 仅在 timeFunction=SLIDING 时允许设置");
            }
            if (query.rankOrder() != null) {
                throw new SemanticException(ErrorCode.INVALID_TIME_FUNCTION,
                        "rankOrder 仅在 timeFunction=RANK 时允许设置");
            }
        } else {
            if (query.timeDimension() == null || query.timeDimension().isBlank()) {
                throw new SemanticException(ErrorCode.INVALID_TIME_FUNCTION,
                        function + " 必须指定 timeDimension");
            }
            timeIndex = dimNames.indexOf(query.timeDimension());
            if (timeIndex < 0) {
                throw new SemanticException(ErrorCode.INVALID_TIME_FUNCTION,
                        "timeDimension " + query.timeDimension()
                                + " 必须出现在 dimensions 中，才会按时间期输出与排名");
            }
            timeColumn = dimColumns.get(timeIndex);
            if (!timeColumn.type().isTemporal()) {
                throw new SemanticException(ErrorCode.INVALID_TIME_FUNCTION,
                        "timeDimension 对应列必须是 DATE 类型，实际为 "
                                + timeColumn.type() + "（列 " + timeColumn.name() + "）");
            }
            if (function == TimeFunction.SLIDING) {
                if (query.windowSize() == null || query.windowSize() < 1) {
                    throw new SemanticException(ErrorCode.INVALID_WINDOW_SIZE,
                            "SLIDING 必须给出 >=1 的 windowSize，实际: " + query.windowSize());
                }
            } else if (query.windowSize() != null) {
                throw new SemanticException(ErrorCode.INVALID_WINDOW_SIZE,
                        "windowSize 仅在 timeFunction=SLIDING 时允许设置，当前为 " + function);
            }
            if (function != TimeFunction.RANK && query.rankOrder() != null) {
                throw new SemanticException(ErrorCode.INVALID_TIME_FUNCTION,
                        "rankOrder 仅在 timeFunction=RANK 时允许设置，当前为 " + function);
            }
        }

        // ---- filters 校验：必须引用本版本已定义且本次已分组的维度 ----
        for (String key : filters.keySet()) {
            if (!version.dimensions().containsKey(key)) {
                throw new SemanticException(ErrorCode.DIMENSION_NOT_FOUND,
                        "filter 引用了版本 " + version.version() + " 中不存在的维度: " + key);
            }
            if (!dimNames.contains(key)) {
                throw new SemanticException(ErrorCode.INVALID_REQUEST,
                        "filter 维度 " + key + " 未包含在 dimensions 中，无法对输出行过滤");
            }
        }

        // ---- 单次顺序扫描分组；组内行保持表行序（求和可重复的关键） ----
        record GroupKey(List<Object> values) {
            GroupKey {
                values = List.copyOf(values);
            }
            @Override
            public boolean equals(Object o) {
                return o instanceof GroupKey g && g.values.equals(values);
            }
            @Override
            public int hashCode() {
                return values.hashCode();
            }
        }

        Map<GroupKey, List<Map<String, Object>>> rowsByGroup = new LinkedHashMap<>();
        for (Map<String, Object> row : table.rows()) {
            List<Object> keyValues = new ArrayList<>(dimNames.size());
            for (ColumnDef column : dimColumns) {
                Object value = Aggregators.readNullable(column, row);
                if (value == null) {
                    throw new SemanticException(ErrorCode.FIELD_VALUE_MISSING,
                            "分组/时间维度列 " + column.name() + " 存在空值，拒绝静默归入 null 分组");
                }
                keyValues.add(value);
            }
            rowsByGroup.computeIfAbsent(new GroupKey(keyValues), k -> new ArrayList<>()).add(row);
        }

        // ---- 派生指标依赖闭包与拓扑序（引用合法性/环已在发布期保证） ----
        Map<String, MetricDef> closure = new LinkedHashMap<>();
        requestedMetrics.stream().map(MetricDef::name).forEach(n -> addClosure(n, version, closure));
        List<String> evaluationOrder = topoOrder(closure);

        // 组装输出列
        List<String> columns = new ArrayList<>(dimNames);
        for (String metric : metricNames) {
            columns.add(metric);
            if (function == TimeFunction.RANK) {
                columns.add(metric + "__rank");
            }
        }

        // ---- 无时间算法：普通分组聚合（含全局聚合） ----
        if (function == TimeFunction.NONE) {
            List<Map<String, Object>> output = new ArrayList<>();
            for (Map.Entry<GroupKey, List<Map<String, Object>>> entry : rowsByGroup.entrySet()) {
                GroupKey key = entry.getKey();
                if (!passesFilters(dimNames, key.values(), filters)) {
                    continue;
                }
                Map<String, Object> values = computeValues(
                        entry.getValue(), closure, evaluationOrder, tableDef);
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 0; i < dimNames.size(); i++) {
                    row.put(dimNames.get(i), key.values().get(i));
                }
                for (String metric : metricNames) {
                    row.put(metric, values.get(metric));
                }
                output.add(row);
            }
            output.sort(rowComparator(dimNames));
            return new QueryResult(columns, output);
        }

        // ---- 时间算法：按分区（剔除时间维度后的分组）组织时间期 ----
        int ti = timeIndex;
        Map<List<Object>, TreeMap<String, GroupKey>> partitions = new TreeMap<>(listComparator());
        Map<GroupKey, List<Map<String, Object>>> groupRowsRef = rowsByGroup;

        for (GroupKey key : rowsByGroup.keySet()) {
            List<Object> partitionKey = new ArrayList<>();
            for (int i = 0; i < key.values().size(); i++) {
                if (i != ti) {
                    partitionKey.add(key.values().get(i));
                }
            }
            String period = (String) key.values().get(ti);
            partitions.computeIfAbsent(partitionKey, k -> new TreeMap<>()).put(period, key);
        }

        // 每个分区、每个指标在每个输出期上的值
        // 结构：partitionKey -> period -> (metricName -> value)
        Map<List<Object>, TreeMap<String, Map<String, Object>>> series = new TreeMap<>(listComparator());
        for (Map.Entry<List<Object>, TreeMap<String, GroupKey>> part : partitions.entrySet()) {
            TreeMap<String, Map<String, Object>> periodValues = new TreeMap<>();
            List<String> periods = new ArrayList<>(part.getValue().keySet());

            for (int i = 0; i < periods.size(); i++) {
                String period = periods.get(i);
                List<Map<String, Object>> windowRows;
                if (function == TimeFunction.RANK) {
                    windowRows = groupRowsRef.get(part.getValue().get(period));
                } else {
                    int from = function == TimeFunction.CUMULATIVE
                            ? 0
                            : Math.max(0, i - query.windowSize() + 1);
                    windowRows = new ArrayList<>();
                    for (int j = from; j <= i; j++) {
                        windowRows.addAll(groupRowsRef.get(part.getValue().get(periods.get(j))));
                    }
                }
                Map<String, Object> values = computeValues(
                        windowRows, closure, evaluationOrder, tableDef);
                periodValues.put(period, values);
            }
            series.put(part.getKey(), periodValues);
        }

        // ---- 生成输出行（filters 只筛输出，不改变窗口输入） ----
        List<Map<String, Object>> output = new ArrayList<>();
        RankOrder order = query.rankOrder() == null ? RankOrder.DESC : query.rankOrder();

        for (Map.Entry<List<Object>, TreeMap<String, Map<String, Object>>> part : series.entrySet()) {
            List<Object> partitionKey = part.getKey();
            TreeMap<String, Map<String, Object>> periodValues = part.getValue();

            // RANK：分区内逐指标竞赛排名
            Map<String, Map<String, Long>> ranksByMetric = new HashMap<>();
            if (function == TimeFunction.RANK) {
                for (String metric : metricNames) {
                    ranksByMetric.put(metric, competitionRank(periodValues, metric, order));
                }
            }

            for (Map.Entry<String, Map<String, Object>> periodEntry : periodValues.entrySet()) {
                String period = periodEntry.getKey();
                // 用分区键 + 时间期重组完整分组键，供过滤与输出
                List<Object> fullKey = new ArrayList<>();
                int p = 0;
                for (int i = 0; i < dimNames.size(); i++) {
                    fullKey.add(i == ti ? period : partitionKey.get(p++));
                }
                if (!passesFilters(dimNames, fullKey, filters)) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 0; i < dimNames.size(); i++) {
                    row.put(dimNames.get(i), fullKey.get(i));
                }
                for (String metric : metricNames) {
                    row.put(metric, periodEntry.getValue().get(metric));
                    if (function == TimeFunction.RANK) {
                        row.put(metric + "__rank", ranksByMetric.get(metric).get(period));
                    }
                }
                output.add(row);
            }
        }

        if (function == TimeFunction.RANK) {
            output.sort(rankRowComparator(dimNames, ti, metricNames));
        } else {
            output.sort(seriesRowComparator(dimNames, ti));
        }
        return new QueryResult(columns, output);
    }

    /** 计算一个（窗口）行集上闭包内全部指标的值：先基础后派生。 */
    private Map<String, Object> computeValues(
            List<Map<String, Object>> rows,
            Map<String, MetricDef> closure,
            List<String> evaluationOrder,
            TableDef tableDef) {

        Map<String, Object> values = new HashMap<>();
        Map<String, Double> numericView = new HashMap<>();
        for (String name : evaluationOrder) {
            MetricDef metric = closure.get(name);
            if (metric.isDerived()) {
                Double value = expressionEvaluator.evaluate(metric, numericView);
                values.put(name, value);
                numericView.put(name, value);
            } else {
                ColumnDef source = null;
                if (metric.sourceColumn() != null && !metric.sourceColumn().isBlank()) {
                    source = tableDef.column(metric.sourceColumn())
                            .orElseThrow(() -> new SemanticException(ErrorCode.COLUMN_NOT_FOUND,
                                    "表 " + tableDef.name() + " 中不存在指标列: " + metric.sourceColumn()));
                } else if (metric.aggregation() != AggregationType.COUNT) {
                    throw new SemanticException(ErrorCode.INVALID_AGGREGATION,
                            metric.aggregation() + " 必须指定 sourceColumn，指标: " + metric.name());
                }
                Object value = Aggregators.aggregate(metric.aggregation(), source, rows);
                values.put(name, value);
                numericView.put(name, value == null ? null : ((Number) value).doubleValue());
            }
        }
        return values;
    }

    private void addClosure(String metricName, SemanticVersion version, Map<String, MetricDef> closure) {
        if (closure.containsKey(metricName)) {
            return;
        }
        MetricDef metric = version.metrics().get(metricName);
        if (metric == null) {
            throw new SemanticException(ErrorCode.METRIC_NOT_FOUND,
                    "派生链引用了版本中不存在的指标: " + metricName);
        }
        closure.put(metricName, metric);
        if (metric.isDerived()) {
            for (String ref : DefinitionValidator.referencedMetrics(metric.expression())) {
                addClosure(ref, version, closure);
            }
        }
    }

    /** 拓扑排序：被依赖者在前；同层按名称排序，保证计算顺序确定。 */
    private List<String> topoOrder(Map<String, MetricDef> closure) {
        List<String> order = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        for (String name : closure.keySet().stream().sorted().toList()) {
            topoVisit(name, closure, visited, order);
        }
        return order;
    }

    private void topoVisit(String name, Map<String, MetricDef> closure,
                           Set<String> visited, List<String> order) {
        if (!visited.add(name)) {
            return;
        }
        MetricDef metric = closure.get(name);
        if (metric.isDerived()) {
            for (String ref : DefinitionValidator.referencedMetrics(metric.expression()).stream()
                    .distinct().sorted().toList()) {
                topoVisit(ref, closure, visited, order);
            }
        }
        order.add(name);
    }

    private boolean passesFilters(List<String> dimNames, List<Object> keyValues,
                                  Map<String, String> filters) {
        for (Map.Entry<String, String> filter : filters.entrySet()) {
            int idx = dimNames.indexOf(filter.getKey());
            Object actual = keyValues.get(idx);
            if (!Objects.toString(actual, null).equals(filter.getValue())) {
                return false;
            }
        }
        return true;
    }

    /**
     * 标准竞赛排名（1,2,2,4）。
     * 排序：先按指标值（DESC 大在前 / ASC 小在前），null 永远在末尾并列；
     * 值相同者共享名次，并列以时间期兜底排序（不影响名次）。
     */
    private Map<String, Long> competitionRank(TreeMap<String, Map<String, Object>> periodValues,
                                              String metric, RankOrder order) {
        List<Object[]> entries = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> e : periodValues.entrySet()) {
            entries.add(new Object[]{e.getKey(), e.getValue().get(metric)});
        }
        Comparator<Object> valueComparator = (a, b) -> compareMetricValues(a, b, order);
        entries.sort((x, y) -> {
            int c = valueComparator.compare(x[1], y[1]);
            if (c != 0) {
                return c;
            }
            return ((String) x[0]).compareTo((String) y[0]);
        });

        Map<String, Long> ranks = new HashMap<>();
        long rank = 0;
        Object previous = new Object();
        boolean hasPrevious = false;
        for (int i = 0; i < entries.size(); i++) {
            Object value = entries.get(i)[1];
            if (!hasPrevious || metricCompareZero(value, previous) != 0) {
                rank = i + 1L;
            }
            ranks.put((String) entries.get(i)[0], rank);
            previous = value;
            hasPrevious = true;
        }
        return ranks;
    }

    /** 排序比较：null 永远末尾；非 null 按方向比较。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compareMetricValues(Object a, Object b, RankOrder order) {
        if (a == null && b == null) {
            return 0;
        }
        if (a == null) {
            return 1;
        }
        if (b == null) {
            return -1;
        }
        int cmp;
        if (a instanceof Number && b instanceof Number) {
            cmp = Double.compare(((Number) a).doubleValue(), ((Number) b).doubleValue());
        } else {
            cmp = ((Comparable) a).compareTo(b);
        }
        return order == RankOrder.DESC ? -cmp : cmp;
    }

    /** 并列判定：null 之间并列；数值用 double 精度比较。 */
    private static int metricCompareZero(Object a, Object b) {
        if (a == null && b == null) {
            return 0;
        }
        if (a == null || b == null) {
            return 1;
        }
        if (a instanceof Number && b instanceof Number) {
            return Double.compare(((Number) a).doubleValue(), ((Number) b).doubleValue());
        }
        @SuppressWarnings({"unchecked", "rawtypes"})
        int cmp = ((Comparable) a).compareTo(b);
        return cmp;
    }

    private static Comparator<Map<String, Object>> rowComparator(List<String> dimNames) {
        return Comparator.comparing((Map<String, Object> row) -> extractKey(row, dimNames),
                listComparator());
    }

    /** 累计/滑动结果：分区维度在前、时间期正序。 */
    private static Comparator<Map<String, Object>> seriesRowComparator(List<String> dimNames, int timeIndex) {
        List<String> partitionDims = new ArrayList<>();
        for (int i = 0; i < dimNames.size(); i++) {
            if (i != timeIndex) {
                partitionDims.add(dimNames.get(i));
            }
        }
        String timeDim = dimNames.get(timeIndex);
        return Comparator
                .comparing((Map<String, Object> row) -> extractKey(row, partitionDims), listComparator())
                .thenComparing(row -> Objects.toString(row.get(timeDim), ""));
    }

    /** 排名结果：分区维度在前、名次升序、时间期兜底。 */
    private static Comparator<Map<String, Object>> rankRowComparator(
            List<String> dimNames, int timeIndex, List<String> metricNames) {
        List<String> partitionDims = new ArrayList<>();
        for (int i = 0; i < dimNames.size(); i++) {
            if (i != timeIndex) {
                partitionDims.add(dimNames.get(i));
            }
        }
        String timeDim = dimNames.get(timeIndex);
        String firstRankColumn = metricNames.get(0) + "__rank";
        return Comparator
                .comparing((Map<String, Object> row) -> extractKey(row, partitionDims), listComparator())
                .thenComparingLong(row -> ((Number) java.util.Objects.requireNonNullElse(
                        row.get(firstRankColumn), 0L)).longValue())
                .thenComparing(row -> Objects.toString(row.get(timeDim), ""));
    }

    private static List<Object> extractKey(Map<String, Object> row, List<String> names) {
        List<Object> key = new ArrayList<>(names.size());
        for (String name : names) {
            key.add(row.get(name));
        }
        return key;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Comparator<List<Object>> listComparator() {
        return (a, b) -> {
            int n = Math.min(a.size(), b.size());
            for (int i = 0; i < n; i++) {
                Object x = a.get(i);
                Object y = b.get(i);
                if (x == null && y == null) {
                    continue;
                }
                if (x == null) {
                    return 1;
                }
                if (y == null) {
                    return -1;
                }
                int cmp;
                if (x instanceof Number && y instanceof Number) {
                    cmp = Double.compare(((Number) x).doubleValue(), ((Number) y).doubleValue());
                } else {
                    cmp = ((Comparable) x).compareTo(y);
                }
                if (cmp != 0) {
                    return cmp;
                }
            }
            return Integer.compare(a.size(), b.size());
        };
    }
}
