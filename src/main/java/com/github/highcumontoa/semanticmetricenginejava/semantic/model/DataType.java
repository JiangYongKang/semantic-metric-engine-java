package com.github.highcumontoa.semanticmetricenginejava.semantic.model;

/** 字段值类型。指标聚合只允许发生在 LONG/DOUBLE 上（COUNT 除外）。 */
public enum DataType {
    LONG,
    DOUBLE,
    STRING,
    DATE
}
