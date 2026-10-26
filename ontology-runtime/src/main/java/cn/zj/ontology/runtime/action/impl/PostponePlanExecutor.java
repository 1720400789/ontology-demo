package cn.zj.ontology.runtime.action.impl;

import cn.zj.ontology.runtime.action.ActionExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/** 延期维保计划：只改到期日，原因写进结果里备查。 */
@Component
public class PostponePlanExecutor implements ActionExecutor {

    @Override
    public String actionCode() {
        return "postponePlan";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> target, Map<String, Object> params, JdbcTemplate jdbc) {
        String planId = String.valueOf(target.get("id"));
        String newDueDate = String.valueOf(params.get("newDueDate"));

        int updated = jdbc.update(
                "update maint.maintenance_plan set next_due_date = ?::date where id = ?",
                newDueDate, planId);
        if (updated == 0) {
            throw new IllegalStateException("维保计划不存在: " + planId);
        }
        return Map.of("planId", planId, "newDueDate", newDueDate,
                "reason", String.valueOf(params.get("reason")));
    }
}
