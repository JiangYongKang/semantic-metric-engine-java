package com.github.highcumontoa.semanticmetricenginejava.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * 派生指标表达式：仅支持 引用 / 常量 / 二元运算，便于做引用校验与循环检测。
 *
 * <p>JSON 形态由 {@code kind} 区分：{@code {"kind":"ref","metricName":"revenue"}}、
 * {@code {"kind":"literal","value":0.1}}、
 * {@code {"kind":"binary","op":"ADD","left":...,"right":...}}。</p>
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
@JsonSubTypes({
        @JsonSubTypes.Type(value = MetricExpression.ExprRef.class, name = "ref"),
        @JsonSubTypes.Type(value = MetricExpression.ExprLiteral.class, name = "literal"),
        @JsonSubTypes.Type(value = MetricExpression.ExprBinary.class, name = "binary")
})
public sealed interface MetricExpression permits MetricExpression.ExprRef,
        MetricExpression.ExprLiteral, MetricExpression.ExprBinary {

    enum Op { ADD, SUB, MUL, DIV }

    record ExprRef(String metricName) implements MetricExpression {
    }

    record ExprLiteral(double value) implements MetricExpression {
    }

    record ExprBinary(Op op, MetricExpression left, MetricExpression right) implements MetricExpression {
    }
}
