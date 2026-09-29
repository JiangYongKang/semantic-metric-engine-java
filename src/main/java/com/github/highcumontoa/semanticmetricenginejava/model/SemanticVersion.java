package com.github.highcumontoa.semanticmetricenginejava.model;

import java.util.Map;

/** 一次发布后不可变的语义定义快照，历史版本永久保留以复现历史结论。 */
public record SemanticVersion(
        String version,
        String parentVersion,
        long createdAtEpochMillis,
        Map<String, DimensionDef> dimensions,
        Map<String, MetricDef> metrics) {
}
