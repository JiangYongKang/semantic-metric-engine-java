package com.github.highcumontoa.semanticmetricenginejava.semantic.seed;

import java.util.List;

import com.github.highcumontoa.semanticmetricenginejava.semantic.model.ColumnDef;
import com.github.highcumontoa.semanticmetricenginejava.semantic.model.DataType;
import com.github.highcumontoa.semanticmetricenginejava.semantic.model.TableDef;

/** 本地演示表 sales 的表结构与列名常量。 */
public final class SalesTable {

    public static final String NAME = "sales";

    public static final String C_REGION = "region";
    public static final String C_CHANNEL = "channel";
    public static final String C_TX_DATE = "tx_date";
    public static final String C_AMOUNT = "amount";
    public static final String C_QTY = "qty";
    public static final String C_ORDER_ID = "order_id";

    private SalesTable() {
    }

    public static TableDef def() {
        return new TableDef(NAME, List.of(
                new ColumnDef(C_REGION, DataType.STRING),
                new ColumnDef(C_CHANNEL, DataType.STRING),
                new ColumnDef(C_TX_DATE, DataType.DATE),
                new ColumnDef(C_AMOUNT, DataType.DOUBLE),
                new ColumnDef(C_QTY, DataType.LONG),
                new ColumnDef(C_ORDER_ID, DataType.STRING)
        ));
    }
}
