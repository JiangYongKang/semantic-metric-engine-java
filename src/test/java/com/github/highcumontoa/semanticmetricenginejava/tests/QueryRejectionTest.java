package com.github.highcumontoa.semanticmetricenginejava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.highcumontoa.semanticmetricenginejava.semantic.engine.ModelValidator;
import com.github.highcumontoa.semanticmetricenginejava.semantic.error.ErrorCode;
import com.github.highcumontoa.semanticmetricenginejava.semantic.error.SemanticException;
import com.github.highcumontoa.semanticmetricenginejava.semantic.query.QueryRequest;
import com.github.highcumontoa.semanticmetricenginejava.semantic.query.QueryService;
import com.github.highcumontoa.semanticmetricenginejava.semantic.seed.SeedModels;
import com.github.highcumontoa.semanticmetricenginejava.semantic.version.ModelRegistry;

/**
 * 查询期非法请求必须被拒绝且原因码可区分（禁止静默返回结果）：
 * 不存在的指标/维度、空指标、算法混用、窗口缺日期维度、未知版本。
 */
class QueryRejectionTest {

    private static final Logger LOG = LoggerFactory.getLogger(QueryRejectionTest.class);
    private static QueryService service;

    @BeforeAll
    static void setUp() {
        ModelRegistry registry = new ModelRegistry(new ModelValidator());
        SeedModels.all().forEach(registry::register);
        service = new QueryService(registry, TestFixtures.dataSet(TestFixtures.salesRows()));
    }

    private void reject(String caseName, QueryRequest request, ErrorCode expected) {
        SemanticException ex = assertThrows(SemanticException.class,
                () -> service.query(request));
        LOG.info("[查询拒绝] 用例={} 请求=metrics{},dimensions={},version={} 拒绝码={} 判定依据={}",
                caseName, request.metrics(), request.dimensions(), request.version(),
                ex.getCode(), ex.getMessage());
        assertEquals(expected, ex.getCode(), "用例[" + caseName + "]原因码不符");
    }

    @Test
    void unknownMetric_rejected() {
        reject("引用不存在指标",
                new QueryRequest(List.of("not_a_metric"), List.of("region"), "v1"),
                ErrorCode.Q_METRIC_NOT_FOUND);
    }

    @Test
    void unknownDimension_rejected() {
        reject("引用不存在维度",
                new QueryRequest(List.of("gmv"), List.of("not_a_dim"), "v1"),
                ErrorCode.Q_DIMENSION_NOT_FOUND);
    }

    @Test
    void emptyMetrics_rejected() {
        reject("空指标列表",
                new QueryRequest(List.of(), List.of("region"), "v1"),
                ErrorCode.Q_EMPTY_METRICS);
    }

    @Test
    void renamedMetric_oldNameOnlyInV1_v2Rejects() {
        // gmv 在 v1 存在，v2 被重命名为 sales_amount；查 v2 用旧名必须拒绝，不能静默兼容
        reject("v2 引用已重命名旧指标 gmv",
                new QueryRequest(List.of("gmv"), List.of(), "v2"),
                ErrorCode.Q_METRIC_NOT_FOUND);
    }

    @Test
    void deletedMetric_v2Rejected() {
        reject("v2 引用已删除指标 legacy_metric",
                new QueryRequest(List.of("legacy_metric"), List.of(), "v2"),
                ErrorCode.Q_METRIC_NOT_FOUND);
    }

    @Test
    void rankMixedWithWindow_rejected() {
        reject("RANK 与 CUMULATIVE 混用",
                new QueryRequest(List.of("amount_rank", "gmv_cum"),
                        List.of("tx_date_month"), "v1"),
                ErrorCode.Q_ALGORITHM_MIX_CONFLICT);
    }

    @Test
    void windowWithoutDateDimension_rejected() {
        reject("CUMULATIVE 未给日期维度",
                new QueryRequest(List.of("gmv_cum"), List.of("region"), "v1"),
                ErrorCode.Q_ALGORITHM_REQUIRES_DATE_DIM);
    }

    @Test
    void unknownVersion_rejected() {
        reject("引用未知版本",
                new QueryRequest(List.of("gmv"), List.of(), "v99"),
                ErrorCode.Q_METRIC_NOT_FOUND);
    }
}
