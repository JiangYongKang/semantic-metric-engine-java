package com.github.highcumontoa.semanticmetricenginejava.semantic.error;

/**
 * 拒绝原因码。非法定义与非法查询必须给出可区分的原因，禁止静默返回结果。
 */
public enum ErrorCode {
    // ---- 定义期（注册模型时）----
    DEF_DUPLICATE_NAME,
    DEF_TABLE_NOT_FOUND,
    DEF_COLUMN_NOT_FOUND,
    DEF_COLUMN_TYPE_MISMATCH,
    DEF_AGG_TYPE_UNSUPPORTED,
    DEF_ROLLING_WINDOW_INVALID,
    DEF_GRANULARITY_INVALID,
    DEF_METRIC_SOURCE_NOT_FOUND,
    DEF_CIRCULAR_REFERENCE,

    // ---- 查询期 ----
    Q_METRIC_NOT_FOUND,
    Q_DIMENSION_NOT_FOUND,
    Q_EMPTY_METRICS,
    Q_MULTIPLE_TABLES,
    Q_AGGREGATE_NOT_NUMERIC,
    Q_ALGORITHM_MIX_CONFLICT,
    Q_ALGORITHM_REQUIRES_DATE_DIM,
    Q_DATE_GRANULARITY_INVALID,

    // ---- 数据期 ----
    DATA_CSV_BAD_HEADER,
    DATA_CSV_ROW_WIDTH_MISMATCH,
    DATA_CSV_BAD_VALUE
}
