# 本地语义层指标查询引擎

一个无需外部数据库、**本地即可跑起来**的语义层（Semantic Layer）：给定统一的指标与维度定义，
直接对 classpath 下的本地 CSV 表数据做聚合查询；支持按维度分组、累计/滑动窗口/排名算法、
模型版本兼容，并提供 HTTP 与 Java 两种本地调用/验证入口。

## 1. 快速开始

```bash
# 运行全部测试（40 个，重点覆盖非法拒绝、确定性、版本兼容）
mvn test

# 启动应用（默认 8080；下例用 19090）。启动后 LocalDemoRunner 会自动打印一组演示查询
mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=19090"
# 关闭启动演示：追加  --semantic.demo=false
```

浏览器 / curl 验证：

```bash
# GET：浏览器直接打开即可
curl "http://localhost:19090/api/semantic/query?metrics=gmv_cum&dimensions=tx_date_month&version=v1"

# POST
curl -X POST http://localhost:19090/api/semantic/query \
  -H 'Content-Type: application/json' \
  -d '{"metrics":["amount_rank"],"dimensions":["region"],"version":"v2"}'

# 查看全部版本的指标/维度定义与发布说明
curl "http://localhost:19090/api/semantic/models"
```

Java 本地调用：

```java
QueryResult r = queryService.query(
        new QueryRequest(List.of("sales_amount", "order_count"),
                         List.of("region", "tx_date_month"), "v2"));
```

## 2. 指标与维度模型

所有定义位于 `semantic.model` 包，模型按**版本快照**组织（`ModelVersion`）。

### 2.1 物理层
- `TableDef(name, columns)` / `ColumnDef(name, DataType)`
- `DataType`：`LONG / DOUBLE / STRING / DATE`
- 本地数据：`src/main/resources/data/<表名>.csv`，首行表头必须与表定义的列名和顺序一致。

### 2.2 维度 `DimensionDef`
| 字段 | 含义 |
|---|---|
| `name` | 维度业务名（查询引用名） |
| `table` / `expression` | 来源表 / 物理字段名（本版本只支持直接字段引用） |
| `dataType` | 输出类型，必须与物理字段一致 |
| `granularity` | 仅 DATE 维度使用：`DAY/MONTH/YEAR`，分桶为日/月首日/年首日 |

### 2.3 指标 `MetricDef`
| 字段 | 含义 |
|---|---|
| `name` | 指标业务名 |
| `table` / `expression` | 来源表 / 聚合输入字段；`COUNT(*)` 时 expression 为 null |
| `aggType` | `SUM / COUNT / COUNT_DISTINCT / AVG / MIN / MAX` |
| `algorithm` | `NONE / CUMULATIVE / ROLLING / RANK` |
| `windowSize` | 仅 `ROLLING`：含当前桶在内的窗口桶数，必须为正整数 |
| `sourceMetric` | 非空时为**派生指标**（别名链），只能声明 name/sourceMetric |

派生指标沿 `sourceMetric` 解析到根物理指标；解析结果以**请求时的别名作为输出列名**。

### 2.4 请求与结果
- `QueryRequest(metrics, dimensions, version)`：metrics 必填；version 为 null 时取最新版本。
- `QueryResult(version, columns, rows)`：columns 为「维度列（请求顺序）+ 指标列（请求顺序）」。

## 3. 口径规则（确定性约定）

### 3.1 聚合
- 数值计算内部一律使用 `BigDecimal`（Long→精确整数，Double→`valueOf(double)`），
  **结果与输入行顺序无关**；`Aggregator.merge` 支持分批执行，合并结果与一次聚合一致。
- `SUM/AVG/MIN/MAX/COUNT(expr)/COUNT_DISTINCT` **忽略 null**；`COUNT(*)` 计全部行；
  组内全为 null 时 SUM/MIN/MAX/AVG 结果为 **null**（不是 0）。
- `AVG` = 非空之和 / 非空个数；全为整数且整除时输出 Long，否则四舍五入 **6 位小数（HALF_UP）**。
- 分组键中的 null 单独成组；维度键排序统一 **nulls-last 升序**，跨数字类型按数值比较。

### 3.2 时间算法
- `CUMULATIVE`（累计）：分区内按时间桶升序，当前桶 = 时间键 ≤ 当前桶的全部行聚合。
- `ROLLING(n)`（滑动窗口）：按分区内**实际存在**的时间桶排序——没有数据的月份不补零、
  也不占窗口位置；当前桶 = 含当前桶往前最多 n 个桶。
- 日期分桶按维度粒度（DAY/MONTH/YEAR）截断；时间键为 null 的行**无法定位时间**，
  不进入累计/窗口，也不产生时间桶输出。
- 分区 = 除唯一日期维度外的其它分组维度；`CUMULATIVE/ROLLING` 要求**恰好一个**日期维度。

### 3.3 排名 `RANK`
- 标准**竞争排名**：1,2,2,4（并列同名次，其后名次跳过）。
- 空聚合值的名次为 **null**（null 视为最小、排在最后），并列判定基于精确值。
- 指定一个日期维度时：分区为其余分组维度、**跨日期桶**排名；
  不指定日期维度时全表为一个分区；不允许两个及以上日期维度。
- `RANK` 不得与 `CUMULATIVE/ROLLING` 混用于同一条查询。
- 输出行序始终按完整维度键升序，**不按名次**，保证重复执行行序稳定。

### 3.4 确定性
同一条查询在以下条件下结果（含行顺序）完全一致：重复执行、源数据行序打乱、分批聚合。
对应测试见 `DeterminismTest`（多组随机种子端到端比对）。

## 4. 拒绝策略（不静默、原因可区分）

任何非法定义/查询都抛出 `SemanticException(code, message)`，HTTP 映射为
`400 {"code","message"}`，message 包含判定依据。错误码：

| 场景 | ErrorCode |
|---|---|
| 表/列/维度/指标名重复或为空 | `DEF_DUPLICATE_NAME` |
| 引用不存在的表 | `DEF_TABLE_NOT_FOUND` |
| 引用不存在的字段 | `DEF_COLUMN_NOT_FOUND` |
| 字段类型不匹配（含 SUM 作用于非数值列） | `DEF_COLUMN_TYPE_MISMATCH` |
| 聚合方式非法/缺失 | `DEF_AGG_TYPE_UNSUPPORTED` |
| ROLLING 窗口非正整数 / 非 ROLLING 携带窗口 | `DEF_ROLLING_WINDOW_INVALID` |
| DATE 维度缺粒度 / 非 DATE 设粒度 | `DEF_GRANULARITY_INVALID` |
| 派生指标来源不存在 | `DEF_METRIC_SOURCE_NOT_FOUND` |
| 派生链循环定义 | `DEF_CIRCULAR_REFERENCE` |
| 查询引用不存在的指标（含已重命名/删除的名字） | `Q_METRIC_NOT_FOUND` |
| 查询引用不存在的维度 | `Q_DIMENSION_NOT_FOUND` |
| 指标列表为空 | `Q_EMPTY_METRICS` |
| 指标/维度跨多张物理表 | `Q_MULTIPLE_TABLES` |
| 聚合输入非数值 | `Q_AGGREGATE_NOT_NUMERIC` |
| RANK 与窗口算法混用 | `Q_ALGORITHM_MIX_CONFLICT` |
| 窗口算法缺日期维度 / RANK 日期维度超过一个 | `Q_ALGORITHM_REQUIRES_DATE_DIM` |
| CSV 表头错 / 行列数不符 / 值无法解析 | `DATA_CSV_BAD_HEADER` / `DATA_CSV_ROW_WIDTH_MISMATCH` / `DATA_CSV_BAD_VALUE` |

## 5. 版本兼容口径

版本是**不可变全量快照**，注册后永不覆盖；重命名/删除/口径调整都通过新版本体现：

1. **历史结论可复现**：查询显式带 `version` 即按该快照计算；旧版本不会因新版本而改变。
2. **按版本区分**：不指定版本取最新版本；
   - v1 `order_count = COUNT(*)` = 12；
   - v2 口径调整为 `COUNT_DISTINCT(order_id)` = 11（样例数据中 O1006 出现两次）。
3. **重命名/删除不静默兼容**：v2 中 `gmv→sales_amount`、删除 `legacy_metric`；
   在 v2 里用旧名查询会得到 `Q_METRIC_NOT_FOUND`，必须改查 v1 或使用新名。
4. **非法引用与循环定义**在模型注册时即拒绝（`DEF_METRIC_SOURCE_NOT_FOUND` /
   `DEF_CIRCULAR_REFERENCE`），不会带病上线。
5. 重复注册同一版本号被 `DEF_DUPLICATE_NAME` 拒绝，保护历史快照。

预置版本（见 `seed/SeedModels`，发布说明可通过 `/api/semantic/models` 查看）：
- **v1**：`gmv`、`order_count=COUNT(*)`、`legacy_metric` 等；
- **v2**：重命名 `gmv* → sales_amount*`、删除 `legacy_metric`、
  `order_count` 改口径、新增派生别名 `gmv_alias → sales_amount`。

## 6. 验证方法与测试

`src/test/.../tests` 下以边界为主，所有用例通过 SLF4J 打印**输入与判定依据**：

| 测试类 | 覆盖内容 |
|---|---|
| `ValuesTest` | 空值解析、坏值拒绝原因、日期分桶、nulls-last 比较 |
| `AggregatorTest` | null 忽略、COUNT(*)、分批合并、AVG 舍入/整除、MIN/MAX |
| `ModelValidatorTest` | 缺字段、类型不符、非法聚合、窗口非法、来源缺失、循环定义、重名 |
| `QueryRejectionTest` | 不存在指标/维度、空指标、重命名/删除名字在新版本被拒、算法混用、缺日期维度、未知版本 |
| `TimeAlgorithmTest` | 累计值、滑动窗口（缺失桶不占位）、竞争排名并列与跳号、空值排名 |
| `DeterminismTest` | 多随机种子打乱行序 + 重复执行，普通/窗口/排名结果逐行一致 |
| `VersioningTest` | v1 结论可复现、重命名按版本区分、口径调整 v1/v2 不同、派生别名等价根指标 |

```bash
mvn test                                  # 全部
mvn -Dtest=TimeAlgorithmTest test         # 单个类
cat target/surefire-reports/*.txt         # 查看含输入/判定依据的输出
```

## 7. 代码结构

```
semantic/
├── model/      DataType/AggType/Algorithm/DateGranularity 枚举；
│               TableDef/ColumnDef/DimensionDef/MetricDef/ModelVersion
├── data/       Row/TableData/DataSet、Values(解析/分桶/比较)、
│               Aggregator(确定性聚合+merge)、CsvDataLoader
├── engine/     ModelValidator(定义校验)、QueryPlanner(请求校验/计划)、QueryExecutor(执行)
├── version/    ModelRegistry(版本快照注册表)
├── query/      QueryRequest/QueryResult/QueryService(统一入口)
├── seed/       SalesTable、SeedModels(v1/v2)、LocalDataConfig(Spring 装配)
├── api/        QueryController(/api/semantic/query|models)、SemanticExceptionHandler
└── LocalDemoRunner  启动时自动演示
```
