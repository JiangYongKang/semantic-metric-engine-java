package com.github.highcumontoa.semanticmetricenginejava.semantic.api;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.github.highcumontoa.semanticmetricenginejava.semantic.model.ModelVersion;
import com.github.highcumontoa.semanticmetricenginejava.semantic.query.QueryRequest;
import com.github.highcumontoa.semanticmetricenginejava.semantic.query.QueryResult;
import com.github.highcumontoa.semanticmetricenginejava.semantic.query.QueryService;
import com.github.highcumontoa.semanticmetricenginejava.semantic.version.ModelRegistry;

/**
 * 本地可调用/验证入口：
 * <ul>
 *   <li>POST /api/semantic/query —— body: {"metrics":[...],"dimensions":[...],"version":"v1"}；</li>
 *   <li>GET  /api/semantic/query?metrics=a,b&dimensions=c&version=v1 —— 浏览器/curl 友好；</li>
 *   <li>GET  /api/semantic/models —— 查看全部版本定义与发布说明。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/semantic")
public class QueryController {

    private final QueryService queryService;
    private final ModelRegistry registry;

    public QueryController(QueryService queryService, ModelRegistry registry) {
        this.queryService = queryService;
        this.registry = registry;
    }

    @PostMapping("/query")
    public QueryResult postQuery(@RequestBody QueryRequest request) {
        return queryService.query(request);
    }

    @GetMapping("/query")
    public QueryResult getQuery(@RequestParam String metrics,
                                @RequestParam(required = false) String dimensions,
                                @RequestParam(required = false) String version) {
        List<String> metricList = split(metrics);
        List<String> dimList = dimensions == null || dimensions.isBlank()
                ? List.of() : split(dimensions);
        return queryService.query(new QueryRequest(metricList, dimList,
                (version == null || version.isBlank()) ? null : version.trim()));
    }

    @GetMapping("/models")
    public Map<String, Object> models() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("versions", registry.versions());
        List<Map<String, Object>> details = new java.util.ArrayList<>();
        for (String v : registry.versions()) {
            ModelVersion model = registry.requireVersion(v);
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("version", model.version());
            detail.put("releaseNote", model.releaseNote());
            detail.put("dimensions", model.dimensions());
            detail.put("metrics", model.metrics());
            details.add(detail);
        }
        out.put("models", details);
        return out;
    }

    private static List<String> split(String csv) {
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
