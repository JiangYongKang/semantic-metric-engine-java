package com.github.highcumontoa.semanticmetricenginejava.engine;

import com.github.highcumontoa.semanticmetricenginejava.model.MetricDef;
import com.github.highcumontoa.semanticmetricenginejava.model.MetricExpression;

import java.util.Map;

/**
 * 按分组计算派生指标表达式。
 *
 * <p>稳定语义：</p>
 * <ul>
 *   <li>任一操作数为 null，算术结果为 null（不静默当 0）；</li>
 *   <li>除法除数为 0（含 -0.0）时结果为 null，不抛异常，保证可重复；</li>
 *   <li>引用的指标在该分组无值时按 null 参与（正常流程中引用合法性已在发布期保证）。</li>
 * </ul>
 */
public class ExpressionEvaluator {

    public Double evaluate(MetricDef metric, Map<String, Double> valuesByGroup) {
        return eval(metric.expression(), valuesByGroup);
    }

    private Double eval(MetricExpression expression, Map<String, Double> values) {
        return switch (expression) {
            case MetricExpression.ExprLiteral literal -> literal.value();
            case MetricExpression.ExprRef ref -> values.get(ref.metricName());
            case MetricExpression.ExprBinary binary -> apply(binary.op(),
                    eval(binary.left(), values), eval(binary.right(), values));
        };
    }

    private Double apply(MetricExpression.Op op, Double left, Double right) {
        if (left == null || right == null) {
            return null;
        }
        return switch (op) {
            case ADD -> left + right;
            case SUB -> left - right;
            case MUL -> left * right;
            case DIV -> right == 0.0d ? null : left / right;
        };
    }
}
