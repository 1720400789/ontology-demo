# 本体平台元模型 v1

> 配套 DDL：[`db/migration/V1__meta_model.sql`](../db/migration/V1__meta_model.sql)
> 场景本体：[`ontology/maint/ontology.yaml`](../ontology/maint/ontology.yaml)

## 1. 三条设计原则

这三条决定了下面所有表结构，先看原则再看表。

**原则一：元模型与实例数据分离。**
`ont_meta` 只描述"本体长什么样"，一行实例数据都不存。实例留在源系统，通过 `binding_def` 生成参数化查询虚拟化访问。收益是不需要为建模搬数据，也不存在"本体库与业务库不一致"这个经典死局。代价是复杂遍历性能受限，所以留了 `sync_mode = CACHE / CDC` 作为逃生舱。

**原则二：语义与动作成对出现。**
只有 `object_type + property_def` 的本体是 ER 图，不是本体。所以 `function_def`（算）和 `action_def`（写）从 v1 就必须进元模型，哪怕第一个版本只实现两个函数。

**原则三：稳定身份与不可变版本分离。**
对象类型分两层：`object_type` 是逻辑身份（id 永不变），`object_type_version` 是 schema 快照（发布后不可变）。所有跨表引用都指向逻辑身份，运行时再解析到当前 `PUBLISHED` 版本。

## 2. 分层

```
┌─────────────────────────────────────────────────────────┐
│ 接入层   Agent 网关（工具生成 / MCP）· REST · 建模 IDE    │
├─────────────────────────────────────────────────────────┤
│ 运行时   对象服务 · 函数引擎 · 动作网关 · 动态权限         │
├─────────────────────────────────────────────────────────┤
│ 元模型   逻辑对象/版本/属性/关系/函数/动作/权限/发布 ← 本次 │
├─────────────────────────────────────────────────────────┤
│ 绑定层   Connector SPI：JDBC / REST / CSV / Kafka        │
├─────────────────────────────────────────────────────────┤
│ 源系统   业务库 · 数仓 · EAM · WMS                       │
└─────────────────────────────────────────────────────────┘
```

## 3. 表总览（16 张）

| # | 表 | 作用 | 关键约束 |
|---|---|---|---|
| 1 | `ont_meta.domain` | 业务域，多域隔离第一层 | `code` 唯一 |
| 2 | `ont_meta.connector` | 数据连接器，口令走 `secret_ref` | 同域 `code` 唯一 |
| 3 | `ont_meta.object_type` | 逻辑对象，稳定身份 | `(domain, code)` 唯一 |
| 4 | `ont_meta.object_type_version` | schema 快照，发布后不可变 | 每个逻辑对象只能有一个 `PUBLISHED` |
| 5 | `ont_meta.property_def` | 属性定义，含来源表达式与 owner | `(version, code)` 唯一 |
| 6 | `ont_meta.relation_def` | 关系语义：谁指向谁、基数 | `code` 唯一 |
| 7 | `ont_meta.relation_binding` | 关系的物理连接方式 | 一条关系一种绑定 |
| 8 | `ont_meta.binding_def` | 版本到物理源的绑定 | 一个版本一份绑定 |
| 9 | `ont_meta.function_def` | 只读业务计算 | `(code, version)` 唯一 |
| 10 | `ont_meta.action_def` | 写操作：前置条件/审批/幂等/补偿 | `compensable` 为真必须有补偿动作 |
| 11 | `ont_meta.permission_policy` | 策略化权限，支持行过滤与字段掩码 | `DENY` 优先 |
| 12 | `ont_meta.ontology_release` | 发布清单，钉住一组版本 | `(domain, version)` 唯一 |
| 13 | `ont_rt.pending_action` | propose → execute 的中间态 | `idempotency_key` 唯一 |
| 14 | `ont_rt.audit_log` | 全链路审计 | 按 `trace_id` / 对象可查 |
| 15 | `ont_rt.object_cache` | 仅 CACHE/CDC 模式物化 | 记 `source_updated_at` |
| 16 | `ont_eval.eval_case` | 金标准与对抗用例 | `kind` 区分两类 |

## 4. 七个关键设计决策

### 决策一：逻辑对象与版本分离（这张图最能说明问题）

第一版设计里我把 `version` 直接放在 `object_type` 上，看起来更简单。但它有一个致命问题：

```
action_def.target_object_type_id ──> object_type(id=1, version=1)
                                       ↑
发布 v2 后新增一行 id=1 的同 code 版本 ─┘ 动作还指着 v1，成为悬空引用
```

要么每次发版克隆所有动作（版本爆炸），要么运行时猜"该用哪版"。所以拆成两层：

```sql
object_type          (id, domain_id, code, name, owner)        -- 逻辑身份
object_type_version  (id, object_type_id, version, status,
                      primary_key_prop, schema_hash)           -- 快照
```

- 动作、权限、关系只引用 `object_type.id`，永不悬空。
- 运行时解析链路：`(domain, code)` → `object_type` → `status='PUBLISHED'` 的版本 → 属性与绑定。
- `schema_hash` 用来发现"库里这份和 Git 里那份不一致"。

部分唯一索引保证同一逻辑对象同一时刻只有一个已发布版本：

```sql
create unique index uq_object_type_published
    on ont_meta.object_type_version (object_type_id)
    where status = 'PUBLISHED';
```

- 被否方案：单表加 `version` 字段。就是上面那个悬空引用问题。
- 被否方案：每次发版全量克隆函数/动作。一次建模改动牵动几十行定义，不可维护。

### 决策二：属性行式存储 + JSONB，不建宽表

`property_def` 一行一个属性，类型相关参数放 `type_config`：

```json
{"enum": ["A", "B", "C"]}
{"ref": "maint.equipment"}
{"precision": 12, "scale": 2, "unit": "kW"}
```

- 收益：加属性不改 DDL；建模工具按行渲染；影响分析容易做。
- 代价：查询时要做"元模型 → SQL"的翻译，翻译器是核心复杂度所在。
- 被否方案：每个对象一张物理宽表。一个域 50 个对象就是 50 张表，改一次模型一次 DDL，运维不可接受。

### 决策三：关系语义与物理绑定分离

`relation_def` 说"工单 MANY_TO_ONE 技师"，`relation_binding` 说"通过 `work_order.assignee_id = technician.id` 连"。换数据源只改绑定，语义不动。

`join_kind` 支持三种：`FK_COLUMN`（外键列）、`JOIN_TABLE`（多对多中间表）、`API`（跨系统按接口拼）。第一个切片只实现 `FK_COLUMN`。

反向遍历由同一条绑定的 from/to 列自动推导，`inverse_code` 只是反向显示名，不需要第二条关系记录。

### 决策四：函数只读，写操作只能走动作

`function_def` 没有副作用声明字段，因为它不该有副作用。运行时给函数开只读事务，检测到写库直接抛异常。

这条约束看起来限制多，实际是**幻觉治理的核心**：模型负责"选函数 + 填参数"，数值永远来自函数，不来自模型心算。

### 决策五：动作必须有幂等键、前置条件、审批策略、补偿声明

```sql
idempotent             boolean
idempotency_key_expr   varchar(512)   -- equipmentId + ":" + type + ":" + today()
precondition_expr      text           -- 受限表达式，不是代码
approval_policy        jsonb          -- NONE / ROLE / THRESHOLD
compensable            boolean
compensate_action_code varchar(64)
side_effects           jsonb          -- 声明会写哪些外部系统
```

`compensable = true` 时强制要求 `compensate_action_code` 非空（CHECK 约束）。`side_effects` 不是为了好看，是为了**影响分析**：改一个对象，能查出哪些动作会写哪些下游系统。

- 被否方案：让动作直接调用任意 Java 方法。不可审计、不可补偿、不可审批。

### 决策六：权限是数据，不是代码

`permission_policy` 支持四种主体（USER / ROLE / AGENT / SERVICE）× 六种动作（含 `PROPOSE` 和 `EXECUTE`）。行过滤写成模板：

```sql
row_filter_expr = 'site_id = ${user.siteId}'
```

运行时把它编译成**参数化谓词**，绝不字符串拼接。`field_mask` 控制字段级脱敏。

为什么 `PROPOSE` 和 `EXECUTE` 要分开？因为 Agent 的合理权限是"能提议、不能直接执行"。只用一个 `EXECUTE` 表达不了这个语义，这是实践里最容易踩的坑。

### 决策七：评测用例进库

`ont_eval.eval_case` 存金标准与对抗用例，`expect` 用 JSON 描述期望：

```json
{"objects": ["maint.equipment:E-1001"], "action": "maint.createWorkOrder",
 "mustRefuse": false, "mustAskClarify": false}
```

把评测当元模型的一等公民，是为了让"本体改了，幻觉率有没有变差"变成 CI 里能跑的断言，而不是拍脑袋。

## 5. 实体关系

```
domain 1─┬─N connector
         ├─N object_type 1─┬─N object_type_version 1─┬─N property_def
         │                 │                         └─1 binding_def
         │                 └─N relation_def (from/to) ──1 relation_binding
         ├─N function_def ──0..1 object_type（返回类型）
         ├─N action_def ──1 object_type（目标）
         ├─N permission_policy ──0..1 object_type
         ├─N ontology_release
         └─N eval_case

action_def 1─N pending_action
任何运行时操作 1─N audit_log
```

## 6. 发布流程

```
YAML ──validate──> 新 DRAFT 版本 ──staging 跑评测──> PUBLISHED
                        │                              │
                        └── 影响分析：谁引用了这个属性 ─┘
```

1. `OntologyLoader` 读 YAML，按 JSON Schema 校验。
2. 逻辑对象不存在就建；已存在就新建 `version + 1` 的 `DRAFT` 版本，写属性与绑定。
3. 影响分析：这个逻辑对象被哪些 `function_def` / `action_def` / `permission_policy` / `relation_def` 引用。
4. 生成 `ontology_release`（`manifest` 记录每个对象的 version + `schema_hash`），staging 跑 `eval_case`。
5. 评测通过：旧 `PUBLISHED` 降为 `DEPRECATED`，新版本置 `PUBLISHED`。
6. 回滚：把上一个版本重新置 `PUBLISHED`，当前版本降级。整个过程不改一行代码。

## 7. 元模型与生产闸门的对应

| 生产闸门 | 靠哪张表支撑 |
|---|---|
| 可解释性 | `property_def.source_expr` + `audit_log.definition_version` |
| 可拒绝性 | `eval_case.mustAskClarify` / `mustRefuse` + 枚举约束 |
| 动作安全 | `action_def` 的幂等/前置条件/审批/补偿 + `pending_action` |
| 变更安全 | `object_type_version` + `ontology_release` + `side_effects` |
| 权限正确 | `permission_policy` + `audit_log.actor_type` |
| 数据新鲜 | `binding_def.freshness_sla_sec` + `object_cache.source_updated_at` |
| 可评测 | `eval_case` |

## 8. 还没做的（明确留白）

- **关系未版本化**：`relation_def` 引用逻辑对象，但 join 列名随 schema 变化时，绑定可能失效。V2 要么给关系加版本，要么在发布时校验列存在。
- **属性删除**：V1 禁止删属性，只允许标 `DEPRECATED`，否则历史数据与函数会断。
- **跨域引用**：域之间只能引用已发布的公共对象，公共对象的准入机制未实现。
- **表达式沙箱**：`precondition_expr` 与 `row_filter_expr` 的解析器、白名单、复杂度限制，是 W2–W3 的主要工作量。
- **字段级血缘**：`schema_hash` 只能发现漂移，做不到"改这个字段影响哪些报表"。
