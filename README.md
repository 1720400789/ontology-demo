# ontology-demo

企业本体平台的可行性验证工程。目标不是做一个 CRUD 系统，而是验证一件事：

> 用「数据驱动的本体 + 受控动作」能不能把 Agent 在特定业务场景里的幻觉压到可接受范围。

## 当前状态

P0 垂直切片进行中。W1 已完成，W2 完成关系遍历与函数引擎，还剩评测跑批。

下一步：评测跑批（W3 开始时补），然后动作网关与权限。

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

## W2 进度

- [x] 关系遍历：正反双向，两条查询实现，不引入图库
- [x] 函数引擎：虚拟线程超时 + 入参校验 + 结构性只读（`FunctionContext` 不暴露写接口）
- [x] `overdueDays`、`spareReadiness` 两个实现
- [ ] 评测跑批（顺延到 W3 开头）

## 本地依赖

- JDK 21+（本机 `D:\jdk-21.0.11`）
- PostgreSQL 17（本机 `D:\PostgreSQL\17`，服务 `postgresql-x64-17`）
- Maven 3.9+（本机 `D:\apache-maven-3.9.16`）

数据库初始化（只需一次）：

```sql
CREATE ROLE ontology LOGIN PASSWORD 'ontology';
CREATE DATABASE ontology      OWNER ontology ENCODING 'UTF8';
CREATE DATABASE ontology_test OWNER ontology ENCODING 'UTF8';
```

## 启动

```powershell
$env:JAVA_HOME   = 'D:\jdk-21.0.11'
$env:DB_URL      = 'jdbc:postgresql://localhost:5432/ontology'
$env:DB_USER     = 'ontology'
$env:DB_PASSWORD = 'ontology'
& 'D:\apache-maven-3.9.16\bin\mvn.cmd' -pl ontology-app spring-boot:run
```

- Swagger UI：<http://localhost:8080/swagger-ui.html>
- 健康检查：<http://localhost:8080/actuator/health>

## 踩过的坑

1. **Boot 4 把 Flyway 自动配置拆成了独立模块**。只引 `flyway-core` 不会报错，但迁移根本不执行，库一直是空的。必须引 `spring-boot-starter-flyway`。
2. **Flyway 默认替换 `${...}` 占位符**。DDL 注释里有 `${user.siteId}`、`${tenant}`，启动时会报 `No value provided for placeholder`。已用 `spring.flyway.placeholder-replacement=false` 关闭。
3. **Flyway 比 `ApplicationRunner` 先执行**。迁移脚本里不能假设 Loader 已经写过数据；`V3__eval_cases.sql` 因此先 `insert ... on conflict do nothing` 建域，再插评测用例。
4. **`BigDecimal.stripTrailingZeros()` 会把 110.00 变成 `1.1E+2`**。整数部分要转 `BigInteger` 再返回，否则 API 里会出现科学计数法。

## 不做的事（V1）

- 可视化建模 IDE（先用 YAML 手写）
- 多租户
- 图数据库（递归 CTE 够用）
- 预测性维护（没有时序数据）
