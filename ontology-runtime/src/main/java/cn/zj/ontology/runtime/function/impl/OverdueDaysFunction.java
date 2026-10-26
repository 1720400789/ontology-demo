package cn.zj.ontology.runtime.function.impl;

import cn.zj.ontology.runtime.function.FunctionContext;
import cn.zj.ontology.runtime.function.OntologyFunction;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 保养超期天数：取该设备所有有效计划中最早的到期日，与今天比较。
 * 正数表示已超期天数，负数表示还有几天到期。
 */
@Component
public class OverdueDaysFunction implements OntologyFunction {

    @Override
    public String implKey() {
        return "maintOverdueDays";
    }

    @Override
    public Map<String, Object> apply(FunctionContext context, Map<String, Object> args) {
        String equipmentId = String.valueOf(args.get("equipmentId"));

        List<Map<String, Object>> plans = context.query("maintenancePlan",
                Map.of("equipmentId", equipmentId, "active", "true"));

        LocalDate earliest = plans.stream()
                .map(plan -> plan.get("nextDueDate"))
                .filter(Objects::nonNull)
                .map(String::valueOf)
                .map(LocalDate::parse)
                .min(Comparator.naturalOrder())
                .orElse(null);

        if (earliest == null) {
            return Map.of("days", 0, "hasPlan", false);
        }

        long days = ChronoUnit.DAYS.between(earliest, LocalDate.now());
        return Map.of(
                "days", days,
                "hasPlan", true,
                "nextDueDate", earliest.toString(),
                "overdue", days > 0);
    }
}
