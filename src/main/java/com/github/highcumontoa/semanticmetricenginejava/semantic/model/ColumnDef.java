package com.github.highcumontoa.semanticmetricenginejava.semantic.model;

/**
 * 物理表字段定义。
 *
 * @param name 字段名
 * @param type 字段类型
 */
public record ColumnDef(String name, DataType type) {
}
