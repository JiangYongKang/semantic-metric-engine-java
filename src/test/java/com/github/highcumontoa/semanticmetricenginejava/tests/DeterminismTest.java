package com.github.highcumontoa.semanticmetricenginejava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
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
 * 确定性：同一条查询在源行序被打乱、分批喂入、重复执行时结果（含行序）完全一致。
 * 分批语义通过聚合器 merge 保证；这里从端到端验证行序无关性与可重复性。
 */
class DeterminismTest {

    private static final Logger LOG = LoggerFactory.getLogger(DeterminismTest.class);

    private QueryService serviceFor(List<com.github.highcumontoa.semanticmetricenginejava.semantic.data.Row> rows) {
        ModelRegistry registry = new ModelRegistry(new ModelValidator());
        SeedModels.all().forEach(registry::register);
        return new QueryService(registry, TestFixtures.dataSet(rows));
    }

    private void assertSame(QueryResult a, QueryResult b, String label) {
        assertEquals(a.columns(), b.columns(), label + " 列不一致");
        assertEquals(a.rows(), b.rows(), label + " 结果行不一致");
        LOG.info("[确定性] {} 通过：{} 行，列={}", label, a.rows().size(), a.columns());
    }

    @Test
    void plainAggregation_rowOrderIndependent_andRepeatable() {
        var base = TestFixtures.salesRows();
        QueryRequest request = new QueryRequest(
                List.of("gmv", "order_count", "distinct_orders", "avg_amount"),
                List.of("region", "channel"), "v1");

        QueryResult canonical = serviceFor(base).query(request);
        for (long seed = 1L; seed <= 5L; seed++) {
            QueryResult shuffled = serviceFor(TestFixtures.shuffled(base, seed)).query(request);
            assertSame(canonical, shuffled, "普通聚合 行序打乱 seed=" + seed);
        }
        QueryResult repeat = serviceFor(base).query(request);
        assertSame(canonical, repeat, "普通聚合 重复执行");
    }

    @Test
    void window_rowOrderIndependent() {
        var base = TestFixtures.salesRows();
        QueryRequest request = new QueryRequest(
                List.of("gmv_cum", "gmv_roll3"), List.of("tx_date_month"), "v1");
        QueryResult canonical = serviceFor(base).query(request);
        for (long seed = 7L; seed <= 10L; seed++) {
            assertSame(canonical,
                    serviceFor(TestFixtures.shuffled(base, seed)).query(request),
                    "窗口算法 行序打乱 seed=" + seed);
        }
    }

    @Test
    void rank_rowOrderIndependent_tiesStable() {
        var base = TestFixtures.salesRows();
        QueryRequest request = new QueryRequest(
                List.of("amount_rank"), List.of("region"), "v1");
        QueryResult canonical = serviceFor(base).query(request);
        canonical.rows().forEach(r -> LOG.info("[确定性] 排名基线行={}", r));
        for (long seed = 11L; seed <= 14L; seed++) {
            assertSame(canonical,
                    serviceFor(TestFixtures.shuffled(base, seed)).query(request),
                    "排名 行序打乱 seed=" + seed);
        }
    }
}
