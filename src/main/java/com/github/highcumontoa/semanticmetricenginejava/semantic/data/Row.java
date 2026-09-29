package com.github.highcumontoa.semanticmetricenginejava.semantic.data;

import java.util.Map;

/**
 * 一行本地表数据：列名 -> 强类型值（null 表示空值）。
 */
public record Row(Map<String, Object> cells) {

    public Object get(String column) {
        return cells.get(column);
    }
}
