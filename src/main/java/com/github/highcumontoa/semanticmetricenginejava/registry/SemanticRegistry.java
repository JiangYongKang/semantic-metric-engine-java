package com.github.highcumontoa.semanticmetricenginejava.registry;

import com.github.highcumontoa.semanticmetricenginejava.model.DimensionDef;
import com.github.highcumontoa.semanticmetricenginejava.model.ErrorCode;
import com.github.highcumontoa.semanticmetricenginejava.model.MetricDef;
import com.github.highcumontoa.semanticmetricenginejava.model.PublishRequest;
import com.github.highcumontoa.semanticmetricenginejava.model.SemanticException;
import com.github.highcumontoa.semanticmetricenginejava.model.SemanticVersion;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * 语义定义注册表。
 *
 * <p>每次发布生成一个不可变 {@link SemanticVersion} 快照并永久保留：
 * 重命名/删除/口径调整都只影响新版本，按旧版本号查询仍可复现历史结论。</p>
 */
@Component
public class SemanticRegistry {

    private final Map<String, SemanticVersion> versions = new TreeMap<>();
    private final List<String> publishOrder = new ArrayList<>();
    private final DefinitionValidator validator;

    public SemanticRegistry(LocalTableRegistry tables) {
        this.validator = new DefinitionValidator(tables);
    }

    /** 发布新版本。非法定义 / 版本冲突 / 父版本不存在都会被明确拒绝。 */
    public synchronized SemanticVersion publish(PublishRequest request) {
        if (request == null || request.version() == null || request.version().isBlank()) {
            throw new SemanticException(ErrorCode.INVALID_REQUEST, "发布请求必须包含非空 version");
        }
        String version = request.version().trim();
        if (versions.containsKey(version)) {
            throw new SemanticException(ErrorCode.VERSION_ALREADY_EXISTS, "版本已存在，不可覆盖: " + version);
        }
        if (!publishOrder.isEmpty() && (request.parentVersion() == null || request.parentVersion().isBlank())) {
            throw new SemanticException(ErrorCode.INVALID_REQUEST,
                    "非首个版本必须声明 parentVersion（新版本 " + version + "）");
        }
        SemanticVersion parent = null;
        if (request.parentVersion() != null && !request.parentVersion().isBlank()) {
            parent = versions.get(request.parentVersion());
            if (parent == null) {
                throw new SemanticException(ErrorCode.VERSION_PARENT_NOT_FOUND,
                        "父版本不存在: " + request.parentVersion());
            }
        }

        List<DimensionDef> dimensions = request.dimensions() == null ? List.of() : request.dimensions();
        List<MetricDef> metrics = request.metrics() == null ? List.of() : request.metrics();

        // 定义期全部硬性校验（列存在性/聚合合法性/引用合法性/循环定义）
        validator.validate(dimensions, metrics);

        // renameOf 血缘校验：声明重命名来源时，父版本中必须能找到旧指标
        if (parent != null) {
            for (MetricDef metric : metrics) {
                if (metric.renameOf() != null && !metric.renameOf().isBlank()
                        && !parent.metrics().containsKey(metric.renameOf())) {
                    throw new SemanticException(ErrorCode.INVALID_REQUEST,
                            "指标 " + metric.name() + " 声明 renameOf=" + metric.renameOf()
                                    + "，但父版本 " + parent.version() + " 中不存在该指标");
                }
            }
        }

        // 名称排序入快照，保证版本内容展示与遍历顺序确定
        Map<String, DimensionDef> dimMap = new TreeMap<>();
        dimensions.forEach(d -> dimMap.put(d.name(), d));
        Map<String, MetricDef> metricMap = new TreeMap<>();
        metrics.forEach(m -> metricMap.put(m.name(), m));

        SemanticVersion snapshot = new SemanticVersion(
                version,
                parent == null ? null : parent.version(),
                System.currentTimeMillis(),
                Map.copyOf(new LinkedHashMap<>(dimMap)),
                Map.copyOf(new LinkedHashMap<>(metricMap)));
        versions.put(version, snapshot);
        publishOrder.add(version);
        return snapshot;
    }

    public Optional<SemanticVersion> getVersion(String version) {
        return Optional.ofNullable(versions.get(version));
    }

    /** 按版本号解析；传入 null 解析为最新发布版本，均不存在时明确拒绝。 */
    public SemanticVersion resolve(String versionOrNull) {
        if (versionOrNull == null || versionOrNull.isBlank()) {
            if (publishOrder.isEmpty()) {
                throw new SemanticException(ErrorCode.VERSION_NOT_FOUND, "尚未发布任何语义版本");
            }
            return versions.get(publishOrder.get(publishOrder.size() - 1));
        }
        SemanticVersion snapshot = versions.get(versionOrNull);
        if (snapshot == null) {
            throw new SemanticException(ErrorCode.VERSION_NOT_FOUND, "语义版本不存在: " + versionOrNull);
        }
        return snapshot;
    }

    public synchronized List<String> listVersions() {
        return List.copyOf(publishOrder);
    }

    public Optional<String> latestVersion() {
        return publishOrder.isEmpty()
                ? Optional.empty()
                : Optional.of(publishOrder.get(publishOrder.size() - 1));
    }
}
