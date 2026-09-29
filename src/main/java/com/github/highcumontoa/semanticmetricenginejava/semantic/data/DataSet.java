package com.github.highcumontoa.semanticmetricenginejava.semantic.data;

import java.util.Map;
import java.util.Optional;

/**
 * 本地数据集：表名 -> 表数据。
 */
public class DataSet {

    private final Map<String, TableData> tables;

    public DataSet(Map<String, TableData> tables) {
        this.tables = Map.copyOf(tables);
    }

    public Optional<TableData> table(String name) {
        return Optional.ofNullable(tables.get(name));
    }

    public TableData requireTable(String name) {
        return table(name).orElseThrow(() ->
                new IllegalArgumentException("table not found: " + name));
    }
}
