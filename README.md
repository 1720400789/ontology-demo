# ontology-demo

企业本体平台的可行性验证工程。目标不是做一个 CRUD 系统，而是验证一件事：

> 用「数据驱动的本体 + 受控动作」能不能把 Agent 在特定业务场景里的幻觉压到可接受范围。

## 当前状态

P0 垂直切片进行中。W1（加载与查询）、W2（关系与函数）、W3（权限与动作网关）已完成；W4 的 MCP 工具层已打通（24 个工具），接真实模型还缺凭证。

下一步：接一个支持 MCP 的客户端/模型，把评测里依赖 Agent 的用例跑起来。

战略、里程碑、风险见 [docs/00-plan.md](docs/00-plan.md)。

## 目录

```
ontology-demo/
├── db/migration/
│   └── V1__meta_model.sql        元模型 DDL（16 表 / 161 字段，全量中文注释）
├── ontology/maint/
│   └── ontology.yaml             设备维保本体：7 对象 / 6 关系 / 5 函数 / 7 动作
├── docs/
│   ├── 00-plan.md                战略、战术、里程碑、风险与债务（先看这篇）
│   ├── 01-meta-model.md          元模型设计与七个关键决策
│   ├── 02-maint-ontology.md      场景本体、Agent 链路、评测用例、验收标准
│   ├── 03-schema-explained.md    schema/表义/关联关系怎么读
│   └── assets/schema-map.png     16 张表的关系示意图
└── README.md
```

## 怎么读

1. [`docs/00-plan.md`](docs/00-plan.md) —— **从这篇开始**：为什么做、做到哪、怎么验收、有什么风险。
2. [`docs/03-schema-explained.md`](docs/03-schema-explained.md) —— 三个 schema 为什么分、每张表回答什么问题、一次建模的插入顺序。
3. [`docs/01-meta-model.md`](docs/01-meta-model.md) —— 七个关键决策，以及被否的方案。
4. [`db/migration/V1__meta_model.sql`](db/migration/V1__meta_model.sql) —— 对着决策看表结构。
5. [`ontology/maint/ontology.yaml`](ontology/maint/ontology.yaml) —— 看一个真实本体长什么样。
6. [`docs/02-maint-ontology.md`](docs/02-maint-ontology.md) —— 看 Agent 怎么用它，以及怎么验收。

## W1 已完成

- [x] Spring Boot 4.1.1 + Java 21 多模块骨架
- [x] Flyway 接入，16 张表已建，161 个字段注释全部落库
- [x] `OntologyLoader`：YAML → 校验 → 写 `ont_meta`（对象/版本/属性/关系/函数/动作/权限/发布）
- [x] 演示数据：`maint` schema 7 张业务表 + 8 台设备
- [x] 通用对象服务：`GET /api/objects/{type}`、`GET /api/objects/{type}/{id}`
- [x] 10 条评测用例入库

## 通用对象接口

```bash
# 列表：除 limit/offset/fields 外的参数按属性等值过滤
curl "http://localhost:8080/api/objects/equipment?criticality=A&limit=5"

# 单对象
curl "http://localhost:8080/api/objects/equipment/E-1001"

# 字段裁剪
curl "http://localhost:8080/api/objects/equipment?fields=id,name&limit=2"
```

返回结构：

```json
{"domain":"maint","type":"equipment","version":1,"total":8,"limit":5,"offset":0,
 "items":[{"id":"E-1001","name":"1# 主泵","criticality":"A"}]}
```

### 关系遍历

```bash
# 正向：站点 → 设备
curl "http://localhost:8080/api/objects/site/S-001/relations/siteEquipment"

# 反向：设备 → 站点
curl "http://localhost:8080/api/objects/equipment/E-1001/relations/siteEquipment"
```

## 函数接口

```bash
# 列出函数与入参 schema（Agent 生成工具定义用）
curl "http://localhost:8080/api/functions"

# 调用：数值必须来自函数，不能由模型心算
curl -X POST "http://localhost:8080/api/functions/overdueDays/invoke" \
     -H "Content-Type: application/json" \
     -d '{"equipmentId":"E-1001"}'
```

```json
{"function":"overdueDays","version":1,"latencyMs":11,
 "result":{"hasPlan":true,"overdue":true,"nextDueDate":"2026-10-13","days":13}}
```

## W3 进度

- [x] 权限：行过滤（`site_id = ${user.siteId}`）、字段掩码、`PROPOSE` / `EXECUTE` 分离
- [x] 动作网关：propose → approve → execute，幂等键、前置条件、审批策略、审计
- [x] 两个动作执行器：`createWorkOrder`（自动通过）、`postponePlan`（需主管审批）
- [x] 评测跑批：`POST /api/eval/run`，对象级断言可跑通（`gold-001` PASS）
- [ ] 需要 Agent 参与才能判定的用例仍标记 SKIPPED，W4 接上 Agent 后再补

## 身份与权限

所有接口靠请求头识别调用者（W3 是临时认证，接 OIDC 时只换 `ActorResolver`）：

```
X-Actor-Id:    u-1001 / maint-agent
X-Actor-Type:  USER / AGENT / SERVICE
X-Actor-Roles: maint_planner,maint_supervisor
X-Actor-Site:  S-001        # 行过滤用的属性
```

```bash
# 匿名 → 403
curl "http://localhost:8081/api/objects/equipment?limit=2"

# 技师：只能看到本站点，备件单价被掩码
curl -H "X-Actor-Id: u-2001" \
     -H "X-Actor-Roles: maint_technician" \
     -H "X-Actor-Site: S-001" \
     "http://localhost:8081/api/objects/equipment?limit=20"
```

## 动作接口

```bash
# 1. Agent 提议（自动通过的策略直接 APPROVED）
curl -X POST "http://localhost:8081/api/actions/createWorkOrder/propose" \
     -H "X-Actor-Id: maint-agent" -H "X-Actor-Type: AGENT" \
     -H "Content-Type: application/json" \
     -d '{"targetId":"E-1001","params":{"equipmentId":"E-1001","type":"抢修","priority":"P2","symptom":"轴承温度持续高于 85 度"}}'

# 2. 需要审批的动作：Agent 提议后是 PROPOSED
curl -X POST "http://localhost:8081/api/actions/postponePlan/propose" \
     -H "X-Actor-Id: maint-agent" -H "X-Actor-Type: AGENT" \
     -H "Content-Type: application/json" \
     -d '{"targetId":"P-2001","params":{"planId":"P-2001","newDueDate":"2026-11-20","reason":"备件到货延迟"}}'

# 3. 主管审批 + 执行
curl -X POST "http://localhost:8081/api/actions/pending/{id}/approve" -H "X-Actor-Id: u-9001" -H "X-Actor-Roles: maint_supervisor"
curl -X POST "http://localhost:8081/api/actions/pending/{id}/execute" -H "X-Actor-Id: u-9001" -H "X-Actor-Roles: maint_supervisor"
```

Agent 直接执行会被显式拒绝（`EXECUTE` 上有一条 DENY），重复提议会命中幂等键返回同一条记录。

## MCP：把元模型编译成工具

`POST /mcp` 提供 JSON-RPC 2.0 端点，支持 `initialize` / `tools/list` / `tools/call` / `ping`。
工具全部由元模型生成，当前 **24 个**：

- 7 个对象 × 2：`query_<type>` / `get_<type>`
- 5 个函数：`fn_<code>`
- 5 个非补偿动作：`action_<code>`

```bash
# 列出工具
curl -X POST http://localhost:8081/mcp \
  -H "Content-Type: application/json" \
  -H "X-Actor-Id: maint-agent" -H "X-Actor-Type: AGENT" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}'

# 调工具
curl -X POST http://localhost:8081/mcp \
  -H "Content-Type: application/json" \
  -H "X-Actor-Id: maint-agent" -H "X-Actor-Type: AGENT" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"fn_overdue_days","arguments":{"equipmentId":"E-1001"}}}'
```

脚本化 Agent 演示（只走 MCP 工具）：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/mcp-agent-demo.ps1
```

它依次执行：找站点 → 找该站 A 类设备 → 问超期天数 → 提议建单 → 尝试直接执行（被 403 拒绝）。

**限制**：这是最小实现，不支持 SSE 流式传输、resources、prompts、sampling；生产环境建议换 Spring AI 的 MCP starter。另外这里**没有接大模型**，脚本是固定策略的 Agent，用来验证工具契约与权限边界，不是智能体本身。

## 接入 DeepSeek（OpenAI 兼容）

**关键点**：DeepSeek 不是 MCP 客户端，不能让它直连 `/mcp`。正确做法是在应用内做 function-calling 循环：

```
问题 → 模型（带 24 个本体工具）→ 返回 tool_calls → 我们执行 → 结果回填 → 模型 → 回答
```

配置（密钥只走环境变量，不写进仓库）：

```powershell
$env:DEEPSEEK_BASE_URL = 'https://api.deepseek.com'
$env:DEEPSEEK_MODEL    = 'deepseek-chat'
$env:DEEPSEEK_API_KEY  = 'sk-...'
```

接口：

```bash
# 真正问模型（消耗 token）
curl -X POST http://localhost:8081/api/agent/ask \
  -H "Content-Type: application/json" \
  -H "X-Actor-Id: maint-agent" -H "X-Actor-Type: AGENT" \
  -d '{"question":"E-1001 保养超期几天？"}'

# 只看会发给模型的 payload，不消耗 token
curl -X POST http://localhost:8081/api/agent/preview \
  -H "Content-Type: application/json" \
  -d '{"question":"E-1001 保养超期几天？"}'
```

返回里带 `steps`：每一轮调了哪个工具、参数是什么、有没有报错，便于排查模型乱来。

没有 key 时可以用桩服务验证循环：

```powershell
python scripts/stub-deepseek.py 9099
$env:DEEPSEEK_BASE_URL = 'http://127.0.0.1:9099'
$env:DEEPSEEK_API_KEY  = 'stub-key'
```

注意事项：

- `deepseek-chat` 支持 function calling；`deepseek-reasoner`（R1）对 `tools` 支持有限，接工具建议用 chat。
- 模型返回的 `tool_calls[].function.arguments` 是 JSON **字符串**，要自己解析。
- 工具结果截断到 4000 字符，避免 token 爆炸。
- 循环最多 6 轮（`ontology.agent.max-steps`），防止模型在工具间打转。

## 本地依赖

- JDK 21+（本机 `D:\jdk-21.0.11`）
- PostgreSQL 17（本机 `D:\PostgreSQL\17`，服务 `postgresql-x64-17`）
- Maven 3.9+（本机 `D:\apache-maven-3.9.16`）

数据库初始化（只需一次）：

```sql
CREATE ROLE ontology LOGIN PASSWORD 'ontology';
CREATE DATABASE ontology_demo OWNER ontology ENCODING 'UTF8';
CREATE DATABASE ontology_test OWNER ontology ENCODING 'UTF8';
```

## 启动

```powershell
$env:JAVA_HOME   = 'D:\jdk-21.0.11'
$env:DB_URL      = 'jdbc:postgresql://localhost:5432/ontology_demo'
$env:DB_USER     = 'ontology'
$env:DB_PASSWORD = 'ontology'
& 'D:\apache-maven-3.9.16\bin\mvn.cmd' -pl ontology-app spring-boot:run
```

- Swagger UI：<http://localhost:8080/swagger-ui.html>
- 健康检查：<http://localhost:8080/actuator/health>

两个环境说明：

- **Flyway 当前是关闭的**（`spring.flyway.enabled: false`，库里的表是手工建的）。新环境要么手工执行 `db/migration` 下的 V1–V4，要么把开关打开让 Flyway 跑。
- **8080 端口被一个旧进程占着**（PID 8904，在当前沙箱里不可见也杀不掉）。验证时用 `SERVER_PORT=8081` 启动，所以上面的示例都是 8081。

## 踩过的坑

1. **Boot 4 把 Flyway 自动配置拆成了独立模块**。只引 `flyway-core` 不会报错，但迁移根本不执行，库一直是空的。必须引 `spring-boot-starter-flyway`。
2. **Flyway 默认替换 `${...}` 占位符**。DDL 注释里有 `${user.siteId}`、`${tenant}`，启动时会报 `No value provided for placeholder`。已用 `spring.flyway.placeholder-replacement=false` 关闭。
3. **Flyway 比 `ApplicationRunner` 先执行**。迁移脚本里不能假设 Loader 已经写过数据；`V3__eval_cases.sql` 因此先 `insert ... on conflict do nothing` 建域，再插评测用例。
4. **`BigDecimal.stripTrailingZeros()` 会把 110.00 变成 `1.1E+2`**。整数部分要转 `BigInteger` 再返回，否则 API 里会出现科学计数法。
5. **`audit_log.operation` 的 CHECK 约束漏了 `APPROVE` / `REJECT`**。审批时写审计直接失败，表现为 500。已加 `V4__audit_operations.sql`。
6. **前置条件里的变量名必须是对象类型 code**。`plan.nextDueDate` 解析不到（变量名要对 `maintenancePlan`），会让条件永远为假。

## 不做的事（V1）

- 可视化建模 IDE（先用 YAML 手写）
- 多租户
- 图数据库（递归 CTE 够用）
- 预测性维护（没有时序数据）
