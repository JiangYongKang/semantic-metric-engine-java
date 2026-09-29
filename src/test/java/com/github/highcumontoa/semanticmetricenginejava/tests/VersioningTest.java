package com.github.highcumontoa.semanticmetricenginejava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.List;
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
 * 版本兼容：
 * 1) 旧版本结论可复现：v1 的 gmv 在注册 v2 后结果不变；
 * 2) 口径按版本区分：order_count v1=COUNT(*)=12，v2=COUNT_DISTINCT(order_id)=11（O1006 重复）；
 * 3) 派生指标别名等价于根指标：v2 的 gmv_alias == sales_amount。
 */
class VersioningTest {

    private static final Logger LOG = LoggerFactory.getLogger(VersioningTest.class);
    private static QueryService service;

    @BeforeAll
    static void setUp() {
        ModelRegistry registry = new ModelRegistry(new ModelValidator());
        SeedModels.all().forEach(registry::register);
        service = new QueryService(registry, TestFixtures.dataSet(TestFixtures.salesRows()));
    }

    @Test
    void v1Result_reproducible_afterV2Registered() {
        QueryResult r1 = service.query(new QueryRequest(List.of("gmv"), List.of(), "v1"));
        QueryResult r1Again = service.query(new QueryRequest(List.of("gmv"), List.of(), "v1"));
        LOG.info("[版本兼容] v1 全局 gmv={} 重复执行={}", r1.rows(), r1Again.rows());
        assertEquals(r1.rows(), r1Again.rows());
        // 总金额 1731.50（null 金额行忽略）
        assertEquals(1731.50, r1.rows().get(0).get("gmv"));
        assertEquals("v1", r1.version());
    }

    @Test
    void renamedMetric_distinguishedByVersion() {
        QueryResult oldName = service.query(
                new QueryRequest(List.of("gmv"), List.of("region"), "v1"));
        QueryResult newName = service.query(
                new QueryRequest(List.of("sales_amount"), List.of("region"), "v2"));
        LOG.info("[版本兼容] v1.gmv 按区域={}", oldName.rows());
        LOG.info("[版本兼容] v2.sales_amount 按区域={}", newName.rows());
        // 口径未变，仅改名：去掉列名后各行值一致
        assertEquals(rowsWithoutName(oldName, "gmv"), rowsWithoutName(newName, "sales_amount"));
    }

    @Test
    void calibreChange_orderCount_differsByVersion() {
        QueryResult v1 = service.query(new QueryRequest(List.of("order_count"), List.of(), "v1"));
        QueryResult v2 = service.query(new QueryRequest(List.of("order_count"), List.of(), "v2"));
        LOG.info("[版本兼容] order_count v1(COUNT*)={} vs v2(COUNT_DISTINCT)={}",
                v1.rows(), v2.rows());
        assertEquals(12L, v1.rows().get(0).get("order_count"));
        assertEquals(11L, v2.rows().get(0).get("order_count"));
        assertNotEquals(v1.rows(), v2.rows(), "口径调整必须能按版本区分，不能静默沿用旧口径");
    }

    @Test
    void derivedAlias_equalsRootMetric() {
        QueryResult alias = service.query(
                new QueryRequest(List.of("gmv_alias"), List.of("region"), "v2"));
        QueryResult root = service.query(
                new QueryRequest(List.of("sales_amount"), List.of("region"), "v2"));
        LOG.info("[版本兼容] 派生别名 gmv_alias={} 根指标 sales_amount={}", alias.rows(), root.rows());
        assertEquals(rowsWithoutName(root, "sales_amount"), rowsWithoutName(alias, "gmv_alias"));
        assertEquals("gmv_alias", alias.columns().get(1), "派生指标输出列使用别名");
    }

    private static List<java.util.Map<String, Object>> rowsWithoutName(QueryResult r, String metric) {
        return r.rows().stream()
                .map(m -> {
                    var copy = new java.util.LinkedHashMap<>(m);
                    copy.remove(metric);
                    return (java.util.Map<String, Object>) copy;
                })
                .toList();
    }
}
