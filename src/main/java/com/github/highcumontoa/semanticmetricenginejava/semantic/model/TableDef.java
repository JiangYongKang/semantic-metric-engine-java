package com.github.highcumontoa.semanticmetricenginejava.semantic.model;

import java.util.List;

/**
 * 物理表定义。
 *
 * @param name    表名
 * @param columns 字段列表（顺序即输出列顺序）
 */
public record TableDef(String name, List<ColumnDef> columns) {
}
