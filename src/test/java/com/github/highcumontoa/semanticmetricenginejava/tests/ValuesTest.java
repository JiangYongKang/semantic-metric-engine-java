package com.github.highcumontoa.semanticmetricenginejava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.highcumontoa.semanticmetricenginejava.semantic.data.Values;
import com.github.highcumontoa.semanticmetricenginejava.semantic.error.ErrorCode;
import com.github.highcumontoa.semanticmetricenginejava.semantic.error.SemanticException;
import com.github.highcumontoa.semanticmetricenginejava.semantic.model.DataType;
import com.github.highcumontoa.semanticmetricenginejava.semantic.model.DateGranularity;

/** 值解析、日期分桶、nulls-last 比较的稳定口径。 */
class ValuesTest {

    private static final Logger LOG = LoggerFactory.getLogger(ValuesTest.class);

    @Test
    void parse_emptyAndLiteralNull_becomeNull() {
        for (String text : new String[]{"", "  ", "null", "NULL"}) {
            Object v = Values.parse(text, DataType.LONG);
            LOG.info("[Values] 输入='{}' 类型=LONG => {}（空值口径）", text, v);
            assertNull(v);
        }
        assertEquals(42L, Values.parse("42", DataType.LONG));
        assertEquals(1.5, Values.parse("1.5", DataType.DOUBLE));
        assertEquals(LocalDate.of(2026, 1, 5), Values.parse("2026-01-05", DataType.DATE));
    }

    @Test
    void parse_badNumber_rejectedWithReason() {
        SemanticException ex = assertThrows(SemanticException.class,
                () -> Values.parse("abc", DataType.LONG));
        LOG.info("[Values] 非法输入='abc' 类型=LONG => 拒绝码={} 依据={}",
                ex.getCode(), ex.getMessage());
        assertEquals(ErrorCode.DATA_CSV_BAD_VALUE, ex.getCode());
    }

    @Test
    void truncate_granularity() {
        LocalDate d = LocalDate.of(2026, 3, 15);
        assertEquals(LocalDate.of(2026, 3, 15), Values.truncate(d, DateGranularity.DAY));
        assertEquals(LocalDate.of(2026, 3, 1), Values.truncate(d, DateGranularity.MONTH));
        assertEquals(LocalDate.of(2026, 1, 1), Values.truncate(d, DateGranularity.YEAR));
        assertNull(Values.truncate(null, DateGranularity.MONTH));
        LOG.info("[Values] 日期={} 截断 => DAY={} MONTH={} YEAR={}", d,
                Values.truncate(d, DateGranularity.DAY),
                Values.truncate(d, DateGranularity.MONTH),
                Values.truncate(d, DateGranularity.YEAR));
    }

    @Test
    void compare_nullsLast_ascending() {
        assertTrue(Values.compareNullsLast(null, "a") > 0);
        assertTrue(Values.compareNullsLast("a", null) < 0);
        assertTrue(Values.compareNullsLast(1L, 2L) < 0);
        assertEquals(0, Values.compareNullsLast(null, null));
        LOG.info("[Values] nulls-last 比较: null 永远排在最后，保证确定性输出");
    }
}
