package com.github.highcumontoa.semanticmetricenginejava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.highcumontoa.semanticmetricenginejava.semantic.data.Row;
import com.github.highcumontoa.semanticmetricenginejava.semantic.engine.ModelValidator;
import com.github.highcumontoa.semanticmetricenginejava.semantic.error.ErrorCode;
import com.github.highcumontoa.semanticmetricenginejava.semantic.error.SemanticException;
import com.github.highcumontoa.semanticmetricenginejava.semantic.query.QueryRequest;
import com.github.highcumontoa.semanticmetricenginejava.semantic.query.QueryResult;
import com.github.highcumontoa.semanticmetricenginejava.semantic.query.QueryService;
import com.github.highcumontoa.semanticmetricenginejava.semantic.seed.SeedModels;
import com.github.highcumontoa.semanticmetricenginejava.semantic.version.ModelRegistry;

/** 补充边界：空时间值处理、窗口/累计前期差异、版本快照不可覆盖、无维度全局聚合空表。 */
class EdgeCaseTest {

    private static final Logger LOG = LoggerFactory.getLogger(EdgeCaseTest.class);

    private QueryService serviceForRows(List<Row> rows) {
        ModelRegistry registry = new ModelRegistry(new ModelValidator());
        SeedModels.all().forEach(registry::register);
        return new QueryService(registry, TestFixtures.dataSet(rows));
    }

    @Test
    void nullDateRow_excludedFromWindow_butKeptInPlainGrouping() {
        // 构造：两行有日期 + 一行 tx_date 为 null
        List<Row> rows = List.of(
                TestFixtures.row("East", "Online", "2026-01-05", 100.0, 1L, "X1"),
                TestFixtures.row("East", "Online", "2026-02-05", 50.0, 1L, "X2"),
                TestFixtures.row("East", "Online", null, 999.0, 1L, "X3"));
        QueryService svc = serviceForRows(rows);

        QueryResult window = svc.query(new QueryRequest(
                List.of("gmv_cum"), List.of("tx_date_month"), "v1"));
        LOG.info("[边界] 空日期行 累计查询结果={}（依据: 空时间值无法定位，不产生桶）", window.rows());
        assertEquals(2, window.rows().size());
        assertEquals(100.0, window.rows().get(0).get("gmv_cum"));
        assertEquals(150.0, window.rows().get(1).get("gmv_cum"));

        // 普通按区域分组：空日期行的金额必须计入（仅时间算法排除它）
        QueryResult plain = svc.query(new QueryRequest(
                List.of("gmv"), List.of("region"), "v1"));
        LOG.info("[边界] 同一数据 普通聚合结果={}（依据: 仅时间轴算法排除空日期行）", plain.rows());
        assertEquals(1149.0, plain.rows().get(0).get("gmv"));
    }

    @Test
    void rollingVsCumulative_differAfterWindowSize() {
        List<Row> rows = List.of(
                TestFixtures.row("East", "Online", "2026-01-01", 10.0, 1L, "A"),
                TestFixtures.row("East", "Online", "2026-02-01", 20.0, 1L, "B"),
                TestFixtures.row("East", "Online", "2026-03-01", 30.0, 1L, "C"),
                TestFixtures.row("East", "Online", "2026-04-01", 40.0, 1L, "D"));
        QueryService svc = serviceForRows(rows);
        QueryResult cum = svc.query(new QueryRequest(
                List.of("gmv_cum"), List.of("tx_date_month"), "v1"));
        QueryResult roll = svc.query(new QueryRequest(
                List.of("gmv_roll3"), List.of("tx_date_month"), "v1"));
        LOG.info("[边界] 累计={}", cum.rows());
        LOG.info("[边界] 滑窗3={}（第4桶只含 02+03+04=90，累计为 100）", roll.rows());
        assertEquals(100.0, cum.rows().get(3).get("gmv_cum"));
        assertEquals(90.0, roll.rows().get(3).get("gmv_roll3"));
        // 前 3 个桶两者一致（窗口尚未滑出）
        for (int i = 0; i < 3; i++) {
            assertEquals(cum.rows().get(i).get("gmv_cum"), roll.rows().get(i).get("gmv_roll3"));
        }
    }

    @Test
    void versionSnapshot_isImmutable_reregistrationRejected() {
        ModelRegistry registry = new ModelRegistry(new ModelValidator());
        registry.register(SeedModels.v1());
        SemanticException ex = assertThrows(SemanticException.class,
                () -> registry.register(SeedModels.v1()));
        LOG.info("[边界] 重复注册 v1 => 拒绝码={} 依据={}", ex.getCode(), ex.getMessage());
        assertEquals(ErrorCode.DEF_DUPLICATE_NAME, ex.getCode());
    }

    @Test
    void emptyTable_globalAggregation_returnsNullsNotZero() {
        QueryService svc = serviceForRows(List.of());
        QueryResult r = svc.query(new QueryRequest(
                List.of("gmv", "avg_amount", "order_count"), List.of(), "v1"));
        LOG.info("[边界] 空表全局聚合={}（SUM/AVG 为 null，COUNT(*) 为 0）", r.rows());
        assertEquals(1, r.rows().size());
        assertNull(r.rows().get(0).get("gmv"));
        assertNull(r.rows().get(0).get("avg_amount"));
        assertEquals(0L, r.rows().get(0).get("order_count"));
    }
}
