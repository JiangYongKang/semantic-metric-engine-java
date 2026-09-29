package com.github.highcumontoa.semanticmetricenginejava.semantic.query;

import com.github.highcumontoa.semanticmetricenginejava.semantic.data.DataSet;
import com.github.highcumontoa.semanticmetricenginejava.semantic.engine.QueryExecutor;
import com.github.highcumontoa.semanticmetricenginejava.semantic.engine.QueryPlanner;
import com.github.highcumontoa.semanticmetricenginejava.semantic.engine.QueryPlanner.Plan;
import com.github.highcumontoa.semanticmetricenginejava.semantic.model.ModelVersion;
import com.github.highcumontoa.semanticmetricenginejava.semantic.version.ModelRegistry;

/**
 * 语义层查询统一入口（本地调用）。
 *
 * <p>流程：解析版本（null=最新） -> 查询计划校验（非法引用/算法组合明确拒绝）
 * -> 本地数据集确定性执行。同一请求重复执行结果一致。</p>
 */
public class QueryService {

    private final ModelRegistry registry;
    private final QueryPlanner planner;
    private final QueryExecutor executor;

    public QueryService(ModelRegistry registry, DataSet dataSet) {
        this.registry = registry;
        this.planner = new QueryPlanner();
        this.executor = new QueryExecutor(dataSet);
    }

    /** 执行查询。 */
    public QueryResult query(QueryRequest request) {
        ModelVersion model = registry.resolve(request.version());
        Plan plan = planner.plan(request, model);
        return executor.execute(plan);
    }
}
