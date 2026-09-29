package com.github.highcumontoa.semanticmetricenginejava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.highcumontoa.semanticmetricenginejava.semantic.engine.ModelValidator;
import com.github.highcumontoa.semanticmetricenginejava.semantic.error.ErrorCode;
import com.github.highcumontoa.semanticmetricenginejava.semantic.error.SemanticException;
import com.github.highcumontoa.semanticmetricenginejava.semantic.model.AggType;
import com.github.highcumontoa.semanticmetricenginejava.semantic.model.Algorithm;
import com.github.highcumontoa.semanticmetricenginejava.semantic.model.DataType;
import com.github.highcumontoa.semanticmetricenginejava.semantic.model.DateGranularity;
import com.github.highcumontoa.semanticmetricenginejava.semantic.model.DimensionDef;
import com.github.highcumontoa.semanticmetricenginejava.semantic.model.MetricDef;
import com.github.highcumontoa.semanticmetricenginejava.semantic.model.ModelVersion;
import com.github.highcumontoa.semanticmetricenginejava.semantic.model.TableDef;

/**
 * 非法定义必须被拒绝且原因码可区分：字段缺失、类型不符、聚合非法、窗口非法、
 * 派生来源不存在、循环定义。
 */
class ModelValidatorTest {

    private static final Logger LOG = LoggerFactory.getLogger(ModelValidatorTest.class);

    private final ModelValidator validator = new ModelValidator();

    private SemanticException reject(String caseName, ModelVersion model, ErrorCode expected) {
        SemanticException ex = assertThrows(SemanticException.class,
                () -> validator.validate(model),
                "用例[" + caseName + "]必须被拒绝");
        LOG.info("[模型校验] 用例={} 输入定义=见测试代码 拒绝码={} 期望码={} 判定依据={}",
                caseName, ex.getCode(), expected, ex.getMessage());
        assertEquals(expected, ex.getCode(), "用例[" + caseName + "]原因码不符");
        return ex;
    }

    private ModelVersion with(MetricDef metric) {
        return new ModelVersion("t", List.of(TestFixtures.salesDef()), List.of(), List.of(metric), "");
    }

    private ModelVersion withMetrics(List<MetricDef> metrics) {
        return new ModelVersion("t", List.of(TestFixtures.salesDef()), List.of(), metrics, "");
    }

    @Test
    void dimension_missingColumn_rejected() {
        ModelVersion m = new ModelVersion("t", List.of(TestFixtures.salesDef()),
                List.of(new DimensionDef("d", "sales", "not_exist", DataType.STRING, null)),
                List.of(), "");
        reject("维度引用不存在字段", m, ErrorCode.DEF_COLUMN_NOT_FOUND);
    }

    @Test
    void dimension_typeMismatch_rejected() {
        ModelVersion m = new ModelVersion("t", List.of(TestFixtures.salesDef()),
                List.of(new DimensionDef("d", "sales", "region", DataType.LONG, null)),
                List.of(), "");
        reject("维度类型与物理字段不符", m, ErrorCode.DEF_COLUMN_TYPE_MISMATCH);
    }

    @Test
    void dateDimension_withoutGranularity_rejected() {
        ModelVersion m = new ModelVersion("t", List.of(TestFixtures.salesDef()),
                List.of(new DimensionDef("d", "sales", "tx_date", DataType.DATE, null)),
                List.of(), "");
        reject("日期维度缺粒度", m, ErrorCode.DEF_GRANULARITY_INVALID);
    }

    @Test
    void metric_missingColumn_rejected() {
        reject("指标引用不存在字段",
                with(new MetricDef("x", "sales", "no_col", AggType.SUM,
                        Algorithm.NONE, null, null)),
                ErrorCode.DEF_COLUMN_NOT_FOUND);
    }

    @Test
    void sum_onStringColumn_rejected() {
        reject("SUM 作用于 STRING 字段",
                with(new MetricDef("x", "sales", "region", AggType.SUM,
                        Algorithm.NONE, null, null)),
                ErrorCode.DEF_COLUMN_TYPE_MISMATCH);
    }

    @Test
    void rolling_windowNotPositive_rejected() {
        reject("ROLLING 窗口为0",
                with(new MetricDef("x", "sales", "amount", AggType.SUM,
                        Algorithm.ROLLING, 0, null)),
                ErrorCode.DEF_ROLLING_WINDOW_INVALID);
    }

    @Test
    void nonRolling_withWindowSize_rejected() {
        reject("CUMULATIVE 携带 windowSize",
                with(new MetricDef("x", "sales", "amount", AggType.SUM,
                        Algorithm.CUMULATIVE, 3, null)),
                ErrorCode.DEF_ROLLING_WINDOW_INVALID);
    }

    @Test
    void derived_sourceMissing_rejected() {
        reject("派生指标来源不存在",
                with(new MetricDef("x", null, null, null, null, null, "ghost")),
                ErrorCode.DEF_METRIC_SOURCE_NOT_FOUND);
    }

    @Test
    void derived_circularChain_rejected() {
        // a -> b -> a 构成环
        MetricDef a = new MetricDef("a", null, null, null, null, null, "b");
        MetricDef b = new MetricDef("b", null, null, null, null, null, "a");
        reject("派生链 a->b->a 循环", withMetrics(List.of(a, b)),
                ErrorCode.DEF_CIRCULAR_REFERENCE);
    }

    @Test
    void duplicate_metricName_rejected() {
        MetricDef m1 = new MetricDef("dup", "sales", "qty", AggType.SUM,
                Algorithm.NONE, null, null);
        MetricDef m2 = new MetricDef("dup", "sales", "amount", AggType.SUM,
                Algorithm.NONE, null, null);
        reject("指标名重复", withMetrics(List.of(m1, m2)), ErrorCode.DEF_DUPLICATE_NAME);
    }

    @Test
    void validSeedModels_passValidation() {
        var validator = new ModelValidator();
        com.github.highcumontoa.semanticmetricenginejava.semantic.seed.SeedModels.all()
                .forEach(model -> {
                    validator.validate(model);
                    LOG.info("[模型校验] 预置版本 {} 校验通过", model.version());
                });
    }
}
