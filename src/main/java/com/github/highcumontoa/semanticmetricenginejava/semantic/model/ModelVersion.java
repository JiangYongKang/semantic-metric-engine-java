package com.github.highcumontoa.semanticmetricenginejava.semantic.model;

import java.util.List;

/**
 * 一个完整的语义层模型版本（快照，不可变）。
 *
 * <p>版本是全量快照而非增量：给定一个 version 即可完全复现历史查询，
 * 重命名/删除/口径调整都通过新版本体现，旧版本永不被覆盖。</p>
 *
 * @param version       版本号（字符串，按注册顺序即发布顺序）
 * @param tables        物理表定义
 * @param dimensions    维度定义
 * @param metrics       指标定义
 * @param releaseNote   版本说明（记录重命名/删除/口径调整等兼容信息）
 */
public record ModelVersion(String version,
                           List<TableDef> tables,
                           List<DimensionDef> dimensions,
                           List<MetricDef> metrics,
                           String releaseNote) {
}
