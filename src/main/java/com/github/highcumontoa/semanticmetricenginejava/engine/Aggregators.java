package com.github.highcumontoa.semanticmetricenginejava.engine;

import com.github.highcumontoa.semanticmetricenginejava.model.AggregationType;
import com.github.highcumontoa.semanticmetricenginejava.model.ColumnDef;
import com.github.highcumontoa.semanticmetricenginejava.model.DataType;
import com.github.highcumontoa.semanticmetricenginejava.model.ErrorCode;
import com.github.highcumontoa.semanticmetricenginejava.model.SemanticException;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 基础聚合原语。
 *
 * <p>固定规则（保证可重复、可预期）：</p>
 * <ul>
 *   <li>行里“键缺失”一律以 FIELD_VALUE_MISSING 拒绝，不把缺列静默当 null；
 *       键存在且值为 null 才算空值。</li>
 *   <li>值类型与列定义不符一律 FIELD_VALUE_MISSING 拒绝。</li>
 *   <li>除 COUNT(*) 外，所有聚合跳过 null 值。</li>
 *   <li>COUNT/COUNT_DISTINCT 在无任何非空输入时返回 0；
 *       SUM/AVG/MIN/MAX 在无任何非空输入时返回 null（空集合不静默归零）；</li>
 *   <li>SUM/AVG 输出统一为 double，避免整型溢出与除不尽截断；COUNT 输出 long。</li>
 * </ul>
 */
public final class Aggregators {

    private Aggregators() {
    }

    public static Object aggregate(AggregationType type, ColumnDef column,
                                   List<Map<String, Object>> rows) {
        return switch (type) {
            case COUNT -> column == null ? countAll(rows) : count(column, rows);
            case COUNT_DISTINCT -> countDistinct(requireColumn(column), rows);
            case SUM -> sum(requireColumn(column), rows);
            case AVG -> avg(requireColumn(column), rows);
            case MIN -> min(requireColumn(column), rows);
            case MAX -> max(requireColumn(column), rows);
        };
    }

    private static ColumnDef requireColumn(ColumnDef column) {
        if (column == null) {
            throw new SemanticException(ErrorCode.INVALID_AGGREGATION,
                    "该聚合方式必须指定被聚合列");
        }
        return column;
    }

    private static long countAll(List<Map<String, Object>> rows) {
        return rows.size();
    }

    private static long count(ColumnDef column, List<Map<String, Object>> rows) {
        long n = 0;
        for (Map<String, Object> row : rows) {
            if (readNullable(column, row) != null) {
                n++;
            }
        }
        return n;
    }

    private static long countDistinct(ColumnDef column, List<Map<String, Object>> rows) {
        Set<Object> seen = new HashSet<>();
        for (Map<String, Object> row : rows) {
            Object value = readNullable(column, row);
            if (value != null) {
                seen.add(value);
            }
        }
        return seen.size();
    }

    private static Double sum(ColumnDef column, List<Map<String, Object>> rows) {
        double total = 0.0;
        long n = 0;
        for (Map<String, Object> row : rows) {
            Object value = readNullable(column, row);
            if (value != null) {
                total += ((Number) value).doubleValue();
                n++;
            }
        }
        // 无任何非空输入返回 null，与 AVG/MIN/MAX 一致，避免“空集合=0”误导
        return n == 0 ? null : total;
    }

    private static Double avg(ColumnDef column, List<Map<String, Object>> rows) {
        double total = 0.0;
        long n = 0;
        for (Map<String, Object> row : rows) {
            Object value = readNullable(column, row);
            if (value != null) {
                total += ((Number) value).doubleValue();
                n++;
            }
        }
        return n == 0 ? null : total / n;
    }

    private static Object min(ColumnDef column, List<Map<String, Object>> rows) {
        Object best = null;
        for (Map<String, Object> row : rows) {
            Object value = readNullable(column, row);
            if (value == null) {
                continue;
            }
            if (best == null || compare(value, best) < 0) {
                best = value;
            }
        }
        return best;
    }

    private static Object max(ColumnDef column, List<Map<String, Object>> rows) {
        Object best = null;
        for (Map<String, Object> row : rows) {
            Object value = readNullable(column, row);
            if (value == null) {
                continue;
            }
            if (best == null || compare(value, best) > 0) {
                best = value;
            }
        }
        return best;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compare(Object a, Object b) {
        if (a instanceof Number && b instanceof Number) {
            return Double.compare(((Number) a).doubleValue(), ((Number) b).doubleValue());
        }
        return ((Comparable) a).compareTo(b);
    }

    /**
     * 读取并按列类型归一化一个单元格。
     * 键缺失 / 值类型不符合列定义 → FIELD_VALUE_MISSING（原因可区分，不静默）。
     * 键存在但值为 null → 返回 null，交由聚合按空值规则处理。
     */
    static Object readNullable(ColumnDef column, Map<String, Object> row) {
        if (!row.containsKey(column.name())) {
            throw new SemanticException(ErrorCode.FIELD_VALUE_MISSING,
                    "行数据缺少列 " + column.name() + "（定义类型 " + column.type() + "），拒绝静默按空值处理");
        }
        Object raw = row.get(column.name());
        if (raw == null) {
            return null;
        }
        return normalize(column, raw);
    }

    private static Object normalize(ColumnDef column, Object raw) {
        DataType type = column.type();
        String where = "列 " + column.name() + " 定义类型 " + type;
        try {
            return switch (type) {
                case LONG -> {
                    if (raw instanceof Long l) {
                        yield l;
                    }
                    if (raw instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue())
                            && !Double.isInfinite(n.doubleValue())) {
                        yield n.longValue();
                    }
                    throw mismatch(where, raw);
                }
                case DOUBLE -> {
                    if (raw instanceof Number n) {
                        yield n.doubleValue();
                    }
                    throw mismatch(where, raw);
                }
                case STRING, DATE -> {
                    if (raw instanceof String s) {
                        yield s;
                    }
                    throw mismatch(where, raw);
                }
            };
        } catch (SemanticException e) {
            throw e;
        }
    }

    private static SemanticException mismatch(String where, Object raw) {
        return new SemanticException(ErrorCode.FIELD_VALUE_MISSING,
                "字段值类型与定义不符：" + where + "，实际值=" + raw
                        + "（" + raw.getClass().getSimpleName() + "）");
    }

    static boolean supports(AggregationType type, DataType dataType) {
        return switch (type) {
            case COUNT, COUNT_DISTINCT, MIN, MAX -> true;
            case SUM, AVG -> dataType.isNumeric();
        };
    }
}
