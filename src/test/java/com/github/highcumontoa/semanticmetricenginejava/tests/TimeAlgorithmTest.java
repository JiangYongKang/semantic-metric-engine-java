package com.github.highcumontoa.semanticmetricenginejava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.highcumontoa.semanticmetricenginejava.semantic.engine.ModelValidator;
import com.github.highcumontoa.semanticmetricenginejava.semantic.query.QueryRequest;
import com.github.highcumontoa.semanticmetricenginejava.semantic.query.QueryResult;
import com.github.highcumontoa.semanticmetricenginejava.semantic.query.QueryService;
import com.github.highcumontoa.semanticmetricenginejava.semantic.seed.SeedModels;
import com.github.highcumontoa.semanticmetricenginejava.semantic.version.ModelRegistry;

/**
 * 时间算法口径（按月分桶，数据见 sales.csv）：
 * 月度 GMV：01=350.50，02=500.25（其中一行 amount 为 null 被忽略），
 * 03=380.50，04=500.25。
 */
class TimeAlgorithmTest {

    private static final Logger LOG = LoggerFactory.getLogger(TimeAlgorithmTest.class);
    private static QueryService service;

    @BeforeAll
    static void setUp() {
        ModelRegistry registry = new ModelRegistry(new ModelValidator());
        SeedModels.all().forEach(registry::register);
        service = new QueryService(registry, TestFixtures.dataSet(TestFixtures.salesRows()));
    }

    private void logResult(String name, QueryResult result) {
        LOG.info("[时间算法] 用例={} 输出列={}", name, result.columns());
        result.rows().forEach(r -> LOG.info("[时间算法]   行={}", r));
    }

    @Test
    void cumulative_monthlyGmv() {
        QueryResult r = service.query(new QueryRequest(
                List.of("gmv_cum"), List.of("tx_date_month"), "v1"));
        logResult("累计 GMV 按月", r);
        List<Map<String, Object>> rows = r.rows();
        assertEquals(4, rows.size(), "4 个有数据的月份桶；不补缺失桶");
        assertEquals(350.50, rows.get(0).get("gmv_cum"));
        assertEquals(850.75, rows.get(1).get("gmv_cum"));
        assertEquals(1231.25, rows.get(2).get("gmv_cum"));
        assertEquals(1731.50, rows.get(3).get("gmv_cum"));
        assertEquals(LocalDate.of(2026, 1, 1), rows.get(0).get("tx_date_month"));
    }

    @Test
    void rolling3_monthlyGmv_missingBucketsDoNotCount() {
        QueryResult r = service.query(new QueryRequest(
                List.of("gmv_roll3"), List.of("tx_date_month"), "v1"));
        logResult("滑动3桶 GMV 按月", r);
        List<Map<String, Object>> rows = r.rows();
        // 窗口按“实际存在的桶”计数，缺月不补零、不占位
        assertEquals(350.50, rows.get(0).get("gmv_roll3"));
        assertEquals(850.75, rows.get(1).get("gmv_roll3"));
        assertEquals(1231.25, rows.get(2).get("gmv_roll3"));       // 01+02+03
        assertEquals(1381.00, rows.get(3).get("gmv_roll3"));       // 02+03+04
    }

    @Test
    void rank_competitionRanking_withTiesAndNullsLast() {
        // 不设日期维度：全表一个分区，按区域汇总后竞争排名
        // East=801.00 > South=680.50 > North=200.00(含null行) > 空region=50.00
        QueryResult r = service.query(new QueryRequest(
                List.of("amount_rank"), List.of("region"), "v1"));
        logResult("区域 GMV 竞争排名", r);
        List<Map<String, Object>> rows = r.rows();
        // 输出按 region 升序 nulls-last：East, North, South, null
        assertEquals("East", rows.get(0).get("region"));
        assertEquals(1L, rows.get(0).get("amount_rank"));
        assertEquals(3L, rows.get(1).get("amount_rank"));
        assertEquals(2L, rows.get(2).get("amount_rank"));
        assertNull(rows.get(3).get("region"));
        assertEquals(4L, rows.get(3).get("amount_rank"));
    }

    @Test
    void rank_tiedMonthlyValues_shareRankAndSkip() {
        // 按月排名：02 与 04 均为 500.25 并列第 1，03=380.50 第 3，01=350.50 第 4
        QueryResult r = service.query(new QueryRequest(
                List.of("amount_rank"), List.of("tx_date_month"), "v1"));
        logResult("月度 GMV 排名(含并列)", r);
        List<Map<String, Object>> rows = r.rows();
        assertEquals(4L, rows.get(0).get("amount_rank")); // 01
        assertEquals(1L, rows.get(1).get("amount_rank")); // 02
        assertEquals(3L, rows.get(2).get("amount_rank")); // 03
        assertEquals(1L, rows.get(3).get("amount_rank")); // 04 与 02 并列
    }
}
