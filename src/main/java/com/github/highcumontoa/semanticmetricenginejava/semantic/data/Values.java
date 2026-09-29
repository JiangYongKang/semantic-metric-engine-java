package com.github.highcumontoa.semanticmetricenginejava.semantic.data;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Comparator;

import com.github.highcumontoa.semanticmetricenginejava.semantic.model.DataType;
import com.github.highcumontoa.semanticmetricenginejava.semantic.model.DateGranularity;
import com.github.highcumontoa.semanticmetricenginejava.semantic.error.ErrorCode;
import com.github.highcumontoa.semanticmetricenginejava.semantic.error.SemanticException;

/**
 * 字段值工具：字符串解析、日期截断/分桶、确定性排序比较。
 *
 * <p>口径要点：
 * <ul>
 *   <li>LONG 解析整数；DOUBLE 用 {@link Double#parseDouble} 解析十进制数；</li>
 *   <li>DATE 为 ISO yyyy-MM-dd；空串/字面 null（忽略大小写）解析为 Java null；</li>
 *   <li>所有比较均为 null-last，保证输出行序与窗口排序稳定。</li>
 * </ul>
 */
public final class Values {

    private Values() {
    }

    /**
     * 按 CSV 文本解析为目标类型。
     *
     * @param text 原始文本
     * @param type 目标类型
     * @return Long / Double / String / LocalDate 或 null（空值）
     * @throws SemanticException DATA_CSV_BAD_VALUE 文本无法按类型解析
     */
    public static Object parse(String text, DataType type) {
        if (text == null) {
            return null;
        }
        String t = text.trim();
        if (t.isEmpty() || t.equalsIgnoreCase("null")) {
            return null;
        }
        try {
            return switch (type) {
                case LONG -> Long.valueOf(t);
                case DOUBLE -> Double.valueOf(t);
                case DATE -> LocalDate.parse(t);
                case STRING -> t;
            };
        } catch (NumberFormatException | DateTimeParseException e) {
            throw new SemanticException(ErrorCode.DATA_CSV_BAD_VALUE,
                    "值无法解析为 " + type + ": '" + t + "'，依据: " + e.getMessage());
        }
    }

    /** 日期截断到指定粒度（MONTH -> 当月 1 日，YEAR -> 当年 1 月 1 日）。 */
    public static LocalDate truncate(LocalDate date, DateGranularity granularity) {
        if (date == null) {
            return null;
        }
        return switch (granularity) {
            case DAY -> date;
            case MONTH -> date.withDayOfMonth(1);
            case YEAR -> date.withDayOfYear(1);
        };
    }

    /**
     * 对维度输出值做确定性比较：null 最大（nulls last）；
     * 同类型按自然顺序；数字跨类型（Long/Double）按数值比较。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static int compareNullsLast(Object a, Object b) {
        if (a == null && b == null) {
            return 0;
        }
        if (a == null) {
            return 1;
        }
        if (b == null) {
            return -1;
        }
        if (a instanceof Number na && b instanceof Number nb) {
            return Double.compare(na.doubleValue(), nb.doubleValue());
        }
        if (a.getClass() == b.getClass() && a instanceof Comparable) {
            return ((Comparable) a).compareTo(b);
        }
        // 兜底：按类型名+字符串比较，保证不同类型混合时仍确定（正常建模不会走到）
        int byType = a.getClass().getSimpleName().compareTo(b.getClass().getSimpleName());
        if (byType != 0) {
            return byType;
        }
        return a.toString().compareTo(b.toString());
    }

    /** nulls-last 升序比较器，供分组键与时间桶排序复用。 */
    public static Comparator<Object> nullsLastComparator() {
        return Values::compareNullsLast;
    }
}
