package com.github.highcumontoa.semanticmetricenginejava.tests;

import com.github.highcumontoa.semanticmetricenginejava.model.AggregationType;
import com.github.highcumontoa.semanticmetricenginejava.model.ColumnDef;
import com.github.highcumontoa.semanticmetricenginejava.model.DataType;
import com.github.highcumontoa.semanticmetricenginejava.model.DimensionDef;
import com.github.highcumontoa.semanticmetricenginejava.model.ErrorCode;
import com.github.highcumontoa.semanticmetricenginejava.model.LocalTable;
import com.github.highcumontoa.semanticmetricenginejava.model.MetricDef;
import com.github.highcumontoa.semanticmetricenginejava.model.MetricExpression;
import com.github.highcumontoa.semanticmetricenginejava.model.PublishRequest;
import com.github.highcumontoa.semanticmetricenginejava.model.SemanticException;
import com.github.highcumontoa.semanticmetricenginejava.model.TableDef;
import com.github.highcumontoa.semanticmetricenginejava.registry.DefinitionValidator;
import com.github.highcumontoa.semanticmetricenginejava.registry.LocalTableRegistry;
import com.github.highcumontoa.semanticmetricenginejava.registry.SemanticRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 定义期拒绝边界：非法聚合、列/表/引用不存在、重名、循环定义、renameOf 血缘造假，
 * 每类原因必须以不同错误码出现，不允许静默降级。
 */
class DefinitionValidatorTest {

    private static final Logger log = LoggerFactory.getLogger(DefinitionValidatorTest.class);

    private LocalTableRegistry tables;
    private SemanticRegistry registry;

    private static MetricExpression ref(String name) {
        return new MetricExpression.ExprRef(name);
    }

    private static MetricExpression bin(MetricExpression.Op op, MetricExpression l, MetricExpression r) {
        return new MetricExpression.ExprBinary(op, l, r);
    }

    private static MetricExpression lit(double v) {
        return new MetricExpression.ExprLiteral(v);
    }

    private MetricDef base(String name, AggregationType agg, String col) {
        return new MetricDef(name, "sales", agg, col, null, null);
    }

    private MetricDef derived(String name, MetricExpression expression) {
        return new MetricDef(name, "sales", null, null, expression, null);
    }

    @BeforeEach
    void setUp() {
        tables = new LocalTableRegistry();
        tables.register(new LocalTable(
                new TableDef("sales", List.of(
                        new ColumnDef("region", DataType.STRING),
                        new ColumnDef("amount", DataType.DOUBLE),
                        new ColumnDef("quantity", DataType.LONG))),
                List.of()));
        registry = new SemanticRegistry(tables);
    }

    private SemanticException reject(PublishRequest request, ErrorCode expected) {
        SemanticException ex = assertThrows(SemanticException.class, () -> registry.publish(request));
        log.info("输入: version={}, 指标数={}; 判定: {} - {}",
                request.version(), request.metrics().size(), ex.getCode(), ex.getMessage());
        assertEquals(expected, ex.getCode());
        return ex;
    }

    @Test
    void sumOnStringColumnIsInvalidAggregation() {
        reject(new PublishRequest("v1", null,
                        List.of(new DimensionDef("region", "sales", "region")),
                        List.of(base("bad", AggregationType.SUM, "region"))),
                ErrorCode.INVALID_AGGREGATION);
    }

    @Test
    void missingColumnAndTableAreRejectedSeparately() {
        reject(new PublishRequest("v1", null,
                        List.of(), List.of(base("bad", AggregationType.SUM, "nope"))),
                ErrorCode.COLUMN_NOT_FOUND);
        reject(new PublishRequest("v2", null,
                        List.of(new DimensionDef("d", "ghost", "c")),
                        List.of(base("m", AggregationType.MAX, "amount"))),
                ErrorCode.TABLE_NOT_FOUND);
    }

    @Test
    void undefinedExpressionReferenceIsRejected() {
        reject(new PublishRequest("v1", null,
                        List.of(),
                        List.of(derived("a", bin(MetricExpression.Op.ADD, ref("ghost"), lit(1))))),
                ErrorCode.INVALID_EXPRESSION_REFERENCE);
    }

    @Test
    void circularDefinitionsAreRejectedIncludingSelfLoop() {
        reject(new PublishRequest("cyc", null,
                        List.of(),
                        List.of(derived("a", bin(MetricExpression.Op.ADD, ref("a"), lit(1))))),
                ErrorCode.CIRCULAR_DEFINITION);

        SemanticException twoNode = reject(new PublishRequest("cyc2", null,
                        List.of(),
                        List.of(
                                derived("a", bin(MetricExpression.Op.ADD, ref("b"), lit(1))),
                                derived("b", bin(MetricExpression.Op.ADD, ref("a"), lit(1))))),
                ErrorCode.CIRCULAR_DEFINITION);
        assertTrue(twoNode.getMessage().contains("a"), "循环链消息应指出成环节点: " + twoNode.getMessage());
        assertTrue(twoNode.getMessage().contains("b"), "循环链消息应指出成环节点: " + twoNode.getMessage());
    }

    @Test
    void duplicateNamesAndVersionAreRejected() {
        registry.publish(new PublishRequest("v1", null,
                List.of(new DimensionDef("region", "sales", "region")),
                List.of(base("m1", AggregationType.SUM, "amount"))));

        reject(new PublishRequest("v1", null,
                        List.of(), List.of(base("x", AggregationType.MAX, "amount"))),
                ErrorCode.VERSION_ALREADY_EXISTS);
        // 同一次发布内指标与维度同名 -> DUPLICATE_DEFINITION
        reject(new PublishRequest("v2", "v1",
                        List.of(new DimensionDef("dup", "sales", "region")),
                        List.of(base("dup", AggregationType.MAX, "amount"))),
                ErrorCode.DUPLICATE_DEFINITION);
        // 同一次发布内两个指标同名 -> DUPLICATE_DEFINITION
        reject(new PublishRequest("v3", "v1",
                        List.of(),
                        List.of(base("dup", AggregationType.MAX, "amount"),
                                base("dup", AggregationType.SUM, "quantity"))),
                ErrorCode.DUPLICATE_DEFINITION);
    }

    @Test
    void unknownParentAndFakeRenameAreRejected() {
        reject(new PublishRequest("v2", "vX",
                        List.of(), List.of()),
                ErrorCode.VERSION_PARENT_NOT_FOUND);

        registry.publish(new PublishRequest("v1", null,
                List.of(), List.of(base("old_metric", AggregationType.SUM, "amount"))));
        reject(new PublishRequest("v2", "v1",
                        List.of(),
                        List.of(new MetricDef("new_metric", "sales", AggregationType.SUM, "amount",
                                null, "missing_in_parent"))),
                ErrorCode.INVALID_REQUEST);
    }

    @Test
    void validDefinitionsPublishAndAreOrdered() {
        log.info("输入: 基础指标 revenue + 派生 tax(revenue*0.1); 判定依据: 全部引用合法且无环 -> 发布成功");
        registry.publish(new PublishRequest("v1", null,
                List.of(new DimensionDef("region", "sales", "region")),
                List.of(
                        base("revenue", AggregationType.SUM, "amount"),
                        derived("tax", bin(MetricExpression.Op.MUL, ref("revenue"), lit(0.1))))));
        assertEquals(List.of("v1"), registry.listVersions());
        assertTrue(registry.getVersion("v1").isPresent());
    }
}
