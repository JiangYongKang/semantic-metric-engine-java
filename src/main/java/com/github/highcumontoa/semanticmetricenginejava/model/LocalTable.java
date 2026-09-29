package com.github.highcumontoa.semanticmetricenginejava.model;

import java.util.List;
import java.util.Map;

/** 本地表：表结构 + 内存行数据（行 = 列名到值的映射，缺失列按 null 处理）。 */
public record LocalTable(TableDef def, List<Map<String, Object>> rows) {
}
