package com.github.highcumontoa.semanticmetricenginejava.tests;

import com.github.highcumontoa.semanticmetricenginejava.engine.Aggregators;
import com.github.highcumontoa.semanticmetricenginejava.model.ColumnDef;
import com.github.highcumontoa.semanticmetricenginejava.model.DataType;
import com.github.highcumontoa.semanticmetricenginejava.model.ErrorCode;
import com.github.highcumontoa.semanticmetricenginejava.model.SemanticException;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 聚合原语边界：空值跳过规则、全空输入结果、缺列与类型不匹配必须可区分地拒绝。
 */
class AggregatorsTest {

    private static final Logger log = LoggerFactory.getLogger(AggregatorsTest.class);

    private final ColumnDef amount = new ColumnDef("amount", DataType.DOUBLE);
    private final ColumnDef qty = new ColumnDef("quantity", DataType.LONG);
    private final ColumnDef region = new ColumnDef("region", DataType.STRING);

    private static Map<String, Object> row(Object value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("amount", value);
        return m;
    }

    @Test
    void nullValuesAreSkippedButMissingKeyIsRejected() {
        List<Map<String, Object>> rows = List.of(row(10.0), row(null));
        log.info("输入: {}; 判定依据: 键存在且为 null -> 跳过；键缺失 -> FIELD_VALUE_MISSING", rows);

        assertEquals(10.0, Aggregators.aggregate(com.github.highcumontoa.semanticmetricenginejava.model.AggregationType.SUM, amount, rows));
        assertEquals(1L, Aggregators.aggregate(com.github.highcumontoa.semanticmetricenginejava.model.AggregationType.COUNT, amount, rows));
        assertEquals(10.0, Aggregators.aggregate(com.github.highcumontoa.semanticmetricenginejava.model.AggregationType.AVG, amount, rows));

        Map<String, Object> missing = new LinkedHashMap<>();
        SemanticException ex = assertThrows(SemanticException.class,
                () -> Aggregators.aggregate(com.github.highcumontoa.semanticmetricenginejava.model.AggregationType.SUM,
                        amount, List.of(missing)));
        log.info("缺列输入: {}; 判定: {} - {}", missing, ex.getCode(), ex.getMessage());
        assertEquals(ErrorCode.FIELD_VALUE_MISSING, ex.getCode());
    }

    @Test
    void allNullInputsHaveStableResults() {
        List<Map<String, Object>> rows = List.of(row(null), row(null));
        log.info("输入: 两行 amount=null; 判定依据: 无任何非空输入时 SUM/AVG/MIN/MAX=null, COUNT=0");
        assertNull(Aggregators.aggregate(com.github.highcumontoa.semanticmetricenginejava.model.AggregationType.SUM, amount, rows));
        assertEquals(0L, Aggregators.aggregate(com.github.highcumontoa.semanticmetricenginejava.model.AggregationType.COUNT, amount, rows));
        assertNull(Aggregators.aggregate(com.github.highcumontoa.semanticmetricenginejava.model.AggregationType.AVG, amount, rows));
        assertNull(Aggregators.aggregate(com.github.highcumontoa.semanticmetricenginejava.model.AggregationType.MIN, amount, rows));
        assertNull(Aggregators.aggregate(com.github.highcumontoa.semanticmetricenginejava.model.AggregationType.MAX, amount, rows));
    }

    @Test
    void typeMismatchIsRejectedNotCoerced() {
        List<Map<String, Object>> rows = List.of(row("not-a-number"));
        log.info("输入: DOUBLE 列收到字符串 {}; 判定依据: 不做静默类型转换", rows);
        SemanticException ex = assertThrows(SemanticException.class,
                () -> Aggregators.aggregate(com.github.highcumontoa.semanticmetricenginejava.model.AggregationType.SUM, amount, rows));
        assertEquals(ErrorCode.FIELD_VALUE_MISSING, ex.getCode());
    }

    @Test
    void countDistinctAndMinMax() {
        List<Map<String, Object>> rows = List.of(
                Map.of("quantity", 3L), Map.of("quantity", 3L), Map.of("quantity", 7L));
        log.info("输入: quantity=[3,3,7]; 判定: COUNT_DISTINCT=2 MIN=3 MAX=7");
        assertEquals(2L, Aggregators.aggregate(com.github.highcumontoa.semanticmetricenginejava.model.AggregationType.COUNT_DISTINCT, qty, rows));
        assertEquals(3L, Aggregators.aggregate(com.github.highcumontoa.semanticmetricenginejava.model.AggregationType.MIN, qty, rows));
        assertEquals(7L, Aggregators.aggregate(com.github.highcumontoa.semanticmetricenginejava.model.AggregationType.MAX, qty, rows));

        List<Map<String, Object>> regions = List.of(
                Map.of("region", "west"), Map.of("region", "east"), Map.of("region", "east"));
        assertEquals("east", Aggregators.aggregate(com.github.highcumontoa.semanticmetricenginejava.model.AggregationType.MIN, region, regions));
    }
}
