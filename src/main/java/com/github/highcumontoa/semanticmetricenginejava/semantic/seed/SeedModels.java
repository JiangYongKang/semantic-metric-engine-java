package com.github.highcumontoa.semanticmetricenginejava.semantic.seed;

import static com.github.highcumontoa.semanticmetricenginejava.semantic.seed.SalesTable.*;

import java.util.List;

import com.github.highcumontoa.semanticmetricenginejava.semantic.model.AggType;
import com.github.highcumontoa.semanticmetricenginejava.semantic.model.Algorithm;
import com.github.highcumontoa.semanticmetricenginejava.semantic.model.DataType;
import com.github.highcumontoa.semanticmetricenginejava.semantic.model.DateGranularity;
import com.github.highcumontoa.semanticmetricenginejava.semantic.model.DimensionDef;
import com.github.highcumontoa.semanticmetricenginejava.semantic.model.MetricDef;
import com.github.highcumontoa.semanticmetricenginejava.semantic.model.ModelVersion;

/**
 * 预置语义模型版本。
 *
 * <p>v1 -> v2 演示三类版本变化（旧结论在 v1 下仍可精确复现）：
 * <ol>
 *   <li>重命名：gmv -> sales_amount；gmv_cum -> sales_amount_cum；gmv_roll3 -> sales_amount_roll3；</li>
 *   <li>删除：legacy_metric 在 v2 移除；</li>
 *   <li>口径调整：order_count 由 COUNT(*) 改为 COUNT_DISTINCT(order_id)。</li>
 * </ol>
 */
public final class SeedModels {

    private SeedModels() {
    }

    private static List<DimensionDef> dimensions() {
        return List.of(
                new DimensionDef("region", NAME, C_REGION, DataType.STRING, null),
                new DimensionDef("channel", NAME, C_CHANNEL, DataType.STRING, null),
                new DimensionDef("tx_date_day", NAME, C_TX_DATE, DataType.DATE, DateGranularity.DAY),
                new DimensionDef("tx_date_month", NAME, C_TX_DATE, DataType.DATE, DateGranularity.MONTH)
        );
    }

    /** v1：初始版本。 */
    public static ModelVersion v1() {
        List<MetricDef> metrics = List.of(
                new MetricDef("gmv", NAME, C_AMOUNT, AggType.SUM, Algorithm.NONE, null, null),
                new MetricDef("order_count", NAME, null, AggType.COUNT, Algorithm.NONE, null, null),
                new MetricDef("distinct_orders", NAME, C_ORDER_ID, AggType.COUNT_DISTINCT,
                        Algorithm.NONE, null, null),
                new MetricDef("avg_amount", NAME, C_AMOUNT, AggType.AVG, Algorithm.NONE, null, null),
                new MetricDef("total_qty", NAME, C_QTY, AggType.SUM, Algorithm.NONE, null, null),
                new MetricDef("gmv_cum", NAME, C_AMOUNT, AggType.SUM, Algorithm.CUMULATIVE, null, null),
                new MetricDef("gmv_roll3", NAME, C_AMOUNT, AggType.SUM, Algorithm.ROLLING, 3, null),
                new MetricDef("amount_rank", NAME, C_AMOUNT, AggType.SUM, Algorithm.RANK, null, null),
                new MetricDef("legacy_metric", NAME, C_QTY, AggType.MAX, Algorithm.NONE, null, null)
        );
        return new ModelVersion("v1", List.of(SalesTable.def()), dimensions(), metrics,
                "初始版本：gmv=SUM(amount)；order_count=COUNT(*)；含 legacy_metric");
    }

    /** v2：重命名 + 删除 + 口径调整，见类注释。 */
    public static ModelVersion v2() {
        List<MetricDef> metrics = List.of(
                new MetricDef("sales_amount", NAME, C_AMOUNT, AggType.SUM, Algorithm.NONE, null, null),
                new MetricDef("order_count", NAME, C_ORDER_ID, AggType.COUNT_DISTINCT,
                        Algorithm.NONE, null, null),
                new MetricDef("distinct_orders", NAME, C_ORDER_ID, AggType.COUNT_DISTINCT,
                        Algorithm.NONE, null, null),
                new MetricDef("avg_amount", NAME, C_AMOUNT, AggType.AVG, Algorithm.NONE, null, null),
                new MetricDef("total_qty", NAME, C_QTY, AggType.SUM, Algorithm.NONE, null, null),
                new MetricDef("sales_amount_cum", NAME, C_AMOUNT, AggType.SUM,
                        Algorithm.CUMULATIVE, null, null),
                new MetricDef("sales_amount_roll3", NAME, C_AMOUNT, AggType.SUM,
                        Algorithm.ROLLING, 3, null),
                new MetricDef("amount_rank", NAME, C_AMOUNT, AggType.SUM, Algorithm.RANK, null, null),
                new MetricDef("gmv_alias", null, null, null, null, null, "sales_amount")
        );
        return new ModelVersion("v2", List.of(SalesTable.def()), dimensions(), metrics,
                "重命名 gmv->sales_amount、gmv_cum->sales_amount_cum、gmv_roll3->sales_amount_roll3；"
                        + "删除 legacy_metric；order_count 口径由 COUNT(*) 调整为 COUNT_DISTINCT(order_id)；"
                        + "新增派生指标 gmv_alias->sales_amount 演示别名链路");
    }

    public static List<ModelVersion> all() {
        return List.of(v1(), v2());
    }
}
