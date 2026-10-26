package cn.zj.ontology.controller;

import cn.zj.ontology.OntologyProperties;
import cn.zj.ontology.runtime.query.AuthorizedObjectService;
import cn.zj.ontology.runtime.query.ObjectQueryService;
import cn.zj.ontology.runtime.query.RelationQueryService;
import cn.zj.ontology.runtime.security.Actor;
import cn.zj.ontology.runtime.security.ForbiddenException;
import cn.zj.ontology.runtime.security.PermissionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
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
 * 调用者身份来自请求头，见 {@link ActorResolver}。
 */
@RestController
@RequestMapping("/api/objects")
@Tag(name = "对象查询", description = "元模型驱动的通用对象查询，按行过滤与字段掩码返回")
public class ObjectController {

    private static final Set<String> RESERVED = Set.of("limit", "offset", "fields");

    private final AuthorizedObjectService objects;
    private final RelationQueryService relations;
    private final PermissionService permissions;
    private final OntologyProperties properties;
    private final ActorResolver actors;

    public ObjectController(AuthorizedObjectService objects,
                            RelationQueryService relations,
                            PermissionService permissions,
                            OntologyProperties properties,
                            ActorResolver actors) {
        this.objects = objects;
        this.relations = relations;
        this.permissions = permissions;
        this.properties = properties;
        this.actors = actors;
    }

    @GetMapping("/{type}")
    @Operation(summary = "按类型查询对象列表",
            description = "除 limit/offset/fields 外的查询参数按属性等值过滤，例如 ?criticality=A&limit=10")
    public ObjectQueryService.ObjectPage list(
            HttpServletRequest request,
            @PathVariable String type,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset,
            @RequestParam(required = false) String fields,
            @RequestParam Map<String, String> params) {

        Map<String, String> filters = new LinkedHashMap<>(params);
        filters.keySet().removeAll(RESERVED);
        Actor actor = actors.resolve(request);
        return objects.list(actor, properties.domain(), type, filters, fields, limit, offset);
    }

    @GetMapping("/{type}/{id}")
    @Operation(summary = "按主键查询单个对象")
    public Map<String, Object> get(HttpServletRequest request,
                                   @PathVariable String type,
                                   @PathVariable String id) {
        Actor actor = actors.resolve(request);
        return objects.get(actor, properties.domain(), type, id);
    }

    @GetMapping("/{type}/{id}/relations/{relation}")
    @Operation(summary = "按关系遍历",
            description = "正反两个方向都支持；W3 只校验起始类型的 READ 权限，行过滤尚未下推到关联对象")
    public RelationQueryService.RelationResult relations(
            HttpServletRequest request,
            @PathVariable String type,
            @PathVariable String id,
            @PathVariable String relation,
            @RequestParam(required = false) Integer limit) {

        Actor actor = actors.resolve(request);
        PermissionService.Decision decision = permissions.check(properties.domain(), actor, type, "READ");
        if (!decision.allowed()) {
            throw new ForbiddenException("无权读取 " + type + "：" + decision.reason());
        }
        return relations.traverse(properties.domain(), type, id, relation, limit);
    }
}
