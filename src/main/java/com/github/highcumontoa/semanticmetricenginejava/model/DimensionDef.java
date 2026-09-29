package com.github.highcumontoa.semanticmetricenginejava.model;

/**
 * 维度定义：绑定到某张本地表的某个列。
 */
public record DimensionDef(String name, String table, String column) {
}
