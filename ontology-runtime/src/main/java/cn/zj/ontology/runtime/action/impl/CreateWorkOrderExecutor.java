package cn.zj.ontology.runtime.action.impl;

import cn.zj.ontology.runtime.action.ActionExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** 创建工单：往源系统 EAM 的工单表写一条记录。 */
@Component
public class CreateWorkOrderExecutor implements ActionExecutor {

    @Override
    public String actionCode() {
        return "createWorkOrder";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> target, Map<String, Object> params, JdbcTemplate jdbc) {
        String id = "WO-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        String code = "WO-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
                + "-" + (1000 + ThreadLocalRandom.current().nextInt(9000));

        String type = String.valueOf(params.get("type"));
        String priority = String.valueOf(params.get("priority"));
        String symptom = String.valueOf(params.get("symptom"));
        int slaHours = switch (priority) {
            case "P1" -> 4;
            case "P2" -> 8;
            case "P3" -> 24;
            default -> 72;
        };

        jdbc.update("""
                        insert into maint.work_order
                            (id, code, equipment_id, plan_id, type, priority, status, symptom,
                             reported_at, sla_due_at)
                        values (?, ?, ?, null, ?, ?, '待派', ?, now(), now() + (? * interval '1 hour'))
                        """,
                id, code, String.valueOf(target.get("id")), type, priority, symptom, slaHours);

        return Map.of("workOrderId", id, "code", code, "status", "待派");
    }
}
