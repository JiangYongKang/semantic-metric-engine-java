package com.github.highcumontoa.semanticmetricenginejava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.highcumontoa.semanticmetricenginejava.semantic.data.Aggregator;
import com.github.highcumontoa.semanticmetricenginejava.semantic.model.AggType;

/**
 * 聚合口径边界：空值忽略、COUNT(*)、分批合并与顺序无关、AVG 整除与舍入。
 */
class AggregatorTest {

    private static final Logger LOG = LoggerFactory.getLogger(AggregatorTest.class);

    private void logCase(String name, Object input, Object expect, Object actual) {
        LOG.info("[Aggregator] 用例={} 输入={} 期望={} 实际={} 判定依据={}",
                name, input, expect, actual,
                expect.equals(actual) ? "精确值一致(确定性口径)" : "不一致(失败)");
    }

    @Test
    void sum_ignoresNull_and_isOrderIndependent() {
        List<Object> values = java.util.Arrays.asList(100.0, null, 50.25, null, 200L);
        Aggregator oneShot = new Aggregator(AggType.SUM);
        values.forEach(oneShot::accumulate);

        // 分两批、顺序颠倒，再合并
        Aggregator a = new Aggregator(AggType.SUM);
        a.accumulate(200L);
        Aggregator b = new Aggregator(AggType.SUM);
        b.accumulate(100.0);
        b.accumulate(null);
        b.accumulate(50.25);
        b.accumulate(null);
        a.merge(b);

        logCase("SUM 忽略null+分批合并", values, 350.25, a.result());
        assertEquals(350.25, a.result());
        assertEquals(oneShot.result(), a.result(), "分批合并必须与一次性聚合一致");
    }

    @Test
    void countStar_countsNullRows_countExpr_skipsNull() {
        Aggregator star = new Aggregator(AggType.COUNT, true);
        Aggregator expr = new Aggregator(AggType.COUNT, false);
        for (Object v : new Object[]{1L, null, 2L, null}) {
            star.accumulate(v);
            expr.accumulate(v);
        }
        logCase("COUNT(*)", "含2个null的4行", 4L, star.result());
        logCase("COUNT(expr)", "含2个null的4行", 2L, expr.result());
        assertEquals(4L, star.result());
        assertEquals(2L, expr.result());
    }

    @Test
    void countDistinct_mergesAcrossBatches() {
        Aggregator a = new Aggregator(AggType.COUNT_DISTINCT);
        a.accumulate("O1");
        a.accumulate("O2");
        Aggregator b = new Aggregator(AggType.COUNT_DISTINCT);
        b.accumulate("O2");
        b.accumulate("O3");
        a.merge(b);
        logCase("COUNT_DISTINCT 分批合并", "[O1,O2]+[O2,O3]", 3L, a.result());
        assertEquals(3L, a.result());
    }

    @Test
    void avg_allNull_isNull_integralDivisible_isLong_otherwiseHalfUp6() {
        Aggregator empty = new Aggregator(AggType.AVG);
        empty.accumulate(null);
        assertNull(empty.result(), "全空 AVG 必须为 null，而不是 0");

        Aggregator integral = new Aggregator(AggType.AVG);
        integral.accumulate(10L);
        integral.accumulate(20L);
        logCase("AVG 整除", "[10,20]", 15L, integral.result());
        assertEquals(15L, integral.result());

        Aggregator frac = new Aggregator(AggType.AVG);
        frac.accumulate(1L);
        frac.accumulate(2L);
        // 1.5 -> HALF_UP 6 位 -> 1.5
        logCase("AVG 小数", "[1,2]", 1.5, frac.result());
        assertEquals(1.5, frac.result());

        Aggregator rounded = new Aggregator(AggType.AVG);
        rounded.accumulate(1L);
        rounded.accumulate(1L);
        rounded.accumulate(1L);
        rounded.accumulate(2L);
        // 5/4 = 1.25
        assertEquals(1.25, rounded.result());
    }

    @Test
    void minMax_ignoreNull_andOutputLongForIntegralInput() {
        Aggregator min = new Aggregator(AggType.MIN);
        Aggregator max = new Aggregator(AggType.MAX);
        for (Object v : new Object[]{3L, null, 1L, 9L}) {
            min.accumulate(v);
            max.accumulate(v);
        }
        logCase("MIN 忽略null", "[3,null,1,9]", 1L, min.result());
        logCase("MAX 忽略null", "[3,null,1,9]", 9L, max.result());
        assertEquals(1L, min.result());
        assertEquals(9L, max.result());
    }
}
