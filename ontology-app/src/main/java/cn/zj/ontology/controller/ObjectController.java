package cn.zj.ontology.controller;

import cn.zj.ontology.OntologyProperties;
import cn.zj.ontology.runtime.query.ObjectQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 通用对象接口。所有人（含 Agent）走同一套 API，权限与审计只有一份实现。
 */
@RestController
@RequestMapping("/api/objects")
@Tag(name = "对象查询", description = "元模型驱动的通用对象查询，不为每个对象写 Controller")
public class ObjectController {

    private static final Set<String> RESERVED = Set.of("limit", "offset", "fields");

    private final ObjectQueryService queryService;
    private final OntologyProperties properties;

    public ObjectController(ObjectQueryService queryService, OntologyProperties properties) {
        this.queryService = queryService;
        this.properties = properties;
    }

    @GetMapping("/{type}")
    @Operation(summary = "按类型查询对象列表",
            description = "除 limit/offset/fields 外的查询参数按属性等值过滤，例如 ?criticality=A&limit=10")
    public ObjectQueryService.ObjectPage list(
            @PathVariable String type,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset,
            @RequestParam(required = false) String fields,
            @RequestParam Map<String, String> params) {

        Map<String, String> filters = new LinkedHashMap<>(params);
        filters.keySet().removeAll(RESERVED);
        return queryService.list(properties.domain(), type, filters, fields, limit, offset);
    }

    @GetMapping("/{type}/{id}")
    @Operation(summary = "按主键查询单个对象")
    public Map<String, Object> get(@PathVariable String type, @PathVariable String id) {
        return queryService.get(properties.domain(), type, id);
    }
}
