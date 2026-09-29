package com.github.highcumontoa.semanticmetricenginejava.semantic.engine;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.github.highcumontoa.semanticmetricenginejava.semantic.data.Aggregator;
import com.github.highcumontoa.semanticmetricenginejava.semantic.data.DataSet;
import com.github.highcumontoa.semanticmetricenginejava.semantic.data.Row;
import com.github.highcumontoa.semanticmetricenginejava.semantic.data.TableData;
import com.github.highcumontoa.semanticmetricenginejava.semantic.data.Values;
import com.github.highcumontoa.semanticmetricenginejava.semantic.model.Algorithm;
import com.github.highcumontoa.semanticmetricenginejava.semantic.model.DimensionDef;
import com.github.highcumontoa.semanticmetricenginejava.semantic.model.MetricDef;
import com.github.highcumontoa.semanticmetricenginejava.semantic.engine.QueryPlanner.Plan;
import com.github.highcumontoa.semanticmetricenginejava.semantic.engine.QueryPlanner.ResolvedMetric;
import com.github.highcumontoa.semanticmetricenginejava.semantic.query.QueryResult;

/**
 * 查询执行器：在本地数据集上执行计划。
 *
 * <h3>确定性口径（重复执行、分批执行结果必须一致）</h3>
 * <ul>
 *   <li>分组与聚合使用精确值；数值聚合内部为 BigDecimal，见 {@link Aggregator}。</li>
 *   <li>输出行按维度键、每个键 nulls-last 升序排序，与源数据行序无关。</li>
 *   <li><b>累计 CUMULATIVE</b>：按时间桶升序，当前桶结果 = 本分区内时间键 ≤ 当前桶的全部行聚合；
 *       时间键为 null 的行不进入任何累计（无法确定其时间位置），也不产生输出桶。</li>
 *   <li><b>滑动 ROLLING(n)</b>：按本分区<b>实际存在</b>的时间桶排序（无数据的缺失桶不补零、
 *       不占窗口位置），当前桶结果 = 含当前桶在内往前最多 n 个桶的行聚合。</li>
 *   <li><b>排名 RANK</b>：标准竞争排名（1,2,2,4）；并列值同名次，其后名次跳过；
 *       空聚合值名次为 null（null 排最后）；指定一个日期维度时，分区 = 其余分组维度、
 *       跨日期桶排名；不指定日期维度时全表为一个分区；日期键为空的组保留输出但名次为 null。
 *       输出行序始终按完整维度键升序，与名次无关。</li>
 * </ul>
 */
public class QueryExecutor {

    private final DataSet dataSet;

    public QueryExecutor(DataSet dataSet) {
        this.dataSet = dataSet;
    }

    public QueryResult execute(Plan plan) {
        TableData table = dataSet.requireTable(plan.tableName());
        List<DimensionDef> dims = plan.dimensions();
        List<ResolvedMetric> metrics = plan.metrics();
        DimensionDef timeDim = plan.timeDimension();

        List<String> columns = new ArrayList<>();
        dims.forEach(d -> columns.add(d.name()));
        metrics.forEach(m -> columns.add(m.outputName()));

        boolean windowed = timeDim != null
                && metrics.stream().anyMatch(m -> m.root().algorithm() == Algorithm.CUMULATIVE
                        || m.root().algorithm() == Algorithm.ROLLING);
        boolean ranked = metrics.stream().anyMatch(m -> m.root().algorithm() == Algorithm.RANK);

        // key = 全部分组维度（日期维度已截断）；保序收集行，后续统一确定性排序
        Map<List<Object>, List<Row>> grouped = new LinkedHashMap<>();
        // 无分组维度时，即使表为空也必须输出一行全局聚合（SUM/AVG=null，COUNT=0）
        if (dims.isEmpty()) {
            grouped.put(List.of(), new ArrayList<>());
        }
        for (Row row : table.rows()) {
            List<Object> key = dimensionKey(row, dims);
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
        }

        List<Map<String, Object>> rows;
        if (windowed) {
            rows = executeWindow(plan, grouped, columns);
        } else if (ranked) {
            rows = executeRank(plan, grouped, columns);
        } else {
            rows = executePlain(plan, grouped, columns);
        }
        return new QueryResult(plan.model().version(), columns, rows);
    }

    // ---------- 普通聚合（含无维度的全局聚合） ----------

    private List<Map<String, Object>> executePlain(Plan plan, Map<List<Object>, List<Row>> grouped,
                                                   List<String> columns) {
        List<List<Object>> keys = sortedKeys(grouped, plan.dimensions());
        List<Map<String, Object>> out = new ArrayList<>();
        for (List<Object> key : keys) {
            Map<String, Object> record = baseRecord(key, plan.dimensions(), columns);
            for (ResolvedMetric rm : plan.metrics()) {
                Aggregator agg = newAggregator(rm.root());
                for (Row row : grouped.get(key)) {
                    agg.accumulate(inputValue(row, rm.root()));
                }
                record.put(rm.outputName(), agg.result());
            }
            out.add(record);
        }
        return out;
    }

    // ---------- 累计 / 滑动窗口 ----------

    private List<Map<String, Object>> executeWindow(Plan plan, Map<List<Object>, List<Row>> grouped,
                                                    List<String> columns) {
        List<DimensionDef> dims = plan.dimensions();
        int timeIndex = dims.indexOf(plan.timeDimension());

        // 分区键 = 去掉时间维度后的其余维度
        Map<List<Object>, TreeMap<LocalDate, List<Row>>> partitions = new LinkedHashMap<>();
        for (Map.Entry<List<Object>, List<Row>> e : grouped.entrySet()) {
            Object timeVal = e.getKey().get(timeIndex);
            if (timeVal == null) {
                continue; // 空时间值无法定位，明确排除在累计/窗口之外
            }
            List<Object> partitionKey = without(e.getKey(), timeIndex);
            TreeMap<LocalDate, List<Row>> buckets =
                    partitions.computeIfAbsent(partitionKey, k -> new TreeMap<>());
            buckets.computeIfAbsent((LocalDate) timeVal, k -> new ArrayList<>()).addAll(e.getValue());
        }

        // 输出顺序：分区键 nulls-last 升序，分区内时间桶升序
        List<List<Object>> partitionKeys = new ArrayList<>(partitions.keySet());
        partitionKeys.sort(listComparator(dims.size() - 1));

        List<Map<String, Object>> out = new ArrayList<>();
        for (List<Object> partitionKey : partitionKeys) {
            TreeMap<LocalDate, List<Row>> buckets = partitions.get(partitionKey);
            List<LocalDate> times = new ArrayList<>(buckets.navigableKeySet());
            for (int i = 0; i < times.size(); i++) {
                LocalDate bucket = times.get(i);
                List<Object> fullKey = withTime(partitionKey, timeIndex, bucket, dims.size());
                Map<String, Object> record = baseRecord(fullKey, dims, columns);
                for (ResolvedMetric rm : plan.metrics()) {
                    Algorithm algo = rm.root().algorithm();
                    int from = algo == Algorithm.ROLLING
                            ? Math.max(0, i - (rm.root().windowSize() - 1))
                            : 0; // CUMULATIVE 从首个桶开始
                    Aggregator agg = newAggregator(rm.root());
                    for (int j = from; j <= i; j++) {
                        for (Row row : buckets.get(times.get(j))) {
                            agg.accumulate(inputValue(row, rm.root()));
                        }
                    }
                    record.put(rm.outputName(), agg.result());
                }
                out.add(record);
            }
        }
        return out;
    }

    // ---------- 排名（标准竞争排名） ----------

    private List<Map<String, Object>> executeRank(Plan plan, Map<List<Object>, List<Row>> grouped,
                                                  List<String> columns) {
        List<DimensionDef> dims = plan.dimensions();
        DimensionDef timeDim = plan.timeDimension();
        int timeIndex = timeDim == null ? -1 : dims.indexOf(timeDim);

        // 1) 每个输出组先算各指标基础聚合值
        Map<List<Object>, Map<String, Object>> baseValues = new LinkedHashMap<>();
        for (List<Object> key : grouped.keySet()) {
            Map<String, Object> metricValues = new LinkedHashMap<>();
            for (ResolvedMetric rm : plan.metrics()) {
                Aggregator agg = newAggregator(rm.root());
                for (Row row : grouped.get(key)) {
                    agg.accumulate(inputValue(row, rm.root()));
                }
                metricValues.put(rm.outputName(), agg.result());
            }
            baseValues.put(key, metricValues);
        }

        // 2) 分区：有日期维度时，分区键 = 去掉日期维度后的维度键；无日期维度时全部组同一分区。
        //    空时间桶组（timeVal == null）仍保留输出，但排名置为 null（无法定位时间位置）。
        Map<List<Object>, List<List<Object>>> partitions = new LinkedHashMap<>();
        for (List<Object> key : baseValues.keySet()) {
            if (timeIndex >= 0 && key.get(timeIndex) == null) {
                continue;
            }
            List<Object> partitionKey = timeIndex < 0 ? List.of() : without(key, timeIndex);
            partitions.computeIfAbsent(partitionKey, k -> new ArrayList<>()).add(key);
        }

        // 3) 每个分区内对每个 RANK 指标做标准竞争排名
        for (List<Object> partitionKey : partitions.keySet()) {
            List<List<Object>> groups = partitions.get(partitionKey);
            for (ResolvedMetric rm : plan.metrics()) {
                if (rm.root().algorithm() != Algorithm.RANK) {
                    continue;
                }
                String name = rm.outputName();
                // 值降序（null 最后）；比较为全序，同值并列
                List<List<Object>> ordered = new ArrayList<>(groups);
                ordered.sort(Comparator.comparing(
                        (List<Object> k) -> baseValues.get(k).get(name),
                        Values.nullsLastComparator().reversed()));
                long rank = 0;
                Object prev = null;
                long position = 0;
                for (List<Object> k : ordered) {
                    position++;
                    Object v = baseValues.get(k).get(name);
                    if (position == 1 || Values.compareNullsLast(v, prev) != 0) {
                        rank = position;
                    }
                    baseValues.get(k).put(name, rank);
                    prev = v;
                }
            }
        }

        // 4) 输出行按完整维度键 nulls-last 升序（不按名次），保证行序稳定
        List<List<Object>> keys = sortedKeys(grouped, dims);
        List<Map<String, Object>> out = new ArrayList<>();
        for (List<Object> key : keys) {
            Map<String, Object> record = baseRecord(key, dims, columns);
            record.putAll(baseValues.get(key));
            out.add(record);
        }
        return out;
    }

    // ---------- 公共工具 ----------

    private Aggregator newAggregator(MetricDef m) {
        return new Aggregator(m.aggType(), m.expression() == null);
    }

    private Object inputValue(Row row, MetricDef m) {
        return m.expression() == null ? null : row.get(m.expression());
    }

    private List<Object> dimensionKey(Row row, List<DimensionDef> dims) {
        List<Object> key = new ArrayList<>(dims.size());
        for (DimensionDef d : dims) {
            Object raw = row.get(d.expression());
            if (raw instanceof LocalDate ld) {
                key.add(Values.truncate(ld, d.granularity()));
            } else {
                key.add(raw);
            }
        }
        return key;
    }

    private List<List<Object>> sortedKeys(Map<List<Object>, List<Row>> grouped,
                                          List<DimensionDef> dims) {
        List<List<Object>> keys = new ArrayList<>(grouped.keySet());
        keys.sort(listComparator(dims.size()));
        return keys;
    }

    /** 逐列 nulls-last 升序的确定性键比较器。 */
    private Comparator<List<Object>> listComparator(int size) {
        return (a, b) -> {
            for (int i = 0; i < size; i++) {
                int c = Values.compareNullsLast(a.get(i), b.get(i));
                if (c != 0) {
                    return c;
                }
            }
            return 0;
        };
    }

    private Map<String, Object> baseRecord(List<Object> key, List<DimensionDef> dims,
                                           List<String> columns) {
        Map<String, Object> record = new LinkedHashMap<>();
        for (String c : columns) {
            record.put(c, null);
        }
        for (int i = 0; i < dims.size(); i++) {
            record.put(dims.get(i).name(), key.get(i));
        }
        return record;
    }

    private List<Object> without(List<Object> key, int index) {
        List<Object> out = new ArrayList<>(key.size() - 1);
        for (int i = 0; i < key.size(); i++) {
            if (i != index) {
                out.add(key.get(i));
            }
        }
        return out;
    }

    private List<Object> withTime(List<Object> partitionKey, int timeIndex, LocalDate bucket,
                                  int fullSize) {
        List<Object> out = new ArrayList<>(fullSize);
        int p = 0;
        for (int i = 0; i < fullSize; i++) {
            if (i == timeIndex) {
                out.add(bucket);
            } else {
                out.add(partitionKey.get(p++));
            }
        }
        return out;
    }
}
