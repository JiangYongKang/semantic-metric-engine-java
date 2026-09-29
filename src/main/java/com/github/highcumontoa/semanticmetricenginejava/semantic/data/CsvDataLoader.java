package com.github.highcumontoa.semanticmetricenginejava.semantic.data;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.core.io.ClassPathResource;

import com.github.highcumontoa.semanticmetricenginejava.semantic.model.ColumnDef;
import com.github.highcumontoa.semanticmetricenginejava.semantic.model.TableDef;
import com.github.highcumontoa.semanticmetricenginejava.semantic.error.ErrorCode;
import com.github.highcumontoa.semanticmetricenginejava.semantic.error.SemanticException;

/**
 * 从 classpath data/ 目录加载 CSV 本地表数据。
 *
 * <p>规则：第一行为表头，列名与顺序必须与表定义一致；数据行列数必须一致；
 * 值无法解析按 DATA_CSV_BAD_VALUE 拒绝。空行跳过（不视为一行数据）。</p>
 */
public class CsvDataLoader {

    /** 从 classpath:data/{tableName}.csv 加载。 */
    public TableData load(TableDef def) {
        String location = "data/" + def.name() + ".csv";
        List<String> lines;
        try {
            byte[] bytes = new ClassPathResource(location).getInputStream().readAllBytes();
            String content = new String(bytes, StandardCharsets.UTF_8)
                    .replace("﻿", "");
            lines = content.lines().toList();
        } catch (IOException e) {
            throw new UncheckedIOException("无法读取数据文件 " + location, e);
        }
        if (lines.isEmpty()) {
            throw new SemanticException(ErrorCode.DATA_CSV_BAD_HEADER,
                    "数据文件 " + location + " 为空，缺少表头");
        }

        List<String> headers = splitCsv(lines.get(0));
        if (headers.size() != def.columns().size()) {
            throw new SemanticException(ErrorCode.DATA_CSV_BAD_HEADER,
                    "表头列数(" + headers.size() + ")与表定义列数("
                            + def.columns().size() + ")不一致: " + location);
        }
        for (int i = 0; i < headers.size(); i++) {
            String expected = def.columns().get(i).name();
            if (!expected.equals(headers.get(i).trim())) {
                throw new SemanticException(ErrorCode.DATA_CSV_BAD_HEADER,
                        "表头第 " + (i + 1) + " 列应为 '" + expected + "'，实际为 '"
                                + headers.get(i).trim() + "': " + location);
            }
        }

        List<Row> rows = new ArrayList<>();
        for (int li = 1; li < lines.size(); li++) {
            String raw = lines.get(li);
            if (raw.trim().isEmpty()) {
                continue;
            }
            List<String> fields = splitCsv(raw);
            if (fields.size() != def.columns().size()) {
                throw new SemanticException(ErrorCode.DATA_CSV_ROW_WIDTH_MISMATCH,
                        "第 " + (li + 1) + " 行列数 " + fields.size()
                                + " 与表头列数 " + def.columns().size() + " 不一致");
            }
            Map<String, Object> cells = new LinkedHashMap<>();
            for (int ci = 0; ci < def.columns().size(); ci++) {
                ColumnDef col = def.columns().get(ci);
                cells.put(col.name(), Values.parse(fields.get(ci), col.type()));
            }
            rows.add(new Row(cells));
        }
        return new TableData(def, rows);
    }

    /** 极简 CSV 切分：按逗号切，去除每段首尾空白；不支持引号转义（本地演示数据约定）。 */
    private static List<String> splitCsv(String line) {
        List<String> out = new ArrayList<>();
        for (String part : line.split(",", -1)) {
            out.add(part.trim());
        }
        return out;
    }
}
