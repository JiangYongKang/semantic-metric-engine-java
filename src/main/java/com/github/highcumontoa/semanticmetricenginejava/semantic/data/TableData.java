package com.github.highcumontoa.semanticmetricenginejava.semantic.data;

import java.util.List;

import com.github.highcumontoa.semanticmetricenginejava.semantic.model.TableDef;

/**
 * 一张本地物理表的全部数据（表定义 + 行）。
 */
public record TableData(TableDef def, List<Row> rows) {

    public String name() {
        return def.name();
    }
}
