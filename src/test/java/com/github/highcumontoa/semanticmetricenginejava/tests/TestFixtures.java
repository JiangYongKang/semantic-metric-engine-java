package com.github.highcumontoa.semanticmetricenginejava.tests;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.github.highcumontoa.semanticmetricenginejava.semantic.data.DataSet;
import com.github.highcumontoa.semanticmetricenginejava.semantic.data.Row;
import com.github.highcumontoa.semanticmetricenginejava.semantic.data.TableData;
import com.github.highcumontoa.semanticmetricenginejava.semantic.model.ColumnDef;
import com.github.highcumontoa.semanticmetricenginejava.semantic.model.DataType;
import com.github.highcumontoa.semanticmetricenginejava.semantic.model.TableDef;

/**
 * 测试夹具：内存中构造 sales 表（与 classpath CSV 同结构），
 * 便于打乱行序、分批聚合等确定性测试。
 */
public final class TestFixtures {

    public static final String T = "sales";
    public static final String REGION = "region";
    public static final String CHANNEL = "channel";
    public static final String TX_DATE = "tx_date";
    public static final String AMOUNT = "amount";
    public static final String QTY = "qty";
    public static final String ORDER_ID = "order_id";

    private TestFixtures() {
    }

    public static TableDef salesDef() {
        return new TableDef(T, List.of(
                new ColumnDef(REGION, DataType.STRING),
                new ColumnDef(CHANNEL, DataType.STRING),
                new ColumnDef(TX_DATE, DataType.DATE),
                new ColumnDef(AMOUNT, DataType.DOUBLE),
                new ColumnDef(QTY, DataType.LONG),
                new ColumnDef(ORDER_ID, DataType.STRING)
        ));
    }

    /** 与 resources/data/sales.csv 等价的 12 行数据。 */
    public static List<Row> salesRows() {
        List<Row> rows = new ArrayList<>();
        rows.add(row("East", "Online", "2026-01-05", 100.00, 2L, "O1001"));
        rows.add(row("East", "Store", "2026-01-10", 150.50, 3L, "O1002"));
        rows.add(row("North", "Online", "2026-01-12", 100.00, 2L, "O1003"));
        rows.add(row("East", "Online", "2026-02-03", 200.00, 1L, "O1004"));
        rows.add(row("North", "Store", "2026-02-08", null, 4L, "O1005"));
        rows.add(row("South", "Online", "2026-02-15", 300.25, 5L, "O1006"));
        rows.add(row("East", "Store", "2026-03-01", 150.50, 3L, "O1007"));
        rows.add(row("South", "Online", "2026-03-10", 80.00, 1L, "O1008"));
        rows.add(row("North", "Online", "2026-03-12", 100.00, 2L, "O1009"));
        rows.add(row(null, "Online", "2026-03-20", 50.00, 1L, "O1010"));
        rows.add(row("East", "Online", "2026-04-02", 200.00, 4L, "O1011"));
        rows.add(row("South", "Store", "2026-04-09", 300.25, 6L, "O1006"));
        return rows;
    }

    public static DataSet dataSet(List<Row> rows) {
        return new DataSet(Map.of(T, new TableData(salesDef(), rows)));
    }

    public static Row row(String region, String channel, String date,
                          Double amount, Long qty, String orderId) {
        Map<String, Object> cells = new LinkedHashMap<>();
        cells.put(REGION, region);
        cells.put(CHANNEL, channel);
        cells.put(TX_DATE, date == null ? null : LocalDate.parse(date));
        cells.put(AMOUNT, amount);
        cells.put(QTY, qty);
        cells.put(ORDER_ID, orderId);
        return new Row(cells);
    }

    /** 随机打乱行序（固定种子，保证测试可复现）。 */
    public static List<Row> shuffled(List<Row> rows, long seed) {
        List<Row> copy = new ArrayList<>(rows);
        var random = new java.util.Random(seed);
        java.util.Collections.shuffle(copy, random);
        return copy;
    }

    public static List<Row> concat(List<Row>... parts) {
        List<Row> all = new ArrayList<>();
        Arrays.stream(parts).forEach(all::addAll);
        return all;
    }
}
