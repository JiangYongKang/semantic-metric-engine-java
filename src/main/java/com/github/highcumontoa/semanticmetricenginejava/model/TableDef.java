package com.github.highcumontoa.semanticmetricenginejava.model;

import java.util.List;
import java.util.Optional;

/** 本地表定义。 */
public record TableDef(String name, List<ColumnDef> columns) {

    public TableDef {
        columns = List.copyOf(columns);
    }

    /** 按名称查找列，不存在返回 empty（由调用方按 COLUMN_NOT_FOUND 拒绝）。 */
    public Optional<ColumnDef> column(String columnName) {
        return columns.stream().filter(c -> c.name().equals(columnName)).findFirst();
    }
}
