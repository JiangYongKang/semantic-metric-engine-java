package com.github.highcumontoa.semanticmetricenginejava.web;

import com.github.highcumontoa.semanticmetricenginejava.engine.QueryEngine;
import com.github.highcumontoa.semanticmetricenginejava.model.MetricQuery;
import com.github.highcumontoa.semanticmetricenginejava.model.PublishRequest;
import com.github.highcumontoa.semanticmetricenginejava.model.QueryResult;
import com.github.highcumontoa.semanticmetricenginejava.model.SemanticVersion;
import com.github.highcumontoa.semanticmetricenginejava.registry.SemanticRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 本地调用入口：查询、发布版本、查看版本与最新版本定义。 */
@RestController
@RequestMapping("/api/semantic")
public class QueryController {

    private final QueryEngine engine;
    private final SemanticRegistry registry;

    public QueryController(QueryEngine engine, SemanticRegistry registry) {
        this.engine = engine;
        this.registry = registry;
    }

    /** 执行指标查询；任何非法引用/口径都以 4xx + errorCode 明确拒绝。 */
    @PostMapping("/query")
    public QueryResult query(@RequestBody MetricQuery query) {
        return engine.execute(query);
    }

    /** 发布新的不可变语义版本（非法定义/循环引用会被拒绝）。 */
    @PostMapping("/versions")
    public SemanticVersion publish(@RequestBody PublishRequest request) {
        return registry.publish(request);
    }

    /** 按发布顺序列出全部版本。 */
    @GetMapping("/versions")
    public List<String> versions() {
        return registry.listVersions();
    }

    /** 查看当前最新版本的完整定义快照。 */
    @GetMapping("/versions/latest")
    public Map<String, Object> latest() {
        String latest = registry.latestVersion()
                .orElseThrow(() -> new com.github.highcumontoa.semanticmetricenginejava.model.SemanticException(
                        com.github.highcumontoa.semanticmetricenginejava.model.ErrorCode.VERSION_NOT_FOUND,
                        "尚未发布任何语义版本"));
        SemanticVersion snapshot = registry.getVersion(latest).orElseThrow();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", snapshot.version());
        body.put("parentVersion", snapshot.parentVersion());
        body.put("dimensions", snapshot.dimensions());
        body.put("metrics", snapshot.metrics());
        return body;
    }
}
