-- =====================================================================
-- V4__audit_operations.sql
-- 审批与驳回也需要留痕：把 APPROVE / REJECT 加入 audit_log.operation 的允许值。
-- =====================================================================

alter table ont_rt.audit_log drop constraint if exists ck_audit_op;

alter table ont_rt.audit_log
    add constraint ck_audit_op check (operation in (
        'QUERY', 'GET', 'INVOKE', 'PROPOSE', 'APPROVE', 'REJECT', 'EXECUTE'));
