package com.github.highcumontoa.semanticmetricenginejava.data;

import com.github.highcumontoa.semanticmetricenginejava.model.AggregationType;
import com.github.highcumontoa.semanticmetricenginejava.model.ColumnDef;
import com.github.highcumontoa.semanticmetricenginejava.model.DataType;
import com.github.highcumontoa.semanticmetricenginejava.model.DimensionDef;
import com.github.highcumontoa.semanticmetricenginejava.model.LocalTable;
import com.github.highcumontoa.semanticmetricenginejava.model.MetricDef;
import com.github.highcumontoa.semanticmetricenginejava.model.MetricExpression;
import com.github.highcumontoa.semanticmetricenginejava.model.PublishRequest;
import com.github.highcumontoa.semanticmetricenginejava.model.TableDef;
import com.github.highcumontoa.semanticmetricenginejava.registry.LocalTableRegistry;
import com.github.highcumontoa.semanticmetricenginejava.registry.SemanticRegistry;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 启动时装载本地样例表与两个语义版本。
 *
 * <p>版本故事：</p>
 * <ul>
 *   <li>v1：revenue(SUM amount)、qty(SUM quantity)、order_count(COUNT order_id)、
 *       avg_amount(AVG amount)、product_count(COUNT_DISTINCT product)，
 *       tax=revenue*0.1，net_revenue=revenue-tax（派生引用派生），
 *       avg_order_value=revenue/order_count（含除零风险路径，由空值规则兜底）；</li>
 *   <li>v2（父版本 v1）：revenue 重命名为 total_revenue（renameOf 血缘）；
 *       删除 order_count（v1 历史结论仍可按版本复现）；
 *       tax 口径由 0.1 调整为 0.2（同名指标，按版本区分结果）。</li>
 * </ul>
 */
@Component
public class SampleDataLoader {

    public static final String TABLE = "sales";
    public static final String V1 = "v1";
    public static final String V2 = "v2";

    private final LocalTableRegistry tables;
    private final SemanticRegistry versions;

    public SampleDataLoader(LocalTableRegistry tables, SemanticRegistry versions) {
        this.tables = tables;
        this.versions = versions;
    }

    @PostConstruct
    public void load() {
        tables.register(buildSalesTable());
        versions.publish(buildV1());
        versions.publish(buildV2());
    }

    private LocalTable buildSalesTable() {
        TableDef def = new TableDef(TABLE, List.of(
                new ColumnDef("order_id", DataType.STRING),
                new ColumnDef("region", DataType.STRING),
                new ColumnDef("product", DataType.STRING),
                new ColumnDef("sale_date", DataType.DATE),
                new ColumnDef("quantity", DataType.LONG),
                new ColumnDef("amount", DataType.DOUBLE)));

        List<Map<String, Object>> rows = List.of(
                row("o1", "east", "A", "2024-01-01", 10L, 100.0),
                row("o2", "east", "B", "2024-01-01", 5L, 50.0),
                row("o3", "west", "A", "2024-01-01", 7L, 70.0),
                row("o4", "east", "A", "2024-01-02", 3L, 30.0),
                // 刻意的金额空值：SUM/AVG 跳过，COUNT(amount) 不计，COUNT(*) 仍计
                row("o5", "east", "B", "2024-01-02", 2L, null),
                // 刻意的并列：与 o3 同为 west/A/70
                row("o6", "west", "A", "2024-01-02", 7L, 70.0),
                row("o7", "west", "B", "2024-01-02", 4L, 40.0),
                row("o8", "east", "A", "2024-01-03", 8L, 80.0),
                // 刻意的并列：与 o7 同为 west/B/40
                row("o9", "west", "B", "2024-01-03", 4L, 40.0));
        return new LocalTable(def, rows);
    }

    private static Map<String, Object> row(String orderId, String region, String product,
                                           String date, Long quantity, Double amount) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("order_id", orderId);
        map.put("region", region);
        map.put("product", product);
        map.put("sale_date", date);
        map.put("quantity", quantity);
        map.put("amount", amount);
        return map;
    }

    private PublishRequest buildV1() {
        List<DimensionDef> dimensions = List.of(
                new DimensionDef("region", TABLE, "region"),
                new DimensionDef("product", TABLE, "product"),
                new DimensionDef("day", TABLE, "sale_date"));

        List<MetricDef> metrics = List.of(
                new MetricDef("revenue", TABLE, AggregationType.SUM, "amount", null, null),
                new MetricDef("qty", TABLE, AggregationType.SUM, "quantity", null, null),
                new MetricDef("order_count", TABLE, AggregationType.COUNT, "order_id", null, null),
                new MetricDef("orders_all", TABLE, AggregationType.COUNT, null, null, null),
                new MetricDef("avg_amount", TABLE, AggregationType.AVG, "amount", null, null),
                new MetricDef("product_count", TABLE, AggregationType.COUNT_DISTINCT, "product", null, null),
                new MetricDef("min_amount", TABLE, AggregationType.MIN, "amount", null, null),
                new MetricDef("max_amount", TABLE, AggregationType.MAX, "amount", null, null),
                new MetricDef("tax", TABLE, null, null,
                        new MetricExpression.ExprBinary(MetricExpression.Op.MUL,
                                new MetricExpression.ExprRef("revenue"),
                                new MetricExpression.ExprLiteral(0.1d)),
                        null),
                new MetricDef("net_revenue", TABLE, null, null,
                        new MetricExpression.ExprBinary(MetricExpression.Op.SUB,
                                new MetricExpression.ExprRef("revenue"),
                                new MetricExpression.ExprRef("tax")),
                        null),
                new MetricDef("avg_order_value", TABLE, null, null,
                        new MetricExpression.ExprBinary(MetricExpression.Op.DIV,
                                new MetricExpression.ExprRef("revenue"),
                                new MetricExpression.ExprRef("order_count")),
                        null));
        return new PublishRequest(V1, null, dimensions, metrics);
    }

    private PublishRequest buildV2() {
        List<DimensionDef> dimensions = List.of(
                new DimensionDef("region", TABLE, "region"),
                new DimensionDef("product", TABLE, "product"),
                new DimensionDef("day", TABLE, "sale_date"));

        List<MetricDef> metrics = List.of(
                // 重命名：口径不变，renameOf 指向 v1 旧名
                new MetricDef("total_revenue", TABLE, AggregationType.SUM, "amount", null, "revenue"),
                new MetricDef("qty", TABLE, AggregationType.SUM, "quantity", null, null),
                // order_count 被删除：v1 查询仍可用，v2 查询必须按 METRIC_NOT_FOUND 拒绝
                new MetricDef("avg_amount", TABLE, AggregationType.AVG, "amount", null, null),
                // 口径调整：税率 0.1 -> 0.2，同名 tax，结果按版本区分
                new MetricDef("tax", TABLE, null, null,
                        new MetricExpression.ExprBinary(MetricExpression.Op.MUL,
                                new MetricExpression.ExprRef("total_revenue"),
                                new MetricExpression.ExprLiteral(0.2d)),
                        null),
                new MetricDef("net_revenue", TABLE, null, null,
                        new MetricExpression.ExprBinary(MetricExpression.Op.SUB,
                                new MetricExpression.ExprRef("total_revenue"),
                                new MetricExpression.ExprRef("tax")),
                        null));
        return new PublishRequest(V2, V1, dimensions, metrics);
    }
}
