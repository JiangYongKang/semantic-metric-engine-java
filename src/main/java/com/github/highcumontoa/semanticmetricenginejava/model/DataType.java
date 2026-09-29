package com.github.highcumontoa.semanticmetricenginejava.model;

/** 字段值类型。 */
public enum DataType {
    /** 字符串，Java 侧用 String 表示。 */
    STRING,
    /** 整数，Java 侧用 Long 表示。 */
    LONG,
    /** 浮点，Java 侧用 Double 表示（LONG 列也允许参与数值运算）。 */
    DOUBLE,
    /** 日期，Java 侧用 String "yyyy-MM-dd" 表示，字典序即时间序。 */
    DATE;

    /** 可参与 SUM/AVG 等数值运算。 */
    public boolean isNumeric() {
        return this == LONG || this == DOUBLE;
    }

    /** 可作为时间维度（累计/滑动/排名的分区排序键）。 */
    public boolean isTemporal() {
        return this == DATE;
    }
}
