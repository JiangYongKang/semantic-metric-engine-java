package com.github.highcumontoa.semanticmetricenginejava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端入口验证：成功路径返回稳定 JSON；非法请求返回 4xx 且错误码按原因可区分，
 * 绝不返回 200 + 空结果。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class QueryControllerIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(QueryControllerIntegrationTest.class);

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    private final ObjectMapper mapper = new ObjectMapper();

    private String url(String path) {
        return "http://localhost:" + port + "/api/semantic" + path;
    }

    @Test
    void sampleDataAggregateAndRepeatable() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", "v1");
        body.put("metrics", java.util.List.of("revenue", "net_revenue", "product_count"));
        body.put("dimensions", java.util.List.of("region"));

        ResponseEntity<String> first = post("/query", body);
        ResponseEntity<String> second = post("/query", body);
        log.info("HTTP 输入: {}; 判定: 200 且重复请求响应体逐字节一致", body);
        assertEquals(HttpStatus.OK, first.getStatusCode());
        assertEquals(first.getBody(), second.getBody());
        JsonNode json = mapper.readTree(first.getBody());
        assertTrue(first.getBody().contains("east"));
        assertFalse(json.path("rows").isEmpty());
    }

    @Test
    void rankEndpointProducesRankColumn() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", "v1");
        body.put("metrics", java.util.List.of("revenue"));
        body.put("dimensions", java.util.List.of("product", "day"));
        body.put("timeDimension", "day");
        body.put("timeFunction", "RANK");
        ResponseEntity<String> response = post("/query", body);
        log.info("HTTP 输入: RANK(product,day); 判定: 200 且包含 revenue__rank 列");
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().contains("revenue__rank"));
    }

    @Test
    void rejectionsHaveDistinctCodesOverHttp() throws Exception {
        expectRejected("不存在版本", query("v9", "revenue", "region"), 404, "VERSION_NOT_FOUND");
        expectRejected("不存在指标", query("v1", "ghost", "region"), 404, "METRIC_NOT_FOUND");
        expectRejected("不存在维度", queryDim("v1", "revenue", "ghost"), 404, "DIMENSION_NOT_FOUND");

        Map<String, Object> badWindow = new LinkedHashMap<>();
        badWindow.put("version", "v1");
        badWindow.put("metrics", java.util.List.of("revenue"));
        badWindow.put("dimensions", java.util.List.of("region", "day"));
        badWindow.put("timeDimension", "day");
        badWindow.put("timeFunction", "SLIDING");
        expectRejected("滑动缺窗口", badWindow, 400, "INVALID_WINDOW_SIZE");

        Map<String, Object> cycle = new LinkedHashMap<>();
        cycle.put("version", "http-cycle");
        cycle.put("parentVersion", "v2");
        cycle.put("dimensions", java.util.List.of());
        cycle.put("metrics", java.util.List.of(
                Map.of("name", "a", "table", "sales",
                        "expression", Map.of("kind", "binary", "op", "ADD",
                                "left", Map.of("kind", "ref", "metricName", "b"),
                                "right", Map.of("kind", "literal", "value", 1))),
                Map.of("name", "b", "table", "sales",
                        "expression", Map.of("kind", "binary", "op", "ADD",
                                "left", Map.of("kind", "ref", "metricName", "a"),
                                "right", Map.of("kind", "literal", "value", 1)))));
        expectRejected("HTTP 循环定义", cycle, 400, "CIRCULAR_DEFINITION");
    }

    @Test
    void versionsEndpointListsBothSampleVersions() {
        ResponseEntity<String> response = rest.getForEntity(url("/versions"), String.class);
        log.info("GET /versions 判定: 样例版本 v1,v2 已装载");
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().contains("v1"));
        assertTrue(response.getBody().contains("v2"));
    }

    private Map<String, Object> query(String version, String metric, String dim) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", version);
        body.put("metrics", java.util.List.of(metric));
        body.put("dimensions", java.util.List.of(dim));
        return body;
    }

    private Map<String, Object> queryDim(String version, String metric, String dim) {
        return query(version, metric, dim);
    }

    private void expectRejected(String label, Map<String, Object> body, int httpStatus, String code) throws Exception {
        String path = body.containsKey("dimensions") && body.containsKey("metrics") && body.get("version") != null
                && !code.equals("CIRCULAR_DEFINITION") ? "/query" : "/versions";
        ResponseEntity<String> response = post(code.equals("CIRCULAR_DEFINITION") ? "/versions" : path, body);
        log.info("HTTP 拒绝[{}]: status={} body={}", label, response.getStatusCode().value(), response.getBody());
        assertEquals(httpStatus, response.getStatusCode().value());
        JsonNode json = mapper.readTree(response.getBody());
        assertEquals(code, json.path("errorCode").asText());
        assertTrue(json.path("rejected").asBoolean());
        assertFalse(response.getStatusCode().is2xxSuccessful(), "非法请求绝不能返回成功: " + label);
    }

    private ResponseEntity<String> post(String path, Map<String, Object> body) {
        return rest.postForEntity(url(path), body, String.class);
    }
}
