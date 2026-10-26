-- =====================================================================
-- V1__meta_model.sql — 本体平台元模型 v1
-- PostgreSQL 16+
--
-- 职责边界：
--   ont_meta   元模型：本体"怎么定义"。低频写、强一致、可进 Git。
--   ont_rt     运行时：动作与审计。高频写、可归档。
--   ont_eval   评测：金标准与对抗用例。
--
-- 一条铁律：ont_meta 不存实例数据。
-- 实例留在源系统，通过 binding 虚拟化访问；只有跨源遍历或性能兜底
-- 才启用 CACHE/CDC，落到 ont_rt.object_cache。
--
-- 对象类型分两层：
--   object_type          逻辑对象（稳定身份，code 不变）
--   object_type_version  版本（schema 快照，发布后不可变）
-- 所有跨表引用都指向逻辑对象，运行时再解析到当前 PUBLISHED 版本。
-- 这样模型升到 v2 时，动作、权限、关系不需要跟着改。
--
-- 每个字段都有 COMMENT，psql 里 \d+ 表名 可直接查看。
-- =====================================================================

create schema if not exists ont_meta;
create schema if not exists ont_rt;
create schema if not exists ont_eval;

comment on schema ont_meta is '本体元模型：对象/属性/关系/函数/动作/权限/发布';
comment on schema ont_rt   is '本体运行时：待执行动作、审计、对象缓存';
comment on schema ont_eval is '本体评测：金标准用例与对抗用例';


-- ---------------------------------------------------------------------
-- 1. 业务域
-- ---------------------------------------------------------------------
create table ont_meta.domain (
    id          uuid         primary key default gen_random_uuid(),
    code        varchar(64)  not null unique,
    name        varchar(128) not null,
    description text,
    created_at  timestamptz  not null default now(),
    updated_at  timestamptz  not null default now()
);

comment on table ont_meta.domain is
    '业务域：多域隔离第一层。跨域只能引用已发布的公共对象，不能直接改别人的模型';

comment on column ont_meta.domain.id          is '主键，UUID';
comment on column ont_meta.domain.code        is '域编码，全局唯一。对象全名以它为前缀，如 maint.equipment';
comment on column ont_meta.domain.name        is '域显示名，如「设备维保」';
comment on column ont_meta.domain.description is '域职责说明：这个域管哪些业务对象、边界在哪';
comment on column ont_meta.domain.created_at  is '创建时间';
comment on column ont_meta.domain.updated_at  is '最后修改时间，由 trg_domain_touch 触发器维护';


-- ---------------------------------------------------------------------
-- 2. 数据连接器
-- ---------------------------------------------------------------------
create table ont_meta.connector (
    id         uuid         primary key default gen_random_uuid(),
    domain_id  uuid         not null references ont_meta.domain (id),
    code       varchar(64)  not null,
    kind       varchar(16)  not null,
    config     jsonb        not null default '{}'::jsonb,
    secret_ref varchar(256),
    status     varchar(16)  not null default 'ACTIVE',
    created_at timestamptz  not null default now(),
    constraint uq_connector_code   unique (domain_id, code),
    constraint ck_connector_kind   check (kind in ('JDBC', 'REST', 'CSV', 'KAFKA')),
    constraint ck_connector_status check (status in ('ACTIVE', 'DISABLED'))
);

comment on table ont_meta.connector is
    '数据连接器。config 只放非敏感参数，口令走 secret_ref 指向密钥管理，绝不落库明文';

comment on column ont_meta.connector.id         is '主键，UUID';
comment on column ont_meta.connector.domain_id  is '所属业务域';
comment on column ont_meta.connector.code       is '连接器编码，域内唯一，绑定与关系通过它引用';
comment on column ont_meta.connector.kind       is '连接器类型：JDBC / REST / CSV / KAFKA';
comment on column ont_meta.connector.config     is '非敏感连接参数 JSON，如 {"url":..., "schema":"maint", "timeoutMs":3000}';
comment on column ont_meta.connector.secret_ref is '密钥管理中的凭据路径，如 secret/maint-pg；禁止存明文口令';
comment on column ont_meta.connector.status     is '状态：ACTIVE 可用 / DISABLED 停用；停用后引用它的对象查询直接失败';
comment on column ont_meta.connector.created_at is '创建时间';


-- ---------------------------------------------------------------------
-- 3. 逻辑对象（稳定身份）
-- ---------------------------------------------------------------------
create table ont_meta.object_type (
    id          uuid         primary key default gen_random_uuid(),
    domain_id   uuid         not null references ont_meta.domain (id),
    code        varchar(64)  not null,
    name        varchar(128) not null,
    description text,
    owner       varchar(64)  not null,
    created_at  timestamptz  not null default now(),
    updated_at  timestamptz  not null default now(),
    constraint uq_object_type_code unique (domain_id, code)
);

comment on table ont_meta.object_type is
    '逻辑对象。这个 id 永远不变，动作、权限、关系都引用它，'
    '所以对象升版不会产生悬空引用';

comment on column ont_meta.object_type.id          is '逻辑对象主键，永不变。动作、权限、关系都引用这个 id，不引用版本 id';
comment on column ont_meta.object_type.domain_id   is '所属业务域';
comment on column ont_meta.object_type.code        is '对象编码，域内唯一，如 equipment；全名为 域编码.对象编码';
comment on column ont_meta.object_type.name        is '对象显示名，如「设备」';
comment on column ont_meta.object_type.description is '业务含义：这个对象代表现实世界里的什么';
comment on column ont_meta.object_type.owner       is '责任人，建模审批与变更通知的接收方';
comment on column ont_meta.object_type.created_at  is '创建时间';
comment on column ont_meta.object_type.updated_at  is '最后修改时间，由 trg_object_type_touch 触发器维护；不含版本内容变更';


-- ---------------------------------------------------------------------
-- 4. 对象版本（schema 快照）
-- ---------------------------------------------------------------------
create table ont_meta.object_type_version (
    id               uuid        primary key default gen_random_uuid(),
    object_type_id   uuid        not null references ont_meta.object_type (id) on delete cascade,
    version          int         not null,
    status           varchar(16) not null default 'DRAFT',
    primary_key_prop varchar(64) not null,
    title_prop       varchar(64),
    schema_hash      varchar(64) not null,
    released_at      timestamptz,
    created_at       timestamptz not null default now(),
    constraint uq_object_type_version unique (object_type_id, version),
    constraint ck_object_version_status check (status in ('DRAFT', 'PUBLISHED', 'DEPRECATED'))
);

-- 同一个逻辑对象，同一时刻只能有一个已发布版本
create unique index uq_object_type_published
    on ont_meta.object_type_version (object_type_id)
    where status = 'PUBLISHED';

comment on table ont_meta.object_type_version is
    '版本一旦 PUBLISHED 就不再修改；改模型 = 新建 DRAFT 版本。'
    'schema_hash 用来发现"库里这份和 Git 里那份不一致"';

comment on column ont_meta.object_type_version.id               is '版本主键；属性、绑定挂在它上面';
comment on column ont_meta.object_type_version.object_type_id   is '所属逻辑对象';
comment on column ont_meta.object_type_version.version          is '版本号，从 1 递增；同一对象内唯一';
comment on column ont_meta.object_type_version.status           is '状态：DRAFT 草稿 / PUBLISHED 已发布 / DEPRECATED 已下线；同一对象只能有一个 PUBLISHED';
comment on column ont_meta.object_type_version.primary_key_prop is '主键属性 code，必须存在于本版本的 property_def 中';
comment on column ont_meta.object_type_version.title_prop       is '展示名属性 code，对象列表与卡片默认显示；为空时用主键';
comment on column ont_meta.object_type_version.schema_hash      is '本版本定义内容的 SHA-256，用于比对库与 Git 是否一致';
comment on column ont_meta.object_type_version.released_at      is '首次发布时间；DRAFT 状态为空';
comment on column ont_meta.object_type_version.created_at       is '版本记录创建时间';


-- ---------------------------------------------------------------------
-- 5. 属性定义（挂在版本上）
-- ---------------------------------------------------------------------
create table ont_meta.property_def (
    id                     uuid         primary key default gen_random_uuid(),
    object_type_version_id uuid         not null references ont_meta.object_type_version (id) on delete cascade,
    code                   varchar(64)  not null,
    name                   varchar(128) not null,
    data_type              varchar(16)  not null,
    type_config            jsonb        not null default '{}'::jsonb,
    source_expr            varchar(512),
    required               boolean      not null default false,
    pii                    boolean      not null default false,
    owner                  varchar(64)  not null,
    description            text,
    sort_order             int          not null default 0,
    constraint uq_property_code unique (object_type_version_id, code),
    constraint ck_property_type check (data_type in (
        'STRING', 'TEXT', 'INT', 'LONG', 'DECIMAL', 'BOOLEAN',
        'DATE', 'TIMESTAMP', 'ENUM', 'REF', 'JSON'))
);

comment on table ont_meta.property_def is
    '属性定义。挂在版本上而不是逻辑对象上，保证历史版本可回放';

comment on column ont_meta.property_def.id                     is '主键，UUID';
comment on column ont_meta.property_def.object_type_version_id is '所属对象版本；属性随版本冻结，不随逻辑对象变化';
comment on column ont_meta.property_def.code                   is '属性编码，驼峰，版本内唯一；动作参数与函数出参按它引用';
comment on column ont_meta.property_def.name                   is '属性显示名，如「设备编码」';
comment on column ont_meta.property_def.data_type              is '数据类型：STRING/TEXT/INT/LONG/DECIMAL/BOOLEAN/DATE/TIMESTAMP/ENUM/REF/JSON';
comment on column ont_meta.property_def.type_config            is '类型参数 JSON：{"enum":[...]} 枚举 / {"ref":"maint.equipment"} 引用 / {"precision":12,"scale":2} 精度 / {"unit":"kW"} 单位';
comment on column ont_meta.property_def.source_expr            is '绑定表达式，如 code 或 coalesce(name, full_name)；为空时按 code 驼峰转下划线同名映射';
comment on column ont_meta.property_def.required               is '是否必填；影响对象校验、动作参数校验与对象卡片渲染';
comment on column ont_meta.property_def.pii                    is '是否个人敏感信息；为真时审计、导出、Agent 上下文默认脱敏';
comment on column ont_meta.property_def.owner                  is '属性责任人；没有 owner 的属性不允许发布';
comment on column ont_meta.property_def.description            is '业务定义与统计口径；没有描述的属性不允许发布';
comment on column ont_meta.property_def.sort_order             is '展示顺序，升序；同一版本内建议从 10 开始留空档';


-- ---------------------------------------------------------------------
-- 6. 关系：语义 + 物理连接分离
--     关系引用"逻辑对象"，因为它描述的是业务语义，不随 schema 变
-- ---------------------------------------------------------------------
create table ont_meta.relation_def (
    id                  uuid         primary key default gen_random_uuid(),
    domain_id           uuid         not null references ont_meta.domain (id),
    code                varchar(64)  not null,
    name                varchar(128) not null,
    from_object_type_id uuid         not null references ont_meta.object_type (id),
    to_object_type_id   uuid         not null references ont_meta.object_type (id),
    cardinality         varchar(16)  not null,
    inverse_code        varchar(64),
    description         text,
    constraint uq_relation_code unique (domain_id, code),
    constraint ck_relation_card check (cardinality in (
        'ONE_TO_ONE', 'ONE_TO_MANY', 'MANY_TO_ONE', 'MANY_TO_MANY'))
);

comment on table ont_meta.relation_def is
    '关系语义：谁指向谁、基数多少。引用逻辑对象，不随版本变化';

comment on column ont_meta.relation_def.id                  is '主键，UUID';
comment on column ont_meta.relation_def.domain_id           is '所属业务域';
comment on column ont_meta.relation_def.code                is '关系编码，域内唯一，如 siteEquipment；遍历接口用它指定方向';
comment on column ont_meta.relation_def.name                is '关系显示名，如「站点包含设备」';
comment on column ont_meta.relation_def.from_object_type_id is '起点逻辑对象';
comment on column ont_meta.relation_def.to_object_type_id   is '终点逻辑对象';
comment on column ont_meta.relation_def.cardinality         is '基数：ONE_TO_ONE / ONE_TO_MANY / MANY_TO_ONE / MANY_TO_MANY';
comment on column ont_meta.relation_def.inverse_code        is '反向遍历时的显示名；反向路径由同一条绑定的 from/to 列自动推导，不需要第二条关系记录';
comment on column ont_meta.relation_def.description         is '业务含义：什么情况下这两个对象会有关联';


create table ont_meta.relation_binding (
    id              uuid        primary key default gen_random_uuid(),
    relation_def_id uuid        not null references ont_meta.relation_def (id) on delete cascade,
    connector_id    uuid        not null references ont_meta.connector (id),
    join_kind       varchar(16) not null,
    config          jsonb       not null,
    constraint uq_relation_binding unique (relation_def_id),
    constraint ck_join_kind check (join_kind in ('FK_COLUMN', 'JOIN_TABLE', 'API'))
);

comment on table ont_meta.relation_binding is
    '关系的物理连接方式。换数据源只改这里，语义与业务代码不动';

comment on column ont_meta.relation_binding.id              is '主键，UUID';
comment on column ont_meta.relation_binding.relation_def_id is '所属关系；一条关系只允许一种绑定';
comment on column ont_meta.relation_binding.connector_id    is '连接器；跨系统关系可指向不同连接器';
comment on column ont_meta.relation_binding.join_kind       is '连接方式：FK_COLUMN 外键列 / JOIN_TABLE 中间表 / API 接口拼接';
comment on column ont_meta.relation_binding.config          is '连接配置 JSON：FK_COLUMN 用 {"from_column":"site_id","to_column":"id"}；JOIN_TABLE 追加 join_table';


-- ---------------------------------------------------------------------
-- 7. 对象到物理源的绑定（挂在版本上，换源不影响逻辑对象）
-- ---------------------------------------------------------------------
create table ont_meta.binding_def (
    id                     uuid         primary key default gen_random_uuid(),
    object_type_version_id uuid         not null references ont_meta.object_type_version (id) on delete cascade,
    connector_id           uuid         not null references ont_meta.connector (id),
    source_kind            varchar(16)  not null,
    source_ref             varchar(512) not null,
    pk_mapping             jsonb        not null,
    property_mapping       jsonb        not null default '{}'::jsonb,
    base_filter            varchar(1024),
    sync_mode              varchar(16)  not null default 'VIRTUAL',
    freshness_sla_sec      int          not null default 60,
    constraint uq_binding_version unique (object_type_version_id),
    constraint ck_source_kind check (source_kind in ('TABLE', 'VIEW', 'QUERY', 'API')),
    constraint ck_sync_mode   check (sync_mode in ('VIRTUAL', 'CACHE', 'CDC'))
);

comment on table ont_meta.binding_def is
    'V1 默认 VIRTUAL：不搬数据，生成参数化查询。base_filter 用于租户隔离与软删';

comment on column ont_meta.binding_def.id                     is '主键，UUID';
comment on column ont_meta.binding_def.object_type_version_id is '所属对象版本；一个版本只允许一份绑定';
comment on column ont_meta.binding_def.connector_id           is '数据来源连接器';
comment on column ont_meta.binding_def.source_kind            is '源类型：TABLE 表 / VIEW 视图 / QUERY 命名查询 / API 接口';
comment on column ont_meta.binding_def.source_ref             is '物理源引用，如 maint.equipment 或 /api/v1/equipment；不使用裸 SQL';
comment on column ont_meta.binding_def.pk_mapping             is '对象主键到源列的映射 JSON，如 {"id":"equipment_id"}';
comment on column ont_meta.binding_def.property_mapping       is '属性到源列的覆盖映射 JSON，如 {"status":"eq_status"}；未列出的按驼峰转下划线自动映射';
comment on column ont_meta.binding_def.base_filter            is '固定过滤条件，如 deleted_at is null 或 tenant_id = ${tenant}；运行时与权限行过滤合并';
comment on column ont_meta.binding_def.sync_mode              is '同步模式：VIRTUAL 实时查询 / CACHE 定期物化 / CDC 增量同步';
comment on column ont_meta.binding_def.freshness_sla_sec      is '数据新鲜度 SLA，单位秒；超过后对象卡片标记为「可能过期」';


-- ---------------------------------------------------------------------
-- 8. 函数：只读业务计算
-- ---------------------------------------------------------------------
create table ont_meta.function_def (
    id                     uuid         primary key default gen_random_uuid(),
    domain_id              uuid         not null references ont_meta.domain (id),
    code                   varchar(64)  not null,
    name                   varchar(128) not null,
    description            text,
    impl_key               varchar(128) not null,
    input_schema           jsonb        not null,
    output_schema          jsonb        not null,
    returns_object_type_id uuid         references ont_meta.object_type (id),
    timeout_ms             int          not null default 3000,
    deterministic          boolean      not null default true,
    version                int          not null default 1,
    status                 varchar(16)  not null default 'DRAFT',
    owner                  varchar(64)  not null,
    constraint uq_function_version unique (domain_id, code, version),
    constraint ck_function_status  check (status in ('DRAFT', 'PUBLISHED', 'DEPRECATED')),
    constraint ck_function_timeout check (timeout_ms between 1 and 30000)
);

comment on table ont_meta.function_def is
    '只读业务计算。写操作必须走 action_def；函数内写库在运行时被直接拒绝';

comment on column ont_meta.function_def.id                     is '主键，UUID';
comment on column ont_meta.function_def.domain_id              is '所属业务域';
comment on column ont_meta.function_def.code                   is '函数编码，域内唯一，如 overdueDays；Agent 工具名由它派生';
comment on column ont_meta.function_def.name                   is '函数显示名，如「保养超期天数」';
comment on column ont_meta.function_def.description            is '计算口径说明；会作为工具描述提供给 Agent';
comment on column ont_meta.function_def.impl_key               is '实现绑定键，对应 Spring Bean 名，如 maintOverdueDays';
comment on column ont_meta.function_def.input_schema           is '入参 JSON Schema，用于参数校验与工具定义生成';
comment on column ont_meta.function_def.output_schema          is '出参 JSON Schema，用于结果校验与前端渲染';
comment on column ont_meta.function_def.returns_object_type_id is '若返回对象列表，指向逻辑对象；返回标量或结构体时为空';
comment on column ont_meta.function_def.timeout_ms             is '超时毫秒数，1–30000；超时按失败处理并降级';
comment on column ont_meta.function_def.deterministic          is '是否确定性：同输入必同输出；为假时结果不进缓存、不进评测断言';
comment on column ont_meta.function_def.version                is '函数版本；审计记录当时调用的版本';
comment on column ont_meta.function_def.status                 is '状态：DRAFT / PUBLISHED / DEPRECATED；只有 PUBLISHED 对 Agent 可见';
comment on column ont_meta.function_def.owner                  is '责任人；函数口径变更需 owner 确认';


-- ---------------------------------------------------------------------
-- 9. 动作：唯一允许写业务的入口
-- ---------------------------------------------------------------------
create table ont_meta.action_def (
    id                     uuid         primary key default gen_random_uuid(),
    domain_id              uuid         not null references ont_meta.domain (id),
    code                   varchar(64)  not null,
    name                   varchar(128) not null,
    description            text,
    target_object_type_id  uuid         not null references ont_meta.object_type (id),
    param_schema           jsonb        not null,
    precondition_expr      text,
    approval_policy        jsonb        not null default '{"mode":"NONE"}'::jsonb,
    idempotent             boolean      not null default true,
    idempotency_key_expr   varchar(512),
    compensable            boolean      not null default false,
    compensate_action_code varchar(64),
    side_effects           jsonb        not null default '[]'::jsonb,
    version                int          not null default 1,
    status                 varchar(16)  not null default 'DRAFT',
    owner                  varchar(64)  not null,
    constraint uq_action_version unique (domain_id, code, version),
    constraint ck_action_status  check (status in ('DRAFT', 'PUBLISHED', 'DEPRECATED')),
    constraint ck_compensable check (compensable = false or compensate_action_code is not null)
);

comment on table ont_meta.action_def is
    '动作定义：唯一允许写业务的入口。幂等、前置条件、审批、补偿、副作用五项缺一不可';

comment on column ont_meta.action_def.id                     is '主键，UUID';
comment on column ont_meta.action_def.domain_id              is '所属业务域';
comment on column ont_meta.action_def.code                   is '动作编码，域内唯一，如 assignTechnician；Agent 工具名由它派生';
comment on column ont_meta.action_def.name                   is '动作显示名，如「派单给技师」';
comment on column ont_meta.action_def.description            is '动作说明：做什么、什么情况下用；会作为工具描述提供给 Agent';
comment on column ont_meta.action_def.target_object_type_id   is '目标逻辑对象；前置条件的上下文变量按它的 code 命名';
comment on column ont_meta.action_def.param_schema           is '参数 JSON Schema；REF 类型参数在运行时自动解析为对象';
comment on column ont_meta.action_def.precondition_expr      is '前置条件表达式（白名单语法），可引用对象属性、参数与已发布函数；为空表示无前置条件';
comment on column ont_meta.action_def.approval_policy        is '审批策略 JSON：{"mode":"NONE"} 直接执行 / {"mode":"ROLE","roles":[...]} 指定角色审批 / {"mode":"THRESHOLD","field":"postponeDays","gt":7,"roles":[...]} 超阈值才审批';
comment on column ont_meta.action_def.idempotent             is '是否幂等；为真时必须能由参数派生稳定的幂等键';
comment on column ont_meta.action_def.idempotency_key_expr   is '幂等键派生表达式，如 equipmentId + ":" + type + ":" + today()；为空时用 目标id + 动作编码 + 请求哈希';
comment on column ont_meta.action_def.compensable            is '是否可补偿；为真时 compensate_action_code 必填（CHECK 约束）';
comment on column ont_meta.action_def.compensate_action_code is '补偿动作编码；只在回滚时由动作网关调用，不暴露给 Agent';
comment on column ont_meta.action_def.side_effects           is '副作用声明 JSON 数组，如 ["eam.work_order","wms.stock"]；用于影响分析与审计';
comment on column ont_meta.action_def.version                is '动作版本；pending_action 记录提交时的版本，便于事后复盘';
comment on column ont_meta.action_def.status                 is '状态：DRAFT / PUBLISHED / DEPRECATED；只有 PUBLISHED 可被提议与执行';
comment on column ont_meta.action_def.owner                  is '责任人；动作上线与变更需 owner 确认';


-- ---------------------------------------------------------------------
-- 10. 权限策略
-- ---------------------------------------------------------------------
create table ont_meta.permission_policy (
    id              uuid         primary key default gen_random_uuid(),
    domain_id       uuid         not null references ont_meta.domain (id),
    subject_type    varchar(16)  not null,
    subject_id      varchar(128) not null,
    object_type_id  uuid         references ont_meta.object_type (id) on delete cascade,
    action          varchar(16)  not null,
    effect          varchar(8)   not null,
    row_filter_expr varchar(1024),
    field_mask      jsonb        not null default '[]'::jsonb,
    priority        int          not null default 100,
    constraint ck_subject_type check (subject_type in ('USER', 'ROLE', 'AGENT', 'SERVICE')),
    constraint ck_perm_action  check (action in ('READ', 'CREATE', 'UPDATE', 'DELETE', 'PROPOSE', 'EXECUTE')),
    constraint ck_perm_effect  check (effect in ('ALLOW', 'DENY'))
);

comment on table ont_meta.permission_policy is
    '权限策略：主体 × 对象 × 动作 → 允许/拒绝，附带行过滤与字段掩码';

comment on column ont_meta.permission_policy.id              is '主键，UUID';
comment on column ont_meta.permission_policy.domain_id       is '所属业务域';
comment on column ont_meta.permission_policy.subject_type    is '主体类型：USER / ROLE / AGENT / SERVICE';
comment on column ont_meta.permission_policy.subject_id      is '主体标识；AGENT 时是 Agent 注册 id，不是模型名';
comment on column ont_meta.permission_policy.object_type_id  is '作用对象（逻辑对象）；NULL 表示域内全部对象类型';
comment on column ont_meta.permission_policy.action          is '动作：READ / CREATE / UPDATE / DELETE / PROPOSE 提议动作 / EXECUTE 执行动作';
comment on column ont_meta.permission_policy.effect          is '效果：ALLOW 允许 / DENY 拒绝；同优先级下 DENY 优先';
comment on column ont_meta.permission_policy.row_filter_expr is '行级过滤模板，如 site_id = ${user.siteId}；运行时编译成参数化谓词，绝不字符串拼接';
comment on column ont_meta.permission_policy.field_mask      is '字段掩码 JSON 数组，如 ["unitPrice","phone"]；返回前脱敏或剔除';
comment on column ont_meta.permission_policy.priority        is '优先级，数值越小越高；建议 1=安全拒绝、10=Agent、20=业务角色、30=降级读取';


-- ---------------------------------------------------------------------
-- 11. 发布清单
-- ---------------------------------------------------------------------
create table ont_meta.ontology_release (
    id          uuid        primary key default gen_random_uuid(),
    domain_id   uuid        not null references ont_meta.domain (id),
    version     varchar(32) not null,
    status      varchar(16) not null default 'DRAFT',
    manifest    jsonb       not null,
    released_by varchar(64),
    released_at timestamptz,
    notes       text,
    constraint uq_release_version unique (domain_id, version),
    constraint ck_release_status  check (status in ('DRAFT', 'PUBLISHED', 'ROLLED_BACK'))
);

comment on table ont_meta.ontology_release is
    '发布清单：把若干对象版本/函数/动作的版本钉在一起。'
    'staging 验证过的那份 manifest，就是 prod 要上的那份';

comment on column ont_meta.ontology_release.id          is '主键，UUID';
comment on column ont_meta.ontology_release.domain_id   is '所属业务域';
comment on column ont_meta.ontology_release.version     is '发布版本号，域内唯一，建议用 2026.10.1 这类可排序格式';
comment on column ont_meta.ontology_release.status      is '状态：DRAFT 待验证 / PUBLISHED 已生效 / ROLLED_BACK 已回滚';
comment on column ont_meta.ontology_release.manifest    is '清单 JSON：每个对象的版本与 schema_hash，如 {"equipment":{"version":1,"hash":"9f2c"}}';
comment on column ont_meta.ontology_release.released_by is '发布人';
comment on column ont_meta.ontology_release.released_at is '生效时间；DRAFT 为空';
comment on column ont_meta.ontology_release.notes       is '变更说明：这次改了什么、为什么改、影响哪些场景';


-- ---------------------------------------------------------------------
-- 12. 待执行动作（propose → execute 的中间态）
-- ---------------------------------------------------------------------
create table ont_rt.pending_action (
    id                 uuid         primary key default gen_random_uuid(),
    idempotency_key    varchar(128) not null unique,
    action_code        varchar(64)  not null,
    action_version     int          not null,
    target_object_type varchar(64)  not null,
    target_object_id   varchar(128) not null,
    params             jsonb        not null,
    status             varchar(16)  not null default 'PROPOSED',
    proposed_by        varchar(128) not null,
    proposed_at        timestamptz  not null default now(),
    approved_by        varchar(128),
    approved_at        timestamptz,
    executed_at        timestamptz,
    result             jsonb,
    error              text,
    expires_at         timestamptz,
    constraint ck_pending_status check (status in (
        'PROPOSED', 'APPROVED', 'REJECTED', 'EXECUTED', 'FAILED', 'COMPENSATED', 'EXPIRED'))
);

create index ix_pending_status on ont_rt.pending_action (status, proposed_at desc);
create index ix_pending_target on ont_rt.pending_action (target_object_type, target_object_id);

comment on table ont_rt.pending_action is
    '动作先落库再执行：Agent 只能 propose，execute 由策略或人工触发。'
    'idempotency_key 由调用方传入，重复提交只生效一次';

comment on column ont_rt.pending_action.id                 is '主键，UUID';
comment on column ont_rt.pending_action.idempotency_key    is '幂等键，全局唯一；重复提交靠它去重';
comment on column ont_rt.pending_action.action_code        is '动作编码；跨版本稳定，配合 action_version 才定位到具体定义';
comment on column ont_rt.pending_action.action_version     is '提交时的动作版本；动作升版后进行中的提议仍按旧版执行或拒绝';
comment on column ont_rt.pending_action.target_object_type is '目标对象编码，如 workOrder';
comment on column ont_rt.pending_action.target_object_id   is '目标对象主键值，如 WO-2026-0007';
comment on column ont_rt.pending_action.params             is '提交参数 JSON，已通过 param_schema 校验';
comment on column ont_rt.pending_action.status             is '状态：PROPOSED 待审批 / APPROVED 已批准 / REJECTED 已驳回 / EXECUTED 已执行 / FAILED 执行失败 / COMPENSATED 已补偿 / EXPIRED 已过期';
comment on column ont_rt.pending_action.proposed_by        is '提议者；Agent 提议时写 Agent 注册 id，不写模型名';
comment on column ont_rt.pending_action.proposed_at        is '提议时间';
comment on column ont_rt.pending_action.approved_by        is '审批人；无需审批时为空';
comment on column ont_rt.pending_action.approved_at        is '审批时间';
comment on column ont_rt.pending_action.executed_at        is '实际执行时间';
comment on column ont_rt.pending_action.result             is '执行结果 JSON，如新建对象的 id、外部系统返回码';
comment on column ont_rt.pending_action.error              is '失败原因；FAILED 状态下必填';
comment on column ont_rt.pending_action.expires_at         is '过期时间；到点未执行自动转 EXPIRED，避免陈旧提议被误执行';


-- ---------------------------------------------------------------------
-- 13. 审计
-- ---------------------------------------------------------------------
create table ont_rt.audit_log (
    id                 bigserial    primary key,
    trace_id           uuid         not null,
    actor_id           varchar(128) not null,
    actor_type         varchar(16)  not null,
    session_id         varchar(128),
    operation          varchar(16)  not null,
    object_type_code   varchar(64),
    object_id          varchar(128),
    function_code      varchar(64),
    action_code        varchar(64),
    definition_version int,
    params             jsonb,
    result_summary     jsonb,
    objects_touched    jsonb,
    latency_ms         int,
    ts                 timestamptz  not null default now(),
    constraint ck_audit_actor check (actor_type in ('USER', 'AGENT', 'SERVICE')),
    constraint ck_audit_op    check (operation in ('QUERY', 'GET', 'INVOKE', 'PROPOSE', 'EXECUTE'))
);

create index ix_audit_trace  on ont_rt.audit_log (trace_id);
create index ix_audit_actor  on ont_rt.audit_log (actor_id, ts desc);
create index ix_audit_object on ont_rt.audit_log (object_type_code, object_id, ts desc);

comment on table ont_rt.audit_log is
    'V1 单表。日增超过 500 万行再按月分区（pg_partman），不改应用代码。'
    'definition_version 记录当时用的函数/动作版本，事后可按版本复盘';

comment on column ont_rt.audit_log.id                 is '自增主键，仅用于分页与归档定位，业务上无意义';
comment on column ont_rt.audit_log.trace_id           is '全链路追踪 id；一次请求内的查询、函数、动作共用同一个值';
comment on column ont_rt.audit_log.actor_id           is '操作者标识：用户工号 / Agent 注册 id / 服务名';
comment on column ont_rt.audit_log.actor_type         is '操作者类型：USER / AGENT / SERVICE';
comment on column ont_rt.audit_log.session_id         is '会话 id；Agent 多轮对话按它归并，便于还原上下文';
comment on column ont_rt.audit_log.operation          is '操作类型：QUERY 列表查询 / GET 单对象 / INVOKE 调函数 / PROPOSE 提议动作 / EXECUTE 执行动作';
comment on column ont_rt.audit_log.object_type_code   is '涉及对象类型编码；纯函数调用时可为空';
comment on column ont_rt.audit_log.object_id          is '涉及对象主键值；列表查询时为空，明细记录在 objects_touched';
comment on column ont_rt.audit_log.function_code      is '调用的函数编码；非函数调用为空';
comment on column ont_rt.audit_log.action_code        is '调用的动作编码；非动作调用为空';
comment on column ont_rt.audit_log.definition_version is '当时使用的函数或动作版本；用于按版本复盘口径变化';
comment on column ont_rt.audit_log.params             is '入参 JSON，PII 字段已脱敏';
comment on column ont_rt.audit_log.result_summary     is '结果摘要 JSON，如命中条数、返回状态；不存全量结果';
comment on column ont_rt.audit_log.objects_touched    is '本次操作触碰的对象 id 列表，如 ["equipment:E-1001","workOrder:WO-7"]';
comment on column ont_rt.audit_log.latency_ms         is '服务端耗时，单位毫秒';
comment on column ont_rt.audit_log.ts                 is '发生时间；分区与归档按它切分';


-- ---------------------------------------------------------------------
-- 14. 对象缓存（仅 CACHE/CDC 模式使用）
-- ---------------------------------------------------------------------
create table ont_rt.object_cache (
    object_type_code  varchar(64)  not null,
    object_id         varchar(128) not null,
    payload           jsonb        not null,
    source_updated_at timestamptz,
    cached_at         timestamptz  not null default now(),
    primary key (object_type_code, object_id)
);

comment on table ont_rt.object_cache is
    'VIRTUAL 模式下这张表是空的。只有跨源遍历性能不够时才物化，且必须记录 source_updated_at';

comment on column ont_rt.object_cache.object_type_code  is '对象类型编码，与 object_type.code 对应';
comment on column ont_rt.object_cache.object_id         is '对象主键值';
comment on column ont_rt.object_cache.payload           is '对象快照 JSON，字段与属性定义一致';
comment on column ont_rt.object_cache.source_updated_at is '源数据最后更新时间；用于判断缓存是否落后于源';
comment on column ont_rt.object_cache.cached_at         is '缓存写入时间；与 source_updated_at 一起计算新鲜度';


-- ---------------------------------------------------------------------
-- 15. 评测用例
-- ---------------------------------------------------------------------
create table ont_eval.eval_case (
    id         uuid        primary key default gen_random_uuid(),
    domain_id  uuid        not null references ont_meta.domain (id),
    code       varchar(64) not null,
    kind       varchar(16) not null,
    question   text        not null,
    expect     jsonb       not null,
    owner      varchar(64),
    active     boolean     not null default true,
    constraint uq_eval_code unique (domain_id, code),
    constraint ck_eval_kind check (kind in ('GOLD', 'ADVERSARIAL'))
);

comment on table ont_eval.eval_case is
    '评测用例：金标准与对抗用例。发布前必须全绿，否则不允许转为 PUBLISHED';

comment on column ont_eval.eval_case.id         is '主键，UUID';
comment on column ont_eval.eval_case.domain_id  is '所属业务域';
comment on column ont_eval.eval_case.code       is '用例编码，域内唯一，如 gold-001、adv-permission-03';
comment on column ont_eval.eval_case.kind       is '类型：GOLD 金标准（答对）/ ADVERSARIAL 对抗（必须拒答、澄清或受限）';
comment on column ont_eval.eval_case.question   is '用户原始问法；对抗用例要保留歧义与越权措辞';
comment on column ont_eval.eval_case.expect     is '期望 JSON：{"objects":[...], "action":"...", "mustRefuse":false, "mustAskClarify":false}';
comment on column ont_eval.eval_case.owner      is '用例责任人，通常是提出该业务问题的人';
comment on column ont_eval.eval_case.active     is '是否启用；下线用例保留记录但不再参与回归';


-- ---------------------------------------------------------------------
-- 16. updated_at 触发器（逻辑对象与域统一维护）
-- ---------------------------------------------------------------------
create or replace function ont_meta.touch_updated_at()
returns trigger
language plpgsql
as $$
begin
    new.updated_at = now();
    return new;
end;
$$;

comment on function ont_meta.touch_updated_at() is
    '通用 updated_at 触发器函数：更新前把 updated_at 置为 now()';

create trigger trg_domain_touch
    before update on ont_meta.domain
    for each row execute function ont_meta.touch_updated_at();

create trigger trg_object_type_touch
    before update on ont_meta.object_type
    for each row execute function ont_meta.touch_updated_at();
