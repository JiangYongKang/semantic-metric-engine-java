package com.github.highcumontoa.semanticmetricenginejava.registry;

import com.github.highcumontoa.semanticmetricenginejava.model.LocalTable;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 本地表数据注册表（内存）。表名唯一，重复注册以表结构不一致为准直接拒绝。 */
@Component
public class LocalTableRegistry {

    private final Map<String, LocalTable> tables = new ConcurrentHashMap<>();

    public void register(LocalTable table) {
        LocalTable existing = tables.putIfAbsent(table.def().name(), table);
        if (existing != null && !existing.def().equals(table.def())) {
            throw new IllegalArgumentException("本地表结构冲突，不允许重复注册不同结构: " + table.def().name());
        }
    }

    public Optional<LocalTable> get(String tableName) {
        return Optional.ofNullable(tables.get(tableName));
    }
}
