package com.github.highcumontoa.semanticmetricenginejava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.highcumontoa.semanticmetricenginejava.semantic.data.CsvDataLoader;
import com.github.highcumontoa.semanticmetricenginejava.semantic.data.TableData;
import com.github.highcumontoa.semanticmetricenginejava.semantic.error.ErrorCode;
import com.github.highcumontoa.semanticmetricenginejava.semantic.error.SemanticException;

/** 本地 CSV 加载：真实文件行数/空值解析，以及坏值必须按原因拒绝。 */
class CsvDataLoaderTest {

    private static final Logger LOG = LoggerFactory.getLogger(CsvDataLoaderTest.class);

    @Test
    void loadsSeedCsv_withNullsParsed() {
        TableData data = new CsvDataLoader().load(TestFixtures.salesDef());
        LOG.info("[CSV加载] 表={} 行数={}（期望12，空单元格解析为null）",
                data.name(), data.rows().size());
        assertEquals(12, data.rows().size());
        // 第 5 行 amount 为空（O1005），第 10 行 region 为空（O1010）
        assertNull(data.rows().get(4).get("amount"));
        assertNull(data.rows().get(9).get("region"));
    }

    @Test
    void badValue_rejectedWithDistinctReason() {
        // 直接验证解析层原因码：qty 为 LONG，文本 'abc' 必须报 DATA_CSV_BAD_VALUE
        SemanticException ex = assertThrows(SemanticException.class,
                () -> com.github.highcumontoa.semanticmetricenginejava.semantic.data.Values
                        .parse("abc", com.github.highcumontoa.semanticmetricenginejava.semantic.model.DataType.LONG));
        LOG.info("[CSV加载] 坏值输入='abc' 期望类型=LONG => 拒绝码={} 依据={}",
                ex.getCode(), ex.getMessage());
        assertEquals(ErrorCode.DATA_CSV_BAD_VALUE, ex.getCode());
    }
}
