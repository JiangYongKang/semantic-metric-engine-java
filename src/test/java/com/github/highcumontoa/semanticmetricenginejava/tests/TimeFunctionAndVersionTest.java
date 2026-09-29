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
import com.github.highcumontoa.semanticmetricenginejava.model.PublishRequest;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 时间算法边界与版本兼容：
 * 累计/滑动基于全量行重算（分批一致）、排名并列规则与 null 置尾、
 * 重命名/删除/口径调整后按版本复现或区分。
 */
class TimeFunctionAndVersionTest {

    private static final Logger log = LoggerFactory.getLogger(TimeFunctionAndVersionTest.class);

    private QueryEngine engine;
    private SemanticRegistry versions;

    // region/day/amount：d1 east=10, south=10(并列); d2 east=null, west=20; d3 east=30
    private static final String[][] DATA = {
            {"east", "d1", "10"}, {"west", "d1", "20"}, {"south", "d1", "10"},
            {"east", "d2", null}, {"west", "d2", "20"},
            {"east", "d3", "30"},
    };

    @BeforeEach
    void setUp() {
        LocalTableRegistry tables = new LocalTableRegistry();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String[] d : DATA) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("region", d[0]);
            m.put("day", d[1]);
            m.put("amount", d[2] == null ? null : Double.parseDouble(d[2]));
            rows.add(m);
        }
        tables.register(new LocalTable(
                new TableDef("sales", List.of(
                        new ColumnDef("region", DataType.STRING),
                        new ColumnDef("day", DataType.DATE),
                        new ColumnDef("amount", DataType.DOUBLE))),
                rows));
        versions = new SemanticRegistry(tables);

        MetricDef revenue = new MetricDef("revenue", "sales", AggregationType.SUM, "amount", null, null);
        MetricDef tax10 = new MetricDef("tax", "sales", null, null,
                new MetricExpression.ExprBinary(MetricExpression.Op.MUL,
                        new MetricExpression.ExprRef("revenue"), new MetricExpression.ExprLiteral(0.1d)), null);
        versions.publish(new PublishRequest("v1", null,
                List.of(new DimensionDef("region", "sales", "region"),
                        new DimensionDef("day", "sales", "day")),
                List.of(revenue, tax10)));

        // v2：revenue 重命名 total_revenue；删除 tax 后以 0.2 口径重建
        MetricDef totalRevenue = new MetricDef("total_revenue", "sales", AggregationType.SUM, "amount", null, "revenue");
        MetricDef tax20 = new MetricDef("tax", "sales", null, null,
                new MetricExpression.ExprBinary(MetricExpression.Op.MUL,
                        new MetricExpression.ExprRef("total_revenue"), new MetricExpression.ExprLiteral(0.2d)), null);
        versions.publish(new PublishRequest("v2", "v1",
                List.of(new DimensionDef("region", "sales", "region"),
                        new DimensionDef("day", "sales", "day")),
                List.of(totalRevenue, tax20)));

        engine = new QueryEngine(versions, tables);
    }

    private MetricQuery timeFn(TimeFunction fn, Integer window, Map<String, String> filters) {
        return new MetricQuery("v1", List.of("revenue"), List.of("region", "day"),
                "day", fn, window, null, filters);
    }

    @Test
    void cumulativeUsesFullDataAndBatchQueriesAreConsistent() {
        QueryResult full = engine.execute(timeFn(TimeFunction.CUMULATIVE, null, Map.of()));
        log.info("累计全量输入: region/day 6 行(含 east/d2 amount=null); 结果: {}", full.rows());

        // east: d1=10, d2=10(null 跳过，累计不变), d3=40；west: d1=20, d2=40；south: d1=10
        Map<String, Object> eastD2 = row(full, "east", "d2");
        assertEquals(10.0, eastD2.get("revenue"), "空值不计入，累计保持 10");
        Map<String, Object> eastD3 = row(full, "east", "d3");
        assertEquals(40.0, eastD3.get("revenue"));

        // 分批执行（不同 filter）后合并必须与全量一致
        QueryResult eastBatch = engine.execute(timeFn(TimeFunction.CUMULATIVE, null, Map.of("region", "east")));
        QueryResult westBatch = engine.execute(timeFn(TimeFunction.CUMULATIVE, null, Map.of("region", "west")));
        QueryResult southBatch = engine.execute(timeFn(TimeFunction.CUMULATIVE, null, Map.of("region", "south")));
        List<Map<String, Object>> merged = new ArrayList<>();
        merged.addAll(eastBatch.rows());
        merged.addAll(westBatch.rows());
        merged.addAll(southBatch.rows());
        assertEquals(full.rows().size(), merged.size(), "分批行数之和应等于全量行数");
        for (Map<String, Object> r : full.rows()) {
            org.junit.jupiter.api.Assertions.assertTrue(merged.contains(r),
                    "分批结果合并后必须包含全量行: " + r);
        }
        // 重复执行字节级一致
        assertEquals(full, engine.execute(timeFn(TimeFunction.CUMULATIVE, null, Map.of())));
    }

    @Test
    void slidingWindowRebuildsFromRawRowsAndFirstPeriodIsPartial() {
        QueryResult result = engine.execute(timeFn(TimeFunction.SLIDING, 2, Map.of()));
        log.info("滑动窗口 k=2; 判定: 每分区首窗仅含首期, 窗口在原始行上重算: {}", result.rows());
        // east: d1=10, d2=10+null行(跳过)=10, d3=null+30=30
        assertEquals(10.0, row(result, "east", "d1").get("revenue"));
        assertEquals(10.0, row(result, "east", "d2").get("revenue"));
        assertEquals(30.0, row(result, "east", "d3").get("revenue"));
        // west: d1=20, d2=20+20=40
        assertEquals(40.0, row(result, "west", "d2").get("revenue"));
        // 稀疏分区 south 只有 d1：窗口不跨分区借用数据
        assertEquals(10.0, row(result, "south", "d1").get("revenue"));

        SemanticException ex = assertThrows(SemanticException.class,
                () -> engine.execute(timeFn(TimeFunction.SLIDING, 0, Map.of())));
        assertEquals(ErrorCode.INVALID_WINDOW_SIZE, ex.getCode());
        log.info("windowSize=0; 判定: {} - {}", ex.getCode(), ex.getMessage());
    }

    @Test
    void rankUsesCompetitionRankingWithTiesAndStableOrder() {
        QueryResult result = engine.execute(new MetricQuery("v1", List.of("revenue"),
                List.of("day"), "day", TimeFunction.RANK, null, RankOrder.DESC, Map.of()));
        log.info("RANK 输入: 按 day 全局收入(d1=40[10+20+10],d2=20,d3=30); 结果: {}", result.rows());
        // d1=40 rank1, d3=30 rank2, d2=20 rank3
        assertEquals(1L, row(result, null, "d1").get("revenue__rank"));
        assertEquals(2L, row(result, null, "d3").get("revenue__rank"));
        assertEquals(3L, row(result, null, "d2").get("revenue__rank"));

        // 分区并列：(region,day) 分区内单期不并列；改为 (day) 维度下构造并列：
        // 使用 region,day 维度时，每分区单期，名次恒为 1；这里验证并列构造：
        // d1 全局 40 唯一，d2=20，d3=30，无并列；另构造 tie 数据集验证 1,2,2,4 段位
        LocalTableRegistry tables2 = new LocalTableRegistry();
        TableDef def = new TableDef("t", List.of(
                new ColumnDef("day", DataType.DATE),
                new ColumnDef("v", DataType.DOUBLE)));
        List<Map<String, Object>> rows2 = List.of(
                Map.of("day", "d1", "v", 5.0),
                Map.of("day", "d2", "v", 5.0),
                Map.of("day", "d3", "v", 5.0),
                Map.of("day", "d4", "v", 9.0));
        tables2.register(new LocalTable(def, rows2));
        SemanticRegistry reg2 = new SemanticRegistry(tables2);
        reg2.publish(new PublishRequest("w", null,
                List.of(new DimensionDef("day", "t", "day")),
                List.of(new MetricDef("vv", "t", AggregationType.SUM, "v", null, null))));
        QueryEngine engine2 = new QueryEngine(reg2, tables2);
        QueryResult tieResult = engine2.execute(new MetricQuery("w", List.of("vv"),
                List.of("day"), "day", TimeFunction.RANK, null, RankOrder.DESC, Map.of()));
        log.info("RANK 并列输入: 值序列 [9,5,5,5](按 d1..d4); 期望竞赛排名 d4=1, d1/d2/d3=2: {}", tieResult.rows());
        assertEquals(1L, row(tieResult, null, "d4").get("vv__rank"));
        assertEquals(2L, row(tieResult, null, "d1").get("vv__rank"));
        assertEquals(2L, row(tieResult, null, "d2").get("vv__rank"));
        assertEquals(2L, row(tieResult, null, "d3").get("vv__rank"));
        assertEquals(tieResult, engine2.execute(new MetricQuery("w", List.of("vv"),
                List.of("day"), "day", TimeFunction.RANK, null, RankOrder.DESC, Map.of())));
    }

    @Test
    void nullRankedValueGoesLastAndTies() {
        LocalTableRegistry tables2 = new LocalTableRegistry();
        TableDef def = new TableDef("t", List.of(
                new ColumnDef("day", DataType.DATE),
                new ColumnDef("v", DataType.DOUBLE)));
        Map<String, Object> n1 = new LinkedHashMap<>();
        n1.put("day", "d1");
        n1.put("v", null);
        Map<String, Object> n2 = new LinkedHashMap<>();
        n2.put("day", "d2");
        n2.put("v", null);
        List<Map<String, Object>> rows2 = List.of(n1, n2, Map.of("day", "d3", "v", 1.0));
        tables2.register(new LocalTable(def, rows2));
        SemanticRegistry reg2 = new SemanticRegistry(tables2);
        reg2.publish(new PublishRequest("w", null,
                List.of(new DimensionDef("day", "t", "day")),
                List.of(new MetricDef("vv", "t", AggregationType.SUM, "v", null, null))));
        QueryEngine engine2 = new QueryEngine(reg2, tables2);
        QueryResult result = engine2.execute(new MetricQuery("w", List.of("vv"),
                List.of("day"), "day", TimeFunction.RANK, null, RankOrder.DESC, Map.of()));
        log.info("RANK null 输入: 指标值 d1=null,d2=null,d3=1; 判定: 非空第一, null 指标值并列置尾: {}", result.rows());
        assertEquals(1L, row(result, null, "d3").get("vv__rank"));
        assertEquals(2L, row(result, null, "d1").get("vv__rank"));
        assertEquals(2L, row(result, null, "d2").get("vv__rank"));
    }

    @Test
    void versionsKeepHistoryAndDistinguishCaliber() {
        // 全局：总收入 90（10+20+10+0空+20+30）
        QueryResult v1 = engine.execute(MetricQuery.aggregate("v1",
                List.of("revenue", "tax"), List.of()));
        log.info("v1 输入: revenue SUM + tax=revenue*0.1; 结果: {}", v1.rows());
        assertEquals(90.0, v1.rows().get(0).get("revenue"));
        assertEquals(9.0, v1.rows().get(0).get("tax"));

        QueryResult v2 = engine.execute(MetricQuery.aggregate("v2",
                List.of("total_revenue", "tax"), List.of()));
        log.info("v2 输入: 重命名 total_revenue + tax=total_revenue*0.2; 结果: {}", v2.rows());
        assertEquals(90.0, v2.rows().get(0).get("total_revenue"));
        assertEquals(18.0, v2.rows().get(0).get("tax"));

        // 旧名在新版本必须被拒绝（不静默降级）
        SemanticException ex = assertThrows(SemanticException.class,
                () -> engine.execute(MetricQuery.aggregate("v2", List.of("revenue"), List.of())));
        assertEquals(ErrorCode.METRIC_NOT_FOUND, ex.getCode());
        log.info("v2 引用旧名 revenue; 判定: {} - {}", ex.getCode(), ex.getMessage());

        // 同一查询重复执行结论一致
        assertEquals(v1, engine.execute(MetricQuery.aggregate("v1", List.of("revenue", "tax"), List.of())));
        assertNotEquals(v1.rows().get(0).get("tax"), v2.rows().get(0).get("tax"),
                "口径调整后同名 tax 必须能按版本区分");
    }

    private static void assertContainsAll(QueryResult full, List<QueryResult> batches, String label) {
        List<Map<String, Object>> merged = new ArrayList<>();
        batches.forEach(b -> merged.addAll(b.rows()));
        assertEquals(full.rows().size(), merged.size(),
                label + ": 分批行数之和应等于全量行数");
        for (Map<String, Object> r : full.rows()) {
            org.junit.jupiter.api.Assertions.assertTrue(merged.contains(r),
                    label + ": 分批结果合并后必须包含全量行: " + r);
        }
    }

    @Test
    void slidingAndRankBatchesAreConsistentWithFullQuery() {
        // SLIDING：按 region 分两批，结果合并必须等于整段
        QueryResult slidingFull = engine.execute(timeFn(TimeFunction.SLIDING, 2, Map.of()));
        List<QueryResult> slidingBatches = List.of(
                engine.execute(timeFn(TimeFunction.SLIDING, 2, Map.of("region", "east"))),
                engine.execute(timeFn(TimeFunction.SLIDING, 2, Map.of("region", "west"))),
                engine.execute(timeFn(TimeFunction.SLIDING, 2, Map.of("region", "south"))));
        log.info("滑动分批一致性: 全量 {} 行 vs 三批 {} 行",
                slidingFull.rows().size(),
                slidingBatches.stream().mapToInt(b -> b.rows().size()).sum());
        assertContainsAll(slidingFull, slidingBatches, "SLIDING");

        // RANK：分区内名次只取决于本分区全量数据；过滤只裁输出行，名次不变
        MetricQuery rankQuery = new MetricQuery("v1", List.of("revenue"),
                List.of("region", "day"), "day", TimeFunction.RANK, null, RankOrder.DESC, Map.of());
        QueryResult rankFull = engine.execute(rankQuery);
        QueryResult rankEastOnly = engine.execute(new MetricQuery("v1", List.of("revenue"),
                List.of("region", "day"), "day", TimeFunction.RANK, null, RankOrder.DESC,
                Map.of("region", "east")));
        log.info("排名分批一致性: east 批次名次必须与全量中 east 行一致: {}", rankEastOnly.rows());
        for (Map<String, Object> eastRow : rankEastOnly.rows()) {
            org.junit.jupiter.api.Assertions.assertTrue(rankFull.rows().contains(eastRow),
                    "RANK 分批行必须与全量对应行（含名次）完全一致: " + eastRow);
        }
        // 每分区只有单期，名次恒为 1
        assertEquals(1L, rankEastOnly.rows().get(0).get("revenue__rank"));
    }

    private static Map<String, Object> row(QueryResult result, String region, String day) {
        for (Map<String, Object> row : result.rows()) {
            boolean regionOk = region == null || region.equals(row.get("region"));
            if (regionOk && day.equals(row.get("day"))) {
                return row;
            }
        }
        throw new AssertionError("结果中找不到行 region=" + region + ", day=" + day + "，实际: " + result.rows());
    }
}
