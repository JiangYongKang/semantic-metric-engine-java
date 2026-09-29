package com.github.highcumontoa.semanticmetricenginejava.semantic.version;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.github.highcumontoa.semanticmetricenginejava.semantic.engine.ModelValidator;
import com.github.highcumontoa.semanticmetricenginejava.semantic.model.ModelVersion;
import com.github.highcumontoa.semanticmetricenginejava.semantic.error.ErrorCode;
import com.github.highcumontoa.semanticmetricenginejava.semantic.error.SemanticException;

/**
 * 模型版本注册表：保存全部已发布版本快照（不可变，按注册顺序即发布顺序）。
 *
 * <p>兼容性口径：
 * <ul>
 *   <li>查询显式指定 version 时按该版本快照复现历史结论；</li>
 *   <li>不指定时使用最新注册版本；</li>
 *   <li>重命名/删除/口径调整只在新版本生效，旧版本永不改变（不会静默降级）；</li>
 *   <li>重复注册同一版本号按 {@link ErrorCode#DEF_DUPLICATE_NAME} 拒绝。</li>
 * </ul>
 */
public class ModelRegistry {

    private final ModelValidator validator;
    private final Map<String, ModelVersion> byVersion = new ConcurrentHashMap<>();
    private volatile List<String> order = List.of();

    public ModelRegistry(ModelValidator validator) {
        this.validator = validator;
    }

    /** 注册新版本：先校验（非法定义明确拒绝），再以版本号去重保存。 */
    public synchronized void register(ModelVersion model) {
        validator.validate(model);
        if (byVersion.containsKey(model.version())) {
            throw new SemanticException(ErrorCode.DEF_DUPLICATE_NAME,
                    "模型版本号已存在且不可覆盖: '" + model.version()
                            + "'，依据: 历史版本快照必须保持不变");
        }
        byVersion.put(model.version(), model);
        List<String> next = new ArrayList<>(order);
        next.add(model.version());
        order = List.copyOf(next);
    }

    /** 按版本号取快照，不存在则拒绝。 */
    public ModelVersion requireVersion(String version) {
        ModelVersion model = byVersion.get(version);
        if (model == null) {
            throw new SemanticException(ErrorCode.Q_METRIC_NOT_FOUND,
                    "模型版本不存在: '" + version + "'，已发布版本: " + order);
        }
        return model;
    }

    /** 最新注册版本；无版本时拒绝（不返回 null 导致静默失败）。 */
    public ModelVersion latest() {
        List<String> o = order;
        if (o.isEmpty()) {
            throw new SemanticException(ErrorCode.DEF_DUPLICATE_NAME,
                    "尚未注册任何模型版本");
        }
        return byVersion.get(o.get(o.size() - 1));
    }

    /** version 为 null/空白时解析为最新版本，否则按指定版本取快照。 */
    public ModelVersion resolve(String version) {
        return (version == null || version.isBlank()) ? latest() : requireVersion(version);
    }

    /** 按发布顺序返回全部版本号。 */
    public List<String> versions() {
        return order;
    }
}
