package com.github.highcumontoa.semanticmetricenginejava.semantic.data;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.Set;

import com.github.highcumontoa.semanticmetricenginejava.semantic.model.AggType;
import com.github.highcumontoa.semanticmetricenginejava.semantic.error.ErrorCode;
import com.github.highcumontoa.semanticmetricenginejava.semantic.error.SemanticException;

/**
 * 单组聚合器：对一组输入值按聚合方式计算。
 *
 * <p>确定性口径（分批执行也必须一致）：
 * <ul>
 *   <li>数值内部一律使用 {@link BigDecimal}：Long 走 valueOf(long)，Double 走 valueOf(double)，
 *       求和结果与输入顺序无关；COUNT 为精确计数。</li>
 *   <li>SUM/COUNT(expr)/COUNT_DISTINCT/AVG/MIN/MAX 忽略 null；COUNT(*) 计全部行。</li>
 *   <li>AVG = 非空值之和 / 非空个数，非空个数为 0 时结果为 null；
 *       不能整除时四舍五入到 6 位小数（HALF_UP），能整除且全为整数时输出 Long。</li>
 * </ul>
 */
public final class Aggregator {

    private static final int AVG_SCALE = 6;

    private final AggType aggType;
    private final boolean countStar;

    private long countAll;
    private BigDecimal sum = BigDecimal.ZERO;
    private long nonNullCount;
    private BigDecimal min;
    private BigDecimal max;
    private boolean allIntegral = true;
    private final Set<Object> distinct = new HashSet<>();

    public Aggregator(AggType aggType) {
        this(aggType, false);
    }

    /**
     * @param aggType   聚合方式
     * @param countStar 仅对 COUNT 有效：true 表示 COUNT(*)（计全部行），
     *                  false 表示 COUNT(expr)（忽略 null）
     */
    public Aggregator(AggType aggType, boolean countStar) {
        this.aggType = aggType;
        this.countStar = countStar;
    }

    /** 累积一个输入值（可为 null）。 */
    public void accumulate(Object value) {
        countAll++;
        if (aggType == AggType.COUNT) {
            if (countStar || value != null) {
                nonNullCount++;
            }
            return;
        }
        if (value == null) {
            return;
        }
        if (aggType == AggType.COUNT_DISTINCT) {
            distinct.add(value);
            return;
        }
        BigDecimal dec = toDecimal(value);
        sum = sum.add(dec);
        nonNullCount++;
        if (min == null || dec.compareTo(min) < 0) {
            min = dec;
        }
        if (max == null || dec.compareTo(max) > 0) {
            max = dec;
        }
    }

    /** 合并另一个同口径聚合器的状态（分批执行，合并顺序不影响结果）。 */
    public void merge(Aggregator other) {
        if (other.aggType != this.aggType) {
            throw new IllegalArgumentException("不能合并不同聚合方式的聚合器: "
                    + this.aggType + " vs " + other.aggType);
        }
        countAll += other.countAll;
        nonNullCount += other.nonNullCount;
        sum = sum.add(other.sum);
        allIntegral = allIntegral && other.allIntegral;
        distinct.addAll(other.distinct);
        if (other.min != null && (this.min == null || other.min.compareTo(this.min) < 0)) {
            this.min = other.min;
        }
        if (other.max != null && (this.max == null || other.max.compareTo(this.max) > 0)) {
            this.max = other.max;
        }
    }

    /** 输出聚合结果（Long / Double / null）。 */
    public Object result() {
        return switch (aggType) {
            case COUNT -> countStar ? countAll : nonNullCount;
            case COUNT_DISTINCT -> (long) distinct.size();
            case SUM -> nonNullCount == 0 ? null : toNumber(sum);
            case AVG -> average();
            case MIN -> nonNullCount == 0 ? null : toNumber(min);
            case MAX -> nonNullCount == 0 ? null : toNumber(max);
        };
    }

    /** 内部精确求和（忽略 null；无输入时为 ZERO），供累计/滑动窗口复用。 */
    public BigDecimal sumDecimal() {
        return aggType == AggType.SUM ? sum : sum;
    }

    /** 非空输入个数，供累计/滑动窗口复用。 */
    public long nonNullCount() {
        return nonNullCount;
    }

    /** 去重集合大小，供累计口径复用。 */
    public long distinctCount() {
        return distinct.size();
    }

    private Object average() {
        if (nonNullCount == 0) {
            return null;
        }
        BigDecimal divisor = BigDecimal.valueOf(nonNullCount);
        if (allIntegral && sum.remainder(divisor).signum() == 0) {
            return sum.divide(divisor).longValueExact();
        }
        BigDecimal q = sum.divide(divisor, AVG_SCALE, RoundingMode.HALF_UP);
        return q.stripTrailingZeros().scale() <= 0
                ? q.longValueExact()
                : q.doubleValue();
    }

    /** 全整数输入输出 Long，否则输出 Double（去尾零，保证稳定表示）。 */
    private Object toNumber(BigDecimal dec) {
        if (allIntegral) {
            return dec.longValueExact();
        }
        BigDecimal stripped = dec.stripTrailingZeros();
        return stripped.scale() <= 0 ? stripped.longValueExact() : stripped.doubleValue();
    }

    private BigDecimal toDecimal(Object value) {
        if (value instanceof Long l) {
            return BigDecimal.valueOf(l);
        }
        if (value instanceof Number n) {
            allIntegral = false;
            return BigDecimal.valueOf(n.doubleValue());
        }
        throw new SemanticException(ErrorCode.Q_AGGREGATE_NOT_NUMERIC,
                "聚合输入不是数值类型: " + value + " (" + value.getClass().getSimpleName()
                        + ")，依据: " + aggType + " 只能作用于 LONG/DOUBLE 字段");
    }
}
