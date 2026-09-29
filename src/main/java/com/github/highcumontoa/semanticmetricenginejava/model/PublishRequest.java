package com.github.highcumontoa.semanticmetricenginejava.model;

import java.util.List;

/** 发布新版本的请求。 */
public record PublishRequest(
        String version,
        String parentVersion,
        List<DimensionDef> dimensions,
        List<MetricDef> metrics) {
}
