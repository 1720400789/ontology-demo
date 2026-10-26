package cn.zj.ontology.runtime.function.impl;

import cn.zj.ontology.runtime.function.FunctionContext;
import cn.zj.ontology.runtime.function.OntologyFunction;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 备件齐套率：按设备类别映射到备件类别，统计「库存 ≥ 安全库存」的比例。
 *
 * <p>W2 简化：设备类别到备件类别的映射暂时写在代码里。等 W3 有设备-备件 BOM 后再改成数据驱动，
 * 这条已记入 docs/00-plan.md 的债务表。
 */
@Component
public class SpareReadinessFunction implements OntologyFunction {

    private static final Map<String, Set<String>> PART_CATEGORIES = Map.of(
            "泵", Set.of("密封件", "轴承"),
            "阀", Set.of("密封件", "电气"),
            "压缩机", Set.of("滤芯", "润滑"),
            "电机", Set.of("轴承", "电气"),
            "变频器", Set.of("电气", "润滑"));

    @Override
    public String implKey() {
        return "maintSpareReadiness";
    }

    @Override
    public Map<String, Object> apply(FunctionContext context, Map<String, Object> args) {
        String equipmentId = String.valueOf(args.get("equipmentId"));
        Map<String, Object> equipment = context.get("equipment", equipmentId);
        String category = String.valueOf(equipment.get("category"));
        Set<String> partCategories = PART_CATEGORIES.getOrDefault(category, Set.of());

        List<Map<String, Object>> parts = context.query("sparePart", Map.of());
        List<Map<String, Object>> relevant = parts.stream()
                .filter(p -> partCategories.contains(String.valueOf(p.get("category"))))
                .toList();

        List<Map<String, Object>> shortages = new ArrayList<>();
        for (Map<String, Object> part : relevant) {
            int stock = ((Number) part.get("stockQty")).intValue();
            int safety = ((Number) part.get("safetyStock")).intValue();
            if (stock < safety) {
                shortages.add(Map.of(
                        "code", part.get("code"),
                        "name", part.get("name"),
                        "stockQty", stock,
                        "safetyStock", safety,
                        "gap", safety - stock));
            }
        }

        double ratio = relevant.isEmpty()
                ? 1.0
                : (double) (relevant.size() - shortages.size()) / relevant.size();

        return Map.of(
                "readyRatio", Math.round(ratio * 100) / 100.0,
                "totalParts", relevant.size(),
                "shortages", shortages);
    }
}
