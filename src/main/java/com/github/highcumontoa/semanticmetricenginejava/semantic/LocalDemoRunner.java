package com.github.highcumontoa.semanticmetricenginejava.semantic;

import java.util.List;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import com.github.highcumontoa.semanticmetricenginejava.semantic.query.QueryRequest;
import com.github.highcumontoa.semanticmetricenginejava.semantic.query.QueryResult;
import com.github.highcumontoa.semanticmetricenginejava.semantic.query.QueryService;

/**
 * 本地验证入口：应用启动后自动执行一组演示查询并打印结果
 * （可用 --semantic.demo=false 关闭）。也可在任意 Java 代码中直接注入
 * {@link QueryService} 本地调用。
 */
@Component
public class LocalDemoRunner implements ApplicationRunner {

    private final QueryService queryService;

    public LocalDemoRunner(QueryService queryService) {
        this.queryService = queryService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (args.containsOption("semantic.demo")
                && "false".equals(args.getOptionValues("semantic.demo").get(0))) {
            return;
        }
        print("v1 按月累计 GMV", new QueryRequest(
                List.of("gmv_cum"), List.of("tx_date_month"), "v1"));
        print("v2 按月滑动3桶 sales_amount", new QueryRequest(
                List.of("sales_amount_roll3"), List.of("tx_date_month"), "v2"));
        print("v1 区域 GMV 竞争排名", new QueryRequest(
                List.of("amount_rank"), List.of("region"), "v1"));
        print("版本口径对比 v1 order_count=COUNT(*)",
                new QueryRequest(List.of("order_count"), List.of(), "v1"));
        print("版本口径对比 v2 order_count=COUNT_DISTINCT(order_id)",
                new QueryRequest(List.of("order_count"), List.of(), "v2"));
    }

    private void print(String title, QueryRequest request) {
        System.out.println("\n==== " + title + " ====");
        QueryResult result = queryService.query(request);
        System.out.println("version=" + result.version() + " columns=" + result.columns());
        result.rows().forEach(System.out::println);
    }
}
