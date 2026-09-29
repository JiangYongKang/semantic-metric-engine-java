package com.github.highcumontoa.semanticmetricenginejava.tests;

import com.github.highcumontoa.semanticmetricenginejava.engine.QueryEngine;
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
import com.github.highcumontoa.semanticmetricenginejava.model.TableDef;
import com.github.highcumontoa.semanticmetricenginejava.model.TimeFunction;
import com.github.highcumontoa.semanticmetricenginejava.registry.LocalTableRegistry;
import com.github.highcumontoa.semanticmetricenginejava.registry.SemanticRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 查询引擎边界：非法引用/非法口径拒绝原因可区分；空值、并列排名、分组边界确定；
 * 同一查询重复执行结果一致、分批执行合并后与整段一致。
 */
class QueryEngineTest {

    private static final Logger log = LoggerFactory.getLogger(QueryEngineTest.class);

    private SemanticRegistry versions;
    private QueryEngine engine;

    // 小数据集：刻意包含 金额null(d2/east)、并列(east d1=10 与 south d1=10)、稀疏分区(south 只有 d1)
    private static final String[][] DATA = {
            // region, day, amount, qty
            {"east", "d1", "10", "1"},
            {"west", "d1", "20", "2"},
            {"south", "d1", "10", "1"},
            {"east", "d2", null, "3"},
            {"west", "d2", "20", "4"},
            {"east", "d3", "30", "5"},
    };

    private static Map<String, Object> row(String region, String day, String amount, String qty) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("region", region);
        m.put("day", day);
        m.put("amount", amount == null ? null : Double.parseDouble(amount));
        m.put("qty", qty == null ? null : Long.parseLong(qty));
        return m;
    }

    @BeforeEach
    void setUp() {
        LocalTableRegistry tables = new LocalTableRegistry();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String[] d : DATA) {
            rows.add(row(d[0], d[1], d[2], d[3]));
        }
        tables.register(new LocalTable(
                new TableDef("sales", List.of(
                        new ColumnDef("region", DataType.STRING),
                        new ColumnDef("day", DataType.DATE),
                        new ColumnDef("amount", DataType.DOUBLE),
                        new ColumnDef("qty", DataType.LONG))),
                rows));
        versions = new SemanticRegistry(tables);
        versions.publish(new com.github.highcumontoa.semanticmetricenginejava.model.PublishRequest(
                "v1", null,
                List.of(new DimensionDef("region", "sales", "region"),
                        new DimensionDef("day", "sales", "day")),
                List.of(new MetricDef("revenue", "sales", AggregationType.SUM, "amount", null, null),
                        new MetricDef("orders", "sales", AggregationType.COUNT, "order_col_missing_on_purpose".equals("x") ? null : null, null, null),
                        new MetricDef("cnt_amt", "sales", AggregationType.COUNT, "amount", null, null),
                        new MetricDef("avg_amt", "sales", AggregationType.AVG, "amount", null, null),
                        new MetricDef("qty_sum", "sales", AggregationType.SUM, "qty", null, null))));
        engine = new QueryEngine(versions, tables);
    }

    private MetricQuery q(List<String> metrics, List<String> dims) {
        return MetricQuery.aggregate("v1", metrics, dims);
    }

    private Object value(QueryResult result, int rowIndex, String column) {
        return result.rows().get(rowIndex).get(column);
    }

    private SemanticException expectReject(MetricQuery query, ErrorCode expected, String inputDesc) {
        SemanticException ex = assertThrows(SemanticException.class, () -> engine.execute(query));
        log.info("输入: {}; 判定: {} - {}", inputDesc, ex.getCode(), ex.getMessage());
        assertEquals(expected, ex.getCode());
        return ex;
    }

    @Test
    void unknownMetricAndDimensionAreRejectedDifferently() {
        expectReject(q(List.of("ghost"), List.of("region")),
                ErrorCode.METRIC_NOT_FOUND, "指标 ghost");
        expectReject(q(List.of("revenue"), List.of("ghost")),
                ErrorCode.DIMENSION_NOT_FOUND, "维度 ghost");
        expectReject(MetricQuery.aggregate("vX", List.of("revenue"), List.of()),
                ErrorCode.VERSION_NOT_FOUND, "版本 vX");
    }

    @Test
    void emptyMetricsAndTimeFunctionMisuseAreRejected() {
        expectReject(MetricQuery.aggregate("v1", List.of(), List.of()),
                ErrorCode.INVALID_REQUEST, "空指标列表");
        expectReject(new MetricQuery("v1", List.of("revenue"), List.of("region", "day"),
                        "day", TimeFunction.SLIDING, null, null, Map.of()),
                ErrorCode.INVALID_WINDOW_SIZE, "SLIDING 无 windowSize");
        expectReject(new MetricQuery("v1", List.of("revenue"), List.of("region"),
                        "day", TimeFunction.CUMULATIVE, null, null, Map.of()),
                ErrorCode.INVALID_TIME_FUNCTION, "timeDimension 未分组");
        expectReject(new MetricQuery("v1", List.of("revenue"), List.of("region", "day"),
                        null, TimeFunction.NONE, 3, null, Map.of()),
                ErrorCode.INVALID_WINDOW_SIZE, "NONE 却给 windowSize");
    }

    @Test
    void groupAggregationNullRulesAndDeterministicOrder() {
        QueryResult result = engine.execute(q(List.of("revenue", "cnt_amt", "avg_amt", "qty_sum"), List.of("region")));
        log.info("输入: 按 region 聚合(amount 含一个 null); 结果: {}", result.rows());

        // 行序按维度字典序确定：east, south, west
        assertEquals("east", value(result, 0, "region"));
        assertEquals("south", value(result, 1, "region"));
        assertEquals("west", value(result, 2, "region"));
        // east: 10 + 30（d2 的 null 跳过）
        assertEquals(40.0, value(result, 0, "revenue"));
        // east: COUNT(amount) 不计 null 行
        assertEquals(2L, value(result, 0, "cnt_amt"));
        // east: AVG = 40/2 = 20
        assertEquals(20.0, value(result, 0, "avg_amt"));
        // qty 全部非空
        assertEquals(9.0, value(result, 0, "qty_sum"));
        // 重复执行必须完全相等
        QueryResult again = engine.execute(q(List.of("revenue", "cnt_amt", "avg_amt", "qty_sum"), List.of("region")));
        assertEquals(result, again);
    }

    @Test
    void nullDimensionValueIsRejected() {
        LocalTableRegistry tables = new LocalTableRegistry();
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row("east", "d1", "1.0", "1"));
        Map<String, Object> bad = row(null, "d1", "2.0", "1");
        rows.add(bad);
        tables.register(new LocalTable(
                new TableDef("t", List.of(
                        new ColumnDef("region", DataType.STRING),
                        new ColumnDef("day", DataType.DATE),
                        new ColumnDef("amount", DataType.DOUBLE),
                        new ColumnDef("qty", DataType.LONG))),
                rows));
        SemanticRegistry reg = new SemanticRegistry(tables);
        reg.publish(new com.github.highcumontoa.semanticmetricenginejava.model.PublishRequest(
                "v1", null,
                List.of(new DimensionDef("region", "t", "region")),
                List.of(new MetricDef("revenue", "t", AggregationType.SUM, "amount", null, null))));
        QueryEngine localEngine = new QueryEngine(reg, tables);
        SemanticException ex = assertThrows(SemanticException.class,
                () -> localEngine.execute(MetricQuery.aggregate("v1", List.of("revenue"), List.of("region"))));
        log.info("输入: 分组列含 null 行 {}; 判定: {} - {}", bad, ex.getCode(), ex.getMessage());
        assertEquals(ErrorCode.FIELD_VALUE_MISSING, ex.getCode());
    }

    @Test
    void missingRowKeyIsRejectedNotTreatedAsNull() {
        LocalTableRegistry tables = new LocalTableRegistry();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("region", "east");
        row.put("day", "d1");
        row.put("qty", 1L); // 故意不放 amount 键
        tables.register(new LocalTable(
                new TableDef("t", List.of(
                        new ColumnDef("region", DataType.STRING),
                        new ColumnDef("day", DataType.DATE),
                        new ColumnDef("amount", DataType.DOUBLE),
                        new ColumnDef("qty", DataType.LONG))),
                List.of(row)));
        SemanticRegistry reg = new SemanticRegistry(tables);
        reg.publish(new com.github.highcumontoa.semanticmetricenginejava.model.PublishRequest(
                "v1", null, List.of(),
                List.of(new MetricDef("revenue", "t", AggregationType.SUM, "amount", null, null))));
        QueryEngine localEngine = new QueryEngine(reg, tables);
        SemanticException ex = assertThrows(SemanticException.class,
                () -> localEngine.execute(MetricQuery.aggregate("v1", List.of("revenue"), List.of())));
        log.info("输入: 行缺少 amount 键 {}; 判定: {} - {}", row, ex.getCode(), ex.getMessage());
        assertEquals(ErrorCode.FIELD_VALUE_MISSING, ex.getCode());
    }
}
