package com.github.highcumontoa.semanticmetricenginejava.model;

import java.util.List;
import java.util.Map;

/** 查询结果：columns 为输出列顺序，rows 为确定性排序后的行。 */
public record QueryResult(List<String> columns, List<Map<String, Object>> rows) {
}
