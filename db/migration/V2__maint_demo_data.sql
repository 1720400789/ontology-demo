-- =====================================================================
-- V2__maint_demo_data.sql
-- 仅用于演示：模拟"源系统"的业务库 maint。
--
-- 注意：真实项目里这些表属于业务系统，不由本体平台管理。
-- 这里放进 Flyway 只是为了让 demo 一条命令起得来，上生产前应移除本文件。
-- =====================================================================

create schema if not exists maint;

-- ---------------------------------------------------------------------
-- 站点
-- ---------------------------------------------------------------------
create table maint.site (
    id         varchar(32)  primary key,
    code       varchar(32)  not null unique,
    name       varchar(128) not null,
    region     varchar(16)  not null,
    site_type  varchar(16)  not null,
    address    varchar(256),
    opened_at  date
);

comment on table maint.site is '演示用源系统表：站点';

insert into maint.site (id, code, name, region, site_type, address, opened_at) values
    ('S-001', 'PS-003', '三号泵站',   '华东', '泵站',   '杭州市余杭区文一西路 18 号', '2019-06-01'),
    ('S-002', 'WP-001', '城南水厂',   '华东', '水厂',   '杭州市滨江区江南大道 500 号', '2017-03-15'),
    ('S-003', 'SS-002', '北岭变电站', '华北', '变电站', '北京市昌平区北岭路 7 号',     '2020-09-10'),
    ('S-004', 'WS-001', '一号车间',   '华南', '车间',   '深圳市宝安区福海街道 3 号',   '2021-11-20');

-- ---------------------------------------------------------------------
-- 设备（列名与本体属性故意不一致：category→eq_category，status→eq_status）
-- ---------------------------------------------------------------------
create table maint.equipment (
    id             varchar(32)  primary key,
    code           varchar(32)  not null unique,
    name           varchar(128) not null,
    eq_category    varchar(16)  not null,
    model          varchar(64),
    install_date   date,
    criticality    varchar(2)   not null,
    eq_status      varchar(16)  not null,
    site_id        varchar(32)  references maint.site (id),
    rated_power_kw numeric(10, 2),
    deleted_at     timestamptz
);

comment on table maint.equipment is '演示用源系统表：设备';

insert into maint.equipment (id, code, name, eq_category, model, install_date, criticality, eq_status, site_id, rated_power_kw) values
    ('E-1001', 'EQ-1001', '1# 主泵',       '泵',     'KSB-200',  '2019-07-01', 'A', '运行',   'S-001', 110.00),
    ('E-1002', 'EQ-1002', '2# 主泵',       '泵',     'KSB-200',  '2019-07-01', 'A', '维修中', 'S-001', 110.00),
    ('E-1003', 'EQ-1003', '出水阀',        '阀',     'DN800',    '2019-07-01', 'B', '运行',   'S-001',   5.50),
    ('E-1004', 'EQ-1004', '加药泵',        '泵',     'Grundfos', '2018-05-20', 'B', '运行',   'S-002',   7.50),
    ('E-1005', 'EQ-1005', '空压机',        '压缩机', 'Atlas-75', '2017-10-11', 'A', '停机',   'S-002',  45.00),
    ('E-1006', 'EQ-1006', '主变冷却风机',  '电机',   'Y2-280',   '2020-10-01', 'B', '运行',   'S-003',  30.00),
    ('E-1007', 'EQ-1007', '循环变频器',    '变频器', 'ABB-ACS',  '2020-10-01', 'A', '运行',   'S-003',  75.00),
    ('E-1008', 'EQ-1008', '传送带电机',    '电机',   'Y2-132',   '2021-12-01', 'C', '报废',   'S-004',  11.00);

-- ---------------------------------------------------------------------
-- 维保计划
-- ---------------------------------------------------------------------
create table maint.maintenance_plan (
    id               varchar(32) primary key,
    equipment_id     varchar(32) not null references maint.equipment (id),
    plan_type        varchar(16) not null,
    interval_days    int         not null,
    last_executed_at date,
    next_due_date    date        not null,
    standard_ref     varchar(64),
    active           boolean     not null default true
);

comment on table maint.maintenance_plan is '演示用源系统表：维保计划';

insert into maint.maintenance_plan (id, equipment_id, plan_type, interval_days, last_executed_at, next_due_date, standard_ref, active) values
    ('P-2001', 'E-1001', '保养', 90,  '2026-07-15', '2026-10-13', 'SOP-PUMP-01', true),
    ('P-2002', 'E-1001', '巡检', 30,  '2026-10-01', '2026-10-31', 'SOP-CHK-02',  true),
    ('P-2003', 'E-1002', '大修', 365, '2025-11-01', '2026-11-01', 'SOP-OVH-01',  true),
    ('P-2004', 'E-1003', '巡检', 30,  '2026-10-05', '2026-11-04', 'SOP-CHK-02',  true),
    ('P-2005', 'E-1004', '保养', 60,  '2026-09-01', '2026-10-31', 'SOP-PUMP-01', true),
    ('P-2006', 'E-1005', '保养', 90,  '2026-06-20', '2026-09-18', 'SOP-AIR-01',  true),
    ('P-2007', 'E-1006', '巡检', 30,  '2026-10-10', '2026-11-09', 'SOP-CHK-02',  true),
    ('P-2008', 'E-1007', '保养', 180, '2026-05-01', '2026-10-28', 'SOP-VFD-01',  true);

-- ---------------------------------------------------------------------
-- 技师
-- ---------------------------------------------------------------------
create table maint.technician (
    id         varchar(32)  primary key,
    name       varchar(64)  not null,
    level      varchar(16)  not null,
    skill_tags jsonb        not null default '[]'::jsonb,
    site_id    varchar(32)  references maint.site (id),
    status     varchar(16)  not null,
    phone      varchar(32)
);

comment on table maint.technician is '演示用源系统表：技师';

insert into maint.technician (id, name, level, skill_tags, site_id, status, phone) values
    ('T-001', '张伟', '高级', '["泵", "密封件"]',        'S-001', '空闲', '13800000001'),
    ('T-002', '李强', '中级', '["阀", "电气"]',          'S-001', '在工', '13800000002'),
    ('T-003', '王敏', '专家', '["压缩机", "变频器"]',    'S-002', '空闲', '13800000003'),
    ('T-004', '赵磊', '初级', '["电机"]',                'S-003', '休假', '13800000004');

-- ---------------------------------------------------------------------
-- 备件
-- ---------------------------------------------------------------------
create table maint.spare_part (
    id           varchar(32)  primary key,
    code         varchar(32)  not null unique,
    name         varchar(128) not null,
    category     varchar(16)  not null,
    stock_qty    int          not null,
    safety_stock int          not null,
    unit         varchar(16),
    warehouse    varchar(64),
    unit_price   numeric(12, 2)
);

comment on table maint.spare_part is '演示用源系统表：备件';

insert into maint.spare_part (id, code, name, category, stock_qty, safety_stock, unit, warehouse, unit_price) values
    ('SP-001', 'SP-0001', '机械密封',    '密封件', 12, 5,  '套', '杭州仓', 380.00),
    ('SP-002', 'SP-0002', '轴承 6208',   '轴承',   3,  6,  '个', '杭州仓', 120.00),
    ('SP-003', 'SP-0003', '滤芯',        '滤芯',   20, 10, '个', '杭州仓',  85.00),
    ('SP-004', 'SP-0004', '接触器',      '电气',   4,  2,  '个', '北京仓', 260.00),
    ('SP-005', 'SP-0005', '润滑油',      '润滑',   30, 15, '桶', '杭州仓', 450.00),
    ('SP-006', 'SP-0006', '密封圈',      '密封件', 2,  8,  '个', '杭州仓',  25.00),
    ('SP-007', 'SP-0007', '变频器风扇',  '电气',   6,  3,  '个', '北京仓', 190.00),
    ('SP-008', 'SP-0008', '高压软管',    '密封件', 9,  4,  '根', '深圳仓', 140.00);

-- ---------------------------------------------------------------------
-- 工单
-- ---------------------------------------------------------------------
create table maint.work_order (
    id           varchar(32)  primary key,
    code         varchar(32)  not null unique,
    equipment_id varchar(32)  not null references maint.equipment (id),
    plan_id      varchar(32)  references maint.maintenance_plan (id),
    type         varchar(16)  not null,
    priority     varchar(4)   not null,
    status       varchar(16)  not null,
    symptom      text,
    resolution   text,
    reported_at  timestamptz  not null,
    scheduled_at timestamptz,
    sla_due_at   timestamptz,
    closed_at    timestamptz,
    assignee_id  varchar(32)  references maint.technician (id)
);

comment on table maint.work_order is '演示用源系统表：工单';

insert into maint.work_order (id, code, equipment_id, plan_id, type, priority, status, symptom, resolution, reported_at, scheduled_at, sla_due_at, closed_at, assignee_id) values
    ('WO-1001', 'WO-2026-0001', 'E-1001', 'P-2001', '预防', 'P2', '待派',   '按计划保养',        null,               '2026-10-14 08:00+08', null,                  '2026-10-16 18:00+08', null,                  null),
    ('WO-1002', 'WO-2026-0002', 'E-1002', 'P-2003', '故障', 'P1', '处理中', '轴承异响、振动超标', null,              '2026-10-20 09:30+08', '2026-10-20 14:00+08', '2026-10-21 09:30+08', null,                  'T-001'),
    ('WO-1003', 'WO-2026-0003', 'E-1005', 'P-2006', '抢修', 'P1', '已派',   '压力不足，无法启动', null,              '2026-10-22 07:10+08', '2026-10-22 10:00+08', '2026-10-22 13:10+08', null,                  'T-003'),
    ('WO-1004', 'WO-2026-0004', 'E-1003', null,     '故障', 'P3', '待验收', '阀门渗漏',           '更换密封圈',      '2026-10-18 15:00+08', '2026-10-19 09:00+08', '2026-10-21 15:00+08', null,                  'T-002'),
    ('WO-1005', 'WO-2026-0005', 'E-1004', 'P-2005', '预防', 'P3', '已关闭', '按计划保养',         '更换接触器并复位', '2026-10-16 08:00+08', '2026-10-17 09:00+08', '2026-10-19 18:00+08', '2026-10-18 16:20+08', 'T-002'),
    ('WO-1006', 'WO-2026-0006', 'E-1007', null,     '故障', 'P2', '待派',   '变频器过热报警',     null,              '2026-10-23 11:00+08', null,                  '2026-10-24 11:00+08', null,                  null),
    ('WO-1007', 'WO-2026-0007', 'E-1006', 'P-2007', '预防', 'P4', '已派',   '按计划巡检',         null,              '2026-10-21 08:00+08', '2026-10-27 09:00+08', '2026-10-29 18:00+08', null,                  'T-004');

-- ---------------------------------------------------------------------
-- 备件消耗（带属性的关系表）
-- ---------------------------------------------------------------------
create table maint.part_consumption (
    id            varchar(32) primary key,
    work_order_id varchar(32) not null references maint.work_order (id),
    part_id       varchar(32) not null references maint.spare_part (id),
    qty           int         not null,
    issued_at     timestamptz not null,
    issued_by     varchar(32)
);

comment on table maint.part_consumption is '演示用源系统表：备件消耗';

insert into maint.part_consumption (id, work_order_id, part_id, qty, issued_at, issued_by) values
    ('PC-001', 'WO-1002', 'SP-001', 2, '2026-10-20 10:10+08', 'T-001'),
    ('PC-002', 'WO-1002', 'SP-005', 1, '2026-10-20 10:12+08', 'T-001'),
    ('PC-003', 'WO-1003', 'SP-003', 4, '2026-10-22 09:40+08', 'T-003'),
    ('PC-004', 'WO-1005', 'SP-004', 1, '2026-10-17 09:30+08', 'T-002'),
    ('PC-005', 'WO-1005', 'SP-006', 2, '2026-10-17 09:32+08', 'T-002'),
    ('PC-006', 'WO-1003', 'SP-002', 2, '2026-10-22 09:45+08', 'T-003');
