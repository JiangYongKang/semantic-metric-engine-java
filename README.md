# 本地语义层指标查询引擎（semantic-metric-engine-java-bootstrap-b）

给引擎一套统一的**指标 / 维度定义**，即可用工程内置的**本地内存表数据**完成指标查询：
分组聚合、累计、滑动窗口、分组排名；并对定义的版本变化（重命名 / 删除 / 口径调整）做兼容。
所有非法输入都按**可区分的原因明确拒绝**（返回错误码），绝不静默返回一个看似正常的结果。

## 1. 快速开始

```bash
mvn spring-boot:run          # 默认端口 18080（见 src/main/resources/application.properties）
mvn test                     # 运行全部 27 个测试
```

启动后自动装载样例：本地表 `sales` 与两个语义版本 `v1`、`v2`。

最简单的一次调用：

```bash
curl -s -X POST http://localhost:18080/api/semantic/query \
  -H 'Content-Type: application/json' \
  -d '{"version":"v1","metrics":["revenue","order_count","net_revenue"],"dimensions":["region"]}'
```

## 2. 指标与维度模型

| 概念 | 载体 | 说明 |
|---|---|---|
| 本地表 | `LocalTable(TableDef, rows)` | 列定义 + 内存行；一行是 `列名 -> 值` 的映射 |
| 类型 | `DataType` | `STRING / LONG / DOUBLE / DATE`（DATE 用 `yyyy-MM-dd` 字符串，字典序即时间序） |
| 维度 | `DimensionDef` | `name` + 绑定表列 `table.column`；分组键 |
| 基础指标 | `MetricDef` | 聚合方式 `SUM/COUNT/COUNT_DISTINCT/AVG/MIN/MAX` + 被聚合列；`COUNT` 允许空列表示 `COUNT(*)` |
| 派生指标 | `MetricDef(expression=...)` | 表达式仅支持 `ref（引用指标）/ literal（常量）/ binary（+,-,*,/）`；只能引用**同表**指标 |
| 语义版本 | `SemanticVersion` | 一次发布生成**不可变快照**永久保留；新版本必须声明 `parentVersion`（首个版本除外） |

派生表达式 JSON 形态（发布接口用）：

```json
{"kind":"binary","op":"MUL","left":{"kind":"ref","metricName":"revenue"},"right":{"kind":"literal","value":0.1}}
```

## 3. 口径规则（固定且稳定）

### 3.1 空值与缺失字段（不静默降级）

- 行数据里**缺列键**：`FIELD_VALUE_MISSING`，拒绝（不会静默当成 null）。
- 列**值类型与定义不符**：`FIELD_VALUE_MISSING`，拒绝（不做隐式转换）。
- 分组 / 时间维度列出现 **null**：`FIELD_VALUE_MISSING`，拒绝（不静默归入 null 分组）。
- 指标列值为 **null**：除 `COUNT(*)` 外所有聚合**跳过 null**。
- 无任何非空输入：`COUNT/COUNT_DISTINCT` 返回 `0`；`SUM/AVG/MIN/MAX` 返回 `null`（空集合不归零）。
- 派生算术：任一操作数为 null → 结果 null；除数为 0 → 结果 null（不抛异常）。

### 3.2 聚合与类型

- `SUM/AVG` 仅可用于 `LONG/DOUBLE`，对 `STRING/DATE` 使用为 `INVALID_AGGREGATION`。
- `SUM/AVG` 统一输出 `double`；`COUNT*` 输出 `long`。
- `MIN/MAX` 结果类型跟随列（数值按 double 比较，字符串/日期按字典序）。

### 3.3 时间算法（`timeFunction`）

时间算法要求 `timeDimension` 是一个已分组的 `DATE` 维度，方向沿日期字典序。

- **CUMULATIVE（累计）**：每个分区内，截至当前期的**全量原始行**重算。
  例：分区 `east`：d1=10，d2 行金额为 null → 累计仍为 10，d3 再加 30 → 40。
- **SLIDING（滑动窗口）**：`windowSize=k`（必填、≥1，否则 `INVALID_WINDOW_SIZE`），
  窗口为当前期及之前最多 k 个**有数据的期**，在窗口原始行上重算；
  首窗只有首期；窗口**不跨分区借用数据**。
- **RANK（排名）**：分区内**标准竞赛排名**（1,2,2,4：并列共享名次，跳过后继名次）。
  默认 `DESC`（可 `ASC`）；**null 指标值永远排末尾并相互并列**；
  同值并列时再按时间期字典序稳定排列（只影响行序，不影响名次）。

**分批执行一致性（核心约定）**：时间算法始终基于**全量行**计算，
`filters` 只裁剪“输出哪些行”，不改变窗口 / 排名的输入。
因此整段查询与按 filter 拆开分批执行、再合并后的结果完全一致；
同一条查询重复执行结果逐字节相等（单次顺序扫描、组内保留行序、所有遍历按名称/日期排序）。

### 3.4 分组边界与输出稳定性

- 一次查询的全部维度、指标必须落在**同一张本地表**，否则 `MULTIPLE_TABLES`。
- `dimensions` 为空表示全局单行聚合。
- 输出列顺序：分组维度（按请求顺序）+ 指标（按请求顺序），RANK 额外输出 `指标名__rank`。
- 行排序：普通/累计/滑动按（分区维度, 时间期正序）；RANK 按（分区维度, 首个指标名次, 时间期）。

## 4. 版本兼容规则

每次 `POST /versions` 生成不可变快照：

- **重命名**：新版本用新名字定义指标，并声明 `renameOf=旧名`；父版本中不存在旧名会被拒绝。
- **删除**：直接在新版本移除。引用旧版本号仍可复现历史结论；
  在新版本引用已删除指标 → `METRIC_NOT_FOUND`，不静默降级。
- **口径调整**：同名指标（如 `tax`，税率 0.1→0.2）结果按版本区分；历史版本结果永久可复现。
- 样例故事：`v1` 有 `revenue/order_count/tax(10%)`；
  `v2` 把 `revenue` 重命名为 `total_revenue`、删除 `order_count`、`tax` 改为 20%。

## 5. 错误码（拒绝原因可区分）

| errorCode | 触发场景 | HTTP |
|---|---|---|
| `VERSION_NOT_FOUND` / `VERSION_PARENT_NOT_FOUND` | 查询版本不存在 / 发布时父版本不存在 | 404 |
| `METRIC_NOT_FOUND` / `DIMENSION_NOT_FOUND` | 引用版本中不存在的指标 / 维度（含已删除、改名后引用旧名） | 404 |
| `TABLE_NOT_FOUND` / `COLUMN_NOT_FOUND` | 定义引用了不存在的表 / 列 | 404/400 |
| `FIELD_VALUE_MISSING` | 缺列键、维度空值、值类型与定义不符 | 400 |
| `INVALID_AGGREGATION` | 聚合方式与列类型不匹配、缺被聚合列、派生引用非数值指标 | 400 |
| `INVALID_TIME_FUNCTION` / `INVALID_WINDOW_SIZE` | 时间维度缺失/非 DATE/未分组；窗口参数非法 | 400 |
| `INVALID_EXPRESSION_REFERENCE` | 派生表达式引用未定义或跨表指标 | 400 |
| `CIRCULAR_DEFINITION` | 派生指标引用成环（含自引用），消息给出成环节点链 | 400 |
| `DUPLICATE_DEFINITION` / `VERSION_ALREADY_EXISTS` | 同次发布重名 / 版本号重复 | 400 |
| `MULTIPLE_TABLES` / `INVALID_REQUEST` | 跨表查询 / 其它请求形态错误 | 400 |

错误响应统一形如：`{"rejected":true,"errorCode":"...","message":"...（含判定依据）"}`。

## 6. HTTP 入口

| 方法与路径 | 作用 |
|---|---|
| `POST /api/semantic/query` | 执行指标查询 |
| `POST /api/semantic/versions` | 发布新语义版本（非法定义/循环引用会被拒绝） |
| `GET  /api/semantic/versions` | 按发布顺序列出版本 |
| `GET  /api/semantic/versions/latest` | 查看最新版本完整定义快照 |

### 6.1 查询请求字段

`version`（null=最新）、`metrics[]`、`dimensions[]`、
`timeDimension`、`timeFunction`（`NONE/CUMULATIVE/SLIDING/RANK`）、
`windowSize`、`rankOrder`（`ASC/DESC`，默认 DESC）、`filters`（维度等值，只裁输出行）。

### 6.2 常用验证示例

```bash
# 分组聚合（v1）
curl -s -X POST localhost:18080/api/semantic/query -H 'Content-Type: application/json' \
  -d '{"version":"v1","metrics":["revenue","order_count","net_revenue"],"dimensions":["region"]}'

# 累计 / 滑动 / 排名
curl -s -X POST localhost:18080/api/semantic/query -H 'Content-Type: application/json' \
  -d '{"version":"v1","metrics":["revenue"],"dimensions":["region","day"],"timeDimension":"day","timeFunction":"CUMULATIVE"}'
curl -s -X POST localhost:18080/api/semantic/query -H 'Content-Type: application/json' \
  -d '{"version":"v1","metrics":["revenue"],"dimensions":["region","day"],"timeDimension":"day","timeFunction":"SLIDING","windowSize":2}'
curl -s -X POST localhost:18080/api/semantic/query -H 'Content-Type: application/json' \
  -d '{"version":"v1","metrics":["revenue"],"dimensions":["product","day"],"timeDimension":"day","timeFunction":"RANK"}'

# 分批（等价于上面累计查询中只取 east 分区，窗口仍基于全量数据）
curl -s -X POST localhost:18080/api/semantic/query -H 'Content-Type: application/json' \
  -d '{"version":"v1","metrics":["revenue"],"dimensions":["region","day"],"timeDimension":"day","timeFunction":"CUMULATIVE","filters":{"region":"east"}}'

# 版本对比：v1 tax=10%，v2 同名 tax=20%（按版本区分）
curl -s -X POST localhost:18080/api/semantic/query -H 'Content-Type: application/json' \
  -d '{"version":"v1","metrics":["revenue","tax"],"dimensions":[]}'
curl -s -X POST localhost:18080/api/semantic/query -H 'Content-Type: application/json' \
  -d '{"version":"v2","metrics":["total_revenue","tax"],"dimensions":[]}'

# 明确拒绝（注意返回的 errorCode 各不同）
curl -s -X POST localhost:18080/api/semantic/query -H 'Content-Type: application/json' \
  -d '{"version":"v2","metrics":["order_count"],"dimensions":["region"]}'
```

## 7. 测试与验证方法

`mvn test` 共 27 个用例（surefire XML 报告内含每条用例打印的**输入与判定依据**日志）：

- `AggregatorsTest`：空值跳过、全空输入、缺列键、类型不匹配、COUNT DISTINCT/MIN/MAX。
- `DefinitionValidatorTest`：非法聚合、列/表缺失、派生引用未定义/跨表、循环与自引用、
  重名、重复版本、父版本缺失、虚假 renameOf。
- `QueryEngineTest`：未知指标/维度/版本的区分化拒绝、时间算法参数非法、
  空值聚合口径、维度 null 拒绝、缺列键拒绝、重复执行一致。
- `TimeFunctionAndVersionTest`：累计（含空值期）、滑动首窗/分区边界/不跨分区、
  竞赛排名 1,2,2,4 与 null 置尾并列、整段与分批一致、重命名/删除/口径调整的版本行为。
- `QueryControllerIntegrationTest`：真实 HTTP 端到端验证成功路径与各类错误码/状态码。

## 8. 关键类索引

| 包/类 | 职责 |
|---|---|
| `model/*` | 类型、表、维度/指标、表达式、版本、查询/结果、错误码与异常 |
| `registry/LocalTableRegistry` | 本地内存表注册 |
| `registry/SemanticRegistry` | 版本快照发布 / 解析 / 重命名血缘校验 |
| `registry/DefinitionValidator` | 定义期校验与 DFS 循环检测 |
| `engine/Aggregators` | 聚合原语与空值/类型规则 |
| `engine/ExpressionEvaluator` | 派生表达式求值（null/除零规则） |
| `engine/QueryEngine` | 查询校验、确定性分组聚合、累计/滑动/排名 |
| `data/SampleDataLoader` | 启动时装载样例表与 v1/v2 |
| `web/QueryController` / `GlobalExceptionHandler` | 本地 HTTP 入口与统一拒绝响应 |
